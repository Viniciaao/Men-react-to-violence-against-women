#!/usr/bin/env python3
"""
Statically check, from the emitted IR2, that no subroutine destroys state its
caller still needs.

Why this exists
---------------
Three separate defects in this mod were the same bug wearing different clothes:
a subroutine wrote to a local that the caller was still using.

  1. ReleaseOneDefender wrote CURSOR (24@), which was also the counter of the
     REPEAT that called it.  gta3sc compiles REPEAT as "increment the variable
     itself", so the loop ended after one pass and ReleaseAllDefenders freed a
     single defender, leaving the rest chasing the player forever.
  2. DebugStatus computed "cooldown seconds left" in NOW (18@) and clamped it to
     zero.  The main loop's wave-cooldown gate read NOW right afterwards, saw 0,
     concluded the cooldown had not elapsed and CONTINUEd every tick - the mod
     loaded, printed its debug line and never scanned for a victim.
  3. the recruit slot-reuse path released the expired occupant through DEFENDER
     (29@), which was holding the witness being recruited, and then re-read the
     slot it had just zeroed.

All three are invisible when reading the source and obvious in the bytecode, so
the check runs on the IR2 that build.sh --verify already produces.

What it does
------------
Two independent checks:

  A. clobber list  - for every subroutine, compute the set of locals it can
                     write (following GOSUB transitively) and diff it against
                     the "// clobbers:" comment in the source.  Undocumented
                     writes are the ones that bite.

  B. live across a call - for every GOSUB site, walk forward through the CFG and
     report any local that the callee can write and that the caller READS before
     writing again.  This is the actual hazard, and it is what catches a REPEAT
     counter being clobbered (the array index counts as a read) and a timestamp
     being consumed after a debug helper flattened it.

Subroutine names are recovered by ordering: gta3sc emits labels in source order,
so the n-th distinct GOSUB target is the n-th subroutine defined in the source.
The first instruction of each is printed so that pairing can be eyeballed.

Exit status is 1 if any check fails, so build.sh can stop on it.
"""
import re
import sys
from collections import OrderedDict

# ---------------------------------------------------------------------------
# Which parameters of an instruction are outputs.
#
# gta3sc's IR2 prints one token per parameter.  Assignment-shaped instructions
# write their FIRST parameter; getters write their LAST; a few write several.
# Anything not listed here is assumed to write nothing - and if it mentions a
# local variable at all, it is reported as UNKNOWN so the table cannot silently
# rot when the script grows.
# ---------------------------------------------------------------------------
WRITES_FIRST = {
    'SET_LVAR_INT', 'SET_LVAR_FLOAT',
    'SET_LVAR_INT_TO_LVAR_INT', 'SET_LVAR_FLOAT_TO_LVAR_FLOAT',
    'SET_LVAR_INT_TO_LVAR_FLOAT', 'SET_LVAR_FLOAT_TO_LVAR_INT',
    'ADD_VAL_TO_INT_LVAR', 'ADD_VAL_TO_FLOAT_LVAR',
    'SUB_INT_LVAR_FROM_INT_LVAR', 'SUB_FLOAT_LVAR_FROM_FLOAT_LVAR',
    'MULT_INT_LVAR_BY_VAL', 'MULT_FLOAT_LVAR_BY_VAL',
    'DIV_INT_LVAR_BY_VAL', 'DIV_FLOAT_LVAR_BY_VAL',
}
# Read-modify-write: these write their first parameter *and* consume its old
# value.  Classifying them as plain writes is how a checker goes quietly blind -
# "NOW = NOW - WAVE_TIME" is exactly the read that the 2011-style cooldown gate
# depends on, and it was missed on the first run of this tool.
RMW_FIRST = {
    'ADD_VAL_TO_INT_LVAR', 'ADD_VAL_TO_FLOAT_LVAR',
    'SUB_VAL_FROM_INT_LVAR', 'SUB_VAL_FROM_FLOAT_LVAR',
    'SUB_INT_LVAR_FROM_INT_LVAR', 'SUB_FLOAT_LVAR_FROM_FLOAT_LVAR',
    'ADD_INT_LVAR_TO_INT_LVAR', 'ADD_FLOAT_LVAR_TO_FLOAT_LVAR',
    'MULT_INT_LVAR_BY_VAL', 'MULT_FLOAT_LVAR_BY_VAL',
    'MULT_INT_LVAR_BY_INT_LVAR', 'MULT_FLOAT_LVAR_BY_FLOAT_LVAR',
    'DIV_INT_LVAR_BY_VAL', 'DIV_FLOAT_LVAR_BY_VAL',
    'DIV_INT_LVAR_BY_INT_LVAR', 'DIV_FLOAT_LVAR_BY_FLOAT_LVAR',
    'INC_INT_LVAR', 'DEC_INT_LVAR', 'INC_FLOAT_LVAR', 'DEC_FLOAT_LVAR',
}

WRITES_LAST = {
    'GET_GAME_TIMER', 'GET_PLAYER_CHAR', 'GET_PED_TYPE', 'GET_CHAR_STAT_ID',
    'GET_CHAR_FEAR', 'GET_CHAR_KILL_TARGET_CHAR', 'BIT_AND', 'BIT_OR',
    'GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE',
}
# opcode -> number of trailing output parameters
WRITES_TAIL = {
    'GET_CHAR_COORDINATES': 3,
    'GET_OFFSET_FROM_CHAR_IN_WORLD_COORDS': 3,
}
# instructions that touch locals but only ever read them
KNOWN_READERS = {
    'IS_INT_LVAR_EQUAL_TO_NUMBER', 'IS_INT_LVAR_EQUAL_TO_INT_LVAR',
    'IS_INT_LVAR_EQUAL_TO_CONSTANT', 'IS_INT_LVAR_GREATER_THAN_NUMBER',
    'IS_INT_LVAR_GREATER_OR_EQUAL_TO_NUMBER',
    'IS_INT_LVAR_GREATER_OR_EQUAL_TO_CONSTANT',
    'IS_NUMBER_GREATER_THAN_INT_LVAR', 'IS_NUMBER_GREATER_OR_EQUAL_TO_INT_LVAR',
    'IS_CONSTANT_GREATER_OR_EQUAL_TO_INT_LVAR',
    'DOES_CHAR_EXIST', 'IS_CHAR_DEAD', 'IS_CHAR_MALE', 'IS_CHAR_ON_FOOT',
    'IS_CHAR_IN_ANY_CAR', 'IS_CHAR_IN_WATER', 'IS_CHAR_IN_AIR',
    'IS_CHAR_FIGHTING', 'IS_CHAR_SCRIPT_CONTROLLED',
    'IS_ON_MISSION', 'IS_ON_CUTSCENE', 'IS_ON_SCRIPTED_CUTSCENE',
    'TASK_KILL_CHAR_ON_FOOT', 'CLEAR_CHAR_TASKS_IMMEDIATELY',
    'CLEAR_CHAR_LAST_DAMAGE_ENTITY', 'CLEAR_CHAR_LAST_WEAPON_DAMAGE',
    'HAS_CHAR_BEEN_DAMAGED_BY_CHAR', 'HAS_CHAR_BEEN_DAMAGED_BY_WEAPON',
    'WRITE_DEBUG', 'WRITE_DEBUG_WITH_INT', 'WRITE_DEBUG_WITH_FLOAT',
    'SET_CLEO_SHARED_VAR', 'WAIT', 'ANDOR', 'NOP', 'RETURN',
    'TERMINATE_THIS_CUSTOM_SCRIPT', 'IS_PC_VERSION', 'IS_AUSTRALIAN_GAME',
    'IS_PLAYER_PLAYING', 'IS_LINE_OF_SIGHT_CLEAR', 'LOCATE_CHAR_DISTANCE_TO_CHAR',
}
CONTROL = {'GOTO', 'GOTO_IF_FALSE', 'GOSUB'}

LOCAL = re.compile(r'^(\d+)@$')
ARRAY = re.compile(r'^(\d+)@\((\d+)@,')


def parse_params(tokens):
    """Split IR2 parameters into (plain locals, array writes, array index reads)."""
    plain, arrays, indices = [], [], []
    for t in tokens:
        m = ARRAY.match(t)
        if m:
            arrays.append(int(m.group(1)))
            indices.append(int(m.group(2)))
            continue
        m = LOCAL.match(t)
        if m:
            plain.append(int(m.group(1)))
    return plain, arrays, indices


class Instr:
    __slots__ = ('op', 'negated', 'params', 'writes', 'reads', 'arrays',
                 'target', 'raw', 'lineno', 'op_unknown')

    def __init__(self, raw, lineno):
        self.raw, self.lineno = raw, lineno
        toks = raw.split()
        i = 0
        self.negated = False
        if toks and toks[0] == 'NOT':
            self.negated, i = True, 1
        self.op = toks[i]
        self.params = toks[i + 1:]
        self.target = None
        self.arrays = []
        self.op_unknown = False
        self.writes, self.reads = [], []

        plain, arrays, indices = parse_params(self.params)
        self.arrays = arrays

        if self.op in CONTROL:
            m = re.match(r'^%MAIN_(\d+)$', self.params[0] if self.params else '')
            if m:
                self.target = 'MAIN_' + m.group(1)
            self.reads = plain + indices
        elif self.op in WRITES_FIRST:
            self.writes = plain[:1]
            self.reads = plain[1:] + indices
            if self.op in RMW_FIRST:
                self.reads = plain + indices
        elif self.op in WRITES_TAIL:
            n = WRITES_TAIL[self.op]
            self.writes = plain[-n:] if len(plain) >= n else plain
            self.reads = plain[:-n] + indices
        elif self.op in WRITES_LAST:
            self.writes = plain[-1:]
            self.reads = plain[:-1] + indices
        else:
            # no outputs; every local mentioned is a read
            self.reads = plain + indices
            if plain and self.op not in KNOWN_READERS:
                self.op_unknown = True


def load_ir(path):
    """Return (blocks, order) where blocks[label] = [Instr] and order is file order."""
    blocks, order, current = OrderedDict(), [], 'MAIN_0'
    blocks[current] = []
    order.append(current)
    with open(path) as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.rstrip('\n')
            if not line.strip():
                continue
            m = re.match(r'^(MAIN_\d+):\s*$', line)
            if m:
                current = m.group(1)
                if current not in blocks:
                    blocks[current] = []
                    order.append(current)
                continue
            if line.startswith('%') or line.startswith('//'):
                continue
            blocks[current].append(Instr(line.strip(), lineno))
    return blocks, order


def successors(label, blocks, order):
    """
    CFG edges out of a block.

    IR2 blocks are not single-exit: a conditional GOTO_IF_FALSE sits in the
    middle of a block and the instructions after it still belong to the same
    block, so every transfer in the body has to be considered, not just the
    last one.  GOSUB is not an edge - a subroutine returns.
    """
    out = []
    for ins in blocks.get(label, []):
        if ins.op == 'GOTO_IF_FALSE':
            if ins.target:
                out.append(ins.target)      # and fall through to the next line
        elif ins.op == 'GOTO':
            if ins.target:
                out.append(ins.target)
            return out                      # unconditional: nothing after runs
        elif ins.op in ('RETURN', 'TERMINATE_THIS_CUSTOM_SCRIPT'):
            return out                      # nothing after runs
    i = order.index(label)
    if i + 1 < len(order):
        out.append(order[i + 1])            # fell off the end of the block
    return out


def subroutine_writes(entry, blocks, order, memo=None):
    """Every local this subroutine can write, following nested GOSUBs."""
    if memo is None:
        memo = {}
    if entry in memo:
        return memo[entry]
    memo[entry] = set()          # guards against recursion while walking
    seen, stack, writes = set(), [entry], set()
    while stack:
        label = stack.pop()
        if label in seen or label not in blocks:
            continue
        seen.add(label)
        for ins in blocks[label]:
            writes.update(ins.writes)
            if ins.op == 'GOSUB' and ins.target:
                writes.update(subroutine_writes(ins.target, blocks, order, memo))
        for nxt in successors(label, blocks, order):
            stack.append(nxt)
    memo[entry] = writes
    return writes


def live_across_call(site_label, site_index, callee_writes, blocks, order,
                     exempt=(), writes_map=None, budget=4000):
    """
    Walk forward from a GOSUB and report locals in callee_writes that some
    execution path READS before writing again.  Array indices count as reads -
    that is how a clobbered REPEAT counter shows up - while an array store does
    not resolve a scalar.

    The pending set is carried per path (DFS, immutable), never shared: a shared
    set makes one path's write silently excuse another path's read.
    """
    pending0 = frozenset(v for v in callee_writes if v not in exempt)
    if not pending0:
        return {}
    offenders = {}
    stack = [(site_label, site_index + 1, pending0)]
    visited = set()
    steps = 0
    while stack and steps < budget:
        label, start, pending = stack.pop()
        if not pending or label not in blocks:
            continue
        key = (label, start, pending)
        if key in visited:
            continue
        visited.add(key)
        steps += 1
        body = blocks[label]
        idx = start
        edges = []
        stopped = False
        while idx < len(body) and pending:
            ins = body[idx]
            for v in ins.reads:
                if v in pending and v not in offenders:
                    offenders[v] = ins
            pending = pending - frozenset(ins.writes)
            if ins.op == 'GOSUB' and ins.target:
                # A call on the way also overwrites the local: without this the
                # walk reports DBG_COUNT as live into the very DebugGate that
                # sets it, and drowns the real hazards in false positives.
                nested = (writes_map or {}).get(ins.target)
                if nested is None:
                    nested = subroutine_writes(ins.target, blocks, order)
                pending = pending - frozenset(nested)
            if ins.op == 'GOTO_IF_FALSE' and ins.target:
                edges.append(ins.target)        # plus fall through
            elif ins.op == 'GOTO':
                if ins.target:
                    edges.append(ins.target)
                stopped = True
                break
            elif ins.op in ('RETURN', 'TERMINATE_THIS_CUSTOM_SCRIPT'):
                stopped = True
                break
            idx += 1
        if not pending:
            continue
        if not stopped and idx >= len(body):
            i = order.index(label)
            if i + 1 < len(order):
                edges.append(order[i + 1])
        for nxt in edges:
            stack.append((nxt, 0, pending))
    return offenders


# ---------------------------------------------------------------------------
# Source side: subroutine names in definition order, and their clobber lists
# ---------------------------------------------------------------------------
LABEL = re.compile(r'^([A-Za-z_][A-Za-z_0-9]*):\s*$')
# IF GOSUB IsCoward is a call too - the predicate form the RETURN_TRUE aliases
# make possible - and missing it shifts every name/label pairing by one.
GOSUB_NAME = re.compile(r'^\s*(?:IF\s+|WHILE\s+|AND\s+|OR\s+)?GOSUB\s+([A-Za-z_]\w*)')


def _locals_of(text):
    """
    Locals named on a documentation line, in every shape the comments use:
    "CURSOR (24@)", "NOW (18@, refreshed)", "PX PY PZ (20@ 21@ 22@)" and the
    range shorthand "20@..22@".  Ranges are expanded, then every bare N@ is
    taken.  Pollution from prose is kept out structurally instead: a section
    ends at the first blank "//" line, and every clobber list in the source is
    followed by one.
    """
    out = set()
    for a, b in re.findall(r'(\d+)@\s*\.\.\s*(\d+)@', text):
        out |= set(range(int(a), int(b) + 1))
    for n in re.findall(r'(\d+)@', text):
        out.add(int(n))
    return out


def load_source(path):
    """
    Return (subroutine names in definition order,
            {name: documented clobber locals},
            {name: documented result locals}).

    A label counts as a subroutine only if the source actually GOSUBs it: the
    loop labels (SCAN_NEXT_CANDIDATE, RECRUIT_NEXT_WITNESS, ...) are jump
    targets, not callees, and including them shifts the whole pairing.

    The documented sets come from the comment block directly above the label -
    the "// clobbers:" line plus its indented continuations, and likewise for
    "// result:".  Reading the block above the label rather than tracking state
    line by line keeps a prose line such as "It must NOT touch NOW (18@)" out of
    the clobber set.
    """
    lines = open(path).read().splitlines()
    called = {m.group(1) for m in (GOSUB_NAME.match(l) for l in lines) if m}

    names, documented, results = [], {}, {}
    for i, line in enumerate(lines):
        m = LABEL.match(line)
        if not m or m.group(1) not in called:
            continue
        name = m.group(1)
        # walk up over the contiguous comment block
        j = i - 1
        block = []
        while j >= 0 and lines[j].startswith('//'):
            block.append(lines[j])
            j -= 1
        block.reverse()
        # keep only the "clobbers:" / "result:" line and its indented continuations
        doc_clob, doc_res, cur = set(), set(), None
        for bl in block:
            low = bl.lower()
            if 'clobbers' in low:
                cur = doc_clob
            elif re.match(r'^//\s*result\s*:', low):
                cur = doc_res
            elif re.match(r'^//\s\s+\S', bl):
                pass                      # continuation of the current section
            else:
                cur = None                # prose, blank "//", or a new heading
            if cur is not None:
                cur |= _locals_of(bl)
        names.append(name)
        documented[name] = doc_clob
        results[name] = doc_res
    return names, documented, results


def main():
    if len(sys.argv) < 3:
        print('usage: check-clobbers.py <ir2.txt> <source.sc>', file=sys.stderr)
        return 2
    blocks, order = load_ir(sys.argv[1])
    names, documented, results = load_source(sys.argv[2])

    gosub_targets = []
    for label in order:
        for ins in blocks[label]:
            if ins.op == 'GOSUB' and ins.target and ins.target not in gosub_targets:
                gosub_targets.append(ins.target)
    entries = sorted(gosub_targets, key=lambda t: int(t.split('_')[1]))

    failures = 0
    print('>> clobber audit (from the emitted IR2)')
    if len(entries) != len(names):
        print(f'   !! {len(entries)} subroutines in the IR but {len(names)} in the '
              f'source - the name pairing below is unreliable')
        failures += 1

    unknown = set()
    for label in order:
        for ins in blocks[label]:
            if getattr(ins, 'op_unknown', False):
                unknown.add(ins.op)
    if unknown:
        print('   !! opcodes that mention locals but are not in the write table: '
              + ', '.join(sorted(unknown)))
        print('      (assumed read-only - add them to tools/check-clobbers.py)')
        failures += 1

    write_sets = {}
    for entry, name in zip(entries, names):
        write_sets[entry] = subroutine_writes(entry, blocks, order)
        first = next((i.raw for i in blocks[entry]), '?')
        doc = documented.get(name, set())
        actual = write_sets[entry]
        missing = actual - doc
        stale = doc - actual
        status = 'ok'
        if missing:
            status = 'UNDOCUMENTED WRITE'
            failures += 1
        elif stale:
            status = 'over-documented'
        shown = ' '.join(f'{v}@' for v in sorted(actual)) or '-'
        print(f'   {name:<24} {entry:<9} writes: {shown:<28} {status}')
        if missing:
            print(f'      {"":<24} the "// clobbers:" comment does not mention '
                  + ', '.join(f'{v}@' for v in sorted(missing)))
        if stale and not missing:
            print(f'      {"":<24} documented but never written: '
                  + ', '.join(f'{v}@' for v in sorted(stale)))
        print(f'      {"":<24} first instruction: {first}')

    # check B: is anything read across a call before being written again?
    print('>> live-across-call audit')
    hazards = 0
    for label in order:
        for idx, ins in enumerate(blocks[label]):
            if ins.op != 'GOSUB' or not ins.target:
                continue
            cw = write_sets.get(ins.target)
            if cw is None:
                cw = subroutine_writes(ins.target, blocks, order)
            owner = next((n for e, n in zip(entries, names) if e == ins.target),
                         ins.target)
            # a subroutine's documented result is *meant* to be read by the
            # caller straight after the call - that is the whole point of it
            bad = live_across_call(label, idx, cw, blocks, order,
                                   exempt=results.get(owner, ()),
                                   writes_map=write_sets)
            for var, at in sorted(bad.items()):
                hazards += 1
                failures += 1
                print(f'   !! line {ins.lineno}: GOSUB {owner} writes {var}@, and '
                      f'it is read before being written again')
                print(f'      at line {at.lineno}: {at.raw}')
    if not hazards:
        print('   no local is read across a call that can overwrite it')
    print(f'>> {"FAIL" if failures else "clean"}: {failures} problem(s)')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())

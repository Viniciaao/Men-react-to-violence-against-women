#!/usr/bin/env python3
"""
Static audit of the debug text in a GTA3script source file.

    ./tools/check-debug-text.py src/MOBBNOBRAVEZA.sc

ScrDebug (https://www.mixmods.com.br/2017/06/sa-scrdebug/) is what gives
0662 WRITE_DEBUG, 0663 WRITE_DEBUG_WITH_INT and 0664 WRITE_DEBUG_WITH_FLOAT
their behaviour - in the retail game all three are no-ops.  Its own
documentation fixes two limits this script is written around:

  * the string parameter is **40 characters**;
  * the messages are kept as a **rolling list of the last twelve** down the
    side of the screen (INI: ShowRecentMessages, MaxRecentMessages), not as one
    line that the next call replaces.

So two things are checked here:

  A. no debug literal is longer than the 40 characters ScrDebug accepts;
  B. no debug line can be reached without the OPT_DEBUG_TEXT gate - either by
     sitting inside DebugStatus, whose first statement is that gate, or by
     being written at a call site whose enclosing block is the
     "IF DBG_COUNT = 1" that follows "GOSUB DebugGate".  A user who turns the
     option off, or who runs ScrDebug for an unrelated reason, must not get this
     mod's output.

B is an indentation check, not a control-flow one: it identifies the statement
that opens the block the call sits in.  tools/check-clobbers.py is the audit
that walks the CFG.

Exit status is 1 if anything fails, so build.sh can stop on it.
"""
import re
import sys

MAX_LEN = 40          # ScrDebug's string parameter

CALL = re.compile(r'^\s*(WRITE_DEBUG|WRITE_DEBUG_WITH_INT|WRITE_DEBUG_WITH_FLOAT)'
                  r'\s+"([^"]*)"')
LABEL = re.compile(r'^([A-Za-z_][A-Za-z_0-9]*):\s*$')
GATE = re.compile(r'^\s*IF\s+DBG_COUNT\s*=\s*1\s*$')
DEBUG_SUB = 'DebugStatus'
OPTION_TEST = re.compile(r'BIT_AND\s+OPTIONS\s+OPT_DEBUG_TEXT')


def indent(line):
    return len(line) - len(line.lstrip())


def enclosing_gate(lines, i):
    """
    Is the statement that opens the block containing lines[i] the debug gate?

    Walking up, the first line indented less than the call is the opener of the
    block the call sits in.  A fixed-size window would not do: the recruit line
    has a REPEAT between the gate and the call, and ReleaseAllDefenders really
    does have no gate at all - the two must not be told apart by luck.
    """
    ind = indent(lines[i])
    for j in range(i - 1, -1, -1):
        line = lines[j]
        if not line.strip() or line.lstrip().startswith('//'):
            continue
        if indent(line) >= ind:
            continue                      # still inside the same block
        return bool(GATE.match(line))
    return False


def main():
    if len(sys.argv) < 2:
        print('usage: check-debug-text.py <source.sc>', file=sys.stderr)
        return 2
    path = sys.argv[1]
    lines = open(path).read().splitlines()

    # Where does each line live?  The nearest label above it, at column 0.
    region, regions = None, []
    for line in lines:
        m = LABEL.match(line)
        if m:
            region = m.group(1)
        regions.append(region)

    # Does the debug subroutine actually gate on the option?
    gated_sub = None
    for i, line in enumerate(lines):
        if OPTION_TEST.search(line) and regions[i] == DEBUG_SUB:
            gated_sub = DEBUG_SUB
            break

    failures = 0
    calls = 0
    print(f'>> debug text audit (ScrDebug: {MAX_LEN}-char strings, '
          f'last 12 messages on screen)')
    if gated_sub is None:
        print(f'   !! {DEBUG_SUB} does not test OPT_DEBUG_TEXT - every status '
              f'line it writes would reach the screen with the option off')
        failures += 1

    for i, line in enumerate(lines):
        m = CALL.match(line)
        if not m:
            continue
        op, text = m.group(1), m.group(2)
        if line.lstrip().startswith('//'):
            continue                      # an example in a comment
        calls += 1
        problems = []

        if len(text) > MAX_LEN:
            problems.append(f'{len(text)} chars, limit is {MAX_LEN}')

        if regions[i] == gated_sub:
            pass                          # the subroutine gates itself
        elif enclosing_gate(lines, i):
            pass                          # call site tested DebugGate's answer
        else:
            problems.append('no OPT_DEBUG_TEXT gate: not inside '
                            f'{DEBUG_SUB}, and the block that contains it is '
                            'not opened by "IF DBG_COUNT = 1"')

        where = f'line {i + 1}' + (f' ({regions[i]})' if regions[i] else '')
        if problems:
            failures += 1
            print(f'   !! {where}: {op} "{text}"')
            for p in problems:
                print(f'      {p}')
        else:
            print(f'   ok  {where:<28} {len(text):>2} chars  "{text}"')

    print(f'>> {"FAIL" if failures else "clean"}: '
          f'{calls} debug call(s), {failures} problem(s)')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())

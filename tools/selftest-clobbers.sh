#!/usr/bin/env bash
#
# Regression test for tools/check-clobbers.py itself.
#
# A checker that only ever prints "clean" is worse than no checker, so this
# rebuilds the two bugs it exists for and demands that it rejects both:
#
#   A. DebugStatus writing NOW (18@) while its "// clobbers:" comment does not
#      admit it              -> check A must fire.
#   B. the same write, this time honestly documented
#                             -> check B must fire, because the main loop still
#                                reads NOW afterwards for the wave cooldown.
#      (B is the bug that made the mod load, print its debug line and then never
#       scan for a victim: NOW reached the gate as 0.)
#
# Exit status 0 means the checker caught both.
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/src/MOBBNOBRAVEZA.sc"
CC="$ROOT/tools/gta3sc/build/gta3sc"
CLEOPLUS="$ROOT/config/cleoplus.xml"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

[ -x "$CC" ] || { echo "selftest: compiler missing, run ./build.sh --rebuild-tools" >&2; exit 2; }

mutate() {   # $1 = output .sc, $2 = "documented" or "undocumented"
python3 - "$SRC" "$1" "$2" <<'PY'
import sys
src, out, mode = sys.argv[1:4]
assert mode in ('documented', 'undocumented'), 'bad mode %r' % mode
s = open(src).read()
i = s.index('DebugStatus:')
j = s.index('//' + '-' * 74, i)
body = s[i:j]
# Read the game timer into NOW next to the read into DEFENDER: the same shape as
# the historical bug (a status routine touching the timestamp the wave-cooldown
# gate needs), and every other write of the routine stays intact so that the two
# cases below differ in nothing but the 18@ write.
buggy = body.replace('GET_GAME_TIMER DEFENDER',
                     'GET_GAME_TIMER DEFENDER\nGET_GAME_TIMER NOW', 1)
assert buggy != body, 'DebugStatus no longer reads the timer; update this test'
s = s[:i] + buggy + s[j:]
if mode == 'documented':
    k = s.rindex('// clobbers:', 0, s.index('DebugStatus:'))
    e = s.index('\n', k)
    s = s[:k] + '// clobbers: NOW (18@), ' + s[k + len('// clobbers: '):e] + s[e:]
    # prove the edit landed on DebugStatus' own comment: without this the case
    # degenerates into a copy of the undocumented one and still "passes"
    k = s.rindex('// clobbers:', 0, s.index('DebugStatus:'))
    assert s[k:k + 22] == '// clobbers: NOW (18@)', s[k:k + 60]
open(out, 'w').write(s)
PY
}

run_case() {   # $1 = name, $2 = documented|undocumented
    local name="$1" mode="$2" sc="$TMP/$1.sc" cs="$TMP/$1.cs" ir="$TMP/$1.ir2.txt"
    mutate "$sc" "$mode"
    "$CC" "$sc" --config=gtasa "--add-config=$CLEOPLUS" --guesser --cs \
          -fbreak-continue -fno-entity-tracking -o "$cs" >/dev/null 2>&1 ||
        { echo "   $name: the mutated script did not compile" >&2; return 2; }
    "$CC" decompile "$cs" --config=gtasa "--add-config=$CLEOPLUS" --cs -emit-ir2 \
          -fno-streamed-scripts -fno-switch -fno-arrays -fno-const \
          -fno-skip-cutscene -o "$ir" >/dev/null 2>&1 ||
        { echo "   $name: decompiling the mutated script failed" >&2; return 2; }
    if python3 "$ROOT/tools/check-clobbers.py" "$ir" "$sc" >"$TMP/$1.log" 2>&1; then
        echo "   $name: MISSED - the checker reported the known bug as clean" >&2
        sed 's/^/      /' "$TMP/$1.log" >&2
        return 1
    fi
    echo "   $name: caught  ($(grep -c '!!' "$TMP/$1.log") live-range finding(s))"
    # show which of the two checks fired, so the cases stay distinguishable
    grep -A1 'DebugStatus' "$TMP/$1.log" | head -2 | sed 's/^/      /'
    grep '!!' "$TMP/$1.log" | head -2 | sed 's/^/      /'
    return 0
}

echo ">> self-test: tools/check-clobbers.py must reject both historical bugs"
rc=0
run_case undocumented-write undocumented || rc=1
run_case documented-write   documented   || rc=1
# and the real source must stay clean, or the test above proves nothing
if python3 "$ROOT/tools/check-clobbers.py" "$ROOT/build/MOBBNOBRAVEZA.ir2.txt" "$SRC" \
        >"$TMP/real.log" 2>&1; then
    echo "   real source  : clean"
else
    echo "   real source  : NOT clean - fix src/MOBBNOBRAVEZA.sc first" >&2
    rc=1
fi
[ "$rc" = 0 ] && echo ">> self-test passed" || echo ">> self-test FAILED" >&2
exit "$rc"

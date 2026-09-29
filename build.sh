#!/usr/bin/env bash
#
# Build MOBBNOBRAVEZA.cs from src/MOBBNOBRAVEZA.sc using thelink2012/gta3sc.
#
#   ./build.sh                 compile
#   ./build.sh --verify        compile + disassemble the result (sanity check)
#   ./build.sh --rebuild-tools (re)build the gta3sc compiler itself
#
# The compiler is looked for at, in this order:
#   1. $GTA3SC
#   2. tools/gta3sc/build/gta3sc   (built by tools/build-gta3sc.sh)
#   3. gta3sc                      (anything on $PATH)
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$ROOT/src/MOBBNOBRAVEZA.sc"
OUT="$ROOT/bin/MOBBNOBRAVEZA.cs"
IR="$ROOT/build/MOBBNOBRAVEZA.ir2.txt"
# CLEO+ opcode definitions.  gta3sc's own config/gtasa/cleo.xml stops at 0xB16,
# so every 0Exx opcode this mod uses has to be declared for the compiler here.
# --add-config resolves relative paths against the compiler's config dir, so
# this one is passed as an absolute path.
CLEOPLUS="$ROOT/config/cleoplus.xml"

# Flags explained:
#   --config=gtasa        San Andreas opcode table
#   --add-config=...      append the CLEO+ opcode definitions (see $CLEOPLUS)
#   --guesser             allow the language features the community had to guess
#   --cs                  emit a CLEO .cs (headerless, -fcleo, local offsets)
#   -fbreak-continue      allow BREAK inside REPEAT/WHILE
#   -fno-entity-tracking  see the note in docs/COMPILER.md section 3.11: gta3sc's
#                         entity checker does not propagate a type through array
#                         elements, so storing ped handles in DEFENDER_HANDLE[5]
#                         (which is the whole point of the slot table) makes every
#                         later "expected variable of type CHAR but got NONE".
#                         Compile-time only, zero effect on the emitted bytecode.
FLAGS=(--config=gtasa "--add-config=$CLEOPLUS" --guesser --cs -fbreak-continue
       -fno-entity-tracking)

find_compiler() {
    if [ -n "${GTA3SC:-}" ] && [ -x "$GTA3SC" ]; then echo "$GTA3SC"; return; fi
    if [ -x "$ROOT/tools/gta3sc/build/gta3sc" ]; then echo "$ROOT/tools/gta3sc/build/gta3sc"; return; fi
    if command -v gta3sc >/dev/null 2>&1; then command -v gta3sc; return; fi
    return 1
}

build_tools() {
    if [ ! -f "$CLEOPLUS" ]; then
    echo "error: $CLEOPLUS is missing; the CLEO+ opcodes cannot be resolved" >&2
    exit 1
fi

if [ ! -d "$ROOT/tools/gta3sc/.git" ]; then
        echo ">> cloning thelink2012/gta3sc"
        mkdir -p "$ROOT/tools"
        git clone --depth 1 https://github.com/thelink2012/gta3sc.git "$ROOT/tools/gta3sc"
    fi
    echo ">> building gta3sc (see tools/build-gta3sc.sh)"
    bash "$ROOT/tools/build-gta3sc.sh"
}

if [ "${1:-}" = "--rebuild-tools" ]; then
    build_tools
    exit 0
fi

if ! CC_BIN="$(find_compiler)"; then
    echo ">> gta3sc not found, building it first"
    build_tools
    CC_BIN="$(find_compiler)"
fi

mkdir -p "$ROOT/bin" "$ROOT/build"

echo ">> $CC_BIN ${FLAGS[*]} -o bin/MOBBNOBRAVEZA.cs"
"$CC_BIN" "$SRC" "${FLAGS[@]}" -o "$OUT"
echo ">> built $(basename "$OUT") ($(stat -c%s "$OUT" 2>/dev/null || stat -f%z "$OUT") bytes)"

if [ "${1:-}" = "--verify" ]; then
    echo ">> disassembling the result into build/MOBBNOBRAVEZA.ir2.txt"
    "$CC_BIN" decompile "$OUT" --config=gtasa "--add-config=$CLEOPLUS" --cs -emit-ir2 \
        -fno-streamed-scripts -fno-switch -fno-arrays -fno-const -fno-skip-cutscene \
        -o "$IR"
    echo "   $(wc -l < "$IR") instructions"
    echo "   locals used : $(grep -oE '\b(0|[1-9]|[12][0-9]|3[01])@' "$IR" | sort -u -n -t@ -k1 | tr '\n' ' ')"
    echo "   gosubs      : $(grep -c '^GOSUB' "$IR")"
    echo "   returns     : $(grep -c '^RETURN' "$IR")"
fi

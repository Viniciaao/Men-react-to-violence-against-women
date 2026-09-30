#!/usr/bin/env bash
#
# CMake-free Linux build of thelink2012/gta3sc (the GTA3script compiler).
#
#   tools/build-gta3sc.sh
#
# Expects the sources in tools/gta3sc (cloned from
# https://github.com/thelink2012/gta3sc).  Produces tools/gta3sc/build/gta3sc
# with config/ copied next to it, which is where the compiler looks for its
# opcode tables.
#
# Why not cmake?  Upstream says `cmake .. && make`, but CMake is not always
# available (it was not in the sandbox this was built in, and neither apt nor
# the CMake release downloads were reachable).  gta3sc's CMakeLists.txt is
# short enough to reproduce by hand:
#
#   * C++17; sources = src/*.cpp + deps/cppformat/cppformat/format.cc
#   * include dirs   = src, deps, deps/rapidxml, deps/cppformat,
#                      deps/{optional,expected,any,variant}/include
#   * every third-party dep is vendored under deps/ (no system packages needed)
#   * src/git-sha1.cpp is GENERATED from git-sha1.cpp.in
#   * config/ is copied next to the executable as a post-build step
#
set -uo pipefail

TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$TOOLS/gta3sc"
BUILD="$SRC/build"
OBJ="$BUILD/obj"

if [ ! -f "$SRC/CMakeLists.txt" ]; then
    echo "error: $SRC is missing. Clone it first:" >&2
    echo "  git clone --depth 1 https://github.com/thelink2012/gta3sc.git $SRC" >&2
    exit 1
fi
mkdir -p "$OBJ"

CXX=${CXX:-g++}
CXXFLAGS="-std=c++17 -O2 -DNDEBUG -DGTA3SC_USING_GIT_DESCRIBE -Wno-placement-new -w"
INCLUDES="-I$SRC/src -I$SRC/deps -I$SRC/deps/rapidxml -I$SRC/deps/cppformat \
-I$SRC/deps/optional/include -I$SRC/deps/expected/include \
-I$SRC/deps/any/include -I$SRC/deps/variant/include"

# Relative to $SRC.  build/git-sha1.cpp is generated below.
FILES="deps/cppformat/cppformat/format.cc
src/codegen.cpp
src/commands.cpp
src/compiler.cpp
src/config.cpp
src/disassembler.cpp
src/main.cpp
src/main_compile.cpp
src/main_decompile.cpp
src/parser_lexer.cpp
src/parser_syntax.cpp
src/program.cpp
src/script.cpp
src/stdinc.cpp
src/symtable.cpp
src/system.cpp
build/git-sha1.cpp"

objname() { echo "$1" | tr '/' '_' | sed 's/\.\(cpp\|cc\)$/.o/'; }

# ---------------------------------------------------------------------------
# git-sha1.cpp: CMake generates it from git-sha1.cpp.in.  Forgetting it is a
# *link* error (undefined reference to GTA3SC_GIT_SHA1), not a compile error.
# ---------------------------------------------------------------------------
if [ ! -f "$BUILD/git-sha1.cpp" ] || [ "$SRC/git-sha1.cpp.in" -nt "$BUILD/git-sha1.cpp" ]; then
    sha1="$(git -C "$SRC" rev-parse HEAD 2>/dev/null || echo unknown)"
    branch="$(git -C "$SRC" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
    sed -e "s/@GIT_SHA1@/$sha1/" \
        -e "s/@GIT_BRANCH@/$branch/" \
        -e "s/@GIT_DESCRIBE_TAG@//g" \
        "$SRC/git-sha1.cpp.in" > "$BUILD/git-sha1.cpp"
    echo "GEN   build/git-sha1.cpp"
fi

# ---------------------------------------------------------------------------
# Compile: parallel (nproc), incremental by mtime.
# ---------------------------------------------------------------------------
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 2)}"
pids=""
failed=0

while IFS= read -r f; do
    [ -z "$f" ] && continue
    o="$OBJ/$(objname "$f")"
    if [ -f "$o" ] && [ "$o" -nt "$SRC/$f" ]; then
        continue
    fi
    echo "CXX   $f"
    (
        if $CXX $CXXFLAGS $INCLUDES -c "$SRC/$f" -o "$o" 2>"$o.log"; then
            rm -f "$o.log"
        else
            echo "FAIL  $f" >&2
            cat "$o.log" >&2
            rm -f "$o"
            exit 1
        fi
    ) &
    pids="$pids $!"
    while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do sleep 0.2; done
done <<< "$FILES"

for p in $pids; do
    wait "$p" || failed=1
done
[ "$failed" = 0 ] || { echo "error: compilation failed" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Link + install config/ next to the binary.
# ---------------------------------------------------------------------------
echo "LD    gta3sc"
$CXX $CXXFLAGS "$OBJ"/*.o -o "$BUILD/gta3sc" || exit 1
rm -rf "$BUILD/config"
cp -r "$SRC/config" "$BUILD/config"

echo "OK    $BUILD/gta3sc"
"$BUILD/gta3sc" --version

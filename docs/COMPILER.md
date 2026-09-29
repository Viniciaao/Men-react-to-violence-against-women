# Building the mod with `thelink2012/gta3sc` on Linux

This is a log of what was actually done in this repository, plus the language
gotchas that were discovered the hard way while getting `MOBBNOBRAVEZA.sc` to
compile.

---

## 1. Getting the compiler

```bash
git clone --depth 1 https://github.com/thelink2012/gta3sc.git tools/gta3sc
```

Upstream README says "just follow the standard CMake procedure":

```bash
cd tools/gta3sc && mkdir build && cd build && cmake .. && make
```

**But CMake was not available in this sandbox** (`apt-get` and every host
except `github.com` are blocked, so no `cmake` package and no prebuilt CMake
tarball either). `CMakeLists.txt` is simple enough to reproduce by hand, so
`tools/build-gta3sc.sh` does exactly that with plain `g++`:

```bash
g++ -std=c++17 -O2 -DNDEBUG -DGTA3SC_USING_GIT_DESCRIBE -w \
    -I src -I deps -I deps/rapidxml -I deps/cppformat \
    -I deps/optional/include -I deps/expected/include \
    -I deps/any/include -I deps/variant/include \
    -c <each source file>
g++ -O2 obj/*.o -o build/gta3sc
cp -r config build/config          # the compiler looks for ./config next to it
```

Notes that cost time:

* All third-party deps are vendored in `deps/` (rapidxml, cppformat,
  optional/expected/any/variant, SmallVector) — **no system packages needed**.
  `std::filesystem` is used directly, so GCC 9+ / Clang 10+ is enough; GCC 12.2
  was used here.
* `src/git-sha1.cpp` does not exist: CMake generates it from `git-sha1.cpp.in`
  by substituting `@GIT_SHA1@`, `@GIT_BRANCH@` and `@GIT_DESCRIBE_TAG@`.
  The build script generates it with `sed`. **Forgetting this is a link error**
  (`undefined reference to GTA3SC_GIT_SHA1`), not a compile error.
* `config/` must sit next to the executable, otherwise every compile fails with
  "config not found".
* `-w` is used because the vendored headers produce a wall of warnings on
  modern GCC.

Result:

```
$ tools/gta3sc/build/gta3sc --version
gta3sc master-e9b4c3035c77b013f57af8595bc76b777acf73f6
```

## 2. Compiling the mod

```bash
./build.sh              # -> bin/MOBBNOBRAVEZA.cs
./build.sh --verify     # also disassembles the result into build/*.ir2.txt
```

which is really:

```bash
gta3sc src/MOBBNOBRAVEZA.sc --config=gtasa --guesser --cs -fbreak-continue \
      -o bin/MOBBNOBRAVEZA.cs
```

* `--config=gtasa` reads `config/gtasa/{commands,constants,cleo,...}.xml`. That
  directory also has a `commandline.txt` which silently adds the SA defaults
  (`-farrays -fconst -fswitch -ftimer-index=32 -flocal-var-limit=32
  -mheader=gtasa -mno-q11.4 -mtyped-text-label ...`).
* `--cs` = CLEO custom script: implies `-fcleo`, `-mno-header` (no SCM header,
  which is what makes it a `.cs`) and `-mlocal-offsets`.
* `--guesser` enables the SA language features the community had to guess.
* `-fbreak-continue` allows `BREAK` inside `REPEAT`/`WHILE`. (`BREAK` itself is
  an always-available extension command; the flag widens where it may appear.)

## 3. Language gotchas found while compiling

These are the things that produced actual errors, in the order they appeared.
They are all real gta3sc behaviour, not Sanny Builder behaviour — the two
front-ends are **not** source compatible.

### 3.1 `{$CLEO .cs}` is not understood

gta3sc has no Sanny-style `{$...}` directives. The equivalent is the `--cs`
command-line flag, and the script must be wrapped in `SCRIPT_START` /
`SCRIPT_END`:

```
SCRIPT_START
SCRIPT_NAME MNBRV
{
MAIN:
NOP
...
}
SCRIPT_END
```

Without `SCRIPT_START` the error is `custom script does not contain
SCRIPT_START`.

### 3.2 Global variables are illegal in a `.cs`

```
src/...: error: declaring global variables in custom scripts is illegal
```

`$PLAYER_CHAR`, `$PLAYER_ACTOR` and `$ONMISSION` **do not exist** in gta3sc's
SA config — global variables are only ever declared with `VAR_INT name`, and
that is forbidden in a custom script (and in anything it `REQUIRE`s). Upstream
issue [#104 "No way of accessing important globals from custom
scripts"](https://github.com/thelink2012/gta3sc/issues/104) is still open; the
suggested `DIM_VAR` does not exist in any branch.

Workarounds used here:

| Needed | Replacement |
|---|---|
| `$PLAYER_ACTOR` | `GET_PLAYER_CHAR 0 PLAYER_ACTOR` (opcode `01F5`) into a local, refreshed every tick |
| `$PLAYER_CHAR` | the literal player index `0` — every `Entity="PLAYER"` argument accepts it (`IS_PLAYER_PLAYING 0`) |
| `$ONMISSION` | no equivalent; approximated with `IS_PLAYER_PLAYING` + pedtype filters (documented in `ANALISE.md`) |

A literal `0` works for a `PLAYER` argument, but a `CONST_INT` **does not**
(`variable kind (global/local) not allowed for this argument` style errors), so
the index is written inline.

### 3.3 Command form, not assignment form

Sanny writes `4@ = get_char_coordinates $PLAYER_ACTOR`. gta3sc wants the
command form with the outputs in their XML positions (outputs last):

```
GET_CHAR_COORDINATES PLAYER_ACTOR PX PY PZ
GENERATE_RANDOM_INT_IN_RANGE 0 100 ROLL
GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE PX PY PZ 3.0 CURSOR 1 CANDIDATE
```

`x = SOME_COMMAND ...` fails with `expected newline after this token`. Plain
arithmetic assignment (`ROLL = NOW - WAVE_TIME`) *is* supported.

### 3.4 Constants cannot be compared with each other

```
IF SHOW_HELP_TEXT = 1     ->  error: could not match alternative
```

gta3sc has no `IS_CONSTANT_EQUAL_TO_CONSTANT`. A condition needs at least one
variable. Since a config knob should still be editable in one place, all the
optional features were packed into a single bit field and tested at runtime
with the CLEO opcode `0B10 BIT_AND`:

```
CONST_INT OPTIONS_DEFAULT 27        // 1 + 2 + 8 + 16
LVAR_INT OPTIONS
OPTIONS = OPTIONS_DEFAULT
BIT_AND OPTIONS OPT_SHOW_HELP_TEXT ROLL
IF NOT ROLL = 0
    PRINT_HELP_STRING "..."
ENDIF
```

One extra opcode per check, no extra locals beyond `OPTIONS` and `ROLL`.

### 3.5 Enum members only work where the XML binds an enum

`PEDTYPE_COP`, `WEAPONTYPE_ANYMELEE` and `VK_F10` work because those arguments
carry `Enum="PEDTYPE"` / `Enum="WEAPONTYPE"` / `Enum="WIN32_VK"`.
`DM_PED_RANDOM_TOUGH` does **not** — `TASK_SET_CHAR_DECISION_MAKER`'s second
argument is a bare `INT`, so the enum member is parsed as a variable name:

```
TASK_SET_CHAR_DECISION_MAKER DEFENDER DM_PED_RANDOM_TOUGH
  -> error: no variable with this name
```

Hence `CONST_INT DM_RANDOM_TOUGH 65539` (the `DecisionMakerCharTemplate`
value for *RandomTough*, confirmed against `config/gtasa/constants.xml` and the
Sanny Builder Library `enums.json`).

Likewise `CONST_INT TOGGLE_KEY VK_F10` fails (`CONST_INT` only accepts an
integer literal), so `IS_KEY_PRESSED VK_F10` is written directly.

### 3.6 Names must not collide with built-in constants

```
CONST_INT PEDTYPE_COP 6   ->  error: user constant exists already as a string constant
LVAR_INT  PEDTYPE         ->  same class of problem
```

Model names, zone names, ped types and weapon types are all pre-registered
string constants. Anything the script declares has to avoid those namespaces
(this is why the ped-type local is called `CURSOR` and is reused, rather than
being called `PEDTYPE`).

### 3.7 `IF` blocks are homogeneous

`IF a AND b OR c` is rejected (`expected AND, got OR`). Use nested `IF`s.

### 3.8 Locals start at `0@` — reserve the CLEO argument slots yourself

gta3sc allocates `LVAR_INT` declarations from `0@` upwards, in declaration
order, and does **not** reserve anything. CLEO passes `0A92:
create_custom_thread` arguments in `0@..3@`, so the script declares a dummy
`LVAR_INT CLEO_ARGS[4]` first and the real state starts at `4@`.

`-ftimer-index=32` puts `TIMERA`/`TIMERB` at `32@`/`33@`, i.e. **outside** the
`0@..31@` CLEO window, so they cannot be used as ordinary locals in a `.cs`.
With `-flocal-var-limit=32` there are exactly 32 usable locals, all of which
are accounted for in the `LOCAL VARIABLE MAP` comment at the top of the source.

Sub-scopes (`{ label: LVAR_INT x ... }` reached by `GOSUB`) also allocate from
`0@`, so they would clobber the caller's state. The mod therefore keeps
**one single scope** and uses plain labels + `GOSUB`/`RETURN`, with the scratch
slots documented per subroutine.

### 3.9 The compiler appends the terminator

Writing `TERMINATE_THIS_CUSTOM_SCRIPT` at the end of a `--cs` script produces
it twice in the bytecode. Harmless, but the source leaves it out.

### 3.10 Decompiling the result needs the SA feature flags off

```bash
gta3sc decompile bin/MOBBNOBRAVEZA.cs --config=gtasa --cs -emit-ir2 \
     -fno-streamed-scripts -fno-switch -fno-arrays -fno-const -fno-skip-cutscene \
     -o build/MOBBNOBRAVEZA.ir2.txt
```

* GTA3script output is disabled in this build, so `-emit-ir2` (Sanny-style IR)
  is the only disassembly target.
* `--cs` is required, otherwise: `corrupted scm header`.
* `-fno-streamed-scripts` is required, otherwise: `file 'bin/script.img' does
  not exist`.

`build.sh --verify` runs this and prints the locals/gosub/return counts as a
sanity check.

## 4. Opcode sources used to validate every command

Every opcode, argument count and argument order in `MOBBNOBRAVEZA.sc` was
cross-checked against two independent sources:

1. `tools/gta3sc/config/gtasa/{commands.xml,cleo.xml}` — what the compiler will
   accept (this is the authority for *compilation*).
2. The Sanny Builder Library (`github.com/sannybuilder/library`, `sa/sa.json` +
   `sa/enums.json`) — what the *game* implements, including `num_params`,
   input/output names and the enum values (`CharSearchFilter`, `PedType`,
   `RelationshipType`, `DecisionMakerCharTemplate`, `WeaponType`, ...).

They disagree in at least one place relevant to this mod: `08E5
GET_RANDOM_CHAR_IN_SPHERE_NO_BRAIN` is documented as 6 parameters by Sanny
Builder but declared with 5 in gta3sc's config. That opcode is therefore not
used; the CLEO `0AE1 GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE` is used
instead, and both sources agree on its 6 inputs + 1 output.

`tools/sbl.py` is a small helper that queries the Sanny Builder Library dump:

```bash
python3 tools/sbl.py 0AE1 051A 05E2      # by opcode
python3 tools/sbl.py -s DECISION_MAKER   # by name
```

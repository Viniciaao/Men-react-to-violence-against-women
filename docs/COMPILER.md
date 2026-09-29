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
gta3sc src/MOBBNOBRAVEZA.sc --config=gtasa \
      --add-config=config/cleoplus.xml \
      --guesser --cs -fbreak-continue -fno-entity-tracking \
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
* `--add-config=<path>` appends an extra XML opcode file to the configuration.
  `config/cleoplus.xml` declares the ten CLEO+ opcodes this mod uses — see §5.
  The path has to be **absolute** (or relative to the compiler's own config
  dir): `main.cpp` pushes the value straight into the `config_files` list that
  `Commands::from_xml` resolves against `config_path()`, so a repo-relative
  string only works if the current directory happens to be the config dir.
  `build.sh` therefore expands it to `$ROOT/config/cleoplus.xml`.
* `-fno-entity-tracking` disables the compile-time entity-type checker — see
  §3.14. It changes nothing in the emitted bytecode.

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
{
MAIN:
NOP
...
}
SCRIPT_END
```

Without `SCRIPT_START` the error is `custom script does not contain
SCRIPT_START`.

`SCRIPT_NAME` is deliberately absent. CLEO 4 names a custom script after its
file name, so the opcode is dead weight — and it is the very first thing in the
bytecode when present (`A4 03` + an 8-byte name), which makes it easy to check
that it is really gone: a `.cs` built from this source starts with `00 00`
(`NOP`).

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
| `$ONMISSION` | **`0E1D IS_ON_MISSION` (CLEO+)** — reads the global that `0180` sets, which is exactly what `$ONMISSION` is. With plain CLEO there is no equivalent at all and the best available approximation is `IS_PLAYER_PLAYING` + pedtype filters |

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
IF OPT_MELEE_ONLY = 1     ->  error: could not match alternative
```

gta3sc has no `IS_CONSTANT_EQUAL_TO_CONSTANT`. A condition needs at least one
variable. Since a config knob should still be editable in one place, all the
optional features were packed into a single bit field and tested at runtime
with the CLEO opcode `0B10 BIT_AND`:

```
CONST_INT OPTIONS_DEFAULT 3         // 1 + 2
LVAR_INT OPTIONS
OPTIONS = OPTIONS_DEFAULT
BIT_AND OPTIONS OPT_MELEE_ONLY ROLL
IF NOT ROLL = 0
    ...
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
integer literal), so `IS_KEY_PRESSED VK_F10` was written directly.

That worked only because `0AB0 IS_KEY_PRESSED` binds `Enum="WIN32_VK"`, and the
CLEO+ replacement behaves the same way — but only if the new declaration binds
the enum too. `WIN32_VK` *is* defined in gta3sc's `config/gtasa/cleo.xml` (line
4, `VK_F10` at line 107), yet this fails:

```
<Arg Type="INT" Desc="Virtual-key code"/>          <!-- enum not bound -->
IF IS_KEY_JUST_PRESSED VK_F10   ->  error: no variable with this name
```

```
<Arg Type="INT" Enum="WIN32_VK"/>                  <!-- official declaration -->
IF IS_KEY_JUST_PRESSED VK_F10   ->  emits 121
```

So an enum that exists in the configuration is still only visible **in the
argument that binds it**; everywhere else the name is parsed as a variable. That
is the whole of §3.5, and it is why `config/cleoplus.xml` copies the official
`Enum=` attributes instead of simplifying them to `INT`.

Three more facts about enums that matter when writing an `--add-config` file:

* an `<Arg Enum="Foo"/>` whose enum `Foo` is not defined in the loaded XML makes
  every use of that argument fail to match, so a new command should only bind an
  enum that exists — either in gta3sc's own files or in the added one, which is
  why `config/cleoplus.xml` carries the official `<Enum Name="PEDSTAT">` block;
* `<Enum Global="true">` is different: those names become usable anywhere in the
  script, not only in the binding argument. `PEDSTAT` is declared global by
  CLEO+, which is what lets the script write `IF CURSOR = PEDSTAT_COWARD` after a
  plain `GET_CHAR_STAT_ID`. It is also what makes a home-made
  `CONST_INT PEDSTAT_COWARD 42` fail with `user constant exists already as a
  string constant` — the enum won, and the enum is right;
* `Enum` values are matched **case-insensitively** and only where the XML says
  so — the Sanny Builder Library names an argument type (`KeyCode`, `PedStat`,
  `PedState`) that gta3sc's `xml_to_argtype` does not accept at all
  (`unexpected 'Type' attribute`). Those are plain integers in the bytecode, so
  they are declared `INT` and the script uses its own `CONST_INT` names.

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
gta3sc decompile bin/MOBBNOBRAVEZA.cs --config=gtasa \
     --add-config=config/cleoplus.xml --cs -emit-ir2 \
     -fno-streamed-scripts -fno-switch -fno-arrays -fno-const -fno-skip-cutscene \
     -o build/MOBBNOBRAVEZA.ir2.txt
```

* GTA3script output is disabled in this build, so `-emit-ir2` (Sanny-style IR)
  is the only disassembly target.
* `--cs` is required, otherwise: `corrupted scm header`.
* `-fno-streamed-scripts` is required, otherwise: `file 'bin/script.img' does
  not exist`.
* `--add-config=config/cleoplus.xml` is required here too, or the ten CLEO+
  opcodes come back as bare numbers instead of names.

`build.sh --verify` runs this and prints the locals/gosub/return counts as a
sanity check.

### 3.11 `WHILE TRUE`, `RETURN_TRUE` and `RETURN_FALSE` exist — as aliases

These three names are real GTA3script, and gta3sc compiles them, but **only
after you declare them**. They are not new opcodes: Junior_Djjr's
[`[GTA3script] WHILE TRUE, RETURN_TRUE e RETURN_FALSE`](https://forum.mixmods.com.br/f16-utilidades/t179-gta3script-while-true-return_true-e-return_false)
simply renames two San Andreas opcodes that always answer the same thing on PC,
and which GTA III and Vice City had under those very names:

```xml
<Command ID="0x485" Name="TRUE"/>          <!-- IS_PC_VERSION      -->
<Command ID="0x485" Name="RETURN_TRUE"/>
<Command ID="0x59a" Name="RETURN_FALSE"/>  <!-- IS_AUSTRALIAN_GAME -->
```

Two things make this work and are worth knowing:

* **gta3sc accepts several `<Command>` entries sharing one ID.** They become
  alternators, so `IS_PC_VERSION` keeps working in old scripts and `RETURN_TRUE`
  becomes available next to it. That is what makes the aliases addable from an
  `--add-config` file without touching `commands.xml`.
* **They are conditions, not statements.** `RETURN_TRUE` sets the script's
  compare flag; the `RETURN` after it hands that flag to the caller, which reads
  it with `IF GOSUB`. This is how a boolean subroutine is written:

```
IF GOSUB IsCoward
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
...
IsCoward:
IF <test>
    RETURN_TRUE
ENDIF
RETURN_FALSE
```

and it emits exactly what you would expect — `GOSUB` / `GOTO_IF_FALSE`, then
`IS_PC_VERSION` or `IS_AUSTRALIAN_GAME` before the `RETURN`. The decompiler
prints the *first* registered name for an ID, so `RETURN_TRUE` comes back as
`IS_PC_VERSION` in the IR; the encoding is right either way.

With `IF GOSUB` available, `-fbreak-continue` gives the full modern loop
vocabulary: `WHILE TRUE` / `BREAK` / `CONTINUE` / `ENDWHILE`, replacing the
`label:` + `GOTO label` idiom that the retail scripts (and the 2011 original)
had to use.

### 3.12 `REPEAT` increments the variable you give it

```
REPEAT MAX_DEFENDERS CURSOR
    ...
    GOSUB ReleaseOneDefender      // writes CURSOR
    ...
ENDREPEAT
```

compiles to

```
MAIN_n:   <body>
          ADD_VAL_TO_INT_LVAR 24@ 1
          IS_INT_LVAR_GREATER_OR_EQUAL_TO_NUMBER 24@ 5
          GOTO_IF_FALSE %MAIN_n
```

The counter **is** the variable — there is no hidden loop register. So a
subroutine that writes to it silently shortens the loop: in this mod that meant
"release every defender whose time is up" releasing exactly one and then ending,
because `ReleaseOneDefender` used the same local for the kill target.

The bug is invisible in the source and obvious in the IR, which is a good
argument for `build.sh --verify` being part of the workflow rather than an
occasional check. The fix is to park and restore the counter around the call:

```
NOW = CURSOR
GOSUB ReleaseOneDefender
CURSOR = NOW
```

`WHILE`/`REPEAT` are otherwise fine — this only bites when the body calls
something that touches the counter.

### 3.13 Three arithmetic forms that do not exist

```
NOW = WAVE_COOLDOWN - NOW     ->  error: cannot do VAR1 = THING - VAR1
NOW = 0 - NOW                 ->  same error
NOW = CONST / NOW             ->  cannot do VAR1 = THING / VAR1
```

`SET var, expr` only ever emits "store", "add to", "subtract from", "multiply
by", so the variable has to be the *left* operand of its own update. To compute
`CONST - var` you negate first and then add — and negation itself has to use the
compound form, because `0 - VAR` is the same rejected shape:

```
NOW *= -1
NOW = NOW + WAVE_COOLDOWN
```

`*=` is in the lexer (`Token::EqTimes`) even though the assignment-expression
docs do not mention it. `VAR / CONST` is fine (`DEFENDER = NOW / MS_PER_SECOND`);
only the reversed operands are a problem.

### 3.14 The entity checker does not understand arrays

`-fentity-tracking` is on by default and makes every variable remember which
entity type it last held, so a ped handle cannot be passed where a car handle is
expected. The tracking is per-variable (`src/script.cpp`,
`handle_entity_command`) and it is propagated by assignment:

```cpp
if(avar.entity && avar.entity != bvar.entity)   // -> "entity type mismatch"
    program.error(...);
avar.entity = bvar.entity;
```

That last line is the problem. An array element carries **no** entity type, so
reading one *erases* the type of the destination:

```
GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE ... DEFENDER   // 31@ becomes CHAR
IF IS_CHAR_DEAD DEFENDER                                   // fine
...
DEFENDER = DEFENDER_HANDLE[ROLL]                           // 31@ becomes NONE
IF DOES_CHAR_EXIST DEFENDER                                // error
```

```
error: assignment of variable of type NONE into one of type CHAR
error: expected variable of type CHAR but got NONE
```

And the damage is not local: the first error poisons the variable for the rest of
the scope, so every later `CHAR` use of it fails too — six errors from one
assignment, in subroutines that are nowhere near it.

The obvious fixes do not exist:

* there is no way to declare a typed array (`LVAR_INT Entity="CHAR" NAME[5]` is
  not a thing, and `<Var>` has no entity attribute);
* there is no way to reset a variable's type (`DEFENDER = 0` does not — the
  literal is not an entity, so the propagation leaves it untouched);
* a `FLOAT` local cannot hold a handle either, so the scratch coordinates cannot
  be used as a stash (`assignment` fails to match: `SET_LVAR_INT` wants an int
  variable, `SET_LVAR_FLOAT` a float one).

Storing ped handles in an array *is* the design here — five defender slots that
a `REPEAT` walks — so the checker is turned off:

```
-fno-entity-tracking
```

It is a compile-time assertion only: every `entity_tracking` use in the source is
a `program.error(...)` or an annotation update, none of it reaches codegen, so
what the flag removes is a check and nothing else. Losing it is acceptable here
because the handle types are a property of the source, not of the script: the
only ped handles in this mod come from `0AE1` and from `GET_PLAYER_CHAR`, and
they are kept in `CANDIDATE`, `DEFENDER` and `PLAYER_ACTOR`.

The cheaper structural workaround, if you would rather keep the checker on: never
read an array element into a variable that an entity command consumes. Pass the
element inline instead (`IF DOES_CHAR_EXIST DEFENDER_HANDLE[ROLL]`) — but note
that a `GOSUB` propagates the caller's argument entity into the callee's local
(`entity type mismatch in target label`), so an array element cannot be handed
to a subroutine parameter either.

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

## 5. Teaching gta3sc about CLEO+

`config/gtasa/cleo.xml` ends at `0xB16 BIT_SHL`, so gta3sc knows nothing about
any `0Exx` opcode out of the box. Everything CLEO+ adds has to be declared in an
extra XML file passed with `--add-config`; [`config/cleoplus.xml`](../config/cleoplus.xml)
is that file for this mod.

### 5.1 CLEO+ already ships one — and it cannot be used as-is

`(for developers)/gta3script/cleo.xml` in `JuniorDjjr/CLEOPlus` (branch `main`,
commit `d04732be7251`) is the upstream definition file, 439 commands and 28
enums. Every command in `config/cleoplus.xml` was compared against it and is
equivalent — same ID, same argument order, same `Type`/`Entity`/`Out`/`Enum`
attributes — so this repo's file is a verified excerpt rather than a guess. It
also confirms all ten IDs independently of the Sanny Builder Library.

Passing the official file to `--add-config` does **not** work, because
`--add-config` *appends* to a configuration that already contains gta3sc's own
`cleo.xml` and `commands.xml`:

| Problem | Detail |
|---|---|
| 119 duplicate IDs | the official file is a superset of gta3sc's `cleo.xml` — all 119 of its commands are declared again (names match, so this part is harmless) |
| **2 vanilla opcodes redefined** | `0x485` is `IS_PC_VERSION` in `commands.xml` and `RETURN_TRUE` in CLEO+; `0x59A` is `IS_AUSTRALIAN_GAME` and `RETURN_FALSE`. A script using either would silently compile to the wrong opcode |
| 1 enum silently rewritten | `BONE` exists in both; `BONE_L_BREAST` is 46 in gta3sc and 302 in CLEO+, `BONE_R_BREAST` 45 vs 301. Enums are merged by name, so whichever file loads last wins |

The official file is meant to *replace* `gtasa/cleo.xml` in a Sanny Builder
installation (that is literally what its accompanying
`CLEO+ gta3sc xml.txt` instructs), not to be layered on top of gta3sc's. Hence
the excerpt: only the ten commands this mod calls plus the `PEDSTAT` enum it
needs, none of which collide with anything already defined.

The upstream file is also where the task ids come from, should a script ever want
`0E42 IS_CHAR_DOING_TASK_ID`:

```
TASK_COMPLEX_KILL_PED_ON_FOOT  1000   TASK_SIMPLE_FIGHT  1016
TASK_COMPLEX_FLEE_ENTITY        909   TASK_NONE           200
```

### 5.2 Format

The format is the same as the shipped configs:

```xml
<GTA3Script>
  <Commands>
    <Command ID="0xefa" Name="GET_CHAR_FEAR">
      <Args>
        <Arg Type="INT" Entity="CHAR"/>
        <Arg Type="INT" Out="true" Desc="Fear 0..100"/>
      </Args>
    </Command>
    <Command ID="0xe1d" Name="IS_ON_MISSION"/>   <!-- conditions take no markup -->
  </Commands>
</GTA3Script>
```

Points that are easy to get wrong:

* **IDs are lowercase hex without padding.** The shipped files use `0x2a2`,
  `0x51a`, `0xb16`; `0x0EFA` is accepted too, but keep one style.
* **Conditions need no attribute.** gta3sc decides "is this a condition?" from
  the position in the source (inside `IF` / `IF NOT`), not from the XML — that is
  why `IS_KEY_PRESSED`, `IS_PLAYER_PLAYING` and `IS_ON_MISSION` look identical in
  the config. An inverted use is encoded by the compiler as `opcode | 0x8000`
  (`IF NOT LOCATE_CHAR_DISTANCE_TO_CHAR ...` emits `E4 8E`), which is also why a
  byte search for `E4 0E` finds nothing.
* **Outputs go last and take `Out="true"`.** A `FLOAT` output needs a `LVAR_FLOAT`
  at the call site; `Entity="CHAR"` on an output makes the destination variable a
  `CHAR` for the entity checker (§3.14).
* **Only `INT FLOAT PARAM LABEL CONSTANT TEXT_LABEL TEXT_LABEL16 TEXT_LABEL32
  STRING` are valid `Type` values** (`src/config.cpp`, `xml_to_argtype`). Sanny
  Builder Library types such as `KeyCode`, `PedStat`, `PedState` and `any` have to
  be translated: the first three are plain integers, and `any` becomes
  `Entity="ANY"` (which *is* defined in `commands.xml`, so it may be kept).
* **`--add-config` is appended after `cleo.xml`**, so a redefinition of an
  existing ID would win; none of the CLEO+ IDs collide, but this is the place to
  fix a shipped declaration that disagrees with the game.

The names, argument counts and argument directions were taken from the Sanny
Builder Library (`sa/sa.json`, `extension: "CLEO+"`), which is generated from the
CLEO+ SDK headers, then checked against the upstream gta3sc file (§5.1), and
every one of them was verified in the compiled bytecode (§6).

## 6. Verifying that the opcodes really landed

`build.sh --verify` disassembles the result, but a disassembly cannot prove that
an opcode ID is the one CLEO+ implements. The direct check is a byte scan of the
`.cs` for the little-endian 16-bit ID — remembering that an inverted condition
carries the `0x8000` bit, which is why `IF NOT LOCATE_CHAR_DISTANCE_TO_CHAR`
shows up as `E4 8E` and not `E4 0E`:

```
CLEO+
0x0E1D IS_ON_MISSION                 x1     0x0EFA GET_CHAR_FEAR              x1
0x0E25 IS_ON_CUTSCENE                x1     0x0EB1 GET_CHAR_STAT_ID           x1
0x0EB7 IS_ON_SCRIPTED_CUTSCENE       x1     0x0E44 GET_CHAR_KILL_TARGET_CHAR  x2
0x0E0A IS_CHAR_SCRIPT_CONTROLLED     x1     0x8EE4 NOT LOCATE_CHAR_DISTANCE.. x1
0x0E47 IS_CHAR_FIGHTING              x1

GTA3script aliases (section 3.11)
0x0485 TRUE / RETURN_TRUE            x9     0x059A RETURN_FALSE               x1

Rockstar debug opcodes (section 8)
0x0662 WRITE_DEBUG                   x5     0x0663 WRITE_DEBUG_WITH_INT       x4

core
0x05E2 TASK_KILL_CHAR_ON_FOOT        x1     0x0792 CLEAR_CHAR_TASKS_IMM..     x2
0x0AE1 GET_RANDOM_CHAR_IN_SPHERE     x2     0x0AB3 SET_CLEO_SHARED_VAR        x2
```

`bin/MOBBNOBRAVEZA.cs` is 3212 bytes, 544 instructions, uses locals `4@..30@`
(`0@..3@` stay reserved for CLEO), and starts with `00 00` (`NOP`) — proof that
no `SCRIPT_NAME` header is emitted (§3.1).

The scan also asserts the absences, which is the half that catches a regression:
`0x00A4 SCRIPT_NAME`, `0x0ACA PRINT_HELP_STRING`, `0x0AB0 IS_KEY_PRESSED`,
`0x0E3D IS_KEY_JUST_PRESSED`, `0x05E5 TASK_SMART_FLEE_CHAR`,
`0x0605 TASK_TURN_CHAR_TO_FACE_CHAR`, `0x060B TASK_SHAKE_FIST` and
`0x060F TASK_LOOK_AT_CHAR` are all at **zero occurrences**.

## 7. Verifying the CLEO+ implementations before trusting them

Declaring an opcode for the compiler only proves that the *call* is well formed.
Two of the CLEO+ opcodes this mod depends on make claims about game data that
are worth checking against the source, because a wrong reading would either break
the coward filter or crash the game.

`CLEOPlus/Misc.cpp` (JuniorDjjr/CLEOPlus, branch `main`):

```cpp
OpcodeResult WINAPI GET_CHAR_STAT_ID(CScriptThread* thread)
{
    CPed *ped = CPools::GetPed(CLEO_GetIntOpcodeParam(thread));
    CLEO_SetIntOpcodeParam(thread, *(DWORD*)ped->m_pStats);
    return OR_CONTINUE;
}

OpcodeResult WINAPI GET_CHAR_FEAR(CScriptThread* thread)
{
    CPed *ped = CPools::GetPed(CLEO_GetIntOpcodeParam(thread));
    uintptr_t pedStats = (uintptr_t)ped->m_pStats;
    CLEO_SetIntOpcodeParam(thread, *(uint8_t*)(pedStats + 0x24));
    return OR_CONTINUE;
}
```

`m_pStats` is `CPed +0x59C`, a pointer to `CPedStats`, whose layout is
documented field by field (GTAForums, "Documenting GTA-SA memory addresses",
exported from IDA and marked CONFIRMED):

```cpp
class CPedStats {
public:
    int           m_Index;                 // +0x00
    char          m_PedStatTypeName[18];   // +0x04
    float         m_fFleeDistance;         // +0x1C
    float         m_fHeadingChangeRate;    // +0x20
    unsigned char m_ucFear;                // +0x24
    unsigned char m_ucTemper;              // +0x25
    unsigned char m_ucLawfullness;         // +0x26
    unsigned char m_ucSexiness;            // +0x27
    float         m_fAttackStrength;       // +0x28
    float         m_fDefendWeakness;       // +0x2C
    unsigned short m_usShootingRate;       // +0x30
    unsigned char m_ucDefaultDecisionMaker;// +0x32
};  // SIZE 0x34
```

Two conclusions the script relies on:

* **`GET_CHAR_FEAR` returns a real fear value, not a bitfield.** `m_ucFear` is a
  single `unsigned char` at exactly `+0x24`, so `*(uint8_t*)(pedStats + 0x24)`
  reads precisely the `Fear` column of `pedstats.dat` (0..100). Had fear been
  packed into a bitfield, `MAX_FEAR` would have been meaningless.
* **`GET_CHAR_STAT_ID` returns the row *index*, not a pointer.** `m_Index` is the
  first member, so `*(DWORD*)m_pStats` is it. The index equals the position in
  `pedstats.dat` (0-based) and matches the `ePedStats` enum order — `STAT_PLAYER`
  0 ... `STAT_STD_MISSION` 41, `STAT_COWARD` 42 — which is what the `PEDSTAT_*`
  constants in the source encode.

On crash safety: both implementations dereference `m_pStats` **without a null
check**, and `CPools::GetPed` returns `nullptr` for a handle that is not in the
pool. So an invalid or stale ped handle in either opcode is a hard crash. The
script only ever passes handles that came from `0AE1` in the same frame and that
just survived `DOES_CHAR_EXIST` + `IS_CHAR_DEAD`, with no `WAIT` between the
check and the call — there is no window in which the pool can reclaim the ped.
This is also why the slot table releases a defender through `DOES_CHAR_EXIST`
before `GET_CHAR_KILL_TARGET_CHAR` touches him.

### 7.1 The AI queries read a per-frame cache, and what that means

`IS_CHAR_FIGHTING`, `IS_CHAR_DOING_TASK_ID` and `GET_CHAR_KILL_TARGET_CHAR` do
not ask the game's task system anything. They read `PedExtended`, CLEO+'s
per-ped side data (`CLEOPlus/PedExtendedData.h`), which is rebuilt for **every
ped in the pool, every frame** (`CLEOPlus/CLEOPlus.cpp`, around line 1000):

```cpp
xdata.aiFlagsIntValue = 0;                    // all AI flags reset
for (i = 0; i < 5; i++)                       // primary task slots
    for (task = taskMgr->m_aPrimaryTasks[i]; task; task = task->GetSubTask())
        CacheOnePedTask(ped, xdata, activeTaskIndex, task, false);
for (i = 0; i < 5; i++)                       // secondary task slots
    ...
```

and `CacheOnePedTask` (`CLEOPlus/Intelligence.cpp`) walks the whole task chain,
so a subtask counts too:

```cpp
case TASK_COMPLEX_KILL_PED_ON_FOOT:           // 1000, what 05E2 creates
    taskOffsetForKillTargetPed = 16;  break;
case TASK_SIMPLE_FIGHT:                       // 1016
    xdata.aiFlags.bFighting = true;   break;
...
if (taskOffsetForKillTargetPed > 0) {
    xdata.aiFlags.bKillingSomething = true;
    xdata.killTargetPed = *(CEntity**)(task + taskOffsetForKillTargetPed);
}
```

Three consequences this script is written around:

* **`GET_CHAR_KILL_TARGET_CHAR` is a real answer to "is he still on our task?".**
  `killTargetPed` is read out of the `TASK_COMPLEX_KILL_PED_ON_FOOT` struct that
  `05E2` created, at offset 16. When the task is gone the value stops being
  refreshed, and the opcode returns `-1` unless the entity pointer is still a
  live ped — so the comparison against the player fails and the script does not
  clear tasks the game has since replaced. Exactly the intended behaviour.
* **`IS_CHAR_FIGHTING` means "in melee right now", not "hostile".** `bFighting`
  comes from `TASK_SIMPLE_FIGHT` (1016), the swing/punch subtask, and the flags
  are zeroed every frame. A defender chasing the player or shooting at him is
  *not* "fighting" by this definition; a ped already trading punches with
  somebody is. As a recruit filter that is the useful reading: whoever is already
  in a fistfight is left to the game's own combat AI.
* **The two are refreshed once per frame for the whole pool**, so there is no
  staleness to poll around and no reason to cache them in locals.

The same cache is what makes `GET_CHAR_KILL_TARGET_CHAR` safe against a target
ped that has since been deleted: the opcode checks `entity->m_nType ==
ENTITY_TYPE_PED` and converts through `CPools::GetPedRef`, returning `-1` when
the pointer is no longer a ped in the pool.

### 7.2 Null-check discipline across the CLEO+ opcodes used here

The implementations are not uniform about validating the handle:

| Opcode | Null check? | Note |
|---|---|---|
| `0E0A IS_CHAR_SCRIPT_CONTROLLED` | **yes** (`ped != nullptr && ped->m_nCreatedBy == 2`) | safe with a stale handle |
| `0E47 IS_CHAR_FIGHTING` | no (`extData.Get(ped)` on a possibly null ped) | needs a live handle |
| `0E44 GET_CHAR_KILL_TARGET_CHAR` | no | needs a live handle |
| `0EE4 LOCATE_CHAR_DISTANCE_TO_CHAR` | no (`pedA->GetPosition()` directly) | needs **two** live handles |
| `0EB1 GET_CHAR_STAT_ID` | no (`*(DWORD*)ped->m_pStats`) | needs a live handle |
| `0EFA GET_CHAR_FEAR` | no (`*(uint8_t*)(m_pStats + 0x24)`) | needs a live handle |

An invalid or reclaimed ped handle in any of the five unchecked opcodes is a hard
crash, not a `false`. The script's rule is therefore: a handle only ever reaches
a CLEO+ opcode in the **same frame** it came out of `0AE1` (or out of
`GET_PLAYER_CHAR`) and after `DOES_CHAR_EXIST` + `IS_CHAR_DEAD`, with no `WAIT`
in between — the pool cannot reclaim a ped mid-frame. The one place a handle is
kept across frames is `DEFENDER_HANDLE[5]`, and every read of it goes through
`DOES_CHAR_EXIST` before anything dereferences it (`ReleaseOneDefender`, and the
inlined copy of it in the slot-reuse path).

`LOCATE_CHAR_DISTANCE_TO_CHAR` takes two handles, so the victim needs the same
treatment: `RecruitDefenders` re-checks `DOES_CHAR_EXIST CANDIDATE` and
`IS_CHAR_DEAD CANDIDATE` at the top of every frame of the recruit window, before
the 3D distance test can run.

## 8. Debug text that only exists for people who asked for it

`0662 WRITE_DEBUG`, `0663 WRITE_DEBUG_WITH_INT` and `0664 WRITE_DEBUG_WITH_FLOAT`
are Rockstar's own script-debugging opcodes. They are still in the retail game as
no-ops — the Sanny Builder Library marks all three `is_nop: true` — and gta3sc
already declares them in `config/gtasa/commands.xml`, so **no `--add-config` is
needed for them**:

```xml
<Command ID="0x662" Name="WRITE_DEBUG">
  <Args><Arg Type="STRING"/></Args>
</Command>
<Command ID="0x663" Name="WRITE_DEBUG_WITH_INT">
  <Args><Arg Type="STRING"/><Arg Type="INT"/></Args>
</Command>
```

Two mods re-activate them, and this is the whole reason a shipped mod can afford
to be chatty:

| Mod | Notes |
|---|---|
| [ScrDebug](https://www.mixmods.com.br/2017/06/sa-scrdebug/) (Deji) | Re-implements the pre-release debug system; also re-enables the `0735`/`0736` key checks that Rockstar used to hide cheats in `main.scm` (662 of them across main.scm + script.img) |
| CLEO5 + the `DebugUtils` plugin | Needs `DebugUtils.General.LegacyDebugOpcodes = 1`; registers `0x0662/0x0663/0x0664` explicitly |

Without either one installed the opcode costs a parameter skip and nothing else —
no text, no log, no gameplay difference. That is exactly how the 2011 original
could ship its `write_debug "НЕ БЕЙ ЖЕНЩИНУ 2, АВТОР IZERLI..."` credit line to
end users (its `{$USE debug}` is Sanny's extension directive; gta3sc has no
`{$...}` directives at all, and none is needed here).

### 8.1 The `_WITH_INT` string is a label, not a format string

From CLEO5's `cleo_plugins/DebugUtils/DebugUtils.cpp`, which follows Rockstar's
original behaviour:

```cpp
// 0663=1, printint %1s% %2d%
auto text  = CLEO_ReadStringOpcodeParam(thread);
auto value = CLEO_GetIntOpcodeParam(thread);
std::ostringstream ss;
ss << text << ": " << value;
CLEO_Log(eLogLevel::Debug, ss.str().c_str());
```

So `WRITE_DEBUG_WITH_INT "MenReact defenders" 3` prints `MenReact defenders: 3`.
Writing a `%d` into the string would print the `%d` literally. Also note the
guard at the top of every one of the three implementations:

```cpp
if (!CLEO_GetScriptDebugMode(thread)) { CLEO_SkipOpcodeParams(thread, 2); return OR_CONTINUE; }
```

— per-script debug mode, so the no-op path is explicit and cheap.

### 8.2 gta3sc upper-cases string literals

The emitted bytes for `WRITE_DEBUG "MenReact: victim detected"` are
`MENREACT: VICTIM DETECTED`. GTA3script string literals are case-insensitive and
the compiler normalises them to upper case, so what appears on screen is upper
case regardless of how the source is written. Write the debug lines knowing that;
there is no flag to preserve the case.

### 8.3 You cannot pass a string to a subroutine

A `GOSUB` argument lands in `0@..`, which belongs to the `CLEO_ARGS` block, and
GTA3script has no string locals anyway — so a `GOSUB Debug "text"` helper is not
expressible. The shape this script uses instead is a gate that answers in a local:

```
DebugGate:
DBG_COUNT = 0
BIT_AND OPTIONS OPT_DEBUG_TEXT ROLL
IF NOT ROLL = 0
    DBG_COUNT = 1
ENDIF
RETURN
```

```
GOSUB DebugGate
IF DBG_COUNT = 1
    WRITE_DEBUG "MenReact: victim detected, recruiting witnesses"
ENDIF
```

Two lines per site instead of one, but every line stays readable at its call
site and `OPT_DEBUG_TEXT` can silence all of them at once — which matters because
somebody running ScrDebug for an unrelated reason would otherwise get this mod's
log mixed into theirs with no way to turn it off.

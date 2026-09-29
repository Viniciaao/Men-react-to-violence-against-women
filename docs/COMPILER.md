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
  §3.11. It changes nothing in the emitted bytecode.

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

That worked only because `0AB0 IS_KEY_PRESSED` binds `Enum="WIN32_VK"`. The
CLEO+ replacement used now, `0E3D IS_KEY_JUST_PRESSED`, is declared by
`config/cleoplus.xml` with a bare `INT` argument, and `WIN32_VK` is not defined
anywhere in gta3sc's `config/gtasa/` — so the enum member would resolve to
nothing. Hence `CONST_INT KEY_F10 121`. Two more facts about enums that matter
when writing an `--add-config` file:

* an `<Arg Enum="Foo"/>` whose enum `Foo` is not defined in the loaded XML makes
  every use of that argument fail to match, so a new command should only bind an
  enum it also defines;
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

### 3.11 The entity checker does not understand arrays

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
  `CHAR` for the entity checker (§3.11).
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
CLEO+ SDK headers, and every one of them was verified in the compiled bytecode
(§6).

## 6. Verifying that the opcodes really landed

`build.sh --verify` disassembles the result, but a disassembly cannot prove that
an opcode ID is the one CLEO+ implements. The direct check is a byte scan of the
`.cs` for the little-endian 16-bit ID — remembering that an inverted condition
carries the `0x8000` bit:

```
0x0E1D IS_ON_MISSION                 x1     0x0EFA GET_CHAR_FEAR              x1
0x0E25 IS_ON_CUTSCENE                x1     0x0EB1 GET_CHAR_STAT_ID           x1
0x0EB7 IS_ON_SCRIPTED_CUTSCENE       x1     0x0E44 GET_CHAR_KILL_TARGET_CHAR  x1
0x0E3D IS_KEY_JUST_PRESSED           x1     0x8EE4 NOT LOCATE_CHAR_DISTANCE.. x1
0x0E0A IS_CHAR_SCRIPT_CONTROLLED     x1     0x05E2 TASK_KILL_CHAR_ON_FOOT     x1
0x0E47 IS_CHAR_FIGHTING              x1     0x0792 CLEAR_CHAR_TASKS_IMM..     x1
```

`bin/MOBBNOBRAVEZA.cs` is 2553 bytes, 476 instructions, and starts with `00 00`
(`NOP`) — proof that no `SCRIPT_NAME` header is emitted (§3.1).

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

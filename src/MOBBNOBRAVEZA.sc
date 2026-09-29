//****************************************************************************
//*  MEN REACT TO VIOLENCE AGAINST WOMEN  -  "Nao bata em mulheres"
//****************************************************************************
//*
//*  WHAT IT DOES
//*  ------------
//*  When the player beats up a woman, the men around them notice and react:
//*  some step in and attack the player, some only shout and threaten, the
//*  cowards run away - and the assaulted woman tries to escape instead of
//*  standing there taking it.
//*
//*  This is a from-scratch rewrite of the 2011 CLEO mod "Nao bata em mulheres"
//*  by Izerli (MixMods, "v2" page).  docs/ANALISE.md contains the line-by-line
//*  audit of the original script and the reasoning behind every change.
//*
//*  REQUIREMENTS
//*  ------------
//*  GTA San Andreas + CLEO 4.1 or newer.  Only two CLEO opcodes are used:
//*      0ACA PRINT_HELP_STRING
//*      0AE1 GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE
//*  Install: copy MOBBNOBRAVEZA.CS into the CLEO folder.
//*
//*  BUILDING (thelink2012/gta3sc on Linux or Windows)
//*  -------------------------------------------------
//*      gta3sc src/MOBBNOBRAVEZA.sc --config=gtasa --guesser --cs \
//*             -fbreak-continue -o bin/MOBBNOBRAVEZA.cs
//*  or simply run ./build.sh from the repository root.
//*
//*  IN GAME
//*  -------
//*  F10 toggles the mod on/off.  Everything tunable is in the CONST block
//*  below: edit it and recompile.
//*
//****************************************************************************

SCRIPT_START

SCRIPT_NAME MNBRV                   // thread name, max 8 chars

//****************************************************************************
//* CONFIG
//****************************************************************************

// Optional features, as a bit field.  Add the values of the features you want
// and put the sum in OPTIONS_DEFAULT.  gta3sc has no constant folding, so the
// flags are resolved at runtime with BIT_AND (opcode 0B10) - this costs one
// opcode per check and keeps every knob in a single place.
//
//      1 = SHOW_HELP_TEXT      on-screen feedback ("Hey! Stop hitting her!")
//      2 = IGNORE_WHEN_IN_CAR  do nothing while the player is driving
//      4 = MELEE_ONLY          ignore gunfire / explosions, melee and cars only
//      8 = VICTIM_FLEES        the assaulted woman runs away
//     16 = USE_TOUGH_BRAIN     defenders get the "random tough" decision maker
//     32 = (internal)          at least one defender is currently active
//
//  1 + 2 + 8 + 16 = 27 (the default: everything except MELEE_ONLY)
CONST_INT   OPTIONS_DEFAULT         27
CONST_INT   OPT_SHOW_HELP_TEXT      1
CONST_INT   OPT_IGNORE_WHEN_IN_CAR  2
CONST_INT   OPT_MELEE_ONLY          4
CONST_INT   OPT_VICTIM_FLEES        8
CONST_INT   OPT_USE_TOUGH_BRAIN     16
CONST_INT   OPT_ANY_ACTIVE          32      // internal: release fast path

CONST_INT   ENABLED_ON_START        1

// -- detection --------------------------------------------------------------
CONST_INT   SCAN_INTERVAL           200     // ms between victim scans
CONST_FLOAT VICTIM_SCAN_RADIUS      3.0     // how close to the player we look

// -- recruitment ------------------------------------------------------------
CONST_FLOAT DEFEND_RADIUS           25.0    // witnesses inside this radius react
CONST_FLOAT DEFEND_RADIUS_Z         8.0     // vertical tolerance of that radius
CONST_INT   MAX_DEFENDERS           5       // hard cap on simultaneous defenders
CONST_INT   RECRUIT_WINDOW          2500    // ms spent looking for witnesses
CONST_INT   RECRUIT_STEP_DELAY      120     // ms between two recruitments
CONST_INT   DEFENDER_TIMEOUT        45000   // ms before a defender gives up

// -- per-witness behaviour --------------------------------------------------
CONST_INT   ATTACK_CHANCE           60      // percent: actually attacks
CONST_INT   FLEE_CHANCE             15      // percent: runs away (rest shouts)
CONST_INT   DEFENDER_ACCURACY       55      // 0..100
CONST_FLOAT DEFENDER_SENSE_RANGE    35.0

// -- rate limiting ----------------------------------------------------------
CONST_INT   WAVE_COOLDOWN           8000    // ms between two reactions (global)
CONST_INT   VICTIM_COOLDOWN         25000   // ms before the same woman re-triggers

// -- victim reaction --------------------------------------------------------
CONST_FLOAT VICTIM_FLEE_RADIUS      30.0
CONST_INT   VICTIM_FLEE_TIME        6000

// -- geometry / misc --------------------------------------------------------
CONST_FLOAT EYE_HEIGHT              0.7     // raises the LOS ray off the ground
CONST_FLOAT NO_OFFSET               0.0
CONST_INT   PLAYER_INDEX            0       // CJ is always player 0
CONST_INT   DEFAULT_ACCURACY        60      // restored when a defender is freed
CONST_INT   LOOK_AT_TIME            4000
CONST_INT   TOGGLE_KEY_COOLDOWN     800     // ms of hotkey debounce
CONST_INT   PERCENT_TOTAL           100

// -- CLEO 0AE1 "filter" argument --------------------------------------------
CONST_INT   SEARCH_ALIVE_NPC        1       // CharSearchFilter.AnyAliveNPC

// -- "random tough" decision maker template (SA data/Decision) --------------
CONST_INT   DM_RANDOM_TOUGH         65539   // DECISION_MAKER_PED template id

// -- CLEO shared variables (0AB3/0AB4) so other mods can read our state -----
CONST_INT   SHAREDVAR_ENABLED       3100
CONST_INT   SHAREDVAR_OPTIONS       3102

//****************************************************************************
//* LOCAL VARIABLE MAP
//****************************************************************************
//  A CLEO thread owns 32 locals (0@..31@) and gta3sc allocates them in
//  declaration order starting at 0@, so the layout below is literal.
//
//  0@..3@   CLEO_ARGS[4]          reserved - 0A92 create_custom_thread params
//
//  persistent state -------------------------------------------------------
//    4@..8@   DEFENDER_HANDLE[5]  recruited witnesses (0 = free slot)
//    9@..13@  DEFENDER_SINCE[5]   game timer value of each recruitment
//   14@       PLAYER_ACTOR        CJ's ped handle, refreshed every tick
//   15@       LAST_VICTIM         last woman we reacted to
//   16@       LAST_VICTIM_TIME    when we reacted to her
//   17@       WAVE_TIME           timestamp of the last reaction (global)
//   18@       ENABLED             runtime on/off
//   19@       NOW                 cached game timer
//   20@       LAST_TOGGLE         hotkey debounce
//   21@       OPTIONS             bit field of enabled optional features
//
//  scratch (free to clobber anywhere) -------------------------------------
//   22@ PX    23@ PY    24@ PZ     player position / LOS start point
//   25@ CANDIDATE                  ped being inspected by the scan loop
//   26@ CURSOR                     0AE1 iteration cursor, then GET_PED_TYPE out
//   27@ ROLL                       dice / temporary arithmetic / loop counter
//   28@ VX    29@ VY    30@ VZ     victim position (raised to eye height)
//   31@ DEFENDER                   witness being recruited or released
//****************************************************************************

{
MAIN:
NOP                                 // guards against the jump-at-offset-0 bug

LVAR_INT   CLEO_ARGS[4]             //  0@..3@   reserved for 0A92 parameters

LVAR_INT   DEFENDER_HANDLE[5]       //  4@..8@
LVAR_INT   DEFENDER_SINCE[5]        //  9@..13@
LVAR_INT   PLAYER_ACTOR             // 14@
LVAR_INT   LAST_VICTIM              // 15@
LVAR_INT   LAST_VICTIM_TIME         // 16@
LVAR_INT   WAVE_TIME                // 17@
LVAR_INT   ENABLED                  // 18@
LVAR_INT   NOW                      // 19@
LVAR_INT   LAST_TOGGLE              // 20@
LVAR_INT   OPTIONS                  // 21@

LVAR_FLOAT PX PY PZ                 // 22@..24@
LVAR_INT   CANDIDATE                // 25@
LVAR_INT   CURSOR                   // 26@
LVAR_INT   ROLL                     // 27@

LVAR_FLOAT VX VY VZ                 // 28@..30@
LVAR_INT   DEFENDER                 // 31@

//----------------------------------------------------------------------------
// Boot
//----------------------------------------------------------------------------
OPTIONS = OPTIONS_DEFAULT
ENABLED = ENABLED_ON_START
SET_CLEO_SHARED_VAR SHAREDVAR_ENABLED ENABLED
SET_CLEO_SHARED_VAR SHAREDVAR_OPTIONS OPTIONS
LAST_VICTIM = -1
LAST_VICTIM_TIME = 0
WAVE_TIME = 0
GET_GAME_TIMER LAST_TOGGLE
LAST_TOGGLE = LAST_TOGGLE - TOGGLE_KEY_COOLDOWN     // allow an instant toggle

BIT_AND OPTIONS OPT_SHOW_HELP_TEXT ROLL
IF NOT ROLL = 0
    PRINT_HELP_STRING "Men React to Violence Against Women: loaded. F10 toggles it."
ENDIF

//****************************************************************************
//* MAIN LOOP
//****************************************************************************
LOOP_FOREVER:
WAIT SCAN_INTERVAL
GET_GAME_TIMER NOW

//----------------------------------------------------------------------------
// Runtime on/off hotkey (VK_F10 = 121)
//----------------------------------------------------------------------------
IF IS_KEY_PRESSED VK_F10
    ROLL = NOW - LAST_TOGGLE
    IF ROLL > TOGGLE_KEY_COOLDOWN
        GET_GAME_TIMER LAST_TOGGLE
        IF ENABLED = 1
            ENABLED = 0
            BIT_AND OPTIONS OPT_SHOW_HELP_TEXT ROLL
            IF NOT ROLL = 0
                PRINT_HELP_STRING "Men React to Violence Against Women: OFF"
            ENDIF
        ELSE
            ENABLED = 1
            BIT_AND OPTIONS OPT_SHOW_HELP_TEXT ROLL
            IF NOT ROLL = 0
                PRINT_HELP_STRING "Men React to Violence Against Women: ON"
            ENDIF
        ENDIF
        SET_CLEO_SHARED_VAR SHAREDVAR_ENABLED ENABLED
    ENDIF
ENDIF

//----------------------------------------------------------------------------
// Only act while the game is really in the player's hands.
// IS_PLAYER_PLAYING is false whenever CJ is wasted, busted or locked down by a
// cutscene, which is exactly when this mod has to stay out of the way.
//----------------------------------------------------------------------------
IF NOT IS_PLAYER_PLAYING PLAYER_INDEX
    GOTO LOOP_RELEASE
ENDIF
GET_PLAYER_CHAR PLAYER_INDEX PLAYER_ACTOR
IF NOT DOES_CHAR_EXIST PLAYER_ACTOR
    GOTO LOOP_RELEASE
ENDIF

BIT_AND OPTIONS OPT_IGNORE_WHEN_IN_CAR ROLL
IF NOT ROLL = 0
    IF IS_CHAR_IN_ANY_CAR PLAYER_ACTOR
        GOTO LOOP_RELEASE
    ENDIF
ENDIF

IF ENABLED = 0
    GOTO LOOP_RELEASE
ENDIF

//----------------------------------------------------------------------------
// Retire the defenders whose time is up
//----------------------------------------------------------------------------
GOSUB ReleaseExpiredDefenders

//----------------------------------------------------------------------------
// Global rate limit between two reactions
//----------------------------------------------------------------------------
ROLL = NOW - WAVE_TIME
IF ROLL < WAVE_COOLDOWN
    GOTO LOOP_FOREVER
ENDIF

//----------------------------------------------------------------------------
// Look for a woman the player has just hurt
//----------------------------------------------------------------------------
GET_CHAR_COORDINATES PLAYER_ACTOR PX PY PZ
CURSOR = 0

SCAN_NEXT_CANDIDATE:
GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE PX PY PZ VICTIM_SCAN_RADIUS CURSOR SEARCH_ALIVE_NPC CANDIDATE
IF CANDIDATE = -1                   // 0AE1 yields -1 when the pool is exhausted
    GOTO LOOP_FOREVER
ENDIF
CURSOR = 1

IF CANDIDATE = PLAYER_ACTOR
    GOTO SCAN_NEXT_CANDIDATE
ENDIF
IF NOT DOES_CHAR_EXIST CANDIDATE
    GOTO SCAN_NEXT_CANDIDATE
ENDIF
IF IS_CHAR_DEAD CANDIDATE
    GOTO SCAN_NEXT_CANDIDATE
ENDIF
IF NOT IS_CHAR_ON_FOOT CANDIDATE
    GOTO SCAN_NEXT_CANDIDATE
ENDIF
IF IS_CHAR_MALE CANDIDATE
    GOTO SCAN_NEXT_CANDIDATE
ENDIF
IF NOT HAS_CHAR_BEEN_DAMAGED_BY_CHAR CANDIDATE PLAYER_ACTOR
    GOTO SCAN_NEXT_CANDIDATE
ENDIF

// Optional filter: melee / run-over damage only, ignore gunfire.
BIT_AND OPTIONS OPT_MELEE_ONLY ROLL
IF NOT ROLL = 0
    IF NOT HAS_CHAR_BEEN_DAMAGED_BY_WEAPON CANDIDATE WEAPONTYPE_ANYMELEE
        IF NOT HAS_CHAR_BEEN_DAMAGED_BY_WEAPON CANDIDATE WEAPONTYPE_RUNOVERBYCAR
            IF NOT HAS_CHAR_BEEN_DAMAGED_BY_WEAPON CANDIDATE WEAPONTYPE_RAMMEDBYCAR
                GOTO SCAN_NEXT_CANDIDATE
            ENDIF
        ENDIF
    ENDIF
ENDIF

// Per-victim cooldown, so the same woman cannot trigger a wave forever.
IF CANDIDATE = LAST_VICTIM
    ROLL = NOW - LAST_VICTIM_TIME
    IF ROLL < VICTIM_COOLDOWN
        GOTO SCAN_NEXT_CANDIDATE
    ENDIF
ENDIF

//****************************************************************************
//* Victim found - react.
//****************************************************************************
LAST_VICTIM = CANDIDATE
LAST_VICTIM_TIME = NOW
WAVE_TIME = NOW

// Consume the damage flags: without this the very same hit would start the
// next wave as soon as WAVE_COOLDOWN expires.  (The 2011 original never
// cleared them and only compared against a "last victim" variable.)
CLEAR_CHAR_LAST_DAMAGE_ENTITY CANDIDATE
CLEAR_CHAR_LAST_WEAPON_DAMAGE CANDIDATE

BIT_AND OPTIONS OPT_SHOW_HELP_TEXT ROLL
IF NOT ROLL = 0
    PRINT_HELP_STRING "Hey! Stop hitting her!"
ENDIF

GOSUB MakeVictimReact
GOSUB RecruitDefenders

GOTO LOOP_FOREVER

//****************************************************************************
//* Release everybody, then keep looping
//****************************************************************************
LOOP_RELEASE:
BIT_AND OPTIONS OPT_ANY_ACTIVE ROLL
IF NOT ROLL = 0
    GOSUB ReleaseAllDefenders
ENDIF
GOTO LOOP_FOREVER

//****************************************************************************
//* SUBROUTINES
//****************************************************************************

//----------------------------------------------------------------------------
// MakeVictimReact - the assaulted woman tries to get away.
// in: CANDIDATE (25@) = the victim, PLAYER_ACTOR (14@)
//----------------------------------------------------------------------------
MakeVictimReact:
BIT_AND OPTIONS OPT_VICTIM_FLEES ROLL
IF ROLL = 0
    RETURN
ENDIF
IF NOT DOES_CHAR_EXIST CANDIDATE
    RETURN
ENDIF
IF IS_CHAR_DEAD CANDIDATE
    RETURN
ENDIF
IF NOT IS_CHAR_ON_FOOT CANDIDATE
    RETURN
ENDIF
TASK_SMART_FLEE_CHAR CANDIDATE PLAYER_ACTOR VICTIM_FLEE_RADIUS VICTIM_FLEE_TIME
RETURN

//----------------------------------------------------------------------------
// RecruitDefenders - for RECRUIT_WINDOW milliseconds, walk the ped pool around
// the victim and turn the eligible witnesses into defenders.
// in: CANDIDATE (25@) = the victim, PLAYER_ACTOR (14@)
//----------------------------------------------------------------------------
RecruitDefenders:

RECRUIT_WINDOW_LOOP:
WAIT 0
GET_GAME_TIMER NOW
NOW = NOW - WAVE_TIME
IF NOW > RECRUIT_WINDOW
    RETURN
ENDIF
IF NOT DOES_CHAR_EXIST CANDIDATE
    RETURN
ENDIF
IF NOT IS_PLAYER_PLAYING PLAYER_INDEX
    RETURN
ENDIF
IF IS_CHAR_IN_ANY_CAR PLAYER_ACTOR
    RETURN
ENDIF

// She may be running away, so keep the search centred on her.
GET_CHAR_COORDINATES CANDIDATE VX VY VZ
VZ = VZ + EYE_HEIGHT

CURSOR = 0

RECRUIT_NEXT_WITNESS:
GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE VX VY VZ DEFEND_RADIUS CURSOR SEARCH_ALIVE_NPC DEFENDER
IF DEFENDER = -1                    // 0AE1 yields -1 when the pool is exhausted
    GOTO RECRUIT_WINDOW_LOOP
ENDIF
CURSOR = 1

// --- usable at all? --------------------------------------------------------
IF DEFENDER = PLAYER_ACTOR
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF DEFENDER = CANDIDATE
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF NOT DOES_CHAR_EXIST DEFENDER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF IS_CHAR_DEAD DEFENDER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF NOT IS_CHAR_MALE DEFENDER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF NOT IS_CHAR_ON_FOOT DEFENDER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF IS_CHAR_IN_WATER DEFENDER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF IS_CHAR_IN_AIR DEFENDER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF

// --- would he be a nonsense defender? --------------------------------------
// CURSOR (26@) is free again right after the 0AE1 call, so it holds the ped
// type here and is rewritten with 0/1 on the next iteration.
GET_PED_TYPE DEFENDER CURSOR
IF CURSOR = PEDTYPE_COP
    GOTO RECRUIT_NEXT_WITNESS       // the police have their own job to do
ENDIF
IF CURSOR >= PEDTYPE_MISSION1
    IF CURSOR <= PEDTYPE_MISSION8
        GOTO RECRUIT_NEXT_WITNESS   // never touch mission characters
    ENDIF
ENDIF
IF CURSOR >= PEDTYPE_PLAYER1
    IF CURSOR <= PEDTYPE_PLAYER_UNUSED
        GOTO RECRUIT_NEXT_WITNESS
    ENDIF
ENDIF

// --- close enough to have actually seen it? --------------------------------
IF NOT LOCATE_CHAR_ANY_MEANS_CHAR_3D DEFENDER CANDIDATE DEFEND_RADIUS DEFEND_RADIUS DEFEND_RADIUS_Z 0
    GOTO RECRUIT_NEXT_WITNESS
ENDIF

// --- line of sight, so nobody reacts through a wall ------------------------
// PX/PY/PZ (22@..24@) are the scan-loop scratch and are re-read every scan.
GET_OFFSET_FROM_CHAR_IN_WORLD_COORDS DEFENDER NO_OFFSET NO_OFFSET EYE_HEIGHT PX PY PZ
IF NOT IS_LINE_OF_SIGHT_CLEAR PX PY PZ VX VY VZ 1 0 1 0 0
    GOTO RECRUIT_NEXT_WITNESS
ENDIF

// --- already recruited? ----------------------------------------------------
IF DEFENDER_HANDLE[0] = DEFENDER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF DEFENDER_HANDLE[1] = DEFENDER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF DEFENDER_HANDLE[2] = DEFENDER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF DEFENDER_HANDLE[3] = DEFENDER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF DEFENDER_HANDLE[4] = DEFENDER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF

// --- pick a free slot: empty, or expired -----------------------------------
// ROLL (27@) doubles as the loop counter, and NOW (19@) as the age of a slot.
CURSOR = -1
REPEAT MAX_DEFENDERS ROLL
    IF DEFENDER_HANDLE[ROLL] = 0
        CURSOR = ROLL
        BREAK
    ENDIF
    GET_GAME_TIMER NOW
    NOW = NOW - DEFENDER_SINCE[ROLL]
    IF NOW > DEFENDER_TIMEOUT
        CURSOR = ROLL
        BREAK
    ENDIF
ENDREPEAT
IF NOT CURSOR > -1
    GOTO RECRUIT_NEXT_WITNESS       // the mob is already big enough
ENDIF

// --- recruit him -----------------------------------------------------------
DEFENDER_HANDLE[CURSOR] = DEFENDER
GET_GAME_TIMER DEFENDER_SINCE[CURSOR]
BIT_OR OPTIONS OPT_ANY_ACTIVE ROLL       // remember that a slot is busy
GOSUB ReactDefender
WAIT RECRUIT_STEP_DELAY             // never recruit a whole crowd in one frame
GOTO RECRUIT_WINDOW_LOOP

//----------------------------------------------------------------------------
// ReactDefender - decide what this particular witness does about it.
// in: DEFENDER (31@), PLAYER_ACTOR (14@)
//----------------------------------------------------------------------------
ReactDefender:
GENERATE_RANDOM_INT_IN_RANGE 0 PERCENT_TOTAL ROLL

SET_CHAR_KEEP_TASK DEFENDER 1
SET_SENSE_RANGE DEFENDER DEFENDER_SENSE_RANGE
BIT_AND OPTIONS OPT_USE_TOUGH_BRAIN CURSOR
IF NOT CURSOR = 0
    TASK_SET_CHAR_DECISION_MAKER DEFENDER DM_RANDOM_TOUGH
ENDIF

IF ROLL < ATTACK_CHANCE
    SET_CHAR_ACCURACY DEFENDER DEFENDER_ACCURACY
    TASK_KILL_CHAR_ON_FOOT DEFENDER PLAYER_ACTOR
ELSE
    ROLL = ROLL - ATTACK_CHANCE
    IF ROLL < FLEE_CHANCE
        TASK_SMART_FLEE_CHAR DEFENDER PLAYER_ACTOR VICTIM_FLEE_RADIUS DEFENDER_TIMEOUT
    ELSE
        TASK_TURN_CHAR_TO_FACE_CHAR DEFENDER PLAYER_ACTOR
        TASK_SHAKE_FIST DEFENDER
        TASK_LOOK_AT_CHAR DEFENDER PLAYER_ACTOR LOOK_AT_TIME
    ENDIF
ENDIF
RETURN

//----------------------------------------------------------------------------
// ReleaseExpiredDefenders - drop whoever has given up.
//----------------------------------------------------------------------------
ReleaseExpiredDefenders:
BIT_AND OPTIONS OPT_ANY_ACTIVE ROLL
IF ROLL = 0
    RETURN
ENDIF
GET_GAME_TIMER NOW
REPEAT MAX_DEFENDERS CURSOR
    IF DEFENDER_HANDLE[CURSOR] > 0
        ROLL = NOW - DEFENDER_SINCE[CURSOR]
        IF ROLL > DEFENDER_TIMEOUT
            DEFENDER = DEFENDER_HANDLE[CURSOR]
            GOSUB ReleaseOneDefender
            DEFENDER_HANDLE[CURSOR] = 0
        ENDIF
    ENDIF
ENDREPEAT
GOSUB UpdateActiveFlag
RETURN

//----------------------------------------------------------------------------
// ReleaseAllDefenders - the player died, got busted, hopped into a car or the
// mod got switched off: everybody minds their own business again.
//----------------------------------------------------------------------------
ReleaseAllDefenders:
REPEAT MAX_DEFENDERS CURSOR
    IF DEFENDER_HANDLE[CURSOR] > 0
        DEFENDER = DEFENDER_HANDLE[CURSOR]
        GOSUB ReleaseOneDefender
        DEFENDER_HANDLE[CURSOR] = 0
    ENDIF
ENDREPEAT
GOSUB UpdateActiveFlag
RETURN

//----------------------------------------------------------------------------
// UpdateActiveFlag - drop OPT_ANY_ACTIVE once every slot is free again, so the
// main loop stops paying for a release pass that has nothing to release.
// clobbers: CURSOR (26@), ROLL (27@)
//----------------------------------------------------------------------------
UpdateActiveFlag:
ROLL = 0
REPEAT MAX_DEFENDERS CURSOR
    IF DEFENDER_HANDLE[CURSOR] > 0
        ROLL = 1
    ENDIF
ENDREPEAT
IF ROLL = 0
    BIT_NOT OPT_ANY_ACTIVE ROLL
    BIT_AND ROLL OPTIONS OPTIONS
ENDIF
RETURN

//----------------------------------------------------------------------------
// ReleaseOneDefender - put DEFENDER (31@) back to being a normal pedestrian.
//
// Every handle used here came from the *_NO_SAVE variant of the random char
// opcodes, so this script never owned a reference to those peds: there is
// nothing to leak and no MARK_CHAR_AS_NO_LONGER_NEEDED to call.  The 2011
// original instead created a CGroup per assault and never removed it.
//----------------------------------------------------------------------------
ReleaseOneDefender:
IF NOT DEFENDER > 0
    RETURN
ENDIF
IF NOT DOES_CHAR_EXIST DEFENDER
    RETURN
ENDIF
IF IS_CHAR_DEAD DEFENDER
    RETURN
ENDIF
CLEAR_CHAR_TASKS_IMMEDIATELY DEFENDER
SET_CHAR_KEEP_TASK DEFENDER 0
SET_CHAR_ACCURACY DEFENDER DEFAULT_ACCURACY
RETURN

// gta3sc appends TERMINATE_THIS_CUSTOM_SCRIPT by itself when building a
// custom script (--cs), so it is deliberately not written here.
}

SCRIPT_END

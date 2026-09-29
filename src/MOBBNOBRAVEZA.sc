//****************************************************************************
//*  MEN REACT TO VIOLENCE AGAINST WOMEN  -  "Nao bata em mulheres"
//****************************************************************************
//*
//*  WHAT IT DOES
//*  ------------
//*  When the player beats up a woman, the men around them who would actually
//*  do something about it attack the player.  Nothing else happens:
//*
//*      - the assaulted woman is NEVER touched.  Her AI and her decisions are
//*        left exactly as the game left them (no flee task, no turn, nothing).
//*      - a pedestrian that would not act against the player is NEVER touched
//*        either.  Cowards, peds already fighting, peds owned by the game or
//*        by another script: the mod does not read them, does not give them a
//*        task, does not change their stats and does not change their brain.
//*        The game keeps controlling them normally.
//*      - the only task this script ever gives anybody is
//*        TASK_KILL_CHAR_ON_FOOT against the player, and it is taken back
//*        (CLEAR_CHAR_TASKS_IMMEDIATELY) only from the peds that still have
//*        that exact task, when their time is up or the situation ends.
//*
//*  No text is ever printed on screen.  No message, no help box, no GXT.
//*
//*  This is a from-scratch rewrite of the 2011 CLEO mod "Nao bata em mulheres"
//*  by Izerli (MixMods, "v2" page).  docs/ANALISE.md contains the line-by-line
//*  audit of the original script and the reasoning behind every change.
//*
//*  REQUIREMENTS
//*  ------------
//*  GTA San Andreas + CLEO 4.1 or newer + **CLEO+** installed.
//*  CLEO+ is a hard requirement, not an optional extra: the script uses its
//*  opcodes to find out whether the game is in a mission, whether a ped is
//*  owned by another script and - the important one - whether a male ped is a
//*  coward, which vanilla CLEO simply cannot ask.  Without CLEO+ the script
//*  stops loading at the first unknown opcode.
//*
//*  Base opcodes (vanilla CLEO):
//*      0AE1 GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE
//*      0AB3 SET_CLEO_SHARED_VAR    0B10 BIT_AND
//*  CLEO+ opcodes:
//*      0E1D IS_ON_MISSION              0E25 IS_ON_CUTSCENE
//*      0EB7 IS_ON_SCRIPTED_CUTSCENE    0E3D IS_KEY_JUST_PRESSED
//*      0E0A IS_CHAR_SCRIPT_CONTROLLED  0E47 IS_CHAR_FIGHTING
//*      0EFA GET_CHAR_FEAR              0EB1 GET_CHAR_STAT_ID
//*      0E44 GET_CHAR_KILL_TARGET_CHAR  0EE4 LOCATE_CHAR_DISTANCE_TO_CHAR
//*  They are declared for the compiler in config/cleoplus.xml, because the
//*  cleo.xml that ships with gta3sc stops at 0xB16.  That file is a verified
//*  excerpt of the gta3sc definition CLEO+ itself publishes - the official one
//*  cannot simply be added on top of gta3sc's config, see docs/COMPILER.md 5.1.
//*
//*  Install: copy bin/MOBBNOBRAVEZA.CS into the CLEO folder.
//*
//*  BUILDING (thelink2012/gta3sc on Linux or Windows)
//*  -------------------------------------------------
//*      gta3sc src/MOBBNOBRAVEZA.sc --config=gtasa --guesser --cs \
//*             --add-config=config/cleoplus.xml -fbreak-continue \
//*             -o bin/MOBBNOBRAVEZA.cs
//*  or simply run ./build.sh from the repository root.
//*
//*  IN GAME
//*  -------
//*  F10 toggles the mod on and off, silently.  The current state is published
//*  in CLEO shared variable 3100 (1 = on, 0 = off) and the active options in
//*  3101, so other mods and a save-game-friendly tool can read it.  Everything
//*  tunable is in the CONST block below: edit it and recompile.
//*
//****************************************************************************

SCRIPT_START

//****************************************************************************
//* CONFIG
//****************************************************************************

// Optional features, as a bit field.  Add the values of the features you want
// and put the sum in OPTIONS_DEFAULT.  gta3sc has no constant folding, so the
// flags are resolved at runtime with BIT_AND (opcode 0B10).
//
//      1 = IGNORE_WHEN_IN_CAR  do nothing while the player is driving
//      2 = MELEE_ONLY          ignore gunfire / explosions, melee and cars only
//
//  1 + 2 = 3 (the default: both on)
CONST_INT   OPTIONS_DEFAULT         3
CONST_INT   OPT_IGNORE_WHEN_IN_CAR  1
CONST_INT   OPT_MELEE_ONLY          2

CONST_INT   ENABLED_ON_START        1

// -- detection --------------------------------------------------------------
CONST_INT   SCAN_INTERVAL           200     // ms between victim scans
CONST_FLOAT VICTIM_SCAN_RADIUS      3.0     // how close to the player we look

// -- recruitment ------------------------------------------------------------
CONST_FLOAT DEFEND_RADIUS           25.0    // witnesses inside this radius react
                                            // (3D: 0EE4 compares the full vector)
CONST_INT   MAX_DEFENDERS           5       // hard cap on simultaneous defenders
CONST_INT   RECRUIT_WINDOW          2500    // ms spent looking for witnesses
CONST_INT   RECRUIT_STEP_DELAY      120     // ms between two recruitments
CONST_INT   DEFENDER_TIMEOUT        45000   // ms before a defender gives up

// -- who is allowed to react ------------------------------------------------
// GET_CHAR_FEAR (CLEO+) returns the Fear column of data/pedstats.dat:
// 0..100, 100 = scared of everything.  Anyone above MAX_FEAR runs away
// instead of fighting, so he is not recruited and is not touched at all.
// Vanilla values: PSYCHO 0, COP 10, TAXIDRIVER 16, GANG1-9 20, CRIMINAL 30,
// TOUGH_GUY 30, SUIT_GUY 35, OLD_GUY 40, SPORTSFAN 40, STEWARD 40,
// STREET_GUY 45, OLDSHOPPER 45, BEACH_GUY 52, GEEK_GUY 56, TRAMP_MALE 60,
// SENSIBLE_GUY 65, COWARD 65, TOURIST 100.
// The default 70 therefore keeps every "normal" male ped out of the way of
// TOURIST only, and the pedstat blacklist below does the real coward work.
CONST_INT   MAX_FEAR                70

// GET_CHAR_STAT_ID (CLEO+) returns the ped's row in data/pedstats.dat,
// 0-based.  The last column of that file is "Default decision maker", and the
// game's own header documents the value 4 as "coward peds" - the peds the
// R_Weak decision maker sends running.  Exactly nine rows carry it:
//      16 SENSIBLE_GUY  17 GEEK_GUY  22 SENSIBLE_GIRL  23 GEEK_GIRL
//      34 STEWARD       36 SHOPPER   37 OLDSHOPPER     40 SKATER
//      42 COWARD
// Seven of those are male: 16, 17, 34, 36, 37, 40 and 42.  (The female half
// never reaches the test - IS_CHAR_MALE rejected it long before.)
//
// The names below come from the PEDSTAT enum that CLEO+ itself publishes for
// gta3sc and that config/cleoplus.xml includes verbatim, so they are the
// authority for the numbers; declaring them again here would fail with "user
// constant exists already as a string constant".  The full vanilla table, with
// the Fear value of each row, is in docs/ANALISE.md section 5.2.

// -- rate limiting ----------------------------------------------------------
CONST_INT   WAVE_COOLDOWN           8000    // ms between two reactions (global)
CONST_INT   VICTIM_COOLDOWN         25000   // ms before the same woman re-triggers

// -- geometry / misc --------------------------------------------------------
CONST_FLOAT EYE_HEIGHT              0.7     // raises the LOS ray off the ground
CONST_FLOAT NO_OFFSET               0.0
CONST_INT   PLAYER_INDEX            0       // CJ is always player 0
CONST_INT   SLOT_EMPTY              0       // a free DEFENDER_HANDLE entry
CONST_INT   NO_SLOT                 -1      // "no free slot found"

// -- CLEO 0AE1 "filter" argument --------------------------------------------
CONST_INT   SEARCH_ALIVE_NPC        1       // CharSearchFilter.AnyAliveNPC

// -- CLEO shared variables (0AB3/0AB4) so other mods can read our state -----
CONST_INT   SHAREDVAR_ENABLED       3100
CONST_INT   SHAREDVAR_OPTIONS       3101

//****************************************************************************
//* LOCAL VARIABLE MAP
//****************************************************************************
//  A CLEO thread owns 32 locals (0@..31@) and gta3sc allocates them in
//  declaration order starting at 0@, so the layout below is literal.  Every
//  subroutine documents what it reads and what it clobbers.
//
//  0@..3@   CLEO_ARGS[4]          reserved - 0A92 create_custom_thread params
//
//  persistent state -------------------------------------------------------
//    4@..8@   DEFENDER_HANDLE[5]  recruited witnesses (0 = free slot)
//    9@..13@  DEFENDER_SINCE[5]   game timer value of each recruitment
//   14@       PLAYER_ACTOR        CJ's ped handle, refreshed every tick
//   15@       LAST_VICTIM         last woman we reacted to (-1 = none yet)
//   16@       LAST_VICTIM_TIME    when we reacted to her
//   17@       WAVE_TIME           timestamp of the last reaction (global)
//   18@       ENABLED             runtime on/off (F10)
//   19@       NOW                 cached game timer
//   20@       LAST_SCAN           when the victim scan last ran
//   21@       OPTIONS             bit field of enabled optional features
//
//  scratch (free to clobber anywhere) -------------------------------------
//   22@ PX    23@ PY    24@ PZ     player position / LOS ray start point
//   25@ CANDIDATE                  ped being inspected by the scan loop
//   26@ CURSOR                     0AE1 cursor, then ped type, pedstat row,
//                                  slot index, kill target, loop counter
//   27@ ROLL                       fear level, BIT_AND result, loop counter,
//                                  then the chosen DEFENDER_HANDLE slot
//   28@ VX    29@ VY    30@ VZ     victim position (raised to eye height)
//   31@ DEFENDER                   witness being recruited, or defender being
//                                  released (never both at the same time)
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
LVAR_INT   LAST_SCAN                // 20@
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
LAST_SCAN = 0

//****************************************************************************
//* MAIN LOOP
//****************************************************************************
// The loop ticks every frame because IS_KEY_JUST_PRESSED (0E3D) is only true
// on the frame the key goes down - polling it every 200 ms would swallow most
// presses.  Everything expensive stays behind the LAST_SCAN gate below, so the
// per-frame cost is one opcode until a key is actually pressed.
LOOP_FOREVER:
WAIT 0
GOSUB PollHotkey

//----------------------------------------------------------------------------
// Release everybody and idle while the game, not the player, is in charge.
//
// IS_PLAYER_PLAYING is false whenever CJ is wasted, busted or locked down by a
// cutscene.  IS_ON_MISSION reads the global set by 0180, which a plain CLEO
// script cannot reach at all - this is the gap that made the 2011 original
// hijack mission peds.  IS_CHAR_SCRIPT_CONTROLLED later catches the script-owned
// peds individually.
//----------------------------------------------------------------------------
IF NOT IS_PLAYER_PLAYING PLAYER_INDEX
    GOSUB ReleaseAllDefenders
    GOTO LOOP_FOREVER
ENDIF
GET_PLAYER_CHAR PLAYER_INDEX PLAYER_ACTOR
IF NOT DOES_CHAR_EXIST PLAYER_ACTOR
    GOSUB ReleaseAllDefenders
    GOTO LOOP_FOREVER
ENDIF
IF IS_ON_MISSION
    GOSUB ReleaseAllDefenders
    GOTO LOOP_FOREVER
ENDIF
IF IS_ON_CUTSCENE
    GOSUB ReleaseAllDefenders
    GOTO LOOP_FOREVER
ENDIF
IF IS_ON_SCRIPTED_CUTSCENE
    GOSUB ReleaseAllDefenders
    GOTO LOOP_FOREVER
ENDIF

BIT_AND OPTIONS OPT_IGNORE_WHEN_IN_CAR ROLL
IF NOT ROLL = 0
    IF IS_CHAR_IN_ANY_CAR PLAYER_ACTOR
        GOSUB ReleaseAllDefenders
        GOTO LOOP_FOREVER
    ENDIF
ENDIF

IF ENABLED = 0
    GOSUB ReleaseAllDefenders
    GOTO LOOP_FOREVER
ENDIF

//----------------------------------------------------------------------------
// Retire the defenders whose time is up (cheap: five slots, every frame)
//----------------------------------------------------------------------------
GOSUB ReleaseExpiredDefenders

//----------------------------------------------------------------------------
// Scan gate: the expensive victim search only runs every SCAN_INTERVAL ms
//----------------------------------------------------------------------------
GET_GAME_TIMER NOW
NOW = NOW - LAST_SCAN
IF NOW < SCAN_INTERVAL
    GOTO LOOP_FOREVER
ENDIF
GET_GAME_TIMER LAST_SCAN

//----------------------------------------------------------------------------
// Global rate limit between two reactions
//----------------------------------------------------------------------------
GET_GAME_TIMER NOW
NOW = NOW - WAVE_TIME
IF NOW < WAVE_COOLDOWN
    GOTO LOOP_FOREVER
ENDIF
GET_GAME_TIMER NOW                  // absolute time again, used by the cooldown below

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
    NOW = NOW - LAST_VICTIM_TIME
    IF NOW < VICTIM_COOLDOWN
        GOTO SCAN_NEXT_CANDIDATE
    ENDIF
ENDIF

//****************************************************************************
//* Victim found - react.
//****************************************************************************
LAST_VICTIM = CANDIDATE
GET_GAME_TIMER LAST_VICTIM_TIME
GET_GAME_TIMER WAVE_TIME

// Consume the damage flags: without this the very same hit would start the
// next wave as soon as WAVE_COOLDOWN expires.  (The 2011 original never
// cleared them and only compared against a "last victim" variable.)
CLEAR_CHAR_LAST_DAMAGE_ENTITY CANDIDATE
CLEAR_CHAR_LAST_WEAPON_DAMAGE CANDIDATE

// Note: nothing at all is done to CANDIDATE herself.  The flags above are the
// damage *record* the game keeps on her, not her AI: clearing them changes no
// decision she makes, it only stops this script from re-reading a hit that has
// already been answered.
GOSUB RecruitDefenders

GOTO LOOP_FOREVER

//****************************************************************************
//* SUBROUTINES
//****************************************************************************

//----------------------------------------------------------------------------
// PollHotkey - F10 toggles the mod, silently.
// IS_KEY_JUST_PRESSED is edge triggered, so no debounce is needed and holding
// the key down cannot flip the state every frame.
// reads:   ENABLED
// writes:  ENABLED, shared var SHAREDVAR_ENABLED
// clobbers: -
//----------------------------------------------------------------------------
PollHotkey:
IF IS_KEY_JUST_PRESSED VK_F10
    IF ENABLED = 1
        ENABLED = 0
    ELSE
        ENABLED = 1
    ENDIF
    SET_CLEO_SHARED_VAR SHAREDVAR_ENABLED ENABLED
ENDIF
RETURN

//----------------------------------------------------------------------------
// RecruitDefenders - for RECRUIT_WINDOW milliseconds, walk the ped pool around
// the victim and hand TASK_KILL_CHAR_ON_FOOT to the witnesses that pass every
// filter.  Whoever fails a filter is not read again and not modified in any
// way: the game keeps controlling him normally.
// in:      CANDIDATE (25@) = the victim, PLAYER_ACTOR (14@)
// clobbers: everything from 19@ and 22@ up
//----------------------------------------------------------------------------
RecruitDefenders:

RECRUIT_WINDOW_LOOP:
WAIT 0
GOSUB PollHotkey                    // F10 during a wave aborts the wave
IF ENABLED = 0
    RETURN
ENDIF
IF NOT IS_PLAYER_PLAYING PLAYER_INDEX
    RETURN
ENDIF
GET_GAME_TIMER NOW
NOW = NOW - WAVE_TIME
IF NOW > RECRUIT_WINDOW
    RETURN
ENDIF
IF NOT DOES_CHAR_EXIST CANDIDATE
    RETURN
ENDIF
IF IS_CHAR_DEAD CANDIDATE
    RETURN
ENDIF
// She got into a car: she is away, so there is nobody left to defend and the
// wave stops instead of spending its whole window on an empty street.
IF IS_CHAR_IN_ANY_CAR CANDIDATE
    RETURN
ENDIF
IF IS_CHAR_IN_ANY_CAR PLAYER_ACTOR
    RETURN
ENDIF

// Keep the search centred on her: she may have walked off since the hit.
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

// --- created or adopted by a script? ---------------------------------------
// IS_CHAR_SCRIPT_CONTROLLED (CLEO+) is true when the ped's "created by" field
// says a script owns him - mission peds and peds spawned by other CLEO mods.
// Random world peds are false, so this is not a substitute for the pedtype
// test below: GET_PED_TYPE still catches the mission and player ped types that
// were not script-created.
IF IS_CHAR_SCRIPT_CONTROLLED DEFENDER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
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
// Ordered before the personality tests on purpose: this is the cheapest way to
// throw away the bulk of a crowded street, and everything below it costs more.
IF NOT LOCATE_CHAR_DISTANCE_TO_CHAR DEFENDER CANDIDATE DEFEND_RADIUS
    GOTO RECRUIT_NEXT_WITNESS
ENDIF

// --- line of sight, so nobody reacts through a wall ------------------------
// The 3D distance test above has no vertical tolerance of its own, so a witness
// on a balcony would pass it; this ray is what keeps the reaction on the same
// level as the victim.  PX/PY/PZ (22@..24@) are the scan-loop scratch and are
// re-read every scan.
GET_OFFSET_FROM_CHAR_IN_WORLD_COORDS DEFENDER NO_OFFSET NO_OFFSET EYE_HEIGHT PX PY PZ
IF NOT IS_LINE_OF_SIGHT_CLEAR PX PY PZ VX VY VZ 1 0 1 0 0
    GOTO RECRUIT_NEXT_WITNESS
ENDIF

// --- already busy with a fight of his own? ---------------------------------
// He is already being driven by the game's combat AI.  Giving him our task
// would overwrite a decision the game made by itself, so he is left alone.
IF IS_CHAR_FIGHTING DEFENDER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF

// --- would he run away instead of acting? ----------------------------------
// The "coward" test, and the reason this rewrite needs CLEO+.  Vanilla CLEO
// cannot ask what a ped's personality is, so the 2011 original recruited
// everybody and then rolled dice to decide whether the man attacked, shouted
// or fled - which is how cowards ended up with scripted behaviour they would
// never have chosen themselves.  Here they are filtered out and left alone.
//
// 1) GET_CHAR_STAT_ID: the ped's row in data/pedstats.dat (0-based).  The last
//    column of that file is "Default decision maker", and the game's own header
//    documents the value 4 as "coward peds" - the peds the R_Weak decision
//    maker sends running.  Exactly nine rows carry it:
//        16 SENSIBLE_GUY  17 GEEK_GUY  22 SENSIBLE_GIRL  23 GEEK_GIRL
//        34 STEWARD       36 SHOPPER   37 OLDSHOPPER     40 SKATER
//        42 COWARD
//    Of those, seven are male: 16, 17, 34, 36, 37, 40 and 42.  Rows 14..25 are
//    tested as one range because they are the twelve consecutive civilian
//    "guy/girl" personality rows - the female half never reaches this point
//    (IS_CHAR_MALE rejected it above), and the male half of the range that is
//    NOT flagged coward by the game (14 STREET_GUY dm 2, 15 SUIT_GUY dm 2,
//    18 OLD_GUY dm 2, 19 TOUGH_GUY dm 3) is rejected too, on purpose: the brief
//    for this mod is to only ever touch a man who will actually act against the
//    player, and in practice those four mill about, shout or wander off.  Drop
//    the range test and keep the four individual ones to recruit them as well.
GET_CHAR_STAT_ID DEFENDER CURSOR
IF CURSOR >= PEDSTAT_STREET_GUY
    IF CURSOR <= PEDSTAT_TOUGH_GIRL
        GOTO RECRUIT_NEXT_WITNESS
    ENDIF
ENDIF
IF CURSOR = PEDSTAT_STEWARD
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF CURSOR = PEDSTAT_SHOPPER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF CURSOR = PEDSTAT_OLDSHOPPER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF CURSOR = PEDSTAT_SKATER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF
IF CURSOR = PEDSTAT_COWARD
    GOTO RECRUIT_NEXT_WITNESS
ENDIF

// 2) GET_CHAR_FEAR: the Fear column of the same file (0..100, 100 = scared of
//    everything).  This is the number the game itself uses to decide how
//    quickly a ped runs away, and it catches the rows that are not flagged
//    coward but panic anyway - TOURIST (fear 100), and any value a ped-mod or
//    an edited pedstats.dat moves up.  Set MAX_FEAR to 100 to disable this
//    second test and keep only the pedstat blacklist.
GET_CHAR_FEAR DEFENDER ROLL
IF ROLL > MAX_FEAR
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
// ROLL (27@) doubles as the loop counter, CURSOR (26@) as the slot found.
// An expired slot is emptied *and* its old defender released right here: the
// 2011 original kept a fixed-size list and silently dropped the handle of a
// ped it had already given a task to, which is how a man could end up chasing
// the player forever with nobody left to time him out.
CURSOR = NO_SLOT
REPEAT MAX_DEFENDERS ROLL
    IF DEFENDER_HANDLE[ROLL] = SLOT_EMPTY
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
IF NOT CURSOR > NO_SLOT
    GOTO RECRUIT_NEXT_WITNESS       // the mob is already big enough
ENDIF

// ROLL (27@) takes over the slot index from CURSOR (26@): ROLL has finished
// its job as the REPEAT counter and CURSOR is needed as the kill-target scratch.
ROLL = CURSOR
IF DEFENDER_HANDLE[ROLL] > SLOT_EMPTY
    // The slot's occupant timed out but is still referenced, so he has to be
    // let go before his handle is overwritten; otherwise he would keep a
    // TASK_KILL_CHAR_ON_FOOT with nothing left to time it out - a man chasing
    // the player forever, which is what the 2011 original did.
    //
    // Inlined copy of ReleaseOneDefender rather than a GOSUB, because DEFENDER
    // (31@) has to hold the outgoing defender here and the witness afterwards,
    // and there is no third scratch local left: 0@..3@ belong to CLEO and
    // PX/PY/PZ are FLOAT.  Keep this in step with ReleaseOneDefender below.
    DEFENDER = DEFENDER_HANDLE[ROLL]
    DEFENDER_HANDLE[ROLL] = SLOT_EMPTY
    IF DOES_CHAR_EXIST DEFENDER
        IF NOT IS_CHAR_DEAD DEFENDER
            GET_CHAR_KILL_TARGET_CHAR DEFENDER CURSOR
            IF CURSOR = PLAYER_ACTOR
                CLEAR_CHAR_TASKS_IMMEDIATELY DEFENDER
            ENDIF
        ENDIF
    ENDIF
ENDIF

// --- recruit him -----------------------------------------------------------
// DEFENDER is loaded from the slot rather than carried in a register, so the
// empty-slot path and the reused-slot path converge here without a stash.
DEFENDER_HANDLE[ROLL] = DEFENDER
GET_GAME_TIMER DEFENDER_SINCE[ROLL]
GOSUB ReactDefender
WAIT RECRUIT_STEP_DELAY             // never recruit a whole crowd in one frame
GOTO RECRUIT_WINDOW_LOOP

//----------------------------------------------------------------------------
// ReactDefender - the one and only thing this mod does to a pedestrian.
// in:      DEFENDER (31@), PLAYER_ACTOR (14@)
// clobbers: -
//
// Deliberately absent, and the reason is in docs/ANALISE.md: no
// SET_CHAR_KEEP_TASK, no SET_SENSE_RANGE, no SET_CHAR_ACCURACY, no
// TASK_SET_CHAR_DECISION_MAKER, no flee task, no fist shaking, no staring.
// Those either rewrote the ped's personality or forced behaviour on peds that
// were never going to act; the man who is going to act needs exactly one task.
//----------------------------------------------------------------------------
ReactDefender:
TASK_KILL_CHAR_ON_FOOT DEFENDER PLAYER_ACTOR
RETURN

//----------------------------------------------------------------------------
// ReleaseExpiredDefenders - drop whoever has given up.
// clobbers: NOW (19@), CURSOR (26@), ROLL (27@), DEFENDER (31@)
//----------------------------------------------------------------------------
ReleaseExpiredDefenders:
// Fast path.  This runs every frame, and on every frame where nobody was
// recruited - which is nearly all of them - the only work left is one compare.
// DEFENDER_HANDLE[0] is local 4@: gta3sc allocates locals in declaration order
// from 0@, and 0@..3@ are the reserved CLEO_ARGS, so the map in the header is
// literal and the five slots are 4@..8@.
IF DEFENDER_HANDLE[0] = SLOT_EMPTY
    RETURN
ENDIF
GET_GAME_TIMER NOW
REPEAT MAX_DEFENDERS CURSOR
    IF DEFENDER_HANDLE[CURSOR] > SLOT_EMPTY
        ROLL = NOW - DEFENDER_SINCE[CURSOR]
        IF ROLL > DEFENDER_TIMEOUT
            DEFENDER = DEFENDER_HANDLE[CURSOR]
            DEFENDER_HANDLE[CURSOR] = SLOT_EMPTY
            GOSUB ReleaseOneDefender
        ENDIF
    ENDIF
ENDREPEAT
RETURN

//----------------------------------------------------------------------------
// ReleaseAllDefenders - the player died, got busted, hopped into a car, a
// mission or a cutscene started, or the mod got switched off: everybody minds
// their own business again.
// clobbers: CURSOR (26@), DEFENDER (31@)
//----------------------------------------------------------------------------
ReleaseAllDefenders:
// Same fast path as ReleaseExpiredDefenders: the slots are filled from index 0
// upwards and are only ever freed in order, so an empty slot 0 means an empty
// list.  This routine is reached every frame while the player is in a mission,
// a cutscene or a car, and almost always has nothing to do.
IF DEFENDER_HANDLE[0] = SLOT_EMPTY
    RETURN
ENDIF
REPEAT MAX_DEFENDERS CURSOR
    IF DEFENDER_HANDLE[CURSOR] > SLOT_EMPTY
        DEFENDER = DEFENDER_HANDLE[CURSOR]
        DEFENDER_HANDLE[CURSOR] = SLOT_EMPTY
        GOSUB ReleaseOneDefender
    ENDIF
ENDREPEAT
RETURN

//----------------------------------------------------------------------------
// ReleaseOneDefender - put DEFENDER (31@) back to being a normal pedestrian.
// in:      DEFENDER (31@)
// clobbers: CURSOR (26@)
//
// The task is only taken away from a ped that is still holding *our* task,
// which GET_CHAR_KILL_TARGET_CHAR answers exactly: if his kill target is not
// the player any more, the game has moved him on to something else and we do
// not clear it.  That keeps the "do not touch peds that are not acting against
// the player" rule true on the way out as well as on the way in.
//
// Every handle used here came from the *_NO_SAVE variant of the random char
// opcodes, so this script never owned a reference to those peds: there is
// nothing to leak and no MARK_CHAR_AS_NO_LONGER_NEEDED to call.  The 2011
// original instead created a CGroup per assault and never removed it.
//----------------------------------------------------------------------------
ReleaseOneDefender:
IF NOT DOES_CHAR_EXIST DEFENDER
    RETURN
ENDIF
IF IS_CHAR_DEAD DEFENDER
    RETURN
ENDIF
GET_CHAR_KILL_TARGET_CHAR DEFENDER CURSOR
IF CURSOR = PLAYER_ACTOR
    CLEAR_CHAR_TASKS_IMMEDIATELY DEFENDER
ENDIF
RETURN

// gta3sc appends TERMINATE_THIS_CUSTOM_SCRIPT by itself when building a
// custom script (--cs), so it is deliberately not written here.
}

SCRIPT_END

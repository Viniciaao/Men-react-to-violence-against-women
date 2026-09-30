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
//*        by another script: the mod does not give them a task, does not change
//*        their stats and does not change their brain.  The game keeps
//*        controlling them normally.
//*      - the only task this script ever gives anybody is
//*        TASK_KILL_CHAR_ON_FOOT against the player, and it is taken back
//*        (CLEAR_CHAR_TASKS_IMMEDIATELY) only from the peds that still have
//*        that exact task, when their time is up or the situation ends.
//*
//*  The mod is ALWAYS ACTIVE.  There is no hotkey and no key handling of any
//*  kind: nothing is read from the keyboard, so nothing can conflict with the
//*  game, with another mod or with ScrDebug's own keys.  The on/off state is
//*  still published in CLEO shared variable 3100 for other mods to read.
//*
//*  The only text this script writes is DEBUG text (0662/0663), which the
//*  retail game ignores completely.  See the DEBUG OUTPUT section below.
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
//*  Base opcodes (vanilla SA, no extension needed):
//*      0AE1 GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE
//*      0AB3 SET_CLEO_SHARED_VAR    0B10 BIT_AND
//*      0662 WRITE_DEBUG            0663 WRITE_DEBUG_WITH_INT
//*      0485 IS_PC_VERSION, aliased to TRUE / RETURN_TRUE
//*      059A IS_AUSTRALIAN_GAME, aliased to RETURN_FALSE
//*  CLEO+ opcodes:
//*      0E1D IS_ON_MISSION              0E25 IS_ON_CUTSCENE
//*      0EB7 IS_ON_SCRIPTED_CUTSCENE    0E0A IS_CHAR_SCRIPT_CONTROLLED
//*      0E47 IS_CHAR_FIGHTING           0EFA GET_CHAR_FEAR
//*      0EB1 GET_CHAR_STAT_ID           0E44 GET_CHAR_KILL_TARGET_CHAR
//*      0EE4 LOCATE_CHAR_DISTANCE_TO_CHAR
//*  The CLEO+ ones are declared for the compiler in config/cleoplus.xml because
//*  the cleo.xml that ships with gta3sc stops at 0xB16.  That file is a verified
//*  excerpt of the gta3sc definition CLEO+ itself publishes - the official one
//*  cannot simply be added on top of gta3sc's config, see docs/COMPILER.md 5.1.
//*
//*  Install: copy bin/MOBBNOBRAVEZA.CS into the CLEO folder.
//*
//*  DEBUG OUTPUT
//*  ------------
//*  WRITE_DEBUG and friends are Rockstar's own script-debugging opcodes, left in
//*  the retail game as no-ops.  Two mods re-activate them, and with either one
//*  installed this script's debug lines appear on screen:
//*
//*      ScrDebug (Deji)     https://www.mixmods.com.br/2017/06/sa-scrdebug/
//*      CLEO5 + DebugUtils  set DebugUtils.General.LegacyDebugOpcodes = 1
//*
//*  Without one of them the opcodes cost a parameter skip and nothing else: no
//*  text, no log, no gameplay difference.  That is exactly why the 2011 original
//*  could ship its "write_debug" credit line to end users, and why this script
//*  can afford to be chatty.
//*
//*  What gets written:
//*      - one line at boot, so an installed mod is visible as installed;
//*      - one status block every SCAN_INTERVAL while the game is playable
//*        (defenders on the field, seconds left on the wave cooldown);
//*      - one line per event: victim detected, man recruited, man turned down
//*        because the mob is full, a defender timing out, and the whole mob
//*        being released (with how many were released).
//*
//*  Two formatting notes, both from how the opcodes are actually implemented
//*  (CLEO5's DebugUtils, which follows Rockstar's original):
//*      WRITE_DEBUG        "text"          ->  text
//*      WRITE_DEBUG_WITH_INT  "Label" 5    ->  Label: 5
//*  So the string of the _WITH_INT form is a LABEL, not a format string - there
//*  is no %d to write, and the ": " separator is added by the game side.
//*
//*  BUILDING (thelink2012/gta3sc on Linux or Windows)
//*  -------------------------------------------------
//*      gta3sc src/MOBBNOBRAVEZA.sc --config=gtasa \
//*             --add-config=config/cleoplus.xml --guesser --cs \
//*             -fbreak-continue -fno-entity-tracking -o bin/MOBBNOBRAVEZA.cs
//*  or simply run ./build.sh from the repository root.  -fno-entity-tracking is
//*  needed because gta3sc's entity checker loses the type of a variable that is
//*  fed from an array element, and DEFENDER_HANDLE[5] is a table of ped handles
//*  (docs/COMPILER.md, section 3.14).  It is a compile-time check only: the
//*  bytecode is the same with or without it.
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
//      4 = DEBUG_TEXT          write the debug lines described in the header
//
//  1 + 2 + 4 = 7 (the default: everything on)
CONST_INT   OPTIONS_DEFAULT         7
CONST_INT   OPT_IGNORE_WHEN_IN_CAR  1
CONST_INT   OPT_MELEE_ONLY          2
CONST_INT   OPT_DEBUG_TEXT          4

// -- detection --------------------------------------------------------------
CONST_INT   SCAN_INTERVAL           200     // ms between victim scans

// The debug status line is throttled to one message per period.  ScrDebug keeps
// a rolling list of the last twelve on screen, so an unthrottled line would push
// the event messages off the screen before they could be read.  The window has
// to stay wider than SCAN_INTERVAL or a tick could skip it; see DebugStatus.
CONST_INT   STATUS_PERIOD           3000    // ms between two debug status lines
CONST_INT   STATUS_WINDOW           300     // ms of the period that may print
CONST_FLOAT VICTIM_SCAN_RADIUS      3.0     // how close to the player we look,
                                            // with MELEE_ONLY on (see below)
CONST_FLOAT GUNFIRE_SCAN_RADIUS     50.0    // ... and with MELEE_ONLY off: a fist
                                            // or a bumper lands within arm's
                                            // reach, a bullet does not.  Lower it
                                            // if the per-tick scan ever feels
                                            // expensive in a crowded street.

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

// The coward blacklist itself uses the PEDSTAT_* names of the enum that CLEO+
// publishes for gta3sc and that config/cleoplus.xml includes verbatim, so the
// numbers are sourced rather than hand-derived.  They are listed, with the
// reasoning, right where the test is performed (see IsCoward below) and in
// docs/ANALISE.md section 5.2.

// -- rate limiting ----------------------------------------------------------
CONST_INT   WAVE_COOLDOWN           8000    // ms between two reactions (global)
CONST_INT   VICTIM_COOLDOWN         25000   // ms before the same woman re-triggers
CONST_INT   MS_PER_SECOND           1000    // only to report the cooldown in s

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
//   18@       NOW                 cached game timer
//   19@       OPTIONS             bit field of enabled optional features
//
//  scratch (free to clobber anywhere) -------------------------------------
//   20@ PX    21@ PY    22@ PZ     player position / LOS ray start point
//   23@ CANDIDATE                  ped being inspected by the scan loop
//   24@ CURSOR                     two unrelated jobs, and the name only fits
//                                  the second one.  In the 0AE1 scan loops it is
//                                  the FIND-NEXT FLAG: 0 starts a fresh search,
//                                  1 continues past the ped just returned (SBL
//                                  calls the parameter findNext:bool, not a
//                                  cursor - it is never an index).  In the
//                                  REPEAT loops and the slot scan it is a real
//                                  index 0..MAX_DEFENDERS-1.  ReleaseOneDefender
//                                  takes it in the index meaning and leaves it
//                                  alone; both callers still park it around that
//                                  call, because the REPEAT counter is what
//                                  section 3.12 of docs/COMPILER.md is about.
//   25@ ROLL                       fear level, BIT_AND result, loop counter,
//                                  then the chosen DEFENDER_HANDLE slot
//   26@ VX    27@ VY    28@ VZ     victim position (raised to eye height); VX is
//                                  the victim-scan radius while the main loop is
//                                  looking for her, which is before RecruitDef-
//                                  enders reads all three from her coordinates
//   29@ DEFENDER                   witness being recruited
//   30@ DBG_COUNT                  counter for the debug lines (defenders on the
//                                  field, how many were just released)
//
//  Nothing is needed for the release path: ReleaseOneDefender reads its slot
//  inline instead of taking a handle in DEFENDER, which is what lets the recruit
//  loop reuse an expired slot without a second scratch local.
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
LVAR_INT   NOW                      // 18@
LVAR_INT   OPTIONS                  // 19@

LVAR_FLOAT PX PY PZ                 // 20@..22@
LVAR_INT   CANDIDATE                // 23@
LVAR_INT   CURSOR                   // 24@
LVAR_INT   ROLL                     // 25@

LVAR_FLOAT VX VY VZ                 // 26@..28@
LVAR_INT   DEFENDER                 // 29@
LVAR_INT   DBG_COUNT                // 30@

//----------------------------------------------------------------------------
// Boot
//----------------------------------------------------------------------------
OPTIONS = OPTIONS_DEFAULT
LAST_VICTIM = -1
LAST_VICTIM_TIME = 0
WAVE_TIME = 0
SET_CLEO_SHARED_VAR SHAREDVAR_ENABLED 1     // always active: there is no toggle
SET_CLEO_SHARED_VAR SHAREDVAR_OPTIONS OPTIONS

GOSUB DebugGate
IF DBG_COUNT = 1
    WRITE_DEBUG "MenReact loaded, always active"
ENDIF

//****************************************************************************
//* MAIN LOOP
//****************************************************************************
// WHILE TRUE is the alias of 0485 IS_PC_VERSION declared in config/cleoplus.xml
// (see that file and docs/COMPILER.md 3.12): always true on PC, so the loop only
// ever ends on a BREAK or by the script being terminated.  It replaces the
// "label + GOTO label" idiom the 2011 original had to use.
//
// One tick is SCAN_INTERVAL, not one frame.  Nothing here needs frame accuracy
// any more - the hotkey that did is gone - and the recruit window below keeps its
// own WAIT 0 inner loop for the part that does.
WHILE TRUE
WAIT SCAN_INTERVAL
GET_GAME_TIMER NOW

//----------------------------------------------------------------------------
// Retire the defenders whose time is up.  Cheap: one compare when the list is
// empty, which is nearly always.
//----------------------------------------------------------------------------
GOSUB ReleaseExpiredDefenders
GOSUB DebugStatus

//----------------------------------------------------------------------------
// Idle while the game, not the player, is in charge.
//
// IS_PLAYER_PLAYING is false whenever CJ is wasted, busted or locked down by a
// cutscene.  IS_ON_MISSION reads the global set by 0180, which a plain CLEO
// script cannot reach at all - this is the gap that made the 2011 original
// hijack mission peds.  IS_CHAR_SCRIPT_CONTROLLED later catches, one by one,
// the peds a script created or adopted.
//----------------------------------------------------------------------------
IF NOT IS_PLAYER_PLAYING PLAYER_INDEX
    GOSUB ReleaseAllDefenders
    CONTINUE
ENDIF
GET_PLAYER_CHAR PLAYER_INDEX PLAYER_ACTOR
IF NOT DOES_CHAR_EXIST PLAYER_ACTOR
    GOSUB ReleaseAllDefenders
    CONTINUE
ENDIF
IF IS_ON_MISSION
    GOSUB ReleaseAllDefenders
    CONTINUE                          // DebugStatus is what reports this
ENDIF
IF IS_ON_CUTSCENE
    GOSUB ReleaseAllDefenders
    CONTINUE
ENDIF
IF IS_ON_SCRIPTED_CUTSCENE
    GOSUB ReleaseAllDefenders
    CONTINUE
ENDIF

BIT_AND OPTIONS OPT_IGNORE_WHEN_IN_CAR ROLL
IF NOT ROLL = 0
    IF IS_CHAR_IN_ANY_CAR PLAYER_ACTOR
        GOSUB ReleaseAllDefenders
        CONTINUE
    ENDIF
ENDIF

//----------------------------------------------------------------------------
// Global rate limit between two reactions
//----------------------------------------------------------------------------
NOW = NOW - WAVE_TIME
IF NOW < WAVE_COOLDOWN
    CONTINUE
ENDIF
GET_GAME_TIMER NOW

//----------------------------------------------------------------------------
// Look for a woman the player has just hurt
//----------------------------------------------------------------------------
GET_CHAR_COORDINATES PLAYER_ACTOR PX PY PZ

// How far to look follows the MELEE_ONLY option: a 3 m scan would silently
// ignore every victim shot from further away, and the option would be a lie.
// VX (26@) is dead here - RecruitDefenders is the only other user of it and
// re-reads all three coordinates from the victim before it touches them.
VX = VICTIM_SCAN_RADIUS
BIT_AND OPTIONS OPT_MELEE_ONLY ROLL
IF ROLL = 0
    VX = GUNFIRE_SCAN_RADIUS
ENDIF

// 0AE1's fifth parameter is its "findNext" flag: 0 restarts the pool walk, 1
// continues after the ped it just handed out.  It is a literal at two call sites
// rather than a local, so that no scratch variable can ever corrupt it - see the
// same note in RecruitDefenders and docs/COMPILER.md 3.15.
SCAN_FIRST_CANDIDATE:
GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE PX PY PZ VX 0 SEARCH_ALIVE_NPC CANDIDATE
GOTO SCAN_GOT_CANDIDATE

SCAN_NEXT_CANDIDATE:
GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE PX PY PZ VX 1 SEARCH_ALIVE_NPC CANDIDATE

SCAN_GOT_CANDIDATE:
IF CANDIDATE = -1                   // 0AE1 yields -1 when the pool is exhausted
    CONTINUE                        // nothing around: wait SCAN_INTERVAL, rescan
ENDIF

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
    // ROLL, not NOW: NOW has to stay a timestamp for the rest of this scan
    // (and for the next tick), and ROLL is dead once the MELEE_ONLY block above
    // has finished with it.
    ROLL = NOW - LAST_VICTIM_TIME
    IF ROLL < VICTIM_COOLDOWN
        GOTO SCAN_NEXT_CANDIDATE     // same woman, still inside her cooldown
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
GOSUB DebugGate
IF DBG_COUNT = 1
    WRITE_DEBUG "MenReact: victim detected, recruiting"
ENDIF
GOSUB RecruitDefenders

ENDWHILE

//****************************************************************************
//* SUBROUTINES
//****************************************************************************

//----------------------------------------------------------------------------
// DebugGate - answers "should a debug line be written right now?".
// result:  DBG_COUNT (30@) = 1 when yes, 0 when no
// clobbers: DBG_COUNT (30@), ROLL (25@)
//
// WRITE_DEBUG (0662) is a no-op in the retail game, so the gate is not about
// cost - it is about the opposite case: somebody who DOES run ScrDebug or
// CLEO5's DebugUtils and wants the screen clean can clear OPT_DEBUG_TEXT.
//
// The caller's shape is always the same:
//
//      GOSUB DebugGate
//      IF DBG_COUNT = 1
//          WRITE_DEBUG "..."
//      ENDIF
//
// A text cannot be passed to a subroutine - GTA3script has no string locals, and
// a GOSUB argument lands in 0@.. which the CLEO_ARGS block owns - so the line
// has to be written at its own call site.  That is also why there is no
// per-rejection logging behind a RECRUIT_DEBUG flag: every filter would need its
// own label to stay readable, and the two counters in DebugStatus below already
// show whether the filters are rejecting everybody.
//----------------------------------------------------------------------------
DebugGate:
DBG_COUNT = 0
BIT_AND OPTIONS OPT_DEBUG_TEXT ROLL
IF NOT ROLL = 0
    DBG_COUNT = 1
ENDIF
RETURN

//----------------------------------------------------------------------------
// DebugStatus - one status line, at most once per STATUS_PERIOD milliseconds.
// clobbers: DBG_COUNT (30@), CURSOR (24@), ROLL (25@), DEFENDER (29@)
//
// It must NOT touch NOW (18@), and that is not a stylistic preference: this
// routine is called before the wave-cooldown gate in the main loop, and an
// earlier version computed "seconds left" in NOW and clamped it to zero - which
// left the gate reading NOW = 0, so "0 < 8000" was always true and the script
// CONTINUEd every single tick without ever scanning for a victim.  The mod
// looked loaded and did nothing at all.  The scratch is DEFENDER instead, which
// is dead here (the only call site is the top of the main loop, far from the
// recruit window that is the only place DEFENDER means something).
//
// The line chosen is the first that applies: defenders on the field, then "a
// mission is running", then the wave cooldown, then a heartbeat that says the
// script is alive and watching.  The heartbeat exists because of that bug - a
// silent screen cannot be told apart from a script that never loaded.
//
// ScrDebug draws 0662/0663/0664 as a rolling list of the last twelve messages
// down the side of the screen (its INI: ShowRecentMessages, MaxRecentMessages,
// WriteToDebugFile), and its string parameter is 40 characters.  Both facts are
// why this routine is throttled and why every literal here is short: at five
// ticks per second an unthrottled status would scroll the event lines - victim
// found, defender recruited - off the screen before they could be read.
//----------------------------------------------------------------------------
DebugStatus:
BIT_AND OPTIONS OPT_DEBUG_TEXT ROLL
IF ROLL = 0
    RETURN
ENDIF

// The throttle.  GTA3script has no modulo operator and no free local to remember
// the last print in, so it is derived from the timer instead: print only while
// the timer sits inside the first STATUS_WINDOW milliseconds of a period.  The
// window is wider than SCAN_INTERVAL, so no period can be skipped by a tick.
// The remainder goes into DBG_COUNT and not into ROLL because gta3sc rejects
// "VAR1 = THING - VAR1" (docs/COMPILER.md 3.13): it has to expand the form into
// a copy followed by a subtraction, and the copy would already have destroyed
// the subtrahend.  DBG_COUNT is zeroed again two lines below.
GET_GAME_TIMER DEFENDER
ROLL = DEFENDER / STATUS_PERIOD
ROLL = ROLL * STATUS_PERIOD
DBG_COUNT = DEFENDER - ROLL             // DEFENDER modulo STATUS_PERIOD
IF DBG_COUNT >= STATUS_WINDOW
    RETURN
ENDIF

DBG_COUNT = 0
REPEAT MAX_DEFENDERS CURSOR
    IF DEFENDER_HANDLE[CURSOR] > SLOT_EMPTY
        DBG_COUNT = DBG_COUNT + 1
    ENDIF
ENDREPEAT
IF DBG_COUNT > 0
    WRITE_DEBUG_WITH_INT "MenReact defenders" DBG_COUNT
    RETURN
ENDIF

IF IS_ON_MISSION
    WRITE_DEBUG "MenReact idle: a mission is running"
    RETURN
ENDIF

// DEFENDER still holds the raw timer from the throttle above.
ROLL = DEFENDER - WAVE_TIME             // how long ago the last wave started
ROLL *= -1                              // gta3sc rejects "VAR = CONST - VAR" (3.13)
ROLL = ROLL + WAVE_COOLDOWN
IF ROLL > 0
    ROLL = ROLL / MS_PER_SECOND
    WRITE_DEBUG_WITH_INT "MenReact cooldown left s" ROLL
    RETURN
ENDIF

WRITE_DEBUG "MenReact watching for a victim"
RETURN

//----------------------------------------------------------------------------
// IsCoward - would this male ped run away instead of acting against the player?
// in:      DEFENDER (29@)
// result:  sets the condition flag for the caller's "IF GOSUB IsCoward":
//          RETURN_TRUE  -> he must be left completely alone
//          RETURN_FALSE -> he is a candidate for recruitment
// clobbers: CURSOR (24@), ROLL (25@)
//
// RETURN_TRUE and RETURN_FALSE are the aliases of 0485 IS_PC_VERSION and
// 059A IS_AUSTRALIAN_GAME declared in config/cleoplus.xml.  They are conditions,
// so each one sets the script's compare flag and the RETURN that follows hands
// that flag to the caller - which is how a boolean subroutine is written in
// GTA3script.  (They existed in GTA III and Vice City and were dropped from San
// Andreas; Junior_Djjr's GTA3script topic re-introduces the names.)
//
// This is the test that vanilla CLEO cannot express at all, and the reason the
// 2011 original rolled dice instead: there is no opcode in plain CLEO that can
// ask what a ped's personality is.  Both readings below come from the game's own
// data/pedstats.dat, via CLEO+.
//----------------------------------------------------------------------------
IsCoward:
IF NOT DOES_CHAR_EXIST DEFENDER
    GOTO COWARD_YES                 // unusable, so treat him as "leave alone"
ENDIF

// 1) GET_CHAR_STAT_ID: the ped's row in data/pedstats.dat (0-based).  The last
//    column of that file is "Default decision maker", and the game's own header
//    documents the value 4 as "coward peds" - the peds the R_Weak decision maker
//    sends running.  Exactly nine rows carry it:
//        16 SENSIBLE_GUY  17 GEEK_GUY  22 SENSIBLE_GIRL  23 GEEK_GIRL
//        34 STEWARD       36 SHOPPER   37 OLDSHOPPER     40 SKATER
//        42 COWARD
//    Seven are male: 16, 17, 34, 36, 37, 40 and 42.  The female half is turned
//    away earlier by IS_CHAR_MALE - which, note, is a ped-type test and not a
//    model test, so it is these rows plus the caller's own filter that decide,
//    not any idea the engine has about the model in front of it.
//
//    The blacklist is exactly those seven male rows, and nothing else.  An
//    earlier revision rejected the whole 14..25 block of civilian "guy/girl"
//    personalities on the theory that STREET_GUY (dm 2), SUIT_GUY (dm 2),
//    OLD_GUY (dm 2) and TOUGH_GUY (dm 3) only mill about, shout and wander off.
//    That was wrong in the only way that matters: those four rows are what the
//    ordinary male pedestrians of Los Santos actually use, so the recruit loop
//    walked a whole street and rejected every man on it.  The playtest said it
//    plainly - the victim was detected, the window opened, nobody came.  The
//    requirement is to leave cowards alone, and the game's own decision-maker
//    column is what says who is a coward; guessing beyond it removes the crowd
//    the mod is supposed to raise.
GET_CHAR_STAT_ID DEFENDER CURSOR
IF CURSOR = PEDSTAT_SENSIBLE_GUY
    GOTO COWARD_YES
ENDIF
IF CURSOR = PEDSTAT_GEEK_GUY
    GOTO COWARD_YES
ENDIF
IF CURSOR = PEDSTAT_STEWARD
    GOTO COWARD_YES
ENDIF
IF CURSOR = PEDSTAT_SHOPPER
    GOTO COWARD_YES
ENDIF
IF CURSOR = PEDSTAT_OLDSHOPPER
    GOTO COWARD_YES
ENDIF
IF CURSOR = PEDSTAT_SKATER
    GOTO COWARD_YES
ENDIF
IF CURSOR = PEDSTAT_COWARD
    GOTO COWARD_YES
ENDIF

// 2) GET_CHAR_FEAR: the Fear column of the same file (0..100, 100 = scared of
//    everything), the number the game itself uses to decide how quickly a ped
//    runs away.  It catches the rows that are not flagged coward but panic
//    anyway - TOURIST is fear 100 - and any value a ped mod or an edited
//    pedstats.dat moved up.  Set MAX_FEAR to 100 to switch this second test off
//    and keep only the pedstat blacklist.
GET_CHAR_FEAR DEFENDER ROLL
IF ROLL > MAX_FEAR
    GOTO COWARD_YES
ENDIF

RETURN_FALSE
RETURN                              // mandatory - see the note above the label

COWARD_YES:
RETURN_TRUE
RETURN

//----------------------------------------------------------------------------
// RecruitDefenders - for RECRUIT_WINDOW milliseconds, walk the ped pool around
// the victim and hand TASK_KILL_CHAR_ON_FOOT to the witnesses that pass every
// filter.  Whoever fails a filter is not modified in any way: the game keeps
// controlling him normally.
// in:      CANDIDATE (23@) = the victim, PLAYER_ACTOR (14@)
// clobbers: NOW (18@), PX PY PZ (20@ 21@ 22@), CURSOR (24@), ROLL (25@),
//           VX VY VZ (26@ 27@ 28@), DEFENDER (29@), DBG_COUNT (30@)
//
// The filters run cheapest first on purpose.  Distance and line of sight throw
// away most of a crowded street for one opcode each, before anything reads a
// ped's personality data.
//----------------------------------------------------------------------------
RecruitDefenders:

RECRUIT_WINDOW_LOOP:
WAIT 0
IF NOT IS_PLAYER_PLAYING PLAYER_INDEX
    RETURN
ENDIF
GET_GAME_TIMER NOW
NOW = NOW - WAVE_TIME
IF NOW > RECRUIT_WINDOW
    // One line per wave, so a wave that recruited nobody can be told apart from
    // a wave that never ran to its end: "victim detected" without this line
    // means the window was cut short (she got into a car, the player did, a
    // mission or a cutscene started), and this line reporting zero defenders
    // means every witness in range failed one of the filters below.
    GOSUB DebugGate
    IF DBG_COUNT = 1
        DBG_COUNT = 0
        REPEAT MAX_DEFENDERS CURSOR
            IF DEFENDER_HANDLE[CURSOR] > SLOT_EMPTY
                DBG_COUNT = DBG_COUNT + 1
            ENDIF
        ENDREPEAT
        WRITE_DEBUG_WITH_INT "MenReact window over, defenders" DBG_COUNT
    ENDIF
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

// The fifth parameter of 0AE1 is its "findNext" flag: 0 walks the ped pool from
// the beginning, 1 continues after the ped it handed out last time.  It is
// written as a literal at two call sites rather than kept in a local, because
// every scratch local in this subroutine doubles for something else and one
// stray write to that flag would silently restart - or stall - the walk.  A
// constant cannot be clobbered.  This is the same lesson as NOW and DebugStatus.
RECRUIT_FIRST_WITNESS:
GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE VX VY VZ DEFEND_RADIUS 0 SEARCH_ALIVE_NPC DEFENDER
GOTO RECRUIT_GOT_WITNESS

RECRUIT_NEXT_WITNESS:
GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE VX VY VZ DEFEND_RADIUS 1 SEARCH_ALIVE_NPC DEFENDER

RECRUIT_GOT_WITNESS:
IF DEFENDER = -1                    // 0AE1 yields -1 when the pool is exhausted
    GOTO RECRUIT_WINDOW_LOOP        // ... so the whole 2.5 s window retries
ENDIF

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
// 03A3 is a ped-type test, not a model test: it answers true unless the type is
// CIVFEMALE or PROSTITUTE.  Two consequences, and both are accepted here.  No
// man is ever lost - gang, criminal and bum types all answer true, whatever the
// model.  And a woman whose type is GANG*, CRIMINAL or BUM answers true as well,
// because those types are shared by both sexes and the engine exposes no opcode
// for the sex of a model.  In gang territory she can therefore end up in the
// mob too; listing female models by hand would break the moment a ped mod is
// installed, which is a worse trade.  On the victim side the same semantics are
// what make the scan precise: only a CIVFEMALE or PROSTITUTE counts as "a woman
// being hit".
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
// Random world peds are false, so this is not a substitute for the pedtype test
// below: GET_PED_TYPE still catches the mission and player ped types that were
// not script-created.
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
IF NOT LOCATE_CHAR_DISTANCE_TO_CHAR DEFENDER CANDIDATE DEFEND_RADIUS
    GOTO RECRUIT_NEXT_WITNESS
ENDIF

// --- line of sight, so nobody reacts through a wall ------------------------
// The 3D distance test above has no vertical tolerance of its own, so a witness
// on a balcony would pass it; this ray is what keeps the reaction on the same
// level as the victim.  PX/PY/PZ (20@..22@) are the scan-loop scratch and are
// re-read every scan.
//
// 06BD takes five flags - buildings, cars, chars, objects, particles - and only
// buildings is set here.  "chars" was set in an earlier revision, which meant
// that a ped standing between the witness and the victim broke the ray: in a
// crowd, which is exactly when this mod fires, that rejected essentially
// everybody.  The intent of this test is "nobody reacts through a wall", so a
// bystander, a parked car or a bin must not count as an obstruction.
GET_OFFSET_FROM_CHAR_IN_WORLD_COORDS DEFENDER NO_OFFSET NO_OFFSET EYE_HEIGHT PX PY PZ
IF NOT IS_LINE_OF_SIGHT_CLEAR PX PY PZ VX VY VZ 1 0 0 0 0
    GOTO RECRUIT_NEXT_WITNESS
ENDIF

// --- already busy with a fight of his own? ---------------------------------
// IS_CHAR_FIGHTING (CLEO+) means "has TASK_SIMPLE_FIGHT in his task chain this
// frame", i.e. he is trading punches right now - not merely hostile.  Whoever is
// already in a fistfight is being driven by the game's own combat AI, and
// overwriting that would break the rule about peds that are not ours to move.
IF IS_CHAR_FIGHTING DEFENDER
    GOTO RECRUIT_NEXT_WITNESS
ENDIF

// --- would he run away instead of acting? ----------------------------------
IF GOSUB IsCoward
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
// ROLL (25@) doubles as the loop counter, CURSOR (24@) as the slot found.
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
    GOSUB DebugGate
    IF DBG_COUNT = 1
        WRITE_DEBUG "MenReact: mob full, witness left alone"
    ENDIF
    GOTO RECRUIT_NEXT_WITNESS       // the mob is already big enough
ENDIF

// ROLL (25@) takes over the slot index from CURSOR (24@): ROLL has finished its
// job as the REPEAT counter and CURSOR is about to be needed as the kill-target
// scratch.
ROLL = CURSOR

// The chosen slot may still hold a defender who timed out but was never freed -
// ReleaseExpiredDefenders had not run between his expiry and this moment.  He
// has to be let go BEFORE his handle is overwritten, otherwise he would keep a
// TASK_KILL_CHAR_ON_FOOT with nothing left to time it out: a man chasing the
// player forever, which is exactly what the 2011 original did.
//
// Every read of the outgoing defender goes straight to DEFENDER_HANDLE[ROLL]
// instead of through DEFENDER (29@).  That is not a stylistic choice: DEFENDER
// is holding the witness we are about to recruit, and there is no second scratch
// local to park him in - 0@..3@ belong to CLEO and PX/PY/PZ are FLOAT, which
// gta3sc will not let hold a handle.  Reading the slot inline means the release
// needs no scratch at all beyond CURSOR for the kill target.  This is the same
// shape as ReleaseOneDefender below; keep the two in step.
IF DEFENDER_HANDLE[ROLL] > SLOT_EMPTY
    IF DOES_CHAR_EXIST DEFENDER_HANDLE[ROLL]
        IF NOT IS_CHAR_DEAD DEFENDER_HANDLE[ROLL]
            GET_CHAR_KILL_TARGET_CHAR DEFENDER_HANDLE[ROLL] CURSOR
            IF CURSOR = PLAYER_ACTOR
                CLEAR_CHAR_TASKS_IMMEDIATELY DEFENDER_HANDLE[ROLL]
            ENDIF
        ENDIF
    ENDIF
ENDIF

// --- recruit him -----------------------------------------------------------
DEFENDER_HANDLE[ROLL] = DEFENDER
GET_GAME_TIMER DEFENDER_SINCE[ROLL]
TASK_KILL_CHAR_ON_FOOT DEFENDER PLAYER_ACTOR

GOSUB DebugGate
IF DBG_COUNT = 1
    DBG_COUNT = 0                   // the gate answered in DBG_COUNT, and now
    REPEAT MAX_DEFENDERS CURSOR     // the line wants the real defender count
        IF DEFENDER_HANDLE[CURSOR] > SLOT_EMPTY
            DBG_COUNT = DBG_COUNT + 1
        ENDIF
    ENDREPEAT
    WRITE_DEBUG_WITH_INT "MenReact recruited, defenders now" DBG_COUNT
ENDIF

WAIT RECRUIT_STEP_DELAY             // never recruit a whole crowd in one frame
GOTO RECRUIT_WINDOW_LOOP

//----------------------------------------------------------------------------
// ReleaseExpiredDefenders - drop whoever has given up.
// clobbers: NOW (18@), CURSOR (24@), ROLL (25@), DEFENDER (29@), DBG_COUNT (30@)
// result:  NOW (18@) = a current timestamp, and that one is deliberate
//
// NOW is left holding a current timestamp either way - untouched on the fast
// path, re-read at the top of the loop otherwise - because the main loop uses it
// for the wave cooldown as soon as this returns.
//----------------------------------------------------------------------------
ReleaseExpiredDefenders:
// Fast path.  DEFENDER_HANDLE[0] is local 4@: gta3sc allocates locals in
// declaration order from 0@, and 0@..3@ are the reserved CLEO_ARGS, so the map
// in the header is literal and the five slots are 4@..8@.  Slots are filled from
// index 0 upwards and only ever freed in order, so an empty slot 0 means an
// empty list - and that is the state this routine is called in on almost every
// tick of the main loop.
IF DEFENDER_HANDLE[0] = SLOT_EMPTY
    RETURN
ENDIF
GET_GAME_TIMER NOW
REPEAT MAX_DEFENDERS CURSOR
    IF DEFENDER_HANDLE[CURSOR] > SLOT_EMPTY
        ROLL = NOW - DEFENDER_SINCE[CURSOR]
        IF ROLL > DEFENDER_TIMEOUT
            // CURSOR is the REPEAT counter AND the slot ReleaseOneDefender reads,
            // and gta3sc compiles REPEAT as "increment the variable itself" - so
            // the counter has to be parked and put back around the call.  See
            // docs/COMPILER.md 3.12.  The parking place is DEFENDER, not NOW:
            // NOW has to leave this routine still holding a timestamp, because
            // the main loop reads it right afterwards for the wave cooldown.
            DEFENDER = CURSOR
            GOSUB ReleaseOneDefender        // reads DEFENDER_HANDLE[CURSOR]
            CURSOR = DEFENDER
            DEFENDER_HANDLE[CURSOR] = SLOT_EMPTY
            GOSUB DebugGate
            IF DBG_COUNT = 1
                WRITE_DEBUG "MenReact: defender timed out, released"
            ENDIF
        ENDIF
    ENDIF
ENDREPEAT
RETURN

//----------------------------------------------------------------------------
// ReleaseAllDefenders - the player died, got busted, hopped into a car, or a
// mission or a cutscene started: everybody minds their own business again.
// clobbers: CURSOR (24@), ROLL (25@), DEFENDER (29@), DBG_COUNT (30@)
// DEFENDER is only a parking space for the REPEAT counter (see below); every
// caller invokes this at a point where DEFENDER (29@) is dead.
//----------------------------------------------------------------------------
ReleaseAllDefenders:
IF DEFENDER_HANDLE[0] = SLOT_EMPTY
    RETURN                          // same fast path, and the common case
ENDIF
DBG_COUNT = 0
REPEAT MAX_DEFENDERS CURSOR
    IF DEFENDER_HANDLE[CURSOR] > SLOT_EMPTY
        DEFENDER = CURSOR               // park the REPEAT counter: 29@ is dead
        GOSUB ReleaseOneDefender        // in the main loop, our only caller
        CURSOR = DEFENDER
        DEFENDER_HANDLE[CURSOR] = SLOT_EMPTY
        DBG_COUNT = DBG_COUNT + 1
    ENDIF
ENDREPEAT
IF DBG_COUNT > 0
    // DebugGate answers in DBG_COUNT and keeps its scratch in ROLL, so the
    // count is parked in CURSOR first: the REPEAT is over and nothing reads it
    // again.  Without the gate this line reached the screen even with
    // OPT_DEBUG_TEXT off, which is the one promise the debug block makes.
    CURSOR = DBG_COUNT
    GOSUB DebugGate
    IF DBG_COUNT = 1
        WRITE_DEBUG_WITH_INT "MenReact released defenders" CURSOR
    ENDIF
ENDIF
RETURN

//----------------------------------------------------------------------------
// ReleaseOneDefender - put the defender in DEFENDER_HANDLE[CURSOR] back to being
// a normal pedestrian.
// in:      CURSOR (24@) = the slot to release (read, never written)
// clobbers: ROLL (25@)
//
// It reads its slot inline rather than taking a handle in DEFENDER (29@).  That
// is what makes the recruit loop able to free an expired slot while DEFENDER is
// holding the witness it is about to recruit: there is no second scratch local
// for a handle anywhere in this script, and a FLOAT one cannot hold an int in
// gta3sc.
//
// CURSOR is deliberately left untouched, because both callers run it as a REPEAT
// counter and gta3sc compiles "REPEAT n CURSOR" into "increment CURSOR itself" -
// a subroutine that quietly rewrote the counter would end the loop after one
// pass, and the emitted bytecode shows exactly that (ADD_VAL_TO_INT_LVAR on the
// counter variable).  The kill target therefore goes into ROLL.  The callers
// still park and restore CURSOR around the call; with this routine writing only
// ROLL that is belt and braces, and it stays that way on purpose, because the
// day someone teaches this routine to take its slot in DEFENDER is the day the
// loops break again.  tools/check-clobbers.py re-derives the write set from the
// emitted IR2 and fails the build if the two ever disagree.
//
// The task is only taken away from a ped that is still holding *our* task, which
// GET_CHAR_KILL_TARGET_CHAR answers exactly: CLEO+ reads the target pointer out
// of the TASK_COMPLEX_KILL_PED_ON_FOOT struct that 05E2 created, so if it is not
// the player any more the game has moved him on and we do not clear it.  That
// keeps "do not touch peds that are not acting against the player" true on the
// way out as well as on the way in.
//
// Every handle used here came from the *_NO_SAVE variant of the random char
// opcodes, so this script never owned a reference to those peds: there is
// nothing to leak and no MARK_CHAR_AS_NO_LONGER_NEEDED to call.  The 2011
// original instead created a CGroup per assault and never removed it.
//----------------------------------------------------------------------------
ReleaseOneDefender:
IF NOT DOES_CHAR_EXIST DEFENDER_HANDLE[CURSOR]
    RETURN
ENDIF
IF IS_CHAR_DEAD DEFENDER_HANDLE[CURSOR]
    RETURN
ENDIF
GET_CHAR_KILL_TARGET_CHAR DEFENDER_HANDLE[CURSOR] ROLL
IF ROLL = PLAYER_ACTOR
    CLEAR_CHAR_TASKS_IMMEDIATELY DEFENDER_HANDLE[CURSOR]
ENDIF
RETURN

// gta3sc appends TERMINATE_THIS_CUSTOM_SCRIPT by itself when building a
// custom script (--cs), so it is deliberately not written here.
}

SCRIPT_END

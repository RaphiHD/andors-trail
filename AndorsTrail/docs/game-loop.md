# The Game Loop / Round System

## Overview

Andor's Trail does not run a fixed-framerate render loop. Instead, a single
`Handler`-driven timer ticks at a fixed wall-clock interval while the main
activity is visible and the player is not in turn-based combat. Every tick,
`GameRoundController` calls a small, hard-coded sequence of methods on other
controllers (move monsters, spawn monsters, apply conditions, advance world
time, etc.). Every few ticks that sequence also fires a "round", and every
few rounds it fires a "full round". This tick/round/full-round cadence is
the closest thing the game has to a heartbeat: it is what makes time pass in
the world outside of combat, and it is the mechanism other systems use to do
periodic work (regeneration, monster AI, condition duration, map respawn
checks, etc.).

Turn-based combat is a separate, event-driven state machine
([CombatController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/CombatController.java))
that suspends the tick loop and instead calls the same round-advancement
methods (`onNewMonsterRound` / `onNewPlayerRound`) directly at the end of
each combat turn, so world time and conditions keep advancing consistently
whether the player is walking around the map or fighting.

## Key classes/files

| File | Role |
|---|---|
| [GameRoundController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/GameRoundController.java) | Owns the timer, decides when a tick becomes a round or full round, and directly invokes the per-tick/per-round work of other controllers in a fixed order. |
| [TimedMessageTask.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/util/TimedMessageTask.java) | Generic `android.os.Handler`-based repeating-message helper. `GameRoundController` implements its `Callback` interface (`onTick`) and owns one instance (`roundTimer`) as its clock source. |
| [GameRoundListener.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/GameRoundListener.java) | Observer interface with `onNewTick()`, `onNewRound()`, `onNewFullRound()`. |
| [GameRoundListeners.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/GameRoundListeners.java) | `ListOfListeners<GameRoundListener>` — the weak-reference broadcast list `GameRoundController` fans its three events out to, in addition to its own direct calls. |
| [Constants.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/Constants.java) | Defines the timing constants (`TICK_DELAY`, `ROUND_DURATION`, `FULLROUND_DURATION`, and the derived `TICKS_PER_ROUND` / `TICKS_PER_FULLROUND`). |
| [ControllerContext.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/context/ControllerContext.java) | Constructs and holds `gameRoundController` alongside every other controller it calls into (`combatController`, `monsterMovementController`, `monsterSpawnController`, `actorStatsController`, `mapController`, `effectController`). |
| [WorldContext.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/context/WorldContext.java) | Holds the mutable `ModelContainer model` (via `world.model`) that `GameRoundController` reads/mutates each tick — e.g. `world.model.uiSelections.isMainActivityVisible`, `isInCombat`, `world.model.worldData`, `world.model.currentMaps`. |
| [WorldData.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/WorldData.java) | Holds `worldTime` (a `long`, "measured in number of game rounds" per its own comment) and `tickWorldTime()`/`tickWorldTime(int)`, which is what actually advances in-game time. |
| [CombatController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/CombatController.java) | During combat, calls `gameRoundController.onNewMonsterRound()` / `onNewPlayerRound()` / `resetRoundTimers()` directly, bypassing the normal tick loop (see Gotchas). |

## How it works

### Timing units

Defined in [Constants.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/Constants.java):

- `TICK_DELAY = 500` (ms) — the base clock period.
- `ROUND_DURATION = 6000` (ms) → `TICKS_PER_ROUND = ROUND_DURATION / TICK_DELAY = 12` ticks per round.
- `FULLROUND_DURATION = 25000` (ms) → `TICKS_PER_FULLROUND = FULLROUND_DURATION / TICK_DELAY = 50` ticks per full round.

So a "tick" happens every 0.5s, a "round" every 6s (every 12th tick), and a
"full round" every 25s (every 50th tick). Ticks and full-round ticks are
counted down independently (`ticksUntilNextRound`, `ticksUntilNextFullRound`),
each reset back to its constant after firing — they are not aligned to a
shared modulo, they're two independent countdowns that both decrement every
tick.

### The clock

`GameRoundController` constructs one `TimedMessageTask roundTimer = new TimedMessageTask(this, Constants.TICK_DELAY, true)`
in its constructor. `TimedMessageTask` is a thin wrapper around an Android
`Handler`: `start()` queues a delayed message, `handleMessage` fires `tick()`
which calls back into `GameRoundController.onTick(...)`, and if that returns
`true` it queues another delayed message (`queueAnotherTick`). This means
the loop is self-rescheduling and single-threaded — everything runs on the
main/UI thread, driven by the Android message queue, not a background thread
or fixed-rate scheduler. The `true` passed as `requireIntervalBeforeFirstTick`
means the very first tick after `start()` still waits a full `TICK_DELAY`
before firing.

### What triggers a round

`GameRoundController.onTick(TimedMessageTask task)` is the single entry
point called every `TICK_DELAY` ms:

```java
if (!world.model.uiSelections.isMainActivityVisible) return false;
if (world.model.uiSelections.isInCombat) return false;
onNewTick();
--ticksUntilNextRound;
if (ticksUntilNextRound <= 0) { onNewRound(); restartWaitForNextRound(); }
--ticksUntilNextFullRound;
if (ticksUntilNextFullRound <= 0) { onNewFullRound(); restartWaitForNextFullRound(); }
return true;
```

Two guard conditions can stop the loop outright (returning `false`, which
means `TimedMessageTask` will *not* requeue another tick):

- `isMainActivityVisible` is `false` — set by `pause()`/`resume()`, called
  from `MainActivity.onPause`/`onResume` and from `Dialogs.java` whenever a
  blocking dialog is shown (e.g. death, level-up, market — see
  `Dialogs.pause()`/`resume()` calls).
- `isInCombat` is `true` — the tick loop is deliberately halted for the
  entire duration of turn-based combat; see Gotchas below.

Otherwise every tick unconditionally calls `onNewTick()`, then independently
checks whether a round and/or full round boundary was crossed.

### Order of operations per tick (`onNewTick`)

```java
controllers.monsterMovementController.moveMonsters();
controllers.monsterSpawnController.maybeSpawn(map, tileMap);
controllers.monsterMovementController.attackWithAgressiveMonsters();
controllers.effectController.updateSplatters(map);
controllers.mapController.handleMapEvents(map, playerPosition, MapObjectEvaluationType.continuously);
gameRoundListeners.onNewTick();
```

This order matters: monsters move first, then may spawn, then aggressive
monsters may attack (which can trigger combat and thus `isInCombat = true`,
halting future ticks), then blood-splatter visual effects are aged out, then
continuous map-object triggers are evaluated, and only after all of that are
registered `GameRoundListener`s notified.

### Order of operations per round (`onNewRound`)

```java
onNewMonsterRound();   // controllers.actorStatsController.applyConditionsToMonsters(map, false)
onNewPlayerRound();    // tickWorldTime(); applyConditionsToPlayer(player, false);
                       // applySkillEffectsForNewRound(player, map);
                       // handleMapEvents(map, playerPosition, MapObjectEvaluationType.afterEveryRound)
gameRoundListeners.onNewRound();
```

`onNewPlayerRound()` is where `world.model.worldData.tickWorldTime()` is
called — i.e. in-game world time (`WorldData.worldTime`) advances once per
round, not once per tick.

### Order of operations per full round (`onNewFullRound`)

```java
controllers.mapController.resetMapsNotRecentlyVisited();
controllers.actorStatsController.applyConditionsToMonsters(map, true);
controllers.actorStatsController.applyConditionsToPlayer(player, true);
gameRoundListeners.onNewFullRound();
```

The `true` argument distinguishes full-round condition application from the
per-round one — used by `conditions-effects.md` mechanics that only tick
down or reapply on full-round boundaries.

### Pause/resume and timer resets

- `pause()` stops `roundTimer` and clears `isMainActivityVisible`.
- `resume()` sets `isMainActivityVisible = true`, restarts `roundTimer`, and
  — if the player was mid-combat when paused — re-enters combat via
  `combatController.enterCombat(BeginTurnAs.continueLastTurn)`.
- `resetRoundTimers()` resets both `ticksUntilNextRound` and
  `ticksUntilNextFullRound` back to their max values without firing the
  round/full-round callbacks. This is called whenever something should
  "delay" the next scheduled round rather than let a partially-elapsed
  countdown fire early — e.g. `Savegames.java` after loading a save,
  `MapController.java` after a map transition, and `CombatController.java`
  when combat ends.

## How other systems hook in

There are two distinct hook-in mechanisms in this file, and they are used
by different kinds of consumers:

1. **Direct, hard-coded calls from `GameRoundController` itself** — the
   primary mechanism for gameplay controllers. `onNewTick`, `onNewRound`,
   `onNewMonsterRound`, `onNewPlayerRound`, and `onNewFullRound` call
   specific methods on other controllers in a fixed order (see above). To
   add new per-tick/per-round gameplay behavior, a controller does not
   implement `GameRoundListener` — it gets an explicit call added into one
   of these methods.
2. **The `GameRoundListener` observer interface**, broadcast via
   `gameRoundListeners` (a `GameRoundListeners`/`ListOfListeners` weak-list)
   *after* the direct calls above have run. In the current codebase, the
   only registered listener is `MainView.java` (`subscribe()`/`unsubscribe()`
   register/unregister `this` as a `GameRoundListener` alongside several
   other listener lists), which uses it purely to trigger UI redraws — it is
   not used by any gameplay/logic controller.

Controllers wired into the tick/round methods directly, and why:

- `controllers.monsterMovementController` (`moveMonsters()`,
  `attackWithAgressiveMonsters()`) — per-tick monster AI movement and
  aggro/attack checks. See `monsters.md` and `movement-pathfinding.md`.
- `controllers.monsterSpawnController` (`maybeSpawn(...)`) — per-tick check
  for whether a new monster should spawn in a spawn area. See `monsters.md`.
- `controllers.effectController` (`updateSplatters(...)`) — per-tick aging
  of blood-splatter visual effects. See `conditions-effects.md`.
- `controllers.mapController` (`handleMapEvents(...)`,
  `resetMapsNotRecentlyVisited()`) — per-tick "continuously" map-object
  triggers, per-round "afterEveryRound" triggers, and per-full-round map
  respawn-eligibility resets.
- `controllers.actorStatsController` (`applyConditionsToMonsters(...)`,
  `applyConditionsToPlayer(...)`, `applySkillEffectsForNewRound(...)`) —
  condition (buff/debuff) duration ticking and regen/skill effects applied
  once per round (and again, with `isFullRound=true`, once per full round).
  See `character-stats-leveling.md` and `conditions-effects.md`.
- `controllers.combatController` does not hook into the tick loop; instead
  it *takes over* from it (see Gotchas) and calls
  `gameRoundController.onNewMonsterRound()` / `onNewPlayerRound()` /
  `resetRoundTimers()` back, to keep world time and conditions advancing
  turn-by-turn during a fight. See `combat.md`.

## Gotchas / non-obvious behavior worth knowing

- **The tick loop fully stops during combat**, rather than continuing to run
  in the background: `onTick` returns `false` as soon as
  `world.model.uiSelections.isInCombat` is `true`, and returning `false`
  tells `TimedMessageTask` not to requeue itself, so no more ticks fire
  until something restarts the timer (`resume()` after `exitCombat`, or
  `enterCombat`/`endOfCombatRound` calling `resetRoundTimers()` +
  eventually `resume()`). During that time, `CombatController` calls
  `gameRoundController.onNewMonsterRound()` / `onNewPlayerRound()` itself at
  the start of each monster/player combat turn (skipping the very first
  turn via an `isFirstRound` flag), and `endOfCombatRound()` additionally
  calls `world.model.worldData.tickWorldTime()` directly. So world time and
  conditions still advance during combat, but on a "per combat turn" cadence
  driven by `CombatController`, not on the wall-clock tick/round cadence
  driven by `GameRoundController`.
- **`onNewMonsterRound()` and `onNewPlayerRound()` are public and split out**
  specifically so `CombatController` can call them independently of
  `onNewRound()` (which calls both plus notifies listeners). Any change to
  what `onNewRound()` does should consider whether the split-out monster/
  player halves also need to change, since combat exercises those two
  methods on a different cadence and without the listener notification.
- **Round and full-round countdowns are independent, not nested.** Both
  `ticksUntilNextRound` and `ticksUntilNextFullRound` decrement every tick
  and are reset independently when they hit zero; a full round is not
  literally "every Nth round", it's a separate countdown from the same tick
  driver, which happens to work out to `50/12 ≈ 4.17` ticks-worth of rounds
  per full round (not an integer multiple).
- **`GameRoundListener` is registered/broadcast last**, after all the
  hard-coded controller calls in `onNewTick`/`onNewRound`/`onNewFullRound`
  have already run. A new `GameRoundListener` cannot influence or run before
  that fixed sequence — it only observes after the fact. In practice today
  only `MainView` uses it (for redraws), so there is no precedent in this
  codebase for a gameplay controller reacting via this interface rather than
  being called directly from `GameRoundController`.
- **`resetRoundTimers()` does not fire round/full-round callbacks** — it
  silently resets the countdowns. Callers use it to avoid a stale, nearly-
  elapsed countdown firing immediately after something like a map change or
  combat exit, but it means round cadence can effectively "reset" and drift
  relative to `worldTime`/wall clock at those points rather than staying
  perfectly periodic.
- **Everything runs on the main/UI thread.** `TimedMessageTask` extends
  `android.os.Handler` with no custom `Looper`, so all controller calls
  triggered from a tick happen synchronously on the UI thread; there is no
  separate simulation thread to reason about, but it also means expensive
  work added to `onNewTick`/`onNewRound`/`onNewFullRound` can visibly affect
  UI responsiveness.

# Travelling NPCs — Work in Progress

> **Status:** in-progress feature on branch `travellingnpc_b`, 40 commits ahead
> of `master` (2026-06-17 → 2026-07-11, HEAD at `e820ef314` "Let's see what
> this does, shall we"). This document is a snapshot of what's actually
> implemented today, not a design spec — see "Known gaps" for what still
> needs work before this is production-ready.

## Goal

Let monsters/NPCs (the game has no separate "NPC" class — a stationary
shopkeeper and a wandering monster are both `Monster` instances, see
[monsters.md](../monsters.md)) walk to a destination beyond their own
`MonsterSpawnArea`, including to a destination on a **different map**,
instead of only wandering inside the rectangle they spawned in.

## Architecture that was added/changed for this

| File | What changed / was added |
|---|---|
| [TravelDestinationArea.java](../../app/src/main/java/com/gpl/rpg/AndorsTrail/model/map/TravelDestinationArea.java) | New `MapArea` subclass (sibling of `MonsterSpawnArea`) representing a named landing zone a monster can travel to. Carries an optional `arrivalScript` (a conversation phrase ID run on arrival). |
| [MapArea.java](../../app/src/main/java/com/gpl/rpg/AndorsTrail/model/map/MapArea.java) | Existing abstract base extracted/reused so `MonsterSpawnArea` and `TravelDestinationArea` share `area`/`areaID`/`mapID`. `Monster.area` was widened from "the `MonsterSpawnArea` I spawned in" to "any `MapArea`" (commit "Decoupling Monsters from SpawnAreas"), so a monster can end up "owned" by a `TravelDestinationArea` after arriving. |
| [GlobalPathFinder.java](../../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/GlobalPathFinder.java) | **New class.** Runs Dijkstra over a graph whose nodes are `(map, newmap-exit)` pairs across every loaded `PredefinedMap`, to plan a multi-map route from a monster's current position to a `TravelDestinationArea` on a possibly different map. |
| [PathFinder.java](../../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/PathFinder.java) | Rewritten to a real 8-directional A\* (commit "Refactor PathFinder to use A\*"; previously something simpler). `GlobalPathFinder` leans on this for both per-map edge weights (`PredefinedMap.getDistance`) and for interpolating a position partway along a route (`findPositionOnPath`, used to place a travelling monster on-screen). See [movement-pathfinding.md](../movement-pathfinding.md) for the algorithm in detail — this document only covers the travel-specific usage. |
| [PredefinedMap.java](../../app/src/main/java/com/gpl/rpg/AndorsTrail/model/map/PredefinedMap.java) | Gained `destinationAreas: TravelDestinationArea[]`, a `calculateDistanceMatrix()`/`getDistance(id1, id2)` pair precomputing pairwise walking distance between every `newmap` exit on the map (via `PathFinder`), and savegame (de)serialization for `destinationAreas`. |
| [Monster.java](../../app/src/main/java/com/gpl/rpg/AndorsTrail/model/actor/Monster.java) | Gained `travelDestination: TravelDestinationArea` and `travelPath: GlobalPathFinder.GlobalPath` fields, plus `currentMapID` (a monster now knows which map it's logically on, needed once it can leave its spawn map). Also `travelFailedScript: String` — a standing, per-NPC phrase ID run whenever a journey fails (unreachable, or given up on after too many blocked retries), independent of which destination was being attempted. |
| [MonsterCollection.java](../../app/src/main/java/com/gpl/rpg/AndorsTrail/model/actor/MonsterCollection.java) | **New class**, holds `travellingMonsters: List<Monster>` — a world-level (not per-map) pool for monsters currently in transit between maps, decoupled from any single map's tick loop. |
| [MonsterMovementController.java](../../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/MonsterMovementController.java) | Owns the one `GlobalPathFinder` instance; gained `beginTravel(...)` (kicks off a journey) and the travelling-monster advancement loop inside `moveMonsters()`, plus `spawnMonsterOnMap(...)` (re-materializes a travelling monster as a positioned `Monster` on whichever map it's currently crossing). Also `fireTravelFailedScript(...)`, called from both places a journey gives up. |
| [ConversationController.java](../../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/ConversationController.java) | New `setDestination` reward type → `setTravelDestination(npc, mapName, destinationID)` → `beginTravel(...)` — the **only** way to start a monster travelling, see below. Also `setTravelFailedScript` → sets `Monster.travelFailedScript`. |
| [Requirement.java](../../app/src/main/java/com/gpl/rpg/AndorsTrail/model/script/Requirement.java) | New `onMap`, `inArea`, `isTravelling` requirement types (checked in `ConversationController.canFulfillRequirement`) so scripts/dialogue can branch on where an NPC currently is or whether it's mid-journey. |
| [MainView.java](../../app/src/main/java/com/gpl/rpg/AndorsTrail/view/MainView.java) | Gained `drawTravelDebug(...)`, a cross-map on-screen visualization of every travelling monster's route (see "Debug visualization" below), toggled by the same `trv` button as `showTravelDebug`'s logcat output. |

## How to trigger travel today

There is **no autonomous behavior** yet — a monster never decides on its own
to travel. The only trigger is a conversation reward:

```json
{ "rewardType": "setDestination", "rewardID": "<destinationAreaID>", "mapName": "<mapName>" }
```

which calls `ConversationController.setTravelDestination` →
`MonsterMovementController.beginTravel(monster, mapID, destinationID)`. The
commit that added this (`19f13e0eb`) frames it as "an NPC could say Goodbye
and only then walk off" — i.e. the intended use is scripted departures, not
a general-purpose scheduling/AI system.

A second, independent reward configures how an NPC reacts if a journey
fails (see [PLAN.md](PLAN.md)'s Phase 3 step 4):

```json
{ "rewardType": "setTravelFailedScript", "rewardID": "<phraseID>" }
```

This sets `Monster.travelFailedScript`, a standing per-NPC fallback (not
cleared after firing, unlike `travelDestination`) run whenever *any* future
journey for that specific monster fails — deliberately a property of the
traveler rather than of the destination, since different monsters
targeting the same destination (or the same monster on different journeys)
may want different failure reactions (e.g. "go back home"). If that
fallback phrase itself starts another journey that's also unreachable,
`beginTravel` fails synchronously and re-invokes the same script — nothing
guards against that recursion, so the fallback destination must actually be
reachable.

## End-to-end flow, as implemented

1. **`beginTravel(m, mapID, destinationID)`** looks up the named
   `TravelDestinationArea` on the target map, sets `m.travelDestination`,
   computes `m.travelPath = globalPathFinder.findPath(m.currentMapID,
   m.rectPosition, mapID, area.area, area.areaID)`, and clears any local
   `movementDestination` (wandering target).
2. **Local approach.** Every tick, `determineMonsterNextPosition` sees
   `m.travelDestination != null` and pathfinds leg-by-leg: while
   `m.travelPath.currentPosition` isn't the last leg, it walks toward the
   `newmap` object named by the current leg's `destinationID` (via
   `findPathFor`, i.e. real per-map A\*). This only happens while the monster
   is in the *currently loaded* map's `monsters` list — see "Known gaps"
   below for the consequence of that.
3. **Crossing a map exit.** Once the monster's position is inside the target
   `newmap` object's rectangle, it's removed from `PredefinedMap.monsters`
   and added to `world.monsters.travellingMonsters`
   (`MonsterCollection`). `travelPath.startTime` is recalibrated
   (`now - unitsSoFar * msPerMove / 10`) so the upcoming time-based
   interpolation (next step) continues from the correct point rather than
   restarting.
4. **World-level advancement.** Every tick, `moveMonsters()` also walks
   `world.monsters.travellingMonsters` and computes progress **purely from
   elapsed wall-clock time**: `elapsedDistance = (now - travelPath.startTime)
   * 10 / getMillisecondsPerMove(m)` (where `getMillisecondsPerMove =
   Constants.MONSTER_MOVEMENT_TURN_DURATION_MS(1200) * moveCost / maxAP`, the
   same speed formula used for normal monster movement — see
   [movement-pathfinding.md](../movement-pathfinding.md)). It finds which leg
   that distance falls into, and:
   - If that leg's map is the map the player currently has loaded, or the
     monster has fully arrived, it calls `spawnMonsterOnMap(...)`, which uses
     `PathFinder.findPositionOnPath(startArea, toArea, distanceOnLeg, m)` to
     place the monster at the interpolated tile on that map, re-adds it to
     `PredefinedMap.monsters`, and fires `onMonsterSpawned` (only if that map
     is the one currently being viewed).
   - Otherwise the monster simply isn't materialized anywhere — it has **no
     `Monster` object in any map's list** while it's crossing a map the
     player isn't on. Its existence is entirely abstract (`travelPath` +
     elapsed time) until the player happens to be on the map it's currently
     traversing, or it arrives.
5. **Arrival.** `TravelDestinationArea.onMonsterArrived(m)` sets `m.area =
   this` and `m.travelDestination = null`, then — if `arrivalScript` is set
   and the area's `mapScriptExecutor`/`controllers` were wired up (see gap
   #4 below) — runs that conversation phrase as a script (via
   `ConversationController.ConversationStatemachine.proceedToPhrase`).
6. **Player-leaves-map safety net.** `MapController.handleMapEvent`'s
   `newmap` case additionally sweeps `world.model.currentMaps.map.monsters`
   for any monster with `travelDestination != null` and force-moves it into
   `world.monsters.travellingMonsters` whenever the player exits the map —
   because step 2's local approach only ever runs for monsters on the
   *currently loaded* map, this is what prevents a travelling monster from
   getting stranded, un-advanced, on a map the player has left before it
   reached its exit tile. (See gap #1 for a side effect of this.)

## Known gaps / unfinished pieces

Ranked roughly by how likely each is to bite first.

1. **~~The `newmap`-to-`newmap` distance matrix was a one-time snapshot~~
   Fixed (see [PLAN.md](PLAN.md)'s Phase 2, and
   [changelog.md](changelog.md)).** `PredefinedMap.calculateDistanceMatrix()`
   (which `GlobalPathFinder` reads via `getDistance(id1, id2)` to weight
   same-map edges) used to run exactly once, in the `PredefinedMap`
   constructor, and never again — `MapController.applyReplacements` (which
   swaps tiles when a quest/script requirement becomes true — see
   [maps-world.md](../maps-world.md)) now recomputes it whenever a
   `ReplaceableMapSection` is actually applied. `PredefinedMap.setDistance`
   remains unused (the plan's cheaper, patch-only alternative was not needed
   — see changelog for why). This recompute was initially correctness-inert
   due to a separate bug — see the next paragraph and the changelog's
   "Phase 2/3 correction" entry — now fixed alongside it.

   A closely related gap — a *live* in-flight monster whose local walk hits
   a now-blocked tile mid-leg — is **fixed** (see [PLAN.md](PLAN.md)'s Phase
   3 step 2, and [changelog.md](changelog.md); this and the matrix recompute
   above both silently didn't work until the "Phase 2/3 correction" fix,
   because `PredefinedMap.tileMap` — what all monster pathfinding actually
   reads — was never updated by a replacement in the first place, only the
   separate live `currentMaps.tileMap` was): the local approach step now
   retries with a bounded backoff instead of silently giving up (or, worse,
   falling through to an unrelated wander step) on the first failed
   `findPathFor` call, and gives up the whole journey after
   `Constants.MONSTER_TRAVEL_MAX_BLOCKED_RETRIES` consecutive failures rather
   than waiting forever. This originally only covered terrain/layout
   blockage — a **follow-up fix** (see changelog) closed a second gap found
   by live testing: the player was entirely invisible to the travel
   pathfinding graph (unlike other monsters, which were already excluded via
   `getMonsterAt`), so a monster blocked by the player never even detected
   it was blocked and just sat frozen forever, bypassing the retry/give-up
   logic entirely. `PathFinder.findPathBetween` can now exclude an arbitrary
   `avoid` tile from its search, and travel-approach pathing always passes
   the player's position as that tile — a travelling monster now genuinely
   detours around the player when a detour exists, and correctly falls into
   the retry/give-up path above when it doesn't (e.g. a single-tile-wide
   doorway). `keyarea` scoping is **also fixed** (see [PLAN.md](PLAN.md)'s
   Phase 3 step 3, and [changelog.md](changelog.md)): rather than teaching
   monsters to evaluate `enteringRequirement` (most requirement types check
   the *player's* inventory/quest state, so "does this monster satisfy it"
   usually isn't a meaningful question), `keyarea` objects gained a static,
   map-author-set `monstersCanPass` boolean (a new TMX property on `key`
   objects, default `false` to preserve existing behavior for every
   pre-existing keyarea). `monsterCanMoveTo`/`PredefinedMap.isWalkable` now
   check this flag instead of unconditionally blocking monsters. Being
   static rather than a dynamic per-`Requirement` check, this needs no new
   support from `GlobalPathFinder`'s distance computations — a keyarea whose
   monster-passability should change based on quest state should use a
   `ReplaceableMapSection` instead, which already exists for exactly that.
2. **~~An unreachable destination left the monster silently stuck, not
   failed.~~ Fixed** (see [PLAN.md](PLAN.md)'s Phase 3 step 1, and
   [changelog.md](changelog.md)). `beginTravel` now checks
   `travelPath.predictedTime < 0` immediately after calling
   `globalPathFinder.findPath(...)` and, if no route exists at all, clears
   `m.travelDestination`/`m.travelPath` back to `null` and logs a warning
   instead of leaving the monster with an unreachable travel state —
   `isTravelling` (`travelDestination != null`) now correctly reports
   `false` again right away, instead of the monster's next tick falling into
   the "final leg reached" branch and asking the *wrong* map's local
   pathfinder to reach coordinates belonging to a different map.
3. **~~Travel state is not saved.~~ Fixed (see [PLAN.md](PLAN.md)'s Phase
   1, and [changelog.md](changelog.md)).** `Monster.writeToParcel`/
   `newFromParcel` now serialize `travelDestination` (as `mapID`+`areaID`,
   resolved back via `PredefinedMap.getArea`) and `travelPath`
   (`GlobalPathFinder.GlobalPath` gained its own `writeToParcel`/
   `newFromParcel`), guarded behind `fileversion >= 87`.
   `MonsterCollection.writeToParcel`/`readFromParcel` now (de)serialize
   `travellingMonsters` as a new top-level section in `Savegames.java`,
   written/read right after `world.model`. `startTime` is persisted as the
   raw wall-clock timestamp it already was at runtime — deliberately not
   clamped on load, so a monster can be found to have already arrived (or
   even progressed further) if enough real time passed while the app was
   closed, consistent with how the interpolation model already treats
   elapsed time everywhere else.
4. **~~Arrival scripts depended on the player having "activated" the
   destination map.~~ Fixed** (see [PLAN.md](PLAN.md)'s Phase 4, and
   [changelog.md](changelog.md)). `TravelDestinationArea.onMonsterArrived`
   used to only run `arrivalScript` if `mapScriptExecutor != null &&
   controllers != null` — fields only ever set by
   `TravelDestinationArea.setScriptEnvironment(...)`, called exclusively
   from `MapController.prepareScriptsOnCurrentMap()` for whichever map the
   player currently has loaded, so a monster arriving on a map the player
   hadn't visited yet this session silently skipped its arrival script.
   `onMonsterArrived` now takes a `ControllerContext` directly (passed in by
   `MonsterMovementController`, which always has one) and builds its own
   `ConversationStatemachine` on demand via the new
   `MapController.runArrivalScript`, independent of visit history.
   `setScriptEnvironment` and its wiring loop in `prepareScriptsOnCurrentMap`
   were removed as dead code. **Known, deliberately out of scope:** a
   `ReplaceableMapSection` change triggered by an arrival script's rewards
   only gets reapplied if the arrival happens to be on the *current* map —
   there's no live/current tileMap to reapply to for a map the player isn't
   on (see the "Phase 2/3 correction" changelog entry for why), so this
   matches the existing, pre-existing scope limit of the replacement system
   rather than introducing a new one.
5. ~~Multi-step itineraries are unimplemented.~~ **Not a gap — discarded.**
   `TravelDestinationArea.executeNextStep()` was a leftover stub from an
   abandoned idea, never wired into anything; it's been removed (see
   [PLAN.md](PLAN.md)'s Phase 5, and [changelog.md](changelog.md)) rather
   than implemented. Chaining one journey into another doesn't need a
   dedicated itinerary mechanism: an `arrivalScript` phrase can already
   carry a `setDestination` reward to start the next leg, which is exactly
   how `map2_rect1`/`map2_rect2`'s "send him back" flow and
   `traveltester`'s `travelFailedScript` fallback both already work today.
6. ~~`GlobalPathFinder`'s same-map shortcut can miss valid routes.~~
   **Fixed, during Phase 0** (see [PLAN.md](PLAN.md)'s Phase 6 item 1, and
   [changelog.md](changelog.md)). When origin and destination share a map,
   the shortcut now falls through to the full graph search on local-path
   failure — including a route that requires leaving the map and
   re-entering it through a different exit — instead of giving up
   immediately.
7. **Map-to-map transitions cost zero in the planning graph — finalized,
   not a gap.** The relevant comment: `// Transitions between maps are
   currently considered instantaneous (distance 0)`. `travelPath
   .predictedTime`/leg timings therefore only reflect in-map walking
   distance, with no per-transition time penalty. Revisited in
   [PLAN.md](PLAN.md)'s Phase 6 and confirmed as a deliberate, permanent
   design choice rather than an open question — its one cited reason to
   reconsider (Phase 5 itineraries making total journey time more visible)
   no longer applies now that Phase 5 was discarded.
8. **No automated test coverage.** There is no JUnit setup in this project
   at all (this repo's only precedent,
   `app/src/main/resources/.../CombatControllerTest.java.txt`, is a checked-in-but-not-compiled
   reference file, not a running test). Travel/pathfinding correctness today
   is verified purely by hand, using the `traveltest1`/`traveltest2` map pair
   below plus the on-screen debug overlays (see next section — as of
   [PLAN.md](PLAN.md)'s Phase 8, this now includes a cross-map
   `GlobalPathFinder` visualization, not just the single-map local A\*
   overlay). Deliberately left unaddressed for now — see
   [PLAN.md](PLAN.md)'s Phase 7, deferred until there's a JUnit setup for
   the whole project, not just this feature.
9. **Minor loose ends observed in passing:** `onMonsterArrived` clears
   `m.travelDestination` but not `m.travelPath` (harmless today only because
   every travel-related check gates on `travelDestination != null` first —
   a stale `travelPath` would become a live bug if that check were ever
   relaxed or reordered).

## Future extension point: pausing travel for other activities

Not designed or implemented here — this is deliberately just a note on where
a future "activity system" (deciding a travelling monster should stop to
hunt, roam, or do something else instead of continuing its journey) would
attach, written down now because the refinement phase that shaped this
attachment point (see `PLAN_refinement.md`'s R6) is fresh. The activity
system itself needs its own separate design pass; don't treat anything below
as a decision already made about it.

**The attachment point that exists today:** `Monster.travelPauseReason`
(nullable enum, currently the only case is `resting`, from R5) plus
`MonsterMovementController.handleTravelPause`/`beginTravelPause` — the
generalized "stand still for a data-driven number of ticks, correcting the
ETA" mechanism R5's resting originally implemented one-off, then R6
factored out specifically so a second pause reason wouldn't need its own
parallel copy. A future activity system's minimum obligations, reusing this
exact mechanism rather than inventing a second one:

- **Suspend tick-driven movement the same way resting does** — call
  `beginTravelPause(m, SomeNewReason, ticks)` to start, and let
  `handleTravelPause` (already called at the top of
  `determineMonsterNextPosition`'s travel branch) continue it tick by tick.
  No changes needed to that call site itself for a new reason to work.
- **Correct the ETA the same way R5 established, unconditionally.** Any
  activity that delays a journey must grow `travelPath.predictedTime` and
  the current/later legs' `cumulatedDistance` by the paused duration —
  `beginTravelPause` already does this via `correctTravelPathForPause` for
  free, for *any* reason, not just resting. Skipping it (e.g. a future
  reason that bypasses `beginTravelPause` and stops the monster some other
  way) would desync off-screen wall-clock interpolation from on-screen
  reality exactly as the Phase 0 cross-cutting concern warns about.
- **Reason-specific cleanup stays reason-specific.** `onTravelPauseEnded`
  dispatches on `travelPauseReason` for exactly this purpose — resting's own
  follow-up (starting `Monster.travelRestCooldownRemaining`) is scoped to
  the `resting` case there and nowhere else, specifically so it doesn't leak
  into whatever a future activity needs when *its* pause ends. A future
  reason with its own post-pause bookkeeping (e.g. a cooldown before the
  same activity can trigger again) would add its own case the same way,
  not touch resting's.

**Explicitly open questions for that future, separate planning pass — not
answered here:**
- Whether an NPC resumes the *same* `travelPath` after an activity ends, or
  whether the activity fully replaces travel for a while (e.g. abandoning
  the journey rather than pausing it).
- Whether an activity can be interrupted by combat/aggression, and if so
  what happens to the in-progress pause/ETA correction.
- Whether `isTravelling` (`travellingNPC_dataSchema.md` §3.3) needs a third
  state, distinct from both "travelling" and "not travelling", for scripts
  to correctly query a monster that's currently paused for an activity
  rather than either actively travelling or done.

## The manual test bed: `traveltest1` / `traveltest2`

A purpose-built pair of maps exists for exercising this feature by hand, and
**is wired into the normal (non-debug) resource-loading list**
(`res/values/loadresources.xml`), so it's part of every build, not gated
behind `DEVELOPMENT_DEBUGRESOURCES`:

- Reachable in-game via a `mapchange` object literally named `"traveltest"`
  placed in the real map **`crossglen`** (`x=64,y=192`), leading to
  `traveltest1`'s `entry` object. There is currently no shortcut to it from
  the hidden dev "teleport" menu (`DebugInterface`'s `dbg_teleport`
  conversation tree) — you have to walk there via `crossglen`, or add one.
- **`traveltest1`** spawns a single `unique` monster, `traveltester`
  (`monsterlist_traveltest.json`, `moveCost: 5` — faster than the
  default humanoid). Talking to it (`conversationlist_traveltest.json`,
  phrase `traveltester_start`) offers ~19 destinations, each a `setDestination`
  reward:
  - Corners and centers of the map's four quadrants (`rect1..4_corner`,
    `rect1..4_center`) — basic point-to-point routing.
  - Two diagonal strips (`diag_1`, `diag_2`) and a `snake` choke point — for
    exercising the A\* search around non-trivial geometry.
  - `variation_one` / `variation_two` — two single-tile-center destinations.
  - Four `obstacle_1..4` destinations sitting behind a `ReplaceableMapSection`
    pair: `closeexit` (blocks the passage, active while quest `traveltests`
    is at stage 10) and `openexit` (reopens it, active at stage 11) — this is
    the rig that exercises gap #1 above.
  - Two cross-map destinations, `map2_rect1`/`map2_rect2`, on **`traveltest2`**
    reached via `rect1_north`/`rect2_north` `mapchange` objects — the only
    cross-map case in the test bed.
- **Debug visualization:** two independent on-screen overlays, both gated
  behind `DebugInterface` buttons (only present when
  `DEVELOPMENT_DEBUGBUTTONS` is on):
  - `pth` toggles `PathFinder.showPathfinderDebug`, overlaying the *local*
    A\* search's visited tiles (yellow) and resulting path (start/end
    highlighted) on top of whichever map is currently on-screen
    (`MainView.drawPathfinderDebug`) — one map's `PathFinder` at a time,
    whichever ran most recently. Also overlays the "control" tile layer's
    per-tile weight (orange tint plus the numeric value, drawn as a base
    layer under the visited/path overlays) wherever it's non-zero, so a
    route that looks longer than expected can be explained at a glance
    instead of by reading map XML — see the data schema doc's §2.6.
  - `trv` toggles `MonsterMovementController.showTravelDebug`, which (in
    addition to the `TRAVEL:`-prefixed logcat detail described throughout
    this doc) now also drives `MainView.drawTravelDebug`: for every monster
    anywhere in the world with an active `travelDestination`, it highlights
    whichever of that monster's `travelPath` legs land on the currently
    displayed map — the next mapchange exit it's heading for (cyan
    outline), or its final `TravelDestinationArea` if this is the last leg
    (orange outline) — each labeled with the monster's type ID. This is
    what makes `GlobalPathFinder`'s cross-map route visible at a glance
    without cross-referencing logcat by hand, closing the gap the original
    `pth` overlay always had (single-map only).

## Where the branch left off

The final commit, `e820ef314` ("Let's see what this does, shall we"),
reworked `moveMonsters()`'s travelling-monster loop and `spawnMonsterOnMap`
to handle a monster arriving on **any** map (not just the player's current
one) and to correctly resume interpolation after crossing a map exit
mid-leg, and — notably — changed `PathFinder`'s diagonal move cost from 14
back to a uniform 10 everywhere. That last change matters beyond the local
search: the whole travel-interpolation model
(`elapsedDistance = ... * 10 / getMillisecondsPerMove(m)`) assumes **1 tile
of movement = 10 distance units** uniformly; a diagonal step costing 14
would have silently desynced the wall-clock-based interpolation from the
actual tile-by-tile walk. The commit message suggests this was an
in-progress/exploratory change rather than a finished, verified fix.

# Travelling NPCs — Implementation Plan

This plans out the remaining work to take "Travelling NPCs" (see
[travellingNPC.md](travellingNPC.md) for the current-state writeup) from a
working prototype to a finished feature. Each phase lists a goal, concrete
steps, the files it touches, and how to verify it on the existing
`traveltest1`/`traveltest2` test bed. Phases are ordered so that later work
isn't built on top of a foundation that's still silently miscounting
distances, losing state on save, or getting NPCs permanently stuck.

Two decisions carried over from discussion, stated here so later phases
don't relitigate them:

- **Diagonal and orthogonal monster movement cost the same.** This is
  correct, not a bug — monsters move diagonally at the same speed as
  orthogonally in this game. `PathFinder`'s uniform `moveCost = 10` (added in
  the branch's last commit) should stay as-is; nothing in this plan reverts
  it.
- **The travel-timing/placement bug reported on the manual test bed is real
  and blocks everything downstream that depends on trusting what the test
  bed shows you.** It's Phase 0.

---

## Phase 0 — Fix the travelling-monster timing/placement bug ✅ Done

See [changelog.md](changelog.md) for the full account of what was actually
found and fixed (it went several layers deeper than either lead below, plus
a couple of bugs the user found during manual testing along the way).


**Symptom (reported):** a monster that crosses a `mapchange` onto another
map is placed in a position that doesn't match the amount of real time that
passed once the player follows it through shortly after.

**Why first:** every other phase below will be "verified on the traveltest
map" by eyeballing where a monster ends up relative to elapsed time. If that
placement math is untrustworthy, none of that verification is meaningful.

### Step 0.1 — Add instrumentation before attempting a fix

There's currently no visibility into the travel state machine at runtime —
the existing pathfinder debug overlay (`PathFinder.showPathfinderDebug`,
toggled by the `pth` debug button) only visualizes a single map's local A\*
search; it says nothing about `GlobalPathFinder`, elapsed distance, or where
a travelling monster's interpolated position came from. Before changing any
math, add temporary logging (`L.log(...)`, same convention as
`GlobalPathFinder`'s existing `"PATHFINDER: ..."` lines) at the points where
timing decisions are made, so the bug can be measured instead of guessed at:

- In `MonsterMovementController.moveMonsters()`'s travelling-monster loop:
  log `m`, `currentTime`, `elapsedDistance`, `legIndex`, `distanceOnLeg`,
  and whichever branch is taken, every time a travelling monster is
  processed.
- In the exit-crossing code inside `determineMonsterNextPosition` (where
  `world.monsters.addTravellingMonster(m)` is called): log the recalibrated
  `startTime`, the `unitsSoFar` value used to compute it, and
  `System.currentTimeMillis()` at that instant.
- In `spawnMonsterOnMap`: log `startArea`, `toArea`, `distanceOnLeg`, and the
  resulting `spawnPos`.

Reproduce on `traveltest1`/`traveltest2` (send `traveltester` on the
`map2_rect1`/`map2_rect2` destinations — the only cross-map case in the test
bed), follow shortly after, and compare the logged numbers against a manual
expectation (`distance walked = elapsed_ms / getMillisecondsPerMove(m) *
10`). This turns "doesn't look right" into a concrete, quotable discrepancy.

### Step 0.2 — Two concrete leads to check first

Reading the current code surfaced two places where the recalibration of
`Monster.travelPath.startTime` (the anchor that all later wall-clock-based
interpolation is measured from) looks suspect. Both are worth checking
against the Step 0.1 logs before assuming either is *the* bug — they may
both be contributing, or the log data may point somewhere else entirely.

**Lead A — a one-move-interval lag baked into every crossing.**
In `MonsterMovementController`, a monster's local approach only *detects*
that it has reached the exit tile on the `moveMonster` call **after** the
one that physically moved it onto that tile — `moveMonster` always sets
`m.nextActionTime = now + getMillisecondsPerMove(m)` at its very top, before
`determineMonsterNextPosition` gets a chance to notice
`o.position.contains(m.position)` is now true. So there is a full
`getMillisecondsPerMove(m)` of real time between the monster physically
landing on the exit tile and the game actually recognizing the crossing and
recalibrating `startTime`. But the recalibration itself —

```java
long unitsSoFar = m.travelPath.path.get(m.travelPath.currentPosition).cumulatedDistance;
m.travelPath.startTime = System.currentTimeMillis() - (unitsSoFar * getMillisecondsPerMove(m) / 10);
```

— anchors `unitsSoFar` (the *theoretical* distance to that exit) to `now`,
i.e. to the moment the crossing was *processed*, not the moment the monster
*actually arrived* one move-interval earlier. Every crossing could be
introducing a small, systematic, one-tile-equivalent drift. On the compact
traveltest maps with a fast test NPC (`moveCost: 5`, so
`getMillisecondsPerMove = 1200 * 5 / 20 = 300ms`), a `300ms` error is a
large fraction of "the player followed a couple of seconds later," which
would make this very noticeable — plausibly matching what was observed.

**Lead B — the "player leaves the map" safety net skips recalibration
entirely.** `MapController.handleMapEvent`'s `newmap` case sweeps the
current map for any monster with `travelDestination != null` and moves it
straight into `world.monsters.travellingMonsters`:

```java
for (Monster m : world.model.currentMaps.map.monsters) {
    if (m.travelDestination != null) {
        world.model.currentMaps.map.removeMonster(m);
        world.monsters.addTravellingMonster(m);
    }
}
```

Unlike the natural exit-crossing path, this never touches
`m.travelPath.startTime` or `m.travelPath.currentPosition` at all. If a
monster is mid-leg (not yet at its exit tile) when the player happens to
leave the map first, it gets dropped into the wall-clock-interpolation model
using a `startTime` that was last set when it *began* that leg (or even
earlier), with no accounting for wherever it had actually physically walked
to in between. This doesn't match the exact reported repro (monster crosses
*first*, player follows into the *same* new map), but it's a second,
independently real instance of the same underlying problem — "switching a
monster between physically-simulated and time-interpolated tracking must
recalibrate `startTime` from where it *actually* is, not from a stale or
theoretical distance" — and should be fixed alongside Lead A rather than
separately.

### Step 0.3 — Fix and unify

Once the logs confirm which lead(s) are responsible:

- Factor the "hand a monster off from local simulation to the
  `travellingMonsters` pool" logic into one shared method on
  `MonsterMovementController` (e.g. `beginOrResumeTravelling(Monster m)`),
  used by both the natural exit-crossing code and
  `MapController.handleMapEvent`'s safety net, so there's exactly one place
  that computes `startTime`/`currentPosition` and both call sites stay in
  sync as this gets fixed.
- For Lead A: either perform the crossing check immediately after the move
  that lands the monster on the exit tile (same tick), or compute
  `unitsSoFar` relative to when the monster's *last actual move* happened
  rather than `System.currentTimeMillis()` at detection time.
- For Lead B: compute the monster's actual distance-so-far on its current
  leg (e.g. via a local `PathFinder` distance query from the leg's start
  area to `m.position`) instead of assuming it's exactly at a leg boundary.
- Re-run the Step 0.1 repro and confirm the logged `spawnPos` now lines up
  with the manual expectation within one tile.

**Files:** `MonsterMovementController.java`, `MapController.java`.
**Verification:** the Step 0.1 logging, plus visual confirmation on
`traveltest1`/`traveltest2` that a monster followed shortly after crossing
appears close to where it should be for the elapsed time.

---

## Phase 1 — Persist travel state across saves ✅ Done

See [changelog.md](changelog.md) for the implementation writeup. Summary:
`Monster.travelDestination`/`travelPath` and `MonsterCollection
.travellingMonsters` are now all serialized (fileversion 87), and the
wall-clock `startTime` is persisted verbatim rather than clamped — a
deliberate choice per step 4 below.

<details>
<summary>Original planning notes (kept for reference)</summary>

Currently `Monster.travelDestination`/`travelPath` aren't written to the
savegame, and `MonsterCollection.travellingMonsters` isn't serialized at
all (see [travellingNPC.md](travellingNPC.md#known-gaps--unfinished-pieces),
gap 3). A save/load cycle while any NPC is mid-journey will lose it today.

**Steps:**
1. Add `writeToParcel`/read-back support for `Monster.travelDestination`
   (store `mapID` + `areaID`, resolve back to the `TravelDestinationArea`
   object on load the same way `Monster`'s own `area` field is already
   resolved post-`fileversion > 85`) and `Monster.travelPath` (serialize the
   `GlobalPath`: `path` entries, `startingPosition`, `currentPosition`,
   `startTime`, `predictedTime`).
2. Serialize `MonsterCollection.travellingMonsters` itself somewhere in the
   save format — this is a world-level list, not per-map, so it needs a new
   top-level section in `Savegames.java`/`WorldContext`'s save routine (see
   [save-load.md](../save-load.md) for the existing format and where
   world-level state like this is written today) rather than living inside
   `PredefinedMap.writeToParcel`.
3. Bump the savegame `fileversion` and add the usual
   `if (fileversion >= N)` guard, following the existing convention (see any
   of the `LegacySavegameFormatReaderFor*` classes for the pattern).
4. `startTime` is a wall-clock timestamp (`System.currentTimeMillis()`) —
   confirm it's meaningful across a save/load gap (it should be: the
   interpolation model already treats elapsed *real* time as the source of
   truth, so a monster correctly keeps "walking" through however much real
   time passed while the game was closed, consistent with how
   `WorldData`/round timers already treat elapsed wall-clock time — see
   [game-loop.md](../game-loop.md)). Decide deliberately whether that's the
   desired behavior (a monster could arrive while the app was closed) or
   whether arrival should be clamped/deferred to the next tick after load;
   don't let this be an accident of serialization order.

**Files:** `Monster.java`, `MonsterCollection.java`, `Savegames.java`,
possibly `WorldContext.java`.
**Verification:** start `traveltester` travelling to a cross-map
destination, save mid-journey, force-close and reload, confirm the monster
resumes (or has correctly arrived, per whatever decision was made in step 4)
rather than vanishing.

</details>

---

## Phase 2 — Keep the exit-to-exit distance matrix in sync with layout changes ✅ Done

See [changelog.md](changelog.md) for the implementation writeup. Summary:
step 1 (recompute on layout change) was implemented as-is; steps 2 and 3
were deliberately not needed/not done, per their own "only if" conditions
below. **Correction (found while starting Phase 4, fixed then):** the
initial step-1 fix was correctness-inert — `PredefinedMap.calculateDistanceMatrix()`
read `PredefinedMap.tileMap`, a field that `MapController.applyReplacements`
never actually touches (it only mutates the separate
`world.model.currentMaps.tileMap`), so recomputing the matrix reused data
that had never changed. See changelog's "Phase 2/3 correction" entry.

<details>
<summary>Original planning notes (kept for reference)</summary>

`PredefinedMap.calculateDistanceMatrix()` runs once, in the constructor.
`MapController.applyReplacements` (which swaps tiles when a
`ReplaceableMapSection` requirement becomes true) never recomputes it, and
`PredefinedMap.setDistance(...)` — the obvious patch point — is currently
dead code. `traveltest1`'s `closeexit`/`openexit` pair (gated on quest
`traveltests` stages 10/11) exists specifically to exercise this. See
[travellingNPC.md](travellingNPC.md#known-gaps--unfinished-pieces), gap 1.

**Steps:**
1. Call `PredefinedMap.calculateDistanceMatrix()` again after
   `applyReplacements` changes a map's walkability — the simplest correct
   fix, at the cost of recomputing the full exit-to-exit matrix (an O(exits²)
   set of local pathfinding searches) every time a replacement is applied.
   For most maps (few `newmap` exits, replacements are infrequent
   quest/script events, not per-tick) this should be cheap enough; profile
   on a map with many exits before assuming otherwise.
2. If profiling shows that's too slow somewhere, use the existing (currently
   unused) `setDistance(id1, id2, distance)` to patch only the specific
   exit pairs affected by the changed region, instead of a full recompute —
   but only do this if step 1 actually proves to be a problem; don't add
   the complexity speculatively.
3. Any monster with a `travelPath` already computed against the *old*
   matrix should have its route considered potentially stale. At minimum,
   decide and document what happens to an in-flight `GlobalPath` when the
   map it's routed through changes layout — recomputing every in-flight
   monster's path on every replacement is probably overkill; a reasonable
   default is "the path was valid when planned; only the live local walk
   (which already reads current tile data) needs to cope with a
   now-blocked tile," which is Phase 3's job.

**Files:** `PredefinedMap.java`, `MapController.java`.
**Verification:** on `traveltest1`, advance quest `traveltests` to stage 10
(closing `closeexit`), send `traveltester` toward an `obstacle_*`
destination that requires the now-closed route, confirm the computed
route/behavior reflects the closed wall; reopen at stage 11 and confirm it
updates again.

</details>

---

## Phase 3 — Fail visibly instead of getting stuck forever ✅ Done

All four steps are done — see [changelog.md](changelog.md), including a
follow-up fix once live testing found that step 2's retry/reroute logic
never triggered when the *player* (rather than terrain) was the
obstruction, because the player was invisible to the pathfinding graph.
Step 3 was resolved with a static, map-author-set `monstersCanPass` flag on
the `keyarea` object itself, rather than teaching monsters to evaluate
`Requirement` (which is usually checked against the player, not a monster,
so mostly doesn't apply) — see changelog for the reasoning. Step 4 was
resolved differently than the plan originally framed it: instead of a
"travel failed" script on the *destination* (`TravelDestinationArea`), it's
a standing `travelFailedScript` field on the *traveling `Monster`* itself,
set via a new `setTravelFailedScript` reward — since how a specific NPC
reacts to failing a journey is a property of that NPC, not of wherever it
was trying to go. See changelog for the design reasoning and the
recursion caveat if a fallback script's own destination is also
unreachable.

<details>
<summary>Original planning notes (kept for reference)</summary>

Two related failure modes currently leave a monster silently wedged with
`isTravelling` permanently `true` and no error anywhere (gaps 1 and 2 in
[travellingNPC.md](travellingNPC.md#known-gaps--unfinished-pieces)):

- `GlobalPathFinder.findPath` returns an empty, unreachable path
  (`predictedTime = -1`) when there's genuinely no route — but
  `determineMonsterNextPosition` doesn't check for this before falling into
  the "final leg reached" branch, where it ends up asking the *wrong* map's
  local pathfinder to reach coordinates from a *different* map.
- A live route becomes blocked mid-walk (e.g. by the Phase 2 scenario, or
  another monster/the player parked on the only path) — `findPathFor` fails,
  and the `else` branch is a bare `// Path blocked, do something to clear
  path TODO` with no recovery. **Confirmed live on the test bed**: standing
  in the NPC's path is enough to make it simply stop moving for good — it
  never tries to route around the obstruction.

**Steps:**
1. In `beginTravel`, check `travelPath.predictedTime < 0` right after
   calling `globalPathFinder.findPath(...)` and treat it as a failed travel
   request: clear `m.travelDestination`/`m.travelPath` back to `null`
   instead of leaving them set, and fire some observable signal (a new
   `MonsterMovementListener` callback, or at minimum an `L.log(...)` under
   `DEVELOPMENT_VALIDATEDATA`, matching the convention used elsewhere for
   "this data doesn't make sense" warnings — see e.g.
   `PredefinedMap`'s duplicate-ID warnings). This alone fixes gap 2, since
   `m.travelDestination == null` means `isTravelling` correctly reports
   `false` again.
2. In `determineMonsterNextPosition`'s `else` branch (path blocked
   mid-leg), don't just wait-and-retry the *same* route — actually reroute.
   `findPathFor`/`PathFinder.findPathBetween` already treats another
   monster/the player occupying a tile as unwalkable for that search (see
   `monsterCanMoveTo`/`map.isWalkable(nextStep, m)`), so a blocking actor
   simply removes that tile from the graph; a fresh `findPathFor` call
   *should* naturally find a detour around it **if one physically exists**.
   Confirm that's actually what happens (vs. the search just failing
   outright because it never retries at all right now) before assuming any
   change is needed here beyond "stop giving up after one failed call" —
   i.e. this may turn out to mostly be the missing-retry bug from point 1's
   neighborhood, not a routing-algorithm gap. Where no detour exists (a
   corridor with only one tile of width, the player standing in a doorway),
   waiting with a bounded retry/backoff (reusing
   `cancelCurrentMonsterMovement`'s pattern) and eventually failing per
   step 1 is the correct fallback — an NPC shouldn't be able to shove the
   player out of the way.
3. This almost certainly needs to be considered together with **key-gated
   areas** (`keyarea` map objects, `Requirement`-gated passage — see
   [maps-world.md](../maps-world.md) and [quests.md](../quests.md)) rather
   than in isolation: a travelling monster's route can legitimately pass
   through a `keyarea`, and today's local walkability checks
   (`monsterCanMoveTo`) already treat `keyarea` as impassable unconditionally
   for monsters (no requirement check at all, unlike the player's
   `canEnterKeyArea`) — so before improving *obstacle* rerouting, decide
   whether monsters should be able to use key-gated passages at all, and if
   so, whether `GlobalPathFinder`'s distance computations (which currently
   have no concept of key requirements) need to account for a route being
   conditionally walkable depending on quest/inventory state. Scope this out
   properly rather than bolting it on inside the obstacle-avoidance fix.
4. Surface both failure modes to script authors: consider whether
   `arrivalScript` should have a counterpart (a "travel failed" script hook)
   so quest/dialogue content can react instead of the NPC just quietly
   stopping.

**Files:** `MonsterMovementController.java`.
**Verification:** manually construct (or temporarily script) a destination
on an unreachable map/area and confirm the monster fails cleanly instead of
freezing with `isTravelling == true`; block `traveltest1`'s only route to an
`obstacle_*` destination without ever opening it and confirm the retry/fail
behavior instead of a silent stall.

</details>

---

## Phase 4 — Make arrival scripts reliable regardless of map visit history ✅ Done

See [changelog.md](changelog.md). Implemented via step 1's second option
(inject `ControllerContext` into `onMonsterArrived` directly, rather than
giving every map its own `ConversationStatemachine`). Step 2's manual
regression case wasn't newly exercised in-session (the existing test bed's
only cross-map destinations, on `traveltest2`, had already been visited
earlier this session before this gap was ever discovered, which is exactly
why it went unnoticed) — see changelog for the recommended way to verify it
(fresh save, or force-quit before ever loading `traveltest2`). Step 3
re-confirmed: `onMonsterArrived` still clears `travelDestination` as its
first action regardless of the script path taken, so a script can't
double-fire.

<details>
<summary>Original planning notes (kept for reference)</summary>

`TravelDestinationArea.onMonsterArrived` only runs `arrivalScript` if
`mapScriptExecutor`/`controllers` are non-null, and those are only set by
`MapController.prepareScriptsOnCurrentMap()` for whichever map the player
currently has loaded (gap 4). A monster arriving on a map the player hasn't
visited yet this session silently skips its arrival script.

**Steps:**
1. Decouple running a script phrase from needing a *live, current-map*
   `ConversationStatemachine`. Either:
   - give every `PredefinedMap` (or every `TravelDestinationArea`) its own
     `ConversationStatemachine` wired up once at load time instead of only
     the current map's, or
   - make `onMonsterArrived` request a `ConversationController`/
     `ControllerContext` reference it always has (e.g. injected once at
     construction, the way other model objects reach controllers), rather
     than depending on a per-current-map setup call.
2. Either way, add a regression case to the manual test bed: an
   `arrivalScript` on a `TravelDestinationArea` that lives on a map the
   player has deliberately never visited, confirm it still runs.
3. Cross-check with Phase 1: if arrival can now happen "off-screen" more
   reliably (including across a save/load gap), make sure the script only
   runs once per arrival (it already should, since `onMonsterArrived` clears
   `travelDestination` — just re-confirm this holds once arrival scripts can
   fire without ever having a `Monster` materialized on-screen).

**Files:** `TravelDestinationArea.java`, `MapController.java`,
`ConversationController.java`.
**Verification:** new destination + arrival script on a traveltest map the
player hasn't opened; send `traveltester` there without visiting it first;
confirm the script runs.

</details>

---

## Phase 5 — Multi-step itineraries ❌ Discarded

`TravelDestinationArea.executeNextStep()` was a leftover, never-wired-in
stub from an idea that was abandoned before this branch's work began — not
an intentionally deferred feature. The user confirmed it should not be
built and the stub (plus its dangling "// Iterate over steps once
implemented" call site comment in `onMonsterArrived`) was removed rather
than implemented — see [changelog.md](changelog.md). "Goto destArea2"-style
chaining doesn't need a dedicated itinerary mechanism anyway: it's already
achievable today by having an `arrivalScript` phrase carry a `setDestination`
reward to the next leg (exactly how `map2_rect1`/`map2_rect2`'s "send him
back" flow and `traveltester`'s `travelFailedScript` fallback already work).

<details>
<summary>Original planning notes (kept for reference, not acted on)</summary>

This was going to be the biggest net-new feature in this plan, not a
bugfix, planned to come after the correctness work above rather than
before it:

1. Design the itinerary data format first (small addition, e.g. a list of
   steps on `TravelDestinationArea` or a new small model type) — a step is
   at minimum "wait N (game time units)" or "travel to destination X",
   matching the two examples already named in the existing TODO comment.
2. Wire `onMonsterArrived` to, instead of just stopping, check for a next
   step and either start a wait timer or call `beginTravel` again toward the
   next destination.
3. This would have directly exercised (and further validated) Phase 0's
   fix — a multi-leg itinerary means multiple recalibration events in
   sequence, so any remaining drift compounds and becomes easy to spot.
4. Extend the `traveltest1`/`traveltest2` conversation with at least one
   multi-step itinerary option, reusing the existing pattern of one
   dialogue reply per test case.

**Files:** `TravelDestinationArea.java`, `MonsterMovementController.java`,
possibly a new small model class for itinerary steps.
**Verification:** a new traveltest dialogue option that sends `traveltester`
through 2+ destinations in sequence, unattended.

</details>

---

## Phase 6 — Algorithmic completeness in `GlobalPathFinder` ✅ Done

Lower urgency than the phases above — these were documented `TODO`s in the
code affecting *route optimality*, not correctness of what already gets
computed:

1. ~~**Same-map shortcut can miss valid routes**~~ **Done, during Phase 0**
   (see [changelog.md](changelog.md)): `findPath`'s same-map shortcut now
   falls through to the full graph search on local-path failure instead of
   returning "no path" immediately.
2. ~~**Map transitions cost zero**~~ **Finalized as-is, no change** — the
   plan's own reason to reconsider ("once Phase 5 itineraries make total
   journey time more visible to players") no longer applies now that
   Phase 5 was discarded. Zero-cost transitions stay a documented, correct
   design choice, not an oversight.
3. ~~**`PathFinder`'s `heuristic()` used a stale octile formula**~~
   **Fixed, then refined twice, confirmed by the user** (see
   [changelog.md](changelog.md)): changed to match the uniform move-cost
   model, which surfaced a follow-up cosmetic issue — equal-cost
   diagonal/orthogonal combinations were resolving arbitrarily (visible as
   zig-zagging, and as diagonal moves clustering at one end of a leg
   instead of interleaving evenly). A bounded straight-line tie-breaker was
   added to fix both — the first version turned out to be a complete no-op
   for any search smaller than the largest possible map (an
   integer-division-by-a-worst-case-constant bug), caught by the user
   re-testing rather than assuming it worked; corrected to normalize by the
   search's own distance instead of a fixed constant. User confirmed the
   corrected version works — path is shortest and interleaves naturally
   during a forced detour, with one small accepted cosmetic gap (abrupt
   pre-detour turning) tracked separately below rather than blocking this
   item.

**Files:** `PathFinder.java`.
**Verification:** user-confirmed on-device on `traveltest1`
(`rect1_corner` → `rect3_corner`, a forced obstacle detour) after the
tie-breaker correction. See "Known cosmetic polish items" below for the
one remaining minor gap.

---

## Phase 7 — Automated tests

There is no JUnit setup anywhere in this project (gap 8) — the only
precedent, `CombatControllerTest.java.txt`, is a checked-in-but-not-compiled
reference file. Setting up a real test harness is a prerequisite, not
optional, if this feature is going to stay correct as it grows through the
phases above.

**Steps:**
1. Decide how to introduce a runnable JUnit dependency without disrupting
   the existing build (Gradle module config, `androidTest` vs. plain `test`
   source set depending on whether `PathFinder`/`GlobalPathFinder` can run
   without the Android framework — they look framework-free enough to unit
   test directly, `PredefinedMap`/`Monster` less so).
2. Start with `PathFinder`/`GlobalPathFinder` unit tests using small,
   synthetic map data (not full TMX loading) — deterministic distance/route
   assertions are exactly what a hand-verified pathfinding algorithm needs
   and are cheap to write.
3. Once persistence (Phase 1) exists, add a save/load round-trip test for a
   mid-travel `Monster`.
4. Leave `traveltest1`/`traveltest2` in place regardless — manual/exploratory
   testing on a real map remains valuable for anything involving rendering,
   timing, and player-follows-monster scenarios that are awkward to unit
   test.

**Files:** new test source set/module config, new test classes.

---

## Phase 8 — Stretch goals (not required to call this feature "done")

- **Autonomous travel triggers.** Today the *only* way to start a monster
  travelling is a conversation `setDestination` reward (see
  travellingNPC.md's "How to trigger travel today"). A scheduled/random
  trigger (e.g. "shopkeeper walks home at night") would need its own design
  pass and is out of scope for finishing the current mechanism.
- **Dev-menu shortcut into the test bed.** The hidden `teleport` debug menu
  doesn't currently include a shortcut to `traveltest1`/`traveltest2` — a
  one-line addition to `conversationlist_debug.json`'s `dbg_teleport` menu
  would make manual verification of every phase above faster.
- ~~**Debug visualization for `GlobalPathFinder`.**~~ **Done** (see
  [changelog.md](changelog.md)). Assessed at the user's request: text
  logging already existed (`beginTravel`'s full leg dump under
  `showTravelDebug`), but there was no on-screen visual, and
  `GlobalPathFinder`'s own two log lines weren't gated behind
  `showTravelDebug` like the rest of the travel logging (a minor
  inconsistency, fixed alongside). Added `MainView.drawTravelDebug`,
  reusing the existing `pth`-overlay's visual language: for every monster
  anywhere in the world with an active `travelDestination`, highlights
  whichever of its `travelPath` legs land on the currently-displayed map
  (next mapchange exit in cyan, final `TravelDestinationArea` in orange),
  labeled with the monster's type ID. Toggled by the existing `trv` button
  (`MonsterMovementController.showTravelDebug`) — no new debug button
  needed.

---

## Known cosmetic polish items (pre-release)

Accepted-for-now gaps in the feature itself, worth a deliberate look before
release rather than being forgotten as "todo" comments in code:

- **Abrupt pre-detour direction change in `PathFinder`'s A\* paths.** Since
  the Phase 6 heuristic fix (see [changelog.md](changelog.md)), a monster's
  path is provably shortest and interleaves diagonal/straight moves
  naturally *during* a forced detour around an obstacle — but it still
  walks straight toward the obstacle right up until the last possible
  moment, then turns sharply, instead of easing into the turn earlier.
  Confirmed and explicitly accepted by the user (traveltest1,
  `rect1_corner` → `rect3_corner`). Root cause: the tie-breaker minimizes
  distance from the straight line between start and destination, which is
  a different objective from minimizing turn sharpness/curvature — the two
  actively pull in different directions, so fixing this would need either
  a turn-angle-penalizing heuristic term or a post-process path-smoothing
  pass, not a small tweak to the current tie-breaker. Low priority, purely
  cosmetic (path is already shortest and collision-correct); revisit only
  if it's still bothersome once more of the map roster is exercised.

## Pre-release regression checklist

Manual checks against **existing, pre-travelling-NPC game content** that
happens to exercise code this branch modified — not part of the
travelling-NPC feature itself, but easy to silently break while touching
shared movement/pathfinding code. Run before shipping this branch.

- **Tunlon's behavior when he turns hostile after his sheep is killed**
  (`bwmfill` area, quest `bwmfill` stage 12: "Tunlon got furious when he saw
  that I killed his sheep."). Once aggressive, Tunlon (`tunlon`/`tunlon2` in
  `monsterlist_bwmfill.json`) chases the player across ground that includes
  a `keyarea`. Confirm this still works exactly as before Phase 3 step 3's
  `monstersCanPass` change. Note: both of Tunlon's spawn objects
  (`bwmfill3.tmx`) already set `ignoreAreas="true"`, which bypasses the
  entire `newmap`/`rest`/`keyarea` event-object check in `monsterCanMoveTo`
  regardless of `monstersCanPass` — so by inspection this scenario doesn't
  actually route through the changed code path at all, and should be
  unaffected. Confirm that holds in practice rather than relying on that
  reading alone, since aggression-triggered movement is exactly the kind of
  interaction that's easy to get subtly wrong.

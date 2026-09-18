# Travelling NPCs — Changelog

Chronological record of the work done against [PLAN.md](PLAN.md), starting
with Phase 0 (and a few items pulled forward from later phases once they
turned out to be blocking), then continuing phase-by-phase. Each entry names
the symptom/goal that motivated it, the root cause or design as diagnosed,
the fix, and the files touched. All entries are verified via
`gradlew :app:compileDebugJavaWithJavac` after every change, and via manual
testing on `traveltest1`/`traveltest2` where applicable.

## Debug tooling added first

Before touching any logic, added the instrumentation Phase 0 called for, so
every fix below could be checked against real numbers instead of guessed at:

- `MonsterMovementController.showTravelDebug` — a togglable static flag
  (mirroring `PathFinder.showPathfinderDebug`'s existing convention), wired
  to a new `trv` button in `DebugInterface.java`.
- Log lines (all gated on `showTravelDebug`, prefixed `TRAVEL:`) added at
  every decision point in the travel lifecycle: entering the travelling
  pool, each tick's elapsed-distance/leg computation, each
  `spawnMonsterOnMap` placement, `beginTravel`'s full computed plan (every
  leg's distance, total `predictedTime`, expected duration in ms), actual
  arrival (`TravelDestinationArea.onMonsterArrived`), and travel-approach
  pathfinding failures falling through to the wander fallback.

**Files:** `MonsterMovementController.java`, `DebugInterface.java`,
`TravelDestinationArea.java`.

## 1. Monster kept "disappearing and reappearing" on the mapchange tile

**Symptom:** sending the `traveltester` NPC across a map exit made it
flicker in and out of existence right at the doorway instead of cleanly
crossing.

**Root cause:** `determineMonsterNextPosition`'s `return;` after handing a
monster off to the travelling pool only exited that method — the caller,
`moveMonster`, had no way to know the monster was already fully handled, and
fell straight through to `monsterCanMoveTo`/`moveMonsterToNextPosition`
using a stale `m.nextPosition`, re-animating a monster that had just been
removed from the map's monster list. This was a pre-existing bug, not
something introduced this session.

**Fix:** `determineMonsterNextPosition` now returns `boolean` — `true` means
"already fully handled here (travel hand-off or arrival), do not touch
`nextPosition`"; `false` means "proceed normally." `moveMonster` checks this
and returns immediately on `true`.

**Files:** `MonsterMovementController.java`.

## 2. Monster "appears then just sits there" instead of progressing

**Symptom:** after fix #1, the flicker stopped, but the monster would
appear near a mapchange and then never move again, with the debug log
showing `unitsSoFar=0` on every cycle.

**Root cause:** `PathFinder.findPositionOnPath` calls `findPathBetween`,
which reports "`from` already overlaps `to`" the same way as "no path
exists" (both return `false`). Since the monster's leg started exactly on
the exit tile it had just crossed through, `findPositionOnPath` kept
returning the unchanged starting position no matter how far the elapsed
time said it should have progressed — placing it right back where it
started, which immediately re-triggered "at the exit" detection and looped.

**Fix:** `findPositionOnPath` now checks `from.intersects(to)` up front and
returns `to.topLeft` (arrived) in that case, before reaching the ambiguous
`findPathBetween` return value.

**Files:** `PathFinder.java`.

## 3. Same ambiguity inside `GlobalPathFinder` itself

Found while investigating #2: all three of `GlobalPathFinder`'s own
`findPathBetween` call sites had the identical blind spot — gating on the
boolean return instead of `getLastPathDistance()` (the authoritative
signal: `-1` = unreachable, `>=0` = a real distance including `0` for
"already there").

**Fix:** all three call sites (same-map shortcut, initial exit-seeding
loop, final-leg distance-to-destination) now read `getLastPathDistance()`
directly. Confirmed `PredefinedMap.fillMapchangeDistances` already did this
correctly and needed no change.

**Files:** `GlobalPathFinder.java`.

## 4. Travel timing endlessly resetting (root cause of #2, more precisely)

**Symptom:** even after fixes #2–#3, a monster crossing into a new leg kept
logging `distanceIntoLeg=0`/`unitsSoFar=0` forever, never advancing.

**Root cause:** `GlobalPath.startingPosition` was never actually frozen —
`Monster.rectPosition` is built as `new CoordRect(this.position, ...)`, and
`CoordRect`'s constructor stores its `Coord` argument by reference, not by
value. `beginTravel` passed `m.rectPosition` straight into
`GlobalPathFinder.findPath`, whose result stored `fromPosition.topLeft` —
the *same* `Coord` object as `m.position` — as `startingPosition`, with no
copy. So `startingPosition` silently tracked the monster's current position
forever instead of the journey's actual start, making every leg-0 distance
measurement report "you haven't moved" even after a full local walk.

**Fix:** `GlobalPath`'s constructor now makes a defensive copy
(`new Coord(startingPosition)`) — matching a pattern already used elsewhere
in this codebase for the same reason
(`moveMonsterToNextPositionWithCallback`'s `new CoordRect(new
Coord(m.position), ...)` before mutating `m.position`).

**Files:** `GlobalPathFinder.java`.

## 5. Unified travel-pool hand-off (`enterTravellingPool`)

Alongside fix #1, unified the two places a monster enters the
wall-clock-interpolated travelling pool (natural exit-crossing, and
`MapController`'s "player leaves the map" safety net) into one method,
`enterTravellingPool`, which recalibrates `travelPath.startTime` from the
monster's *actual* current position (measured live via local pathfinding
from the leg's start) rather than assuming it's exactly at a leg boundary.
This fixed two related leads at once:

- **Lead A:** a one-tick processing lag between a monster physically
  landing on an exit tile and the game recognizing the crossing, which
  anchored the recalibration to the wrong instant.
- **Lead B:** the safety net previously didn't recalibrate `startTime` at
  all when yanking a mid-leg monster off the map, leaving it anchored to a
  stale value.

Also extracted `getLegStartArea` (previously duplicated inline in
`spawnMonsterOnMap`) as a shared helper used by both.

**Files:** `MonsterMovementController.java`, `MapController.java`.

## 6. Tick rate silently capping monster move speed

**Symptom:** even with fixes #1–#5, real journeys consistently took much
longer than `beginTravel`'s predicted ETA (measured up to ~74% longer in
one trace).

**Root cause:** `traveltester`'s `moveCost: 5` with `maxAP: 20` gives a
nominal `getMillisecondsPerMove` of 300ms/tile — but `GameRoundController`
only calls `moveMonsters()` once per `Constants.TICK_DELAY` (500ms). A
monster whose stats claim 300ms/tile can never actually move that fast in
practice; the interpolation model was predicting a speed the tick loop
could never deliver.

**Fix:** `getMillisecondsPerMove` now returns
`Math.max(nominal, Constants.TICK_DELAY)`. This affects both travel-timing
math and this monster's own ordinary move/wait scheduling (not combat
pacing, which uses a separate method). Verified against a real trace: the
old prediction (3900ms) was 42% under the actual duration (6774ms); the
clamped prediction (6500ms) came within ~4%.

**Files:** `MonsterMovementController.java`.

## 7. Same-map "warp" mapchanges not used by travelling monsters

**Symptom:** user noticed that a large chunk of remaining timing error came
from an 8-tile walk that a 0-cost same-map warp (`rect1_west` ↔
`rect3_west`, a teleport pair, not a real cross-map exit) should have
shortened — but the NPC walked the long way, ignoring the warp entirely.

**Root cause:** `GlobalPathFinder`'s Relaxation Step 2 already treats
crossing *any* `newmap` object as an instantaneous, zero-cost hop —
including one whose `map` property points back at its own map (a same-map
warp). So the *planned* distance already assumed the monster would
teleport. But nothing on the monster's execution side knew to actually
*do* that — walking onto such a tile during normal travel-approach did
nothing special, since only genuinely-different-map crossings were handled
(via `enterTravellingPool`).

**Design decision:** discussed two options (make the NPC use the warp vs.
make the planner avoid it) and chose to make the NPC use it, since the warp
is a real, working mechanic the player already benefits from
(`MapController.handleMapEvent`'s `newmap` case doesn't distinguish
same-map from cross-map at all), and the fix is smaller/lower-risk than
teaching Dijkstra to avoid a legitimate shortcut.

**Fix:** in `determineMonsterNextPosition`'s "reached this leg's target
mapchange" check, when that mapchange's `map` property equals the monster's
own current map, it now instantly relocates the monster to the paired
`place` object's position (preserving offset within the object's footprint,
mirroring `MovementController.placePlayerAt`'s convention) and advances to
the next leg — instead of routing through `enterTravellingPool`. Falls back
to the old cross-map behavior (with a warning log) if the map data is
malformed (missing `place` object).

**Files:** `MonsterMovementController.java`.

## 8. Warp not used in the *other* travel direction

**Symptom:** fix #7 worked for `traveltest2 → traveltest1`, but the reverse
direction still walked the long way, ignoring the same warp pair.

**Root cause:** `GlobalPathFinder`'s path-reconstruction backtracking
collapses consecutive same-map exits into a single leg to keep the leg list
concise — but it did so blindly, unable to distinguish "connected by a
normal walk" (safe to collapse; local pathfinding reaches it automatically)
from "connected by a warp crossing" (must **not** collapse; the far side of
a warp isn't walkable at all and needs its own explicit leg). In one travel
direction the warp happened to land exactly on a leg boundary the algorithm
already preserved; in the other, it got silently absorbed into a longer,
walk-only leg.

**Fix:** added a `viaCrossing` tracking set, updated alongside `previous` in
both relaxation steps (Step 1 walk-based updates clear it, Step 2
crossing-based updates set it). The backtracking loop now stops collapsing
as soon as it would skip past a warp destination, and instead emits a leg
for wherever that warp actually departs from. Hand-traced against both
travel directions before compiling to confirm the reconstructed leg
structure matched what was already working, plus the newly-fixed direction.

**Files:** `GlobalPathFinder.java`.

## 9. Same-map destination behind a barrier reported as unreachable

**Symptom:** commanding the NPC (while on `traveltest2`) to travel to a
different destination also on `traveltest2`, but separated by a physical
barrier only passable by leaving and re-entering via `traveltest1`, made it
not move at all.

**Root cause:** this was the pre-existing, already-documented TODO in
`GlobalPathFinder.findPath`'s same-map shortcut ("there is not always a
local path -> still do global!"). When `fromMapName.equals(toMapName)`, the
code only ever tried one direct local path and returned "unreachable"
immediately if that failed, never falling through to the full graph search
— even though that search already works correctly regardless of whether
the start and target map happen to be the same one.

**Fix:** the same-map shortcut now falls through into the existing full
graph search when the direct local path fails, instead of returning `-1`.

**Files:** `GlobalPathFinder.java`.

## 10. NPC appears a beat late after a map transition

**Symptom:** switching to the map a travelling monster should already be
on, the monster only appeared after a short, visible delay rather than
being present as soon as the map rendered.

**Root cause:** travelling-monster placement was only (re-)evaluated inside
`moveMonsters()`, which runs on its own independent tick timer
(`Constants.TICK_DELAY` = 500ms), not synchronized with when the player's
own map transition actually completes.

**Fix:** extracted the "should this travelling monster be materialized on
the current map right now" logic out of `moveMonsters()`'s loop into a
shared method, `tryPlaceTravellingMonster`. Added
`syncTravellingMonstersOntoCurrentMap()` — the same check without the
per-tick `nextActionTime` throttle — called once from
`MovementController.prepareMapAsCurrentMap` (the single method every map
transition funnels through) right after the new map becomes current.

**Files:** `MonsterMovementController.java`, `MovementController.java`.

## 11. Monster stranded when a new journey starts while off-screen

**Symptom:** after an NPC "virtually" arrived at a destination on a map the
player wasn't on, using the arrival dialog's "send back" option computed a
new travel path but the NPC never actually started moving — until the
player happened to visit the map it was physically sitting on.

**Root cause:** `beginTravel` only sets `travelDestination`/`travelPath`
fields; it never checked whether the monster was still a member of some
map's `monsters` list that isn't the player's *current* map. Since
`moveMonsters()`'s local-simulation pass only walks the current map's
monster list, and the monster wasn't in the travelling pool either (it had
just been removed from it on arrival), nothing ever called
`moveMonster`/`determineMonsterNextPosition` for it again.

**Fix:** `beginTravel` now checks whether the monster's current map differs
from the player's loaded map, and if so, hands it straight to the
travelling pool via the same `enterTravellingPool` used elsewhere (safe to
reuse here since the journey just started — its live-distance measurement
correctly computes "0 progress so far").

**Files:** `MonsterMovementController.java`.

## Phase 1 — Persist travel state across saves

**Goal:** a save/load cycle while an NPC is mid-journey should resume the
journey, not silently lose it (`travellingNPC.md` gap 3).

**Implementation:**

- `GlobalPathFinder.GlobalPath` gained `writeToParcel`/`newFromParcel`: each
  leg (`mapID`, `destinationID`, `distance`, `cumulatedDistance`), the frozen
  `startingPosition`, `currentPosition`, `startTime`, and `predictedTime` are
  all written out and reconstructed directly — no live object references to
  resolve, since a leg only needs map/destination *names*.
- `Monster.writeToParcel`/the deserialization constructor now (de)serialize
  `travelDestination` (as `mapID`+`areaID`, resolved back through
  `PredefinedMap.getArea` the same way `Monster.area` already was) and
  `travelPath` (via the new `GlobalPath` parcel methods), guarded behind a
  new `fileversion >= 87` (the next version after 86, which the branch's
  prior `currentMapID`/`area` persistence work already claimed via
  `fileversion > 85`).
- `MonsterCollection` gained `writeToParcel`/`readFromParcel` for
  `travellingMonsters` (the world-level pool of monsters currently abstract
  mid-crossing, not present in any map's monster list) and a
  `resetForNewGame()` that clears it, wired into
  `WorldContext.resetForNewGame()` alongside the existing `maps
  .resetForNewGame()` call — otherwise starting a new game in the same app
  process (no restart) could leak a previous game's in-flight travellers.
- `Savegames.saveWorld`/`loadWorld` write/read this as a new top-level
  section, right after `world.model`: `world.monsters.writeToParcel(dest)` /
  `world.monsters.readFromParcel(src, world, header.fileversion)` — a
  world-level list doesn't belong inside `PredefinedMap`'s per-map format,
  matching the plan's original note.

**Design decision (plan step 4):** `startTime` is persisted as the raw
`System.currentTimeMillis()` value it already was at runtime, deliberately
*not* clamped or deferred on load. The interpolation model already treats
elapsed real time as ground truth everywhere else this session (that's the
whole basis of Phase 0's fixes) — persisting the same timestamp and letting
`elapsedDistance = (now - startTime) * 10 / getMillisecondsPerMove(m)`
naturally account for however long the app was closed is the behavior
consistent with that design, not a special case. Concretely, this means a
monster can be found already arrived (or further along) immediately after
loading a save if enough real time passed meanwhile — matching what would
have happened had the app simply stayed open. No extra clamping logic was
needed for this to work correctly: `syncTravellingMonstersOntoCurrentMap()`
(added in Phase 0, item 10) already runs immediately after
`prepareMapAsCurrentMap` on load and re-evaluates every travelling monster
against however much time has actually elapsed, regardless of how large that
gap is.

**Files:** `GlobalPathFinder.java`, `Monster.java`, `MonsterCollection.java`,
`WorldContext.java`, `Savegames.java`.
**Verification:** compiles clean; user-confirmed on-device with a monster
mid-journey via both a soft reload (return to main menu, re-enter) and a
full app close/reopen — travel state survives both.

## Phase 2 — Keep the exit-to-exit distance matrix in sync with layout changes

**Goal:** `MapController.applyReplacements` swapping a map's tiles (e.g. the
`traveltest1` `closeexit`/`openexit` `ReplaceableMapSection` pair) should
invalidate the exit-to-exit distances `GlobalPathFinder` plans routes from,
instead of leaving `PredefinedMap`'s one-time constructor snapshot silently
stale (`travellingNPC.md` gap 1, the matrix-staleness half of it).

**Implementation (plan step 1 — the simple full-recompute fix):**
`MapController.applyReplacements` now tracks a separate `layoutChanged`
flag, set only when `tileMap.applyReplacement(replacement)` actually runs
(not by the pre-existing `hasUpdated` flag, which also covers a color-filter
change or a hash-only diff — neither affects walkability). If any
replacement was applied, it calls `map.calculateDistanceMatrix()` again
before returning, so the next `GlobalPathFinder.findPath` call for that map
sees current distances.

**Steps 2 and 3 — deliberately not done:**

- Step 2 (patch only the affected exit pairs via the existing but unused
  `PredefinedMap.setDistance`, if full recompute proves too slow) — the plan
  explicitly says only do this if profiling shows a problem. `traveltest1`
  has few `newmap` exits and replacements fire from infrequent quest/script
  events, not per-tick, so a full recompute costs nothing noticeable; adding
  the patch-only path now would be speculative complexity with no measured
  need.
- Step 3 (deciding what happens to an in-flight monster's already-computed
  `GlobalPath` when the map it routes through changes layout) — the plan
  itself defers this to Phase 3 ("the live local walk, which already reads
  current tile data, needs to cope with a now-blocked tile"). No change was
  made here; `travellingNPC.md` gap 1 was split so the still-open half (a
  blocked live walk has no recovery) stays clearly flagged for Phase 3
  rather than getting lost under the now-fixed matrix-staleness half.

**Files:** `MapController.java`.
**Verification:** compiles clean. Manual on-device verification (advance
`traveltests` to stage 10/11 on `traveltest1` and confirm routing to
`obstacle_*` reflects the closed/reopened wall) still to be done by the
user.

## Phase 3 (steps 1 and 2) — Fail visibly instead of getting stuck forever

**Goal:** two related failure modes left a monster silently wedged with
`isTravelling` permanently `true`: a genuinely unreachable destination
(`travellingNPC.md` gap 2), and a live route blocked mid-walk by an
obstruction or a layout change (gap 1's still-open half, discovered by the
user live-testing Phase 2's distance-matrix recompute — closing a wall via
`traveltest1`'s `traveltest_closeo`/`traveltest_openo` toggle left an
in-flight monster stuck bumping the closed wall instead of rerouting or
giving up).

**Step 1 — fail fast on a genuinely unreachable destination:**
`beginTravel` now checks `m.travelPath.predictedTime < 0` immediately after
`globalPathFinder.findPath(...)`. On failure it clears
`m.travelDestination`/`m.travelPath` back to `null` (so `isTravelling`
reports `false` again immediately) and logs a warning
(`DEVELOPMENT_VALIDATEDATA`/`showTravelDebug`) instead of leaving the
monster in a state `determineMonsterNextPosition` would have mishandled
next tick anyway (falling into the "final leg reached" branch and asking
the *wrong* map's local pathfinder for coordinates belonging to a different
map).

**Step 2 — retry with backoff instead of giving up (or wandering off) on
one failed local step:** both places `determineMonsterNextPosition` calls
`findPathFor` while approaching a leg (the next mapchange, or the final
destination area) previously had no recovery on failure — one was a bare
`// Path blocked, do something to clear path TODO` comment that did
nothing, the other had no `else` at all and silently fell through to an
*unrelated* wander-fallback several lines down, actively walking the
monster away from its route. Confirmed (as the plan's step 2 asked to
verify before assuming a routing-algorithm gap) that `findPathFor`'s A\*
search already excludes occupied/blocked tiles from its graph on every
call — so a fresh retry naturally finds a detour on its own if one exists;
the actual bug was purely that nothing ever retried.

Added `Monster.travelBlockedRetries` (an ephemeral, not-persisted counter —
it's retry bookkeeping, not journey state) and
`MonsterMovementController.handleBlockedTravelPath(m)`, called from both
blocked-path sites instead of falling through:
- Increments the counter; if it exceeds the new
  `Constants.MONSTER_TRAVEL_MAX_BLOCKED_RETRIES` (10), gives up the whole
  journey the same way step 1 does (clear `travelDestination`/`travelPath`,
  log a warning) rather than waiting forever — matching the plan's explicit
  call that an NPC shouldn't be able to shove the player out of the way
  indefinitely.
- Otherwise waits with a randomized backoff by reusing
  `cancelCurrentMonsterMovement`'s pattern, instead of retrying every single
  500ms tick.
- The counter resets to `0` on every successful `findPathFor` call (both
  sites) and at the start of a fresh `beginTravel`, so an old blockage never
  carries over into a later, unrelated one.

**Deliberately not done (per the plan's own scoping):** step 3 (`keyarea`
overlap) and step 4 (a "travel failed" script hook) were left open — the
user approved doing steps 1–2 now and leaving 3 as a separate design pass,
matching the plan's own note that `keyarea` handling needs its own scoping
rather than being bolted onto this fix.

**Files:** `MonsterMovementController.java`, `Monster.java`,
`Constants.java`.
**Verification:** compiles clean. User-tested on `traveltest1` by closing
the wall while `traveltester` was already walking through it — this is what
surfaced the player-blocking follow-up gap below, which is now also fixed
and confirmed working.

## Phase 3 follow-up — the player was invisible to travel pathfinding

**Symptom:** user tested step 2 above by physically standing in the NPC's
path instead of closing a wall. Instead of waiting/retrying/giving up like
the wall case, the NPC just sat there indefinitely with no retry countdown
and no eventual failure - a different, worse-looking stall than the one
step 2 just fixed.

**Root cause:** `findPathFor`'s A\* search already excludes *other
monsters* from its walkability graph (`monsterCanMoveTo`'s
`map.getMonsterAt(p, movingMonster) != null` check), but has no concept of
the player at all - the player isn't a `Monster`, so `getMonsterAt` never
sees them. This means the search doesn't fail when the player blocks the
only route: it doesn't even notice, and keeps returning the same
straight-through-the-player "path" every tick, resetting
`travelBlockedRetries` to `0` on every one of those "successes." The actual
stall happens one layer up, in `moveMonster`'s existing (pre-existing,
unrelated-to-travel) player-collision check: when the computed next step
lands on the player's tile and the monster isn't aggressive toward them, it
just calls `cancelCurrentMonsterMovement` and waits - forever, since nothing
ever escalates that into a failure. Two structurally different "blocked"
signals, only one of which fed into the retry/give-up logic just added.

**Fix:** rather than teaching the second code path about travel state (or
loosening it to let a friendly NPC shove the player aside), taught the
pathfinding search itself to treat the player as an obstacle for travel
purposes, the same way it already treats other monsters:
- `PathFinder.findPathBetween` gained an overload taking an extra `Coord
  avoid` tile, excluded from both the seed loop (searching from `to`) and
  the neighbor-expansion loop - so a route through that tile is never
  considered walkable for this search, matching how another monster's tile
  is already excluded.
- `MonsterMovementController.findPathFor(Monster, CoordRect)` now always
  passes `world.model.player.position` as `avoid`. This overload is used
  *exclusively* by the two travel-approach call sites (confirmed via
  grep - aggressive-chase-player uses the separate `Coord` overload, which
  was deliberately left untouched since that search legitimately targets
  the player's own tile as `to`; excluding it there would make the
  destination unreachable and break attacking).

With the player now genuinely excluded from the graph, a travelling monster
detours around them exactly like it already does around another monster if
a detour exists, and correctly falls into the existing `findPathFor`-fails
→ `handleBlockedTravelPath` retry/give-up path (added in step 2, above) if
one doesn't - e.g. the player blocking a single-tile-wide doorway.

**Files:** `PathFinder.java`, `MonsterMovementController.java`.
**Verification:** compiles clean; user-confirmed on-device — standing in
`traveltester`'s path now makes it detour around the player instead of
stalling.

## Phase 2/3 correction — monster pathfinding read a permanently stale tileMap

**Discovered:** while starting Phase 4, re-checking Phase 2's fix before
building on top of it. The user's "please continue" had confirmed the
player-blocking fix (unaffected by this), not a re-test of the original
wall-closing scenario that started Phase 2/3 — good thing checked before
moving on, since it turned out still broken underneath.

**Root cause:** `PredefinedMap.tileMap` is a `final` field built once when
a map first loads and never touched again — not even by
`ReplaceableMapSection`. `MapController.applyReplacements` only ever
mutates `world.model.currentMaps.tileMap`, a *separate* `LayeredTileMap`
instance that `MovementController.prepareMapAsCurrentMap` rebuilds from
scratch (re-parsing the TMX via `TMXMapTranslator.readLayeredTileMap`,
confirmed no caching) every time a map becomes current. `PredefinedMap
.isWalkable` - which `PathFinder`'s A\* search, `calculateDistanceMatrix()`,
and every travel-pathfinding call read walkability through - used
`this.tileMap`, the permanently-stale copy. The player's own movement,
meanwhile, correctly checks the live `currentMaps.tileMap`
(`MovementController.java:213`).

Net effect: Phase 2's "recompute the matrix after a replacement" fix
recomputed from data that had never actually changed - correctness-inert.
And a monster routed straight into a wall that just closed (live for the
player, invisible to the stale copy) would only get bounced by
`moveMonster`'s final move-validation (which does use the live tileMap) -
silently, forever, in the exact same never-escalating
`cancelCurrentMonsterMovement` loop pattern as the player-blocking bug from
the entry above, completely bypassing Phase 3's retry/give-up logic (which
only triggers when `findPathFor` itself reports failure - it never did,
since its search data never changed).

**Fix:** `PredefinedMap` gained a private `liveTileMap()` helper: returns
`world.model.currentMaps.tileMap` when `this` is the map currently loaded
(matching what the player's own movement already uses), falling back to
`this.tileMap` otherwise (including during initial world/map construction,
before `world.model` exists yet - guarded with a null check). Both
`isWalkable` overloads now go through it instead of `this.tileMap` directly.

**Known, accepted limitation (not fixed, not new):** a map the player
currently isn't on has no live/current tileMap to prefer, so a travelling
monster pathfinding through *that* map still won't see its replacement
state change - nothing in this codebase tracks replacement/requirement
state for a non-current map at all, so this isn't a new gap introduced by
travel; it's the existing scope of the replacement system. Confirmed with
the user before fixing, given it corrects the actual claimed behavior of
two already-"done" phases.

**Files:** `PredefinedMap.java`.
**Verification:** compiles clean. Re-verify on-device: close the wall on
`traveltest1` (the current map) while `traveltester` is walking toward an
`obstacle_*` destination through it - it should now genuinely detour or
wait-then-give-up, the same way the player-blocking case already does.

## Phase 4 — Make arrival scripts reliable regardless of map visit history

**Goal:** `TravelDestinationArea.onMonsterArrived` only ran `arrivalScript`
if `mapScriptExecutor`/`controllers` were non-null, and those were only set
by `MapController.prepareScriptsOnCurrentMap()` for whichever map the
player currently has loaded — a monster arriving on a map the player hadn't
visited yet this session silently skipped its arrival script
(`travellingNPC.md` gap 4).

**Implementation (plan step 1, second option):** rather than giving every
map its own permanently-wired `ConversationStatemachine` at load time,
`onMonsterArrived` now takes a `ControllerContext controllers` parameter
directly and builds a fresh `ConversationStatemachine` on demand via a new
`MapController.runArrivalScript(phraseID, npc)` method — reusing the same
`conversationStateListener` `runScriptInArea` already uses, so an arrival
script behaves identically to any other script trigger regardless of
whether the player has ever loaded that map. `MonsterMovementController`
(which already holds a `ControllerContext` field) passes it at all three
`onMonsterArrived` call sites. Removed `TravelDestinationArea
.setScriptEnvironment` and its wiring loop in `prepareScriptsOnCurrentMap`
as dead code, since nothing calls it anymore.

`runArrivalScript` only reapplies `ReplaceableMapSection` changes
(`applyCurrentMapReplacements`) when the arriving monster's map is the
*current* one — there's no live tileMap to reapply to otherwise (see the
correction entry below), so this deliberately matches the existing scope of
the replacement system rather than pretending to fix it further.

**Step 2 (regression case) — not newly exercised:** the test bed's only
cross-map destinations (`map2_rect1`/`map2_rect2` on `traveltest2`, whose
`arrivalScript` is `traveltester_start` — reusing the destination-choice
menu, which is how the earlier "send him back" flow worked) had already
been visited at least once earlier in this session, before gap 4 was even
discovered — every visit permanently sets `TravelDestinationArea`'s old
`mapScriptExecutor` field via `setScriptEnvironment`, and nothing ever
cleared it on leaving, so once wired it kept working regardless of current
map. That's exactly why the gap went unnoticed in testing until now. To
verify the actual fix, this needs a genuinely fresh case: a new save/fresh
character, or restarting the app before ever loading `traveltest2`, then
sending `traveltester` there directly.

**Step 3 (re-confirmed):** `onMonsterArrived` clears `m.travelDestination
= null` as its very first action, before anything script-related runs, so
`isTravelling` flips false and the script can't be triggered twice for the
same arrival regardless of which code path reaches it.

**Files:** `TravelDestinationArea.java`, `MapController.java`,
`MonsterMovementController.java`.
**Verification:** compiles clean. On-device re-verification (fresh
save/character sending `traveltester` to `traveltest2` without visiting it
first) still to be done by the user.

## Phase 3 (step 3) — let monsters pass through designated key areas

**Goal:** a travelling monster's route can legitimately need to pass
through a `keyarea` (a key-gated passage, `Requirement`-checked for the
player via `MapController.canEnterKeyArea`), but `monsterCanMoveTo` treated
every `keyarea` as unconditionally impassable for monsters, with no way to
opt a specific one out.

**Design decision (user's proposal):** rather than teaching monsters to
evaluate `enteringRequirement` the way the player does, add a static,
map-author-set `monstersCanPass` boolean directly on the `keyarea` object.
This is a better fit than a dynamic per-`Requirement` check for two
reasons: most `Requirement` types (`hasItem`, `questProgress`, ...) are
checked against the *player's* inventory/quest state, so "does this monster
satisfy it" usually isn't even a meaningful question; and because the flag
is static rather than derived from live quest state, it needs zero new
support from `GlobalPathFinder`'s distance computations (no "conditionally
walkable" concept to add) - exactly the complexity the plan's step 3 was
worried about. A keyarea whose monster-passability should change based on
quest state can already use a `ReplaceableMapSection` for that; no need to
overload `keyarea` with dynamic logic it wasn't designed for.

**Implementation:**
- `MapObject` gained a `monstersCanPass` field (only meaningful for
  `keyarea`), threaded through the private constructor and every `createX`
  factory (`false` for every non-`keyarea` type). `createKeyArea` takes it
  as a new parameter.
- `TMXMapTranslator` parses a new `monstersCanPass` property on `type="key"`
  objects (defaults to `false`, preserving existing behavior for every
  keyarea already in the game unless a map author opts a specific one in).
- `MonsterMovementController.monsterCanMoveTo` and `PredefinedMap
  .isWalkable(CoordRect, boolean)` both split `keyarea` out of the shared
  `newmap`/`rest`/`keyarea` → always-blocked switch case: `keyarea` now
  only blocks a monster when `!mObj.monstersCanPass`. (The `isWalkable
  (CoordRect, boolean)` overload's own copy of this switch is currently
  dead code either way - its only two callers, both in `PathFinder`, always
  pass `ignoreAreas=true` - fixed for consistency regardless.)
- Player-facing `keyarea` behavior (`MovementController.moveToNextIfPossible`
  → `MapController.canEnterKeyArea`, which does evaluate `enteringRequirement`)
  is a fully separate code path and is completely unaffected.

**Files:** `MapObject.java`, `TMXMapTranslator.java`,
`MonsterMovementController.java`, `PredefinedMap.java`.
**Verification:** compiles clean. On-device verification still to be done
by the user: add `monstersCanPass=true` to a `key`-type TMX object on a
travelltest map and confirm a travelling monster now walks through it
(existing keyareas elsewhere in the game default to unaffected/still
blocked, since the property defaults to `false`).

## Phase 3 (step 4) — let an NPC react to its own failed journeys

**Goal:** surface both travel-failure modes (unreachable from the start, or
given up on after too many blocked retries) to script/quest authors instead
of the NPC just quietly stopping.

**Design decision (user's proposal, discussed before implementing):** the
plan originally framed this as an `arrivalScript` counterpart on
`TravelDestinationArea` - a "travel failed" script tied to the destination.
The user pushed back: a monster's reaction to failing a journey is a
property of *who's traveling*, not of *where they were headed* - many
monsters could target the same destination and legitimately want different
failure reactions, and the same monster's fallback ("always go back home
if you can't get there") shouldn't need to be duplicated on every
destination it might ever fail to reach. Agreed this is the better fit, and
implemented it as a standing field on `Monster` instead:

- `Monster.travelFailedScript: String` - a phrase ID, persisted across
  saves (`fileversion >= 88`) like `travelDestination`, but *not* cleared
  after firing (unlike `travelDestination`) - it represents a per-NPC
  fallback behavior that should keep applying to every future failed
  journey, not one-shot per-journey state.
- New `setTravelFailedScript` reward type (`ScriptEffect.ScriptEffectType`,
  `ConversationController`) mirrors `setDestination`'s existing pattern:
  `{ "rewardType": "setTravelFailedScript", "rewardID": "<phraseID>" }` →
  `monster.travelFailedScript = phraseID`. A quest/dialogue author sets this
  once on NPC A, and it stays in effect - "send him back home" is just a
  normal phrase using the existing `setDestination` reward, no new
  machinery needed for the recovery action itself, only for triggering it.
- Fired from both failure sites added in step 1/2:
  `beginTravel`'s immediate-unreachable check, and
  `handleBlockedTravelPath`'s give-up-after-N-retries branch - both via a
  new shared `fireTravelFailedScript(m)` helper, after `travelDestination`/
  `travelPath` are already cleared (matching `onMonsterArrived`'s existing
  ordering, so if the failure script itself starts a new journey there's no
  stale state left over).
- Reuses `MapController.runScriptForNpc` (renamed from `runArrivalScript`,
  since it's no longer arrival-specific) - already visit-history-independent
  from the Phase 4 work, so a travel-failed script fires the same way
  whether or not the player is anywhere near the monster.

**Known risk, deliberately not guarded against:** if a `travelFailedScript`'s
own effects start another journey (`setDestination`) that is *itself*
immediately unreachable, `beginTravel` fails synchronously and re-invokes
the same script - an infinite synchronous recursion (stack overflow in the
worst case) if the fallback destination can never be reached. No
re-entrancy guard was added; this is documented as a script-authoring
responsibility (the fallback destination must actually be reachable) rather
than built-in protection, consistent with how other script-authored side
effects in this codebase are already trusted not to create pathological
loops.

**Files:** `Monster.java`, `ScriptEffect.java`, `ConversationController.java`,
`MapController.java`, `MonsterMovementController.java`.

**Wired into the test bed:** `conversationlist_traveltest.json`'s
`traveltester_start` phrase (the "Choose a destination" menu, run every
time the player talks to `traveltester`) now carries a `setTravelFailedScript`
reward pointing at the existing `traveltester_rect1_center` phrase - reused
as-is rather than adding a near-duplicate phrase, the same way
`map2_rect1`/`map2_rect2`'s `arrivalScript` already reuses `traveltester_start`.
Since the reward is idempotent and the phrase is re-run every time the menu
opens, this doesn't need a one-time setup step - talking to `traveltester`
once is enough to have the fallback in effect for every journey after that.

**Verification:** compiles clean; JSON validated; user-confirmed on-device —
sending `traveltester` toward a destination made unreachable now returns
it to `rect1_center` instead of leaving it stuck.

## Phase 5 — discarded, not implemented

Before starting Phase 5 (multi-step itineraries), presented a design
proposal covering the two real open questions: whether "goto destArea2"
needed new plumbing at all (it doesn't - already achievable via
`arrivalScript` + `setDestination` chaining, as just demonstrated by
`travelFailedScript`'s test-bed wiring above), and where a "stay N" pause
primitive and the itinerary sequence itself should live (`Monster` vs.
`TravelDestinationArea`, mirroring the step-4 traveler-vs-destination
question).

**User's answer: don't build this at all.** `TravelDestinationArea
.executeNextStep()` was a leftover stub from an idea abandoned before this
branch's work began, accidentally left in rather than intentionally
deferred. Removed it (and the dangling "// Iterate over steps once
implemented" comment in `onMonsterArrived` that referenced it) instead of
implementing it. While touching the file, also fixed a stale doc-comment
reference to the old `runArrivalScript` name (renamed to `runScriptForNpc`
during Phase 3 step 4, comment wasn't updated at the time).

**Files:** `TravelDestinationArea.java`.
**Verification:** compiles clean.

## Phase 6 — algorithmic completeness in `GlobalPathFinder`

Item 1 (same-map shortcut fallthrough) was already fixed during Phase 0.
The remaining two items:

**Item 2 - map transitions cost zero:** decision-only, no code. The plan's
one cited reason to reconsider ("once Phase 5 itineraries make total
journey time more visible to players") no longer applies now that Phase 5
was discarded. Finalized as the permanent, deliberate design - zero-cost
map transitions stay as-is.

**Item 3 - stale heuristic formula:** `PathFinder.heuristic()` used an
octile-distance formula (`10 * max + 4 * min`, i.e. diagonal cost 14) left
over from before moves were made uniform-cost (orthogonal and diagonal both
10, the diagonal-cost decision already recorded at the top of
[PLAN.md](PLAN.md)). An inflated heuristic like this overestimates true
distance for diagonal-heavy routes, which makes it *inadmissible* for A\* -
the search can return a path that isn't actually shortest, and since
`GlobalPathFinder`'s whole route-planning graph is weighted by
`PredefinedMap.getDistance` (backed by this same search via
`calculateDistanceMatrix`/`fillMapchangeDistances`), a wrong "shortest"
distance between two exits could silently steer global route planning
toward a worse leg. Fixed by changing `heuristic()` to plain Chebyshev
distance (`10 * max(dx, dy)`), matching the actual uniform move cost.

**Files:** `PathFinder.java`.
**Verification:** compiles clean. `diag_1`/`diag_2`/`snake` on `traveltest1`
are good candidates for eyeballing whether routes still look
shortest-possible - not yet re-checked on-device by the user.

## Phase 6 follow-up — natural-looking diagonal/straight interleaving

**Symptom (user, on-device):** after the Chebyshev heuristic fix above, the
NPC's path was still the fastest, but the specific tile sequence looked
wrong two ways - occasional zig-zagging, and (a separate, broader
observation) a diagonal-then-straight *clustering* pattern that predates
this session's changes entirely: a trip 10 tiles east and 20 south would
walk 10 diagonal south-east moves first, then 10 straight south, instead of
a natural-looking interleave.

**Root cause:** since diagonal and orthogonal moves cost the same, a huge
number of different tile sequences between two points are *exactly*
tied on total cost - a clean interleaved staircase, all-diagonal-then-
straight, all-straight-then-diagonal, and plenty of zig-zag variations all
cost identically. Plain Chebyshev distance doesn't prefer any of them, so
which one A\* actually returns is decided arbitrarily by exploration/heap
order. The old octile heuristic (`10*max+4*min`) had *accidentally*
produced the clustered-not-interleaved pattern as a side effect of
consistently overestimating diagonal-light paths; removing that bias
(correctly, for admissibility) left ties to resolve with no preference at
all, which is what surfaced as zig-zag.

**Fix:** rather than reverting to the old formula (which the user
specifically asked about, but which reintroduces the same clustering
problem, not just risking inadmissibility), added a proper, bounded
tie-breaking term - the standard technique for exactly this class of
problem (Amit Patel's "tie-breaking" write-up on grid A\* is the canonical
reference). `heuristic()` now also takes the search's fixed goal reference
point (`to`'s center, computed once per `findPathBetween` call) alongside
the existing fixed start reference (`from.topLeft`), and adds a small
penalty proportional to the cross-product magnitude between
(candidate − start) and (goal − start) - i.e. how far the candidate node
strays from the straight line connecting the search's two endpoints. This
is what turns "cluster all diagonal moves at one end" into "interleave
them evenly," since a straight-hugging path is by definition the one that
stays closest to that line at every intermediate step, not just at the
end.

The penalty is capped well under one move's cost (10) via
`TIEBREAK_DIVISOR`, sized against `Constants.MAX_MAP_WIDTH`/`MAX_MAP_HEIGHT`
(the largest buildable map, not just the current one) so the worst-case
contribution is a single digit regardless of map size - it can only ever
resolve a genuine tie between equal-true-cost paths, not override a real
shortest-path decision by any meaningful margin. This bound is tighter
than the old octile heuristic's worst-case overestimate ever was (which
could reach `4 * min(dx,dy)` - e.g. 40 for the user's 10-east/20-south
example - a bigger, unbounded-by-map-size risk than this fix carries),
so the new version is strictly safer than the one being replaced, on top
of producing the better-looking path.

**Files:** `PathFinder.java`.
**Verification:** compiles clean. On-device re-verification still to be
done by the user: `diag_1`/`diag_2`/`snake` on `traveltest1`, and ideally a
long asymmetric trip (e.g. `rect1_corner` to `rect4_corner` or similar)
to see the interleaved staircase pattern directly.

## Phase 6 follow-up, correction — the tie-breaker above was a complete no-op

**Symptom (user, on-device):** still zig-zagging after the fix above -
tested `rect1_corner` → `rect3_corner` (roughly straight south, forced 3
tiles east then 3 tiles west around unwalkable terrain) and the detour was
still all-diagonal with no visible change from the tie-breaker.

**Root cause:** `TIEBREAK_DIVISOR` was sized against the *worst-case*
46x46 map (`Constants.MAX_MAP_WIDTH`/`HEIGHT`), landing at ~471. For this
specific trip (dx~0, dy~17, deviations of only 3-4 tiles), the raw
cross-product magnitude tops out around 50-60 - divided by ~471 and
truncated to an `int`, that's `0` for every single candidate node. The
tie-breaker wasn't weak, it was completely inert for any search smaller
than the largest possible map, which in practice is nearly all of them.
The earlier verification ("compiles clean") never caught this because it's
a runtime magnitude/scale bug, not a type error.

**Fix:** stopped dividing by a fixed worst-case constant and instead
normalized the cross-product by the search's own start-to-goal distance,
turning it into an actual perpendicular-distance-from-the-line measurement
in tile units (`cross / lineLength`) - meaningful at any trip length, long
or short, instead of only at the theoretical map-spanning extreme. The
result is scaled by a constant weight and hard-capped at `9` (`Math.min(9,
...)`, still under one move's cost of 10) for the same
never-override-a-real-shortest-path-decision safety property as before -
the cap now does that job directly instead of depending on a
divisor sized for a hypothetical worst case.

**Caveat flagged to the user, not resolved:** for a detour forced into a
narrow y-range by the obstacle's exact shape, there may be no room to
interleave straight moves without leaving the safe corridor - in that case
the path would legitimately have to be diagonal-heavy regardless of
tie-breaking, and this fix wouldn't (and shouldn't) change that specific
outcome. Whether that's what's happening on `traveltest1`'s
`rect1_corner`-to-`rect3_corner` obstacle specifically isn't confirmed -
the tile layout wasn't inspected.

**Files:** `PathFinder.java`.
**Verification:** compiles clean; user-confirmed on-device on
`traveltest1` (`rect1_corner` → `rect3_corner`) - path is shortest, and the
detour itself now interleaves diagonal/straight moves naturally, exactly
as intended.

**One remaining cosmetic gap, explicitly accepted by the user rather than
chased further:** the path still walks straight toward an obstacle right
up to the last possible moment before turning, instead of easing into the
turn earlier - confirmed by the user on the same test case. This is a
different objective than what the tie-breaker optimizes for (minimizing
distance from the straight start-goal line, not minimizing turn
sharpness/curvature - the two pull in different directions), so it isn't a
small follow-up tweak; it would need either a turn-angle-penalizing
heuristic term or a post-process path-smoothing pass. Logged in
[PLAN.md](PLAN.md)'s new "Known cosmetic polish items (pre-release)"
section at the user's request, rather than being silently dropped or
chased further right now.

## Phase 8 — cross-map debug visualization for `GlobalPathFinder`

**Ask:** the user asked whether Phase 8's "Debug visualization for
`GlobalPathFinder`" stretch item should be considered complete, and to
implement whatever's missing if not.

**Assessment:** not complete. `showTravelDebug`'s logcat output already
covered the "even just logging the chosen `GlobalPath`'s legs" half of the
plan's suggested minimum (`beginTravel` already dumps the full leg plan),
but there was no on-screen visual at all for the cross-map route - only
`PathFinder.showPathfinderDebug`'s `pth` overlay existed, and that only
ever shows one map's *local* A\* search. Also found and fixed a smaller
inconsistency while in there: `GlobalPathFinder.findPath`'s two log lines
were unconditional (not gated behind `showTravelDebug` like the rest of
the travel logging), so turning the toggle off didn't actually silence
them.

**Implementation:**
- `GlobalPathFinder.findPath`'s two `L.log(...)` calls are now gated behind
  `MonsterMovementController.showTravelDebug`, matching every other travel
  debug log in the codebase.
- `MainView` gained `drawTravelDebug(...)`, hooked into `doDrawRect`
  alongside the existing `drawPathfinderDebug(...)` call, reusing its
  visual language (colored `debugPaint` outlines + text labels via
  `canvas.drawText`). For every monster anywhere in the world with an
  active `travelDestination` - checked across every map's `.monsters` list
  via `world.maps.getAllMaps()`, plus the world-level
  `travellingMonsters` pool, so an off-screen or not-yet-visited monster's
  route is still visible once you're standing on a map one of its legs
  passes through - it highlights whichever of that monster's `travelPath`
  legs land on the currently displayed map: a cyan outline on the next
  mapchange exit object it's walking toward, or an orange outline on its
  final `TravelDestinationArea` if that's the last leg, each labeled with
  the monster's type ID.
- No new debug button - reuses the existing `trv` toggle
  (`MonsterMovementController.showTravelDebug`), which already existed for
  the logcat output; the visual overlay and the log lines now turn on and
  off together as one coherent "travel debugging" mode.

**Files:** `GlobalPathFinder.java`, `MainView.java`.
**Verification:** compiles clean; user-confirmed on-device working.

## Documentation updates (no code change)

- **`travellingNPC.md`** — updated gap 1 to note it's now confirmed via live
  testing and is more general than the `ReplaceableMapSection` case
  originally described: any actor occupying a tile in the NPC's path is
  enough to permanently stop it, with no attempt to route around.
- **`PLAN.md`** Phase 3 — expanded per that same finding: clarified that a
  fix needs to actually verify whether `findPathFor` already finds detours
  around blocking actors (since occupied tiles are already excluded from
  the search) versus simply never retrying at all, and flagged that
  obstacle-avoidance work needs to be scoped together with `keyarea`
  handling (monsters currently treat `keyarea` as unconditionally
  impassable, with no `Requirement` check unlike the player's
  `canEnterKeyArea`) rather than being bolted on in isolation.
- **`PLAN.md`** Phase 0 and Phase 1 — marked done, each pointing back at this
  changelog for the actual account. Phase 6 item 1 (same-map shortcut
  fallthrough) marked done — it was actually fixed during Phase 0 (item 9
  above) but the plan text hadn't been reconciled yet.
- **`travellingNPC.md`** gap 3 — marked fixed, describing the Phase 1
  persistence work and the deliberate no-clamping decision on `startTime`.
- **`PLAN.md`** Phase 2 — added a correction note pointing at the stale-tileMap
  bug and its fix, since the phase's original "done" claim turned out to be
  correctness-inert until then.
- **`travellingNPC.md`** gap 1 — updated both halves (matrix staleness and
  live-blocked-path retry) to note they silently didn't work until the
  stale-tileMap correction.
- **`PLAN.md`** Phase 4, **`travellingNPC.md`** gap 4 — marked done,
  describing the `ControllerContext`-injection fix and the reason the
  regression case wasn't newly exercised (the test bed's cross-map
  destinations had already been visited earlier this session).
- **`PLAN.md`** Phase 3 step 3, **`travellingNPC.md`** gap 1 — marked done,
  describing the `monstersCanPass` design and why a static flag was chosen
  over a dynamic `Requirement` check.
- **`PLAN.md`** Phase 3 (now fully done), **`travellingNPC.md`**
  architecture table and "How to trigger travel today" — documented
  `travelFailedScript`/`setTravelFailedScript` and why it landed on
  `Monster` instead of `TravelDestinationArea`.
- **`PLAN.md`** — added a new "Pre-release regression checklist" section
  (Tunlon's aggression-triggered movement across a `keyarea` in the
  `bwmfill` area), per the user's request to flag existing, non-travel game
  content worth manually re-testing before release given the shared
  `monsterCanMoveTo`/pathfinding code this branch touched.
- **`PLAN.md`** Phase 5, **`travellingNPC.md`** gap 5 — marked discarded
  (not done, not deferred) per the user's call that the itinerary stub was
  leftover from an abandoned idea and should be removed, not built.
- **`PLAN.md`** Phase 6 (now fully done), **`travellingNPC.md`** gaps 6 and
  7 — gap 6 corrected (it was already fixed during Phase 0 but the gap text
  was never updated to say so); gap 7 marked finalized rather than open,
  same reasoning as the Phase 6 item 2 changelog entry above.
- **`PLAN.md`** — added a new "Known cosmetic polish items (pre-release)"
  section (separate from the regression checklist, which is scoped to
  pre-existing non-travel content) for the abrupt-pre-detour-turn gap, at
  the user's explicit request to track it rather than let it disappear
  once they said they were fine leaving it as-is for now.
- **`PLAN.md`** Phase 8's `GlobalPathFinder` visualization item,
  **`travellingNPC.md`**'s architecture table, gap 8, and "Debug
  visualization" section — marked done, describing `drawTravelDebug` and
  the `GlobalPathFinder` logging-consistency fix.
- Added **`docs/wip/travellingNPC_dataSchema.md`** — a new, standalone data
  schema reference (distinct from `travellingNPC.md`'s design narrative),
  written for whoever later updates the ATCS content editor to support
  authoring travel data. Documents the exact `.tmx` object schema
  (`destination`, `mapchange`/same-map warp pairs, `key`/`monstersCanPass`,
  and the travel-relevant parts of `spawn`/`replace`) and the exact `.json`
  phraselist reward/requirement schema (`setDestination`,
  `setTravelFailedScript`, `isTravelling`), each field's Java source of
  truth, cross-object referential-integrity rules that aren't currently
  validated at load time (unique exit/destination names per map, `place`
  matching, `monstersCanPass`'s closed-by-default behavior), which
  `Monster` fields are runtime-only and must never be exposed as editable
  data, and a minimal worked example alongside pointers into the
  `traveltest1`/`traveltest2` test bed for a fuller one. No code change.

## Refinement planning

With the core feature working end-to-end and confirmed by the user, added
**`docs/wip/PLAN_refinement.md`** — a plan for six further, non-bugfix
enhancements requested next: fixing the accepted abrupt-pre-detour-turning
cosmetic gap; per-`MonsterType` default `travelFailedScript`/
`travelDestination` (data-driven, alongside the existing script-reward
mechanism); a new non-rendered TMX "control" tile layer letting map authors
weight terrain so travelling monsters prefer paths/roads without being
forced onto them; a data-driven per-NPC path-variance multiplier so
identical journeys look individually organic rather than machine-generated;
letting an on-screen travelling NPC pause to rest for 1-2 ticks with its
`travelPath` ETA corrected accordingly; and a preparatory extension point
(not the feature itself) for a later, separately-planned system letting
NPCs interrupt travel for other activities. Phases are sequenced R1-R6 by
shared risk/dependency rather than request order — notably, R3 (terrain
weighting) and R4 (path variance) are placed adjacently since both modify
the same `PathFinder` per-tile move-cost computation, and R6 is placed last
since it deliberately reuses R5's pause/ETA-correction mechanism rather than
building parallel machinery. Two cross-cutting constraints from the already-
completed work are carried forward explicitly into the plan: A* admissibility
(R3 chosen to preserve it via a penalty-only cost model; R4 deliberately
relaxes it in a small, bounded way, with the trade-off called out) and the
Phase 0 lesson that on-screen-only behavior must never desync from the
wall-clock interpolation model used for off-screen travelling monsters (R4
sidesteps this by construction; R5 addresses it head-on via an explicit ETA
correction). No code change.

## R1 — Fix the abrupt pre-detour turning

See [PLAN_refinement.md](PLAN_refinement.md) for the full writeup. Summary:
implementing the plan's proposed "turn-angle heuristic term" (option A)
surfaced that neither of its two literal framings actually work — a turn
*penalty* pushes the wrong direction (the reported behavior already has the
fewest possible turns, so discouraging turns further entrenches it, not
fixes it), and a per-step bearing-deviation term can't distinguish "on the
direct approach to an obstacle" from "on a normal straight stretch," since
every tile on that approach genuinely is the most direct bearing available
at the moment it's stepped on - a local metric has no way to anticipate an
obstacle a few tiles ahead.

The real gap is the same *class* of tie the Phase 6 line-tiebreak already
resolves (multiple equal-true-cost routes, chosen arbitrarily by exploration
order) along a different axis: *when* a route starts detouring around an
obstacle sitting on the straight line, not how it distributes diagonal moves
once already detouring - every tile on that line has perpendicular distance
0, so the existing tiebreak can't distinguish "detour starts here" from
"detour starts one tile closer to the wall." Fixed with a second, small,
bounded tiebreak term, `clearanceTiebreak`: `PathFinder.heuristic()` (now an
instance method, so it can reach `map`) counts a candidate's unwalkable
terrain neighbours via a new `countUnwalkableNeighbors` helper (using a
preallocated `clearanceScratch` `CoordRect` to avoid any new per-call
allocation in this hot path), deliberately terrain-only (ignoring event
areas/other actors, which move every tick and would make this a moving
target). Combined with the existing `lineTiebreak` under one shared cap
(`TIEBREAK_MAX = 9`, `CLEARANCE_TIEBREAK_MAX = 6` for the new term's own
ceiling) rather than stacking two independent bounds, preserving the
original invariant that the combined bias always stays well below one
move's cost (10) - it can only ever break a genuine tie, never outrank a
real shortest-path difference.

**Files:** `PathFinder.java`.
**Verification:** compiles clean
(`gradlew :app:compileDebugJavaWithJavac`). On-device verification on
`traveltest1` (`rect1_corner` → `rect3_corner`) still to be done by the
user, per this feature's established pattern of needing real-device
confirmation before trusting a tiebreak change (the original Phase 6
tie-breaker needed two rounds of correction after looking right on paper).

## R1 follow-up, correction — the first clearance term was direction-dependent

**Symptom (reported):** travelling corner-to-corner on `traveltest1`
(`rect4`→`rect3` and `rect1`→`rect2`, both horizontal-primary trips) showed
the new eased-turn behavior correctly, but `rect2`→`rect4` and `rect3`→`rect1`
(both vertical-primary trips) still showed the old abrupt behavior - straight
for several tiles, then a sharp turn.

**Investigation:** rather than guessing, replayed the *exact* algorithm -
`heuristic()`'s formula *and* a faithful port of `OpenSetHeap` (a plain
array-based binary heap with no explicit tie-breaking, so which of several
equal-`f` candidates wins depends on insertion order/heap structure, not
just the scoring formula) - against `traveltest1`'s real decoded `Walkable`
layer data, tick-by-tick (recomputing from the current position each step,
exactly like `MonsterMovementController` does every game tick, not a single
upfront full-path solve). This reproduced the reported split exactly:
`rect1`↔`rect2`/`rect4`↔`rect3` eased in immediately, `rect2`↔`rect4`/
`rect3`↔`rect1` didn't.

**Root cause:** every one of the four corner destinations sits directly
against its room's walls (that's what makes it a "corner" test case) - but
*which* walls differ per corner (`rect1`: north+west, `rect2`: north+east,
`rect3`: west+south, `rect4`: east+south-ish, confirmed by decoding and
printing the actual tile grid around each). The direction that "escapes" a
given corner's walls sometimes happens to roughly agree with that trip's
straight line to its destination, and sometimes is roughly perpendicular to
it. When perpendicular, the previous fix's clearance signal (an immediate
8-neighbour unwalkable count, sharing one combined cap with `lineTiebreak`)
was both too weak/sharply-localized and too easily crowded out by
`lineTiebreak`'s own pull back toward the line, so the line term won and the
old abrupt pattern resurfaced. Confirmed via hand-traced heuristic values at
`rect1`'s corner that the two terms' formulas are themselves properly
symmetric under swapping x/y - this was never a directional bug in the math,
purely an interaction between two individually-correct but jointly
under-powered/over-coupled terms and each corner's specific, differently-
oriented wall geometry.

**Fix:** widened `clearanceScore` (renamed from `countUnwalkableNeighbors`)
to a 2-tile-radius weighted falloff (immediate neighbours weight 1.0, the
outer ring weight 0.4) instead of a flat 8-neighbour count, and gave
`clearanceTiebreak`/`lineTiebreak` **independent** caps (`CLEARANCE_TIEBREAK_MAX
= 9`, same value as `TIEBREAK_MAX`, no longer sharing one combined ceiling)
instead of one shared cap that let a large `lineTiebreak` starve
`clearanceTiebreak` of any room to act. Swept the clearance weight via the
same simulation harness (values 2.0-6.0) before settling on `2.0`: it was the
smallest weight that produced a leading straight-run of 0 (diagonal easing
from the very first step) for **all four** corner pairs simultaneously,
rather than shifting which two pairs looked right the way every other weight
tried did. Re-verified with the same harness: all four corner pairs, the
original Phase 6 `rect1_corner`→`rect3_corner` regression case, and two
longer corner-to-opposite-corner diagonal trips (`rect1`↔`rect4`,
`rect2`↔`rect3`) - every one matches its pre-this-phase path length exactly
(no shortest-path regression), with diagonal/straight moves redistributed
earlier rather than added or removed.

Widening the combined worst-case bound from 9 to 18 (each term capped at 9,
no longer sharing) is a deliberate, explicitly-accepted trade-off: `gScore`
(the real accumulated path cost) is never touched by either tiebreak term,
only exploration priority is, so the practical effect of the wider bound is
confined to which of several near-equal-cost routes gets explored first -
and empirically, across every case checked, it never changed which route
was *found*, only when it started curving.

**Files:** `PathFinder.java`.
**Verification:** compiles clean. Checked via the same real-data,
real-heap-algorithm simulation described above for all four corner pairs,
the original Phase 6 regression case, and two additional diagonal-corner
trips - all show consistent early easing with unchanged path length.
On-device confirmation from the user is still the final word before this is
considered settled - please re-test all four corner pairs this time (not
just one), since that's exactly what exposed the first version's blind spot.

**User-confirmed on-device:** "the path already looks very natural." R1 is
done.

## R2 — Per-`MonsterType` default `travelFailedScript` / `travelDestination`

See [PLAN_refinement.md](PLAN_refinement.md) for the full writeup. Both
fields, previously settable only via the `setTravelFailedScript`/
`setDestination` script rewards scoped to a live conversation's NPC context,
can now also be declared once per `MonsterType` in `monsterlist` JSON as a
standing default applied to every spawned instance of that type:

- `JsonFieldNames.Monster` gained `travelFailedScript` (plain phrase-ID
  string) and `travelDestination` (nested object, `{mapName, areaID}` -
  new `JsonFieldNames.MonsterTravelDestination` class for its two inner
  keys), parsed by `MonsterTypeParser` and carried on three new `MonsterType`
  fields (`travelFailedScript`, `travelDestinationMapID`,
  `travelDestinationAreaID`).
- `travelFailedScript`'s default is applied in `Monster`'s constructor -
  deliberately **not** in `resetStatsToBaseTraits()`, which was the plan's
  other candidate location. Checking call sites first (rather than assuming)
  found `resetStatsToBaseTraits()` is also invoked from
  `ActorStatsController.recalculateMonsterCombatTraits()`, which runs
  repeatedly over a monster's whole life (any condition change triggers it),
  not just at spawn - applying the default there would have silently
  clobbered a `setTravelFailedScript` reward's value on the next unrelated
  combat-stat recalculation. The constructor runs exactly once, matching the
  field's actual "starting value only, a reward always wins afterward"
  semantics.
- `travelDestination`'s default can't be a simple field copy - it needs a
  real `beginTravel` call (pathfinding, `ControllerContext`/`WorldContext`),
  so it's triggered from `MonsterSpawningController.spawnInArea` (every real
  spawn path) right after `MonsterSpawnArea.spawn(...)` returns the new
  `Monster`, and deliberately *before* `monsterSpawnListeners
  .onMonsterSpawned(...)` fires - `beginTravel` can immediately hand the
  monster to the travelling pool (removing it from `map.monsters`) if the
  player isn't on this map, so the listener should see that settled state,
  not a monster about to be yanked away right after being announced.
- Savegame interaction checked, not assumed: the parcel-reading `Monster`
  constructor calls the regular constructor first (applying the default),
  then conditionally overwrites `travelFailedScript` from save data only if
  `fileversion >= 88` *and* this specific monster had a persisted value.
  There's no `else` branch resetting to `null` when absent - consistent with
  how every other optional, fileversion-gated field already works in this
  constructor (`travelDestination`/`travelPath` included) - so loading an
  older save now lets a newly-added `MonsterType` default apply retroactively
  to a monster that never got the reward, rather than leaving it stuck on
  `null` forever. No `fileversion` bump needed.
- Respawn re-triggers the default every time (documented as deliberate, not
  a gap, in `travellingNPC_dataSchema.md`'s new §3.4) - matches the
  motivating "shopkeeper always starts by walking to their stall" case; a
  respawned monster never "remembers" a previous instance's progress, since
  that's unrelated per-instance savegame state.

Added a second test `MonsterType`, `traveltester_defaults`
(`monsterlist_traveltest.json`, spawned via a new spawn object on
`traveltest1.tmx`), deliberately without a `phraseID` (not talkable) so only
the default-driven path can be exercised, independent of the existing
reward-driven `traveltester`. It declares both new fields: a
`travelFailedScript` reusing the existing `traveltester_rect1_center` phrase,
and a `travelDestination` of `rect4_corner` on `traveltest1`.

**Files:** `resource/parsers/json/JsonFieldNames.java`,
`resource/parsers/MonsterTypeParser.java`, `model/actor/MonsterType.java`,
`model/actor/Monster.java`, `controller/MonsterSpawningController.java`,
`docs/wip/travellingNPC_dataSchema.md` (new §3.4),
`res/raw/monsterlist_traveltest.json`, `res/xml/traveltest1.tmx`.
**Verification:** compiles clean; JSON syntax validated. On-device
verification still to be done by the user: confirm `traveltester_defaults`
begins travelling to `rect4_corner` immediately on spawn with no dialogue
interaction, and confirm `traveltester`'s existing reward-driven flow is
unaffected.

**User-confirmed on-device:** "the second test monster immediately starts
travelling when I enter the map - and also returns when I block the path."

## R2 follow-up — global, map-visit-independent spawning for always-travelling monster types

See [PLAN_refinement.md](PLAN_refinement.md) for the full assessment written
before implementing, at the user's explicit request. Summary: the user asked
for a behavior change on top of the now-confirmed R2 - a monster with a
`travelDestination` default should begin travelling as soon as the world
loads, regardless of which map is active, not only once the player happens
to visit its spawn's home map. Assessed feasibility and performance first
(every map's exit-distance matrix is already computed at asset-load time
regardless of visited status, so `beginTravel` was never the blocker - only
monster *creation* is lazy/map-visit-gated today), then asked the user to
choose between a one-time eager spawn only, or also making *ongoing*
respawns of non-unique always-travelling types map-independent. **The user
chose the larger option.**

Implemented without any new savegame state or schema - entirely a change to
when/how an already-declared `travelDestination` default gets acted on:

- `MonsterSpawningController.getTravelSpawnAreas()` - a lazily-built, cached
  list (process lifetime, never invalidated - the underlying map/spawn-area/
  MonsterType asset data never changes across game sessions, only each
  area's live `isSpawning`/`quantity.current` state does, which is
  deliberately read fresh every time rather than cached) of every spawn area,
  across every map, whose spawn group resolves to exactly one `MonsterType`
  declaring a `travelDestination`. Deliberately excludes mixed-type spawn
  groups - `getRandomMonsterType`'s random roll can't guarantee it lands on
  the travel-default type, so treating a mixed group as "always travels"
  would be misleading; an always-travelling NPC should get its own dedicated
  spawn area, the same pattern `traveltester_defaults` already uses.
- `spawnAllTravellers()` (one-time catch-up, reuses the existing
  `spawnAllInArea(..., respawnUniqueMonsters=true)` unchanged) wired into
  both `MapController.lotsOfTimePassed()` (new game, player respawn, resting
  - all moments that already mean "let the world's background state catch
  up") and `Savegames.onWorldLoaded()` (loading an existing save doesn't go
  through `lotsOfTimePassed()`, and skipping this would leave an old save's
  never-visited travel-eligible areas stuck waiting on the slow probabilistic
  roll below - inconsistent with R2's own "old saves retroactively benefit
  from new defaults" precedent).
- `maybeSpawnTravellers()` (ongoing per-tick half, mirrors `maybeSpawn`'s
  per-area body - `isSpawnable(false)` excluding uniques, then
  `rollShouldSpawn()`, then spawn) wired into `GameRoundController
  .onNewTick()` alongside the existing `maybeSpawn` call.

**The one real design risk, addressed deliberately:** naively making the
per-*tick* check (not just the one-time catch-up) scan every spawn area on
every map, every 500ms, would scale with total world size for a feature
meant to cover a handful of specially-authored NPCs - the wrong shape, even
though each individual `isSpawnable`/`rollShouldSpawn` check is cheap alone.
Fixed by pre-filtering once into the small cached list above and only ever
iterating *that* per tick, keeping the added cost proportional to how many
always-travelling NPCs actually exist in the content, not to total map count
- mirroring the same "expensive work stays on-demand, not per-tick" shape
`GlobalPathFinder` already uses.

**Files:** `controller/MonsterSpawningController.java`,
`controller/MapController.java`, `controller/GameRoundController.java`,
`savegames/Savegames.java`.
**Verification:** compiles clean. On-device verification still to be done
by the user: confirm immediate travel on a fresh game/load without ever
visiting `traveltest1`; confirm respawn-and-resume-travelling after killing
it (it doesn't despawn on its own after arriving, so exercising the ongoing
per-tick respawn path specifically needs the quota freed by a kill); confirm
no duplicate spawns if the player later visits `traveltest1` normally.

## R3 — Data-driven terrain weighting via a new "control" tile layer

See [PLAN_refinement.md](PLAN_refinement.md) for the full writeup. A new,
non-rendered `Control` tile layer (same never-drawn treatment as the
existing `Walkable` layer) lets a map author weight terrain so a travelling
monster prefers certain ground (a road, a path) without being forced onto
it - implemented as a per-tile addition to `PathFinder`'s move cost,
deliberately penalty-only (never a discount) so the existing heuristic stays
provably admissible with no changes to it at all.

**A real, previously-unknown parser gap had to be fixed first, not just
extended.** `TMXMapFileParser` never captured tile-level custom properties
at all - `readTMXTileSet` only read the `<tileset>` tag's own attributes,
never descending into `<tile>` children. Worse than a simple gap: because
`XmlResourceParserUtils.readCurrentTagUntilEnd` dispatches every `START_TAG`
it sees until the matching outer `END_TAG` with no depth tracking, leaving a
tileset's `<tile><properties><property .../></properties></tile>` block
unconsumed wouldn't just silently drop the new data - the map-level
dispatcher one level up would have picked up that stray `<property>` tag
and misfiled it into the *map's own* top-level properties list. Fixed by
giving `readTMXTileSet` its own explicit body-consuming call (the same
pattern every other nested element in this parser already uses), and adding
`TMXTileSet.tileWeights` (local tile id → the tile's `weight` custom
property, authored in Tiled's Tileset Editor - the same place/workflow used
for every other tile property, not a new hardcoded lookup table) plus a new
`readTMXTile` helper.

**Data plumbing**, mirroring the existing `isWalkable` precedent throughout,
with one deliberate difference: `MapSection.pathWeight` (`int[][]`) - unlike
`isWalkable`, which can legitimately be `null` - is always fully allocated,
even on a map with no `Control` layer at all, since most maps won't have
one and `PathFinder`'s hot per-tile lookup must never need a null check.
`LayeredTileMap.getPathWeight`/`PredefinedMap.getPathWeight` read through
the same `liveTileMap()` seam `isWalkable` already uses. A `replace` object
can also carry a `Control` property alongside `Walkable`, so a preferred
route can change with quest state for free, the same as walkability already
can.

**`PathFinder` integration found a third spot the plan's own step 5 didn't
name.** Beyond the two obvious ones (the neighbour-expansion loop, the
final adjacent-to-`from` step), `findPositionOnPath()` has a third place
that computes `moveCost` - to re-derive, after a completed search, which
candidate node actually produced `lastPathDistance` (used by the
travelling-pool's wall-clock position interpolation,
`MonsterMovementController.spawnMonsterOnMap`). Missing this would have
left it hardcoded at the old flat `10`, silently breaking mid-leg position
interpolation specifically on weighted maps - found by grepping every
`moveCost` occurrence in the file after the main change, not by re-reading
the plan text alone.

Added a placeholder tileset (`res/drawable/map_control_1.png`, generated
directly via Python's stdlib `zlib`/`struct` - no image library was
available - 4 flat-color 32x32 tiles, real pixel content is irrelevant
since this layer never renders) with tile-level `weight` properties (3, 6,
9), and a test setup on `traveltest1`: a `Control` layer weighting all of
row 5 (an always-open east-west corridor shared with row 4) at 6, plus two
new `traveltester`-menu destinations - `control_test_1` on the unweighted
row 4, `control_test_2` on the weighted row 5 (forcing at least one
unavoidable step onto it). Expected: the monster walks almost the entire
distance along row 4, dropping onto row 5 only at the last unavoidable
step, rather than freely mixing both rows the way pure Chebyshev-shortest
pathing would without weighting. The generated layer's base64/zlib tile
data and gid arithmetic were round-trip verified with a throwaway Python
script before being embedded in the TMX file, rather than trusting
hand-computed bytes.

**Files:** `model/map/TMXMapTranslator.java`, `model/map/TMXMapFileParser.java`,
`model/map/MapSection.java`, `model/map/LayeredTileMap.java`,
`model/map/PredefinedMap.java`, `controller/PathFinder.java`,
new `res/drawable/map_control_1.png`, `docs/wip/travellingNPC_dataSchema.md`
(new §2.6), `res/xml/traveltest1.tmx`, `res/raw/conversationlist_traveltest.json`.
**Verification:** compiles clean; JSON/XML syntax validated; layer data
round-trip verified. On-device verification still to be done by the user:
confirm the row-4-then-drop-to-row-5 behavior on `control_test_1` ↔
`control_test_2`, and re-run the Phase 6/R1 regression case
(`rect1_corner`→`rect3_corner`, an unweighted route) to confirm the new cost
term is a no-op there.

## R3 correction — out-of-memory crash during new-character creation

**Symptom (reported):** the game reliably crashed with an out-of-memory
error while creating a new character (first launch of a fresh app process;
a subsequent retry within the same session succeeded). The user's logcat
showed `Cannot find maplayer "control" requested by map "home"`, and heap
usage climbing steadily to 191MB/192MB across roughly a minute of
background resource-loading before `OutOfMemoryError: Failed to allocate a
24 byte allocation... giving up on allocation because <1% of heap free
after GC` and process termination.

**Root cause:** this game has **1229 maps** and **1990 `replace` objects**
- a fact the R3 work above never accounted for, having been developed and
tested exclusively against the tiny `traveltest1`/`traveltest2` bed. Two
places in that work correctly mirrored the existing `Walkable`-layer
precedent for *behavior* but diverged from it on *cost*:

1. `defaultLayerNames` unconditionally included `"control"`, so every one
   of the ~1228 other maps (plus ~1990 replace sections) looked it up and
   logged a `DEVELOPMENT_VALIDATEDATA` warning for not having one - the
   `"home"` map warning in the log was the first of what would have been
   thousands of such calls across a full asset load.
2. `transformControlMapLayer` always allocated a real, full-size `int[][]`
   even when there was no control layer at all, instead of returning `null`
   the way `transformWalkableMapLayer` deliberately does. That meant
   roughly 3219 permanently-retained, almost-entirely-zero arrays (one per
   map's default layout, one per replace section) persisting for the app's
   entire lifetime - several MB of dead weight for a feature exactly one
   test map uses, on a heap the log showed already sitting at its ceiling
   before finally failing.

**Fix:** matched the `Walkable` precedent exactly instead of diverging from
it for the sake of avoiding one extra null check in `PathFinder`'s hot loop
- a tradeoff that wasn't worth it once actually measured against real data.
`transformControlMapLayer` now returns `null` when absent;
`"control"` is exempted from `findLayer`'s warning the same way
`"top"`/`"base"` already are; `LayeredTileMap.getPathWeight` and
`MapSection.replaceLayerContentsWith` each gained the one cheap null check
this reintroduces.

**Files:** `model/map/TMXMapTranslator.java`, `model/map/MapSection.java`,
`model/map/LayeredTileMap.java`.
**Verification:** compiles clean. On-device verification still to be done
by the user: confirm new-character creation no longer crashes, and that
`traveltest1`'s control-layer test destinations are unaffected (only the
allocation timing changed, not the weighting logic).

## R3 correction, part 2 — the remaining crash is a heap-ceiling issue, not a code leak

**Symptom (reported):** re-tested after the fix above; still crashed with
an out-of-memory error during new-character creation.

**What changed, and why it matters:** the `"Cannot find maplayer control"`
warning spam was completely gone from the new logcat (confirming the first
fix worked), and the app progressed significantly further before failing.
But the new crash's stack trace is entirely inside the Android framework -
`android.graphics.drawable.AnimationDrawable.run` (the loading-screen
spinner) - not anywhere in AndorsTrail's own code, and it hit the *exact
same* heap ceiling as before (`Clamp target GC heap from ... to 192MB`,
repeated many times right before failing). An unrelated allocation failing
at the identical cap, after the confirmed leak was fixed, points away from
a retained-memory leak and toward this specific emulator/device simply
defaulting to a 192MB per-app heap ceiling that a 1229-map game with a
large tileset library - loaded in one startup burst - legitimately needs
more of.

**Fix:** added `android:largeHeap="true"` to `AndroidManifest.xml`'s
`<application>` tag - the standard Android mechanism for exactly this
situation, not a workaround. This asks the OS for a larger ceiling; it
doesn't reduce what the app actually uses.

**Files:** `app/src/main/AndroidManifest.xml`.
**Verification:** manifest XML validated. On-device verification still to
be done by the user - the last lead from this investigation. If the crash
still occurs with a larger heap, that would point back to genuine excess
memory use worth checking against a clean pre-session checkout.

## R2 follow-up disabled — crashes the real game world, root cause not found

**🛑 CRITICAL — MUST BE RESOLVED BEFORE RELEASE.** Confirmed on-device: with
this feature's call sites wired in, `GlobalPathFinder.findPath()` against
the real, full game world can burn 170+ seconds of CPU and exhaust the heap,
reliably preventing new-character creation entirely. It is currently
**disabled** (see "Fix" below) and the game works normally with it off - but
the underlying defect in `GlobalPathFinder` was never found, only worked
around by not calling it this way. Before release: either root-cause and fix
the actual performance problem (needs on-device CPU profiling - see below),
or make a deliberate decision to permanently drop the map-visit-independent
global spawning mechanism (R2 follow-up) and keep only R2's original,
narrower, already-validated hook. Do not re-enable the disabled call sites
without first confirming whichever path is chosen.

**Symptom (reported):** still crashed with a fresh logcat even with
`android:largeHeap="true"` applied. This time the evidence pointed somewhere
completely different from the first two rounds.

**What the log showed:** a thread dump captured at the moment of failure -
included because the OOM handler dumps all runnable threads - showed a
background `AsyncTask` with **171+ seconds of accumulated CPU time**, stuck
in: `MonsterSpawningController.spawnAllTravellers()` →
`MapController.lotsOfTimePassed()` → `WorldSetup.createNewWorld()` →
`GlobalPathFinder.findPath()` → `PathFinder.findPathBetween()`. This is
squarely the R2 follow-up feature (global, map-visit-independent spawning
for always-travelling monster types) - and specifically, it's the *first
and only* code path anywhere that had ever called `beginTravel()` against
the real ~1229-map game world. Every previous confirmation of this whole
feature (R2's reward path, R2's original per-spawn-area hook,
`traveltester_defaults` working as reported) only ever exercised the 2-map
`traveltest1`/`traveltest2` bed.

**Investigated thoroughly; root cause not found.** Checked and ruled out,
against the real map data rather than assumption:
- Graph size (1229 maps, 3827 total exits, 50 max on one map - too small to
  explain 171 seconds via a standard Dijkstra).
- Destination unreachability (`crossglen`, a plausible player start, has a
  direct, correctly bidirectional link straight to `traveltest1`).
- A linear map-name lookup (`MapCollection.findPredefinedMap` is a proper
  `HashMap` lookup, not a scan).
- An infinite loop (both `GlobalPathFinder`'s Dijkstra and `PathFinder`'s
  local A* are structurally bounded by construction - non-negative edges
  with strict-improvement relaxation, and a hard 500-iteration cap per local
  search call, respectively).

None of these explain the actual magnitude observed. The true mechanism
remains unknown - would need on-device CPU profiling to identify the real
hot loop, which wasn't available this session.

**Fix: disable rather than guess.** Given the severity (new-character
creation was completely blocked) and no confirmed mechanism to target
directly, removed the three call sites wiring this feature into the tick
loop and world-setup paths (`MapController.lotsOfTimePassed()`,
`GameRoundController.onNewTick()`, `Savegames.onWorldLoaded()`), leaving the
underlying `MonsterSpawningController` methods in place but unused, so nothing
is lost - only re-enable once `GlobalPathFinder`'s real-world performance is
understood. R2's original, narrower hook (travel starts when a monster's own
spawn point is actually visited) is untouched and still confirmed working -
only the part that calls `beginTravel()` against a real, distant starting
map is disabled.

**Files:** `controller/MapController.java`,
`controller/GameRoundController.java`, `savegames/Savegames.java`.
**Verification:** compiles clean. On-device verification still to be done
by the user: confirm the game now starts and a new character can be
created; confirm `traveltester_defaults` reverts to R2's original,
visit-triggered behavior rather than travelling immediately.

**User-confirmed on-device:** a new character can be created again.

## `pth` debug overlay now also visualizes control-layer weights

Requested by the user specifically to help debug R3 (the "control" tile
layer added earlier): when the `pth` debug overlay is active, it previously
showed only local A* search state (which tiles were visited, the resulting
path, per-tile cumulative distance) - with no visibility into *why* a
travelling monster's path avoided or crossed a given tile once a `Control`
layer entered the picture. Since `PathFinder`'s per-tile cost is now
`10 + map.getPathWeight(...)`, someone debugging a route that looks longer
than expected had no way to see the weight data that shaped it without
reading map XML directly.

`MainView.drawPathfinderDebug` now also queries `PredefinedMap.getPathWeight`
for every visible tile and, wherever it's non-zero, draws an orange tint
(as a base layer, so the existing visited/path overlays still show through
on top of it) plus the numeric weight value in the tile's bottom-left corner
- deliberately not overlapping the existing per-path-tile distance label,
which occupies the top-left. Tied to the same `pth` toggle rather than a new
one, per the request ("when active, it should also display the control
layer"). Costs nothing extra on a map that never authors a `Control` layer
at all (`getPathWeight` returns 0 everywhere, so nothing new is drawn).

**Files:** `view/MainView.java`.
**Verification:** compiles clean. On-device verification still to be done by
the user - enable `pth`, send a travelling NPC across `traveltest1`'s
weighted row-5 corridor (`control_test_1`/`control_test_2`, from R3), and
confirm the weighted tiles are visibly tinted with their weight value shown,
explaining the route's preference for row 4.

## R2/R3 confirmed working; `android:largeHeap` removed as unnecessary

**User-confirmed on-device:** both R2 (per-`MonsterType` default
`travelFailedScript`/`travelDestination`) and R3 (control-layer terrain
weighting) work as designed. Marked done in `PLAN_refinement.md`.

Reconsidered `android:largeHeap="true"` (added mid-investigation, see the
"heap-ceiling" correction above) now that R2 follow-up - the actual cause of
all three crash reports - is disabled. Reconstructing the timeline: R2
follow-up's runaway `GlobalPathFinder` computation was already active during
*every* crash report, including the first two, before the manifest was ever
touched. The R3 fix genuinely helped (removed real dead weight, delayed the
crash) but was never sufficient alone, since R2 follow-up kept burning
CPU/memory in the background regardless. Adding `largeHeap` didn't fix
anything - it just gave that runaway computation more room to run (192MB →
576MB) before finally exhausting even the larger heap; the only reason it
looked like partial progress is that a bigger heap took longer to fill,
which incidentally gave Android enough time to capture the thread dump that
finally revealed the real culprit. With no remaining evidence of a genuine
baseline memory need now that R2 follow-up is off, and given `largeHeap` is
generally discouraged (costs other apps their share of system memory,
signals a memory-inefficient app) except when actually necessary, removed it
- also so that any future memory issue produces a clean signal rather than
being masked by a needlessly larger heap.

**Files:** `app/src/main/AndroidManifest.xml`.
**Verification:** manifest XML validated. Not separately re-tested on-device
(no reason to expect a regression - this only lowers the heap ceiling back
to the platform default, and the app was already confirmed working at that
default before R2 follow-up ever existed); flag it if a memory issue
resurfaces.

## R2 follow-up's dead code fully removed from the codebase

The user manually reverted the three call sites disabled earlier
(`MapController.lotsOfTimePassed()`, `GameRoundController.onNewTick()`,
`Savegames.onWorldLoaded()`) back to their pre-R2-follow-up state, and asked
whether `MonsterSpawningController.java` - left untouched at the time, since
disabling it wasn't required to stop the crash - could be cleaned up the
same way without breaking anything.

Confirmed and removed the now-fully-dead part: `TravelSpawn`, the
`travelSpawnAreas` cache field, `getTravelSpawnAreas()`, `spawnAllTravellers()`,
and `maybeSpawnTravellers()`, plus the now-unused `ArrayList`/`List` imports.
With all three call sites gone, nothing anywhere referenced these anymore.

**Explicitly left in place**, and must stay: the `travelDestinationMapID`
check inside the private `spawnInArea(...)` method (`beginTravel(...)` call)
- this is R2's *original* hook, confirmed working, triggered by the normal
per-map-visit spawn machinery (`spawnAll`/`maybeSpawn`/`spawnAllInArea`,
never the disabled global mechanism). Removing it would have regressed R2
itself, not just finished cleaning up the crash-causing follow-up.

**Files:** `controller/MonsterSpawningController.java`.
**Verification:** compiles clean.

## R4 — Data-driven per-NPC path variance ("organic" route jitter)

**Goal:** two monsters of the same type walking the same route shouldn't
compute byte-for-byte identical paths — see `PLAN_refinement.md`'s R4 section
for the full design writeup. Implemented per that plan, no deviations.

**Design:** `MonsterType` gains `pathVarianceMultiplier` (float, `[0,1]`,
clamped at parse time, default `0`); `Monster` gains `pathVarianceSeed` (int,
assigned once via `Constants.rnd.nextInt()` at construction, persisted across
saves). `PathFinder` hashes `(pathVarianceSeed, x, y)` into a small,
deterministic per-tile cost bump (`jitter()`, capped by `JITTER_MAX = 18`,
scaled by the monster type's multiplier) — deterministic so the same monster
re-searching the same tile on a later tick (every tick reruns A* from
scratch) doesn't flicker between alternatives, but different per monster so
two individuals of the same type visibly diverge.

Unlike R3's control-layer weighting (deliberately kept admissible), this is
knowingly, deliberately a small bounded exception to "provably shortest path"
— the goal here was never route-optimality, only that a monster never takes
an obviously bad detour purely from jitter.

**Scoping:** rides the exact same `Monster m`-nullness seam
`PathFinder.findPathBetween` already uses to scope player-avoidance to
travel-approach searches, for free — no new plumbing needed. Confirmed by
grep, not assumed, that every `m == null` caller
(`PredefinedMap.calculateDistanceMatrix`, all three of `GlobalPathFinder`'s
own `findPathBetween` calls) is therefore structurally guaranteed to never
see jitter, keeping the shared exit-distance matrix and off-screen wall-clock
interpolation exactly as before. `moveCost` is now computed by one shared
`PathFinder.moveCost(x, y, m)` helper, called from all three spots that used
to separately inline `10 + map.getPathWeight(...)` (the two in
`findPathBetween`, plus `findPositionOnPath`'s re-derivation of which node
produced `lastPathDistance` — the same third spot R3's own notes already
flagged as easy to miss), so a future change to this formula only has one
place to get right instead of three kept in sync by hand.

`MonsterMovementController.enterTravellingPool`/`.spawnMonsterOnMap` also
pass a real `m`, so their local searches (recalibrating `travelPath
.startTime`/finding a wall-clock handoff position) pick up jitter too — by
design, not a scope leak: those recomputations must agree with the same
jittered cost model that actually produced the monster's on-screen movement
moments earlier, or they'd desync from reality the same way Phase 0's bugs
did, just between "what was actually walked" and "what this recalibration
thinks was walked" instead of between on-screen and off-screen state.

**Savegame compatibility:** `pathVarianceSeed` is written/read unconditionally
as a plain int (`fileversion >= 89`, one past R2's `travelFailedScript` gate
of 88) — no presence flag needed, since every `Monster` always has a value,
mirroring how `moveCost` itself (`fileversion >= 34`) is handled. No
savegame format change beyond this one new gated field.

**Test rig:** set `pathVarianceMultiplier: 1.0` on the existing `traveltester`
`MonsterType` rather than authoring new spawn/monster/TMX data — doing so
activates `variation_one`/`variation_two`, destination objects already
present in `traveltest1.tmx` with matching "Go to variation one/two tile
center" replies already wired in `conversationlist_traveltest.json`, both
apparently pre-staged for exactly this phase and otherwise unused until now.
Documented in `travellingNPC_dataSchema.md` §3.5 that this also perturbs
`traveltester`'s other trips (including the Phase 6/R1 regression routes) —
temporarily zero the field for an exact jitter-free regression re-check.

**Files:** `resource/parsers/json/JsonFieldNames.java`,
`resource/parsers/MonsterTypeParser.java`, `model/actor/MonsterType.java`,
`model/actor/Monster.java`, `controller/PathFinder.java`,
`docs/wip/travellingNPC_dataSchema.md`, plus test data
(`res/raw/monsterlist_traveltest.json`'s `traveltester` entry).
**Verification:** compiles clean (`gradlew :app:compileDebugJavaWithJavac`).
**On-device verification still needed from the user**: talk to `traveltester`
and send it to "variation one"/"variation two" (and a couple of existing
rect/diag/control-test trips) to confirm the walked path looks organically
varied rather than perfectly clean and still arrives correctly, and that
temporarily setting `pathVarianceMultiplier` back to `0` reproduces the exact
pre-R4 deterministic route on the Phase 6 `rect1_corner`→`rect3_corner` case.

## R5 — Let an on-screen travelling NPC pause to rest mid-journey

**Goal:** a travelling NPC, only while physically simulated on the map the
player currently has loaded, should occasionally stand still for a short,
data-driven number of ticks, with `travelPath`'s ETA bookkeeping corrected to
match. Full design in `PLAN_refinement.md`'s R5 section.

**Design:** `MonsterType` gains `travelRestChance` (int percent, default `0`,
rolled once per tick via `Constants.roll100`) and `travelRestDuration`
(`ConstRange`, default `{min:1, max:2}`). `Monster` gains a **not persisted**
`travelRestTicksRemaining` (int), mirroring `travelBlockedRetries`'
short-lived, journey-independent lifetime. The check sits at the very top of
`MonsterMovementController.determineMonsterNextPosition`'s travel branch,
before any pathfinding call: if already resting, decrement and stand still;
otherwise roll `travelRestChance` once, and on success roll
`travelRestDuration`, correct the ETA (below), and stand still the same way.

**On-screen only, structurally, not by policy.** `determineMonsterNextPosition`
is only ever reached from `moveMonsters()`'s loop over `currentMap.monsters`;
`tryPlaceTravellingMonster` (the wall-clock path for pooled, off-screen
monsters) never calls it at all - confirmed by grep before implementing, not
assumed. A new `showTravelDebug` log line fires the first time resting logic
runs, naming the loop/map, so this stays positively checkable on-device too.

**Return value note:** both resting paths return `true` from
`determineMonsterNextPosition`, not the plan's literal `false` - re-checked
against that method's own documented return contract (`true` = "already
fully handled, don't touch `nextPosition`") immediately above it in the
file. Returning `false` would have driven the normal move machinery
(`monsterCanMoveTo` + `moveMonsterToNextPosition`, a real animation +
`onMonsterMoved` listener firing) every single resting tick for a
zero-distance "move" - directly against the plan's own "cheaper than a real
tick" framing for this path. `true` matches both the contract and that intent.

**ETA correction:** `MonsterMovementController.correctTravelPathForRest`
increases `travelPath.predictedTime` by `ticksRested * 10` and
`cumulatedDistance` on the current `GlobalPathEntry` (identified by
`travelPath.currentPosition`) and every later one - never an already-completed
leg, never `distance` itself - by the same amount. Required loosening
`GlobalPath.predictedTime` and `GlobalPathEntry.cumulatedDistance` from
`final` to mutable (checked every reader of both fields first via grep -
only `MonsterMovementController` and `MainView`'s debug view, both read-only,
neither assumes immutability - before loosening them). Worked through
algebraically why correcting only `cumulatedDistance` (never touching
`distance`) is sufficient even across multiple rests on the same leg or a
later mid-leg hand-off to the travelling pool: both `enterTravellingPool` and
`tryPlaceTravellingMonster` derive a leg's *start* threshold as
`cumulatedDistance - distance` rather than from a stored value, so inflating
`cumulatedDistance` alone correctly shifts both ends of the resting leg
outward by the cumulative rest time so far, regardless of which of those two
call sites reads it next. No separate hand-off correction needed:
`enterTravellingPool` already recalibrates `startTime` from the monster's
live physical position, which has no independent dependency on how it got
there.

**Test rig:** `traveltester` (`monsterlist_traveltest.json`) now also has
`travelRestChance: 25` and `travelRestDuration: {min:1, max:3}` - reuses the
existing NPC and menu, no new spawn/monster/TMX data needed.

**Files:** `resource/parsers/json/JsonFieldNames.java`,
`resource/parsers/MonsterTypeParser.java`, `model/actor/MonsterType.java`,
`model/actor/Monster.java`, `controller/MonsterMovementController.java`,
`controller/GlobalPathFinder.java`, `docs/wip/travellingNPC_dataSchema.md`,
plus test data (`res/raw/monsterlist_traveltest.json`'s `traveltester` entry).
**Verification:** compiles clean (`gradlew :app:compileDebugJavaWithJavac`).
**On-device verification still needed from the user**: send `traveltester`
on any trip while staying on `traveltest1`, confirm it visibly pauses for a
tick or few at a time; check the `showTravelDebug` log for the "resting
on-screen (...) map=" line and for `predictedTime` growing by exactly
`10 * ticksRested` each time a rest begins; separately, confirm a monster
that travels entirely off-screen arrives with no behavioral difference from
before this phase.

## R5 follow-up — hardcoded cooldown between rests

**Symptom (user-reported after on-device testing):** R5 worked, but a
monster could rest, walk a single tile, and immediately roll another rest -
technically correct (each tick's `travelRestChance` roll is independent) but
visually unnatural, "rest, walk 1 tile, rest again."

**Fix:** `Constants.MONSTER_TRAVEL_REST_COOLDOWN_TICKS = 10` (hardcoded, not
a new authorable `MonsterType` field, per the user's explicit request) -
placed alongside `MONSTER_TRAVEL_MAX_BLOCKED_RETRIES`, the same "travel
tuning constant" family. New, not-persisted `Monster
.travelRestCooldownRemaining` starts counting down from that constant the
instant a rest ends (handled for both the "multi-tick rest's final
decrement" case and the "one-tick rest already over this same tick" case,
which needed its own explicit check). While the cooldown is active, the
`travelRestChance` roll is skipped entirely each tick and the monster falls
through to normal travel movement, exactly as if `travelRestChance` were `0`
for that stretch.

Implemented as a tick count rather than tracking literal walked distance:
one tick's local travel-approach step covers at most one tile (the same
tick~move~tile equivalence R5's own `correctTravelPathForRest` already
relies on), so a flat tick count is a faithful, much simpler stand-in for
"tiles walked" - actual distance tracking would also have to account for
ticks where movement was attempted but blocked, which consume a tick without
any real position change.

**Files:** `controller/Constants.java`, `model/actor/Monster.java`,
`controller/MonsterMovementController.java`,
`docs/wip/travellingNPC_dataSchema.md`.
**Verification:** compiles clean (`gradlew :app:compileDebugJavaWithJavac`).
**On-device verification still needed from the user**: confirm consecutive
rests are now visibly spaced apart rather than back-to-back.

## R6 — Extension point for a future "pause travel for other activities" feature

**Goal:** prepare a clean, reusable attachment point for a future activity
system (hunting, roaming, etc. - its own separate design pass, out of scope
here), using R5's resting mechanism as the prototype. No new pause trigger,
no observable behavior change - a pure refactor. Full design in
`PLAN_refinement.md`'s R6 section.

**Design:** `Monster.travelRestTicksRemaining` is replaced by a nested
`Monster.TravelPauseReason` enum (one case today: `resting`) plus
`travelPauseReason` (nullable) / `travelPauseTicksRemaining`. The single
inline rest-continuation block in `MonsterMovementController` is split into
four single-purpose methods: `handleTravelPause(m)` (generalized
continuation, called once at the top of the travel branch), `beginTravelPause
(m, reason, ticks)` (starts a new pause and corrects the ETA),
`onTravelPauseEnded(m)` (reason-specific cleanup), and `correctTravelPathForPause`
(R5's `correctTravelPathForRest`, renamed - its logic was already fully
reason-agnostic).

**Per the user's explicit instruction** ("the Rest separation should not
interfere with stuff like hunting"), `travelRestCooldownRemaining` was
deliberately *not* folded into the generalized hook - it stays a separate
field, checked and decremented locally where resting's own trigger (the
`travelRestChance` roll) lives, not inside the shared
`handleTravelPause`/`beginTravelPause`/`onTravelPauseEnded` machinery.
`onTravelPauseEnded`'s `if (endedReason == resting)` guard is the only place
resting's own follow-up state is touched, so a future pause reason inherits
the stand-still/ETA-correction mechanism for free but nothing rest-specific.
The decision of *whether* to begin a pause (resting's cooldown gate + chance
roll) was likewise kept local to `determineMonsterNextPosition`'s travel
branch, not folded into a shared "decide to pause" method, for the same
reason.

Added a "Future extension point" section to `travellingNPC.md` restating the
plan's coordination requirements (suspend movement via the same hook,
correct the ETA the same unconditional way, keep reason-specific cleanup
reason-specific) and its three explicitly open questions (same `travelPath`
resumed vs. replaced, combat interruptibility, whether `isTravelling` needs
a third state) without answering them - that's for a future, separate
planning pass.

**Files:** `model/actor/Monster.java`, `model/actor/MonsterType.java` (one
stale doc-comment reference fixed), `controller/MonsterMovementController.java`,
`controller/GlobalPathFinder.java` (doc comments only, updated to point at
the renamed method), `docs/wip/travellingNPC.md`,
`docs/wip/travellingNPC_dataSchema.md`.
**Verification:** compiles clean (`gradlew :app:compileDebugJavaWithJavac`).
No behavior change intended - re-verify R5's own on-device checks (rests
pause visibly, spaced apart by the cooldown, `predictedTime` still grows
correctly) still hold after this refactor.

## Not yet addressed

Everything else in [PLAN.md](PLAN.md) beyond what's listed above remains
open: automated tests (Phase 7, explicitly deferred by the user until
there's a JUnit setup for the whole project); Phase 8's other two stretch
items (autonomous travel triggers, a dev-menu teleport shortcut into the
test bed) weren't asked about and remain untouched.

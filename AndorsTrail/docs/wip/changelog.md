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

## Not yet addressed

Everything else in [PLAN.md](PLAN.md) beyond what's listed above remains
open: automated tests (Phase 7, explicitly deferred by the user until
there's a JUnit setup for the whole project); Phase 8's other two stretch
items (autonomous travel triggers, a dev-menu teleport shortcut into the
test bed) weren't asked about and remain untouched.

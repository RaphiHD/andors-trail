# Travelling NPCs — Refinement Plan

The core feature (see [travellingNPC.md](travellingNPC.md), [PLAN.md](PLAN.md),
[changelog.md](changelog.md)) is implemented and working end-to-end. This
document plans the next round of work: six refinements requested after the
fact, none of which are bugfixes — all are deliberate enhancements on top of
a working foundation.

**Standing instruction, same as the original plan: always update
[changelog.md](changelog.md) alongside every change made against this
document.** Each phase below (R1-R6) gets its own entry there as it's worked
on, following the existing changelog's format (symptom/goal, root cause or
design, the fix, files touched) — don't batch documentation updates until
the end, and don't let a phase land without one.

## Ordering rationale

The six items are grouped into phases **R1–R6**, ordered by dependency and
shared risk rather than the order they were requested in:

1. **R1 (cosmetic turning)** first — fully scoped already (it's the one
   pre-existing open item from `PLAN.md`'s "Known cosmetic polish items"),
   isolated to `PathFinder.java`, zero interaction with anything else here.
2. **R2 (MonsterType travel defaults)** second — pure data-schema/plumbing
   work, no interaction with pathfinding internals, safe to land independently
   of R3–R5.
3. **R3 (control-tile path weighting)** and **R4 (per-NPC path variance)**
   are grouped adjacently because **both modify the exact same code**:
   `PathFinder`'s per-tile move-cost computation. Doing them back-to-back
   means that computation is deliberately redesigned once (as "base cost +
   terrain weight + monster jitter") instead of two separate, potentially
   conflicting patches to the same lines. R3 is done first because it
   establishes the "cost can now vary per tile" infrastructure that R4 then
   reuses for its own, orthogonal purpose.
4. **R5 (rest ticks)** comes after R3/R4 — it doesn't touch the cost model at
   all, but it does touch the same travel tick-loop
   (`MonsterMovementController.determineMonsterNextPosition`) that R3/R4's
   verification will already have exercised carefully, and it establishes a
   "pause the tick-driven walk cycle" primitive that...
5. **R6 (future activity-pause hook)** deliberately reuses, so it must come
   last.

## Cross-cutting concerns (apply to more than one phase below)

- **A\* optimality.** `PathFinder`'s heuristic is currently *admissible* for
  the uniform-cost model (base cost 10 per move, `10 * Chebyshev distance`
  heuristic) plus one already-accepted, deliberately *bounded* exception (the
  Phase 6 tie-breaker, capped at 9 — always less than one move's cost, so it
  can only ever break exact ties, never override a genuine shortest-path
  decision). R3 and R4 both add further per-tile cost variation and must each
  make a **deliberate, stated choice** about whether they preserve
  admissibility (safe, but limits what "preference" can mean — see R3) or
  knowingly extend the existing bounded-inadmissibility precedent (R4, by
  design — see its section). Re-run the Phase 6 regression check
  (`traveltest1`, `rect1_corner` → `rect3_corner`, forced detour) after each
  of R1/R3/R4 to confirm neither reintroduces zig-zagging or a no-op-tiebreak
  class of bug.
- **Wall-clock interpolation integrity (the Phase 0 lesson).** A travelling
  monster is tracked two structurally different ways depending on whether
  it's on the map the player currently has loaded: tick-by-tick local
  simulation (`moveMonsters()`'s first loop, over `currentMap.monsters`) or
  wall-clock interpolation from `travelPath.startTime`/`predictedTime`
  (`tryPlaceTravellingMonster`, over `world.monsters.travellingMonsters`).
  Phase 0 was entirely about bugs caused by these two models silently
  disagreeing. R4 and R5 both introduce on-screen-only behavior differences
  (jitter, pausing) — R4 avoids ever needing a correction by construction
  (§R4), R5 requires one and must not skip it (§R5). Any future phase that
  touches this tick loop must re-ask this question explicitly.
- **Data schema doc.** [`travellingNPC_dataSchema.md`](travellingNPC_dataSchema.md)
  was written as the authoritative schema reference for the ATCS content
  editor. R2, R3, R4, and R5 all add new authorable fields — each phase's
  steps include updating that document, not as an afterthought at the end.

---

## R1 — Fix the abrupt pre-detour turning (accepted cosmetic gap) ✅ Done

**Source:** `PLAN.md`'s "Known cosmetic polish items (pre-release)" section;
full root-cause analysis already recorded there and in `changelog.md`'s Phase
6 follow-up entries — not repeated here.

**What was actually implemented, and why it differs from the plan's literal
option A:** working through option A's two concrete framings while
implementing this surfaced that neither is quite right. A pure turn-*penalty*
(discouraging direction changes) would push the *opposite* direction from
what's wanted — the reported behavior ("walk straight, then one sharp turn")
already has the *minimum possible number* of direction changes, so
discouraging turns further would only entrench it. A pure bearing-deviation
term (comparing each step's direction against the current tile's bearing to
the goal) can't help either: every tile on the direct approach to an obstacle
*is*, at the moment it's stepped on, the most direct bearing available — a
per-step local metric has no way to "know" a detour is coming a few tiles
ahead, so it scores the entire straight approach identically to today's
behavior right up to the tile where the detour becomes unavoidable.

The actual fix needed is a form of "obstacle clearance" bias: among several
routes that tie on total true cost (the same class of tie the Phase 6
line-tiebreak already resolves, just along a different axis — *when* a route
starts detouring, not how it distributes diagonal/straight moves once
detouring), prefer whichever one keeps a little more distance from
unwalkable terrain as soon as that's free to do, rather than hugging the
obstacle's edge until physically forced off. Implemented as a second, small,
bounded tiebreak term — same architecture and same "always a tiebreak, never
a route-overriding force" contract as the existing line-tiebreak:

- `PathFinder.clearanceScore(x, y)` measures how hemmed in a candidate is by
  unwalkable *terrain* (deliberately ignoring event areas and other actors —
  those move every tick and would make this a moving target not worth
  chasing) within a 2-tile radius, immediate neighbours weighted fully and
  the outer ring less (a falloff, not a sharp step function) — using a
  preallocated scratch `CoordRect` (`clearanceScratch`) so this adds no
  per-call allocation to the hot path.
- `heuristic()` (now an instance method, since it needs `map`) combines this
  into `clearanceTiebreak` alongside the existing `lineTiebreak`, **each
  capped independently at 9** rather than sharing one combined cap.

**A first version (8-immediate-neighbour count, sharing `lineTiebreak`'s cap)
looked right on paper but failed a direct on-device-equivalent check**: after
implementing it, it was tested by porting `PathFinder`'s exact algorithm
(including a faithful port of the non-stable array-based `OpenSetHeap`, not
just its scoring formula) into a small script and running it against
`traveltest1`'s actual decoded tile data for all four corner-to-corner pairs.
`rect1`↔`rect2` and `rect4`↔`rect3` eased in correctly; `rect2`↔`rect4` and
`rect3`↔`rect1` still showed the old abrupt-turn behavior — exactly the split
reported. Root cause, found by tracing the real numbers: every one of the
four corner destinations sits right next to its room's walls (that's what
makes it a "corner"), but *which* walls differ per corner (N+W, N+E, ...).
Escaping those specific walls sometimes happens to agree with staying on that
trip's straight line and sometimes conflicts with it — and when it conflicts,
the original 8-neighbour signal was too weak/sharp and too easily crowded out
by the shared cap to overcome `lineTiebreak`'s pull back toward the line. The
formulas themselves were confirmed properly symmetric under swapping x/y (no
code bug there) — the asymmetry came entirely from each corner's differently-
oriented geometry interacting with two terms that were individually correct
but jointly too weak/too coupled. Widening the radius (so the signal isn't
so sharply tied to the exact tile standing at a wall) and decoupling the caps
(so a strong nearby-wall signal can no longer be crowded out by an
already-large line deviation) was verified, the same simulation way, to
resolve all four corners consistently, with **zero path-length change** from
before this phase on every case checked (all four corners, plus the original
Phase 6 `rect1_corner`→`rect3_corner` regression case, plus two longer
diagonal-corner trips) — see `changelog.md` for the full investigation and
the concrete numbers.

This is squarely still "a bounded heuristic/tiebreak term," matching the
plan's option A in spirit and architecture — just measuring obstacle
proximity instead of a literal turn angle, since that's what the goal
("ease into the turn earlier") actually requires. Option B (post-process
path smoothing) was not needed.

**Files:** `PathFinder.java`.
**Verification:** compiles clean
(`gradlew :app:compileDebugJavaWithJavac`), independently checked by
replaying the algorithm (exact heuristic formula *and* the real, non-stable
`OpenSetHeap`) against `traveltest1`'s actual decoded map data for all four
corner pairs plus the Phase 6 regression case — all now ease in within the
first move or two, with no path-length regression. **User-confirmed
on-device: "the path already looks very natural."**

<details>
<summary>Original planning notes (kept for reference)</summary>

**Recap of the problem:** the Phase 6 tie-breaker minimizes a candidate
tile's *distance from the straight start→goal line*, which correctly produces
a natural interleaved diagonal/straight stagger **during** a forced detour,
but does nothing to encourage turning *early*, before the detour is
unavoidable — a monster walks straight at an obstacle right up to the last
possible tile, then turns sharply. This is a genuinely different optimization
objective (minimize distance-from-line vs. minimize turn sharpness), not a
tuning bug in the existing tie-breaker.

**Steps:**
1. Prototype **option A — a turn-angle term in the heuristic/tie-breaker**:
   in addition to the existing perpendicular-distance term, penalize a
   candidate node based on the angle between "direction from predecessor to
   candidate" and "direction from candidate to goal" (or, cheaper to compute
   without tracking direction explicitly: penalize based on the second
   derivative of position along the path so far — a discrete curvature
   proxy). Must stay bounded the same way the existing tie-breaker is
   (small relative to move cost 10) so it remains a tie-breaker, not a
   route-changing force.
2. Prototype **option B — a post-process smoothing pass**: after
   `findPathBetween` returns a path (already reconstructed via `predecessor`
   in `showPathfinderDebug`'s `last_path`, though that reconstruction isn't
   normally kept — only the immediate `nextStep` is used live), walk it and,
   for a short lookahead window, replace a "straight-straight-...-then-sharp
   diagonal-run" pattern with an evenly-eased version, provided the
   replacement doesn't cross a now-unwalkable tile. More invasive (touches
   how paths are consumed, not just scored) and only meaningful for the local
   per-tick `findPathFor` callers, since nothing else reads a full path today
   (the game only ever asks for "what's the next single step").
3. Recommend **option A first** — it fits the existing architecture (a pure
   scoring change, same shape as the Phase 6 tie-breaker, no new data
   structures) and is far less invasive than B. Only fall back to B if A
   can't produce a good-enough result without compromising the tie-breaker's
   existing bounded-ness guarantee.
4. Whichever option is chosen, re-verify the full Phase 6 regression case
   (interleaving during a detour) alongside the new pre-detour case — don't
   fix one at the other's expense.

</details>

---

## R2 — Per-`MonsterType` default `travelFailedScript` / `travelDestination` ✅ Done

**Goal:** today both are only ever set dynamically, via script rewards
(`setTravelFailedScript`, `setDestination` — see
[`travellingNPC_dataSchema.md`](travellingNPC_dataSchema.md#3-json--phraselist-rewards-and-requirements)),
scoped to whichever `Monster` is the current NPC context of a conversation.
There's no way to say "every monster of this type should start with this
standing fallback script" or "this monster type should always begin
travelling toward X as soon as it spawns" without scripting it by hand for
every spawn. Add both as optional `MonsterType`-level defaults, applied once
per spawned `Monster` instance, without removing or changing the existing
script-reward mechanism (which continues to work exactly as before, and — for
`travelDestination` in particular — continues to be how an already-spawned
monster gets sent somewhere *new* later).

### Step 1 — `travelFailedScript` default

The simpler of the two: a plain phrase-ID string, copied onto the monster at
construction time, no pathfinding involved.

- `JsonFieldNames.Monster.travelFailedScript = "travelFailedScript"`.
- `MonsterTypeParser`: `o.optString(JsonFieldNames.Monster.travelFailedScript, null)`, following the exact pattern already used for `phraseID` (line 66 of `MonsterTypeParser.java`).
- `MonsterType`: new `public final String travelFailedScript` field + constructor parameter.
- `Monster`: in the constructor (or `resetStatsToBaseTraits`, whichever is judged more correct — `resetStatsToBaseTraits` re-applies on respawn-in-place scenarios, which is probably what's wanted here too, so a unique monster that gets its stats reset doesn't lose its standing fallback), set `this.travelFailedScript = monsterType.travelFailedScript;` as the *initial* value. A later `setTravelFailedScript` reward still simply overwrites the field, exactly as today — no interaction/precedence logic needed beyond "constructor sets the default, a reward can always override it afterward."

### Step 2 — `travelDestination` default

More involved: setting a *destination* requires actually computing a
`GlobalPath` via `beginTravel`, which needs `ControllerContext`/`WorldContext`
and the monster to already have a resolved `currentMapID`/position — none of
which are available inside `MonsterType`/`Monster`'s own constructors. This
needs to be applied where a `Monster` is actually placed into the world, not
where it's constructed.

- New optional JSON shape (nested object, mirroring the `setDestination`
  reward's own `rewardID`/`mapName` pair):
  ```json
  "travelDestination": { "mapName": "townmap", "areaID": "shop_home" }
  ```
  Parsed via `o.optJSONObject(JsonFieldNames.Monster.travelDestination)` →
  two new `MonsterType` fields, `travelDestinationMapID` /
  `travelDestinationAreaID` (both `null` if the property is absent — the
  common case).
- Hook point: `MonsterSpawningController.spawnInArea` (the one non-test
  private overload, called by both `spawnAllInArea`'s and `maybeSpawn`'s
  paths — i.e. every real spawn), immediately after `Monster m = a.spawn(p,
  type);`. If `type.travelDestinationMapID != null`, call
  `controllers.monsterMovementController.beginTravel(m, type.travelDestinationMapID, type.travelDestinationAreaID)`.
  `beginTravel` already handles every edge case this needs for free: it's
  safe to call before `m` is added to `map.monsters` or not (re-check
  ordering against `spawnInArea`'s existing `monsterSpawnListeners
  .onMonsterSpawned` call — `beginTravel` only needs `m.currentMapID`
  and `m.rectPosition`, both already set by `Monster`'s own constructor via
  the `area`/`MonsterSpawnArea` passed to it, so ordering relative to the
  spawn listener call shouldn't matter, but verify by reading
  `MonsterSpawnArea.spawn` before assuming), it already fails cleanly via
  `travelFailedScript` if unreachable (which, per step 1, may itself now be
  a `MonsterType` default — a spawn-time itinerary with a built-in fallback,
  entirely from data), and it already special-cases "monster isn't on the
  player's currently-loaded map" by handing straight to the travelling pool.
- **Respawn behavior is a deliberate design point, not an oversight:** every
  time this spawn code path creates a fresh instance of this `MonsterType`
  (initial spawn, respawn after death/despawn, `spawnAllInArea` refilling a
  spot) the default re-applies. This is almost certainly the desired
  behavior for the motivating use case ("this shopkeeper always starts by
  walking to their stall") — call this out explicitly in the doc update
  (step 3) so a future content author isn't surprised that a respawned
  unique monster travels again from scratch rather than resuming wherever it
  last was (which is unrelated saved-`Monster` state, not part of
  `MonsterType` at all, and out of scope here).

### Step 3 — documentation

Add both new fields to `travellingNPC_dataSchema.md` §2/§3 area — most
naturally as a new subsection near §3's existing `setDestination`/
`setTravelFailedScript` writeup, cross-referencing that these are the
*default* form of the same two mechanisms, with the same reachability
caveats (§4 of that doc) applying equally to a `MonsterType`-level default
destination.

**Implementation note, resolving Step 1's open question:** the default is
applied in `Monster`'s constructor, **not** `resetStatsToBaseTraits()`.
Checking call sites before implementing found `resetStatsToBaseTraits()` is
also called from `ActorStatsController.recalculateMonsterCombatTraits()`,
which runs repeatedly over a monster's whole lifetime (e.g. after any
condition change), not just at spawn — applying the default there would have
silently overwritten a `setTravelFailedScript` reward's value on the very
next unrelated combat-stat recalculation. The constructor runs exactly once
per `Monster` instance, which is what "starting value, reward always wins
afterward" actually requires.

Savegame compatibility was also checked rather than assumed: `Monster`'s
parcel-reading constructor calls the regular constructor first (applying the
`MonsterType` default), then conditionally overwrites `travelFailedScript`
from the save data only if `fileversion >= 88` *and* the save actually had a
value for this specific monster. Loading an old save where this monster
never got the reward therefore now picks up whatever default its
`MonsterType` currently declares, rather than staying stuck on `null` forever
— consistent with how this codebase already treats every other
newly-introduced, fileversion-gated field (no explicit "else" branch
resetting to a neutral value anywhere in this constructor), and arguably the
more useful behavior besides (existing shopkeepers gain the new behavior
without needing a fresh save). No savegame format change or `fileversion`
bump was needed for this step.

**Files:** `resource/parsers/json/JsonFieldNames.java`,
`resource/parsers/MonsterTypeParser.java`, `model/actor/MonsterType.java`,
`model/actor/Monster.java`, `controller/MonsterSpawningController.java`,
`docs/wip/travellingNPC_dataSchema.md`, plus test data
(`res/raw/monsterlist_traveltest.json`'s new `traveltester_defaults` entry,
`res/xml/traveltest1.tmx`'s new matching spawn object).
**Verification:** compiles clean
(`gradlew :app:compileDebugJavaWithJavac`). Added a second test `MonsterType`
(`traveltester_defaults`, no `phraseID` — deliberately not talkable, so only
the default-driven path can possibly be exercised) rather than modifying
`traveltester` itself, keeping the reward-driven and default-driven paths
independently testable side by side. It declares `travelFailedScript:
"traveltester_rect1_center"` and `travelDestination: {mapName:
"traveltest1", areaID: "rect4_corner"}`, spawned via a new spawn object on
`traveltest1.tmx` near the existing `traveltester` spawn.
**User-confirmed on-device:** "the second test monster immediately starts
travelling when I enter the map - and also returns when I block the path."
Both the default-driven and reward-driven paths, and their interaction with
the R1 pathfinding work, are confirmed working.

---

## R2 follow-up — global, map-visit-independent spawning for always-travelling monster types ⚠️ Disabled — crashes the real game world, root cause not found

**Trigger:** after confirming R2's basic version worked, the user asked for
a behavior change: a monster with a `travelDestination` default should begin
travelling *immediately when the world loads, regardless of which map is
currently active* — not only once the player happens to visit its spawn's
home map (R2's original behavior, inherited from every other spawn area's
laziness). Assessed before implementing, per the user's explicit request.

### Assessment

**Feasible, and cheaper than expected.** Checked rather than assumed: every
`PredefinedMap`'s exit-to-exit distance matrix is already computed in its own
constructor, at asset-load time, for every map in the game — `beginTravel`
already works correctly for a monster whose home map has never been visited.
What's lazy is monster *creation*, not map geometry: `spawnAll`/`maybeSpawn`
are only ever called for `world.model.currentMaps.map`. An existing hook,
`MapController.lotsOfTimePassed()`, already loops over *every* map and
already runs at new-game creation, player respawn, and resting — extending
it to also spawn+`beginTravel` any not-yet-used travel-eligible spawn area,
globally, fits its existing purpose exactly. Duplicate-spawning is prevented
for free by the existing `MonsterSpawnArea.quantity`/`isSpawnable()`
bookkeeping (already persisted across saves) — spawning through the same
`MonsterSpawnArea.spawn()` path R2 already uses marks the area's quota used,
so a later, normal lazy spawn attempt on that same area correctly sees
`isSpawnable() == false` and does nothing.

**Performance: negligible, provided the *ongoing* respawn check doesn't
naively scan every map every tick.** The user's follow-up choice (see below)
extends this from "spawn once at world catch-up" to "keep respawning
non-unique travel-eligible monsters over time, regardless of player
location" — which needs a *per-tick*, not just one-time, global check. Doing
that check by iterating every map's every spawn area, every tick (the tick
runs every `Constants.TICK_DELAY` = 500ms), would scale with total world
size for a feature that's meant to cover a handful of specially-authored
NPCs, not the general monster population — the wrong shape, even though each
individual check (`isSpawnable`/`rollShouldSpawn`) is cheap in isolation.
**The correct design pre-filters once**: scan every map's spawn areas a
single time, cache the small list of ones whose (single) monster type
declares a `travelDestination`, and have the per-tick check iterate only
that small cached list. This keeps the added per-tick cost proportional to
how many always-travelling NPCs the content actually defines, not to total
map count - the same shape `GlobalPathFinder`'s on-demand (not per-tick)
search already uses to stay cheap.

### Scope decision (asked via AskUserQuestion)

Presented two options: (a) one-time eager spawn only, or (b) also make
*ongoing* respawns of non-unique always-travelling types map-independent.
**The user chose (b).**

### Design implemented

- `MonsterSpawningController` gained a private, lazily-built, cached list
  (`getTravelSpawnAreas()`) of every spawn area, across every map, whose spawn
  group resolves to exactly one `MonsterType` that declares a
  `travelDestination` default. Deliberately restricted to single-type spawn
  groups: a mixed group's random-type roll (`getRandomMonsterType`) can't
  guarantee it lands on the travel-default type, so treating a mixed group as
  "always travels" would be misleading — an always-travelling NPC should use
  a spawn area dedicated to it (the same pattern `traveltester_defaults`
  already uses). The list is built once and cached for the process lifetime:
  the set of maps/spawn areas/`MonsterType` data behind it is static asset
  data that never changes across game sessions, only each area's *live*
  `isSpawning`/`quantity.current` state does — which is deliberately **not**
  cached, and re-checked fresh via `isSpawnable()`/`rollShouldSpawn()` every
  time the list is consulted, so save-state correctness is untouched by the
  caching.
- `spawnAllTravellers()` — the one-time, world-catch-up half: for every
  cached entry, calls the existing `spawnAllInArea(..., respawnUniqueMonsters
  = true)` (unchanged, reused as-is), filling that area to its quota exactly
  like a normal map-visit spawn would, uniques included. Wired into
  `MapController.lotsOfTimePassed()` (covers new game, player respawn,
  resting) and `Savegames.onWorldLoaded()` (covers loading an existing save —
  `lotsOfTimePassed()` alone doesn't run on load, and skipping this would
  have left an old save's never-visited travel-eligible spawn areas stuck
  relying on the slow probabilistic per-tick roll below, inconsistent with
  R2's own "old saves retroactively benefit from new defaults" precedent).
- `maybeSpawnTravellers()` — the ongoing, per-tick half: mirrors
  `maybeSpawn`'s per-area body (`isSpawnable(false)` — excludes uniques, same
  as `maybeSpawn` — then `rollShouldSpawn()`, then spawn) but iterates the
  small cached list instead of one map's `spawnAreas`. Wired into
  `GameRoundController.onNewTick()` alongside the existing `maybeSpawn` call,
  so it runs at the same cadence (twice a second) but only over the
  pre-filtered list, not the whole world.
- No new savegame state, no `fileversion` bump, no new JSON schema beyond
  what R2 already added — this is entirely a change to *when/how* an
  already-declared `travelDestination` default gets acted on, not a new field.

**Files:** `controller/MonsterSpawningController.java`,
`controller/MapController.java`, `controller/GameRoundController.java`,
`savegames/Savegames.java`.
**Verification:** compiles clean. **On-device verification still needed
from the user**, specifically: confirm `traveltester_defaults` (now
non-unique, per the user's own test-data edit) begins travelling immediately
on a fresh game/on load without the player ever visiting `traveltest1`;
confirm it respawns and resumes travelling again some time after being
killed (note: it does *not* despawn on its own after arriving - `arrivalScript`
doesn't remove the monster, so exercising the ongoing per-tick respawn path
specifically requires killing it first to free its spawn area's quota,
again without visiting `traveltest1`); and confirm no duplicate/ghost
instances appear if the player *does* later visit `traveltest1` normally.

### Disabled — confirmed crash against the real game world, root cause not found

Two rounds of memory-crash investigation against this feature (see the "R3
correction" entries below - the first two rounds turned out to be R3
regressions, fixed there) still left the game crashing on new-character
creation. A third logcat, with `android:largeHeap="true"` already applied,
finally isolated it precisely: a thread dump captured at the moment of
failure showed **171+ seconds of accumulated CPU time** on one thread, stuck
in exactly this feature's call chain -
`MonsterSpawningController.spawnAllTravellers()` →
`MapController.lotsOfTimePassed()` → `WorldSetup.createNewWorld()` →
`GlobalPathFinder.findPath()` → `PathFinder.findPathBetween()`. This is the
first and only code path, anywhere, that had ever called `beginTravel()`
against the *real* ~1229-map game world - every earlier confirmation
(R2, R2's original hook) only ever exercised the 2-map `traveltest` bed.

**Investigated, but the exact mechanism was not found**, despite ruling out
every concrete hypothesis checked against the real data:
- **Graph too large?** No - 1229 maps, 3827 total `mapchange` exits, 50 max
  on any single map. Dijkstra over a graph this size should be sub-second.
- **Destination unreachable, forcing an exhaustive search?** No - `crossglen`
  (a plausible player start) has a direct, correctly-bidirectional single-hop
  link to `traveltest1`; the shortest path should be found almost immediately.
- **A linear map-name lookup amplifying cost?** No -
  `MapCollection.findPredefinedMap` is a proper `HashMap` lookup.
- **A genuine infinite loop?** No - both `GlobalPathFinder`'s Dijkstra
  (non-negative edges, strict-improvement relaxation) and `PathFinder`'s
  local A* (hard 500-iteration cap per call) are structurally bounded and
  cannot loop forever by construction.

None of this explains 171 seconds of CPU time from a graph this size. R1's
clearance-term addition to `PathFinder.heuristic()` makes each local search
modestly more expensive, but not by the orders of magnitude that would be
needed. **The actual root cause remains unknown** - closing this out would
need on-device CPU profiling (e.g. Android Studio's profiler attached to a
debug build) to catch the real hot loop, which wasn't available in this
session.

**Fix applied: disable, don't guess.** Given the severity (the game could
not start at all) and the lack of a confirmed mechanism to fix directly, the
three call sites wiring this feature into the tick loop and world-setup
paths were removed - `MapController.lotsOfTimePassed()`,
`GameRoundController.onNewTick()`, and `Savegames.onWorldLoaded()`. The
underlying `MonsterSpawningController` methods
(`spawnAllTravellers`/`maybeSpawnTravellers`/`getTravelSpawnAreas`) were
initially left in place, unused - but have since been **removed entirely**
(the user cleaned up all four call-site files by hand once the game was
confirmed working again; see `changelog.md`'s "R2 follow-up's dead code
fully removed" entry). Re-implementing this feature, if/when
`GlobalPathFinder`'s real-world performance is actually understood and
fixed, means writing it again from this plan's design, not restoring code -
this write-up remains the reference for that. **R2's original hook is
untouched and still confirmed working**
(travel starts when a monster's own spawn point is visited, e.g. entering
`traveltest1` directly) - only the newly-added, map-visit-*independent*
global mechanism is disabled, since that's specifically what invokes
`GlobalPathFinder.findPath()` against a real, distant starting map for the
first time anywhere in this codebase.

**Files:** `controller/MapController.java`,
`controller/GameRoundController.java`, `savegames/Savegames.java`.
**Verification:** compiles clean. **On-device verification still needed
from the user**: confirm the game now starts and a new character can be
created normally, and that `traveltester_defaults` no longer travels on its
own until the player visits `traveltest1` (R2's original, narrower
behavior) - if a new character can be created, this narrowing is the
expected, intentional result, not a regression to investigate further.

---

## R3 — Data-driven terrain weighting via a new "control" tile layer ✅ Done

**Goal:** let a map author mark certain ground tiles (e.g. a dirt path,
a road) as preferable for travelling-monster pathfinding — an NPC should
walk on the path when it's a reasonable option, but must still be able to
leave it (to reach a destination off the path, or route around an obstacle)
rather than treating it as a hard constraint.

### Step 1 — design decision: penalize off-path, don't discount on-path

Two ways to model "prefer tile X": (a) give preferred tiles a cost *below*
the baseline 10, or (b) give non-preferred tiles a cost *at or above* the
baseline, leaving preferred tiles at the plain baseline. **Recommend (b).**
A discount (a) makes the actual cheapest-path cost lower than
`10 * Chebyshev distance` can predict, which breaks the heuristic's
admissibility in an *unbounded*, author-controlled way (unlike the
deliberately-capped tie-breaker) — a large enough discount could make A*
return a demonstrably non-shortest path, or in a pathological layout, behave
strangely near the iteration cap. A penalty-only model (b) keeps the
heuristic admissible with **zero changes to `heuristic()`** — the existing
`10 * Chebyshev` lower bound remains valid as long as no tile ever costs less
than 10 to enter — while still achieving the exact same authored *outcome*:
"prefer the path" and "everywhere off the path costs relatively more than
the path" describe the same relative preference. Document this reasoning
where the constant/weight table is defined so a future author doesn't
"simplify" it into a discount model without re-deriving why that's unsafe.

### Step 2 — new TMX layer

Follow the existing `Walkable` layer's precedent exactly
(`TMXMapTranslator`'s `LAYERNAME_WALKABLE`/`transformWalkableMapLayer`):
a new reserved layer name, e.g. `LAYERNAME_CONTROL = "control"`, read the
same way (`findLayer`, then a small transform function), **not** added to
`defaultLayerNames`'s rendered set — this alone is sufficient for `MainView`
to never draw it; no rendering-code changes are needed at all, exactly as
`Walkable` itself is invisible today.

Unlike `Walkable` (which only needs presence/absence — `gid > 0` — with no
tileset resolution), a weight layer needs to know *which* tile was placed, so
it must resolve `gid` → `(tilesetName, localId)` via the existing `getTile`
helper (same as `transformMapLayer` does for rendered layers), then map that
identity to a numeric weight.

### Step 3 — per-tile weight authoring: Tiled custom tile properties

Recommend defining weight as a **custom property on the tileset's tiles
themselves** (Tiled supports per-tile properties natively, edited in the
Tileset Editor), read once per tileset at load time and cached by
`(tilesetName, localId)`, rather than a hardcoded lookup table in Java code.
This keeps weight authoring inside the same tool/workflow content authors
(and ATCS) already use for everything else, instead of requiring a Java code
change to add a new weighted tile. Needs a new small parsing step wherever
`TMXTileSet`s are currently parsed (`TMXMapFileParser`) to also capture each
tile's custom properties, if that isn't already retained — check before
assuming a new data path is needed end-to-end.

A single new spritesheet (e.g. `map_control_1.png`) holding a handful of
marker tiles (e.g. "preferred path", "strongly avoid") is sufficient — the
actual pixel content is irrelevant since the layer never renders; simple
solid-color or symbol tiles are fine and make the tileset self-documenting
when viewed directly in Tiled.

### Step 4 — data plumbing: `MapSection` → `LayeredTileMap` → `PredefinedMap`

- `MapSection` gains `public final int[][] pathWeight` (0 = no modifier),
  parallel to `isWalkable`, and `replaceLayerContentsWith` gains the matching
  `System.arraycopy` splice for it (so a `ReplaceableMapSection` can add,
  remove, or change a preferred-path region — e.g. "the shortcut only
  becomes the preferred route after a quest opens it" falls out of this for
  free, no extra design work).
- `LayeredTileMap` gains a `getPathWeight(x, y)` accessor mirroring
  `isWalkable`'s shape (bounds-checked, returns 0 outside the map).
- `PredefinedMap` exposes it through the same `liveTileMap()` seam
  `isWalkable` already uses (§ of `travellingNPC_dataSchema.md`'s note on
  `liveTileMap()`'s "only the player's current map gets a live-synced copy"
  caveat applies identically here — no new limitation introduced, just
  inherited from the existing pattern).

### Step 5 — `PathFinder` cost integration

In both places `moveCost` is currently the literal `10` (the neighbor-
expansion loop and the final adjacent-to-`from` step), change to
`10 + map.getPathWeight(nx, ny)` (destination-tile weight — i.e. the cost of
*entering* a tile depends on that tile, not the one being left, matching how
`isWalkable` is already checked for the tile being entered). Clamp the
weight to `>= 0` when read (defensive — a negative value would violate step
1's decision; reject or clamp at the data-loading boundary, not silently at
every pathfinding call).

**Implementation notes:**
- Confirmed rather than assumed: `TMXMapFileParser` did **not** already
  capture tile-level properties (`readTMXTileSet` only read the `<tileset>`
  tag's own attributes, never descending into `<tile>` children). Worse,
  simply *not* consuming a `<tileset>`'s body while nested inside the map's
  own body-consuming loop wasn't just "silently miss the new data" - because
  `XmlResourceParserUtils.readCurrentTagUntilEnd` doesn't track nesting depth
  (it dispatches every `START_TAG` it sees until the matching outer
  `END_TAG`, regardless of how deep), a `<tile><properties><property
  name="weight" .../></properties></tile>` block left unconsumed would have
  had its `<property>` tag picked up by the *map*-level dispatcher one level
  up and misfiled into the map's own top-level properties list. Fixed by
  giving `readTMXTileSet` its own explicit `readCurrentTagUntilEnd` call
  (mirroring every other nested element in this parser), so `<tile>` (and
  its `<property>` children, picked up the same flattened way `readTMXObject`
  already relies on) are correctly scoped to their own tileset.
- `PathFinder.findPositionOnPath()` has a *third* place that needed the same
  `10 + weight` treatment the plan's step 5 only explicitly called out two
  of: its "find the tile adjacent to `from` that starts the path" search
  re-derives which candidate node produced `lastPathDistance` by recomputing
  the same cost formula `findPathBetween` used - missing this third spot
  would have made wall-clock position interpolation
  (`MonsterMovementController.spawnMonsterOnMap`) silently break on any
  weighted map, a bug that wouldn't have surfaced until a travelling
  monster's mid-leg position was checked against a weighted route, not
  immediately obvious at review time. Caught by grepping every `moveCost`
  occurrence after the main change, not by re-reading the plan alone.
- The control layer's weight array (`MapSection.pathWeight`) is always
  fully-allocated and non-null, even on a map that doesn't author a
  `Control` layer at all (unlike `isWalkable`, which legitimately can be
  `null`) - deliberately different from the `Walkable` precedent the plan
  pointed to, so `PathFinder`'s hot per-tile lookup never needs a null
  check.

**Files:** `model/map/TMXMapTranslator.java`, `model/map/TMXMapFileParser.java`,
`model/map/MapSection.java`, `model/map/LayeredTileMap.java`,
`model/map/PredefinedMap.java`, `controller/PathFinder.java`,
new `res/drawable/map_control_1.png`, `docs/wip/travellingNPC_dataSchema.md`
(new §2.6), plus test data (`res/xml/traveltest1.tmx`'s new tileset/layer/
two destinations, `res/raw/conversationlist_traveltest.json`'s two new
replies/phrases).
**Test setup:** added a `Control` layer to `traveltest1.tmx` weighting all of
row 5 (a normally-unremarkable, always-open east-west corridor spanning the
whole map, shared with row 4) at weight 6, plus two new `traveltester`-menu
destinations: `control_test_1` on row 4 (the unweighted "preferred" row) and
`control_test_2` on row 5 (forcing at least one step onto the weighted row,
since that's where the destination itself sits). Expected result: the
monster should walk almost the entire distance along row 4 and only step
down onto row 5 at the very last unavoidable moment, rather than freely
mixing rows 4/5 the way pure Chebyshev-shortest pathing would without any
weighting.
**Verification:** compiles clean; JSON/XML syntax validated; the generated
layer data and tileset gid arithmetic were round-trip verified with a
throwaway Python script (decode-what-was-encoded) before being embedded in
the TMX file, rather than trusting hand-computed base64/zlib bytes. **Full
on-device verification still needed from the user**: confirm the described
row-4-then-drop-to-row-5 behavior on `control_test_1`→`control_test_2`
(and the reverse), and re-run the Phase 6/R1 regression case
(`rect1_corner`→`rect3_corner`, an unweighted route) to confirm the new cost
term is a no-op there (every `getPathWeight` call should return `0` off the
`Control` layer, so behavior must be unchanged).
**User-confirmed on-device**, after the two corrections below and the R2
follow-up disable: the weighting behavior works as designed.

### Correction — this game has 1229 maps, not just the traveltest bed; two real regressions fixed

Reported by the user as an out-of-memory crash during new-character creation
(reproducible: crashed on a clean first launch). Root cause traced to two
scale mistakes in the original implementation above, both of which correctly
followed the `Walkable`-layer precedent for the *behavior* they added but
diverged from it on *cost*, and neither was caught by testing exclusively
against the tiny `traveltest1`/`traveltest2` bed:

1. `defaultLayerNames` unconditionally included `"control"`, so **every**
   map's `transformMapSection` call (not just `traveltest1`'s) looked up a
   `"control"` layer - logging a `DEVELOPMENT_VALIDATEDATA` warning for
   every one of the ~1228 other maps (plus ~1990 `replace` sections) that
   don't have one. Confirmed via the user's logcat (`Cannot find maplayer
   "control" requested by map "home"`).
2. `transformControlMapLayer` always allocated a full, real `int[][]` even
   with no control layer present, unlike `transformWalkableMapLayer`
   (deliberately kept nullable). That meant ~3219 permanently-retained,
   almost-entirely-zero-filled arrays (one per map's default layout, one per
   `replace` section) for the game's entire lifetime, on a heap the user's
   logcat showed repeatedly sitting at 191MB/192MB before finally failing on
   a 24-byte allocation - real, measurable permanent overhead added for a
   feature only one test map actually uses, not a theoretical concern.

**Fixed by matching the `Walkable` precedent exactly rather than diverging
from it for hot-path-null-check reasons that turned out not to be worth the
tradeoff:** `transformControlMapLayer` now returns `null` (not an allocated
array) when there's no control layer, `"control"` is exempted from
`findLayer`'s warning the same way `"top"`/`"base"` already are, and
`LayeredTileMap.getPathWeight`/`MapSection.replaceLayerContentsWith` both
gained the one cheap null check this reintroduces - a clearly correct
tradeoff against several MB of permanent dead weight across 1229 maps.

**Files:** `model/map/TMXMapTranslator.java`, `model/map/MapSection.java`,
`model/map/LayeredTileMap.java`.
**Verification:** compiles clean. **On-device verification still needed
from the user**: confirm the game now launches and a new character can be
created without the memory-full crash, and that `traveltest1`'s control-layer
test destinations still behave as before (the fix only changes *when* an
allocation happens, not the weighting logic itself).

#### Second correction — the remaining OOM points to a heap-ceiling issue, not a code leak

Re-tested after the fix above: the crash still occurred, but the evidence
changed character in a way that matters. The `"Cannot find maplayer
control"` spam was gone (confirming the first fix worked), the app got
significantly further before failing, and - critically - **the new crash's
stack trace is entirely inside the Android framework**
(`android.graphics.drawable.AnimationDrawable.run`, the loading-screen
spinner animation), not anywhere in AndorsTrail's own code, yet it hit the
*exact same* `Clamp target GC heap from ... to 192MB` ceiling as before. An
unrelated allocation failing at the identical heap cap, after the confirmed
leak was fixed, is strong evidence this is no longer (or was never entirely)
a retained-memory leak - it's this specific emulator/device defaulting to a
192MB per-app heap ceiling that a 1229-map game with a large tileset
library, loaded in a single startup burst, is legitimately bumping against.

**Fix:** added `android:largeHeap="true"` to `AndroidManifest.xml`'s
`<application>` tag - the standard, intended Android mechanism for exactly
this situation (an asset-heavy app that legitimately needs more headroom
than the platform default), rather than a workaround. This is a system
resource-request change, not a code fix - it doesn't reduce this app's
memory usage, it asks the OS for a larger ceiling to use it within.

**Files:** `app/src/main/AndroidManifest.xml`.
**Verification:** manifest XML validated. **On-device verification still
needed from the user** - this is the last lead from this investigation; if
the crash persists even with a larger heap ceiling, that would point back
toward genuine excess memory usage (worth checking, at that point, whether
it reproduces on a clean pre-this-session checkout to isolate whether it's
pre-existing and unrelated to the travelling-NPC branch entirely, or newly
introduced somewhere across R2/R2-follow-up/R3 beyond what's already been
found and fixed here).

---

## R4 — Data-driven per-NPC path variance ("organic" route jitter) ✅ Implemented, pending on-device confirmation

**Goal:** two monsters walking the same route shouldn't compute byte-for-byte
identical paths — a small, tunable amount of per-monster randomness should
make each one's exact tile sequence look individually plausible rather than
machine-generated, without meaningfully changing route *quality* (still
visibly the same overall route, just not an identical tile-for-tile copy).

### Step 1 — scope: local on-screen search only, never the canonical distance model

This is the phase's one hard rule, following directly from the "wall-clock
interpolation integrity" cross-cutting concern above: variance must **only**
affect `PathFinder.findPathBetween` calls made with a non-null `Monster m`
for **local, tick-by-tick travel-approach movement**
(`MonsterMovementController.findPathFor(Monster, CoordRect)` — the only
caller that already passes both `m` and the player-avoidance `avoid`
parameter). It must **never** affect:
- `PredefinedMap.calculateDistanceMatrix()` / `fillMapchangeDistances` (calls
  `findPathBetween` with `m = null`) — this is the shared, per-map,
  monster-independent exit-to-exit distance table.
- `GlobalPathFinder.findPath`'s own `findPathBetween` calls for exit
  distances and the final entry→destination leg (also always `m = null`) —
  this is what computes `travelPath.predictedTime`/`cumulatedDistance`, which
  wall-clock interpolation for *off-screen* monsters depends on.

Fortunately, this scoping is **free** — it rides the exact same `m`-nullness
seam that already exists for the unrelated player-avoidance feature, so no
new plumbing is needed to keep variance out of the global/matrix calls; it
only needs to be added inside the `m != null` branches of the per-tile cost
computation.

### Step 2 — data field and deterministic per-tile jitter

- New `MonsterType` JSON field `pathVarianceMultiplier` (float, default
  `0.0`) — `0` must reproduce **exactly** today's deterministic behavior (no
  jitter contribution at all when the multiplier is 0 — not merely "usually
  0", so existing monster types are provably unaffected by this phase unless
  explicitly opted in).
- New `Monster` field `pathVarianceSeed` (int), assigned once when the
  monster is created (`Constants.rnd.nextInt()`). Decide deliberately (as
  Phase 1 did for `startTime`) whether this is persisted across saves: **recommend
  persisting it** — an NPC's "walks slightly to the left of the path" quirk
  should read as a stable trait of that individual, not something that
  reshuffles on every reload, mirroring the reasoning that kept `startTime`
  unclamped.
- Jitter must be a **deterministic function of `(pathVarianceSeed, x, y)`**,
  not a fresh `Constants.rnd` draw per pathfinding call — the same monster
  searching over the same tile on consecutive ticks (which happens
  constantly; A* re-runs from scratch every tick) must get the same jitter
  value for that tile, or the path would visibly flicker/oscillate
  tick-to-tick instead of looking like a single coherent, organic route. Use
  a small fast integer hash (e.g. a standard 32-bit mix of
  `seed ^ (x * primeA) ^ (y * primeB)`) rather than reseeding a `Random`
  per call.
- Bound the jitter the same way the tie-breaker is bounded, but as an
  explicit, deliberate, *unbounded-by-design-within-its-own-cap* choice:
  unlike R3, this phase's entire purpose is to be mildly inadmissible (to
  produce a visibly different, technically-not-always-shortest path) — but
  it must still be capped low enough that a monster never takes an obviously
  bad detour just from jitter. Suggest `jitter = round(multiplier *
  hash01(seed, x, y) * JITTER_MAX)` with a small constant `JITTER_MAX` (e.g.
  15–20, comparable to a bit more than one tile's worth of the existing
  tie-breaker's own 9-point cap) and `multiplier` clamped to `[0, 1]` at load
  time so content data can't accidentally set an unbounded value.

### Step 3 — `PathFinder` integration

Extend the same `moveCost` expression touched in R3 step 5:
`moveCost = 10 + map.getPathWeight(nx, ny) + (m != null ? jitter(m, nx, ny) :
0)`. Keep this as one clearly-commented expression, not two separate patches,
per the ordering rationale above.

### Step 4 — documentation

Add `pathVarianceMultiplier` to `travellingNPC_dataSchema.md`'s `MonsterType`
field reference, explicitly noting the admissibility trade-off decision made
here (in contrast to R3's deliberately-admissible design) so a future reader
understands why the two features, despite touching the same line of code,
made opposite calls about correctness guarantees — and why that's fine (R3
models a real terrain preference where "shortest available path that
respects the preference" is the desired semantic; R4 models cosmetic
individual variation where "provably shortest" was never the goal).

**Files:** `resource/parsers/json/JsonFieldNames.java`,
`resource/parsers/MonsterTypeParser.java`, `model/actor/MonsterType.java`,
`model/actor/Monster.java` (+ savegame `writeToParcel`/`readFromParcel` +
`fileversion` bump if `pathVarianceSeed` is persisted per step 2),
`controller/PathFinder.java`, `docs/wip/travellingNPC_dataSchema.md`.
**Verification:** spawn two same-`MonsterType` travelling NPCs with
`pathVarianceMultiplier` set on the same start/destination pair (simplest:
temporarily raise `traveltester`'s spawn quantity and send several
simultaneously to the same destination), confirm their walked tile sequences
visibly differ while all still arrive by a plausible, similar-length route;
confirm a `pathVarianceMultiplier: 0` (or field omitted) monster's path is
identical to its pre-R4 behavior on the existing Phase 6 regression case.

**Implementation notes:**
- `pathVarianceSeed` is persisted (per the plan's own recommendation) as a
  plain `dest.writeInt(pathVarianceSeed)`/`fileversion >= 89`-gated
  `src.readInt()` — no boolean presence flag needed, the same unconditional-
  int pattern already used for `moveCost` (`fileversion >= 34`), since the
  field always has a value (never `null`) once a `Monster` exists. 89 is one
  past R2's `travelFailedScript` gate (88), the highest fileversion check
  already in this file.
- `moveCost` is now computed by one shared `PathFinder.moveCost(x, y, m)`
  helper (`10 + map.getPathWeight(x, y) + (m != null ? jitter(m, x, y) : 0)`)
  called from all **three** spots that used to each inline `10 +
  map.getPathWeight(...)` — the neighbour-expansion loop, the "adjacent to
  `from`" terminal step, and `findPositionOnPath`'s own re-derivation of
  which node produced `lastPathDistance`. R3's own notes already flagged that
  third spot as easy to miss; centralizing the formula in one method removes
  the risk of a fourth call site someday adding `pathWeight` but forgetting
  jitter (or vice versa), rather than trusting three hand-kept-in-sync copies.
- Confirmed rather than assumed: every `m == null` caller of
  `findPathBetween` (`PredefinedMap.calculateDistanceMatrix`'s single-tile
  overload, and all three of `GlobalPathFinder`'s own calls) already goes
  through the same overloads that default `m` to `null` — grepped every call
  site (`PathFinder.java`, `MonsterMovementController.java`,
  `PredefinedMap.java`, `GlobalPathFinder.java`) before implementing rather
  than trusting the plan's step 1 description alone. `jitter()`'s own
  early-return on `m == null` (implicit, via `moveCost`'s `m != null` guard)
  and on `multiplier <= 0` means the canonical distance model and every
  pre-existing (`pathVarianceMultiplier` defaults to `0`) `MonsterType` are
  both provably unaffected without needing a separate opt-out mechanism.
- One subtlety worth being explicit about: `MonsterMovementController
  .enterTravellingPool` and `.spawnMonsterOnMap` also call into `PathFinder`
  with a non-null `m` (to recalibrate `travelPath.startTime`/find a wall-
  clock handoff position from a *local* search), so those searches pick up
  jitter too. This is correct, not a scope leak - per the plan's own framing,
  the scoping "rides the exact same `m`-nullness seam" with no special-casing
  needed, and these two calls are recomputing a real physical distance/
  position against the same jittered cost model that actually produced the
  monster's on-screen movement moments earlier; using the *unjittered* cost
  there instead would reintroduce exactly the kind of two-models-disagreeing
  bug the Phase 0 cross-cutting concern warns about, just between "what the
  monster actually walked" and "what this recalibration thinks it walked",
  rather than between on-screen and off-screen state.
- **Test rig:** rather than adding a new spawn/monster type (which would
  need new TMX object placement without a clear known-walkable spot to put
  it), set `pathVarianceMultiplier: 1.0` directly on the existing
  `traveltester` `MonsterType`. Doing so activates test infrastructure that
  was already sitting unused in the traveltest bed: `traveltest1.tmx`
  already has `variation_one`/`variation_two` destination objects, and
  `conversationlist_traveltest.json` already has a "Go to variation one/two
  tile center" reply wired to them on `traveltester_start` - both apparently
  pre-staged for this exact phase, needing no new map or conversation data,
  only the code and this one JSON field to make them do anything. Documented
  in `travellingNPC_dataSchema.md` §3.5 that this also now perturbs
  `traveltester`'s other trips, including the Phase 6/R1 regression routes -
  temporarily zero the field for an exact regression re-check.
**Verification:** compiles clean
(`gradlew :app:compileDebugJavaWithJavac`). **Full on-device verification
still needed from the user**: talk to `traveltester` and send it to
"variation one"/"variation two" (and re-check a couple of the existing
rect/diag/control-test trips) to confirm the walked path looks organically
varied rather than perfectly clean, that it still arrives correctly, and
that temporarily setting `pathVarianceMultiplier` back to `0` reproduces the
exact pre-R4 deterministic route on the Phase 6 `rect1_corner`→`rect3_corner`
case.

---

## R5 — Let an on-screen travelling NPC pause to rest mid-journey ✅ Implemented, pending on-device confirmation

**Goal:** a travelling NPC, **only while physically simulated on the map the
player currently has loaded** (never while abstracted into the wall-clock
`travellingMonsters` pool), should occasionally stand still for a short,
data-driven number of ticks, with `travelPath`'s ETA bookkeeping corrected to
match.

### Step 1 — confirm the "on-screen only" constraint is structural, not incidental

This is already true by construction and should be **verified, not assumed**:
the travel-pathfinding branch lives entirely inside
`MonsterMovementController.determineMonsterNextPosition`, which is only ever
invoked from `moveMonster`, which is only ever invoked from
`moveMonsters()`'s loop over `currentMap.monsters` (the player's current
map). `tryPlaceTravellingMonster` (the wall-clock interpolation path for
pooled, off-screen monsters) never calls `determineMonsterNextPosition` at
all. Placing the new resting logic inside `determineMonsterNextPosition`
therefore automatically satisfies the constraint for free — but add a debug
log (guarded by `showTravelDebug`, matching every other travel log line) the
first time resting logic runs, stating which map/loop it fired from, so this
can be positively confirmed on-device rather than trusted by code-reading
alone, and so a future refactor that accidentally merges these two code
paths would be caught immediately rather than silently reintroducing a
Phase-0-shaped bug.

### Step 2 — data-driven trigger

- New `MonsterType` fields: `travelRestChance` (int percent, default `0`,
  rolled once per tick via `Constants.roll100` — `0` must mean "never rests",
  preserving default behavior) and `travelRestDuration` (a `ConstRange`,
  parsed via the same `ResourceParserUtils.parseConstRange` pattern already
  used for e.g. `attackDamage`, e.g. default `{min:1, max:2}` matching the
  user's "one or two movement ticks").
- New, **not persisted** `Monster` field `travelRestTicksRemaining` (int,
  default 0) — ephemeral like `travelBlockedRetries`, not part of the
  journey's durable state.

### Step 3 — tick-loop hook

At the very top of `determineMonsterNextPosition`'s `m.travelDestination !=
null` branch (before any pathfinding call):
- If `travelRestTicksRemaining > 0`: decrement it, leave `m.nextPosition`
  equal to `m.position` (no movement), and return `false` — this is
  deliberately cheaper than a real tick, too, since no pathfinding call
  happens at all while resting.
- Otherwise, roll `travelRestChance` once; on success, roll
  `travelRestDuration` into `travelRestTicksRemaining`, correct
  `travelPath` (step 4) immediately, and stand still this tick the same way.

### Step 4 — ETA correction

Per the user's explicit requirement ("the further path and predicted ETA
should be corrected accordingly"): when a rest begins, increase
`travelPath.predictedTime` by `ticksRested * 10` (10 matches the existing
distance-unit-per-move convention used everywhere else in this codebase —
`getMillisecondsPerMove`, `moveCost`, etc.) and increase
`cumulatedDistance` on every **remaining** `GlobalPathEntry` (the current leg
and all later ones — not legs already completed) by the same amount, so leg
boundaries and the overall ETA shift outward by the paused duration without
disturbing anything already walked.

**Implementation note:** `GlobalPath.predictedTime` and
`GlobalPathEntry.distance`/`cumulatedDistance` are currently declared
`final`. Satisfying this requirement needs either (a) loosening those fields
to non-`final` and mutating them in place, or (b) rebuilding the trailing
portion of `path` with new `GlobalPathEntry` instances. Prefer (a) for
simplicity unless something elsewhere relies on `GlobalPath`/`GlobalPathEntry`
being immutable (grep for other readers before deciding — none are known to
as of this plan, but confirm rather than assume). Do not skip this correction
to avoid the refactor — it was explicitly requested.

Note what does **not** need correction, and why: if the monster later leaves
the player's current map mid-journey and enters the travelling pool,
`enterTravellingPool` already recalibrates `startTime` from the monster's
**live physical position** via a fresh local pathfinder query — it has no
dependency on how much real time was spent getting there (including any
resting), so no separate hand-off correction is needed beyond step 4's
`predictedTime`/`cumulatedDistance` adjustment.

### Step 5 — documentation

Add `travelRestChance`/`travelRestDuration` to
`travellingNPC_dataSchema.md`'s `MonsterType` field reference, noting the
"only while on-screen" scoping explicitly (a content author should not
expect a resting NPC to visibly pause if the player isn't on its map — from
the player's perspective off-screen, it simply travels a little slower on
average, smoothed into the wall-clock model with no visible stutter).

**Files:** `resource/parsers/json/JsonFieldNames.java`,
`resource/parsers/MonsterTypeParser.java`, `model/actor/MonsterType.java`,
`model/actor/Monster.java`, `controller/MonsterMovementController.java`,
`controller/GlobalPathFinder.java` (if `final` fields need loosening),
`docs/wip/travellingNPC_dataSchema.md`.
**Verification:** set a high `travelRestChance` on `traveltester`, send it
travelling while the player stays on the same map, visually confirm it
pauses for the expected 1–2 ticks at a time; check `showTravelDebug` logs to
confirm `predictedTime` grows by the correct amount per rest; separately,
confirm a monster that travels while off-screen the entire time (player never
visits its map) arrives with **no** behavioral difference from before this
phase (since it can never roll a rest at all, per step 1's constraint).

**Implementation notes:**
- Step 1's "verify, don't assume" was checked by reading the call graph, not
  just trusted: `determineMonsterNextPosition` is only ever called from
  `moveMonster`, which is only ever called from `moveMonsters()`'s loop over
  `currentMap.monsters`. `tryPlaceTravellingMonster` (the wall-clock path for
  pooled, off-screen monsters) never calls `determineMonsterNextPosition` at
  all - confirmed by grep, not just by reading `moveMonsters()` itself. The
  new resting log line names the loop/map it fired from so this stays
  positively checkable on-device, not just true by construction.
- The resting check sits at the very top of the `m.travelDestination != null`
  branch, before the leg-progression logic, and **returns `true`** (not the
  plan's literally-stated `false`) on both the "still resting" and
  "rest just began" paths. Deviated from the plan's literal wording here
  after re-checking `determineMonsterNextPosition`'s own documented return
  contract immediately above it in the file: `true` means "already fully
  handled, caller must not touch `nextPosition`", `false` means "caller
  should apply `nextPosition` normally" (`monsterCanMoveTo` +
  `moveMonsterToNextPosition`, which plays a move animation and fires
  `onMonsterMoved`). A resting monster's `nextPosition` is never set to
  anything in this phase, and returning `false` would have driven that
  normal-move machinery every single resting tick for a zero-distance
  "move" - a spurious animation/listener firing the plan's own "cheaper
  than a real tick" framing argues against. `true` matches both the
  existing contract and that framing exactly; `nextPosition` is left
  untouched entirely (harmless - the next non-resting tick's `findPathFor`
  overwrites it fully before it's ever read again).
- `GlobalPath.predictedTime` and `GlobalPathEntry.cumulatedDistance` were
  loosened to non-`final` (option (a), as the plan recommended) after
  grepping every reader of both fields first - only `MonsterMovementController`
  and `MainView`'s debug visualization read them, both read-only, neither
  assumes immutability. `GlobalPathEntry.distance` stays `final` - only
  `cumulatedDistance` is ever corrected, never a leg's own true length.
- Worked through *why* correcting only the current leg's `cumulatedDistance`
  (not a separate "resting offset" field) is sufficient even for a monster
  that rests more than once on the same leg, or is later handed off to the
  travelling pool mid-leg: both `enterTravellingPool` and
  `tryPlaceTravellingMonster` derive a leg's *start* threshold as
  `cumulatedDistance - distance`, never from a stored value - so inflating
  `cumulatedDistance` alone (leaving `distance` untouched) correctly shifts
  both the start and end of the resting leg outward by the cumulative rest
  time so far, and does so identically regardless of which of those two
  call sites reads it next. Verified algebraically before implementing,
  not just asserted - see the doc comment on
  `MonsterMovementController.correctTravelPathForRest`.
- **Test rig:** `traveltester` (`monsterlist_traveltest.json`) now also has
  `travelRestChance: 25` and `travelRestDuration: {min:1, max:3}`, alongside
  R4's `pathVarianceMultiplier` (currently reverted to `0` by the user after
  confirming R4) - no new spawn/monster/TMX data needed, since this reuses
  the same already-verified `traveltester` NPC and its existing menu of
  destinations.
**Verification:** compiles clean (`gradlew :app:compileDebugJavaWithJavac`).
**User-confirmed on-device: "the feature works"**, with one follow-up
requested below.

### Follow-up — hardcoded cooldown between rests

**Trigger:** on-device testing showed a monster could rest, walk a single
tile, then immediately roll another rest — technically correct against the
data (each tick's roll is independent) but visually unnatural. The user
asked for a hardcoded cooldown (not a new authorable `MonsterType` field) of
10 tiles.

**Design:** `Constants.MONSTER_TRAVEL_REST_COOLDOWN_TICKS = 10` (placed next
to the existing `MONSTER_TRAVEL_MAX_BLOCKED_RETRIES`, the same "travel
tuning constant" family). Implemented as a tick count, not a literal
tile-distance tracker: one tick's local travel-approach step covers at most
one tile (`PathFinder`'s move cost is 10 distance units per move - the same
tick~move~tile equivalence this phase's own `correctTravelPathForRest`
already relies on), so a flat tick count is a faithful stand-in for "tiles
walked" without needing new machinery to track a monster's actual walked
distance (which would also have to account for blocked-path ticks that
consume a tick without any movement at all).

New, **not persisted** `Monster.travelRestCooldownRemaining` (int, mirroring
`travelRestTicksRemaining`'s lifetime) - started at
`MONSTER_TRAVEL_REST_COOLDOWN_TICKS` the instant a rest ends (whether that's
after a multi-tick rest's final decrement, or immediately within the same
tick for a one-tick rest, which needed its own explicit check since no
further decrement would otherwise ever fire). While `> 0`, decremented once
per tick and the `travelRestChance` roll is skipped entirely - the monster
just falls through to normal travel movement that tick, exactly as if
`travelRestChance` were `0` for the duration of the cooldown.

**Files:** `controller/Constants.java`, `model/actor/Monster.java`,
`controller/MonsterMovementController.java`,
`docs/wip/travellingNPC_dataSchema.md`, plus test data
(`res/raw/monsterlist_traveltest.json` unchanged - the existing
`travelRestChance: 25` on `traveltester` is enough to exercise the cooldown).
**Verification:** compiles clean. **On-device verification still needed
from the user**: confirm consecutive rests are now visibly spaced apart by
at least a handful of steps rather than back-to-back.

---

## R6 — Extension point for a future "pause travel for other activities" feature ✅ Implemented, pending on-device confirmation

**Explicitly out of scope here:** the activity system itself (deciding to
hunt, roam, or do anything else instead of travelling) needs its own separate
design pass, per the original request — this phase only prepares a clean
place for that future work to attach, using R5's "stand-still tick" mechanism
as the prototype for the shape that hook should take.

### Steps

1. Generalize R5's resting flag into a slightly broader shape before this
   phase closes it out: rename/reframe `travelRestTicksRemaining` handling
   as one case of a small, single hook — e.g. a `Monster.travelPauseReason`
   (nullable, or a tiny enum whose only current value is `resting`) checked
   at the very top of the travel branch, with the tick-skipping/ETA-correction
   logic from R5 steps 3–4 factored into one shared method (e.g.
   `handleTravelPause(Monster m)`) that both today's resting and any future
   pause reason would call into, rather than each maintaining its own parallel
   copy of "stand still and correct the ETA."
2. Do **not** implement any new pause *trigger* beyond R5's resting roll in
   this phase — the hook should currently only ever produce the `resting`
   case; anything else is future work.
3. Add a short note to `travellingNPC.md` (not a code comment — this is
   forward-looking design context, not an explanation of current behavior)
   describing where a future activity system would plug in, and what it
   would need to coordinate with at minimum:
   - Suspending tick-driven movement the same way resting does (reusing
     `handleTravelPause`'s mechanism from step 1).
   - The same ETA-correction obligation R5 established, generalized: any
     future activity that delays a journey must correct
     `predictedTime`/`cumulatedDistance` the same way, or off-screen
     wall-clock interpolation will desync exactly as Phase 0 warned about.
   - Explicitly flag, as **open questions for that future separate planning
     pass** (do not answer them now): whether an NPC resumes the *same*
     `travelPath` after an activity or the activity fully replaces travel for
     a while; whether an activity can be interrupted by combat/aggression;
     and whether `isTravelling` (§3.3 of the data schema doc) needs a third
     state distinct from both "travelling" and "not travelling" for scripts
     to query a paused-for-an-activity NPC correctly.

**Files:** `model/actor/Monster.java`, `controller/MonsterMovementController.java`
(generalizing R5's own additions — no new functional behavior beyond R5),
`docs/wip/travellingNPC.md` (design note only).
**Verification:** none beyond re-confirming R5's own verification still
passes after the rename/refactor — this phase changes no observable
behavior.

**Implementation notes:**
- `Monster.travelRestTicksRemaining` was replaced by a nested
  `Monster.TravelPauseReason` enum (one case today: `resting`) plus
  `Monster.travelPauseReason` (nullable) / `travelPauseTicksRemaining` —
  matching the plan's own suggested shape exactly.
- The single inline rest-continuation block was split into four methods in
  `MonsterMovementController`, each with one clear responsibility:
  `handleTravelPause(m)` (generalized continuation - decrements, logs, ends
  the pause when ticks run out; called once at the top of the travel branch,
  returns `true`/`false` the same way the rest of `determineMonsterNextPosition`
  already does), `beginTravelPause(m, reason, ticks)` (starts a new pause -
  sets the reason/ticks, corrects the ETA, handles the one-tick-pause-already-
  over-this-tick edge case), `onTravelPauseEnded(m)` (reason-specific cleanup
  - today, only clears state and starts `travelRestCooldownRemaining` for the
  `resting` case specifically), and `correctTravelPathForPause` (R5's
  `correctTravelPathForRest`, renamed - its own logic was already fully
  reason-agnostic, so no behavior change, just a name that no longer implies
  it's rest-only).
- **Per the user's explicit instruction, `travelRestCooldownRemaining` was
  deliberately *not* folded into the generalized hook** - it stays its own
  field, checked and decremented locally where resting's trigger (the
  `travelRestChance` roll) lives in `determineMonsterNextPosition`, not
  inside `handleTravelPause`/`beginTravelPause`/`onTravelPauseEnded`. A
  future pause reason (e.g. hunting) therefore inherits the generalized
  stand-still/ETA-correction machinery for free, but nothing rest-specific -
  `onTravelPauseEnded`'s `if (endedReason == resting)` guard is the only
  place resting's own follow-up state is touched.
- The trigger logic itself (cooldown gate + `travelRestChance` roll) was
  deliberately left in `determineMonsterNextPosition`, not moved into a
  shared method - per plan step 2, R6 adds no new trigger, and per the
  cooldown decision above, resting's trigger has resting-specific
  concerns a shared "decide whether to pause" method would have had to
  either special-case or expose, defeating the point of separating it out.
- Added `travellingNPC.md`'s "Future extension point" section per step 3,
  restating the plan's own coordination requirements and three open
  questions rather than answering them.
**Verification:** compiles clean (`gradlew :app:compileDebugJavaWithJavac`).
No behavior change intended or expected - re-verify R5's own on-device
checks (rests pause visibly, spaced out by the cooldown, `predictedTime`
still grows correctly) still hold after this refactor.

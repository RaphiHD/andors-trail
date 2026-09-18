# Travelling NPCs — Changelog Summary

A condensed, bullet-point index of [changelog.md](changelog.md), in the same
order. Each change gets a few bullets: what broke (or was added), the fix,
and anything still open. Read this for a fast overview; read the full
changelog for root-cause detail, code snippets, and verification steps.

## Debug tooling added first
- Added `showTravelDebug` (`trv` button) with `TRAVEL:`-prefixed logcat lines at every travel decision point.
- Files: `MonsterMovementController.java`, `DebugInterface.java`, `TravelDestinationArea.java`.

## 1. Monster kept "disappearing and reappearing" on the mapchange tile
- `determineMonsterNextPosition`'s early `return` didn't stop the caller from also re-animating an already-removed monster.
- Fixed by making it return `boolean`: `true` = already fully handled, don't touch `nextPosition`.

## 2. Monster "appears then just sits there"
- `findPositionOnPath` treated "already at destination" the same as "unreachable" (both `false`).
- Fixed with an explicit `from.intersects(to)` check up front.

## 3. Same ambiguity inside `GlobalPathFinder`
- All three of its `findPathBetween` call sites had the identical blind spot (gating on the boolean instead of `getLastPathDistance()`).
- Fixed all three to read `getLastPathDistance()` directly.

## 4. Travel timing endlessly resetting
- `GlobalPath.startingPosition` aliased the monster's live `Coord` instead of freezing it, so "distance walked" always read 0.
- Fixed with a defensive copy in `GlobalPath`'s constructor.

## 5. Unified travel-pool hand-off (`enterTravellingPool`)
- Two different code paths handed a monster into the wall-clock pool inconsistently.
- Unified into one method that recalibrates `startTime` from the monster's actual live position.

## 6. Tick rate silently capping monster move speed
- A monster's nominal move speed could be faster than the 500ms tick loop could ever deliver, breaking ETA predictions (up to 74% off).
- `getMillisecondsPerMove` now clamps to `Math.max(nominal, Constants.TICK_DELAY)`.

## 7. Same-map "warp" mapchanges not used by travelling monsters
- Zero-cost same-map teleport pairs were planned for by the router but never actually executed by a walking monster.
- Travel-approach logic now detects a same-map warp and relocates the monster instantly instead of routing through the travelling pool.

## 8. Warp not used in the *other* travel direction
- `GlobalPathFinder`'s leg-collapsing backtrack couldn't tell "connected by a walk" from "connected by a warp," and silently swallowed the warp leg in one direction.
- Added a `viaCrossing` tracking set so warp destinations always keep their own leg.

## 9. Same-map destination behind a barrier reported as unreachable
- The same-map shortcut gave up immediately on failure instead of falling through to the full graph search.
- Now falls through to the full search (which already handles this case correctly).

## 10. NPC appears a beat late after a map transition
- Travelling-monster placement only ran on `moveMonsters()`'s own tick timer, not synced to the player's actual map transition.
- Added `syncTravellingMonstersOntoCurrentMap()`, called right after a map becomes current.

## 11. Monster stranded when a new journey starts while off-screen
- `beginTravel` never checked whether the monster was on a map other than the player's current one.
- Now hands the monster straight to the travelling pool if so.

## Phase 1 — Persist travel state across saves
- `GlobalPath`/`Monster.travelDestination`/`travelPath` are now (de)serialized (`fileversion >= 87`); world-level `travellingMonsters` pool too.
- `startTime` deliberately persisted as a raw, unclamped timestamp — elapsed real time (even across app restarts) is treated as ground truth, consistent with the rest of the model.
- User-confirmed on-device across both a soft reload and a full app close/reopen.

## Phase 2 — Keep the exit-to-exit distance matrix in sync with layout changes
- `MapController.applyReplacements` now recomputes `PredefinedMap.calculateDistanceMatrix()` whenever a replacement actually changes walkability.
- The cheaper patch-only alternative and in-flight-monster handling were deliberately deferred (no measured need / scoped to Phase 3).

## Phase 3 (steps 1–2) — Fail visibly instead of getting stuck forever
- Unreachable destination: `beginTravel` now clears travel state and logs a warning instead of leaving the monster wedged.
- Blocked mid-walk: new `Monster.travelBlockedRetries` + `handleBlockedTravelPath` retries with backoff, gives up after `MONSTER_TRAVEL_MAX_BLOCKED_RETRIES` (10).
- `keyarea` handling and a "travel failed" script hook were deliberately left for later steps.

## Phase 3 follow-up — the player was invisible to travel pathfinding
- The player (not a `Monster`) was never excluded from the pathfinding graph, so a monster blocked by the player just sat frozen instead of retrying/giving up.
- `PathFinder.findPathBetween` gained an `avoid` tile parameter; travel-approach searches now always avoid the player's position.

## Phase 2/3 correction — monster pathfinding read a permanently stale tileMap
- `PredefinedMap.isWalkable` read a `final` tileMap snapshot from map load, never updated by replacements — made Phase 2's fix correctness-inert and reintroduced Phase 3's stuck-forever bug for the *current* map.
- Added `liveTileMap()`, which prefers the player's live current-map tileMap when applicable.
- Known accepted limitation: a non-current map still can't see its own replacement state change (pre-existing scope, not new).

## Phase 4 — Make arrival scripts reliable regardless of map visit history
- Arrival scripts silently no-op'd if the player had never visited the arriving monster's map.
- `onMonsterArrived` now builds a fresh `ConversationStatemachine` on demand via `MapController.runArrivalScript`, independent of visit history.

## Phase 3 (step 3) — let monsters pass through designated key areas
- Every `keyarea` was unconditionally impassable to monsters, with no opt-out.
- Added a static, map-author-set `monstersCanPass` flag on `keyarea` objects (default `false`, preserving existing behavior everywhere else).

## Phase 3 (step 4) — let an NPC react to its own failed journeys
- No way for a monster to react to its own travel failure (unreachable, or gave up after blocked retries).
- Added `Monster.travelFailedScript` (persisted, not cleared after firing) + `setTravelFailedScript` reward, deliberately modeled as a property of the traveler, not the destination.
- Known, accepted risk: a failure script whose own fallback destination is itself unreachable can recurse — treated as a script-authoring responsibility, not guarded in code.

## Phase 5 — discarded, not implemented
- Multi-step itineraries were assessed and explicitly **not built** — already achievable via `arrivalScript` + `setDestination` chaining. Removed a leftover, never-wired `executeNextStep()` stub instead.

## Phase 6 — algorithmic completeness in `GlobalPathFinder`
- Zero-cost map transitions finalized as permanent, deliberate design (no code change).
- Fixed a stale octile-distance heuristic (diagonal cost 14) that had become *inadmissible* once move costs were unified to 10 — could have silently steered global routing wrong. Changed to plain Chebyshev distance.

## Phase 6 follow-up — natural-looking diagonal/straight interleaving
- Removing the old (accidentally-biased) heuristic left true ties (many equally-costed tile sequences) resolved arbitrarily, producing zig-zag / clustered-not-interleaved paths.
- Added a bounded `lineTiebreak` term nudging the search toward the straight start-goal line, well under one move's cost.

## Phase 6 follow-up, correction — the tie-breaker above was a complete no-op
- The tie-breaker's divisor was sized for the *largest possible* map — for any smaller/typical trip it rounded to `0` every time, doing nothing.
- Fixed by normalizing against the search's own start-goal distance instead, capped at 9. User-confirmed on-device: shortest path preserved, interleaving now works.
- One cosmetic gap explicitly accepted, not chased: path still turns abruptly right at the last possible tile before a detour (logged in `PLAN.md`'s pre-release polish list — later fixed by R1).

## Phase 8 — cross-map debug visualization for `GlobalPathFinder`
- No on-screen visual existed for a monster's cross-map route (only single-map local A* debug).
- Added `MainView.drawTravelDebug` (cyan/orange leg outlines across every map), reusing the existing `trv` toggle. Also fixed unconditional log lines that should've been gated on `showTravelDebug`.

## Documentation updates (no code change)
- Multiple `PLAN.md`/`travellingNPC.md` passes: marked finished phases/gaps done, corrected stale "done" claims (Phase 2, Phase 6 item 1), recorded discarded Phase 5, added a new standalone `travellingNPC_dataSchema.md` schema reference, added a pre-release regression checklist (Tunlon/`bwmfill`) and a "known cosmetic polish items" list.

## Refinement planning
- With the core feature confirmed working end-to-end, added `PLAN_refinement.md`: six further phases (R1–R6), sequenced by shared risk/dependency rather than request order. No code change.

## R1 — Fix the abrupt pre-detour turning
- The reported "walks straight at a wall, turns sharply" behavior needed a different fix than a turn-angle penalty (which pushes the wrong direction) — a genuine gap in the existing tie-breaker, which can't distinguish "detour starts here" from "one tile closer."
- Added a second, independently-capped `clearanceTiebreak` term (terrain-only, ignores actors) alongside the existing line tie-breaker.

## R1 follow-up, correction — the first clearance term was direction-dependent
- The first version worked on 2 of 4 test corners and not the other 2 — traced to each corner's differently-oriented walls interacting with a too-weak, too-easily-crowded-out signal.
- Widened to a 2-tile falloff radius and gave the two tie-break terms independent caps. Verified via algorithm replay against real map data for all cases, zero path-length regression.
- **User-confirmed on-device: "the path already looks very natural."**

## R2 — Per-`MonsterType` default `travelFailedScript` / `travelDestination`
- Both fields, previously only settable via live script rewards, can now be declared once per `MonsterType` as a standing default applied at spawn.
- `travelFailedScript` applied in `Monster`'s constructor (not `resetStatsToBaseTraits`, which runs too often); `travelDestination` triggered from `MonsterSpawningController.spawnInArea`.
- Savegame-compatible with no `fileversion` bump — an old save's monster now retroactively picks up a newly-added default.
- **User-confirmed on-device.**

## R2 follow-up — global, map-visit-independent spawning for always-travelling monster types
- User asked for always-travelling monsters to start immediately on world load, not just once their home map is visited.
- Implemented via a cached list of eligible spawn areas + one-time catch-up (`spawnAllTravellers`) + a pre-filtered per-tick check (`maybeSpawnTravellers`), avoiding an O(all maps) per-tick cost.
- **Later found to crash the real game world — see below; ultimately removed entirely, not just disabled.**

## R3 — Data-driven terrain weighting via a new "control" tile layer
- Added a non-rendered `Control` TMX layer letting map authors weight terrain so travelling monsters prefer (not require) certain ground — penalty-only cost model, chosen specifically to keep the pathfinding heuristic provably admissible.
- Fixed a previously-unknown parser gap along the way: tile-level custom properties were never captured at all, and would have been misfiled into the map's own properties if left unconsumed.
- Found and fixed a third `moveCost` call site (`findPositionOnPath`) the plan itself didn't call out.

## R3 correction — out-of-memory crash during new-character creation
- R3 was developed/tested only against the 2-map test bed; against the real 1229-map game it warned on every map without a `Control` layer and permanently allocated ~3219 near-empty arrays.
- Fixed by matching the existing `Walkable`-layer precedent exactly: return `null` when absent, exempt `"control"` from the missing-layer warning.

## R3 correction, part 2 — the remaining crash is a heap-ceiling issue, not a code leak
- After the fix above, still crashed — but now failing inside Android's own framework code at the same 192MB heap ceiling, pointing at a genuine platform ceiling rather than a leak.
- Added `android:largeHeap="true"` — later found to be unnecessary and removed (see below).

## R2 follow-up disabled — crashes the real game world, root cause not found
- **🛑 Originally marked CRITICAL.** A thread dump showed R2 follow-up's `GlobalPathFinder.findPath()` burning 170+ seconds of CPU against the real world map graph — root mechanism never identified despite ruling out graph size, unreachability, linear lookups, and infinite loops.
- Fixed by disabling (not guessing): removed the three call sites wiring it into the tick loop/world-setup. R2's original, narrower per-spawn-area hook is untouched and confirmed still working.
- **Superseded:** the underlying code was later removed entirely (see "R2 follow-up's dead code fully removed" below) rather than left disabled — this line item is effectively closed by permanent removal, not by a fix.

## `pth` debug overlay now also visualizes control-layer weights
- Requested specifically to help debug R3: the `pth` overlay had no visibility into *why* a route avoided/crossed a weighted tile.
- `MainView.drawPathfinderDebug` now also tints weighted tiles and labels their weight value, tied to the same `pth` toggle.

## R2/R3 confirmed working; `android:largeHeap` removed as unnecessary
- **User-confirmed on-device** for both R2 and R3.
- Reconstructed the crash timeline: R2 follow-up's runaway computation was present in *every* crash report, including before `largeHeap` was ever added — `largeHeap` only delayed the failure, never fixed it. Removed as unnecessary now that R2 follow-up is disabled.

## R2 follow-up's dead code fully removed from the codebase
- User manually reverted the three disabled call sites back to clean pre-R2-follow-up state.
- Confirmed and removed the now-fully-dead `TravelSpawn`/`travelSpawnAreas`/`getTravelSpawnAreas()`/`spawnAllTravellers()`/`maybeSpawnTravellers()` from `MonsterSpawningController`.
- R2's original hook (inside private `spawnInArea`) explicitly kept — still required, still working.

## R4 — Data-driven per-NPC path variance ("organic" route jitter)
- Added `MonsterType.pathVarianceMultiplier` + `Monster.pathVarianceSeed`, feeding a small deterministic per-tile cost hash so same-type monsters don't walk byte-identical routes.
- Rides the existing `Monster m`-nullness seam for free — structurally guaranteed to never affect the shared distance matrix or off-screen wall-clock model.
- Centralized `moveCost` into one shared helper used by all three call sites that need it (was two, then three separately-inlined copies).
- Test rig: set on `traveltester` directly, activating pre-existing but previously-unused `variation_one`/`variation_two` test destinations.
- **User-confirmed on-device** (later reverted to `0` on `traveltester` by the user to keep a clean regression baseline).

## R5 — Let an on-screen travelling NPC pause to rest mid-journey
- Added `MonsterType.travelRestChance`/`travelRestDuration` + a per-tick roll, standing the monster still while on-screen only (structurally guaranteed — `tryPlaceTravellingMonster` never reaches this code path).
- Mandatory ETA correction (`correctTravelPathForRest`) grows `predictedTime` and remaining legs' `cumulatedDistance` by the rested duration, keeping the off-screen wall-clock model in sync.
- Deliberately deviated from the plan's literal "return `false`" — returns `true` instead, matching the method's own documented contract and avoiding a spurious zero-distance move animation every resting tick.
- **User-confirmed on-device: "it works."**

## R5 follow-up — hardcoded cooldown between rests
- User-reported: a monster could rest, walk one tile, and immediately rest again — visually unnatural.
- Added `Constants.MONSTER_TRAVEL_REST_COOLDOWN_TICKS = 10` (hardcoded, not authorable) + `Monster.travelRestCooldownRemaining`, gating the next `travelRestChance` roll.
- **User-confirmed on-device.**

## R6 — Extension point for a future "pause travel for other activities" feature
- Pure refactor, no new trigger, no observable behavior change. Generalized R5's resting into `Monster.travelPauseReason`/`travelPauseTicksRemaining` + shared `handleTravelPause`/`beginTravelPause`/`onTravelPauseEnded`/`correctTravelPathForPause` methods a future activity system (hunting, roaming, etc.) can reuse.
- Per explicit user instruction, `travelRestCooldownRemaining` was deliberately **not** folded into the shared hook — stays rest-specific so it can't constrain an unrelated future pause reason.
- Added a "Future extension point" design note to `travellingNPC.md` (three explicitly open questions, not answered).
- **User-confirmed on-device: no regression after the refactor.**

## Not yet addressed
- Phase 7 (automated tests) — deferred until there's a JUnit setup for the whole project.
- Phase 8's remaining stretch items (autonomous travel triggers, a dev-menu teleport shortcut) — not asked about, untouched.
- The R2-follow-up crash's `GlobalPathFinder` root cause was never actually found — moot for now since the feature was permanently removed rather than fixed, but worth knowing if a similar always-on/global search is ever attempted again against the real ~1229-map world.

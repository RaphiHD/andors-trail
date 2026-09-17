# Monsters

## Overview

A "monster" in Andor's Trail is any non-player actor placed on a map — hostile
creatures, but also stationary shopkeepers/quest NPCs that happen to be
modeled as `Monster` instances. The system has three moving parts: a
data-driven template (`MonsterType`, one per monster species/definition,
loaded from game data), a per-instance runtime object (`Monster`, one per
actual creature standing on a map, holding its own HP/AP/position/AI state),
and per-map placement rules (`MonsterSpawnArea`, a rectangular zone that
creates and caps a population of monsters over time). Two controllers drive
the runtime behavior every game tick (see
[game-loop.md](./game-loop.md)): `MonsterSpawningController` decides whether
new monsters appear, and `MonsterMovementController` decides how existing
monsters wander, chase, flee, and step onto the player to start combat.

## Key classes/files

| File | Role |
|---|---|
| [MonsterType.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/actor/MonsterType.java) | Immutable template: base stats (`maxAP`, `maxHP`, `moveCost`, `attackCost`, `attackChance`, `criticalSkill`, `criticalMultiplier`, `damagePotential`, `blockChance`, `damageResistance`), `AggressionType`, `MonsterClass`, `dropList`, `exp`, `phraseID`, `faction`, `isUnique`, `spawnGroup`, on-hit/on-death item effects. One instance per monster definition in game data. |
| [MonsterTypeCollection.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/actor/MonsterTypeCollection.java) | Holds all `MonsterType`s by id (`getMonsterType`), and resolves a `spawnGroup` name to the list of `MonsterType`s that belong to it (`getMonsterTypesFromSpawnGroup`) or falls back to treating the group string as a direct type id. |
| [Monster.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/actor/Monster.java) | Extends `Actor`. One instance per creature on a map: current position (`position`/`nextPosition`), the `MonsterType` it was built from, its `MapArea area` (usually the `MonsterSpawnArea` it spawned in), AI/movement state (`movementDestination`, `nextActionTime`, `travelDestination`, `travelPath`), and combat-trait fields copied from `MonsterType` at construction (`resetStatsToBaseTraits`). |
| [MonsterCollection.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/actor/MonsterCollection.java) | World-level (not per-map) list of `travellingMonsters` — monsters currently in transit between maps (see "Travelling monsters" below). |
| [MonsterSpawnArea.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/map/MonsterSpawnArea.java) | A `MapArea` subtype: a rectangle on one map plus spawn rules — `quantity` (current/max population as a `Range`), `respawnspeed` (a `Range` rolled for spawn timing), `monsterTypeIDs`, `isUnique`, `ignoreAreas`, `isSpawning`. Owns `spawn(...)`, `isSpawnable(...)`, `rollShouldSpawn()`. |
| [MonsterSpawningController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/MonsterSpawningController.java) | Orchestrates spawning across a map's `spawnAreas`: `maybeSpawn` (per-tick chance-based spawn), `spawnAll`/`spawnAllInArea` (bulk-fill on map load/activation), `activateSpawnArea`/`deactivateSpawnArea`, `remove`. |
| [MonsterMovementController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/MonsterMovementController.java) | Per-tick monster AI: `moveMonsters()` (wander/chase/flee/travel), `attackWithAgressiveMonsters()` (aggro monsters stepping onto the player), plus the shared tile-occupancy check `monsterCanMoveTo` and inter-map travel (`beginTravel`, `spawnMonsterOnMap`). |
| [MonsterMovementListener.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/MonsterMovementListener.java) / [MonsterMovementListeners.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/MonsterMovementListeners.java) | Observer interface (`onMonsterSteppedOnPlayer`, `onMonsterMoved`) and its `ListOfListeners` broadcaster, owned by `MonsterMovementController.monsterMovementListeners`. |
| [MonsterSpawnListener.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/MonsterSpawnListener.java) / [MonsterSpawnListeners.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/MonsterSpawnListeners.java) | Observer interface (`onMonsterSpawned`, `onMonsterRemoved`, plus blood-splatter events `onSplatterAdded`/`onSplatterChanged`/`onSplatterRemoved`) and its broadcaster, owned by `MonsterSpawningController.monsterSpawnListeners`. |
| [MapArea.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/map/MapArea.java) | Base class `MonsterSpawnArea` extends: `area` (a `CoordRect`), `areaID`, `mapID`. |

## How it works

### MonsterType vs. Monster

`MonsterType` is the read-only, shared definition (one per species, e.g.
"giant rat") — it never changes at runtime. `Monster` is a live instance:
its constructor (`Monster(MonsterType monsterType, MapArea area)`) copies
combat traits from the type via `resetStatsToBaseTraits()` (name, AP/HP max,
`moveCost`, `attackCost`, `attackChance`, `criticalSkill`,
`criticalMultiplier`, `damagePotential`, `blockChance`, `damageResistance`,
on-hit/on-death effects) and then fills current AP/HP to max. From then on a
`Monster`'s stats can diverge from its `MonsterType` (conditions, equipment
effects for special monsters, savegame data), which is why `Monster` stores
its own copies rather than reading through to `monsterType` every time —
`writeToParcel`/`addToChecksum` even special-case writing full combat data
only "if it differs from monsterType's" to save space. `Monster.isFlippedX`
is rolled once per instance from `monsterType.horizontalFlipChance`, so two
monsters of the same type can render mirrored independently.

`MonsterType.AggressionType` (`none`, `helpOthers`, `protectSpawn`,
`wholeMap`, `flee`) governs movement AI (below); combat AI for the
`helpOthers`/`protectSpawn`/`wholeMap` distinction during actual turn-based
fights is evaluated separately in `CombatController` — see
[combat.md](./combat.md).

### Spawning

Each `PredefinedMap` has a list of `MonsterSpawnArea`s (`map.spawnAreas`).
Each area independently tracks `quantity` (a `Range` — `current` alive vs.
`max` allowed) and a `respawnspeed` `Range` used to roll spawn timing.

- **`MonsterSpawningController.maybeSpawn(map, tileMap)`** — called every
  game tick from `GameRoundController` (see
  [game-loop.md](./game-loop.md)). For every spawn area: skip if
  `!area.isSpawnable(false)` (not currently `isSpawning`, unique-and-already-
  spawned, or already at `quantity.max`), skip if `!area.rollShouldSpawn()`
  (`Constants.rollResult(respawnspeed)` — a randomized "did enough time pass"
  check, not a fixed timer), otherwise attempt one spawn.
- **`spawnAllInArea(map, tileMap, area, respawnUniqueMonsters)`** — loops
  `spawnInArea` until `area.isSpawnable(respawnUniqueMonsters)` is false or a
  spawn attempt fails, i.e. fills the area up to `quantity.max` immediately,
  then heals every monster already in that area
  (`actorStatsController.healAllMonsters`). Used for `spawnAll(map, tileMap)`
  on map load/entry (`MapController`, `MovementController` after a map
  change) — with `respawnUniqueMonsters = !map.visited`, so unique monsters
  only bulk-spawn the first time a map is visited — and by
  `activateSpawnArea(..., spawnAllMonsters=true)`, used when a conversation
  or map trigger turns a spawn area on (`ConversationController`).
- **Picking a monster type**: `MonsterSpawnArea.getRandomMonsterType`
  picks uniformly at random from `monsterTypeIDs` (the area's configured
  list of possible species).
- **Picking a position**: `MonsterSpawningController.getRandomFreePosition`
  tries up to 100 random points inside the area's `CoordRect`, accepting the
  first one that passes `MonsterMovementController.monsterCanMoveTo` (tile
  walkable, no other monster already there, and — unless `ignoreAreas` is
  set — not on top of a `newmap`/`keyarea`/`rest` map event object) and isn't
  the player's tile. Returns `null` (spawn silently skipped for that
  attempt) if no free spot is found in 100 tries.
- **Actually spawning**: `MonsterSpawnArea.spawn(Coord p, MonsterType type)`
  constructs a `new Monster(type, this)`, sets its position, adds it to
  `map.monsters`, and increments `quantity.current`. `Monster`'s constructor
  copies `ignoreAreas` from the `MonsterSpawnArea` onto the monster instance
  itself (`this.ignoreAreas = ((MonsterSpawnArea) area).ignoreAreas`), so a
  monster keeps that "can stand on event objects" flag even later during
  movement.
- **Freeing capacity**: `PredefinedMap.removeMonster(m)` always calls
  `m.clearMobCap()` first, which decrements `((MonsterSpawnArea)
  area).quantity.current` if the monster's `area` is a `MonsterSpawnArea` —
  this runs on every removal path (player kill via
  `MonsterSpawningController.remove`, `deactivateSpawnArea`,
  `removeAllMonsters`, a travelling monster being pulled off the map), so
  killing/removing a monster always frees a slot for `maybeSpawn` to refill
  later.
- **Enabling/disabling an area**: `activateSpawnArea`/`deactivateSpawnArea`
  flip `area.isSpawning` and optionally bulk-spawn or remove every monster
  currently attributed to that area (`m.area == spawnArea`). Used by
  scripted map/conversation events (see `ConversationController.java`).
- **`isUnique`**: a unique spawn area's monsters are not respawned once
  cleared in normal play (`isSpawnable` returns `false` for a unique area
  unless the caller explicitly passes `includeUniqueMonsters = true`, as
  `spawnAll`/`PredefinedMap`'s savegame-load path does for a freshly-visited
  or newly-added unique area).

### Movement AI

`MonsterMovementController.moveMonsters()` runs every tick and, for every
monster on the current map whose `nextActionTime` has elapsed
(`m.nextActionTime <= currentTime`), calls `moveMonster(m, area, ignoreAreas)`
where `area` is the monster's home `m.area.area` (or the whole map if
`m.area == null`).

- **Pacing**: each move (or wait) reschedules `nextActionTime` by
  `getMillisecondsPerMove(m) = Constants.MONSTER_MOVEMENT_TURN_DURATION_MS *
  m.getMoveCost() / m.getMaxAP()` (`MONSTER_MOVEMENT_TURN_DURATION_MS =
  1200`), so monsters with a higher `moveCost` relative to their max AP act
  less often. A monster with `moveCost == Constants.MONSTER_IMMOBILE_MOVE_COST`
  (`999`) never moves at all: `moveMonster` returns immediately, and the
  constructor already set `nextActionTime = Long.MAX_VALUE` for such
  monsters so `moveMonsters()` never even attempts to act on them — this is
  how stationary NPCs/shopkeepers modeled as `Monster` stay put.
- **Reaching a destination**: if the monster already has a
  `movementDestination` and has arrived (`m.position.equals(m.movementDestination)`),
  `cancelCurrentMonsterMovement(m)` clears the destination and schedules a
  random wait: `getMillisecondsPerMove(m) *
  Constants.rollValue(Constants.monsterWaitTurns)` (`monsterWaitTurns` is a
  `ConstRange(5,1)`), i.e. the monster pauses for a randomized number of
  "turns" before picking a new destination.
- **Deciding the next step** (`determineMonsterNextPosition`), in priority
  order:
  1. If the monster's `AggressionType` is `wholeMap`, or it's
     `protectSpawn` and the player is currently inside the monster's home
     `area`, it pathfinds straight at the player
     (`findPathFor(m, playerPosition)` → `PathFinder.findPathBetween`, see
     [movement-pathfinding.md](./movement-pathfinding.md)) and uses the
     resulting `m.nextPosition` if a path was found.
  2. If `AggressionType` is `flee`, it computes a `movementDestination`
     mirrored through the monster's own position away from the player
     (`mx - (px - mx)`, `my - (py - my)`, clamped to map bounds) — i.e. it
     picks a point exactly as far past itself as the player is close, so it
     tends to run away in a straight line.
  3. If the monster has an active `travelDestination`/`travelPath` (see
     "Travelling monsters" below), it pathfinds toward the next leg's
     map-change object or, if already on the destination map, toward the
     final destination area.
  4. Otherwise, if idle with no `movementDestination`, it picks a new random
     wander target inside its home area: a coin flip decides whether to
     randomize the `x` or `y` coordinate (`Constants.rnd.nextBoolean()`) —
     only one axis is randomized per new destination, the other stays at the
     monster's current position — and then every subsequent step moves one
     tile at a time straight toward that destination (`sgn(...)` on each
     axis, not real pathfinding for plain wandering).
- **Validating the step**: `monsterCanMoveTo` (also reused by the spawn
  position search) rejects the target tile if it isn't walkable on the
  tilemap, another monster already occupies it
  (`map.getMonsterAt(p, movingMonster)`, excluding itself), or — unless
  `ignoreAreas` — it overlaps an active `newmap`/`keyarea`/`rest` map event
  object. Failing this check cancels the current movement attempt exactly
  like arriving at a destination (random wait, no move this tick).
- **Stepping onto the player**: if the computed `nextPosition` would land on
  the player's tile, a non-aggressive monster simply cancels its move
  (treats the player as an obstacle); an aggressive monster instead fires
  `monsterMovementListeners.onMonsterSteppedOnPlayer(m)` and calls
  `controllers.combatController.monsterSteppedOnPlayer(m)`, which is how
  free-roam contact with a hostile monster starts turn-based combat (see
  [combat.md](./combat.md)).
- **Actually moving**: `moveMonsterToNextPosition`/
  `moveMonsterToNextPositionDuringCombat` update `m.lastPosition`/`m.position`
  and drive a visual slide effect via
  `effectController.startActorMoveEffect`, firing
  `monsterMovementListeners.onMonsterMoved(map, m, previousPosition)` when
  the animation completes. The "during combat" variant uses
  `preferences.attackspeed_milliseconds` instead of the free-roam movement
  duration, and is what `CombatController` calls when a monster moves as
  part of its combat turn (see `CombatController.java:420`).

### Aggro / attacking

`MonsterMovementController.attackWithAgressiveMonsters()` runs every tick
right after `moveMonsters()`. For the first aggressive monster
(`m.isAgressive(world.model.player)`) that is already adjacent to the player
(`m.isAdjacentTo(player)`), it rolls
`Constants.MONSTER_AGGRESSION_CHANCE_PERCENT` (`15`) minus a bias from the
player's `evasion` skill
(`SkillCollection.PER_SKILLPOINT_INCREASE_EVASION_MONSTER_ATTACK_CHANCE_PERCENTAGE`
per skill level — see
[character-stats-leveling.md](./character-stats-leveling.md)); on success it
fires `onMonsterSteppedOnPlayer`/`monsterSteppedOnPlayer` (the same combat
trigger used when a monster walks onto the player) and returns — only one
monster can trigger combat this way per tick.

`Monster.isAgressive(Player p)` is `true` if the monster has no dialogue
(`getPhraseID() == null` — a monster with nothing to say defaults to
hostile), or `forceAggressive` was set (`Monster.forceAggressive()`, e.g. by
scripted events), or the player's alignment with the monster's `faction` is
negative (`p.getAlignment(getFaction()) < 0`).

### Travelling monsters (cross-map movement)

A monster can be sent walking to a destination on a possibly different map
via `MonsterMovementController.beginTravel(m, mapID, destinationID)`, which
looks up the named `TravelDestinationArea`, computes a `GlobalPath` via the
map-graph `GlobalPathFinder` (`globalPathFinder.findPath(...)`, see
[movement-pathfinding.md](./movement-pathfinding.md)), and clears any local
`movementDestination`. While travelling, `determineMonsterNextPosition`
pathfinds leg-by-leg toward each `newmap` object on the path; once the
monster reaches the map-change tile, it is removed from the current map
(`world.model.currentMaps.map.removeMonster(m)`) and added to the
world-level `world.monsters.travellingMonsters` list
(`MonsterCollection`), decoupling it from any single map's tick loop.

Every tick, `moveMonsters()` also walks `world.monsters.travellingMonsters`
and computes each one's progress along its `GlobalPath` purely from elapsed
wall-clock time (`elapsedDistance = (currentTime - travelPath.startTime) *
10 / getMillisecondsPerMove(m)`) rather than per-tile stepping. If the
current leg's map matches the map the player is currently viewing, or the
monster has fully arrived, `spawnMonsterOnMap` re-adds it to that
`PredefinedMap`'s `monsters` list at the interpolated position
(`map.pathfinder.findPositionOnPath(...)`) and fires `onMonsterSpawned` (only
if that map is the one currently being viewed). This means a travelling
monster only "exists" as a positioned `Monster` on a map while the player is
on that map or it has just arrived there — otherwise it's tracked abstractly
by `travelPath`/elapsed time alone.

## How other systems hook in

- **[game-loop.md](./game-loop.md)** — `GameRoundController.onNewTick()`
  calls `monsterMovementController.moveMonsters()`, then
  `monsterSpawnController.maybeSpawn(...)`, then
  `monsterMovementController.attackWithAgressiveMonsters()`, in that fixed
  order, every tick (not every round). Map load/transition
  (`MapController.java`, `MovementController.java`) and map re-entry after a
  layout change also call `monsterSpawnController.spawnAll(...)` directly,
  outside the tick loop.
- **[movement-pathfinding.md](./movement-pathfinding.md)** — monster
  chase/flee/travel movement is computed by the same `PathFinder` used for
  the player (`MonsterMovementController.findPathFor` →
  `PathFinder.findPathBetween`), and cross-map travel uses the dedicated
  `GlobalPathFinder` owned by `MonsterMovementController`
  (`globalPathFinder`) plus `PredefinedMap.pathfinder.findPositionOnPath`
  for interpolating a travelling monster's on-map position.
- **[combat.md](./combat.md)** — `CombatController.monsterSteppedOnPlayer`
  is the entry point free-roam monster contact calls into to start combat.
  During combat, `CombatController` calls back into
  `monsterMovementController.findPathFor`/`moveMonsterToNextPositionDuringCombat`
  for monster repositioning, and `monsterSpawnController.remove(map,
  killedMonster)` when a monster dies in combat. `Monster.isAgressive`,
  `AggressionType`, and the combat-trait fields on `Monster` (copied from
  `MonsterType`) are read directly by `CombatController`'s monster-turn AI.
- **[character-stats-leveling.md](./character-stats-leveling.md)** — a
  `Monster`'s combat stats originate from `MonsterType` via
  `resetStatsToBaseTraits()`, and are recalculated/healed by
  `ActorStatsController` (e.g. `healAllMonsters`, called after
  `spawnAllInArea` bulk-fills a spawn area, and monster-condition
  application each round/full round — see `game-loop.md`).
- **[items-equipment.md](./items-equipment.md)** — `Monster.getDropList()`/
  `createLoot(...)` (via `MonsterType.dropList`) generate the loot bag a
  player receives on a kill; `Monster.getShopItems(Player)`
  lazily generates a shopkeeper-style inventory from the same `dropList`
  machinery for monsters used as vendors (consistent with monsters also
  modeling stationary NPCs).
- **Listeners** — `MonsterMovementListener`
  (`onMonsterSteppedOnPlayer`, `onMonsterMoved`) and `MonsterSpawnListener`
  (`onMonsterSpawned`, `onMonsterRemoved`, plus blood-splatter events
  `onSplatterAdded`/`onSplatterChanged`/`onSplatterRemoved`, fired from
  `VisualEffectController.java`) are broadcast via
  `MonsterMovementController.monsterMovementListeners` and
  `MonsterSpawningController.monsterSpawnListeners`. The only current
  subscriber is `MainView.java` (registers/unregisters itself in
  `subscribe()`/`unsubscribe()`), used purely to trigger redraws — same
  pattern as `GameRoundListener` in `game-loop.md`.

## Gotchas / non-obvious behavior worth knowing

- **`MONSTER_IMMOBILE_MOVE_COST` (999) is how stationary "monsters" (e.g.
  shopkeepers) are modeled.** There's no separate NPC class for a
  non-moving, non-wandering actor — it's a `Monster` whose `MonsterType`
  sets `moveCost` to this sentinel value, which both skips
  `moveMonster`'s body and sets `nextActionTime = Long.MAX_VALUE` in the
  constructor so it's never even considered.
- **Wandering monsters do not pathfind; chasing/fleeing/travelling monsters
  do.** Plain idle wandering (`determineMonsterNextPosition`'s last branch)
  moves one tile at a time in a straight line toward a randomly chosen point
  using simple sign-of-difference math (`sgn(...)`), with no obstacle
  awareness beyond the immediate next tile's `monsterCanMoveTo` check. Only
  the aggressive-chase, flee-target, and travel-to-mapchange branches call
  `findPathFor` (real pathfinding). A monster that fails a `monsterCanMoveTo`
  check while wandering just cancels and waits — it doesn't try to route
  around the obstacle.
- **`quantity.current` can go stale or negative-adjacent bookkeeping is easy
  to break.** It's incremented in `MonsterSpawnArea.spawn(...)` and
  decremented in `Monster.clearMobCap()`, called from
  `PredefinedMap.removeMonster`/`removeAllMonsters`. Any code path that
  removes a monster from `map.monsters` *without* going through
  `PredefinedMap.removeMonster` (e.g. directly mutating the list) would leak
  spawn-area capacity.
- **`respawnspeed`/`rollShouldSpawn()` is a per-tick probability roll, not a
  timer.** `maybeSpawn` re-rolls `Constants.rollResult(respawnspeed)` every
  single tick for every spawn area that still has room, so actual real-world
  time between spawns is randomized and only statistically related to the
  configured `respawnspeed` range.
- **`isSpawnable`/unique-monster handling depends on the caller explicitly
  opting in.** `maybeSpawn` always passes `includeUniqueMonsters = false`,
  so unique spawn areas are never refilled by the normal per-tick spawn
  check once emptied — only explicit calls with `respawnUniqueMonsters =
  true` (first map visit, savegame migration adding a new unique area, or
  `activateSpawnArea(..., true)`) can (re)populate them.
- **A travelling monster's on-map position is a time-based interpolation,
  not a persisted tile-by-tile walk.** If the player isn't on the map a
  travelling monster is currently crossing, that monster has no `Monster`
  object in any map's `monsters` list at all — its state lives entirely in
  `Monster.travelPath`/`nextActionTime` math, reconstructed into a concrete
  position only via `spawnMonsterOnMap` when relevant (arrival, or the
  player being on that map).
- **`ignoreAreas` is decided per spawn area but stored per monster.** A
  `MonsterSpawnArea.ignoreAreas` flag is copied onto each `Monster` at
  construction time (`Monster`'s constructor), so it travels with the
  monster instance even if the monster later leaves its spawn area (e.g. via
  chasing or travelling) — a chasing monster from an `ignoreAreas` spawn can
  walk onto `newmap`/`keyarea`/`rest` objects elsewhere on the map too.
- **Only one monster can trigger free-roam combat per tick.**
  `attackWithAgressiveMonsters()` returns as soon as one aggressive,
  adjacent monster wins its aggression roll — it doesn't evaluate the rest
  of the monster list that tick even if several aggressive monsters are
  adjacent to the player simultaneously.

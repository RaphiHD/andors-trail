# Movement & Pathfinding

## Overview

This mechanic covers how the player and monsters get from one tile to
another: the per-tile collision checks, the timers that turn a held
direction or a screen tap into a sequence of single-tile steps, and the two
separate pathfinding algorithms the codebase uses for monster AI (a local
A\*-style search within one map, and a Dijkstra search across the map
graph for long-distance monster travel). Player movement, notably, does
**not** use either pathfinder — it is driven by simple directional stepping
(see "How it works" and Gotchas below). All movement ultimately reads
collision data from the tile grid (`maps-world.md`), is paced by the
tick/round timer (`game-loop.md`), and monster movement decisions feed into
monster AI more broadly (`monsters.md`).

## Key classes/files

| File | Role |
|---|---|
| [MovementController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/MovementController.java) | Drives player movement: turns a held direction into repeated single-tile steps, resolves collisions/obstacles per step, fires the move visual effect, and notifies `PlayerMovementListener`s. Also handles placing the player on a map (map transitions, respawn) and fixing up actors that ended up on now-unwalkable tiles. |
| [PathFinder.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/PathFinder.java) | Per-map, 8-directional A\*-style search over the tile grid. One instance is owned by each `PredefinedMap` (`map.pathfinder`). Used by monster AI to chase/flee/travel locally, and internally by `PredefinedMap` to precompute distances between map exits. |
| [GlobalPathFinder.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/GlobalPathFinder.java) | Dijkstra search over a graph whose nodes are map-exit (`newmap`) objects across *all* loaded maps, used to plan multi-map monster journeys (`beginTravel`). Leans on `PathFinder`/`PredefinedMap.getDistance` for the local (single-map) edge weights in that graph. |
| [MonsterMovementController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/MonsterMovementController.java) | Per-tick monster movement AI: decides each monster's next tile (chase/flee/wander/travel), calls `PathFinder`/`GlobalPathFinder`, and applies the resulting step. Owns the one `GlobalPathFinder` instance used for cross-map travel. |
| [InputController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/InputController.java) | Translates raw input (keyboard keys, D-pad, touch/tap/drag) into calls to `MovementController.startMovement`/`stopMovement` or, in combat, `CombatController.executeMoveAttack`. |
| [PlayerMovementListener.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/PlayerMovementListener.java) | Observer interface (`onPlayerMoved`, `onPlayerEnteredNewMap`) for reacting to player movement. |
| [PlayerMovementListeners.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/PlayerMovementListeners.java) | `ListOfListeners<PlayerMovementListener>` broadcast list owned by `MovementController` (`playerMovementListeners`). |
| [MonsterMovementListeners / MonsterMovementListener](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/MonsterMovementListeners.java) | Equivalent observer pair for monster movement (`onMonsterMoved`, `onMonsterSteppedOnPlayer`), owned by `MonsterMovementController`. |
| [PredefinedMap.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/map/PredefinedMap.java) | Owns the map's `PathFinder` instance, exposes `isWalkable(CoordRect, ...)` (area + event-object collision), and precomputes a distance matrix between all `newmap` exits on the map (`fillMapchangeDistances`) that `GlobalPathFinder` reads via `getDistance(id1, id2)`. |
| [LayeredTileMap.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/map/LayeredTileMap.java) | Owns the raw per-tile `isWalkable[x][y]` boolean grid that both `PathFinder` and direct movement checks ultimately query. See `maps-world.md`. |
| [Constants.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/Constants.java) | Timing constants used by movement: `MINIMUM_INPUT_INTERVAL` (player step interval), `MONSTER_MOVEMENT_TURN_DURATION_MS`, `MONSTER_IMMOBILE_MOVE_COST`, `monsterWaitTurns`. |

## How it works

### Player movement is directional stepping, not pathfinding

Despite the tap-to-move UI, the player never runs `PathFinder`. The whole
flow is:

1. **Input → direction.** `InputController` converts keyboard/D-pad state
   or a touched screen tile into a `(dx, dy)` delta and calls
   `MovementController.startMovement(dx, dy, destination)`. For touch,
   `InputController.onTouchedTile(tile_x, tile_y)` computes
   `dx = tile_x - player.position.x`, `dy = tile_y - player.position.y`
   from the *current* player position at the moment of the touch event, and
   is invoked on both `ACTION_DOWN` and `ACTION_MOVE` (see
   `MainView.onTouchEvent`), so dragging a finger continuously updates the
   heading.
2. **`startMovement` just stores a heading.** `MovementController.startMovement`
   stores `movementDx`/`movementDy` and starts `movementHandler`, a
   `TimedMessageTask` ticking at `Constants.MINIMUM_INPUT_INTERVAL`
   (200 ms normally, 50 ms in `DEVELOPMENT_FASTSPEED` builds). The
   `destination` parameter passed in is **not used** anywhere in the method
   — see Gotchas.
3. **Each timer tick calls `movePlayer(movementDx, movementDy)`**
   (`MovementController.onTick`), which:
   - Bails out if in combat (`mayMovePlayer()`).
   - Calls `findWalkablePosition(dx, dy)` to resolve one single-tile step
     (see below).
   - If a monster occupies the resolved tile, delegates to
     `MapController.steppedOnMonster` instead of moving (this is how
     walking into a monster starts combat).
   - Otherwise calls `moveToNextIfPossible()`, which checks `keyarea`
     event objects blocking the tile, updates `player.position`, clears
     any combat selection, and plays the move animation via
     `VisualEffectController.startActorMoveEffect` (duration
     `MINIMUM_INPUT_INTERVAL / 2`). Its completion callback fires
     `playerMovementListeners.onPlayerMoved(...)`,
     `MapController.handleMapEventsAfterMovement(...)`, and loot-bag pickup.
4. Movement keeps repeating every tick until `MovementController.stopMovement()`
   is called (key released, `onTouchCancel`/`ACTION_UP`, combat entered, or
   `movePlayer` returns early because a step was blocked).

### Resolving a single step: `findWalkablePosition`

Only the **sign** of `dx`/`dy` matters here (`movePlayer` doesn't shrink the
delta as the player approaches a target — see Gotchas). `findWalkablePosition`
first tries with the user's `movementAggressiveness` preference, and if that
fails and the preference wasn't already `MOVEMENTAGGRESSIVENESS_NORMAL`,
retries once with `NORMAL`. Depending on `preferences.movementMethod`, one
of two sliding strategies picks the actual tile to try:

- `findWalkablePosition_straight` (`MOVEMENTMETHOD_STRAIGHT`): try the exact
  diagonal/direction first; if blocked and the input was a pure axis
  direction, give up; if it was a true diagonal, fall back to the
  larger-magnitude axis component.
- `findWalkablePosition_directional` (default): try the exact direction
  first; if blocked, try sliding along one axis, then the other (e.g.
  moving north into a wall tries north-east then north-west).

Each candidate tile is checked by `tryWalkablePosition(dx, dy, aggressiveness)`,
which sets `player.nextPosition` and:
- Rejects the tile outright if `tileMap.isWalkable(nextPosition)` is false
  (raw tile collision grid — see `maps-world.md`).
- Under `NORMAL` aggressiveness, any walkable tile is accepted.
- Under `AGGRESSIVE`/`DEFENSIVE`, the tile is additionally accepted/rejected
  based on whether a monster is standing there and whether it is aggressive
  towards the player (`Monster.isAgressive`), implementing the "walk into
  monsters" vs. "avoid monsters" movement preferences.

### Local pathfinding: `PathFinder`

`PathFinder` is a hand-rolled A\*-style search (8-directional, uniform
integer costs: 10 for orthogonal, effectively encoded via the octile
heuristic below) over a single map's tile grid, using a flat `int[]`
open-set binary min-heap (`OpenSetHeap`) rather than a library priority
queue. Key details, grounded in the code:

- `findPathBetween(CoordRect from, CoordRect to, CoordRect nextStep, Monster m)`
  seeds the **open set from every walkable tile inside the `to` rectangle**
  (multi-tile goal region) with `gScore = 0`, then searches outward from
  there — i.e. the search runs from destination toward origin, not the
  other way around. It stops as soon as it pops a tile adjacent to the
  `from` rectangle (`from.isAdjacentTo(curr)`).
- The heuristic is `heuristic(ax, ay, bx, by) = 10 * max(dx, dy) + 4 * min(dx, dy)`,
  an octile-distance estimate tuned for move costs of 10 (orthogonal); the
  code comment notes it was tuned for orth=10/diag=14 even though diagonal
  moves are charged the same `moveCost = 10` as orthogonal ones in the
  current implementation.
- Walkability during the search uses `map.isWalkable(nextStep, m)` when a
  `Monster m` is supplied (adds monster-specific obstacles, e.g. other
  monsters, via `MonsterMovementController.monsterCanMoveTo`), or
  `map.isWalkable(nextStep, true)` (ignoring event-object areas) otherwise.
- **Hard iteration cap of 500** (`if (++iterations > 500) ... return false`)
  — the search gives up and logs `"PATHFINDER: iteration limit reached
  (500)."` rather than exhaustively searching a large/open map.
- `findPositionOnPath(from, to, distance, m)` reuses the just-computed
  `gScore`/`predecessor` arrays to find the tile reached after moving a
  given `distance` along the discovered path — this is how
  `MonsterMovementController.spawnMonsterOnMap` places a travelling monster
  partway along its route when it reappears on a map.
- A `showPathfinderDebug` static flag (toggled from `DebugInterface`) makes
  the finder record `last_visited`/`last_path`/`last_path_distances` for
  on-screen debugging of the search.

### Cross-map pathfinding: `GlobalPathFinder`

`GlobalPathFinder.findPath(fromMapName, fromPosition, toMapName, toPosition, destinationID)`
runs **Dijkstra's algorithm** (a `PriorityQueue<NodeDistance>`, not A\*) over
a graph where each node (`Node`) is a `(map, mapchange MapObject)` pair —
i.e. a specific map exit — and is used for multi-map monster travel
(`MonsterMovementController.beginTravel`):

- If `fromMapName.equals(toMapName)`, it short-circuits to a single local
  `PathFinder.findPathBetween` call and returns a one-entry path — but the
  code has a `TODO` noting "there is not always a local path -> still do
  global!", i.e. this same-map fast path can incorrectly fail to find a
  route that would require leaving and re-entering the map.
- Otherwise it seeds the queue with every exit reachable from the start
  position (weighted by local `PathFinder` distance), relaxes edges (a)
  between exits on the *same* map using the precomputed
  `PredefinedMap.getDistance` matrix, and (b) across a map boundary, which
  is modeled as **zero-cost** ("Transitions between maps are currently
  considered instantaneous (distance 0)" per the code's own comment).
- The result is a `GlobalPath`: an ordered list of `GlobalPathEntry`
  (map ID, destination/exit ID, leg distance, cumulative distance).
  `MonsterMovementController.moveMonsters()` advances a travelling
  monster along this path by comparing elapsed wall-clock time (converted
  to distance units via `getMillisecondsPerMove`) against each entry's
  `cumulatedDistance`, removing the monster from its source map and
  re-spawning it (via `PathFinder.findPositionOnPath`) on the destination
  map once its leg is reached.

### Monster step-by-step movement

`MonsterMovementController.moveMonsters()` runs every game tick (see
`game-loop.md`) and, per monster whose `nextActionTime` has elapsed, calls
`moveMonster`, which:

- Skips monsters with `getMoveCost() == Constants.MONSTER_IMMOBILE_MOVE_COST`.
- Schedules the *next* action `getMillisecondsPerMove(m)` ms out, where
  `getMillisecondsPerMove = Constants.MONSTER_MOVEMENT_TURN_DURATION_MS * m.getMoveCost() / m.getMaxAP()`
  — i.e. move speed is derived from the monster's action-point cost, the
  same stat system combat uses (see `combat.md`).
- Calls `determineMonsterNextPosition`, which picks `m.nextPosition` by, in
  priority order: chasing the player via `findPathFor(m, playerPosition)`
  (wraps `PathFinder.findPathBetween`) if aggressive/whole-map aggression;
  fleeing by mirroring the player's position through the monster
  (`m.movementDestination = 2*m.position - playerPosition`, clamped to map
  bounds) if the `flee` aggression type; following its `GlobalPath` leg by
  leg if `m.travelDestination != null`; or otherwise wandering toward a
  random point inside its spawn `area` picked one axis at a time, moving
  one tile per turn straight toward it (`sgn` on each axis, no pathfinder).
- Validates the chosen tile with `monsterCanMoveTo` (tile walkability,
  no other monster present, and — unless `ignoreAreas` — not inside a
  `newmap`/`keyarea`/`rest` event object), and either steps into the player
  (triggering combat via `MapController`/`CombatController`) or moves.
- Actual movement (`moveMonsterToNextPositionWithCallback`) plays a move
  animation (`VisualEffectController.startActorMoveEffect`, duration
  `getMillisecondsPerMove(m) / 4` outside combat, or
  `preferences.attackspeed_milliseconds / 4` during combat) and fires
  `monsterMovementListeners.onMonsterMoved(...)` on completion.

## How other systems hook in

- **`PlayerMovementListener` / `PlayerMovementListeners`**
  (`MovementController.playerMovementListeners`): `onPlayerMoved(map,
  newPosition, previousPosition)` fires after each completed player step
  (after the move animation), `onPlayerEnteredNewMap(map, position)` fires
  on map transitions/respawn. Registered by `MainView` and `MainActivity`
  (both use it to trigger redraws/UI updates).
- **`MonsterMovementListener` / `MonsterMovementListeners`**
  (`MonsterMovementController.monsterMovementListeners`): `onMonsterMoved`
  and `onMonsterSteppedOnPlayer` — see `monsters.md` for consumers and how
  this feeds monster AI/aggression more broadly.
- **The game loop** (`game-loop.md`) is what actually drives monster
  movement: `GameRoundController.onNewTick()` calls
  `monsterMovementController.moveMonsters()` and
  `attackWithAgressiveMonsters()` every tick, unconditionally, as long as
  the tick loop is running (i.e. not paused, not in combat). Player
  movement, by contrast, is driven by its own independent
  `TimedMessageTask` (`MovementController.movementHandler`) started/stopped
  directly by input, not by the round/tick controller.
- **Combat** (`combat.md`): entering combat (`isInCombat = true`) is the
  main thing that stops player movement (`mayMovePlayer()` checks it, and
  `onTick` in both `MovementController` and `GameRoundController` bail out
  while it's true). Walking into a monster (`MapController.steppedOnMonster`)
  or a monster stepping onto the player
  (`MonsterMovementController.attackWithAgressiveMonsters`,
  `moveMonster`'s aggressive-stepped-on-player branch) is one of the ways
  combat starts. During combat, movement instead goes through
  `CombatController.executeMoveAttack` (see `InputController.onRelativeMovement`),
  not through `MovementController.startMovement`.
- **Collision/walkability data** (`maps-world.md`): both the direct
  step-by-step checks (`tryWalkablePosition`, `monsterCanMoveTo`) and both
  pathfinders ultimately bottom out in `LayeredTileMap.isWalkable`
  (the precomputed per-tile boolean grid) plus a scan of active `MapObject`
  event areas (`newmap`/`keyarea`/`rest`) for area-based blocking.

## Gotchas / non-obvious behavior worth knowing

- **Tap-to-move does not pathfind to the tapped tile.** `startMovement`'s
  `destination` parameter is accepted but never read inside the method —
  only `dx`/`dy` are stored, and only their *sign* is used every tick
  thereafter (`sgn(dx)`, `sgn(dy)` in `tryWalkablePosition`). Since the
  stored `movementDx`/`movementDy` are not recalculated as the player moves
  closer to the tapped tile, tapping a distant tile sets a sustained
  heading rather than a destination — the player keeps walking in that
  general direction (sliding around obstacles per
  `findWalkablePosition_directional`/`_straight`) until blocked or until
  something calls `stopMovement()` (finger lifted, key released, combat
  starts). It will happily walk past the tapped tile if nothing stops it.
- **The player never uses `PathFinder`/`GlobalPathFinder` at all.** Those
  classes and their A\*/Dijkstra searches exist purely for monster AI. Any
  "smarter" tap-to-move pathing would be new functionality, not something
  already wired up and dormant.
- **`GlobalPathFinder`'s same-map fast path can miss valid routes.** Per
  its own `TODO` comment, when `fromMapName.equals(toMapName)` it only ever
  tries a direct local path and never considers leaving the map and
  re-entering it, even if that would be the only (or shorter) route.
- **Map-to-map transitions cost zero in the global path graph.** `GlobalPathFinder`
  models crossing a `newmap` exit as instantaneous, so a travelling
  monster's `predictedTime`/leg timings only reflect in-map walking
  distance, not any transition delay.
- **`PathFinder.findPathBetween` searches backward from the goal.** It
  seeds the open set with the destination area and searches until it
  reaches a tile adjacent to the *origin*, which is why `nextStep` (an
  out-parameter) ends up holding the first step of the real path even
  though the search itself explores from destination to origin.
- **Diagonal and orthogonal monster/pathfinder moves cost the same** (both
  `moveCost = 10` in `PathFinder`), even though the heuristic comment
  references a tuned orth=10/diag=14 cost model — the actual edge weights
  used during the search do not implement that distinction.
- **Pathfinding silently gives up after 500 iterations** on a single
  `findPathBetween` call, logging a message and returning `false` (treated
  as "no path") rather than continuing — relevant if a large open map or a
  pathological layout causes monster AI to seemingly fail to chase/flee.
- **`MovementController.resetMovementHandler()` and the `movementHandler`
  field's missing `final`** are marked with `//TODO restore final modifier
  before release` / `//TODO remove this method before release` comments in
  the source — a known-temporary state in the codebase, not necessarily
  representative of intended long-term design.
- **Player movement speed is a fixed global constant**
  (`MINIMUM_INPUT_INTERVAL`, 200 ms normally / 50 ms in dev-fast-speed
  builds) independent of any player stat, whereas monster movement speed
  (`getMillisecondsPerMove`) is derived per-monster from `getMoveCost() /
  getMaxAP()` — the two actor types use unrelated speed models.

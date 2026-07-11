package com.gpl.rpg.AndorsTrail.controller;

import com.gpl.rpg.AndorsTrail.context.ControllerContext;
import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.controller.listeners.MonsterMovementListeners;
import com.gpl.rpg.AndorsTrail.model.ability.SkillCollection;
import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.model.actor.MonsterType;
import com.gpl.rpg.AndorsTrail.model.map.LayeredTileMap;
import com.gpl.rpg.AndorsTrail.model.map.MapObject;
import com.gpl.rpg.AndorsTrail.model.map.TravelDestinationArea;
import com.gpl.rpg.AndorsTrail.model.map.PredefinedMap;
import com.gpl.rpg.AndorsTrail.util.Coord;
import com.gpl.rpg.AndorsTrail.util.CoordRect;

import java.util.ArrayList;
import java.util.List;

public final class MonsterMovementController {
	private final ControllerContext controllers;
	private final WorldContext world;
	private final GlobalPathFinder globalPathFinder;
	public final MonsterMovementListeners monsterMovementListeners = new MonsterMovementListeners();

	public MonsterMovementController(ControllerContext controllers, WorldContext world) {
		this.controllers = controllers;
		this.world = world;
		this.globalPathFinder = new GlobalPathFinder(world);
	}

	public void moveMonsters() {
		long currentTime = System.currentTimeMillis();
		PredefinedMap currentMap = world.model.currentMaps.map;

		for (Monster m : currentMap.monsters) {
			if (m.nextActionTime <= currentTime) {
				if (m.area == null) {
					moveMonster(m, new CoordRect(new Coord(0, 0), currentMap.size), m.ignoreAreas);
				} else {
					moveMonster(m, m.area.area, m.ignoreAreas);
				}
			}
		}

		List<Monster> toRemoveFromTravelling = new ArrayList<>();
		for (Monster m : world.monsters.travellingMonsters) {
			if (m.nextActionTime > currentTime) continue;
			if (m.travelPath == null || m.travelPath.predictedTime < 0 || m.travelPath.path.isEmpty()) continue;

			long elapsedDistance = (currentTime - m.travelPath.startTime) * 10 / getMillisecondsPerMove(m);

			// Find current leg
			int legIndex = -1;
			for (int i = 0; i < m.travelPath.path.size(); i++) {
				if (elapsedDistance < m.travelPath.path.get(i).cumulatedDistance) {
					legIndex = i;
					break;
				}
			}

			boolean arrived = (elapsedDistance >= m.travelPath.predictedTime);
			if (arrived) {
				legIndex = m.travelPath.path.size() - 1;
				elapsedDistance = m.travelPath.predictedTime;
			}

			GlobalPathFinder.GlobalPath.GlobalPathEntry entry = m.travelPath.path.get(legIndex);
			long distanceOnLeg = elapsedDistance - (entry.cumulatedDistance - entry.distance);

			if (entry.mapID.equals(currentMap.name)) {
				// Monster is on the map the player is currently on.
				m.travelPath.currentPosition = legIndex;
				spawnMonsterOnMap(m, currentMap, legIndex, distanceOnLeg);
				toRemoveFromTravelling.add(m);
				if (arrived && m.travelDestination != null) m.travelDestination.onMonsterArrived(m);
			} else if (arrived) {
				// Arrived at destination on a DIFFERENT map.
				PredefinedMap destMap = world.maps.findPredefinedMap(entry.mapID);
				m.travelPath.currentPosition = legIndex;
				spawnMonsterOnMap(m, destMap, legIndex, distanceOnLeg);
				toRemoveFromTravelling.add(m);
				if (m.travelDestination != null) m.travelDestination.onMonsterArrived(m);
			}
		}
		for (Monster m : toRemoveFromTravelling) {
			world.monsters.removeTravellingMonster(m);
		}
	}

	public void attackWithAgressiveMonsters() {
		PredefinedMap currentMap = world.model.currentMaps.map;
		for (Monster m : currentMap.monsters) {
			if (!m.isAgressive(world.model.player)) continue;
			if (!m.isAdjacentTo(world.model.player)) continue;

			int aggressionChanceBias = world.model.player.getSkillLevel(SkillCollection.SkillID.evasion) * SkillCollection.PER_SKILLPOINT_INCREASE_EVASION_MONSTER_ATTACK_CHANCE_PERCENTAGE;
			if (Constants.roll100(Constants.MONSTER_AGGRESSION_CHANCE_PERCENT - aggressionChanceBias)) {
				monsterMovementListeners.onMonsterSteppedOnPlayer(m);
				controllers.combatController.monsterSteppedOnPlayer(m);
				return;
			}
		}
	}

	public static boolean monsterCanMoveTo(final Monster movingMonster, final PredefinedMap map, final LayeredTileMap tilemap, final CoordRect p, boolean ignoreAreas) {
		if (tilemap != null) {
			if (!tilemap.isWalkable(p)) return false;
		}
		if (map.getMonsterAt(p, movingMonster) != null) return false;

		if (!ignoreAreas) {
			for (MapObject mObj : map.eventObjects) {
				if (mObj == null) continue;
				if (!mObj.isActive) continue;
				if (!mObj.position.intersects(p)) continue;
				switch (mObj.type) {
				case newmap:
				case keyarea:
				case rest:
					return false;
				}
			}
		}
		return true;
	}

	private void moveMonster(final Monster m, final CoordRect area, final boolean ignoreAreas) {
		if (m.getMoveCost() == Constants.MONSTER_IMMOBILE_MOVE_COST) {
			return;
		}
		PredefinedMap map = world.model.currentMaps.map;
		LayeredTileMap tileMap = world.model.currentMaps.tileMap;
		m.nextActionTime = System.currentTimeMillis() + getMillisecondsPerMove(m);
		if (m.movementDestination != null && m.position.equals(m.movementDestination)) {
			// Monster has been moving and arrived at the destination.
			cancelCurrentMonsterMovement(m);
		} else {
			determineMonsterNextPosition(m, area, world.model.player.position);

			if (!monsterCanMoveTo(m, map, tileMap, m.nextPosition, ignoreAreas)) {
				cancelCurrentMonsterMovement(m);
				return;
			}
			if (m.nextPosition.contains(world.model.player.position)) {
				if (!m.isAgressive(world.model.player)) {
					cancelCurrentMonsterMovement(m);
					return;
				}
				monsterMovementListeners.onMonsterSteppedOnPlayer(m);
				controllers.combatController.monsterSteppedOnPlayer(m);
			} else {
				moveMonsterToNextPosition(m, map);
			}
		}
	}

	private void determineMonsterNextPosition(Monster m, CoordRect area, Coord playerPosition) {
		MonsterType.AggressionType aggressionType = m.getMovementAggressionType();

		// If monster is aggressive towards player
		if ((aggressionType == MonsterType.AggressionType.protectSpawn && area.contains(playerPosition)) ||
				aggressionType == MonsterType.AggressionType.wholeMap
		) {
			if (findPathFor(m, playerPosition)) {
				// we use m.nextPosition from the pathfinding
				return;
			}

		// If monster tries to flee from player
		} else if (aggressionType == MonsterType.AggressionType.flee) {
			// if flee then run towards the point where the PC is not
			m.movementDestination = new Coord(playerPosition);
			m.movementDestination.x = Math.clamp((long)2 * m.position.x - playerPosition.x, 0, world.model.currentMaps.map.size.width - 1);// mx - ( px - mx )
			m.movementDestination.y = Math.clamp((long)2 * m.position.y - playerPosition.y, 0, world.model.currentMaps.map.size.height - 1);// my - ( py - my )
		}

		// Monster is travelling -> pathfind
		if (m.travelDestination != null) {
			if (m.travelPath.currentPosition >= m.travelPath.path.size() - 1) {
				// Target map reached, pathfind locally to destinationArea
				if (m.travelDestination.area.contains(m.position)) {
					// Destination reached
					m.travelDestination.onMonsterArrived(m);
					return;
				} else if (findPathFor(m, m.travelDestination.area)) {
					// Pathfind locally to destinationArea
					return;
				}
			} else {
				String destinationID = m.travelPath.getNextDestination().destinationID;
				// Pathfind locally to next mapchange
				for (MapObject o : world.model.currentMaps.map.eventObjects) {
					if (o.type != MapObject.MapObjectType.newmap) continue;
					if (!o.id.equals(destinationID)) continue;
					if (!o.isActive) continue;

					// Check if Monster already reached mapchange area
					if (o.position.contains(m.position)) {
						// Remove Monster from map and add it to global travelling monsters
						long unitsSoFar = m.travelPath.path.get(m.travelPath.currentPosition).cumulatedDistance;
						m.travelPath.startTime = System.currentTimeMillis() - (unitsSoFar * getMillisecondsPerMove(m) / 10);
						m.travelPath.currentPosition++;
						world.monsters.addTravellingMonster(m);
						world.model.currentMaps.map.removeMonster(m);
						return;
					}

					if (findPathFor(m, o.position)) {
						// Path found, monster moved
						return;
					} else {
						// Path blocked, do something to clear path TODO
					}
				}
			}
		}

		// Monster has waited and should start to move again.
		if (m.movementDestination == null) {
			m.movementDestination = new Coord(m.position);
			// Decide whether to move horizontally or vertically
			if (Constants.rnd.nextBoolean()) {
				m.movementDestination.x = m.area.area.topLeft.x + Constants.rnd.nextInt(m.area.area.size.width);
			} else {
				m.movementDestination.y = m.area.area.topLeft.y + Constants.rnd.nextInt(m.area.area.size.height);
			}
		}

		// Monster is moving in a straight line.
		m.nextPosition.topLeft.set(
				m.position.x + sgn(m.movementDestination.x - m.position.x)
				, m.position.y + sgn(m.movementDestination.y - m.position.y)
		);
	}

	private static void cancelCurrentMonsterMovement(final Monster m) {
		m.movementDestination = null;
		m.nextActionTime = System.currentTimeMillis() + ((long) getMillisecondsPerMove(m) * Constants.rollValue(Constants.monsterWaitTurns));
	}

	private static int getMillisecondsPerMove(Monster m) {
		return Constants.MONSTER_MOVEMENT_TURN_DURATION_MS * m.getMoveCost() / m.getMaxAP();
	}
	

	private int getMillisecondsPerCombatMove() {
        return Math.max(controllers.preferences.attackspeed_milliseconds, 0);
    }

	private static int sgn(int i) {
		if (i <= -1) return -1;
		if (i >= 1) return 1;
		return 0;
	}

	public boolean findPathFor(Monster m, CoordRect to) {
		PathFinder pathfinder = world.maps.findPredefinedMap(m.currentMapID).pathfinder;
		return pathfinder.findPathBetween(m.rectPosition, to, m.nextPosition, m);
	}
	public boolean findPathFor(Monster m, Coord to) {
		PathFinder pathfinder = world.maps.findPredefinedMap(m.currentMapID).pathfinder;
		return pathfinder.findPathBetween(m.rectPosition, to, m.nextPosition, m);
	}

	public void moveMonsterToNextPosition(final Monster m, final PredefinedMap map) {
		moveMonsterToNextPositionWithCallback(m, map, getMillisecondsPerMove(m) / 4, null);
	}
	
	public void moveMonsterToNextPositionDuringCombat(final Monster m, final PredefinedMap map, final VisualEffectController.VisualEffectCompletedCallback callback) {
		moveMonsterToNextPositionWithCallback(m, map, getMillisecondsPerCombatMove() / 4, callback);
	}
	
	private void moveMonsterToNextPositionWithCallback(final Monster m, final PredefinedMap map, int duration, final VisualEffectController.VisualEffectCompletedCallback callback) {
		final CoordRect previousPosition = new CoordRect(new Coord(m.position), m.rectPosition.size);
		m.lastPosition.set(previousPosition.topLeft);
		m.position.set(m.nextPosition.topLeft);
		controllers.effectController.startActorMoveEffect(m, map, previousPosition.topLeft, m.position, duration, new VisualEffectController.VisualEffectCompletedCallback() {
			
			@Override
			public void onVisualEffectCompleted(int callbackValue) {
				if (callback != null) callback.onVisualEffectCompleted(callbackValue);
				monsterMovementListeners.onMonsterMoved(map, m, previousPosition);
			}
		}, 0);
	}

	private void spawnMonsterOnMap(Monster m, PredefinedMap map, int legIndex, long distanceOnLeg) {
		CoordRect startArea;
		if (legIndex == 0) {
			startArea = new CoordRect(m.travelPath.startingPosition, m.nextPosition.size);
		} else {
			GlobalPathFinder.GlobalPath.GlobalPathEntry prevEntry = m.travelPath.path.get(legIndex - 1);
			PredefinedMap prevMap = world.maps.findPredefinedMap(prevEntry.mapID);
			MapObject exitObj = prevMap.findEventObject(MapObject.MapObjectType.newmap, prevEntry.destinationID);
			if (exitObj != null) {
				MapObject entranceObj = map.findEventObject(MapObject.MapObjectType.newmap, exitObj.place);
				if (entranceObj != null) {
					startArea = entranceObj.position;
				} else {
					startArea = new CoordRect(new Coord(0,0), m.nextPosition.size);
				}
			} else {
				startArea = new CoordRect(new Coord(0,0), m.nextPosition.size);
			}
		}

		GlobalPathFinder.GlobalPath.GlobalPathEntry entry = m.travelPath.path.get(legIndex);
		CoordRect toArea;
		MapObject mo = map.findEventObject(MapObject.MapObjectType.newmap, entry.destinationID);
		if (mo != null) {
			toArea = mo.position;
		} else {
			toArea = m.travelDestination.area;
		}

		Coord spawnPos = map.pathfinder.findPositionOnPath(startArea, toArea, distanceOnLeg, m);

		m.position.set(spawnPos);
		m.nextPosition.topLeft.set(spawnPos);
		m.currentMapID = map.name;
		map.monsters.add(m);

		long unitsToTile = (distanceOnLeg / 10) * 10;
		long unitsRemaining = 10 - (distanceOnLeg - unitsToTile);
		if (unitsRemaining > 0) {
			m.nextActionTime = System.currentTimeMillis() + (unitsRemaining * getMillisecondsPerMove(m) / 10);
		} else {
			m.nextActionTime = System.currentTimeMillis();
		}

		if (map == world.model.currentMaps.map) {
			controllers.monsterSpawnController.monsterSpawnListeners.onMonsterSpawned(map, m);
		}
	}

	public void beginTravel(final Monster m, final String mapID, final String destinationID) {
		for (TravelDestinationArea a : world.maps.findPredefinedMap(mapID).destinationAreas) {
			if (a.areaID.equals(destinationID)) {
				m.travelDestination = a;
				m.travelPath = globalPathFinder.findPath(m.currentMapID, m.rectPosition, mapID, a.area, a.areaID);

				m.movementDestination = null;
			}
		}
	}
}

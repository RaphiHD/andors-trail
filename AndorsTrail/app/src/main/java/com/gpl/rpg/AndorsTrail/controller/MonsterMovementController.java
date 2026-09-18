package com.gpl.rpg.AndorsTrail.controller;

import com.gpl.rpg.AndorsTrail.AndorsTrailApplication;
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
import com.gpl.rpg.AndorsTrail.util.L;
import com.gpl.rpg.AndorsTrail.util.Size;

import java.util.ArrayList;
import java.util.List;

public final class MonsterMovementController {
	private final ControllerContext controllers;
	private final WorldContext world;
	private final GlobalPathFinder globalPathFinder;
	public final MonsterMovementListeners monsterMovementListeners = new MonsterMovementListeners();

	/** Toggled by the "trv" debug button. Logs travelling-monster timing/placement decisions. */
	public static volatile boolean showTravelDebug = false;

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
			if (tryPlaceTravellingMonster(m, currentMap, currentTime)) {
				toRemoveFromTravelling.add(m);
			}
		}
		for (Monster m : toRemoveFromTravelling) {
			world.monsters.removeTravellingMonster(m);
		}
	}

	/**
	 * Immediately (re-)evaluates every travelling monster's position against whatever map the
	 * player has just arrived on, instead of waiting for the next moveMonsters() tick. Without
	 * this, a monster that should already be visible on a freshly-loaded map wouldn't appear until
	 * up to one full tick (Constants.TICK_DELAY) after that map has already been rendered to the
	 * player. Unlike moveMonsters()'s own pass, this does not gate on nextActionTime - a fresh map
	 * load should show the monster's true current position right away, not wait for its next
	 * scheduled recompute.
	 */
	public void syncTravellingMonstersOntoCurrentMap() {
		long currentTime = System.currentTimeMillis();
		PredefinedMap currentMap = world.model.currentMaps.map;

		List<Monster> toRemoveFromTravelling = new ArrayList<>();
		for (Monster m : world.monsters.travellingMonsters) {
			if (tryPlaceTravellingMonster(m, currentMap, currentTime)) {
				toRemoveFromTravelling.add(m);
			}
		}
		for (Monster m : toRemoveFromTravelling) {
			world.monsters.removeTravellingMonster(m);
		}
	}

	/**
	 * Checks whether the travelling monster `m` should now be materialized on `currentMap` -
	 * either because that's the map its current leg is on, or because it has fully arrived - and
	 * if so, places it via spawnMonsterOnMap (and fires arrival, if applicable).
	 *
	 * @return true if `m` was placed and should be removed from the travelling pool.
	 */
	private boolean tryPlaceTravellingMonster(Monster m, PredefinedMap currentMap, long currentTime) {
		if (m.travelPath == null || m.travelPath.predictedTime < 0 || m.travelPath.path.isEmpty()) return false;

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

		if (showTravelDebug) {
			L.log("TRAVEL: " + m.getMonsterTypeID() + " tick: elapsedDistance=" + elapsedDistance
					+ ", legIndex=" + legIndex + " (map=" + entry.mapID + ", dest=" + entry.destinationID + ")"
					+ ", distanceOnLeg=" + distanceOnLeg + ", arrived=" + arrived
					+ ", playerMap=" + currentMap.name);
		}

		if (entry.mapID.equals(currentMap.name)) {
			// Monster is on the map the player is currently on.
			m.travelPath.currentPosition = legIndex;
			spawnMonsterOnMap(m, currentMap, legIndex, distanceOnLeg);
			if (arrived && m.travelDestination != null) m.travelDestination.onMonsterArrived(m, controllers);
			return true;
		} else if (arrived) {
			// Arrived at destination on a DIFFERENT map.
			PredefinedMap destMap = world.maps.findPredefinedMap(entry.mapID);
			m.travelPath.currentPosition = legIndex;
			spawnMonsterOnMap(m, destMap, legIndex, distanceOnLeg);
			if (m.travelDestination != null) m.travelDestination.onMonsterArrived(m, controllers);
			return true;
		}
		return false;
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
				case rest:
					return false;
				case keyarea:
					if (!mObj.monstersCanPass) return false;
					break;
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
			if (determineMonsterNextPosition(m, area, world.model.player.position)) {
				// The monster was already fully handled inside determineMonsterNextPosition
				// (e.g. handed off to the travelling pool, or it just arrived at its travel
				// destination) - m.nextPosition was never updated, and the monster may no longer
				// even be a member of `map.monsters`, so applying a move here would be wrong.
				return;
			}

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

	/**
	 * Computes this monster's next move and stores it in {@code m.nextPosition} for the caller
	 * ({@link #moveMonster}) to apply.
	 *
	 * @return {@code true} if the monster was already fully handled here (handed off to the
	 * travelling pool, or it just arrived at its travel destination) and the caller must NOT touch
	 * {@code m.nextPosition} or otherwise treat it as still being locally simulated on this tick;
	 * {@code false} if {@code m.nextPosition} was computed normally and the caller should apply it.
	 */
	private boolean determineMonsterNextPosition(Monster m, CoordRect area, Coord playerPosition) {
		MonsterType.AggressionType aggressionType = m.getMovementAggressionType();

		// If monster is aggressive towards player
		if ((aggressionType == MonsterType.AggressionType.protectSpawn && area.contains(playerPosition)) ||
				aggressionType == MonsterType.AggressionType.wholeMap
		) {
			if (findPathFor(m, playerPosition)) {
				// we use m.nextPosition from the pathfinding
				return false;
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
			// R6: an in-progress pause (today, only ever R5's resting) stands the monster still,
			// before any pathfinding call - structurally on-screen-only, since this whole method is
			// only ever reached from moveMonsters()'s loop over currentMap.monsters (the player's
			// current map); tryPlaceTravellingMonster (the wall-clock path for pooled, off-screen
			// monsters) never calls this method at all.
			if (handleTravelPause(m)) return true;

			// Deciding WHETHER to begin a new pause is deliberately kept local to resting's own
			// trigger here, not folded into handleTravelPause - the cooldown gate in particular
			// (travelRestCooldownRemaining) is specific to "don't roll another rest too soon" and
			// has no reason to constrain some future, unrelated pause reason (e.g. hunting).
			if (m.travelRestCooldownRemaining > 0) {
				// On cooldown since the last rest ended - don't even roll travelRestChance, just
				// fall through to normal travel-approach movement below like any other tick.
				m.travelRestCooldownRemaining--;
			} else if (m.monsterType.travelRestChance > 0 && Constants.roll100(m.monsterType.travelRestChance)) {
				int restTicks = Constants.rollValue(m.monsterType.travelRestDuration);
				if (restTicks > 0) {
					beginTravelPause(m, Monster.TravelPauseReason.resting, restTicks);
					return true;
				}
			}

			if (m.travelPath.currentPosition >= m.travelPath.path.size() - 1) {
				// Target map reached, pathfind locally to destinationArea
				if (m.travelDestination.area.contains(m.position)) {
					// Destination reached
					m.travelDestination.onMonsterArrived(m, controllers);
					return true;
				} else if (findPathFor(m, m.travelDestination.area)) {
					// Pathfind locally to destinationArea
					m.travelBlockedRetries = 0;
					return false;
				} else {
					return handleBlockedTravelPath(m);
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
						// Some `newmap` objects point back at their own map (e.g. a "west edge"
						// warp pair) rather than a genuinely different one - GlobalPathFinder
						// already treats crossing any mapchange as an instantaneous, zero-cost
						// hop (see its Relaxation Step 2), so the plan's distances already assume
						// this executes as a teleport, not a further walk. Execute it as such here
						// instead of handing off to the travelling pool, which is only meant for
						// actually leaving this map.
						if (o.map != null && o.map.equals(world.model.currentMaps.map.name)) {
							MapObject placeObj = world.model.currentMaps.map.findEventObject(MapObject.MapObjectType.newmap, o.place);
							if (placeObj != null) {
								int offset_x = m.position.x - o.position.topLeft.x;
								int offset_y = m.position.y - o.position.topLeft.y;
								m.position.set(placeObj.position.topLeft);
								m.position.x += Math.min(offset_x, placeObj.position.size.width - 1);
								m.position.y += Math.min(offset_y, placeObj.position.size.height - 1);
								m.nextPosition.topLeft.set(m.position);
								m.travelPath.currentPosition++;
								if (showTravelDebug) {
									L.log("TRAVEL: " + m.getMonsterTypeID() + " used same-map warp " + o.id
											+ " -> " + o.place + ", now at " + m.position
											+ ", advancing to leg " + m.travelPath.currentPosition);
								}
								return true;
							}
							L.log("WARNING: same-map mapchange " + o.id + " on map " + o.map
									+ " has no matching 'place' object " + o.place + " - falling back to normal cross-map handling.");
						}
						enterTravellingPool(m, world.model.currentMaps.map);
						return true;
					}

					if (findPathFor(m, o.position)) {
						// Path found, monster moved
						m.travelBlockedRetries = 0;
						return false;
					} else {
						return handleBlockedTravelPath(m);
					}
				}
			}
		}

		if (showTravelDebug && m.travelDestination != null) {
			// We only get here if the travel-approach findPathFor(...) above failed (final-leg
			// destination, or the next mapchange) - about to fall through to the UNRELATED wandering
			// fallback below (a random point inside the monster's original spawn area), which would
			// take a step away from the actual travel route. Logged to check whether this is
			// happening often enough to explain the local-walk timing overrun.
			L.log("TRAVEL: " + m.getMonsterTypeID() + " travel-approach pathfinding failed this tick"
					+ " (pos=" + m.rectPosition.topLeft + ", falling through to unrelated wander step)");
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
		return false;
	}

	private static void cancelCurrentMonsterMovement(final Monster m) {
		m.movementDestination = null;
		m.nextActionTime = System.currentTimeMillis() + ((long) getMillisecondsPerMove(m) * Constants.rollValue(Constants.monsterWaitTurns));
	}

	/**
	 * Called when a travelling monster's local approach step (findPathFor towards the next
	 * mapchange, or towards the final destination area) fails to find a route this tick - either
	 * a blocking actor (player/another monster) parked on the only path, or a layout change that
	 * closed it off. `findPathFor`'s A* search already excludes occupied/unwalkable tiles from
	 * the graph, so it naturally finds a detour on its own the next time it's tried, if one
	 * exists - the bug here was never retrying at all. Waits with a bounded retry/backoff
	 * (reusing cancelCurrentMonsterMovement's pattern, so it doesn't hammer the pathfinder every
	 * single tick) instead of falling through to the unrelated wander fallback, and gives up on
	 * the whole journey after too many consecutive failures - where no detour physically exists
	 * (a one-tile-wide corridor, the player standing in a doorway), an NPC shouldn't be able to
	 * shove the player out of the way, so eventually failing per beginTravel's convention (clear
	 * travelDestination/travelPath) is the correct fallback instead of waiting forever.
	 *
	 * @return always true - the monster is considered fully handled this tick either way
	 * (waiting, or the journey was just abandoned), so the caller must not apply any further
	 * movement of its own.
	 */
	private boolean handleBlockedTravelPath(Monster m) {
		m.travelBlockedRetries++;
		if (m.travelBlockedRetries > Constants.MONSTER_TRAVEL_MAX_BLOCKED_RETRIES) {
			if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA || showTravelDebug) {
				L.log("WARNING: " + m.getMonsterTypeID() + " gave up travelling (blocked path, "
						+ m.travelBlockedRetries + " consecutive failed retries) at pos="
						+ m.rectPosition.topLeft + ", map=" + m.currentMapID);
			}
			m.travelDestination = null;
			m.travelPath = null;
			m.travelBlockedRetries = 0;
			fireTravelFailedScript(m);
		} else {
			if (showTravelDebug) {
				L.log("TRAVEL: " + m.getMonsterTypeID() + " local path blocked at pos="
						+ m.rectPosition.topLeft + " (retry " + m.travelBlockedRetries + "/"
						+ Constants.MONSTER_TRAVEL_MAX_BLOCKED_RETRIES + ") - waiting before retrying");
			}
			cancelCurrentMonsterMovement(m);
		}
		return true;
	}

	/**
	 * Runs a monster's standing travelFailedScript, if it has one, after a journey has just been
	 * abandoned (travelDestination/travelPath already cleared by the caller). Not one-shot - the
	 * script stays set for the next failure too, since it represents a per-NPC fallback behavior
	 * ("always go back home if you can't get there"), not per-journey state. Caution for script
	 * authors: if this script's own effects start another journey (setDestination) that is itself
	 * immediately unreachable, beginTravel fails synchronously and re-invokes this same script -
	 * an infinite recursion if the fallback destination can never be reached. Nothing here guards
	 * against that; the fallback destination must be one that's actually reachable.
	 */
	private void fireTravelFailedScript(Monster m) {
		if (m.travelFailedScript != null) {
			controllers.mapController.runScriptForNpc(m.travelFailedScript, m);
		}
	}

	private static int getMillisecondsPerMove(Monster m) {
		int nominal = Constants.MONSTER_MOVEMENT_TURN_DURATION_MS * m.getMoveCost() / m.getMaxAP();
		// Monster movement is only ever advanced from moveMonsters(), which itself only runs once
		// per game tick (Constants.TICK_DELAY). A monster whose stats say it should move faster than
		// that can never actually achieve that speed in practice - moveMonsters() simply isn't called
		// often enough - so every wall-clock prediction based on this value (travel ETAs, the
		// interpolation/recalibration math, and this monster's own move/wait scheduling) must use
		// what the tick loop can actually deliver, not the unreachable nominal figure.
		return Math.max(nominal, Constants.TICK_DELAY);
	}
	

	private int getMillisecondsPerCombatMove() {
        return Math.max(controllers.preferences.attackspeed_milliseconds, 0);
    }

	private static int sgn(int i) {
		if (i <= -1) return -1;
		if (i >= 1) return 1;
		return 0;
	}

	/**
	 * Only used by travel-approach pathing (never by aggressive-chase-player, which uses the
	 * {@code Coord} overload below and targets the player's own tile as {@code to}) - so it's safe
	 * to always route around wherever the player currently stands, the same way other monsters are
	 * already excluded from this graph via {@code monsterCanMoveTo}/{@code getMonsterAt}.
	 */
	public boolean findPathFor(Monster m, CoordRect to) {
		PathFinder pathfinder = world.maps.findPredefinedMap(m.currentMapID).pathfinder;
		return pathfinder.findPathBetween(m.rectPosition, to, m.nextPosition, m, world.model.player.position);
	}
	public boolean findPathFor(Monster m, Coord to) {
		PathFinder pathfinder = world.maps.findPredefinedMap(m.currentMapID).pathfinder;
		return pathfinder.findPathBetween(m.rectPosition, to, m.nextPosition, m);
	}

	/**
	 * R6: continues whatever pause (today, only ever R5's resting) is already in progress on `m`.
	 * This is the generalized, reason-agnostic half of the mechanism - a future activity system
	 * (hunting, roaming, etc.) would reuse this exact continuation logic for its own pause reason via
	 * {@link Monster#travelPauseReason}/{@link Monster#travelPauseTicksRemaining} rather than
	 * maintaining its own parallel "stand still, tick down, eventually stop" copy. Deciding *whether*
	 * to begin a new pause (e.g. resting's own cooldown gate and {@code travelRestChance} roll) is
	 * deliberately not this method's job - see {@link #beginTravelPause} and the call site in
	 * {@link #determineMonsterNextPosition}.
	 *
	 * @return true if `m` was paused (already fully handled - the caller must return true from
	 * determineMonsterNextPosition without touching nextPosition); false if `m` has no active pause
	 * and the caller should proceed with normal travel-approach logic.
	 */
	private boolean handleTravelPause(Monster m) {
		if (m.travelPauseReason == null) return false;

		m.travelPauseTicksRemaining--;
		if (showTravelDebug) {
			L.log("TRAVEL: " + m.getMonsterTypeID() + " paused on-screen (" + m.travelPauseReason
					+ ", moveMonsters()'s currentMap.monsters loop, map=" + world.model.currentMaps.map.name
					+ "), " + m.travelPauseTicksRemaining + " tick(s) remaining");
		}
		if (m.travelPauseTicksRemaining <= 0) {
			onTravelPauseEnded(m);
		}
		return true;
	}

	/**
	 * R6: begins a new pause of `ticks` ticks for `reason`, correcting the ETA (see
	 * {@link #correctTravelPathForPause}) immediately. Shared by resting today and by any future
	 * pause reason - see {@link Monster#travelPauseReason}'s doc comment.
	 */
	private void beginTravelPause(Monster m, Monster.TravelPauseReason reason, int ticks) {
		m.travelPauseReason = reason;
		m.travelPauseTicksRemaining = ticks - 1; // this tick is the first paused tick
		correctTravelPathForPause(m, ticks);
		if (showTravelDebug) {
			L.log("TRAVEL: " + m.getMonsterTypeID() + " begins travel pause (" + reason + ") on-screen"
					+ " (moveMonsters()'s currentMap.monsters loop, map=" + world.model.currentMaps.map.name
					+ ") for " + ticks + " tick(s), predictedTime now " + m.travelPath.predictedTime);
		}
		if (m.travelPauseTicksRemaining <= 0) {
			// A one-tick pause is already over as of this same tick - end it now rather than
			// waiting for a handleTravelPause() decrement that will never happen.
			onTravelPauseEnded(m);
		}
	}

	/**
	 * R6: reason-specific cleanup once a pause's ticks have run out. Resting's own follow-up
	 * (starting {@link Monster#travelRestCooldownRemaining}) is scoped to the `resting` case
	 * specifically, deliberately not part of the shared pause machinery above, so it can't leak into
	 * whatever a future pause reason needs - see {@link Monster#travelRestCooldownRemaining}'s doc
	 * comment.
	 */
	private void onTravelPauseEnded(Monster m) {
		Monster.TravelPauseReason endedReason = m.travelPauseReason;
		m.travelPauseReason = null;
		m.travelPauseTicksRemaining = 0;
		if (endedReason == Monster.TravelPauseReason.resting) {
			m.travelRestCooldownRemaining = Constants.MONSTER_TRAVEL_REST_COOLDOWN_TICKS;
		}
	}

	/**
	 * R5's ETA correction, generalized in R6 to any pause reason: a paused tick is real wall-clock
	 * time spent with zero physical progress, so every distance-equivalent threshold the off-screen
	 * wall-clock model (tryPlaceTravellingMonster, enterTravellingPool) later compares real elapsed
	 * time against must grow by the same amount, or a monster that pauses and then leaves the
	 * player's current map mid-journey would desync exactly the way the Phase 0 cross-cutting concern
	 * warns about. `predictedTime` (the whole journey's total) and every remaining leg's
	 * `cumulatedDistance` (the current one - identified by `travelPath.currentPosition` - and every
	 * later one, never an already-completed leg, never `distance` itself, which stays each leg's true
	 * physical length) are increased by `pausedTicks * 10`, the same distance-per-move convention
	 * used everywhere else in this codebase. Correcting the *current* leg's `cumulatedDistance` (not
	 * just later ones) is what makes this correct even if the monster pauses more than once on the
	 * same leg, or is later handed off to the travelling pool mid-leg: both `enterTravellingPool` and
	 * `tryPlaceTravellingMonster` derive a leg's *start* threshold as `cumulatedDistance - distance`,
	 * so inflating `cumulatedDistance` alone correctly pushes both the start and end of the paused leg
	 * outward by the cumulative paused time so far, without this method needing to know which of
	 * those two callers will read it next.
	 */
	private void correctTravelPathForPause(Monster m, int pausedTicks) {
		int delta = pausedTicks * 10;
		m.travelPath.predictedTime += delta;
		for (int i = m.travelPath.currentPosition; i < m.travelPath.path.size(); ++i) {
			m.travelPath.path.get(i).cumulatedDistance += delta;
		}
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

	/**
	 * Finds the area a monster starts walking from at the beginning of the given leg of its
	 * {@code travelPath}: either the monster's original starting position (leg 0), or the entrance
	 * object on {@code map} matching the exit used to enter this leg's map (later legs). Shared by
	 * {@link #spawnMonsterOnMap} and {@link #enterTravellingPool} so both agree on where a leg begins.
	 */
	private CoordRect getLegStartArea(Monster m, PredefinedMap map, int legIndex) {
		if (legIndex == 0) {
			return new CoordRect(m.travelPath.startingPosition, m.nextPosition.size);
		}
		GlobalPathFinder.GlobalPath.GlobalPathEntry prevEntry = m.travelPath.path.get(legIndex - 1);
		PredefinedMap prevMap = world.maps.findPredefinedMap(prevEntry.mapID);
		MapObject exitObj = prevMap.findEventObject(MapObject.MapObjectType.newmap, prevEntry.destinationID);
		if (exitObj != null) {
			MapObject entranceObj = map.findEventObject(MapObject.MapObjectType.newmap, exitObj.place);
			if (entranceObj != null) {
				return entranceObj.position;
			}
		}
		return new CoordRect(new Coord(0,0), m.nextPosition.size);
	}

	/**
	 * Hands a monster off from locally-simulated, tick-by-tick movement (on {@code currentLegMap}'s
	 * monster list) into the world-level, wall-clock-interpolated {@code travellingMonsters} pool,
	 * recalibrating {@code travelPath.startTime} from the monster's actual current position rather
	 * than a theoretical leg boundary. This is what keeps later interpolation in sync with reality
	 * regardless of *why* the monster is being handed off: it may have just reached its leg's exit
	 * tile (the normal case), or it may be yanked off mid-leg because the player left the map first
	 * (see {@code MapController.handleMapEvent}'s "player leaves map" sweep) - either way, the actual
	 * distance walked so far on the current leg is measured live via the local pathfinder instead of
	 * assumed.
	 */
	public void enterTravellingPool(Monster m, PredefinedMap currentLegMap) {
		if (m.travelPath != null && !m.travelPath.path.isEmpty()) {
			int legIndex = Math.min(m.travelPath.currentPosition, m.travelPath.path.size() - 1);
			GlobalPathFinder.GlobalPath.GlobalPathEntry entry = m.travelPath.path.get(legIndex);
			long legStartDistance = entry.cumulatedDistance - entry.distance;

			CoordRect startArea = getLegStartArea(m, currentLegMap, legIndex);
			long distanceIntoLeg;
			if (startArea.intersects(m.rectPosition)) {
				// Monster hasn't moved from the start of this leg yet.
				distanceIntoLeg = 0;
			} else {
				CoordRect nextStep = new CoordRect(new Coord(), new Size(1, 1));
				if (currentLegMap.pathfinder.findPathBetween(startArea, m.rectPosition, nextStep, m)) {
					distanceIntoLeg = currentLegMap.pathfinder.getLastPathDistance();
				} else {
					// Live search failed (blocked, or the 500-iteration cap was hit) - fall back to
					// treating the leg as complete rather than silently under-counting progress.
					distanceIntoLeg = entry.distance;
				}
			}

			long unitsSoFar = legStartDistance + distanceIntoLeg;
			long previousStartTime = m.travelPath.startTime;
			m.travelPath.startTime = System.currentTimeMillis() - (unitsSoFar * getMillisecondsPerMove(m) / 10);
			m.travelPath.currentPosition = legIndex;

			if (showTravelDebug) {
				L.log("TRAVEL: " + m.getMonsterTypeID() + " entering travelling pool on map " + currentLegMap.name
						+ ", leg " + legIndex + ": legStartDistance=" + legStartDistance
						+ ", distanceIntoLeg=" + distanceIntoLeg + ", unitsSoFar=" + unitsSoFar
						+ ", startTime " + previousStartTime + " -> " + m.travelPath.startTime);
			}
		}

		world.monsters.addTravellingMonster(m);
		currentLegMap.removeMonster(m);
	}

	private void spawnMonsterOnMap(Monster m, PredefinedMap map, int legIndex, long distanceOnLeg) {
		CoordRect startArea = getLegStartArea(m, map, legIndex);

		GlobalPathFinder.GlobalPath.GlobalPathEntry entry = m.travelPath.path.get(legIndex);
		CoordRect toArea;
		MapObject mo = map.findEventObject(MapObject.MapObjectType.newmap, entry.destinationID);
		if (mo != null) {
			toArea = mo.position;
		} else {
			toArea = m.travelDestination.area;
		}

		Coord spawnPos = map.pathfinder.findPositionOnPath(startArea, toArea, distanceOnLeg, m);

		if (showTravelDebug) {
			L.log("TRAVEL: " + m.getMonsterTypeID() + " spawning on map " + map.name + " leg " + legIndex
					+ ": startArea=" + startArea.topLeft + ", toArea=" + toArea.topLeft
					+ ", distanceOnLeg=" + distanceOnLeg + " -> spawnPos=" + spawnPos);
		}

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

				if (m.travelPath.predictedTime < 0) {
					// No route exists at all between here and the destination - don't leave the
					// monster wedged with isTravelling permanently true and a travelPath that can
					// never be walked. Fail the request immediately instead.
					if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA || showTravelDebug) {
						L.log("WARNING: " + m.getMonsterTypeID() + " has no travel route from map="
								+ m.currentMapID + " pos=" + m.rectPosition.topLeft + " to map=" + mapID
								+ " dest=" + destinationID + " - travel request ignored.");
					}
					m.travelDestination = null;
					m.travelPath = null;
					fireTravelFailedScript(m);
					return;
				}
				m.travelBlockedRetries = 0;

				m.movementDestination = null;

				if (showTravelDebug) {
					StringBuilder legs = new StringBuilder();
					for (GlobalPathFinder.GlobalPath.GlobalPathEntry e : m.travelPath.path) {
						legs.append("[map=").append(e.mapID).append(" dest=").append(e.destinationID)
								.append(" distance=").append(e.distance)
								.append(" cumulated=").append(e.cumulatedDistance).append("] ");
					}
					long expectedTotalMs = (long) m.travelPath.predictedTime * getMillisecondsPerMove(m) / 10;
					L.log("TRAVEL: " + m.getMonsterTypeID() + " beginTravel from map=" + m.currentMapID
							+ " pos=" + m.rectPosition.topLeft + " to map=" + mapID + " dest=" + destinationID
							+ " at wallClock=" + System.currentTimeMillis()
							+ ": predictedTime=" + m.travelPath.predictedTime
							+ " (~" + expectedTotalMs + "ms at " + getMillisecondsPerMove(m) + "ms/tile), legs: " + legs);
				}

				// If this monster isn't on the map the player currently has loaded, nothing will
				// ever call moveMonster()/determineMonsterNextPosition() for it -
				// moveMonsters()'s local-simulation pass only walks the current map's monster
				// list. Left alone, it would just sit frozen wherever it physically is until the
				// player happens to visit that map. This can happen e.g. when an arrival script
				// immediately sends a monster on a new journey while the player is still
				// elsewhere. Hand it straight to the travelling pool in that case, the same way it
				// would get there mid-journey, so wall-clock interpolation picks it up starting
				// this tick instead.
				PredefinedMap playerMap = world.model.currentMaps.map;
				if (playerMap == null || !m.currentMapID.equals(playerMap.name)) {
					PredefinedMap monsterMap = world.maps.findPredefinedMap(m.currentMapID);
					if (monsterMap != null && monsterMap.monsters.contains(m)) {
						if (showTravelDebug) {
							L.log("TRAVEL: " + m.getMonsterTypeID() + " started travel while off-screen (on "
									+ m.currentMapID + ", player on "
									+ (playerMap == null ? "?" : playerMap.name)
									+ ") - moving straight to the travelling pool");
						}
						enterTravellingPool(m, monsterMap);
					}
				}
			}
		}
	}
}

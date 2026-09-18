package com.gpl.rpg.AndorsTrail.controller;

import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.model.map.MapObject;
import com.gpl.rpg.AndorsTrail.model.map.PredefinedMap;
import com.gpl.rpg.AndorsTrail.util.Coord;
import com.gpl.rpg.AndorsTrail.util.CoordRect;
import com.gpl.rpg.AndorsTrail.util.L;
import com.gpl.rpg.AndorsTrail.util.Size;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * GlobalPathFinder is responsible for finding the shortest path for a monster (or potentially other actors)
 * across multiple maps in the game world. It treats the world as a graph where each "node" is a transition
 * point (map exit/entry) and edges represent travel either within a map or between maps.
 */
public class GlobalPathFinder {
	private final WorldContext world;

	public GlobalPathFinder(WorldContext world) {
		this.world = world;
	}

	/**
	 * Finds a global path from a starting position on one map to a destination area on another (or same) map.
	 *
	 * @param fromMapName   The name of the map where the journey begins.
	 * @param fromPosition  The starting coordinates and size of the traveler.
	 * @param toMapName     The name of the destination map.
	 * @param toPosition    The target area on the destination map.
	 * @param destinationID The ID of the final destination area (stored in the path result).
	 * @return A GlobalPath object containing the sequence of maps and transitions, or a path with distance -1 if unreachable.
	 */
	public GlobalPath findPath(String fromMapName, CoordRect fromPosition, String toMapName, CoordRect toPosition, String destinationID) {
		if (MonsterMovementController.showTravelDebug) {
			L.log("PATHFINDER: finding path between " + fromMapName + " and " + toMapName);
		}

		// Basic validation: pathfinding requires both start and end maps to exist.
		if (fromMapName == null || toMapName == null) return new GlobalPath(new ArrayList<>(), fromPosition.topLeft, System.currentTimeMillis(), 0);

		// Optimization: if already on the target map, try a direct local path first - much cheaper
		// than the full graph search below, and correct whenever nothing blocks a straight walk.
		if (fromMapName.equals(toMapName)) {
			PredefinedMap map = world.maps.findPredefinedMap(fromMapName);
			// Don't gate on findPathBetween's boolean return: it reports "fromPosition already
			// overlaps toPosition" the same way as "genuinely unreachable" (both `false`).
			// getLastPathDistance() is the authoritative signal: -1 = unreachable, >=0 = a real
			// distance (0 meaning "already there").
			map.pathfinder.findPathBetween(fromPosition, toPosition, new CoordRect(new Size(1, 1)));
			int d = map.pathfinder.getLastPathDistance();
			if (d >= 0) {
				List<GlobalPath.GlobalPathEntry> path = new ArrayList<>();
				// Path consists of a single leg: staying on this map to reach the destination.
				path.add(new GlobalPath.GlobalPathEntry(fromMapName, destinationID, d, d));
				return new GlobalPath(path, fromPosition.topLeft, System.currentTimeMillis(), d);
			}
			// No direct local path (e.g. a physical barrier splits the map) - fall through to the
			// full graph search below instead of giving up. That search works regardless of
			// whether the start and target map happen to be the same one, and can find a route
			// that leaves this map and re-enters it through a different exit.
		}

		/**
		 * Represents a "state" in our global search.
		 * A node is uniquely identified by the map we are on and the specific exit (mapchange) we are at.
		 */
		class Node {
			final PredefinedMap map;
			final MapObject mapchange;

			Node(PredefinedMap map, MapObject mapchange) {
				this.map = map;
				this.mapchange = mapchange;
			}

			@Override
			public boolean equals(Object o) {
				if (this == o) return true;
				if (o == null || getClass() != o.getClass()) return false;
				Node node = (Node) o;
				return map.name.equals(node.map.name) && mapchange.id.equals(node.mapchange.id);
			}

			@Override
			public int hashCode() {
				return map.name.hashCode() * 31 + mapchange.id.hashCode();
			}
		}

		/**
		 * Helper class for the PriorityQueue to store nodes along with their current cumulative distance.
		 */
		class NodeDistance {
			final Node node;
			final int dist;
			NodeDistance(Node node, int dist) {
				this.node = node;
				this.dist = dist;
			}
		}

		// Dijkstra's algorithm structures
		final Map<Node, Integer> distances = new HashMap<>(); // Shortest known distance to each map exit
		final Map<Node, Node> previous = new HashMap<>();     // Used for backtracking to reconstruct the path
		// Nodes whose current shortest-known route arrives via a same-map warp crossing
		// (Relaxation Step 2, e.g. a "west edge" teleport pair) rather than a normal walk
		// (Relaxation Step 1). Such a node cannot be walked to directly - reconstruction must not
		// silently fold it into an earlier leg the way it safely can for a walk-connected node.
		final Set<Node> viaCrossing = new HashSet<>();
		PriorityQueue<NodeDistance> pq = new PriorityQueue<>(Comparator.comparingInt(n -> n.dist));

		// Initial seeding: find all exits on the starting map that are reachable from the current position.
		PredefinedMap fromMap = world.maps.findPredefinedMap(fromMapName);
		for (MapObject o : fromMap.eventObjects) {
			if (o.type == MapObject.MapObjectType.newmap) {
				// Use the map's local pathfinder to calculate distance to each exit. Read
				// getLastPathDistance() instead of gating on the boolean return, so an exit whose
				// area the traveler's starting position already overlaps (distance 0, findPathBetween
				// returns false the same as "unreachable") still gets seeded instead of silently
				// dropped from the search.
				fromMap.pathfinder.findPathBetween(fromPosition, o.position, new CoordRect(new Size(1, 1)));
				int d = fromMap.pathfinder.getLastPathDistance();
				if (d >= 0) {
					Node node = new Node(fromMap, o);
					distances.put(node, d);
					pq.add(new NodeDistance(node, d));
				}
			}
		}

		// Pre-populate all other map exits in the world with "infinite" distance.
		for (PredefinedMap m : world.maps.getAllMaps()) {
			for (MapObject o : m.eventObjects) {
				if (o.type == MapObject.MapObjectType.newmap) {
					Node node = new Node(m, o);
					if (!distances.containsKey(node)) {
						distances.put(node, Integer.MAX_VALUE);
					}
				}
			}
		}

		Node targetNode = null;
		int totalDistance = -1;
		int bestDistanceToTargetMap = Integer.MAX_VALUE;

		// Main Dijkstra loop
		while (!pq.isEmpty()) {
			NodeDistance top = pq.poll();
			Node u = top.node;
			int uDist = top.dist;

			// If the closest node is unreachable, the search is finished.
			if (uDist == Integer.MAX_VALUE) break;
			// If we've already found a shorter way to this node, skip it.
			if (uDist > distances.get(u)) continue;

			// Check if this exit leads into the destination map.
			if (toMapName.equals(u.mapchange.map)) {
				PredefinedMap targetMap = world.maps.findPredefinedMap(toMapName);
				// Find the specific point where we enter the target map from this exit.
				MapObject entryPoint = targetMap.findEventObject(MapObject.MapObjectType.newmap, u.mapchange.place);
				if (entryPoint != null) {
					// Calculate distance from that entry point to the final destination area. As
					// above, read getLastPathDistance() rather than gating on the boolean return -
					// otherwise a destination area that overlaps this map's entry point (a
					// trivially-short final leg) would look "unreachable" via this entry and get
					// skipped, possibly leaving the whole journey unreachable if every candidate
					// entry point has the same property.
					targetMap.pathfinder.findPathBetween(entryPoint.position, toPosition, new CoordRect(new Size(1, 1)));
					int dToDest = targetMap.pathfinder.getLastPathDistance();
					if (dToDest >= 0) {
						int total = uDist + dToDest;
						// Keep track of the best entry point into the final map.
						if (total < bestDistanceToTargetMap) {
							bestDistanceToTargetMap = total;
							targetNode = u;
							totalDistance = total;
						}
					}
				}
			}

			// Relaxation Step 1: Traveling within the same map to other exits.
			// These connections use the pre-calculated distanceMatrix for speed.
			for (MapObject vObj : u.map.eventObjects) {
				if (vObj.type != MapObject.MapObjectType.newmap) continue;
				if (vObj == u.mapchange) continue;

				int d = u.map.getDistance(u.mapchange.id, vObj.id);
				if (d < 0) continue; // No traversable path between these two exits on this map.

				Node v = new Node(u.map, vObj);
				int alt = uDist + d;
				if (alt < distances.get(v)) {
					distances.put(v, alt);
					previous.put(v, u);
					viaCrossing.remove(v); // this route to v is a walk, superseding any earlier crossing-based route
					pq.add(new NodeDistance(v, alt));
				}
			}

			// Relaxation Step 2: Crossing through an exit to the next map.
			// Transitions between maps are currently considered instantaneous (distance 0).
			if (u.mapchange.map != null) {
				PredefinedMap nextMap = world.maps.findPredefinedMap(u.mapchange.map);
				if (nextMap != null) {
					// Find the matching "entry" exit on the destination map.
					for (MapObject vObj : nextMap.eventObjects) {
						if (vObj.type != MapObject.MapObjectType.newmap) continue;
						if (u.mapchange.place != null && u.mapchange.place.equals(vObj.id)) {
							Node v = new Node(nextMap, vObj);
							Integer vDist = distances.get(v);
							if (vDist != null && uDist < vDist) {
								distances.put(v, uDist);
								previous.put(v, u);
								viaCrossing.add(v);
								pq.add(new NodeDistance(v, uDist));
							}
							break;
						}
					}
				}
			}
		}

		// If no path was found to the target map.
		if (targetNode == null) return new GlobalPath(new ArrayList<GlobalPath.GlobalPathEntry>(), fromPosition.topLeft, System.currentTimeMillis(), -1);

		// Path Reconstruction
		List<GlobalPath.GlobalPathEntry> path = new ArrayList<>();

		// Add the final leg: from the last map transition to the destination.
		path.add(new GlobalPath.GlobalPathEntry(toMapName, destinationID, totalDistance - distances.get(targetNode), totalDistance));

		// Backtrack through the 'previous' nodes to find the sequence of map transitions.
		Node curr = targetNode;
		while (curr != null) {
			Node p = previous.get(curr);
			// Skip redundant nodes connected by a plain same-map walk to keep the path concise -
			// local pathfinding reaches them automatically while walking toward `curr`. Stop as
			// soon as the node we'd skip past was itself reached via a same-map warp crossing: the
			// far side of a warp cannot be walked to directly, so it must remain its own leg
			// boundary rather than being silently absorbed into this one.
			while (p != null && p.map.name.equals(curr.map.name) && !viaCrossing.contains(p)) {
				p = previous.get(p);
			}

			Integer dCurr = distances.get(curr);
			int dist = (dCurr == null) ? 0 : dCurr;
			if (p != null) {
				Integer dP = distances.get(p);
				if (dP != null) dist -= dP;
			}

			path.add(new GlobalPath.GlobalPathEntry(curr.map.name, curr.mapchange.id, dist, (dCurr == null) ? 0 : dCurr));

			if (p != null && viaCrossing.contains(p)) {
				// `p` is itself an unwalkable warp destination - the next leg backward must
				// target where that warp actually departs from, not `p` itself.
				curr = previous.get(p);
			} else {
				curr = p;
			}
		}
		// The path is backtracked from finish to start, so reverse it for the correct order.
		Collections.reverse(path);

		if (MonsterMovementController.showTravelDebug) {
			L.log("PATHFINDER: found path: " + path);
		}
		return new GlobalPath(path, fromPosition.topLeft, System.currentTimeMillis(), totalDistance);
	}

	/**
	 * Represents a sequence of map transitions to reach a distant destination.
	 */
	public static class GlobalPath {
		public final List<GlobalPathEntry> path; // Sequence of map legs.
		public final Coord startingPosition;    // Starting position of the journey.
		public int currentPosition = 0;          // Current step in the journey.
		public long startTime;             // System.currentTimeMillis() when travel started.
		/**
		 * Estimated total distance/cost. Not final: an on-screen travel pause (R5's resting; R6
		 * generalized this to any future pause reason) mutates this in place (increasing it by 10 per
		 * paused tick, the same distance-per-move convention used everywhere else) to keep the
		 * off-screen wall-clock model in sync with real elapsed time - see
		 * MonsterMovementController.correctTravelPathForPause.
		 */
		public int predictedTime;

		public GlobalPath(List<GlobalPathEntry> path, Coord startingPosition, long startTime, int predictedTime) {
			this.path = path;
			// Defensive copy: every caller passes some actor's live rectPosition.topLeft here
			// (e.g. Monster.rectPosition is built as `new CoordRect(this.position, ...)`, aliasing
			// the same Coord instance - CoordRect's constructor stores it by reference, not by
			// value). Without copying, startingPosition would silently track the monster's current
			// position forever instead of freezing where the journey began, which breaks leg-0
			// distance/position math (getLegStartArea) for the entire lifetime of this path.
			this.startingPosition = new Coord(startingPosition);
			this.startTime = startTime;
			this.predictedTime = predictedTime;
		}

		/**
		 * @return The next transition point the traveler is heading towards.
		 */
		public GlobalPathEntry getNextDestination() {
			if (currentPosition >= path.size()) return null;
			return path.get(currentPosition);
		}

		// ====== PARCELABLE ===================================================================

		public void writeToParcel(DataOutputStream dest) throws IOException {
			dest.writeInt(path.size());
			for (GlobalPathEntry e : path) {
				dest.writeUTF(e.mapID);
				dest.writeUTF(e.destinationID);
				dest.writeInt(e.distance);
				dest.writeInt(e.cumulatedDistance);
			}
			startingPosition.writeToParcel(dest);
			dest.writeInt(currentPosition);
			dest.writeLong(startTime);
			dest.writeInt(predictedTime);
		}

		public static GlobalPath newFromParcel(DataInputStream src, int fileversion) throws IOException {
			int numEntries = src.readInt();
			List<GlobalPathEntry> path = new ArrayList<>(numEntries);
			for (int i = 0; i < numEntries; i++) {
				path.add(new GlobalPathEntry(src.readUTF(), src.readUTF(), src.readInt(), src.readInt()));
			}
			Coord startingPosition = new Coord(src, fileversion);
			int currentPosition = src.readInt();
			long startTime = src.readLong();
			int predictedTime = src.readInt();

			GlobalPath result = new GlobalPath(path, startingPosition, startTime, predictedTime);
			result.currentPosition = currentPosition;
			return result;
		}

		/**
		 * A single leg of a journey, defined by the target map and the exit to reach on it.
		 */
		public static class GlobalPathEntry {
			public final String mapID;         // Name of the map for this leg.
			public final String destinationID; // ID of the MapChange or Area to reach.
			public final int distance;         // Distance cost of this specific leg.
			/**
			 * Cumulated distance cost from start to this leg. Not final: an on-screen travel pause
			 * (R5's resting; R6 generalized this to any future pause reason) increases this in place
			 * (on the current leg and every later one - never an already-completed leg, never
			 * `distance` itself, which stays the leg's true physical length) so the off-screen
			 * wall-clock model's leg-boundary thresholds correctly grow to account for real time spent
			 * paused - see MonsterMovementController.correctTravelPathForPause.
			 */
			public int cumulatedDistance;

			public GlobalPathEntry(String mapID, String destinationID, int distance, int cumulatedDistance) {
				this.mapID = mapID;
				this.destinationID = destinationID;
				this.distance = distance;
				this.cumulatedDistance = cumulatedDistance;
			}
		}
	}
}
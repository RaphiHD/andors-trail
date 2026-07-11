package com.gpl.rpg.AndorsTrail.controller;

import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.model.map.MapObject;
import com.gpl.rpg.AndorsTrail.model.map.PredefinedMap;
import com.gpl.rpg.AndorsTrail.util.Coord;
import com.gpl.rpg.AndorsTrail.util.CoordRect;
import com.gpl.rpg.AndorsTrail.util.L;
import com.gpl.rpg.AndorsTrail.util.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

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
		L.log("PATHFINDER: finding path between " + fromMapName + " and " + toMapName);

		// Basic validation: pathfinding requires both start and end maps to exist.
		if (fromMapName == null || toMapName == null) return new GlobalPath(new ArrayList<>(), fromPosition.topLeft, world.model.worldData.getWorldTime(), 0);

		// Optimization: If already on the target map, perform local pathfinding. TODO: there is not always a local path -> still do global!
		if (fromMapName.equals(toMapName)) {
			PredefinedMap map = world.maps.findPredefinedMap(fromMapName);
			if (map.pathfinder.findPathBetween(fromPosition, toPosition, new CoordRect(new Size(1, 1)))) {
				int d = map.pathfinder.getLastPathDistance();
				List<GlobalPath.GlobalPathEntry> path = new ArrayList<>();
		// Path consists of a single leg: staying on this map to reach the destination.
		path.add(new GlobalPath.GlobalPathEntry(fromMapName, destinationID, d, d));
		return new GlobalPath(path, fromPosition.topLeft, world.model.worldData.getWorldTime(), d);
	}
	return new GlobalPath(new ArrayList<GlobalPath.GlobalPathEntry>(), fromPosition.topLeft, world.model.worldData.getWorldTime(), -1);
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
		PriorityQueue<NodeDistance> pq = new PriorityQueue<>(Comparator.comparingInt(n -> n.dist));

		// Initial seeding: find all exits on the starting map that are reachable from the current position.
		PredefinedMap fromMap = world.maps.findPredefinedMap(fromMapName);
		for (MapObject o : fromMap.eventObjects) {
			if (o.type == MapObject.MapObjectType.newmap) {
				// Use the map's local pathfinder to calculate distance to each exit.
				if (fromMap.pathfinder.findPathBetween(fromPosition, o.position, new CoordRect(new Size(1, 1)))) {
					int d = fromMap.pathfinder.getLastPathDistance();
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
					// Calculate distance from that entry point to the final destination area.
					if (targetMap.pathfinder.findPathBetween(entryPoint.position, toPosition, new CoordRect(new Size(1, 1)))) {
						int dToDest = targetMap.pathfinder.getLastPathDistance();
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
								pq.add(new NodeDistance(v, uDist));
							}
							break;
						}
					}
				}
			}
		}

		// If no path was found to the target map.
		if (targetNode == null) return new GlobalPath(new ArrayList<GlobalPath.GlobalPathEntry>(), fromPosition.topLeft, world.model.worldData.getWorldTime(), -1);

		// Path Reconstruction
		List<GlobalPath.GlobalPathEntry> path = new ArrayList<>();

		// Add the final leg: from the last map transition to the destination.
		path.add(new GlobalPath.GlobalPathEntry(toMapName, destinationID, totalDistance - distances.get(targetNode), totalDistance));

		// Backtrack through the 'previous' nodes to find the sequence of map transitions.
		Node curr = targetNode;
		while (curr != null) {
			Node p = previous.get(curr);
			// Skip redundant nodes on the same map to keep the path concise.
			while (p != null && p.map.name.equals(curr.map.name)) {
				p = previous.get(p);
			}

			Integer dCurr = distances.get(curr);
			int dist = (dCurr == null) ? 0 : dCurr;
			if (p != null) {
				Integer dP = distances.get(p);
				if (dP != null) dist -= dP;
			}

			path.add(new GlobalPath.GlobalPathEntry(curr.map.name, curr.mapchange.id, dist, (dCurr == null) ? 0 : dCurr));
			curr = p;
		}
		// The path is backtracked from finish to start, so reverse it for the correct order.
		Collections.reverse(path);

		L.log("PATHFINDER: found path: " + path);
		return new GlobalPath(path, fromPosition.topLeft, world.model.worldData.getWorldTime(), totalDistance);
	}

	/**
	 * Represents a sequence of map transitions to reach a distant destination.
	 */
	public static class GlobalPath {
		public final List<GlobalPathEntry> path; // Sequence of map legs.
		public final Coord startingPosition;    // Starting position of the journey.
		public int currentPosition = 0;          // Current step in the journey.
		public final long startTime;             // Game round when travel started.
		public final int predictedTime;          // Estimated total distance/cost.

		public GlobalPath(List<GlobalPathEntry> path, Coord startingPosition, long startTime, int predictedTime) {
			this.path = path;
			this.startingPosition = startingPosition;
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

		/**
		 * A single leg of a journey, defined by the target map and the exit to reach on it.
		 */
		public static class GlobalPathEntry {
			public final String mapID;         // Name of the map for this leg.
			public final String destinationID; // ID of the MapChange or Area to reach.
			public final int distance;         // Distance cost of this specific leg.
			public final int cumulatedDistance; // Cumulated distance cost from start to this leg.

			public GlobalPathEntry(String mapID, String destinationID, int distance, int cumulatedDistance) {
				this.mapID = mapID;
				this.destinationID = destinationID;
				this.distance = distance;
				this.cumulatedDistance = cumulatedDistance;
			}
		}
	}
}
package com.gpl.rpg.AndorsTrail.controller;

import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.model.map.MapObject;
import com.gpl.rpg.AndorsTrail.model.map.PredefinedMap;
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

public class GlobalPathFinder {
	private final WorldContext world;

	public GlobalPathFinder(WorldContext world) {
		this.world = world;
	}

	public GlobalPath findPath(String fromMapName, CoordRect fromPosition, String toMapName, CoordRect toPosition, String destinationID) {
		L.log("PATHFINDER: finding path between " + fromMapName + " and " + toMapName);

		if (fromMapName == null || toMapName == null) return new GlobalPath(new ArrayList<>(), world.model.worldData.getWorldTime(), 0);

		if (fromMapName.equals(toMapName)) {
			PredefinedMap map = world.maps.findPredefinedMap(fromMapName);
			if (map.pathfinder.findPathBetween(fromPosition, toPosition, new CoordRect(new Size(1, 1)))) {
				int d = map.pathfinder.getLastPathDistance();
				List<GlobalPath.GlobalPathEntry> path = new ArrayList<>();
				path.add(new GlobalPath.GlobalPathEntry(fromMapName, destinationID, d));
				return new GlobalPath(path, world.model.worldData.getWorldTime(), d);
			}
			return new GlobalPath(new ArrayList<>(), -1,-1);
		}

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

		class NodeDistance {
			final Node node;
			final int dist;
			NodeDistance(Node node, int dist) {
				this.node = node;
				this.dist = dist;
			}
		}

		final Map<Node, Integer> distances = new HashMap<>();
		final Map<Node, Node> previous = new HashMap<>();
		PriorityQueue<NodeDistance> pq = new PriorityQueue<>(Comparator.comparingInt(n -> n.dist));

		PredefinedMap fromMap = world.maps.findPredefinedMap(fromMapName);
		for (MapObject o : fromMap.eventObjects) {
			if (o.type == MapObject.MapObjectType.newmap) {
				if (fromMap.pathfinder.findPathBetween(fromPosition, o.position, new CoordRect(new Size(1, 1)))) {
					int d = fromMap.pathfinder.getLastPathDistance();
					Node node = new Node(fromMap, o);
					distances.put(node, d);
					pq.add(new NodeDistance(node, d));
				}
			}
		}

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

		while (!pq.isEmpty()) {
			NodeDistance top = pq.poll();
			Node u = top.node;
			int uDist = top.dist;

			if (uDist == Integer.MAX_VALUE) break;
			if (uDist > distances.get(u)) continue;

			if (toMapName.equals(u.mapchange.map)) {
				PredefinedMap targetMap = world.maps.findPredefinedMap(toMapName);
				MapObject entryPoint = targetMap.findEventObject(MapObject.MapObjectType.newmap, u.mapchange.place);
				if (entryPoint != null) {
					if (targetMap.pathfinder.findPathBetween(entryPoint.position, toPosition, new CoordRect(new Size(1, 1)))) {
						int dToDest = targetMap.pathfinder.getLastPathDistance();
						int total = uDist + dToDest;
						if (total < bestDistanceToTargetMap) {
							bestDistanceToTargetMap = total;
							targetNode = u;
							totalDistance = total;
						}
					}
				}
			}

			// Same map: mapchanges are connected via distanceMatrix
			for (MapObject vObj : u.map.eventObjects) {
				if (vObj.type != MapObject.MapObjectType.newmap) continue;
				if (vObj == u.mapchange) continue;

				int d = u.map.getDistance(u.mapchange.id, vObj.id);
				if (d < 0) continue;

				Node v = new Node(u.map, vObj);
				int alt = uDist + d;
				if (alt < distances.get(v)) {
					distances.put(v, alt);
					previous.put(v, u);
					pq.add(new NodeDistance(v, alt));
				}
			}

			// Across mapchange: transition to next map (distance 0)
			if (u.mapchange.map != null) {
				PredefinedMap nextMap = world.maps.findPredefinedMap(u.mapchange.map);
				if (nextMap != null) {
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

		if (targetNode == null) return new GlobalPath(new ArrayList<>(), world.model.worldData.getWorldTime(), -1);

		List<GlobalPath.GlobalPathEntry> path = new ArrayList<>();

		// Final map entry
		path.add(new GlobalPath.GlobalPathEntry(toMapName, destinationID, totalDistance - distances.get(targetNode)));

		Node curr = targetNode;
		while (curr != null) {
			Node p = previous.get(curr);
			while (p != null && p.map.name.equals(curr.map.name)) {
				p = previous.get(p);
			}
			Integer dCurr = distances.get(curr);
			int dist = (dCurr == null) ? 0 : dCurr;
			if (p != null) {
				Integer dP = distances.get(p);
				if (dP != null) dist -= dP;
			}

			path.add(new GlobalPath.GlobalPathEntry(curr.map.name, curr.mapchange.id, dist));
			curr = p;
		}
		Collections.reverse(path);



		L.log("PATHFINDER: found path: " + path);
		return new GlobalPath(path, world.model.worldData.getWorldTime(), totalDistance);
	}

	public static class GlobalPath {
		public final List<GlobalPathEntry> path;
		public int currentPosition = 0;
		public final long startTime;
		public final int predictedTime;

		public GlobalPath(List<GlobalPathEntry> path, long startTime, int predictedTime) {
			this.path = path;
			this.startTime = startTime;
			this.predictedTime = predictedTime;
		}

		public GlobalPathEntry getNextDestination() {
			if (currentPosition >= path.size()) return null;
			return path.get(currentPosition);
		}

		public static class GlobalPathEntry {
			public final String mapID;
			public final String destinationID;
			public final int distance;

			public GlobalPathEntry(String mapID, String destinationID, int distance) {
				this.mapID = mapID;
				this.destinationID = destinationID;
				this.distance = distance;
			}
		}
	}
}

package com.gpl.rpg.AndorsTrail.controller;

import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.model.map.MapObject;
import com.gpl.rpg.AndorsTrail.model.map.PredefinedMap;
import com.gpl.rpg.AndorsTrail.util.L;

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

	public GlobalPath findPath(String fromMapName, String toMapName) {
		L.log("PATHFINDER: finding path between " + fromMapName + " and " + toMapName);

		if (fromMapName == null || toMapName == null || fromMapName.equals(toMapName)) {
			L.log("PATHFINDER: from and to are on the same map");
			return new GlobalPath(new ArrayList<>());
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

		for (PredefinedMap m : world.maps.getAllMaps()) {
			for (MapObject o : m.eventObjects) {
				if (o.type == MapObject.MapObjectType.newmap) {
					Node node = new Node(m, o);
					if (m.name.equals(fromMapName)) {
						distances.put(node, 0);
						pq.add(new NodeDistance(node, 0));
					} else {
						distances.put(node, Integer.MAX_VALUE);
					}
				}
			}
		}

		Node targetNode = null;
		while (!pq.isEmpty()) {
			NodeDistance top = pq.poll();
			Node u = top.node;
			int uDist = top.dist;

			if (uDist == Integer.MAX_VALUE) break;
			if (uDist > distances.get(u)) continue;

			if (u.mapchange.map.equals(toMapName)) {
				targetNode = u;
				break;
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
			PredefinedMap nextMap = world.maps.findPredefinedMap(u.mapchange.map);
			if (nextMap != null) {
				for (MapObject vObj : nextMap.eventObjects) {
					if (vObj.type != MapObject.MapObjectType.newmap) continue;
					if (vObj.id.equals(u.mapchange.place)) {
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

		if (targetNode == null) return new GlobalPath(new ArrayList<>());

		List<String> path = new ArrayList<>();
		Node curr = targetNode;
		while (curr != null) {
			path.add(curr.mapchange.id);
			Node p = previous.get(curr);
			while (p != null && p.map.name.equals(curr.map.name)) {
				p = previous.get(p);
			}
			curr = p;
		}
		Collections.reverse(path);

		L.log("PATHFINDER: found path: " + path);
		return new GlobalPath(path);
	}

	public static class GlobalPath {
		public final List<String> path;
		public int currentPosition = 0;

		public GlobalPath(List<String> path) {
			this.path = path;
		}

		public String getNextDestination() {
			if (currentPosition >= path.size()) return null;
			return path.get(currentPosition);
		}
	}
}

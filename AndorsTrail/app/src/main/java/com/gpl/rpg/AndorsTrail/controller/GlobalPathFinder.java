package com.gpl.rpg.AndorsTrail.controller;

import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.model.map.MapObject;
import com.gpl.rpg.AndorsTrail.model.map.PredefinedMap;

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
		if (fromMapName == null || toMapName == null || fromMapName.equals(toMapName)) {
			return new GlobalPath(new ArrayList<String>());
		}

		class Node {
			final PredefinedMap map;
			final MapObject portal;

			Node(PredefinedMap map, MapObject portal) {
				this.map = map;
				this.portal = portal;
			}

			@Override
			public boolean equals(Object o) {
				if (this == o) return true;
				if (o == null || getClass() != o.getClass()) return false;
				Node node = (Node) o;
				return map.name.equals(node.map.name) && portal.id.equals(node.portal.id);
			}

			@Override
			public int hashCode() {
				return map.name.hashCode() * 31 + portal.id.hashCode();
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
		PriorityQueue<NodeDistance> pq = new PriorityQueue<>(new Comparator<NodeDistance>() {
			@Override
			public int compare(NodeDistance n1, NodeDistance n2) {
				return Integer.compare(n1.dist, n2.dist);
			}
		});

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

			if (u.portal.map.equals(toMapName)) {
				targetNode = u;
				break;
			}

			// Same map: portals are connected via distanceMatrix
			for (MapObject vObj : u.map.eventObjects) {
				if (vObj.type != MapObject.MapObjectType.newmap) continue;
				if (vObj == u.portal) continue;

				int d = u.map.getDistance(u.portal.id, vObj.id);
				if (d < 0) continue;

				Node v = new Node(u.map, vObj);
				int alt = uDist + d;
				if (alt < distances.get(v)) {
					distances.put(v, alt);
					previous.put(v, u);
					pq.add(new NodeDistance(v, alt));
				}
			}

			// Across portal: transition to next map (distance 0)
			PredefinedMap nextMap = world.maps.findPredefinedMap(u.portal.map);
			if (nextMap != null) {
				for (MapObject vObj : nextMap.eventObjects) {
					if (vObj.type != MapObject.MapObjectType.newmap) continue;
					if (vObj.id.equals(u.portal.place)) {
						Node v = new Node(nextMap, vObj);
						int alt = uDist;
						Integer vDist = distances.get(v);
						if (vDist != null && alt < vDist) {
							distances.put(v, alt);
							previous.put(v, u);
							pq.add(new NodeDistance(v, alt));
						}
						break;
					}
				}
			}
		}

		if (targetNode == null) return new GlobalPath(new ArrayList<String>());

		List<String> path = new ArrayList<>();
		Node curr = targetNode;
		while (curr != null) {
			path.add(curr.portal.id);
			Node p = previous.get(curr);
			while (p != null && p.map.name.equals(curr.map.name)) {
				p = previous.get(p);
			}
			curr = p;
		}
		Collections.reverse(path);
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
			return path.get(currentPosition++);
		}
	}
}

package com.gpl.rpg.AndorsTrail.controller;

import java.util.Arrays;

import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.model.map.PredefinedMap;
import com.gpl.rpg.AndorsTrail.util.Coord;
import com.gpl.rpg.AndorsTrail.util.CoordRect;
import com.gpl.rpg.AndorsTrail.util.Size;

public class PathFinder {
	private final int maxWidth;
	private final int maxHeight;
	private final boolean[] visited;
	private final int[] gScore;
	private final OpenSetHeap openSet;
	private final PredefinedMap map;
	private int lastPathDistance = -1;

	public int getLastPathDistance() { return lastPathDistance; }

	public PathFinder(int maxWidth, int maxHeight, PredefinedMap map) {
		this.maxWidth = maxWidth;
		this.maxHeight = maxHeight;
		this.map = map;
		this.visited = new boolean[maxWidth*maxHeight];
		this.gScore = new int[maxWidth*maxHeight];
		this.openSet = new OpenSetHeap(maxWidth*maxHeight);
	}

	public boolean findPathBetween(final CoordRect from, final Coord to, CoordRect nextStep) {
		return findPathBetween(from, new CoordRect(to, new Size(1, 1)), nextStep, null);
	}
	public boolean findPathBetween(final CoordRect from, final Coord to, CoordRect nextStep, Monster m) {
		return findPathBetween(from, new CoordRect(to, new Size(1, 1)), nextStep, m);
	}

	public boolean findPathBetween(final CoordRect from, final CoordRect to, CoordRect nextStep, Monster m) {
		lastPathDistance = -1;
		if (from.intersects(to)) {
			lastPathDistance = 0;
			return false;
		}

		int iterations = 0;
		Coord measureDistanceTo = from.topLeft;
		Coord curr = nextStep.topLeft;

		Arrays.fill(visited, false);
		Arrays.fill(gScore, Integer.MAX_VALUE / 4);
		openSet.clear();

		// seed open set with all reachable tiles in `to` rectangle
		for (int y = to.topLeft.y; y < to.topLeft.y + to.size.height; ++y) {
			if (y < 0 || y >= maxHeight) continue;
			for (int x = to.topLeft.x; x < to.topLeft.x + to.size.width; ++x) {
				if (x < 0 || x >= maxWidth) continue;
				int i = (y * maxWidth) + x;
				nextStep.topLeft.x = x; nextStep.topLeft.y = y;
				if (m != null && !map.isWalkable(nextStep, m)) continue;
				else if (!map.isWalkable(nextStep, true)) continue;
				gScore[i] = 0;
				int h = heuristic(measureDistanceTo.x, measureDistanceTo.y, x, y);
				openSet.add(x, y, h);
			}
		}
		if (openSet.isEmpty()) return false;

		while (!openSet.isEmpty()) {
			openSet.pop();
			if (++iterations > 500) return false;
			int cx = openSet.px; int cy = openSet.py;
			int ci = (cy * maxWidth) + cx;
			if (visited[ci]) continue;
			visited[ci] = true;

			curr.x = cx; curr.y = cy;
			if (from.isAdjacentTo(curr)) {
				Coord closest = from.findPositionAdjacentTo(curr);
				int dx = Math.abs(cx - closest.x);
				int dy = Math.abs(cy - closest.y);
				int moveCost = (dx == 0 || dy == 0) ? 10 : 14;
				lastPathDistance = gScore[ci] + moveCost;
				return true;
			}

			// explore neighbours (8-way)
			for (int dy = -1; dy <= 1; ++dy) {
				for (int dx = -1; dx <= 1; ++dx) {
					if (dx == 0 && dy == 0) continue;
					int nx = cx + dx; int ny = cy + dy;
					if (nx < 0 || ny < 0 || nx >= maxWidth || ny >= maxHeight) continue;
					int ni = (ny * maxWidth) + nx;
					if (visited[ni]) continue;

					// check walkable using nextStep as scratch
					nextStep.topLeft.x = nx; nextStep.topLeft.y = ny;
					if (m != null && !map.isWalkable(nextStep, m)) continue;
					else if (!map.isWalkable(nextStep, true)) continue;

					int moveCost = (dx == 0 || dy == 0) ? 10 : 14;
					int tentativeG = gScore[ci] + moveCost;
					if (tentativeG < gScore[ni]) {
						gScore[ni] = tentativeG;
						int h = heuristic(measureDistanceTo.x, measureDistanceTo.y, nx, ny);
						openSet.add(nx, ny, tentativeG + h);
					}
				}
			}
		}
		return false;
	}

	/** Octile heuristic tuned to move costs orth=10 diag=14 (integers) */
	private static int heuristic(int ax, int ay, int bx, int by) {
		int dx = Math.abs(ax - bx);
		int dy = Math.abs(ay - by);
		int max = Math.max(dx, dy);
		int min = Math.min(dx, dy);
		return 10 * max + 4 * min;
	}

	/** Minimal primitive binary heap for open set (stores x,y,f) */
	private static final class OpenSetHeap {
		private final int[] hx;
		private final int[] hy;
		private final int[] hf;
		private int size = 0;
		public int px, py; // last popped coordinates

		public OpenSetHeap(int capacity) {
			this.hx = new int[capacity+1];
			this.hy = new int[capacity+1];
			this.hf = new int[capacity+1];
		}

		public void clear() { size = 0; }
		public boolean isEmpty() { return size == 0; }

		public void add(int x, int y, int f) {
			int i = ++size;
			hx[i] = x; hy[i] = y; hf[i] = f;
			// sift up
			while (i > 1) {
				int parent = i >> 1;
				if (hf[parent] <= hf[i]) break;
				swap(i, parent);
				i = parent;
			}
		}

		private void swap(int a, int b) {
			int tx = hx[a]; hx[a] = hx[b]; hx[b] = tx;
			int ty = hy[a]; hy[a] = hy[b]; hy[b] = ty;
			int tf = hf[a]; hf[a] = hf[b]; hf[b] = tf;
		}

		public void pop() {
			if (size == 0) return;
			px = hx[1]; py = hy[1];
			hx[1] = hx[size]; hy[1] = hy[size]; hf[1] = hf[size];
			--size;
			// sift down
			int i = 1;
			while (true) {
				int left = i << 1;
				if (left > size) break;
				int right = left + 1;
				int smallest = left;
				if (right <= size && hf[right] < hf[left]) smallest = right;
				if (hf[i] <= hf[smallest]) break;
				swap(i, smallest);
				i = smallest;
			}
		}
	}
}

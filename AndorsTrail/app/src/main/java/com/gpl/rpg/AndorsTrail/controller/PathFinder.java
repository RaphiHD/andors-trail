package com.gpl.rpg.AndorsTrail.controller;

import java.util.Arrays;

import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.util.Coord;
import com.gpl.rpg.AndorsTrail.util.CoordRect;

public class PathFinder {
	private final int maxWidth;
	private final int maxHeight;
	private final boolean visited[];
	private final int gScore[];
	private final OpenSetHeap openSet;
	private final EvaluateWalkable map;

	public PathFinder(int maxWidth, int maxHeight, EvaluateWalkable map) {
		this.maxWidth = maxWidth;
		this.maxHeight = maxHeight;
		this.map = map;
		this.visited = new boolean[maxWidth*maxHeight];
		this.gScore = new int[maxWidth*maxHeight];
		this.openSet = new OpenSetHeap(maxWidth*maxHeight);
	}

	public interface EvaluateWalkable {
		public boolean isWalkable(CoordRect r, Monster m);
	}

	public boolean findPathBetween(final CoordRect from, final CoordRect to, CoordRect nextStep, Monster m) {
		int iterations = 0;
		if (from.intersects(to)) return false;
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
				if (!map.isWalkable(nextStep, m)) continue;
				gScore[i] = 0;
				int h = heuristic(measureDistanceTo.x, measureDistanceTo.y, x, y);
				openSet.add(x, y, h);
			}
		}
		if (openSet.isEmpty()) return false;

		while (!openSet.isEmpty()) {
			openSet.pop();
			++iterations;
			if (iterations > 500) return false;
			int cx = openSet.px; int cy = openSet.py;
			int ci = (cy * maxWidth) + cx;
			if (visited[ci]) continue;
			visited[ci] = true;

			curr.x = cx; curr.y = cy;
			if (from.isAdjacentTo(curr)) return true;

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
					if (!map.isWalkable(nextStep, m)) continue;

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

	public boolean findPathBetween(final CoordRect from, final Coord to, CoordRect nextStep, Monster m) {
		int iterations = 0;
		if (from.contains(to)) return false;
		Coord measureDistanceTo = from.topLeft;
		Coord curr = nextStep.topLeft;

		Arrays.fill(visited, false);
		Arrays.fill(gScore, Integer.MAX_VALUE / 4);
		openSet.clear();

		int ti = (to.y * maxWidth) + to.x;
		if (to.x >= 0 && to.x < maxWidth && to.y >= 0 && to.y < maxHeight) {
			nextStep.topLeft.x = to.x; nextStep.topLeft.y = to.y;
			if (map.isWalkable(nextStep, m)) {
				gScore[ti] = 0;
				int h = heuristic(measureDistanceTo.x, measureDistanceTo.y, to.x, to.y);
				openSet.add(to.x, to.y, h);
			}
		}
		if (openSet.isEmpty()) return false;

		while (!openSet.isEmpty()) {
			openSet.pop();
			++iterations;
			if (iterations > 500) return false;

			int cx = openSet.px; int cy = openSet.py;
			int ci = (cy * maxWidth) + cx;
			if (visited[ci]) continue;
			visited[ci] = true;

			curr.x = cx; curr.y = cy;
			if (from.isAdjacentTo(curr)) return true;

			for (int dy = -1; dy <= 1; ++dy) {
				for (int dx = -1; dx <= 1; ++dx) {
					if (dx == 0 && dy == 0) continue;
					int nx = cx + dx; int ny = cy + dy;
					if (nx < 0 || ny < 0 || nx >= maxWidth || ny >= maxHeight) continue;
					int ni = (ny * maxWidth) + nx;
					if (visited[ni]) continue;

					nextStep.topLeft.x = nx; nextStep.topLeft.y = ny;
					if (!map.isWalkable(nextStep, m)) continue;

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

	private void visit(CoordRect r, Coord measureDistanceTo, Monster m) {
		final int x = r.topLeft.x;
		final int y = r.topLeft.y;

		if (x < 0) return;
		if (y < 0) return;
		if (x >= maxWidth) return;
		if (y >= maxHeight) return;

		final int i = (y * maxWidth) + x;
		if (visited[i]) return;
		visited[i] = true;
		if (!map.isWalkable(r, m)) return;

		int dx = Math.abs(measureDistanceTo.x - x);
		int dy = Math.abs(measureDistanceTo.y - y);
		int h = heuristicFromD(dx, dy);
		int gi = (y * maxWidth) + x;
		if (gScore[gi] > Integer.MAX_VALUE / 8) gScore[gi] = Integer.MAX_VALUE / 4;
		openSet.add(x, y, gScore[gi] + h);
	}
	/** Octile heuristic tuned to move costs orth=10 diag=14 (integers) */
	private static int heuristic(int ax, int ay, int bx, int by) {
		int dx = Math.abs(ax - bx);
		int dy = Math.abs(ay - by);
		return heuristicFromD(dx, dy);
	}
	private static int heuristicFromD(int dx, int dy) {
		int max = Math.max(dx, dy);
		int min = Math.min(dx, dy);
		return 10 * max + 4 * min; // equals 10*(dx+dy) - 6*min
	}

	/** Minimal primitive binary heap for open set (stores x,y,f) */
	private static final class OpenSetHeap {
		private final int hx[];
		private final int hy[];
		private final int hf[];
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
			hf[size] = 0; hx[size] = 0; hy[size] = 0;
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

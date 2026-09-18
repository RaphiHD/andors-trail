package com.gpl.rpg.AndorsTrail.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.model.map.PredefinedMap;
import com.gpl.rpg.AndorsTrail.util.Coord;
import com.gpl.rpg.AndorsTrail.util.CoordRect;
import com.gpl.rpg.AndorsTrail.util.L;
import com.gpl.rpg.AndorsTrail.util.Size;

public class PathFinder {
	private final int maxWidth;
	private final int maxHeight;
	private final boolean[] visited;
	private final int[] gScore;
	private final int[] predecessor;
	private final OpenSetHeap openSet;
	private final PredefinedMap map;
	private int lastPathDistance = -1;
	// Reused scratch rect for the clearance probes in heuristic()/countUnwalkableNeighbors(),
	// the same allocation-avoidance pattern as findPathBetween's own `nextStep` scratch - never
	// touched outside heuristic()'s own call stack, so it can't collide with the caller's use of
	// `nextStep` as scratch for the "real" neighbour check happening around the same statements.
	private final CoordRect clearanceScratch = new CoordRect(new Coord(), new Size(1, 1));

	public static volatile boolean showPathfinderDebug = false;
	public final boolean[] last_visited;
	public final List<Coord> last_path = new ArrayList<Coord>();
	public final List<Integer> last_path_distances = new ArrayList<Integer>();

	public int getLastPathDistance() { return lastPathDistance; }

	public PathFinder(int maxWidth, int maxHeight, PredefinedMap map) {
		this.maxWidth = maxWidth;
		this.maxHeight = maxHeight;
		this.map = map;
		this.visited = new boolean[maxWidth*maxHeight];
		this.gScore = new int[maxWidth*maxHeight];
		this.predecessor = new int[maxWidth*maxHeight];
		this.openSet = new OpenSetHeap(maxWidth*maxHeight);
		this.last_visited = new boolean[maxWidth*maxHeight];
	}

	public boolean findPathBetween(final CoordRect from, final Coord to, CoordRect nextStep) {
		return findPathBetween(from, new CoordRect(to, new Size(1, 1)), nextStep, null);
	}
	public boolean findPathBetween(final CoordRect from, final CoordRect to, CoordRect nextStep) {
		return findPathBetween(from, to, nextStep, null);
	}
	public boolean findPathBetween(final CoordRect from, final Coord to, CoordRect nextStep, Monster m) {
		return findPathBetween(from, new CoordRect(to, new Size(1, 1)), nextStep, m);
	}

	public boolean findPathBetween(final CoordRect from, final CoordRect to, CoordRect nextStep, Monster m) {
		return findPathBetween(from, to, nextStep, m, null);
	}

	/**
	 * Same as {@link #findPathBetween(CoordRect, CoordRect, CoordRect, Monster)}, but additionally
	 * treats the single tile {@code avoid} as unwalkable for this search only. Used to route a
	 * travelling monster around the player: {@code monsterCanMoveTo}/{@code map.isWalkable(_, m)}
	 * already exclude other monsters from this graph (see {@code PredefinedMap.getMonsterAt}), but
	 * have no concept of the player at all, so without this the player is invisible to the search -
	 * a route that happens to lead straight through wherever the player is standing looks perfectly
	 * walkable, and the monster keeps recomputing and re-attempting that exact route instead of
	 * detouring around them. Not applied to aggressive monsters pathing *at* the player (that search
	 * targets the player's own tile as {@code to}, which would make the destination itself
	 * unreachable) - only travel-approach callers pass a non-null {@code avoid}.
	 */
	public boolean findPathBetween(final CoordRect from, final CoordRect to, CoordRect nextStep, Monster m, final Coord avoid) {
//		L.log("PATHFINDER: finding path between "
//				+ "(" + from.topLeft.x + ", " + from.topLeft.y + ")"
//				+ " and "
//				+ "(" + to.topLeft.x + ", " + to.topLeft.y + ")");

		lastPathDistance = -1;
		if (from.intersects(to)) {
			lastPathDistance = 0;
			if (showPathfinderDebug) {
				synchronized (last_path) {
					last_path.clear();
					last_path_distances.clear();
					Arrays.fill(last_visited, false);
				}
			}
			return false;
		}

		int iterations = 0;
		Coord measureDistanceTo = from.topLeft;
		Coord straightLineGoal = to.getCenter();
		Coord curr = nextStep.topLeft;

		Arrays.fill(visited, false);
		Arrays.fill(gScore, Integer.MAX_VALUE / 4);
		Arrays.fill(predecessor, -1);
		openSet.clear();

		// seed open set with all reachable tiles in `to` rectangle
		for (int y = to.topLeft.y; y < to.topLeft.y + to.size.height; ++y) {
			if (y < 0 || y >= maxHeight) continue;
			for (int x = to.topLeft.x; x < to.topLeft.x + to.size.width; ++x) {
				if (x < 0 || x >= maxWidth) continue;
				int i = (y * maxWidth) + x;
				if (avoid != null && x == avoid.x && y == avoid.y) continue;
				nextStep.topLeft.x = x; nextStep.topLeft.y = y;
				if (m != null && !map.isWalkable(nextStep, m)) continue;
				else if (!map.isWalkable(nextStep, true)) continue;
				gScore[i] = 0;
				int h = heuristic(measureDistanceTo.x, measureDistanceTo.y, x, y, straightLineGoal.x, straightLineGoal.y);
				openSet.add(x, y, h);
			}
		}
		if (openSet.isEmpty()) {
//			L.log("PATHFINDER: openSet is empty, no path possible.");
			if (showPathfinderDebug) {
				synchronized (last_path) {
					last_path.clear();
					last_path_distances.clear();
					Arrays.fill(last_visited, false);
				}
			}
			return false;
		}

		while (!openSet.isEmpty()) {
			openSet.pop();
			if (++iterations > 500) {
				L.log("PATHFINDER: iteration limit reached (500).");
				if (showPathfinderDebug) {
					synchronized (last_path) {
						System.arraycopy(visited, 0, last_visited, 0, visited.length);
						last_path.clear();
						last_path_distances.clear();
					}
				}
				return false;
			}
			int cx = openSet.px; int cy = openSet.py;
			int ci = (cy * maxWidth) + cx;
			if (visited[ci]) continue;
			visited[ci] = true;

			curr.x = cx; curr.y = cy;
			if (from.isAdjacentTo(curr)) {
				Coord closest = from.findPositionAdjacentTo(curr);
				int dx = Math.abs(cx - closest.x);
				int dy = Math.abs(cy - closest.y);
				// (cx,cy) is the tile the monster's very first physical step lands on (the search
				// runs backward from `to`, terminating as soon as it's adjacent to `from`) - so its
				// own control-layer weight applies here, the same "cost of entering this tile"
				// convention as the neighbour-expansion loop below.
				int moveCost = 10 + map.getPathWeight(cx, cy);
				lastPathDistance = gScore[ci] + moveCost;
				if (showPathfinderDebug) {
					synchronized (last_path) {
						System.arraycopy(visited, 0, last_visited, 0, visited.length);
						last_path.clear();
						last_path_distances.clear();
						int i = ci;
						while (i != -1) {
							last_path.add(new Coord(i % maxWidth, i / maxWidth));
							last_path_distances.add(lastPathDistance - gScore[i]);
							i = predecessor[i];
						}
					}
				}
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
					if (avoid != null && nx == avoid.x && ny == avoid.y) continue;

					// check walkable using nextStep as scratch
					nextStep.topLeft.x = nx; nextStep.topLeft.y = ny;
					if (m != null && !map.isWalkable(nextStep, m)) continue;
					else if (!map.isWalkable(nextStep, true)) continue;

					// Cost of entering (nx,ny) - see the control-layer/pathWeight doc comment on
					// TMXMapTranslator.LAYERNAME_CONTROL. Penalty-only by construction (pathWeight is
					// clamped >= 0 at data-load time - see TMXMapFileParser.readTMXTile), so this can
					// only ever make a tile cost 10 or more, never less - the heuristic() base term
					// below (10 * Chebyshev distance) therefore stays a valid admissible lower bound
					// with no changes needed there.
					int moveCost = 10 + map.getPathWeight(nx, ny);
					int tentativeG = gScore[ci] + moveCost;
					if (tentativeG < gScore[ni]) {
						gScore[ni] = tentativeG;
						predecessor[ni] = ci;
						int h = heuristic(measureDistanceTo.x, measureDistanceTo.y, nx, ny, straightLineGoal.x, straightLineGoal.y);
						openSet.add(nx, ny, tentativeG + h);
					}
				}
			}
		}
		if (showPathfinderDebug) {
			synchronized (last_path) {
				System.arraycopy(visited, 0, last_visited, 0, visited.length);
				last_path.clear();
				last_path_distances.clear();
			}
		}
		return false;
	}

	public Coord findPositionOnPath(final Coord from, final CoordRect to, final long distance, Monster m) {
		return findPositionOnPath(new CoordRect(from, m.rectPosition.size), to, distance, m);
	}

	public Coord findPositionOnPath(final CoordRect from, final CoordRect to, final long distance, Monster m) {
		// findPathBetween reports "from already overlaps to" as a plain `false`, indistinguishable
		// from "no path exists" - without this check we'd fall through to the `from.topLeft` fallback
		// below and never actually arrive, even though we're already there (e.g. a travelling
		// monster's leg starts on the very tile it needs to end on, such as a mapchange object it
		// just arrived through).
		if (from.intersects(to)) return to.topLeft;
		if (distance <= 0) return from.topLeft;

		CoordRect nextStep = new CoordRect(new Coord(), new Size(1, 1));
		if (!findPathBetween(from, to, nextStep, m)) return from.topLeft;

		if (distance >= lastPathDistance) {
			// Find a tile in the target area that was reached
			for (int y = to.topLeft.y; y < to.topLeft.y + to.size.height; ++y) {
				if (y < 0 || y >= maxHeight) continue;
				for (int x = to.topLeft.x; x < to.topLeft.x + to.size.width; ++x) {
					if (x < 0 || x >= maxWidth) continue;
					int i = (y * maxWidth) + x;
					if (visited[i] && gScore[i] == 0) return new Coord(x, y);
				}
			}
			return to.topLeft;
		}

		// Find the tile adjacent to 'from' that starts the path
		int ci = -1;
		for (int y = 0; y < maxHeight; ++y) {
			for (int x = 0; x < maxWidth; ++x) {
				int i = (y * maxWidth) + x;
				if (visited[i] && from.isAdjacentTo(x, y)) {
					int dx = Math.abs(x - from.topLeft.x);
					int dy = Math.abs(y - from.topLeft.y);
					// Must match findPathBetween's own final-step cost formula exactly (including
					// the control-layer weight) - this is re-deriving which candidate node the just-
					// completed search actually used to produce `lastPathDistance`, not a fresh cost
					// computation of its own.
					int moveCost = 10 + map.getPathWeight(x, y);
					if (gScore[i] + moveCost == lastPathDistance) {
						ci = i;
						break;
					}
				}
			}
			if (ci != -1) break;
		}

		if (ci == -1) return from.topLeft;

		int i = ci;
		Coord best = from.topLeft;
		while (i != -1) {
			int distFromStart = lastPathDistance - gScore[i];
			if (distFromStart > distance) return best;
			best = new Coord(i % maxWidth, i / maxWidth);
			i = predecessor[i];
		}
		return best;
	}

	/**
	 * Since diagonal and orthogonal moves cost the same (10), many visually different tile
	 * sequences between two points tie on total cost - e.g. for a trip 10 tiles east and 20 south,
	 * "20 diagonal-then-straight" and "10 diagonal moves clustered at one end, 10 straight at the
	 * other" and "diagonal/straight neatly interleaved into an even staircase" all cost exactly the
	 * same. Plain Chebyshev distance doesn't prefer any of them, so which one A* actually returns
	 * is decided arbitrarily by exploration order - which can look like a random zig-zag depending
	 * on map layout. `lineTiebreak` below nudges the search toward whichever candidate node stays
	 * closest to the straight line between this search's two fixed endpoints (`ax,ay` and `gx,gy`,
	 * both constant for the whole search), which is exactly what turns "cluster all diagonals at
	 * one end" or "arbitrary zig-zag" into the natural-looking even interleave.
	 *
	 * `perpendicularDistance` (the candidate's distance from that line, in tiles - the raw
	 * cross-product normalized by the line's own length, not by some fixed worst-case constant, so
	 * it stays meaningful at any trip length instead of rounding away to 0 on short/local searches)
	 * feeds `lineTiebreak`.
	 *
	 * `clearanceTiebreak` fixes a separate gap `lineTiebreak` can't: every tile sitting exactly on
	 * the straight line has perpendicular distance 0, including every tile along the direct
	 * approach to an obstacle that's *on* that line - so among several equal-true-cost routes that
	 * differ only in *when* they start detouring around it, `lineTiebreak` can't tell "detour
	 * starts here" from "detour starts one tile closer to the obstacle" apart, and exploration
	 * order picks arbitrarily. Observed as the monster walking straight at an obstacle right up to
	 * the last possible tile, then turning sharply, instead of easing away from it a little
	 * earlier. `clearanceScore` measures how hemmed in a candidate is by unwalkable terrain within
	 * a 2-tile radius (immediate neighbours weighted fully, the outer ring less - a falloff, not a
	 * sharp step function), and `clearanceTiebreak` penalizes hugging obstacle edges, so among tied
	 * routes the search prefers the one that keeps a little more breathing room as soon as that's
	 * free to do, without affecting searches that never come near an obstacle at all (0 there, same
	 * as today).
	 *
	 * An immediate-8-neighbour, un-falloff version of this term (and a version sharing one combined
	 * cap with `lineTiebreak` instead of each term having its own) both looked correct on paper but
	 * produced a direction-dependent result once checked against real map data: on `traveltest1`,
	 * every one of the four corner destinations sits right next to its room's walls (that's what
	 * makes it a "corner"), but which walls (N+W, N+E, ...) differs per corner, and depending on
	 * whether escaping those particular walls happened to agree or conflict with staying on that
	 * trip's straight line, the two prior, narrower terms either reinforced or cancelled out - e.g.
	 * `rect1`&harr;`rect2` (an escape direction that happened to agree with the line) eased in
	 * correctly while `rect2`&harr;`rect4` (an escape direction perpendicular to the line) didn't,
	 * even though the underlying formulas are properly symmetric under swapping x/y - the asymmetry
	 * came from the specific, differently-oriented geometry each corner happens to sit in, not from
	 * either term's math. Widening the radius (so it isn't so sharply tied to the exact tile
	 * standing at a wall) and no longer sharing `lineTiebreak`'s cap (so a strong nearby-wall signal
	 * can't be crowded out by an already-large line deviation) resolved all four corners
	 * consistently when checked the same way - see `changelog.md` for the full investigation.
	 *
	 * Each term is bounded by its own cap (9, still well below one move's cost of 10) rather than a
	 * shared one, so the combined worst case (both fully saturated, 18) is a deliberately larger,
	 * but still small and bounded, allowance than a single term alone would get - gScore (the real
	 * accumulated cost) is never touched by either term, only priority order is, so the practical
	 * effect of this widened allowance is confined to which of several near-equal-cost routes gets
	 * explored first; empirically (checked against every corner pair plus the original Phase 6
	 * regression case) this produced zero path-length regressions.
	 */
	private static final double TIEBREAK_WEIGHT = 3.0;
	private static final int TIEBREAK_MAX = 9;
	private static final double CLEARANCE_WEIGHT = 2.0;
	private static final int CLEARANCE_TIEBREAK_MAX = 9;

	private int heuristic(int ax, int ay, int bx, int by, int gx, int gy) {
		int dx = Math.abs(ax - bx);
		int dy = Math.abs(ay - by);
		int base = 10 * Math.max(dx, dy);

		double lineDx = gx - ax;
		double lineDy = gy - ay;
		double lineLength = Math.sqrt(lineDx * lineDx + lineDy * lineDy);
		double lineTiebreak = 0;
		if (lineLength > 0) {
			double cross = Math.abs((bx - ax) * lineDy - lineDx * (by - ay));
			double perpendicularDistance = cross / lineLength;
			lineTiebreak = Math.min(TIEBREAK_MAX, perpendicularDistance * TIEBREAK_WEIGHT);
		}
		double clearanceTiebreak = Math.min(CLEARANCE_TIEBREAK_MAX, clearanceScore(bx, by) * CLEARANCE_WEIGHT);

		return (int) (base + lineTiebreak + clearanceTiebreak);
	}

	/**
	 * Weighted count of unwalkable terrain within a 2-tile radius of (x,y) - immediate (radius-1)
	 * neighbours count fully, radius-2 ones count less, a falloff rather than a hard cutoff.
	 * Terrain-only (ignores event areas/monster occupancy - see heuristic()'s doc comment).
	 */
	private double clearanceScore(int x, int y) {
		double score = 0;
		for (int dy = -2; dy <= 2; ++dy) {
			for (int dx = -2; dx <= 2; ++dx) {
				if (dx == 0 && dy == 0) continue;
				int nx = x + dx; int ny = y + dy;
				if (nx < 0 || ny < 0 || nx >= maxWidth || ny >= maxHeight) continue;
				clearanceScratch.topLeft.x = nx;
				clearanceScratch.topLeft.y = ny;
				if (map.isWalkable(clearanceScratch, true)) continue;
				score += (Math.max(Math.abs(dx), Math.abs(dy)) == 1) ? 1.0 : 0.4;
			}
		}
		return score;
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

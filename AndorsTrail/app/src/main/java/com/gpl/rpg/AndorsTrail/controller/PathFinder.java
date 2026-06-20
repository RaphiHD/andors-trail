package com.gpl.rpg.AndorsTrail.controller;

import java.util.Arrays;

import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.util.Coord;
import com.gpl.rpg.AndorsTrail.util.CoordRect;

public class PathFinder {
	private final int maxWidth;
	private final int maxHeight;
	private final boolean visited[];
	private final ListOfCoords visitQueue;
	private final EvaluateWalkable map;

	public PathFinder(int maxWidth, int maxHeight, EvaluateWalkable map) {
		this.maxWidth = maxWidth;
		this.maxHeight = maxHeight;
		this.map = map;
		this.visited = new boolean[maxWidth*maxHeight];
		this.visitQueue = new ListOfCoords(maxWidth*maxHeight);
	}

	public interface EvaluateWalkable {
		public boolean isWalkable(CoordRect r, Monster m);
	}

	public boolean findPathBetween(final CoordRect from, final CoordRect to, CoordRect nextStep, Monster m) {
		int iterations = 0;
		if (from.intersects(to)) return false;

		Coord measureDistanceTo = from.topLeft;
		Coord p = nextStep.topLeft;
		Arrays.fill(visited, false);
		visitQueue.reset();

		for (int y = to.topLeft.y; y < to.topLeft.y + to.size.height; ++y) {
			if (y < 0 || y >= maxHeight) continue;
			for (int x = to.topLeft.x; x < to.topLeft.x + to.size.width; ++x) {
				if (x < 0 || x >= maxWidth) continue;
				int i = (y * maxWidth) + x;
				if (visited[i]) continue;
				visited[i] = true;
				int dx = measureDistanceTo.x - x;
				int dy = measureDistanceTo.y - y;
				visitQueue.push(x, y, dx * dx + dy * dy);
			}
		}
		if (visitQueue.isEmpty()) return false;

		while (!visitQueue.isEmpty()) {
			visitQueue.popFirst(p);
			++iterations;

			if (iterations > 500) return false;

			if (from.isAdjacentTo(p)) return true;

			p.x -= 1; visit(nextStep, measureDistanceTo, m);
			p.x += 2; visit(nextStep, measureDistanceTo, m);
			p.x -= 1; p.y -= 1; visit(nextStep, measureDistanceTo, m);
			p.y += 2; visit(nextStep, measureDistanceTo, m);
			p.x -= 1; visit(nextStep, measureDistanceTo, m);
			p.x += 2; visit(nextStep, measureDistanceTo, m);
			p.y -= 2; visit(nextStep, measureDistanceTo, m);
			p.x -= 2; visit(nextStep, measureDistanceTo, m);
		}
		return false;
	}

	public boolean findPathBetween(final CoordRect from, final Coord to, CoordRect nextStep, Monster m) {
		int iterations = 0;
		if (from.contains(to)) return false;

		Coord measureDistanceTo = from.topLeft;
		Coord p = nextStep.topLeft;
		Arrays.fill(visited, false);
		visitQueue.reset();

		visitQueue.push(to.x, to.y, 0);
		visited[(to.y * maxWidth) + to.x] = true;

		while (!visitQueue.isEmpty()) {
			visitQueue.popFirst(p);
			++iterations;

			if (iterations > 500) return false;

			if (from.isAdjacentTo(p)) return true;

			p.x -= 1; visit(nextStep, measureDistanceTo, m);
			p.x += 2; visit(nextStep, measureDistanceTo, m);
			p.x -= 1; p.y -= 1; visit(nextStep, measureDistanceTo, m);
			p.y += 2; visit(nextStep, measureDistanceTo, m);
			p.x -= 1; visit(nextStep, measureDistanceTo, m);
			p.x += 2; visit(nextStep, measureDistanceTo, m);
			p.y -= 2; visit(nextStep, measureDistanceTo, m);
			p.x -= 2; visit(nextStep, measureDistanceTo, m);
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

		int dx = (measureDistanceTo.x - x);
		int dy = (measureDistanceTo.y - y);
		visitQueue.push(x, y, dx * dx + dy * dy);
	}

	private static final class ListOfCoords {
		private final int xCoords[];
		private final int yCoords[];
		private final int weights[];
		private final int maxIndex;
		private int lastIndex; // Index of the last coord that was inserted
		private int frontIndex; // Index to the first coord that is not discarded
		private static final int DISCARDED = -1;

		public ListOfCoords(int maxSize) {
			this.maxIndex = maxSize-1;
			this.xCoords = new int[maxSize];
			this.yCoords = new int[maxSize];
			this.weights = new int[maxSize];
		}

		public void reset() {
			lastIndex = -1;
			frontIndex = 0;
		}

		public void push(int x, int y, int weight) {
			if (lastIndex == maxIndex) return;
			++lastIndex;
			xCoords[lastIndex] = x;
			yCoords[lastIndex] = y;
			weights[lastIndex] = weight;
		}

		public int popFirst(Coord dest) {
			int i = frontIndex;
			int lowestWeightIndex = i;
			int lowestWeight = weights[i];
			++i;
			for(;i <= lastIndex; ++i) {
				if (weights[i] == DISCARDED) continue;
				if (weights[i] < lowestWeight) {
					lowestWeightIndex = i;
					lowestWeight = weights[i];
				}
			}
			dest.x = xCoords[lowestWeightIndex];
			dest.y = yCoords[lowestWeightIndex];
			weights[lowestWeightIndex] = DISCARDED;

			// Increase frontIndex to the first index that is not discarded.
			while (frontIndex <= lastIndex) {
				if (weights[frontIndex] == DISCARDED) ++frontIndex;
				else break;
			}

			return lowestWeight;
		}

		public boolean isEmpty() {
			return frontIndex > lastIndex;
		}
	}
}

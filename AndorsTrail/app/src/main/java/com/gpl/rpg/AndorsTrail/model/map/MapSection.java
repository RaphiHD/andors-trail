package com.gpl.rpg.AndorsTrail.model.map;

import com.gpl.rpg.AndorsTrail.util.ByteUtils;
import com.gpl.rpg.AndorsTrail.util.CoordRect;

public final class MapSection {
	public final MapLayer layerBase;
	public final MapLayer layerGround;
	public final MapLayer layerObjects;
	public final MapLayer layerAbove;
	public final MapLayer layerTop;
	public final boolean[][] isWalkable;
	/** Travel path-cost modifier per tile (0 = unmodified baseline) - see PathFinder.getPathWeight. Null (same contract as isWalkable) wherever this section doesn't author a "control" layer at all - which is most maps, so this deliberately doesn't force a wasted full-size array onto every one of them. */
	public final int[][] pathWeight;
	private final byte[] layoutHash;

	public MapSection(
			MapLayer layerBase
			, MapLayer layerGround
			, MapLayer layerObjects
			, MapLayer layerAbove
			, MapLayer layerTop
			, boolean[][] isWalkable
			, int[][] pathWeight
			, byte[] layoutHash
	) {
		this.layerBase = layerBase;
		this.layerGround = layerGround;
		this.layerObjects = layerObjects;
		this.layerAbove = layerAbove;
		this.layerTop = layerTop;
		this.isWalkable = isWalkable;
		this.pathWeight = pathWeight;
		this.layoutHash = layoutHash;
	}

	public void replaceLayerContentsWith(final MapSection replaceLayersWith, final CoordRect replacementArea) {
		replaceTileLayerSection(layerBase, replaceLayersWith.layerBase, replacementArea);
		replaceTileLayerSection(layerGround, replaceLayersWith.layerGround, replacementArea);
		replaceTileLayerSection(layerObjects, replaceLayersWith.layerObjects, replacementArea);
		replaceTileLayerSection(layerAbove, replaceLayersWith.layerAbove, replacementArea);
		replaceTileLayerSection(layerTop, replaceLayersWith.layerTop, replacementArea);
		if (replaceLayersWith.isWalkable != null) {
			final int dy = replacementArea.topLeft.y;
			final int height = replacementArea.size.height;
			for (int sx = 0, dx = replacementArea.topLeft.x; sx < replacementArea.size.width; ++sx, ++dx) {
				System.arraycopy(replaceLayersWith.isWalkable[sx], 0, isWalkable[dx], dy, height);
			}
		}
		if (pathWeight != null && replaceLayersWith.pathWeight != null) {
			replaceIntLayerSection(pathWeight, replaceLayersWith.pathWeight, replacementArea);
		}
		ByteUtils.xorArray(layoutHash, replaceLayersWith.layoutHash);
	}

	private static void replaceTileLayerSection(MapLayer dest, MapLayer src, CoordRect area) {
		if (src == null) return;
		final int dy = area.topLeft.y;
		final int height = area.size.height;
		for (int sx = 0, dx = area.topLeft.x; sx < area.size.width; ++sx, ++dx) {
			System.arraycopy(src.tiles[sx], 0, dest.tiles[dx], dy, height);
		}
	}

	/**
	 * Both null (the common case - neither this section nor the replacement author a "control"
	 * layer) is handled by the caller skipping this method entirely. The one case NOT handled here:
	 * a base section with no control layer at all, replaced by a section that *does* specify one -
	 * since `pathWeight` can't grow from null to a real array after construction, that replacement's
	 * weight data is silently not applied. Not expected to come up in practice (a map author using
	 * this feature at all would define its baseline on the section being replaced, the same way a
	 * ReplaceableMapSection's Walkable property already assumes a base walkable layer exists), but
	 * worth knowing if it ever needs generalizing.
	 */
	private static void replaceIntLayerSection(int[][] dest, int[][] src, CoordRect area) {
		final int dy = area.topLeft.y;
		final int height = area.size.height;
		for (int sx = 0, dx = area.topLeft.x; sx < area.size.width; ++sx, ++dx) {
			System.arraycopy(src[sx], 0, dest[dx], dy, height);
		}
	}

	public String calculateHash(String filter) {
		byte[] hash = layoutHash.clone();
		if (filter != null) ByteUtils.xorArray(hash, filter.getBytes());
		return ByteUtils.toHexString(hash, 4);
	}
}

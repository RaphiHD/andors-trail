package com.gpl.rpg.AndorsTrail.resource.tiles;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import com.gpl.rpg.AndorsTrail.controller.Constants;

public final class TileCollection {
	private final Bitmap[] bitmaps;
	public final int maxTileID;

	public TileCollection(int maxTileID) {
		this.bitmaps = new Bitmap[maxTileID+1];
		this.maxTileID = maxTileID;
	}

	public Bitmap getBitmap(int tileID) {
		return bitmaps[tileID];
	}

	public void setBitmap(int tileID, Bitmap bitmap) {
		bitmaps[tileID] = bitmap;
	}

	public void drawTile(Canvas canvas, int tile, int px, int py, Paint mPaint) {
		drawTile(canvas, tile, px, py, mPaint, false);
	}
	public void drawTile(Canvas canvas, int tile, int px, int py, Paint mPaint, boolean allowHorizontalSpriteFlip) {
		if (allowHorizontalSpriteFlip && Constants.roll100(50))
			canvas.drawBitmap(flipHorizontal(bitmaps[tile]), px, py, mPaint);
		else canvas.drawBitmap(bitmaps[tile], px, py, mPaint);
	}

	public Bitmap flipHorizontal(Bitmap source) {
		Matrix matrix = new Matrix();
		matrix.postScale(-1, 1, source.getWidth() / 2f, source.getHeight() / 2f);
		return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
	}
}

package com.gpl.rpg.AndorsTrail.model.map;

import java.util.Collection;

import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.util.Coord;
import com.gpl.rpg.AndorsTrail.util.CoordRect;
import com.gpl.rpg.AndorsTrail.util.Size;

import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

public final class LayeredTileMap {
	private static final float[] colorMatrixInvert = new float[] {
				-1.00f, 0.00f, 0.00f, 0.0f, 255.0f,
				0.00f, -1.00f, 0.00f, 0.0f, 255.0f,
				0.00f, 0.00f, -1.00f, 0.0f, 255.0f,
				0.00f, 0.00f, 0.00f, 1.0f, 0.0f
	};
	private static final float[] colorMatrixBW = new float[] {
				0.33f, 0.59f, 0.11f, 0.0f, 0.0f,
				0.33f, 0.59f, 0.11f, 0.0f, 0.0f,
				0.33f, 0.59f, 0.11f, 0.0f, 0.0f,
				0.00f, 0.00f, 0.00f, 1.0f, 0.0f
	};
	private static final float[] colorMatrixRed = new float[] {
				1.20f, 0.20f, 0.20f, 0.0f, 25.0f,
				0.00f, 0.80f, 0.00f, 0.0f, 0.0f,
				0.00f, 0.00f, 0.80f, 0.0f, 0.0f,
				0.00f, 0.00f, 0.00f, 1.0f, 0.0f
	};
	private static final float[] colorMatrixGreen = new float[] {
				0.85f, 0.00f, 0.00f, 0.0f, 0.0f,
				0.15f, 1.15f, 0.15f, 0.0f, 15.0f,
				0.00f, 0.00f, 0.85f, 0.0f, 0.0f,
				0.00f, 0.00f, 0.00f, 1.0f, 0.0f
	};
	private static final float[] colorMatrixBlue = new float[] {
				0.70f, 0.00f, 0.00f, 0.0f, 0.0f,
				0.00f, 0.70f, 0.00f, 0.0f, 0.0f,
				0.30f, 0.30f, 1.30f, 0.0f, 40.0f,
				0.00f, 0.00f, 0.00f, 1.0f, 0.0f
	};
	private static final float[] colorMatrixNoon = new float[] {
			1.00f, 0.00f, 0.00f, 0.0f, 0.0f,
			0.00f, 1.00f, 0.00f, 0.0f, 0.0f,
			0.00f, 0.00f, 1.00f, 0.0f, 0.0f,
			0.00f, 0.00f, 0.00f, 1.0f, 0.0f
	};
	private static final float[] colorMatrixSunset = new float[] {
			0.60f, 0.00f, 0.00f, 0.0f, 40.0f,
			0.00f, 0.50f, 0.00f, 0.0f, 20.0f,
			0.00f, 0.00f, 0.40f, 0.0f, 0.0f,
			0.00f, 0.00f, 0.00f, 1.0f, 0.0f
	};
	private static final float[] colorMatrixMidnight = new float[] {
			0.40f, 0.00f, 0.00f, 0.0f, 0.0f,
			0.00f, 0.30f, 0.00f, 0.0f, 0.0f,
			0.00f, 0.00f, 0.70f, 0.0f, 30.0f,
			0.00f, 0.00f, 0.00f, 1.0f, 0.0f
	};
	private static final float[] colorMatrixSunrise = new float[] {
			0.60f, 0.00f, 0.00f, 0.0f, 20.0f,
			0.00f, 0.60f, 0.00f, 0.0f, 20.0f,
			0.00f, 0.00f, 0.50f, 0.0f, 0.0f,
			0.00f, 0.00f, 0.00f, 1.0f, 0.0f
	};

	public enum ColorFilterId {
		none,
		black20,
		black40,
		black60,
		black80,
		invert,
		bw,
		redtint,
		greentint,
		bluetint
	}

	private final Size size;
	public final MapSection currentLayout;
	private String currentLayoutHash;
	public final ReplaceableMapSection[] replacements;
	public final ColorFilterId originalColorFilter;
	public ColorFilterId colorFilter;
	public final Collection<Integer> usedTileIDs;
	public LayeredTileMap(
			Size size
			, MapSection layout
			, ReplaceableMapSection[] replacements
			, ColorFilterId colorFilter
			, Collection<Integer> usedTileIDs
	) {
		this.size = size;
		this.currentLayout = layout;
		this.replacements = replacements;
		this.originalColorFilter = colorFilter;
		colorFilter = originalColorFilter;
		this.usedTileIDs = usedTileIDs;
		this.currentLayoutHash = currentLayout.calculateHash(colorFilter.name());
	}

	public final boolean isWalkable(final Coord p) {
		if (isOutside(p.x, p.y)) return false;
		return currentLayout.isWalkable[p.x][p.y];
	}
	public final boolean isWalkable(final int x, final int y) {
		if (isOutside(x, y)) return false;
		return currentLayout.isWalkable[x][y];
	}
	public final boolean isWalkable(final CoordRect p) {
		for (int y = 0; y < p.size.height; ++y) {
			for (int x = 0; x < p.size.width; ++x) {
				if (!isWalkable(p.topLeft.x + x, p.topLeft.y + y)) return false;
			}
		}
		return true;
	}
	public final boolean isOutside(final Coord p) { return isOutside(p.x, p.y); }
	public final boolean isOutside(final int x, final int y) {
		if (x < 0) return true;
		if (y < 0) return true;
		if (x >= size.width) return true;
		if (y >= size.height) return true;
		return false;
	}
	public final boolean isOutside(final CoordRect area) {
		if (isOutside(area.topLeft)) return true;
		if (area.topLeft.x + area.size.width > size.width) return true;
		if (area.topLeft.y + area.size.height > size.height) return true;
		return false;
	}

	public boolean setColorFilter(Paint mPaint, Paint alternateColorFilterPaint, boolean highQuality) {
		if (!highQuality) {
			highQuality = !setColor(alternateColorFilterPaint);
		}
		mPaint.setColorFilter(highQuality ? getColorFilter() : null);
		return !highQuality;
	}

	public boolean setDaylightColorFilter(WorldContext world, Paint mPaint, Paint daylightColorFilterPaint, boolean dayLight) {
		setColor(daylightColorFilterPaint);
		mPaint.setColorFilter(dayLight ? createDaylightColorFilter(world, mPaint.getColorFilter()) : mPaint.getColorFilter());
		return dayLight;
	}

	public ColorFilter getColorFilter() {
		float[] colorMatrix = getColorMatrix();
		return (colorMatrix == null) ? null : new ColorMatrixColorFilter(colorMatrix);
	}

	public float[] getColorMatrix() {
		if (colorFilter == null) return null;
		switch (colorFilter) {
			case black20:
				return createColorMatrixGrayScale(0.2f);
			case black40:
				return createColorMatrixGrayScale(0.4f);
			case black60:
				return createColorMatrixGrayScale(0.6f);
			case black80:
				return createColorMatrixGrayScale(0.8f);
			case invert:
				return colorMatrixInvert;
			case bw:
				return colorMatrixBW;
			case redtint:
				return colorMatrixRed;
			case greentint:
				return colorMatrixGreen;
			case bluetint:
				return colorMatrixBlue;
			default:
				return null;
		}
	}

	public boolean setColor(Paint p) {
		if (colorFilter == null) {
			p.setARGB(0, 0, 0, 0);
			return true;
		}
		switch (colorFilter) {
		case black20:
			p.setARGB(51, 0, 0, 0); return true;
		case black40:
			p.setARGB(102, 0, 0, 0); return true;
		case black60:
			p.setARGB(153, 0, 0, 0); return true;
		case black80:
			p.setARGB(204, 0, 0, 0); return true;
		case redtint:
			p.setARGB(50, 200, 0, 0); return true;
		case greentint:
			p.setARGB(50, 0, 200, 0); return true;
		case bluetint:
			p.setARGB(50, 0, 0, 200); return true;
		case bw:
		case invert:
			return false;
		default:
			p.setARGB(0, 0, 0, 0); return true;
		
		}
		
	}

	private ColorMatrixColorFilter createDaylightColorFilter(WorldContext world, ColorFilter colorFilter) {
		long worldTime = world.model.worldData.getWorldTime(); // get current world time
		int dayLength = world.model.worldData.getDayLength(); // number of rounds in a full day
		int phaseLength = dayLength / 10; // number of rounds of each transition phase
		int dayTime = (int) (worldTime % dayLength); // dayTime: 0-4=noon 5-9=sunset 10-14=midnight 15-19=sunrise
		float blendFactor = (dayTime % phaseLength) / (float) phaseLength;

		float[] finalMatrix;

		if (dayTime < dayLength / 2) {
			finalMatrix = colorMatrixNoon;
		} else if (dayTime < dayLength / 2 - phaseLength * 2) {
			finalMatrix = lerpColorMatrix(colorMatrixNoon, colorMatrixSunset, blendFactor);
		} else if (dayTime < dayLength / 2 - phaseLength) {
			finalMatrix = lerpColorMatrix(colorMatrixSunset, colorMatrixMidnight, blendFactor);
		} else if (dayTime < dayLength - phaseLength * 2){
			finalMatrix = colorMatrixMidnight;
		} else if (dayTime < dayLength - phaseLength) {
			finalMatrix = lerpColorMatrix(colorMatrixMidnight, colorMatrixSunrise, blendFactor);
		} else if (dayTime < dayLength) {
			finalMatrix = lerpColorMatrix(colorMatrixSunrise, colorMatrixNoon, blendFactor);
		} else {
			finalMatrix = colorMatrixNoon;
		}

		ColorMatrix computedDayLightMatrix = new ColorMatrix(finalMatrix);
		float[] overlayMatrix = getColorMatrix();

		if (overlayMatrix != null) {
			computedDayLightMatrix.postConcat(new ColorMatrix(getColorMatrix())); // Apply additional color filter
		}
		return new ColorMatrixColorFilter(computedDayLightMatrix);
	}

	private float[] lerpColorMatrix(float[] matrixA, float[] matrixB, float blendFactor) {
		float[] out = new float[20];
		for (int i = 0; i < 20; i++) {
			out[i] = matrixA[i] * (1-blendFactor) + matrixB[i] * blendFactor;
		}
		return out;
	}

	public String getCurrentLayoutHash() {
		return currentLayoutHash;
	}

	public void applyReplacement(ReplaceableMapSection replacement) {
		replacement.apply(currentLayout);
		currentLayoutHash = currentLayout.calculateHash(colorFilter == ColorFilterId.none ? null : colorFilter.name());
	}
	
	public void changeColorFilter(ColorFilterId id) {
		if (colorFilter == id) return;
		colorFilter = id;
		currentLayoutHash = currentLayout.calculateHash(colorFilter == ColorFilterId.none ? null : colorFilter.name());
	}
	

	public void changeColorFilter(String idString) {
		ColorFilterId id;
		if (idString == null) id = originalColorFilter;
		else id = ColorFilterId.valueOf(idString);
		if (id != null) {
			changeColorFilter(id);
		}
	}

	private static float[] createColorMatrixGrayScale(float blackOpacity) {
		final float f = blackOpacity;
		return new float[] {
				f,     0.00f, 0.00f, 0.0f, 0.0f,
				0.00f, f,     0.00f, 0.0f, 0.0f,
				0.00f, 0.00f, f,     0.0f, 0.0f,
				0.00f, 0.00f, 0.00f, 1.0f, 0.0f
		};
	}
}

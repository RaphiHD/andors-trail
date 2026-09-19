package com.gpl.rpg.AndorsTrail.model.map;

import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.context.ControllerContext;
import com.gpl.rpg.AndorsTrail.controller.MonsterMovementController;
import com.gpl.rpg.AndorsTrail.model.ChecksumBuilder;
import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.util.CoordRect;
import com.gpl.rpg.AndorsTrail.util.L;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class TravelDestinationArea extends MapArea {
	public final String arrivalScript;
	public TravelDestinationArea(
			WorldContext world
			, String mapID
			, CoordRect area
			, String areaID
			, String arrivalScript
	) {
		super(world, area, areaID, mapID);
		this.arrivalScript = arrivalScript;
	}

	/**
	 * @param controllers Always available - unlike the old setScriptEnvironment()-based approach,
	 * this no longer depends on MapController.prepareScriptsOnCurrentMap() having been called for
	 * this area's map (which only ever happened for whichever map the player currently has
	 * loaded), so an arrival script now runs the same way regardless of whether the player has
	 * ever visited this map this session. See MapController.runScriptForNpc.
	 */
    public void onMonsterArrived(Monster m, ControllerContext controllers) {
		if (MonsterMovementController.showTravelDebug) {
			L.log("TRAVEL: " + m.getMonsterTypeID() + " arrived at " + areaID + " on map " + mapID
					+ " pos=" + m.rectPosition.topLeft + " at wallClock=" + System.currentTimeMillis());
		}

		// Move monster from its old area into this destination area.
		m.area = this;
		m.travelDestination = null;

		if (arrivalScript != null) {
			controllers.mapController.runScriptForNpc(arrivalScript, m);
		}
  	}


	// ====== PARCELABLE ===================================================================

	public void readFromParcel(DataInputStream src, WorldContext world, int fileversion) throws IOException {
		if (fileversion <= 86) {
			if (fileversion >= 41) {
				// legacy boolean present for spawn areas; discard it
				src.readBoolean();
			}
		}
	}

	public void writeToParcel(DataOutputStream dest) throws IOException {
	}

	public void addToChecksum(ChecksumBuilder builder) {
	}
}

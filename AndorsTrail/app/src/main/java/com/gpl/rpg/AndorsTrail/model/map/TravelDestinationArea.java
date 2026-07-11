package com.gpl.rpg.AndorsTrail.model.map;

import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.context.ControllerContext;
import com.gpl.rpg.AndorsTrail.controller.ConversationController;
import com.gpl.rpg.AndorsTrail.model.ChecksumBuilder;
import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.util.CoordRect;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class TravelDestinationArea extends MapArea {
	public final String arrivalScript;
	private ConversationController.ConversationStatemachine mapScriptExecutor;
	private ControllerContext controllers;
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

    public void onMonsterArrived(Monster m) {
		// Move monster from its old area into this destination area.
		m.area = this;
		m.travelDestination = null;

		if (arrivalScript != null) {
			if (mapScriptExecutor != null && controllers != null) {
				mapScriptExecutor.setCurrentNPC(m);
				mapScriptExecutor.proceedToPhrase(controllers.getResources(), arrivalScript, true, true);
				controllers.mapController.applyCurrentMapReplacements(controllers.getResources(), true);
			}
		}
    	// Iterate over steps once implemented
  	}

	public void setScriptEnvironment(ConversationController.ConversationStatemachine exec, ControllerContext controllers) {
		this.mapScriptExecutor = exec;
		this.controllers = controllers;
	}

	public void executeNextStep() {
		// TODO once implemented
		// execute steps like "stay 10", "goto destArea2", ...
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

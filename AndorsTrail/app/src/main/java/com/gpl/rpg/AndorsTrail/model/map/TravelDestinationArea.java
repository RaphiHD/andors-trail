package com.gpl.rpg.AndorsTrail.model.map;

import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.context.ControllerContext;
import com.gpl.rpg.AndorsTrail.controller.ConversationController;
import com.gpl.rpg.AndorsTrail.model.ChecksumBuilder;
import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.util.Coord;
import com.gpl.rpg.AndorsTrail.util.CoordRect;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
		if (m.area != null) {
			m.area.monsters.remove(m);
		}
		m.area = this;
		m.travelDestination = null;
		this.monsters.add(m);

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
		monsters.clear();
		if (fileversion <= 86) {
			if (fileversion >= 41) {
				// legacy boolean present for spawn areas; discard it
				src.readBoolean();
			}
			int quantity = src.readInt();
			for (int i = 0; i < quantity; ++i) {
				monsters.add(Monster.newFromParcel(src, world, fileversion, this));
			}
			return;
		}
		int quantity = src.readInt();
		for(int i = 0; i < quantity; ++i) {
			monsters.add(Monster.newFromParcel(src, world, fileversion, this));
		}
	}

	public void writeToParcel(DataOutputStream dest) throws IOException {
		dest.writeInt(monsters.size());
		for (Monster m : monsters) {
			m.writeToParcel(dest);
		}
	}

	public void addToChecksum(ChecksumBuilder builder) {
		builder.add(monsters.size());
		for (Monster m : monsters) {
			m.addToChecksum(builder);
		}
	}
}

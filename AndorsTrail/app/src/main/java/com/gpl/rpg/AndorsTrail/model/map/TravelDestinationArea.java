package com.gpl.rpg.AndorsTrail.model.map;

import com.gpl.rpg.AndorsTrail.context.WorldContext;
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
	public TravelDestinationArea(
			String mapID,
			CoordRect area
			, String areaID
	) {
        super(area, areaID, mapID);
	}

    public void onMonsterArrived(Monster monster) {
    monster.travelDestination = null;
		// EVTL set current movement box to here (separate from spawn area)

    // Iterate over steps once implemented
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

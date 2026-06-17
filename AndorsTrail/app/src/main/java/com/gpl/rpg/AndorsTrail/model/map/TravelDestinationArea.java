package com.gpl.rpg.AndorsTrail.model.map;

import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.util.CoordRect;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TravelDestinationArea {
	public final CoordRect area;
	public final String areaID;
	public final List<Monster> monsters = new CopyOnWriteArrayList<>();

	public TravelDestinationArea(
			CoordRect area
			, String areaID
	) {
		this.area = area;
		this.areaID = areaID;
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

}

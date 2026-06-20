package com.gpl.rpg.AndorsTrail.model.map;

import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.util.Coord;
import com.gpl.rpg.AndorsTrail.util.CoordRect;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TravelDestinationArea {
	public final String mapID;
	public final CoordRect area;
	public final String areaID;
	public final List<Monster> monsters = new CopyOnWriteArrayList<>();

	public TravelDestinationArea(
			String mapID,
			CoordRect area
			, String areaID
	) {
		this.mapID = mapID;
		this.area = area;
		this.areaID = areaID;
	}

	public Monster getMonsterAt(final Coord p) { return getMonsterAt(p.x, p.y); }
	public Monster getMonsterAt(final int x, final int y) {
		for (Monster m : monsters) {
			if (m.rectPosition.contains(x, y)) return m;
		}
		return null;
	}
	public Monster getMonsterAt(final CoordRect p) {
		for (Monster m : monsters) {
			if (m.rectPosition.intersects(p)) return m;
		}
		return null;
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

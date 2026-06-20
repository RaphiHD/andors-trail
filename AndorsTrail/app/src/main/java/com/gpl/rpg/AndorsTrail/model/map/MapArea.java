package com.gpl.rpg.AndorsTrail.model.map;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.model.ChecksumBuilder;
import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.util.Coord;
import com.gpl.rpg.AndorsTrail.util.CoordRect;

public abstract class MapArea {
    public final CoordRect area;
    public final String areaID;
    public final String mapID;
    public final List<Monster> monsters = new CopyOnWriteArrayList<>();

    public MapArea(
            CoordRect area
            , String areaID
            , String mapID
    ) {
        this.area = area;
        this.areaID = areaID;
        this.mapID = mapID;
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

    public Monster findSpawnedMonster(String monsterTypeID) {
        for (Monster m : monsters) {
            if (m.getMonsterTypeID().equals(monsterTypeID)) return m;
        }
        return null;
    }

    public void remove(Monster m) {
        monsters.remove(m);
    }

    public void removeAllMonsters() {
        monsters.clear();
    }

    public void resetShops() {
        for (Monster m : monsters) {
            m.resetShopItems();
        }
    }

    public void resetForNewGame() {
        removeAllMonsters();
    }

    public abstract void readFromParcel(DataInputStream src, WorldContext world, int fileversion) throws IOException;
    public abstract void writeToParcel(DataOutputStream dest) throws IOException;
    public abstract void addToChecksum(ChecksumBuilder builder);
}

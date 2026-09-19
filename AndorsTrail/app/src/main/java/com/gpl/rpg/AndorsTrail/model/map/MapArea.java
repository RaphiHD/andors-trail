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
    public final WorldContext world;
    public final CoordRect area;
    public final String areaID;
    public final String mapID;

    public MapArea(
            WorldContext world
            , CoordRect area
            , String areaID
            , String mapID
    ) {
        this.world = world;
        this.area = area;
        this.areaID = areaID;
        this.mapID = mapID;
    }

    public abstract void readFromParcel(DataInputStream src, WorldContext world, int fileversion) throws IOException;
    public abstract void writeToParcel(DataOutputStream dest) throws IOException;
    public abstract void addToChecksum(ChecksumBuilder builder);
}

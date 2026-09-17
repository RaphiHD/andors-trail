package com.gpl.rpg.AndorsTrail.model.actor;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.gpl.rpg.AndorsTrail.context.WorldContext;

public class MonsterCollection {
    public final List<Monster> travellingMonsters = new ArrayList<>();

    public void addTravellingMonster(Monster monster) {
        travellingMonsters.add(monster);
    }

    public void removeTravellingMonster(Monster monster) {
        travellingMonsters.remove(monster);
    }

    public void resetForNewGame() {
        travellingMonsters.clear();
    }

    // ====== PARCELABLE ===================================================================

    public void writeToParcel(DataOutputStream dest) throws IOException {
        dest.writeInt(travellingMonsters.size());
        for (Monster m : travellingMonsters) {
            m.writeToParcel(dest);
        }
    }

    public void readFromParcel(DataInputStream src, WorldContext world, int fileversion) throws IOException {
        travellingMonsters.clear();
        if (fileversion < 87) return;

        int count = src.readInt();
        for (int i = 0; i < count; i++) {
            travellingMonsters.add(Monster.newFromParcel(src, world, fileversion, null));
        }
    }
}

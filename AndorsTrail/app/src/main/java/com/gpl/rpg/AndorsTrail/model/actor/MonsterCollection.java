package com.gpl.rpg.AndorsTrail.model.actor;

import java.util.ArrayList;
import java.util.List;

public class MonsterCollection {
    public final List<Monster> travellingMonsters = new ArrayList<>();

    public void addTravellingMonster(Monster monster) {
        travellingMonsters.add(monster);
    }

    public void removeTravellingMonster(Monster monster) {
        travellingMonsters.remove(monster);
    }

}

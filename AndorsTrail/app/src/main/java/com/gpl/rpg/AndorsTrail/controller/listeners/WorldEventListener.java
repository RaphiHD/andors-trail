package com.gpl.rpg.AndorsTrail.controller.listeners;

import java.util.Collection;

import com.gpl.rpg.AndorsTrail.model.actor.Actor;
import com.gpl.rpg.AndorsTrail.model.actor.Monster;
import com.gpl.rpg.AndorsTrail.model.item.Loot;
import com.gpl.rpg.AndorsTrail.model.map.MapObject;

public interface WorldEventListener {
	void onPlayerStartedConversation(Monster m, String phraseID);
	void onScriptAreaStartedConversation(Monster m, String phraseID);
	void onPlayerSteppedOnMonster(Monster m);
	void onPlayerSteppedOnMapSignArea(MapObject area);
	void onPlayerSteppedOnKeyArea(MapObject area);
	void onPlayerSteppedOnRestArea(MapObject area);
	void onPlayerSteppedOnGroundLoot(Loot loot);
	void onPlayerPickedUpGroundLoot(Loot loot);
	void onPlayerFoundMonsterLoot(Collection<Loot> loot, int exp);
	void onPlayerPickedUpMonsterLoot(Collection<Loot> loot, int exp);
	void onPlayerRested();
	void onPlayerDied(int lostExp);
}

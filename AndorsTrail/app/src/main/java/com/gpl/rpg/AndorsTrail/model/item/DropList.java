package com.gpl.rpg.AndorsTrail.model.item;

import android.content.ClipData;

import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.controller.Constants;
import com.gpl.rpg.AndorsTrail.controller.SkillController;
import com.gpl.rpg.AndorsTrail.model.actor.Player;
import com.gpl.rpg.AndorsTrail.util.ConstRange;

public final class DropList {
	private final DropItem[] items;
	private final ItemTypeCollection itemTypeCollection;
	private final ItemFilterCollection itemFilterCollection;

	public DropList(
			DropItem[] items
			, ItemTypeCollection itemTypeCollection
			, ItemFilterCollection itemFilterCollection
	) {
		this.items = items;
		this.itemTypeCollection = itemTypeCollection;
		this.itemFilterCollection = itemFilterCollection;
	}
	public void createRandomLoot(Loot loot, Player player) {
		for (DropItem item : items) {

			if (ItemTypeCollection.isItemTag(item.itemType.id)) {
				item = new DropItem(
						itemTypeCollection.getRandomItemByTag(item.itemType.id)
						, item.chance
						, item.quantity
				);
			} else if (ItemTypeCollection.isItemFilter(item.itemType.id)) {
				ItemFilter itemFilter = itemFilterCollection.getItemFilter(item.itemType.id);
				item = new DropItem(
						itemFilter.getRandomItem()
						, item.chance
						, item.quantity
				);
			}

			final int chanceRollBias = SkillController.getDropChanceRollBias(item, player);
			if (Constants.rollResult(item.chance, chanceRollBias)) {

				final int quantityRollBias = SkillController.getDropQuantityRollBias(item, player);
				int quantity = Constants.rollValue(item.quantity, quantityRollBias);

				loot.add(item.itemType, quantity);
			}
		}
	}

	// Unit test method. Not part of the game logic.
	public DropItem[] UNITTEST_getAllDropItems() {
		return items;
	}

	public static class DropItem {
		public final ItemType itemType;
		public final ConstRange chance;
		public final ConstRange quantity;
		public DropItem(ItemType itemType, ConstRange chance, ConstRange quantity) {
			this.itemType = itemType;
			this.chance = chance;
			this.quantity = quantity;
		}
	}
}

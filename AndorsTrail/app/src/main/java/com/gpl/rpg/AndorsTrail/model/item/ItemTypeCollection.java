package com.gpl.rpg.AndorsTrail.model.item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gpl.rpg.AndorsTrail.AndorsTrailApplication;
import com.gpl.rpg.AndorsTrail.controller.Constants;
import com.gpl.rpg.AndorsTrail.resource.parsers.ItemTypeParser;
import com.gpl.rpg.AndorsTrail.util.L;
import com.gpl.rpg.AndorsTrail.util.Range;

public final class ItemTypeCollection {
	private static final String ITEMTYPE_GOLD = "gold";

	private final HashMap<String, ItemType> itemTypes = new HashMap<String, ItemType>();
	private final HashMap<String, Set<ItemType>> tagList = new HashMap<String, Set<ItemType>>();

	public ItemType getItemType(String id) {
		if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
			if (!itemTypes.containsKey(id)) {
				L.log("WARNING: Cannot find ItemType for id \"" + id + "\".");
				return null;
			}
		}
		return itemTypes.get(id);
	}
	public List<ItemType> getItemTypesByTag(String tag) {
		tag = getItemTagID(tag);

		Set<ItemType> items = tagList.get(tag);
		if (items != null)
			return new ArrayList<>(items);
		return new ArrayList<>();
	}
	public ItemType getRandomItemByTag(String tag) {
		tag = getItemTagID(tag);

		List<ItemType> allItemsInTag = getItemTypesByTag(tag);
		int ran = Constants.rollValue(new Range(allItemsInTag.size()-1, 0));
		return allItemsInTag.get(ran);
	}

	public static boolean isGoldItemType(String itemTypeID) {
		if (itemTypeID == null) return false;
		return itemTypeID.equals(ITEMTYPE_GOLD);
	}

	public static boolean isItemTag(String itemTypeID) {
		if (itemTypeID == null) return false;
		return itemTypeID.toLowerCase().startsWith("tag:");
	}
	public static boolean isItemFilter(String itemTypeID) {
		if (itemTypeID == null) return false;
		return itemTypeID.toLowerCase().startsWith("filter:");
	}
	public static String getItemTagID(String itemTypeID) {
		if (isItemTag(itemTypeID))
			return itemTypeID.substring(4);
		else return itemTypeID;
	}
	public static String getItemFilterID(String itemTypeID) {
		if (isItemFilter(itemTypeID))
			return itemTypeID.substring(7);
		else return itemTypeID;
	}

	public void initialize(final ItemTypeParser parser, String input) {
		parser.parseRows(input, itemTypes);

		for (Map.Entry<String, ItemType> set : itemTypes.entrySet()) {
			for (String tag : set.getValue().itemTags) {
				tagList.computeIfAbsent(tag, k -> new HashSet<>()).add(set.getValue());
			}
		}
	}

	// Unit test method. Not part of the game logic.
	public HashMap<String, ItemType> UNITTEST_getAllItemTypes() {
		return itemTypes;
	}
}

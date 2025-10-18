package com.gpl.rpg.AndorsTrail.model.item;

import com.gpl.rpg.AndorsTrail.controller.Constants;
import com.gpl.rpg.AndorsTrail.util.Range;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ItemFilter {

    public final String id;
    private final List<ItemType> include;
    private final List<ItemType> exclude;


    public ItemFilter(
            String id
            , List<ItemType> include
            , List<ItemType> exclude
    ) {
        this.id = id;
        this.include = include;
        this.exclude = exclude;
    }

    public List<ItemType> getItemTypes() {
        if (include == null)
            return new ArrayList<>();

        Set<ItemType> itemsInFilter = new HashSet<>(include);
        if (exclude != null) {
            itemsInFilter.removeAll(exclude);
        }
        return new ArrayList<>(itemsInFilter);
    }

    public ItemType getRandomItem() {
        List<ItemType> allItemsInFilter = getItemTypes();
        int ran = Constants.rollValue(new Range(allItemsInFilter.size()-1, 0));
        return allItemsInFilter.get(ran);
    }
}

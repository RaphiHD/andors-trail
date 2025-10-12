package com.gpl.rpg.AndorsTrail.model.item;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ItemFilter {
    public final String id;
    private final List<ItemType> includeItems;
    private final List<String> includeTags;
    private final List<ItemType> excludeItems;
    private final List<String> excludeTags;

    private final ItemTypeCollection itemTypeCollection;

    public ItemFilter(
            String id
            , List<ItemType> includeItems
            , List<String> includeTags
            , List<ItemType> excludeItems
            , List<String> excludeTags
            , ItemTypeCollection itemTypeCollection
    ) {
        this.id = id;
        this.includeItems = includeItems;
        this.includeTags = includeTags;
        this.excludeItems = excludeItems;
        this.excludeTags = excludeTags;
        this.itemTypeCollection = itemTypeCollection;
    }

    public List<ItemType> getItemTypes() {
        if (includeItems == null && includeTags == null)
            return new ArrayList<>();

        Set<ItemType> itemsInFilter = new HashSet<>();
        if (includeItems != null) {
            itemsInFilter.addAll(includeItems);
        }
        if (includeTags != null) {
            for (String tag : includeTags) {
                itemsInFilter.addAll(itemTypeCollection.getItemTypesByTag(tag));
            }
        }
        if (excludeItems != null) {
            itemsInFilter.removeAll(excludeItems);
        }
        if (excludeTags != null) {
            for (String tag : excludeTags) {
                itemsInFilter.removeAll(itemTypeCollection.getItemTypesByTag(tag));
            }
        }
        return new ArrayList<>(itemsInFilter);
    }
}

package com.gpl.rpg.AndorsTrail.model.item;

import com.gpl.rpg.AndorsTrail.controller.Constants;
import com.gpl.rpg.AndorsTrail.util.Range;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ItemFilter {
    public static enum FilterType {
        any
        ,all
        ,exact;

        public static ItemFilter.FilterType fromString(String s, ItemFilter.FilterType default_) {
            if (s == null) return default_;
            return valueOf(s);
        }
    }

    private final FilterType filterType;
    private final int filterTypeModifier;

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
            , FilterType filterType
            , int filterTypeModifier
    ) {
        this.id = id;
        this.includeItems = includeItems;
        this.includeTags = includeTags;
        this.excludeItems = excludeItems;
        this.excludeTags = excludeTags;
        this.itemTypeCollection = itemTypeCollection;
        this.filterType = filterType;
        this.filterTypeModifier = filterTypeModifier;
    }

    public List<ItemType> getItemTypes() {
        if (includeItems == null && includeTags == null)
            return new ArrayList<>();

        Set<ItemType> itemsInFilter = new HashSet<>();
        if (includeItems != null) {
            itemsInFilter.addAll(includeItems);
        }
        if (includeTags != null) {
            switch (filterType) {
                case any:
                    itemsInFilter.addAll(getItemTypesAny());
                    break;
                case all:
                    itemsInFilter.addAll(getItemTypesAll());
                    break;
                case exact:
                    itemsInFilter.addAll(getItemTypesExact());
                    break;
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

    public ItemType getRandomItem() {
        List<ItemType> allItemsInFilter = getItemTypes();
        int ran = Constants.rollValue(new Range(0, allItemsInFilter.size()));
        return allItemsInFilter.get(ran);
    }

    private List<ItemType> getItemTypesAny() {
        Set<ItemType> result = new HashSet<>();
        if (includeTags != null) {
            for (String tag : includeTags) {
                result.addAll(itemTypeCollection.getItemTypesByTag(tag));
            }
        }
        return new ArrayList<>(result);
    }
    private List<ItemType> getItemTypesAll() {
        Set<ItemType> result = new HashSet<>();
        boolean first = true;
        for (String tag : includeTags) {
            Set<ItemType> taggedItems = new HashSet<>(itemTypeCollection.getItemTypesByTag(tag));
            if (first) {
                result.addAll(taggedItems);
                first = false;
            } else {
                result.retainAll(taggedItems);
            }
        }
        return new ArrayList<>(result);
    }
    private List<ItemType> getItemTypesExact() {
        Set<ItemType> possible = new HashSet<>();
        Set<ItemType> result = new HashSet<>();
        for (String tag : includeTags) {
            possible.addAll(itemTypeCollection.getItemTypesByTag(tag));
        }
        for (ItemType item : possible) {
            Set<String> tags = new HashSet<>(item.itemTags);
            if (tags.containsAll(includeTags) && tags.size() == includeTags.size()) {
                result.add(item);
            }
        }
        return new ArrayList<>(result);
    }
}

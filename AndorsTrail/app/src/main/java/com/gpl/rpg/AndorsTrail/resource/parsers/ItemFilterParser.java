package com.gpl.rpg.AndorsTrail.resource.parsers;

import com.gpl.rpg.AndorsTrail.model.item.ItemFilter;
import com.gpl.rpg.AndorsTrail.model.item.ItemType;
import com.gpl.rpg.AndorsTrail.model.item.ItemTypeCollection;
import com.gpl.rpg.AndorsTrail.resource.parsers.json.JsonCollectionParserFor;
import com.gpl.rpg.AndorsTrail.resource.parsers.json.JsonFieldNames;
import com.gpl.rpg.AndorsTrail.util.Pair;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ItemFilterParser extends JsonCollectionParserFor<ItemFilter> {

    private final ItemTypeCollection itemTypeCollection;

    public ItemFilterParser(final ItemTypeCollection itemTypeCollection) {
        this.itemTypeCollection = itemTypeCollection;
    }

    @Override
    public Pair<String, ItemFilter> parseObject(JSONObject o) throws JSONException {
        final String id = o.getString(JsonFieldNames.ItemFilter.itemFilterID);
        final JSONArray includeItemsJson = o.optJSONArray(JsonFieldNames.ItemFilter.includeItems);
        final JSONArray includeTagsJson = o.optJSONArray(JsonFieldNames.ItemFilter.includeTags);
        final JSONArray excludeItemsJson = o.optJSONArray(JsonFieldNames.ItemFilter.excludeItems);
        final JSONArray excludeTagsJson = o.optJSONArray(JsonFieldNames.ItemFilter.excludeTags);
        final String filterType = o.optString(JsonFieldNames.ItemFilter.filterType, null);
        final int filterTypeModifier = o.optInt(JsonFieldNames.ItemFilter.filterTypeModifier, 1);

        List<ItemType> includeItems = new ArrayList<ItemType>();
        List<String> includeTags = new ArrayList<String>();
        List<ItemType> excludeItems = new ArrayList<ItemType>();
        List<String> excludeTags = new ArrayList<String>();

        if (includeItemsJson != null) {
            for (int i = 0; i < includeItemsJson.length(); i++) {
                includeItems.add(itemTypeCollection.getItemType(includeItemsJson.getString(i)));
            }
        }
        if (includeTagsJson != null) {
            for (int i = 0; i < includeTagsJson.length(); i++) {
                includeTags.add(includeTagsJson.getString(i));
            }
        }
        if (excludeItemsJson != null) {
            for (int i = 0; i < excludeItemsJson.length(); i++) {
                excludeItems.add(itemTypeCollection.getItemType(excludeItemsJson.getString(i)));
            }
        }
        if (excludeTagsJson != null) {
            for (int i = 0; i < excludeTagsJson.length(); i++) {
                excludeTags.add(excludeTagsJson.getString(i));
            }
        }

        final ItemFilter itemFilter = new ItemFilter(
                id
                , includeItems
                , includeTags
                , excludeItems
                , excludeTags
                , itemTypeCollection
                , ItemFilter.FilterType.fromString(filterType, ItemFilter.FilterType.any)
                , filterTypeModifier
        );
        return new Pair<String, ItemFilter>(id, itemFilter);
    }
}

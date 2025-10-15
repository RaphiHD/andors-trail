package com.gpl.rpg.AndorsTrail.model.script;

import java.util.HashMap;
import java.util.Map;

public class ConversationContext {
    private Map<String, Object> context;

    public ConversationContext () {
        this.context = new HashMap<>();
    }
    public ConversationContext (Map<String, Object> context){
        this.context = context;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void addContext (String key, Object value) {
        context.put(key, value);
    }

    public Object get (String key) {
        return context.get(key);
    }

}

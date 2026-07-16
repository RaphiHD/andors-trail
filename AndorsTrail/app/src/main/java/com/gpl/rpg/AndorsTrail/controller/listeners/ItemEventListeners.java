package com.gpl.rpg.AndorsTrail.controller.listeners;

import com.gpl.rpg.AndorsTrail.util.ListOfListeners;

public class ItemEventListeners extends ListOfListeners<ItemEventListener> implements ItemEventListener {


    private final ListOfListeners.Function1<ItemEventListener, String> onItemUseStartedConversation = new ListOfListeners.Function1<ItemEventListener, String>() {
        @Override public void call(ItemEventListener listener, String phraseID) { listener.onItemUseStartedConversation(phraseID); }
    };

    @Override
    public void onItemUseStartedConversation(String phraseID) {
        callAllListeners(this.onItemUseStartedConversation, phraseID);
    }

}

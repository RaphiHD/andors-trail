package com.gpl.rpg.AndorsTrail.model.conversation;

import com.gpl.rpg.AndorsTrail.model.script.ConversationContext;
import com.gpl.rpg.AndorsTrail.model.script.Requirement;

import java.util.HashMap;
import java.util.Map;

public final class Reply {
	public final String text;
	public final String nextPhrase;
	public final Requirement[] requires;

	// While currently only used to pass selected items, this may be used for other purposes too,
	// e.g. passing map or npc ids or other data like Strings and ints
	// potentially even allowing players to input their own data which then gets passed to the next dialog
	public final ConversationContext context;

	public boolean hasRequirements() {
		return requires != null;
	}

	public Reply(
			String text
			, String nextPhrase
			, Requirement[] requires
	) {
		this.text = text;
		this.nextPhrase = nextPhrase;
		this.requires = requires;
		this.context = new ConversationContext();
	}
}

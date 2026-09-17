# Conversations / Dialogue

## Overview

Every NPC that isn't purely hostile can be talked to. A conversation is a tree
of **phrases** (NPC/narrator lines) and **replies** (player response options)
identified by string IDs. Talking to an NPC starts the tree at a phrase ID
looked up from the NPC's monster type; picking a reply moves to another named
phrase, optionally after checking `Requirement`s (to decide whether the reply
is even offered) and running `ScriptEffect`s (to reward the player or mutate
world state) attached to phrases. The same phrase/reply engine is also reused
outside of "talking to someone": map script tiles, sign tiles, key-area
entry, resting, and a periodic "passive achievement" check all run through
the identical phrase-walking state machine, just without a visible dialogue
UI in most cases.

## Key classes/files

| File | Role |
|---|---|
| [ConversationController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/ConversationController.java) | Owns the `ConversationStatemachine` inner class that walks the phrase graph; evaluates `Requirement`s and applies `ScriptEffect`s (shared with quests — see below) |
| [ConversationCollection.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/conversation/ConversationCollection.java) | In-memory map of `phraseID -> Phrase`, populated by parsing one conversation JSON resource file; defines the reserved phrase/reply IDs (`X`, `S`, `F`, `R`, `N`) |
| [Phrase.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/conversation/Phrase.java) | A single dialogue node: `message`, `replies[]`, `scriptEffects[]`, `switchToNPC` |
| [Reply.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/conversation/Reply.java) | A single reply option: `text`, `nextPhrase` ID, `requires[]` |
| [ConversationLoader.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/resource/ConversationLoader.java) | Lazily loads (and parses on first use) the resource file that contains a given phrase ID |
| [ConversationListParser.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/resource/parsers/ConversationListParser.java) | Parses conversation JSON into `Phrase`/`Reply`/`Requirement`/`ScriptEffect` objects |
| [ConversationActivity.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/activity/ConversationActivity.java) | The dialogue UI activity; implements `ConversationStateListener` and drives the state machine from user input |
| [Dialogs.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/Dialogs.java) | Builds the `Intent` that launches `ConversationActivity` (`showConversation` / `showMapScriptMessage`) |
| [Requirement.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/script/Requirement.java) | Gating condition type used on `Reply.requires` (documented in full in [quests.md](quests.md)) |
| [ScriptEffect.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/script/ScriptEffect.java) | World-mutation/reward type used on `Phrase.scriptEffects` (documented in full in [quests.md](quests.md)) |

## How it works

### Data shape

A conversation JSON file is a list of phrase objects (see
`JsonFieldNames.Phrase`/`Reply`/`ReplyRequires`/`PhraseReward` for the exact
key names: `id`, `message`, `rewards`, `replies`, `switchToNPC` on a phrase;
`text`, `nextPhraseID`, `requires` on a reply; `requireType`/`requireID`/
`value`/`negate` on a requirement; `rewardType`/`rewardID`/`value`/`mapName`
on a script effect/"reward"). Each phrase has:

- `message` — the NPC's line. `Phrase.message == null` marks a "silent"
  routing phrase (see below).
- `replies[]` — the options the player can choose from at this phrase.
- `scriptEffects[]` — effects that fire unconditionally the moment this
  phrase is *reached* (`Phrase` javadoc: "If this phrase is reached, all
  these effects will run").
- `switchToNPC` — optionally re-targets the conversation at a different
  spawned monster on the current map (looked up by
  `map.findSpawnedMonster(...)`), used for multi-actor scenes.

A `Reply` has `text` (the button label), `nextPhrase` (the phrase ID to go to
if chosen) and `requires[]` (an AND-list of `Requirement`s that gate whether
the reply is offered at all).

`ConversationCollection` reserves five special IDs that are not real phrases:
`X` (`PHRASE_CLOSE`), `S` (`PHRASE_SHOP`), `F` (`PHRASE_ATTACK`), `R`
(`PHRASE_REMOVE`) as `nextPhrase`/entry targets, and `N` (`REPLY_NEXT`) as
reply text for a synthetic "continue" reply (see below).

### Loading

Conversation files are indexed once at startup: `ResourceLoader` parses every
configured conversation resource into a throwaway `ConversationCollection`
just to collect the set of phrase IDs it contains, then registers
`resourceID -> {phraseIDs}` in `world.conversationLoader`
(`ConversationLoader.addIDs`). The actual `Phrase` objects from that pass are
discarded. Each `ConversationStatemachine` owns its own real
`ConversationCollection` cache; `ConversationLoader.loadPhrase` looks a phrase
up there first, and only re-parses (and translates via `TranslationLoader`)
the owning resource file — populating that instance's `ConversationCollection`
with every phrase from that file — the first time a phrase from it is needed.

### Starting and driving a conversation

`ConversationController.ConversationStatemachine` is the engine. A caller
constructs one with a `ConversationStateListener`, calls `setCurrentNPC(...)`,
then `proceedToPhrase(res, phraseID, applyScriptEffects, displayPhraseMessage)`
to enter the tree at a starting phrase ID. In the normal "talk to NPC" path,
`MapController.steppedOnMonster` looks up the NPC's starting phrase from
`Monster.getPhraseID()` (`MonsterType.phraseID`) and fires
`onPlayerStartedConversation`, which `Dialogs.showConversation` turns into an
`Intent` for `ConversationActivity` (data URI
`content://.../conversation/<phraseID>`). `ConversationActivity.onCreate`
constructs its own `ConversationStatemachine` and calls `proceedToPhrase` with
that phrase ID.

`proceedToPhrase` loops via `proceedToPhraseInternal` (following
`nextPhrase`/reserved IDs) until it lands on a phrase that requires listener
interaction, i.e. it's iterative rather than one-node-per-call:

1. If `phraseID` is one of the reserved IDs, the conversation ends
   immediately: `X` -> `onConversationEnded()`; `S` -> hands off to the shop
   UI via `onConversationEndedWithShop(npc)`; `F` -> forces the NPC hostile
   and enters combat (`endConversationWithCombat`); `R` -> removes the NPC
   from the map via `monsterSpawnController.remove(...)`
   (`endConversationWithRemovingNPC`).
2. Otherwise `setCurrentPhrase` loads the `Phrase` (via `ConversationLoader`,
   see above) and, if it declares a `switchToNPC`, re-targets `npc`.
3. If `applyScriptEffects` is true, `applyScriptEffectsForPhrase` runs every
   entry in `phrase.scriptEffects` (see next section) and notifies
   `onScriptEffectsApplied` if anything happened.
4. If `phrase.message == null` (a silent/routing phrase), the state machine
   picks the **first** reply whose requirements pass
   (`canSelectReply`), applies that reply's own requirement side effects
   (`applyReplyEffect`), and loops to its `nextPhrase` — this is how
   conditional branching without player-visible choices works (e.g. "if quest
   X is done, silently jump to phrase A, else phrase B").
5. If there is a message and `displayPhraseMessage` is true, it's shown via
   `onTextPhraseReached(message, npc, phraseID)` (player-name/faction-score
   placeholders substituted by `replacePlayerName`, see Gotchas).
6. If the phrase has exactly one reply and its text equals `N`
   (`REPLY_NEXT`, `hasOnlyOneNextReply()`), the UI is told to show a plain
   "Next" affordance instead of a radio-button list
   (`onConversationCanProceedWithNext`) — this is the common case for a
   simple monologue.
7. Otherwise, every reply that passes `canSelectReply` is offered to the
   listener via `onConversationHasReply(reply, displayText)`; replies that
   fail their requirements are simply never shown (no "greyed out" option).

When the player picks a reply (`playerSelectedReply`, or
`playerSelectedNextStep` for the single-"Next"-reply case), the controller
first applies that reply's requirement side effects
(`applyReplyEffect`/`requirementFulfilled` — e.g. removing consumed items,
unequipping gear) and then calls `proceedToPhrase` again targeting
`reply.nextPhrase`, continuing the loop.

### Requirements gating replies

`Reply.requires` is an array of `Requirement`s (same type used by quest
stages — see [quests.md](quests.md) for the full enum). `canSelectReply`
requires **all** of them to pass via
`ConversationController.canFulfillRequirement(world, requirement, npc)`. Two
`RequirementType`s are conversation/monster-specific and only make sense here
(they pass a `Monster npc` that quest-progress checks don't have):
`onMap` (`npc.currentMapID` matches), `inArea` (`npc.area.areaID` matches),
and `isTravelling` (`npc.travelDestination != null`). Some requirement types
also have a *consuming* side effect when the reply is actually chosen
(`requirementFulfilled`): `inventoryRemove` deducts the item/gold, and
`wearRequire`'s `wearRemove` variant unequips the item via
`itemController.removeEquippedItem`. Note the asymmetry: side effects only
run for the reply that was picked (`applyReplyEffect` inside
`playerSelectedReply`) and — separately — for the single auto-selected reply
of a silent routing phrase (`proceedToPhraseInternal` step 4); they never run
just from a requirement being *checked* for display purposes.

### Script effects triggered by reaching a phrase

`Phrase.scriptEffects` (JSON key `rewards`) fire once, unconditionally, the
instant `proceedToPhraseInternal` lands on that phrase (if
`applyScriptEffects` is true for that call) — not tied to any particular
reply. `applyScriptEffectsForPhrase` dispatches each `ScriptEffect` by
`ScriptEffect.ScriptEffectType` in `applyScriptEffect`'s switch, covering
things far beyond quest rewards: granting items/exp/drop-list loot, actor
conditions and immunities, skill increases, faction/alignment math
(`alignmentChange`/`Set`/`Add`/`Sub`/`Mult`/`Div`/`ToReg*`/`FromReg*`),
`questProgress`/`removeQuestProgress`, world timers (`createTimer`),
spawn-area activation (`spawnAll`, `removeSpawnArea`, `deactivateSpawnArea`),
map-object-group toggling (`activateMapObjectGroup`/
`deactivateMapObjectGroup`), map color filters (`changeMapFilter`), forcing a
map change (`mapchange`), changing the player's sprite (`changeIcon`), and
sending the current NPC off on a scripted journey
(`setDestination` -> `monsterMovementController.beginTravel`). Accumulated
loot/exp/conditions/skills/quest-progress are collected into a
`ScriptEffectResult` and reported once to the listener via
`onScriptEffectsApplied`; `ConversationActivity` renders these as "reward"
lines in the dialogue log (`onScriptEffectsApplied` -> `addRewardMessage`).

## How other systems hook in / cross-references

- **Quests**: `questProgress` / `removeQuestProgress` script effects are how
  dialogue advances or rolls back quest stages, and `questProgress` /
  `questLatestProgress` requirements are how dialogue reads current quest
  state to branch or gate replies. Requirement/ScriptEffect semantics
  (quest stages, faction alignment registers, timers, etc.) are documented in
  full in [quests.md](quests.md) — this document only describes how the
  conversation engine invokes them.
- **Maps**: the *same* `ConversationStatemachine` machinery is reused for
  non-dialogue scripted moments. `MapController` keeps a persistent
  `mapScriptExecutor` instance (constructed once, with a listener wired up
  elsewhere) and calls `proceedToPhrase` directly (no `ConversationActivity`)
  for: `sign`/`script`-type `MapObject`s stepped on by the player
  (`runScriptInArea`), key-area entry when the entering requirement fails
  (`MapController.canEnterKeyArea`), and a periodic
  `Constants.PASSIVE_ACHIEVEMENT_CHECK_PHRASE` phrase run every time
  `lotsOfTimePassed()` executes (e.g. after resting). Because these reuse
  phrase IDs and `Phrase`/`Reply` objects, "map scripts" and "quest achievement
  checks" are authored as ordinary (often silent, `message == null`)
  conversation phrases rather than a separate system.
- **Shops / combat**: reaching phrase `S` or `F` is how dialogue hands off to
  the shop UI (`onConversationEndedWithShop`, then `Dialogs`/`ShopActivity`)
  or forces combat (`endConversationWithCombat`, which calls
  `npc.forceAggressive()` and `combatController.enterCombat(...)`).
- **Monster removal**: phrase `R` deletes the NPC from the map via
  `monsterSpawnController.remove(...)` — used for e.g. an NPC that
  disappears after a one-time exchange.

## Gotchas / non-obvious behavior

- **Reserved single-letter phrase/reply IDs are magic strings, not enum
  values.** `X`, `S`, `F`, `R` as a `nextPhrase`/starting phrase ID, and `N`
  as reply text, are compared case-insensitively
  (`phraseID.equalsIgnoreCase(...)`) in `proceedToPhraseInternal` before any
  phrase lookup happens — a real authored phrase can never use these IDs.
- **Requirements that fail are invisible, not disabled.** `canSelectReply`
  simply filters the reply out of what's offered; there's no "greyed out but
  visible" affordance, so failing a hidden `Requirement` looks identical to
  the reply not existing.
- **`Reply.hasRequirements()` treats an empty-vs-null array differently only
  at the type level** — `hasRequirements()` returns `requires != null`, so a
  reply parsed with no `requires` key gets `requires == null` and is always
  selectable; there's no distinct "empty array" case in practice given how
  the parser builds it.
- **Script effects fire on *reaching* a phrase, independent of which reply
  led there** — including phrases reached automatically via a silent
  routing phrase's chosen branch, or via `mapScriptExecutor` calls that pass
  `applyScriptEffects=true`. A phrase's `scriptEffects` are not attached to
  individual replies.
- **Silent phrases auto-pick the *first* eligible reply and keep looping** —
  a `message == null` phrase with no reply passing its requirements would
  fall through without returning a `nextPhrase` (the loop in
  `proceedToPhrase` simply ends because `proceedToPhraseInternal` returns
  `null` at the bottom without invoking any listener callback), effectively
  ending the conversation silently with no UI feedback. This depends on
  content always providing a catch-all (often requirement-less) reply.
- **Two-pass loading of conversation files.** Every conversation resource
  file is fully parsed once at startup purely to build the phraseID-to-file
  index (`ResourceLoader` / `ConversationLoader.addIDs`), and that parse
  result is thrown away; the file is parsed again (and translated again)
  the first time one of its phrases is actually needed at runtime, this time
  into the per-`ConversationStatemachine` `ConversationCollection`. Each
  `ConversationStatemachine` instance (a new one is created per
  `ConversationActivity`, plus the one long-lived `mapScriptExecutor` in
  `MapController`) has its own independent cache, so the same file can be
  parsed multiple times across different conversation sessions.
- **`switchToNPC` silently no-ops if the named monster isn't currently
  spawned** — `setCurrentPhrase` calls
  `map.findSpawnedMonster(currentPhrase.switchToNPC)` and passes whatever
  comes back (including `null`) straight to `setCurrentNPC`, with no
  fallback or error handling visible in `ConversationController`.
- **`DEVELOPMENT_DEBUGMESSAGES` provides a soft failure mode for missing
  phrases**: if a `phraseID` can't be loaded, `setCurrentPhrase` logs a trace
  and (only in that debug build flag) substitutes a placeholder `Phrase`
  reading `(phrase "<id>" not implemented yet)` instead of crashing —
  outside that flag, `loadPhrase` returning `null` would NPE on
  `currentPhrase.replies`/`.message` shortly after.
- **`ConversationController` is two things in one file**: an instance
  (`applyScriptEffectsForPhrase` and friends, holding `controllers`/`world`)
  used for script-effect execution, plus a nested `static` class
  `ConversationStatemachine` that is the actual per-conversation runtime
  state and is largely independent of the outer instance except for calling
  back into `controllers.conversationController.applyScriptEffectsForPhrase`.
  Several helper methods (`canFulfillRequirement`, `requirementFulfilled`)
  are `public static` and are reused directly by other controllers (e.g.
  `MapController.canEnterKeyArea`) without going through a conversation at
  all.

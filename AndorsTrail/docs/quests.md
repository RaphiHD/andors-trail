# Quests & World Events

## Overview

A "quest" in Andor's Trail is nothing more than a named sequence of integer
**progress stages** that the player can reach. There is no dedicated quest
engine: quest stages are granted and read through the same generic
`Requirement` / `ScriptEffect` mechanism that also drives conversations, map
scripts, and area triggers. A `Requirement` of type `questProgress` gates
something (a reply, a map area, a map-section replacement) behind a quest
stage the player must already have; a `ScriptEffect` of type `questProgress`
grants the player a new stage (optionally with an experience reward). The
player's current standing in every quest is just a set of "reached stage
numbers" per quest ID, stored on `Player`. This document covers the quest
data model and the `Requirement`/`ScriptEffect` system in general — since the
same two classes also gate/trigger everything else in the game (dialogue,
map events, item drops, alignment, spawning, etc.).

## Key classes/files

| File | Role |
|---|---|
| [Quest.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/quest/Quest.java) | Static definition of one quest: an ID, a display name, an array of `QuestLogEntry` stages, whether it shows in the quest log, and a sort order. |
| [QuestLogEntry.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/quest/QuestLogEntry.java) | One stage of a quest: `progress` number, `logtext` shown in the quest log, `rewardExperience`, and `finishesQuest` flag. |
| [QuestCollection.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/quest/QuestCollection.java) | Static, immutable lookup table of all `Quest` objects, keyed by `questID`. Loaded once via `QuestParser`. Exposed on `WorldContext` as `world.quests`. |
| [QuestProgress.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/quest/QuestProgress.java) | A tiny value pair `(questID, progress)` — not player state itself, just a "which stage of which quest" reference used to grant/check/remove progress. Has a `parseQuestProgress("questID:stageNumber")` factory used when reading `questID:stage`-style strings from resource/save data. |
| [Requirement.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/script/Requirement.java) | Generic gating condition. `RequirementType` enum covers quest progress, inventory, equipment, skills, kills, timers, faction/alignment score, date/time, map/area location, and more. Carries `requireID`, `value`, a `negate` flag, and an optional `chance` range (for `random`). |
| [ScriptEffect.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/script/ScriptEffect.java) | Generic side effect. `ScriptEffectType` enum covers granting/removing quest progress, loot/drop lists, skill increases, actor conditions, alignment math, spawning/map-object activation, map changes, icon changes, travel destinations, etc. Carries `effectID`, `value`, and `mapName`. |
| [WorldEventListener.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/WorldEventListener.java) | Observer interface for world/UI-facing events (conversations started, stepped on sign/key/rest area, loot found/picked up, rested, died). Does **not** fire on quest-progress changes directly — see Gotchas. |
| [WorldEventListeners.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/WorldEventListeners.java) | Fan-out multiplexer (`ListOfListeners`) that calls every registered `WorldEventListener` for a given event; owned by `MapController.worldEventListeners`. |
| [ConversationController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/ConversationController.java) | The actual engine: evaluates `Requirement`s (`canFulfillRequirement`), applies their side effects (`requirementFulfilled`), and applies `ScriptEffect`s (`applyScriptEffectsForPhrase`/`applyScriptEffect`), including `questProgress`/`removeQuestProgress`. |
| [MapController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/MapController.java) | Drives map-level `Requirement`/`ScriptEffect` evaluation: key-area entry gating, map-section replacements, and running conversation "scripts" attached to map objects. |
| [QuestParser.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/resource/parsers/QuestParser.java) | Parses the quest JSON resource into `Quest`/`QuestLogEntry` objects, assigning an incrementing `sortOrder` in file order. |
| [Player.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/actor/Player.java) | Owns the actual per-save quest state: `questProgress` map plus `hasExactQuestProgress`, `isLatestQuestProgress`, `addQuestProgress`, `removeQuestProgress`, etc. |
| [HeroinfoActivity_Quests.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/activity/fragment/HeroinfoActivity_Quests.java) | Quest log UI fragment: lists quests the player has any progress in, filters active/completed/all, renders each reached stage's `logtext`. |

## How it works

### Quest structure

A `Quest` (`Quest.java`) is just an ID, a display name, and a sorted array of
`QuestLogEntry` "stages" (`stages` must be in ascending `progress` order,
per the field comment — `QuestParser` explicitly sorts them with
`sortByQuestProgress` after parsing). `Quest.getQuestLogEntry(progress)`
linearly scans `stages` for a matching `progress` number and returns `null`
(with a `DEVELOPMENT_VALIDATEDATA` warning) if not found. There is no
enforced continuity between stage numbers — they are whatever integers the
quest data uses; `QuestLogEntry.finishesQuest` marks which stage(s) count as
"quest complete". `Quest.isCompleted(Player)` checks whether the player has
*exactly* reached any stage flagged `finishesQuest`.

`QuestCollection` is a flat `HashMap<String, Quest>` loaded once by
`QuestParser.parseRows` and exposed read-only via `world.quests`. It has no
mutation methods at runtime — all per-player state lives elsewhere (on
`Player`).

### Player-side progress tracking

`Player` stores quest progress as:

```java
private final LinkedHashMap<String, LinkedHashSet<Integer>> questProgress;
```

This is the crucial design point: **a quest's progress is a *set* of stage
numbers the player has ever reached, not a single "current stage" pointer.**
Key `Player` methods:

- `hasExactQuestProgress(questID, progress)` — was this exact stage ever
  granted?
- `isLatestQuestProgress(questID, progress)` — has this stage been reached,
  *and* is it the highest-numbered stage reached so far for that quest?
  (Loops over all recorded stages and fails if any is greater.)
- `addQuestProgress(QuestProgress)` — adds a stage to the set; returns
  `false` (no-op) if that exact stage was already present, `true` if it was
  newly added.
- `removeQuestProgress(QuestProgress)` — removes one specific stage number
  from the set (used by the `removeQuestProgress` script effect, e.g. to
  walk a quest backward or clear a flag stage).
- `hasAnyQuestProgress(questID)` / `getAllQuestProgressIDs()` /
  `getQuestProgress(questID)` — used by the quest log UI to enumerate what
  to display.

Because it's a set, older/lower stages are **not** automatically cleared
when a higher stage is granted — a quest can have several stages "true" at
once unless the quest data explicitly removes earlier ones with a
`removeQuestProgress` effect. This is exactly why `questLatestProgress`
exists as a separate requirement type from `questProgress` (see below).

`QuestLogEntry.rewardExperience` and `.logtext` are per-stage: reaching a
stage can hand out XP once, and its `logtext` (if non-empty) is what shows
up as a bullet point under that quest in the log
(`HeroinfoActivity_Quests.reloadQuests`, which iterates
`player.getQuestProgress(questID)` and matches each number back to its
`QuestLogEntry` for display text).

### Requirement — gating

`Requirement` is evaluated by the single static method
`ConversationController.canFulfillRequirement(WorldContext, Requirement, Monster)`
(the `Monster` parameter is only used for the NPC-relative types `onMap`,
`inArea`, `isTravelling`). It's a big `switch` over `RequirementType`
returning a boolean, and the final result is XOR'd with `requirement.negate`
(`return requirement.negate != result;`), so every requirement type
implicitly supports "must NOT satisfy X" for free.

For quests specifically:

- `questProgress` → `player.hasExactQuestProgress(requireID, value)` — "has
  the player ever reached this exact stage" (order/recency irrelevant).
- `questLatestProgress` → `player.isLatestQuestProgress(requireID, value)` —
  "is this stage the *furthest* the player has gotten in this quest" — the
  idiom used to gate dialogue/content on "player is currently exactly at
  this point in the quest, not further".

Other requirement types worth knowing about (all evaluated in the same
switch, since quest gating is frequently combined with them): `inventoryKeep`
/`inventoryRemove` (has item, gold checked via
`ItemTypeCollection.isGoldItemType`), `wear`/`wearRemove`, `skillLevel`/
`skillIncrease` (the latter checks `canLevelupSkillWithQuest`, i.e. whether
a skill *could* still be raised), `killedMonster`/`usedItem`/`spentGold`/
`consumedBonemeals` (all backed by `GameStatistics` counters), `timerElapsed`
(`world.model.worldData.hasTimerElapsed`), `hasActorCondition`,
`factionScore`/`factionScoreEquals` (alignment/reputation), `date`/`time`
(+`Equals` variants, from `world.model.worldData`), `random` (rolls
`requirement.chance` via `Constants.rollResult`), and the monster-relative
`onMap`/`inArea`/`isTravelling`.

`Requirement.isValid()` is a data-validation helper (only meaningful under
`AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA`) checking that each type
has the fields it needs (e.g. `requireID != null`, `value >= 0`, or a
non-null `chance` for `random`).

Some requirement types also have a **side effect when satisfied**, applied
by the separate static method `ConversationController.requirementFulfilled`:
`inventoryRemove` actually deducts the item/gold from inventory (and logs
spent gold to `GameStatistics`), and `wearRemove` unequips the item via
`controllers.itemController.removeEquippedItem`. This is called immediately
after a reply/area is confirmed as selectable, i.e. checking a requirement
and "consuming" it are two separate calls that callers must both invoke (see
below).

### Where Requirements are evaluated

- **Conversations** (`ConversationController.ConversationStatemachine`): a
  `Reply` may carry `requires: Requirement[]`. `canSelectReply` filters which
  replies are even offered to the player (and which one is auto-picked when
  a phrase has no message, i.e. an automatic branch); `applyReplyEffect` is
  invoked whenever a reply is actually taken, calling
  `requirementFulfilled` for each of its requirements (so choosing a reply
  that required an item removes that item). See
  [conversations.md](conversations.md) for the phrase/reply data model
  itself.
- **Map key areas**: `MapController.canEnterKeyArea(MapObject area)` checks
  `area.enteringRequirement` (a single `Requirement` on `MapObject`, e.g. "do
  you have the key item / the right quest stage") via
  `canFulfillRequirement`, and if satisfied, immediately calls
  `requirementFulfilled` (consuming the key item, etc.) and lets the player
  through. If not satisfied, it fires
  `worldEventListeners.onPlayerSteppedOnKeyArea(area)` and still runs any
  script attached to that area (`runScriptInArea`).
- **Map section replacements**: `MapController.applyReplacements` iterates a
  map's `ReplaceableMapSection`s; `satisfiesCondition` wraps
  `canFulfillRequirement(world, replacement.requirement)` (no `Monster`
  context) to decide whether a map layout swap (e.g. a door opening, rubble
  clearing) should apply, then calls `requirementFulfilled` once it does.
  See [maps-world.md](maps-world.md) for `ReplaceableMapSection`/map-layout
  details.

### ScriptEffect — side effects

`ScriptEffect`s only ever come from a conversation `Phrase.scriptEffects`
array, applied by `ConversationController.applyScriptEffectsForPhrase` (via
`applyScriptEffect`'s switch) whenever the conversation state machine lands
on that phrase (`ConversationStatemachine.proceedToPhraseInternal`, if
`applyScriptEffects` is true — which it always is for normal conversation
flow and for scripted map phrases, see below). Effects accumulate into a
`ScriptEffectResult` (loot, actor conditions, skill increases, quest
progress list) which is then applied in bulk (loot added to inventory, XP
added via `actorStatsController.addExperience`) and reported to the
conversation UI via `ConversationStateListener.onScriptEffectsApplied`.

Quest-relevant effect types:

- `questProgress` → `addQuestProgressReward`: builds a `QuestProgress` and
  calls `player.addQuestProgress`. **Only if the stage was newly added**
  (not already present) does it look up the `QuestLogEntry` for that stage
  and add `stage.rewardExperience` to the loot/XP result — re-triggering a
  phrase that already granted a stage does not re-award XP.
- `removeQuestProgress` → `addRemoveQuestProgressReward`: calls
  `player.removeQuestProgress`, clearing one specific stage number.

Non-quest effect types (for context, since they're triggered by the exact
same phrases and often combined with quest effects): `dropList`/`giveItem`
(loot), `skillIncrease` (`SkillController.levelUpSkillByQuest` — note the
name implies these level-ups are quest-driven grants, not player-spent skill
points — see [skills.md](skills.md)), `actorCondition`/
`actorConditionImmunity` (see [conditions-effects.md](conditions-effects.md)),
the `alignment*` family (faction/reputation math, with three general-purpose
"registers" `FACTION_SCORE_CALC_REGISTER1/2/3_NAME` for chained
arithmetic), `createTimer` (`world.model.worldData.createTimer`, paired with
the `timerElapsed` requirement), `spawnAll`/`removeSpawnArea`/
`deactivateSpawnArea`/`activateMapObjectGroup`/`deactivateMapObjectGroup`
(monster spawning and map object toggling — see
[maps-world.md](maps-world.md) and [monsters.md](monsters.md)),
`changeMapFilter`/`mapchange`/`changeIcon`/`setDestination` (visual/map
state and NPC travel via `monsterMovementController.beginTravel`).

### Where conversation "scripts" get triggered from map actions

`ConversationController.ConversationStatemachine` isn't only used for
player-initiated NPC dialogue — `MapController` keeps its own instance,
`mapScriptExecutor`, created in `prepareScriptsOnCurrentMap()`, and drives it
as a general-purpose script runner (no visible dialogue window unless a
phrase happens to have a `message`):

- `runScriptInArea(MapObject o)` calls
  `mapScriptExecutor.proceedToPhrase(res, o.id, true, true)` — used for
  `sign` map objects and generic `script` map objects
  (`MapController.handleMapEvent`), i.e. stepping on/near a map object can
  silently run a "phrase" purely for its `Requirement`-gated auto-branches
  and `ScriptEffect`s (commonly quest progress grants).
- `Player.lotsOfTimePassed()` (called after resting, respawning, etc.) runs
  `mapScriptExecutor.proceedToPhrase(res, Constants.PASSIVE_ACHIEVEMENT_CHECK_PHRASE, true, true)`
  — a well-known fixed phrase ID (`"passive_achievement_check"`) that lets
  quest data hook arbitrary "check achievement/quest conditions on time
  passing" logic into a single well-defined phrase, evaluated purely through
  ordinary `Requirement`/`ScriptEffect` phrase branching.
- `TravelDestinationArea` also holds a reference to the same
  `mapScriptExecutor` (wired up in `prepareScriptsOnCurrentMap`) and runs an
  `arrivalScript` phrase when a travelling monster/NPC arrives at a
  destination — see [maps-world.md](maps-world.md).

## How other systems hook in

- **WorldEventListener** (`MapController.worldEventListeners`) is a
  UI-facing notification channel for player actions — conversations
  started, stepping on sign/key/rest areas, loot found/picked up, resting,
  dying. It fires *alongside* `Requirement`/`ScriptEffect` processing (e.g.
  `onPlayerSteppedOnKeyArea` fires when a key area's requirement was **not**
  met, so the UI can show a "you need X" message), but it is not itself part
  of the gating/effect pipeline — nothing in `WorldEventListener` is
  quest-specific, and quest progress changes do not fire a dedicated
  listener event (UI screens instead just re-read `Player` state, e.g.
  `HeroinfoActivity_Quests.reloadQuests()` re-scanning
  `player.getAllQuestProgressIDs()` on `onStart`/`onActivityResult`).
- **Conversations** ([conversations.md](conversations.md)) are the primary
  authoring surface for quests: quest stages are almost always granted and
  gated through `Reply.requires` and `Phrase.scriptEffects` in the
  conversation JSON, resolved by `ConversationController`.
- **Maps** ([maps-world.md](maps-world.md)) contribute two more gating/
  triggering points: `MapObject.enteringRequirement` (key areas) and
  `ReplaceableMapSection.requirement` (map layout swaps), both funneled
  through the same `ConversationController.canFulfillRequirement`/
  `requirementFulfilled` statics, plus `sign`/`script` map objects and
  `TravelDestinationArea` arrival scripts that run full conversation phrases
  as "scripts" via `mapScriptExecutor`.

## Gotchas / non-obvious behavior

- **Quest progress is a set, not a pointer.** Reaching stage 30 does not
  remove stage 10 from the player's record. If quest content wants "only the
  latest stage counts", it must either explicitly use `removeQuestProgress`
  effects to retire old stages, or requirement authors must use
  `questLatestProgress` instead of `questProgress` to mean "the player is
  currently at exactly this point, not further".
- **Re-granting a stage is a silent no-op for rewards.** `addQuestProgress`
  returns `false` if the exact stage is already present, and
  `addQuestProgressReward` only awards `rewardExperience` when `added` is
  `true`. Replaying a phrase (e.g. re-talking to an NPC) that grants a stage
  the player already has will not re-award XP, but any *other* script
  effects on that same phrase (items, alignment changes, etc.) still run
  every time — quest content has to guard those separately with
  `Requirement`s if idempotence is wanted.
- **Checking a `Requirement` and "consuming" it are separate steps.**
  `canFulfillRequirement` is read-only; `requirementFulfilled` performs the
  side effect (removing inventory items, unequipping worn items). Callers
  (`ConversationStatemachine`, `MapController`) are responsible for calling
  both in sequence — a `Requirement` type without a matching case in
  `requirementFulfilled`'s switch (e.g. `questProgress` itself) simply has
  no consumption side effect.
- **`negate` applies to the whole evaluated result**, including types that
  already return a bounded comparison (e.g. `factionScoreEquals`), so a
  negated `factionScoreEquals` requirement means "score is anything except
  exactly this value" — easy to misread when scanning quest data.
- **Map "scripts" reuse the full conversation phrase engine**, including
  auto-branching (a phrase with no `message` walks straight through the
  first reply whose `requires` are satisfied). This means a sign or map
  script can chain through several phrases and grant/require quest progress
  without ever showing dialogue — the only visible trace is whatever
  `ScriptEffect`s it applies (items appearing, monsters spawning, etc.).
- **`Quest.getQuestLogEntry`/`QuestCollection.getQuest` fail soft.** Both
  just log a warning (only when `DEVELOPMENT_VALIDATEDATA` is on) and return
  `null` for an unknown quest ID or stage number rather than throwing —
  a typo'd `questID`/stage number in conversation or map data will silently
  no-op (e.g. `addQuestProgressReward` returns early with no XP reward if
  `world.quests.getQuestLogEntry(progress)` comes back `null`) instead of
  failing loudly outside of dev builds.
- **`showInLog` only affects the quest log screen**, not whether the quest
  can be progressed/gated — hidden "quests" (e.g. internal achievement/flag
  tracking via `questProgress` effects that never show a log entry) work
  identically to visible ones; `HeroinfoActivity_Quests` simply skips
  `showInLog == false` quests when building its list.

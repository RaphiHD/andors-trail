# Items, Inventory & Equipment

## Overview

This mechanic covers everything about "stuff the player can carry, wear, or use": the static item definitions loaded from game data (`ItemType`, `ItemCategory`), the runtime containers that hold stacks of items (`ItemContainer`, `Inventory`), the player's worn-equipment and quick-use slots, monster/ground loot generation (`Loot`, `DropList`), and the three points in time where an item can push effects into the rest of the game — on equip, on use, and on hit-received. It is deliberately data-driven: almost all item behavior is expressed as small immutable "traits" objects attached to an `ItemType`, which other controllers (stats, combat) read and apply rather than the item classes acting on their own.

## Key classes/files

| File | Role |
|---|---|
| [ItemType.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/item/ItemType.java) | Immutable definition of one item type (id, name, category, market cost, and its equip/use/hit/kill trait bundles). Loaded once from game data, shared by reference everywhere. |
| [ItemTypeCollection.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/item/ItemTypeCollection.java) | Global registry mapping item id string to `ItemType`, populated by `ItemTypeParser`. `getItemType(id)` is the lookup used when deserializing saves. |
| [ItemCategory.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/item/ItemCategory.java) | Defines a category of items: which `Inventory.WearSlot` it goes in, its `ActionType` (`none`/`use`/`equip`), and its `ItemCategorySize` (drives two-handed/offhand rules). |
| [ItemCategoryCollection.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/item/ItemCategoryCollection.java) | Global registry of `ItemCategory` by id, populated by `ItemCategoryParser`. |
| [ItemContainer.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/item/ItemContainer.java) | Generic stack-of-items container (`ArrayList<ItemEntry>`). Base class for `Inventory`; also used standalone for merchant stock and loot bags. |
| [Inventory.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/item/Inventory.java) | Player-specific container: extends `ItemContainer` and adds `gold`, the 9 worn-equipment slots (`WearSlot` enum), and the 3 quick-item slots. |
| [ItemController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/ItemController.java) | The controller that mutates inventory/equipment state: equip/unequip, use, drop, buy/sell, loot pickup, quick-slot assignment. Owns `quickSlotListeners`. |
| [ItemTraits_OnEquip.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/item/ItemTraits_OnEquip.java) | Trait bundle applied while an item is worn: `AbilityModifierTraits stats` (passive stat/derived-stat modifiers) and `addedConditions` (permanent `ActorConditionEffect`s applied on equip). |
| [ItemTraits_OnUse.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/item/ItemTraits_OnUse.java) | Trait bundle for "instantaneous" effects: `changedStats` (a `StatsModifierTraits`, e.g. HP/AP change) plus conditions added to the source and/or target actor. Used for on-use, on-hit-dealt, on-miss-dealt, on-kill effects. |
| [ItemTraits_OnHitReceived.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/item/ItemTraits_OnHitReceived.java) | Subclass of `ItemTraits_OnUse` adding `changedStats_target`, i.e. stat effects applied to the *attacker* when the wearer is hit/missed (e.g. thorn/retaliation gear). |
| [Loot.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/item/Loot.java) | A bag of `exp`, `gold` and `items` (an `ItemContainer`) sitting at a map `Coord`. Represents both ground loot and monster corpse drops. |
| [DropList.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/item/DropList.java) | A weighted table of `DropItem` (itemType, chance range, quantity range) that rolls random contents into a `Loot` via `createRandomLoot`. |
| [DropListCollection.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/item/DropListCollection.java) | Global registry of named `DropList`s, including the special `"startitems"` list used to seed a new character's inventory. |
| [QuickSlotListener.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/QuickSlotListener.java) / [QuickSlotListeners.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/QuickSlotListeners.java) | Observer interface + multicaster for UI components that need to react when a quick slot's assigned item changes or is used. |

## How it works

### Item type and category model

An `ItemType` (`ItemType.java`) is a purely immutable, shared definition — one instance per item id, held in `ItemTypeCollection`. It carries:

- Identity/display: `id`, `iconID`, `name`/`description` (via `getName(Player)`, `getDescription()`), `displayType` (`ordinary`/`quest`/`rare`/`extraordinary`/`legendary`, used for icon overlay color via `getOverlayTileID()`).
- Economy: `baseMarketCost`, either a `fixedBaseMarketCost` (`hasManualPrice`) or auto-computed by `calculateCost(...)` as the sum of the costs contributed by its equip/use/hit/kill traits (`Math.max(1, ...)`, so no item is worth literally 0 unless deliberately unsellable — see Gotchas).
- Category: an `ItemCategory` reference, which is where "what kind of item is this" actually lives.
- Seven optional trait bundles: `effects_equip`, `effects_use`, `effects_hit`, `effects_miss`, `effects_kill`, `effects_hitReceived`, `effects_missReceived`.

`ItemType` itself has almost no behavior beyond convenience booleans that delegate to its category: `isEquippable()`, `isUsable()`, `isWeapon()`, `isArmor()`, `isShield()`, `isTwohandWeapon()`, `isOffhandCapableWeapon()`. `isQuestItem()`/`isOrdinaryItem()` check `displayType` directly. `isSellable()` is false for quest items and for items whose `baseMarketCost == 0`.

`ItemCategory` (`ItemCategory.java`) is where slot and handling rules are defined per category (e.g. "sword", "helmet", "potion"):
- `inventorySlot`: which `Inventory.WearSlot` an equippable item of this category goes into (irrelevant for non-equippable categories).
- `actionType`: `none`, `use`, or `equip` — this is the single flag that decides whether an item is usable or equippable at all (`isUsable()`/`isEquippable()` on both `ItemCategory` and `ItemType` derive from it).
- `size`: `none`/`light`/`std`/`large`, which feeds `isTwohandWeapon()` (weapon + `large` size) and `isOffhandCapableWeapon()` (weapon + `light` or `std` size).

### Inventory storage and stacking

`ItemContainer` is the generic storage primitive: a flat `ArrayList<ItemEntry>` where each `ItemEntry` pairs an `ItemType` reference with an integer `quantity`. Items stack automatically — `addItem(itemType, quantity)` calls `findItem(itemType.id)` and increments the existing entry's quantity instead of creating a duplicate row; there is no per-entry quantity cap in this class. `removeItem(itemTypeID, quantity)` removes the entry entirely if the quantity matches exactly, decrements it if there's a surplus, and returns `false` (no-op) if there isn't enough to remove — callers must check the return value. `ItemContainer` is also reused directly for non-player containers: merchant stock and loot bags are plain `ItemContainer`/`Loot` instances, not `Inventory`.

`ItemContainer` also provides sorting (`sortByName`, `sortByPrice`, `sortByQuantity`, `sortByRarity`, `sortByType`, `sortByReverse`, plus `sortToTop`/`sortToBottom` for pinning one item) and category-bucketing helpers used by inventory UI screens, e.g. `usableItems()`. `determineType(ItemEntry)` encodes a manual sort-order (equip slot family, then potions/food, then quest items, then everything else) used by `sortByType`.

`Inventory` (`Inventory.java`) extends `ItemContainer` and is the player-only container, adding:
- `gold` (a plain int, not an `ItemEntry` — see Gotchas).
- `wear[]`, a fixed array sized by `WearSlot.values().length` (`NUM_WORN_SLOTS`), indexed by `WearSlot.ordinal()`. Accessed via `getItemTypeInWearSlot`/`setItemTypeInWearSlot`/`isEmptySlot`.
- `quickitem[]`, a fixed 3-element array (`NUM_QUICK_SLOTS = 3`) of `ItemType` references, the player's quick-use slots.

`WearSlot` is a 9-value enum: `weapon, shield, head, body, hand, feet, neck, leftring, rightring`. `Inventory.isArmorSlot(slot)` classifies only `head/body/hand/feet` as "armor" slots (weapon/shield/neck/rings are not). Note both ring slots exist as distinct enum values, but `ItemCategory` has no `rightring` mapping in practice — `determineType()`'s comment confirms "all rings are leftring by category" (see Gotchas).

`Inventory` also exposes several `buildXItems()` helpers (`buildQuestItems`, `buildJewelryItems`, `buildPotionItems`, `buildFoodItems`, `buildWeaponItems`, `buildArmorItems`, `buildOtherItems`) that filter the container into a fresh `Inventory` for UI tab views; jewelry is defined as "equippable, not weapon, not armor, not shield" (i.e. rings/amulets), and potions vs. food is split by category id string `"pot"`/`"healing"` vs. everything else usable.

### Equip slots

Equipping and unequipping is driven entirely through `ItemController`, never by mutating `Inventory.wear[]` directly from elsewhere:

- `equipItem(ItemType type, WearSlot slot)`: bails if `!type.isEquippable()`. If in combat, spends `player.getReequipCost()` AP via `actorStatsController.useAPs` and aborts if the player can't afford it. Removes one unit of the item from inventory (`removeItem`), unequips whatever is currently in the target slot, and handles two-handed weapon interaction: equipping a two-handed weapon also unequips the `shield` slot; equipping into `shield` while a two-handed weapon is in `weapon` unequips the weapon. It then places the item (`setItemTypeInWearSlot`), applies its on-equip conditions (`actorStatsController.addConditionsFromEquippedItem`), and recalculates derived stats (`actorStatsController.recalculatePlayerStats`). If this exhausted the player's turn in combat, it ends the turn.
- `unequipSlot(ItemType type, WearSlot slot)` (public overload): same AP-cost/combat handling, then delegates to the private `unequipSlot(Player, WearSlot)` which puts the item back into inventory (`addItem`), clears the slot, removes its on-equip conditions (`removeConditionsFromUnequippedItem`), and recalculates stats.
- `removeEquippedItem(String itemTypeID, int count)`: a different path used e.g. for effects that strip specific equipped items — walks all `WearSlot`s, clears any matching slot (without returning the item to inventory), removes its conditions, and recalculates stats, stopping once `count` items have been removed.

Applying the passive stat effects of everything currently worn is done by `ItemController.applyInventoryEffects(Player)`, which is called from the stats-recalculation pipeline. It walks each `WearSlot` in a fixed order (weapon, shield, head, body, hand, feet, neck, leftring, rightring, with fighting-style/proficiency skill effects interleaved between shield and head, and again at the end) and, for each occupied slot, applies `type.effects_equip.stats` via `actorStatsController.applyAbilityEffects`. Weapon damage bonuses (`increaseMinDamage`/`increaseMaxDamage`) are added only if `type.isWeapon()`. The `shield` slot's stats are skipped when the player is dual-wielding two weapons (`SkillController.isDualWielding`), because dual-wield stats are applied separately in `SkillController.applySkillEffectsFromFightingStyles`. `getMainWeapon(Player)` resolves the "attacking weapon" as the `weapon` slot if occupied, else the `shield` slot if it holds a weapon (off-hand weapon), else `null` (unarmed).

`ItemController.recalculateHitEffectsFromWornItems(Player)` (static) scans all worn slots and collects each item's `effects_hit`/`effects_miss`/`effects_hitReceived`/`effects_missReceived` into flat arrays stored on the `Player` (`onHitEffects`, `onMissEffects`, `onHitReceivedEffects`, `onMissReceivedEffects`), or `null` if none apply. This is a cache: combat code reads these arrays directly off the actor instead of re-scanning equipment on every attack.

`applyDamageModifier(Player)` (static) resolves `setNonWeaponDamageModifier` from the weapon/shield-as-weapon items and blends it according to the dual-wield fighting-style skill level (max/average/min of the two hands' modifiers depending on skill rank), then scales the player's non-weapon damage potential accordingly.

### Quick slots

Quick slots are just 3 `ItemType` pointers (`Inventory.quickitem[]`) that shortcut into `useItem`, with no separate quantity or state of their own:

- `ItemController.setQuickItem(ItemType, quickSlotId)` writes the reference and fires `quickSlotListeners.onQuickSlotChanged(slotId)`.
- `ItemController.quickitemUse(quickSlotId)` calls `useItem(inventory.quickitem[quickSlotId])` then fires `onQuickSlotUsed(slotId)`.
- `checkQuickslotItemLooted(ItemContainer items)` is invoked after picking up loot (`pickupAll`): for every looted usable item, if it matches an item currently assigned to a quick slot, it fires `onQuickSlotChanged` for that slot — this exists purely to let the UI refresh a quick slot's displayed quantity/enabled-state after a pickup, since the slot only stores a type reference, not a count.

`QuickSlotListener`/`QuickSlotListeners` is a small observer pair (`QuickSlotListeners` extends the shared `ListOfListeners<T>` multicaster) with two events, `onQuickSlotChanged(slotId)` and `onQuickSlotUsed(slotId)`. `ItemController` owns the single `quickSlotListeners` multicaster instance; the UI (`QuickitemView`) is the other registered listener.

### Using items

`ItemController.useItem(ItemType type)`: bails if `!type.isUsable()`. In combat, spends `player.getUseItemCost()` AP (abort if insufficient). Removes one unit from inventory; if that fails (not actually present), the method has already logged nothing and simply stops having spent AP — note the AP is spent *before* the inventory check succeeds is not the case: `removeItem` is checked and the method returns early on failure, but the AP was already deducted at that point (see Gotchas). On success it appends a combat-log message, calls `actorStatsController.applyUseEffect(player, null, type.effects_use)` (target is `null` — on-use effects only ever affect the user), records statistics (`world.model.statistics.addItemUsage`), and ends the turn if AP is exhausted.

`ActorStatsController.applyUseEffect(Actor source, Actor target, ItemTraits_OnUse effect)` is the shared engine behind on-use, on-hit, on-miss and on-kill effects: it rolls `addedConditions_source` onto `source` and (if `target != null`) `addedConditions_target` onto `target` via `rollForConditionEffect` (chance-based, using `Constants.rollResult` with a skill-derived bias), and if `changedStats != null` applies the stat delta via `applyStatsModifierEffect` and triggers a visual effect via `effectController.startEnqueuedEffect`.

### Item traits and when each fires

| Trait field on `ItemType` | Type | Fires when | Applied via |
|---|---|---|---|
| `effects_equip` | `ItemTraits_OnEquip` | Item is equipped (stays active while worn) | `ItemController.equipItem` → `addConditionsFromEquippedItem` (conditions) + `applyInventoryEffects` (stats), reversed by `unequipSlot`/`removeEquippedItem` → `removeConditionsFromUnequippedItem` |
| `effects_use` | `ItemTraits_OnUse` | Item is consumed via `useItem` / quick slot | `ItemController.useItem` → `ActorStatsController.applyUseEffect(player, null, ...)` |
| `effects_hit` | `ItemTraits_OnUse` | Wearer lands a successful attack | Collected onto `player.onHitEffects` by `recalculateHitEffectsFromWornItems`; applied per-hit in `CombatController.applyAttackHitStatusEffects` via `applyUseEffect(attacker, target, e)` |
| `effects_miss` | `ItemTraits_OnUse` | Wearer's attack misses | Same mechanism, `player.onMissEffects`, applied in `CombatController.applyAttackMissStatusEffects` |
| `effects_kill` | `ItemTraits_OnUse` | Wearer's attack kills the target | `ActorStatsController.applyKillEffectsToPlayer` walks all worn slots and applies each item's `effects_kill` with `target = null` |
| `effects_hitReceived` | `ItemTraits_OnHitReceived` | Wearer is hit by an attack | Collected onto `player.onHitReceivedEffects`; applied in `CombatController.applyAttackHitStatusEffects` via `ActorStatsController.applyHitReceivedEffect(target, attacker, e)` — note the wearer is the `source`/`target` of the stat change depending on the `changedStats` vs `changedStats_target` split (see below) |
| `effects_missReceived` | `ItemTraits_OnHitReceived` | An attack against the wearer misses | Same mechanism, `player.onMissReceivedEffects`, via `applyAttackMissStatusEffects` |

`ItemTraits_OnHitReceived` extends `ItemTraits_OnUse` and adds `changedStats_target`. `ActorStatsController.applyHitReceivedEffect(source, target, effect)` first calls `applyUseEffect(source, target, effect)` (applying `changedStats`/`addedConditions_source` to the wearer and `addedConditions_target` to the attacker), then, if `changedStats_target != null`, applies that second stat delta to the attacker (`target` parameter) — this is how "retaliation" gear (e.g. damage back to the attacker) is expressed as two independent stat-change blocks in one trait object.

Cross-references: the stat deltas here (`StatsModifierTraits`, `AbilityModifierTraits`) and the conditions (`ActorConditionEffect`) are documented in `character-stats-leveling.md` and `conditions-effects.md` respectively — this doc only covers *when* item traits fire, not what the effect payloads mean. Combat's per-attack dispatch of hit/miss effects is documented in `combat.md`.

### Loot generation and drop lists

`Loot` (`Loot.java`) bundles `exp`, `gold`, an `items` `ItemContainer`, a map `position`, and an `isVisible` flag. `isContainer()` returns `!isVisible` — an "invisible" loot bag is a container-type drop (e.g. a treasure chest object) as opposed to a bag dropped directly on the ground. `Loot.add(ItemType, quantity)` special-cases the item id `"gold"` (checked via `ItemTypeCollection.isGoldItemType`) by adding straight to `gold` instead of inserting a `"gold"` `ItemEntry` into `items`.

`DropList` (`DropList.java`) is a static table of `DropItem`s (itemType + `ConstRange chance` + `ConstRange quantity`). `createRandomLoot(Loot loot, Player player)` rolls each entry independently: `Constants.rollResult(item.chance, chanceRollBias)` decides whether it drops at all (chance roll biased by `SkillController.getDropChanceRollBias`, e.g. a looting skill), and if so `Constants.rollValue(item.quantity, quantityRollBias)` decides how many (also skill-biased). `DropListCollection` looks drop lists up by string id; the well-known id `DROPLIST_STARTITEMS = "startitems"` is what seeds a brand-new player's starting inventory (`Player.java`). Drop lists are also used for monster corpse loot (`Monster.getDropList()`/`monsterType.dropList`), container objects on the map (`PredefinedMap`, `TMXMapTranslator`), and scripted loot drops from conversations (`ConversationController`).

Player-side pickup flow lives in `ItemController`:
- `playerSteppedOnLootBag(Loot)` / `lootMonsterBags(Collection<Loot>, totalExp)`: decide, via `pickupLootBagWithoutConfirmation`/`pickupLootBagsWithoutConfirmation` and the `AndorsTrailPreferences.displayLoot` setting, whether to auto-pick-up or show a confirmation dialog. A container-type bag (`isContainer()`) is never auto-picked-up regardless of preference.
- `pickupAll(Loot)`: merges `loot.items` into the player's inventory (`inventory.add`), transfers gold (`consumeNonItemLoot`), refreshes any affected quick slots (`checkQuickslotItemLooted`), then clears the loot object.
- `removeLootBagIfEmpty(Loot)`: once a bag has no items or gold left, removes it from the map (`currentMaps.map.removeGroundLoot`) and notifies `mapController.mapLayoutListeners.onLootBagRemoved`.

### Buying and selling

Also in `ItemController`: `getBuyingPrice`/`getSellingPrice` apply a barter-skill-driven percentage (`getMarketPriceFactor`, offset by `SkillCollection.SkillID.barter`) to `itemType.baseMarketCost`. `sell`/`buy` are symmetric — they move an `ItemEntry` between the player's `Inventory` and a merchant `ItemContainer` and adjust `player.inventory.gold`, failing safely (returning `false`, no state changed) if the source container doesn't have the item/gold. `maySellItem` gates on `itemType.isSellable()`.

## How other systems hook in

- **Conditions/effects** (`conditions-effects.md`): equip/use/hit traits carry `ActorConditionEffect[]` payloads applied through `ActorStatsController.applyActorCondition`/`rollForConditionEffect`. This doc documents only the trigger points (equip, use, hit, miss, kill, hit-received, miss-received); the condition model itself lives in that doc.
- **Character stats/leveling** (`character-stats-leveling.md`): `effects_equip.stats` (an `AbilityModifierTraits`) and the `StatsModifierTraits` inside on-use/on-hit traits feed into `ActorStatsController.applyAbilityEffects`/`applyStatsModifierEffect`/`recalculatePlayerStats`. Two derived-stat entry points worth noting from this doc: `ItemController.applyInventoryEffects` (passive gear stats) and `ItemController.applyDamageModifier` (weapon-slot-driven non-weapon damage scaling).
- **Combat** (`combat.md`): `CombatController.applyAttackHitStatusEffects`/`applyAttackMissStatusEffects` read the cached `player.onHitEffects`/`onMissEffects`/`onHitReceivedEffects`/`onMissReceivedEffects` arrays (populated by `ItemController.recalculateHitEffectsFromWornItems`) after every attack roll. AP costs for equip/unequip/use (`getReequipCost`, `getUseItemCost` on `Player`) are spent through `ActorStatsController.useAPs`, and both `equipItem`/`unequipSlot`/`useItem` end the player's combat turn via `combatController.endPlayerTurn()` if AP is exhausted.
- **Skills** (`SkillController`): several item-adjacent numbers are skill-modulated rather than fixed — drop chance/quantity roll bias (`getDropChanceRollBias`/`getDropQuantityRollBias`), market price factor (barter skill), dual-wield stat blending (`isDualWielding`, `applySkillEffectsFromFightingStyles`), and item-proficiency skill effects (`applySkillEffectsFromItemProficiencies`).
- **UI**: `QuickitemView` is the concrete listener for `QuickSlotListener`, re-rendering a quick slot's icon/label when notified. Inventory list/sort screens consume `ItemContainer`'s `sortByX`/`buildXItems` helpers rather than re-implementing filtering.
- **Data loading**: `ItemType`/`ItemCategory`/`DropList` instances are never constructed ad hoc at runtime — they are parsed once at startup by `ItemTypeParser`/`ItemCategoryParser`/`DropListParser` into the respective `*Collection` registries and referenced by shared identity afterward (relevant when reasoning about equality/identity checks like `item.itemType == world.model.player.inventory.quickitem[i]` in `checkQuickslotItemLooted`).
- **Save/load**: `Inventory`/`ItemContainer`/`Loot` all implement the project's manual `readFromParcel`/`writeToParcel`/`addToChecksum` triplet, persisting item ids (resolved back to `ItemType` via `world.itemTypes.getItemType(id)`) rather than full objects.

## Gotchas / non-obvious behavior

- **Item identity, not entries, is what "==" compares**: `checkQuickslotItemLooted` matches looted items to quick slots with reference equality (`item.itemType == quickitem[i]`), which is safe only because `ItemType` instances are singletons from `ItemTypeCollection` — never construct a second `ItemType` with the same id at runtime.
- **Right ring slot is effectively dead**: `Inventory.WearSlot` defines both `leftring` and `rightring`, and `determineType()` in `ItemContainer` has a value for `rightring` explicitly, but its own comment says "not used - all rings are leftring by category," meaning no `ItemCategory` in the data actually maps to `Inventory.WearSlot.rightring`. Code that iterates `WearSlot.values()` will still touch this slot; it will just never be populated through normal equip flow unless something calls `setItemTypeInWearSlot(rightring, ...)` directly.
- **Gold is not an `ItemEntry`**: `Inventory.gold` and `Loot.gold` are plain ints tracked outside the `items` list, even though `"gold"` exists as a real item id (`ItemTypeCollection.isGoldItemType`). `Loot.add(ItemType, quantity)` special-cases it; code that iterates `container.items` directly will never see gold there.
- **`removeItem` fails silently if quantity is insufficient**: `ItemContainer.removeItem(id, quantity)` returns `false` and leaves the container untouched if the requested quantity exceeds what's present — callers (`equipItem`, `useItem`, `sell`, `dropItem`) all check the boolean return, but a caller that doesn't would silently no-op.
- **AP is spent before the inventory-removal check in `equipItem`/`useItem`**: both methods call `actorStatsController.useAPs(...)` (deducting AP immediately) before calling `inventory.removeItem(...)`; if `removeItem` then returns `false` (item wasn't actually there), the AP has already been spent with no compensating refund in that code path.
- **Two-handed/shield interaction is asymmetric special-casing, not a general rule engine**: `equipItem` hardcodes "equip two-hander clears shield" and "equip into shield while a two-hander is in weapon clears weapon." Any future equip-slot exclusivity rules (e.g. new large-item categories) would need equivalent hardcoded checks; there's no generic slot-conflict declaration on `ItemCategory`.
- **`baseMarketCost` is a real gameplay input, not just a display number, and its auto-calculation floors at 1**: `ItemType.calculateCost` uses `Math.max(1, ...)`, so an item with zero explicit combat/use value still prices at 1 unless `hasManualPrice` is used to force `fixedBaseMarketCost = 0` (which `isSellable()` then treats as unsellable).
- **`isSellable()` doesn't check equip status or quest-only-ness beyond `displayType`**: any item with `displayType != quest` and nonzero cost is sellable, including e.g. items that are technically supposed to be unique/plot-critical if a data author forgets to mark them `quest`.
- **Dual-wielding suppresses the shield slot's own stat application**: `ItemController.applyInventoryEffects` explicitly skips applying `effects_equip.stats` for the `shield` slot when `SkillController.isDualWielding(mainHandItem, shieldItem)` is true, because those stats are instead folded into `SkillController.applySkillEffectsFromFightingStyles`. Reading `effects_equip.stats` off a shield-slot weapon directly (bypassing that skip) would double-count when dual-wielding.
- **`effects_kill` fires per equipped item, not per weapon used**: `ActorStatsController.applyKillEffectsToPlayer` walks *all* worn slots, not just the weapon that landed the kill — any equipped item with `effects_kill` set triggers on every kill regardless of which slot dealt the damage.
- **Development-only integrity checks**: `ItemTypeCollection.getItemType`, `ItemCategoryCollection.getItemCategory`, and `DropListCollection.getDropList` only log a warning for unknown ids when `AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA` is true (beta/dev builds); in a release build an unknown id lookup returns `null` silently, which can surface later as an NPE far from the actual data error.

# Skills / Abilities

## Overview

Skills are the player's permanent, level-purchased upgrades — things like
"Weapon Chance", "Barkskin", "Coinfinder", or weapon/armor proficiencies.
Each skill is an entry in a fixed enum (`SkillCollection.SkillID`) with an
integer level per player (0 = not learned). Raising a skill's level costs a
skill point (`Player.availableSkillIncreases`), which the player earns
periodically on level-up, or in some cases a skill level is granted directly
by a quest. Skills don't have their own state machine or "active/passive"
distinction — they are pure data (a level per `SkillID`) and all of their
gameplay effect is computed on demand by `SkillController`, which reads the
skill levels and adds flat or percentage modifiers onto the player's combat
stats, loot rolls, and condition resistances. There is no generic
"skill effect" data object akin to items' `AbilityModifierTraits` — each
skill's effect is hardcoded as a small formula in `SkillController`, driven by
per-skill-point constants declared in `SkillCollection`.

## Key classes/files

| File | Role |
|---|---|
| [SkillController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/SkillController.java) | Applies every skill's gameplay effect (stat bonuses, combat rolls, loot bias, proficiency/fighting-style bonuses) and handles skill level-up requests. |
| [SkillCollection.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/ability/SkillCollection.java) | Defines the `SkillID` enum, the `SkillCategory` enum, every `PER_SKILLPOINT_INCREASE_*` / max-level constant, and builds the static registry of `SkillInfo` (one per `SkillID`) in `initialize()`. |
| [SkillInfo.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/ability/SkillInfo.java) | Static definition of one skill: max level, level-up visibility/gating type, category, UI position, and its `SkillLevelRequirement[]` prerequisites. |
| [AbilityModifierTraits.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/ability/traits/AbilityModifierTraits.java) | Generic "bundle of stat deltas" used by items and conditions (not directly by skills) — see Gotchas. |
| [StatsModifierTraits.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/ability/traits/StatsModifierTraits.java) | Generic current-HP/AP boost bundle used by consumable items/conditions, unrelated to the skill point system. |
| [Player.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/actor/Player.java) | Owns the actual per-player skill levels (`skillLevels`, a `SparseIntArray`) and `availableSkillIncreases`; exposes `getSkillLevel`, `addSkillLevel`, `hasSkill`, `hasAvailableSkillpoints`. |
| [ActorStatsController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/ActorStatsController.java) | Orchestrates the full stat recalculation pipeline (`recalculatePlayerStats`) that invokes `SkillController.applySkillEffects`; also grants skill points on level-up. |
| [SkillListAdapter.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/view/SkillListAdapter.java) / [SkillInfoActivity.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/activity/SkillInfoActivity.java) / [HeroinfoActivity_Skills.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/activity/fragment/HeroinfoActivity_Skills.java) | UI for browsing skills and spending skill points (calls `SkillController.canLevelupSkillManually` / `levelUpSkillManually`). |

## How it works

### Data model: `SkillID`, `SkillInfo`, and per-player levels

`SkillCollection.SkillID` is a large enum (~40 entries) covering combat
skills (`weaponChance`, `weaponDmg`, `dodge`, `barkSkin`, `moreCriticals`,
`betterCriticals`, `speed`, ...), utility skills (`barter`, `coinfinder`,
`moreExp`, `magicfinder`, `lowerExploss`), resistances
(`resistanceMental`/`resistancePhysical`/`resistanceBlood`, `shadowBless`,
`rejuvenation`), special combat triggers (`taunt`, `concussion`, `crit1`,
`crit2`), and equipment proficiencies/fighting styles
(`weaponProficiency*`, `armorProficiency*`, `fightstyle*`,
`specialization*`). `SkillCollection.initialize()` builds one immutable
`SkillInfo` per `SkillID` and stores it in a `LinkedHashMap<Integer,
SkillInfo>` keyed by `SkillID.ordinal()`.

Each `SkillInfo` carries:
- `maxLevel` (or `SkillInfo.MAXLEVEL_NONE` = -1 for uncapped skills — checked via `hasMaxLevel()`).
- `levelupVisibility`, one of `LevelUpType.alwaysShown`, `onlyByQuests`, or `firstLevelRequiresQuest`.
- `levelupRequirements`, an array of `SkillLevelRequirement` prerequisites (may be `null`).
- `categoryType` (`SkillCategory`: offense/defense/criticals/immunity/utility/specialty/proficiency) and `position`, both used only for UI grouping/ordering.

The player's actual levels live on `Player.skillLevels`, a `SparseIntArray`
keyed by `SkillID.ordinal()` (`Player.getSkillLevel`, `addSkillLevel`,
`hasSkill`). This is also the on-disk savegame representation: both the
Parcelable and the builder-based save path write `(ordinal, level)` pairs
(`Player.java` lines ~364-369, ~466-469, ~524-527) — see Gotchas.

### Prerequisites: `SkillLevelRequirement`

`SkillInfo.canLevelUpSkillTo(player, requestedLevel)` walks
`levelupRequirements` and requires every entry's
`isSatisfiedByPlayer(player, requestedLevel)` to hold. A requirement is
built via one of three factories on `SkillLevelRequirement`:
- `requireOtherSkill(SkillID, everyLevelRequires)` — needs another skill at a minimum level (e.g. `crit1` requires `moreCriticals` >= 2 and `betterCriticals` >= 2).
- `requireExperienceLevels(everyLevelRequires, initialRequired)` — needs a minimum player level (e.g. `speed` requires character level 15).
- `requirePlayerStats(Player.StatID, everyLevelRequires, initialRequired)` — needs a minimum derived stat value (e.g. `barkSkin` requires `blockChance` >= 15).

The required threshold scales with the *target* skill level:
`getRequiredValue(requestedSkillLevel) = requestedSkillLevel * everySkillLevelRequiresThisAmount + initialRequiredAmount`,
so higher skill levels demand proportionally more of the prerequisite.

### Learning / leveling up a skill

Two paths exist, both funneled through `Player.addSkillLevel` and both
finishing with `ActorStatsController.recalculatePlayerStats` so the new
level takes effect immediately:

- **Manual (spending a skill point):** `SkillController.canLevelupSkillManually` requires `player.hasAvailableSkillpoints()`, `canLevelupSkillWithQuest` (i.e. not already at `maxLevel` and prerequisites satisfied), and rejects skills whose `levelupVisibility` is `onlyByQuests`; a `firstLevelRequiresQuest` skill additionally requires the player already `hasSkill(id)` (i.e. level 1 must have come from a quest, only levels 2+ are purchasable). `levelUpSkillManually` then decrements `player.availableSkillIncreases` and calls `addSkillLevel`. This is what the Skills UI (`SkillListAdapter`, `SkillInfoActivity`, `HeroinfoActivity_Skills`) calls.
- **Quest-granted:** `SkillController.levelUpSkillByQuest` only checks `canLevelupSkillWithQuest` (max level + prerequisites) — it does **not** check `levelupVisibility` or spend a skill point. This is how `onlyByQuests` skills (`shadowBless`, `sporeImmunity`) and the first level of `firstLevelRequiresQuest` proficiency skills are unlocked, via `ConversationController` (dialogue/quest reward effects).

Skill points themselves are granted in `ActorStatsController`'s level-up
handling: every level where `Player.nextLevelAddsNewSkillpoint()` is true
(i.e. `(level - Constants.FIRST_SKILL_POINT_IS_GIVEN_AT_LEVEL) % Constants.NEW_SKILL_POINT_EVERY_N_LEVELS == 0`,
first at level 4 and every 4 levels thereafter with current constants),
`player.availableSkillIncreases++`.

### How a skill level becomes a gameplay effect

There is no generic "skill effect" object — `SkillController` reads
`player.getSkillLevel(SkillID.x)` and hand-applies the per-skill-point
constant from `SkillCollection` (all named `PER_SKILLPOINT_INCREASE_*`).
Effects fall into a few patterns, mirrored by separate methods:

- **`applySkillEffects(Player)`** — flat/percentage additions to the player's already-computed stat fields (`attackChance`, `damagePotential`, `blockChance`, `damageResistance`, `criticalSkill`, `criticalMultiplier`, max AP via `ActorStatsController.addActorMaxAP`), each scaled by `getSkillLevel(...) * PER_SKILLPOINT_INCREASE_...`. Example: `player.attackChance += PER_SKILLPOINT_INCREASE_WEAPON_CHANCE * player.getSkillLevel(SkillID.weaponChance)`. `moreCriticals`/`betterCriticals` are percentage multipliers gated by `player.hasCriticalSkillEffect()` / `hasCriticalMultiplierEffect()` (see Gotchas).
- **`applySkillEffectsFromItemProficiencies(Player)`** (static) — weapon/shield/armor proficiency skills, resolved through `getProficiencySkillForItemCategory(ItemCategory)` which maps an equipped item's category (dagger, 1h/2h sword, axe, blunt, pole, shield, light/heavy armor, etc.) to the matching `SkillID`. The skill level scales a *percentage* of the item's own `effects_equip.stats` values via the private `addPercent*` helpers and `getPercentage(...)`, so proficiency skills amplify whatever the currently worn item already grants rather than adding a flat number. Unarmed and unarmored proficiency only apply when `isUnarmed`/`isUnarmored` are true.
- **`applySkillEffectsFromFightingStyles(Player)`** (static) — style/specialization skills (`fightstyleDualWield`, `fightstyle2hand`, `fightstyleWeaponShield`, `fightstyleUnarmedUnarmored`, and the matching `specialization*` skills) that only apply under the matching equipment configuration (`isWieldingWeaponAndShield`, `isWielding2HandItem`, `isDualWielding`, or bare-handed+unarmored). Dual wield is the most involved: `fightstyleDualWield` level 0/1/2 sets an "off-hand efficiency" percentage (`DUALWIELD_EFFICIENCY_LEVEL0/1/2` = 25/50/100) that scales how much of the off-hand weapon's stats (damage, AC, BC, crit, HP/AP boosts, cost maluses) actually apply, and also affects combined `attackCost`/`criticalMultiplier`.
- **Combat-roll and condition-chance skills** — not stat deltas but biases applied at roll time: `getActorConditionEffectChanceRollBias` (resistance/`shadowBless`/`sporeImmunity` skills reduce the chance an incoming condition succeeds), `getDropChanceRollBias`/`getDropQuantityRollBias` (`coinfinder`/`magicfinder` bias loot rolls), and `rollForSkillChance` (a flat `skillLevel * chancePerLevel` percent roll, used for `taunt`, `concussion`, `crit1`/`crit2`, `rejuvenation`).
- **Post-attack triggers** — `applySkillEffectsFromPlayerAttack` (on a player hit, rolls `concussion` when attack-vs-block margin exceeds `CONCUSSION_THRESHOLD`, and on a critical hit rolls `crit1`/`crit2` to inflict the corresponding condition on the monster) and `applySkillEffectsFromMonsterAttack` (on a monster miss, rolls `taunt` to drain the monster's AP and fire `combatActionListeners.onPlayerTauntsMonster`).

`SkillController.combatActionListeners` (a `CombatActionListeners`
instance) is the only listener hook owned by this controller, used solely to
notify the UI when `taunt` triggers.

## How other systems consume this

Skill effects are not applied continuously — they are recomputed from
scratch whenever `ActorStatsController.recalculatePlayerStats(Player)` runs
(see [character-stats-leveling.md](character-stats-leveling.md) for the
full pipeline). Within that method, `player.resetStatsToBaseTraits()` runs
first, then `ItemController.applyInventoryEffects(player)` (which also
internally calls `SkillController.applySkillEffectsFromFightingStyles` and
`applySkillEffectsFromItemProficiencies`, since those skills depend on
currently worn items), then `SkillController.applySkillEffects(player)` for
the flat combat skills, then condition effects. This means proficiency and
fighting-style skill bonuses are layered on top of the equipped item's own
stats before the flat skill bonuses are added, and the whole stack is
rebuilt (not incrementally patched) on every recalculation.

Other integration points, per mechanic:
- **Combat** ([combat.md](combat.md)): `CombatController` calls `applySkillEffectsFromPlayerAttack`/`applySkillEffectsFromMonsterAttack` after each attack resolution; `ActorStatsController.applyConditionsToPlayer` rolls `rejuvenation` each partial round via `removeConditionsFromSkillEffects`, and applies `regeneration` healing (`applySkillEffectsForNewRound`) and `resistance*`/`shadowBless` biases when conditions are applied to the player (`getActorConditionEffectChanceRollBias`, called from `applyAbilityEffects`'s condition-application path).
- **Leveling** ([character-stats-leveling.md](character-stats-leveling.md)): `ActorStatsController`'s level-up code grants `availableSkillIncreases` on qualifying levels and adds `fortitude`-skill HP directly into the level-up HP increase.
- **Loot** (items-equipment.md): `DropList` calls `SkillController.getDropChanceRollBias`/`getDropQuantityRollBias` while rolling drops, biasing gold (`coinfinder`) and non-ordinary item (`magicfinder`) drop chance/quantity.
- **Quests/dialogue**: `ConversationController` calls `levelUpSkillByQuest` to grant skill levels as a quest/dialogue reward, bypassing the normal skill-point cost and `levelupVisibility` gating.

## Gotchas / non-obvious behavior

- **Skill effects are recomputed, not stored.** A skill's bonus is not persisted as a modifier anywhere — it exists only as long as `recalculatePlayerStats` is re-derived from `skillLevels` each time it runs. If you add a new skill effect, it must go into one of the `SkillController.applySkillEffects*` methods so it's included in every recalculation, not applied once at level-up time.
- **`SkillID.ordinal()` is the savegame key.** Both `Player`'s Parcelable path and its builder-based save path persist skill levels as `(SkillID.ordinal(), level)` pairs. Reordering or deleting entries in the `SkillID` enum will silently remap existing savegames' skill levels to the wrong skill. New skills must be appended at the end of the enum.
- **`AbilityModifierTraits`/`StatsModifierTraits` are not used by the skill system.** These two "generic modifier bundle" classes (used by items' `effects_equip`/`effects_onUse` and by condition types) are a separate, reusable mechanism; skills instead hardcode their math directly in `SkillController` against named `PER_SKILLPOINT_INCREASE_*` constants. Don't expect to find a skill's effect represented as an `AbilityModifierTraits` instance.
- **`moreCriticals`/`betterCriticals` are gated by an existing nonzero base value.** `applySkillEffects` only multiplies `player.criticalSkill`/`criticalMultiplier` if `player.hasCriticalSkillEffect()`/`hasCriticalMultiplierEffect()` are already true — and those booleans are evaluated against whatever `criticalSkill`/`criticalMultiplier` value equipment already set earlier in the same recalculation pass (`ItemController.applyInventoryEffects` runs first). In practice this means these two skills do nothing unless the equipped weapon already grants a nonzero critical chance/multiplier.
- **`firstLevelRequiresQuest` is stricter than it looks.** For such a skill, `canLevelupSkillManually` requires `player.hasSkill(id)` (level > 0) before the player is allowed to spend a point on it — so the manual UI path can only ever raise level 2+; level 1 must always come from `levelUpSkillByQuest`.
- **`levelUpSkillByQuest` does not check `levelupVisibility`.** It happily grants levels to `onlyByQuests` skills (bypassing the manual-path restriction by design) but also does not re-verify anything about how the skill "should" be obtained beyond `canLevelupSkillWithQuest` — the calling quest/dialogue script is trusted to only call it where intended.
- **Dual-wield math double-counts an off-hand proficiency scaling.** In `applySkillEffectsFromFightingStyles`, the off-hand weapon's proficiency bonus is itself scaled down by the dual-wield efficiency `percent` (`getPercentage(PER_SKILLPOINT_INCREASE_WEAPON_PROF_*_PERCENT * skillLevel, percent, 0)`), on top of the off-hand's raw stats already being scaled by the same `percent`. This is intentional layering but easy to misread as a bug when tracing the numbers.
- **The commented-out `berserker` skill.** `applySkillEffects` contains a large dead/commented block referencing a `SKILL_BERSERKER` skill and `Skills.*` constants that no longer exist (the class was renamed to `SkillCollection`) — it is not wired to any `SkillID` and has no effect; treat it as a historical stub, not a partially-working feature.

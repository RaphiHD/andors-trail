# Character Stats & Leveling

## Overview

Every actor in Andor's Trail — the player character and every monster — carries
a set of combat stats (HP, AP, attack chance, damage, block chance, etc.).
These stats exist in two layers: a **base** layer (what the actor would have
with no equipment, conditions, or skills applied) and a **current/derived**
layer (what the actor actually fights with right now). The derived layer is
not stored incrementally; it is fully rebuilt from the base layer plus every
active modifier (worn items, learned skills, active conditions) each time
something relevant changes. `ActorStatsController` owns this recalculation,
plus HP/AP bookkeeping, player experience/leveling, and the listener
notifications that let the UI and other systems react to stat changes.

## Key classes/files

| File | Role |
|---|---|
| [ActorStatsController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/ActorStatsController.java) | Central controller: recalculates derived stats, applies conditions/ability effects, handles HP/AP changes, experience gain, and level-up. |
| [Actor.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/actor/Actor.java) | Base class for both `Player` and `Monster`. Holds the *current/derived* stat fields (`health`, `ap`, `attackChance`, `damagePotential`, `blockChance`, `damageResistance`, `criticalSkill`, `criticalMultiplier`, `moveCost`, `attackCost`, conditions/immunities lists) and simple derived getters (`getAttacksPerTurn()`, `getEffectiveCriticalChance()`). |
| [Player.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/actor/Player.java) | Player-specific state: `level`, `totalExperience`, `levelExperience` (progress toward next level), `baseTraits` (the player's base/unequipped stats), skill levels, inventory, `weaponDamage`. Contains the experience-curve formulas and `resetStatsToBaseTraits()`. |
| [Monster.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/actor/Monster.java) | Monster instance; stats are seeded from its `MonsterType` via `resetStatsToBaseTraits()`. Can diverge from its type's stats at runtime (e.g. from conditions) — only non-default combat stats are persisted to savegames. |
| [MonsterType.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/actor/MonsterType.java) | Static, shared template read from game data: base HP/AP/attack/damage/etc. for a monster species. Multiple `Monster` instances of the same type share one `MonsterType`. |
| [HeroCollection.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/actor/HeroCollection.java) | Not a stats system — a small static registry of selectable hero sprite sets (small/large icon resource ids) used at character creation. Included here only because it lives in the `actor` package. |
| [StatsModifierTraits.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/ability/traits/StatsModifierTraits.java) | Describes a one-shot current-HP/current-AP boost (e.g. a potion or a condition's per-round tick). Not a persistent stat modifier — it changes `current`, not `max`. |
| [AbilityModifierTraits.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/ability/traits/AbilityModifierTraits.java) | The generic "stat delta bundle" shape (`increaseMaxHP`, `increaseAttackChance`, `increaseMinDamage`, ...) used by items, skills, and conditions alike to modify an actor's derived stats. `ActorStatsController.applyAbilityEffects()` is the single method that applies one of these bundles onto an actor. |
| [ActorStatsListener.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/ActorStatsListener.java) / [ActorStatsListeners.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/ActorStatsListeners.java) | Observer interface (+ multiplexer) for HP/AP/move-cost/attack-cost/reequip-cost/use-cost changes on any actor. |
| [PlayerStatsListener.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/PlayerStatsListener.java) / [PlayerStatsListeners.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/PlayerStatsListeners.java) | Observer interface (+ multiplexer) for player experience changes only. |

## How it works

### Base stats vs. current/derived stats

- **Player**: `Player.baseTraits` (a `PlayerBaseTraits` inner class) holds the
  unequipped, unbuffed values — `maxAP`, `maxHP`, `moveCost`, `attackCost`,
  `attackChance`, `criticalSkill`, `criticalMultiplier`, `damagePotential`,
  `blockChance`, `damageResistance`, `useItemCost`, `reequipCost`. These are
  set once at character creation (`initializeNewPlayer()`, e.g.
  `baseTraits.maxHP = 25`, `baseTraits.attackChance = 60`) and are then only
  changed by leveling up (`addLevelupEffect`) or skill-point resets — never by
  equipping items or gaining a temporary condition.
- **Monster**: there is no separate base-traits object. `MonsterType` (the
  shared species template) *is* the base data. `Monster.resetStatsToBaseTraits()`
  copies every field straight from `monsterType` into the actor's live fields.
- The **derived/current** fields live directly on `Actor` (`health`, `ap`,
  `attackChance`, `damagePotential`, `blockChance`, `damageResistance`,
  `criticalSkill`, `criticalMultiplier`, `moveCost`, `attackCost`). These are
  what combat code reads. They get overwritten wholesale on every
  recalculation — nothing here is "incrementally patched" across calls.

### The recalculation pipeline

`ActorStatsController` exposes two top-level recalculation entry points, and
almost everything else in the mechanic funnels into one of them:

- `recalculatePlayerStats(Player player)`:
  1. `player.weaponDamage.set(0,0)` — clears the weapon-only damage tracker.
  2. `player.resetStatsToBaseTraits()` — derived fields := base traits.
  3. `player.recalculateLevelExperience()` — refreshes the level-progress `Range`.
  4. `controllers.itemController.applyInventoryEffects(player)` — walks every
     worn item slot and applies each item's `effects_equip.stats`
     (an `AbilityModifierTraits`) via `applyAbilityEffects`; also derives
     `weaponDamage` and fighting-style/proficiency skill bonuses. See
     [items-equipment.md](items-equipment.md).
  5. `controllers.skillController.applySkillEffects(player)` — adds
     per-skill-point bonuses (e.g. `weaponChance`, `weaponDmg`, `dodge`,
     `barkSkin`, `speed`, critical-related skills) directly onto the actor's
     derived fields. See [skills.md](skills.md).
  6. `applyEffectsFromCurrentConditions(player)` — for every active
     `ActorCondition`, applies its `conditionType.abilityEffect`
     (an `AbilityModifierTraits`) via `applyAbilityEffects`. See
     [conditions-effects.md](conditions-effects.md).
  7. `ItemController.recalculateHitEffectsFromWornItems(player)` and
     `ItemController.applyDamageModifier(player)` — item-driven on-hit/on-miss
     effect lists and non-weapon damage modifiers.
  8. Caps: `capActorHealthAtMax`, `capActorAPAtMax`,
     `lowCapActorAttackChance` (floors `attackChance` at 0),
     `lowCapActorDamagePotential` (floors `damagePotential.max` at 0, zeroing
     both ends if so).
- `recalculateMonsterCombatTraits(Monster monster)`: the same idea but much
  shorter, since monsters have no inventory or skills — `resetStatsToBaseTraits()`
  → `applyEffectsFromCurrentConditions()` → the same four caps.
- `recalculateActorCombatTraits(Actor actor)` is a small dispatcher used
  internally (e.g. from `applyActorCondition`) that picks the right one of
  the two methods above based on `actor.isPlayer`.

The single method that actually mutates derived fields from a stat-delta
bundle is `applyAbilityEffects(Actor actor, AbilityModifierTraits effects, int multiplier)`:
it adds `effects.increaseMaxHP * multiplier` to max HP (and current HP if
the actor should gain it immediately), `increaseMaxAP * multiplier` to max AP,
adjusts move/attack cost (and, for players only, reequip/use-item cost), and
adds attack chance, critical skill, min/max damage, block chance, and damage
resistance. The `multiplier` parameter is how a condition's *magnitude*
(stack count) scales its effect — the same `AbilityModifierTraits` bundle is
reused for magnitude 1, 2, 3, etc. Note the code comment: "criticalMultiplier
should not be increased. It is always defined by the weapon in use" —
`setCriticalMultiplier` is applied directly (as an assignment) only from the
equipped weapon, in `ItemController.applyInventoryEffects`, not through this
additive path.

Recalculation is triggered whenever the inputs to it change: equipping/
unequipping an item, gaining/losing/leveling a skill, any condition being
added, having its magnitude changed, or expiring, and leveling up. It is
*not* continuously re-run — it is an explicit, on-demand rebuild called from
the relevant controller after the underlying change is made.

### HP and AP mechanics

Current/max HP and AP are represented as [`Range`](../app/src/main/java/com/gpl/rpg/AndorsTrail/util/Range.java)
objects (`current`/`max` pair) on `Actor.health` and `Actor.ap`. Key helper
methods on `ActorStatsController`:

- `changeActorHealth` / `addActorHealth` / `removeActorHealth` — delta
  `current`, optionally allowing under/overflow past 0/max, and fire
  `onActorHealthChanged`.
- `addActorMaxHealth(actor, amount, affectCurrentHealth)` — grows `max`
  (used by level-ups and HP-boosting ability effects), optionally also
  bumping `current` by the same amount.
- `capActorHealthAtMax` / `setActorMaxHealth` — clamp/snap `current` to `max`
  (used after recalculation and when fully healing, e.g. `healAllMonsters`).
- The AP equivalents (`changeActorAP`, `addActorAP`, `addActorMaxAP`,
  `capActorAPAtMax`, `setActorMaxAP`, `setActorMinAP`) mirror the HP ones.
  `useAPs(actor, cost)` is the gate combat/movement code calls to spend AP —
  it fails (returns `false`) without mutating anything if `current < cost`.

`Actor.isDead()` is simply `health.current <= 0`.

### Experience and leveling (player only)

- `Player.totalExperience` is the all-time cumulative XP counter.
  `Player.levelExperience` is a `Range` representing progress *within* the
  current level: `current` = XP earned since reaching this level, `max` = XP
  required to go from this level to the next.
- `ActorStatsController.addExperience(int exp)` adds to
  `p.totalExperience` and to `p.levelExperience` (via `Range.add`, capped at
  max — `mayOverflow=true` is passed, actually: `levelExperience.add(exp, true)`
  allows `current` to exceed `max` temporarily), then fires
  `onPlayerExperienceChanged`.
- The XP curve, in `Player`:
  ```java
  private static final int EXP_base = 55;
  private static int getRequiredExperienceForNextLevel(int currentLevel) {
      return (int) (EXP_base * currentLevel * currentLevel);
  }
  ```
  i.e. the XP needed to go from level `L` to `L+1` is `55 * L^2`. Total XP
  required to *reach* level `L` (`getRequiredExperience`) is the running sum
  of that formula for `i = 1..L-1`.
- `Player.recalculateLevelExperience()` (called every `recalculatePlayerStats`)
  resets `levelExperience` to
  `(max = getRequiredExperienceForNextLevel(level), current = totalExperience - getRequiredExperience(level))`
  — i.e. it is always recomputed from `totalExperience` and `level`, not
  tracked independently.
- `Player.canLevelup()` is just `levelExperience.isMax()` (current >= max).
  The controller does not auto-level; something else in the UI/game-loop
  layer must observe `onPlayerExperienceChanged` / `canLevelup()` and invoke
  `addLevelupEffect`.
- `ActorStatsController.addLevelupEffect(Player player, LevelUpSelection selectionID)`
  performs the actual level-up. `LevelUpSelection` is one of `health`,
  `attackChance`, `attackDamage`, `blockChance` — the player picks one bonus
  per level:
  - `health` → `hpIncrease = Constants.LEVELUP_EFFECT_HEALTH` (5)
  - `attackChance` → `baseTraits.attackChance += Constants.LEVELUP_EFFECT_ATK_CH` (5)
  - `attackDamage` → `baseTraits.damagePotential.max/current += Constants.LEVELUP_EFFECT_ATK_DMG` (1)
  - `blockChance` → `baseTraits.blockChance += Constants.LEVELUP_EFFECT_DEF_CH` (3)

  Then, regardless of selection:
  - If `player.nextLevelAddsNewSkillpoint()` is true, `availableSkillIncreases++`.
    A new skill point is granted every `Constants.NEW_SKILL_POINT_EVERY_N_LEVELS`
    (4) levels, starting at `Constants.FIRST_SKILL_POINT_IS_GIVEN_AT_LEVEL`
    (4): `(level - 4) % 4 == 0`.
  - `player.level++`.
  - `hpIncrease` additionally gets
    `player.getSkillLevel(SkillID.fortitude) * SkillCollection.PER_SKILLPOINT_INCREASE_FORTITUDE_HEALTH`
    added, so the fortitude skill passively boosts every level-up's HP gain
    (even when `health` wasn't the chosen bonus).
  - `addActorMaxHealth(player, hpIncrease, true)` (bumps live max+current HP)
    **and** `player.baseTraits.maxHP += hpIncrease` are both done — the HP
    gain is baked permanently into `baseTraits`, unlike the other three
    selections which mutate `baseTraits` directly at the top of the switch.
  - Finally `recalculatePlayerStats(player)` rebuilds everything from the
    now-updated base traits.

### Hero vs. monster differences

| Aspect | Player | Monster |
|---|---|---|
| Base stats storage | `Player.baseTraits` (mutable, per-instance, grows with levels) | `MonsterType` (immutable, shared template per species) |
| Equipment | Full `Inventory` with wear slots feeding `applyInventoryEffects` | None — `Monster` has no inventory-driven stats |
| Skills | `SkillCollection` skill levels feed `applySkillEffects` | None |
| Leveling / experience | Has `level`, `totalExperience`, `levelExperience` | No concept of level or experience; `Monster.getExp()` is the XP *awarded to the player* on kill, not something the monster itself gains |
| Recalculation entry point | `recalculatePlayerStats` (8-step pipeline above) | `recalculateMonsterCombatTraits` (reset → conditions → caps) |
| Conditions | Same `ActorCondition`/`ActorConditionEffect` system, plus item-driven "permanent" conditions from worn gear (see Gotchas) | Same condition system, but conditions are the *only* thing that can deviate a monster from its `MonsterType` defaults |
| Persistence | Full state serialized every save | Only combat stats that differ from `monsterType` defaults are written to the savegame (see `Monster.writeToParcel`'s field-by-field equality check) |
| Critical-hit immunity | Always `false` (`isImmuneToCriticalHits=false` passed to `Actor`'s constructor) | Determined by `MonsterType.isImmuneToCriticalHits()` — true for `ghost`, `construct`, and `demon` monster classes |

`MonsterType.hasCombatStats()` is a data-authoring helper: it detects whether
a monster type defines any real combat stats at all (its check for "default"
is `attackCost != 10`, i.e. 10 is treated as the "no combat stats configured"
sentinel for that field).

### Derived combat-stat formulas on `Actor`

A couple of read-only derived values are computed straight from current
stats rather than being stored fields:

```java
public int getAttacksPerTurn() { return (int) Math.floor(getMaxAP() / getAttackCost()); }

public static int getEffectiveCriticalChance(int criticalSkill) {
    if (criticalSkill <= 0) return 0;
    int v = (int) (-5 + 2 * Math.sqrt(5*criticalSkill));
    if (v < 0) return 0;
    return v;
}
```

`hasCriticalAttacks()` requires both a non-zero `criticalSkill`
(`hasCriticalSkillEffect`) and a `criticalMultiplier` that is neither `0` nor
`1` (`hasCriticalMultiplierEffect`) — a multiplier of exactly `1` is treated
as "no critical bonus" even though it's non-zero.

## How other systems hook in

- **Items/equipment** ([items-equipment.md](items-equipment.md)): equipping/
  unequipping calls into `ActorStatsController` for condition side-effects
  (`addConditionsFromEquippedItem` / `removeConditionsFromUnequippedItem`),
  and `ItemController.applyInventoryEffects` (called from
  `recalculatePlayerStats`) is what actually folds each worn item's
  `effects_equip.stats` (`AbilityModifierTraits`) into the player's derived
  stats. Worn-item conditions get special handling in
  `gotConditionFromWornItem`/`actorConditionsRemove`: if a temporary
  condition expires but an equipped item still grants it, it's silently
  re-applied as `DURATION_FOREVER` instead of being removed.
- **Skills** ([skills.md](skills.md)): `SkillController.applySkillEffects`
  (called from `recalculatePlayerStats`) adds each skill's per-point bonus
  directly to the player's derived fields. Skills can also be queried by
  other systems via `Player.getSkillLevel(SkillID)` / `hasSkill(SkillID)`
  (e.g. `fortitude` affecting level-up HP, `moreExp` affecting monster kill
  XP in `Monster.createLoot`, `regeneration` healing per round in
  `applySkillEffectsForNewRound`).
- **Conditions** ([conditions-effects.md](conditions-effects.md)): every
  condition type carries an `abilityEffect` (`AbilityModifierTraits`,
  applied on every recalculation via `applyEffectsFromCurrentConditions`) and
  optionally `statsEffect_everyRound` / `statsEffect_everyFullRound`
  (`StatsModifierTraits`, one-shot current-HP/AP ticks applied by
  `applyConditionsToPlayer` / `applyConditionsToMonster` each game round via
  `applyStatsModifierEffect`). Adding, stacking, or expiring a condition
  always ends in a call to `recalculateActorCombatTraits`.
- **Combat** (see `combat.md`): reads the derived fields on `Actor`
  (`attackChance`, `damagePotential`, `blockChance`, `damageResistance`,
  `criticalSkill`/`criticalMultiplier` via `getEffectiveCriticalChance`,
  `attackCost`/`getAttacksPerTurn`) to resolve attacks, and calls back into
  `ActorStatsController` (`changeActorHealth`, `useAPs`, `applyUseEffect`,
  `applyHitReceivedEffect`, `applyKillEffectsToPlayer`,
  `applyOnDeathEffectsToPlayer`) to apply combat outcomes.
- **UI**: subscribes to `ActorStatsListeners` (HP/AP/move-cost/attack-cost/
  reequip-cost/use-cost changes on any actor) and `PlayerStatsListeners`
  (experience changes) to refresh health bars, AP indicators, and the
  experience bar without polling.

## Gotchas / non-obvious behavior

- **Recalculation is a full rebuild, not an incremental patch.** Every call
  to `recalculatePlayerStats`/`recalculateMonsterCombatTraits` starts from
  `resetStatsToBaseTraits()` and reapplies *everything* (items, skills,
  conditions) from scratch. Any code path that changes an input (equip an
  item, learn a skill, add a condition) but forgets to trigger recalculation
  will leave stale derived stats.
- **`weaponDamage` vs `damagePotential`**: `recalculatePlayerStats` zeroes
  `player.weaponDamage` at the very start of every recalculation, then
  `ItemController.applyInventoryEffects` repopulates it from the equipped
  weapon. This is a separate tracked value from `damagePotential` (the
  actual derived min/max damage used in combat) — `weaponDamage` exists
  specifically to isolate weapon-sourced damage from other bonus sources.
- **Level-up HP is double-applied to different places.** In
  `addLevelupEffect`, the chosen `health` bonus and the `fortitude`
  skill bonus are combined into one `hpIncrease`, which is applied to the
  *live* HP via `addActorMaxHealth` **and** baked into `baseTraits.maxHP`
  directly — but `attackChance`/`attackDamage`/`blockChance` selections
  are only ever written into `baseTraits` (via the switch statement), not
  applied directly to the live actor; they only take effect once
  `recalculatePlayerStats` runs at the end of the method.
- **A `criticalMultiplier` of `1` is indistinguishable from "no crit bonus".**
  `hasCriticalMultiplierEffect()` explicitly excludes both `0` and `1`, so
  setting a weapon's critical multiplier to exactly 1 disables
  `hasCriticalAttacks()` even if `criticalSkill` is non-zero.
- **`AbilityModifierTraits.increaseCriticalSkill` and `setCriticalMultiplier`
  are handled asymmetrically.** Critical *skill* is additive through
  `applyAbilityEffects` (so conditions/skills can raise it), but critical
  *multiplier* is never touched by `applyAbilityEffects` — it's set as a flat
  assignment only from the currently equipped weapon in
  `ItemController.applyInventoryEffects` (`player.criticalMultiplier =
  weapon.effects_equip.stats.setCriticalMultiplier`), per the explicit code
  comment that it "should not be increased."
- **Attack chance and damage potential are floored, not the other stats.**
  After recalculation, `lowCapActorAttackChance` clamps negative attack
  chance to 0 and `lowCapActorDamagePotential` zeroes out damage potential
  entirely if its max would go negative — but there is no equivalent floor
  for block chance or damage resistance, which can end up negative from
  conditions/items.
- **Monster stat divergence from `MonsterType` is deliberately rare and
  explicitly tracked for savegames.** `Monster.writeToParcel` and
  `addToChecksum` compare every combat field against `monsterType`'s values
  and write a single boolean flag; only if something differs (almost always
  because of an active condition at save time) are the individual fields
  serialized. A monster whose stats exactly match its type after
  recalculation is cheaper to save/checksum.
- **`levelExperience.add(exp, true)` allows temporary overflow.**
  `addExperience` passes `mayOverflow=true`, so a large XP reward can push
  `levelExperience.current` above `levelExperience.max` before the player
  actually levels up (leveling is a separate, caller-driven action via
  `canLevelup()`/`addLevelupEffect`, not automatic).
- **`recalculateLevelExperience` derives everything from `totalExperience`
  and `level`, every time.** There's no persisted "XP into this level" field
  independent of `totalExperience` — if `level` and `totalExperience` ever
  get out of sync (e.g. via save-editing or a bug), the displayed progress
  bar will be wrong until the mismatch is corrected, since it's recomputed
  from scratch on every `recalculatePlayerStats` call.

# Actor Conditions & Visual Effects

## Overview

"Conditions" are Andor's Trail's status-effect system: poison, stun-like
ability penalties, regeneration, immunities, and equipment-granted buffs are
all represented the same way — as an `ActorCondition` attached to an
`Actor` (a `Player` or `Monster`). A condition has a *type* (its rules: is it
stacking, positive, what it does per round), a *magnitude* (how strong an
instance of it is) and a *duration* (how many rounds it has left, or a
special "forever" value). Conditions are applied by items, combat hits, and
equipment, are recalculated into an actor's combat stats every time they
change, and are ticked down once per game round by `GameRoundController`.

Layered on top, but architecturally separate, is the **visual effect**
system (`VisualEffectController` / `VisualEffectCollection`): short sprite
animations (a red splash, a blue swirl, a green splash, a "miss" icon) with
a floating damage/heal number, played on the map when stats change. This
layer has no direct reference to `ActorCondition` — it is driven by
`enqueueEffect`/`startEnqueuedEffect` calls that `ActorStatsController`
makes whenever a stat-modifying effect (including a condition's per-round
effect) actually changes HP or AP.

## Key classes/files

| File | Role |
|---|---|
| [ActorCondition.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/ability/ActorCondition.java) | Runtime instance of a condition on an actor: `conditionType`, mutable `magnitude`, mutable `duration`. Defines the special constants `MAGNITUDE_REMOVE_ALL`, `DURATION_FOREVER`, `DURATION_FOREVER_UNTIL_SLEEP`, `DURATION_NONE`. |
| [ActorConditionType.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/ability/ActorConditionType.java) | Static, shared definition of a condition (loaded from game data): id, name/description, icon, `ConditionCategory` (spiritual/mental/physical/blood), `isStacking`, `isPositive`, and the three effect blocks (`statsEffect_everyRound`, `statsEffect_everyFullRound`, `abilityEffect`). |
| [ActorConditionTypeCollection.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/ability/ActorConditionTypeCollection.java) | Registry of all `ActorConditionType`s by id, populated by `ActorConditionsTypeParser` at data-load time; looked up via `WorldContext.actorConditionsTypes`. |
| [ActorConditionEffect.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/ability/ActorConditionEffect.java) | A *template* for granting/removing a condition: type, magnitude, duration, and a `ConstRange chance` of it actually applying. Found on item traits and equipment. Has `isImmunity()`/`isRemovalEffect()` helpers and `createCondition()` to turn itself into a live `ActorCondition`. |
| [ActorStatsController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/ActorStatsController.java) | The controller that owns all condition apply/remove/tick logic and folds condition effects into an actor's recalculated stats (see below). Not in the read list but central to this mechanic. |
| [VisualEffectController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/VisualEffectController.java) | Drives frame-by-frame sprite animations for combat effects (`VisualEffectAnimation`), actor movement (`SpriteMoveAnimation`), and blood splatters. Independent of the condition data model. |
| [VisualEffectCollection.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/resource/VisualEffectCollection.java) | Static registry of the four `VisualEffectID`s (`redSplash`, `blueSwirl`, `greenSplash`, `miss`) and their `VisualEffect` frame/timing/color data. |
| [ActorConditionListener.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/ActorConditionListener.java) / [ActorConditionListeners.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/ActorConditionListeners.java) | Observer interface + fan-out broadcaster for condition lifecycle events (added/removed/duration changed/magnitude changed/round effect applied/immunity added/removed/duration changed). Exposed as `ActorStatsController.actorConditionListeners`. |
| [VisualEffectFrameListener.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/VisualEffectFrameListener.java) / [VisualEffectFrameListeners.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/VisualEffectFrameListeners.java) | Observer interface + broadcaster for per-frame animation updates, used by the map/view layer to redraw. |

## How it works

### The data model

- `ActorConditionType` is the shared, immutable "rulebook" entry for a kind
  of condition (e.g. "poison"): whether it stacks (`isStacking`), whether
  it's a buff (`isPositive`), which `ConditionCategory` it belongs to
  (`spiritual`, `mental`, `physical`, `blood` — used for resistance-skill
  lookups, see below), and up to three effect blocks:
  - `statsEffect_everyRound` (`StatsModifierTraits`) — HP/AP delta applied
    every normal round.
  - `statsEffect_everyFullRound` (`StatsModifierTraits`) — HP/AP delta
    applied every *full* round (a coarser, less frequent tick — see
    `game-loop.md`).
  - `abilityEffect` (`AbilityModifierTraits`) — passive modifiers to combat
    traits (max HP/AP, move/attack/reequip/use costs, attack chance,
    critical skill, damage potential, block chance, damage resistance)
    that apply for as long as the condition is present.
- `ActorCondition` is the live instance on an `Actor` — just a
  `conditionType` reference plus mutable `magnitude` and `duration`.
  Actors hold two lists: `Actor.conditions` (active conditions) and
  `Actor.immunities` (active immunities, represented the same way).
- `ActorConditionEffect` is what item/equipment JSON data actually
  specifies: "grant condition X at magnitude M for duration D with chance
  C". `chance` is a `ConstRange` rolled via `Constants.rollResult(...)`.
  Two special encodings layered on top of magnitude/duration:
  - `isImmunity()` — `magnitude == MAGNITUDE_REMOVE_ALL` and
    `duration != DURATION_NONE`: grants immunity to the condition type
    instead of the condition itself.
  - `isRemovalEffect()` — `magnitude == MAGNITUDE_REMOVE_ALL` and
    `duration == DURATION_NONE`: strips all active conditions of that type
    (e.g. a cure potion).
- Duration constants: `DURATION_NONE` (0), `DURATION_FOREVER` (999, used for
  equipment-granted conditions that last as long as the item is worn),
  `DURATION_FOREVER_UNTIL_SLEEP` (998, cleared only by sleeping — see
  `ActorCondition.isTemporaryEffect()`, which is true for anything other
  than these two "forever" values).

### Applying conditions

All of this logic lives in `ActorStatsController`:

- `applyActorCondition(Actor, ActorConditionEffect)` is the main entry
  point. It dispatches on the effect's kind: immunity → `removeAllConditionsOfType` then `addActorConditionImmunity`; removal effect → `removeAllConditionsOfType`; otherwise, if the actor isn't already immune to that condition type, it adds the condition via `addStackableActorCondition` or `addNonStackableActorCondition` depending on `conditionType.isStacking`. It always finishes by calling `recalculateActorCombatTraits(actor)`.
- Stacking conditions (`addStackableActorCondition`): if the actor already
  has a condition of the same type *and same duration*, magnitudes are
  summed instead of adding a second instance.
- Non-stacking conditions (`addNonStackableActorCondition`): a new instance
  only replaces an existing one of the same type if its magnitude is
  strictly higher, or equal magnitude with a longer duration; otherwise the
  weaker/shorter one is dropped and nothing changes.
- `rollForConditionEffect` is how combat/item hits turn an
  `ActorConditionEffect` into an actual roll: it rolls against
  `effect.chance`, biased by `SkillController.getActorConditionEffectChanceRollBias` (resistance skills reduce the chance an enemy's condition effect lands — see `skills.md`), fires a `CombatActionListener` notification, then calls `applyActorCondition`.
- Equipment-granted conditions (`addConditionsFromEquippedItem` /
  `removeConditionsFromUnequippedItem`, both driven from `ItemTraits_OnEquip.addedConditions` — see `items-equipment.md`) always use
  `ActorCondition.DURATION_FOREVER`. Unequipping doesn't blindly delete the
  condition: `removeNonStackableActorCondition` first scans the player's
  other worn slots for an item granting the same condition type/duration
  and bails out if one is found, so two items granting the same debuff/buff
  keep it alive until the last one is removed. The same "is another item
  still granting this" re-check happens for immunities in
  `removeActorConditionImmunity`, including reapplying any condition that
  the immunity had been suppressing.

### Ticking and removal

`GameRoundController` drives this each round (see `game-loop.md` for the
tick/round/full-round cadence):

- `onNewRound()` → `applyConditionsToPlayer(player, false)` and
  `applyConditionsToMonsters(map, false)` (normal round).
- `onNewFullRound()` → same two calls with `isFullRound = true`.

`applyConditionsToPlayer`/`applyConditionsToMonster`:
1. Skip entirely if the actor has no conditions (and, for the player, no
   immunities).
2. On a normal (non-full) round, first roll the `rejuvenation` skill via
   `removeConditionsFromSkillEffects` — on success it reduces the magnitude
   (or removes) one random *temporary, negative, non-spiritual* condition.
3. `applyStatsEffects` applies every condition's `statsEffect_everyRound` or
   `statsEffect_everyFullRound` (negative/non-positive conditions are
   applied before positive ones), then calls
   `controllers.effectController.startEnqueuedEffect(actor.position)` to
   flush any visual effect that was enqueued by those stat changes.
4. If the actor died from a condition's round effect, the round-processing
   function returns early (`mapController.handlePlayerDeath()` for the
   player, `combatController.playerKilledMonster(monster)` for a monster) —
   duration is not decremented in that case.
5. On a normal round only, `decreaseDurationAndRemoveConditions` runs:
   temporary conditions/immunities with `duration <= 1` are removed,
   otherwise `duration` is decremented by 1 and an
   `onActorConditionDurationChanged`/`onActorConditionImmunityDurationChanged`
   event fires. Removing a condition goes through
   `actorConditionsRemove`, which — for a `Player` only — first checks
   `gotConditionFromWornItem`: if an equipped item still grants that
   condition type, the condition is *kept* (its magnitude/duration are
   reset to the item's values) instead of actually being removed. If any
   condition or immunity was removed this round, stats are recalculated
   once via `recalculateActorCombatTraits`.
- `removeAllTemporaryConditions(Actor)` (used e.g. by
  `healAllMonsters`/monster respawn) strips everything that isn't
  `DURATION_FOREVER`, including `DURATION_FOREVER_UNTIL_SLEEP` conditions —
  this is the "sleep clears it" mechanism referenced by that duration
  constant's name, invoked wherever the game handles resting/respawning.

### Feeding into stats

`ActorConditionType.abilityEffect` (an `AbilityModifierTraits`) is *not*
applied at tick time — it's folded into the actor's full stat recompute:
`applyEffectsFromCurrentConditions(actor)` iterates `actor.conditions` and
calls `applyAbilityEffects(actor, c.conditionType.abilityEffect, c.magnitude)`
for each, scaling every modifier field (max HP/AP, move/attack/reequip/use
cost, attack chance, critical skill, min/max damage, block chance, damage
resistance) by the condition's current `magnitude`. This is invoked from:
- `recalculatePlayerStats(Player)` — the full player stat pipeline (also
  resets to base traits, then layers in inventory effects, skill effects,
  conditions, hit effects, damage modifiers, then caps HP/AP and low-caps
  attack chance/damage). See `character-stats-leveling.md` for the rest of
  this pipeline.
- `recalculateMonsterCombatTraits(Monster)` — the monster equivalent
  (no inventory/skills, just base traits + conditions).

Both are reached via `recalculateActorCombatTraits(actor)`, which is called
every time a condition is added, removed, or (indirectly, via the "any
condition removed this round" check) ticked down — so condition-driven stat
changes are always eventually consistent with `actor.conditions`, but are
*not* incrementally patched; the whole stat set is rebuilt from scratch each
time.

### The visual effect layer (separate from conditions)

`VisualEffectController` has no awareness of `ActorCondition` at all. It
manages three independent animation kinds:
- `VisualEffectAnimation` — a short looping sprite (from
  `VisualEffectCollection`, one of `redSplash`/`blueSwirl`/`greenSplash`/`miss`)
  plus an optional floating number, positioned over a tile. Frame stepping
  is driven by a repeating `Handler` (`animationRunnable`) on a fixed
  `EFFECT_UPDATE_INTERVAL` (25ms) scaled by the attack-speed preference.
- `SpriteMoveAnimation` — interpolates an actor's sprite between two tiles
  over a duration; sets `Actor.hasVFXRunning`/`vfxDuration`/`vfxStartTime`
  so the map view can render the in-between position.
- `BloodSplatter` — a fading decal left on the map tile, unrelated to
  actors at all (`updateSplatters`/`addSplatter`, keyed off
  `MonsterType.MonsterClass`).

The link between a condition's *round effect* and a visual effect is
indirect and one-directional: `ActorStatsController.applyStatsModifierEffect`
(called for each condition's `statsEffect_everyRound`/`_everyFullRound`, and
also for direct item use/hit effects) rolls the HP/AP delta, and if it
actually changed the actor's health or AP, calls
`effectController.enqueueEffect(visualEffectID, effectValue)`. The
`VisualEffectID` comes from `StatsModifierTraits.visualEffectID` if set in
the data, otherwise it defaults to `blueSwirl` for AP changes, and to
`blueSwirl` (healing) or `redSplash` (damage) for HP changes based on the
sign of the change. `enqueueEffect` coalesces multiple stat changes in the
same round into a single displayed number (summing `displayValue`, keeping
whichever effect ID had the larger absolute value), and
`startEnqueuedEffect(position)` is what actually calls
`VisualEffectController.startEffect(...)` to spawn the animation. So a
condition's *type/category/icon* has nothing to do with which animation
plays — only its stat delta does. The condition's `iconID` (on
`ActorConditionType`) is a separate, static UI element (see
`ActorConditionList`/`ActorConditionEffectList` below), not part of the
animated `VisualEffectController` system.

## How other systems hook in

- **Combat** (`combat.md`): hit resolution calls
  `ActorStatsController.applyHitReceivedEffect`/`applyUseEffect`, which roll
  `ActorConditionEffect`s from `ItemTraits_OnHitReceived`/`ItemTraits_OnUse`
  via `rollForConditionEffect`. `CombatController` also directly calls
  `applyConditionsToPlayer(player, false)` /
  `applyConditionsToMonsters(map, true)` at specific points in its turn
  handling (in addition to the per-round calls from `GameRoundController`).
- **Items & equipment** (`items-equipment.md`): `ItemTraits_OnEquip.addedConditions`,
  `ItemTraits_OnUse.addedConditions_source`/`addedConditions_target`, and
  `ItemTraits_OnHitReceived` all carry `ActorConditionEffect[]` arrays that
  feed into the apply/remove paths described above. `ItemController` also
  reacts to equip/unequip to call `addConditionsFromEquippedItem`/
  `removeConditionsFromUnequippedItem`.
- **Skills** (`skills.md`): `SkillController.getActorConditionEffectChanceRollBias`
  reduces the effective chance of hostile conditions landing, based on the
  target's resistance skills (mapped from `ConditionCategory`) and a
  special-cased `spore_poison`/`sporeImmunity` interaction. The
  `rejuvenation` skill (rolled in `removeConditionsFromSkillEffects`) can
  shave a negative condition's magnitude/duration each round.
- **Character stats / leveling** (`character-stats-leveling.md`):
  `recalculatePlayerStats`/`recalculateMonsterCombatTraits` are the shared
  recompute pipeline that both leveling and conditions feed into; conditions
  are just one of several inputs (inventory, skills, base traits) folded in
  on every recompute.
- **Game loop** (`game-loop.md`): `GameRoundController.onNewRound()` /
  `onNewFullRound()` are the sole per-round drivers of condition ticking
  (`applyConditionsToPlayer`/`applyConditionsToMonsters`); understanding the
  tick/round/full-round cadence there is required to understand why some
  condition effects (`statsEffect_everyRound`) fire more often than others
  (`statsEffect_everyFullRound`).
- **UI**: [ActorConditionList.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/view/ActorConditionList.java)
  renders the current `conditions`/`immunities` lists as clickable icon rows
  (tapping one opens `Dialogs.showActorConditionInfo`); it reuses
  `ActorConditionEffectList.describeEffect` (built for describing item
  traits) by wrapping a live `ActorCondition` back into a synthetic
  `ActorConditionEffect` with `chance = MAX_CHANCE`. Both
  `ActorConditionListeners` and `VisualEffectFrameListeners` are consumed by
  view-layer listeners to trigger redraws/UI updates without controllers
  reaching into the UI directly.
- **Save/load** (`save-load.md`): `ActorCondition` has its own
  `DataInputStream`/`DataOutputStream` constructor/`writeToParcel` and
  `addToChecksum`, serializing `conditionTypeID` + `magnitude` + `duration`
  (the type itself is re-resolved from `world.actorConditionsTypes` on
  load, not serialized).

## Gotchas / non-obvious behavior

- **"Forever" is a magic number, not infinite.** `DURATION_FOREVER = 999`
  and `DURATION_FOREVER_UNTIL_SLEEP = 998` are just large sentinel ints
  checked by `isTemporaryEffect()`/`isDurationForeverUntilSleep()` — there's
  no separate boolean flag. A condition created with a numeric duration of
  998 or 999 through some other path would be silently treated as
  permanent.
- **Non-stacking "add" can be a no-op or a downgrade-blocker.**
  `addNonStackableActorCondition` only replaces an existing condition of the
  same type if the new one has strictly higher magnitude, or equal
  magnitude with `duration >= existing`. A weaker or equal-but-shorter
  reapplication is silently dropped — this is easy to misread as "conditions
  always refresh."
- **Equipment conditions can survive removal from the visible list.**
  `actorConditionsRemove` re-adds/keeps a condition (resetting its magnitude
  and duration to `DURATION_FOREVER`) if the player still has another item
  equipped that independently grants the same condition type — a condition
  can look like it's ticking down to expiry while actually being propped up
  by gear, and its duration will jump back to 999 rather than hit zero.
- **Immunity suppresses without deleting the grant.** Adding an immunity via
  `applyActorCondition` calls `removeAllConditionsOfType` first, so any
  currently active instance of that condition type is wiped — but the
  *source* (e.g. an equipped item's `addedConditions` entry) is untouched;
  when the immunity itself later expires or is unequipped, `removeActorConditionImmunity`/
  `decreaseDurationAndRemoveConditions` explicitly re-scan worn items and
  re-apply any condition-granting effect that the immunity had been
  masking.
- **Round-effect application order is fixed, not data-driven.**
  `applyStatsEffects` always processes all non-positive conditions before
  all positive ones for a given actor, each round — this is a hardcoded
  loop order in `ActorStatsController`, not something configurable per
  condition.
- **Stat recompute is a full reset, not a delta.** Every call path that
  changes a condition ends in `recalculateActorCombatTraits`, which calls
  `resetStatsToBaseTraits()` and rebuilds everything (inventory, skills,
  conditions, ...) from scratch. Code that mutates `actor.conditions`
  directly without going through `ActorStatsController`'s methods will leave
  stats stale until something else triggers a recompute.
- **The visual effect layer never inspects `ActorConditionType`.** There is
  no mapping table from condition type to `VisualEffectID`; the only link is
  through whatever `StatsModifierTraits.visualEffectID` a condition's round
  effect data happens to specify, or the HP/AP-sign-based default. A
  condition with only an `abilityEffect` (no `statsEffect_everyRound`/`_everyFullRound`)
  produces no visual effect at all when it ticks.
- **`applyConditionsToPlayer`/`applyConditionsToMonster` early-return on
  empty condition lists**, but immunities alone won't cause monsters to be
  processed (`applyConditionsToMonster` only checks `monster.conditions.isEmpty()`,
  unlike the player path, which also checks `player.immunities.isEmpty()`) —
  monsters practically don't carry immunities in the current codebase, but
  this asymmetry is worth knowing if that ever changes.

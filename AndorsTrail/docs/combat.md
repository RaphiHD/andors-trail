# Combat

## Overview

Combat in Andor's Trail is turn-based and grid-based, fought between the `Player` and one or more `Monster`s on the current map. It alternates full "turns": the player spends action points (AP) to move or attack, then all aggressive monsters take their actions, then control returns to the player. Everything about starting/ending a fight, whose turn it is, resolving an individual attack (hit/miss/damage/critical), and notifying the UI/other systems about what happened is owned by `CombatController`. The actual numeric character stats that feed into combat (attack chance, damage potential, block chance, critical skill, etc.) are computed elsewhere by `ActorStatsController` and just read by `CombatController` — see [character-stats-leveling.md](./character-stats-leveling.md) for how those numbers are derived.

## Key classes/files

| File | Role |
|---|---|
| [CombatController.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/CombatController.java) | Owns all combat flow: entering/exiting combat, turn order (player turn vs. monster turn), player action handling (attack/move/flee), monster AI action selection, attack resolution (hit chance, damage, critical hits), and firing listener callbacks. |
| [AttackResult.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/AttackResult.java) | Immutable value object returned by every attack: whether it hit, whether it was a critical hit, the damage dealt, and whether the target died. `AttackResult.MISS` is a shared constant for missed attacks. |
| [CombatActionListener.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/CombatActionListener.java) / [CombatActionListeners.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/CombatActionListeners.java) | Callback interface (and fan-out multiplexer) for individual combat *actions*: attack hit/miss, monster movement, kills, fleeing, out-of-AP, taunts, and condition application. This is what UI (activities/views) and other controllers subscribe to in order to react to combat events. |
| [CombatSelectionListener.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/CombatSelectionListener.java) / [CombatSelectionListeners.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/CombatSelectionListeners.java) | Callback interface for changes to the player's current combat *target/destination selection* (tapping a monster or a tile while in combat). |
| [CombatTurnListener.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/CombatTurnListener.java) / [CombatTurnListeners.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/listeners/CombatTurnListeners.java) | Callback interface for combat/turn *lifecycle*: combat started/ended, a new player turn began, a monster is attacking. |
| [Actor.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/actor/Actor.java) | Base model class for `Player` and `Monster`. Holds the combat-relevant derived fields `CombatController` reads directly: `attackChance`, `blockChance`, `damageResistance`, `damagePotential` (a `Range`), `criticalSkill`, `criticalMultiplier`, `attackCost`, `moveCost`, `ap`/`health` (`Range`s), plus on-hit/on-miss item effect arrays. |
| `ActorStatsController.java` (controller package) | Not a combat file per se, but computes/recalculates the derived fields above (`recalculatePlayerStats`, `recalculateMonsterCombatTraits`, `applyAbilityEffects`) from base traits, equipment, skills, and active conditions, and applies AP/health changes (`useAPs`, `removeActorHealth`, `setActorMaxAP`, etc.) that `CombatController` calls into. Full details belong in [character-stats-leveling.md](./character-stats-leveling.md). |

## How it works

### Entering and leaving combat

- `CombatController.enterCombat(BeginTurnAs whoseTurn)` sets `world.model.uiSelections.isInCombat = true`, resets per-fight state (`resetCombatState()` clears `killedMonsterBags` and `totalExpThisFight`), fires `combatTurnListeners.onCombatStarted()`, and then starts either the player's turn, the monsters' turn, or continues whatever turn was already in progress, depending on `BeginTurnAs`.
- Combat is also entered reactively: `monsterSteppedOnPlayer(Monster m)` selects that monster and calls `enterCombat(BeginTurnAs.monsters)` — i.e. a monster moving onto the player's tile during free-roam triggers combat starting on the monsters' turn.
- `exitCombat(boolean pickupLootBags, boolean canceledCombat)` clears the current selection, sets `isInCombat = false`, fires `combatTurnListeners.onCombatEnded()`, resets the player's AP to max, and either resets round timers (if the player died or combat was canceled) or runs `endOfCombatRound()` (ticks world time, resets round timers, applies conditions to player and monsters — see [conditions-effects.md](./conditions-effects.md)). If there is loot/XP from killed monsters, it hands off to `itemController.lootMonsterBags(...)`; otherwise it just resumes the game round controller.
- Combat naturally ends each time control returns to the player: `newPlayerTurn()` calls `canExitCombat()` first, and if there is no `getAdjacentAggressiveMonster()`, it calls `exitCombat(true)` immediately instead of granting the player a turn.
- The player can also actively flee: `startFlee()` clears the current selection and fires `onPlayerStartedFleeing()`; the actual flee attempt happens through `executeMoveAttack` → `executeFlee(dx, dy)`, described below.

### Turn structure

Combat alternates between **the player's turn** and **the monsters' turn**, tracked by `world.model.uiSelections.isPlayersCombatTurn`:

- **Player turn**: `newPlayerTurn(isFirstRound)` refills the player's AP to max (`actorStatsController.setActorMaxAP`), advances the round counter (`gameRoundController.onNewPlayerRound()`, skipped on the very first round), sets `isPlayersCombatTurn = true`, and fires `onNewPlayerTurn()`. The player then spends AP through `executeMoveAttack(dx, dy)`, which dispatches to attacking the selected monster, moving to a selected tile, exiting combat, fleeing, or auto-selecting an adjacent aggressive monster and attacking it — all gated on `isPlayersCombatTurn` being true. Every player action costs AP (see `useAPs`); once the player can no longer afford to move, attack, or use an item (`playerHasApLeft()` checks `getUseItemCost()`, `getAttackCost()`, `getMoveCost()`), `playerActionCompleted()` calls `endPlayerTurn()`.
- **Monster turn**: `beginMonsterTurn(isFirstRound)` sets the player to minimum AP (`setActorMinAP`), sets `isPlayersCombatTurn = false`, refills AP for every monster on the map, and repeatedly calls `handleNextMonsterAction()`. For each step, `determineNextMonsterAction(playerPosition)` picks one monster (`currentActiveMonster`) to either `attack` (if it has enough AP and is adjacent to the player, via `shouldAttackWithMonsterInCombat`) or `move` (if it has a `MonsterType.AggressionType` other than `none` and its movement rules are satisfied, via `shouldMoveMonsterInCombat` — handles `protectSpawn`, `helpOthers`, and `wholeMap` aggression types), or `none` if no monster can act, which ends the monster turn (`endMonsterTurn()` → `newPlayerTurn(false)`). Between monster actions, `waitForNextMonsterAction()` optionally delays via a `Handler` by `preferences.attackspeed_milliseconds` to pace the animation; if that preference is `<= 0`, actions resolve immediately with no delay.
- Player death during a monster attack is handled inline: `monsterAttackCompleted()` checks `lastAttackResult.targetDied` and calls `mapController.handlePlayerDeath()` instead of continuing the monster turn.

### Movement and fleeing during combat

- `executeCombatMove(dest)` costs `getMoveCost()` AP and rolls a flee-failure chance: `Constants.FLEE_FAIL_CHANCE_PERCENT` (20) minus a bias from the `evasion` skill (`SkillCollection.PER_SKILLPOINT_INCREASE_EVASION_FLEE_CHANCE_PERCENTAGE` per skill level). If `Constants.roll100(...)` succeeds against that (reduced) fail chance, `fleeingFailed()` fires `onPlayerFailedFleeing()` and immediately ends the player's turn — i.e. a failed move-away attempt burns the rest of the turn.
- `executeFlee(dx, dy)` (triggered by a directional input with no monster/position selected) looks for a walkable tile using `MOVEMENTAGGRESSIVENESS_DEFENSIVE` (avoiding monster "fields") and aborts if a monster already occupies the destination.

### Attack resolution

An attack is resolved by the private `attack(Actor attacker, Actor target)` method, called from `playerAttacks()` / `monsterAttacks()` as `attack(world.model.player, currentMonster)` or `attack(currentMonster, world.model.player)`. Steps:

1. **Hit chance** — `getAttackHitChance(attacker, target)` computes:

   ```
   c = attacker.getAttackChance() - target.getBlockChance()
   hitChance = 50 * (1 + (2/pi) * atan((c - n) / F))   // n = 50, F = 40
   ```

   clamped implicitly into `[0..100]` by the arctangent's range. This is an S-curve, not a linear difference: at `c == n` (i.e. attack chance exceeds block chance by exactly 50) hit chance is exactly 50%, and it approaches (but never reaches) 0% or 100% at the extremes. The code comments point to a forum post (`https://andorstrail.com/viewtopic.php?f=3&t=6661`) explaining the derivation, and explicitly warn: *"if you change code here make sure to run the tests in `CombatControllerTest.java`."*
2. `Constants.roll100(hitChance)` decides hit vs. miss. On a miss, `applyAttackMissStatusEffects(attacker, target)` runs (see below) and `AttackResult.MISS` is returned — no AP/health side effects beyond those on-miss item effects.
3. **Damage** — on a hit, `Constants.rollValue(attacker.getDamagePotential())` rolls a value inside the attacker's damage `Range` (`current`..`max`).
4. **Critical hits** — `hasCriticalAttack(attacker, target)` requires `attacker.hasCriticalAttacks()` (both `hasCriticalSkillEffect()` i.e. `criticalSkill != 0`, and `hasCriticalMultiplierEffect()` i.e. `criticalMultiplier` is set and not `1`) and that the target is not `isImmuneToCriticalHits()`. If eligible, `Constants.roll100(attacker.getEffectiveCriticalChance())` decides the crit; `getEffectiveCriticalChance()` on `Actor` converts raw `criticalSkill` into a percentage via `(int)(-5 + 2*sqrt(5*criticalSkill))` (floored at 0) — a diminishing-returns curve, not a linear percentage. On a crit, rolled damage is multiplied by `attacker.getCriticalMultiplier()` (a float, defined by the weapon in use per the comment in `ActorStatsController.applyAbilityEffects`).
5. **Damage resistance** — `target.getDamageResistance()` is subtracted from the (possibly critical) damage; negative results are clamped to 0.
6. Damage is applied via `actorStatsController.removeActorHealth(target, damage)`.
7. `applyAttackHitStatusEffects(attacker, target)` runs on-hit item effects (see "How other systems hook in" below).
8. The method returns `new AttackResult(true, isCriticalHit, damage, target.isDead())`.

`AttackResult` is stored as `lastAttackResult` on the controller (used to detect the player's death after a monster's attack) and passed to all relevant `combatActionListeners` calls (`onPlayerAttackSuccess`/`onPlayerAttackMissed`/`onMonsterAttackSuccess`/`onMonsterAttackMissed`) plus into `skillController.applySkillEffectsFromPlayerAttack`/`applySkillEffectsFromMonsterAttack`.

There is no separate ranged-vs-melee attack path in `CombatController` — a single `attack()`/`getAttackHitChance()` code path handles any attacker/target pair; any ranged-specific behavior (line of sight, ammo) would live in whatever computes `attackChance`/positioning rather than in the combat resolution itself.

### Visual pacing of attacks

After an attack is resolved, `startAttackEffect(...)` (hit) or `startMissedEffect(...)` (miss) triggers a `VisualEffectController` animation (`redSplash` with the damage number, or `miss` with the localized "miss" string) at the target's position. If `preferences.attackspeed_milliseconds <= 0`, the callback (`playerAttackCompleted()` / `monsterAttackCompleted()`) fires immediately with no animation; otherwise it fires once the visual effect completes, via the `VisualEffectCompletedCallback` interface that `CombatController` itself implements (`onVisualEffectCompleted(int callbackValue)`, dispatching on `CALLBACK_PLAYERATTACK` / `CALLBACK_MONSTERATTACK`).

### Combat/monster difficulty estimation

`CombatController` also exposes analytical helpers not tied to a specific fight:

- `getAverageDamagePerHit(attacker, target)` computes expected damage per successful strike by enumerating every possible damage roll, weighting non-critical vs. critical outcomes by `getEffectiveCriticalChance()`, and multiplying by hit chance — used for UI/tooltip-style estimates rather than actual combat resolution.
- `getTurnsToKillTarget` and the public `getMonsterDifficulty(Monster monster)` (returns `[0..100)`, 100 = easy) use that average-damage estimate plus `getAttacksPerTurn()` (`floor(maxAP / attackCost)`) to estimate how many turns each side needs to kill the other, and combine them into a single difficulty score the player-facing UI can show before engaging a monster.

### Player kills and rewards

`playerKilledMonster(killedMonster)` creates loot on the monster's tile, removes the monster from its spawn area, adds a blood splatter visual effect, grants bonus AP/health from the `cleave`/`eater` skills, records the kill in `world.model.statistics`, grants experience via `actorStatsController.addExperience(loot.exp)`, applies kill/on-death effects (`applyKillEffectsToPlayer`, `applyOnDeathEffectsToPlayer`), stashes the loot bag for later pickup if still in combat, and fires `onPlayerKilledMonster(killedMonster)`. If the killed monster was the current selection, it auto-advances to `selectNextAggressiveMonster()`.

## How other systems hook in

- **`CombatActionListener`** is the primary extension point for reacting to individual combat events: attack hit/miss (with the `AttackResult`), a monster moving during combat, a kill, fleeing succeeding/failing, running out of AP, taunting, and conditions being applied to player or monster as a result of combat (`onPlayerReceviesActorCondition`, `onMonsterReceivesActorCondition`). UI layers (activities/views) and other controllers register here to update combat log text, animations, etc.
- **`CombatSelectionListener`** notifies when the player's tap-target changes during combat — a monster selected, a movement tile selected, or the selection cleared — driven by `setCombatSelection(...)`.
- **`CombatTurnListener`** notifies on the coarse combat lifecycle: `onCombatStarted`/`onCombatEnded`/`onNewPlayerTurn`/`onMonsterIsAttacking`.
- **Items** ([items-equipment.md](./items-equipment.md)): `Actor` carries `onHitEffects`/`onMissEffects` (`ItemTraits_OnUse[]`, the attacker's own effects triggered by landing/missing a blow) and `onHitReceivedEffects`/`onMissReceivedEffects` (`ItemTraits_OnHitReceived[]`, the target's reaction to being hit or missed). `CombatController.applyAttackHitStatusEffects`/`applyAttackMissStatusEffects` apply these through `actorStatsController.applyUseEffect(...)` / `applyHitReceivedEffect(...)` after every resolved attack, win or miss. `damagePotential`, `criticalMultiplier`, `attackChance`, `blockChance`, and `damageResistance` on `Actor` are themselves populated largely from equipped weapon/armor traits — see items-equipment.md for how gear translates into those fields.
- **Conditions** ([conditions-effects.md](./conditions-effects.md)): `endOfCombatRound()` calls `actorStatsController.applyConditionsToPlayer(...)` and `applyConditionsToMonsters(...)` after every full combat round, and on-hit/on-miss item effects can themselves grant `ActorCondition`s (surfaced via `onPlayerReceviesActorCondition`/`onMonsterReceivesActorCondition` on `CombatActionListener`). Active conditions feed back into combat stats through `ActorStatsController.applyEffectsFromCurrentConditions` → `applyAbilityEffects`, which adjusts `attackChance`, `criticalSkill`, `damagePotential`, `blockChance`, and `damageResistance` directly on the `Actor`.
- **Stats/leveling** ([character-stats-leveling.md](./character-stats-leveling.md)): all of `attackChance`, `blockChance`, `damageResistance`, `damagePotential`, `criticalSkill`, `criticalMultiplier`, `attackCost`, `moveCost` are fields on `Actor` recalculated by `ActorStatsController.recalculatePlayerStats`/`recalculateMonsterCombatTraits` from base traits, equipment, skills, and conditions — `CombatController` only reads these via `Actor`'s getters, it never computes them itself.

## Gotchas / non-obvious behavior worth knowing

- **Hit chance is non-linear.** `getAttackHitChance` is an arctangent S-curve around a 50-point advantage (`n = 50`, `F = 40` as the slope divisor), not a simple percentage difference between attack and block chance. Small stat changes near the middle of the curve swing hit chance a lot more than the same change near the extremes.
- **Critical chance is also non-linear**, via `Actor.getEffectiveCriticalChance()`'s `-5 + 2*sqrt(5*criticalSkill)` formula — diminishing returns on raw critical skill investment.
- **`criticalMultiplier` is weapon-defined, not additive.** The comment in `ActorStatsController.applyAbilityEffects` explicitly notes "criticalMultiplier should not be increased. It is always defined by the weapon in use" — unlike most other combat stats, effects don't accumulate a bonus into it.
- **A monster's `currentActiveMonster` is sticky within a monster turn.** `determineNextMonsterAction` re-checks `currentActiveMonster` first and keeps using it as long as it can still attack, before falling back to scanning `world.model.currentMaps.map.monsters` for the next aggressive monster — so one monster can take a move-then-attack sequence across multiple `handleNextMonsterAction()` calls before another monster gets a turn.
- **Failing to flee (a failed combat move) ends the player's whole turn immediately**, not just that action — `fleeingFailed()` calls `endPlayerTurn()` directly rather than just declining the move.
- **`playerActionCompleted()` can silently exit combat mid-turn.** After any successful player action, if `canExitCombat()` becomes true (no adjacent aggressive monster left), combat ends right there via `exitCombat(true)` rather than waiting for the player to run out of AP.
- **Attack pacing (`preferences.attackspeed_milliseconds`) can fully disable animations.** When it is `<= 0`, both `startAttackEffect`/`startMissedEffect` and monster-turn delays (`waitForNextMonsterAction`) skip the visual effect / `Handler` delay entirely and resolve synchronously — worth checking when debugging combat callback ordering, since the async delay path and the synchronous path both funnel into the same callback methods but with very different timing.
- **`AttackResult.MISS` is a shared singleton** (`damage = 0`, `targetDied = false`) reused for every miss rather than constructing a new instance — fine since `AttackResult` is immutable, but worth knowing if you ever consider mutating it.
- **On-hit/on-miss item effects fire even for the attacker on a miss.** `applyAttackMissStatusEffects` still applies `attacker.getOnMissEffects()` and `target.getOnMissReceivedEffects()`, so items can have gameplay-relevant "on miss" behavior, not just "on hit."

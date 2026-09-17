# Andor's Trail — Core Mechanics Documentation

This folder documents the core game mechanics of Andor's Trail, an open-source
Android RPG. It is aimed at contributors who need to understand how a system
works before changing it — each document explains the relevant classes, the
data model, and how the pieces interact at runtime.

All source paths below are relative to `app/src/main/java/com/gpl/rpg/AndorsTrail/`.

## Contents

| Doc | Covers |
|---|---|
| [game-loop.md](game-loop.md) | The central round/tick loop that drives time, controllers, and listeners |
| [combat.md](combat.md) | Turn-based combat, attack resolution, hit/damage/miss, combat controllers |
| [character-stats-leveling.md](character-stats-leveling.md) | Actor/Player stats, experience, leveling, derived combat stats |
| [items-equipment.md](items-equipment.md) | Item types, inventory, equipment slots, quick slots, loot, item traits |
| [skills.md](skills.md) | Player skills/abilities and their stat/ability modifiers |
| [conditions-effects.md](conditions-effects.md) | Actor conditions (buffs/debuffs/status effects) and visual effects |
| [quests.md](quests.md) | Quest definitions, progress tracking, requirements, and script effects |
| [conversations.md](conversations.md) | Dialogue trees, phrases, replies, and conversation-driven world events |
| [maps-world.md](maps-world.md) | Local maps, map objects/sections, the overworld map, and travel |
| [monsters.md](monsters.md) | Monster types, AI movement, and spawning |
| [movement-pathfinding.md](movement-pathfinding.md) | Player/monster movement and the pathfinding algorithms |
| [save-load.md](save-load.md) | Savegame format, serialization, and legacy-format migration |
| [wip/travellingNPC.md](wip/travellingNPC.md) | **Work in progress.** Status of the cross-map "Travelling NPCs" feature being built on this branch: what's implemented, the manual test bed, and known gaps to close |
| [wip/PLAN.md](wip/PLAN.md) | **Work in progress.** Phased implementation plan for finishing the "Travelling NPCs" feature, starting with the known travel-timing bug |
| [wip/changelog.md](wip/changelog.md) | **Work in progress.** Chronological record of bugs found and fixed while working through PLAN.md's Phase 0, with root causes and files touched |

## How the systems relate

At the top, `WorldContext` and `ControllerContext` wire together the game's
static data (loaded once from JSON/TMX resources) and its mutable controllers
(one per mechanic, e.g. `CombatController`, `MapController`, `ItemController`).
Controllers communicate with the UI and with each other through the
`controller/listeners` observer interfaces rather than direct references,
which keeps each mechanic relatively decoupled. `GameRoundController` is the
heartbeat that advances world time and lets other controllers react each
round (monster movement, condition durations, spawning, etc.).

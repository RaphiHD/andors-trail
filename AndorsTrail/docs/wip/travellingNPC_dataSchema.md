# Travelling NPCs — Data Schema Reference

This document specifies exactly what content authors (and, eventually, the ATCS
content editor) need to write into `.tmx` map files and `.json` resource files
to make a monster travel across one or more maps to a destination, and to
control how that monster's local pathfinding treats map obstacles along the
way.

It is a **schema reference**, not a design narrative — see
[`travellingNPC.md`](travellingNPC.md) for the "why" and the implementation
history, and [`PLAN.md`](PLAN.md) for phase-by-phase status. Line/field names
below are taken directly from the parser code as of this branch
(`travellingnpc_c`); if a future refactor renames something, this document
must be updated alongside it.

All source references are relative to `app/src/main/java/com/gpl/rpg/AndorsTrail/`.


## 1. Data model overview

A journey is defined by three independent pieces of authored data, plus one
runtime-only piece that a content editor must **never** try to author directly:

| Piece | Authored in | Authored by |
|---|---|---|
| Where a monster *can* travel to | `.tmx` map file, a `destination` object | Map/level author |
| How the world's maps connect to each other | `.tmx` map files, `mapchange` objects (every map, not travel-specific) | Map/level author |
| What *triggers* a journey, and what happens if it fails | `.json` conversation/phrase file, `setDestination` / `setTravelFailedScript` rewards | Dialogue/quest author |
| The monster's live position, current leg, retry counter, etc. | Runtime only (`Monster.travelDestination`, `Monster.travelPath`, `Monster.travelBlockedRetries`) | **Not authored.** Computed by `GlobalPathFinder` / `MonsterMovementController` at runtime and persisted only in savegames. |

A "travelling NPC" needs no special flag on the monster itself and no special
spawn setup. Any monster that exists as a normal `Monster` instance (spawned
via a `spawn` map object, or otherwise placed in the world) can be sent
travelling at any time by firing a `setDestination` script reward against it as
the current NPC context (`ConversationController.setTravelDestination`,
`ScriptEffect.ScriptEffectType.setDestination`). There is no `MonsterType`-level
"can this monster type travel" switch.


## 2. TMX object types

All travel-relevant objects are read by `TMXMapTranslator.transformMaps`
(`model/map/TMXMapTranslator.java`), inside the per-`TMXObjectGroup` /
per-`TMXObject` loop. Object *group* names are cosmetic organization inside
Tiled — the parser only looks at each object's own `type` attribute — but by
convention this codebase keeps a `Destinations` group for `destination`
objects, a `Keys` group for `key` objects, a `Mapevents` group for `mapchange`
objects, and a `Replace` group for `replace` objects (see `traveltest1.tmx`).

### 2.1 `destination` — a `TravelDestinationArea`

```xml
<object id="49" name="rect1_center" type="destination" x="128" y="128" width="64" height="64">
 <properties>
  <property name="script" value="traveltester_start"/>
 </properties>
</object>
```

| XML attribute / property | Maps to | Required | Notes |
|---|---|---|---|
| `name` | `TravelDestinationArea.areaID` | **Yes** | Must be unique **within this map** (checked at load time when `DEVELOPMENT_VALIDATEDATA` is on; duplicates just silently shadow each other otherwise — see `PredefinedMap`'s constructor-time duplicate check). This is the string a `setDestination` reward's `rewardID` must match. |
| `x`, `y`, `width`, `height` | `TravelDestinationArea`'s area (`MapArea.area`, in tile units, converted from pixels via the map's `tilewidth`/`tileheight`) | **Yes** | Any tile-sized rectangle. A monster is considered "arrived" as soon as its position is contained in this rectangle — see `MonsterMovementController.determineMonsterNextPosition`. |
| `script` (property) | `TravelDestinationArea.arrivalScript` | No | A phrase ID, run via `MapController.runScriptForNpc(arrivalScript, monster)` the instant the monster arrives (`TravelDestinationArea.onMonsterArrived`). Omit for "nothing happens on arrival". This script runs **even if the player is nowhere near this map** — it does not depend on the player having this map loaded. |

Runtime effect on arrival, in order: `monster.area` is set to this
`TravelDestinationArea`, `monster.travelDestination` is cleared (so
`isTravelling` requirements — §3.3 — become false again), then
`arrivalScript` (if any) is run with this monster as the NPC context.

There is no restriction on how many `destination` objects a map may have, and
no requirement that they be reachable from anywhere in particular — an
unreachable destination is simply a journey nobody can complete (see §4 on
validating reachability).

### 2.2 `mapchange` — a map exit/entry point

This object type is **not travel-specific** — it is the same object type used
for the player's own map transitions — but travelling monsters' cross-map
routing (`GlobalPathFinder`) is built entirely out of these objects, so its
rules matter here.

```xml
<object id="27" name="rect1_north" type="mapchange" x="128" y="0" width="64" height="32">
 <properties>
  <property name="map" value="traveltest2"/>
  <property name="place" value="map2_1"/>
 </properties>
</object>
```

| XML attribute / property | Maps to | Required | Notes |
|---|---|---|---|
| `name` | `MapObject.id` | **Yes** | Must be unique **within this map** among all `mapchange` objects. `GlobalPathFinder`'s search graph, `PredefinedMap.getDistance`/`calculateDistanceMatrix`, and `PredefinedMap.findEventObject(newmap, ...)` all key off this string. Two `mapchange` objects with the same name on one map will corrupt the local distance matrix (one silently overwrites the other during `calculateDistanceMatrix`). |
| `x`, `y`, `width`, `height` | `MapObject.position` | **Yes** | The exit's footprint. Used both as a normal walkable-area trigger for the player and as the "entry point" area a travelling monster spawns into when it arrives on this map from elsewhere (`MonsterMovementController.spawnMonsterOnMap`). |
| `map` (property) | `MapObject.map` | No | Name of the map this exit leads to. **Omit entirely** for an object that only ever serves as an *arrival point* referenced by some other map's `place` (see `entry` in `traveltest1.tmx`, which has no `map`/`place` at all) — `GlobalPathFinder` only follows an exit outward when `mapchange.map != null`. |
| `place` (property) | `MapObject.place` | Only if `map` is set | The `name` of the corresponding `mapchange` object on the **destination** map that this exit leads to. `GlobalPathFinder` matches `u.mapchange.place` against the destination map's exits by `id` (i.e. by `name`) — a `place` value that doesn't match any `mapchange` `name` on that map makes this exit a dead end for cross-map routing. |

**Same-map warp pairs.** A `mapchange` object may point back at its own map
(`map` equal to the map's own name) — used for things like a wraparound edge.
Both `traveltest1.tmx`'s `rect1_west`/`rect3_west` pair are on `traveltest1`
itself, each pointing at the other:

```xml
<object id="1" name="rect1_west" type="mapchange" x="0" y="128" width="32" height="64">
 <properties><property name="map" value="traveltest1"/><property name="place" value="rect3_west"/></properties>
</object>
<object id="4" name="rect3_west" type="mapchange" x="0" y="448" width="32" height="64">
 <properties><property name="map" value="traveltest1"/><property name="place" value="rect1_west"/></properties>
</object>
```

`GlobalPathFinder` treats crossing *any* `mapchange` (same-map or not) as an
instantaneous, zero-cost hop between its two ends — the walk is only ever
costed up to the exit and, separately, onward from the entry point.
`MonsterMovementController.determineMonsterNextPosition` special-cases a
same-map pair to execute it as an instant position swap (offset-preserving)
rather than routing the monster through the travelling-monster pool, since
there's no real "other map" to hand it off to.

**Local reachability requirement.** `PredefinedMap.calculateDistanceMatrix()`
precomputes local walking distance between every pair of `mapchange` exits on
the same map, once at map load (and again whenever a `ReplaceableMapSection`
changes the walkable layout — see §2.5). `GlobalPathFinder`'s graph search
relies entirely on this precomputed matrix for "travel within a map between
two of its exits" edges (`PredefinedMap.getDistance`). If two exits on a map
are not connected by any walkable path, `getDistance` returns `-1` and the
global search simply won't route through that map that way — this is correct
behavior for e.g. a map split into two disconnected physical regions, not a
bug, but it does mean **a destination is only reachable from an exit if that
exit's map has an actual walkable route to the destination area** (or to
another exit on a route toward it).

### 2.3 `key` — a `keyarea` (`MapObject.MapObjectType.keyarea`)

Note the TMX `type` attribute is `"key"`, but the internal enum value and all
Java-side terminology is `keyarea` — this is an existing naming quirk, not new
to travel.

```xml
<object id="9" name="gate_open" type="key" x="96" y="96" width="32" height="32">
 <properties>
  <property name="phrase" value="gate_denied"/>
  <property name="requireType" value="questProgress"/>
  <property name="requireId" value="towngate"/>
  <property name="requireValue" value="1"/>
  <property name="requireNegation" value="false"/>
  <property name="monstersCanPass" value="true"/>
 </properties>
</object>
```

| Property | Maps to | Required | Notes |
|---|---|---|---|
| `phrase` | `MapObject.id` (phrase ID) | No (defaults to `""`) | Phrase run for the **player** when they fail the requirement (`MapController.canEnterKeyArea` → `runScriptInArea`). |
| `requireType`, `requireId`, `requireValue`, `requireNegation` | Parsed into a `Requirement` (`TMXMapTranslator.parseRequirement`) | `requireType` effectively required (defaults to `questProgress` if omitted; an unparsable enum value makes the whole `Requirement` `null`, i.e. no gate at all for the player) | Same `Requirement` schema used everywhere else in the game (quest stage, item possession, faction score, etc. — see `Requirement.RequirementType`). This is evaluated **only for the player**, never for monsters. |
| `monstersCanPass` | `MapObject.monstersCanPass` | No (defaults to `false`) | **New in this branch.** A static, author-set flag deciding whether a travelling monster's local pathfinding (`PathFinder`, via `MonsterMovementController.monsterCanMoveTo`) may route through this key area at all. Monsters never evaluate `enteringRequirement` — most requirement types (inventory, quest progress) check player-specific state, so "does this monster satisfy it" usually isn't a meaningful question. If a key area's monster-passability needs to change dynamically (e.g. only after a quest stage), do **not** try to make `monstersCanPass` conditional — instead gate the `key` object itself behind a `ReplaceableMapSection` (§2.5) that adds/removes it, or place a `monstersCanPass="true"` key area under one `objectgroup` `active`-toggled by `activateMapObjectGroup`/`deactivateMapObjectGroup` script rewards. |

**Default is closed to monsters.** Any `key` object authored without
`monstersCanPass="true"` blocks travelling monsters' pathfinding exactly like
a wall (`MonsterMovementController.monsterCanMoveTo` returns `false` for it) —
this is the same default a plain unwalkable tile has, so existing maps need no
changes unless a specific key area should now let travellers through.

### 2.4 `spawn` — monster spawn areas (not travel-specific, but relevant)

A travelling monster must exist somewhere first — normally via a `spawn`
object. Two existing properties matter for travel-capable monsters:

| Property | Effect on travel |
|---|---|
| `ignoreAreas` (boolean, default `false`) | If `true`, this monster's `Monster.ignoreAreas` is `true`, which makes both its normal local movement *and* its travel pathfinding (`monsterCanMoveTo`'s `ignoreAreas` branch) skip all `MapObject` event-area checks entirely — including `keyarea`/`monstersCanPass` and other `newmap`/`rest` blocking. Used by `traveltest1.tmx`'s `traveltester` test NPC so it can freely path anywhere on the test maps regardless of key areas. Only use this for NPCs that are genuinely meant to ignore every gate. |
| `spawngroup` | Selects which `MonsterType`(s) (via `monsterlist`'s `spawnGroup` JSON field) populate this spawn. Not travel-specific, but this is how a travelling NPC's `MonsterType` gets chosen. |

### 2.5 `replace` — `ReplaceableMapSection` (layout changes, not travel-specific)

Any `replace` object whose `Walkable` property swaps in a different walkable
layer is picked up automatically: `MapController.applyReplacements` triggers
`PredefinedMap.calculateDistanceMatrix()` whenever a replacement actually
changes the map's layout, so both a monster's live local A* search and the
map's exit-to-exit distance matrix (§2.2) reflect the new layout on the very
next pathfinding attempt. No travel-specific authoring is needed here beyond
what layout-replacement already requires (a `Requirement`, same schema as
§2.3). See `PredefinedMap.liveTileMap()` for the one caveat: only the map the
*player* currently has loaded gets a live-synced tile map for this purpose —
a travelling monster routing through a map the player isn't on will path
against that map's most recently loaded default layout, not necessarily
its current replacement state.


## 3. JSON — phraselist rewards and requirements

Parsed by `ConversationListParser` (`resource/parsers/ConversationListParser.java`)
against field names declared in `JsonFieldNames.PhraseReward` /
`JsonFieldNames.ReplyRequires`.

### 3.1 `setDestination` — start a journey

```json
{
    "rewardType": "setDestination",
    "rewardID": "rect1_center",
    "mapName": "traveltest1"
}
```

| JSON field | Meaning | Required |
|---|---|---|
| `rewardType` | Literal `"setDestination"` | Yes |
| `rewardID` | The target `destination` object's `name` (§2.1's `areaID`) | Yes |
| `mapName` | The map that `destination` object lives on | Yes |
| `value` | Unused by this reward type | No (conventionally `0` in existing data, purely cosmetic) |

Fired via `ConversationController.applyScriptEffect` against **whichever
`Monster` is the current NPC context** of the conversation/script
(`ConversationController.setTravelDestination` →
`MonsterMovementController.beginTravel(monster, mapName, rewardID)`). This
means `setDestination` must be used inside a phrase reached through that
monster's own conversation (or an arrival/failure script run with
`runScriptForNpc`, which sets the NPC context explicitly) — it cannot target
an arbitrary, unrelated monster elsewhere on the map.

If no route exists at all from the monster's current position to the named
destination, the request fails **synchronously and silently** from the
script's point of view (no error surfaced to the player) — `travelDestination`
is left `null`, and `travelFailedScript` (§3.2) fires immediately if one is
set. Content authors are responsible for making sure a `setDestination`
target is actually reachable (a normal walkable route through `mapchange`
exits, per §2.2's reachability rules) — there is no data-time validation of
this today (a good candidate for an ATCS-side reachability check, since it
requires walking the same exit graph `GlobalPathFinder` does).

### 3.2 `setTravelFailedScript` — standing fallback behavior

```json
{
    "rewardType": "setTravelFailedScript",
    "rewardID": "traveltester_rect1_center"
}
```

| JSON field | Meaning | Required |
|---|---|---|
| `rewardType` | Literal `"setTravelFailedScript"` | Yes |
| `rewardID` | Phrase ID to run (via `runScriptForNpc`) whenever a journey fails for this monster | Yes |
| `mapName`, `value` | Unused | No |

Sets `Monster.travelFailedScript`. This is a **standing, per-NPC** setting,
not per-journey — unlike `travelDestination`, it is *not* cleared after firing,
so once set it applies to every future failed journey for this monster until
explicitly overwritten by another `setTravelFailedScript` reward. It fires in
exactly two situations:
1. `beginTravel` finds no route at all, at the moment `setDestination` is
   requested (immediate failure).
2. `handleBlockedTravelPath` gives up after `Constants.MONSTER_TRAVEL_MAX_BLOCKED_RETRIES`
   consecutive ticks of the local approach step failing to find a path (an
   obstruction — commonly the player standing in a doorway — or a layout
   change that closed the only route).

**Authoring caution — avoid recursive failure loops.** If the phrase named by
`travelFailedScript` itself issues a `setDestination` reward, and *that*
destination is also unreachable, `beginTravel` fails synchronously and
re-invokes the same `travelFailedScript` again immediately, with no guard
against infinite recursion. Always point a fallback script at a destination
that is unconditionally reachable (e.g. "go home").

Design rationale for why this lives on the monster rather than on
`TravelDestinationArea`: destinations are shared across every monster that
might target them, but how a specific NPC reacts to failing to reach one
(e.g. "go back home") is personal to that NPC, not the destination.

### 3.3 `isTravelling` — requirement (reply/script gating)

```json
{
    "requireType": "isTravelling",
    "requireID": "unused",
    "negate": false
}
```

A `Requirement.RequirementType` usable anywhere requirements are (reply
`requires`, replace-area gating, etc.). Evaluates
`monster.travelDestination != null` against the current NPC context — true
from the moment `setDestination` succeeds until the monster arrives (or the
journey is abandoned). `requireID`/`value` are not read for this type but the
JSON parser still requires the `requireID` field to be present (any
placeholder string works, following the pattern of the `random`/
`consumedBonemeals` types, whose `requireID` is likewise parsed-but-unused for
some of their variants — see `ConversationListParser`'s `requirementParser`).


## 4. Cross-cutting validation rules for content authors

These are not currently enforced by any data validator (`DEVELOPMENT_VALIDATEDATA`
only logs a subset — see below) but breaking them produces silent, hard-to-diagnose
runtime failures:

1. **`destination` object names must be unique per map.** Duplicates are
   logged as a `WARNING` at map-load time under `DEVELOPMENT_VALIDATEDATA`,
   but not rejected — the second one is simply unreachable via `getArea`/lookup
   collisions.
2. **`mapchange` object names must be unique per map.** Not explicitly
   validated; a duplicate silently corrupts `PredefinedMap`'s exit distance
   matrix and `GlobalPathFinder`'s node identity (`Node.equals`/`hashCode`
   key off `map.name + mapchange.id`).
3. **A `mapchange`'s `place` must exactly match some `mapchange` `name` on
   the map named by its `map` property.** No load-time validation exists for
   this at all; a typo here just makes that specific exit a dead end for
   cross-map routing (and, in the player-facing case, likely places the
   player at a wrong/missing spawn point too, since the exact same objects
   serve both purposes).
4. **A `setDestination` reward's `rewardID`/`mapName` pair must name an
   existing `destination` object.** No load-time validation; `beginTravel`
   simply finds nothing to travel to (loop over `destinationAreas` finds no
   match, `beginTravel` silently returns without ever assigning
   `travelDestination`).
5. **Reachability is a *routing-time* concern, not a *data* concern** — the
   schema itself never guarantees any destination is reachable from anywhere.
   A destination behind an unmarked `keyarea` (`monstersCanPass` left at its
   `false` default) is authored perfectly validly but will make every journey
   toward it fail once a monster's local approach reaches that gate.
6. **`monstersCanPass` defaults closed.** When authoring a `key` object that
   a travelling monster is expected to be able to cross, the property must be
   added explicitly — omitting it (or any pre-existing `key` object authored
   before this branch) blocks monster pathfinding through it exactly as a
   solid wall would.


## 5. Fields that are runtime state, not data

For completeness, so an ATCS integration doesn't accidentally try to expose
these as editable map/phrase data — they only ever exist on live `Monster`
instances and in savegames, never in source `.tmx`/`.json`:

- `Monster.travelDestination` / `Monster.travelPath` — current journey state.
- `Monster.travelBlockedRetries` — short-lived retry counter, not even
  persisted across saves.
- `GlobalPathFinder.GlobalPath` / `GlobalPathEntry` — the computed multi-leg
  route itself, recomputed fresh by `beginTravel` every time a journey
  starts.


## 6. Minimal worked example

The smallest possible single-map journey: a monster travels a few tiles to a
destination area, with a fallback if it can't get there.

**`.tmx`** (inside an `objectgroup`):
```xml
<object name="npc_home" type="destination" x="64" y="64" width="32" height="32">
 <properties><property name="script" value="npc_arrived_home"/></properties>
</object>
```

**Phraselist `.json`**, reached from the NPC's own conversation tree:
```json
{
    "id": "npc_start_journey",
    "message": "I'm heading home.",
    "rewards": [
        { "rewardType": "setTravelFailedScript", "rewardID": "npc_travel_failed" },
        { "rewardType": "setDestination", "rewardID": "npc_home", "mapName": "thismap" }
    ]
}
```

```json
{ "id": "npc_arrived_home", "message": "Ahh, home at last." }
```

```json
{
    "id": "npc_travel_failed",
    "rewards": [
        { "rewardType": "setDestination", "rewardID": "npc_home", "mapName": "thismap" }
    ]
}
```

(`npc_travel_failed` here just retries the same destination — a legitimate
pattern only because `npc_home` is a plain, always-reachable local area with
no gates in between. See §3.2's recursion caution before copying this pattern
onto a destination that might genuinely be unreachable.)

For a much larger worked reference covering multi-map routing, same-map
warps, and gated key areas, see `res/xml/traveltest1.tmx`,
`res/xml/traveltest2.tmx` and `res/raw/conversationlist_traveltest.json` — the
manual test bed built alongside this feature (see
[`travellingNPC.md`](travellingNPC.md#the-manual-test-bed-traveltest1--traveltest2)).

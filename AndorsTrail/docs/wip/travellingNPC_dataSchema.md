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

### 2.6 `Control` — a non-rendered layer weighting travel pathfinding

A reserved, purely technical tile layer (same treatment as `Walkable` — never
added to the rendered-layer set, so it's simply never drawn; no rendering
code is aware of it at all). Where `Walkable` only needs a tile's
presence/absence, `Control` needs to know *which* tile was placed, because
the weight is a **custom property on the tileset's own tiles**, authored in
Tiled's Tileset Editor — not per-map, per-placement data:

```xml
<tileset firstgid="11335" name="map_control_1" tilewidth="32" tileheight="32" tilecount="4" columns="4">
 <image source="../drawable/map_control_1.png" width="128" height="32"/>
 <tile id="1">
  <properties><property name="weight" type="int" value="3"/></properties>
 </tile>
 <tile id="2">
  <properties><property name="weight" type="int" value="6"/></properties>
 </tile>
</tileset>
...
<layer name="Control" width="30" height="30">
 <data encoding="base64" compression="zlib">...</data>
</layer>
```

| Element | Maps to | Required | Notes |
|---|---|---|---|
| A tileset's per-tile `weight` property (int) | `TMXTileSet.tileWeights` (local tile id → weight), read once per tileset by `TMXMapFileParser.readTMXTile` | No (a tile with no `weight` property placed on a `Control` layer resolves to `0`, logged under `DEVELOPMENT_VALIDATEDATA` as likely-unintentional) | Read directly from the tileset definition, the same place/workflow used for every other tile property in Tiled — no separate per-map authoring step. **Must be `>= 0`** — a negative value is clamped to `0` at load time with a warning (see the design note below for why). |
| The `Control` layer itself | `MapSection.pathWeight` (`int[][]`, always non-null — `0` everywhere on a map that doesn't author this layer at all) → `PathFinder`'s per-tile move cost (`10 + pathWeight` for whichever tile is being entered) | No — omit the layer entirely on any map that doesn't need path preferences | A `replace` object may also carry a `Control` property (alongside `Walkable`, `Base`, etc. — see §2.5) naming an alternate weight layer for its region, so a preferred route can open, close, or change weight based on a `Requirement`, exactly like `Walkable` already can. |

**Design decision: penalize off-path, don't discount on-path.** A tile's
weight can only ever *add* to the baseline move cost (10) — there's no way to
author a tile that's cheaper than default. This is deliberate: "prefer this
road" and "make the ground around this road relatively less attractive" are
the same authored *outcome*, but only the second is safe — a below-baseline
discount would make `PathFinder`'s `10 * Chebyshev distance` heuristic
under-predict the true cheapest cost, in an unbounded, author-controlled way,
risking a demonstrably non-shortest route. To make a path attractive,
**weight the terrain around it**, not the path tiles themselves (which
should simply be left unpainted on the `Control` layer, i.e. implicit `0`).
See `PathFinder.java`'s `heuristic()`/`moveCost` comments for the admissibility
reasoning in full.

**This is a genuine preference, not a hard constraint.** A travelling
monster will still cross weighted terrain when that's the only route, or
when it's genuinely shorter overall — weighting only ever breaks ties/near-
ties between otherwise-comparable routes in favor of the cheaper one, the
same bounded-tiebreak spirit as `PathFinder`'s existing diagonal/straight
tie-breaking (see `changelog.md`'s Phase 6 and R1 entries). A large enough
weight difference can still be worth a real detour, exactly as intended.


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

### 3.4 `MonsterType`-level defaults for `travelFailedScript` / `travelDestination`

Parsed by `MonsterTypeParser` (`resource/parsers/MonsterTypeParser.java`)
against field names declared in `JsonFieldNames.Monster` /
`JsonFieldNames.MonsterTravelDestination`, alongside a `monsterlist` entry's
other fields (`monsterlist_traveltest.json`'s `traveltester_defaults` entry
is a worked example of both together):

```json
{
    "id": "shopkeeper_bob",
    "...": "... (the usual MonsterType fields) ...",
    "travelFailedScript": "shopkeeper_bob_go_home",
    "travelDestination": { "mapName": "town", "areaID": "bobs_stall" }
}
```

These are the **default, standing** form of §3.2's `setTravelFailedScript`
reward and §3.1's `setDestination` reward respectively — declared once per
`MonsterType` instead of needing a script to set them on every monster of
that type individually. Both are optional and independent of each other.

| JSON field | Meaning | Applied |
|---|---|---|
| `travelFailedScript` | Same phrase-ID semantics as the `setTravelFailedScript` reward (§3.2) — including the same recursive-failure-loop caution. | Copied onto `Monster.travelFailedScript` once, when the `Monster` object is constructed (not re-applied later, so a `setTravelFailedScript` reward fired afterward always wins and stays in effect for the rest of that monster's life — this field only supplies the *starting* value). |
| `travelDestination` (nested object: `mapName`, `areaID`) | Same target semantics as the `setDestination` reward (§3.1) — including the same "must actually be reachable" caution, since there is no data-time validation of this any more than there is for the reward form. | Triggered once per spawn, from `MonsterSpawningController.spawnInArea` (every real spawn path — initial map population, respawns, and any other code that ultimately spawns via a `spawn` map object), via the same `beginTravel` machinery the reward uses. |

**Respawn behavior is deliberate, not an oversight.** Every time a fresh
`Monster` instance of this `MonsterType` is spawned — the map's initial
population, a respawn after death/despawn, `spawnAllInArea` refilling an
empty slot — `travelDestination` re-triggers from scratch and
`travelFailedScript` gets re-applied as the starting value. This is exactly
the intended behavior for the motivating case ("this shopkeeper always
starts by walking to their stall") — a respawned monster does **not**
"remember" wherever a previous instance of it last got to; that's unrelated,
per-instance `Monster` savegame state (§5), not part of `MonsterType` at all.

If both a `MonsterType` default and a script reward apply to the same
monster over its lifetime, the reward always wins for whatever it sets,
whenever it fires — there is no separate precedence flag to configure; it
falls out naturally from "the default only ever supplies an initial value,
a reward unconditionally overwrites the current value" for both fields.

### 3.5 `pathVarianceMultiplier` — per-NPC route "jitter"

```json
{
    "id": "shopkeeper_bob",
    "...": "... (the usual MonsterType fields) ...",
    "pathVarianceMultiplier": 0.5
}
```

| JSON field | Meaning | Applied |
|---|---|---|
| `pathVarianceMultiplier` (float, default `0`) | How strongly this `MonsterType`'s individual monsters' *local, on-screen* travel-approach pathing deviates from the single deterministic route every other monster of the same type would otherwise compute. Clamped to `[0, 1]` at parse time (`MonsterTypeParser`) — a content value outside that range can't accidentally request an unbounded detour. | `0` (the default, and the value for every pre-existing `MonsterType`) reproduces today's pathfinding **exactly** — no jitter term is ever added, not merely a small one. A non-zero value adds a small, per-tile, per-monster cost bump (`PathFinder.jitter()`) so two same-type monsters walking the same route don't produce byte-for-byte identical tile sequences. |

**Scope: local on-screen movement only, never the shared distance model.**
Unlike §2.6's control-layer weighting (a genuine terrain preference, deliberately
kept admissible — see that section), this is purely cosmetic individual
variation and is deliberately, knowingly allowed to make a monster's exact
tile-for-tile route not always the provably shortest one, within a small,
capped bound (`PathFinder.JITTER_MAX`). It **never** affects
`PredefinedMap.calculateDistanceMatrix()` or any of `GlobalPathFinder`'s own
searches — those compute the shared, monster-independent exit-to-exit
distance table that off-screen wall-clock interpolation (`travelPath
.predictedTime`/`cumulatedDistance`) depends on, and jitter touching that
model would desync it from what an on-screen monster's tick-by-tick movement
actually walks (the same class of bug the original Phase 0 work fixed — see
`changelog.md`). This scoping falls out for free from the existing `Monster
m`-nullness seam `PathFinder.findPathBetween` already uses for player-
avoidance — every caller that passes a real monster gets jitter, every caller
that passes `null` never does, with no separate flag to get wrong.

**Deterministic per monster, not per search.** The same monster re-searching
the exact same tile on a later tick (which happens constantly — the local A*
search reruns from scratch every tick) always gets the same jitter value for
that tile, so the walked route looks like one coherent, organic path rather
than flickering between alternatives tick to tick. This comes from hashing
each monster's own `pathVarianceSeed` (an int assigned once, at construction,
and persisted across saves — see §5 — so an individual's "walks slightly off
the beaten path" quirk reads as a stable trait, not something that reshuffles
on reload) together with the tile coordinates, not from a fresh random draw.

**Test rig:** `traveltester` (`monsterlist_traveltest.json`) has
`pathVarianceMultiplier: 1.0` (the maximum, for visibility during testing) —
the `variation_one`/`variation_two` destinations and their matching
"Go to variation one/two tile center" replies on `traveltester_start`
(`conversationlist_traveltest.json`) were pre-staged specifically for this
phase. Since this is set directly on the shared `traveltester` type, it also
now affects every other trip in its menu, including the Phase 6/R1 regression
routes (`rect1_corner`→`rect3_corner` etc.) — temporarily set it back to `0`
(or omit the field) to re-run those as an exact, jitter-free regression check.

### 3.6 `travelRestChance` / `travelRestDuration` — let a travelling NPC pause mid-journey

```json
{
    "id": "shopkeeper_bob",
    "...": "... (the usual MonsterType fields) ...",
    "travelRestChance": 15,
    "travelRestDuration": { "min": 1, "max": 3 }
}
```

| JSON field | Meaning | Applied |
|---|---|---|
| `travelRestChance` (int percent, default `0`) | Rolled once per tick (`Constants.roll100`), **only** while this monster is physically simulated on the map the player currently has loaded. `0` (the default) means "never rests" — behavior is unchanged from before this phase. | Checked at the very top of `MonsterMovementController.determineMonsterNextPosition`'s travel branch, before any pathfinding call. |
| `travelRestDuration` (`{min, max}`, default `{min:1, max:2}` if the field is omitted entirely) | How many ticks a triggered rest lasts, rolled via `Constants.rollValue` the moment a rest begins. | Stored in `Monster.travelPauseTicksRemaining` (not persisted — short-lived, like `travelBlockedRetries`), decremented one tick at a time. |

**Strictly on-screen — this is structural, not a policy choice.**
`determineMonsterNextPosition` (where the resting check lives) is only ever
reached from `MonsterMovementController.moveMonsters()`'s loop over
`currentMap.monsters` — i.e. the player's currently-loaded map.
`tryPlaceTravellingMonster`, the wall-clock interpolation path used for every
travelling monster the player *isn't* currently near, never calls
`determineMonsterNextPosition` at all, so an off-screen monster can never
roll a rest and always arrives with **zero** behavioral difference from
before this phase — confirmed on-device via a `showTravelDebug` log line
fired the first time resting logic runs, stating which loop/map it fired
from, not merely asserted from reading the code.

**ETA correction is mandatory, not optional polish.** The instant a rest
begins, `MonsterMovementController.correctTravelPathForPause` increases
`travelPath.predictedTime` by `ticksRested * 10` (the same
distance-per-move convention used everywhere else — see `getMillisecondsPerMove`,
`PathFinder`'s `moveCost`), and increases `cumulatedDistance` on the
*current* `GlobalPathEntry` and every later one (never an already-completed
leg, never `distance` itself — each leg's own true physical length never
changes) by the same amount. Skipping this would desync the off-screen
wall-clock model from what actually happened on-screen the moment this
monster later leaves the player's current map mid-journey — the same class
of bug Phase 0 was originally about (see `changelog.md`). No separate
correction is needed for the hand-off itself: `enterTravellingPool` already
recalibrates `travelPath.startTime` from the monster's live physical
position via a fresh local search, which has no independent dependency on
how much time was spent resting to get there — it's already covered by the
`cumulatedDistance` correction above. (R6 generalized this mechanism —
`Monster.travelPauseReason`/`travelPauseTicksRemaining`,
`MonsterMovementController.handleTravelPause`/`beginTravelPause` — so a
future, unrelated pause reason could reuse it without duplicating this
logic; see `travellingNPC.md`'s "Future extension point" note. Resting is
still the only reason that exists today.)

**Cooldown between rests, not authored.** `Constants
.MONSTER_TRAVEL_REST_COOLDOWN_TICKS` (currently `10`) is a hardcoded, global
minimum number of ticks a monster must spend actually travelling before it's
eligible to roll another rest, started the instant a rest ends
(`Monster.travelRestCooldownRemaining`, ephemeral like
`travelPauseTicksRemaining` — not authored per-`MonsterType`, not persisted).
Added after on-device testing showed a monster could otherwise rest, take a
single step, and immediately roll another rest — technically correct per the
data but visually unnatural. One tick's local travel-approach step covers at
most one tile (`PathFinder`'s move cost is 10 distance units per move, the
same tick~move~tile equivalence `correctTravelPathForPause` above already
relies on), so a flat tick count doubles as a tile-count spacing minimum
without needing to track actually-walked distance separately. Deliberately
kept separate from the generalized `travelPauseReason` hook above — see
`travellingNPC.md`'s "Future extension point" note for why a rest-specific
cooldown shouldn't constrain some future, unrelated pause reason.


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
- `Monster.pathVarianceSeed` — random per-instance seed for §3.5's route
  jitter, assigned once when the `Monster` is constructed (`Constants.rnd
  .nextInt()`), not authored. Persisted across saves (unlike
  `travelBlockedRetries`) so an individual's jitter quirk stays stable across
  reloads.
- `Monster.travelPauseReason` / `Monster.travelPauseTicksRemaining` — §3.6's
  in-progress pause (today, only ever `resting`) and its countdown,
  generalized in R6 for a future pause reason to reuse. Short-lived like
  `travelBlockedRetries`, not persisted across saves.
- `Monster.travelRestCooldownRemaining` — §3.6's post-rest cooldown
  countdown, started from `Constants.MONSTER_TRAVEL_REST_COOLDOWN_TICKS`
  (a hardcoded global, not authorable per-`MonsterType`). Also not persisted.


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

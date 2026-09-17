# Save / Load

## Overview

Andor's Trail persists the entire game state — player, inventory, quest
progress, map state, world time, UI selections — into a single custom binary
file per save slot, written and read with plain `java.io.DataOutputStream` /
`DataInputStream`. There is no JSON, XML, or Android `Parcelable`/`Bundle`
involved; the "parcel" terminology used throughout the codebase (`writeToParcel`
/ `readFromParcel`) is a naming convention only, not a use of Android's
`Parcel` class. The format is versioned by a single integer
(`AndorsTrailApplication.CURRENT_VERSION`, tied to the app's build number)
written at the front of every file, and reading is entirely backward-compatible:
every serializable class receives the save's `fileversion` and internally
branches (`if (fileversion >= N) ... else ...`) to know which fields are
present and how to interpret them. There is no forward migration on write —
old saves are upgraded to in-memory model objects on load, not rewritten to a
newer file layout, except where the game explicitly patches game-content
correctness issues (`LegacySavegamesContentAdaptations`). The `savegames/`
package additionally contains a family of `LegacySavegameFormatReaderFor*`
helper classes that hold the actual "old format" decoding logic (or, in the
case of `LegacySavegameFormatReaderForPlayer`, in-memory data-correction
patches) that would otherwise clutter the live model classes' `readFromParcel`
constructors.

## Key classes/files

| File | Role |
|---|---|
| [Savegames.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/savegames/Savegames.java) | The entry point for all save/load I/O: writing/reading save files, slot file management, the `FileHeader` (save metadata) and `CheatDetection` parcel formats, and the quicksave-vs-cheat-check bookkeeping. |
| [LegacySavegamesContentAdaptations.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/savegames/LegacySavegamesContentAdaptations.java) | One-time post-load content fix-up (`adaptToNewContentForVersion45`) applied to saves older than fileversion 45: resets a specific monster spawn area and forces regeneration of cached world-map HTML files. |
| [LegacySavegameFormatReaderForItemContainer.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/savegames/LegacySavegameFormatReaderForItemContainer.java) | Post-load correction that refunds gold for items that were stacked beyond quantity 1 before item-upgrade stacking rules existed (`refundUpgradedItems`). |
| [LegacySavegameFormatReaderForMap.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/savegames/LegacySavegameFormatReaderForMap.java) | `getMapnameFromIndex(int)` — a hard-coded index-to-map-name table used to read pre-v0.7.0 (fileversion < 35) saves, which stored maps positionally instead of by name. |
| [LegacySavegameFormatReaderForMonster.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/savegames/LegacySavegameFormatReaderForMonster.java) | `newFromParcel_pre_v25` — reconstructs a `Monster` from the pre-fileversion-25 monster parcel layout. |
| [LegacySavegameFormatReaderForPlayer.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/savegames/LegacySavegameFormatReaderForPlayer.java) | The largest legacy-handling class: reads the pre-fileversion-13 quest-progress format (`readQuestProgressPreV13`, mapping old boolean quest flags to the current `QuestProgress` model), reads the pre-v0.3.4 combat-traits parcel layout (`readCombatTraitsPreV034`, values discarded), and applies a sequence of numbered game-balance/data patches keyed to fileversion thresholds (`upgradeSavegame`). |
| [WorldContext.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/context/WorldContext.java) | Holds `maps` (`MapCollection`) and `model` (`ModelContainer`) — the two top-level objects `Savegames` serializes/deserializes — and `getChecksum()`, used for save-tamper detection. |
| [ControllerContext.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/context/ControllerContext.java) | Passed into load so legacy adaptations and post-load hooks (`onWorldLoaded`) can call back into live controllers (`actorStatsController`, `mapController`, `movementController`, `gameRoundController`, `monsterSpawnController`). |
| [ModelContainer.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/model/ModelContainer.java) | Serializes/deserializes `player`, current map name, `uiSelections`, `statistics`, `worldData` — the per-game-session state. |
| [Constants.java](../app/src/main/java/com/gpl/rpg/AndorsTrail/controller/Constants.java) | Defines save file naming: `FILENAME_SAVEGAME_QUICKSAVE = "savegame"`, `FILENAME_SAVEGAME_DIRECTORY = "andors-trail"`, `FILENAME_SAVEGAME_FILENAME_PREFIX = "savegame"`, and the obfuscated `CHEAT_DETECTION_FOLDER` name. |

## How it works

### File format

Every save is one binary stream, written in this exact order by
`Savegames.saveWorld(WorldContext, OutputStream, String displayInfo)`:

1. **`FileHeader`** (`FileHeader.writeToParcel`): `int` fileversion
   (`AndorsTrailApplication.CURRENT_VERSION`), `UTF` player name, `UTF`
   display info string, `int` icon ID, `boolean` isDead, `boolean`
   hasUnlimitedSaves, `UTF` player id, `long` savedVersion, `boolean`
   isAlteredSavegame.
2. **`world.maps.writeToParcel(dest, world)`** — all map state (`MapCollection`).
3. **`world.model.writeToParcel(dest)`** — `player`, current map name,
   `uiSelections`, `statistics`, `worldData` (`ModelContainer`).

Reading (`Savegames.loadWorld(Resources, WorldContext, ControllerContext,
Context, InputStream, FileHeader)`) mirrors this: it re-reads the
`FileHeader` from the stream (the caller-supplied `fh` from `quickload` is
only used for its `skipIcon` flag and pre-flight metadata), rejects the file
outright if `header.fileversion > AndorsTrailApplication.CURRENT_VERSION`
(`LoadSavegameResult.savegameIsFromAFutureVersion`), then reads maps and
constructs a new `ModelContainer` from the stream, both passed the same
`header.fileversion` so every nested object can apply its own
version-conditional parsing.

There is no game-content data in the save file itself beyond references
(item type IDs, map names, quest IDs, monster type IDs): static game data
(item types, monster types, maps, quests, skills) lives in resources and is
looked up by ID/name during deserialization via the `WorldContext` passed
into every `readFromParcel`/`newFromParcel` call.

### Versioning

- `AndorsTrailApplication.CURRENT_VERSION` is normally the app's
  `BuildConfig.VERSION_CODE`, but is forced to the sentinel
  `DEVELOPMENT_INCOMPATIBLE_SAVEGAME_VERSION = 999` when
  `DEVELOPMENT_INCOMPATIBLE_SAVEGAMES` is set, so in-development saves are
  never confused with a real released version and are visibly flagged in
  `FileHeader.describe()` with a `"(D) "` prefix.
- Every versioned type constructor takes `fileversion` and branches on it
  (`if (fileversion >= N)`), so there is one unbroken read path that handles
  every historical file layout back to the earliest versions rather than a
  separate upgrade pass. `FileHeader`'s own constructor is a good example of
  the pattern in miniature: it conditionally reads `playerName`/`displayInfo`
  only if `fileversion >= 14`, icon only if `>= 43`, `isDead`/
  `hasUnlimitedSaves`/`playerId`/`savedVersion` only if `>= 49`, and
  `isAlteredSavegame` only if `>= 81`, defaulting each field when the file
  predates it.
- Special case: fileversion `11` is remapped to `5` in `FileHeader`'s
  constructor with the comment "Fileversion 5 had no version identifier, but
  the first byte was 11" — an ambiguity in the very earliest format that is
  resolved by a hard-coded special case rather than a general rule.
- A checksum (`world.getChecksum()`, from `WorldContext.getChecksum()` via
  `ChecksumBuilder`, hashing `model` and `maps`) is written into
  `statistics` on save and re-verified on load
  (`Savegames.checkChecksum`) — but only for `fileversion >= 81`; older
  saves have no checksum and cannot be checked. A mismatch calls
  `world.model.statistics.markAsAlteredSavegame()` rather than rejecting the
  load.

### Legacy format migration

Legacy handling happens in two different ways depending on the class:

- **Inline, version-gated parsing** inside each model class's own
  `readFromParcel`/constructor (the majority of the system — not shown here
  since it lives outside the `savegames/` package, but it's what
  `fileversion` is threaded through everywhere for).
- **Extracted into `LegacySavegameFormatReaderFor*` helper classes** when the
  old format's structure is substantially different from the current one, or
  when the fix-up is really a data correction rather than parsing:
  - `LegacySavegameFormatReaderForMap.getMapnameFromIndex(int)` — pre-v0.7.0
    (fileversion < 35) saves stored maps in a fixed positional order instead
    of by name; this hard-coded 0–282 index table translates the old
    positional index back to the current map name string.
  - `LegacySavegameFormatReaderForMonster.newFromParcel_pre_v25` —
    reconstructs a `Monster` using the pre-fileversion-25 field layout
    (position, `ap.current`, `health.current`, and — only if `fileversion
    >= 12` — an aggressive-forcing boolean).
  - `LegacySavegameFormatReaderForPlayer.readQuestProgressPreV13` — pre-v13
    saves stored quest state as a set of opaque boolean flag keys (e.g.
    `"qmikhail_bread_complete"`); this method reads that old key set and
    translates each recognized key into a `QuestProgress(questID, progress)`
    call against the current quest-ID/progress-value model.
  - `LegacySavegameFormatReaderForPlayer.readCombatTraitsPreV034` — reads
    (and discards) the pre-v0.3.4 combat-traits parcel layout, which existed
    as its own struct before combat traits were folded into the player
    model directly; note the parsed values are read only to advance the
    stream correctly, then thrown away (all assignments are commented out).
  - `LegacySavegameFormatReaderForPlayer.upgradeSavegame(player, world,
    controllers, fileversion)` — a sequence of independent `if (fileversion
    <= N)` data patches applied after the player is otherwise fully loaded:
    e.g. `<= 12` grants a one-time +5 max/current HP and lowers
    `useItemCost`; `<= 21` recomputes `availableSkillIncreases` from scratch
    and retroactively grants a missing quest-progress entry for two
    quests that could desync; `<= 27` re-derives certain actor conditions
    from currently-equipped items (`correctActorConditionsFromItemsPre0611b1`)
    to fix a bug where item-granted conditions could persist after
    unequipping; `<= 30` resets `attackCost` to the default; `<= 37` and
    `<= 40` fix specific quest-state/monster-spawn inconsistencies.
  - `LegacySavegameFormatReaderForItemContainer.refundUpgradedItems`
    (`Inventory` and `Loot` overloads) — refunds gold for stacked items that
    predate a rule change forbidding stacking of certain (non-quest,
    non-extraordinary/legendary, market-priced-above-fixed) item types, by
    collapsing any stack `>= 2` back to `1` and crediting the difference in
    market value as gold.
  - `LegacySavegamesContentAdaptations.adaptToNewContentForVersion45` — run
    once from `Savegames.loadWorld` when `header.fileversion < 45`: looks up
    the `"fields5"` map, finds a specific monster
    (`"feygard_bridgeguard"`) by monster-type ID, resets its spawn area
    (`MonsterSpawnArea.resetForNewGame()`) and force-spawns a different
    named spawn area (`"guynmart_robber1"`) via
    `monsterSpawnController.spawnAllInArea(...)`; then, separately, forces
    regeneration of every cached world-map HTML segment file
    (`WorldMapController.updateWorldMapSegment`) to pick up a template
    change to UTF-8 encoding.

### Save slots, quicksave, and cheat detection

- `Savegames.SLOT_QUICKSAVE = 0` is a reserved slot written to a fixed
  filename (`Constants.FILENAME_SAVEGAME_QUICKSAVE = "savegame"`, via
  `Context.openFileOutput`/`openFileInput` — Android's private app-file
  storage). Other slots are numbered files (`getSlotFileName(slot)` =
  `"savegame" + slot`) inside a dedicated directory
  (`Constants.FILENAME_SAVEGAME_DIRECTORY = "andors-trail"`, resolved via
  `AndroidStorage.getStorageDirectory`). `getUsedSavegameSlots(Context)`
  scans that directory with a regex (`savegame(\d+)`) to enumerate existing
  numbered slots.
- `saveWorld(...)` writes to an in-memory `ByteArrayOutputStream` first and
  only flushes those bytes to the real file afterward, specifically so a
  serialization error can't corrupt/truncate an existing save file.
- If the player does not have unlimited saves
  (`!world.model.statistics.hasUnlimitedSaves()`) and the save target is a
  numbered slot (not quicksave), `world.model.player.savedVersion` is
  incremented on every save — a strictly-increasing counter used purely for
  cheat detection, unrelated to the file-format `fileversion`.
- **Cheat detection**: on a non-quicksave, non-unlimited-saves save, a
  `CheatDetection` record (`fileversion` + `savedVersion`) is written to two
  places keyed by the player's `id`: a file named after the player ID inside
  the obfuscated `Constants.CHEAT_DETECTION_FOLDER` directory, and an
  identically-named file in the app's private storage
  (`writeCheatCheck`/`androidContext.openFileOutput(playerId, ...)`) — i.e.
  two copies in two locations, presumably so restoring/tampering with only
  one is insufficient. `loadWorld` (the slot-based overload) calls
  `triedToCheat(...)` before loading a numbered slot: it reads both
  cheat-check copies, takes the higher `savedVersion` of the two (or the
  sentinel `DENY_LOADING_BECAUSE_GAME_IS_CURRENTLY_PLAYED = -1` if either
  says so), and refuses to load
  (`LoadSavegameResult.cheatingDetected`) if the slot file's own
  `savedVersion` is lower than that — i.e. loading an older/stale copy of a
  save whose "real" progress has since moved on is treated as cheating.
  Quicksave (slot 0) is exempt from this check entirely.
- After successfully loading a **non-quicksave** slot (and only when the
  player doesn't have unlimited saves), `loadWorld` immediately re-saves the
  loaded state into the quicksave slot, deletes the original numbered slot
  file, and writes a cheat-check entry with
  `DENY_LOADING_BECAUSE_GAME_IS_CURRENTLY_PLAYED` for that player ID — i.e.
  loading a numbered save "consumes" it and forces all further play through
  quicksave until the player explicitly saves to a slot again, and
  simultaneously locks that player ID against reloading the just-consumed
  save while it's "currently being played."
- `hasUnlimitedSaves()` (an option, presumably a difficulty/permadeath
  toggle — see `GameStatistics`) bypasses all of the above: no
  `savedVersion` increment, no cheat-check writing, no slot-consuming
  behavior, and `triedToCheat` is skipped outright.
- A periodic backup: every quicksave write also opportunistically writes a
  copy of the raw save bytes to `<CHEAT_DETECTION_FOLDER>/<playerId>X`
  (`writeBackup`), but throttled to at most once per 120 real-world seconds
  (`SystemClock.uptimeMillis() > lastBackup + 120000`, a `static` field so
  it's process-lifetime, not persisted).
- `quickload(Context, int slot)` reads only the `FileHeader` (not the full
  save) from a slot — used by the load/save-slot UI to list save metadata
  (player name, display info, icon, dead/altered flags) cheaply without
  deserializing the whole game state.

## How other systems hook in

- **`MainActivity.onPause()`** calls `controllers.gameRoundController.pause()`
  then `save(Savegames.SLOT_QUICKSAVE)` — i.e. the game auto-saves to the
  quicksave slot every time the activity is paused (backgrounded, screen
  off, etc.), not on a timer. See
  [game-loop.md](game-loop.md) for what `gameRoundController.pause()` stops.
- **`LoadingActivity`** (`continueWorld()`) is the load entry point used when
  starting/resuming a game; it calls `Savegames.loadWorld(world, controllers,
  ctx, loadFromSlot)` on a background-loading screen and reports
  `onSceneLoadFailed(LoadSavegameResult)` back to its caller for the
  `savegameIsFromAFutureVersion` / `cheatingDetected` cases.
- **`LoadSaveActivity`** is the save/load slot-management UI: it lists used
  slots via `Savegames.getUsedSavegameSlots`, reads per-slot metadata via
  `Savegames.quickload`, and resolves slot files via
  `Savegames.getSlotFile`/`getSlotFileName` (e.g. for delete/overwrite
  confirmation and renaming flows).
- **`StartScreenActivity_MainMenu`** checks `Savegames.quickload(...,
  SLOT_QUICKSAVE)` to decide whether a "Continue" option should be offered
  on the main menu, and routes into `continueGame(false, SLOT_QUICKSAVE, null)`.
- On successful load, `Savegames.onWorldLoaded(...)` calls back into
  `ControllerContext`: `actorStatsController.recalculatePlayerStats`,
  `mapController.resetMapsNotRecentlyVisited`,
  `movementController.prepareMapAsCurrentMap`, and
  `gameRoundController.resetRoundTimers()` — the last of which is also
  referenced in [game-loop.md](game-loop.md) as one of the standard callers
  that resets tick/round countdowns without firing them, so a just-loaded
  game doesn't immediately fire an already-elapsed round.
- `WorldMapController.populateWorldMap(...)` is called unconditionally right
  after deserialization (before any legacy content adaptation), rebuilding
  transient world-map UI state that isn't itself part of the save file.

## Gotchas / non-obvious behavior worth knowing

- **`writeToParcel`/`readFromParcel` naming is misleading**: nothing here
  uses Android's `Parcelable`/`Parcel` API. It's hand-rolled
  `DataOutputStream`/`DataInputStream` binary I/O; the naming is just a
  borrowed convention.
- **Loading a numbered slot deletes it.** As described above, a successful
  non-quicksave load re-saves into quicksave and deletes the original slot
  file (when `hasUnlimitedSaves()` is false). A contributor who expects slot
  saves to behave like independent, reusable checkpoints will be surprised
  that loading one destroys it.
- **`fileversion` (save format version) and `savedVersion` (cheat-detection
  counter) are two unrelated numbers** that both live in `FileHeader`/the
  player model and are easy to confuse. `fileversion` gates parsing logic;
  `savedVersion` only gates the cheat-detection comparison and is unrelated
  to what fields exist in the file.
- **The future-version check is a hard rejection, not a warning**: if
  `header.fileversion > AndorsTrailApplication.CURRENT_VERSION`, load fails
  outright (`savegameIsFromAFutureVersion`) — relevant when testing against
  builds using `DEVELOPMENT_INCOMPATIBLE_SAVEGAME_VERSION = 999`, since any
  such dev save becomes unloadable by a normal build by design.
- **Checksum verification only exists for fileversion >= 81**; pre-81 saves
  have no tamper detection at all via this mechanism, and a mismatch on
  newer saves doesn't block loading — it only flags
  `statistics.markAsAlteredSavegame()` for later (e.g. UI/achievement
  purposes), so "altered" saves still load successfully.
- **`readCombatTraitsPreV034` parses fields purely to consume bytes** — every
  value it reads is discarded (assignments are commented out in the source).
  This is a reminder that in this binary format, every legacy reader must
  consume exactly as many bytes as the old writer produced, even when the
  data itself is no longer meaningful, or every subsequent read in the
  stream will be misaligned.
- **`LegacySavegameFormatReaderForMap`'s index table is a fixed historical
  snapshot** (283 entries, index 0–282) of map ordering as it existed before
  fileversion 35; it must never be reordered or renumbered even if the
  current map list changes, since it only exists to decode old files.
- **The periodic backup file (`<playerId>X`) and the two cheat-check files
  are stored using the player's `id` (and an obfuscated folder name,
  `CHEAT_DETECTION_FOLDER = "dEAGyGE3YojqXjI3x4x7"`) rather than the slot
  number** — they are per-player-character, not per-slot, and persist
  independently of which numbered slots currently exist.

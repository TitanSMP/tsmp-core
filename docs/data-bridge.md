# TitanSMP Data Bridge — Phase 1

## Scope

Phase 1 synchronizes only Player State + Inventory/Armor/Offhand + Health/Food/Experience. The selected state is a UUID-keyed player snapshot. The EnderChest remains wholly owned by `modules/ender-chest` and is not represented in the player-state API or table.

The implementation is native TitanSMP code based on the source audit in [data-bridge-audit.md](data-bridge-audit.md). It does not use the archive as a runtime dependency and does not copy code from it.

## Module boundaries

- `api` contains `PlayerState`, opaque `SerializedPlayerItem`, `PlayerDataService`, persistence exceptions, and the `PlayerStateUpdated` event. It has no Paper, Bukkit, Velocity, JDBC, MariaDB, Lettuce, or Redis types.
- `modules/data-bridge` contains the asynchronous service facade, JDBC repository, binary payload codec, and a small monotonic revision tracker. It receives the existing shared database pool from `core` and creates no pool or Redis client.
- `core` owns the Flyway migration and existing database/event infrastructure.
- `paper` snapshots/applies live player values on the Paper thread and owns join gating, quit/shutdown lifecycle, and Paper ItemStack serialization.
- `velocity` does not yet coordinate state transfer.

The Data Bridge implementation lives in one Gradle feature project, with Paper integration in the existing root `paper` project. This matches the working EnderChest integration pattern and avoids a nested Paper subproject dependency cycle.

## Player-state model

`PlayerState` contains only:

- player UUID (identity; never username or server name)
- 36 main-inventory slots (0–35)
- four armor slots in API order: helmet, chestplate, leggings, boots
- one offhand slot
- current health
- food level (validated 0–20)
- XP-bar progress (validated 0–1), level, and total experience
- optimistic persistence revision

Empty slots are null. Cursor items, temporary containers, open GUIs, EnderChest, saturation, max-health attributes, and all Phase 2 fields are excluded. If MariaDB has no state row, the local inventory and vitals are cleared to vanilla defaults (empty inventory, full food and default max health, level/XP zero); old server-local player files are not imported. The default snapshot is written on first quit/shutdown.

## Item serialization

The shared Paper-only `PaperItemStackCodec` uses Paper's native `ItemStack.serializeAsBytes` / `deserializeBytes` API. It is used by both Data Bridge and EnderChest, preserving Paper-supported ItemMeta and data such as enchantments, lore, custom model data, attributes, potion/skull metadata, and PDC without a hand-maintained field list. Core stores opaque bytes in a versioned, bounded envelope and never interprets Bukkit objects. A Paper round-trip test covers display name, lore, enchantment, flags, custom model data, and PDC.

The ZIP archive is not modified, unpacked, included, or used as a dependency. The prior audit records its GPL-3.0 license; this implementation does not require upstream code or attribution.

## MariaDB persistence and atomicity

Flyway `V3__create_player_state.sql` creates one `player_state` row per UUID with `state_payload LONGBLOB`, `revision`, and `updated_at`. The row references `players.uuid`; it has no server-specific key and contains no EnderChest, economy, or statistics data. Storing the selected snapshot in one binary payload means all Phase 1 fields are replaced together.

The payload has a magic/version header, explicit slot counts, length-prefixed Paper item bytes, scalar values, a 64 MiB upper bound, and strict validation on decode. Database calls run on a dedicated worker executor; Paper object capture/application stays on the Paper main thread.

For a first save, a conditional `INSERT ... SELECT` requires the online UUID to belong to the current server. Later saves use one `UPDATE` with `WHERE revision = expectedRevision` and the same server ownership condition. MariaDB's single-row update is atomic. A duplicate initial insert or mismatched revision is rejected; no merge is attempted. No distributed lock is used.

## Join and save lifecycle

1. The existing identity listener creates/updates UUID identity and claims online presence on this server.
2. Only after that succeeds does it ask the Data Bridge to load the persisted state asynchronously.
3. While loading, Paper blocks movement, inventory, item interactions, pickup/drop, swaps, and damage to prevent use of a local stale snapshot.
4. The main thread applies the state. An absent row means a first-time player; current Paper vanilla defaults become the initial baseline. Load/decode/apply failure is logged and the player is kicked so they cannot continue with stale local state.
5. On quit, Paper snapshots once. If a saved baseline is unchanged, there is no DB write; a new row is still created for a first-time player. On successful persistence the identity listener marks presence offline and publishes the existing disconnect event. A failed save is logged and presence remains online, rather than asserting a successful disconnect.
6. On normal plugin shutdown, all online snapshots are captured on the main thread, saves are drained, then shared infrastructure closes. A failed shutdown save is logged.

Inventory events do not cause database writes. Dirty detection is a lifecycle snapshot comparison against the loaded baseline. There is no periodic autosave. After a crash, the latest committed DB revision remains intact, but in-memory changes since the last successful quit/shutdown save can be lost; without a transfer checkpoint the loss window is the time since the last successful lifecycle save.

## Revisions, events, and concurrency

Successful saves increment `revision`. A stale writer cannot overwrite a newer revision. `PlayerStateUpdated` carries only UUID and committed revision and is published after persistence succeeds. It is a notification, not a state transfer or source of truth. Publish failure is logged and does not undo the database commit. Redis Pub/Sub can lose, duplicate, or reorder messages.

Consumers that project these notifications must track the highest revision per UUID and ignore equal or lower revisions. `PlayerStateRevisionTracker` implements this monotonic rule. Consumers needing the actual state must load it from MariaDB after observing a newer revision.

An old backend save is accepted only while `players.online = TRUE` and `current_server` still matches that backend. Thus a backend that has lost current presence cannot write after the destination claims presence. A concurrent save with the same revision also loses through the conditional update. Conflicts and ownership errors are logged; they are not silently retried with stale contents.

## Server transfers and limits

No Velocity transfer event or source/destination handoff is implemented. Current saves occur at quit and shutdown. The presence ownership check blocks late source writes after a destination has claimed the player, but without a proxy-coordinated save-before-connect or destination wait/retry, a fast transfer can load the last committed state before the source quit save completes. No implementation can recover uncommitted source memory after a crash. Before relying on seamless inventory transfer, add a transfer protocol that freezes the source, commits the state with its revision, then permits destination load; durable session/fencing ownership should guard that protocol. Redis may wake or notify participants but must not authorize a state transition.

Other known limitations: no periodic checkpoints; no state migration from vanilla player files; Paper serialization compatibility follows the Paper/Minecraft version that reads the bytes; event delivery is best-effort; failed quit persistence deliberately leaves online presence until recovery/manual reconciliation; concurrent or stale snapshots are rejected rather than merged.

## Explicitly excluded from Phase 1

Potion Effects, Advancements, Statistics, Maps, PDC player state, Location, Gamemode, Flight, Companions, separate Gamemode Inventories, EnderChest, Economy, Moderation, Chat, Friends, Clans, Discord, and Web API behavior are not synchronized here. ItemStack PDC remains included as part of the native item bytes; this does not add general player PDC synchronization.

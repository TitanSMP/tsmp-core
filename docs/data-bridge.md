# TitanSMP Data Bridge — Phase 1

## Scope

Phase 1 synchronizes only Player State + Inventory/Armor/Offhand + Health/Food/Experience. The selected state is a UUID-keyed player snapshot. The EnderChest remains wholly owned by `modules/ender-chest` and is not represented in the player-state API or table.

The implementation is native TitanSMP code based on the source audit in [data-bridge-audit.md](data-bridge-audit.md). It does not use the archive as a runtime dependency and does not copy code from it.

## Module boundaries

- `api` contains `PlayerState`, opaque `SerializedPlayerItem`, `PlayerDataService`, persistence exceptions, and the `PlayerStateUpdated` event. It has no Paper, Bukkit, Velocity, JDBC, MariaDB, Lettuce, or Redis types.
- `modules/data-bridge` contains the asynchronous service facade, JDBC repository, binary payload codec, and a small monotonic revision tracker. It receives the existing shared database pool from `core` and creates no pool or Redis client.
- `core` owns the Flyway migration and existing database/event infrastructure.
- `paper` snapshots/applies live player values on the Paper thread and owns join gating, quit/shutdown lifecycle, and Paper ItemStack serialization.
- `velocity` coordinates backend transfers through the Redis event bus. Its process starts Redis only; it does not connect to MariaDB or run migrations.

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
3. While loading or preparing a transfer, Paper blocks movement, inventory, item interactions, pickup/drop, swaps, damage, health regeneration, food changes, experience gain, and item consumption to prevent use of a stale snapshot.
4. The main thread applies the state. An absent row means a first-time player; current Paper vanilla defaults become the initial baseline. Load/decode/apply failure is logged and the player is kicked so they cannot continue with stale local state.
5. On quit, Paper snapshots once. If a saved baseline is unchanged, there is no DB write; a new row is still created for a first-time player. On successful persistence the identity listener marks presence offline and publishes the existing disconnect event. A failed save is logged and presence remains online, rather than asserting a successful disconnect.
6. On normal plugin shutdown, all online snapshots are captured on the main thread, saves are drained, then shared infrastructure closes. A failed shutdown save is logged.

Inventory events do not cause database writes. Dirty detection is a lifecycle snapshot comparison against the loaded baseline. There is no periodic autosave. After a crash, the latest committed DB revision remains intact, but in-memory changes since the last successful quit/shutdown save can be lost; without a transfer checkpoint the loss window is the time since the last successful lifecycle save.

## Revisions, events, and concurrency

Successful saves increment `revision`. A stale writer cannot overwrite a newer revision. `PlayerStateUpdated` carries only UUID and committed revision and is published after persistence succeeds. It is a notification, not a state transfer or source of truth. Publish failure is logged and does not undo the database commit. Redis Pub/Sub can lose, duplicate, or reorder messages.

Consumers that project these notifications must track the highest revision per UUID and ignore equal or lower revisions. `PlayerStateRevisionTracker` implements this monotonic rule. Consumers needing the actual state must load it from MariaDB after observing a newer revision.

An old backend save is accepted only while `players.online = TRUE` and `current_server` still matches that backend. Thus a backend that has lost current presence cannot write after the destination claims presence. A concurrent save with the same revision also loses through the conditional update. Conflicts and ownership errors are logged; they are not silently retried with stale contents.

## Server transfers (Phase 1.5)

Velocity intercepts server-to-server `ServerPreConnectEvent`s. Initial joins and same-server connections use the normal Paper load path. For backend transfers, Velocity publishes a unique `PlayerTransferPrepare` event before it allows the connection. Every Paper server observes the intent; the target caches it and acknowledges with `PlayerTransferTargetPrepared`. The source locks the player, snapshots the existing Phase 1 fields on the Paper thread, and saves through the same MariaDB revision-CAS service. Only a committed save can produce `PlayerTransferReady`. Velocity waits for both the source's committed revision and the target's cached-intent acknowledgement before it permits the requested connection. Any source save failure, missing source session, publish failure, or preparation timeout denies the request and publishes `PlayerTransferFailed` so the source can release its lock.

The target uses the same `loadState` path as a normal join. When it has a matching Prepare and Ready for the exact transfer ID, it loads from MariaDB and requires the loaded revision to be at least the source-confirmed revision before applying the snapshot. The player remains join-gated until apply completes. A stale, duplicate, or mismatched transfer event cannot authorize a different in-flight transfer. On successful apply, the target publishes `PlayerTransferCompleted`; Velocity then drops the in-memory transfer projection. Failed and completed events are notifications/protocol messages, not persistent records. No transfer table is created.

The transfer events are `PlayerTransferPrepare`, `PlayerTransferTargetPrepared`, `PlayerTransferReady`, `PlayerTransferFailed`, and `PlayerTransferCompleted`. The timeout is configured with `data-bridge.transfer.timeout-ms` (default `5000`, accepted range `250..60000`) or `TITANSMP_TRANSFER_TIMEOUT_MS`. Backend IDs in Velocity's server list must match each Paper server's `server.id`, because they are carried in the transfer events. The proxy starts the Redis event runtime only and does not require database credentials. Redis is coordination only; MariaDB remains the player-state source of truth. Transfer state exists in memory for the short lifecycle and is not a distributed lock service.

Presence remains one UUID row with one `current_server`. The target claims presence as part of the existing identity join path. A late source quit clears presence only if the source still owns that row, so it cannot erase target ownership. The source transfer snapshot is already committed before the target connection is permitted, and the source quit hook sees the new baseline revision and does not overwrite it with an older revision.

If the source save succeeds but the target cannot be reached, Velocity denies/fails the attempted connection or the normal connection attempt fails while the persisted revision remains available. A subsequent normal join reads MariaDB. If the target joins but cannot load or apply the committed revision, it is kicked and reports failure; the committed state remains available for a later join. A crash before the transfer save commits leaves the previous committed revision intact; changes since the last successful save cannot be recovered. Redis Pub/Sub itself is best-effort, so loss of a coordination event fails closed by timeout rather than authorizing a transfer.

The in-memory protocol prevents duplicate/replayed event IDs within a process lifetime and checks all transfer identity fields. It is not a cross-proxy distributed lock. A multi-proxy deployment would need a stronger shared claim/lease before enabling competing coordinators; that coordination is intentionally not introduced in this phase.

Other known limitations: no periodic checkpoints; no state migration from vanilla player files; Paper serialization compatibility follows the Paper/Minecraft version that reads the bytes; event delivery is best-effort; failed quit persistence deliberately leaves online presence until recovery/manual reconciliation; concurrent or stale snapshots are rejected rather than merged.

## Explicitly excluded from Phase 1

Potion Effects, Advancements, Statistics, Maps, PDC player state, Location, Gamemode, Flight, Companions, separate Gamemode Inventories, EnderChest, Economy, Moderation, Chat, Friends, Clans, Discord, and Web API behavior are not synchronized here. ItemStack PDC remains included as part of the native item bytes; this does not add general player PDC synchronization.

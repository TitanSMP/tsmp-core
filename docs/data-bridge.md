# TitanSMP Data Bridge — Phase 1

## Scope

Phases 1 and 1.5 provide UUID-keyed player snapshots and coordinated server transfer. Phase 2 adds active Potion Effects and Phase 3 adds Advancement criterion progress to that same snapshot. Map item stacks use the existing inventory item codec, but map assets are not part of player state; see [Map Synchronization Analysis](map-sync.md). The EnderChest remains wholly owned by `modules/ender-chest` and is not represented in the player-state API or table.

The implementation is native TitanSMP code based on the source audit in [data-bridge-audit.md](data-bridge-audit.md). It does not use the archive as a runtime dependency and does not copy code from it.

## Module boundaries

- `api` contains `PlayerState`, `PotionEffectData`, opaque `SerializedPlayerItem`, `PlayerDataService`, persistence exceptions, and the `PlayerStateUpdated` event. It has no Paper, Bukkit, Velocity, JDBC, MariaDB, Lettuce, or Redis types.
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
- active potion effects with stable namespaced type IDs, duration in ticks, amplifier, ambient/particle/icon flags, infinite-duration marker, and Paper's hidden lower-priority effect chain
- capture instant used to account for elapsed time before applying finite effects

Empty slots are null. Cursor items, temporary containers, open GUIs, EnderChest, saturation, max-health attributes, and all Phase 2 fields are excluded. If MariaDB has no state row, the local inventory and vitals are cleared to vanilla defaults (empty inventory, full food and default max health, level/XP zero); old server-local player files are not imported. The default snapshot is written on first quit/shutdown.

## Item serialization

The shared Paper-only `PaperItemStackCodec` uses Paper's native `ItemStack.serializeAsBytes` / `deserializeBytes` API. It is used by both Data Bridge and EnderChest, preserving Paper-supported ItemMeta and data such as enchantments, lore, custom model data, attributes, potion/skull metadata, and PDC without a hand-maintained field list. Core stores opaque bytes in a versioned, bounded envelope and never interprets Bukkit objects. A Paper round-trip test covers display name, lore, enchantment, flags, custom model data, and PDC.

The ZIP archive is not modified, unpacked, included, or used as a dependency. The prior audit records its GPL-3.0 license; this implementation does not require upstream code or attribution.

## MariaDB persistence and atomicity

Flyway `V3__create_player_state.sql` creates one `player_state` row per UUID with `state_payload LONGBLOB`, `revision`, and `updated_at`. The row references `players.uuid`; it has no server-specific key and contains no EnderChest, economy, or statistics data. Storing the selected snapshot in one binary payload means all Phase 1 fields are replaced together.

The payload has a magic/version header, explicit slot counts, length-prefixed Paper item bytes, scalar values, effect and advancement records, a 64 MiB upper bound, and strict validation on decode. Version 2 adds the capture instant and potion effect list. Version 3 adds criterion-level advancement progress. Version 1 payloads decode with empty effects and advancements; version 2 retains effects and decodes advancements as empty. No SQL migration is needed. Database calls run on a dedicated worker executor; Paper object capture/application stays on the Paper main thread.

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

## Maps (Phase 4)

Inventory serialization continues to preserve Paper-supported map item bytes and references through the shared `PaperItemStackCodec`; no second map item serializer or PlayerState map field was added. A filled-map reference points to server-managed shared map state, and Paper's integer map ID is not defined as a network-wide identity. Paper 26.3 exposes MapView metadata but no supported complete export/import API for vanilla pixel content and renderer-layer state. Custom renderer instances are plugin runtime objects and are not replicated. Consequently, item persistence must not be interpreted as cross-backend map asset synchronization: for independent map registries, TitanSMP cannot currently guarantee that a transferred filled map resolves to the same image. There is no map asset table, map-specific transfer service, or payload version bump. The API findings and future asset boundary are documented in [map-sync.md](map-sync.md).

## Potion Effects (Phase 2)

Paper 26.3 exposes an effect's registry key, duration in ticks, amplifier, ambient state, particle and icon visibility, infinite-duration semantics (`PotionEffect.isInfinite()` / `INFINITE_DURATION`), and an optional hidden effect. TitanSMP stores the type as a namespaced identifier such as `minecraft:speed`, never a display name. The hidden effect is kept recursively because it may become visible after a stronger effect expires. The API DTO contains no Bukkit or Paper types.

Each snapshot carries `capturedAt`. Finite durations are reduced by the elapsed wall-clock time at apply, rounded up to ticks so transfer delay does not extend an effect; effects at zero are omitted. Infinite effects use Paper's special infinite sentinel and do not undergo arithmetic. The arithmetic is bounded to avoid overflow. Unknown effect keys on a target are skipped with a warning; the committed payload remains intact for a later compatible server.

After a successful load, the target removes its current potion effects and then applies the snapshot. This also applies to a valid first-time load with no row, which resets to vanilla defaults with no effects. No effects are cleared if load fails or a stale transfer revision is rejected. Effect add/change/remove events mark the player's effect state dirty; no DB operation runs per tick. The existing quit, transfer, and shutdown snapshots observe the complete active effect set, so milk, plugin removals, refreshes, natural expiry, and newly applied effects are represented by the next lifecycle save.

Death is treated as a dirty-state transition. The normal lifecycle snapshot reads the actual post-death/respawn Paper state; effects removed by Minecraft are therefore not brought back from an old state. No standalone death save or effect table is created. The target and source use the same CAS revisions as all other state, so stale snapshots cannot overwrite a newer effect list.

This phase synchronizes the state visible to Paper when a lifecycle snapshot occurs. It does not define external plugin effect ownership or per-effect exemptions. Effects from other plugins are included in the complete snapshot, with no per-tick persistence or general external-effect API.

## Advancements (Phase 3)

The API stores an `AdvancementProgressData` per namespaced advancement key and a `CriterionProgressData` for every criterion in the server's loaded definition, including both completed and incomplete criteria. Snapshots enumerate Paper's current advancement iterator and read live `AdvancementProgress` values; no local progress cache is used. Vanilla and plugin namespaces are treated uniformly. A registry definition is not copied: titles, icons, parents, rewards, and other definition data remain server-owned. The stored payload is retained for advancement keys absent on the target and for criterion names that do not match the target's version. Target-only advancements that have no state entry are outside that snapshot and are left alone.

Restoration uses Paper's per-player `AdvancementProgress` award/revoke API for safe changes. The algorithm applies the saved true/false criterion map without merging target-only awards. It revokes target criteria that the saved state marks false, and awards saved criteria when the resulting target progress stays incomplete. If a saved complete state would cause an incomplete target advancement to transition to complete, TitanSMP defers that advancement and retains the canonical saved criterion state in the next snapshots. It likewise avoids changing criteria on an already-complete target when that change could make it incomplete and then complete again. This prevents synchronization itself from executing advancement rewards.

Paper exposes `getDateAwarded` as a read-only completion date and marks awarded criteria at the current time. There is no public way to set the original completion timestamp. It is therefore intentionally not stored; only criterion completion booleans are synchronized. Reward-free completion restore is also not exposed, and the criterion grant API may execute the advancement definition's rewards as part of a completion. For that reason, a completed saved advancement is not forced to completion on a target where it is currently incomplete. Its full state remains in MariaDB and Redis/transfer revision flow, but the target's visible local progress can remain incomplete until the player naturally completes it there. This is a documented fidelity limit of the public API. External plugins that attach rewards to criterion-grant events may also react to safe partial awards; TitanSMP cannot suppress those callbacks while using the public progress API.

Paper 26.3 has a cancellable `PlayerAdvancementCriterionGrantEvent`, which reports a grant after it occurs and revokes that criterion if cancelled. `PlayerAdvancementDoneEvent` reports completion but is not cancellable. No corresponding public revoke event is present in this API version, so snapshots read current progress on the existing quit, transfer, and shutdown lifecycle. Grant/completion events mark state dirty; they never write directly to MariaDB. Advancement data shares the same state revision and compare-and-set write as inventory and effects. Death and respawn do not clear advancements.

## Explicitly excluded from current phases

Statistics, Maps, PDC player state, Location, Gamemode, Flight, Companions, separate Gamemode Inventories, EnderChest, Economy, Moderation, Chat, Friends, Clans, Discord, and Web API behavior are not synchronized here. ItemStack PDC remains included as part of the native item bytes; this does not add general player PDC synchronization.

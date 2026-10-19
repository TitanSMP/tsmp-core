# TitanSMP EnderChest

## Purpose and module boundaries

TitanSMP EnderChest is a network-wide player storage feature. The TitanSMP storage, keyed by player UUID, is the durable source of truth. `/ec` and right-clicking a normal EnderChest block both open the same Paper inventory backed by the same service. The Paper `PlayerEnderChest` inventory is neither read nor written by this feature.

```text
api/src/main/kotlin/de/titansmp/api/enderchest/
  EnderChestService.kt              # platform-independent storage and item-payload contracts
modules/ender-chest/
  src/main/kotlin/de/titansmp/core/enderchest/
    DefaultEnderChestService.kt     # async service and row permission resolution
    JdbcEnderChestRepository.kt     # shared DataSource, binary codec, CAS revision writes
  src/main/resources/db/migration/
    V2__create_ender_chest_storage.sql
paper/src/main/kotlin/de/titansmp/paper/enderchest/
  EnderChestPaperModule.kt          # /ec, GUI, item serialization, event guards, lifecycle save
```

Dependency direction:

```text
api
 ↑
core ← modules/ender-chest
 ↑             ↑
 └────────── paper
```

The API contains no Bukkit, Paper, LuckPerms, JDBC, or Redis types. The feature core depends on root `api` and `core`; Paper uses the feature service and provides the platform boundary. The root Paper plugin composes the module using `TitanSmpRuntime`'s existing MariaDB `DataSource`. No second database pool or Redis client is created.

## Storage and persistence

Each player has one row in `ender_chest_storage`:

| Column | Purpose |
|---|---|
| `player_uuid BINARY(16)` | Player identity and primary key; foreign key to `players.uuid`. |
| `contents LONGBLOB` | Versioned encoding of all 54 slots. Empty slots are encoded explicitly. |
| `revision BIGINT UNSIGNED` | Monotonic compare-and-set revision for stale write rejection. |
| `updated_at DATETIME(3)` | Last successful save timestamp. |

There is no server key, Minecraft-name key, rank, premium flag, or permission-derived row count in this table. MariaDB is the shared durable store for every backend. A new player's API storage is a full 54-slot empty snapshot at revision 0; the first successful save inserts revision 1. Later saves update only when the stored revision still matches the loaded revision.

The feature service returns `CompletionStage` values and dispatches JDBC operations to a dedicated single-thread worker. No database operation runs synchronously on the Paper server thread. Opening reads TitanSMP's shared `players.online/current_server` presence and rejects a player whose current session is recorded on another backend. Writes compare the storage revision and also require that the player is still owned by this backend or is offline (allowing a quit save after the presence row is cleared). Inventory changes stay in the open in-memory session; the feature writes on close, quit, and plugin shutdown, not per click. Shutdown snapshots open sessions on the server thread, then waits for queued persistence off the server thread before closing the shared runtime.

## Item serialization

The Paper adapter serializes each non-empty slot with Paper's `ItemStack.serializeAsBytes()` and reconstructs it with `ItemStack.deserializeBytes()`. Paper documents this as raw NBT serialization, including the data version so migration to a newer Minecraft version can use the built-in data converter. The adapter therefore stores the complete Paper item payload rather than reducing an item to a material ID; item components and supported metadata such as names, lore, enchantments, attributes, potion/skull data, and item PDC are carried by the serialized item data. TitanSMP's outer storage blob is separately versioned and validates slot count, payload sizes, format marker, and trailing bytes. [Paper ItemStack API](https://jd.papermc.io/paper/26.3/org/bukkit/inventory/ItemStack.html)

The API/core only see opaque per-slot bytes. This allows the later Data Bridge design to reuse the same item codec contract without adding any Data Bridge dependency or implementation now. Serialization tests exercise item metadata and item PDC through Paper's data format.

## Permissions and rows

LuckPerms is required by the Paper plugin metadata and is queried through its online-player adapter. The only feature permission nodes are:

- `titansmp.enderchest.rows.1` — default access is one row (27 slots).
- `titansmp.enderchest.rows.2` — unlocks both rows (54 slots).

The service selects the highest matching supported row permission. If none resolves true, access falls back to one row. Permission evaluation happens when opening and again during inventory interaction/close, so online permission changes affect the current view without requiring a permission event subscription. LuckPerms remains the authority; TitanSMP stores no group or rank data. Its public API is kept out of root `api` and the feature core. [LuckPerms API reference](https://javadoc.io/static/net.luckperms/api/5.5/net/luckperms/api/platform/PlayerAdapter.html)

The current storage capacity is 54 slots, with one or two rows currently supported. The policy has a maximum-row setting constrained by storage capacity, so introducing more rows later requires increasing the supported capacity and GUI layout intentionally. Existing contents are not conditioned on the current permission.

## GUI and locked slots

The custom GUI is always 54 slots to keep slot positions stable. With one row, slots 0–26 show player items and slots 27–53 show Barrier indicators; with two rows, all slots display the stored inventory. A custom `InventoryHolder` identifies the GUI in inventory events.

Barrier items are presentation only. Clicks on locked top slots, number-key swaps, shift-clicks, double-click collection, drops, offhand swaps, and drags that include locked slots are cancelled server-side. Drop and offhand-swap events are also blocked while this GUI is open. The GUI stores a backing snapshot for every slot. If permission is reduced while open, currently visible slots are captured first and then masked with barriers; the underlying slot bytes are retained and cannot be removed through the display item. If permission is later restored, those bytes are decoded and made visible again.

The handler re-evaluates permissions on an inventory event and cancels the triggering action when the row count changes, then redraws the accessible/locked area. A player reopening either access path always loads the shared storage row and checks current permissions.

## `/ec` and vanilla EnderChest access

`/ec` is registered as a player command. Console invocation receives a player-only response. The command checks current LuckPerms permissions, asynchronously loads the UUID storage, then opens the custom inventory on the server thread.

Right-clicking a normal `ENDER_CHEST` block cancels the interaction and opens that same custom view. An `InventoryOpenEvent` guard also cancels direct attempts by other plugins to open a vanilla EnderChest inventory and redirects the player to TitanSMP storage. Both paths use the same `EnderChestService` and revision mechanism.

This feature does not import, clear, or overwrite existing Vanilla EnderChest contents. Those contents may still exist in Minecraft player data, but normal block use no longer exposes them. A later one-time importer could snapshot each player's old Paper EnderChest inventory on the entity thread, serialize it with the same ItemStack codec, and insert it only when no TitanSMP storage row exists (or after an explicit conflict report). It must never blindly replace TitanSMP contents.

## Downgrades and permission changes

Permissions control visibility and interaction only; they do not alter capacity or persisted slot bytes. A change from two rows to one replaces the second row in the display with barriers after snapshotting its current contents into the session backing list. Save/close preserves it. Regaining the second-row permission reveals the exact item data again. Storage never moves hidden items into player inventory, drops them, or deletes them.

## Server changes and concurrency

Every loaded snapshot carries a MariaDB revision. Writes compare the loaded revision in the same `UPDATE`; a first insert at revision 0 relies on the UUID primary key. The existing player-presence row also gates load/save ownership, so a backend whose UUID is no longer `current_server` cannot write after another backend takes over that player. If two backends save snapshots from the same revision, only the first write succeeds. A stale write receives `EnderChestStorageConflictException`, is logged, and does not overwrite the newer row. A close conflict notifies the online player that newer storage was kept and asks them to reopen it. This is optimistic concurrency control, not a distributed lock.

The current Player Presence service records the current backend, but it does not yet provide an atomic transfer-save handshake or a session fencing token. Presence-gated writes prevent the old backend from saving after the new backend is recorded as owner, and revision checks prevent stale overwrite. They cannot guarantee that a destination which opens before the source's close-save completes sees the source's last in-memory change; a permission/session ownership check may also briefly reject `/ec` until the join presence update completes. This limitation is explicit. The next synchronization step should add a versioned transfer-save request / committed notification over the existing Redis Event Bus, with the MariaDB revision as authority and a destination wait/reload before opening. Redis caching, inventory replication, distributed locks, and EnderChest-specific Redis events are not implemented here.

## Shutdown and save lifecycle

- Inventory close: snapshot current accessible slots plus all preserved locked slots, then save asynchronously if contents changed.
- Player quit: fallback snapshot/save if the close event did not already persist the session.
- Plugin shutdown: snapshot all sessions on the Paper thread, queue writes, then wait for them on TitanSMP's lifecycle executor before the runtime closes.
- Clicks: update only the session inventory; no database write per click.

An unchanged 54-slot snapshot is not written again. Concurrent/repeated save attempts for one GUI session are guarded so close/quit do not duplicate a write.

## Redis and future integration

MariaDB is sufficient for initial cross-server durable storage. No Redis key, cache, Pub/Sub event, lock, or replication is introduced. The future transfer handshake should publish only coordination metadata (player UUID, source/target server identity, storage revision/session generation, event ID and schema version); item payloads remain in MariaDB. Consumers must re-read the durable row and reject older generations.

The shared item payload contract may support a later Data Bridge integration, but Data Bridge is a separate feature and this EnderChest module does not import its source, ZIP, dependencies, or tables.

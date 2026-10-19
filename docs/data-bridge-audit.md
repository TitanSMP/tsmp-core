# MC Data Bridge Source Audit

## 1. Executive summary

The supplied `mc-data-bridge-main.zip` contains a Java 25, Maven-built plugin that combines a Bukkit-family backend plugin with Velocity and BungeeCord proxy entry points. Its central implementation is a large Paper/Bukkit listener and a JDBC persistence manager. It snapshots player data on backend lifecycle events, serializes it into MySQL/MariaDB (and optionally local SQLite), and coordinates transfers with database leases plus proxy plugin messages. The inspected source has no Redis client, Redis protocol, or Redis event bus.

TitanSMP should integrate the domain behavior as native `modules/data-bridge` code, not deploy or embed the upstream plugin as-is. Reuse the domain inventory of player-state components and selected compatibility knowledge; replace platform lifecycle wiring, persistence, migrations, transport, serialization boundaries, observability, and concurrency ownership with TitanSMP services. Do not port the upstream database manager, plugin message protocol, or its schema management.

The feature set matching the current TitanSMP request is health, food, experience, inventory, armor, potion effects, advancements, and a considered map capability. Ender-chest storage belongs to the separate `/ec` system. Location, PDC, flight/gamemode, companions, and separate gamemode inventories are out of scope. Vanilla statistics need a deliberate integration with the future `modules/stats` domain so there is one persisted source of truth.

This is a source audit and plan only. No source from the ZIP, dependency, schema, event, or implementation has been added to TitanSMP.

## 2. Source structure

The archive is a single Maven project, not a multi-module build. Production sources use Java packages under `com.digitalserverhost.plugins`; the plugin is not Kotlin and has no upstream `de.titansmp` namespace.

```text
mc-data-bridge-main/
├── pom.xml
├── README.md, ARCHITECTURE.md, DATABASE_SETUP.md, LICENSE
├── src/main/resources/
│   ├── config.yml
│   ├── plugin.yml
│   ├── bungee.yml
│   └── velocity-plugin.json
├── src/main/java/com/digitalserverhost/plugins/
│   ├── MCDataBridge.java                 # Bukkit/Paper plugin entry point and schema/bootstrap
│   ├── commands/BridgeCommand.java
│   ├── listeners/
│   │   ├── PlayerListener.java            # snapshots, loads, applies, lifecycle, inventory guards
│   │   └── AuthMeListener.java
│   ├── managers/
│   │   ├── DatabaseManager.java           # JDBC, tables, persistence, identity, locks
│   │   ├── BackupManager.java
│   │   └── MetricsManager.java
│   ├── proxy/bungee/                      # BungeeCord entry point, listener, unlock command
│   ├── proxy/velocity/                    # Velocity entry point, listener, unlock command
│   └── utils/                              # PlayerData, schedulers, platform bridges, GUI,
│                                           # hashing, maps, messages, and compatibility helpers
└── src/test/java/...                       # JUnit/Mockito tests for selected flows and edge cases
```

`MCDataBridge` is the Bukkit plugin entry point (`plugin.yml`); separate metadata and classes provide BungeeCord and Velocity entry points. `PlayerListener` is the backend lifecycle/synchronization hub. `PlayerData` is both a Bukkit-facing snapshot/model and serializer. `DatabaseManager` combines connection pool setup, schema-facing SQL, component persistence, lock operations, checksums, identity lookup/migration, and map/companion merges. This concentration is a reason to separate responsibilities during a native port.

The source imports Bukkit/Paper APIs across the listener and `PlayerData`: player lifecycle and pre-login events; player/inventory/item APIs; health, food, experience, potion effects; advancements, recipes and statistics; PDC/NBT; game mode, flight, worlds and locations; maps, map views/renderers/canvases; plugin messaging; and platform schedulers. Some map paths inspect and mutate internal map implementation fields reflectively. Scheduler helpers branch for Paper/Folia behavior. The plugin metadata advertises support across multiple Bukkit-family server implementations, but the supplied API dependency is a Paper API artifact; compatibility claims should be validated against TitanSMP's chosen Paper target rather than carried over as guarantees.

## 3. Build, dependencies, and configuration

The archive's `pom.xml` defines Maven coordinates `com.digitalserverhost.plugins:mc-data-bridge:2.2.3-SNAPSHOT`, Java release 25, one production artifact, and a test source set. The compiler config includes parameter metadata and Velocity annotation processing. Packaging uses Maven Shade to include selected runtime libraries and relocate the NBT API package.

| Dependency in upstream POM | Scope/use | TitanSMP disposition |
|---|---|---|
| Paper API `26.2.build.25-alpha` | Provided; backend APIs | Use TitanSMP's existing Paper module dependency/version, not a second one. Verify exact server target. |
| BungeeCord API `1.21-R0.4` | Provided; Bungee proxy implementation | Do not adopt unless TitanSMP chooses Bungee; current architecture has Velocity. |
| Velocity API `3.5.0-SNAPSHOT` | Provided; proxy implementation | Use the existing Velocity module API/version. Avoid the upstream snapshot. |
| HikariCP `7.1.0` | Included JDBC connection pool | Replace with TitanSMP's existing database service/pool. |
| MySQL Connector/J `26.7.0` | Included JDBC driver for MySQL/MariaDB | Replace with the driver's existing TitanSMP-managed runtime dependency/configuration. |
| SQLite JDBC (referenced by code) | Optional local SQLite path | Do not adopt; network state uses TitanSMP MariaDB. |
| `de.tr7zw:item-nbt-api:2.16.1` | Item/PDC/map compatibility paths; shaded and relocated | Do not assume it is needed. Prefer supported Paper APIs and the existing serialization contract; justify any NBT library separately. |
| Adventure API, legacy serializer, MiniMessage `5.2.0` | Provided messaging/UI APIs | Already covered by platform/monorepo choices; no separate upstream copy. |
| Gson `2.14.0` | Provided JSON model/serialization | TitanSMP uses its existing JSON stack; do not add a parallel serializer without a migration need. |
| AuthMe `5.7.0` | Provided optional authentication integration | Not part of the requested data-sync scope; omit. |
| JUnit Jupiter `6.1.3`, Mockito `5.23.0`, Mockito Inline `5.2.0`, MockMC `0.0.5` | Test scope | No production dependency. Select test tools through the existing build conventions if needed later. |
| Maven Compiler `3.13.0`, Surefire `3.2.3`, Shade `3.6.2`, ASM `9.9.1` | Build/package plugins | Not TitanSMP runtime dependencies; TitanSMP uses Gradle Kotlin DSL. |

The POM declares Sonatype snapshots, CodeMC, and PaperMC repositories. Avoid inheriting broad repositories or snapshot coordinates by default. Kotlin, MariaDB driver, Lettuce/Redis, and Flyway are not dependencies in this upstream POM; the latter infrastructure already exists in TitanSMP. Upstream source names the SQL backend MySQL while its setup documentation also supports MariaDB.

Configuration uses Bukkit `config.yml` plus plugin metadata, with options for database type/credentials/pool, table prefix, lock timeout/heartbeat, sync toggles, identity mode and security seed, server/world blacklists, backups, map behavior, metrics, and debug output. Some config values have defaults in code; the configuration file's sync defaults enable core state such as inventory but leave several optional capabilities disabled. The sample credentials are placeholders and must not be treated as production settings.

## 4. Synchronization features

Backend snapshots are assembled in `PlayerData`; `PlayerListener` reads them before persistence and applies loaded values through Bukkit/Paper methods. `DatabaseManager` reads/writes a mixture of normalized tables and JSON/BLOB columns. The database is the persisted source of truth between backend sessions. Plugin messages coordinate a switch; they are not the player-state store. Upstream has no Redis data path.

| Option | Upstream implementation and lifecycle | TitanSMP decision / platform APIs |
|---|---|---|
| `health` | `PlayerData` snapshots `Player.getHealth`; apply path calls player health setter. Stored with statistics/basic scalar state in `databridge_statistics`; loaded before/at join and saved on quit/switch. | Include. Paper adapter reads/writes on the owning entity thread; core DTO stores validated scalar values. DB is authoritative between sessions. |
| `food-level` | Captures food level, saturation, exhaustion; applies to player; shares the statistics component table and lifecycle. | Include. Same entity-thread and validation rules as health. |
| `experience` | Captures level, fractional progress, total experience; applies to player; persisted with scalar statistics. | Include. Define one canonical representation and avoid applying setters in an order that silently recalculates/overwrites another field. |
| `inventory` | Serializes the player inventory contents as an ordered list, stored as inventory BLOB data. Loaded in pre-login/join flow, then applied to inventory. Inventory event handlers block mutation while switching/saving/editing/locked. | Include. Persist slot-indexed contents with an explicit schema/version and empty-slot representation; snapshot/apply on Paper entity scheduler. |
| `armor` | Snapshots armor contents separately from normal inventory, stores a separate blob, restores through `PlayerInventory` armor APIs. | Include; keep its slots distinct from main inventory and document the platform slot mapping. |
| `potion-effects` | Captures type, duration, amplifier and effect flags in serializable DTOs; applies by clearing/replacing active effects. It is included in `PlayerData` JSON rather than the normalized component tables. | Include only after DTO/version policy is set. Paper resolves effect keys and applies effects on the owning thread. Decide handling for unknown/removed effects and expired durations. |
| `ender-chest` | Separate inventory blob and snapshot/apply methods; configurable and disabled by default. The existing Inspector GUI can inspect/edit ender chest data. | Exclude as a Data Bridge feature. `/ec` owns network storage, permissions, and slot entitlement. Potentially reuse only generic slot serialization/validation patterns, after licensing and design review; do not reuse GUI or entitlement logic. |
| `location` | Captures world name and coordinates/orientation; applies a location/teleport when enabled. Config is disabled by default. | Exclude. Server/world-specific spawn and transfer rules make this a separate policy; no cross-server teleport state in the initial module. |
| `advancements` | Snapshots completed advancements and awarded criteria via Bukkit advancement iteration/progress APIs; stores JSON in `databridge_metadata`; apply grants/revokes progress. Recipe discovery is paired in the same option/data path. | Include advancements as requested. Treat advancement keys/criteria as versioned identifiers and define unknown-key handling. Recipe synchronization is not explicitly requested and should be decided separately rather than assumed. |
| `statistics` | Iterates Bukkit `Statistic` values and stores integer values, then applies with `Player.setStatistic`; uses `databridge_statistics.vanilla_stats_json`. This is Minecraft Vanilla Statistics, not a TitanSMP analytics system. | Do not make a competing second stats store. Capture/consume only through a defined integration contract with future `modules/stats`; decide canonical accumulation, reset semantics, and website queries first. |
| `pdc` | Captures/restores persistent data through serialized NBT/PDC representation using NBT API/internal `PublicBukkitValues` access. Stored in `databridge_metadata`. | Exclude initially. PDC keys are plugin-owned data with separate compatibility/security ownership; broad copying risks cross-plugin state corruption. |
| `flight-gamemode` | Captures flight flags and gamemode, applies flight/allowFlight and game mode. | Exclude. Rank/per-world policy should remain authoritative; do not synchronize privilege state. |
| `companions` | Captures selected nearby entities and shoulder entities through Bukkit entity APIs; persists NBT snapshots in `databridge_companions`; applies/removes entities. | Exclude. Entity ownership, duplication, plugin compatibility, and world semantics are outside initial player-state sync. |
| `maps` | Custom map-item path (`PlayerData` and `MapSnapshot`) tags map items with origin server/map ID, captures map pixels/canvas and serialized item data, persists JSON in `databridge_maps`, then recreates/restores map renderers and map files on a target backend. | Consider narrowly, not as general world-map replication. Include only if TitanSMP needs portable filled-map items; map IDs and canvases are server/world implementation state. See map analysis below. |
| `separate-gamemode-inventories` | On game-mode change, snapshots old mode inventory, asynchronously saves it, loads target mode inventory, then applies it on entity scheduler. Uses `databridge_gamemode_inventories`. | Exclude. This is a distinct policy and not needed for the requested single inventory state. |

The archive's tests cover selected player sync and flow cases, lock fencing, map synchronization, gamemode inventories, identity collisions, migrations, serialization, and security. Test presence is useful evidence of intended behavior, not proof that every race or server-version combination is closed.

## 5. Persistence and schema

Upstream uses `DatabaseManager` with HikariCP/JDBC and either MySQL-compatible SQL or a local SQLite mode. It does not use Flyway. The Bukkit entry point creates/updates schema during plugin startup using DDL and `auto-update-schema`; schema evolution is therefore coupled to plugin startup and has a separate migration path from TitanSMP.

The configurable `table-prefix` applies to these logical tables:

| Table | Data held |
|---|---|
| `player_data` | `uuid` (primary key), legacy `data`, `is_locked`, `locking_server`, `lock_timestamp`, `lock_version`, `snapshot_checksum`, `last_known_name`, `data_checksum`, `identity_hash`, `name_last_updated`, `last_updated`. |
| `databridge_inventories` | `uuid` (primary key), `inventory_blob`, `armor_blob`, `ender_chest_blob`, `last_updated`. |
| `databridge_statistics` | `uuid` (primary key), `health`, `food_level`, `xp_level`, `xp_exp`, `xp_total`, `saturation`, `exhaustion`, `vanilla_stats_json`, `last_updated`. |
| `databridge_metadata` | `uuid` (primary key), `pdc_data`, `advancements`, `last_updated`. |
| `databridge_companions` | `uuid` (primary key), `companions_nbt`, `last_updated`. |
| `databridge_maps` | `uuid` (primary key), `maps_nbt`, `last_updated`. |
| `databridge_gamemode_inventories` | Composite primary key `uuid`, `gamemode`; also `inventory_blob`, `armor_blob`, `ender_chest_blob`, `last_updated`. |

Column types vary between MySQL/MariaDB and SQLite, and older single-row JSON data remains as a compatibility path. The code performs compatibility schema alterations and data migrations; it includes no checked-in Flyway migration set. Identity migration can update UUIDs in the primary and selected component tables. Any TitanSMP migration must be designed from TitanSMP's schema, with explicit table ownership and backfill policy, not mechanically ported from the upstream DDL.

Connection pooling and driver setup live inside the plugin's `DatabaseManager`; SQL is executed with prepared statements for values, while table identifiers are dynamically assembled from configured table names/prefixes. The upstream code contains a comment that inputs are sanitized and removes backticks from the main table name, but this is not a reason to accept its identifier-management pattern in TitanSMP. SQL ownership should remain behind TitanSMP's database service/repository boundary.

Component saves use JDBC transactions in selected flows, including component writes and lock release. Other methods save components independently. The active lifecycle and legacy overloads should be audited before relying on one atomicity guarantee. There is no Redis cache. Player data has a short-lived in-memory loading cache during pre-login/join and maps tracking active/saving/switching/editing players; those structures are process-local, not shared state.

## 6. Redis and messaging

There is no Redis dependency, Redis Pub/Sub, Redis lock, or upstream event bus. The implementation uses the `mc-data-bridge:main` plugin-message channel, with UTF subchannel names and UUID strings. The backend listener handles `SaveAndRelease`, `ForceUnlock`, `LiveInventorySync`, and `LockReleased` messages. Velocity's `ServerPreConnectEvent` sends `SaveAndRelease` to the current backend; Bungee's `ServerSwitchEvent` sends it to the previous backend. Backend save/release code sends a `LockReleased` signal so a waiting backend can wake; database polling remains the fallback. This transport depends on proxy/backend plugin messaging and its delivery timing.

TitanSMP should replace these messages with typed, versioned contracts over its existing Redis Event Bus. Candidate semantic events are `PlayerTransferSaveRequested` (targeted to the source session), `PlayerStateCommitted`/`PlayerLockReleased` (for wake-up and observability), and possibly a targeted state-refresh request for administrative tools. Event payloads should carry player UUID, source/target server identity, session/fencing generation, correlation ID, and schema version. Events are notifications, not authoritative inventory payloads; the database remains the durable handoff source. `ForceUnlock` should not be ported as an unauthenticated broadcast action. Live inventory inspection/edit is not part of this requested module scope.

The event bus must tolerate duplicate, late, missing, and reordered messages. A consumer should treat a message as a hint, re-read the durable lease/version, and make no state transition from an old generation. Redis Pub/Sub itself is not durable; publish failure must not allow a destination to load an uncommitted snapshot.

## 7. Serialization

`PlayerData` is Gson-oriented and holds scalar values, lists/maps, potion DTOs, and item data. ItemStacks are represented as Base64-encoded bytes or NBT JSON depending on path/version. Deserialization contains compatibility paths for modern bytes, compressed binary, MockMC JSON, legacy NBT, and legacy YAML; an old Java object deserialization path is also present. The POM's serialization-format setting selects JSON or binary for database blobs. The code uses `ItemStack.serializeAsBytes` on the modern path, with NBT API fallback/compatibility handling.

TitanSMP should define a versioned, bounded, explicit item encoding with migration readers only for formats actually present in TitanSMP data. Do not copy the Java object deserialization fallback into a new storage format. Validate payload size, item count, slot count, material/key availability, and malformed input. Keep platform serialization at the Paper adapter boundary; core should work with API DTOs and opaque/versioned item payloads rather than Bukkit `ItemStack` types.

The data checksum and identity HMAC use SHA-256/HMAC-style hashing with a configured seed. Integrity checksums can detect accidental or unsophisticated tampering but do not replace transactional ownership or authorization. A checksum is not an item merge or conflict-resolution policy.

## 8. Inventory behavior and gaps

- Main inventory and armor are snapshotted separately as ordered arrays/lists, retaining slot position and empty entries. Offhand is included only insofar as the Bukkit `PlayerInventory.getContents()` result includes its platform-defined slot; TitanSMP must explicitly define and test the slot contract for its Paper version.
- Cursor item is not represented as a field in `PlayerData` and is not separately persisted. The listener closes open inventories during transfer/save and cancels inventory actions while guarded, but cursor behavior on disconnect/close must be verified on the target Paper version. Do not claim cursor preservation from the source.
- On async pre-login, the backend acquires a SQL lock, loads a `PlayerData` snapshot, and stores it in a process-local cache for join application. Join applies cached state; quit/kick snapshots and saves/releases. Proxy transfer requests a source save/release. There is no general periodic inventory checkpoint in the inspected lifecycle; crash recovery therefore depends on the last completed save.
- `PlayerListener` tracks loading, saving, switching, edited, applying, and lock-version state. It cancels selected click/drag/drop/pickup/interact actions while a player is in a protected state and closes open inventory views before save/transfer. This reduces the window for mutation but does not by itself prove that every inventory mutation path or plugin-driven mutation is covered.
- Snapshotting player state must happen on the Paper entity scheduler; JDBC/Redis work must be asynchronous. The source takes snapshots before/asynchronous transitions in several flows and applies loaded values on player/entity schedulers. Review every call site in a port to prevent Bukkit access off-thread.
- The source uses a single player snapshot and replaces fields; it does not implement slot-level conflict merges. The database row and lock are intended to serialize ownership. Concurrent server writes should be rejected, not merged.

## 9. Race conditions and scenario analysis

| Scenario | Upstream behavior / residual concern | TitanSMP handling |
|---|---|---|
| A. Immediate SMP-01 → SMP-02 switch | Proxy asks source to `SaveAndRelease`; destination pre-login waits for the lock with periodic DB retry and a local monitor awakened by `LockReleased`. This aims to serialize transfer. Delivery can be delayed/lost, and Bungee's `ServerSwitchEvent` is after switching, so timing differs by proxy. | Gate destination load on durable session ownership/fencing. Request source save before transfer where possible; destination waits/retries asynchronously and only loads after committed state and released/expired lease. Use event as wake-up only. |
| B. Connection lost during switch | Quit/kick paths attempt save/release; if backend never completes the save, lease expiry eventually permits another owner. A crash/disconnect between source save and destination load can leave the latest committed state as durable state; no in-flight event can restore an uncommitted snapshot. | Make save-and-release atomic. On ambiguous disconnect, destination re-reads lease and latest committed version. Define lease expiry and recovery; never let timeout alone authorize an old server's late write. |
| C. Two servers save simultaneously | SQL lock owner and `lock_version` are intended to serialize; version-aware component save checks the token. Legacy save/release overloads do not include the fencing token. Verify all production call paths use the fenced transaction. | Every write carries session generation and conditional update verifies it in the same transaction as state persistence. No stale server can overwrite newer state. |
| D. Inventory open during disconnect | Source closes inventory and guards selected inventory/player interactions while saving/switching. Cursor item is not modeled; other plugins and less common mutation APIs can bypass event guards. Disconnect close semantics can affect cursor handling. | Define a Paper-side freeze/snapshot transition. Capture main, armor, offhand, and cursor semantics explicitly; test open container, crafting, drag, death, plugin mutation, and disconnect cases. |
| E. Server crashes while saving | JDBC transactions protect their own transactional writes; incomplete transactions roll back on connection loss. If crash occurs before a snapshot starts/commits, newest in-memory changes are lost. Legacy or independent component saves may not share one transaction. | Store the whole selected player-state generation and release ownership atomically. A crash leaves prior committed generation intact and lease recoverable; add an explicit checkpoint strategy only if required by product needs. |
| F. Two near-simultaneous logins | Pre-login lock acquisition and wait loop are intended to admit one owner; lock expiry permits recovery. `acquireLock` and subsequent `acquireLockVersion` are separate operations, and waiting monitors are local to one process. Verify the interleaving and every error cleanup path. | Unique active-session lease/conditional generation in DB. Atomic claim returns the fencing generation. A second login is rejected or waits under defined policy; failure always releases only its own generation. |
| G. Old event arrives after a newer event | Plugin messages can be delayed/duplicated; local monitors are only wake-ups. The DB lock version is the authoritative stale-write defense only on paths that verify it. | Include monotonically increasing generation and event ID; ignore stale generation. Re-read durable state before action. Events never carry authority to apply old player state. |

Additional review point: the waiting code uses process-local `lockMonitors` and removes monitor entries after waits; tests should cover concurrent waiters and notification/removal timing. This is an implementation detail to re-evaluate, not a proven exploitable race from source inspection alone.

## 10. Performance considerations

The plugin opens JDBC connections for lock acquisition, polling, component save/load, identity lookups, and heartbeats. Lock wait retries can perform repeated DB writes/reads at 500 ms intervals for up to roughly 10 seconds (the exact retry count is code/config-flow specific). The configured pool defaults to 10 connections, so bursts of logins/transfers across several servers can contend with heartbeats and persistence. Hikari metrics are local to each plugin instance.

Player API snapshots and application are necessarily platform-thread work; item serialization and broad advancement/statistic iteration can cost CPU proportional to inventory size and enabled data. Map capture/restoration uses reflective probing, file I/O, renderer work, and map creation, making it especially expensive and version-sensitive. Do not run Bukkit/Paper calls on generic async threads merely to move work away from the main thread.

TitanSMP should keep SQL off the Paper main/entity thread, serialize a bounded snapshot after safely freezing the player state, batch database work by player/session generation, and use event-driven wakeups plus bounded backoff rather than a Redis/database polling storm. Measure transfer latency, pool pressure, serialization time/payload size, stale-write rejects, lease timeouts, and save failures before tuning for network scale.

## 11. License

The archive's `LICENSE` is GNU General Public License version 3, dated 29 June 2007. GPL-3.0 permits copying, modification, and redistribution, subject to its conditions. If TitanSMP distributes a derivative that incorporates upstream code, the applicable GPL obligations include preserving copyright/license notices, marking modified files, providing the complete license, and providing corresponding source under GPL-3.0-compatible terms for the covered work as required by the license. Keep provenance and the exact upstream revision for any future port; do not copy code without an explicit compatibility review of the whole combined distribution and deployment model. The project README points to the upstream repository; confirm the archive's commit/release provenance before deciding attribution text. This section is a technical checklist, not legal advice.

## 12. Reuse, replacement, and discard

### Reusable as design input

- The list of player-state components and their platform read/write API touchpoints.
- Slot-oriented separation of main inventory and armor, and the need to guard inventory changes during a transfer.
- Compatibility cases for ItemStack encoding and map items, after a TitanSMP-specific format and migration policy is agreed.
- Tests as scenario ideas: concurrent login, stale generation, component serialization, map compatibility, and transfer lifecycle.

### Replace with TitanSMP services

- `DatabaseManager`, Hikari/driver setup, SQL schemas, automatic DDL upgrades, SQLite mode, and backup manager → TitanSMP database service/repositories and reviewed Flyway migrations.
- Plugin-message handoff and local monitor wake-ups → TitanSMP versioned Redis Event Bus events, with DB lease/generation as authority.
- `PlayerData` as combined Bukkit model/JSON/JDBC payload → API contracts and versioned platform-neutral DTOs; Paper-only adapters handle live Player/ItemStack APIs.
- Upstream lock, checksum, identity migration, metrics, and config ownership → integrate with existing TitanSMP player identity, service identity, database, event bus, configuration, and observability contracts. Retain only requirements proven necessary.
- Proxy plugin entry points and cross-platform bridge code → TitanSMP's existing `paper` and `velocity` integrations. Do not add Bungee unless the network platform decision changes.

### Exclude from initial scope

- Ender-chest data and Inspector GUI, location, PDC, flight/gamemode, companions, separate gamemode inventories, AuthMe/FastLogin migrations, admin commands, and standalone metrics HTTP server.
- Upstream's own database/Redis infrastructure (there is no Redis implementation), backup system, broad multi-platform packaging, and direct SQL behavior in Paper listeners.
- Vanilla statistics as a second authoritative store until `modules/stats` integration is designed.

## 13. TitanSMP target architecture

Keep the existing monorepo foundation and isolate the feature beneath `modules/data-bridge`:

```text
api/                                      # stable shared contracts and player/server identities
core/                                     # MariaDB, Flyway, Redis Event Bus, shared player services
paper/                                    # Paper platform adapters and lifecycle integration
velocity/                                 # proxy transfer coordination adapter
modules/
└── data-bridge/
    ├── api/                              # optional only if feature contracts must be public;
    │                                     # otherwise contracts stay in root api
    ├── core/                             # sync policy, snapshot orchestration, persistence ports,
    │                                     # session/fencing decisions, versioned state DTOs
    └── paper/                            # Paper snapshot/apply adapters, ItemStack codec,
                                          # inventory freeze and lifecycle listeners
```

The nested `api/core/paper` split is a proposed internal organization, not a request to add those Gradle projects in this documentation-only change. Prefer root `api` for contracts shared with other TitanSMP modules and add feature-local subprojects only if the current Gradle structure makes the boundary useful. Avoid a module dependency cycle: feature code may depend on root `api` and `core` services; core must not depend on feature code or Paper.

### Contracts and ownership

- **API:** player-state component identifiers/options, immutable DTOs for health/food/experience, potion effects and advancements, versioned serialized inventory envelope, synchronization service/port contracts, and typed transfer/commit event payloads where other modules need them. No Bukkit, Paper, Velocity, JDBC, Lettuce, or MariaDB types.
- **Core:** orchestration and policy, session generation/fencing, validation, durable save/load via TitanSMP database service, retry/timeout policy, and publication/consumption through the existing Event Bus. It owns no direct platform objects and no private pool/client.
- **Paper:** join/pre-login/quit/kick/transfer lifecycle adapter, entity-thread snapshot/application, slot semantics, safe inventory freeze/close, potion/advancement/statistic API bridging, and Paper item serialization. No direct JDBC or standalone Redis client.
- **Velocity:** transfer intent/target server context and coordination with the current player-session contract. It must not independently mutate player-state persistence.
- **Database:** TitanSMP MariaDB remains the durable source of truth. Store the selected portable state under explicit player UUID and schema/generation ownership. Avoid duplicating state already owned by vanilla files, TitanSMP player identity, `/ec`, or `modules/stats`.
- **Migrations:** Flyway-owned, additive and reversible where practical. Define a player-state schema/version, indexes and concurrency fields, payload limits, backfill/rollback strategy, and compatibility policy before creating migration files. This audit does not prescribe table names or add migrations.
- **Redis:** publish only coordination/notification events through the current Event Bus. Store no authoritative inventory in Pub/Sub and do not treat Redis messages as durable commits or distributed locks.

### Stats recommendation

The upstream feature synchronizes Vanilla statistics from Bukkit and stores them in its own JSON table. TitanSMP should not add that second table. Decide which Vanilla counters the future `modules/stats` wants, then define one ingestion/merge model (for example, server-side deltas with idempotent session/event identifiers) and one canonical Stats storage contract for the Web API. A raw per-server snapshot copied on each switch can regress counters or duplicate increments if stale or repeated. The eventual path should be Paper vanilla API → stats ingestion contract/storage → Web API → Next.js, with Data Bridge only coordinating required player session state and no parallel statistics authority.

### Map recommendation

Upstream `maps` is not simply player location sync. It targets map items carried in inventories: it records map item data, source-server/map-ID metadata and inventory slot/type, extracts a 128×128 pixel canvas through Bukkit/NMS/renderer introspection, stores snapshots as JSON, and attempts to recreate/restore map canvas/renderers and map files on another server. ItemStack data is part of the snapshot, but the map ID is local server/world map state. It does not provide general world replication or a portable world/location model. Reflection into `WorldMap`/`MapItemSavedData` and writing world map files are version-sensitive and need testing.

Recommendation: first establish whether TitanSMP's servers share the same world/map storage and whether players need custom filled maps to look identical on every backend. If ordinary shared-world map IDs already resolve, synchronize the ItemStack only and avoid copying canvas data. If worlds differ, specify map identity and canvas lifecycle as a separate capability; do not port the reflection/file mutation approach without a focused compatibility design.

### Ender-chest boundary

The Data Bridge must not own `/ec`, permissions, premium slot entitlement, or ender-chest persistence. The upstream separate ender-chest blob and generic slot encoding may inform serialization tests, but TitanSMP's dedicated ender-chest system owns storage and access policy, including retention when premium status changes.

## 14. Open technical questions

1. Which exact Paper/Minecraft version and server topology will TitanSMP target, and do backends share world files/map storage?
2. Should transfer saves be mandatory before Velocity completes a connect, or should the destination wait on a session service after connection begins?
3. What is the desired policy for a second simultaneous login: reject, queue, or replace the earlier session?
4. Which Vanilla statistics should feed `modules/stats`, and should the source model be per-session deltas or canonical snapshots?
5. Are advancements and discovered recipes both wanted? The request explicitly accepts advancements, but recipes are coupled upstream and are not a stated TitanSMP requirement.
6. Does the network need cross-backend custom map canvas fidelity, or only portable map item stacks in a shared world?
7. What is the existing database service's transaction/repository API and the current Redis Event Bus event-versioning/delivery contract for use by feature modules?
8. What recovery window and checkpoint policy are acceptable if a backend crashes before its next committed transfer/quit save?
9. What upstream archive commit/tag produced this ZIP? Verify provenance and exact license attribution before any code-derived implementation is copied.

## 15. Next implementation step

Before adding code, resolve the topology, session/lease policy, statistics ownership, map requirement, and recipe decision above. Then write a small implementation design against the existing TitanSMP database and Event Bus APIs, including state DTOs, transaction boundary, fencing generation, migration/backfill plan, and failure behavior. The first code increment should implement only the agreed core persistence contract plus Paper-thread-safe health/food/experience/inventory/armor/potion/advancement snapshots and tests for transfer races; keep maps and statistics behind explicit follow-up decisions. This is a recommended sequence, not work performed by this audit.

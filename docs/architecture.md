# Module Architecture

TitanSMP is a Gradle multi-module monorepo. This document defines the responsibilities and permitted project dependencies for the current structure. The core contains shared configuration, database, migration, Redis, server identity, lifecycle, and player identity infrastructure.

## Layout

```text
api/
core/
paper/
velocity/
modules/
  data-bridge/
  moderation/
  chat/
  display/
  economy/
  social/
  stats/
  auction-house/
  admin-shop/
  tab/
  scoreboard/
  discord/
services/
  web-api/
```

Kotlin sources use the `de.titansmp` package root. Each module has a corresponding package directory under `src/main/kotlin/de/titansmp/`.

## Responsibilities

### Foundation

- **`api`** contains only public, platform-independent interfaces, DTOs, enums, and contracts. It has no Paper, Bukkit, Velocity, MariaDB, or Redis implementation dependencies. Its server package defines `ServerIdentity`, `ServerType`, `ServerEnvironment`, and `NetworkServer`.
- **`core`** contains shared implementations and central services. It owns configuration loading, the MariaDB pool and migration runner, the Redis client, the configured `NetworkServer` implementation, and startup/shutdown lifecycle. Platform-specific code does not belong here.
- **Player identity**: the public DTO and service contract live in `api`; the service and JDBC repository live in `core` and use the shared MariaDB pool.
- **Event messaging**: platform-neutral events and `EventBus` contracts live in `api`; core owns the versioned JSON codec, Redis Pub/Sub transport, dispatch, and listener lifecycle. See [event-bus.md](event-bus.md).

### Platform integrations

- **`paper`** contains Paper-specific implementation and integration code. It may depend on `api` and `core`; the Paper API is confined to this module.
- **`velocity`** contains Velocity-specific implementation and integration code. It may depend on `api` and `core`; the Velocity API is confined to this module. Its runtime shares MariaDB, Flyway, Redis and the event bus with Paper so network moderation can check persisted bans before backend connection.

Platform wiring belongs in these modules. Paper forwards join and quit events asynchronously to the shared player service. Velocity presence writes are deferred until a proxy/backend ownership protocol can prevent stale and duplicate state changes. Feature modules remain separate from Paper and Velocity APIs.

### Feature modules

Each feature module encapsulates its own domain and uses shared infrastructure through `core`. Contracts required by other modules belong in `api`. Feature modules must not create parallel database or Redis systems.

- **`modules/data-bridge`** contains the TitanSMP-native player-state persistence service, JDBC repository, and versioned payload codec. Its platform-independent player/effect/advancement DTOs are in root `api`. Paper snapshot/application, effect lifecycle tracking, criterion progress and Paper-native item/effect conversion are in root `paper` to reuse the existing platform boundary without creating nested Gradle task cycles. Payload v2 adds Potion Effects; v3 adds Advancement criteria; v1/v2 decode with empty advancement lists. No SQL migration or second state table is used. The source audit is design input only; no upstream code was copied.
- The Data Bridge transfer contracts and Redis event payloads live in `api`; the in-memory transfer lifecycle projection lives in `core`; Paper snapshots, locks, loads, and applies in `paper`; Velocity gates backend pre-connects in `velocity`. MariaDB stores only player state, and Redis Pub/Sub coordinates the transient protocol.
- **`modules/ender-chest`** owns the platform-independent TitanSMP EnderChest storage service and JDBC repository. Its public DTO/service contract lives in root `api`; Paper GUI, command, item serialization, and vanilla EnderChest interception live in `paper`. Both entry points use the same UUID-keyed MariaDB row and revision-checked saves. LuckPerms supplies permission checks; it does not define storage identity or persisted rank state.
- **`modules/moderation`** owns the shared moderation service, transactional JDBC repository, duration parser, and LuckPerms permission adapter. Public moderation contracts and Redis event DTOs live in `api`; Flyway owns the punishment and immutable action-history tables. Paper registers moderation commands and blocks muted Minecraft chat; Velocity denies active bans during login and disconnects players for network ban/kick events. Platform code delegates authorization, self-target checks, immunity, and persistence to the shared service.
- **`modules/chat`** owns platform-independent chat validation, the send/broadcast service, UUID-based rate limiting, and bounded duplicate-message detection. Its public `ChatMessage`, source enum, service contract, and Redis event live in `api`. It reuses the existing moderation service and LuckPerms permission adapter. Paper cancels vanilla chat distribution and renders only messages received through the shared Redis EventBus. The sender's display prefix is resolved by the shared `modules/display` service and carried on the message.
- **`modules/display`** owns the shared player display/meta layer. Public `PlayerDisplayMeta`, provider/suffix-provider/service contracts, and the `PlayerDisplayMetaChanged` event live in `api`. The module contains the LuckPerms display adapter (the only display code that reads ranks), the caching service, the single formatter, and the TAB ordering model. Velocity owns the network-wide TAB list; Paper renders nametags with the scoreboard-team API. No PlaceholderAPI or second rank store is used, and no new permissions are introduced.
- **`modules/economy`** owns economy functionality.
- **`modules/social`** owns social functionality.
- **`modules/stats`** owns player and network statistics functionality.
- **`modules/auction-house`** owns auction house functionality.
- **`modules/admin-shop`** owns admin shop functionality.
- **`modules/tab`** owns TAB-related functionality.
- **`modules/scoreboard`** owns scoreboard functionality.
- **`modules/discord`** is a standalone service/process. It is not a Paper plugin and is not loaded by the Paper module.

### Standalone service

- **`services/web-api`** is a standalone service for the existing Next.js website. The website must not connect directly to MariaDB; the Web API is the intended service boundary for accessing TitanSMP services. No transport or service protocol is selected by this scaffolding.

## Dependency direction

Allowed Gradle project dependencies are:

```text
core      -> api
modules/ender-chest -> api, core
modules/data-bridge -> api, core
modules/moderation -> api, core
modules/chat -> api, core
modules/display -> api
paper     -> api, core, modules/ender-chest, modules/data-bridge
paper     -> modules/moderation
paper     -> modules/chat
paper     -> modules/display
velocity  -> api, core, modules/data-bridge, modules/ender-chest, modules/moderation
velocity  -> modules/display
modules/* -> api, core
services/web-api -> (no project dependency declared yet)
```

`api` depends on no TitanSMP module. `core` must not depend on `paper`, `velocity`, feature modules, or services. Feature modules and platform integrations may depend on the foundation, but must not depend on one another by default. If a feature needs to expose a contract, that contract belongs in `api`.

`modules/discord` is a standalone process while retaining the feature-module dependency direction (`api` and `core`). `services/web-api` has no project dependency configured at this stage, keeping its future service calls separate from direct database access. The Data Bridge Paper adapter is hosted by the existing root `paper` project; it does not create a second Paper plugin or dependency cycle. Potion Effects and Advancement criterion progress are part of the existing revisioned state and Velocity transfer flow. Potion Effects use stable registry IDs, duration ticks, flags, infinite duration, hidden effect chains, and a capture timestamp. Advancement data stores namespaced keys with per-criterion completion booleans, not definitions. If restoring a completed advancement would execute rewards, the target progress is deferred while the complete canonical saved state remains in the same payload. There is no feature-specific Redis protocol or separate persistence path. Death and respawn do not remove advancements.

These directions are acyclic: foundation contracts sit at the bottom, shared implementation depends on those contracts, and integrations/features depend on the foundation. Database and Redis connections are implemented only in `core`; feature modules reuse them. See [player-identity.md](player-identity.md), [ender-chest.md](ender-chest.md), [data-bridge.md](data-bridge.md), [moderation.md](moderation.md), [chat.md](chat.md), and [display.md](display.md) for implemented service boundaries. Chat, moderation, and display do not create another connection or event bus.

Player inventory map items use Paper's shared opaque item codec, but their referenced map assets and `MapView` state remain server-managed. The Paper 26.3 API does not expose a complete safe map-content export/import contract, and map IDs are not defined as network-global. TitanSMP does not put map pixels in `PlayerState`, remap IDs, or serialize plugin renderers. See [map-sync.md](map-sync.md) for the API analysis and limitations.

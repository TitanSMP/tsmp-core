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
- **`velocity`** contains Velocity-specific implementation and integration code. It may depend on `api` and `core`; the Velocity API is confined to this module.

Platform wiring belongs in these modules. Paper forwards join and quit events asynchronously to the shared player service. Velocity presence writes are deferred until a proxy/backend ownership protocol can prevent stale and duplicate state changes. Feature modules remain separate from Paper and Velocity APIs.

### Feature modules

Each feature module encapsulates its own domain and uses shared infrastructure through `core`. Contracts required by other modules belong in `api`. Feature modules must not create parallel database or Redis systems.

- **`modules/data-bridge`** contains the TitanSMP-native Phase 1 player-state model persistence service and JDBC repository. Its public contract is in root `api`. Paper snapshot/application, lifecycle wiring, and Paper-native item serialization are in root `paper` to reuse the existing platform boundary without creating nested Gradle task cycles. The source audit is design input only; no upstream code was copied.
- **`modules/ender-chest`** owns the platform-independent TitanSMP EnderChest storage service and JDBC repository. Its public DTO/service contract lives in root `api`; Paper GUI, command, item serialization, and vanilla EnderChest interception live in `paper`. Both entry points use the same UUID-keyed MariaDB row and revision-checked saves. LuckPerms supplies permission checks; it does not define storage identity or persisted rank state.
- **`modules/moderation`** owns moderation-related functionality.
- **`modules/chat`** owns chat functionality.
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
paper     -> api, core, modules/ender-chest, modules/data-bridge
velocity  -> api, core
modules/* -> api, core
services/web-api -> (no project dependency declared yet)
```

`api` depends on no TitanSMP module. `core` must not depend on `paper`, `velocity`, feature modules, or services. Feature modules and platform integrations may depend on the foundation, but must not depend on one another by default. If a feature needs to expose a contract, that contract belongs in `api`.

`modules/discord` is a standalone process while retaining the feature-module dependency direction (`api` and `core`). `services/web-api` has no project dependency configured at this stage, keeping its future service calls separate from direct database access. The Data Bridge Paper adapter is hosted by the existing root `paper` project; it does not create a second Paper plugin or dependency cycle.

These directions are acyclic: foundation contracts sit at the bottom, shared implementation depends on those contracts, and integrations/features depend on the foundation. Database and Redis connections are implemented only in `core`; feature modules reuse them. See [player-identity.md](player-identity.md), [ender-chest.md](ender-chest.md), and [data-bridge.md](data-bridge.md) for the implemented state and storage boundaries. No Phase 2 player-state features are included.

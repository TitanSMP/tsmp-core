# TitanSMP Documentation

This directory contains architecture and infrastructure documentation for the TitanSMP monorepo.

## Architecture

See [architecture.md](architecture.md) for the current module layout, module responsibilities, and allowed dependency directions.

See [infrastructure.md](infrastructure.md) for configuration, MariaDB, Flyway migrations, Redis, server identity, lifecycle, and operations guidance.
See [player-identity.md](player-identity.md) for UUID-based player identity, persistence, platform lifecycle, and concurrency constraints.
See [event-bus.md](event-bus.md) for the Redis Pub/Sub event contract, lifecycle, and threading semantics.
See [data-bridge-audit.md](data-bridge-audit.md) for the source audit of the upstream `mc-data-bridge` archive and the proposed TitanSMP integration plan. The audit is documentation only; no upstream source or dependencies have been integrated.
See [ender-chest.md](ender-chest.md) for the network-wide TitanSMP EnderChest architecture, persistence, access rules, and transfer consistency limits.

The repository is organized into platform-independent contracts (`api`), shared infrastructure (`core`), platform integrations (`paper` and `velocity`), feature modules under `modules/`, and standalone services under `services/`. The first shared domain capability is player identity: its contract is in `api`, persistence and service logic are in `core`, and Paper currently forwards lifecycle updates. The TitanSMP EnderChest is the first implemented feature module; the remaining feature modules are structural placeholders. Velocity presence writes remain deferred until proxy/backend ownership can be coordinated safely.

## Module inventory

### Foundation and platform integrations

- `api`
- `core`
- `paper`
- `velocity`

### Feature modules

- `modules/data-bridge`
- `modules/ender-chest`
- `modules/moderation`
- `modules/chat`
- `modules/economy`
- `modules/social`
- `modules/stats`
- `modules/auction-house`
- `modules/admin-shop`
- `modules/tab`
- `modules/scoreboard`
- `modules/discord` — a standalone service/process, not a Paper plugin

### Services

- `services/web-api` — a standalone service for the existing Next.js website; the website must not access MariaDB directly

The `data-bridge` module is reserved for a future TitanSMP adaptation based on `mc-data-bridge`. No upstream code has been copied or forked into this repository.

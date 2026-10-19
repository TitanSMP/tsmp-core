# TitanSMP Documentation

This directory contains architecture and infrastructure documentation for the TitanSMP monorepo.

## Architecture

See [architecture.md](architecture.md) for the current module layout, module responsibilities, and allowed dependency directions.

See [infrastructure.md](infrastructure.md) for configuration, MariaDB, Flyway migrations, Redis, server identity, lifecycle, and operations guidance.
See [player-identity.md](player-identity.md) for UUID-based player identity, persistence, platform lifecycle, and concurrency constraints.
See [event-bus.md](event-bus.md) for the Redis Pub/Sub event contract, lifecycle, and threading semantics.
See [data-bridge.md](data-bridge.md) for the Phase 1 player-state model, Phase 1.5 transfer protocol, Phase 2 Potion Effect synchronization, and Phase 3 Advancement criterion synchronization, including persistence, lifecycle, concurrency, and limitations.
See [map-sync.md](map-sync.md) for the Paper 26.3 map API analysis, the distinction between inventory map references and server map assets, and the current cross-server synchronization limits.
See [data-bridge-audit.md](data-bridge-audit.md) for the source audit of the upstream `mc-data-bridge` archive and the proposed TitanSMP integration plan. The audit is documentation only; no upstream source or dependencies have been integrated.
See [ender-chest.md](ender-chest.md) for the network-wide TitanSMP EnderChest architecture, persistence, access rules, and transfer consistency limits.
See [moderation.md](moderation.md) for the network-wide moderation service, LuckPerms permissions and immunity, durable punishment history, Redis propagation, Paper commands, and Velocity ban enforcement.

The repository is organized into platform-independent contracts (`api`), shared infrastructure (`core`), platform integrations (`paper` and `velocity`), feature modules under `modules/`, and standalone services under `services/`. Player identity, the independent EnderChest, Data Bridge Phases 1, 1.5, 2, and 3, and the first network-wide moderation system are implemented. Other feature modules remain structural placeholders. MariaDB is the durable source of truth; Redis carries transfer coordination and network events.

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

The Data Bridge is a native TitanSMP implementation informed by the source audit. No upstream source has been copied or forked into this repository. It synchronizes Inventory/Armor/Offhand, Health/Food/Experience, active Potion Effects, and Advancement criterion progress; EnderChest remains isolated in its own module and table. Advancement completion restoration is deferred when Paper would otherwise re-run rewards. Map items remain in the existing serialized inventory; server-managed map content is not currently replicated between independent map registries.

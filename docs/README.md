# TitanSMP Documentation

This directory contains architecture and infrastructure documentation for the TitanSMP monorepo.

## Architecture

See [architecture.md](architecture.md) for the current module layout, module responsibilities, and allowed dependency directions.
See [friends.md](friends.md) for the network-wide friends model, commands, persistence, events, and notifications.
See [clans.md](clans.md) for network-wide clans, roles, invitations, colors, chat, display integration, and concurrency.
See [teleport.md](teleport.md) for network homes, teleport requests, cooldowns, permissions, RTP safety, and transfer integration.
See [auction-house.md](auction-house.md) for network listings, confirmation purchases, item claims, expiry, permissions, and EconomyService integration.

See [infrastructure.md](infrastructure.md) for configuration, MariaDB, Flyway migrations, Redis, server identity, lifecycle, and operations guidance.
See [player-identity.md](player-identity.md) for UUID-based player identity, persistence, platform lifecycle, and concurrency constraints.
See [event-bus.md](event-bus.md) for the Redis Pub/Sub event contract, lifecycle, and threading semantics.
See [data-bridge.md](data-bridge.md) for the Phase 1 player-state model, Phase 1.5 transfer protocol, Phase 2 Potion Effect synchronization, and Phase 3 Advancement criterion synchronization, including persistence, lifecycle, concurrency, and limitations.
See [map-sync.md](map-sync.md) for the Paper 26.3 map API analysis, the distinction between inventory map references and server map assets, and the current cross-server synchronization limits.
See [data-bridge-audit.md](data-bridge-audit.md) for the source audit of the upstream `mc-data-bridge` archive and the proposed TitanSMP integration plan. The audit is documentation only; no upstream source or dependencies have been integrated.
See [ender-chest.md](ender-chest.md) for the network-wide TitanSMP EnderChest architecture, persistence, access rules, and transfer consistency limits.
See [moderation.md](moderation.md) for the network-wide moderation service, LuckPerms permissions and immunity, durable punishment history, Redis propagation, Paper commands, and Velocity ban enforcement.
See [chat.md](chat.md) for the network chat service, message/event model, moderation and permission checks, Paper integration, rate limits, length validation, and duplicate protection.
See [display.md](display.md) for the shared player display/meta layer, LuckPerms-backed prefix and weight resolution, the network-wide Velocity TAB list, Paper nametags, chat prefix integration, and clan-suffix preparation.
See [discord.md](discord.md) for the Discord integration: the dedicated JDA bot runtime, Minecraft/Discord account linking with single-use hashed link codes, the configurable verified role, and the two-way chat bridge between Minecraft and the configured Discord channel. Discord IDs are configuration values and are never hardcoded in source code.
See [economy.md](economy.md) for the network-wide TitanCoins system: exact minor-unit money, LuckPerms permissions, atomic MariaDB transfers and audit rows, Redis balance events, and the Paper commands `/balance`, `/pay`, `/balancetop`, and `/eco`.
See [server-transfer.md](server-transfer.md) for the network-wide server transfer and queue system: configuration-driven server types and backend mapping, `/join`, `/lobby`, `/hub`, native Velocity `/server`, LuckPerms queue priorities, Redis-coordinated reservations, capacity-aware selection, ActionBar positions, and Data Bridge integration.
See [stats.md](stats.md) for the network-wide player statistics system: the ten tracked statistics and their units, configuration-driven per-statistic server-type filtering, network-wide joins, playtime session accounting, local batching and flush strategy, the `player_stats` MariaDB model, and future Web API usage.

The repository is organized into platform-independent contracts (`api`), shared infrastructure (`core`), platform integrations (`paper` and `velocity`), feature modules under `modules/`, and standalone services under `services/`. Player identity, the independent EnderChest, Data Bridge Phases 1, 1.5, 2, and 3, network moderation, network chat, the shared player display/TAB layer, the Discord integration, the single-currency TitanCoins economy, the network server transfer and queue system, and network-wide player statistics are implemented. Other feature modules remain structural placeholders. MariaDB is the durable source of truth; Redis carries transfer coordination, transient server-queue state and reservations, and network events including ephemeral chat messages and display-meta change notifications.

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
- `modules/display`
- `modules/economy` — the single-currency TitanCoins service, transactional JDBC repository, LuckPerms adapter, and Paper command surface; see [economy.md](economy.md)
- `modules/server-transfer` — the platform-independent server transfer service, configuration-driven backend selection, Redis-backed queues/reservations, and the LuckPerms queue-priority adapter; see [server-transfer.md](server-transfer.md)
- `modules/stats` — the network-wide player statistics service, additive JDBC repository, local batching aggregator, and configuration-driven per-statistic server-type filtering; see [stats.md](stats.md)
- `modules/social`
- `modules/auction-house`
- `modules/admin-shop`
- `modules/tab`
- `modules/scoreboard`
- `modules/discord` — the JDA-free Discord domain: linking service, JDBC repository, chat relay, command handler, role listener; a Paper plugin never loads it

### Services

- `services/web-api` — a standalone service for the existing Next.js website; the website must not access MariaDB directly
- `services/discord-bot` — the dedicated Discord bot runtime (JDA 6.7.0); exactly one instance owns the gateway, no Paper server starts a bot

The Data Bridge is a native TitanSMP implementation informed by the source audit. No upstream source has been copied or forked into this repository. It synchronizes Inventory/Armor/Offhand, Health/Food/Experience, active Potion Effects, and Advancement criterion progress; EnderChest remains isolated in its own module and table. Advancement completion restoration is deferred when Paper would otherwise re-run rewards. Map items remain in the existing serialized inventory; server-managed map content is not currently replicated between independent map registries.

See [friends.md](friends.md) for the network-wide friends feature.

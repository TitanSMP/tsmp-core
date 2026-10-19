# TitanSMP Documentation

This directory contains the technical documentation and planning documents for the TitanSMP network platform.

## Current status

The repository currently contains the initial Gradle multi-module foundation:

- `api` — platform-independent public contracts and abstractions.
- `core` — shared implementation and central network logic.
- `paper` — Paper-specific integration.
- `velocity` — Velocity-specific integration.

The project is currently infrastructure-only. Network features have not yet been implemented.

## Planned architecture

TitanSMP is intended to be a modular monorepo rather than one large plugin or a collection of unrelated repositories. Shared infrastructure should live in the core/API layers, while larger feature areas are isolated into modules that can produce their own artifacts where appropriate.

Planned infrastructure:

- Paper
- Velocity
- MariaDB as the persistent source of truth
- Redis for fast cross-server communication, events and caching where appropriate
- LuckPerms for permissions and rank management
- A network-wide player/data synchronization module based on and substantially adapted from `mc-data-bridge`

Planned network features include:

- Network-wide chat
- Cross-server player data/inventory/ender chest synchronization
- TAB and optionally scoreboards
- Network-wide moderation (`ban`, `unban`, `mute`, `unmute`, `kick`, `invsee`, `endersee`)
- Economy and `/pay`
- Auction House
- Admin Shop
- Friends
- Clans
- Player statistics, playtime and advancements
- Web API for the existing Next.js website
- Discord bot and Minecraft/Discord account linking
- Minecraft ↔ Discord chat integration

The Discord bot is planned as part of this repository and should be implemented as a standalone service/module rather than as a Paper plugin.

## Database strategy

TitanSMP should use one MariaDB database for TitanSMP-owned persistent data, logically separated by tables and repositories per domain. A separate MariaDB database for LuckPerms is recommended rather than sharing TitanSMP's tables with LuckPerms.

LuckPerms remains the authoritative system for permissions and groups. TitanSMP may integrate with LuckPerms to read and manage ranks, but should not maintain a second independent rank database.

## Documentation roadmap

Additional documents should be added here as the architecture is finalized, especially:

- module architecture
- database schema
- Redis/event architecture
- permissions and LuckPerms integration
- player data synchronization
- moderation architecture
- web API specification
- Discord integration
- economy architecture
- deployment and configuration

This documentation is intentionally kept separate from implementation details so that architectural decisions can be reviewed before large amounts of code are written.

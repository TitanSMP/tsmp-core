# TitanSMP Documentation

This directory contains architecture and planning documents for the TitanSMP monorepo.

## Architecture

See [architecture.md](architecture.md) for the current module layout, module responsibilities, and allowed dependency directions.

The repository is organized into platform-independent contracts (`api`), shared infrastructure (`core`), platform integrations (`paper` and `velocity`), feature modules under `modules/`, and standalone services under `services/`. The modules currently provide build and source-set structure only; feature implementations are intentionally not included.

## Module inventory

### Foundation and platform integrations

- `api`
- `core`
- `paper`
- `velocity`

### Feature modules

- `modules/data-bridge`
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

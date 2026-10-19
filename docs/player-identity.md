# Player Identity

This first domain service stores only network player identity and current presence. It does not implement a profile, permissions, economy, moderation, social data, or statistics.

## Identity and API

The Minecraft UUID is the sole stable identity. `NetworkPlayer` in `api` carries the UUID, current username, `firstJoin`, `lastSeen`, nullable `currentServer`, and `online`. Names can change and are only a case-insensitive lookup key. The API's `PlayerService` supports UUID lookup, username lookup, create-or-load, last-seen updates, and presence updates. Missing records use nullable `find` methods; `getPlayer` throws `PlayerNotFoundException`.

## Persistence

The core `PlayerRepository` contains persistence operations only. `JdbcPlayerRepository` uses the shared `DatabaseService` data source. UUID values are stored as 16-byte `BINARY(16)` values and form the primary key. Usernames are indexed but not unique, so a rename cannot change identity or collide with a former player's name. `first_join` is insert-only; `createOrLoad` atomically updates the current username and last-seen value without resetting presence.

`first_join` and `last_seen` use `DATETIME(3)` values interpreted explicitly as UTC on both write and read. The table contains no feature-specific fields.

## Server lifecycle

Paper forwards `PlayerJoinEvent` and `PlayerQuitEvent` using UUIDs. Database calls run on a single worker executor and do not block the Paper event thread. On join, the service creates/loads the identity and marks it online using the configured `ServerIdentity.id` (for example, `smp-01`). A quit clears the online state only if the stored server id still matches that Paper server, preventing a late disconnect from an old backend from marking a player on a new server offline.

Velocity has connection lifecycle events, but this repository does not yet define a proxy/backend ownership handshake. Having both proxy and backend independently write presence could cause duplicate or out-of-order state changes. Therefore Velocity can use the API, but its presence writes are deferred until a shared transition protocol is designed. That protocol must define authoritative ownership and ordering/token semantics for proxy connect, backend handoff, and disconnect.

## Concurrency and future events

There is no local player cache or distributed lock. UUID-keyed upserts and conditional server-owner disconnects are atomic database operations. The conditional disconnect protects a common transfer race; it does not solve all competing connect transitions across multiple proxies or backend event reorderings. A future Redis transport can publish `PlayerConnected`, `PlayerDisconnected`, and `PlayerServerChanged` after authoritative transitions. No event bus is implemented here.

## Tests

Player persistence tests use a disposable MariaDB Testcontainer and Flyway migrations. They do not use a configured or production database. The tests are skipped when a local Docker runtime is unavailable.

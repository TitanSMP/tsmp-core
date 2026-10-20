# Network-Wide Player Statistics

## Scope

The statistics feature captures ten network-wide player statistics, persists them in MariaDB, and
exposes a platform-independent API prepared for the future Web API. It reuses the existing shared
MariaDB pool, the existing Flyway migration pipeline, the existing Redis EventBus, and the existing
Player-Identity presence lifecycle. It creates no second database, no second connection pool, and no
second event bus.

It intentionally does **not** implement `/stats`, `/statstop`, or any player-facing command, and it
adds no HTTP/Web dependency. The Web API is a later, separate service that calls `StatsService`
directly on the classpath.

## Tracked statistics and units

| Statistic | Unit | Meaning |
| --- | --- | --- |
| `playtime` | whole seconds | Time on servers that count playtime |
| `joins` | count | Network-wide joins (see below) |
| `deaths` | count | Deaths of the player |
| `blocks_mined` | count | Successfully broken blocks |
| `blocks_walked` | whole blocks | Horizontal ground distance |
| `blocks_elytra` | whole blocks | Horizontal distance while gliding |
| `damage_taken` | whole health points | 1.0 = one half-heart |
| `damage_dealt` | whole health points | 1.0 = one half-heart |
| `mobs_killed` | count | Non-player kills by the player |
| `players_killed` | count | Player kills by the player |

`StatType` is the stable identity of a statistic; the persisted column name is mapped explicitly by
`StatColumns` so a renamed constant can never silently move data. Adding a future statistic appends a
column and a `StatType` entry without rewriting existing rows.

## Deliberate boundaries

- **Server changes are not a statistic.** A server switch is a routing event that belongs to the
  future logging/audit system. It is never counted here, and no existing transfer or presence event
  is artificially persisted as a statistic. There is no `server_changes` column or field.
- **Economy balance is not a statistic.** The TitanCoins balance is owned exclusively by
  `modules:economy` and lives in `economy_balances`. Statistics never store or duplicate a balance,
  so there is exactly one source of truth. The future Web API can read `StatsService` and
  `EconomyService` separately.

## Configuration

Tracking is entirely configuration-driven; production code contains no server name and no server-type
literal. The values shown below (and in `config/titansmp.example.properties`) are examples only.

```properties
stats.enabled=false
stats.flush-interval-ms=5000

# Network-wide: counted on every server, once per real network join.
stats.tracking.joins.enabled=true
stats.tracking.joins.scope=network

# Collected only on the configured server identity types.
stats.tracking.playtime.enabled=true
stats.tracking.playtime.scope=server-types
stats.tracking.playtime.server-types=smp
# ... one block per statistic ...
```

- `stats.enabled` turns the whole subsystem on or off. When it is off, Paper registers no listeners.
- `stats.flush-interval-ms` controls how often batched local counters are written to MariaDB.
- `stats.tracking.<statistic>.enabled` enables or disables one statistic.
- `stats.tracking.<statistic>.scope` is `network` (every server) or `server-types` (only the listed
  server identity types).
- `stats.tracking.<statistic>.server-types` is a comma-separated allow-list matched
  case-insensitively against the server's configured `server.type` (`ServerIdentity.type`).

The loader validates configuration at startup: an unknown statistic name, an invalid scope, a
non-boolean value, an enabled `server-types` statistic without any type, and an unknown server type
are all rejected as configuration errors, so a typo cannot silently drop statistics.

### Server-type behavior

A statistic with `scope=server-types` is collected only when the server's configured identity type is
in the allow-list. All other statistics on that server contribute nothing for that statistic; a
server of another type simply does not register the corresponding accounting. `joins` uses
`scope=network` and is therefore collected on every server type.

## Join behavior

`joins` counts **network-wide joins only**. It is derived from the existing `PlayerConnected` presence
event, which is published only on a genuine network entry (Velocity -> PlayerIdentity -> network
join), and never from `PlayerServerChanged`, which is published on intra-network transfers. A
`Lobby -> SMP -> Lobby -> SMP` journey therefore counts as **one** join.

Because every server receives `PlayerConnected` over the shared EventBus, only the server whose ID
matches the event's `serverId` records the join. That guarantees exactly-once counting without a
second join mechanism.

## Playtime behavior

Playtime is measured per local session:

- a Paper join starts a session and a Paper quit converts the elapsed time to whole seconds;
- only servers whose identity type is listed for `playtime` count time; other servers contribute `0`;
- a network transfer always fires a quit on the source server before the join on the target server, so
  every second is attributed to exactly one session and nothing is counted twice;
- a server shutdown drains every open session, so an abrupt stop loses as little as is technically
  sensible.

Playtime is stored as a `BIGINT` of seconds, never as `double` or `float`.

## Movement, combat, and block accounting

- **Blocks mined** are counted on the Paper `BlockBreakEvent`, which fires only for an actual
  successful break, not for mere interaction.
- **Damage taken / dealt** are read from Paper's final damage in half-hearts and stored in whole
  health points.
- **Mob vs. player kills** are strictly separated: a player death increments `players_killed` for the
  killer, and the same death is never also counted as a mob kill.
- **Movement** uses the horizontal X/Z distance between the two positions of a `PlayerMoveEvent`.
  Distance is accumulated as a `double` and rounded to whole blocks only at flush time, so no
  sub-block distance is lost. Elytra distance uses the same mechanism while the player is gliding.

## Performance strategy

No block, step, or hit triggers its own MariaDB write.

1. Paper listeners run on the server thread and only increment in-memory counters
   (`StatsAggregator` for counts, `FractionalAccumulator` for distance and damage).
2. A background flush task runs on the configured interval, drains the counters, and calls
   `StatsService.incrementAll` once per player.
3. `DefaultStatsService` executes the database work on a dedicated background executor, never on the
   Paper main thread.

Statistics are monotonic: a negative delta is rejected before it reaches the database, so no
statistic can ever decrease. Draining resets a counter atomically, so a concurrent record during a
flush is not lost and is picked up by the next flush.

## Database model

Flyway migration `V7__create_player_stats.sql` creates one row per player:

```sql
CREATE TABLE player_stats (
    player_uuid BINARY(16) NOT NULL,
    playtime BIGINT NOT NULL DEFAULT 0,
    joins BIGINT NOT NULL DEFAULT 0,
    deaths BIGINT NOT NULL DEFAULT 0,
    blocks_mined BIGINT NOT NULL DEFAULT 0,
    blocks_walked BIGINT NOT NULL DEFAULT 0,
    blocks_elytra BIGINT NOT NULL DEFAULT 0,
    damage_taken BIGINT NOT NULL DEFAULT 0,
    damage_dealt BIGINT NOT NULL DEFAULT 0,
    mobs_killed BIGINT NOT NULL DEFAULT 0,
    players_killed BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (player_uuid),
    -- one descending index per statistic for leaderboard reads
    ...
);
```

The UUID is the only identity; there is no player name used as a key or foreign key. A `username` is
resolved for leaderboard display from the shared `players` table and is never identity. The migration
is bundled into the Paper plugin, the Velocity proxy, and the Discord bot runtime so every runtime
that starts `TitanSmpRuntime` sees the same schema.

### Cross-server consistency

Every mutation is a single atomic upsert:

```sql
INSERT INTO player_stats (player_uuid, <columns>, created_at, updated_at)
VALUES (?, <deltas>, ?, ?)
ON DUPLICATE KEY UPDATE <column> = <column> + VALUES(<column>), ..., updated_at = VALUES(updated_at)
```

Because the addition happens inside MariaDB, concurrent increments from different network servers can
never lose data. No `SELECT value` + `UPDATE value` pattern is used, so there is no lost-update race.

## Redis usage

MariaDB is the durable source of truth. Statistics are never synchronized per increment over Redis.
After an aggregated flush commits, `DefaultStatsService` publishes a single `stats.player_updated`
event through the existing EventBus. The event carries absolute totals, so it is idempotently
processable and useful for cache invalidation. A publish failure is logged and never rolls back or
rewrites the committed statistics.

## Public API

The contracts live in `api` and have no Paper, Bukkit, Velocity, MariaDB, Redis, or LuckPerms
dependency (guarded by a bytecode boundary test):

```kotlin
interface StatsService {
    fun increment(playerUuid: UUID, statType: StatType, delta: Long): CompletionStage<PlayerStats>
    fun incrementAll(playerUuid: UUID, deltas: Map<StatType, Long>): CompletionStage<PlayerStats>
    fun getStats(playerUuid: UUID): CompletionStage<PlayerStats>
    fun getStats(playerUuid: UUID, statTypes: Collection<StatType>): CompletionStage<PlayerStats>
    fun getTopPlayers(statType: StatType, limit: Int = 10): CompletionStage<List<StatsLeaderboardEntry>>
}
```

Leaderboards use a database-side `ORDER BY value DESC, player_uuid ASC LIMIT`, so the ordering is
deterministic and the read is bounded even on large tables. `getTopPlayers` clamps `limit` to
`1..MAX_TOP_LIMIT` (100).

## Future Web API usage

The Web API will call `StatsService` (and `EconomyService` separately) on the classpath:

```kotlin
val stats = statsService.getStats(playerUuid).await()
val topPlaytime = statsService.getTopPlayers(StatType.PLAYTIME, 10).await()
```

No statistics HTTP endpoint is implemented in this phase, and `modules:stats` has no Web dependency.

## Tests

- `StatsApiBoundaryTest` guards that the public contracts reference no platform, database, or
  infrastructure type.
- `DefaultStatsServiceTest` covers increments, additive batches, concurrent increments without lost
  updates, rejection of negative deltas, empty and filtered snapshots, leaderboard ordering and
  clamping, and the aggregated event.
- `StatsConfigurationTest` covers enabled/disabled statistics, server-type filtering, `smp` vs
  `lobby` example types, network-wide joins, unknown server types, invalid scopes, and invalid
  booleans.
- `StatsAggregatorTest`, `FractionalAccumulatorTest`, and `PlaytimeTrackerTest` cover local batching,
  sub-unit rounding, `SMP -> counted`, `Lobby -> not counted`, `SMP -> Lobby -> SMP` without double
  counting, and shutdown draining.
- `StatsPaperModuleTest` covers block mining, damage taken/dealt, mob kills, player kills, deaths,
  movement, elytra, network join counting, lobby filtering, and the absence of per-event database
  writes.
- `JdbcStatsRepositoryIntegrationTest` (requires Docker) verifies the V7 migration and atomic,
  race-safe increments against a real MariaDB, including parallel increments and leaderboard ordering.

## Limitations

- Statistics are best-effort counters. A process crash can lose the deltas accumulated since the last
  successful flush; nothing is written per event by design.
- The playtime session boundary is a Paper join/quit. A hard crash without a quit loses that open
  session's seconds; a normal shutdown drains them.
- `mobs_killed` counts any non-player entity killed by a player, with no mob-type granularity.
- Movement uses horizontal distance only; vertical movement is not counted.

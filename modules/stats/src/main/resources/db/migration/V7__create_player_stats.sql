-- Network-wide TitanSMP player statistics. V7 follows the economy migration V6 and the earlier
-- core, Discord, and economy migrations. Flyway scans classpath:db/migration on every runtime that
-- starts TitanSmpRuntime, so this file is bundled into the Paper plugin, the Velocity proxy, and the
-- Discord bot runtime.

-- player_stats stores one monotonic, network-wide row per player. UUID is the only identity; the
-- mutable username stays in the shared players table and is never used as a key here.
--
-- All values are non-negative whole numbers. Playtime is stored in seconds, blocks_walked and
-- blocks_elytra in blocks, and damage_taken/damage_dealt in whole health points (1.0 = one half
-- heart). Every mutation is an atomic `value = value + ?` upsert, so concurrent increments from
-- different servers can never lose data.
--
-- Server switches and economy balances are deliberately NOT columns here. Server changes belong to
-- the future logging/audit system, and the balance is owned exclusively by economy_balances.
CREATE TABLE player_stats (
    player_uuid BINARY(16) NOT NULL,
    playtime BIGINT NOT NULL DEFAULT 0 COMMENT 'seconds',
    joins BIGINT NOT NULL DEFAULT 0,
    deaths BIGINT NOT NULL DEFAULT 0,
    blocks_mined BIGINT NOT NULL DEFAULT 0,
    blocks_walked BIGINT NOT NULL DEFAULT 0 COMMENT 'blocks',
    blocks_elytra BIGINT NOT NULL DEFAULT 0 COMMENT 'blocks',
    damage_taken BIGINT NOT NULL DEFAULT 0 COMMENT 'whole health points',
    damage_dealt BIGINT NOT NULL DEFAULT 0 COMMENT 'whole health points',
    mobs_killed BIGINT NOT NULL DEFAULT 0,
    players_killed BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL COMMENT 'UTC',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (player_uuid),
    -- Deterministic leaderboard ordering: value DESC, player_uuid ASC (one index per statistic).
    KEY idx_player_stats_playtime (playtime DESC, player_uuid ASC),
    KEY idx_player_stats_joins (joins DESC, player_uuid ASC),
    KEY idx_player_stats_deaths (deaths DESC, player_uuid ASC),
    KEY idx_player_stats_blocks_mined (blocks_mined DESC, player_uuid ASC),
    KEY idx_player_stats_blocks_walked (blocks_walked DESC, player_uuid ASC),
    KEY idx_player_stats_blocks_elytra (blocks_elytra DESC, player_uuid ASC),
    KEY idx_player_stats_damage_taken (damage_taken DESC, player_uuid ASC),
    KEY idx_player_stats_damage_dealt (damage_dealt DESC, player_uuid ASC),
    KEY idx_player_stats_mobs_killed (mobs_killed DESC, player_uuid ASC),
    KEY idx_player_stats_players_killed (players_killed DESC, player_uuid ASC),
    CONSTRAINT fk_player_stats_player
        FOREIGN KEY (player_uuid) REFERENCES players (uuid) ON DELETE CASCADE
);

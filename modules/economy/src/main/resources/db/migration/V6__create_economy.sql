-- Network-wide TitanSMP economy. V6 follows core migrations V1-V4 and the Discord migration V5.
-- Flyway scans classpath:db/migration on every runtime that starts TitanSmpRuntime, so this file is
-- bundled into the Paper plugin, the Velocity proxy, and the Discord bot runtime.

-- economy_balances is the authoritative current state of every wallet with a non-zero history.
-- One TitanCoin is 100 minor units: balance 1000 means 10.00 TitanCoins.
-- player_uuid is the only identity. Usernames are mutable and live in the shared players table.
CREATE TABLE economy_balances (
    player_uuid BINARY(16) NOT NULL,
    balance BIGINT NOT NULL COMMENT 'minor units; 100 = 1.00 TitanCoins',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (player_uuid),
    -- Matches /balancetop ORDER BY balance DESC, player_uuid ASC exactly.
    KEY idx_economy_balances_top (balance DESC, player_uuid ASC),
    CONSTRAINT fk_economy_balances_player
        FOREIGN KEY (player_uuid) REFERENCES players (uuid) ON DELETE CASCADE,
    CONSTRAINT chk_economy_balances_non_negative CHECK (balance >= 0)
);

-- Append-only audit trail written in the same transaction as the balance update.
-- transaction_id identifies one money movement. A /pay writes exactly two rows that share the
-- transaction id and point at each other through counterparty_uuid, so both sides stay correlatable.
-- player_uuid is the side described by the row.
--
-- There is deliberately no foreign key on this table (including player_uuid): audit history must
-- survive identity cleanup. There is intentionally no CHECK constraint on type so future reasons
-- such as AUCTION_HOUSE can be recorded without a schema migration.
CREATE TABLE economy_transactions (
    transaction_id BINARY(16) NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    counterparty_uuid BINARY(16) NULL,
    type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount BIGINT NOT NULL COMMENT 'minor units; requested or resulting amount, always >= 0',
    balance_before BIGINT NOT NULL COMMENT 'minor units',
    balance_after BIGINT NOT NULL COMMENT 'minor units',
    source_server_id VARCHAR(64) NOT NULL,
    actor_uuid BINARY(16) NULL COMMENT 'admin actor UUID; NULL for player/system actions',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (transaction_id, player_uuid),
    KEY idx_economy_transactions_player_time (player_uuid, created_at),
    KEY idx_economy_transactions_counterparty_time (counterparty_uuid, created_at),
    KEY idx_economy_transactions_actor_time (actor_uuid, created_at),
    CONSTRAINT chk_economy_transactions_amount CHECK (amount >= 0),
    CONSTRAINT chk_economy_transactions_before CHECK (balance_before >= 0),
    CONSTRAINT chk_economy_transactions_after CHECK (balance_after >= 0)
);

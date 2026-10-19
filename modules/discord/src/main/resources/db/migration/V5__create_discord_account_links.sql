-- Discord account linking: one Minecraft account <-> one Discord account, plus single-use link tokens.
-- Version V5 follows the core migrations V1-V4; Flyway scans classpath:db/migration on every runtime.

CREATE TABLE discord_account_links (
    player_uuid BINARY(16) NOT NULL,
    player_name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    discord_user_id BIGINT NOT NULL,
    linked_at DATETIME(3) NOT NULL COMMENT 'UTC',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (player_uuid),
    UNIQUE KEY uq_discord_account_links_discord_user (discord_user_id),
    CONSTRAINT fk_discord_account_links_player
        FOREIGN KEY (player_uuid) REFERENCES players (uuid)
        ON DELETE CASCADE
);

-- Only a SHA-256 hash of the normalized link code is stored; the plaintext code is never persisted.
-- used_at IS NULL marks an unused token; a single UPDATE ... WHERE used_at IS NULL makes redemption
-- atomic so a code can be consumed at most once.
CREATE TABLE discord_link_tokens (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    player_name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    created_at DATETIME(3) NOT NULL COMMENT 'UTC',
    expires_at DATETIME(3) NOT NULL COMMENT 'UTC',
    used_at DATETIME(3) NULL COMMENT 'UTC',
    PRIMARY KEY (token_hash),
    KEY idx_discord_link_tokens_player (player_uuid),
    CONSTRAINT fk_discord_link_tokens_player
        FOREIGN KEY (player_uuid) REFERENCES players (uuid)
        ON DELETE CASCADE
);

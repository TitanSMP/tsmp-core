CREATE TABLE player_state (
    player_uuid BINARY(16) NOT NULL,
    state_payload LONGBLOB NOT NULL,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_uuid),
    CONSTRAINT fk_player_state_player FOREIGN KEY (player_uuid) REFERENCES players (uuid) ON DELETE CASCADE
);

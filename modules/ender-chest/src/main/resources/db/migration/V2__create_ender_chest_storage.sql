CREATE TABLE ender_chest_storage (
    player_uuid BINARY(16) NOT NULL,
    contents LONGBLOB NOT NULL,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_uuid),
    CONSTRAINT fk_ender_chest_storage_player
        FOREIGN KEY (player_uuid) REFERENCES players (uuid)
        ON DELETE CASCADE
);

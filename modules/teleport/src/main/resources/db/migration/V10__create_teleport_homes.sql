CREATE TABLE player_homes (
    player_uuid BINARY(16) NOT NULL,
    normalized_name VARCHAR(32) NOT NULL,
    display_name VARCHAR(32) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    world VARCHAR(128) NOT NULL,
    x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL,
    yaw FLOAT NOT NULL, pitch FLOAT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (player_uuid, normalized_name),
    CONSTRAINT fk_player_homes_player FOREIGN KEY (player_uuid) REFERENCES players(uuid) ON DELETE CASCADE
);

CREATE TABLE clan_homes (
    clan_uuid BINARY(16) NOT NULL,
    normalized_name VARCHAR(32) NOT NULL,
    display_name VARCHAR(32) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    world VARCHAR(128) NOT NULL,
    x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL,
    yaw FLOAT NOT NULL, pitch FLOAT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (clan_uuid, normalized_name),
    CONSTRAINT fk_clan_homes_clan FOREIGN KEY (clan_uuid) REFERENCES clans(clan_uuid) ON DELETE CASCADE
);

CREATE TABLE teleport_requests (
    request_id BINARY(16) PRIMARY KEY,
    requester_uuid BINARY(16) NOT NULL,
    target_uuid BINARY(16) NOT NULL,
    requester_name VARCHAR(16) NOT NULL,
    direction VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    INDEX ix_teleport_requests_target (target_uuid, created_at),
    UNIQUE KEY uq_teleport_pending_pair (requester_uuid, target_uuid),
    CONSTRAINT fk_teleport_requester FOREIGN KEY (requester_uuid) REFERENCES players(uuid) ON DELETE CASCADE,
    CONSTRAINT fk_teleport_target FOREIGN KEY (target_uuid) REFERENCES players(uuid) ON DELETE CASCADE
);

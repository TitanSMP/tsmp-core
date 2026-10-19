CREATE TABLE moderation_punishments (
    id BINARY(16) NOT NULL,
    target_uuid BINARY(16) NOT NULL,
    target_name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    reason VARCHAR(512) NOT NULL,
    created_at DATETIME(3) NOT NULL COMMENT 'UTC',
    expires_at DATETIME(3) NULL COMMENT 'UTC; NULL means permanent',
    actor_uuid BINARY(16) NULL,
    actor_name VARCHAR(64) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_moderation_target_type (target_uuid, type),
    KEY idx_moderation_active_lookup (target_uuid, type, expires_at),
    CONSTRAINT fk_moderation_punishment_player FOREIGN KEY (target_uuid) REFERENCES players (uuid) ON DELETE CASCADE,
    CONSTRAINT chk_moderation_punishment_type CHECK (type IN ('BAN', 'MUTE'))
);

CREATE TABLE moderation_actions (
    id BINARY(16) NOT NULL,
    target_uuid BINARY(16) NOT NULL,
    target_name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    actor_uuid BINARY(16) NULL,
    actor_name VARCHAR(64) NOT NULL,
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    reason VARCHAR(512) NULL,
    created_at DATETIME(3) NOT NULL COMMENT 'UTC',
    expires_at DATETIME(3) NULL COMMENT 'UTC',
    server_id VARCHAR(64) NOT NULL,
    metadata JSON NULL,
    PRIMARY KEY (id),
    KEY idx_moderation_actions_target_time (target_uuid, created_at),
    KEY idx_moderation_actions_actor_time (actor_uuid, created_at),
    CONSTRAINT chk_moderation_action CHECK (action IN ('BAN', 'UNBAN', 'MUTE', 'UNMUTE', 'KICK'))
);

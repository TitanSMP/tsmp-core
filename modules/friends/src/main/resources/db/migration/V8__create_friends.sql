CREATE TABLE friend_pair_locks (
    player_low BINARY(16) NOT NULL,
    player_high BINARY(16) NOT NULL,
    PRIMARY KEY (player_low, player_high)
) ENGINE=InnoDB;

CREATE TABLE friendships (
    player_low BINARY(16) NOT NULL,
    player_high BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (player_low, player_high),
    CONSTRAINT chk_friendships_order CHECK (player_low < player_high)
) ENGINE=InnoDB;

CREATE TABLE friend_requests (
    requester_uuid BINARY(16) NOT NULL,
    target_uuid BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (requester_uuid, target_uuid),
    INDEX idx_friend_requests_target (target_uuid, created_at DESC),
    CONSTRAINT chk_friend_request_self CHECK (requester_uuid <> target_uuid)
) ENGINE=InnoDB;

CREATE TABLE friend_blocks (
    blocker_uuid BINARY(16) NOT NULL,
    blocked_uuid BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (blocker_uuid, blocked_uuid),
    CONSTRAINT chk_friend_block_self CHECK (blocker_uuid <> blocked_uuid)
) ENGINE=InnoDB;

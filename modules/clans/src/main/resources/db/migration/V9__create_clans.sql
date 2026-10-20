CREATE TABLE clan_player_locks (
    player_uuid BINARY(16) NOT NULL,
    PRIMARY KEY (player_uuid),
    CONSTRAINT fk_clan_lock_player FOREIGN KEY (player_uuid) REFERENCES players(uuid) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE clans (
    clan_uuid BINARY(16) NOT NULL,
    name VARCHAR(24) NOT NULL,
    normalized_name VARCHAR(24) NOT NULL,
    tag VARCHAR(8) NOT NULL,
    normalized_tag VARCHAR(8) NOT NULL,
    color_mode VARCHAR(16) NOT NULL DEFAULT 'STANDARD',
    color_value VARCHAR(16) NOT NULL DEFAULT 'white',
    rainbow_base_color VARCHAR(16) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (clan_uuid),
    UNIQUE KEY uq_clans_normalized_name (normalized_name),
    UNIQUE KEY uq_clans_normalized_tag (normalized_tag),
    CONSTRAINT chk_clan_color_mode CHECK (color_mode IN ('STANDARD', 'RAINBOW'))
) ENGINE=InnoDB;

CREATE TABLE clan_members (
    player_uuid BINARY(16) NOT NULL,
    clan_uuid BINARY(16) NOT NULL,
    role VARCHAR(16) NOT NULL,
    joined_at DATETIME(3) NOT NULL,
    PRIMARY KEY (player_uuid),
    KEY idx_clan_members_clan (clan_uuid, joined_at),
    CONSTRAINT fk_clan_member_clan FOREIGN KEY (clan_uuid) REFERENCES clans(clan_uuid) ON DELETE CASCADE,
    CONSTRAINT fk_clan_member_player FOREIGN KEY (player_uuid) REFERENCES players(uuid) ON DELETE CASCADE,
    CONSTRAINT chk_clan_member_role CHECK (role IN ('MEMBER', 'MODERATOR', 'ADMIN'))
) ENGINE=InnoDB;

CREATE TABLE clan_invitations (
    clan_uuid BINARY(16) NOT NULL,
    player_uuid BINARY(16) NOT NULL,
    inviter_uuid BINARY(16) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (clan_uuid, player_uuid),
    KEY idx_clan_invitations_player (player_uuid, expires_at),
    CONSTRAINT fk_clan_invitation_clan FOREIGN KEY (clan_uuid) REFERENCES clans(clan_uuid) ON DELETE CASCADE,
    CONSTRAINT fk_clan_invitation_player FOREIGN KEY (player_uuid) REFERENCES players(uuid) ON DELETE CASCADE,
    CONSTRAINT fk_clan_invitation_inviter FOREIGN KEY (inviter_uuid) REFERENCES players(uuid) ON DELETE CASCADE
) ENGINE=InnoDB;

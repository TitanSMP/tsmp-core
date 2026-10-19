CREATE TABLE players (
    uuid BINARY(16) NOT NULL,
    username VARCHAR(16) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    first_join DATETIME(3) NOT NULL COMMENT 'UTC',
    last_seen DATETIME(3) NOT NULL COMMENT 'UTC',
    current_server VARCHAR(64) NULL,
    online BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (uuid),
    KEY idx_players_username (username),
    KEY idx_players_online_server (online, current_server)
);

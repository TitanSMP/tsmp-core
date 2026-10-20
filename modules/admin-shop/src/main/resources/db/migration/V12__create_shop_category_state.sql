CREATE TABLE shop_category_state (
    category_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by BINARY(16) NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (category_id)
);

CREATE TABLE shop_category_state_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    category_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    old_enabled BOOLEAN NOT NULL,
    new_enabled BOOLEAN NOT NULL,
    updated_by BINARY(16) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_shop_category_audit (category_id, updated_at)
);

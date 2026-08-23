-- Hot-path shared-state tables for PostgreSQL backend

CREATE TABLE IF NOT EXISTS client_rate_limit (
    client_id   VARCHAR(128)  NOT NULL,
    window_key  VARCHAR(64)   NOT NULL,
    cnt         BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (client_id, window_key)
);

CREATE TABLE IF NOT EXISTS client_tpm_usage (
    client_id   VARCHAR(128)  NOT NULL,
    minute_key  VARCHAR(64)   NOT NULL,
    tokens      BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (client_id, minute_key)
);

CREATE TABLE IF NOT EXISTS client_usage (
    client_id    VARCHAR(128)  NOT NULL,
    period_key   VARCHAR(64)   NOT NULL,
    tokens       BIGINT        NOT NULL DEFAULT 0,
    request_cnt  BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (client_id, period_key)
);

CREATE TABLE IF NOT EXISTS client_cost (
    client_id    VARCHAR(128)  NOT NULL,
    period_key   VARCHAR(64)   NOT NULL,
    cost_micros  BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (client_id, period_key)
);

CREATE TABLE IF NOT EXISTS route_state (
    route_id          VARCHAR(128)  NOT NULL PRIMARY KEY,
    open_until_ms     BIGINT,
    failure_ts_json   TEXT          NOT NULL DEFAULT '[]'
);

CREATE TABLE IF NOT EXISTS provider_runtime (
    provider    VARCHAR(128)  NOT NULL PRIMARY KEY,
    state_json  TEXT          NOT NULL DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS aggregate_metric (
    dimension_type  VARCHAR(64)   NOT NULL,
    dimension_key   VARCHAR(256)  NOT NULL,
    bucket          VARCHAR(32)   NOT NULL,
    requests        BIGINT        NOT NULL DEFAULT 0,
    tokens          BIGINT        NOT NULL DEFAULT 0,
    cost_micros     BIGINT        NOT NULL DEFAULT 0,
    display_name    VARCHAR(256),
    PRIMARY KEY (dimension_type, dimension_key, bucket)
);

CREATE TABLE IF NOT EXISTS config_kv (
    config_type VARCHAR(64)   NOT NULL,
    key         VARCHAR(256)  NOT NULL,
    value_json  TEXT          NOT NULL,
    PRIMARY KEY (config_type, key)
);

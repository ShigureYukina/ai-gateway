-- V8: Webhook delivery tables
-- Supports outbound webhook delivery with HMAC signing and retry tracking.

-- ============================================================
-- 1. webhook_endpoint — registered webhook target URLs
-- ============================================================
CREATE TABLE IF NOT EXISTS webhook_endpoint (
    id              BIGSERIAL    PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    url             VARCHAR(512) NOT NULL,
    secret          VARCHAR(256),
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    event_types     JSONB        DEFAULT '["*"]',
    retry_max       INT          NOT NULL DEFAULT 3,
    timeout_ms      INT          NOT NULL DEFAULT 5000,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================
-- 2. webhook_delivery_log — delivery attempt audit trail
-- ============================================================
CREATE TABLE IF NOT EXISTS webhook_delivery_log (
    id              BIGSERIAL    PRIMARY KEY,
    endpoint_id     BIGINT       NOT NULL REFERENCES webhook_endpoint(id) ON DELETE CASCADE,
    event_type      VARCHAR(64)  NOT NULL,
    event_id        VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending',
    http_status     INT,
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      TEXT,
    payload         TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_wdl_endpoint ON webhook_delivery_log(endpoint_id);
CREATE INDEX IF NOT EXISTS idx_wdl_status   ON webhook_delivery_log(status);

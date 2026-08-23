-- V7: Governance domain tables
-- These tables formalize the gateway's governance objects. Hot-path state
-- (rate limits, usage counters, resilience windows) remains in Redis/InMemory.

-- ============================================================
-- 1. employee — gateway employees/operators (extends admin_user concept)
-- ============================================================
CREATE TABLE IF NOT EXISTS employee (
    id              BIGSERIAL    PRIMARY KEY,
    username        VARCHAR(128) NOT NULL UNIQUE,
    display_name    VARCHAR(256),
    email           VARCHAR(256),
    department      VARCHAR(128),
    role            VARCHAR(32)  NOT NULL DEFAULT 'operator',
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    password_hash   VARCHAR(128) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_employee_status ON employee(status);
CREATE INDEX IF NOT EXISTS idx_employee_role   ON employee(role);

-- ============================================================
-- 2. employee_group — organizational groups for RBAC
-- ============================================================
CREATE TABLE IF NOT EXISTS employee_group (
    id              BIGSERIAL    PRIMARY KEY,
    name            VARCHAR(128) NOT NULL UNIQUE,
    description     TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================
-- 3. employee_group_member — junction: employee ↔ group
-- ============================================================
CREATE TABLE IF NOT EXISTS employee_group_member (
    employee_id     BIGINT       NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    group_id        BIGINT       NOT NULL REFERENCES employee_group(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (employee_id, group_id)
);

CREATE INDEX IF NOT EXISTS idx_egm_group ON employee_group_member(group_id);

-- ============================================================
-- 4. employee_key — API keys issued to employees
-- ============================================================
CREATE TABLE IF NOT EXISTS employee_key (
    id              BIGSERIAL    PRIMARY KEY,
    employee_id     BIGINT       NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    key_hash        VARCHAR(128) NOT NULL,
    key_prefix      VARCHAR(16)  NOT NULL,
    allowed_models  JSONB        DEFAULT '[]',
    allowed_scenes  JSONB        DEFAULT '[]',
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    last_used_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ekey_employee ON employee_key(employee_id);
CREATE INDEX IF NOT EXISTS idx_ekey_status   ON employee_key(status);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ekey_hash ON employee_key(key_hash);

-- ============================================================
-- 5. provider_registry — upstream provider registry
-- (uses provider_registry to avoid conflict with existing "channel" table)
-- ============================================================
CREATE TABLE IF NOT EXISTS provider_registry (
    id              BIGSERIAL    PRIMARY KEY,
    name            VARCHAR(128) NOT NULL UNIQUE,
    type            VARCHAR(64)  NOT NULL DEFAULT 'openai-compatible',
    base_url        VARCHAR(512),
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    metadata        JSONB        DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_provider_registry_status ON provider_registry(status);

-- ============================================================
-- 6. public_model — model catalog per provider
-- ============================================================
CREATE TABLE IF NOT EXISTS public_model (
    id              BIGSERIAL    PRIMARY KEY,
    provider_id     BIGINT       NOT NULL REFERENCES provider_registry(id) ON DELETE CASCADE,
    model_id        VARCHAR(256) NOT NULL,
    display_name    VARCHAR(256),
    capabilities    JSONB        DEFAULT '{}',
    pricing         JSONB        DEFAULT '{}',
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (provider_id, model_id)
);

CREATE INDEX IF NOT EXISTS idx_pmodel_provider ON public_model(provider_id);
CREATE INDEX IF NOT EXISTS idx_pmodel_status   ON public_model(status);

-- ============================================================
-- 7. public_model_mapping — gateway alias → public_model mapping
-- ============================================================
CREATE TABLE IF NOT EXISTS public_model_mapping (
    id              BIGSERIAL    PRIMARY KEY,
    alias           VARCHAR(256) NOT NULL UNIQUE,
    public_model_id BIGINT       NOT NULL REFERENCES public_model(id) ON DELETE CASCADE,
    client_overrides JSONB       DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pmm_model ON public_model_mapping(public_model_id);

-- ============================================================
-- 8. admin_action_audit — structured admin action audit trail
-- (uses admin_action_audit to avoid conflict with existing "audit_log")
-- ============================================================
CREATE TABLE IF NOT EXISTS admin_action_audit (
    id              BIGSERIAL    PRIMARY KEY,
    operator        VARCHAR(128) NOT NULL,
    action          VARCHAR(64)  NOT NULL,
    target_type     VARCHAR(64)  NOT NULL,
    target_key      VARCHAR(256),
    detail          JSONB        DEFAULT '{}',
    ip_address      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_aaa_operator   ON admin_action_audit(operator);
CREATE INDEX IF NOT EXISTS idx_aaa_action     ON admin_action_audit(action);
CREATE INDEX IF NOT EXISTS idx_aaa_target     ON admin_action_audit(target_type, target_key);
CREATE INDEX IF NOT EXISTS idx_aaa_created_at ON admin_action_audit(created_at);

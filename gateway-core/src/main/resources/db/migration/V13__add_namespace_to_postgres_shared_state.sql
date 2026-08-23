-- 为 PostgreSQL shared-state 表增加 namespace 隔离，保持与 Redis keyPrefix 语义一致。
-- 默认值使用 gateway：与 SharedStateConfig / RedisStoreUtils.safePrefix() 的默认前缀保持一致，
-- 以确保历史未分 namespace 数据在未显式配置 keyPrefix 时仍可被当前实例读取。

ALTER TABLE config_kv ADD COLUMN IF NOT EXISTS namespace VARCHAR(128) NOT NULL DEFAULT 'gateway';
ALTER TABLE config_kv DROP CONSTRAINT IF EXISTS config_kv_pkey;
ALTER TABLE config_kv ADD PRIMARY KEY (namespace, config_type, key);

ALTER TABLE client_usage ADD COLUMN IF NOT EXISTS namespace VARCHAR(128) NOT NULL DEFAULT 'gateway';
ALTER TABLE client_usage DROP CONSTRAINT IF EXISTS client_usage_pkey;
ALTER TABLE client_usage ADD PRIMARY KEY (namespace, client_id, period_key);

ALTER TABLE client_cost ADD COLUMN IF NOT EXISTS namespace VARCHAR(128) NOT NULL DEFAULT 'gateway';
ALTER TABLE client_cost DROP CONSTRAINT IF EXISTS client_cost_pkey;
ALTER TABLE client_cost ADD PRIMARY KEY (namespace, client_id, period_key);

ALTER TABLE client_rate_limit ADD COLUMN IF NOT EXISTS namespace VARCHAR(128) NOT NULL DEFAULT 'gateway';
ALTER TABLE client_rate_limit DROP CONSTRAINT IF EXISTS client_rate_limit_pkey;
ALTER TABLE client_rate_limit ADD PRIMARY KEY (namespace, client_id, window_key);

ALTER TABLE client_tpm_usage ADD COLUMN IF NOT EXISTS namespace VARCHAR(128) NOT NULL DEFAULT 'gateway';
ALTER TABLE client_tpm_usage DROP CONSTRAINT IF EXISTS client_tpm_usage_pkey;
ALTER TABLE client_tpm_usage ADD PRIMARY KEY (namespace, client_id, minute_key);

ALTER TABLE aggregate_metric ADD COLUMN IF NOT EXISTS namespace VARCHAR(128) NOT NULL DEFAULT 'gateway';
ALTER TABLE aggregate_metric DROP CONSTRAINT IF EXISTS aggregate_metric_pkey;
ALTER TABLE aggregate_metric ADD PRIMARY KEY (namespace, dimension_type, dimension_key, bucket);

ALTER TABLE request_trace ADD COLUMN IF NOT EXISTS namespace VARCHAR(128) NOT NULL DEFAULT 'gateway';
ALTER TABLE request_trace DROP CONSTRAINT IF EXISTS request_trace_pkey;
ALTER TABLE request_trace ADD PRIMARY KEY (namespace, request_id);

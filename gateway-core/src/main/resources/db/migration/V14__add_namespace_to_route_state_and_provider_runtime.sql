-- Add namespace to route_state and provider_runtime (missed in V13)

ALTER TABLE route_state ADD COLUMN IF NOT EXISTS namespace VARCHAR(128) NOT NULL DEFAULT 'gateway';
ALTER TABLE route_state DROP CONSTRAINT IF EXISTS route_state_pkey;
ALTER TABLE route_state ADD PRIMARY KEY (namespace, route_id);

ALTER TABLE provider_runtime ADD COLUMN IF NOT EXISTS namespace VARCHAR(128) NOT NULL DEFAULT 'gateway';
ALTER TABLE provider_runtime DROP CONSTRAINT IF EXISTS provider_runtime_pkey;
ALTER TABLE provider_runtime ADD PRIMARY KEY (namespace, provider);

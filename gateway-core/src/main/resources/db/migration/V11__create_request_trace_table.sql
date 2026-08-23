-- Request trace persistence for TraceStore PG backend
CREATE TABLE IF NOT EXISTS request_trace (
    request_id    VARCHAR(128)  NOT NULL PRIMARY KEY,
    request_body  TEXT,
    response_body TEXT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_request_trace_created_at ON request_trace (created_at DESC);

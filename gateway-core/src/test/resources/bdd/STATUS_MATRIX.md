# BDD Status Matrix

This document maps the newly designed BDD features to the current repository state.

Assessment basis: repository implementation and BDD design only. Existing automated tests are not counted as closure evidence.

## Implemented surfaces (not yet independently validated as a closed loop)

### Batch A client identity contract is implemented
- `RequestLogService.RequestLogEntry` now carries both masked `clientId` and internal `clientKey`.
- Request-log queries now resolve by `clientKey` with fallback compatibility for older records that only stored `clientId`.
- Admin and internal request-detail/list responses were moved to view records so raw `clientKey` is not exposed in JSON.
- Auth self-service recent usage responses also map through a view model and keep masked `clientId` at the boundary.

### Request logging, request detail, and admin request views are implemented
- Request-level logging exists via `src/main/java/io/gateway/oss/core/observability/RequestLogService.java`.
- Request detail and recent request APIs exist via `src/main/java/io/gateway/oss/core/web/InternalRequestLogController.java`.
- Repository evidence shows request logging service, request detail/recent request APIs, and admin request/cost pages are present; end-to-end closure still requires independent validation.
- Admin request console already exists in:
  - `frontend/src/features/admin/pages/AdminRequestsPage.tsx`
  - `frontend/src/features/admin/pages/AdminRequestsCostsPage.tsx`
- Batch B backend verification now proves one successful request can be reconciled across admin recent requests, request detail, trace detail, and cost aggregates in the current backend test stack.

### Trace body redaction by role is implemented
- Role-based trace redaction is implemented in `InternalRequestLogController.redactTraceBodies(...)`.
- The controller keeps metadata while nulling `requestBody` and `responseBody` for non-admin/operator system viewers.
- Batch B backend verification now proves admin can read trace bodies, viewer can read only metadata with redacted bodies, and a normal non-system user is rejected.

### Metrics instrumentation primitives are implemented
- Micrometer counters, timers, quota gauges, circuit breaker state, upstream latency, and resilience counters exist in `src/main/java/io/gateway/oss/core/observability/GatewayMetricsRecorder.java`.
- Batch C backend verification now proves dashboard overview, admin recent requests, and internal system status can be reconciled within one controlled backend scenario in the current JUnit/WebTestClient stack.

### Cost aggregation APIs and UI consumers are implemented
- Model cost aggregation exists at `GET /internal/cost/by-model` in `InternalRequestLogController`.
- Client cost aggregation exists at `GET /internal/cost/client` in `InternalRequestLogController`.
- Frontend consumers already exist in:
  - `frontend/src/api/usage.ts`
  - `frontend/src/features/admin/pages/AdminRequestsCostsPage.tsx`
  - `frontend/src/features/admin/pages/AdminDashboardPage.tsx`
- Batch B backend verification now proves the same request sample rolls consistently into both `by-model` and `by-client` aggregates.

### Operational status and alert views are implemented
- Alert service exists in `src/main/java/io/gateway/oss/core/web/alerts/AdminAlertsService.java`.
- Alert page exists in `frontend/src/features/admin/pages/AdminAlertsPage.tsx`.
- System status API exists in `src/main/java/io/gateway/oss/core/web/InternalSystemStatusController.java`.
- Dashboard system status section exists in `frontend/src/features/admin/pages/AdminDashboardPage.tsx`.
- Batch C backend verification now proves disabled-route and frozen-account alert payloads are consumer-visible through `/admin/alerts`, and route recovery clears the active disabled-route alert coherently with `/admin/routes`.
- Batch D backend verification now proves one degraded operational scenario can be observed and recovered in the current backend stack: disabling `openai-primary` surfaces `route_disabled` in `/admin/alerts`, flips `/internal/system/status.globalCircuit.hasAvailableRoute` to `false`, causes request-time `500 config_error` with stable `requestId`, and after route recovery the alert clears, status returns healthy, and a new request succeeds.

### Quota rejection path is implemented
- Repository evidence indicates the quota rejection path returns operationally meaningful failure responses, but closure still needs independent validation across request history, visibility, and remediation.

## Closure validation gaps (implemented in part, not yet independently validated)

### Batch A runtime proof is now established for the client identity chain
- The `unknown_model` runtime blocker was fixed by changing `ConfigLoadService` to overlay persisted config onto YAML defaults instead of replacing route/provider/client/scene maps wholesale.
- Runtime verification now succeeds for both JWT and API-key request paths: `POST /v1/chat/completions` returns `200`, `/admin/requests/recent` shows the new request IDs, `/internal/requests/{requestId}` returns masked request detail plus trace, and `/internal/cost/client?client=user|demo-client-key` returns matching client aggregates.
- `GET /auth/usage/recent` also now returns newly created request records through the Batch A `clientKey` query path, while still exposing only masked `clientId` at the JSON boundary.
- Targeted regression tests pass for `ConfigLoadServiceTest`, `AuthControllerTest`, `InternalRequestLogControllerTest`, `ConfigAuditControllerTest`, and `InternalUsageSummaryControllerTest`.
- The previously unrelated `/admin/sync/models-dev` timeout path has now been hardened as well: `ModelsDevSyncService` catches sync failures as `false`, so `AdminConfigControllerTest.shouldReturnFailedStatusWhenSyncDisabled` returns the expected `200` + `status=failed` contract instead of bubbling `500`.

### Batch B backend verification is established for feature 01 + 05 and 02
- `CoreFlowIntegrationTest` now proves one fixed `X-Request-Id` request can be observed in admin recent requests, internal request detail, trace detail, model-cost aggregation, client-cost aggregation, and JWT self-service recent usage.
- `InternalRequestLogControllerTest` now proves masked `clientId` boundary behavior, no `clientKey` leakage in JSON, role-based trace redaction, and forbidden access for callers without system-view permission.
- These checks are executable in the current JUnit/WebTestClient stack without introducing a new BDD runner.

### Batch C backend verification is established for feature 03 and 04
- `InternalUsageSummaryControllerTest` now proves one controlled backend sample can be reconciled across `/internal/dashboard/overview`, `/admin/requests/recent`, and `/internal/system/status`, including request totals, token totals, cost totals, 2xx/5xx distribution, active-client count, top-model/top-client summaries, and non-contradictory system status fields.
- `AdminConfigControllerTest` now proves `/admin/alerts` exposes consumer-visible payloads for `route_disabled`, `account_frozen`, and `circuit_open`, including `type`, `severity`, `status`, `message`, `source`, and metadata fields, and also proves both route recovery and circuit recovery clear the corresponding active alert coherently.
- These checks are executable in the current JUnit/WebTestClient stack without introducing a new BDD runner.

### Batch D backend verification is established for feature 06
- `CoreFlowIntegrationTest` now proves one minimal degraded-state recovery loop in the current integration stack: route disable -> alert visible -> system status degraded -> request fails with `config_error` and stable `requestId` -> route re-enabled -> alert cleared -> system status healthy -> next request succeeds.
- This closes the strongest currently feasible backend-only slice of `06_resilience_and_failure_observability.feature` without introducing a new runner or requiring external observability infrastructure.

### Full BDD execution support
- Feature design files now exist under `src/test/resources/bdd/`.
- There is no Cucumber/Gherkin runtime wiring yet.
- Current behavior validation capability is split across backend API checks and frontend UI checks rather than a native BDD runner.

### Metrics-to-UI reconciliation is not yet independently validated
- Backend metrics are recorded.
- Dashboard and request pages expose aggregated operational numbers.
- There is still no independent validation evidence yet that frontend UI summaries reconcile against metric sources or Prometheus-style outputs end to end.

### Alert lifecycle is only partially implemented
- Alerts page and alert service exist.
- Current implemented alerts cover config/user-state sources plus one runtime resilience source (`route_disabled`, `account_frozen`, `circuit_open`).
- There is not yet a richer event-driven alert lifecycle for latency spikes, 5xx bursts, cost anomalies, upstream provider degraded state, or TPM exhaustion.
- Batch C + the current circuit-open extension close the current backend payload and recovery-path checks for the implemented alert types, but they do not yet add broader anomaly-driven alert families.

### Failure correlation across request history, status, and operator views is not yet validated
- Quota rejection and request logging have evidence.
- System status surfaces maintenance mode and route availability.
- Batch D now provides one degraded-state recovery loop across alerts, system status, failure response, remediation, and post-recovery success.
- There is still no independent validation evidence yet tying upstream failures, retry/circuit transitions, and admin-visible request history into one richer closed scenario with request-history drill-down.

### Request detail role matrix is not yet independently validated
- Redaction logic exists.
- Independent closure evidence is still needed to show admin, non-admin system viewer, and unauthorized callers all behave correctly in the same operational flow.

### CSV export behavior is not yet independently validated
- Export buttons exist in admin pages.
- There is no independent validation evidence yet that exported file structure, column set, and data values reconcile with the underlying request/cost data.
- This remains outside the current backend-only Batch B scope.

## Missing validation or execution support

### Native execution of these feature files
- The feature files are design assets only right now.
- No glue code, step definitions, runner, or CI wiring exists to execute them.

## Missing or materially incomplete for a true closed loop

### External observability stack verification is not in evidence
- The report asks for observability closed loop across Logs, Metrics, and Traces.
- Current repository evidence shows internal logs, metrics recording, trace persistence, and admin pages.
- There is no independent validation evidence yet for Prometheus scrape outputs, Grafana dashboards, or Jaeger trace visualization.

### Event-driven anomaly and SLO alerts are missing
- No inspected implementation evidence for scenarios such as:
  - latency threshold breach creates alert
  - 5xx burst creates alert
  - cost anomaly creates alert
  - upstream provider degraded event creates alert

### Canonical request-id correlation across all operational surfaces is not yet evidenced
- `X-Request-Id` is returned and used in request detail flows.
- But there is no independent validation evidence yet that the same ID is correlated across logs, metrics labels, traces, alert items, dashboard drill-downs, and downloadable reports as one canonical operational handle.

### Detect -> investigate -> remediate -> verify recovery workflow is not yet evidenced
- The report emphasizes observation to operation loop closure.
- Current repository has observation surfaces and some control surfaces.
- There is no independent validation evidence yet proving: detect issue -> alert or status visible -> operator drills into request detail/system status -> config or route action -> recovery is confirmed.

## Independent validation order

1. Validate `01_request_observability_chain.feature` and `05_cost_attribution_and_reporting.feature` first via direct API and UI checks using the same request sample and time window.
2. Validate role-matrix behavior for `02_trace_redaction_and_access_control.feature` with admin, non-admin system viewer, and unauthorized access paths.
3. Validate dashboard, export, and reconciliation behavior for `03_metrics_dashboard_and_requests_console.feature`.
4. Extend alert sources and validate `04_alerts_and_operational_visibility.feature` with one recoverable operational event.
5. Validate `06_resilience_and_failure_observability.feature` with a detect -> investigate -> remediate -> verify recovery flow.

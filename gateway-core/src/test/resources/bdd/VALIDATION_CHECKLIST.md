# Closed-Loop Validation Checklist

Purpose: validate the full operational loop from the most basic API call to observability and operational management.

Scope rule: this checklist does not rely on existing automated tests as evidence. Each item must be checked independently through direct API, UI, runtime, or operational verification.

## A. Basic request success

### A1. Minimum successful API call
- [ ] Use a fixed `X-Request-Id` and send `POST /v1/chat/completions` with a minimal valid request.
- [ ] Confirm HTTP status is `200`.
- [ ] Confirm response header echoes the same `X-Request-Id`.
- [ ] Confirm response body contains the expected model-completion shape.
- [ ] Confirm the resolved model/provider/route are the intended ones for this request.

### A2. Basic failure path
- [ ] Trigger one controlled failure path such as quota exceeded, disabled route, or unavailable route.
- [ ] Confirm the API returns an operationally meaningful status and error code.
- [ ] Confirm the failure response is distinguishable from a successful request.

## B. Request visibility

### B1. Admin recent requests
- [ ] Open the admin recent requests surface.
- [ ] Locate the successful request by the same `X-Request-Id`.
- [ ] Confirm the row contains at least: `requestId`, `status`, `model`, `provider`, `routeId`, `scene`, `latencyMs`, `usageTokens`, `costUsd`, `timestamp`.

### B2. Request filtering
- [ ] Filter by `client` and confirm the target request remains visible.
- [ ] Filter by `model` and confirm the target request remains visible.
- [ ] Filter by `status` and confirm successful and failed requests are separated correctly.

### B3. User visibility boundary
- [ ] Confirm a normal user can view only their own recent usage records.
- [ ] Confirm a user cannot see unrelated requests from other users or clients.

## C. Trace and request detail

### C1. Request detail retrieval
- [ ] Open request detail by the same `requestId`.
- [ ] Confirm trace metadata matches the recent request row.
- [ ] Confirm request/response payloads are retrievable for an admin path.

### C2. Role-based trace access
- [ ] Confirm admin can view full request and response bodies.
- [ ] Confirm non-admin system viewer sees metadata but redacted bodies.
- [ ] Confirm unauthorized caller is denied access.

## D. Cost attribution

### D1. Request-level cost evidence
- [ ] Confirm the successful request contains `usageTokens`.
- [ ] Confirm the successful request contains `costUsd` when pricing is configured.

### D2. Aggregation by model
- [ ] Query model cost aggregation for the same day or time window.
- [ ] Confirm the request contribution is rolled into the expected model bucket.
- [ ] Confirm requests, total tokens, and total cost are numerically consistent within display precision.

### D3. Aggregation by client
- [ ] Query client cost aggregation for the same client and time window.
- [ ] Confirm the request contribution is rolled into the expected client/model bucket.
- [ ] Confirm prompt/completion/total tokens reconcile where those fields are available.

### D4. Cross-surface reconciliation
- [ ] Reconcile the same request across request detail, by-model summary, and by-client summary.
- [ ] Confirm no surface reports contradictory request count, token count, or cost for the same sample window.

## E. Dashboard and summary views

### E1. Operational summary cards
- [ ] Open dashboard summary cards.
- [ ] Confirm total requests align with the selected validation window.
- [ ] Confirm total tokens align with the selected validation window.
- [ ] Confirm total cost aligns with the selected validation window.
- [ ] Confirm success rate reflects the observed successful vs failed requests.

### E2. Status distribution and system state
- [ ] Confirm request status distribution is consistent with the validation sample.
- [ ] Confirm maintenance mode state is visible.
- [ ] Confirm emergency rate limit state is visible.
- [ ] Confirm route availability state is visible.

### E3. Consistent dashboard mouthpiece
- [ ] Confirm dashboard is not presenting a contradictory operational picture versus requests, costs, and status endpoints.

## F. Metrics and external observability

### F1. Internal metrics evidence
- [ ] Confirm request count metrics increase after a successful call.
- [ ] Confirm request outcome metrics distinguish success and failure.
- [ ] Confirm latency metrics are emitted for the request.

### F2. External observability stack evidence
- [ ] If Prometheus is part of the target operating model, confirm the scrape surface exposes the request.
- [ ] If Grafana is part of the target operating model, confirm dashboard visualizations reflect the same operational sample.
- [ ] If trace visualization backend such as Jaeger is part of the target operating model, confirm the same request can be traced there.

## G. Alerts

### G1. Minimum recoverable event
- [ ] Create one controlled, recoverable operational event such as disabling a route or freezing an account.
- [ ] Confirm the event appears as an active alert.
- [ ] Confirm the alert includes `type`, `source`, `severity`, and time information when available.

### G2. Alert history
- [ ] Confirm the event is visible in recent alerts, not only active alerts.
- [ ] Confirm alert source is sufficient to continue investigation.

### G3. Event-driven alert extension
- [x] Confirm whether runtime anomalies such as 5xx burst, latency spike, cost anomaly, or circuit open are represented.
- [ ] Record the still-missing runtime anomaly coverage for a stronger operational loop (`5xx burst`, `latency spike`, `cost anomaly`; `circuit_open` is represented).

## H. Operations investigation

### H1. From alert/status to root-cause surface
- [ ] Starting from an alert or system status anomaly, confirm the operator can continue to request history.
- [ ] Confirm the operator can continue to request detail or trace.
- [ ] Confirm the operator can continue to provider/runtime/status context needed for diagnosis.

### H2. Actionability
- [ ] Confirm there is at least one available operational action to mitigate the event.
- [ ] Examples: re-enable route, restore provider availability, unfreeze account, revert configuration, relax emergency gate, or restore valid limit settings.

## I. Remediation and recovery closure

### I1. Perform remediation
- [ ] Execute one remediation action against the created operational event.
- [ ] Confirm the action is accepted by the system.

### I2. Recovery proof
- [ ] Re-run the same basic API request pattern after remediation.
- [ ] Confirm the request succeeds when recovery is expected.
- [ ] Confirm requests view reflects healthy post-recovery traffic.
- [ ] Confirm system status returns to healthy state.
- [ ] Confirm alert state is cleared or transitions appropriately.

### I3. Final closure statement
- [ ] Confirm the operator can show the full chain: request -> visibility -> trace -> cost -> dashboard/metrics -> alert/status -> investigation -> remediation -> recovery confirmation.

## Mandatory items for a true closed loop

- [ ] Mandatory: one stable `requestId` must be traceable from API response through request list and request detail.
- [ ] Mandatory: the successful request must be visible to admin operators with complete operational metadata.
- [ ] Mandatory: trace detail must enforce role-based visibility correctly.
- [ ] Mandatory: the same request must roll into token and cost aggregation views.
- [ ] Mandatory: dashboard numbers must reconcile with the underlying request and cost sample window.
- [ ] Mandatory: at least one operational event must produce alert or status evidence with a usable source.
- [ ] Mandatory: the operator must be able to investigate from alert/status into request-level evidence.
- [ ] Mandatory: one remediation action must be performed and its recovery must be proven by a new successful request and converged operational surfaces.

## Closure verdict rule

- If A through I are all satisfied, the system can be claimed as having a true executable closed loop for the validated scope.
- If A through F are satisfied but G through I are incomplete, the system has observability coverage but not operational closure.
- If only A through D are satisfied, the system has request and cost visibility but not full observability management closure.

# Architecture

> System architecture decisions and patterns for the Simple AI Gateway.

---

## Overview

The Simple AI Gateway is a configuration-driven Spring Boot WebFlux application. It proxies OpenAI-compatible chat completion requests to multiple upstream providers (OpenAI-compatible, Anthropic, Gemini) with route resolution, fallback, rate limiting, usage tracking, cost calculation, admin/config management, and webhook event notifications.

---

## Request Pipeline

```
operational gate → auth → model authorization → defaults/policy validation
→ rate limit → concurrency limit → quota/budget check → TPM check
→ route resolution → scene authorization → upstream call → response normalization
```

### Pipeline Stages

1. **Operational Gate** (`OperationalGateService`) — maintenance mode (503) + emergency rate limit (429). Runs before auth. Maintenance whitelist bypass supported.
2. **Authentication** (`ClientAuthService`) — JWT → user API key → static API key fallback
3. **Authorization** (`AuthorizationService`) — role-based (admin > operator > viewer > user) + permission-based (admin_full, manage_system, view_system)
4. **Model/Scene Authorization** — per-client allowed models and scenes
5. **Defaults & Policy** — client-configured defaults (temperature, max_tokens, scene)
6. **Rate Limiting** — per-client RPM (lock-free CAS), concurrency limit (AtomicInteger), TPM reserve
7. **Quota & Budget** — daily/monthly token quota, daily/monthly cost budget
8. **Route Resolution** — model alias → route → scene → primary + fallback
9. **Upstream Call** — provider adapter selection, key rotation, resilience tracking, load balancing
10. **Post-processing** — usage recording (batched), cost calculation, request logging, aggregate reporting, metrics

The `ChatCompletionsController` is a thin HTTP adapter. All business logic lives in `ChatCompletionsOrchestrator`.

---

## Config Management

### Dynamic Config Service

`DynamicConfigService` is the admin write-through orchestrator. Every admin write:
1. Saves to `ConfigStore` (persistent storage)
2. Updates `GatewayProperties` in-memory (immediate effect)
3. Records audit log (async, fire-and-forget with Disposable lifecycle tracking)
4. Saves version snapshot (async)
5. Publishes sync notification via Redis Pub/Sub (cross-instance)

**Contract**: changes are immediately effective for subsequent requests without restart.

**Startup**: Config loading is parallelized via `CompletableFuture.allOf()` with a 5-thread pool (providers/routes/scenes/clients/system loaded concurrently).

### Config Store Layer

- **Interface**: `ConfigStore` (save/load/loadAll/delete)
- **Implementations**: `InMemoryConfigStore`, `RedisConfigStore`, `PostgresConfigStore`
- **Config types**: `providers`, `routes`, `scenes`, `clients`, `system`, `users`, `config-audit`, `config-versions`, `request-logs`, `refresh-token-blacklist`, `alerts-config`, `alerts-history`

### Config Audit & Versioning

- `ConfigAuditService`: Dual-write (memory ring buffer + ConfigStore), async with `@PreDestroy` lifecycle
- `ConfigVersionService`: Synchronous snapshot on `@PostConstruct` (blockLast), async version save
- `DynamicConfigService`: Injects audit/version hooks into all save/delete methods

### Config Change Notification (Redis Pub/Sub)

- **Channel**: `{keyPrefix}:config:sync`
- **Message payload**: config type string (`"providers"`, `"routes"`, `"clients"`, `"system"`, `"operational"`)
- **Subscriber**: runs on Redis connection thread (safe for synchronous `load*()` + `.block()`)

---

## Provider Adapter Architecture

Upstream providers are abstracted behind `ChatProviderAdapter` interface. Three built-in adapters:

| Type | Auth | Streaming | Reference |
|------|------|-----------|-----------|
| `openai-compatible` | `Authorization: Bearer` | Supported | `OpenAiCompatibleChatProviderAdapter` |
| `anthropic` | `x-api-key` | Supported (SSE, 需协议转换) | `AnthropicChatProviderAdapter` |
| `gemini` | `x-goog-api-key` | Supported | `GeminiChatProviderAdapter` |

All adapters normalize responses to OpenAI-compatible `chat.completion` shape. Streaming adapters convert upstream SSE events to OpenAI `chat.completion.chunk` format.

---

## Shared-State Backend

**Config**: `gateway.shared-state.backend` (`in_memory` | `redis` | `postgresql` | `hybrid`, default `in_memory`)

Components using shared state:
- Rate limiting (`ClientRateLimiter`)
- Route resilience tracking (`RouteResilienceTracker`)
- Provider key resilience tracking (`ProviderKeyResilienceTracker`)
- Usage/cost stores (`ClientUsageStore`, `ClientCostStore`)

All must use the selected backend consistently.

---

## Authentication & Authorization

### Dual-Channel Auth

```
请求 → Authorization: Bearer <token>
  ├── auth.enabled=true
  │   ├── JWT 解析成功 && typ=access && client 有效 → ✅ 认证通过（JWT 用户）
  │   ├── 匹配已注册用户 API Key → ✅ 认证通过（动态用户）
  │   └── JWT 解析失败 / 不匹配 API Key → 回退静态 API Key
  └── auth.enabled=false
      └── 仅走静态 API Key（跳过 JWT 和用户 API Key）
```

Static API Key always works (backward compatibility guarantee).

### RBAC (4 roles, 3 permissions)

| Role | Hierarchy | Permissions |
|------|-----------|-------------|
| `admin` | highest | admin_full, manage_system, view_system |
| `operator` | ↓ | view_system |
| `viewer` | ↓ | view_system |
| `user` | lowest | self-service only |

- `tokenVersion` claim in JWT for invalidation on role change / deletion
- Internal endpoints (`/internal/**`) require Bearer auth via `InternalEndpointAuthFilter`

---

## Archival Tables (PostgreSQL)

The following Flyway-created tables exist in the database schema but have no active runtime code:

| Table | Status | Alternative |
|-------|--------|-------------|
| `admin_action_audit` | 🗄️ Archived | Audit stored via `ConfigStore(config_type='audit')` |
| `employee` / `employee_group` / `employee_group_member` / `employee_key` | 🗄️ Archived | User management uses `UserAccount`/`ClientPrincipal` |

> 实体使用 `@JdbcTypeCode(SqlTypes.JSON)` 处理 JSON 字段，列类型由 Hibernate 方言在运行时确定。

---

## Webhook & Event System (Minimal Slice)

Webhook delivery has a **minimal implemented slice**:

- Admin webhook endpoint CRUD/list (`/admin/webhooks`)
- Delivery log readback (`/admin/webhooks/deliveries`)
- Best-effort async `alert.triggered` dispatch after `GET /admin/alerts` succeeds
- Optional HMAC headers when `endpoint.secret` is non-blank (`X-Webhook-Timestamp`, `X-Webhook-Signature`)

Still not implemented: retry queue/scheduler, generic event bus, and broader webhook product surface.

---

## Resilience

### Route-Level

- Retryable failure threshold within a time window opens a route temporarily
- Open duration after which route becomes available again
- Only normalized retryable upstream failures count toward threshold
- Successful calls clear failure/open state

### Key-Level

- Per-provider-key health tracking (same threshold/window/open-duration pattern)
- Key selection uses round-robin across healthy keys

### Load Balancing

- `gateway.load-balancer.enabled` (default `false`)
- Weighted round-robin across healthy concrete candidates
- For streaming: initial route selection only, no auto-fallback

### System-Level

- **Maintenance mode**: all requests rejected with 503 `maintenance_mode` (whitelist bypass supported)
- **Emergency rate limit**: global requests-per-minute cap, 429 `emergency_rate_limited`
- **Global circuit status**: aggregated view of route/provider health at `/internal/system/status`

---

## Observability

### Metrics

- Micrometer counters: `gateway.request.count`, `gateway.request.outcome` (tags: path, method, outcome, status)
- Micrometer timer: `gateway.request.latency` (tags: path, method)
- OpenTelemetry tracing bridge (configurable sampling probability)

### Health

`GatewayHealthIndicator` checks PostgreSQL connectivity (`DataSource.getConnection()`) and Redis connectivity (only when `shared-state.backend=REDIS`). Both use `ObjectProvider` for graceful absence.

### Logging

- SLF4J with MDC `requestId` on every request
- Parameterized logging (`log.info("key={} value={}", ...)`)
- Fire-and-forget subscriptions tracked via `Disposable` list with `@PreDestroy` lifecycle

---

## Profile Isolation

| Profile | Purpose | Logging | Tracing |
|---------|---------|---------|---------|
| default | dev/demo | INFO | 100% |
| `dev` | development | DEBUG | 100% |
| `prod` | production | INFO/WARN | 10% |
| `test` | test (H2) | WARN | off |

---

## Hot Path Performance

The request hot path executes all in-memory checks synchronously on the Netty event loop thread (<1ms total). Key design decisions:

- **Lock-free stores**: `InMemoryRateLimiter` and `InMemoryConcurrentRequestLimiter` use CAS-based `AtomicLong`/`AtomicInteger` instead of `synchronized`
- **Batched I/O**: `UserAccountService.markApiKeyUsed()` accumulates dirty accounts in memory, flushes to ConfigStore every 30s
- **Minimal allocations**: single `Instant.now()` per request, indexOf-based streaming usage capture instead of regex

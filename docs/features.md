# Simple AI Gateway — 功能点文档

> 基于代码实现和 1045 个后端测试 + 122+ 个 E2E 场景的覆盖分析，维护日期：2026-06-05

---

## 功能域总览

| 域 | 模块 | 测试类数 | 测试用例数 | 前端页面 | 完成度 |
|----|------|----------|-----------|----------|--------|
| 业务网关 | Chat Completions、Models、Health | 4 | 48 | 2 | ✅ 完成 |
| 认证鉴权 | 登录/注册、JWT、API Key、RBAC | 7 | 97 | 4 | ✅ 完成 |
| 安全防护 | 请求体限制、CORS、登录限流、启动校验 | 0 | 0 | — | ✅ 完成（新） |
| 限流控制 | RPM、并发、TPM | 7 | ~35 | — | ✅ 完成 |
| 配额预算 | 日/月 Token 配额 + 成本预算 + 用户限额 | 8 | ~40 | 2 | ✅ 完成 |
| 路由分发 | 解析、负载均衡、权重轮询 | 3 | 11 | — | ✅ 完成 |
| 韧性熔断 | 路由级、Key 级、全局熔断 | 4 | 19 | — | ✅ 完成 |
| 上游适配 | OpenAI / Anthropic / Gemini | 5 | 30 | — | ✅ 完成 |
| 配置管理 | 动态配置、审计、版本、回滚 | 6 | 63 | — | ✅ 完成 |
| 管理控制 | Admin CRUD + 系统配置 + 审计 + 配置导入导出 + 路由管理 | 18 | ~200 | 6 | ✅ 完成 |
| 可观测性 | 请求日志、聚合报表、指标、Prometheus、客户端成本明细 | 6 | ~50 | 2 | ✅ 完成 |
| 同步集成 | models.dev 同步、定价同步 | 8 | ~25 | — | ✅ 完成 |
| 运维控制 | 维护模式、紧急限流、运行状态 | 2 | 21 | 1 | ✅ 完成 |
| 事件通知 | Webhook 投递、事件发布 | 3 | ~20 | — | ⚠️ 最小实现切片 |
| 共享状态 | InMemory / Redis / PostgreSQL 三后端 | 7 | 21 | — | ✅ 完成 |

---

## 1. 业务网关

### 1.1 Chat Completions（核心业务入口）

兼容 OpenAI API 协议，支持流式 SSE 和非流式响应。

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 非流式请求 | `POST /v1/chat/completions`, `stream=false` | `ChatCompletionsControllerTest` (23) |
| 流式请求 SSE | `stream=true`, `Content-Type: text/event-stream` | 同上 |
| 多轮消息 | `messages[]` 多轮 system/user/assistant | 同上 |
| 参数透传 | temperature, max_tokens, top_p 等 | 同上 |
| 请求 ID 追踪 | 每个请求生成 `req_xxx`，MDC 注入 | `RequestIdFilter` |
| 响应标准化 | object=chat.completion, choices[].message.role=assistant | `CrossProviderNormalizationTest` (2) |

**源码**: `ChatCompletionsController.java`, `ChatCompletionsOrchestrator.java`, `ChatCompletionsRequest.java`, `ChatMessage.java`

### 1.2 Models（模型列表）

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 模型列表查询 | `GET /v1/models` 返回 OpenAI 兼容格式 | `ModelsControllerTest` (11) |
| models.dev 快照 | 从 models.dev 同步的模型元数据 | `InternalModelsSnapshotControllerTest` (6) |

**源码**: `ModelsController.java`

### 1.3 Health Check

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 聚合健康检查 | `GET /healthz` 聚合 PG / Redis / Gateway | `HealthController`（通过集成测试验证） |
| 存活探测 | `GET /healthz/live` 进程存活，始终返回 UP | `HealthController.java` |
| 就绪探测 | `GET /healthz/ready` 检查 DB/Redis 连通性 | 同上 |
| 组件探测 | 分别探测 DataSource.getConnection() 和 Redis PING | `GatewayHealthIndicator.java` |

**源码**: `HealthController.java`, `GatewayHealthIndicator.java`

---

## 2. 认证鉴权

### 2.1 用户认证

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 用户登录 | `POST /auth/login`, 返回 access + refresh token | `AuthControllerTest` (31) |
| Token 刷新 | `POST /auth/refresh`, 刷新 access token | 同上 |
| 登出黑名单 | `POST /auth/logout`, refresh token 加入黑名单 | `RefreshTokenBlacklistTest` (4) |
| 用户注册 | `POST /auth/register`, 创建用户并生成 API Key | `AuthControllerTest` |
| 密码修改 | `PUT /auth/password`, 旧密码验证后更新 | `AuthControllerTest` |
| 当前用户信息 | `GET /auth/me`, 返回 profile + quota | `UserAccountServiceTest` (25) |
| Token 版本机制 | `tokenVersion` 在 JWT 中，角色变更后失效 | `AuthorizationServiceTest` (13) |

**源码**: `AuthController.java`, `JwtService.java`, `RefreshTokenBlacklistService.java`

### 2.2 双通道认证

| 功能项 | 说明 | 测试 |
|--------|------|------|
| JWT 认证 | 解析 Bearer token, 验证 `typ=access` | `ClientAuthService.java` |
| 用户 API Key 认证 | 匹配已注册用户的 API Key | 同上 |
| 静态 API Key 回退 | JWT/用户 Key 均失败时使用静态 Key | 同上 |
| 认证禁用模式 | `auth.enabled=false` 时仅走静态 Key | `AuthDisabledIntegrationTest` (2) |

### 2.3 RBAC 权限体系

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 四级角色 | admin > operator > viewer > user | `AuthorizationServiceTest` (13) |
| 权限位 | admin_full, manage_system, view_system | 同上 |
| 端点权限守卫 | `/admin` 需 admin | 同上 |

### 2.4 API Key 自助管理

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 创建 Key | `POST /auth/keys`, 明文仅返回一次, 支持 allowedModels | `UserAccountServiceTest` (25) |
| 列出 Keys | `GET /auth/keys`, 脱敏展示 (apiKeyMasked) | 同上 |
| 启停 Key | `PATCH /auth/keys/{keyId}`, toggle enabled | 同上 |
| 删除 Key | `DELETE /auth/keys/{keyId}` | 同上 |
| Key 轮换 | `POST /auth/keys/{keyId}/rotate` 原子轮换（旧 Key 失效，签发新 Key） | `UserAccountServiceTest` (新) |
| Key 用量追踪 | `markApiKeyUsed()` 异步更新 lastUsedAt + requestCount | 同上 |

**源码**: `UserAccountService.java`, `UserAccount.java`, `ClientPrincipal.java`

---

## 3. 安全防护（2026-05 新增）

### 3.1 启动安全校验

| 功能项 | 说明 |
|--------|------|
| JWT 密钥长度校验 | `SecurityStartupValidator` 启动时检查 secret ≥ 32 字符，不足抛 IllegalStateException |
| 静态用户密码校验 | 默认 YAML 中 password 为空时仅 warn，避免误杀测试环境 |
| 非生产旁路 | validator 仅在生产环境启动时生效，测试环境通过 `@Profile` 旁路 |

### 3.2 请求体限制

| 功能项 | 说明 |
|--------|------|
| 请求体大小限制 | `spring.codec.max-in-memory-size: 1MB` 防止内存耗尽 |
| Header 大小限制 | `server.max-http-request-header-size: 16KB` 防止大 Header 攻击 |

### 3.3 CORS 跨域

| 功能项 | 说明 |
|--------|------|
| CORS 过滤器 | `CorsConfig` 实现 `WebFluxConfigurer`，全局允许跨域访问 |
| 允许的源/方法/Headers | `*` origin patterns, 所有 HTTP 方法, 所有 headers |
| Credentials | `allowCredentials=true`, 暴露 `Authorization` header |

### 3.4 登录防爆破

| 功能项 | 说明 |
|--------|------|
| 失败计数限流 | `LoginRateLimiter` 每用户名 10 次失败/5min 窗口 |
| 超限返回 429 | `login_rate_limited` 错误码 |
| 成功清零 | 登录成功后清除该用户失败计数 |
| 集成点 | `AuthController.login()` 和 `loginFromStaticConfig()` 调用 |

**源码**: `SecurityStartupValidator.java`, `CorsConfig.java`, `LoginRateLimiter.java`, `application.yml`

---

## 4. 限流控制

### 4.1 请求频率限流 (RPM)

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 滑动窗口计数 | 基于 `requestsPerWindow` + `window` Duration | `InMemoryRateLimiterTest` (4) |
| 无锁 CAS 实现 | `AtomicLong` CAS, 避免 synchronized | 同上 |
| Redis 分布式限流 | `RedisRateLimiter` 共享状态 | `RedisRateLimiterTest` (1) |
| Postgres 限流 | `PostgresClientRateLimiter` | `PostgresClientRateLimiterTest` |

### 4.2 并发限流

| 功能项 | 说明 | 测试 |
|--------|------|------|
| AtomicInteger 计数 | 请求到达 +1, 完成 -1 | `InMemoryRateLimiterTest` |
| 超限返回 429 | `concurrent_limit_exceeded` | 同上 |

### 4.3 TPM 限流（每分钟 Token 数）

| 功能项 | 说明 | 测试 |
|--------|------|------|
| Token 预留 | `ClientTpmService.reserve()` | `InMemoryClientTpmStoreTest` + `RedisClientTpmStoreTest` + `PostgresClientTpmStoreTest` |
| 多后端存储 | InMemory / Redis / Postgres | 同上 |

> ⚠️ TPM 后端实现存在，控制台未消费

**源码**: `ClientRateLimiter.java`（接口）, `InMemoryRateLimiter.java`, `RedisRateLimiter.java`, `PostgresClientRateLimiter.java`, `ClientTpmService.java`

---

## 5. 配额与预算

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 日 Token 配额 | `dailyTokens` 限额, 超限返回 `quota_exceeded` | `ClientQuotaServiceTest` (9) |
| 日成本预算 | `dailyCost` 限额, 超限返回 `budget_exceeded` | `ClientBudgetServiceTest` (4) |
| 月 Token 配额 | `monthlyTokens` 限额, 超限 `monthly_quota_exceeded` | 同上 |
| 月成本预算 | `monthlyCost` 限额, 超限 `monthly_budget_exceeded` | 同上 |
| **用户级限额** | 每个 `UserAccount` 可配置独立 `UserLimits`，新用户默认高限额 | `UserAccountServiceTest` (4, 新) |
| **用户限额 CRUD** | `PUT /admin/users/{username}/limits` 管理员修改用户限额 | `AdminConfigControllerTest` (4, 新) |
| **限额回退链** | 静态 client 限额 > 用户级限额 > 无限制 (Long.MAX_VALUE) | `ClientQuotaService`, `ClientBudgetService`, `ClientTpmService` |
| 成本计算 | `CostCalculator` 基于 pricing 计算 USD | `CostCalculatorTest` (5) |
| InMemory 用量/成本 | `InMemoryClientUsageStore`, `InMemoryClientCostStore` | — |
| Redis 用量/成本 | `RedisClientUsageStore`, `RedisClientCostStore` | `RedisClientUsageStoreTest` (2), `RedisClientCostStoreTest` (2) |

**源码**: `ClientQuotaService.java`, `ClientBudgetService.java`, `CostCalculator.java`, `ClientUsageStore.java`, `ClientCostStore.java`, `UserAccount.java` (UserLimits)

**前端**: `/quota` 页面对接 `/auth/me` + `/internal/usage/summary` 拼装；月度累计为 best-effort 聚合值

---

## 6. 路由分发

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 模型解析 | `model` → alias → route → scene → primary + fallback | `ModelRouteResolverTest` (5) |
| 权重轮询 | WRR 按权重分配请求到健康候选节点 | `WeightedRoundRobinTest` (1), `RouteLoadBalancerTest` (5) |
| 负载均衡开关 | `gateway.load-balancer.enabled` 控制是否启用 | `RouteLoadBalancerTest` |
| 流式路由 | 流式请求仅初始路由选择，不自动 fallback | — |

**源码**: `ModelRouteResolver.java`, `RouteLoadBalancer.java`, `WeightedRoundRobin.java`, `ResolvedRoute.java`

---

## 7. 韧性熔断

### 7.1 路由级熔断

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 失败阈值触发 | 滑动窗口内失败数达阈值时打开 | `RouteResilienceTrackerTest` (5) |
| 自动恢复 | openDuration 过后半开尝试 | 同上 |
| 成功清零 | 成功后清除失败/打开状态 | 同上 |
| 仅可重试失败计数 | 非可重试错误不计入 | 同上 |

### 7.2 Key 级熔断

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 按 Key 健康追踪 | 每 Key 独立跟踪阈值/窗口/恢复 | `ProviderKeyResilienceTrackerTest` (7) |
| 健康 Key 轮询 | `ProviderKeySelector` 轮询健康 Key | `ProviderKeySelectorTest` (4) |

### 7.3 Provider 健康探测

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 定时健康检查 | `ProviderHealthScheduler` 定期探测上游 | `ProviderHealthScheduler`（通过集成验证） |
| 运行时状态暴露 | `GET /internal/providers/runtime` | `InternalProviderStateController`（通过集成验证） |

### 7.4 全局防护

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 维护模式 | 所有请求返回 503 `maintenance_mode` | `OperationalGateServiceTest` (15) |
| 紧急限流 | 全局 RPM 上限, 429 `emergency_rate_limited` | 同上 |
| 白名单绕过 | 维护模式下白名单 token 可访问 | 同上 |

**源码**: `RouteResilienceTracker.java`, `ProviderKeyResilienceTracker.java`, `ProviderKeySelector.java`, `ProviderHealthScheduler.java`, `OperationalGateService.java`

---

## 8. 上游适配器

| 适配器 | 类型标识 | 认证方式 | 流式 | 测试 |
|--------|----------|----------|------|------|
| OpenAI Compatible | `openai-compatible` | Bearer | ✅ | `OpenAiCompatibleChatProviderAdapterTest` (10) |
| Anthropic | `anthropic` | x-api-key | ✅ | `AnthropicChatProviderAdapterTest` (11) |
| Gemini | `gemini` | x-goog-api-key | ✅ | `GeminiChatProviderAdapterTest` (10) |

### 7.1 跨供应商标准化

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 响应归一化 | 所有适配器输出 OpenAI-compatible shape | `CrossProviderNormalizationTest` (2) |
| 角色校验 | 不支持的消息角色返回 400 | 各适配器测试 |
| finish_reason 映射 | stop/length/content_filter 统一 | 各适配器测试 |
| usage 补齐 | 上游无 usage 时自动计算 | 各适配器测试 |

**源码**: `ChatProviderAdapter.java`（接口）, `UpstreamChatClient.java`, `OpenAiCompatibleChatProviderAdapter.java`, `AnthropicChatProviderAdapter.java`, `GeminiChatProviderAdapter.java`

---

## 9. 配置管理

### 8.1 动态配置

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 写穿透 | 写 ConfigStore + 更新内存 GatewayProperties | `DynamicConfigServiceTest` (20) |
| 业务级即时生效 | providers/routes/scenes/clients 无需重启 | 同上 |
| 系统级即时生效 | limit/resilience/pricing/operational/load-balancer/concurrent-limit/tracing/sync/provider-health/auth 全部 10 种配置持久化后立即生效，无需重启 | `DynamicConfigServiceTest` (29) |
| 启动并发加载 | CompletableFuture.allOf() 5 线程池并行 | 同上 |
| 双写一致性 | ConfigStore + GatewayProperties 同步更新 | 同上 |

### 8.2 配置审计

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 异步审计记录 | 双写（内存环形缓冲区 + ConfigStore） | `ConfigAuditServiceTest` (6) |
| 配置类型追踪 | providers/routes/clients/system 等 | `ConfigAuditControllerTest` (14) |
| 审计日志查询 | `GET /internal/config/audit` 支持过滤 | 同上 |

### 8.3 配置版本与回滚

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 启动快照 | `@PostConstruct` blockLast() 同步快照 | `ConfigVersionServiceTest` (6) |
| 版本保存 | 异步保存 `{type}:{key}:v{version}` | 同上 |
| 版本历史查询 | `GET /internal/config/versions/{type}/{key}` | `ConfigAuditControllerTest` |
| 配置回滚 | `POST /internal/config/rollback/{type}/{key}/{version}` | `ConfigAuditControllerTest` |

### 8.4 Redis Pub/Sub 同步

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 跨实例通知 | 通道 `{keyPrefix}:config:sync` | `RedisConfigStoreTest` (5) |
| 自动重载 | 订阅者收到通知后 load 最新配置 | 同上 |

**源码**: `DynamicConfigService.java`, `ConfigAuditService.java`, `ConfigVersionService.java`, `ConfigStore.java`

---

## 10. 管理控制

### 10.1 Admin CRUD

| 资源 | 操作 | 测试 |
|------|------|------|
| 供应商 | 列表/创建/更新/删除/测试/模型列表/运行时/发现 | `AdminConfigControllerTest` (75) |
| 路由 | 列表/创建/更新/删除 | 同上 |
| 路由管理 CRUD | `GET/PUT/DELETE /admin/routes/{id}` 路由配置管理 | `AdminConfigControllerTest` (新) |
| 客户端 | 列表/创建/更新/删除 | 同上 |
| 用户 | 列表/创建/更新/删除/重置密码 | 同上 |
| 用户限额 | `PUT /admin/users/{username}/limits` 修改用户使用量限额 | `AdminConfigControllerTest` (4, 新) |
| 用户模型白名单 | `PUT /admin/users/{username}/allowed-models` 管理员修改用户允许模型 | `AdminConfigControllerTest` (新) |
| 用户 API Key | `GET/POST/PATCH/DELETE /admin/users/{username}/api-keys` 管理员管理用户 Key | `AdminConfigControllerTest` (6, 新) |
| 管理员 Key 轮换 | `POST /admin/users/{username}/api-keys/{keyId}/rotate` 管理员轮换用户 Key | `AdminConfigControllerTest` (新) |
| 配置导入 | `POST /admin/config/import` 批量导入 providers/routes/scenes/clients | `AdminConfigControllerTest` (新) |
| 配置导出 | `GET /admin/config/export` 导出全部配置 | `AdminConfigControllerTest` (新) |
| 系统配置 | limit/resilience/pricing/operational/load-balancer/concurrent-limit/tracing/sync/provider-health/auth 全部 10 种持久化 | `AdminSystemConfigControllerTest` (12) |
| 告警 | 列表查询 | 同上 |
| 模型分组 | 列表/创建/更新/删除 | `ModelGroupControllerTest` (7) |
| 触发同步 | `POST /admin/sync/models-dev` | `ModelsDevSyncSchedulerTest` (3) |
| 请求日志 | 近期日志查询, 支持 client/model/status 过滤 | — |

**前端**: `/admin/providers`, `/admin/groups`, `/admin/users`, `/admin/requests`, `/admin/quotas`, `/admin/costs`, `/admin/audit`, `/admin/settings`, `/usage`

**源码**: `AdminConfigController.java`, `ModelGroupController.java`

## 11. 管理与审计

管理能力统一收敛在 `/admin` 与 `/internal` 端点，完整接口定义以 `/v3/api-docs` 为准。Webhook 投递与审计能力仍保留，并通过管理端点暴露。

---

## 11. 可观测性

### 11.1 请求日志

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 请求记录 | `RequestLogService` 异步记录请求详情 | `InternalRequestLogControllerTest` (5) |
| 近期日志查询 | `GET /internal/requests/recent` (client/model/status 过滤) | 同上 |
| 请求详情 | `GET /internal/requests/{requestId}` | 同上 |
| 用户侧用量记录 | `GET /auth/usage/recent` | `InternalUsageSummaryControllerTest` (17) |

### 11.2 聚合报表

| 功能项 | 说明 | 测试 |
|--------|------|------|
| 用量汇总 | `GET /internal/usage/summary` (client/day 过滤) | `InternalUsageSummaryControllerTest` (17) |
| 成本汇总 | `GET /internal/cost/summary` | 同上 |
| 按模型成本 | `GET /internal/cost/by-model` | 同上 |
| 客户端成本明细 | `GET /internal/cost/client` 按 client+model 拆分 promptTokens/completionTokens | 同上 |
| 供应商报表 | `GET /internal/reporting/providers` | 同上 |
| 用户报表 | `GET /internal/reporting/users` | 同上 |
| Key 报表 | `GET /internal/reporting/keys` | 同上 |

### 11.3 指标（2026-05 升级）

| 功能项 | 说明 |
|--------|------|
| Micrometer 计数器 | `gateway.request.count`, `gateway.request.outcome` |
| Micrometer 计时器 | `gateway.request.latency` |
| **配额指标** | `gateway.quota.daily.remaining`, `gateway.quota.monthly.remaining` (Gauge, 新) |
| **断路器指标** | `gateway.circuit_breaker.state` (0/1, 新) |
| **上游延迟** | `gateway.upstream.latency` (Timer, 新) |
| **Prometheus 端点** | `GET /actuator/prometheus` 导出 Prometheus 抓取格式 (新) |
| **结构化日志** | `logback-spring.xml` 配置 JSON 格式日志 (logstash-logback-encoder, 新) |
| OpenTelemetry 追踪 | 可配置采样率 |

### 11.4 Dashboard（前端 Mock 状态）

| 需求项 | 现状 |
|--------|------|
| KPI-平均延迟 | 后端无延迟字段, 前端 Mock 推导 |
| 趋势图 | 无统一趋势接口, 由 recent 本地聚合 |
| Top 模型/用户排名 | 无榜单接口, 前端聚合 |
| 角色切换口径 | 前端本地过滤 |

**源码**: `RequestLogService.java`, `AggregateReportingService.java`, `GatewayMetricsRecorder.java`

---

## 12. 同步集成

### 12.1 models.dev 同步

| 功能项 | 说明 | 测试 |
|--------|------|------|
| API 客户端 | 调用 models.dev API 获取模型列表 | `ModelsDevClient.java` |
| 定时同步 | `@Scheduled` 定时从 models.dev 拉取 | `ModelsDevSyncSchedulerTest` (3) |
| 增量同步 | 解析 models.dev 响应并持久化 | `ModelsDevSyncServiceTest` (2) |
| 手动触发 | `POST /admin/sync/models-dev` | `AdminSystemConfigControllerTest` |

### 12.2 Provider 模型发现

| 功能项 | 说明 |
|--------|------|
| Provider 模型探测 | `ProviderDiscoveryService` 从 provider 获取模型列表 |
| 模型目录 | `ProviderModelCatalogService` 汇总所有供应商模型 |
| 模型持久化 | `ProviderModelPersistenceService` 持久化模型信息 |

**源码**: `ModelsDevSyncService.java`, `ModelsDevSyncScheduler.java`, `PricingSyncService.java`, `ProviderDiscoveryService.java`

---

## 13. Webhook 事件（最小实现切片）

Webhook 事件通知已实现**最小切片**（非完整产品面）：

- 管理端接口：`GET/POST/PUT/DELETE /admin/webhooks`（端点 CRUD/列表）
- 投递日志：`GET /admin/webhooks/deliveries`（最近日志读回）
- 事件触发：`GET /admin/alerts` 成功后，best-effort 异步触发 `alert.triggered`
- 持久化：投递前写 `pending`，成功写 `delivered`，失败写 `failed`
- 可选签名：`endpoint.secret` 非空白时附加 `X-Webhook-Timestamp` 和 `X-Webhook-Signature`（HMAC-SHA256）；空白 secret 保持无签名投递

当前仍未实现：重试/队列/调度器、通用事件总线、治理域（Employee/Group）联动。

---

## 14. 共享状态后端

支持 InMemory（单实例）和 Redis（多实例）双重后端，组件一致性切换：

| 组件 | InMemory | Redis | Postgres |
|------|----------|-------|----------|
| ConfigStore | `InMemoryConfigStore` | `RedisConfigStore` | `PostgresConfigStore` |
| RateLimiter | `InMemoryRateLimiter` | `RedisRateLimiter` | `PostgresClientRateLimiter` |
| TpmStore | `InMemoryClientTpmStore` | `RedisClientTpmStore` | `PostgresClientTpmStore` |
| UsageStore | `InMemoryClientUsageStore` | `RedisClientUsageStore` | `PostgresClientUsageStore` |
| CostStore | `InMemoryClientCostStore` | `RedisClientCostStore` | `PostgresClientCostStore` |
| RouteStateStore | `InMemoryRouteStateStore` | `RedisRouteStateStore` | `PostgresRouteStateStore` |
| ProviderRuntimeStore | `InMemoryProviderRuntimeStateStore` | `RedisProviderRuntimeStateStore` | `PostgresProviderRuntimeStateStore` |
| AggregateMetricStore | `InMemoryAggregateMetricStore` | `RedisAggregateMetricStore` | `PostgresAggregateMetricStore` |

**配置**: `gateway.shared-state.backend=in_memory|redis|postgresql|hybrid`

**测试**: `InMemoryConfigStoreTest` (12), `PostgresConfigStoreTest` (7), `RedisConfigStoreTest` (5), `RedisRouteStateStoreTest` (3), `RedisRateLimiterTest` (1), `RedisClientUsageStoreTest` (2), `RedisClientCostStoreTest` (2), `RedisIntegrationTestSupport`, `PostgresAggregateMetricStoreTest` (4)

---

## 15. 端到端集成测试

| 测试类 | 用例 | 说明 |
|--------|------|------|
| `EndToEndApiTest` | 13 | 管理接口主流程 |
| `CoreFlowIntegrationTest` | 12 | 核心请求管道完整流程 |
| `AuthDisabledIntegrationTest` | 2 | 认证禁用模式 |
| `MockUpstreamIntegrationTest` | 4 | Mock 上游全链路 |

---

## 附录：覆盖率缺口

| 缺口 | 严重度 | 说明 |
|------|--------|------|
| 部分管理流程无独立单元测试 | 中 | 依赖 `EndToEndApiTest` 端到端覆盖 |
| Webhook PostgreSQL 实证依赖 Docker/Testcontainers | 中 | 已新增 `WebhookPostgresIntegrationTest` 覆盖 JSON round-trip 与最小投递流；本地无 Docker 时可能 skip，需在 CI/可用 Docker 环境执行 |
| TPM 控制台未消费 | 低 | 后端实现存在但前端报表未集成 |
| Dashboard 部分 Mock | 低 | 延迟/趋势/Top 榜单前端聚合 |
| Provider runtime/discovery 控制台未消费 | 低 | 后端暴露但前端未集成 |
| 集成测试（Testcontainers）覆盖不足 | 中 | 核心端到端流程已覆盖，Redis/PostgreSQL 增强场景待补充 |

---

## 附录：最近更新记录

| 日期 | 变更 | 说明 |
|------|------|------|
| 2026-05-03 | P0 安全补强 | JWT 校验、请求体限制、CORS、登录防爆破 |
| 2026-05-03 | P1 可观测性 | Prometheus 导出、JSON 日志、扩展指标、健康分层 |
| 2026-05-03 | 用户限额 | UserAccount 新增 UserLimits，管理员 CRUD + 回退链 |
| 2026-05-03 | 测试补充 | +19 个测试覆盖管理员 API Key 管理、限额、密码修改 |
| 2026-05-03 | Key 轮换 + 过期 | 原子轮换端点（管理员+自助）、expiresAt 字段、管理端 allowedModels CRUD |
| 2026-05-03 | 配置导入导出 | `GET/POST /admin/config/export` 批量配置管理 |
| 2026-05-03 | 模型过滤增强 | `/v1/models` 按 key allowedModels 过滤；models.dev 能力解析（supports_files/images/vision/audio/tools） |
| 2026-05-03 | 测试补充 | +25 个测试覆盖以上所有新功能，总测试数 472 |
| 2026-05-23 | API Key 轮换 | 用户自助 `POST /auth/keys/{keyId}/rotate` + 管理员 `POST /admin/users/{username}/api-keys/{keyId}/rotate` |
| 2026-05-23 | 路由管理 CRUD | `GET/PUT/DELETE /admin/routes/{id}` 路由配置完整管理 |
| 2026-05-23 | 配置导入导出 | `POST /admin/config/import` 批量导入 + `GET /admin/config/export` 全量导出 |
| 2026-05-23 | 用户限额编辑器 | `PUT /admin/users/{username}/limits` 管理员修改用户使用量限额 |
| 2026-05-23 | 用户模型白名单 | `PUT /admin/users/{username}/allowed-models` 管理员修改用户允许模型 |
| 2026-05-23 | 客户端成本明细 | `GET /internal/cost/client` 按 client+model 拆分 promptTokens/completionTokens |
| 2026-05-23 | 用量页面 | 前端 `/usage` 页面对接用量与成本数据 |
| 2026-05-23 | 测试补充 | 总测试数 649 后端 + 64 前端 + 46 E2E |
| 2026-05-26 | RedisTraceStore 实现 | RedisTraceStore 消除静默降级，+8 测试 |
| 2026-05-26 | Metrics 单测补全 | GatewayMetricsRecorderTest (11) + PrometheusMetricsTest (3) |
| 2026-05-26 | 运维恢复循环测试 | CoreFlowIntegrationTest provider runtime 断言 + MockUpstreamIntegrationTest CB 完整恢复循环 |
| 2026-05-26 | 生产 Bug 修复 | ChatCompletionsOrchestrator 非流式 error 路径补全 recordFailure() |
| 2026-05-26 | 文档清理 | Webhook/Employee 虚假章节归档, README 维护 |
| 2026-06-03 | 全部 11 种系统配置热更新 | 新增 6 种系统配置持久化 + 即时生效，CB/Retry/Bulkhead 对象运行中刷新 |
| 2026-06-03 | 架构债务 P6 清理 | admin 独立 @ComponentScan、模块重复消除、AuthController 标废弃 |
| 2026-06-03 | Provider 删除引用检查 | deleteProvider 检查 route 引用，有引用时返回 409 |
| 2026-06-04 | M2 计费管线修复 | ModelsDevSnapshot 新增 modelPricings 字段，双价格同步修复 |
| 2026-06-04 | M3 可靠性补强 | ErrorCode 增加 CIRCUIT_BREAKER_OPEN、GatewayException cause 构造函数、适配器错误体透传、非 GatewayException 兜底 502/504 |
| 2026-06-04 | 假绿治理完成 | 去 LENIENT、Postgres SQL 精确匹配、Redis 边界增强、Optional.empty 分支补全 |
| 2026-06-04 | 黑盒回归扩充至 122 场景 | 新增 TPM/并发/CB/超时/运维门禁/双价格/内容安全/配置热更新 8 类场景 |
| 2026-06-04 | M4 审计前端完成 | OperationsPage 4 Tab（Alerts/Logs/Audit/Cost）、offset 分页、详情弹窗、配置审计 6 列 |
| 2026-06-05 | CI 全量测试切换 | 从聚焦子集扩展为运行全部非 Testcontainers 测试 |
| 2026-06-05 | 跨模块 CB 测试拆分 | MockUpstreamIntegrationTest 剥离 admin 端点断言至 ChatCompletionsControllerIT |
| 2026-06-05 | Webhook 单元测试补齐 | WebhookEndpointService/Dispatcher/DeliveryLog 3 文件纯 Mockito 测试，~20 用例 |
| 2026-06-05 | Aggregate 可观测性测试 | AggregateReportingService/AggregateMetricRecorderImpl/InMemory/Redis 4 文件 |
| 2026-06-05 | Admin Controller 测试补齐 | Provider/Route/SystemConfig/User/Alerts 5 文件，~80 用例 |
| 2026-06-05 | Admin Service 测试补齐 | ProviderHealthScheduler/ModelMetadata/Catalog/List/TPM 6 文件 |
| 2026-06-05 | DTO + Config + Utility 测试 | 16 个文件覆盖 12 个 DTO、4 个 Config、4 个 Utility |
| 2026-06-05 | 核心 Web 层测试归位 + 补齐 | ChatCompletionsOrchestratorTest 移入 core；5 个新测试（CompletionRecorder/ConfigMasking/Health/ModelListProvider/RequestIdFilter） |
| 2026-06-05 | 孤儿测试清理 | 3 个无源类测试文件删除、1 个保留验证 |
| 2026-06-05 | bootstrapper 黑盒补充 | verify-supplement.sh 新 6 场景（Webhook/Runtime/Discovery/Alerts/Audit/SystemLimit） |
| 2026-06-05 | 文档同步 | features.md 测试统计、缺口表、章节编号、Local JSON fallback 声明同步更新 |

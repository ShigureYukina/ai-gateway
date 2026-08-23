# Simple AI Gateway API Reference

> 本文是**人工维护的稳定 API 参考**，面向“接口怎么调、字段语义是什么、常见边界和错误是什么”。
> - 如果你想按步骤跑通接入、配置和排障，请先看 [`usage.md`](./usage.md)。
> - 如果你要导入 Swagger、生成 SDK 或做契约校验，请以 [`openapi.json`](./openapi.json) / 运行时 `/v3/api-docs` 为准。
> - 本文不追求复刻全部 schema，而是保留最常查的接口语义、关键字段、最小示例和注意事项。

Swagger UI: `http://localhost:8081/swagger-ui.html`（启动后访问）  
OpenAPI JSON: `http://localhost:8081/v3/api-docs`

---

## 鉴权

### API Key 方式

所有受保护端点（`/v1/chat/completions`）需要在 `Authorization` 头中携带网关客户端 API Key：

```http
Authorization: Bearer <gateway-client-api-key>
```

默认示例 key：`demo-client-key`。

### JWT 方式

当 `gateway.auth.enabled=true` 时，可通过 `/auth/login` 端点获取 JWT access token：

```http
Authorization: Bearer <jwt-access-token>
```

- Access token 有效期：30 分钟（可配置）
- Refresh token 有效期：24 小时（可配置）
- JWT claims: `sub`（username）、`scope`（allowedModels）、`role`、`typ`、`tokenVersion`、`iat`/`exp`；需要时会额外包含 `clientId` claim

### 双通道优先级

```
请求 → Authorization: Bearer <token>
  ├── auth.enabled=true
  │   ├── JWT 解析成功 && typ=access && client 有效 → ✅ JWT 用户
  │   ├── 匹配已注册用户 API Key → ✅ 动态用户
  │   └── 回退静态 API Key
  └── auth.enabled=false
      └── 仅走静态 API Key
```

### 角色与权限

| 角色 | 权限 |
|------|------|
| `admin` | admin_full, manage_system, view_system |
| `operator` | view_system |
| `viewer` | view_system |
| `user` | 仅自助端点 |

> 流程型说明（例如“先登录再创建 key，再调用 chat”）请看 [`usage.md`](./usage.md)。

---

## 错误响应格式

所有错误统一返回：

```json
{
  "code": "error_code",
  "message": "Human-readable description",
  "requestId": "req_xxx"
}
```

### 错误码表

| HTTP | code | 说明 |
|------|------|------|
| 400 | `invalid_request` | 请求参数校验失败 |
| 400 | `unknown_model` | 模型未在路由中注册 |
| 400 | `stream_not_supported` | 客户端未启用流式能力 |
| 400 | `max_tokens_exceeded` | 默认 max_tokens 超限 |
| 400 | `bad_request` | 通用请求错误 |
| 401 | `unauthorized` | 认证失败 / 缺少 Authorization 头 |
| 401 | `invalid_credentials` | 用户名或密码错误 |
| 401 | `invalid_token` | JWT 无效 |
| 401 | `token_expired` | JWT 已过期 |
| 401 | `invalid_token_type` | token 类型不匹配（如用 refresh token 调接口） |
| 403 | `forbidden` | 权限不足 |
| 403 | `forbidden_model` | 模型不在客户端允许列表中 |
| 403 | `forbidden_scene` | 场景不在客户端允许列表中 |
| 403 | `account_frozen` | 账户已冻结 |
| 404 | `not_found` | 资源不存在 |
| 404 | `user_not_found` | 用户不存在 |
| 404 | `key_not_found` | API Key 不存在 |
| 409 | `conflict` | 资源冲突（如重名） |
| 409 | `username_taken` | 用户名已被注册 |
| 429 | `rate_limited` | 请求频率超限（RPM） |
| 429 | `concurrent_limit_exceeded` | 并发请求数超限 |
| 429 | `quota_exceeded` | 日 token 配额超限 |
| 429 | `monthly_quota_exceeded` | 月 token 配额超限 |
| 429 | `budget_exceeded` | 日成本预算超限 |
| 429 | `monthly_budget_exceeded` | 月成本预算超限 |
| 429 | `tpm_exceeded` | 每分钟 token 数超限 |
| 429 | `emergency_rate_limited` | 全局紧急限流 |
| 500 | `internal_error` | 服务端内部错误 |
| 502 | `upstream_error` | 上游供应商错误 |
| 503 | `maintenance_mode` | 系统维护模式 |
| 504 | `upstream_timeout` | 上游供应商超时 |

> 说明：这里列的是人工维护的常见错误语义；字段结构与运行时契约仍以 OpenAPI 为准。

---

## 业务端点

### POST /v1/chat/completions

OpenAI 兼容 Chat Completions 接口，支持流式和非流式。

**认证**: Bearer token（JWT 或 API Key）

**用途**: 这是网关最核心的对外调用入口。一次请求通常会经过认证、模型授权、限流/配额/预算检查、路由解析、上游调用与 fallback。

**请求体**:

```json
{
  "model": "gpt-4o-mini",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user", "content": "Hello!"}
  ],
  "temperature": 0.7,
  "max_tokens": 256,
  "stream": false
}
```

**响应**（非流式）:

```json
{
  "id": "chatcmpl-xxx",
  "object": "chat.completion",
  "created": 1234567890,
  "model": "gpt-4o-mini",
  "choices": [
    {
      "index": 0,
      "message": {"role": "assistant", "content": "Hello! How can I help?"},
      "finish_reason": "stop"
    }
  ],
  "usage": {"prompt_tokens": 10, "completion_tokens": 8, "total_tokens": 18}
}
```

**响应**（流式，`Content-Type: text/event-stream`）:

```
data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"delta":{"content":"Hello"},"index":0}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"delta":{},"finish_reason":"stop","index":0}],"usage":{"prompt_tokens":10,"completion_tokens":8,"total_tokens":18}}

data: [DONE]
```

### GET /v1/models

返回可用模型列表（来自 models.dev 快照或本地配置）。

**认证**: 无

**用途**: 给调用方发现当前可用模型。无 admin 能力时，系统可能降级返回空列表并带降级提示头。

**响应**:

```json
{
  "object": "list",
  "data": [
    {"id": "gpt-4o-mini", "object": "model", "created": 1234567890, "owned_by": "openai"}
  ]
}
```

### GET /healthz

健康检查端点，聚合所有 `HealthIndicator`（PG 连接、Redis 连接、系统状态）。

**认证**: 无

**响应**:

```json
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "redis": {"status": "UP"},
    "gateway": {"status": "UP"}
  }
}
```

### GET /healthz/live

存活探针，检查进程是否存活。

**认证**: 无

### GET /healthz/ready

就绪探针，检查进程是否可以接受流量。

**认证**: 无

---

## 认证端点 (`/auth`)

> 这些接口主要服务于 JWT 用户、自助 API key 与个人用量查询；对机器到机器的最小接入流程，先看 [`usage.md`](./usage.md)。

### POST /auth/login

用户登录，返回 JWT access + refresh token。

**用途**: 交互式用户会话入口。后续可调用 `/auth/me`、`/auth/keys`、`/auth/usage/*` 等自助接口。

**请求体**:

```json
{"username": "admin", "password": "admin123"}
```

**响应**:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer"
}
```

### POST /auth/refresh

刷新 access token。

**注意**: refresh token 会参与轮换与黑名单控制，错误类型与 access token 不同。

**请求体**:

```json
{"refreshToken": "eyJhbGciOiJIUzI1NiJ9..."}
```

### POST /auth/logout

注销（refresh token 加入黑名单）。

**注意**: 注销成功后的具体状态码可因实现细节返回 `200` 或 `204`，语义上都表示 refresh token 已失效。

**请求体**:

```json
{"refreshToken": "eyJhbGciOiJIUzI1NiJ9..."}
```

### POST /auth/register

注册新用户。

**请求体**:

```json
{"username": "newuser", "password": "pass123"}
```

**响应**: 返回 `apiKey`、`accessToken`、`refreshToken`。

**注意**: 注册返回的是“可立即使用”的主身份信息，适合体验或自助接入。

### GET /auth/me

当前用户信息 + 配额摘要。

**认证**: Bearer

**响应**:

```json
{
  "username": "admin",
  "role": "admin",
  "apiKey": "demo-client-key",
  "createdAt": "2026-04-29T00:00:00Z",
  "quota": {
    "dailyTokens": 12345,
    "dailyCost": 0.5,
    "monthlyTokens": 123456,
    "monthlyCost": 5.0
  }
}
```

### PUT /auth/password

修改密码。

**认证**: Bearer

**请求体**:

```json
{"oldPassword": "old123", "newPassword": "new456"}
```

### GET /auth/usage/recent

近期用量记录。

**认证**: Bearer

### GET /auth/usage/costs

获取当前用户按模型的成本分布。

**认证**: Bearer

**查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `from` | `string` | 否 | 起始日期（ISO-8601） |
| `to` | `string` | 否 | 截止日期（ISO-8601） |

> 说明：当前支持的自助用量接口为 `GET /auth/usage/recent` 与 `GET /auth/usage/costs`。`GET /auth/usage/summary` 不在当前已实现/支持的 API surface 中，请勿按该路径接入；需要汇总口径时使用管理/内部侧 `GET /internal/usage/summary`。

### POST /auth/keys

创建 API Key。

**认证**: Bearer

**请求体**:

```json
{"name": "my-key"}
```

**响应**:

```json
{"id": "primary", "name": "my-key", "key": "gw-xxxx...", "status": "active"}
```

### GET /auth/keys

列出 API Keys（脱敏）。

### PATCH /auth/keys/{keyId}

更新 API Key（启用/禁用）。

### DELETE /auth/keys/{keyId}

删除 API Key。

### POST /auth/keys/{keyId}/rotate

轮换 API Key（旧 Key 立即失效，签发新 Key）。

**认证**: Bearer

**路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| `keyId` | `string` | 要轮换的 API Key ID |

**响应**:

```json
{
  "id": "primary",
  "name": "my-key",
  "key": "gw-yyyy...",
  "status": "active",
  "rotatedFrom": "gw-xxxx..."
}
```

---

## 管理端点 (`/admin`) — admin 角色

所有 `/admin` 端点需要 admin 角色的 Bearer token。

### 供应商管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/admin/providers` | 供应商列表（apiKey 脱敏） |
| `PUT` | `/admin/providers/{name}` | 创建/更新供应商 |
| `DELETE` | `/admin/providers/{name}` | 删除供应商 |
| `POST` | `/admin/providers/{name}/test` | 测试供应商连接 |
| `GET` | `/admin/providers/{name}/models` | 供应商模型列表 |
| `PUT` | `/admin/providers/{name}/models` | 手工覆盖供应商模型目录 |
| `POST` | `/admin/providers/{name}/models/fetch` | 立即从上游抓取供应商模型目录 |
| `GET` | `/admin/providers/runtime` | 供应商运行时状态 |
| `GET` | `/admin/providers/discovery` | 供应商发现结果 |

**PUT /admin/providers/{name} 请求体**:

```json
{
  "type": "openai-compatible",
  "baseUrl": "http://localhost:8081",
  "apiKey": "sk-xxx",
  "timeout": "10s"
}
```

#### POST /admin/providers/{name}/test

对指定 provider 做一次轻量连通性测试，用于区分“配置能否访问上游”和“业务路由是否正常”。该接口适合在刚保存 provider 后立即调用。

**认证**: Bearer（admin 角色）

**响应语义**:

- 常见成功状态为 `200`
- 黑盒验证已确认响应至少包含 `status`
- `status` 当前可见值包括 `ok`、`error`

**响应示例**:

```json
{
  "status": "ok"
}
```

> 说明：`error` 更偏“可达但测试失败/上游返回异常”的诊断语义，不等价于接口路径不存在。

#### GET /admin/providers/{name}/models

读取当前 provider 关联的模型目录快照，可来自上游抓取结果，也可来自手工写入的覆盖值。

**认证**: Bearer（admin 角色）

**响应要点**:

- 黑盒验证已确认包含 `provider`
- 黑盒验证已确认包含 `generatedAt`
- `models` 为数组

**响应示例**:

```json
{
  "provider": "mock",
  "generatedAt": "2026-06-05T12:00:00Z",
  "models": ["gpt-4o-mini", "gpt-4.1-mini"]
}
```

#### PUT /admin/providers/{name}/models

手工写入 provider 的模型目录覆盖值，适合上游无稳定 `/models` 能力、需要临时固定候选模型，或想在抓取前先提供可用目录的场景。

**认证**: Bearer（admin 角色）

**请求体**:

```json
{
  "models": ["gpt-4o-mini", "gpt-4.1-mini"]
}
```

**响应**: 当前黑盒验证为 `204 No Content`。

#### POST /admin/providers/{name}/models/fetch

立即从上游供应商抓取模型目录，并返回本次抓取结果；适合在 provider 已联通后主动刷新目录，而不是等待后台同步。

**认证**: Bearer（admin 角色）

**响应要点**:

- 常见成功状态为 `200`
- 黑盒验证已确认返回 `provider`
- 黑盒验证已确认返回 `models` 数组

**响应示例**:

```json
{
  "provider": "mock",
  "models": ["gpt-4o-mini", "gpt-4.1-mini"]
}
```

### 路由管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/admin/routes` | 路由列表 |
| `PUT` | `/admin/routes/{id}` | 创建/更新路由 |
| `DELETE` | `/admin/routes/{id}` | 删除路由 |

### 客户端管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/admin/clients` | 客户端列表（key 脱敏） |
| `PUT` | `/admin/clients/{key}` | 创建/更新客户端 |
| `DELETE` | `/admin/clients/{key}` | 删除客户端 |

**PUT /admin/clients/{key} 请求体**:

```json
{
  "enabled": true,
  "allowedModels": ["gpt-4o-mini", "gpt-4o"],
  "defaults": {"scene": "default-chat", "temperature": 0.7},
  "capabilities": {"streaming": true},
  "limits": {"maxTokens": 512, "dailyTokens": 200000, "dailyCost": 20.0}
}
```

### 用户管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/admin/users` | 用户列表 |
| `POST` | `/admin/users` | 创建用户 |
| `PUT` | `/admin/users/{username}` | 更新用户（角色/冻结） |
| `DELETE` | `/admin/users/{username}` | 删除用户 |
| `POST` | `/admin/users/{username}/reset-password` | 重置密码 |
| `PUT` | `/admin/users/{username}/limits` | 更新用户使用量限额 |
| `PUT` | `/admin/users/{username}/allowed-models` | 更新用户允许模型白名单 |
| `POST` | `/admin/users/{username}/api-keys/{keyId}/rotate` | 轮换用户 API Key |

### 系统配置

> **热更新**：系统级配置（limit / resilience / pricing / operational）修改后支持热更新，可即时生效。
> 部分响应中如仍出现 `X-Pending-Restart*` 历史兼容头，不应再解读为“必须重启后才生效”。

| 方法 | 路径 | 说明 |
|------|------|------|
| `PUT` | `/admin/system/limit` | 限流配置（支持热更新） |
| `PUT` | `/admin/system/resilience` | 熔断配置（支持热更新） |
| `PUT` | `/admin/system/pricing` | 定价配置（支持热更新） |
| `PUT` | `/admin/system/operational` | 运维配置（支持热更新） |

**兼容性响应头**（部分 `PUT /admin/system/*` 响应可能出现）：

| 响应头 | 类型 | 说明 |
|--------|------|------|
| `X-Pending-Restart` | `string` | 历史兼容头；如出现，不应再解读为“本次配置必须重启后才生效” |
| `X-Pending-Restart-Keys` | `string` | 历史兼容头；保留原命名，仅用于兼容旧响应语义 |

---

#### PUT /admin/system/limit

**认证**: Bearer（admin 角色）

**请求体**:

```json
{
  "requestsPerWindow": 120,
  "window": "PT1M"
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `requestsPerWindow` | `int` | `60` | 每个时间窗口允许的最大请求数 |
| `window` | `Duration` | `PT1M` | 时间窗口长度（ISO-8601 格式，如 `PT30S`、`PT5M`） |

**响应**:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "requestsPerWindow": 120,
  "window": "PT1M"
}
```

---

#### PUT /admin/system/resilience

**认证**: Bearer（admin 角色）

**请求体**:

```json
{
  "maxAttempts": 3,
  "retryableFailureThreshold": 5,
  "failureWindow": "PT1M",
  "openDuration": "PT1M"
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `maxAttempts` | `int` | `2` | 单次请求最大尝试次数 |
| `retryableFailureThreshold` | `int` | `2` | 触发熔断的可重试失败次数阈值 |
| `failureWindow` | `Duration` | `PT30S` | 失败计数的滑动窗口 |
| `openDuration` | `Duration` | `PT30S` | 熔断器打开状态持续时间 |

**响应**:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "maxAttempts": 3,
  "retryableFailureThreshold": 5,
  "failureWindow": "PT1M",
  "openDuration": "PT1M"
}
```

---

#### PUT /admin/system/pricing

**认证**: Bearer（admin 角色）

**用途**:

- 配置系统级默认价格与按模型覆盖价格。
- 当前还支持通过 `exactMatches` 把别名模型映射到一个已同步的 canonical model，用于 no-DDL 的“名称精确映射 → synced pricing”场景。
- 当前计费解析优先级为：`manual override → exact match → fuzzy normalized-name fallback → configured default`。

**请求体**:

```json
{
  "default": {
    "unitPrice": 0.002
  },
  "models": {
    "gpt-4o": {
      "unitPrice": 0.005
    },
    "gpt-4o-mini": {
      "unitPrice": 0.0015
    }
  },
  "exactMatches": {
    "alias-model": "gpt-4o"
  }
}
```

也支持 input/output 双价：

```json
{
  "default": {
    "inputUnitPrice": 0.002,
    "outputUnitPrice": 0.008
  },
  "models": {
    "openai/gpt-5-pro": {
      "inputUnitPrice": 15,
      "outputUnitPrice": 120
    }
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `default.unitPrice` | `BigDecimal` | 默认单价（美元 / 1K tokens） |
| `models.<model>.unitPrice` | `BigDecimal` | 按模型名覆盖的单价 |
| `models.<model>.inputUnitPrice` | `BigDecimal` | 输入侧单价；存在时优先于 `unitPrice` |
| `models.<model>.outputUnitPrice` | `BigDecimal` | 输出侧单价；存在时优先于 `unitPrice` |
| `exactMatches.<alias>` | `string` | 把请求模型名精确映射到一个已同步 canonical model（如 `openai/gpt-5-pro` 或 `gpt-4o`） |

**响应**:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "default": {
    "unitPrice": 0.002
  },
  "models": {
    "gpt-4o": {
      "unitPrice": 0.005
    },
    "gpt-4o-mini": {
      "unitPrice": 0.0015
    }
  },
  "exactMatches": {
    "alias-model": "gpt-4o"
  }
}
```

**注意**:

- `models` 中直接配置价格时，属于手工覆盖（manual override）。
- `exactMatches` 只负责把别名映射到某个 synced model，本身不提供价格；若目标模型在 synced pricing 中不存在，会继续走后续 fallback。
- 当前 fuzzy fallback 仅覆盖保守的名称归一化差异（trim / lowercase / 分隔符归一），不会做 substring / Levenshtein / 猜测式匹配。

#### GET /admin/system/pricing/resolve

预览某个模型请求最终会命中哪条计费规则、来源和价格。

**认证**: Bearer（admin 角色）

**查询参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| `model` | `string` | 请求模型名（必填） |
| `upstreamModel` | `string` | 可选，上游模型名 |
| `provider` | `string` | 可选，当前仅回显在结果中，便于调试 |

**响应示例**:

```json
{
  "requestedModel": "alias-model",
  "upstreamModel": "gpt-4o",
  "provider": "openai",
  "matchedModel": "gpt-4o",
  "source": "manual_override",
  "matchedBy": "manual_override",
  "unitPrice": 0.0003,
  "inputUnitPrice": 0.0003,
  "outputUnitPrice": 0.0003,
  "resolved": true
}
```

**已确认语义**:

- `source` 当前可能为：`manual_override`、`synced_pricing`、`configured_default`
- `matchedBy` 用于解释命中原因，如：`manual_override`、`exact_mapping`、`exact_match`、`fuzzy_name_fallback`、`default_price`
- 该接口适合在管理员配置 `models` / `exactMatches` 或 models.dev 同步后，用来确认实际计费来源是否符合预期

---

#### PUT /admin/system/operational

**认证**: Bearer（admin 角色）

**请求体**:

```json
{
  "maintenanceMode": false,
  "emergencyRateLimit": {
    "enabled": true,
    "maxRequestsPerMinute": 1000
  },
  "maintenanceWhitelist": ["token-to-bypass"]
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `maintenanceMode` | `boolean` | `false` | 是否开启维护模式（所有请求返回 503） |
| `emergencyRateLimit.enabled` | `boolean` | `false` | 是否启用全局紧急限流 |
| `emergencyRateLimit.maxRequestsPerMinute` | `int` | `60` | 紧急限流每分钟最大请求数（≥1） |
| `maintenanceWhitelist` | `string[]` | `[]` | 维护模式下的豁免 token 列表 |

**响应**:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "maintenanceMode": false,
  "emergencyRateLimit": {
    "enabled": true,
    "maxRequestsPerMinute": 1000
  },
  "maintenanceWhitelist": ["token-to-bypass"]
}
```

### 配置导入导出

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/admin/config/import` | 批量导入 providers/routes/scenes/client configs |
| `GET` | `/admin/config/export` | 导出全部配置 |

**POST /admin/config/import 请求体**:

```json
{
  "providers": { "openai": { "type": "openai-compatible", "baseUrl": "...", "apiKey": "sk-xxx" } },
  "routes": { "gpt-4o": { "scene": "default-chat", "providers": ["openai"] } },
  "scenes": { "default-chat": { "temperature": 0.7 } },
  "clients": { "demo-client-key": { "enabled": true, "allowedModels": ["gpt-4o-mini"] } }
}
```

**响应**: 返回导入结果摘要（各类型成功/失败计数）。

```json
{
  "providers": { "imported": 1, "failed": 0 },
  "routes": { "imported": 1, "failed": 0 },
  "scenes": { "imported": 1, "failed": 0 },
  "clients": { "imported": 1, "failed": 0 }
}
```

**GET /admin/config/export 响应**:

```json
{
  "providers": { ... },
  "routes": { ... },
  "scenes": { ... },
  "clients": { ... }
}
```

### 用户限额与模型白名单

#### PUT /admin/users/{username}/limits

更新用户级使用量限额。

**认证**: Bearer（admin 角色）

**路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| `username` | `string` | 目标用户名 |

**请求体**:

```json
{
  "dailyTokens": 200000,
  "monthlyTokens": 5000000,
  "dailyCost": 50.0,
  "monthlyCost": 1000.0,
  "maxRpm": 120,
  "maxConcurrent": 10,
  "maxTpm": 100000
}
```

**响应**: 返回更新后的 `UserLimits` 对象。

#### PUT /admin/users/{username}/allowed-models

更新用户允许的模型白名单。

**认证**: Bearer（admin 角色）

**路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| `username` | `string` | 目标用户名 |

**请求体**:

```json
{
  "allowedModels": ["gpt-4o-mini", "gpt-4o", "claude-3-haiku"]
}
```

**响应**: 返回更新后的用户信息（含 `allowedModels` 字段）。

#### POST /admin/users/{username}/api-keys/{keyId}/rotate

管理员轮换指定用户的 API Key（旧 Key 立即失效，签发新 Key）。

**认证**: Bearer（admin 角色）

**路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| `username` | `string` | 目标用户名 |
| `keyId` | `string` | 要轮换的 API Key ID |

**响应**:

```json
{
  "id": "primary",
  "name": "user-key",
  "key": "gw-zzzz...",
  "status": "active",
  "rotatedFrom": "gw-xxxx..."
}
```

### 用户 API Key 管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/admin/users/{username}/api-keys` | 用户 API Key 列表 |
| `POST` | `/admin/users/{username}/api-keys` | 创建用户 API Key |
| `DELETE` | `/admin/users/{username}/api-keys/{keyId}` | 删除用户 API Key |
| `PATCH` | `/admin/users/{username}/api-keys/{keyId}` | 更新用户 API Key |
| `POST` | `/admin/users/{username}/api-keys/{keyId}/toggle` | 切换用户 API Key 状态 |

### 其他管理端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/admin/alerts` | 告警列表 |
| `POST` | `/admin/sync/models-dev` | 触发 models.dev 同步 |
| `GET` | `/admin/requests/recent` | 近期请求日志（支持 client/model/status 过滤） |
| `GET` | `/admin/model-groups` | 模型分组列表 |
| `PUT` | `/admin/model-groups/{alias}` | 创建/更新模型分组 |
| `DELETE` | `/admin/model-groups/{alias}` | 删除模型分组 |
| `GET` | `/admin/webhooks` | Webhook 端点列表 |
| `POST` | `/admin/webhooks` | 创建 Webhook 端点 |
| `GET` | `/admin/webhooks/{id}` | 获取单个 Webhook 端点 |
| `PUT` | `/admin/webhooks/{id}` | 更新单个 Webhook 端点 |
| `DELETE` | `/admin/webhooks/{id}` | 删除 Webhook 端点 |
| `GET` | `/admin/webhooks/deliveries` | 查看 Webhook 投递记录 |

#### GET /admin/model-groups

返回模型分组（alias）到成员列表的映射，常用于把多个 provider/upstreamModel 组合成一个稳定别名，供路由或上层配置引用。

**认证**: Bearer（admin 角色）

**响应要点**:

- 顶层通常为 `groups` 对象
- 每个 group 下有 `members` 数组
- 黑盒验证已覆盖创建、更新、删除、删除不存在返回 `404`、空 `members` 非 2xx

**响应示例**:

```json
{
  "groups": {
    "chat-default": {
      "members": [
        {"provider": "openai", "upstreamModel": "gpt-4o-mini", "weight": 1},
        {"provider": "openai", "upstreamModel": "gpt-4o", "weight": 2}
      ]
    }
  }
}
```

#### PUT /admin/model-groups/{alias}

创建或更新一个模型分组。

**请求体**:

```json
{
  "members": [
    {"provider": "openai", "upstreamModel": "gpt-4o-mini", "weight": 1}
  ]
}
```

**注意**:

- `members` 不应为空
- 成功状态当前可能为 `200` 或 `201`

#### POST /admin/sync/models-dev

触发一次 models.dev 同步任务，用于刷新模型目录相关数据源。

**认证**: Bearer（admin 角色）

**当前文档口径**:

- 这是“触发型”接口，不保证同步结果已经落盘到所有下游视图
- 黑盒验证当前主要确认接口可触发，不把它当作强结果断言端点
- 是否有可见更新，建议结合 `/v1/models`、`/internal/catalog/providers`、`/internal/snapshots/models-pricing` 等只读端点观察

#### /admin/webhooks

Webhook 用于把管理面事件（当前以 admin CRUD + best-effort delivery 为主）异步投递到外部 HTTP 接收端。

**认证**: Bearer（admin 角色）

**创建示例**:

```json
{
  "name": "ops-alerts",
  "url": "http://localhost:18083",
  "secret": "whsec-test",
  "enabled": true,
  "eventTypes": ["alert.triggered"],
  "retryMax": 1,
  "timeoutMs": 5000
}
```

**已确认语义**:

- `POST /admin/webhooks` 创建后返回对象，包含 `id`、`name`
- `GET /admin/webhooks` 返回 `endpoints` 数组
- `GET /admin/webhooks/{id}` 可回读单资源
- `PUT /admin/webhooks/{id}` 可更新 `name`、`enabled`、`retryMax` 等字段
- `GET /admin/webhooks/deliveries` 返回 `deliveries` 数组，可观察 `endpointId`、`status`

**更新示例响应片段**:

```json
{
  "id": 1,
  "name": "ops-alerts-updated",
  "enabled": false,
  "retryMax": 2
}
```

---

## 文档入口

为避免文档与代码漂移，完整端点与字段定义以运行时 OpenAPI 为准：

- Swagger UI: `/swagger-ui.html`
- OpenAPI JSON: `/v3/api-docs`

本页仅保留常用端点摘要；如需完整 schema、参数与响应，以 OpenAPI 文档为准。

---

## 内部端点 (`/internal`) — Bearer 认证

所有 `/internal/**` 端点需要 Bearer token（通过 `InternalEndpointAuthFilter` 强制）。

### 系统状态

#### GET /internal/system/status

返回系统运维状态快照。

**用途**: 给运维面板或脚本快速判断维护模式、紧急限流和全局路由可用性，不替代细粒度 request / provider 诊断接口。

**已确认语义**:

- `maintenance.active` 为布尔值
- `emergencyRateLimit.enabled` 为布尔值
- `globalCircuit.hasAvailableRoute` 为布尔值

**响应**:

```json
{
  "generatedAt": "2026-05-01T12:00:00Z",
  "maintenance": {"active": false},
  "emergencyRateLimit": {
    "enabled": false,
    "maxRequestsPerMinute": 60,
    "currentWindowCount": 0
  },
  "globalCircuit": {"hasAvailableRoute": true}
}
```

### 请求日志

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/internal/requests/recent` | 近期请求日志（?client=, ?model=, ?status=） |
| `GET` | `/internal/requests/{requestId}` | 请求详情 |
| `GET` | `/internal/cost/by-model` | 按模型成本统计（?day=） |
| `GET` | `/internal/cost/client` | 按客户端+模型成本明细（?client=, ?from=, ?to=） |

### 用量报表

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/internal/usage/summary` | 用量汇总（?client=, ?day=） |
| `GET` | `/internal/cost/summary` | 成本汇总（?client=, ?day=） |
| `GET` | `/internal/reporting/providers` | 供应商维度报表 |
| `GET` | `/internal/reporting/users` | 用户维度报表 |
| `GET` | `/internal/reporting/keys` | Key 维度报表 |

#### GET /internal/cost/client

获取指定客户端在日期范围内的按模型成本明细（含 promptTokens / completionTokens 拆分）。

**认证**: Bearer

**查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `client` | `string` | 是 | 客户端标识 |
| `from` | `string` | 否 | 起始日期（ISO-8601，如 `2026-05-01`），默认当天 |
| `to` | `string` | 否 | 截止日期（ISO-8601），默认当天 |

**响应**:

```json
{
  "client": "demo-client-key",
  "from": "2026-05-01",
  "to": "2026-05-23",
  "models": [
    {
      "model": "gpt-4o-mini",
      "promptTokens": 15000,
      "completionTokens": 3000,
      "totalTokens": 18000,
      "cost": 0.027
    },
    {
      "model": "gpt-4o",
      "promptTokens": 5000,
      "completionTokens": 1000,
      "totalTokens": 6000,
      "cost": 0.03
    }
  ],
  "totalCost": 0.057
}
```

### 供应商状态

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/internal/providers/runtime` | 供应商运行时状态 |
| `GET` | `/internal/providers/discovery` | 供应商发现状态 |
| `GET` | `/internal/catalog/providers` | 模型目录快照 |
| `GET` | `/internal/pricing/models` | 动态定价 |
| `GET` | `/internal/snapshots/models-pricing` | 模型定价快照 |

### 配置审计与版本

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/internal/config/audit` | 配置变更审计日志 |
| `GET` | `/internal/config/audit-center` | 审计中心 |
| `GET` | `/internal/config/versions/{configType}/{configKey}` | 配置版本历史 |
| `POST` | `/internal/config/rollback/{configType}/{configKey}/{versionNumber}` | 配置回滚 |
| `GET` | `/internal/config/snapshot` | 当前完整配置快照 |

#### GET /internal/config/audit-center

统一审计视图，适合从“时间线 / 过滤器 / 运维追溯”角度查看配置变更，而不是按单一配置类型逐页读取。

**认证**: Bearer

**常见查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `limit` | `int` | 否 | 返回条数上限 |
| `configType` | `string` | 否 | 配置类型过滤，如 `system` |
| `configKey` | `string` | 否 | 配置 key 过滤，如 `limit` |
| `action` | `string` | 否 | 动作过滤，如 `update` |

**响应要点**:

- 返回 `entries` 数组
- 黑盒验证已确认 `?configType=system&configKey=limit&action=update` 过滤有效
- 过滤后的结果可稳定映射到 `resourceType`、`resourceId`、`action`

**响应示例**:

```json
{
  "entries": [
    {
      "resourceType": "system",
      "resourceId": "limit",
      "action": "update"
    }
  ]
}
```

# Simple AI Gateway 使用指南

> 本文是**流程型主入口**，面向“启动、配置、调用、排障”。
> - 如果你想快速跑通一条完整链路，请看本文。
> - 如果你要查单个接口的鉴权、字段和错误语义，请看 [`api-reference.md`](./api-reference.md)。
> - 如果你要做 SDK 生成、契约校验或 Swagger 导入，请看 [`openapi.json`](./openapi.json)。

## 目录

- [快速启动](#快速启动)
- [概念模型](#概念模型)
- [管理员操作流程](#管理员操作流程)
  - [1. 登录获取 Admin Token](#1-登录获取-admin-token)
  - [2. 添加 Provider（上游供应商）](#2-添加-provider上游供应商)
  - [3. 配置 Route（路由规则）](#3-配置-route路由规则)
  - [4. 创建 Client（接入客户端）](#4-创建-client接入客户端)
  - [5. 设置定价（可选）](#5-设置定价可选)
  - [6. 配置限流与熔断（可选）](#6-配置限流与熔断可选)
  - [7. 导入导出配置（可选）](#7-导入导出配置可选)
- [普通用户操作流程](#普通用户操作流程)
  - [1. 登录 / 注册](#1-登录--注册)
  - [2. 创建个人 API Key](#2-创建个人-api-key)
  - [3. 调用 Chat Completions](#3-调用-chat-completions)
  - [4. 查看个人用量](#4-查看个人用量)
- [监控与管理](#监控与管理)
  - [查看请求日志](#查看请求日志)
  - [查看用量统计](#查看用量统计)
  - [管理用户](#管理用户)
- [常见场景示例](#常见场景示例)
- [认证方式对比](#认证方式对比)
- [故障排查](#故障排查)

---

## 快速启动

无需数据库，单命令启动：

```bash
# 编译
./mvnw -q compile

# 启动（local profile + in-memory 模式，所有配置重启即丢失）
./mvnw spring-boot:run \
  -pl bootstrap \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--server.port=8081"
```

启动后验证：

```bash
curl -f http://localhost:8081/healthz
# → {"status":"UP",...}
```

> 默认预置了一个演示客户端：API Key `demo-client-key`，可用于快速测试。

> 说明：本文只保留完成任务所需的最小步骤；更完整的接口字段和边界行为见 [`api-reference.md`](./api-reference.md)。

---

## 概念模型

```
┌──────────┐     ┌──────────┐     ┌────────────┐
│  Client   │────▶│  Route   │────▶│  Provider  │
│ (接入方)  │     │ (路由规则)│     │ (上游供应商)│
└──────────┘     └──────────┘     └────────────┘
     │                │
     │           ┌────┴────┐
     │           │  Model  │
     │           │ gpt-4o  │
     │           └─────────┘
     │
     ├── allowedModels（客户端可用模型白名单）
     ├── limits（配额 / 预算 / 速率限制）
     └── capabilities（流式支持等）
```

| 概念 | 说明 | 类比 |
|------|------|------|
| **Provider** | 上游 AI 供应商，如 OpenAI、Anthropic、自定义兼容服务 | "供应商/渠道" |
| **Route** | 模型到 Provider 的映射规则，一个模型可配多个 provider 做 fallback | "路由表" |
| **Client** | 调用方，拥有 API Key、模型白名单、配额限制 | "应用/租户" |
| **Scene** | 场景配置（temperature 等参数模板） | "场景模板" |

### 完整请求链路

```
用户请求
  → POST /v1/chat/completions
    → 认证（JWT / API Key / 静态 Key）
    → 模型授权（是否在 allowedModels 中）
    → 速率限制（RPM）
    → 配额检查（日/月 token 配额）
    → 预算检查（日/月 成本预算）
    → TPM 限制（每分钟 token 数）
    → 路由解析（模型 → Provider）
    → 上游调用（primary → fallback）
    → 返回响应
```

---

## 管理员操作流程

### 前置条件

确保已启动网关，并获取 admin 令牌：

```bash
BASE=http://localhost:8081

# 用 admin 账号登录
ADMIN_TOKEN=$(curl -s -X POST "$BASE/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.accessToken')

echo "$ADMIN_TOKEN"
```

后续所有 admin 请求都使用此 token：

```bash
AUTH="Authorization: Bearer $ADMIN_TOKEN"
```

---

### 1. 添加 Provider（上游供应商）

Provider 定义了上游 AI 服务的接入信息。

#### 常见类型

| type | 说明 | Auth 方式 |
|------|------|-----------|
| `openai-compatible` | OpenAI 兼容服务 | `Authorization: Bearer <key>` |
| `anthropic` | Anthropic Claude | `x-api-key: <key>` |
| `gemini` | Google Gemini | `x-goog-api-key: <key>` |

#### 最小示例：添加 OpenAI Provider

```bash
curl -X PUT "$BASE/admin/providers/openai" \
  -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "openai-compatible",
    "baseUrl": "https://api.openai.com",
    "apiKey": "sk-your-openai-key",
    "timeout": "30s"
  }'
```

#### 调试示例：添加本地 Mock Provider

```bash
# 仓库内置了 OpenAI 兼容 mock 服务 jmeter/mock_openai_server_node.mjs（监听 :18080）
# 黑盒脚本 scripts/verify.sh 和 scripts/regression.sh 会自动启动它。
# 也可手动启动: node jmeter/mock_openai_server_node.mjs &

# 再添加 provider
curl -X PUT "$BASE/admin/providers/mock-openai" \
  -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "openai-compatible",
    "baseUrl": "http://localhost:18080",
    "apiKey": "sk-mock",
    "timeout": "10s"
  }'
```

#### 其他类型示例：Anthropic Provider

```bash
curl -X PUT "$BASE/admin/providers/anthropic" \
  -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "anthropic",
    "baseUrl": "https://api.anthropic.com",
    "apiKey": "sk-ant-your-key",
    "timeout": "30s"
  }'
```

#### 验证与排查

```bash
# 查看 Provider 列表（apiKey 已脱敏）
curl -f "$BASE/admin/providers" -H "$AUTH" | jq

# 测试 Provider 连接
curl -X POST "$BASE/admin/providers/openai/test" -H "$AUTH" | jq

# 查看 Provider 运行时状态
curl -f "$BASE/admin/providers/runtime" -H "$AUTH" | jq

# 获取 Provider 的模型列表（需 Provider 支持 /v1/models）
curl -f "$BASE/admin/providers/openai/models" -H "$AUTH" | jq
```

#### 删除 Provider

```bash
curl -X DELETE "$BASE/admin/providers/openai" -H "$AUTH"
```

> **注意**：删除 Provider 前，请确保没有 Route 引用它，否则删除会失败或导致路由失效。字段级说明、测试/模型目录相关接口见 [`api-reference.md`](./api-reference.md)。

---

### 2. 配置 Route（路由规则）

Route 定义了哪个模型由哪个 Provider 处理，支持多 provider 做故障转移。

#### 示例：创建路由

```bash
curl -X PUT "$BASE/admin/routes/gpt-4o-mini" \
  -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{
    "scene": "default-chat",
    "providers": ["mock-openai", "openai"],
    "rateLimit": {
      "enabled": true,
      "maxRequestsPerSecond": 50
    },
    "timeout": "30s"
  }'
```

这里最重要的语义只有两点：

- `providers` 按顺序生效：第一个是 primary，后续作为 fallback。
- `scene` / `timeout` / 路由级限流属于运行时行为控制项。

完整字段说明见 [`api-reference.md`](./api-reference.md) 的 Route 章节。

#### 故障转移（Fallback）机制

当 primary provider 返回 5xx 或超时时，自动按序尝试 fallback provider：

```
请求 gpt-4o-mini
  → mock-openai（primary）
    ├── 成功 → 返回
    └── 失败 → openai（fallback）
                  ├── 成功 → 返回
                  └── 全部失败 → 502 upstream_error
```

#### 查看与删除路由

```bash
# 查看路由列表
curl -f "$BASE/admin/routes" -H "$AUTH" | jq

# 删除路由
curl -X DELETE "$BASE/admin/routes/gpt-4o-mini" -H "$AUTH"
```

> 如果你只是在做最小接入，到这里已经足够支撑 `/v1/chat/completions` 调用。更复杂的 fallback / 限流 / 多路由编排说明，建议结合 [`api-reference.md`](./api-reference.md) 与 [`architecture.md`](./architecture.md) 阅读。

---

### 3. 创建 Client（接入客户端）

Client 代表一个调用方，拥有自己的 API Key、模型白名单、配额和预算限制。

#### 示例：创建客户端

```bash
curl -X PUT "$BASE/admin/clients/my-app" \
  -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{
    "enabled": true,
    "allowedModels": ["gpt-4o-mini", "gpt-4o"],
    "defaults": {
      "scene": "default-chat",
      "temperature": 0.7
    },
    "capabilities": {
      "streaming": true
    },
    "limits": {
      "maxTokens": 4096,
      "dailyTokens": 1000000,
      "monthlyTokens": 20000000,
      "dailyCost": 20.0,
      "monthlyCost": 500.0,
      "maxRpm": 60,
      "maxConcurrent": 5,
      "maxTpm": 100000
    }
  }'
```

> **Key 说明**：`/admin/clients/{key}` 路径上的 `key` 就是该客户端调用时的 Bearer token 值。上例中，客户端用 `Bearer my-app` 认证。
>
> **限制说明**：`dailyTokens` / `monthlyTokens` / `dailyCost` / `monthlyCost` / `maxRpm` / `maxConcurrent` / `maxTpm` / `maxTokens` 共同决定治理行为。本文不展开字段表，完整错误码与限制语义见 [`api-reference.md`](./api-reference.md)。

#### 查看与删除

```bash
# 查看客户端列表
curl -f "$BASE/admin/clients" -H "$AUTH" | jq

# 删除客户端
curl -X DELETE "$BASE/admin/clients/my-app" -H "$AUTH"
```

---

### 4. 设置定价（可选）

配置模型单价，用于成本统计和预算控制。当前还支持：

- 直接按模型名手工覆盖价格
- 通过 `exactMatches` 把别名模型映射到已同步的 canonical model
- 在 exact 失败后，使用保守名称归一化作为默认保底匹配

```bash
curl -X PUT "$BASE/admin/system/pricing" \
  -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{
    "default": {
      "unitPrice": 0.002
    },
    "models": {
      "gpt-4o": { "unitPrice": 0.005 },
      "gpt-4o-mini": { "unitPrice": 0.0015 },
      "claude-3-haiku": { "unitPrice": 0.0025 }
    },
    "exactMatches": {
      "alias-model": "gpt-4o"
    }
  }'
```

`unitPrice` 单位为 **美元 / 1K tokens**。无匹配模型时使用 `default` 价格。

如需确认某个模型请求最终会命中哪条价格规则，可预览：

```bash
curl -f "$BASE/admin/system/pricing/resolve?model=alias-model&upstreamModel=gpt-4o&provider=openai" \
  -H "$AUTH" | jq
```

系统配置类接口的完整字段和计费匹配语义见 [`api-reference.md`](./api-reference.md)。

---

### 5. 配置限流与熔断（可选）

系统级限流和熔断配置。**修改后支持热更新，可即时生效**。

```bash
# 限流
curl -X PUT "$BASE/admin/system/limit" \
  -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{
    "requestsPerWindow": 120,
    "window": "PT1M"
  }'

# 熔断
curl -X PUT "$BASE/admin/system/resilience" \
  -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{
    "maxAttempts": 3,
    "retryableFailureThreshold": 5,
    "failureWindow": "PT1M",
    "openDuration": "PT1M"
  }'
```

---

### 6. 导入导出配置（可选）

用于批量配置或环境迁移。

```bash
# 导出全部配置到文件
curl -f "$BASE/admin/config/export" -H "$AUTH" | jq > gateway-config.json

# 从文件导入
curl -X POST "$BASE/admin/config/import" \
  -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d @gateway-config.json | jq
```

> 配置导入/导出/版本回滚属于运维型接口，适合在理解主链路后再使用；详见 [`api-reference.md`](./api-reference.md)。

---

## 普通用户操作流程

### 1. 登录 / 注册

#### 已有账号登录

```bash
LOGIN_RESP=$(curl -s -X POST "$BASE/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"user","password":"user123"}')

ACCESS_TOKEN=$(echo "$LOGIN_RESP" | jq -r '.accessToken')
REFRESH_TOKEN=$(echo "$LOGIN_RESP" | jq -r '.refreshToken')

echo "Access: $ACCESS_TOKEN"
echo "Refresh: $REFRESH_TOKEN"
```

#### 注册新账号

```bash
REG_RESP=$(curl -s -X POST "$BASE/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"username":"newuser","password":"pass123"}')

echo "$REG_RESP" | jq
# 返回中包含 apiKey、accessToken、refreshToken
```

#### 刷新 Token

```bash
curl -s -X POST "$BASE/auth/refresh" \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}" | jq
```

#### 查看当前用户信息

```bash
curl -f "$BASE/auth/me" -H "Authorization: Bearer $ACCESS_TOKEN" | jq
```

> 登录、注册、刷新、注销、密码修改、个人 API key 生命周期等完整接口说明见 [`api-reference.md`](./api-reference.md) 的 Auth 章节。

---

### 2. 创建个人 API Key

用户可以为自己的账号创建多个 API Key，用于不同应用。

```bash
# 创建 Key
KEY_RESP=$(curl -s -X POST "$BASE/auth/keys" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"my-app-key"}')

API_KEY=$(echo "$KEY_RESP" | jq -r '.key')
echo "API Key: $API_KEY"
# 输出类似：API Key: gw-xxxx...
```

> 个人 API Key 以 `gw-` 开头，与静态客户端 Key 可区分。字段命名、轮换/删除状态码、禁用语义以 [`api-reference.md`](./api-reference.md) 为准。

#### 管理 API Key

```bash
# 列出所有 Key（脱敏）
curl -f "$BASE/auth/keys" -H "Authorization: Bearer $ACCESS_TOKEN" | jq

# 禁用 Key
curl -X PATCH "$BASE/auth/keys/primary" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"status":"disabled"}'

# 轮换 Key（旧 Key 立即失效，签发新 Key）
curl -X POST "$BASE/auth/keys/primary/rotate" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq

# 删除 Key
curl -X DELETE "$BASE/auth/keys/primary" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

---

### 3. 调用 Chat Completions

支持三种认证方式，任选其一：

#### 方式一：静态 API Key

```bash
curl -X POST "$BASE/v1/chat/completions" \
  -H 'Authorization: Bearer demo-client-key' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "Hello!"}
    ],
    "stream": false
  }' | jq
```

#### 方式二：JWT Access Token

```bash
curl -X POST "$BASE/v1/chat/completions" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [
      {"role": "user", "content": "Hello!"}
    ]
  }' | jq
```

#### 方式三：个人 API Key

```bash
curl -X POST "$BASE/v1/chat/completions" \
  -H "Authorization: Bearer $API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [
      {"role": "user", "content": "Hello!"}
    ]
  }' | jq
```

#### 流式调用

```bash
curl -X POST "$BASE/v1/chat/completions" \
  -H 'Authorization: Bearer demo-client-key' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [{"role": "user", "content": "Count to 5"}],
    "stream": true
  }'
```

SSE 格式响应：

```
data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"delta":{"content":"1"},"index":0}]}
data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"delta":{"content":"2"},"index":0}]}
...
data: [DONE]
```

---

### 4. 查看个人用量

```bash
# 近期用量
curl -f "$BASE/auth/usage/recent" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq

# 按模型成本分布
curl -f "$BASE/auth/usage/costs" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq
```

> 注意：当前支持的个人用量查询接口是 `recent` 与 `costs`。`/auth/usage/summary` 不属于当前已实现/支持的接口；如果你需要汇总口径，请改用管理/内部侧统计接口（如 `/internal/usage/summary`）。

---

## 监控与管理

这一部分只给出最常见的查看入口，不重复列出所有管理接口。完整端点清单见 [`api-reference.md`](./api-reference.md)。

### 查看请求日志

```bash
# 近期请求（支持 ?client=、?model=、?status= 过滤）
curl -f "$BASE/admin/requests/recent?status=200" \
  -H "$AUTH" | jq

# 请求详情
curl -f "$BASE/admin/requests/req_xxx" \
  -H "$AUTH" | jq
```

### 查看用量统计

```bash
# 用量汇总（按客户端）
curl -f "$BASE/internal/usage/summary?client=demo-client-key&day=2026-06-03" \
  -H "$AUTH" | jq

# 按模型成本
curl -f "$BASE/internal/cost/by-model?day=2026-06-03" \
  -H "$AUTH" | jq

# 客户端 + 模型成本明细
curl -f "$BASE/internal/cost/client?client=demo-client-key&from=2026-06-01&to=2026-06-03" \
  -H "$AUTH" | jq
```

### 管理用户

```bash
# 用户列表
curl -f "$BASE/admin/users" -H "$AUTH" | jq

# 创建用户
curl -X POST "$BASE/admin/users" \
  -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{
    "username": "team-member",
    "password": "secure-pass",
    "role": "user"
  }' | jq

# 设置用户限额
curl -X PUT "$BASE/admin/users/team-member/limits" \
  -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{
    "dailyTokens": 500000,
    "monthlyTokens": 10000000,
    "dailyCost": 10.0,
    "monthlyCost": 200.0
  }' | jq

# 设置用户模型白名单
curl -X PUT "$BASE/admin/users/team-member/allowed-models" \
  -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{
    "allowedModels": ["gpt-4o-mini"]
  }' | jq

# 冻结/解冻用户
curl -X PUT "$BASE/admin/users/team-member" \
  -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{"frozen": true}' | jq
```

---

## 常见场景示例

### 场景一：调试环境搭建

> 仓库内置了 mock 上游服务 `jmeter/mock_openai_server_node.mjs`（监听 `:18080`）。黑盒测试脚本 `scripts/verify.sh` 和 `scripts/regression.sh` 会自动启动它。如需单独启动：`node jmeter/mock_openai_server_node.mjs`。

```bash
# 1. 启动网关（local profile + in-memory）
./mvnw spring-boot:run \
  -pl bootstrap \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--server.port=8081"

# 2. 登录 admin
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8081/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.accessToken')
AUTH="Authorization: Bearer $ADMIN_TOKEN"

# 3. 添加 mock provider
curl -X PUT http://localhost:8081/admin/providers/mock \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"type":"openai-compatible","baseUrl":"http://localhost:18080","apiKey":"sk-mock","timeout":"10s"}'

# 4. 创建路由
curl -X PUT http://localhost:8081/admin/routes/gpt-4o-mini \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"scene":"default-chat","providers":["mock"]}'

# 5. 测试调用
curl -X POST http://localhost:8081/v1/chat/completions \
  -H 'Authorization: Bearer demo-client-key' \
  -H 'Content-Type: application/json' \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}],"stream":false}'
```

### 场景二：多 Provider 灾备

```
模型 gpt-4o → Route 配置 → providers: ["openai-01", "openai-02", "anthropic"]
                             ↑primary         ↑fallback 1     ↑fallback 2
```

```bash
# 添加三个 provider
curl -X PUT "$BASE/admin/providers/openai-01" ... -d '{"type":"openai-compatible","baseUrl":"https://api.openai.com","apiKey":"sk-key1"}'
curl -X PUT "$BASE/admin/providers/openai-02" ... -d '{"type":"openai-compatible","baseUrl":"https://api.openai.com","apiKey":"sk-key2"}'
curl -X PUT "$BASE/admin/providers/anthropic" ... -d '{"type":"anthropic","baseUrl":"https://api.anthropic.com","apiKey":"sk-ant-key"}'

# 创建路由，三个 fallback 层级
curl -X PUT "$BASE/admin/routes/gpt-4o" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"providers":["openai-01","openai-02","anthropic"],"timeout":"30s"}'
```

### 场景三：多租户隔离

```bash
# 为每个团队创建独立的 client + 配额
for team in team-a team-b team-c; do
  curl -X PUT "$BASE/admin/clients/$team" \
    -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{
      \"enabled\": true,
      \"allowedModels\": [\"gpt-4o-mini\"],
      \"limits\": {
        \"dailyTokens\": 500000,
        \"dailyCost\": 10.0
      }
    }"
done
```

---

## 认证方式对比

| 方式 | 场景 | 获取途径 | Token 格式 | 生命周期 |
|------|------|---------|-----------|---------|
| 静态 API Key | 机器对机器、测试 | `gateway.clients` 配置（YAML 或 admin API） | 任意字符串，如 `demo-client-key` | 永久（手动管理） |
| JWT Access Token | 交互式用户会话 | `POST /auth/login` | JWT（HMAC 签名） | 30 分钟（可配置） |
| 动态用户 API Key | 用户自有应用 | `POST /auth/keys`（需先登录） | 以 `gw-` 开头 | 永久（可轮换/删除） |

三种方式共用一个 `Authorization: Bearer <token>` 标头，认证系统按优先级自动识别。

---

## 故障排查

### 请求链路调试

利用网关的请求日志快速定位问题：

```bash
# 发起一个请求，拿到 requestId
# 然后查询该请求的完整链路
curl -f "$BASE/admin/requests/recent?status=401" -H "$AUTH" | jq '.'
```

### 常见问题

| 问题 | 可能原因 | 排查方法 |
|------|---------|---------|
| 401 Unauthorized | Token 错误/过期/不存在 | 用 `/auth/me` 验证 token 有效性 |
| 403 Forbidden (model) | 模型不在 allowedModels 中 | 检查 client 和 user 的模型白名单 |
| 429 quota_exceeded | 日 token 配额耗尽 | 检查 client limits，或等次日重置 |
| 429 budget_exceeded | 日成本预算耗尽 | 检查 client limits，或等次日重置 |
| 502 upstream_error | 所有 provider 都不可用 | 检查 provider runtime 状态和网络连接 |
| 503 maintenance_mode | 系统维护模式 | 检查 `/internal/system/status` |
| 504 upstream_timeout | 上游超时 | 增大 timeout 配置，检查上游负载 |
| 请求日志查不到 | 后端不是 in-memory | 确认 shared-state.backend 配置正确 |

### 健康检查

```bash
# 基本存活
curl -f http://localhost:8081/healthz/live

# 就绪状态（含依赖检查）
curl -f http://localhost:8081/healthz/ready

# 完整健康检查（含组件状态）
curl -f http://localhost:8081/healthz
```

---

## 参考

- [API 参考文档](./api-reference.md) — 按资源分组的人工接口参考
- [架构说明](./architecture.md) — 系统架构与核心模式
- [功能清单](./features.md) — 完整功能列表
- [OpenAPI 规范](./openapi.json) — 机器可消费的 OpenAPI 快照
- [示例代码](./examples.md) — 快速示例集合

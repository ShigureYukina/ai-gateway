# AI Gateway 测试设计（二）— 接口集成测试

> 本文件从 HTTP / REST 合约视角设计测试，承接《01-业务验收测试》的业务目标。

---

## 1. 设计原则

- 只覆盖当前仓库真实存在的接口
- 重点验证：路径、方法、鉴权、请求体、状态码、响应结构、关键副作用
- 优先采用 `WebTestClient / MockMvc + WireMock + Testcontainers`
- Redis / PostgreSQL 仅在对应共享状态或持久化能力测试中作为增强环境，不假设全部接口都必须依赖它们

### 1.1 首版收敛建议

本文件保留完整编号，便于后续持续扩展；但首版集成测试不建议一次性覆盖全部 50+ 条。

- **首版建议范围**：优先收敛到约 **35~40 条以内**。
- **主线优先**：认证、API Key、自助与管理端最小可用配置、`/v1/chat/completions`、`/v1/models` 主线过滤、recent / internal 查询主链路。
- **后续增强**：共享状态细节、复杂统计口径、Provider 发现态、部分管理端细颗粒度接口，可在 Phase 2+ 补齐。

---

## 2. 接口分组总览

1. Auth API
2. Self-service API Key API
3. Admin User API
4. Admin Provider API
5. Model Group API
6. OpenAI-compatible API
7. Internal Reporting / Request Log API

### 2.1 首版主线 / 后续增强划分

**建议首版主线优先覆盖：**

- I001-I007：认证与当前用户基本能力
- I011-I016：自助 API Key 全生命周期
- I017、I018、I019、I022、I023：最小管理能力
- I024-I027、I029：Provider 基本管理与可用性检查
- I033-I035：模型分组最小编排闭环
- I036-I047：聊天主链路、鉴权、流式、限流 / 配额 / 预算 / 上游失败
- I048、I049、I052、I053：recent / detail / usage / cost 核心查询

**更适合后续增强的项：**

- I008-I010：自助统计与日期边界补强
- I020、I021：管理端额度与密码专项
- I028、I030-I032：Provider 模型保存、运行态、发现态
- I039：按 provider 过滤模型列表
- I050、I051、I054：统计维度补强与实现差异校验

---

## 3. API Integration Test 清单

## 模块 I1：Auth API

### I001 登录接口成功
- **接口**：`POST /auth/login`
- **验证点**：
  - 状态码 `200`
  - 返回 `accessToken`、`refreshToken`、`tokenType=Bearer`

### I002 登录接口失败
- **接口**：`POST /auth/login`
- **场景**：密码错误
- **验证点**：
  - 状态码 `401`
  - 错误码为 `invalid_credentials` 或等价错误

### I003 刷新令牌成功
- **接口**：`POST /auth/refresh`
- **验证点**：
  - 状态码 `200`
  - 返回新的 access / refresh token

### I004 登出成功
- **接口**：`POST /auth/logout`
- **验证点**：
  - 状态码 `204`
  - 已登出的 refresh token 再次刷新返回 `401`

### I005 获取当前用户信息
- **接口**：`GET /auth/me`
- **验证点**：
  - 状态码 `200`
  - 返回 `username`、`role`、`apiKeyMasked`、`createdAt`、`quota`
  - `quota` 仅验证结构存在，不断言真实 usage/cost 增长

### I006 修改密码
- **接口**：`PUT /auth/password`
- **验证点**：
  - 状态码 `204`
  - 旧密码登录失败，新密码登录成功

### I007 查询个人 recent 使用记录
- **接口**：`GET /auth/usage/recent?limit=10`
- **验证点**：
  - 状态码 `200`
  - 仅返回当前用户可见的 recent 数据
  - 作为近实时视图验证可见性，不断言聚合数值同步增长

### I008 查询个人近期费用聚合
- **接口**：`GET /auth/usage/costs?from=2026-06-01&to=2026-06-30`
- **验证点**：
  - 状态码 `200`
  - 返回近期聚合结构
  - 不把该接口当作长周期权威账单

### I009 当前无个人汇总接口
- **接口**：无（`GET /auth/usage/summary` 当前不在已实现/支持的 API surface）
- **验证点**：
  - 文档与测试计划不再把该路径当作可调用接口
  - 个人侧仅覆盖 `GET /auth/usage/recent` 与 `GET /auth/usage/costs`

### I010 非法日期参数返回 400
- **接口**：`GET /auth/usage/costs`
- **场景**：传入非法 `from/to/day`
- **验证点**：
  - 状态码 `400`
  - 返回参数错误结构

---

## 模块 I2：自助 API Key API

### I011 创建 API Key
- **接口**：`POST /auth/keys`
- **验证点**：
  - 状态码 `200`
  - 返回 `keyId`、`apiKey`、`enabled`、`allowedModels`

### I012 列出 API Key
- **接口**：`GET /auth/keys`
- **验证点**：
  - 状态码 `200`
  - 能看到创建结果

### I013 更新 API Key
- **接口**：`PATCH /auth/keys/{keyId}`
- **验证点**：
  - 状态码 `204`
  - 再查询时更新生效

### I014 删除 API Key
- **接口**：`DELETE /auth/keys/{keyId}`
- **验证点**：
  - 状态码 `204`
  - 删除后旧 Key 调用返回 `401`

### I015 轮换 API Key
- **接口**：`POST /auth/keys/{keyId}/rotate`
- **验证点**：
  - 状态码 `200`
  - 返回新的 `apiKey`
  - 旧 Key 失效

### I016 不存在的 API Key 返回 404
- **接口**：`PATCH /auth/keys/{keyId}` / `DELETE /auth/keys/{keyId}`
- **验证点**：
  - 状态码 `404`

---

## 模块 I3：Admin User API

### I017 查询用户列表
- **接口**：`GET /admin/users`
- **验证点**：
  - 管理员调用返回 `200`
  - 普通用户调用返回 `403`

### I018 创建用户
- **接口**：`POST /admin/users`
- **验证点**：
  - 状态码 `201`

### I019 更新用户角色与冻结状态
- **接口**：`PUT /admin/users/{username}`
- **验证点**：
  - 状态码 `200`
  - `role` / `frozen` 生效

### I020 更新用户额度限制
- **接口**：`PUT /admin/users/{username}/limits`
- **验证点**：
  - 状态码 `200`
  - daily/monthly tokens、TPM、maxTokens、dailyCost、monthlyCost 生效

### I021 重置用户密码
- **接口**：`POST /admin/users/{username}/reset-password`
- **验证点**：
  - 状态码 `200`
  - 返回临时密码

### I022 管理员管理用户 API Key
- **接口**：
  - `GET /admin/users/{username}/api-keys`
  - `POST /admin/users/{username}/api-keys`
  - `PATCH /admin/users/{username}/api-keys/{keyId}`
  - `DELETE /admin/users/{username}/api-keys/{keyId}`
  - `PUT /admin/users/{username}/api-keys/{keyId}/toggle`
  - `POST /admin/users/{username}/api-keys/{keyId}/rotate`
- **验证点**：
  - 列表 `200`
  - 创建 `201`
  - 更新 / toggle / 删除 `204`
  - rotate `200`

### I023 更新用户允许模型
- **接口**：`PUT /admin/users/{username}/allowed-models`
- **验证点**：
  - 状态码 `200`
  - 用户白名单更新生效

---

## 模块 I4：Admin Provider API

### I024 查询 Provider 列表
- **接口**：`GET /admin/providers`
- **验证点**：
  - 状态码 `200`
  - `apiKey` / `keys` 已脱敏

### I025 新增 / 更新 Provider
- **接口**：`PUT /admin/providers/{name}`
- **验证点**：
  - 新建返回 `201`
  - 更新返回 `200`

### I026 删除 Provider
- **接口**：`DELETE /admin/providers/{name}`
- **验证点**：
  - 存在时返回 `204`
  - 不存在时返回 `404`

### I027 测试 Provider 健康
- **接口**：`POST /admin/providers/{name}/test`
- **验证点**：
  - 状态码 `200` 或稳定错误结构
  - 上游异常时不泄漏内部堆栈

### I028 查询 Provider 模型列表
- **接口**：`GET /admin/providers/{name}/models`
- **验证点**：
  - 状态码 `200`

### I029 拉取上游模型列表
- **接口**：`POST /admin/providers/{name}/models/fetch`
- **验证点**：
  - 状态码 `200` 或稳定错误结构

### I030 保存 Provider 模型列表
- **接口**：`PUT /admin/providers/{name}/models`
- **验证点**：
  - 状态码 `204`

### I031 查询 Provider 运行时状态
- **接口**：`GET /admin/providers/runtime`
- **验证点**：
  - 状态码 `200`

### I032 查询 Provider 发现状态
- **接口**：`GET /admin/providers/discovery`
- **验证点**：
  - 状态码 `200`

---

## 模块 I5：Model Group API

### I033 查询模型分组
- **接口**：`GET /admin/model-groups`
- **验证点**：
  - 状态码 `200`
  - 返回 alias / scene / members / fallback 顺序

### I034 创建 / 更新模型分组
- **接口**：`PUT /admin/model-groups/{alias}`
- **验证点**：
  - 新建返回 `201`
  - 更新返回 `200`
  - 成员顺序能映射 primary / fallback 角色

### I035 删除模型分组
- **接口**：`DELETE /admin/model-groups/{alias}`
- **验证点**：
  - 状态码 `204`

---

## 模块 I6：OpenAI-compatible API

### I036 普通聊天完成
- **接口**：`POST /v1/chat/completions`
- **验证点**：
  - 状态码 `200`
  - 返回 `object`、`choices`

### I037 SSE 流式聊天
- **接口**：`POST /v1/chat/completions`
- **验证点**：
  - 状态码 `200`
  - `Content-Type=text/event-stream`
  - 至少有 1 个 `data:` 事件

### I038 查询可用模型
- **接口**：`GET /v1/models`
- **验证点**：
  - 状态码 `200`
  - `object=list`
  - `data[].id` 存在
  - 当前主线聚焦“成功鉴权的 API Key 的 `allowedModels` 过滤效果”，不把该接口写成严格受保护接口或所有主体统一授权视图

### I039 查询特定 Provider 模型
- **接口**：`GET /v1/models?provider=openai`
- **验证点**：
  - 状态码 `200`
  - 返回项与 provider 过滤一致

### I040 未认证调用被拒绝
- **接口**：`POST /v1/chat/completions`
- **验证点**：
  - 状态码 `401`

### I041 非法 Authorization 被拒绝
- **接口**：`POST /v1/chat/completions`
- **验证点**：
  - 状态码 `401`

### I042 未授权模型调用被拒绝
- **接口**：`POST /v1/chat/completions`
- **验证点**：
  - 状态码 `403`

### I043 流式能力被禁用时返回 400
- **接口**：`POST /v1/chat/completions`
- **场景**：principal `streaming=false` 且请求 `stream=true`
- **验证点**：
  - 状态码 `400`
  - 错误码 `stream_not_supported`

### I043-1 Provider / adapter 不支持流式时返回 501
- **接口**：`POST /v1/chat/completions`
- **场景**：principal 允许流式，但目标 Provider / adapter 本身不支持 `stream=true`
- **验证点**：
  - 状态码 `501`
  - 错误码 `stream_not_supported` 或当前实现等价错误
  - 与主体流式能力不足场景的 `400` 明确区分

### I044 限流命中返回 429
- **接口**：`POST /v1/chat/completions`
- **验证点**：
  - 状态码 `429`

### I045 配额命中返回 429
- **接口**：`POST /v1/chat/completions`
- **验证点**：
  - 状态码 `429`
  - 错误码 `quota_exceeded` 或 `monthly_quota_exceeded`

### I046 预算命中返回 429
- **接口**：`POST /v1/chat/completions`
- **验证点**：
  - 状态码 `429`
  - 错误码 `budget_exceeded` 或 `monthly_budget_exceeded`

### I047 上游全部失败返回统一错误
- **接口**：`POST /v1/chat/completions`
- **验证点**：
  - 返回统一失败结构
  - 状态码稳定在实现定义的失败集合内

---

## 模块 I7：Internal Reporting / Request Log API

### I048 查询最近请求日志
- **接口**：`GET /internal/requests/recent?limit=20`
- **鉴权**：`Authorization: Bearer <具备 view_system 权限的身份>`
- **验证点**：
  - 状态码 `200`
  - recent 结果按时间倒序
  - 作为近实时视图验证排序与可见性，不断言权威聚合数值

### I049 查询单条请求详情
- **接口**：`GET /internal/requests/{requestId}`
- **验证点**：
  - 状态码 `200`
  - `requestId` 匹配
  - 作为近实时明细视图验证可读性，不额外要求与聚合口径逐字段强一致

### I050 按模型查询成本
- **接口**：`GET /internal/cost/by-model?day=2026-06-30`
- **验证点**：
  - 状态码 `200`

### I051 按客户端查询成本
- **接口**：`GET /internal/cost/client?client=demo-client-key&from=2026-06-01&to=2026-06-30`
- **验证点**：
  - 状态码 `200`
  - 对成功调用后的成本增长做强一致断言

### I052 查询 usage 汇总
- **接口**：`GET /internal/usage/summary?client=demo-client-key&day=2026-06-30`
- **验证点**：
  - 状态码 `200`
  - 对成功调用后的 token / request 增长做强一致断言

### I053 查询 cost 汇总
- **接口**：`GET /internal/cost/summary?client=demo-client-key&day=2026-06-30`
- **验证点**：
  - 状态码 `200`
  - 对成功调用后的成本增长做强一致断言

### I054 internal 非法日期参数按当前实现回退当天
- **接口**：`GET /internal/cost/by-model?day=bad-date` 或 `GET /internal/usage/summary?day=bad-date`
- **验证点**：
  - 状态码 `200`
  - 行为为“回退当天”，不是 `400`

---

## 4. 推荐测试切片

- **Controller 切片**：认证、参数校验、状态码、响应结构
- **集成切片**：
  - Auth + UserAccountService + JWT
  - ChatCompletions + RouteResolver + Upstream mock
  - Provider Admin + DynamicConfigService
  - Internal Reporting + trace / usage / cost store
- **共享状态切片**：
  - Redis：rate limit / TPM / recent 视图相关行为
  - PostgreSQL：usage / cost / trace / aggregate store 持久化行为

---

## 5. 当前假设与范围说明

1. `/auth/usage/*` 与 `/internal/*` 职责不同：前者偏自助视图，后者偏系统统计源。
2. `/v1/models` 当前重点验证 API Key `allowedModels` 过滤，不扩大到全部主体授权模型全集。
3. Redis / PostgreSQL 落库验证只要求出现在依赖对应 store 的能力测试中，不要求每个公开 API 都强绑持久化断言。
4. 一致性断言分级：`/internal/usage/summary`、`/internal/cost/summary`、`/internal/cost/client` 做强一致数值断言；`/auth/usage/recent`、`/internal/requests/recent`、`/internal/requests/{requestId}` 做近实时视图断言；`/auth/me.quota` 当前只验证结构/可用性。
5. 真实实现存在 `/admin/routes/*` 低层配置接口；本文件暂不把它纳入当前主线集成测试范围。

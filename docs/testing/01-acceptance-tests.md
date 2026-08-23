# AI Gateway 测试设计（一）— 业务验收测试

> 适用范围：校招 / 个人项目级别 AI Gateway
>
> 当前范围说明：
> - 对外协议以 **OpenAI-compatible** 为主
> - 重点覆盖 **openai-compatible upstream** 与 **anthropic upstream** 两条适配链路
> - 本地默认运行形态是 **H2 + in_memory**；Redis / PostgreSQL 属于共享状态增强验证环境，不是本地唯一前提
> - 暂不把 Prometheus / Grafana / OpenTelemetry 作为本轮验收主线
> - 文档遵循 **TDD + 自顶向下**：先定义用户价值，再下钻到接口、规则和全链路

---

## 1. 文档目的

本文件定义最顶层业务验收测试，用于回答：

- 用户能否完成认证、拿到凭证并正常发起模型调用
- 管理员能否管理用户、API Key、Provider、模型别名 / 场景 / 路由编排
- 系统能否在 streaming、限流、配额、预算、故障转移、日志审计下保持正确行为
- 所有高风险链路是否都有可执行、可自动化、可量化的预期结果

验收测试只描述用户可见结果，不下沉到 Controller / Repository 细节。

> 阅读说明：验收层按业务目标组织，便于从“用户是否能完成关键任务”角度阅读；实际落地时，也可以按用户生命周期（登录 → 凭证 → 调用 → 治理 → 统计）合并理解，不要求首版严格按 60+ 条逐条同时完成。

---

## 2. 验收范围

本轮重点覆盖：

1. 用户认证与凭证失效
2. 自助 API Key 生命周期
3. Provider 管理
4. 模型别名 / 模型分组 / scene / 主备 routes 编排
5. `/v1/chat/completions` 普通请求与异常请求
6. SSE 流式响应
7. 限流、并发控制
8. Token 配额
9. Token 计费与预算
10. 故障转移
11. recent 日志与内部聚合查询

不纳入本轮主线：

- 前端视觉细节
- 不存在的 Claude-native 对外 API
- 长时间压测、容量压测
- Prometheus / Grafana / OTel 专项

### 2.1 首版建议关注的 MVP 验收清单

为避免首版范围失控，建议先聚焦以下可交付主线；其余编号保留，作为后续增强验收项继续使用：

1. **认证主线**：A001、A002、A004
2. **API Key 主线**：A008、A010、A013、A015
3. **Provider / 模型编排主线**：A016、A022、A025
4. **聊天与流式主线**：A027、A029、A032、A034、A034-1
5. **治理主线**：A039、A045、A055
6. **可靠性与审计主线**：A056、A059、A060、A061

> 说明：以上清单用于首版收敛，不代表其余 A 编号无效；未进入 MVP 的用例更适合作为 Phase 2+ 的补强验收项。

---

## 3. 业务验收测试清单

## 模块 A1：用户认证

### A001 用户登录成功
- **前置条件**：认证已启用；存在未冻结用户
- **输入**：合法用户名、密码
- **动作**：调用 `POST /auth/login`
- **预期结果**：
  - 返回 `200`
  - 返回 `accessToken`、`refreshToken`、`tokenType=Bearer`
  - 后续使用 access token 调用 `GET /auth/me` 返回 `200`

### A002 用户登录失败
- **前置条件**：认证已启用；用户存在
- **输入**：正确用户名 + 错误密码
- **动作**：调用 `POST /auth/login`
- **预期结果**：
  - 返回 `401`
  - 错误码为 `invalid_credentials` 或等价认证错误
  - 不签发 token

### A003 冻结用户禁止登录
- **前置条件**：目标用户已冻结
- **输入**：冻结用户的正确用户名、密码
- **动作**：调用 `POST /auth/login`
- **预期结果**：
  - 返回 `403`
  - 错误码为 `account_frozen` 或等价错误

### A004 Token 过期后访问受保护接口失败
- **前置条件**：已拿到一个过期 access token
- **输入**：`Authorization: Bearer <expired-token>`
- **动作**：调用 `GET /auth/me`
- **预期结果**：
  - 返回 `401`
  - 不返回用户信息

### A005 Token 缺失时访问受保护接口失败
- **前置条件**：系统正常运行
- **输入**：无 `Authorization` 头
- **动作**：调用 `GET /auth/me`
- **预期结果**：
  - 返回 `401`
  - 返回统一错误结构

### A006 Token 非法时访问受保护接口失败
- **前置条件**：系统正常运行
- **输入**：伪造或损坏的 Bearer token
- **动作**：调用 `GET /auth/me`
- **预期结果**：
  - 返回 `401`
  - 不返回用户信息

### A007 refresh token 成功续期
- **前置条件**：用户已成功登录并持有有效 refresh token
- **输入**：合法 refresh token
- **动作**：调用 `POST /auth/refresh`
- **预期结果**：
  - 返回 `200`
  - 返回新的 access token 与 refresh token
  - 原 refresh token 再次使用时返回 `401`

---

## 模块 A2：API Key 管理

### A008 用户创建 API Key
- **前置条件**：用户已登录
- **输入**：名称、`allowedModels`
- **动作**：调用 `POST /auth/keys`
- **预期结果**：
  - 返回 `200`
  - 返回 `keyId`、明文 `apiKey`、`enabled=true`
  - 明文 key 仅本次可见

### A009 查询 API Key 列表
- **前置条件**：当前用户已存在至少一个 API Key
- **输入**：用户 access token
- **动作**：调用 `GET /auth/keys`
- **预期结果**：
  - 返回 `200`
  - 返回 `keyId`、名称、启用状态、创建时间、最近使用时间、`allowedModels`
  - 不直接暴露完整历史密钥

### A010 禁用 API Key 后调用失败
- **前置条件**：存在可用 API Key
- **输入**：目标 `keyId`
- **动作**：
  1. 调用 `PATCH /auth/keys/{keyId}` 将 `enabled=false`
  2. 使用该 Key 调用 `POST /v1/chat/completions`
- **预期结果**：
  - 更新动作返回 `204`
  - 后续模型调用返回 `401`
  - 不发起上游请求

### A011 启用 API Key 后调用恢复成功
- **前置条件**：存在已禁用 API Key
- **输入**：目标 `keyId`
- **动作**：
  1. 调用 `PATCH /auth/keys/{keyId}` 将 `enabled=true`
  2. 使用该 Key 调用 `POST /v1/chat/completions`
- **预期结果**：
  - 更新动作返回 `204`
  - 模型调用返回 `200`

### A012 删除 API Key 后彻底失效
- **前置条件**：存在可用 API Key
- **输入**：目标 `keyId`
- **动作**：
  1. 调用 `DELETE /auth/keys/{keyId}`
  2. 使用旧 Key 调用 `POST /v1/chat/completions`
- **预期结果**：
  - 删除动作返回 `204`
  - 旧 Key 调用返回 `401`

### A013 轮换 API Key 成功
- **前置条件**：存在启用中的 API Key
- **输入**：目标 `keyId`
- **动作**：
  1. 调用 `POST /auth/keys/{keyId}/rotate`
  2. 使用旧 Key 调用聊天接口
  3. 使用新 Key 调用聊天接口
- **预期结果**：
  - rotate 返回 `200`
  - 返回新的明文 `apiKey`
  - 旧 Key 调用返回 `401`
  - 新 Key 调用返回 `200`

### A014 API Key 不存在时更新失败
- **前置条件**：用户已登录
- **输入**：不存在的 `keyId`
- **动作**：调用 `PATCH /auth/keys/{keyId}` 或 `DELETE /auth/keys/{keyId}`
- **预期结果**：
  - 返回 `404`
  - 不影响其他 Key

### A015 API Key 模型权限生效
- **前置条件**：创建一个 `allowedModels=[model-a]` 的 Key
- **输入**：受限 API Key
- **动作**：
  1. 调用 `GET /v1/models`
  2. 调用 `model-a`
  3. 调用 `model-b`
- **预期结果**：
  - `/v1/models` 当前重点验证“成功鉴权的 API Key 的 `allowedModels` 过滤效果”，仅返回 `model-a`
  - `model-a` 调用返回 `200`
  - `model-b` 调用返回 `403`

---

## 模块 A3：Provider 管理

### A016 创建 Provider 成功
- **前置条件**：管理员已登录
- **输入**：Provider 名称、类型、baseUrl、apiKey、timeout
- **动作**：调用 `PUT /admin/providers/{name}`
- **预期结果**：
  - 新建返回 `201`
  - Provider 出现在列表中
  - `apiKey` / `keys` 字段在查询结果中已脱敏

### A017 修改 Provider 成功
- **前置条件**：目标 Provider 已存在
- **输入**：修改后的 timeout、enabled、models 等字段
- **动作**：调用 `PUT /admin/providers/{name}`
- **预期结果**：
  - 返回 `200`
  - 再查询时字段已更新

### A018 删除 Provider 成功
- **前置条件**：目标 Provider 已存在
- **输入**：Provider 名称
- **动作**：调用 `DELETE /admin/providers/{name}`
- **预期结果**：
  - 返回 `204`
  - 列表中不再包含该 Provider

### A019 禁用 Provider 后不再被正常选路
- **前置条件**：存在可用 primary Provider 和备用 Provider
- **输入**：将 primary Provider `enabled=false`
- **动作**：调用目标模型
- **预期结果**：
  - 配置更新返回 `200`
  - 请求不命中已禁用 Provider
  - 若存在备用路由，请求最终返回 `200`

### A020 启用 Provider 后恢复可用
- **前置条件**：存在一个已禁用 Provider
- **输入**：将该 Provider `enabled=true`
- **动作**：重新调用目标模型
- **预期结果**：
  - 配置更新返回 `200`
  - Provider 可重新参与候选选择

### A021 Provider 异常场景返回稳定结果
- **前置条件**：Provider 指向可控 mock upstream
- **输入**：上游返回超时、`500` 或 `429`
- **动作**：
  1. 调用 `POST /admin/providers/{name}/test`
  2. 调用 `POST /admin/providers/{name}/models/fetch`
- **预期结果**：
  - 接口本身返回稳定错误结构
  - 不出现未处理异常堆栈泄漏

---

## 模块 A4：模型别名 / 场景 / 路由编排

> 说明：当前项目并非“独立模型表 CRUD”，而是 alias + scene + primary/fallback routes 的聚合管理。

### A022 创建模型分组成功
- **前置条件**：至少已存在 2 个可用 Provider
- **输入**：alias、scene、members（第 1 个为 primary，其余为 fallback）
- **动作**：调用 `PUT /admin/model-groups/{alias}`
- **预期结果**：
  - 新建返回 `201`
  - 列表中可见 alias、scene、members、fallback 顺序

### A023 修改模型分组成功
- **前置条件**：目标模型分组已存在
- **输入**：新的 members 顺序和 fallback 组合
- **动作**：调用 `PUT /admin/model-groups/{alias}`
- **预期结果**：
  - 更新返回 `200`
  - 新编排立即可被 `/v1/models` 与聊天调用感知

### A024 删除模型分组成功
- **前置条件**：目标 alias 已存在
- **输入**：目标 alias
- **动作**：调用 `DELETE /admin/model-groups/{alias}`
- **预期结果**：
  - 返回 `204`
  - `/v1/models` 中不再返回该 alias

### A025 `/v1/models` 返回当前 API Key 可见模型
- **前置条件**：已存在模型 alias；当前 API Key 配置了 `allowedModels`
- **输入**：受限 API Key
- **动作**：调用 `GET /v1/models`
- **预期结果**：
  - 返回 `200`
  - 返回 OpenAI-compatible list 结构
  - 仅返回该 Key 可访问的模型 alias
  - 本条不把 `/v1/models` 视为“所有主体统一授权视图”；未鉴权场景不作为本轮主线强约束

### A026 模型不存在时调用失败
- **前置条件**：认证通过
- **输入**：不存在的 `model`
- **动作**：调用 `POST /v1/chat/completions`
- **预期结果**：
  - 返回 `404` 或项目定义的稳定业务错误
  - 不出现空指针或未处理异常

---

## 模块 A5：聊天转发

### A027 普通聊天请求成功
- **前置条件**：存在可用 openai-compatible route
- **输入**：标准 chat completions body
- **动作**：调用 `POST /v1/chat/completions`
- **预期结果**：
  - 返回 `200`
  - 响应结构兼容 OpenAI
  - 包含 `choices`、`model`，若上游返回 usage 则包含 `usage`

### A028 长文本请求成功或被稳定拒绝
- **前置条件**：设置一个接近上游上下文上限的请求体
- **输入**：长 messages 内容
- **动作**：调用 `POST /v1/chat/completions`
- **预期结果**：
  - 若在配置范围内，返回 `200`
  - 若超出限制，返回稳定业务错误（`400` / `413` / 项目定义错误之一）
  - 不出现连接悬挂

### A029 anthropic upstream 适配链路成功
- **前置条件**：存在映射到 anthropic Provider 的 alias
- **输入**：合法聊天请求
- **动作**：调用该 alias
- **预期结果**：
  - 返回 `200`
  - 对外仍返回统一 OpenAI-compatible 结构

### A030 非法请求参数被拒绝
- **前置条件**：系统正常运行
- **输入**：缺少 `model` 或 `messages` 的 JSON
- **动作**：调用 `POST /v1/chat/completions`
- **预期结果**：
  - 返回 `400`
  - 返回统一错误结构

### A031 未授权模型调用被拒绝
- **前置条件**：当前 JWT / API Key 不具备目标模型权限
- **输入**：目标未授权模型
- **动作**：调用 `POST /v1/chat/completions`
- **预期结果**：
  - 返回 `403`
  - 不发起上游调用

---

## 模块 A6：SSE 流式响应

### A032 SSE 正常输出
- **前置条件**：目标模型支持 streaming
- **输入**：`stream=true`
- **动作**：调用 `POST /v1/chat/completions`
- **预期结果**：
  - 返回 `200`
  - `Content-Type=text/event-stream`
  - 至少返回 1 个 `data:` 事件

### A033 SSE 结束标识正确
- **前置条件**：可控 SSE mock upstream
- **输入**：标准 stream 请求
- **动作**：完整消费事件流
- **预期结果**：
  - 流中最终出现完成信号
  - 网关完成收尾并关闭连接

### A034 客户端无 streaming 能力时被拒绝
- **前置条件**：当前客户端 / principal `streaming=false`
- **输入**：`stream=true`
- **动作**：调用 `POST /v1/chat/completions`
- **预期结果**：
  - 返回 `400`
  - 错误码为 `stream_not_supported`
  - 不触发上游流式连接

### A034-1 Provider / adapter 不支持流式时返回 501
- **前置条件**：当前主体允许流式；目标 Provider 或 adapter 本身不支持流式
- **输入**：`stream=true`
- **动作**：调用 `POST /v1/chat/completions`
- **预期结果**：
  - 返回 `501`
  - 错误码为 `stream_not_supported` 或当前实现等价错误
  - 不把该场景误判为主体能力校验失败的 `400`

### A035 Provider 中途断开时按流式失败语义结束
- **前置条件**：上游先返回部分 chunk，再主动断开
- **输入**：`stream=true`
- **动作**：调用并持续读取 SSE
- **预期结果**：
  - 已输出首块后不再静默切换到新 Provider 拼接成功结果
  - 网关按既定失败语义结束当前流

### A036 长时间流式输出保持稳定
- **前置条件**：mock upstream 持续输出多批 chunk
- **输入**：长时流式请求
- **动作**：持续消费 SSE 直到结束
- **预期结果**：
  - 连接不中途无故关闭
  - 最终能正常结束或稳定报告失败

---

## 模块 A7：限流与并发控制

### A037 未达到阈值时请求成功
- **前置条件**：已配置 client rate limit
- **输入**：窗口内低于阈值的连续请求
- **动作**：多次调用聊天接口
- **预期结果**：
  - 均返回 `200`
  - 不返回 `429`

### A038 达到阈值边界时最后一个合法请求成功
- **前置条件**：阈值设置为可控小值
- **输入**：连续发送至边界值
- **动作**：按顺序发起请求
- **预期结果**：
  - 边界内最后一个合法请求返回 `200`
  - 下一个请求返回 `429`

### A039 超过阈值时被拒绝
- **前置条件**：阈值设置为可控小值
- **输入**：快速连续超量请求
- **动作**：调用聊天接口
- **预期结果**：
  - 超限请求返回 `429`
  - 被拒请求不继续走上游

### A040 窗口恢复后再次成功
- **前置条件**：已触发一次 `429`
- **输入**：等待窗口结束后的同类请求
- **动作**：再次调用聊天接口
- **预期结果**：
  - 返回 `200`

### A041 用户 / client 维度限流生效
- **前置条件**：为目标主体配置低限流阈值
- **输入**：同一主体下多次请求
- **动作**：调用聊天接口
- **预期结果**：
  - 命中主体限流时返回 `429`

### A042 API Key 触发同一主体限流
- **前置条件**：同一用户下存在多个 API Key
- **输入**：通过同一主体的某个 API Key 连续请求
- **动作**：调用聊天接口
- **预期结果**：
  - 命中限流时返回 `429`
  - 文档验证的是“API Key 可触发主体限流”，不假设系统存在独立 key-rate-ledger

### A043 模型级或模型相关限制命中时返回稳定结果
- **前置条件**：目标模型绑定了更严格的路由或主体约束
- **输入**：高频请求同一模型
- **动作**：调用聊天接口
- **预期结果**：
  - 命中限制时返回稳定错误码
  - 不出现随机成功 / 失败漂移

---

## 模块 A8：Token 配额

### A044 配额充足时调用成功并完成扣减
- **前置条件**：按 client / 用户主体配置足够的 daily/monthly token 配额
- **输入**：一次成功聊天请求
- **动作**：调用聊天接口后查询内部 usage 汇总
- **预期结果**：
  - 聊天接口返回 `200`
  - `/internal/usage/summary` 中 token 用量增加

### A045 配额不足时调用被拒绝
- **前置条件**：剩余 daily 或 monthly token 配额小于本次所需
- **输入**：一次会超限的请求
- **动作**：调用聊天接口
- **预期结果**：
  - 返回 `429`
  - 错误码为 `quota_exceeded` 或 `monthly_quota_exceeded`
  - 不继续上游调用

### A046 日配额与月配额分别生效
- **前置条件**：分别准备“日不足”和“月不足”两组数据
- **输入**：相同聊天请求
- **动作**：分别发起调用
- **预期结果**：
  - 日配额不足场景返回 `429` 且命中日错误码
  - 月配额不足场景返回 `429` 且命中月错误码

### A047 配额边界值计算正确
- **前置条件**：daily quota 为可控边界值，例如 1000
- **输入**：先消费到 900，再发起估算 100 的请求，再发起估算 1 的请求
- **动作**：连续调用
- **预期结果**：
  - 边界内请求成功
  - 超边界请求返回 `429`

### A048 配额重置后恢复可调用
- **前置条件**：已命中配额不足；存在日维度或月维度重置条件
- **输入**：重置后的同类请求
- **动作**：推进时间窗或重置数据后再次调用
- **预期结果**：
  - 返回 `200`

---

## 模块 A9：Token 计费与预算

### A049 输入 Token 计费正确
- **前置条件**：目标模型已配置 input 单价
- **输入**：已知 input token 数量的成功请求
- **动作**：调用聊天接口后查询成本汇总
- **预期结果**：
  - 成本增加值与 input 单价 × input tokens 一致

### A050 输出 Token 计费正确
- **前置条件**：目标模型已配置 output 单价
- **输入**：已知 output token 数量的成功请求
- **动作**：调用聊天接口后查询成本汇总
- **预期结果**：
  - 成本增加值与 output 单价 × output tokens 一致

### A051 单次费用统计正确
- **前置条件**：日志与成本聚合可查询
- **输入**：一次成功调用
- **动作**：查询 recent 日志与内部成本汇总
- **预期结果**：
  - 单次记录包含 input tokens、output tokens、cost
  - cost 与模型定价一致

### A052 累计费用统计正确
- **前置条件**：已完成多次成功调用
- **输入**：多次不同 usage 的请求结果
- **动作**：查询成本聚合
- **预期结果**：
  - 聚合 cost = 各次明细 cost 之和

### A053 月度费用统计正确
- **前置条件**：存在同月多次调用数据
- **输入**：月度统计查询条件
- **动作**：查询 `/internal/cost/summary` 或 `/internal/cost/client`
- **预期结果**：
  - 返回 `200`
  - 月度汇总字段非负且与明细聚合一致

### A054 不同模型价格分别生效
- **前置条件**：openai-compatible 模型与 anthropic 模型价格不同
- **输入**：分别调用两个模型
- **动作**：查询成本聚合
- **预期结果**：
  - 两个模型产生不同成本结果
  - 聚合结果可区分模型 / provider

### A055 预算超限时调用被拒绝
- **前置条件**：已设置 dailyCost 或 monthlyCost 上限
- **输入**：一组会触发预算超限的请求
- **动作**：调用聊天接口
- **预期结果**：
  - 返回 `429`
  - 错误码为 `budget_exceeded` 或 `monthly_budget_exceeded`
  - 不继续调用上游

---

## 模块 A10：故障转移

### A056 Provider 超时后自动切换备用路由
- **前置条件**：alias 已配置 primary + fallback
- **输入**：primary upstream timeout
- **动作**：调用聊天接口
- **预期结果**：
  - 客户端最终返回 `200`
  - 实际命中 fallback Provider

### A057 Provider 返回 500 后自动切换备用路由
- **前置条件**：存在可用 fallback
- **输入**：primary upstream 返回 `500`
- **动作**：调用聊天接口
- **预期结果**：
  - 客户端最终返回 `200`
  - 日志显示走了 fallback

### A058 Provider 返回 429 后自动切换备用路由
- **前置条件**：存在可用 fallback
- **输入**：primary upstream 返回 `429`
- **动作**：调用聊天接口
- **预期结果**：
  - 客户端最终返回 `200`
  - 日志显示走了 fallback

### A059 全部 Provider 不可用时统一失败
- **前置条件**：primary 与全部 fallback 均失败
- **输入**：合法聊天请求
- **动作**：调用聊天接口
- **预期结果**：
  - 返回统一上游不可用错误
  - 状态码应稳定在实现定义的失败集合内（当前重点关注 `503/504` 或统一业务错误）
  - 不泄漏内部堆栈

---

## 模块 A11：日志审计

### A060 成功请求记录完整日志
- **前置条件**：recent 日志功能开启
- **输入**：一次成功聊天请求
- **动作**：调用 `/internal/requests/recent`
- **预期结果**：
  - recent 列表中存在该请求
  - 记录 `requestId`、主体、模型、provider、token、cost、status

### A061 失败请求记录完整日志
- **前置条件**：准备一个会被限流、配额不足或上游失败的请求
- **输入**：失败聊天请求
- **动作**：调用 `/internal/requests/recent`
- **预期结果**：
  - recent 列表中存在失败记录
  - 包含失败码或失败原因

### A062 Token 消耗记录正确
- **前置条件**：完成一次成功调用
- **输入**：requestId
- **动作**：调用 `/internal/requests/{requestId}`
- **预期结果**：
  - 明细中 token 字段与本次 usage 对齐

### A063 费用记录正确
- **前置条件**：完成一次有定价的成功调用
- **输入**：requestId 或成本查询条件
- **动作**：查询 request detail / cost summary
- **预期结果**：
  - 成本字段大于等于 `0`
  - 金额与定价配置一致

### A064 状态码记录正确
- **前置条件**：准备 1 次成功请求、1 次失败请求
- **输入**：两个 requestId
- **动作**：分别查询 detail
- **预期结果**：
  - 成功请求记录状态码 `200`
  - 失败请求记录实际失败状态码

---

## 4. 验收进入准则

- mock upstream 已准备：openai-compatible + anthropic
- 已准备管理员、普通用户、受限用户或受限 API Key
- 已准备至少一条 openai-compatible 主路由、一条 anthropic 路由和一条 fallback 链路
- 若验证共享状态一致性，Redis / PostgreSQL 测试环境需可用；若仅做本地主线验收，可使用 H2 + in_memory

---

## 5. 验收退出准则

- 认证、API Key、Provider、模型编排、聊天、SSE、限流、配额、计费、故障转移、日志均有对应验收用例
- 正常流、异常流、边界流均有覆盖
- 所有用例预期结果都可量化、可验证、可自动化
- 高风险模块（限流、配额、计费、Provider 路由、故障转移）全部完成三层覆盖映射：Acceptance + Integration + Domain

---

## 6. 当前假设与范围说明

1. 当前支持的自助用量接口为 `/auth/usage/recent` 与 `/auth/usage/costs`；`/auth/usage/summary` 不在现行支持面中，不纳入验收对象。
2. 权威 usage/cost 汇总优先使用 `/internal/usage/summary`、`/internal/cost/summary`、`/internal/cost/client`。
3. `/v1/models` 在本轮重点验证的是 **API Key `allowedModels` 过滤效果**，不扩大成所有主体的完整授权视图。
4. `/internal/requests/recent` 是 recent 视图，不作为全历史审计接口验收。
5. 当前认证链同时兼容 store-backed 用户与静态 YAML 用户路径；若只验主线，需要在用例前置条件里明确。
6. 一致性断言分级：`/internal/usage/summary`、`/internal/cost/summary`、`/internal/cost/client` 按强一致口径断言数值增长；`/auth/usage/recent`、`/internal/requests/recent`、`/internal/requests/{requestId}` 按近实时视图验证可见性；`/auth/me` 中的 `quota` 当前只验证结构/可用性。
7. 真实实现存在 `/admin/routes/*` 低层配置接口；该接口族暂不纳入本轮验收主线覆盖。

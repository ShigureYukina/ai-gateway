# AI Gateway 测试设计（三）— 业务规则测试

> 本文件面向领域服务 / 核心规则层，重点验证边界值、状态转换与异常路径。

---

## 1. 设计目标

业务规则测试回答的问题：

- 认证与授权规则是否严谨
- 配置解析与路由编排是否符合实现语义
- 运行时治理规则是否可预测
- 观测与结算规则是否稳定、可追踪

建议以 **JUnit 5 + Mockito + Reactor Test** 为主，对核心 service 做高密度边界覆盖。

> 说明：Domain 层是本项目测试分层中的**核心价值层**，最能稳定承接业务规则、边界值与异常语义。与 Acceptance / Integration 相比，首版更建议优先保留本层的高密度自动化覆盖，而不是过度压缩。

### 1.1 首版建议优先自动化的规则测试集合

建议首版优先完成以下高价值规则集合：

1. **认证与授权**：S001-S012、S012-1
2. **模型解析与列表过滤**：S013-S017、S020、S021、S021-1
3. **限流 / 并发 / 配额**：S022-S032
4. **计费 / 预算**：S033-S038
5. **故障转移**：S039-S045
6. **流式与日志结算关键规则**：S046-S053

> 实施建议：若首版需要继续收敛，优先保证 S010、S021-1、S022-S032、S033-S040、S047、S050-S053 这组规则先自动化落地。

---

## 2. 规则分组

1. 认证与授权规则
2. 配置解析与路由编排规则
3. 运行时治理规则
4. 观测与结算规则

---

## 3. Domain Service Test 清单

## 模块 S1：认证与授权规则

### S001 正确账号密码生成 JWT
- **输入条件**：合法用户名、密码
- **执行动作**：认证并生成 token
- **预期结果**：生成合法 access / refresh token

### S002 错误密码认证失败
- **输入条件**：存在用户、错误密码
- **执行动作**：authenticate
- **预期结果**：抛出 `invalid_credentials` 或等价错误

### S003 冻结用户不可通过认证
- **输入条件**：冻结用户、正确密码
- **执行动作**：authenticate
- **预期结果**：返回 `account_frozen`

### S004 refresh 只接受 refresh token
- **输入条件**：把 access token 传入 refresh 流程
- **执行动作**：refresh
- **预期结果**：返回 `invalid_token_type`

### S005 tokenVersion 变化后旧 token 失效
- **输入条件**：旧 token + 新 tokenVersion
- **执行动作**：token 校验
- **预期结果**：旧 token 被拒绝

### S006 refresh token 黑名单生效
- **输入条件**：已进入 blacklist 的 refresh token
- **执行动作**：refresh
- **预期结果**：返回 `token_revoked`

### S007 API Key 禁用后立即失效
- **输入条件**：已禁用 key
- **执行动作**：使用 key 调用 authenticate
- **预期结果**：认证失败

### S008 API Key 删除后彻底失效
- **输入条件**：已删除 key
- **执行动作**：`findByApiKey` / authenticate
- **预期结果**：查无结果

### S009 API Key 轮换后旧值失效
- **输入条件**：已有 key
- **执行动作**：`rotateApiKey`
- **预期结果**：新 key 可用、旧 key 不可用

### S010 API Key `allowedModels` 精确生效
- **输入条件**：`key.allowedModels={A,B}`，请求模型 `C`
- **执行动作**：`authorizeModel`
- **预期结果**：拒绝访问 `C`

### S011 用户 `allowedModels` 与 `keyAllowedModels` 从严叠加
- **输入条件**：用户允许 `{A,B,C}`，key 允许 `{A}`
- **执行动作**：授权检查
- **预期结果**：仅 `A` 可访问

### S012 无 streaming 能力时拒绝流式请求
- **输入条件**：principal `streaming=false`，请求 `stream=true`
- **执行动作**：`validateRequestCapabilities`
- **预期结果**：抛出 `400` + `stream_not_supported`

### S012-1 Provider / adapter 不支持流式时返回 501
- **输入条件**：principal 允许流式，但目标 Provider / adapter 不支持 `stream=true`
- **执行动作**：流式能力校验或适配层分发
- **预期结果**：抛出 `501` + `stream_not_supported` 或当前实现等价错误

---

## 模块 S2：配置解析与路由编排规则

### S013 alias 先解析到 scene，再解析主备 routes
- **输入条件**：alias route 配置了 scene
- **执行动作**：`ModelRouteResolver.resolve`
- **预期结果**：得到 primary route 与 fallback routes

### S014 指定 provider 条件时只返回匹配候选
- **输入条件**：多个 provider，指定 `provider=openai`
- **执行动作**：构建模型列表或解析路由
- **预期结果**：仅保留匹配 provider 的结果

### S015 disabled route 不参与候选
- **输入条件**：一个禁用 route，一个启用 route
- **执行动作**：候选解析与选择
- **预期结果**：只选择启用 route

### S016 disabled provider 不参与候选
- **输入条件**：一个禁用 provider，一个启用 provider
- **执行动作**：候选解析与选择
- **预期结果**：不选择禁用 provider

### S017 model-group 成员顺序决定 primary / fallback 角色
- **输入条件**：members=[A,B,C]
- **执行动作**：生成 model-group 配置
- **预期结果**：A 为 primary，B/C 按顺序成为 fallback

### S018 `weight` 不在 model-group 层默认断言为加权选主
- **输入条件**：members 含不同 `weight`
- **执行动作**：构建 model-group
- **预期结果**：测试重点是主备编排顺序，不把 group member 解释成加权随机选主

### S019 WRR 负载均衡按 route 权重生效
- **输入条件**：两条可用 route，权重 `100:20`
- **执行动作**：多次 load balance
- **预期结果**：分布近似权重比例

### S020 `ModelListProvider` 无数据时 `/v1/models` 返回空列表
- **输入条件**：provider 为空或 `hasData=false`
- **执行动作**：`listModels`
- **预期结果**：返回空 `data`

### S021 `ModelListService` 按优先级组装模型列表
- **输入条件**：同时存在 snapshot、model-group、本地配置
- **执行动作**：`buildModels`
- **预期结果**：优先级为 snapshot > model-group > local config

### S021-1 `/v1/models` 主线只验证 API Key `allowedModels` 过滤
- **输入条件**：成功鉴权的 API Key 配置了 `allowedModels`
- **执行动作**：构建或过滤模型列表
- **预期结果**：测试重点是该 Key 可见模型过滤效果，不把 `/v1/models` 扩写成所有主体统一授权视图；未鉴权场景不作为本轮主线强约束

---

## 模块 S3：运行时治理规则

### S022 限流阈值内请求放行
- **输入条件**：当前计数小于阈值
- **执行动作**：`rateLimiter.check`
- **预期结果**：放行

### S023 达到阈值边界时行为正确
- **输入条件**：当前计数处于阈值边界
- **执行动作**：连续两次 `check`
- **预期结果**：边界内最后一次通过，下一次拒绝

### S024 超过阈值时抛出限流异常
- **输入条件**：当前计数超过阈值
- **执行动作**：`check`
- **预期结果**：抛出限流异常

### S025 窗口过期后计数恢复
- **输入条件**：窗口内已满，推进到下一窗口
- **执行动作**：再次 `check`
- **预期结果**：恢复放行

### S026 并发信号量在完成 / 异常 / cancel 时均释放
- **输入条件**：正常完成、异常结束、取消三种路径
- **执行动作**：`acquire -> finalize`
- **预期结果**：并发计数最终归还

### S027 日配额充足时允许调用
- **输入条件**：已用 800，本次 100，限额 1000
- **执行动作**：`checkDailyQuota`
- **预期结果**：通过

### S028 日配额不足时返回 `quota_exceeded`
- **输入条件**：已用 950，本次 100，限额 1000
- **执行动作**：`checkDailyQuota`
- **预期结果**：抛出 `429` + `quota_exceeded`

### S029 月配额不足时返回 `monthly_quota_exceeded`
- **输入条件**：接近月上限
- **执行动作**：`checkMonthlyQuota`
- **预期结果**：抛出 `429` + `monthly_quota_exceeded`

### S030 成功调用后按实际 usage 扣减配额
- **输入条件**：一次成功调用返回 usage
- **执行动作**：record usage
- **预期结果**：已用 token 增加准确值

### S031 流式调用只结算一次 usage
- **输入条件**：SSE 多个 chunk，完成时提供 usage
- **执行动作**：`recordStreamingUsageOnSuccess`
- **预期结果**：只结算一次，值准确

### S032 TPM 预估与回收一致
- **输入条件**：估算 token 与实际 token 不同
- **执行动作**：`reserve -> complete/reconcile`
- **预期结果**：最终占用与实际一致

### S033 OpenAI-compatible 模型费用计算正确
- **输入条件**：input/output 单价与 token 用量已知
- **执行动作**：`CostCalculator.calculate`
- **预期结果**：金额正确

### S034 anthropic 模型费用计算正确
- **输入条件**：anthropic 模型定价与 token 用量已知
- **执行动作**：`calculate`
- **预期结果**：金额正确

### S035 无价格配置时行为稳定
- **输入条件**：模型缺少 pricing
- **执行动作**：`calculate`
- **预期结果**：按项目约定稳定返回，不抛未处理异常

### S036 成本累计正确
- **输入条件**：多次不同 usage 记录
- **执行动作**：`recordCostOnSuccess`
- **预期结果**：日 / 月累计正确

### S037 日预算不足时返回 `budget_exceeded`
- **输入条件**：接近日预算上限
- **执行动作**：`checkDailyBudget`
- **预期结果**：抛出 `429` + `budget_exceeded`

### S038 月预算不足时返回 `monthly_budget_exceeded`
- **输入条件**：接近月预算上限
- **执行动作**：`checkMonthlyBudget`
- **预期结果**：抛出 `429` + `monthly_budget_exceeded`

### S039 primary 正常时不走 fallback
- **输入条件**：primary healthy
- **执行动作**：发起调用
- **预期结果**：仅调用 primary

### S040 primary timeout 触发 fallback
- **输入条件**：primary timeout，fallback healthy
- **执行动作**：`callWithFallback` / `streamWithFallback`
- **预期结果**：fallback 成功返回

### S041 primary 返回 500 触发 fallback
- **输入条件**：primary=500
- **执行动作**：发起调用
- **预期结果**：切换 fallback

### S042 primary 返回 429 触发 fallback
- **输入条件**：primary=429
- **执行动作**：发起调用
- **预期结果**：切换 fallback

### S043 全部候选失败时返回统一错误
- **输入条件**：全部 route 不可用
- **执行动作**：发起调用
- **预期结果**：统一错误，不泄漏内部细节

### S044 连续失败达到阈值后熔断打开
- **输入条件**：连续 retryable failures
- **执行动作**：记录失败
- **预期结果**：route / provider 状态变为 open

### S045 open-duration 过后允许半开恢复
- **输入条件**：route 已 open，推进到恢复时间
- **执行动作**：再次选择路由
- **预期结果**：允许试探调用

---

## 模块 S4：观测与结算规则

### S046 流式首块输出前失败可尝试 fallback
- **输入条件**：stream 初始化阶段失败，尚未向客户端输出业务 chunk
- **执行动作**：发起 streaming
- **预期结果**：允许 fallback

### S047 流式已输出后失败不再 fallback
- **输入条件**：已发出首个业务 chunk，随后失败
- **执行动作**：streaming 中断
- **预期结果**：结束当前流并记录失败，不拼接新流

### S048 `[DONE]` 终止信号识别正确
- **输入条件**：SSE 数据包含 `[DONE]`
- **执行动作**：解析流
- **预期结果**：正常结束并触发收尾逻辑

### S049 流式 usage 缺失时结算行为稳定
- **输入条件**：流结束但 usage 不完整
- **执行动作**：完成结算与记录
- **预期结果**：不抛未处理异常，按项目约定记录默认值或估算值

### S050 成功请求记录完整日志
- **输入条件**：一次成功调用
- **执行动作**：`CompletionRecorder` success path
- **预期结果**：明细与聚合均记录

### S051 失败请求记录失败日志
- **输入条件**：限流、配额不足、上游 500 等失败
- **执行动作**：`CompletionRecorder` failure path
- **预期结果**：日志包含 `requestId`、失败码、关键上下文

### S052 recent 视图与权威汇总职责分离
- **输入条件**：完成一次成功调用
- **执行动作**：分别读取 recent log、usage summary、cost summary
- **预期结果**：
  - recent 负责明细可见性
  - internal summary 负责汇总统计
  - 不要求两者字段形态完全一致

### S052-1 一致性断言按接口分级
- **输入条件**：完成一次或多次成功调用
- **执行动作**：分别验证自助视图、recent 视图、internal 汇总
- **预期结果**：
  - `/internal/usage/summary`、`/internal/cost/summary`、`/internal/cost/client` 按强一致口径断言数值增长
  - `/auth/usage/recent`、`/internal/requests/recent`、`/internal/requests/{requestId}` 按近实时视图验证可见性与结构
  - `/auth/me.quota` 当前只验证结构/可用性

### S053 internal 非法日期参数回退当天
- **输入条件**：`day=bad-date`
- **执行动作**：调用内部汇总日期解析逻辑
- **预期结果**：按当前实现回退当天，而不是抛 `400`

---

## 4. 高优先级建议补测点

1. `ModelListService`：模型列表组装优先级、过滤与 pricing 融合
2. `ProviderHealthService`：`test` / `fetchModels` 成功与失败边界
3. `CompletionRecorder`：streaming 成功 / 失败路径分叉
4. `RequestLogService`：失败请求、掩码字段、聚合一致性
5. TPM reserve / reconcile / release 在流式中的一致性

---

## 5. 当前假设与范围说明

1. 规则测试明确区分：认证授权、配置编排、运行时治理、观测结算四层。
2. model-group 层重点验证 primary / fallback 编排，不把 `weight` 误写成 group 内加权选主。
3. 限流、配额、计费、Provider 路由、故障转移五类高风险规则必须与 Acceptance / Integration 形成三层映射。
4. 真实实现存在 `/admin/routes/*` 低层配置接口；本文件暂不将其纳入当前主线规则测试映射。

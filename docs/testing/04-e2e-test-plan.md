# AI Gateway 测试设计（四）— 端到端测试计划与总览

> 本文件定义完整业务链路测试，并给出覆盖矩阵、高风险场景、自动化建议与验收结论标准。

---

## 1. E2E 测试目标

E2E 测试验证多个模块协同工作是否正确，重点关注：

- 登录 / API Key / 聊天调用 / 日志记录的完整主链路
- OpenAI-compatible 对外协议，以及 anthropic upstream 适配链路是否都能走通
- streaming、限流、配额、预算、故障转移、日志统计是否跨模块一致

> 收敛说明：E2E 层保留完整规划，但首版不追求一次性打满全部链路。首版只需优先打通 **E001 / E003 / E006 / E007 / E012 / E015** 六条主线，其余 E2E 作为 Phase 2+ 增强项。

---

## 2. 环境建议

### 2.1 轻量本地 E2E
- Spring Boot + H2 + in_memory
- WireMock / Node mock upstream
- 目标：功能冒烟、主链路快速回归

### 2.2 共享状态增强 E2E
- PostgreSQL Testcontainers（验证 usage / cost / trace / aggregate store）
- Redis Testcontainers（验证共享限流 / TPM / 状态存储）
- openai-compatible mock upstream
- anthropic mock upstream
- 目标：验证共享状态与多种 store 实现的一致性

### 2.3 上游模拟要求

至少准备以下 mock：

1. openai-compatible 普通成功响应
2. openai-compatible SSE 成功响应
3. anthropic 普通成功响应
4. anthropic SSE / timeout / `429` / `500` 响应

---

## 3. E2E 测试清单

### 3.1 MVP E2E（首版最低可交付）

首版建议仅要求以下六条主线通过：

- **E001** 登录到普通聊天完整链路
- **E003** 创建 API Key 后用 Key 调用模型
- **E006** SSE 流式调用完整链路
- **E007** 限流拒绝链路
- **E012** Provider 故障转移链路
- **E015** 调用日志查询链路

### 3.2 Phase 2+ E2E（后续增强）

以下编号建议放入 Phase 2+，用于补齐能力广度与异常深度：

- E002、E004、E005
- E006-1
- E008-E011
- E013-E014
- E016

### E001 登录到普通聊天完整链路
- **链路**：登录 → 获取 JWT → 调用 `/v1/chat/completions` → 查询 recent log
- **验证点**：
  - 登录返回 `200`
  - 聊天返回 `200`
  - recent log 出现该请求

### E002 登录到 anthropic upstream 适配链路
- **链路**：登录 → 调用映射到 anthropic 的 alias
- **验证点**：
  - 返回 `200`
  - 对外仍是 OpenAI-compatible 响应结构
  - 日志中 provider=anthropic

### E003 创建 API Key 后用 Key 调用模型
- **链路**：登录 → 创建 API Key → 使用该 Key 调用聊天接口
- **验证点**：
  - 创建 Key 返回 `200`
  - 聊天返回 `200`
  - 日志中记录 keyId / 用户 / 模型

### E004 禁用 API Key 后调用失败链路
- **链路**：创建 Key → 禁用 Key → 使用禁用 Key 调用
- **验证点**：
  - Key 更新返回 `204`
  - 聊天返回 `401`
  - 不发生上游请求

### E005 API Key 白名单限制链路
- **链路**：创建受限 Key → 查询 `/v1/models` → 调用授权模型 / 未授权模型
- **验证点**：
  - `/v1/models` 当前主线验证“成功鉴权的 API Key 的 `allowedModels` 过滤效果”，仅返回授权模型
  - 授权模型调用 `200`
  - 未授权模型调用 `403`

### E006 SSE 流式调用完整链路
- **链路**：登录或 API Key → `stream=true` → 消费 SSE → 正常结束
- **验证点**：
  - `Content-Type=text/event-stream`
  - 至少一个 `data:` 事件
  - 存在结束信号

### E006-1 流式状态码分场景校验
- **链路**：分别构造“主体不支持流式”与“Provider / adapter 不支持流式”两类请求
- **验证点**：
  - 主体能力不支持 `stream=true` 时返回 `400`
  - Provider / adapter 本身不支持流式时返回 `501`
  - 避免把所有 `stream_not_supported` 都验成同一类 `400`

### E007 限流拒绝链路
- **链路**：降低阈值 → 连续请求 → 命中限流
- **验证点**：
  - 前几次请求 `200`
  - 超限后请求 `429`
  - recent log 中可见失败记录

### E008 配额扣减链路
- **链路**：设置 token 配额 → 发起成功调用 → 查询 `/internal/usage/summary`
- **验证点**：
  - 聊天返回 `200`
  - internal usage summary 中 token 用量增长

### E009 配额不足拒绝链路
- **链路**：设置极小 daily/monthly token 配额 → 发起请求
- **验证点**：
  - 请求返回 `429`
  - 错误码为 `quota_exceeded` 或 `monthly_quota_exceeded`
  - 不调用上游

### E010 成本统计链路
- **链路**：设置模型价格 → 发起多次调用 → 查询 `/internal/cost/summary` 与 `/internal/cost/client`
- **验证点**：
  - 成本大于 `0`
  - 聚合值等于明细求和

### E011 预算超限拒绝链路
- **链路**：设置低预算 → 多次调用累计 → 触发预算超限
- **验证点**：
  - 超限前请求成功
  - 超限后请求返回 `429`
  - 错误码为 `budget_exceeded` 或 `monthly_budget_exceeded`

### E012 Provider 故障转移链路
- **链路**：primary 失败 → fallback 成功
- **验证点**：
  - 客户端最终返回 `200`
  - 日志中能看见实际命中的 fallback provider / route

### E013 全部 Provider 失败链路
- **链路**：primary 失败 + fallback 失败
- **验证点**：
  - 返回统一错误结构
  - 不泄漏内部堆栈

### E014 配置 Provider 与模型分组后立即可调用
- **链路**：新增 Provider → 新增模型分组 → 查询 `/v1/models` → 发起聊天
- **验证点**：
  - `/v1/models` 可见新 alias
  - 聊天调用返回 `200`

### E015 调用日志查询链路
- **链路**：发起聊天 → 查询 `/internal/requests/recent` → 查询 `/internal/requests/{requestId}`
- **验证点**：
  - recent 中有该请求
  - detail 中 token / cost / status / provider 字段正确
  - 作为近实时视图验证可见性与结构，不把 recent/detail 当作强一致聚合口径

### E016 自助视图与内部统计源差异链路
- **链路**：发起调用 → 查询 `/auth/usage/recent`、`/auth/usage/costs`、`/internal/usage/summary`、`/internal/cost/summary`
- **验证点**：
  - 自助接口可查询当前用户近期视图
  - internal 接口给出权威汇总视图
  - 不要求两类接口返回完全相同字段结构
  - `/auth/usage/recent` 按近实时视图验证可见性；`/internal/usage/summary`、`/internal/cost/summary` 按强一致口径断言数值增长

---

## 4. 测试模块总览

### 4.1 业务验收测试模块
- 认证鉴权
- API Key 管理
- Provider 管理
- 模型别名 / 模型分组 / 路由编排
- 聊天转发
- SSE 流式响应
- 限流 / 配额 / 预算 / 计费
- 故障转移
- 日志审计

### 4.2 接口集成测试模块
- `/auth/*`
- `/admin/users*`
- `/admin/providers*`
- `/admin/model-groups*`
- `/v1/chat/completions`
- `/v1/models`
- `/internal/requests/*`
- `/internal/usage/*`
- `/internal/cost/*`

> 说明：真实实现存在 `/admin/routes/*` 低层配置接口，但本轮 E2E 主线暂不纳入覆盖。

### 4.3 业务规则测试模块
- 认证与授权规则
- 配置解析与路由编排规则
- 运行时治理规则
- 观测与结算规则

### 4.4 E2E 主链路
- 登录到调用全链路
- API Key 到调用全链路
- 配额 / 预算 / 统计全链路
- Provider fallback 全链路
- 日志与汇总查询全链路

---

## 5. 测试覆盖矩阵

| 功能模块 | Acceptance | Integration | Domain | E2E |
|---|---|---|---|---|
| 认证鉴权 | A001-A007 | I001-I010 | S001-S012、S012-1 | E001-E004 |
| API Key 管理 | A008-A015 | I011-I016, I022 | S007-S011 | E003-E005 |
| Provider 管理 | A016-A021 | I024-I032 | S013-S021, S039-S045 | E012-E014 |
| 模型编排 / 模型列表 | A022-A026 | I033-I039 | S013-S021 | E005, E014 |
| 聊天转发 | A027-A031 | I036-I047 | S012-S021, S039-S049 | E001-E003, E012-E013 |
| SSE 流式响应 | A032-A036 | I037、I043、I043-1 | S012、S012-1、S046-S049 | E006、E006-1 |
| 限流 | A037-A043 | I044 | S022-S026 | E007 |
| Token 配额 | A044-A048 | I045 | S027-S032 | E008-E009 |
| Token 计费 / 预算 | A049-A055 | I046, I050-I053 | S033-S038、S052、S052-1 | E010-E011, E016 |
| 故障转移 | A056-A059 | I047 | S039-S045, S046-S047 | E012-E013 |
| 日志审计 / 统计 | A060-A064 | I048-I054 | S050-S053、S052-1 | E015-E016 |

---

## 6. 需求→测试追踪矩阵

| 需求 / 业务目标 | Acceptance | Integration | Domain | E2E | 首版优先级 |
|---|---|---|---|---|---|
| 用户登录并完成首次调用 | A001、A004、A027 | I001、I005、I036、I040 | S001-S005 | E001 | MVP |
| 用户创建 API Key 并独立调用 | A008、A010、A013 | I011-I016 | S007-S011 | E003 | MVP |
| 流式调用可正常完成 | A032-A035 | I037、I043、I043-1 | S012、S012-1、S046-S049 | E006 | MVP |
| 限流可正确阻断超量请求 | A037-A043 | I044 | S022-S026 | E007 | MVP |
| Provider 故障时能切换备用路由 | A056-A059 | I047 | S039-S047 | E012 | MVP |
| 调用日志与明细可追踪 | A060-A064 | I048、I049、I052、I053 | S050-S053 | E015 | MVP |
| anthropic upstream 适配链路可用 | A029 | I036、I047 | S033-S045 | E002 | Phase 2+ |
| 模型白名单 / `/v1/models` 过滤正确 | A015、A025 | I038、I039、I042 | S010、S021-1 | E005 | Phase 2+ |
| 配额扣减与阻断正确 | A044-A048 | I045、I052 | S027-S032 | E008、E009 | Phase 2+ |
| 计费 / 预算统计正确 | A049-A055 | I046、I050-I053 | S033-S038、S052 | E010、E011、E016 | Phase 2+ |
| 配置新增后可立即对外提供模型 | A016-A025 | I024-I035 | S013-S021 | E014 | Phase 2+ |
| 所有候选 Provider 均失败时稳定报错 | A059 | I047 | S043、S047 | E013 | Phase 2+ |

---

## 7. 高风险场景

以下项目必须同时拥有：Acceptance + Integration + Domain 三层覆盖。

### 7.1 限流
- 风险：时间窗、共享状态、边界值、释放时机

### 7.2 配额
- 风险：预估与实际 usage 偏差、streaming 结算时机

### 7.3 Token 计费
- 风险：input/output 双价、跨模型差异、聚合一致性

### 7.4 Provider 路由
- 风险：alias / scene / routes 解析偏差、禁用配置失效

### 7.5 故障转移
- 风险：timeout / `429` / `500` 重试与 fallback 行为错误

---

## 8. 异常与边界专项提醒

### 8.1 参数边界
- 空参数
- null 参数
- 超长字符串
- 重复名称
- 不存在 ID
- 最大 token 请求
- 最大上下文请求

### 8.2 异常路径
- PostgreSQL 异常
- Redis 不可用
- Provider timeout
- Provider 拒绝请求
- Provider 网络异常
- SSE 中断
- 非法 JSON
- 非法 Authorization

### 8.3 日期解析差异
- `/auth/usage/*` 非法日期：按当前实现返回 `400`
- `/internal/*` 非法 `day`：按当前实现回退当天，不返回 `400`

---

## 9. 自动化测试建议

### 9.1 预计测试用例总数
- Acceptance：65 条（含 `A034-1`）
- Integration：55 条（含 `I043-1`）
- Domain：56 条（含 `S012-1`、`S021-1`、`S052-1`）
- E2E：17 条（含 `E006-1`）

**预计总数：193 条**

> 说明：这是规划总量，不要求一次性全部落地。

### 9.1.1 MVP 测试集 / 最低可交付版本

若以“首版能上线做受控演示 / 冒烟回归”为目标，建议把最低可交付测试集收敛为：

- **E2E**：E001、E003、E006、E007、E012、E015
- **Integration**：至少覆盖与上述六条 E2E 直接映射的登录、API Key、聊天、流式、限流、fallback、recent/detail 查询接口
- **Domain**：至少覆盖鉴权、allowedModels 过滤、限流、配额 / 预算、fallback、日志结算关键规则
- **Acceptance**：至少完成与上述六条主线对应的业务验收走查

> 该 MVP 测试集的目标是证明系统“能登录、能发 key、能调用、能流式、能限流、会 fallback、可追踪日志”，而不是首版一次性完成全部规划项。

### 9.2 推荐自动化覆盖率
- Domain：90%+
- Integration：80%+
- E2E：60%+
- Acceptance：70%+

### 9.3 第一优先级自动化场景
1. 登录 / 刷新 / 登出 / 非法 token
2. API Key 创建、禁用、删除、轮换
3. `/v1/chat/completions` 普通成功 / `401` / `403` / `429` / 统一上游失败
4. `/v1/models` 的 `allowedModels` 过滤
5. SSE 成功 / 主体 streaming 被禁（`400`）/ Provider 不支持流式（`501`）/ 中途断开
6. 配额扣减与预算阻断
7. primary → fallback 切换
8. recent log 与 internal summary 查询

---

## 10. 分阶段落地建议

### Phase 1：主线可用
- E001 / E003 / E006 / E007 / E012 / E015
- I001 / I011 / I024 / I034 / I036 / I048
- S001 / S010 / S022 / S027 / S033 / S040 / S047

### Phase 2：治理能力补强
- 配额、预算、统计、日志
- anthropic upstream 适配专项
- `/v1/models` 与模型编排一致性

### Phase 3：共享状态与异常边界
- Redis / PostgreSQL 异常
- 长时间流式
- 多实例共享状态一致性

---

## 11. 测试覆盖结论标准

通过标准：

- 需求覆盖率 ≥ 95%
- 高风险业务覆盖率 = 100%
- 核心接口覆盖率 = 100%
- 关键业务规则覆盖率 = 100%
- 所有测试项均具备可执行、可量化预期

满足以上条件后，测试设计可进入开发阶段。

---

## 12. 当前假设与范围说明

1. `/v1/models` 在本轮 E2E 主线中，重点验证成功鉴权 API Key 的 `allowedModels` 过滤效果；未鉴权场景不作为本轮主线强约束，也不把该接口表述为所有主体统一授权视图。
2. 一致性断言分级：`/internal/usage/summary`、`/internal/cost/summary`、`/internal/cost/client` 按强一致口径断言数值增长；`/auth/usage/recent`、`/internal/requests/recent`、`/internal/requests/{requestId}` 按近实时视图验证可见性；`/auth/me.quota` 当前只验证结构/可用性。
3. 真实实现存在 `/admin/routes/*` 低层配置接口，但本轮 E2E 主线暂不纳入覆盖。

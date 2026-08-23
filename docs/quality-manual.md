# Simple AI Gateway 代码质量手册

> 本文是**工程维护入口**，面向“如何在不破坏项目边界的前提下持续修改代码”。
> - 启动、配置、调用流程看 [`usage.md`](./usage.md)
> - 接口语义与错误模型看 [`api-reference.md`](./api-reference.md) / [`openapi.json`](./openapi.json)
> - 黑盒覆盖入口看 [`testing/endpoint-blackbox-coverage.md`](./testing/endpoint-blackbox-coverage.md)
> - 本文只保留高价值、可审查规则，不展开低价值格式约束

---

## 目标

- 稳住模块边界：`bootstrap -> gateway-admin -> gateway-core`
- 控制 Web 层膨胀、核心类职责过重、前端 API 文件持续堆积
- 固化“最小必要验证”，避免过测和漏测
- 明确文档同步责任，减少实现与文档漂移

---

## 当前仓库重点关注

- 当前整体结构健康，但主要质量瓶颈仍在**模块耦合密度**与**依赖链深度**
- 当前优先关注以下热点位置：
  - `gateway-admin/src/main/java/io/gateway/oss/admin/web/InternalUsageSummaryController.java`
  - `gateway-core/src/main/java/io/gateway/oss/core/observability/RequestLogService.java`
  - `gateway-core/src/main/java/io/gateway/oss/core/web/CompletionRecorder.java`
  - `gateway-core/src/main/java/io/gateway/oss/core/config/DynamicConfigService.java`
  - `gateway-core/src/main/java/io/gateway/oss/core/security/UserAccountService.java`
  - `frontend/src/api/modules/admin.ts`

这些位置不代表一定有缺陷，但属于后续改动时最容易继续变重、变乱的位置。

---

## 拆分触发器

满足以下任一条件时，默认应评估拆分，而不是继续在原位置堆逻辑：

| 对象 | 触发条件 |
|------|------|
| Controller | 直接依赖 ≥ 6；单方法同时承担鉴权/参数解析/聚合计算/DTO 组装；出现跨 Store 聚合、topN、排序分页、口径转换 |
| Service | 同时承担内存态、持久化、重试、序列化、生命周期；读模型查询与写模型持久化混在同一类 |
| 前端 API 文件 | 单文件超过约 150~200 行；同时覆盖 3 个以上子域；稳定接口仍大量使用 `unknown` / `Record<string, unknown>` |
| 前端页面 | 单文件超过约 200 行；同时承担 query、filter、transform、dialog、formatting 等多类职责 |

优先拆分落点：

- 后端：`QueryService` / `ReadFacade` / `Assembler` / 子域 `Service`
- 前端：子域 API 文件 / custom hook / 子组件

---

## 必守规则

### 1. 模块边界不可逆

- 只允许：`bootstrap -> gateway-admin -> gateway-core`
- 禁止 `gateway-core` 依赖 `gateway-admin`
- 禁止把业务逻辑塞进 `bootstrap`

### 2. Controller 只做接口层

- 允许：参数接收、鉴权、调用 Service / Contract、返回响应
- 禁止：复杂聚合、排序过滤主逻辑、多 Store 拼装、核心业务分支
- internal 且只读接口若直接依赖 Store，必须满足：**单一数据源、无写操作、无复杂业务分支、无跨源聚合、无 topN/排序分页/口径转换**
- 只要涉及 dashboard / overview / summary / reporting 这类读型聚合，默认下沉到 `QueryService` 或 `ReadFacade`

### 3. 新增 admin 子域优先走 feature contract

- 新增 Provider / Route / Client / User / System 管理能力时，优先新增专用读口/写口
- 不要继续把所有读取/写入都堆回中心化总接口
- 系统总入口 Controller 不要继续吸附告警、同步、观测、请求日志等杂项能力

### 4. 统一依赖注入与装配风格

- 新代码统一使用 `private final` + 构造器注入
- 优先 `@RequiredArgsConstructor`
- 禁止新增字段注入
- 禁止在生产路径手动 `new` 复杂协作对象，除非它是**无状态、无外部依赖**的轻量 helper

### 5. 业务异常必须使用统一模型

- 可预期业务失败统一使用 `GatewayException`
- 禁止用 `RuntimeException` / `IllegalArgumentException` 表达业务拒绝
- 新接口必须保持稳定错误语义，不能同类错误各返回一套格式

### 6. 多写点操作必须显式评估事务

- 只要一次操作涉及多表、多 Store 或“持久化 + 审计 + 快照 + 通知”，必须评估事务边界
- 需要事务时使用 `@Transactional(rollbackFor = Exception.class)`
- 必须区分：
  - 哪些属于同步成功条件
  - 哪些属于异步副作用
  - 异步失败是否允许降级

### 7. 控制核心类复杂度

- 类超过约 300 行、依赖过多、同时承担查询/写入/拼装/生命周期时，应评估拆分
- 优先拆分职责，而不是继续增加分支
- 对当前热点文件，禁止继续把新聚合、新重试、新过滤逻辑直接堆回原类

### 8. 前端请求必须统一出口

- 页面和组件不得直接 `fetch`
- 所有 HTTP 请求统一经过 `frontend/src/api/http.ts` 与 `frontend/src/api/modules/*`
- 服务端数据优先交给 React Query，Zustand 只保留会话态和轻量全局状态

### 9. 稳定接口优先显式 DTO，避免松散契约扩散

- 后端稳定 admin / internal 返回优先使用明确 DTO，避免长期使用 `Map<String, Object>` 作为对外契约
- 前端稳定接口应优先收敛为明确类型，避免把后端松散结构传导成 `unknown` / `any`

### 10. 前端页面只做装配，不做总线式编排

- 页面优先负责路由容器、调用 hooks、组织 UI
- query 参数构造、响应转换、分页筛选、提交映射优先抽到 custom hook / helper
- Providers / Clients / Routes / Users 这类重复 CRUD 页面，优先抽共用 query / dialog / toast 模式，而不是复制粘贴

### 11. 前端稳定接口避免长期 `unknown`

- 管理端稳定返回结构不应长期保留 `Record<string, unknown>`、`unknown[]`
- 可复用接口优先补齐明确 DTO 类型

### 12. 文档必须写到正确位置

- 启动/构建/联调方式变更 → `README.md`
- 使用流程变更 → `docs/usage.md`
- 接口语义变更 → `docs/api-reference.md` / `docs/openapi.json`
- 黑盒覆盖事实变更 → `docs/testing/endpoint-blackbox-coverage.md`
- 项目状态或当前计划变更 → `CONTEXT.md` / `CONTEXT-plan.md`

---

## 最小必要验证矩阵

| 改动类型 | 必跑 | 按需补充 |
|------|------|------|
| Java 普通实现改动 | `./mvnw -q -DskipTests compile` | 无 |
| 前端页面/组件改动 | `cd frontend && npm run build` | `cd frontend && npm run lint` |
| Controller / 对外接口改动 | compile + `./scripts/verify.sh` | 按影响范围补专项脚本 |
| 认证 / JWT / API Key 改动 | compile + `./scripts/verify.sh` | `./scripts/user-journey-blackbox.sh` |
| 配置写链 / 热更新改动 | compile + `./scripts/verify.sh` | `./scripts/verify-gaps.sh` |
| PG / Redis / Store 改动 | compile + `./scripts/verify.sh` | `./scripts/regression-backends.sh` / `./scripts/stress-test-backends.sh` |
| 结构重构 / 边界调整 | compile + `./scripts/verify.sh` | `sentrux_check_rules` / `sentrux_health` |

原则：**默认轻量验证，高风险改动触发加严，不做无差别全量测试。**

---

## 维护性验收口径

维护性改动不能只看“代码能跑”，还要至少满足以下 4 类收敛结果：

1. **边界收敛**
   - Controller 依赖数不再上升
   - 新增管理能力优先落到子域 contract，而不是总入口/总接口
2. **职责收敛**
   - 聚合逻辑从 Controller 下沉
   - 持久化、序列化、重试、生命周期不再长期混写于单一核心类
3. **契约收敛**
   - 稳定 admin / internal 接口的 `Map<String, Object>`、`unknown`、`any` 数量下降或不再扩散
4. **验证与文档收敛**
   - 跑过对应最小验证
   - 规则、计划、入口文档同步到正确位置

---

## 近期候选治理方向

> 本节只记录近期仍值得关注的低风险方向，不作为强制阶段计划；当前有效顺序以 `CONTEXT-plan.md` 为准。

| 优先级 | 工作包 | 目标 | 主要产出 | 最小验证 |
|------|------|------|------|------|
| P0 | `web` 包归位与文档入口收口 | 降低导航成本与职责外溢 | 收敛 `web` 包中的非 HTTP 辅助职责；统一 README / CONTEXT / 脚本入口定位 | `./mvnw -q -DskipTests compile` + `./scripts/verify.sh` |
| P1 | 复杂度中心再薄一刀 | 降低单类多原因变更 | 优先审视 `CompletionRecorder` / `DynamicConfigService` / `UserAccountService` 的职责边界 | `./mvnw -q -DskipTests compile` + `./scripts/verify.sh`；必要时模块级 checkstyle |
| P1 | 前端稳定接口继续收敛 | 阻止总 API 文件再次膨胀 | 继续按子域收紧稳定 DTO、控制 `unknown` / `Record<string, unknown>` 扩散 | `cd frontend && npm run build`；必要时 `cd frontend && npm run lint` |
| P2 | 手册与治理模板补强 | 让后续改动按同一规则执行 | 维护本手册中的拆分触发器、例外模板、验收口径；必要时补维护者热点导航 | 文档回读核对即可 |

执行原则：

- 只做最小变更，不借题发挥成大重构
- 优先处理“继续变坏成本最高”的热点
- 先收敛职责边界，再考虑更大范围的结构实验

---

## 不建议优先做的事

- 不继续机械复制更多 feature contract 纵切，把它当成默认优先路线
- 不做全仓库 `unknown` / `Map<String, Object>` 清零运动
- 不把所有改动都升级为全量黑盒、全量压测或全量 E2E
- 不借维护性之名新增模块、引入 MQ / 事件总线或启动大重构

---

## 文档索引与维护分工

| 文档 | 作用 |
|------|------|
| `README.md` | 启动、构建、联调、文档入口 |
| `docs/usage.md` | 流程型使用指南 |
| `docs/api-reference.md` | 稳定接口语义与错误模型 |
| `docs/openapi.json` | 机器契约 |
| `docs/testing/endpoint-blackbox-coverage.md` | 黑盒覆盖事实 |
| `CONTEXT.md` | 项目状态总览 |
| `CONTEXT-plan.md` | 当前阶段计划 |

---

## 例外处理

- 允许例外，但必须说明原因、影响范围和回收条件
- 临时绕过规则时，至少在代码注释、变更说明或 `CONTEXT.md` 中留痕
- 不允许“先这样，后面再说”且没有任何记录

建议记录模板：

```text
[maintainability-exception]
rule: <违反的规则>
file: <文件路径>
reason: <为什么当前不能按规范处理>
impact: <影响范围>
recovery: <计划在哪个工作包回收>
```

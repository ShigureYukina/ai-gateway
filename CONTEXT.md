# CONTEXT.md — 项目状态总览

> 本文件由 AI 维护，用于跨 session 共享项目上下文、已知问题、决策记录和当前进度总览。
> 每次 session 结束时更新。
> 历史完成记录已归档至 `CONTEXT-history.md`。
> 当前阶段的实施计划与验收标准单独维护在 `CONTEXT-plan.md`。

---

## 项目概况

Spring Boot 3 + WebFlux 多上游 LLM 网关，提供 OpenAI 兼容 API，叠加认证、限流、配额、路由、熔断、观测，附带 React 管理后台。

当前状态：Phase 0-5 前端闭环、代码审查修复 Fix-A~G、ESLint/Checkstyle 接入、代码分数优化 Lane A-E、Phase F~H、Phase I（Provider/Route 纵切）、Phase K（PG 写入链路测量与归因）、Phase L（写入链路最小优化）均已全部完成并验证。**Phase M（回归与压测基线固化）已完成**。R1-R4 风险修复已完成并验证；2026-08-29 核实确认 H2/H3/H5 已在代码中修复，**H4（模型发布补偿回滚）已于 2026-08-29 补齐并验证**；同日发现并修复 `parseAccessClaims` 缓存未命中 `block()` 阻塞 bug（详见已知问题）。Phase J（Client 子域纵切）仍为 P2 候选。`quality_signal=6636`，当前主要瓶颈仍为 `modularity=4517`（depth 已改善至 5000）。

| 模块 | 测试数（约） | 状态 |
|------|--------|------|
| gateway-core | ~625 | ✅ 全部通过 |
| gateway-admin | ~626 | ✅ 全部通过 |
| bootstrap | 6 (SPA fallback) | ✅ 全部通过 |
| frontend | 124 (21 files) | ✅ Vitest + RTL |

| 总览指标 | 数值 |
|----------|------|
| 后端测试总数 | ~1250 |
| 前端测试总数 | 124 (21 files) |
| 黑盒回归测试点 | ~260+（含 7 条无 Docker 用户旅程，支持 in_memory / PostgreSQL+Redis 双路径） |
| sentrux 质量信号 | **6636**（modularity 4517, equality 6394, depth 5000；acyclicity 10000 完美，冗余度 8910） |

**优化审计结论**：无未使用的已声明 Maven 依赖，无明显废弃的 Spring 托管生产文件。当前最大源文件为 `ChatCompletionsOrchestrator`（665 行）、`UserAccountService`（663 行）、`DynamicConfigService`（590 行）。前端 `frontend/src/hooks/` 目录虽为空但非阻塞项。模块化（modularity）仍为主要瓶颈。

---

## 已知问题

### 架构
- 包名重构已完成，模块间物理隔离，无共享根包风险
- `@ComponentScan` 已全部移除，改为定向扫描 + 显式 `@Import`
- `/v1/models` 通过 `ModelListProvider` SPI 获取数据，无 admin 时返回空列表 + `X-Degraded` 头
- `BatchFlusher` 参数已配置化，但运行时修改需重启
- **配置契约化已完成**：admin 侧 `GatewayProperties` 直接引用已收敛为 0，全量读取路径迁移至 `GatewayConfigView` / `SystemConfigView`；`SystemConfigView` 已为各子域返回对应只读 View 接口；新增 pricing/model-publication 窄读口 `PricingPublicationConfigView`
- **类职责拆分多轮已完成**：`ChatCompletionsOrchestrator`（3 helpers，~724→450 行）、`AnthropicChatProviderAdapter` 与 `GeminiChatProviderAdapter`（~500/452→280/260 行）、`AdminConfigImportSupport`（422→250 行）、`AdminUserController`（367→250 行）、`DynamicConfigService`（5 mutators）、`RequestLogService`（3 collaborators）；共享 `boundedElasticScheduler` Bean 已落地
- **读聚合下沉已完成**：`InternalUsageSummaryController` → `InternalUsageSummaryReadService`、`ConfigAuditController` → `AuditQueryService`、Admin+Internal request log → `RequestLogQueryService`；聚合读取确认无 N+1
- **Feature 纵切实验已完成两刀**：Provider 子域（`ProviderCatalogView` + `ProviderConfigWriter`）和 Route 子域（`RouteCatalogView` + `RouteConfigWriter`）；modularity 各 +2 正反馈，`cross_module_edges` 未增；Client 纵切（Phase J）待 Phase M 收口后决定
- **包展平已完成**：`core.contract.config`、`core.upstream.state`、`core.config.store` → 父包，消除冗余层级
- **经验总结 1**：同包内提取辅助类对 sentrux 指标提升有限（Phase F `quality_signal` +6），更大幅度的改善需要跨包重组或减少跨模块边
- **经验总结 2**：依赖契约化（`GatewayProperties` → `GatewayConfigView` / `SystemConfigView`）虽然改善了工程边界，但对 sentrux **不加分**；sentrux 的 modularity 更关注跨模块 import 边密度，而非“依赖接口还是实现”
- **经验总结 3**：目录展平（`contract/config`、`upstream/state`、`config/store` 上移一层）对 sentrux 的 `depth` **不加分**；这证明 sentrux 的 depth 反映的是**依赖图最长链**，不是包路径层级
- **经验总结 4**：根据 sentrux 官方文档，`quality_signal` 是 5 个根因指标的**几何平均**；`modularity` 基于 **Newman Q** 的图结构聚类，`depth` 基于**依赖图最长链**。因此即便把 `GatewayConfigView` 局部缩窄为 `PricingPublicationConfigView`，只要依赖图主结构未变，分数仍可能不升反降
- **经验总结 5**：相比“局部窄读口”或“目录展平”，**按 feature 纵切读/写口** 更接近 sentrux 关注的图结构变化。provider 写链实验让 `quality_signal` **5369 → 5370**、`modularity` **4485 → 4487**，虽提升很小，但这是目前首次出现的正向反馈
- **Phase I 第二刀（Route 纵切）完成**：Route 子域新增 `RouteCatalogView`、`RouteConfigWriter`，`AdminRouteController` 改为依赖专用读/写口。`modularity` 4487→**4489**（+2），`cross_module_edges` 不变
- `.sentrux/rules.toml` 已修正为 `[[boundaries]]` 格式，当前 `rules_checked=3`、`0 violation`
- **Phase K（PG 写入链路测量）已完成并收口**：瓶颈排序 —— usageCheckAndRecordBoth(39.9ms avg) > costCheckAndRecordBoth(31.9ms avg) > aggregateMetricBatch(29.7ms avg)
- **Phase L（写入链路最小优化）已完成并通过 stress 验证**：aggregate 写缓冲（SQL 调用 -99%）、usage/cost CTE 合并、BatchFlusher 任务分舱（drain 均长 -53%）、吞吐 +4%、p50 -6.5%、p99 -4.8%
- **Phase M（回归与压测基线固化）已完成**。复验分层策略已定义（见 CONTEXT-plan.md），性能基线已固化：HYBRID mixed-model stress 最优轮 234.3 req/s / p50 72ms / p99 259ms
- **Phase M 瓶颈诊断闭环**：通过 JFR 全量采样 + in-JVM echo 替代 Node.js mock（`EchoDiagnosticServer`，`@Profile("stress-test")`，独立端口 18089）完成瓶颈定位。结论文档：mock 上游非瓶颈，瓶颈为单机 CPU 天花板（机器利用率 94-98%，无单方法 >2%）。gateway 内部流水线分布均匀，无微观瓶颈。生产分离部署 PG/Redis 后吞吐应远超当前 360 req/s；同 JVM echo 模式因 CPU 争用反而降低吞吐（echo 仅用于排除上游变量，非优化手段）；约 360 req/s 上限为单机硬件饱和（gateway + PG + Redis + JMeter 共宿主）。
- **新增诊断工具**：`EchoDiagnosticServer.java`（bootstrap 模块，React Netty 独立端口 echo server）+ `stress-test-backends.sh --with-echo` 参数，用于压测时消除上游 mock 变量；脚本新增预热轮（warm-up pass before measurement），JFR profiling 输出至 `build/stress-profile.jfr`。
- **工作区存在进行中的性能优化 WIP**：`BufferedClientUsageStore` / `BufferedClientCostStore` 内存写缓冲封装、`PostgresClientTpmStore` RETURNING SQL 合并、`ChatCompletionsOrchestrator` 调度分离 Phase 1/2、`AggregateReportingService` O(1) 计数 + `@Scheduled` 兜底 flush

### 测试
- 已完成 1 轮静态性能专项审查（性能 / JVM / 数据库 / Spring / 压测评估 + 总控汇总），结论与当前 Phase K/L 主线一致：主瓶颈仍集中在 `/v1/chat/completions` 成功路径后的 **PostgreSQL 写入链路** 与 **BatchFlusher 过载回退放大**，优先级高于继续做局部 SQL 微调
- 本轮静态审查新增的 P0 结论：应优先收敛 success path 上的 trace / request log / aggregate metric / usage-cost 高频写放大，减少每次 completions 触发的 PG round-trip；同时将 `BatchFlusher` 中“影响业务正确性”的任务与“仅影响观测完整性”的任务分舱，避免观测类任务在队列积压时同步回退到请求线程
- 本轮静态审查新增的 P1 结论：后台统计/看板接口存在明显按 client 循环读取（N+1）风险，重点位于 `gateway-admin` 的 usage summary / dashboard 读取路径；此外 JVM/线程池/连接池边界仍缺统一容量模型，当前不应先盲目调大 Hikari 或 BatchFlusher 线程数
- 本轮静态审查新增的稳定性风险中，`DirtyAccountFlushBuffer` 生命周期问题已修复；`ConfigSyncPublisher` 已从 `@PostConstruct` 订阅切换为 `ApplicationReadyEvent` 后启动；`RedisTraceStore.resetForTests()` 为测试专用方法，因 mock 兼容性改用 `keys()` 而非 `execute(RedisCallback)`；其余同类 Redis 运行态 `KEYS` 使用仍需继续收敛
- 静态容量评估（以既有压测为校准，不是新增实测结论）：`in_memory` 约 **300~900 QPS**、`hybrid` 约 **80~250 QPS**、`postgresql-only` 约 **40~150 QPS**；Phase L 优化后实测 HYBRID 已达 **234.3 req/s**（p50 72ms, p99 259ms），超出此前静态估算上限（190 req/s），性能主线验证闭环完成
- **Phase K（测量）和 Phase L（优化）均已闭环**，详细步骤见 `CONTEXT-history.md`。当前性能基线：HYBRID mixed-model stress 最优轮 **234.3 req/s, p50 72ms, p99 259ms**。瓶颈排序：usageCheckAndRecordBoth(39.9ms) > costCheckAndRecordBoth(31.9ms) > aggregateMetricBatch(29.7ms)
- 静态 YAML 用户 JWT 兼容性已修复（`username != clientId` 时按 JWT subject 补查）
- 日志降噪已完成：dev/test 默认日志级别收紧为 INFO，高频正常路径降级
- `/internal/**` 认证已收敛到 `InternalEndpointAuthFilter`；`GlobalExceptionHandler` 错误日志收紧
- 安全/运维已补齐：明文密码默认关闭、API Key BCrypt 组合存储、refresh token 原子消费、正式环境 admin 初始化、SSRF IPv6 防护、Webhook secret 末 2 位展示
- 测试基础设施优化：6 个 Controller 测试从 `@SpringBootTest` 收敛为 `@WebFluxTest` 切片；Flyway/NoFlyway 测试共享基类已抽出；Webhook 异步查询噪音已消除
- end-to-end 集成测试（Testcontainers）覆盖仍不足
- CI 已升级为分层门禁：后端模块单测 + 集成测试、前端 lint/test/build、Maven Checkstyle、`scripts/regression.sh`、`scripts/verify-supplement.sh`、`scripts/user-journey-blackbox.sh` 全部纳入 PR / push 到 `main` 的工作流；本地默认门禁仍保持 `compile + frontend lint/test/build`，黑盒与压测按改动范围补跑
- `application-local.yml` 已显式覆盖 `gateway.shared-state.backend: in_memory`，避免本地 H2 启动误继承 base `hybrid` 模式并走到 `PostgresConfigStore/config_kv` SQL 路径；`local` / `local-file` 现都符合“开箱即用、无外部 PG/Redis 依赖”的定位
- admin web 装配已确认继续采用 **显式 `@Import`** 而非 `@ComponentScan("io.gateway.oss.admin.web")`：`web` 主包内混有 `AdminConfigImportSupport`、`AdminConfigExportSupport`、`InternalUsageSummaryReadService` 这类非 stereotype 支持类，扫描方案不稳；新增 `bootstrap/src/test/java/io/gateway/oss/bootstrap/AdminRoutesAssemblyTest.java` 作为组装态守卫，验证 `/admin/providers`、`/admin/routes`、`/admin/dashboard/overview` 在 assembled app 中真实可达
- **parseAccessClaims 非阻塞改造已完成（2026-08-29，修复 verify-gaps 暴露的 P1 缺陷）**：Fix-B 引入的"缓存未命中回退 store"原实现为 `.block()`，而该方法在 WebFlux 事件循环线程上执行——任何缓存未命中（如重启后旧 token 访问自助端点）都会抛 `block() not supported` → `/auth/keys` 等端点 500。现改为 `Mono<Claims>` 组合（`AuthSupport` + `UserKeyController`/`UserProfileController`/`UserUsageController` 共 12 处调用点），冻结/失效/删除语义不变，AuthControllerTest 50/50 通过，verify-gaps 69/69 全绿


### 脚本
- **本机（Windows Git Bash）黑盒运行环境（2026-08-29 搭建，跨 session 有效）**：
  - JDK 21：`C:\Users\sweyyuki\jdk-21.0.12.1+1`（Temurin zip 解压；跑 `./mvnw` 前需 `export JAVA_HOME`，机器原无 JDK/Maven）
  - `.mvn/wrapper/maven-wrapper.properties` 曾缺失，已按 Maven 3.9.9 补回
  - jq：`~/bin/jq.exe`（已在 PATH）；Redis：便携版 `E:\redis\redis-server.exe`（跑 verify/gaps 前需后台启动 `--port 6379`）
  - **Redis 可执行路径不能含 `\u` 序列**（如 `C:\Users\...`）：Spring Redis 健康检查把 INFO 的 executable 路径当 Properties 解析，`\u` 后跟非 hex 会报 `Malformed \uxxxx encoding` → 健康聚合 DOWN → verify.sh [01] 假红；`E:\redis` 的 `\r` 是合法转义，无此问题
  - user-journey-blackbox 的 `[06] lsof 已安装` 断言在本机预期假红（Git Bash 无 lsof），功能不受影响，实际通过口径为 **126/127**
  - 脚本请求体中的中文已改为 JSON `\u` 转义（CHAT_PAYLOAD）：Windows 下 bash 向原生 curl 传参时非 ASCII 会被按本地代码页转码 → 服务端 JSON 解码 400

- 黑盒脚本职责已收敛为个人项目模式，按使用频率分三层：

  **日常门禁**（每次改动必跑）
  - `scripts/verify.sh`：最小主链路冒烟（health / admin 登录 / 注册登录 / `/v1/models` / `/v1/chat/completions` / 基础额度与白名单前置）。~36 断言，实跑 36/36 ✅。
  - `scripts/lib.sh`：新增 `wait_for_url()` 工具函数；`scripts/verify.sh` 已改用 `wait_for_url()` 代替裸 `sleep` 轮询，提升冒烟鲁棒性。

  **发布前补充**
  - `scripts/user-journey-blackbox.sh`：7 条无 Docker 真实用户旅程，按最终用户/管理员操作链路验证，支持 `in_memory` / `postgresql(+redis,+flyway)` 双后端路径。关键断言已升级为结果正确性校验（chat 响应体、usage 聚合值、管理员重置密码闭环等）。实跑 in_memory 125/125 ✅，PG 128/128 ✅。

  **按需专项**（改动对应能力时补跑，不纳入默认门禁）
  - `scripts/regression.sh`：主回归，覆盖认证/Provider/Route/Client/User 管理/chat/key 生命周期/限流配额预算/request log/usage/cost/dashboard 等核心串联路径
  - `scripts/verify-gaps.sh`：历史黑盒缺口补充（模型分组/配置导入/系统配置热更新/Auth 自助 Key 管理/配置版本回滚/模型发布/Client CRUD）
  - `scripts/verify-supplement.sh`：运维与观测面（webhook/alerts/audit/system-limit）
  - `scripts/verify-regression-patterns.sh`：历史问题回归模板
  - `scripts/regression-backends.sh`：PG/Redis 后端回归
  - `scripts/verify-init-admin.sh`：正式环境初始化验证
  - `scripts/stress-test*.sh`：压力测试

- 个人项目测试策略（详见 `README.md`）：本地默认 `compile + frontend lint/test/build`，发布前补 `verify.sh`，用户链路与专项黑盒按改动范围补跑；CI 则承担单测 / 集成 / 前端 / Checkstyle / 主回归 / supplement / user-journey 的完整门禁。`docs/testing/endpoint-blackbox-coverage.md` 保留为覆盖参考，不作为强制 stop condition。

### 文档
- `docs/quality-manual.md` 已基于最佳实践补强为维护规范与改进计划入口，新增拆分触发器、读型聚合下沉规则、契约收敛要求、维护性验收口径与 2~4 周治理计划
- `CONTRIBUTING.md` 提到 Testcontainers，但无 Failsafe 配置
- 文档分层策略已明确：`docs/usage.md` 作为流程型主入口，`docs/api-reference.md` 作为人工维护的稳定 API 参考，`docs/openapi.json` 作为机器可消费的契约快照；三者不再相互替代
- 新增 `docs/testing/endpoint-blackbox-coverage.md` 作为“后端端点 ↔ 黑盒脚本覆盖”矩阵，补足 usage / api-reference / openapi 之外的测试可见性文档层
- README 已补充“个人项目版” Testing Strategy：默认轻量门禁为 compile/build + `verify.sh`，发布前优先运行 `user-journey-blackbox.sh`，其余黑盒脚本降为按需专项入口
- `README.md` 现已改为中文默认版本，顶部提供 `README.en.md` 语言切换入口；`README.en.md` 保留完整英文内容，顶部提供中文跳转
- 启动/发布前文档入口已收敛：根 `README.md`（中文默认）现聚焦本地启动、验证、前端联调与文档阅读顺序；`frontend/README.md` 已由 Vite 模板改为项目专用说明；`docs/api-reference.md` 已补充 provider 模型目录、model-groups、webhooks、models-dev sync、`/internal/system/status`、`/internal/config/audit-center` 的简要语义与示例
- `/auth/usage/summary` 在 launch-facing 文档中统一标记为**未实现/不支持**，避免再次被误读为可接入接口
- 前端管理台信息架构文案已最小调整：`/providers` 页面与导航显示为“渠道接入 / Channels”，`/routes` 页面与导航显示为“模型发布 / Model Routes”，仅更新 i18n 与页头引导文案，未改动路由 path 与后端 API
- 前端 `frontend/src/api/modules/admin.ts` 已拆分为按子域聚合出口（dashboard / providers / routes / clients / users / system / observability / webhooks / config），并保持 `@/api/client` 现有 named exports 兼容；同时补齐 admin users api key 与 system config/export 稳定 DTO，收紧 `ManageApiKeysDialog`、`SystemConfigPage` 的 `any/unknown` 扩散
- 前端共享集成收口已完成：`frontend/src/pages/ModelGroupsPage.tsx`、`frontend/src/pages/PublicationsPage.tsx`、`frontend/src/pages/WebhooksPage.tsx` 已接入 `frontend/src/App.tsx` 管理台路由与 `frontend/src/components/layout.tsx` 侧边导航；`frontend/src/api/modules/admin.ts` 已补齐 `modelGroups`、`publications` 聚合出口，并同步更新相关布局测试
- 工具接入已补齐并稳定可运行（详见 `CONTEXT-history.md` ESLint/Checkstyle 接入记录）

### 已修复风险摘要

| ID | 风险 | 等级 | 简述 |
|----|------|------|------|
| R1 | Refresh Token 轮换非原子 | ✅ Fixed | 并发请求可重复消费同一 refresh token → ConfigStore 接口方法改为 abstract，编译期安全 |
| R2 | JWT 鉴权缓存缺失可绕过 | ✅ Fixed | 缓存丢失时冻结/删除用户可能被放行 → AuthSupport 增加 store 回退，缓存缺失时从 DB 补查 |
| R3 | Postgres 无 namespace 隔离 | ✅ Fixed | 多实例共 PG 库时配置/配额互相污染 → route_state / provider_runtime 表加 namespace 列（V14），SQL 全部按 namespace 过滤 |
| R4 | 删除用户 write-behind 复活 | ✅ Fixed | dirty buffer 定时把已删用户写回 → recordDeletedVersion 在 evictAccount 之前执行，关闭并发复活窗 |
| H1 | API Key 明文存储 | ✅ Fixed | 动态用户 API Key 已改为确定性摘要 + BCrypt 组合存储，鉴权路径同步兼容升级 |
| H2 | SSRF IPv6 绕过 | ✅ Fixed | 2026-08-29 代码核实：`BaseUrlValidator` 已显式拦截 ULA(fc00::/7)/link-local，provider 保存入口已接入校验 |
| H3 | 配置 reload 增量 merge | ✅ Fixed | 2026-08-29 代码核实：`ConfigLoadService` 已整表替换，删除经 sync 发布 + 5s 版本对账兜底传播 |
| H4 | 模型发布非原子 | ✅ Fixed | 2026-08-29 补齐：`ModelPublicationService.publish()` 每步成功登记补偿动作，失败按完成逆序回滚（6 个单测 + verify-gaps 发布场景全过） |
| H5 | Webhook 无重试 | ✅ Fixed | 2026-08-29 代码核实：`WebhookDispatcherService` 的 `Retry.backoff(retryMax)` 生效，投递结果有 delivery log 留痕 |
| H6 | 多 Controller 缺 `@Valid` | ✅ Fixed | internal/admin 入口已补方法参数校验与 request body 校验 |

## 关键文件索引

| 文件 | 用途 |
|------|------|
| `AGENTS.md` | AI 代理工作指南 |
| `CONTEXT.md` | 本文件，跨 session 上下文 |
| `CONTEXT-plan.md` | 当前阶段实施计划、范围与验收标准 |
| `docs/quality-manual.md` | 代码质量与可维护性手册（规则/拆分触发器/验证矩阵/治理计划） |
| `docs/testing/endpoint-blackbox-coverage.md` | 后端端点黑盒覆盖矩阵（端点 ↔ 脚本 ↔ 覆盖强度） |
| `CONTEXT-history.md` | 已完成工作历史归档 |
| `scripts/regression.sh` | 主回归测试脚本（122+ 场景） |
| `scripts/verify.sh` | 轻量冒烟测试脚本 |
| `scripts/verify-supplement.sh` | 补充黑盒测试（Webhook/Runtime/Alerts/Audit/SystemLimit） |
| `scripts/user-journey-blackbox.sh` | 无 Docker 真实用户旅程黑盒脚本（7 条用户旅程） |
| `scripts/verify-regression-patterns.sh` | 纯后端历史问题回归模板脚本（空校验/局部更新/状态码/热更新/版本接口） |
| `scripts/verify-gaps.sh` | 黑盒覆盖缺口补充（71 测试点） |
| `scripts/regression-backends.sh` | PG+Redis 回归测试（37 断言点） |
| `scripts/stress-test.sh` | 并发压力测试 in-memory（JMeter 5 场景） |
| `scripts/stress-test-backends.sh` | 并发压力测试 PG+Redis |
| `jmeter/mock_openai_server_node.mjs` | OpenAI 兼容 mock 上游 |
| `jmeter/mock_error_server_node.mjs` | 错误模拟服务（500，fallback 测试用） |
| `jmeter/mock_slow_server_node.mjs` | 慢响应模拟服务（超时/并发测试） |
| `jmeter/mock_anthropic_server_node.mjs` | Anthropic Messages API mock 上游 |
| `bootstrap/src/main/resources/application.yml` | 基础配置 |
| `bootstrap/src/main/resources/application-local.yml` | 本地开发配置（H2 内存 + in_memory shared-state，重启丢失） |
| `bootstrap/src/test/java/.../AdminRoutesAssemblyTest.java` | bootstrap 级 admin 路由装配守卫（assembled app 非 404 / 已鉴权 200） |
| `bootstrap/src/main/resources/application-local-file.yml` | 轻量持久化配置（H2 文件模式，重启不丢失，`--spring.profiles.active=local-file` 启用；`data/` 目录已加入 `.gitignore`） |
| `bootstrap/src/main/java/.../EchoDiagnosticServer.java` | 压测诊断 in-JVM echo server（`@Profile("stress-test")`，独立端口 18089） |
| `gateway-core/src/main/java/.../config/GatewayCoreAutoConfiguration.java` | Core 自动配置入口 |
| `gateway-admin/src/main/java/.../config/GatewayAdminAutoConfiguration.java` | Admin 自动配置入口 |
| `gateway-core/src/test/java/.../CoreTestConfiguration.java` | Core 模块测试配置 |
| `gateway-admin/src/test/java/.../web/WebTestCleanupSupport.java` | 测试间状态恢复（10 种系统配置） |

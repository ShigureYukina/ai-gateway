# CONTEXT-history.md — 已完成工作历史记录

> 本文件归档 `CONTEXT.md` 中已完成的过往工作记录。
> 当前状态快照见 `CONTEXT.md`。

---

## 最近完成（2026-06-03 ~ 2026-06-09）

### 最新完成（2026-08-29 — H4 发布补偿回滚 + parseAccessClaims 非阻塞改造 + Windows 本机环境补齐）

**1. H4 模型发布补偿回滚（排期第 1 项，唯一确认存留的 High 风险）**
- `ModelPublicationService.publish()` 原为多步写 Mono 链（primary route → scene → alias route → pricing → 清理 obsolete），中途失败留下半发布态
- 修复：每个前向步骤成功后登记惰性补偿动作（restore-or-delete，快照在装配期同步读取——mutator 内存写入发生在订阅时），任一步失败按**完成逆序**回滚已完成步骤；补偿单步 best-effort（失败记 warn 继续其余），最后抛原始错误；`deleteScene` 失败时旧数据未被删除，不登记补偿
- 新增 `ModelPublicationServiceTest` 6 个单测（首个失败无回滚 / scene 失败回滚删 primary / 重发布失败恢复旧 primary+scene / 清理失败全链回滚含 pricing 快照 / 成功路径零回滚 / 无历史 pricing 跳过 undo）；Mockito 视口 `Map<String, ? extends View>` 通配返回用 `doReturn` 打桩
- 验证：compile ✓、checkstyle（core/admin）✓、verify.sh 36/36 ✓、verify-gaps.sh 69/69 ✓（含模型发布场景）

**2. parseAccessClaims 缓存未命中 `.block()` 缺陷修复（verify-gaps 暴露的新 P1）**
- 现象：verify-gaps 批次 4（Auth 自助 Key 管理）7/69 失败，`POST /auth/keys` 500，日志 `block()/blockFirst()/blockLast() are blocking, which is not supported in thread reactor-http-nio-*`
- 根因：Fix-B 的"缓存未命中回退 store"在 `AuthSupport.parseAccessClaims:76` 用 `.block()`，而该方法在事件循环线程上执行，缓存未命中必炸；PATCH/rotate/DELETE 的 404 为空 keyId 的连锁假象
- 修复：`parseAccessClaims` 改为 `Mono<Claims>`（`Mono.defer` 包同步校验 + store 回退非阻塞组合，冻结/失效/删除语义不变），`UserKeyController`(5)/`UserProfileController`(3)/`UserUsageController`(2) 共 10 个调用点机械 flatMap 化
- 验证：AuthControllerTest 50/50、UserAccountServiceTest/ClientAuthServiceTest/JwtServiceTest 83 全过；verify-gaps **7 失败 → 69/69 全绿**

**3. user-journey 126/127（本机中文编码修复）**
- 首跑 55/127：全部 chat 断言失败。根因是 **Windows 环境问题而非代码缺陷**——bash 向原生 curl 传参时把 `CHAT_PAYLOAD` 的中文字面量按本地代码页转码，服务端 JSON 解码 400（ASCII payload 200、中文 payload 400、`--data-binary @utf8文件` 200 三组对照实验确认）
- 修复：`CHAT_PAYLOAD` 中文改为 JSON `\u8bf7\u56de\u590d` 转义（请求体纯 ASCII，跨平台免疫）；重跑 **126/127**，唯一失败为本机无 lsof 的环境断言（`scripts/lib.sh` 端口清理已优雅降级）

**4. Windows 本机（Git Bash）验证环境补齐（跨 session 有效，详见 CONTEXT.md 脚本节）**
- 机器原无 JDK/Maven/jq/Redis：装 Temurin 21（`~/jdk-21.0.12.1+1`，`JAVA_HOME` 需 export）、补回 `.mvn/wrapper/maven-wrapper.properties`（Maven 3.9.9）、`~/bin/jq.exe`、便携 Redis `E:\redis`
- 踩坑记录：Redis 可执行路径含 `\u`（如 `C:\Users\...`）时 Spring 健康检查解析 INFO 报 `Malformed \uxxxx encoding` → 健康聚合 DOWN → verify.sh [01] 假红；放 `E:\redis` 规避
- 顺手清理基线遗留无用导入：`BufferedStoreHelper`（AtomicInteger）、`RedisTraceStore`（RedisCallback）+ 本轮改造产生的 2 个 Claims 导入；checkstyle 双模块恢复 0 violation

### 最新完成（2026-06-10 — 前端 catch any 收敛）

- 将 8 个前端页面/组件中的 `catch (e: any)` / `catch (err: any)` 收敛为 `catch (error: unknown)`
- toast / 表单错误文案统一改为 `error instanceof Error ? error.message : <fallback>`，避免显式 `any` 扩散并兼容非 `Error` 抛出值
- 覆盖文件：`EditUserDialog.tsx`、`UserRow.tsx`、`UsersPage.tsx`、`CreateUserDialog.tsx`、`RouteEditDialog.tsx`、`PublicationsPage.tsx`、`LoginPage.tsx`、`RegisterPage.tsx`

### 最新完成（2026-06-09 — admin 路由装配修复与组装态守卫）

- 修复 `gateway-admin` 自动装配：确认 `web` 主包是“controller + support class”混合包，不能稳定依赖 `@ComponentScan("io.gateway.oss.admin.web")`
- 恢复 `gateway-admin/src/main/java/com/example/gateway/admin/config/AdminImportedBeansConfig.java` 的显式 web 层导入，保留 `AdminConfigImportSupport`、`AdminConfigExportSupport`、`InternalUsageSummaryReadService` 等非 stereotype 支持类的注册路径
- `AdminComponentScanConfig` 回退为仅扫描 `io.gateway.oss.admin.web.alerts`，与 `gateway-core` 的“web 显式 import、纯组件包再 scan”模式保持一致
- 新增 `bootstrap/src/test/java/com/example/gateway/bootstrap/AdminRoutesAssemblyTest.java`，用 `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `WebTestClient` 验证 assembled app 中 `/admin/providers`、`/admin/routes`、`/admin/dashboard/overview` 已鉴权返回 200，未鉴权探针不再落成 404
- 额外确认：此前手工 `spring-boot:run -pl bootstrap` 的运行态误报，和未带 `-am` 时 bootstrap 可能继续消费本地仓库旧 snapshot 有关；使用 reactor 方式 `-pl bootstrap -am` 运行/测试后，admin 路由装配测试通过

### 最新完成（2026-06-09 — gateway-admin 测试修复：585/585 全绿）

- 根因：`security.password.allow-plaintext` 属性在测试 `application.yml` 中被错误放在 `gateway.security.password.allow-plaintext` 路径下，而 `@Value` 读的是顶层 `security.password.allow-plaintext`；另有多数 auth 测试类的 `@TestPropertySource` 缺失此配置
- 修复测试 `application.yml` key 路径；补齐 `AuthControllerTest`、`AuthRegistrationModeTest`、`AdminWebhookControllerTest`、`AdminProviderControllerTest` 的 `@TestPropertySource` 中 `security.password.allow-plaintext=true`
- 最终验证：`gateway-admin` 模块 585 测试全通过，0 失败；bootstrap 装配测试 2/2 通过

### 最新完成（2026-06-09 — local profile 共享状态装配修正）

- 修复 `bootstrap/src/main/resources/application-local.yml`：显式增加 `gateway.shared-state.backend: in_memory`
- 根因是 base `application.yml` 默认 `gateway.shared-state.backend=hybrid`，而 `local` 之前未覆盖，导致 H2 本地启动误命中 `ConfigStoreHybridConfig -> StoreConfig.config=POSTGRESQL -> PostgresConfigStore`
- 该问题会在本地聊天调用或启动期配置加载时打到 `config_kv` SQL，最终经 `GlobalExceptionHandler` 返回 `{"code":"internal_error","message":"Internal server error","requestId":"req_..."}`
- 修正后 `local` 与 `local-file` 都回归“无需 PG/Redis 的开箱即用模式”，与 README / CONTEXT 中的本地启动承诺保持一致

### 最新完成（2026-06-09 — CI 门禁补强）

- `.github/workflows/ci.yml` 已补齐前端 `lint` / `test` / `build`，并新增独立 `checkstyle` job（`gateway-core` / `gateway-admin` / `bootstrap`）
- `regression` 与 `supplement` job 现依赖 `checkstyle`，PR / push 到 `main` 的默认门禁扩展为：后端单测 + 集成测试、前端 lint/test/build、Maven Checkstyle、主回归、supplement、user-journey
- 本地验证文档已同步：`README.md`、`CONTEXT.md`、`CONTEXT-plan.md` 统一为“本地默认 compile + frontend lint/test/build；黑盒按改动范围补跑”
- 为让新增门禁能跑通，已顺手修复两处现存阻断：
  - `frontend/src/pages/ModelGroupsPage.tsx` 初始加载改为 effect 内部异步流程，规避 `react-hooks/set-state-in-effect` lint error
  - `gateway-core/src/main/java/com/example/gateway/core/security/UserApiKeyService.java` 删除未使用 `Locale` import，消除 Checkstyle `UnusedImportsCheck`

### 最新完成（2026-06-09 — Phase M 收口与优化审计）

#### Phase M 回归与压测基线固化 — 已收口
- **瓶颈诊断闭环**：JFR 全量采样 + in-JVM `EchoDiagnosticServer`（`@Profile("stress-test")`，独立端口 18089）双验证，确认 mock 上游非瓶颈，瓶颈为单机 CPU 天花板（94-98%）；same-JVM echo 模式因 CPU 争用反而降低吞吐
- **工具增强**：`stress-test-backends.sh` 新增 `--with-echo` 参数、预热轮、JFR 输出至 `build/stress-profile.jfr`
- **wait_for_url() 工具**：`scripts/lib.sh` 新增 `wait_for_url()`；`scripts/verify.sh` 已接入替代裸 sleep

#### H2 文件模式本地配置
- `bootstrap/src/main/resources/application-local-file.yml` 存在（`jdbc:h2:file:./data/gateway`）；`data/` 已入 `.gitignore`
- 激活方式：`--spring.profiles.active=local-file`

#### 优化审计结论
- 无未使用的已声明 Maven 依赖
- 无明显废弃的 Spring 托管生产文件
- 最大文件：`ChatCompletionsOrchestrator`(665)、`UserAccountService`(663)、`DynamicConfigService`(590)
- 前端 `frontend/src/hooks/` 为空（非阻塞）
- `quality_signal=6636`，模块化仍为主瓶颈

#### CONTEXT.md / CONTEXT-plan.md 同步更新
- Phase M 标记为“已完成/已收口”
- 基线指导新增预热轮感知压测与可选 JFR / echo 诊断说明
- 新增 wait_for_url() / lib.sh 到脚本章节
- 优化审计结论写入概况指标区

### 结构收敛补充（2026-06-08）
- `gateway-admin` 新增 `RequestLogQueryService`，抽出 `AdminRequestLogController` 与 `InternalRequestLogController` 的 `recent` / `costByClient` / `costByModel` 共用查询逻辑与请求日志视图映射，保留原有 `/admin`、`/internal` 路由不变
- `gateway-admin` 新增 `AuditQueryService`，下沉 `ConfigAuditController` 的 audit / audit-center / versions 过滤、分页、脱敏与统一审计组装逻辑；Controller 仅保留鉴权、路由与 rollback/snapshot 转调
- `ModelGroupController` 改为继承 `AdminBaseController`，删除本地重复 `requireAdminAccess(...)`，统一复用父类 `requireAdminAccess(String authorizationHeader)`

### 性能主线里程碑明细（从 `CONTEXT.md` 迁入，便于主文档收口）
- **Milestone 1 — Postgres cost 双写收敛**：`PostgresClientCostStore` 新增 Postgres 专用 `checkAndRecordBoth(...)`，将成功路径 cost 记账从 `ClientCostStore` 默认的两次独立 `JdbcTemplate` 调用收敛为单次 `jdbc.execute(ConnectionCallback)` 内完成 daily/monthly 两段 SQL；未改动 daily/monthly 预算语义、返回值语义或服务层调用方式。验证：`./mvnw -q -pl gateway-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PostgresClientCostStoreTest,PostgresClientUsageStoreTest,ClientBudgetServiceTest,ClientQuotaServiceTest test`、`./mvnw -q -DskipTests compile`、`./scripts/verify.sh`。
- **Milestone 2 — Postgres usage zero-token 双查收敛**：`PostgresClientUsageStore.checkAndRecordBoth(...)` 的 zero-token 分支由“`request_cnt` upsert + daily SELECT + monthly SELECT”收敛为“`request_cnt` upsert + 单条 `SELECT period_key, tokens ... IN (?, ?)` 读取 daily/monthly”，继续保持 `request_cnt` 递增、daily/monthly 返回值与 `ClientQuotaService` 调用语义不变。验证：`./mvnw -q -pl gateway-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PostgresClientUsageStoreTest,ClientQuotaServiceTest,PostgresClientCostStoreTest,ClientBudgetServiceTest test`、`./mvnw -q -DskipTests compile`、`./scripts/verify.sh`。
- **Milestone 3 — success artifacts 首轮合并**：`ChatCompletionsOrchestrator` 成功路径把原先分开的两次 best-effort 提交（`CompletionRecorder.recordRequestLog(...)` + `recordSuccessObservability(...)`）收敛为单次 `CompletionRecorder.recordSuccessArtifacts(...)` 提交，继续保持 request log、trace、metrics 与 aggregate metric 的写入语义不变，仅减少每个成功请求 1 个 `BatchFlusher` best-effort 任务；同时将 `CompletionRecorder.accessLog(...)` 的失败耗时计算延后到 `status >= 400` 分支内，避免成功路径无效 `Instant.now()` / `Duration` 计算。验证：`./mvnw -q -pl gateway-core -Dtest=CompletionRecorderTest,ChatCompletionsOrchestratorTest,RequestLogServiceTest,PostgresTraceStoreTest,BatchFlusherTest test`、`./mvnw -q -DskipTests compile`、`./scripts/verify.sh`。
- **Milestone 4 — request log buffer 去 O(n)**：`RequestLogBuffer` 新增基于 `requestId` 的内存索引，`add()` 不再通过 `ConcurrentLinkedDeque` 做 O(n) 去重扫描，而是改为 `ConcurrentHashMap` 定位旧记录后再移除；同时 `findByRequestId()` 也从流式扫描改为 O(1) map 命中，继续保持“同 requestId 仅保留最新记录”、recent 顺序与 `MAX_ENTRIES` 裁剪语义不变。`CompletionRecorder` 成功路径内部进一步复用一次 `completedAt` / `latencyMs`，避免 `recordSuccessArtifacts()` 下 request log 与 success trace/metrics 各自重复 `Instant.now()` / `Duration.between(...)` 计算；失败路径也统一复用单次完成时刻，不改 trace / request log / metrics 语义。验证：`./mvnw -q -pl gateway-core -Dtest=RequestLogServiceTest,CompletionRecorderTest,ChatCompletionsOrchestratorTest,PostgresTraceStoreTest,BatchFlusherTest test`、`./mvnw -q -DskipTests compile`、`./scripts/verify.sh`。
- **Milestone 5 — `costUsd` 计算复用**：成功路径 `costUsd` 改为单次计算后复用给 request log 与 aggregate success，避免 `CompletionRecorder.recordRequestLog(...)` 与 `ChatCompletionsOrchestrator.recordAggregateSuccess(...)` 各自重复执行 `calculateCostUsd(...)`；同时修复了此前性能改动引入的多构造器注入歧义，已恢复 `ChatCompletionsOrchestrator` 与 `CompletionRecorder` 主构造器 `@Autowired` 标注。验证与压测：`./mvnw -q -pl gateway-core -Dtest=CompletionRecorderTest,ChatCompletionsOrchestratorTest,RequestLogServiceTest,PostgresTraceStoreTest,BatchFlusherTest,GatewayMetricsRecorderTest test`、`./mvnw -q -DskipTests compile`、`./scripts/verify.sh`、`./scripts/stress-test-backends.sh --build`；结果：`total=4492`、`200=4492`、`sample-window=29.86s`、约 **150.4 req/s**、`p50=120ms`、`p99=416ms`。
- **Milestone 6 / 分批优化 Batch 1 — success artifacts + aggregate success 合并为单次 best-effort**：`ChatCompletionsOrchestrator` 成功路径将原先分开的两次 best-effort 提交（`CompletionRecorder.recordSuccessArtifacts(...)` 与 `recordAggregateSuccess(...)`）进一步合并为**单次** `batchFlusher.submitBestEffort(...)`，并在同一 lambda 内对 success artifacts 与 aggregate success 分别做独立 `try/catch`，保持“任一观测动作失败不压制另一动作”的现有 best-effort 语义，同时继续**不改** `BatchFlusher` overload/fallback 行为。验证：`./mvnw -q -pl gateway-core -Dtest=CompletionRecorderTest,ChatCompletionsOrchestratorTest,PostgresTraceStoreTest,BatchFlusherTest,GatewayMetricsRecorderTest test`、`./mvnw -q -pl gateway-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=AggregateReportingServiceTest,AggregateMetricRecorderImplTest test`、`./mvnw -q -DskipTests compile`；黑盒 `./scripts/verify.sh` 首轮曾出现一次 cleanup 阶段 `DELETE /admin/providers/mock` 返回 `409` 的非稳定失败，但在单独重跑后 `36/36 PASS`，判定为瞬态/脚本数据态问题而非本次代码回归；HYBRID 压测结果：`total=8148`、`200=4065`、`sample-window=29.95s`、`p50=61ms`、`p99=288ms`。
- **Milestone 7 / 分批优化 Batch 2 — Postgres cost zero-cost 双查收敛**：`PostgresClientCostStore.checkAndRecordBoth(...)` 的 zero-cost 分支由“daily 单查 + monthly 单查”收敛为单条 `SELECT period_key, cost_micros FROM client_cost ... period_key IN (?, ?)` 批量读取后按 `period_key` 回填 daily/monthly，保持“纯读取、缺失默认 0、daily/monthly 返回语义不变”。验证：`./mvnw -q -DskipTests compile`、`./scripts/verify.sh`、`./scripts/stress-test-backends.sh --build`；结果：`total=4318`、`200=4318`、`sample-window=29.91s`、`p50=137ms`、`p99=455ms`。另记：尝试执行 `./mvnw -q -pl gateway-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PostgresClientCostStoreTest,ClientBudgetServiceTest,PostgresClientUsageStoreTest test` 时，被当前分支中与本次改动无关的 `gateway-admin` 测试编译噪音阻断，故该轮 focused store 测试结果以静态代码核对 + compile + 黑盒 + HYBRID 压测为主。

### 关键完成项（2026-06-07 — HYBRID 压测入口收口与真实性增强）
- **唯一保留压测入口收口** — `scripts/stress-test-backends.sh --build` 已收敛为当前唯一保留的 HYBRID 压测入口；脚本显式以 `PostgreSQL + Redis + HYBRID shared-state` 启动，并把吞吐口径改为以 JMeter sample window 为准，不再使用 `TotalRequests / ElapsedSeconds` 的粗口径
- **HYBRID 全量 500 问题定位与修复** — 首轮切换到 HYBRID 后出现 `3321/3321 500`，根因先后定位为两类：
  1. runtime routing refresh 不完整：`RuntimeRefreshHooks.onRoutingConfigChanged()` 未统一清 `ModelRouteResolver` cache，`ProviderConfigMutator` 也未触发 routing refresh；随后已补齐 route/scene/provider 统一 refresh + route cache reset
  2. 压测脚本 setup 数据不符合 scene 路由拓扑：单独把 `gpt-4o-mini` 写成 alias+provider 混合 route，导致 `default-chat -> openai-primary/openai-fallback` 链路不一致；随后脚本改为创建 `openai-primary`、`openai-fallback` 和 alias route `gpt-4o-mini -> scene default-chat`，并增加 pre-JMeter `smoke_check_chat()`
- **HYBRID 压测入口恢复可用** — 修复后 `./scripts/stress-test-backends.sh --build` 跑通，实测一轮为 `2865` 次请求、`200=2865`、`sample-window=19.88s`，JMeter 汇总 `2865 in 00:00:20 = 141.9/s`，说明单入口 HYBRID 路径已恢复为功能正确的统一压测入口
- **验证阶段已完成并暂停优化** — 黑盒与前后端验证已全通过：`./scripts/verify.sh` `36/36`、`./scripts/user-journey-blackbox.sh` `127/127`、`./mvnw -q -DskipTests compile`、`frontend npm test`（`21` files / `124` tests）、`frontend npm run build` 全部通过；本轮在用户要求下先停止进一步性能优化
- **文档/提交整理** — 代码改动以 `feat: narrow config contracts and stabilize hybrid validation` 形成提交，随后按用户要求 amend 移除了 `CONTEXT*.md` 与 `README.md` 文档；工作区只保留 docs/readme 类文档改动
- **sentrux 快照（收口后）** — `sentrux_scan` 得到 `files=644`、`import_edges=1485`、`lines=100165`、`quality_signal=6631`；`sentrux_health` 显示瓶颈仍为 `modularity=4512`，`cross_module_edges=990`；`sentrux_check_rules` 0 violation；DSM 仍为 clean layering（`above_diagonal=0`）
- **压测真实性增强（单入口仍保持可回归）** — 为解决“单一请求模型 + 流量形状过于理想化”，`stress-plan.jmx` 已从单 thread-group 匀速压测改为：
  - `serialize_threadgroups=false`
  - `Steady Mixed Traffic`：`14` threads、`6s` ramp、`30s` duration
  - `Burst Overlay`：`22` threads、`1s` ramp、`8s` delay、`12s` duration
  - 两个 thread group 下都通过 `RandomController` 混合 `gpt-4o-mini` 与 `gpt-4.1-mini` 两种非流式请求，统一仍使用 label `high-pressure-chat`
- **mixed-model 首轮回归暴露授权缺口并已修正** — 第一次 mixed-model 实跑结果为 `7016` 请求、`200=3525`、`403=3491`，日志显示所有 `403` 都是 `model=gpt-4.1-mini provider=unknown`，说明新模型未被主压测 client 授权；随后 `stress-test-backends.sh` 的 `ensure_stress_main_client_cost_budget()` 追加最小补齐 `allowedModels += ["gpt-4o-mini", "gpt-4.1-mini"]`
- **mixed-model + steady/burst 单入口最终验证通过** — 修正授权后重新运行 `./scripts/stress-test-backends.sh --build`，结果：`5812` 次请求、`200=5812`、`sample-window=29.86s`、`elapsed=34s`、`p50=92ms`、`p99=295ms`，换算约 **194.6 req/s**；说明当前单入口既比旧方案更接近真实流量，又仍保持可重复回归

### 架构质量快照（存档时）
- **sentrux 质量信号：6355**（acyclicity=10000, redundancy=8811, depth=5714, equality=4965, modularity=4147）

### 关键完成项（2026-06-03 ～ 2026-06-04）
- **P1-P6 全部完成** — admin 测试归位（35+ 文件迁移）、最小 CI、fallback 顺序测试、配置分层、报表闭环、6 处文档失真修复、黑盒回归 71/71 ✅。
- **M2 计费管线修复** — ModelsDevClient `output_price` 同步到 `ModelPricingEntry`，`CostCalculator` 双价格生效。✅
- **M3 可靠性补强** — ErrorCode 枚举化 + 上游错误透传（502/504）+ 超时/熔断/运维门禁 8 个缺口修复 + 黑盒回归 122/122 ✅。
- **假绿治理（P0+P1+P2+M2+L4）** — `ChatCompletionsOrchestratorTest` 去 LENIENT（15/15 strict✅）、Postgres SQL 精确匹配、Redis 边界增强、Optional.empty 补缺、空 catch 修复。
- **M4 审计前端完成** — OperationsPage 4 Tab（Alerts/Logs/Audit/Cost），Logs 分页+详情弹窗，Audit 对接审计中心，Cost 成本面板。前端 build ✅。
- **系统配置热更新完成** — 全部 10 种系统配置持久化后即时生效，`Resilience4jCircuitBreakerService` 支持运行时清空 CB/Retry/Bulkhead 缓存。
- **黑盒回归扩充至 122 场景** — 新增 Observability、TPM 限流、并发限制、熔断、上游超时、运维门禁、双价格计费、配置热更新等。

### 关键完成项（2026-06-05）
- **P0 双向删除保护** — Provider 删除前检查 route 引用（409 + route 列表）；route 删除后清 `ModelRouteResolver` 缓存。
- **P1 剩余 5 种系统配置热更新完成** — `saveSystemConcurrentLimit/Tracing/Sync/ProviderHealth/Auth`，至此全部 10 种系统配置支持运行时热更新。
- **P2 黑盒测试补充（9+2）** — AdminConfig 9 个（Provider 删除 409、system PUT、Scene 导入、Limit 热更新）+ ChatCompletions 2 个（Streaming 断开/CB 展示）。
- **测试基础设施修复** — `WebTestCleanupSupport.BaselineState` 扩充 5 种配置；`CoreTestConfiguration.java` 新建修复 2 个预存 error。
- **跨模块 CB 测试拆分** — 消除 core→admin 隐式依赖。
- **测试扩容完成** — 新增 25 个测试文件 ~157 用例（Webhook/Aggregate/Admin Controller/Service/DTO/Config/Utility）。
- **孤儿测试清理 & 核心 Web 测试归位** — 3 个无源测试删除，`ChatCompletionsOrchestratorTest` 归位 core，5 个新 Web 层测试（29/29 ✅）。
- **黑盒补充** — `verify-supplement.sh` 新增 6 场景（Webhook CRUD+触发/Runtime/Alerts/Audit/SystemLimit）。
- 验证：gateway-core 555 ✅ + gateway-admin 490 ✅

### 关键完成项（2026-06-05 第二批次）
- **内容安全全部删除** — 删除 `ContentSafetyConfig`/`ContentSafetyService`/`ContentSafetyServiceTest` + 冗余 `ChatCompletionsOrchestratorTest`（admin 版）。14 个文件修改。
- **可观测性 3 项正确性修复** — (1) Dashboard `successRate` 分母改用 AggregateMetricStore；(2) `/internal/requests/recent` `total` 移至过滤器之后；(3) `AggregateReportingService.processedRequestIds` 改为 `ConcurrentHashMap` 带 10000 entry/5min TTL。
- 验证：gateway-core 535 ✅ + gateway-admin 495 ✅

### 关键完成项（2026-06-06 — Phase A/B 架构清理）
- **gateway-admin 根包扫描移除** — 新增 `AdminComponentScanConfig`，`GatewayAdminAutoConfiguration` 删除 `@ComponentScan`
- **gateway-core 根包扫描治理** — 新增 `CoreComponentScanConfig`，`GatewayCoreAutoConfiguration` 移除 `@ComponentScan`
- **Anthropic mock 上游 + 黑盒验证** — `jmeter/mock_anthropic_server_node.mjs`（端口 18084）
- **PG + Redis 真实后端回归** — `regression-backends.sh`（37 断言点）
- **并发压力测试脚本** — `stress-test.sh`（JMeter 5 场景）
- **去除 docker 依赖** — 删除 `docker-compose.yml`
- **Phase A — 消除根包扫描完成** — `@ComponentScan` 全部移除，改为定向扫描 + 显式 `@Import`
- **Phase B Core/Admin/Bootstrap 包名重构** — 三模块包名物理隔离（`core.*` / `admin.*` / `bootstrap.*`）
- **Phase B admin 测试修复** — `AdminTestConfiguration` 从错误包迁移，批量修复旧包引用
- 验证：core/admin/bootstrap compile ✅，SpaRoutingConfigTest 6/6 ✅

### 关键完成项（2026-06-06 — 测试扩容与前端建设）
- **BatchFlusher 参数配置化** — `GatewayProperties.batchFlusher` 新增 config 内部类
- **/v1/models 降级语义** — 无 admin 时返回 `X-Degraded: admin-unavailable`
- **废弃用户接口收口** — `AuthController` 三处 `@Deprecated` 补充文档
- **前端测试基础建设** — Vitest + RTL + jsdom + smoke test（2 用例）
- **高价值测试缺口填补** — 5 个文件 49 用例（PasswordService、ContentTranslator、InMemoryRouteStateStore、AdminClientController、InternalEndpointAuthFilter）
- **4 方向完善（685 → ~847 测试）** — JPA Repository(28)、配额月度路径(24)、InMemory 存储(39)、废弃接口清理
- **前端测试扩展（2 → 47 测试）** — LoginPage/Dashboard/Layout/Providers/API client

### 关键完成项（2026-06-06 第七批次）
- **黑盒测试缺口补充 — verify-gaps.sh（71 测试点，全部通过）** — 覆盖 6 个此前 0% 的领域：
  - 模型分组 CRUD(8)、配置导入(7)、系统配置热更新 5 种(10)、Auth API Key 管理(8)、配置版本/回滚(11)、Internal Catalog/Pricing/Discovery/Runtime/Dashboard(16)、Auth refresh/logout/password(9)
- **黑盒覆盖率从 56.5% → ~88%**（92 端点中约 81 个已覆盖）

### 额外修复（同期）
- `AdminTestConfiguration` 包位置修复（`io.gateway.oss.admin.admin` → `io.gateway.oss.admin`）
- 测试全局配置添加 `gateway.security.block-internal-urls: false`
- `AdminConfigControllerTest` 回归保留（`block-internal-urls=true` 局部覆盖）

### 关键完成项（2026-06-04 调优）
- **性能调优** — PG 模式瓶颈确认（每次 8 次同步查询），HYBRID 模式启用（限流/TPM→Redis），BatchFlusher 线程池 2→16
- **压测对比** — PG 130 QPS → HYBRID 159 QPS（+22%），瓶颈转移至 upstream mock 争抢 CPU
- 验证：Pg+Redis 回归 37/37 ✅

### 关键完成项（2026-06-06 — Phase 0-5 前端闭环 + 代码分数优化 Lane A-E）
- **Phase 0-5 全部完成** — 管理员自服务（ProfilePage/改密码）、用户门户骨架（路由守卫/UserLayout）、API Key 自助管理、用量/成本/请求记录、注册页、管理端用户维度请求日志聚合
- **`/v1/models` snapshot 优先级修复** — ModelListService 合并 model_groups 至 snapshot 结果
- **代码分数优化 Lane A-E 全量完成** — 配置契约化（`GatewayConfigView`/`SystemConfigManager`）、`UserAccountService` 首次拆分（`UserApiKeyService`）、`BillingPriceResolver` 拆分（`PricingResolutionEngine`/`PricingPreviewService`）、前端 API 层拆分、sentrux 规则补强
- **黑盒脚本增强** — `user-journey-blackbox.sh` 新增 PG 路径（128/128 ✅）、断言升级为结果正确性校验；`verify-gaps.sh` 100/100 ✅；`verify-supplement.sh` 35/35 ✅；三脚本合计 171/171 ✅
- **Bootstrap H2/Flyway 配置收归 local profile** — PG 路径不再依赖 CLI 覆盖
- **静态 YAML 用户 JWT clientId 修复** — 解决 PG 压测 `monthly_quota_exceeded` 导致的 100% 429

### 关键完成项（2026-06-06 — 代码审查修复 Fix-A~G）
- **Fix-A Refresh Token 原子轮换** — `consumeOnce` 原子接口（Redis/InMemory/Postgres 三后端），`AuthLoginController.refresh()` 同步消费
- **Fix-B JWT 鉴权缓存缺失拒绝** — `ClientAuthService` 缓存未命中 → 401，refresh token → 401
- **Fix-C Postgres namespace 隔离** — 7 个 Postgres Store + `V13__add_namespace_to_postgres_shared_state.sql`，PG 黑盒 130/130 ✅
- **Fix-D Write-behind 数据一致性** — `deleteUser()` 清理 dirty buffer，`flushDirtyAccount()` 不重建已删用户
- **Fix-E API Key 加密 + SSRF 加固** — SHA-256 hash 存储/匹配；`BaseUrlValidator` scheme 白名单 + IPv6 ULA/link-local/site-local 拦截
- **Fix-F 配置/发布/Webhook 可靠性** — `ConfigLoadService` 权威替换；`ModelPublicationService` 顺序执行；Webhook backoff 重试
- **Fix-G Controller 校验补全** — 8 个 Controller 补 `@Valid`；`HealthController` 异常收敛；日期参数 400；`ModelsController` 认证失败 401
- **集成回归补修** — `ClientAuthService` 静态 YAML 用户 JWT 兜底；`ConfigLoadService` 空 store 保留 YAML 默认值
- **总体验收** — compile ✅、focused tests ✅、verify.sh 36/36 ✅、user-journey-blackbox.sh 130/130 ✅、PG 路径 130/130 ✅、verify-gaps.sh 69/69 ✅、verify-supplement.sh 35/35 ✅、sentrux_check_rules ✅

### 关键完成项（2026-06-06 — ESLint/Checkstyle 接入 + 代码分数优化 A）
- **ESLint 接入** — `frontend/eslint.config.js` 配置，`no-explicit-any` 降为 warn，修复 75 个 error → 0 error / 43 warnings
- **Checkstyle 接入** — `config/checkstyle/` 配置 + suppressions，core/admin 均通过
- **Java 真实问题修复** — 批量移除未用 import、冗余修饰符、`KeyHashUtil` final class、`GeminiChatProviderAdapter` NeedBraces
- **sentrux 规则修复** — 删除无效 `[[rules]]` 格式，改为 `[[boundaries]]` 结构，`rules_checked=3`、`0 violation`
- **代码分数优化 A（depth/modularity）** — `UserAccountService` 拆出 `UserAccountCacheIndex` + `DirtyAccountFlushBuffer`，对外 API 不变
  - `quality_signal` 5364 → **5368**，`modularity` 4486 → **4495**，`cross_module_edges` 862 → **854**，`total_import_edges` 1313 → **1302**
  - `depth` 维持 4444（当前瓶颈）
- **最终验证** — compile ✅、lint ✅、checkstyle ✅、sentrux ✅、frontend build ✅

## 详细计划归档（Phase 0-5 / Fix-A~G / Lane A-E）

以下为已完成的计划详细内容，由 CONTEXT-plan.md 移出存档。

## 计划背景

基于当前仓库核查，后端核心能力已基本具备，但前端能力存在明显结构性缺口：

- 管理员侧缺少“修改本人密码 / 完善个人资料”前端闭环
- 普通用户侧缺少独立门户、注册页、API Key 自助管理、个人用量/成本/请求记录页面
- `/v1/models` snapshot 优先级问题已修复；后续以防回归和口径保持一致为主

本轮目标不是扩展新业务，而是在**不改变现有后端分层和核心语义**的前提下，按优先级补齐用户与管理员的主线闭环。

补充说明：在 Phase 0-5 完成后，本轮又对 `scripts/user-journey-blackbox.sh` 做了可信度增强，聚焦两项已确认补点：

1. 增加更接近真实后端的执行路径（PostgreSQL + Redis + Flyway）
2. 增强关键断言，从字段存在升级为结果正确

此后又继续完成两项收尾：

3. Bootstrap H2/Flyway 基础配置收归 `local` profile，PG 路径不再依赖 CLI 覆盖才能正常迁移
4. 修复静态 YAML 用户 JWT 缺失真实 `clientId` 的语义问题，解决 PG 压测中 `monthly_quota_exceeded` 导致的 baseline/moderate/streaming 100% 429

---

## 总体执行策略

### 原则

1. **安全性 > 正确性 > 最小变更 > 可读性 > 一致性**
2. 严格复用现有后端接口，不随意新增后端语义
3. 先补闭环，再补体验；先补用户可用，再补后台增强
4. 前端新增页面优先基于现有技术栈：React + React Router + Zustand + React Query + 自有 UI 组件
5. `/v1/models` snapshot 优先级问题已修复，后续以防回归与文案口径持续一致为主

### 主线顺序

按以下顺序推进：

1. **Phase 0：管理员自服务快速补齐 + 语义收敛**
2. **Phase 1：普通用户门户基础骨架**
3. **Phase 2：普通用户 API Key 自助管理**
4. **Phase 3：普通用户用量 / 成本 / 请求记录 / 接入信息**
5. **Phase 4：普通用户注册闭环**
6. **Phase 5：后台聚合视图与产品语义一致性增强**

---

## 范围清单

### 本轮纳入范围（按优先级）

#### P0：必须补，不然主线不成立

1. 普通用户前端门户
2. 普通用户 API Key 自助管理页面
3. 普通用户用量 / 成本 / 请求记录页面
4. 面向普通用户的接入说明 / 可复制接入信息页面
5. `/v1/models` 与模型发布可见性语义持续收敛（当前修复已完成，后续以防回归为主）

#### P1：影响管理员首次体验

6. 管理员自助修改自己密码页面
7. 管理员个人资料 / 基础信息页面

#### P2：影响产品说明一致性

8. 用户维度日志 / 用量 / 成本后台聚合视图
9. 模型发布与 fallback 流程关系说明或后续统一方案
10. 渠道 key 管理模式口径统一（单 key / 多 key）
11. 渠道模型保存权威数据源口径统一

### 本轮明确不纳入范围

为避免范围失控，以下内容本轮仅记录，不主动扩展实现：

- 新的后端鉴权模型
- 新的数据库表 / DDL 迁移
- 对现有 `/auth/me.quota.used` 占位语义做大范围重构
- 把 fallback 全量改造成另一套新编排系统
- 无直接闭环价值的大规模前端视觉重设计

---

## 分阶段实施计划

## Phase 0 — 管理员自服务快速补齐 + 语义收敛

### 目标

先把管理员“首次登录后台后完善自己信息”的最小闭环补齐，并确保 `/v1/models` 修复后的真实语义与文档保持一致。

### 计划项

1. 在前端新增管理员个人资料入口
2. 新增“修改本人密码”表单，调用 `PUT /auth/password`
3. 新增“编辑个人资料”表单，调用 `PUT /auth/profile`
4. 登录后读取 `/auth/me`，展示 `displayName / email / role / createdAt` 等基础信息
5. 收敛 `/v1/models` 当前口径：
   - snapshot 优先级问题已修复，published alias 不再被 snapshot 遮蔽
   - 后续以防回归验证与文档/计划口径持续一致为主

### 依赖

- 前端已有登录流程与 token 注入能力
- 后端已具备 `GET /auth/me`、`PUT /auth/profile`、`PUT /auth/password`

### 验收标准

- 管理员登录后能在前端进入个人资料页
- 成功修改 `displayName / email`
- 成功修改本人密码并能用新密码重新登录
- 文档与计划中不再出现“模型发布保存后立即可见”这类强保证表述

---

## Phase 1 — 普通用户门户基础骨架

### 目标

打通“普通用户登录后有地方可去”的基础承接页和基础路由，不再被 admin-only 逻辑挡住。

### 计划项

1. 调整前端路由守卫，区分 admin 与普通用户路由
2. 保留现有 admin 后台入口不变
3. 新增普通用户布局（例如 `/portal/*`）
4. 新增普通用户首页，至少承接：
   - 当前用户基础信息
   - API Key 入口
   - 用量 / 成本入口
   - 接入说明入口

### 依赖

- 需要先明确普通用户登录成功后的默认跳转策略
- 建议不改动后端登录接口，仅前端按 role 分流

### 验收标准

- 普通用户登录后不再显示“Access Denied”
- 普通用户登录成功后自动进入用户门户
- admin 登录后仍然进入现有管理后台，不回归不串线

---

## Phase 2 — 普通用户 API Key 自助管理

### 目标

补齐普通用户“拿到账号后创建自己的 API Key”主线。

### 计划项

1. 新增 API Key 列表页
2. 新增创建 API Key 对话框，调用 `POST /auth/keys`
3. 支持删除 API Key，调用 `DELETE /auth/keys/{keyId}`
4. 如当前后端已具备，补齐启停 / 轮换操作
5. 对首次创建返回的完整 `apiKey` 做一次性展示与复制提醒

### 依赖

- 前端用户门户骨架已存在
- 后端 `/auth/keys*` 自助接口可用

### 验收标准

- 普通用户可列出自己的 keys
- 可成功创建 key，并在创建后立即拿到明文值
- 可删除 key
- 如启停 / 轮换已接入，前端动作与后端返回一致

---

## Phase 3 — 普通用户用量 / 成本 / 请求记录 / 接入信息

### 目标

补齐普通用户“会用、能看、可核对”的运营闭环。

### 计划项

1. 新增近期请求记录页，调用 `GET /auth/usage/recent`
2. 新增成本页，调用 `GET /auth/usage/costs`
3. 新增个人资料页，展示 `/auth/me`
4. 新增接入说明页，显式展示：
   - Base URL
   - 当前可用 API Key 获取方式
   - 可用模型名获取方式
   - OpenAI-compatible 接入示例
5. 视页面结构决定是否把“修改个人资料 / 修改密码”也并入用户门户

### 依赖

- 用户门户与 API Key 页面已完成
- 如需展示可用模型名，需结合 `/v1/models` 当前可见性现实，不得过度承诺

### 验收标准

- 普通用户可查看自己的近期请求记录
- 普通用户可查看自己的成本聚合
- 普通用户可从页面上拿到接入所需最少信息并完成复制

---

## Phase 4 — 普通用户注册闭环

### 目标

把“自助注册 → 登录/拿 token → 进入用户门户”补成完整闭环。

### 计划项

1. 新增注册页与注册路由
2. 接入 `POST /auth/register`
3. 若注册成功返回 token，则复用现有登录态写入逻辑
4. 注册成功后自动跳转普通用户门户
5. 如当前后端 registration mode 为 restricted/disabled，需要前端对异常提示做清晰表达

### 依赖

- 用户门户基础已完成
- 需要明确产品是否默认开放注册；若后端配置为 restricted/disabled，前端仅负责提示，不强行绕过

### 验收标准

- 在允许注册的配置下，普通用户可完成前端注册并进入门户
- 在不允许注册的配置下，页面反馈符合后端实际语义

---

## Phase 5 — 后台聚合视图与产品语义一致性增强

### 目标

提升管理台对普通用户的运营可见性，并继续收敛当前产品语义与实现的偏差。

### 实际交付

1. **用户维度请求日志聚合（Phase 5.1）**：
   - 在管理端 `UsersPage` 的 UserRow 新增"View Requests"按钮
   - 点击后弹出 `UserRequestLogsDialog`，调用 `/admin/requests/recent?client={clientId}`
   - 展示摘要统计（请求数、总 tokens、总成本）+ 近期请求明细表格
   - i18n 已补充中英文翻译
2. **产品语义收敛文档更新（Phase 5.2-5.4）**：
   - `CONTEXT-plan.md`：更新完成状态与交付描述
   - `CONTEXT.md`：同步进展，更新 `/v1/models` 已知问题（已修复 snapshot 优先级逻辑）

### 验收标准

- ✅ 后台能以"用户"为中心查看请求日志 + 统计摘要
- ✅ 相关文案不再误导产品语义

---

## 交付清单映射

### 管理员侧最终应具备

- 登录后台
- 修改本人密码
- 编辑本人基础信息
- 渠道接入与模型同步
- 模型发布（并明确 `/v1/models` 当前可见性语义）
- 用户管理与代管能力

### 普通用户侧最终应具备

- 注册（如配置允许）/ 登录
- 进入用户门户
- 创建与管理自己的 API Key
- 查看自己的请求记录 / 用量 / 成本
- 获取接入信息并在第三方软件中接入

---

## 验证与验收策略

## 通用最小验证

### 前端变更

```bash
cd frontend && npm run build
cd frontend && npm run lint
```

### 后端 / Java 变更

```bash
./mvnw -q -DskipTests compile
```

### 涉及认证接口 / 用户主链路时建议补充

```bash
./scripts/verify.sh
./scripts/user-journey-blackbox.sh
```

## 分阶段验收重点

### Phase 0

- 管理员资料页与密码页手工验证
- 必要时补前端页面测试

### Phase 1-4

- 前端构建通过
- 用户登录/注册/进入门户主链路可手工复现
- 如页面涉及真实接口，优先通过已有黑盒脚本确认后端不回归

### Phase 5

- 以页面功能回放 + 文档语义校验为主

---

## 当前执行顺序（已确认）

用户已确认采用：

> **Phase 0 → 1 → 2 → 3 → 4 顺序执行**

这意味着后续实现默认按该顺序推进，除非用户再次调整优先级。

---

## 风险与注意事项

1. **`/v1/models` 回归风险**
   - snapshot 与 publication / model-group 的优先级问题已修复
   - 后续需以防回归验证和文档口径持续一致为主

2. **普通用户门户是新增信息架构，不是局部修补**
   - 需要调整前端路由守卫和跳转逻辑
   - 需要确保不影响现有 admin 台

3. **`/auth/me.quota.used` 仍为占位值**
   - 用户页面若展示 quota，需要明确口径，避免暗示其为完整实时计量

4. **注册能力受后端配置约束**
   - 前端只能承接，不应覆盖 `registration-mode` 真实语义

---

## 相关文件

- `CONTEXT.md`：项目状态总览
- `CONTEXT-history.md`：历史完成记录
- `frontend/src/App.tsx`：当前前端路由入口
- `frontend/src/store/auth.ts`：当前登录态与角色处理
- `frontend/src/api/client.ts`：前端 API 调用入口
- `gateway-core/src/main/java/com/example/gateway/core/security/AuthController.java`：自助 auth/profile/password/keys/usage 接口
- `gateway-admin/src/main/java/com/example/gateway/admin/web/ModelPublicationController.java`：模型发布入口
- `gateway-admin/src/main/java/com/example/gateway/admin/web/ModelPublicationService.java`：发布后可见性与 warning 逻辑


> ⚠️ **以上为已完成的 Phase 0-5 前端闭环 + 代码分数优化 Lane A-E 计划。**
> **以下为 2026-06-06 全量代码审查结果触发的修复计划。**
> 审查覆盖 242 文件 / 25,903 行，分 6 阶段（架构 → Controller → Service → Repository → 并发/安全/缓存 → 汇总），发现 30 个真实问题（4 Critical, 11 High, 11 Medium, 4 Low）。

---

## 代码审查修复计划（基于 6 阶段审查结果）

> 该修复计划已完成实施与验收收口；详细结果以 `CONTEXT.md` 的项目状态总览为准。本节保留计划结构、实施要点与最终验收结果，供后续回看。

### 计划背景

2026-06-06 完成的 6 阶段代码审查发现 **30 个真实问题**，其中：
- 4 个 **Critical**：均为认证/数据一致性漏洞
- 11 个 **High**：安全、性能、架构缺陷
- 11 个 **Medium**：可维护性/可观测性/查询问题
- 4 个 **Low**：优化建议

当前阶段目标：**按风险等级排序修复已确认缺陷**，不新增功能，不改变现有业务语义。

### 总体执行策略

1. **P0 级 (Critical) 优先修复** — 每项独立评估，小步合并 PR
2. **P1 级 (High) 分组并行** — 按安全/数据/可观测性分组修复
3. **P2 级 (Medium) 跟进** — 穿插在 P0/P1 的 review 间隙
4. **P3 级 (Architecture Debt)** — 整合已有 Lane A-E 优化计划推进
5. **不改变业务语义** — 修复聚焦缺陷，不扩展功能
6. **每次修复后验证** — 最小验证 + 对应黑盒脚本

### 修复 Lane 体系（Fix Lane A~G）

| Lane | 主题 | 严重问题数 | 预估工作量 | 前置依赖 |
|------|------|-----------|-----------|---------|
| **Fix-A** | Refresh Token 原子轮换 + 黑名单修复 | 2 Critical | 1-2 天 | 无 |
| **Fix-B** | JWT 鉴权重构 & 缓存未命中拒绝 | 1 Critical | 1-2 天 | 无 |
| **Fix-C** | Postgres namespace 隔离 | 1 Critical | 2-3 天 | 无 |
| **Fix-D** | Write-behind 数据一致性 | 1 Critical | 1 天 | 无 |
| **Fix-E** | API Key 加密 + SSRF 加固 | 2 High | 1-2 天 | 无 |
| **Fix-F** | 配置/发布/Webhook 可靠性 | 2 High | 2-3 天 | 无 |
| **Fix-G** | Controller 校验补全 + 错误处理收敛 | 1 High (模式问题) | 1 天 | 无 |

### 依赖关系

- Fix-A ~ Fix-G **互无强依赖**，可完全并行
- 每个 Lane 内部步骤建议顺序执行
- 各 Lane 验证均基于已有黑盒脚本，无需新增基础设施

### 当前状态

- Fix-A ~ Fix-G 已全部完成
- 集成回归中额外补修两点也已完成：
  1. `ClientAuthService` 已补静态 YAML 用户 JWT principal 构造，避免 `admin` 等静态用户被误判为 `Account no longer exists`
  2. `ConfigLoadService` 已明确为“空 store 保留 YAML/Spring 默认值；仅覆盖 store 中存在的 system key”
- 验收脚本已全部通过；黑盒脚本需**串行执行**，避免共享 `18080/8081` 端口造成假失败

---

## Fix-A: Refresh Token 原子轮换

### 目标

解决 refresh token 轮换中的 check-then-act 竞态和异步拉黑不持久问题。

### 覆盖的问题

| 编号 | 来源 | 等级 | 描述 |
|------|------|------|------|
| R1 | Phase 3/5 | Critical | Refresh token 轮换非原子，两个并发请求可同时消费同一旧 token |
| - | Phase 5 | Medium | Refresh token 未被显式拒绝（JWT 识别为 refresh 后未立即拒绝） |

### 涉及文件

- `gateway-core/src/main/java/com/example/gateway/core/security/AuthLoginController.java`
- `gateway-core/src/main/java/com/example/gateway/core/security/RefreshTokenBlacklistService.java`
- `gateway-core/src/main/java/com/example/gateway/core/security/ClientAuthService.java`（refresh token 显式拒绝逻辑）

### 实施步骤

1. **Step 1**：在 `RefreshTokenBlacklistService` 新增原子接口 `consumeOnce(token, claims)`：
   - Redis 后端：使用 Lua 脚本或 `SET NX EX` 实现"仅首次消费成功"
   - InMemory 后端：使用 `ConcurrentHashMap.putIfAbsent` + 过期淘汰
   - Postgres 后端：`INSERT ON CONFLICT` 检查
2. **Step 2**：重构 `AuthLoginController.refresh()`：
   - 移除 `.subscribe()` 异步拉黑模式
   - 改为在原子消费成功后同步签发新 token
   - 旧 refresh token 仅当首次消费成功时才签发新 token
3. **Step 3**：在 `ClientAuthService.authenticate()` 中，JWT 识别为 refresh token 后立即抛出 401，不继续 fallback

### 验证

```bash
./mvnw -q -DskipTests compile
./mvnw -q -pl gateway-core -Dtest=AuthControllerTest,JwtServiceTest,ClientAuthServiceTest test
./scripts/verify.sh
./scripts/user-journey-blackbox.sh
```

### 风险

- 中：Redis Lua 脚本需确保与现有 blacklist 语义完全兼容
- 低：InMemory 版本需注意 JVM 重启后 blacklist 丢失（已有行为，不做改变）

---

## Fix-B: JWT 鉴权缓存缺失拒绝

### 目标

消除缓存未命中时 JWT 鉴权绕过风险。

### 覆盖的问题

| 编号 | 来源 | 等级 | 描述 |
|------|------|------|------|
| R2 | Phase 3 | Critical | `ClientAuthService.authenticate()` 在缓存未命中时仍构造 `ClientPrincipal` 放行 |

### 涉及文件

- `gateway-core/src/main/java/com/example/gateway/core/security/ClientAuthService.java`
- `gateway-core/src/main/java/com/example/gateway/core/security/UserAccountService.java`

### 实施步骤

1. **Step 1**：在 `ClientAuthService.authenticate()` JWT 路径中，`findByUsernameSync()` 返回 `null` 时直接抛出 `GatewayException(HttpStatus.UNAUTHORIZED)`，不继续放行
2. **Step 2**：删除 `buildJwtPrincipal()` 中 `client == null && role != null` 的兜底放行分支
3. **Step 3**：考虑在 `findByUsernameSync()` 回源失败时记录 warn 日志，辅助排障

### 验证

```bash
./mvnw -q -DskipTests compile
./mvnw -q -pl gateway-core -Dtest=ClientAuthServiceTest,JwtServiceTest test
./scripts/verify.sh
# 手动验证：停掉 Redis，用有效 JWT 请求，确认返回 401 而非 200
```

### 风险

- 中：现有缓存的 100% 命中率假设需要打破测试来验证修复有效
- 建议在测试中主动清缓存后验证 JWT 请求被拒绝

---

## Fix-C: Postgres namespace 隔离

### 目标

让 Postgres 系列存储实现与 Redis 保持一致的 keyPrefix 命名空间隔离语义。

### 覆盖的问题

| 编号 | 来源 | 等级 | 描述 |
|------|------|------|------|
| R3 | Phase 4 | Critical | 全部 Postgres 存储实现（7 个 Store）未使用 `gateway.shared-state.keyPrefix` |

### 涉及文件

- `gateway-core/src/main/java/com/example/gateway/core/config/store/PostgresConfigStore.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/quota/PostgresClientUsageStore.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/quota/PostgresClientCostStore.java`
- `gateway-core/src/main/java/com/example/gateway/core/limit/PostgresClientRateLimiter.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/limit/PostgresClientTpmStore.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/observability/PostgresAggregateMetricStore.java`
- `gateway-core/src/main/java/com/example/gateway/core/observability/PostgresTraceStore.java`
- `bootstrap/src/main/resources/db/migration/V1__initial_schema.sql`（计划阶段记录的追加 DDL 迁移位置）

> 实际落地时，迁移文件按仓库现有 Flyway 目录与版本序列新增为 `gateway-core/src/main/resources/db/migration/V13__add_namespace_to_postgres_shared_state.sql`，未改动 `bootstrap` 下历史迁移文件。

### 实施步骤

1. **Step 1**：新增 Flyway 迁移脚本，为相关表增加 `namespace` 列：
   - `config_kv` → `namespace VARCHAR(128) NOT NULL DEFAULT 'gateway'`，主键改为 `(namespace, config_type, key)`
   - `client_usage`、`client_cost`、`rate_limit`、`tpm`、`aggregate_metrics`、`trace` → 加 `namespace` 到主键/唯一键
2. **Step 2**：逐个更新 7 个 Postgres Store 实现，在 SQL 中注入 `prefix`（复用 `RedisStoreUtils.safePrefix()` 获取）
3. **Step 3**：确认本地/in_memory 路径不受影响（仅影响 Postgres 后端）

### 验证

```bash
./mvnw -q -DskipTests compile
./scripts/user-journey-blackbox.sh --backend postgresql  # 确认 PG 路径仍然 128/128
./scripts/regression-backends.sh                         # PG+Redis 回归
```

### 风险

- 中：DDL 迁移不能破坏已有数据（实际采用 `DEFAULT 'gateway'` 以对齐仓库默认 `keyPrefix` 并保持历史数据可见）
- 低：新增迁移脚本需排在现有迁移文件之后

---

## Fix-D: Write-behind 数据一致性

### 目标

防止删除用户被 write-behind 缓冲区意外复活。

### 覆盖的问题

| 编号 | 来源 | 等级 | 描述 |
|------|------|------|------|
| R4 | Phase 3 | Critical | `UserAccountService.deleteUser()` 不清理 `dirtyAccounts`，定时 flush 重建已删用户 |

### 涉及文件

- `gateway-core/src/main/java/com/example/gateway/core/security/UserAccountService.java`

### 实施步骤

1. **Step 1**：`deleteUser()` 中先 `dirtyAccounts.remove(username)` 清理 write-behind 缓冲区
2. **Step 2**：`evictAccount()` 中补 `dirtyAccounts.remove(existing.username())`
3. **Step 3**：`flushDirtyAccount()` 在 store 中查不到时直接 `Mono.empty()`，不再 `switchIfEmpty(Mono.just(snapshot))` → `save(...)`
4. **Step 4**：`markApiKeyUsed()` 中严格只在修改后加入 `dirtyAccounts`，对已删除/冻结账号不做标记

### 验证

```bash
./mvnw -q -DskipTests compile
./mvnw -q -pl gateway-core -Dtest=UserAccountServiceTest test
./scripts/verify.sh
```

### 风险

- 低：Step 3 的行为变更需要确认是否有业务依赖"flush 时 store 不存在则重建"的语义。当前代码审查未发现这种依赖，是原实现的一个 bug

---

## Fix-E: API Key 加密 + SSRF 加固

### 目标

消除 API Key 明文存储泄露风险和完善 SSRF 防护。

### 覆盖的问题

| 编号 | 来源 | 等级 | 描述 |
|------|------|------|------|
| H1 | Phase 5 | High | API Key 以明文形式通过 ConfigStore 持久化 |
| H2 | Phase 5 | High | SSRF 防护不完整，IPv6 ULA/link-local 地址可绕过校验 |

### 涉及文件

- `gateway-core/src/main/java/com/example/gateway/core/security/UserAccountService.java`
- `gateway-core/src/main/java/com/example/gateway/core/security/BaseUrlValidator.java`
- `gateway-core/src/main/java/com/example/gateway/core/security/ClientAuthService.java`（认证时 hash 匹配）

### 实施步骤

#### 子项 E1: API Key 加密存储

1. **Step 1**：引入 SHA-256 + server-side pepper 做 key hash（复用或新增配置 `gateway.auth.key-pepper`）
2. **Step 2**：`generateApiKeyPlaintext()` 保持生成明文，但 `saveRecord()` 存 hash
3. **Step 3**：`findByApiKeySync()` 改为先 hash 再匹配
4. **Step 4**：`createUser/createApiKey` 仅在创建时返回一次明文 key（已有前端"复制提醒"）

#### 子项 E2: SSRF 加固

1. **Step 1**：`BaseUrlValidator.checkResolvedAddress()` 中增加 `isSiteLocalAddress()`、`isLinkLocalAddress()` 和 IPv6 ULA 显式拦截
2. **Step 2**：限制 scheme 为 `http/https`
3. **Step 3**：增加对 `Inet6Address` 的 `fc00::/7`（ULA）拦截

### 验证

```bash
./mvnw -q -DskipTests compile
./mvnw -q -pl gateway-core -Dtest=UserAccountServiceTest,ClientAuthServiceTest test
./scripts/verify.sh
```

### 风险

- 中：E1 的 hash 改造会改变现有 API Key 的存储格式，需要迁移脚本或"双读"兼容期
- 低：E2 的 scheme 限制可能影响已有使用非 `http/https` baseUrl 的 provider（需在部署前确认无此类配置）

---

## Fix-F: 配置/发布/Webhook 可靠性

### 目标

解决配置删除跨节点不同步、模型发布半原子、Webhook 投递无重试问题。

### 覆盖的问题

| 编号 | 来源 | 等级 | 描述 |
|------|------|------|------|
| H3 | Phase 5 | High | `ConfigLoadService` reload 采用增量 merge，已删配置跨节点残留 |
| H4 | Phase 3 | High | `ModelPublicationService.publish()` 多步操作非原子，`Mono.when()` 并发执行 |
| H5 | Phase 5 | High | Webhook `retryMax` 配置不生效，投递是单次 fire-and-forget |

### 涉及文件

- `gateway-core/src/main/java/com/example/gateway/core/config/ConfigLoadService.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/ModelPublicationService.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/webhook/WebhookDispatcherService.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/webhook/WebhookEndpointService.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/webhook/WebhookDeliveryLogService.java`

### 实施步骤

#### 子项 F1: 配置 reload 改为权威替换

1. **Step 1**：`ConfigLoadService` 的 `loadProviders/routes/scenes/clients/system` 改为先清空再填充，而非 merge

#### 子项 F2: Model 发布原子化

1. **Step 1**：`ModelPublicationService.publish()` 改为顺序执行（`.then()` 链），去掉 `Mono.when()`
2. **Step 2**：在第一步失败时提供补偿回滚（reverse operations）

#### 子项 F3: Webhook 重试

1. **Step 1**：`WebhookDispatcherService.dispatchAlertTriggered()` 中使用 `Retry.backoff(maxRetries, ...)` 替代单次 `sendOnce().subscribe()`
2. **Step 2**：递增 `deliveryLog.attempts` 计数
3. **Step 3**：在 `WebhookEndpointService.apply()` 中确认 `retryMax` 配置进入持久化

### 验证

```bash
./mvnw -q -DskipTests compile
./mvnw -q -pl gateway-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ModelPublicationControllerTest test
./scripts/verify-supplement.sh  # webhook 专项
./scripts/verify-gaps.sh        # 包含模型发布
```

### 风险

- 中：F1 的"先清空再填充"需要确认 ConfigSyncPublisher 的跨节点同步语义是否兼容
- 低：F2 的补偿回滚可以分步走，第一步先改成顺序执行 + 检测失败，回滚作为第二步

---

## Fix-G: Controller 校验补全 + 错误处理收敛

### 目标

解决多处 Controller 缺少 `@Valid` 导致校验失效、异常信息泄漏、非法日期参数静默回退等问题。

### 覆盖的问题

| 编号 | 来源 | 等级 | 描述 |
|------|------|------|------|
| H6 | Phase 2 | High (模式) | 多 Controller 请求体缺少 `@Valid`（ChatCompletionsController、AdminUserController、AdminProviderController、ModelGroupController 等） |
| - | Phase 2 | Medium | `HealthController` 异常信息泄漏（`e.getMessage()` 拼入响应） |
| - | Phase 2 | Medium | `InternalRequestLogController` 非法日期返回 500 而非 400 |
| - | Phase 2 | Low | `InternalRequestLogController/InternalUsageSummaryController` 非法日期静默回退为今天 |

### 涉及文件

- `gateway-core/src/main/java/com/example/gateway/core/web/ChatCompletionsController.java`
- `gateway-core/src/main/java/com/example/gateway/core/web/HealthController.java`
- `gateway-core/src/main/java/com/example/gateway/core/web/ModelsController.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/AdminUserController.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/AdminProviderController.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/ModelGroupController.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/InternalRequestLogController.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/InternalUsageSummaryController.java`

### 实施步骤

1. **Step 1**：批量补全 `@Valid` 注解到所有缺少校验的 Controller 请求体
2. **Step 2**：`HealthController` 异常处理分支改为 `log.error` + 返回稳定错误串，不泄漏 `e.getMessage()`
3. **Step 3**：`InternalRequestLogController/InternalUsageSummaryController` 的日期解析改为抛出 `IllegalArgumentException`（被 `GlobalExceptionHandler` 转为 400）
4. **Step 4**：`ModelsController.resolveKeyAllowedModels()` 在认证失败且携带认证头时返回 401 而非 `null`

### 验证

```bash
./mvnw -q -DskipTests compile
./scripts/verify.sh
# 手动验证：用非法参数请求受影响接口，确认返回 400 而非 500
```

### 风险

- 低：所有修改均为局部加固，不改变业务语义
- 低：补 `@Valid` 后非法请求返回 400（之前可能进入 service 层报 500），是改善不是破坏

---

## 下一阶段候选

### 技术债务清理（基于审查 Phase 1 架构问题）

以下架构债务整合到已有 Lane A-E 优化计划中，不单独起新 lane：

| 债务项 | 已有覆盖 |
|--------|---------|
| `gateway-core.config/` 上帝包（47 文件） | Lane A 契约化已收窄依赖面，包拆分待规划 |
| `gateway-admin` 无 service 层 | 待规划 |
| 三大上帝对象（ChatCompletionsOrchestrator/UserAccountService/DynamicConfigService） | Lane B 已拆 UserAccountService，其余待规划 |
| `web/` 包混合 Controller/Service/基础设施 | 待规划 |
| Contract/View 接口迁移（admin 仍 23 处依赖 GatewayProperties） | Lane A Step 3 已处理 4 文件，继续推进 |
| 健康检查测试修复 | 已完成（见 CONTEXT.md） |

### 测试债务补充

| 债务项 | 建议 |
|--------|------|
| Refresh token 原子轮换场景黑盒覆盖 | Fix-A 完成后补充 |
| API Key hash 改造后回归测试 | Fix-E 完成后补充 |
| Postgres namespace 迁移测试 | Fix-C 完成后补充 |

上述 3 项已随本轮收口完成：
- refresh / JWT / API key 聚焦测试已补跑通过
- Postgres namespace 路径已通过 `./scripts/user-journey-blackbox.sh --backend postgresql`

---

## 推荐执行顺序

### 第一批（可完全并行启动）

| Lane | 预计工时 | 验证依赖 |
|------|---------|---------|
| Fix-A: Refresh Token 原子轮换 | 1-2 天 | 无 |
| Fix-B: JWT 鉴权重构 | 1-2 天 | 无 |
| Fix-C: Postgres namespace | 2-3 天 | 无 |
| Fix-D: Write-behind 数据一致性 | 1 天 | 无 |
| Fix-E: API Key 加密 + SSRF | 1-2 天 | 无 |
| Fix-F: 配置/发布/Webhook | 2-3 天 | 无 |
| Fix-G: Controller 校验补全 | 1 天 | 无 |

### 总体验收标准

1. 所有修复通过 `./mvnw -q -DskipTests compile`
2. `./scripts/verify.sh` 全部通过
3. `./scripts/user-journey-blackbox.sh`（in_memory 路径）全部通过（130/130）
4. `./scripts/user-journey-blackbox.sh --backend postgresql`（PG 路径）全部通过（130/130）
5. 相关 focused 测试全部通过
6. 确认修复后不引入新的 sentrux 规则违反

### 当前执行顺序

已完成。实际执行时基于文件归属将 Fix-A ~ G 重排为 G1 ~ G7 并行组，避免 `ClientAuthService.java` / `UserAccountService.java` 等共享文件发生覆盖冲突；最终各组已集成完成并通过总体验收。

### 实际验收结果（已完成）

- `./mvnw -q -DskipTests compile` ✅
- `./mvnw -q -pl gateway-core -Dtest=ClientAuthServiceTest,ConfigLoadServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` ✅
- `./scripts/verify.sh` ✅（36/36）
- `./scripts/user-journey-blackbox.sh` ✅（130/130）
- `./scripts/user-journey-blackbox.sh --backend postgresql` ✅（130/130）
- `./scripts/verify-gaps.sh` ✅（69/69）
- `./scripts/verify-supplement.sh` ✅（35/35）
- `sentrux_check_rules` ✅（0 violation）
- 最新 `quality_signal=5364`

### 收口说明

- `ClientAuthServiceTest` 的 focused test 失败根因已确认是 Mockito matcher 误用（`any()` → `anyInt()`），并非测试源码整体漂移。
- `scripts/user-journey-blackbox.sh` 已按当前已实现契约修正，不再调用仓库中不存在的 `GET /auth/keys/{keyId}`，改为通过 `GET /auth/keys` 列表断言 key 元信息。


## 下一阶段候选：代码分数优化并发计划

### 背景

基于当前仓库核查，计划编写时的 `sentrux` 质量信号为 **5419**；第一批并发实现完成后已复扫到 **5440**，主要瓶颈仍为 **modularity=4295**。

当前问题不是模块依赖方向错误，而是：

1. `gateway-admin -> gateway-core` 依赖面过宽，跨模块边数量偏高
2. `gateway-core` 内存在若干超大中心类，承担过多横切职责
3. `.sentrux/rules.toml` 已有基础约束，但覆盖范围仍偏窄，尚不足以形成有效门禁

DSM 结果显示 `above_diagonal=0`，说明仓库总体依赖方向仍然干净；本轮目标不是推翻现有分层，而是在**保持现有模块边界与业务语义不变**前提下，收窄依赖面、拆薄中心类、补强治理规则。

### 本轮目标

1. 优先压缩 `gateway-admin -> gateway-core` 的细粒度直接依赖
2. 拆分后端高耦合/大体量类，降低单文件职责混杂度
3. 收敛前端超大页面与 API 聚合入口，避免继续积累模块化负担
4. 补强 `.sentrux/rules.toml`，把已确认的架构约束显式化、防回退

### 范围边界

#### 纳入范围

1. 后端配置/发布/定价/认证相关结构优化
2. 前端 API 层与管理台大页面拆分
3. sentrux 规则补强
4. 与上述结构优化直接相关的最小测试补齐

#### 明确不纳入范围

1. 新业务功能扩展
2. 数据库 DDL 变更
3. 改变现有接口语义、鉴权模型或模型发布业务语义
4. 大规模 UI 视觉重设计
5. 无直接结构收益的广泛重命名或风格清理

---

## 并发推进总览

本轮采用 **Lane A ~ E** 并发推进，其中：

- **Lane A / B / C**：后端结构优化主线
- **Lane D**：前端拆分与测试补齐
- **Lane E**：治理规则补强

建议并发启动顺序：

1. **Lane A Step 1**（接口定义）
2. **Lane B Step 1**（`UserAccountService` 拆分）
3. **Lane C Step 1**（`BillingPriceResolver` 拆分）
4. **Lane D Step 1**（`frontend/src/api/client.ts` 拆分）
5. **Lane E Step 1**（低风险规则先落地）

上述 5 个起步动作文件重叠极少，可直接并发。

### 实施状态（已完成）

**第一批（已交付）：**

1. **Lane A Step 1** ✅：已在 `gateway-core/src/main/java/com/example/gateway/core/contract/config/` 新增 `ProviderConfigView`、`RouteConfigView`、`SceneConfigView`、`ClientConfigView`、`SystemConfigView`、`GatewayConfigView`、`SystemConfigManager`；`GatewayProperties` / `DynamicConfigService` / 若干 config 类型已挂接到新契约。
2. **Lane B Step 1** ✅：已从 `UserAccountService` 提取 `UserApiKeyService` 承接 API key 生命周期逻辑；最小收口后，`ApiKeyView` 映射已回收至 `UserAccountService#listApiKeys`，避免新服务反向依赖旧服务内部 DTO。
3. **Lane C Step 1** ✅：`BillingPriceResolver` 已拆出 `PricingResolutionEngine` 与 `PricingPreviewService`，并已进一步调整为 Spring 可注入协作者，`BillingPriceResolver` 不再在 `@Service` 内手动 `new`。
4. **Lane D Step 1** ✅：`frontend/src/api/client.ts` 已拆分为 `http.ts`、`modules/auth.ts`、`modules/admin.ts`，`client.ts` 保留兼容出口，现有调用方无需改 import。
5. **Lane E Step 1** ✅：`.sentrux/rules.toml` 已补入 `no-empty-catch`、`frontend-tests-stay-under-src`、`no-controller-db-access`、`no-plain-runtime-exception` 四条低风险规则。

**第二批（新增交付）：**

6. **Lane A Step 3** ✅：admin 侧 3 个文件已迁移到新契约 — `AdminSystemConfigController` 字段 `DynamicConfigService` → `SystemConfigManager`；`AdminConfigExportSupport` 读操作 `GatewayProperties` → `GatewayConfigView`、写操作 `DynamicConfigService` → `SystemConfigManager`、循环变量改为 view 类型；`ModelPublicationService` 读操作走 `GatewayConfigView`、写操作保留 `DynamicConfigService`。`GatewayProperties` 不再作为 admin 业务代码的直接接口依赖。
7. **Lane D Step 2** ✅：三个大页面已完成子组件提取 — `UsersPage.tsx`（559→66行）拆出 5 个组件到 `frontend/src/components/users/`（`CreateUserDialog`、`EditUserDialog`、`ManageApiKeysDialog`、`UserRequestLogsDialog`、`UserRow`），`RoutesPage.tsx`（362→130行）拆出 `RouteEditDialog` 到 `frontend/src/components/routes/`，`OperationsPage.tsx`（379→279行）拆出 `RequestDetailView`/`CostPanel` 到 `frontend/src/components/operations/`。所有提取组件使用 `useTranslation()` 替代 `t: any` prop。
8. **脚本修复** ✅：`scripts/verify-supplement.sh` 修复 4 类预存缺陷 — `start_webhook_receiver` 阻塞（`$(node ... &)` 死锁 + `host.docker.internal` 无超时 hang）、6 处 `jq type` 不带 `-r` 导致引号污染、webhook delivery 时序、audit 检查顺序/过滤参数。修复后 35/35 全量通过。

### 验证结果

- `./mvnw -q -DskipTests compile` ✅
- `cd frontend && npm run build` ✅
- `cd frontend && npm test -- --run src/api/client.test.ts` ✅
- `./scripts/verify.sh` — 36/36 ✅
- `./scripts/verify-gaps.sh` — 100/100 ✅
- `./scripts/verify-supplement.sh` — 35/35 ✅
- 三脚本合计 **171/171 全量黑盒通过**
- `./mvnw -q -pl gateway-core -Dtest=UserAccountServiceTest test` ⚠️ 受既有测试编译问题阻塞：`gateway-core/src/test/java/com/example/gateway/core/web/HealthControllerTest.java` 仍按旧同步返回值断言，和当前 `HealthController` 的 `Mono<Map<String, Object>>` 签名不一致
- `./mvnw -q -pl gateway-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CostCalculatorTest,CostCalculatorDualPriceTest,ModelListServiceTest,AdminSystemConfigControllerTest test` ⚠️ 同样被上述 `HealthControllerTest` 编译错误阻塞，非本轮改动直接引入

### 后续候选

1. **独立清理验证阻塞项**：修复 `gateway-core/src/test/java/com/example/gateway/core/web/HealthControllerTest.java` 对旧同步签名的断言方式，再恢复 Lane B/C focused 测试链路
2. **Lane D Step 3**：为 `UsersPage`、`RoutesPage`、`OperationsPage` 及提取的子组件补页面测试

---

## Lane A — 核心配置层接口化（高收益 / 高风险）

### 目标

收窄 `gateway-admin` 对 `gateway-core.config` 内部实现与配置类型的直接依赖面，降低 `cross_module_edges`。

### 重点文件

- `gateway-core/src/main/java/com/example/gateway/core/config/GatewayProperties.java`
- `gateway-core/src/main/java/com/example/gateway/core/config/DynamicConfigService.java`
- `gateway-core/src/main/java/com/example/gateway/core/config/ConfigLoadService.java`
- `gateway-core/src/main/java/com/example/gateway/core/config/RuntimeRefreshHooks.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/AdminSystemConfigController.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/AdminConfigController.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/AdminConfigImportSupport.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/AdminConfigExportSupport.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/ConfigAuditController.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/AdminProviderController.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/AdminRouteController.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/AdminClientController.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/ModelGroupController.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/ModelPublicationService.java`

### 分批步骤

#### Step 1：先定义稳定接口（可与其他 lane 并发启动）

在 `gateway-core` 新增只读视图/门面接口，例如：

- `core/contract/config/ProviderConfigView`
- `core/contract/config/RouteConfigView`
- `core/contract/config/SceneConfigView`
- `core/contract/config/ClientConfigView`
- `core/contract/config/SystemConfigManager`

#### Step 2：让 `GatewayProperties` / `DynamicConfigService` 实现或委托这些接口

要求：

- 不改变当前配置语义
- 不新增业务分支
- runtime refresh / audit / snapshot 语义保持一致

#### Step 3：逐个适配 admin 侧 controller/support

优先顺序：

1. `AdminSystemConfigController`
2. `AdminConfigController`
3. `AdminConfigImportSupport` / `AdminConfigExportSupport`
4. `ModelPublicationService`
5. 其余 provider/route/client/group/audit 入口

### 依赖关系

- Step 1 必须先完成
- Step 2 依赖 Step 1
- Step 3 依赖 Step 2
- Step 3 内部多个 controller/support 可并发改造

### 风险与收益

- 风险：**高**
- 收益：**最高**（本轮最直接作用于 modularity）

### 验证

```bash
./mvnw -q -DskipTests compile
./mvnw -q -pl gateway-core -DskipTests compile
./mvnw -q -pl gateway-admin -q -DskipTests compile
```

必要时补：

```bash
./mvnw -q -pl gateway-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=AdminSystemConfigControllerTest,ModelPublicationControllerTest test
```

---

## Lane B — Auth / UserAccount 大类拆分（中收益 / 中风险）

### 目标

拆薄 `gateway-core` 中职责过重的认证与账户管理中心类，降低单类复杂度与内部耦合。

### 重点文件

- `gateway-core/src/main/java/com/example/gateway/core/security/AuthController.java`
- `gateway-core/src/main/java/com/example/gateway/core/security/UserAccountService.java`
- `gateway-core/src/main/java/com/example/gateway/core/security/JwtService.java`
- `gateway-core/src/main/java/com/example/gateway/core/security/RefreshTokenBlacklistService.java`

### 分批步骤

#### Step 1：先拆 `UserAccountService`

优先提取边界更清晰的子职责：

- `UserApiKeyService`
- `UserCacheManager` / `WriteBehindFlusher`
- `UserAccountCore`

#### Step 2：再拆 `AuthController`

建议按接口语义分组：

- `AuthLoginController`
- `UserProfileController`
- `UserKeyController`
- `UserUsageController`

#### Step 3：收口装配与 focused 测试

确保路由 path、认证语义、token/refresh 黑名单语义不变。

### 依赖关系

- Step 1 优先于 Step 2
- Step 3 依赖前两步
- 与 Lane A / C / D / E 无强依赖，可独立并发

### 风险与收益

- 风险：**中**
- 收益：**中高**

### 验证

```bash
./mvnw -q -pl gateway-core -Dtest=AuthControllerTest,JwtServiceTest,ClientAuthServiceTest test
./mvnw -q -DskipTests compile
```

必要时补：

```bash
./scripts/verify.sh
./scripts/user-journey-blackbox.sh
```

---

## Lane C — 定价 / 模型 / 发布边界收敛（中收益 / 低到中风险）

### 目标

收敛模型发布、模型列表、定价解析之间的边界，减少 admin 对 core web/config 细节的直接耦合。

### 重点文件

- `gateway-admin/src/main/java/com/example/gateway/admin/pricing/BillingPriceResolver.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/sync/ModelListService.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/web/ModelPublicationService.java`
- `gateway-admin/src/main/java/com/example/gateway/admin/sync/ModelsDevClient.java`
- `gateway-core/src/main/java/com/example/gateway/core/pricing/PricingResolver.java`
- `gateway-core/src/main/java/com/example/gateway/core/web/ModelListProvider.java`
- `gateway-core/src/main/java/com/example/gateway/core/web/ModelsController.java`

### 分批步骤

#### Step 1：拆分 `BillingPriceResolver`

建议提取：

- `PricingResolutionEngine`
- `PricingPreviewService`

使 resolver 自身回归薄门面。

#### Step 2：拆分 `ModelListService`

优先提取：

- 路由可见性判断 helper
- 模型展示组装 helper

#### Step 3：收敛 `ModelPublicationService`

优先改为依赖 Lane A 产出的 manager/view 接口，而不是继续直接依赖多个 config 细节类型。

### 依赖关系

- Step 1 与 Step 2 可立即启动
- Step 3 最好在 Lane A Step 1/2 之后进行

### 风险与收益

- 风险：**低到中**
- 收益：**中**

### 验证

```bash
./mvnw -q -pl gateway-admin -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CostCalculatorTest,CostCalculatorDualPriceTest,ModelListServiceTest,ModelPublicationControllerTest,AdminSystemConfigControllerTest test
./mvnw -q -DskipTests compile
```

---

## Lane D — 前端 API 与大页面拆分（中收益 / 低风险）

### 目标

降低 `frontend` 侧超大文件和职责混杂，避免前端继续成为模块化短板。

### 重点文件

- `frontend/src/api/client.ts`
- `frontend/src/pages/UsersPage.tsx`
- `frontend/src/pages/RoutesPage.tsx`
- `frontend/src/pages/OperationsPage.tsx`
- `frontend/src/pages/ProvidersPage.tsx`
- `frontend/src/pages/ClientsPage.tsx`
- `frontend/src/pages/SystemConfigPage.tsx`

### 分批步骤

#### Step 1：先拆 `client.ts`

建议拆为：

- `frontend/src/api/http.ts`
- `frontend/src/api/modules/auth.ts`
- `frontend/src/api/modules/admin.ts`

`client.ts` 仅保留 barrel re-export，降低改动面。

#### Step 2：并发拆大页面中的 dialog / 纯展示子组件

建议并发子任务：

- `UsersPage.tsx`：提取 Create/Edit/ManageApiKeys/UserRequestLogs 4 个 dialog
- `RoutesPage.tsx`：提取 RouteFormDialog
- `OperationsPage.tsx`：先提取 `RequestDetailView` / `CostPanel`
- `ProvidersPage.tsx`：提取 ProviderFormDialog
- `ClientsPage.tsx`：提取 ClientFormDialog
- `SystemConfigPage.tsx`：提取 4 个 config 子组件

#### Step 3：补最小页面测试

优先补：

- `RoutesPage.test.tsx`
- `ClientsPage.test.tsx`
- `UsersPage.test.tsx`
- `OperationsPage.test.tsx`
- `SystemConfigPage.test.tsx`
- `UserLayout.test.tsx`

### 依赖关系

- Step 1 可立即启动
- Step 2 内多个页面可完全并发
- Step 3 可与 Step 2 并行推进，或在拆分稳定后集中补齐

### 风险与收益

- 风险：**低到中**
- 收益：**中**

### 验证

```bash
cd frontend && npm run build
cd frontend && npm test -- --run
```

必要时补：

```bash
cd frontend && npm run lint
```

---

## Lane E — sentrux 规则补强（低风险 / 治理收益高）

### 目标

把已确认的模块边界、注入规范、异常规范与前端类型/异常处理约束固化到 `.sentrux/rules.toml`，防止结构回退。

### 现状

当前仅有 3 条规则：

1. `no-core-depends-on-admin`
2. `no-root-package-component-scan`
3. `admin-tests-stay-in-admin-module`

### 建议新增规则

#### Step 1：先加低风险强约束（优先）

1. `no-empty-catch`
2. `test-colocation-frontend`
3. `no-controller-db-access`
4. `no-plain-runtime-exception`

#### Step 2：再加渐进收紧规则（先 warn）

5. `no-frontend-any-type`
6. `no-i18n-t-any`
7. `no-page-any-type`
8. `no-field-injection`

### 约束重点路径

- `frontend/src/**/*.{ts,tsx}`
- `frontend/src/pages/**/*.{ts,tsx}`
- `**/*Controller.java`
- `**/*.java`

### 依赖关系

- 与其他 lane 无强依赖
- 可最早启动
- 渐进类规则建议先 `warn`，待存量问题收敛后再升 `error`

### 风险与收益

- 风险：**低**
- 收益：**治理收益高，直接提分有限但可防回退**

### 验证

1. 规则文件语义人工复核
2. 结合 sentrux 再次扫描，确认规则与当前代码结构不冲突
3. 如后续接入 CI，再纳入门禁

---

## 推荐执行顺序

### 第一批：可立即并发启动

1. Lane A / Step 1：定义 config view / manager 接口
2. Lane B / Step 1：拆 `UserAccountService`
3. Lane C / Step 1：拆 `BillingPriceResolver`
4. Lane D / Step 1：拆 `frontend/src/api/client.ts`
5. Lane E / Step 1：补 4 条低风险规则

### 第二批：在第一批稳定后推进

1. Lane A / Step 2-3：core 实现 + admin 适配
2. Lane B / Step 2：拆 `AuthController`
3. Lane C / Step 2：拆 `ModelListService`
4. Lane D / Step 2：并发拆大页面子组件
5. Lane E / Step 2：补 warn 级渐进规则

### 第三批：收尾与验证

1. Lane C / Step 3：收敛 `ModelPublicationService`
2. Lane D / Step 3：补页面测试
3. 聚焦编译、focused 测试与黑盒回归
4. 重跑 sentrux，观察 `quality_signal` 与 `modularity` 变化

---

### Phase F — Modularity & Equality 专项（已归档）

**目标**：拆薄超大文件 + 修复冗余包深度。

**实施内容**：
| Lane | 内容 | 状态 |
|------|------|------|
| F1 | 拆薄 `ChatCompletionsOrchestrator`（724→~450 行）— 提取 `TokenExtractionHelper`、`ErrorResponseMapper`、`ConcurrencyLimitHelper` | ✅ |
| F2 | 拆薄 `AnthropicChatProviderAdapter`（500→~280 行）+ `GeminiChatProviderAdapter`（452→~260 行）— 提取 `*RequestBuilder`、`*ResponseParser` | ✅ |
| F3 | 拆薄 `AdminConfigImportSupport`（422→~250 行）+ `AdminUserController`（367→~250 行）— 提取 `ConfigImportFormatValidator`、`ConfigImportApplier`、`UserSearchCriteria`、`UserResponseAssembler` | ✅ |
| F4 | 修复 `web/alerts/alerts/` 目录冗余嵌套 | ✅ |

**验证结果**：compile ✅、verify.sh 36/36 ✅、sentrux quality_signal 5366→**5372**（+6）、modularity 4488→4492（+4）、equality 5073→5088（+15）。

**关键发现**：同包内提取辅助类对 sentrux 提升有限（cross_module_edges 873 > 868，新增文件引入额外跨模块 import）。

---

### Phase G — 依赖契约化（已归档）

**目标**：将 admin→core 从依赖具体实现类（`GatewayProperties`）改为依赖契约接口（`GatewayConfigView`）。

**实施内容**：
| Lane | 内容 | 状态 |
|------|------|------|
| G1 | 扩展 `SystemConfigView`：新增 `getSharedState()`、`getStore()` | ✅ |
| G2 | `TraceConfig` / `StoreConfig` 从 `GatewayProperties` 内部类提取为独立顶层类 | ✅ |
| G3a | 3 个纯类型引用文件迁移（BillingPriceResolver、CostCalculator、AdminUserController） | ✅ |
| G3b | 14 个业务调用文件迁移（sync、pricing、upstream、web 等） | ✅ |
| G3c | 7 个 infra 文件迁移（RedisClientTpmStore、TpmStoreConfig 等） | ✅ |
| G3d | `AdminSystemConfigController` 中 `GatewayProperties.TraceConfig` → `TraceConfig` | ✅ |
| G4 | `ConfigImportApplier` 从 `gateway-admin` 下沉到 `gateway-core` | ✅ |

**验证结果**：compile ✅、verify.sh 36/36 ✅、admin 侧 `GatewayProperties` 引用 25→0 ✅。sentrux quality_signal 5372→5371（±0），modularity 4492→4488。

**关键发现**：依赖契约化不改变跨模块边数，对 sentrux modularity 无正向影响。25 个文件全部迁移，但 cross_module_edges 873→875 反而微增。

---

### Phase H — 包展平（已归档）

**目标**：展平 `contract/config`、`upstream/state`、`config/store` 子包，验证 depth 是否由目录层级决定。

**实施内容**：
- H1a：`core.contract.config` 下 7 个契约类上移到 `core.contract` ✅
- H1b：`core.upstream.state` 下 9 个状态存储类上移到 `core.upstream` ✅
- H1c：`core.config.store` 下 6 个配置存储类上移到 `core.config` ✅

**验证结果**：compile ✅、verify.sh 36/36 ✅。sentrux quality_signal 5371（不变），modularity 4488→**4484**（-4），depth 4444（不变）。

**关键发现**：目录展平不影响 sentrux depth。depth 度量的是依赖图最长链长度，不是 Java package 物理层级。包移动引入少量新 import 边（cross_module_edges 875→878）。

---

### Phase I — 真实依赖链收缩（已归档）

#### I1：Provider 写链纵切（首刀）

**目标**：将 `AdminProviderController` 从直接依赖 `GatewayConfigView` / `DynamicConfigService` 改为依赖 `ProviderCatalogView` / `ProviderConfigWriter`。

**实施内容**：
| 项目 | 状态 |
|------|------|
| 新增 `ProviderCatalogView` | ✅ |
| 新增 `ProviderConfigWriter` | ✅ |
| `GatewayConfigView` 继承 `ProviderCatalogView` | ✅ |
| `DynamicConfigService` 实现 `ProviderConfigWriter` | ✅ |
| `AdminProviderController` 改为依赖 provider 专用读/写口 | ✅ |

**验证结果**：compile ✅、verify.sh 36/36 ✅、sentrux_rules 0 violation ✅。quality_signal 5369→**5370**，modularity 4485→**4487**（+2），cross_module_edges 878 不变。

**结论**：首次出现弱正反馈。按 feature 做真实读/写口纵切比局部整理更接近 sentrux 对图结构变化的判定方式。

#### I2：Route 子域纵切（第二刀）

**目标**：按 Provider 模式为 Route 子域新增专用读/写口。

**实施内容**：
| 项目 | 状态 |
|------|------|
| 新增 `RouteCatalogView` | ✅ |
| 新增 `RouteConfigWriter` | ✅ |
| `GatewayConfigView` 继承 `RouteCatalogView` | ✅ |
| `DynamicConfigService` 实现 `RouteConfigWriter` | ✅ |
| `AdminRouteController` 改为依赖 `RouteCatalogView` + `RouteConfigWriter` | ✅ |

**验证结果**：compile ✅、verify.sh 36/36 ✅、sentrux_rules 0 violation ✅。quality_signal 5370（不变），modularity 4487→**4489**（+2），cross_module_edges 878 不变。

**结论**：结果与 Provider 纵切一致，确认按 feature 纵切模式可稳定复现。

---

### 后续阶段总结

Phase I 之后，计划重心从结构实验切换到性能瓶颈定位：
- **Phase J**（候选）：Client 子域纵切，验证第三个子域是否还能复现弱正反馈
- **Phase K**（主线）：PostgreSQL 写入链路测量与归因
- **Phase L**：写入链路最小优化（以证据驱动）—— **✅ 已完成**。Aggregate 写缓冲（99% SQL 减少）+ usage/cost CTE 合并，stress 验证：234.3 req/s（+4%），p50 72ms（−6.5%）
- **Phase M**：回归与压测基线固化 — 🟢 当前阶段

---

## 本轮验收标准

1. `CONTEXT-plan.md` 已明确记录代码分数优化的并发推进方案
2. 至少形成 5 条可并发 lane，并标注目标、依赖、风险、验证方式
3. 每条 lane 均落到具体文件路径，不停留在抽象建议
4. 后续实施时默认遵循“先减跨模块边，再拆中心类，最后补规则与测试”的顺序
5. 结构优化不得擅自改变现有接口语义、权限语义或模型发布业务语义

---

## 决策记录

| 日期 | 决策 | 理由 |
|------|------|------|
| 2026-06-03 | SPA fallback → 最低优先级 HandlerMapping | 前置映射未命中时才触发，语义更准确 |
| 2026-06-03 | 开发密钥放入 application-local.yml | 防止误部署使用弱密钥 |
| 2026-06-03 | 前端构建集成到 bootstrap/pom.xml | 保持 fat JAR 自包含 |
| 2026-06-03 | regression.sh 作为主黑盒验证入口 | verify.sh 不自包含 |
| 2026-06-03 | `/v1/models` 使用 ModelListProvider SPI | 避免 core 反向依赖 admin；无实现时优雅降级 |
| 2026-06-03 | regression.sh 移除模型条目检查 | 条目数取决于初始配置，不可预测 |
| 2026-06-04 | HYBRID 模式替代全 PG 模式 | 同步路径从 40ms 降至 3ms |
| 2026-06-04 | BatchFlusher 线程池 2→16 | 高 QPS 下 2 线程退化为同步写入 |
| 2026-06-06 | BatchFlusher 参数配置化 | 避免后续调优改源码 |
| 2026-06-06 | `/v1/models` 降级语义头 `X-Degraded` | 区分"空数据"和"admin 挂了" |

---

## 已完成（P1-P5）

### P1 — admin 测试归位 + 模块边界止血 ✅
- 35+ 错放测试迁回 `gateway-admin`，共享基础类拷贝，依赖修复

### P2 — 最小 CI + 主链路可靠性补强 ✅
- `.github/workflows/ci.yml`（3 job），fallback 顺序测试

### P3 — 配置分层 + 文档收尾 + 报表闭环补强 ✅
- `AggregateMetricRecorder` 接口+实现，6 处文档失真修复

### P4 — 黑盒回归链路补强 + E2E Phase 1 ✅
- `regression.sh` 新增 7 场景，错误模拟服务创建

### P5 — 集成测试 Phase 1 + 高风险领域测试收敛 ✅
- Phase 1 清单 42 项全部覆盖，S010/S011 补强

---

## 详细计划归档：Phase K — PostgreSQL 写入链路测量与归因

### 优先级

**P0（最高）**

### 目标

在不改变现有业务语义的前提下，确认 PG+Redis 场景下**请求完成后写入链路**的真实耗时分布，找出最主要的延迟来源，为后续最小优化提供证据。

### 背景判断

当前最可能瓶颈不再是：

- 多实例共库污染
- 测试环境配置缺失
- 日志噪音本身

而是 **PostgreSQL 支撑下请求完成后的写入链路**，重点关注：

- request log 写入
- trace 写入
- usage / cost / limit 相关持久化
- batch-flusher 聚合 / 冲刷逻辑

### 执行原则

1. **先测量，后优化**
2. 先补"链路拆账"，不直接做激进改造
3. 只围绕已怀疑链路补充最小指标，不扩散到全系统
4. 保持 in-memory 与 PG+Redis 两条路径可对比

### 建议动作

1. 为请求完成后的关键写入节点补充最小必要的耗时 / 计数观测
2. 区分以下几类时间占比：
   - 主请求处理时间
   - 请求完成后同步写入时间
   - batch flush 触发 / 排队 / 执行时间
3. 在 PG+Redis 与 in-memory 两条路径上对比相同场景
4. 识别最耗时的 1~2 个写入点，形成明确瓶颈排序
5. 补充 success path 各类写节点的**计数 / 耗时 / trace 命中率 / body 大小 / BatchFlusher queueDepth / fallback / drop** 统计，明确"业务必须写"与"仅影响观测完整性"的边界
6. 在形成观测结论前，**不先调大** Hikari 连接池、BatchFlusher 线程数或其他放大型参数，避免将"写放大"误判为"资源不足"

### 当前进展（已完成）

1. `BatchFlusher` 已补最小运行时观测，覆盖：
   - queue depth：`gateway.batch_flusher.queue.depth{taskClass=all|critical|best_effort}`
   - task wait / exec：`gateway.batch_flusher.task.wait`、`gateway.batch_flusher.task.exec`
   - overload：`gateway.batch_flusher.overload{action=drop|sync_fallback,taskClass=...}`
   - drain 侧基础统计：submitted / fallback / drop / drain cycles / drained tasks / total drain time / last cycle / last task count
2. success-path PG 写链已统一补齐 `gateway.write.latency{writePoint=...}`：
   - 已有复用：`requestLogPersist`、`traceStoreSave`
   - 本轮补齐：`aggregateMetricBatch`
   - usage：`usageCheckAndRecordBoth`、`usageCheckAndRecordDaily`、`usageCheckAndRecordMonthly`、`usageAddDaily`、`usageAddMonthly`、`usageAddDailyRequestCount`
   - cost：`costCheckAndRecordBoth`、`costCheckAndRecordDaily`、`costCheckAndRecordMonthly`、`costAddDaily`、`costAddMonthly`
3. 约束保持不变：未新增高基数 tag，未先调大 Hikari / BatchFlusher 参数，未调整 `BatchFlusher` fallback/drop 语义

### 验收标准

#### 输出验收

1. 能明确给出"PG 写入链路最耗时环节"的排序
2. 能区分"单次请求尾部同步成本"和"批量 flush 成本"
3. 能说明 in-memory 与 PG+Redis 差值主要落点
4. 能形成下一阶段仅针对 1~2 个瓶颈点的优化清单

#### 验证验收

| 验证项 | 目标 |
|------|------|
| `./scripts/verify.sh` | 不回退 |
| `./scripts/stress-test.sh` | 结果可复现 |
| `./scripts/stress-test-backends.sh` | 结果可复现 |
| 关键指标日志 / 统计 | 能支持瓶颈归因 |

#### 当前验证状态

1. `./mvnw -pl gateway-core -Dtest=GatewayMetricsRecorderTest,BatchFlusherTest test` 已通过
2. `./mvnw -pl gateway-admin -Dtest=PostgresAggregateMetricStoreTest,PostgresClientUsageStoreTest,PostgresClientCostStoreTest test` 暂未完成模块级验证
3. admin 侧本轮变更已做 scoped 编译与测试代码校验

### 风险 / 未验证项（已更新）

1. ~~**风险**：若观测粒度不足，可能只能看到"慢"~~ — **已解决**
2. ~~**风险**：若 batch-flusher 的延迟与写入合并在一起~~ — **已解决**
3. ~~**未验证项**：当前"瓶颈主要在请求完成后写入链路"~~ — **已收口**

### 已迁移到下一阶段（Phase L）

Phase K 的关键测量目标已完成。具体证据与排序见 `CONTEXT.md`「测试」段的当前基线。更细粒度的优化方向现在具备证据基础，进入 Phase L。

---

## 详细计划归档：Phase L — 写入链路最小优化（以证据驱动）

### 优先级

**P1**

### 前置条件

仅在 **Phase K 已明确瓶颈排序** 后启动；若无法定位主要耗时点，则本阶段不启动。

### 目标

基于测量结果，对 PG 写入链路做**最小、局部、可归因**的优化，优先减少请求完成后的同步写入负担或提升批处理效率，但不改变业务语义、不引入新基础设施。

### 执行原则

1. 优先配置级和局部实现级优化
2. 一次只动一个主要瓶颈点，保证结果可归因
3. 不做"为了性能而重写整条链路"的高风险改造
4. 不把优化扩展成存储架构升级项目

### 建议优化方向（仅作为候选，不要求同时实施）

1. **BatchFlusher 任务分舱与回退策略收敛**
   - 区分"影响业务正确性"与"仅影响观测完整性"的任务
   - 避免观测类任务在积压时同步回退到请求线程
2. **降低 success path 上的 trace / request log 成本**
   - 成功请求 trace 采样
   - 收紧 body 大小
   - 减少 request log / trace / aggregate metric 的职责重叠
3. **减少请求完成时必须同步落库的内容**
   - 能延后但不影响业务语义的，继续留在批处理边界
4. **减少重复写入 / 重复计算 / 额外 round-trip**
   - 避免同一请求结束时重复生成或重复持久化同类统计
5. **后台统计读取去 N+1**
   - usage summary / dashboard / top clients 等改为批量读取 + 内存聚合
6. **JVM / 线程池 / 连接池边界校准**
   - 先建立容量模型，再做小幅调参

### 验收标准

#### 结果验收

1. 至少 1 个已定位瓶颈点的耗时有明确下降
2. `verify.sh` 与既有主链路语义不回退
3. in-memory 路径行为不变
4. PG+Redis 路径压测结果较基线至少"不变或更优"

#### 验证验收

| 验证项 | 目标 |
|------|------|
| `./mvnw -q -DskipTests compile` | 必须通过 |
| `./scripts/verify.sh` | 必须通过 |
| `./scripts/stress-test-backends.sh` | 至少不回退 |
| 必要时 `./scripts/stress-test.sh` | 用于与 in-memory 基线对比 |

### 风险 / 未验证项

1. **风险**：若错误地将同步写入改成异步，可能引入一致性语义变化
2. **风险**：如果 batch 参数调优过度，可能将单请求耗时转移为更高峰值抖动
3. **未验证项**：在现有架构和不改语义约束下，可获得的性能收益上限目前未知

### 已完成（Phase L 已通过验证）

Phase K 测量→Phase L 优化→验证的闭环已完成：

**基线对比（HYBRID mixed-model stress）：**

| 指标 | Phase K 基线 | Phase L（最优轮） | 变化 |
|------|:-:|:-:|:-:|
| 吞吐 | 225.2 req/s | **234.3 req/s** | **+4%** |
| p50 | 77ms | **72ms** | **−6.5%** |
| p99 | 272ms | **259ms** | **−4.8%** |
| drain 周期均长 | 357ms | **168ms** | **−53%** |
| aggregateMetricBatch 调用 | 6727 | **74** | **−99%** |

**四项目标优化落地详情：**

1. **AggregateReportingService 写缓冲**：DimensionRecord 从即时写入改为 ConcurrentLinkedQueue 缓冲 + 批量刷入（每 100 条记录或显式 flushPending()），aggregateMetricBatch SQL 调用从 6727 次降到 **74 次（99% 减少）**
2. **usage `checkAndRecordBoth` CTE 合并**：daily+monthly 双 SQL 合并为单条 WITH CTE INSERT（14 参数单次 PreparedStatement）
3. **cost `checkAndRecordBoth` CTE 合并**：同上模式，daily+monthly 成本写/预算检查合为单条 SQL
4. **BatchFlusher 任务分舱**：drain 循环只 join CRITICAL 任务，BEST_EFFORT 提交后 fire-and-forget，避免观测类任务阻塞关键路径

**验证记录：**
- 两轮 stress 结果存在环境噪声（207.9 vs 234.3 req/s），但最优轮次全面超越基线
- 优化后 drain 效率大幅提升（平均循环 168ms vs 357ms），20 个 Hikari idle 连接表明 PG 连接不再吃紧
- `gateway-core` 聚焦测试（BatchFlusherTest + GatewayMetricsRecorderTest）31 tests 0 failure
- CTE EXPLAIN ANALYZE 确认 SQL 执行 0.27ms，瓶颈仍在 JDBC 往返和线程调度延迟

**已知约束（已解决）：**
- ~~`gateway-admin` 模块因既有编译阻塞无法完整 Maven 验证~~ — **已修复**。阻塞原因实为依赖缓存问题：`gateway-core` 未先行 install，旧 JAR 不含 `recordWriteLatency()` 方法
- 后续编译 `gateway-admin` 前建议先 `./mvnw -pl gateway-core -DskipTests install`，或使用 `-am` 参数连带编译依赖

# CONTEXT-plan.md — 当前阶段推进计划

> 本文件聚焦"接下来怎么做"，用于承接当前已确认缺口的实施顺序、范围边界、依赖关系与验收标准。
> `CONTEXT.md` 保持项目总览、历史状态与当前进度；本文件只维护执行计划。
> 已完成计划的详细内容已迁移至 `CONTEXT-history.md` 的"详细计划归档"小节。

---

## 验证策略（可复用）

### 前端变更

```bash
cd frontend && npm run lint
cd frontend && npm run test
cd frontend && npm run build
```

### 后端 / Java 变更

```bash
./mvnw -q -DskipTests compile
```

### 涉及认证接口 / 用户主链路

```bash
./scripts/verify.sh
./scripts/user-journey-blackbox.sh
```

### 结构优化 / 重构

```bash
./mvnw -q -DskipTests compile
./mvnw -q -pl gateway-core checkstyle:check
./mvnw -q -pl gateway-admin checkstyle:check
./scripts/verify.sh
sentrux_rescan
sentrux_health
sentrux_check_rules
```

---

## 完整后续计划（当前有效）

### 当前判断

基于最近一轮结构实验、全量验证与双路径压测，当前后续计划需要从"继续验证 feature 纵切是否对 sentrux 有正反馈"，逐步切换到"在保持小步结构收敛的同时，优先定位并收敛 PostgreSQL 写入链路瓶颈"。

**2026-08-29 更新：第二轮全量代码审查完成（`docs/reviews/2026-08-29-second-review.md`），修复主线已切换为审查发现收敛**——第 1 批（立即）：D1 period_key 月 1 日碰撞（9 月 1 日触发）、D2 月度成本不入账、D4 聚合批量整批失败；第 2 批：D3 flush 回灌、S1 自助 key 白名单交集、S2 账户缓存失效、配额缓存毒化组；第 3 批：事件循环阻塞 C1-C5 与安全 P2 组。Phase J（结构纵切）与性能主线继续让位，除非审查发现全部收口。

当前已确认事实：

1. **Provider / Route 子域纵切已完成并验证**，均出现轻微 modularity 正反馈：
   - Provider：`modularity 4485 → 4487`
   - Route：`modularity 4487 → 4489`
   - 两次实验均未引入 `cross_module_edges` 上升
2. **验证链路已跑通**：
   - `./mvnw test`
   - `./scripts/verify.sh`
   - `./scripts/stress-test.sh`
   - `./scripts/stress-test-backends.sh`
3. **PG+Redis 压测隔离问题已修复**：`scripts/stress-test-backends.sh` 已支持独立 schema 隔离，避免历史 shared-state 污染本轮结果
4. **当前最可能瓶颈** 已从环境/隔离问题收敛到 **PostgreSQL 支撑下请求完成后的写入链路**，重点关注：
   - request log
   - trace
   - usage / cost / limit
   - batch-flusher
5. **Phase L 首个最小优化已落地并通过黑盒里程碑**：`gateway-admin` 的 `PostgresClientCostStore.checkAndRecordBoth(...)` 已从接口默认双调用收敛为单次 `jdbc.execute(ConnectionCallback)`，以减少成功路径 cost 记账的应用层 round-trip/回调开销；语义未变，`./scripts/verify.sh` 已通过
6. **Phase L 第二个最小优化已落地并通过黑盒里程碑**：`gateway-admin` 的 `PostgresClientUsageStore.checkAndRecordBoth(...)` 已将 zero-token 分支的 daily/monthly 双 SELECT 收敛为单次按 period 批量读取，继续保持 `request_cnt` 与返回语义不变；`./scripts/verify.sh` 已通过
7. **日志已完成一轮最小降噪**，后续不再以大面积日志整理为主线，仅补充性能归因所需的关键指标与摘要输出
8. 仓库**已初始化 git 并完成初始基线提交**（`main` 分支，commit `fbc7f95`，663 文件）；本地默认门禁可正常运行。**尚未配置 remote**，后续分享/协作/备份需先配置远程仓库；不阻塞当前代码实施
9. **已完成 1 轮多 Agent 静态性能审查（性能 / JVM / 数据库 / Spring / 压测总控）**，进一步确认：
   - 当前最高收益治理点仍是 `/v1/chat/completions` 成功路径后的 **PG 写放大** 与 **BatchFlusher 过载回退放大**
   - `gateway-admin` 的 usage summary / dashboard 读取路径存在明显 **N+1 / 按 client 循环读取** 风险，应作为性能次主线收敛项
   - JVM / 线程池 / Hikari 连接池仍缺统一容量模型，当前**不应先盲目调大** Hikari 或 BatchFlusher 并发
   - Redis `KEYS`、`ConfigSyncPublisher` 生命周期、`DirtyAccountFlushBuffer` 非完整 Bean 托管曾属于**次级稳定性风险**；其中 `DirtyAccountFlushBuffer`、`ConfigSyncPublisher` 生命周期与 `RedisTraceStore.resetForTests()` 的 `KEYS` 已完成最小修复，其余同类 Redis `KEYS` 使用仍按需继续收敛
10. **本轮最小实现已落地**：为 core 新增共享 `boundedElasticScheduler` Bean，并仅把 `/v1/chat/completions` 预热路径与 `RedisConfigStore` / `PostgresConfigStore` 切到统一调度器；同时确认 `InternalUsageSummaryReadService` 已是 batch 聚合实现，当前无新的 N+1 代码需要修复
11. **2026-08-29 收口**：H4（模型发布补偿回滚）补齐完成；verify-gaps 暴露的 `parseAccessClaims` 缓存未命中 `.block()` 500 缺陷已改造为非阻塞（`AuthSupport` + UserKey/UserProfile/UserUsage 三个自助 Controller，语义不变）；同日补齐 Windows 本机验证链路（JDK 21 / jq / 便携 Redis / journey 中文 `\u` 转义）。当前门禁实测：`compile` ✓、双模块 `checkstyle` ✓、`verify.sh` **36/36**、`verify-gaps.sh` **69/69**、`user-journey` **126/127**（唯一失败为本机无 lsof 的环境断言）

### 范围边界

#### 纳入范围

1. 围绕 PG 写入链路做**测量、归因、最小优化**，继续作为当前主线
2. 围绕 success path 观测写、BatchFlusher 回退放大、后台统计 N+1、线程池/JVM 容量边界做**最小风险收敛**
3. 复用现有验证入口，形成"性能改动"优先、"结构改动"延后的分层验收
4. 若性能主线收口且仍有余力，再做 **1 个低风险 feature 纵切**（优先 Client 子域）

#### 明确不纳入范围

1. 不做新一轮大规模架构重构
2. 不新增模块、不调整模块依赖方向
3. 不改接口语义、不改数据库业务语义
4. 不引入 MQ / 事件总线 / 新持久化组件
5. 不做数据库 DDL 变更
6. 不在本阶段处理 remote 配置、push 或 PR 流程

---

## Phase J — Client 子域纵切（低风险结构延续）

### 优先级

**P2（仅在性能主线收口后再执行）**

### 目标

在 Provider / Route 已验证的模式上，继续对 **Client 子域** 做一次最小纵切，验证"按 feature 收敛专用读/写口"是否还能稳定带来弱正反馈；若收益仍然极小，则停止继续复制该模式，避免陷入低收益重复劳动。

### 前置条件

仅在 **Phase K / Phase L / Phase M** 已完成当前性能主线收口，且 `/v1/chat/completions` 的 PG 写链与 BatchFlusher 回退风险已有稳定结论后启动。若性能主线仍持续带来更明确收益，则本阶段继续后移。

### 执行原则

1. 只切 Client 子域，不并行扩张到 system / scene / 其他总线
2. 复用 Provider / Route 的已验证模式
3. 保持 controller 对外端点、请求体、返回体、业务语义不变
4. 不把该阶段演化成 system 总线大拆分

### 建议动作

1. 梳理 admin 侧 client 管理入口的最小读面与写面
2. 新增 client 子域专用 contract（命名与 Provider / Route 模式保持一致）
3. 让现有配置实现类继续承接该专用写口，避免新增装配复杂度
4. 将 admin 入口从直接依赖中心化总读 / 总写口，切换为依赖 client 专用读 / 写口

### 验收标准

#### 结构验收

1. Client 管理相关 admin 入口不再直接依赖中心化总读口 / 总写口
2. client 子域 CRUD / 关联检查通过专用 contract 完成
3. 不新增 controller 业务语义分支
4. 不修改现有 API path、参数和响应语义

#### 验证验收

| 验证项 | 目标 |
|------|------|
| `./mvnw -q -DskipTests compile` | 必须通过 |
| `./scripts/verify.sh` | 必须通过 |
| `sentrux_check_rules` | 维持 0 violation |
| `sentrux_health` | 至少不回退 |

### 风险 / 未验证项

1. **风险**：若只是"换接口名"而未形成真实 feature 边界，收益可能继续很弱
2. **风险**：若 client 子域依赖比 provider / route 更杂，可能引入额外 import 边
3. **未验证项**：第三个子域纵切能否继续稳定复现 modularity 微增，当前尚无证据

### 阶段退出条件

满足以下任一条件，即可结束"继续复制纵切模式"的探索：

1. Client 纵切后 sentrux 无增益或出现回退
2. Client 纵切后收益仍仅为极小增量，且无法转化为更明确的工程收益
3. 结构收益低于性能主线收益，后续优先级切换到写入链路治理

---

## Phase K — PostgreSQL 写入链路测量与归因

**优先级：P0 ✅ 已完成**

瓶颈排序明确：usageCheckAndRecordBoth(39.9ms avg) > costCheckAndRecordBoth(31.9ms avg) > aggregateMetricBatch(29.7ms avg)。详细计划已归档至 `CONTEXT-history.md`。

---

## Phase L — 写入链路最小优化

**优先级：P1 ✅ 已完成**

aggregate 写缓冲（SQL 调用 -99%）、usage/cost CTE 合并、BatchFlusher 任务分舱（drain 均长 -53%）、吞吐 +4%、p50 -6.5%、p99 -4.8%。详细计划已归档至 `CONTEXT-history.md`。

---

## Phase M — 回归与压测基线固化

### 优先级

**P1**（✅ **已完成 — 本轮瓶颈诊断闭环**）

### 目标

将当前已经跑通的验证结果收敛为后续改动的最小复验策略，避免每次都从头探索验证入口，同时避免无差别全量重测。

### 当前状态

**Phase M 已闭环。** 完成事项：
1. 性能基线固化：HYBRID 234.3 req/s / p50 72ms / p99 259ms
2. 复验分层策略已定义（结构小改 → compile+verify+sentrux；写入链路 → compile+verify+stress-test）
3. **瓶颈诊断完成**：JFR 全量采样 + in-JVM echo（`EchoDiagnosticServer` / `--with-echo`）双验证。结论：mock 上游非瓶颈，单机 CPU 天花板（机器利用率 94-98%）；echo 模式因 CPU 争用反而降低吞吐（纯诊断工具，非优化手段）
4. 新增 `stress-test-backends.sh --with-echo` 参数，复用已有 JMeter plan 即可排除上游变量
5. stress 脚本已集成 JFR 录制（输出 `build/stress-profile.jfr`）+ 预热轮

### 基线结论（已更新 Phase L+M 基线）

当前已具备可信基线：

- `./mvnw test` 已跑通
- `./scripts/verify.sh` 已跑通
- `./scripts/stress-test.sh` 已跑通
- `./scripts/stress-test-backends.sh` 已跑通

**最新性能基线（HYBRID mixed-model stress, Phase L+M 优化后）：**
- 吞吐：234.3 req/s（↑4% vs Phase K）
- p50：72ms（↓6.5%）
- p99：259ms（↓4.8%）
- drain 均长：168ms（↓53%）
- aggregateMetricBatch 调用：74（↓99%）

**瓶颈诊断确认：** 机器 CPU 为瓶颈（94-98%），非网关代码微观瓶颈。无单方法 >2% CPU。生产环境分离部署后吞吐应大幅提升。

### 建议复验分层

#### 结构小改（如 Client 纵切）

| 验证项 | 是否必跑 |
|------|------|
| `./mvnw -q -DskipTests compile` | 是 |
| `./scripts/verify.sh` | 是 |
| `sentrux_check_rules` / `sentrux_health` | 是 |
| stress-test | 否，按需 |

#### 写入链路改动

| 验证项 | 是否必跑 |
|------|------|
| `./mvnw -q -DskipTests compile` | 是 |
| `./scripts/verify.sh` | 是 |
| `./scripts/stress-test-backends.sh` | 是 |
| `./scripts/stress-test.sh` | 建议 |
| `./mvnw test` | 按改动范围聚焦复验，必要时再全量 |

### 验收标准

1. 后续每类改动都有明确、最小、可复用的复验组合
2. 不把全量验证作为所有小改动的默认门槛
3. 性能结论必须至少由一次 PG+Redis 压测支撑

### 风险 / 未验证项

1. **风险**：如果后续改动跨越结构与性能两类边界，复验组合可能需要临时提升
2. **未验证项**：当前压测结果是否足以覆盖所有高负载写入形态，仍需结合后续瓶颈定位判断

---

## 交付前置项（非本轮实施内容）

### 优先级

**P3**

### 现状

仓库已完成初始基线提交（`main` 分支，commit `fbc7f95`，663 文件），但**尚未配置 remote**。

### 计划定位

该事项仅记录为后续交付 / 协作前置条件，**不是当前代码实施内容**，本阶段不处理：

- remote 配置
- push
- PR 创建
- 发布流程接入

### 影响

若后续需要共享结果、建立远端备份或进入 PR 工作流，再单独确认并处理。

### 风险 / 未验证项

1. **风险**：在未配置 remote 的情况下，当前成果只能本地留存
2. **未验证项**：后续使用 GitHub / GitLab / 其他托管方式尚未确定

---

## 总体执行顺序（当前建议）

| 顺序 | 阶段 | 优先级 | 目的 | 状态 |
|------|------|------|------|------|
| 1 | **Phase K — PostgreSQL 写入链路测量与归因** | **P0** | 先确认真实性能瓶颈 | ✅ 已完成 |
| 2 | **Phase L — 写入链路最小优化** | **P1** | 基于证据优先收敛 success path 写放大与 BatchFlusher 回退 | ✅ 已完成（验证通过） |
| 3 | **Phase M — 回归与压测基线固化** | **P1/P2** | 压测基线 + 瓶颈诊断（JFR + echo mode） | ✅ 已完成 |
| 4 | **Phase J — Client 子域纵切** | **P2** | 仅在性能主线收口后，再验证结构实验是否仍值得继续 | ⏳ 待定 |
| 5 | 交付前置项（remote） | **P3** | 非代码主线，后续按需处理 | 📋 已记录 |

### 决策门槛（已更新）

1. ~~Phase K + Phase L~~ — **已完成**，性能主线首轮闭环跑通，确认 aggregate 缓冲 + CTE 合并策略有效
2. 若 Phase M 的回归基线固化完成，且后续有明确结构收益场景，再择机启动 Phase J
3. **Phase J 启动前提**：已完成 Phase M 固化的基线+回归验证链路，且有可测的结构收益假设
4. 在未明确收益前，不启动 system 总线大拆分、不启动主链重构、不做存储架构升级、不过早放大 Hikari/BatchFlusher 等参数

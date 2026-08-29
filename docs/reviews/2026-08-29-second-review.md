# 第二轮全量代码审查报告（2026-08-29）

> 审查方式：4 个并行专项审查（本轮改动深审 / 并发与响应式 / 安全 / 数据与存储一致性），全部发现经人工复核代码证据后定级。
> 范围：gateway-core / gateway-admin / bootstrap 后端代码。上一轮全量审查为 2026-06-06（30 个问题）。
> 结论：**P0 = 0，P1 = 11，P2 = 15，P3 ≈ 20**。高严重度发现集中在 **PG 计费正确性**、**事件循环阻塞**、**多节点缓存一致性** 三个区域。

---

## P1（11 项，全部经人工复核确认）

### 数据与计费正确性（5 项）

| # | 标题 | 证据 | 触发条件与影响 |
|---|------|------|----------------|
| D1 | **period_key 每月 1 日碰撞：daily 与 monthly 落同一行** | `RedisStoreUtils.java:37-49`（dayKey 与 monthKey 在每月 1 日产出相同字符串）；`PostgresClientUsageStore.java:44-67`（CTE 两分支写同一表同键）；cost 同构 | PG 后端每月 1 日（UTC）：`checkAndRecordBoth` 两 CTE 冲突目标相同 → PG 21000 整条失败（当日记账全失败）；缓冲 flush 路径 daily+monthly 两次 UPSERT 叠加同一行 → 双倍入账。**下次触发 2026-09-01**。修复：period_key 加周期类型前缀（如 `d:`/`m:`）+ 迁移清洗 |
| D2 | **无 daily 预算时月度成本永不入账，月度预算失效** | `ClientBudgetService.java:101-106`（dailyBudget=MAX 提前 return，跳过唯一月度入账路径 `checkAndRecordBoth`；`addMonthlyCost` 无生产调用点） | 只配月度成本预算的客户端 monthly cost 恒为 0 → 月度预算永不拦截、报表恒 0（资损）。修复：去掉短路，统一走 `checkAndRecordBoth` |
| D3 | **Buffered 写缓冲 flush 失败静默丢账** | `BufferedStoreHelper.java:44-69`（先 drain 后 batchUpdate，异常仅 warn，不回灌不重试）；5s 调度 | PG 短暂故障超过一个 flush 周期 → 已确认成功的请求 tokens/cost 永久丢失；重启后从 PG 重新播种 → 配额被"退还"（资损）。修复：失败批次回排队首 + 告警 |
| D4 | **聚合报表批量 INSERT 同键重复行整批失败** | `PostgresAggregateMetricStore.recordAll:47-90`（多行 VALUES 单语句，语句内不按冲突键聚合）；异常被 best-effort catch 吞 | PG 模式同一 flush 批次内 ≥2 条请求共享任一维度值（常态）→ 21000 整批失败 → 仪表盘数据系统性缺失。修复：flush 前按冲突键聚合 |
| D5 | **删除用户与 in-flight flush 竞态复活（R4 残留窗口）** | `DirtyAccountFlushBuffer.java:90-105`（load→save 无存在性复查）；`UserAccountService.java:396-424` | flush 的 load 落在 delete 前、save 落在 delete 后 → 已删用户连同 API key 写回 store，重启复活。毫秒级窗口、低概率，但后果严重。修复：save 前复查存在性/条件写（安全专项同报此条，定级 P3，综合定为 P2 亦可） |

### 事件循环阻塞（1 项热路径 + 4 项同类，共 5 处）

| # | 标题 | 证据 |
|---|------|------|
| C1 | **每条 chat 请求在事件循环上做阻塞 Redis/JDBC 路由韧性回写**（热路径） | `UpstreamChatClient.java:98-107/131-141`（doOnSuccess/doOnError → `RedisRouteStateStore.recordSuccess` 2 次阻塞 DELETE / `recordRetryableFailure` 5 次阻塞调用；`PostgresRouteStateStore` JDBC）——redis/pg route-state 后端下每请求命中 |
| C2 | 登录限速同步 Redis 在事件循环 | `AuthLoginController.java:73` + `LoginRateLimiter.java:139-168` |
| C3 | Webhook 投递重试与结果落库（JPA）在事件循环 | `WebhookDispatcherService.java:77-89` |
| C4 | Provider 探活写回无 subscribeOn | `ProviderHealthScheduler.java:97-124` |
| C5 | admin/internal 同步 Controller 在事件循环做多路 JDBC/Redis 查询 | `AdminDashboardController.java:29-36`、`InternalSystemStatusController.java:39-90`、`InternalProviderStateController.java:37-45` 等 |

C1 触发慢 Redis 时会拖垮全部请求处理线程（已抽查复核属实）。统一修复方向：`Mono.fromCallable(...).subscribeOn(boundedElastic)` 或并入 BatchFlusher。

### 安全（2 项）

| # | 标题 | 证据 | 攻击场景 |
|---|------|------|----------|
| S1 | **自助 API key 的 allowedModels 不与账户/注册模板求交集，可绕过模型准入** | `UserKeyController.java:39-58/74-86`（直接透传）；`UserApiKeyService.java:65-77`（仅规范化）；`ClientAuthService.authorizeModel:137-143`（key 白名单非空即跳过 client 级检查） | restricted 注册用户被模板限定 gpt-4o-mini，但可自助创建 `allowedModels=[任意模型]` 的 key 调用任何已配置路由的昂贵模型（模型准入约束完全失效，成本边界被绕过）。修复：自助路径与账户 allowedModels 求交集，仅允许子集收紧 |
| S2 | **账户缓存无跨节点失效：冻结/删除/改密在其它节点不生效** | `UserAccountCacheIndex.java:14`（无 TTL）；`ConfigSyncPublisher.java:96-117`（对账只含 providers/routes/scenes/clients/system，**不含 users**） | 多节点部署下节点 A 冻结/删除账户后，节点 B 的缓存永久保留旧账户：JWT validateJwtAccount 用陈旧 tokenVersion 通过、API key 分支 frozen=false → 被处置账户在 B 上无限期可用。修复：accountCache 短 TTL 或订阅 users 变更广播 |

## P2（15 项，摘要）

**配额/计费语义（数据专项）**
- P2-1 monthly 拒绝时 Buffered store 连带回滚 daily 并返回 (-1,-1) 毒化缓存（其余后端保留 daily），与默认实现语义分裂（`BufferedClientUsageStore.java:123-136`）
- P2-2 记账被拒后 pre-route 检查缓存被 `put(0)` 毒化 → 超限客户端继续打上游且无账目（`ClientQuotaService.java:92-93,109-110`、`ClientBudgetService.java:121-124`）
- P2-3 `PostgresClientTpmStore.reserve` INSERT 路径无限额守卫，首请求可越过整个 TPM 限额（`:45-54`）；adjust 负 delta 落新分钟 key 时写负数行（`:58-66`）
- P2-4 `markApiKeyUsed` 与 deleteUser 并发把已删账户放回 accountCache；登录路径缓存命中无 deleted 校验（`UserAccountService.java:553-565`）
- P2-5 Redis 聚合 bucket 到期即清空，历史 day/month 查询为空，与 PG/InMemory 保留语义背离（`RedisAggregateMetricStore.java:41-50`）

**并发/响应式**
- P2-6 Buffered store 阈值 flush 在调用线程执行阻塞 JDBC；BatchFlusher sync_fallback 时落在事件循环（`BufferedClientUsageStore.java:139-143`、`BatchFlusher.java:124-129`）
- P2-7 `InMemoryClientTpmStore` 每 client×分钟 entry 永不清理（无界内存）
- P2-8 mutator 家族（Provider/Scene/Client/System）currentValue 装配期快照 + 链尾 publish 阻塞 Redis（同 `RouteConfigMutator` 已知模式，4 处复制）

**发布补偿回滚（本轮新增代码的已知边界，深审发现）**
- P2-9 并发发布同一 alias 时，失败方的补偿回滚会用旧快照覆盖并发方的成功写入（底层共享状态无串行化）
- P2-10 客户端断连取消订阅不触发回滚（cancel 不走 onError 信号）——最现实的超时场景下半发布态仍存在；需补 `doOnCancel → rollBack`

**安全**
- P2-11 Redis 后端 logout 完全无效（logout 与 consumeOnce 写不同键，refresh 不查 logout 键；PG/内存后端语义正确）
- P2-12 refresh token 未绑定 tokenVersion：改密/重置后旧 refresh token 仍可续期 12-24h
- P2-13 登录限速仅按用户名无 IP 维度：credential stuffing 不受限 + 单用户名 10 次失败可定向锁号
- P2-14 webhook / models-dev URL 完全不走 BaseUrlValidator（admin→内网 SSRF 通道）
- P2-15 `block-internal-urls` 出厂默认 false 覆盖代码默认 true + BaseUrlValidator 缺 0.0.0.0/8 与 100.64/10 + DNS 校验为保存时一次性（TOCTOU）——三个 SSRF 相关缺口合并记录

## P3（约 20 项，简列）

Redis 计数器孤儿 key、三后端限流计数语义差异（被拒请求是否占额度）、InMemory 各 store 无淘汰/PG 热路径表无清理任务、Redis key 前缀可被 clientId 碰撞、Buffered pendingSize 只增不减、`UserAccountService` 的 @Transactional 实际无效（非 Bean 代理 + 自调用）、PG trace upsert 不清残留错误字段、refresh 黑名单 key 截断 8 字节、RouteStateStore 裸读改写、聚合去重 >5min 重放重复计数、API key 比较非常量时间、pepper 默认值形同虚设、upstream_error 消息透传内网拓扑、perClientCounters/InMemoryRateLimiter 慢泄漏、`DynamicConfigService.importConfig` 原地改共享 Map（潜伏）、ProviderHealth 跨 tick 重叠、单线程 @Scheduled 串行头部阻塞、回滚审计噪音（半发布态版本固化进历史）、"持久化成功但内存应用失败"不登记补偿、回滚单步失败续跑缺测试用例。

## 干净区域（明确核查无误）

- namespace 隔离（R3）：45 条 SQL 全部带过滤，与 V13/V14 一致
- CTE 本体原子性与 UTC 时区全链路一致（D1 的碰撞在键方案，非 CTE 逻辑）
- JWT：算法固定/过期/refresh 不可用作认证/tokenVersion 校验全部正确
- refresh token 三后端消费原子性成立（但 Redis 键位分裂见 P2-11）
- /internal/** 与 /admin/** 权限边界逐 controller 核对无遗漏；自助端点无横向越权
- SQL 全参数化、Redis key 无注入面、导入脱敏正确、日志/审计无明文密钥
- H1 API Key 双层存储实现正确（pepper 默认值问题单独列 P3）
- 本轮 H4/parseAccessClaims 改造经逐路径验证语义正确（边界见 P2-9/P2-10）
- CORS、错误响应、WebClient 重定向均安全

## 修复排期建议

1. **第 1 批（立即，9 月 1 日前）**：D1 period_key 前缀 + 迁移清洗、D2 去掉 recordCost 短路、D4 聚合 flush 前聚合——三个都是小改动，赶在下次月 1 日触发前
2. **第 2 批**：D3 flush 回灌重试、S1 自助 key 白名单求交集、S2 账户缓存失效机制、P2-1/P2-2 配额缓存毒化、P2-3 TPM 守卫、D5 删除竞态条件写
3. **第 3 批**：C1-C5 事件循环阻塞统一改造、安全 P2 组（P2-11~15）、P2-9/P2-10 发布回滚边界
4. **第 4 批（择机）**：P3 长尾 + 跨后端保留语义文档化

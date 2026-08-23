# 后端端点黑盒覆盖矩阵

> 本文是**仓库内维护的后端端点黑盒覆盖矩阵**，用于回答“哪些已实现端点已被脚本覆盖、覆盖强度如何、哪些地方仍需确认”。
> - 使用流程与操作示例请看 [`../usage.md`](../usage.md)
> - 稳定接口语义请看 [`../api-reference.md`](../api-reference.md)
> - 机器可消费契约请看 [`../openapi.json`](../openapi.json)
> - 当黑盒脚本或后端端点发生变化时，应同步更新本文，避免 coverage 认知漂移。

## 状态定义

| 状态 | 含义 |
|------|------|
| Covered | 已被一个以上黑盒脚本稳定命中，或已覆盖关键主链路与常见状态语义 |
| Weakly covered | 已有黑盒命中，但通常仅单脚本覆盖、偏成功路径，或只覆盖链路中的一部分 |
| Not implemented | 文档/猜测中可能出现，但当前 Java Controller 未实现 |
| Documentation drift / needs confirmation | 在文档 / OpenAPI 中可见，但当前代码映射未确认，不应直接当成“实现未覆盖” |

## 使用约定

- 以**资源族**为单位维护，不按原始 Controller 类逐个罗列。
- `Covered by scripts` 只写最能代表当前事实的脚本，不追求把每个命中点全部展开。
- 本文不是 OpenAPI 镜像；仅记录**高价值、常用、易漂移**的后端端点覆盖状态。

## Health

| Endpoint | Status | Covered by scripts | Notes |
|----------|--------|--------------------|-------|
| `GET /healthz` | Covered | `verify.sh`, `regression.sh` | 主健康检查，作为启动后基础可达性断言 |
| `GET /healthz/live` | Covered | `verify.sh`, `verify-gaps.sh`, `regression.sh` | liveness 被多脚本用于等待服务就绪，实跑信号最强 |
| `GET /healthz/ready` | Covered | `verify.sh`, `regression.sh` | readiness 已纳入主脚本健康检查 |

## Public Chat / Models

| Endpoint | Status | Covered by scripts | Notes |
|----------|--------|--------------------|-------|
| `POST /v1/chat/completions` | Covered | `verify.sh`, `regression.sh`, `verify-gaps.sh`, `user-journey-blackbox.sh` | 核心主链路；覆盖静态 key、JWT、fallback、限流/配额/预算、SSE、Anthropic 适配等；用户旅程脚本现已升级为强断言（`object/model/content/usage`）且支持 in_memory / PostgreSQL+Redis 双路径 |
| `GET /v1/models` | Covered | `verify.sh`, `regression.sh`, `user-journey-blackbox.sh` | 已被主冒烟与主回归覆盖；用户旅程脚本已从字符串包含升级为 `data[].id` JSON 语义断言 |

## Auth

| Endpoint | Status | Covered by scripts | Notes |
|----------|--------|--------------------|-------|
| `POST /auth/login` | Covered | `verify.sh`, `regression.sh`, `verify-gaps.sh` | admin 登录与普通用户流转均有覆盖 |
| `POST /auth/register` | Covered | `verify.sh`, `regression.sh`, `user-journey-blackbox.sh` | 用户自注册主链路稳定覆盖 |
| `POST /auth/refresh` | Covered | `verify-gaps.sh`, `regression.sh`, `regression-backends.sh` | 现已进入主回归与 PG/Redis 回归，覆盖 token 刷新与后续失效链路 |
| `POST /auth/logout` | Covered | `verify-gaps.sh`, `regression.sh`, `regression-backends.sh` | 现已进入主回归与 PG/Redis 回归，覆盖 logout 后 refresh token 吊销语义 |
| `GET /auth/me` | Covered | `regression.sh`, `user-journey-blackbox.sh` | 用户登录后直接验证身份与基本字段；管理员 reset-password 后用户闭环也已补到用户旅程脚本 |
| `PUT /auth/password` | Weakly covered | `verify-gaps.sh` | 已验证改密与新密码登录，仍主要是 focused 补点脚本 |
| `POST /auth/keys` | Covered | `verify-gaps.sh`, `regression.sh` | 个人 API key 生命周期入口之一 |
| `GET /auth/keys` | Covered | `verify-gaps.sh`, `regression.sh` | 与 patch/delete/rotate 联动验证列表变化 |
| `PATCH /auth/keys/{keyId}` | Covered | `verify-gaps.sh` | 已覆盖局部更新语义与启停效果 |
| `POST /auth/keys/{keyId}/rotate` | Covered | `verify-gaps.sh`, `regression.sh` | key 轮换已纳入生命周期验证 |
| `DELETE /auth/keys/{keyId}` | Covered | `verify-gaps.sh`, `regression.sh` | 删除后列表/使用路径均有回归 |
| `GET /auth/usage/recent` | Covered | `user-journey-blackbox.sh` | 已有真实用户旅程覆盖，且已增强为成功记录明细断言（model/status/usageTokens/promptTokens/completionTokens） |
| `GET /auth/usage/costs` | Covered | `user-journey-blackbox.sh` | 已有真实用户旅程覆盖，且已增强为当前模型聚合值断言（requests/tokens 聚合） |
| `GET /auth/usage/summary` | Not implemented | — | 当前支持面不包含该接口；文档层历史引用已按“未实现/不支持”收敛，避免再被误判为待补黑盒实现 |

## Admin Providers

| Endpoint | Status | Covered by scripts | Notes |
|----------|--------|--------------------|-------|
| `GET /admin/providers` | Covered | `verify-gaps.sh`, `regression.sh` | provider 列表稳定覆盖 |
| `PUT /admin/providers/{name}` | Covered | `verify.sh`, `verify-gaps.sh`, `regression.sh` | create/update 语义均覆盖 |
| `DELETE /admin/providers/{name}` | Covered | `verify-gaps.sh`, `regression.sh` | 删除与引用冲突场景在主回归链路中出现 |
| `POST /admin/providers/{name}/test` | Covered | `regression.sh` | 本轮新增补点，且已做 focused 实跑确认 |
| `GET /admin/providers/{name}/models` | Covered | `regression.sh` | 本轮新增补点，已做 focused 实跑确认 |
| `PUT /admin/providers/{name}/models` | Covered | `regression.sh` | 本轮新增补点，已验证 `204` 语义 |
| `POST /admin/providers/{name}/models/fetch` | Covered | `regression.sh` | 本轮新增补点，已验证返回 `models` 数组 |
| `GET /admin/providers/runtime` | Covered | `verify-supplement.sh` | 典型运维观测端点，脚本命中明确 |
| `GET /admin/providers/discovery` | Covered | `verify-supplement.sh` | 典型运维观测端点，当前主要由 supplement 覆盖 |

## Admin Routes

| Endpoint | Status | Covered by scripts | Notes |
|----------|--------|--------------------|-------|
| `GET /admin/routes` | Covered | `verify-gaps.sh`, `regression.sh` | 路由列表稳定覆盖 |
| `PUT /admin/routes/{id}` | Covered | `verify-gaps.sh`, `regression.sh` | create/update 都在主链路中反复出现 |
| `DELETE /admin/routes/{id}` | Covered | `verify-gaps.sh`, `regression.sh` | 删除及清理路径已有覆盖 |

## Admin Model Groups

| Endpoint | Status | Covered by scripts | Notes |
|----------|--------|--------------------|-------|
| `GET /admin/model-groups` | Covered | `verify-gaps.sh` | 列表结构已验证 |
| `PUT /admin/model-groups/{alias}` | Covered | `verify-gaps.sh` | create/update、空 members 4xx 都已覆盖 |
| `DELETE /admin/model-groups/{alias}` | Covered | `verify-gaps.sh` | 删除与不存在 404 已覆盖 |

## Admin Clients

| Endpoint | Status | Covered by scripts | Notes |
|----------|--------|--------------------|-------|
| `GET /admin/clients` | Covered | `verify.sh`, `regression.sh` | 客户端列表在冒烟与回归中都有断言 |
| `PUT /admin/clients/{key}` | Covered | `regression.sh`, `stress-test-backends.sh`, `regression-backends.sh` | 用于限流/压测前置，也覆盖 create/update 语义 |
| `DELETE /admin/clients/{key}` | Covered | `regression.sh`, `stress-test-backends.sh`, `regression-backends.sh` | 清理链路长期使用，稳定性较高 |

## Admin Users

| Endpoint | Status | Covered by scripts | Notes |
|----------|--------|--------------------|-------|
| `GET /admin/users` | Weakly covered | `verify.sh` | 当前更多是辅助确认，未像 provider/route 那样多脚本反复断言 |
| `POST /admin/users` | Covered | `regression.sh` | 本轮新增补点，已做 focused 实跑确认 |
| `PUT /admin/users/{username}` | Covered | `regression.sh` | 本轮新增补点，已做 focused 实跑确认 |
| `PUT /admin/users/{username}/limits` | Covered | `verify.sh`, `regression.sh`, `user-journey-blackbox.sh` | 限额/预算/TPM 主链路覆盖较强 |
| `DELETE /admin/users/{username}` | Weakly covered | `regression.sh` | 多用于测试清理与少量失败路径收口 |
| `POST /admin/users/{username}/reset-password` | Covered | `user-journey-blackbox.sh`, `regression.sh` | 现已覆盖 temporaryPassword 返回、旧密码失效、临时密码可登录 |
| `GET /admin/users/{username}/api-keys` | Weakly covered | `user-journey-blackbox.sh` | 当前仍以间接生命周期覆盖为主，未升级为显式列表状态强断言 |
| `POST /admin/users/{username}/api-keys` | Covered | `user-journey-blackbox.sh` | 管理员代管 key 生命周期已覆盖 |
| `PATCH /admin/users/{username}/api-keys/{keyId}` | Covered | `user-journey-blackbox.sh` | 已覆盖禁用/重新启用语义 |
| `DELETE /admin/users/{username}/api-keys/{keyId}` | Covered | `user-journey-blackbox.sh` | 已覆盖轮换后删除路径 |
| `PUT /admin/users/{username}/api-keys/{keyId}/toggle` | Covered | `regression.sh` | 与实际使用失败/恢复联动验证 |
| `POST /admin/users/{username}/api-keys/{keyId}/rotate` | Covered | `user-journey-blackbox.sh` | 已覆盖管理员代管 key 轮换 |
| `PUT /admin/users/{username}/allowed-models` | Covered | `verify.sh`, `regression.sh`, `user-journey-blackbox.sh` | 已覆盖热更新即时生效 |

## Admin Webhooks

| Endpoint | Status | Covered by scripts | Notes |
|----------|--------|--------------------|-------|
| `GET /admin/webhooks` | Covered | `verify-supplement.sh` | 列表与创建后回读已覆盖 |
| `POST /admin/webhooks` | Covered | `verify-supplement.sh` | 创建与后续 alert 投递联动覆盖 |
| `PUT /admin/webhooks/{id}` | Covered | `verify-supplement.sh` | 本轮新增补点，且已做 focused 实跑确认 |
| `DELETE /admin/webhooks/{id}` | Covered | `verify-supplement.sh` | 删除路径已覆盖 |
| `GET /admin/webhooks/deliveries` | Covered | `verify-supplement.sh` | 与 alert 触发链路一起验证 |
| `GET /admin/webhooks/{id}` | Covered | `verify-supplement.sh` | 已补单资源读取，并在创建/更新后回读关键字段 |

## Admin System / Config / Alerts

| Endpoint | Status | Covered by scripts | Notes |
|----------|--------|--------------------|-------|
| `GET /admin/alerts` | Covered | `verify-supplement.sh` | 同时承担 webhook 触发入口验证 |
| `GET /admin/requests/recent` | Covered | `verify.sh`, `regression.sh`, `user-journey-blackbox.sh` | 管理面请求日志主入口 |
| `GET /admin/config/export` | Covered | `verify.sh`, `verify-gaps.sh`, `regression.sh` | 导出链路稳定覆盖 |
| `POST /admin/config/import` | Covered | `verify-gaps.sh` | dry-run、正式导入、非法配置 4xx 都已覆盖 |
| `PUT /admin/system/limit` | Covered | `verify-supplement.sh` | 系统限流配置已覆盖 |
| `PUT /admin/system/resilience` | Weakly covered | `regression.sh` | 已有命中，但主要是配置写入成功路径 |
| `PUT /admin/system/pricing` | Covered | `regression.sh` | 定价配置与后续 cost/usage 链路联动较强 |
| `PUT /admin/system/operational` | Weakly covered | `regression.sh` | 目前以配置写入成功路径为主 |
| `PUT /admin/system/load-balancer` | Covered | `regression.sh` | 已用于 WRR 行为验证，不只是写配置 |
| `PUT /admin/system/concurrent-limit` | Covered | `verify-gaps.sh` | 含非法配置 4xx 断言 |
| `PUT /admin/system/tracing` | Weakly covered | `verify-gaps.sh` | 已有写入断言，行为面回归较少 |
| `PUT /admin/system/sync` | Weakly covered | `verify-gaps.sh` | 主要覆盖写入成功路径 |
| `PUT /admin/system/provider-health` | Weakly covered | `verify-gaps.sh` | 主要覆盖写入成功路径 |
| `PUT /admin/system/auth` | Covered | `verify-gaps.sh` | 与登录/改密流转联动验证，价值较高 |
| `POST /admin/sync/models-dev` | Weakly covered | `verify-gaps.sh` | 已补 200 与 `triggeredAt/completedAt/status/success` 结构断言，但仍主要为单脚本覆盖 |

## Internal endpoints

| Endpoint | Status | Covered by scripts | Notes |
|----------|--------|--------------------|-------|
| `GET /internal/usage/summary` | Covered | `regression.sh`, `regression-backends.sh`, `user-journey-blackbox.sh` | 多脚本使用，已是核心观测断言之一 |
| `GET /internal/cost/summary` | Covered | `regression.sh` | cost 汇总已纳入主回归 |
| `GET /internal/reporting/providers` | Covered | `regression.sh` | 报表端点已有明确断言 |
| `GET /internal/reporting/users` | Covered | `regression.sh` | 报表端点已有明确断言 |
| `GET /internal/reporting/keys` | Covered | `verify-gaps.sh` | 已补 internal 报表缺口 |
| `GET /internal/dashboard/overview` | Covered | `verify-gaps.sh` | 已补结构断言（如 `totalRequests`、`topModels`） |
| `GET /internal/system/status` | Covered | `verify-gaps.sh`, `regression.sh` | 已补嵌套布尔结构断言 |
| `GET /internal/requests/recent` | Covered | `verify-gaps.sh` | 已补过滤参数与核心字段断言 |
| `GET /internal/requests/{requestId}` | Covered | `regression.sh`, `regression-backends.sh` | 现已校验 requestId、status、model 与 trace 对齐，不再只是可达性断言 |
| `GET /internal/cost/by-model` | Weakly covered | `regression.sh` | 已命中，但当前更多是可达性/基础结构层 |
| `GET /internal/cost/client` | Weakly covered | `regression.sh` | 已命中，但主要为成功路径 |
| `GET /internal/config/snapshot` | Covered | `verify-gaps.sh` | 配置快照已补足 |
| `GET /internal/config/versions/{configType}/{configKey}` | Covered | `verify-gaps.sh` | 版本历史已覆盖 |
| `POST /internal/config/rollback/{configType}/{configKey}/{versionNumber}` | Covered | `verify-gaps.sh` | 已覆盖成功回滚与非法版本 4xx |
| `GET /internal/config/audit` | Covered | `verify-supplement.sh`, `regression.sh` | 配置审计稳定覆盖 |
| `GET /internal/config/audit-center` | Covered | `verify-supplement.sh` | supplement 专项覆盖 |
| `GET /internal/catalog/providers` | Covered | `verify-gaps.sh` | provider catalog 已补点 |
| `GET /internal/pricing/models` | Covered | `verify-gaps.sh` | pricing 快照已补点 |
| `GET /internal/snapshots/models-pricing` | Covered | `verify-gaps.sh` | 组合快照已补点 |
| `GET /internal/providers/runtime` | Covered | `verify-gaps.sh` | internal runtime 视角已覆盖 |
| `GET /internal/providers/discovery` | Covered | `verify-gaps.sh` | internal discovery 视角已覆盖 |

## Next supplement candidates

仅列当前**已实现且仍偏弱覆盖**、同时补一条脚本就能显著增益的端点：

1. `POST /admin/sync/models-dev` — 已补结构断言，但仍主要依赖单脚本，可继续补与配置状态联动的结果面验证。
2. `GET /admin/users/{username}/api-keys` — 用户旅程脚本已覆盖代管 key 生命周期，但仍缺显式列表状态校验（enabled/keyId 变化）。

## 维护建议

- 新增端点时：先决定是否属于“需要进入黑盒 stop condition 的高价值端点”，再补本文。
- 新增脚本断言时：优先把 `Covered by scripts` 更新为**最能代表稳定性的脚本**，不要把表格写成 grep 输出。
- 当 OpenAPI / 文档出现而代码未出现时：若已确认当前支持面不包含该接口，优先标记为 **Not implemented** 并同步清理文档；仅在事实尚未确认时才使用 **Documentation drift / needs confirmation**。

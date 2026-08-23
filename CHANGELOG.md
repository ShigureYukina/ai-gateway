# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.0.1-SNAPSHOT] - 2026-06-03

### Added

- **OpenAI 兼容 API** — `POST /v1/chat/completions`，支持流式（SSE）与非流式
- **多供应商适配** — OpenAI-compatible、Anthropic、Gemini 协议自动转换
- **Tool/Function Calling 透传** — `tools`/`tool_choice` 跨供应商翻译，响应 `tool_calls` 归一化
- **请求内容安全** — PII 检测（email/phone/SSN/credit card/API key），支持 BLOCK/MASK/LOG 三种动作
- **请求 ID 链路追踪** — `X-Request-Id` 贯穿全链路
- **模型别名路由** — 逻辑模型名映射到物理供应商/模型
- **场景化路由** — 按客户端/场景分配不同供应商
- **加权轮询（WRR）** — nginx 式平滑加权轮询，锁-free 原子实现
- **多级降级链** — 主路由 → 场景 fallback → 路由 fallback
- **三层熔断** — 路由级 + API Key 级 + Resilience4j 级
- **指数退避重试** — Resilience4j Retry
- **Bulkhead 隔离** — 线程池隔离
- **API Key 轮询池** — 多 Key 加权选择，熔断自动跳过
- **供应商健康检查** — 定时轮询 + 运行时状态追踪
- **请求速率限流** — 滑动窗口，锁-free CAS 实现
- **并发请求限制** — 全局 + per-client 在途请求数限制
- **Token 速率限制（TPM）** — 预估 + 校准 + 释放
- **每日/每月配额** — Token 配额 + 成本预算，input/output 双价拆分
- **JWT 认证** — Access token + Refresh token，支持 token 版本控制
- **API Key 认证** — 多 Key 管理，支持创建/轮换/撤销
- **角色权限** — ADMIN/OPERATOR/VIEWER/USER 四级角色
- **动态配置管理** — 供应商、路由、场景、客户端的热加载 CRUD
- **配置审计日志** — 所有配置变更记录
- **管理面板** — React 19 + TypeScript + shadcn/ui 前端
- **可观测性** — Micrometer + OpenTelemetry + Prometheus +结构化日志
- **多后端存储** — InMemory / Redis / PostgreSQL 可选
- **请求日志** — 完整请求/响应记录与查询

### Performance

- 流式吞吐 **709 req/s**，P50 延迟 **127ms**（对比初始 190 req/s，+273%）

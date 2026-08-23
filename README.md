<div align="center">

# Simple AI Gateway

**OpenAI 兼容的 LLM 网关 — 内置认证、限流、计费追踪与后台管理面板** ~~（大概）~~

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-green)
![OpenAI Compatible](https://img.shields.io/badge/API-OpenAI%20compatible-9cf)
![Anthropic](https://img.shields.io/badge/Provider-Anthropic-orange)
![Gemini](https://img.shields.io/badge/Provider-Gemini-4285f4)

</div>

> **🌐 [English](README.en.md) · 简体中文**

> **纯 AI 构建 · 耗时 3 天** — 从零到全功能，全部由 AI Agent 自主完成。

---

## 为什么用 Simple AI Gateway

即使你只用一个 LLM 提供商，也会遇到原始 API key 无法回答的问题：

- **如何控制谁能调用什么，以及能花多少钱？**
- **如何在不改客户端代码的情况下切换模型或提供商？**
- **如何知道每天、每个模型、每个用户实际花了多少钱？**

Simple AI Gateway 位于你的应用与 LLM 提供商之间，提供一个 OpenAI 兼容的统一接入端点，内置认证、限流、配额、计费追踪与熔断能力——让你像管理任何其他 API 一样管理 LLM 访问。

这是一个个人开源项目，适合自托管、公开发布与小规模评估 ~~（不承诺企业级 SLA）~~。

---

## 功能特性

### 统一接入

- **OpenAI 兼容 API** — 即插即用端点（`/v1/chat/completions`、`/v1/models`），兼容任意 OpenAI SDK 或工具
- **多提供商路由** — 自动协议适配 OpenAI、Anthropic、Gemini
- **模型别名与降级链** — 将友好名称映射到上游模型，支持加权轮询与场景级降级
- **API Key 池化** — 每个提供商支持多 key，加权选择，失败自动跳过

### 治理能力

- **认证** — JWT + API Key 双模式，基于角色的访问控制（ADMIN / OPERATOR / VIEWER / USER）
- **限流** — 滑动窗口 RPM、并发请求限制、TPM 估算（无锁 CAS）
- **配额与预算** — 每日/每月令牌配额 + 成本预算，支持输入/输出双重定价
- **熔断** — 三级熔断器、指数退避重试、舱壁隔离

### 观测能力

- **成本追踪** — 每次请求的令牌计数与费用计算，定价可配置（手动覆盖 → 精确匹配 → 模糊降级 → 默认）
- **请求日志与链路追踪** — 结构化日志、请求追踪、聚合报表
- **指标** — 集成 Micrometer + Prometheus + OpenTelemetry
- **配置审计** — 版本历史、导入/导出、快照回滚

### 管理能力

- **管理面板** — React 19 + TypeScript SPA，管理提供商、路由、客户端、用户与系统配置
- **热更新配置** — 全部 CRUD 操作即时生效，无需重启
- **Webhook 通知** — 管理操作的事件驱动告警
- **用户门户** — API Key 自助管理、用量/成本查看、接入指南

### 协议兼容性

| 端点 | 说明 |
|------|------|
| `POST /v1/chat/completions` | 流式 (SSE) 与非流式 |
| `GET /v1/models` | 模型列表，支持可见性控制 |
| Tool / Function Calling | 跨提供商透明翻译 |
| 未知字段 | 通过 `@JsonAnySetter` 透传（`stream_options`、`seed`、`logprobs` 等） |

---

## 快速开始

### 前置条件

- JDK 21+
- Node.js 20+（仅前端开发或构建打包 UI 时需要）

### 本地启动网关（无需数据库）

```bash
# 构建后端模块
./mvnw -q compile

# 使用 local profile 启动（内存模式）
./mvnw spring-boot:run -pl bootstrap \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--server.port=8081"
```

### 验证是否正常

```bash
curl -f http://localhost:8081/healthz

# 尝试 OpenAI 兼容的聊天端点
curl -X POST http://localhost:8081/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer demo-client-key' \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello!"}],"stream":false}'
```

### 打开管理界面

- **管理面板**: [http://localhost:8081](http://localhost:8081)
- **默认登录**: `admin` / `admin123`

### 可选：单独运行前端

```bash
cd frontend && npm ci && npm run dev
```

Vite 开发服务器默认将 `/auth`、`/admin`、`/internal`、`/v1`、`/healthz` 代理到 `http://localhost:8081`。

### 可选：构建可部署 JAR

```bash
# 完整构建（包含前端）
./mvnw -pl bootstrap -am package

# 仅后端（跳过前端构建）
./mvnw -pl bootstrap -am package -DskipFrontendBuild=true
```

> 默认 `local` profile 使用 H2 / in_memory 共享状态，适用于演示、集成测试与开发 ~~（不建议用于生产部署）~~。

---

## 架构

```
┌─────────────┐     ┌──────────────┐     ┌──────────────────┐
│ 客户端应用  │────▶│ Simple AI    │────▶│ OpenAI Provider  │
│ (OpenAI SDK)│     │ Gateway      │     │ Anthropic        │
└─────────────┘     │              │     │ Gemini           │
                    │ 限流         │     └──────────────────┘
                    │ 认证         │
                    │ 配额         │     ┌──────────────────┐
                    │ 路由         │────▶│ 管理面板         │
                    │ 熔断         │     │ (React 前端)     │
                    │ 观测         │     └──────────────────┘
                    └──────────────┘
```

完整流水线细节见 [docs/architecture.md](docs/architecture.md)。

---

## 项目结构

```
simple-ai-gateway/
├── gateway-core/     # 核心引擎：路由、认证、限流、上游适配
├── gateway-admin/    # 管理 API：CRUD、审计、Webhook、配额、同步、观测
├── bootstrap/        # Spring Boot 应用装配（组合 core + admin）
├── frontend/         # React 管理面板
├── docs/             # 架构、API 参考、示例、OpenAPI 规范
├── config/           # Checkstyle、SpotBugs 质量配置
└── .github/          # Issue/PR 模板、Dependabot 配置
```

---

## 项目状态

Simple AI Gateway 是一个活跃的个人开源项目。核心网关能力——认证、限流、配额管理、成本追踪、多提供商路由、熔断与后台管理面板——均已实现并测试。项目适合自托管与小规模评估 ~~（企业级用户请绕道）~~。

**已完成：**
- 完整请求流水线：认证 → 限流 → 配额 → 路由 → 上游 → 成本追踪 → 日志
- 多提供商支持（OpenAI 兼容、Anthropic、Gemini）含协议翻译
- 管理面板与热更新配置
- 用户门户：API Key 自助管理、用量视图、接入指南

详细项目状态与已知问题见 [CONTEXT.md](CONTEXT.md)（中文）。

---

## 文档

| 资源 | 链接 |
|------|------|
| **使用指南**（从这里开始） | **[docs/usage.md](docs/usage.md)** |
| 质量手册（维护规范与改进计划） | [docs/quality-manual.md](docs/quality-manual.md) |
| 架构说明 | [docs/architecture.md](docs/architecture.md) |
| API 参考 | [docs/api-reference.md](docs/api-reference.md) |
| 功能特性 | [docs/features.md](docs/features.md) |
| 使用示例 | [docs/examples.md](docs/examples.md) |
| OpenAPI 规范 | [docs/openapi.json](docs/openapi.json) |
| 变更日志 | [CHANGELOG.md](CHANGELOG.md) |
| 贡献指南 | [CONTRIBUTING.md](CONTRIBUTING.md) |

**推荐阅读顺序：**
- 使用者：`README` → `docs/usage.md` → `docs/api-reference.md`
- 维护者：`README` → `docs/quality-manual.md` → `CONTEXT.md` → `CONTEXT-plan.md`

---

## 验证层次

根据改动范围的验证分级：

| 层级 | 覆盖场景 | 门禁 |
|------|----------|------|
| **本地默认门禁** | 普通 Java / 前端改动 | `./mvnw -q -DskipTests compile` · `cd frontend && npm run lint` · `cd frontend && npm run test` · `cd frontend && npm run build` |
| **本地发布前补充** | 认证/API Key/配置写链/Store 改动 | 本地默认门禁 + `./scripts/verify.sh` |
| **专项诊断** | PG/Redis/Store/性能相关改动 | 本地发布前补充 + 按需 `./scripts/stress-test-backends.sh` |

当前 GitHub CI 会在 `main` 的 push / pull request 上额外执行：后端模块单测 + 集成测试、前端 `lint` + `test` + `build`、Maven `checkstyle:check`、`./scripts/regression.sh`、`./scripts/verify-supplement.sh`、`./scripts/user-journey-blackbox.sh`。

详细验证矩阵见 [docs/quality-manual.md](docs/quality-manual.md) → 最小必要验证矩阵。

## 技术栈

**后端：** Java 21 · Spring Boot 3.3.5 · WebFlux (Netty) · Resilience4j · JTokkit · Caffeine · Micrometer + OpenTelemetry

**前端：** React 19 · TypeScript 6 (beta) · Vite 8 · Tailwind CSS 4 · shadcn/ui · Zustand · TanStack React Query

**数据：** PostgreSQL · Redis · 或 InMemory — 按组件配置选择

---

## 参与贡献

欢迎通过 Issue、Feature Request 或 Pull Request 贡献。请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 了解指南。

贡献者快速入口：

```bash
# 后端编译检查
./mvnw -q compile

# 前端构建检查
cd frontend && npm ci && npm run build
```

---

## 许可

[Apache 2.0](LICENSE)

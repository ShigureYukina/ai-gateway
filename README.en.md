<div align="center">

# Simple AI Gateway

**OpenAI-compatible LLM gateway with auth, rate limiting, cost tracking, and an admin dashboard.**

Route requests to OpenAI, Anthropic, and Gemini through a single unified API — with quota management, circuit breaking, and real-time observability built in.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-green)
![OpenAI Compatible](https://img.shields.io/badge/API-OpenAI%20compatible-9cf)
![Anthropic](https://img.shields.io/badge/Provider-Anthropic-orange)
![Gemini](https://img.shields.io/badge/Provider-Gemini-4285f4)

</div>

> **English · [🌐 简体中文](README.md)**

---

## Why Simple AI Gateway?

If you're using multiple LLM providers — or even just one — you quickly run into questions that a raw API key can't answer:

- **How do I control who can call what, and how much they can spend?**
- **How do I switch models or providers without changing client code?**
- **How do I know what I'm actually paying, per model, per user, per day?**

Simple AI Gateway sits between your applications and LLM providers, giving you a single OpenAI-compatible endpoint with authentication, rate limiting, quota enforcement, cost tracking, and circuit breaking — so you can manage LLM access the way you'd manage any other API.

It's a personal open-source project, suitable for self-hosting, public sharing, and small-scale evaluation — not an enterprise SLA commitment.

---

## Features

### Unified Access

- **OpenAI-compatible API** — drop-in endpoint (`/v1/chat/completions`, `/v1/models`) works with any OpenAI SDK or tool
- **Multi-provider routing** — OpenAI, Anthropic, and Gemini with automatic protocol translation
- **Model alias & fallback chains** — map friendly names to upstream models, with weighted round-robin and scene-level fallback
- **API key pooling** — multiple keys per provider with weighted selection and automatic skip on failure

### Governance

- **Authentication** — JWT + API Key dual mode, role-based access (ADMIN / OPERATOR / VIEWER / USER)
- **Rate limiting** — sliding window RPM, concurrent request limit, TPM estimation (lock-free CAS)
- **Quota & budget** — daily/monthly token quotas + cost budgets, input/output dual pricing
- **Circuit breaking** — 3-level circuit breaker, exponential backoff retry, bulkhead isolation

### Observability

- **Cost tracking** — per-request token counting and cost calculation with configurable pricing (manual override → exact match → fuzzy fallback → default)
- **Request logging & tracing** — structured logging, request tracing, aggregated dashboards
- **Metrics** — Micrometer + Prometheus + OpenTelemetry integration
- **Configuration audit** — version history, import/export, snapshot rollback

### Management

- **Admin dashboard** — React 19 + TypeScript SPA for providers, routes, clients, users, and system config
- **Hot-reloaded config** — all CRUD operations take effect immediately, no restart required
- **Webhook notifications** — event-driven alerts for admin operations
- **User portal** — self-service API key management, usage/cost views, onboarding guide

### Protocol Compatibility

| Endpoint | Details |
|----------|---------|
| `POST /v1/chat/completions` | Streaming (SSE) and non-streaming |
| `GET /v1/models` | Model listing with visibility control |
| Tool / Function Calling | Transparently translated across providers |
| Unknown fields | Pass-through via `@JsonAnySetter` (`stream_options`, `seed`, `logprobs`, etc.) |

---

## Quick Start

### Prerequisites

- JDK 21+
- Node.js 20+ (only for frontend dev or building the bundled UI)

### Start the gateway locally (no database required)

```bash
# Build backend modules
./mvnw -q compile

# Run with local profile (in-memory mode)
./mvnw spring-boot:run -pl bootstrap \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments="--server.port=8081"
```

### Verify it's working

```bash
curl -f http://localhost:8081/healthz

# Try the OpenAI-compatible chat endpoint
curl -X POST http://localhost:8081/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer demo-client-key' \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello!"}],"stream":false}'
```

### Open the admin UI

- **Admin UI**: [http://localhost:8081](http://localhost:8081)
- **Default login**: `admin` / `admin123`

### Optional: run the frontend separately

```bash
cd frontend && npm ci && npm run dev
```

Vite dev server proxies `/auth`, `/admin`, `/internal`, `/v1`, `/healthz` to `http://localhost:8081` by default.

### Optional: build a deployable JAR

```bash
# Full build (includes frontend)
./mvnw -pl bootstrap -am package

# Backend only (skip frontend build)
./mvnw -pl bootstrap -am package -DskipFrontendBuild=true
```

> The default `local` profile uses H2 / in-memory shared state — suitable for demos, integration testing, and development. It is not a recommended deployment topology for external-facing use.

---

## Architecture

```
┌─────────────┐     ┌──────────────┐     ┌──────────────────┐
│ Client App  │────▶│ Simple AI    │────▶│ OpenAI Provider  │
│ (OpenAI SDK)│     │ Gateway      │     │ Anthropic        │
└─────────────┘     │              │     │ Gemini           │
                    │ Rate Limit   │     └──────────────────┘
                    │ Auth         │
                    │ Quota        │     ┌──────────────────┐
                    │ Route        │────▶│ Admin Dashboard  │
                    │ Resilience   │     │ (React Frontend) │
                    │ Observability│     └──────────────────┘
                    └──────────────┘
```

See [docs/architecture.md](docs/architecture.md) for the complete pipeline detail.

---

## Project Structure

```
simple-ai-gateway/
├── gateway-core/     # Core engine: routing, auth, rate limiting, upstream adapters
├── gateway-admin/    # Admin API: CRUD, audit, webhook, quota, sync, observability
├── bootstrap/        # Spring Boot application assembly (combines core + admin)
├── frontend/         # React admin dashboard
├── docs/             # Architecture, API reference, examples, OpenAPI spec
├── config/           # Checkstyle, SpotBugs quality configuration
└── .github/          # Issue/PR templates, Dependabot config
```

---

## Project Status

Simple AI Gateway is an active personal open-source project. Core gateway capabilities — authentication, rate limiting, quota management, cost tracking, multi-provider routing, circuit breaking, and the admin dashboard — are implemented and tested. The project is suitable for self-hosting and small-scale evaluation.

**What's working:**
- Full request pipeline: auth → rate limit → quota → route → upstream → cost tracking → logging
- Multi-provider support (OpenAI-compatible, Anthropic, Gemini) with protocol translation
- Admin dashboard with hot-reloaded configuration
- User portal with self-service API keys, usage views, and onboarding guide

See [CONTEXT.md](CONTEXT.md) for the detailed project status and known issues.

---

## Documentation

| Resource | Link |
|----------|------|
| **Usage Guide** (start here) | **[docs/usage.md](docs/usage.md)** |
| Architecture | [docs/architecture.md](docs/architecture.md) |
| API Reference | [docs/api-reference.md](docs/api-reference.md) |
| Features | [docs/features.md](docs/features.md) |
| Examples | [docs/examples.md](docs/examples.md) |
| OpenAPI Spec | [docs/openapi.json](docs/openapi.json) |
| Changelog | [CHANGELOG.md](CHANGELOG.md) |
| Contributing | [CONTRIBUTING.md](CONTRIBUTING.md) |

**Recommended reading order:** `README` → `docs/usage.md` → `docs/api-reference.md`

---

## Tech Stack

**Backend:** Java 21 · Spring Boot 3.3.5 · WebFlux (Netty) · Resilience4j · JTokkit · Caffeine · Micrometer + OpenTelemetry

**Frontend:** React 19 · TypeScript 6 (beta) · Vite 8 · Tailwind CSS 4 · shadcn/ui · Zustand · TanStack React Query

**Data:** PostgreSQL · Redis · or InMemory — choose per-component via config

---

## Contributing

Contributions are welcome! Whether it's a bug report, feature request, or pull request — please check [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

Quick start for contributors:

```bash
# Backend compile check
./mvnw -q compile

# Frontend build check
cd frontend && npm ci && npm run build
```

---

## License

[Apache 2.0](LICENSE)

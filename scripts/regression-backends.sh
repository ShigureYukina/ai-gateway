#!/usr/bin/env bash
# ============================================================================
# AI Gateway — PG/Redis 后端黑盒回归脚本
#
# 在真实 PostgreSQL + Redis 后端上回归核心主链路，覆盖 regression.sh
# 在 in_memory 后端验证的同一功能集，用于发现持久化/缓存/后端协作语义偏差。
# 支持 in_memory 与 postgresql 两种后端模式，默认 postgresql。
#
# 用法:
#   ./scripts/regression-backends.sh                    # 完整验证（默认 postgresql）
#   ./scripts/regression-backends.sh --build            # 先编译再验证
#   ./scripts/regression-backends.sh --backend in_memory # 用 in_memory 后端
#   ./scripts/regression-backends.sh --help
#
# 环境变量:
#   GATEWAY_URL   网关地址 (默认 http://localhost:8081)
#   MOCK_URL      Mock upstream 地址 (默认 http://localhost:18080)
#   BUILD_OPTS    编译选项 (默认 -DskipTests -q)
#   PGHOST        PostgreSQL 主机 (默认 /var/run/postgresql)
#   PGPORT        PostgreSQL 端口 (默认 5433)
#   PGDATABASE    PostgreSQL 库名 (默认 llm_gateway)
#   PGUSER        PostgreSQL 用户 (默认 llm_user)
#
# 依赖: curl, jq, java(21+), node, lsof
# 不依赖: Docker / Testcontainers
# ============================================================================

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_DIR/scripts/lib.sh"

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
MOCK_URL="${MOCK_URL:-http://localhost:18080}"
BUILD_OPTS="${BUILD_OPTS:--DskipTests -q}"
MAVEN="$PROJECT_DIR/mvnw"
BOOTSTRAP_JAR="$PROJECT_DIR/bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar"
MOCK_SCRIPT="$PROJECT_DIR/jmeter/mock_openai_server_node.mjs"
LOG_DIR="$PROJECT_DIR/.regression-logs"
GATEWAY_LOG="$LOG_DIR/gateway-backends.log"
MOCK_LOG="$LOG_DIR/mock-backends.log"

GATEWAY_PID=""
MOCK_PID=""
ADMIN_TOKEN=""
ADMIN_AUTH=""
ADMIN_REFRESH_TOKEN=""
STATIC_KEY_AVAILABLE=false
CHAT_AUTH_HEADER=""
RL_CLIENT=""
BUILD_BEFORE_RUN=false
BACKEND="${BACKEND:-postgresql}"

show_help() {
    cat <<'EOF'
AI Gateway — PG/Redis 后端黑盒回归脚本

在真实 PostgreSQL + Redis 后端上回归核心主链路。
支持 in_memory 与 postgresql 两种后端模式，默认 postgresql。

用法:
  ./scripts/regression-backends.sh                    # 完整验证（默认 postgresql）
  ./scripts/regression-backends.sh --build            # 先编译再验证
  ./scripts/regression-backends.sh --backend in_memory # 用 in_memory 后端
  ./scripts/regression-backends.sh --help

环境变量:
  GATEWAY_URL   网关地址 (默认 http://localhost:8081)
  MOCK_URL      Mock upstream 地址 (默认 http://localhost:18080)
  BUILD_OPTS    编译选项 (默认 -DskipTests -q)
  PGHOST        PostgreSQL 主机 (默认 /var/run/postgresql)
  PGPORT        PostgreSQL 端口 (默认 5433)
  PGDATABASE    PostgreSQL 库名 (默认 llm_gateway)
  PGUSER        PostgreSQL 用户 (默认 llm_user)
  PGPASSWORD    default llm_password
  REDIS_HOST    default 127.0.0.1
  REDIS_PORT    default 6379
EOF
}

BACKEND="${BACKEND:-postgresql}"

skip_script() {
    info "$1"
    printf '\n═══════════════════════════════════════════\n'
    printf '  结果: SKIP\n'
    printf '═══════════════════════════════════════════\n'
    exit 0
}

build_project() {
    require_file "$MAVEN" "Maven wrapper"
    stage "Build"
    check "Maven wrapper executable" test -x "$MAVEN"
    info "Running: $MAVEN -pl bootstrap -am package -DskipFrontendBuild=true -Dmaven.test.skip=true ${BUILD_OPTS}"
    if ! "$MAVEN" -pl bootstrap -am package -DskipFrontendBuild=true -Dmaven.test.skip=true ${BUILD_OPTS}; then
        log_line "$RED" "Build failed"
        exit 2
    fi
    check "Bootstrap JAR created" test -f "$BOOTSTRAP_JAR"
}

start_mock() {
    require_file "$MOCK_SCRIPT" "mock upstream script"
    mkdir -p "$LOG_DIR"
    info "Starting mock upstream on $MOCK_URL"
    node "$MOCK_SCRIPT" >"$MOCK_LOG" 2>&1 &
    MOCK_PID=$!
    sleep 2
    if ! kill -0 "$MOCK_PID" 2>/dev/null; then
        log_line "$RED" "Mock upstream failed to start; see $MOCK_LOG"
        exit 2
    fi
}

resolve_pg_host() {
    local socket_host="${PGHOST:-/var/run/postgresql}"
    local tcp_host="127.0.0.1"
    local pg_port="${PGPORT:-5433}"
    local pg_user="${PGUSER:-llm_user}"
    local pg_db="${PGDATABASE:-llm_gateway}"

    if command -v pg_isready >/dev/null 2>&1 && pg_isready -h "$socket_host" -p "$pg_port" -U "$pg_user" -d "$pg_db" >/dev/null 2>&1; then
        printf '%s\n' "$socket_host"
        return 0
    fi
    if command -v pg_isready >/dev/null 2>&1 && pg_isready -h "$tcp_host" -p "$pg_port" -U "$pg_user" -d "$pg_db" >/dev/null 2>&1; then
        printf '%s\n' "$tcp_host"
        return 0
    fi
    printf '%s\n' "$socket_host"
}

ensure_database() {
    local pg_host="$1"
    local pg_port="${PGPORT:-5433}"
    local pg_user="${PGUSER:-llm_user}"

    if ! command -v psql >/dev/null 2>&1; then
        skip_script "psql not found; cannot verify/create PostgreSQL database for backend regression"
    fi

    info "Ensuring PostgreSQL database llm_gateway exists"
    PGPASSWORD="${PGPASSWORD:-llm_password}" psql -U "$pg_user" -h "$pg_host" -p "$pg_port" -tc "SELECT 1 FROM pg_database WHERE datname='llm_gateway'" | grep -q 1 || \
        PGPASSWORD="${PGPASSWORD:-llm_password}" psql -U "$pg_user" -h "$pg_host" -p "$pg_port" -c "CREATE DATABASE llm_gateway"
}

start_gateway() {
    local pg_host pg_port pg_db pg_user pg_password redis_host redis_port

    if [ "$BACKEND" = "in_memory" ]; then
        info "Starting gateway with in-memory backend..."
        java -jar "$BOOTSTRAP_JAR" \
            --server.port=8081 \
            --spring.profiles.active=local \
            --gateway.shared-state.backend=in_memory \
            --spring.flyway.enabled=false \
            >"$GATEWAY_LOG" 2>&1 &
        GATEWAY_PID=$!
    else
        pg_host="${PGHOST:-127.0.0.1}"
        pg_port="${PGPORT:-5433}"
        pg_db="${PGDATABASE:-llm_gateway}"
        pg_user="${PGUSER:-llm_user}"
        pg_password="${PGPASSWORD:-llm_password}"
        redis_host="${REDIS_HOST:-127.0.0.1}"
        redis_port="${REDIS_PORT:-6379}"
        info "Starting gateway with PostgreSQL + Redis..."
        java -jar "$BOOTSTRAP_JAR" \
            --server.port=8081 \
            --spring.profiles.active=local,test-pg \
            --gateway.shared-state.backend=postgresql \
            --spring.datasource.url="jdbc:postgresql://${pg_host}:${pg_port}/${pg_db}" \
            --spring.datasource.username="${pg_user}" \
            --spring.datasource.password="${pg_password}" \
            --spring.datasource.driver-class-name=org.postgresql.Driver \
            --spring.flyway.enabled=true \
            --spring.data.redis.host="${redis_host}" \
            --spring.data.redis.port="${redis_port}" \
            --spring.data.redis.password= \
            >"$GATEWAY_LOG" 2>&1 &
        GATEWAY_PID=$!
    fi
}

wait_for_gateway() {
    printf '[%s] Waiting for gateway readiness' "$(timestamp)"
    for _ in $(seq 1 60); do
        if [ "$(http_code "$GATEWAY_URL/healthz")" = "200" ]; then
            printf ' done\n'
            return 0
        fi
        printf '.'
        sleep 1
    done
    printf '\n'
    return 1
}

cleanup_resources() {
    if [ -n "$ADMIN_AUTH" ]; then
        http_code -X DELETE "$GATEWAY_URL/admin/routes/gpt-4o-mini" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
        http_code -X DELETE "$GATEWAY_URL/admin/providers/mock" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
        if [ -n "$RL_CLIENT" ]; then
            http_code -X DELETE "$GATEWAY_URL/admin/clients/$RL_CLIENT" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
        fi
    fi
}

cleanup() {
    cleanup_resources
    kill_pid_gracefully "$GATEWAY_PID" "gateway"
    kill_pid_gracefully "$MOCK_PID" "mock upstream"
}

ensure_backends_running() {
    local pg_host="${PGHOST:-/var/run/postgresql}"
    local pg_port="${PGPORT:-5433}"
    local pg_db="${PGDATABASE:-llm_gateway}"
    local pg_user="${PGUSER:-llm_user}"
    local redis_host="${REDIS_HOST:-127.0.0.1}"
    local redis_port="${REDIS_PORT:-6379}"

    local pg_ready=false
    local redis_ready=false

    if command -v pg_isready >/dev/null 2>&1; then
        if pg_isready -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_db" >/dev/null 2>&1 || \
            pg_isready -h 127.0.0.1 -p "$pg_port" -U "$pg_user" -d "$pg_db" >/dev/null 2>&1; then
            pg_ready=true
        fi
    fi

    if command -v redis-cli >/dev/null 2>&1; then
        if [ "$(redis-cli -h "$redis_host" -p "$redis_port" ping 2>/dev/null || true)" = "PONG" ] || \
            [ "$(redis-cli -h 127.0.0.1 -p "$redis_port" ping 2>/dev/null || true)" = "PONG" ]; then
            redis_ready=true
        fi
    fi

    if [ "$pg_ready" = true ] && [ "$redis_ready" = true ]; then
        return 0
    fi

    skip_script "PostgreSQL/Redis not available; skipping backend regression"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --build)
            BUILD_BEFORE_RUN=true
            ;;
        --backend)
            BACKEND="$2"
            shift
            ;;
        --help)
            show_help
            exit 0
            ;;
        *)
            log_line "$RED" "Unknown argument: $1"
            exit 1
            ;;
    esac
    shift
done

trap cleanup INT TERM EXIT

stage "Pre-flight"
check "curl installed" command -v curl
check "jq installed" command -v jq
check "java installed" command -v java
check "node installed" command -v node
check "lsof installed" command -v lsof

JAVA_VERSION_OUTPUT="$(java -version 2>&1 || true)"
JAVA_MAJOR="0"
JAVA_VERSION_PATTERN='version[[:space:]]+"([0-9]+)'
if [[ "$JAVA_VERSION_OUTPUT" =~ $JAVA_VERSION_PATTERN ]]; then
    JAVA_MAJOR="${BASH_REMATCH[1]}"
fi
assert_gt "Java version is 21+" "${JAVA_MAJOR:-0}" 20 "got ${JAVA_MAJOR:-unknown}, expected >= 21"

check "project scripts directory exists" test -d "$PROJECT_DIR/scripts"
check "mock script exists" test -f "$MOCK_SCRIPT"
check "Maven wrapper exists" test -f "$MAVEN"

if [ "$BACKEND" = "postgresql" ]; then
    if ! command -v pg_isready >/dev/null 2>&1; then
        skip_script "pg_isready not found; cannot verify PostgreSQL availability"
    fi
    if ! command -v redis-cli >/dev/null 2>&1; then
        skip_script "redis-cli not found; cannot verify Redis availability"
    fi
fi

kill_port_processes 8081 "gateway"
kill_port_processes 18080 "mock upstream"
assert "Gateway port free after cleanup" "false" "$(if is_port_busy 8081; then printf 'true'; else printf 'false'; fi)" "port 8081 is in use"

if [ "$BACKEND" = "postgresql" ]; then
    ensure_backends_running
    PGHOST="$(resolve_pg_host)"
    export PGHOST
    export PGPORT="${PGPORT:-5433}"
    export PGDATABASE="${PGDATABASE:-llm_gateway}"
    export PGUSER="${PGUSER:-llm_user}"
    export PGPASSWORD="${PGPASSWORD:-llm_password}"
    export REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
    export REDIS_PORT="${REDIS_PORT:-6379}"
    ensure_database "$PGHOST"
fi

if [ "$BUILD_BEFORE_RUN" = true ]; then
    build_project
else
    require_file "$BOOTSTRAP_JAR" "bootstrap JAR"
fi

stage "Start services"
start_mock
check "mock process started" kill -0 "$MOCK_PID"
check "mock models endpoint responds" test "$(http_code "$MOCK_URL/v1/models")" = "200"
mkdir -p "$LOG_DIR"
start_gateway
check "gateway process started" kill -0 "$GATEWAY_PID"
if ! wait_for_gateway; then
    log_line "$RED" "Gateway failed to become ready; see $GATEWAY_LOG"
    exit 2
fi
check "gateway readiness endpoint returns 200" test "$(http_code "$GATEWAY_URL/healthz")" = "200"

stage "Stage 1: Health & Readiness"
HEALTH_BODY="$(http_body "$GATEWAY_URL/healthz")"
assert "Health check returns UP" "UP" "$(jq_value "$HEALTH_BODY" '.status // empty')" "got $(jq_value "$HEALTH_BODY" '.status // empty'), expected UP"
assert "Liveness probe 200" "200" "$(http_code "$GATEWAY_URL/healthz/live")"
assert "Readiness probe 200" "200" "$(http_code "$GATEWAY_URL/healthz/ready")"
MODELS_BODY="$(http_body "$GATEWAY_URL/v1/models")"
assert "Model list accessible" "list" "$(jq_value "$MODELS_BODY" '.object // empty')"

stage "Stage 2: Admin Login + Add Provider + Route"
ADMIN_LOGIN_BODY=$(http_body -X POST "$GATEWAY_URL/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}')
ADMIN_TOKEN=$(jq_value "$ADMIN_LOGIN_BODY" '.accessToken')
ADMIN_REFRESH_TOKEN=$(jq_value "$ADMIN_LOGIN_BODY" '.refreshToken // empty')
check "Admin login returns token" test -n "$ADMIN_TOKEN"
check "Admin login returns refreshToken" test -n "$ADMIN_REFRESH_TOKEN"
ADMIN_AUTH="Authorization: Bearer $ADMIN_TOKEN"

PROVIDER_PAYLOAD='{"type":"openai-compatible","baseUrl":"'"$MOCK_URL"'","apiKey":"sk-mock","timeout":"10s"}'
PROVIDER_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/providers/mock" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$PROVIDER_PAYLOAD")"
STEP=$((STEP + 1)); printf '  [%02d] Add mock provider ... ' "$STEP"; if [[ "$PROVIDER_CODE" =~ ^[0-9]+$ ]] && [ "$PROVIDER_CODE" -lt 300 ]; then pass; else fail "got $PROVIDER_CODE, expected < 300"; fi
PROVIDER_LIST_BODY="$(http_body "$GATEWAY_URL/admin/providers" -H "$ADMIN_AUTH")"
assert "Provider list contains mock" "true" "$(jq_value "$PROVIDER_LIST_BODY" '.providers | has("mock")')"

ROUTE_PAYLOAD='{"scene":"default-chat","provider":"mock","upstreamModel":"gpt-4o-mini"}'
ROUTE_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/routes/gpt-4o-mini" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$ROUTE_PAYLOAD")"
STEP=$((STEP + 1)); printf '  [%02d] Add route ... ' "$STEP"; if [[ "$ROUTE_CODE" =~ ^[0-9]+$ ]] && [ "$ROUTE_CODE" -lt 300 ]; then pass; else fail "got $ROUTE_CODE, expected < 300"; fi
ROUTE_LIST_BODY="$(http_body "$GATEWAY_URL/admin/routes" -H "$ADMIN_AUTH")"
assert "Route list contains gpt-4o-mini" "true" "$(jq_value "$ROUTE_LIST_BODY" '.routes | has("gpt-4o-mini")')"

stage "Stage 3: Chat API (static key + JWT)"
CHAT_PAYLOAD='{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}],"stream":false}'
STATIC_CHAT_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H 'Authorization: Bearer demo-client-key' -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
if [ "$STATIC_CHAT_CODE" = "200" ]; then
    STATIC_KEY_AVAILABLE=true
    CHAT_AUTH_HEADER='Authorization: Bearer demo-client-key'
    STATIC_CHAT_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" -H "$CHAT_AUTH_HEADER" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
    assert_gt "Static key chat returns choices" "$(jq_number "$STATIC_CHAT_BODY" '.choices | length')" 0 "no choices returned"
    assert "Static key response model matches" "gpt-4o-mini" "$(jq_value "$STATIC_CHAT_BODY" '.model // empty')"
else
    CHAT_AUTH_HEADER="$ADMIN_AUTH"
    STEP=$((STEP + 1))
    printf '  [%02d] Static key chat ... ✓ SKIP (demo-client-key unavailable with PostgreSQL backend)\n' "$STEP"
fi

JWT_CHAT_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert_gt "JWT chat returns choices" "$(jq_number "$JWT_CHAT_BODY" '.choices | length')" 0 "no choices returned"

stage "Stage 4: SSE Streaming"
SSE_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H "$CHAT_AUTH_HEADER" -H 'Content-Type: application/json' -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}],"stream":true}')"
assert "SSE returns 200" "200" "$SSE_CODE"
SSE_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" -H "$CHAT_AUTH_HEADER" -H 'Content-Type: application/json' -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}],"stream":true}')"
assert_contains "SSE has data events" "$SSE_BODY" "data:"
assert_contains "SSE has DONE terminator" "$SSE_BODY" "[DONE]"

stage "Stage 5: Rate Limiting"
RL_CLIENT="ratelimit-pg-$(date +%s)"
RL_CLIENT_BODY='{"enabled":true,"allowedModels":["gpt-4o-mini"],"limits":{"requestsPerWindow":1,"window":"PT1M"}}'
RL_CREATE_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/clients/$RL_CLIENT" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$RL_CLIENT_BODY")"
STEP=$((STEP + 1)); printf '  [%02d] Create rate-limit client ... ' "$STEP"; if [[ "$RL_CREATE_CODE" =~ ^[0-9]+$ ]] && [ "$RL_CREATE_CODE" -lt 300 ]; then pass; else fail "got $RL_CREATE_CODE, expected < 300"; fi
sleep 1
RL_FIRST_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H "Authorization: Bearer $RL_CLIENT" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert "Rate-limit first request 200" "200" "$RL_FIRST_CODE"
RL_SECOND_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H "Authorization: Bearer $RL_CLIENT" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert "Rate-limit second request 429" "429" "$RL_SECOND_CODE"

stage "Stage 6: Request Logging + Usage Summary"
REQUESTS_BODY="$(http_body "$GATEWAY_URL/admin/requests/recent" -H "$ADMIN_AUTH")"
assert_gt "Request log has records" "$(jq_number "$REQUESTS_BODY" '.requests | length')" 0 "recent request log too short"
DETAIL_REQUEST_ID="$(jq_value "$REQUESTS_BODY" '.requests[0].requestId // empty')"
if [ -n "$DETAIL_REQUEST_ID" ] && [ "$DETAIL_REQUEST_ID" != "null" ]; then
    DETAIL_BODY="$(http_body "$GATEWAY_URL/internal/requests/$DETAIL_REQUEST_ID" -H "$ADMIN_AUTH")"
    assert "Request detail accessible" "200" "$(http_code "$GATEWAY_URL/internal/requests/$DETAIL_REQUEST_ID" -H "$ADMIN_AUTH")"
    assert "Request detail requestId matches" "$DETAIL_REQUEST_ID" "$(jq_value "$DETAIL_BODY" '.request.requestId // empty')"
    EXPECTED_DETAIL_STATUS="$(jq_value "$REQUESTS_BODY" '.requests[0].status // empty')"
    if [ -n "$EXPECTED_DETAIL_STATUS" ] && [ "$EXPECTED_DETAIL_STATUS" != "null" ]; then
        assert "Request detail status matches recent entry" "$EXPECTED_DETAIL_STATUS" "$(jq_value "$DETAIL_BODY" '.request.status // empty')"
    fi
    STEP=$((STEP + 1))
    printf '  [%02d] Request detail trace aligns when present ... ' "$STEP"
    DETAIL_TRACE_ID="$(jq_value "$DETAIL_BODY" '.trace.requestId // empty')"
    DETAIL_TRACE_STATUS="$(jq_value "$DETAIL_BODY" '.trace.status // empty')"
    if [ -z "$DETAIL_TRACE_ID" ] || { [ "$DETAIL_TRACE_ID" = "$DETAIL_REQUEST_ID" ] && { [ -z "$DETAIL_TRACE_STATUS" ] || [ "$DETAIL_TRACE_STATUS" = "$EXPECTED_DETAIL_STATUS" ]; }; }; then
        pass
    else
        fail "trace requestId/status mismatch: traceId=$DETAIL_TRACE_ID traceStatus=$DETAIL_TRACE_STATUS expectedId=$DETAIL_REQUEST_ID expectedStatus=$EXPECTED_DETAIL_STATUS"
    fi
fi
TODAY="$(date +%Y-%m-%d)"
USAGE_SUMMARY="$(http_body "$GATEWAY_URL/internal/usage/summary?client=$RL_CLIENT&day=$TODAY" -H "$ADMIN_AUTH")"
assert "Usage summary accessible" "200" "$(http_code "$GATEWAY_URL/internal/usage/summary?client=$RL_CLIENT&day=$TODAY" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] Usage total tokens > 0 ... ' "$STEP"
USAGE_TOKENS="$(printf '%s' "$USAGE_SUMMARY" | jq '[.clients[].tokens] | add // 0')"
if [ "$USAGE_TOKENS" -gt 0 ] 2>/dev/null; then pass; else fail "totalTokens=$USAGE_TOKENS, expected > 0"; fi

stage "Stage 7: Config Export"
EXPORT_BODY="$(http_body "$GATEWAY_URL/admin/config/export" -H "$ADMIN_AUTH")"
assert "Config export has providers" "true" "$(jq_value "$EXPORT_BODY" '.providers | has("mock")')"
assert "Config export has routes" "true" "$(jq_value "$EXPORT_BODY" '.routes | has("gpt-4o-mini")')"

stage "Stage 8: Auth Session Lifecycle"
REFRESH_BODY="$(http_body -X POST "$GATEWAY_URL/auth/refresh" -H 'Content-Type: application/json' -d '{"refreshToken":"'"$ADMIN_REFRESH_TOKEN"'"}')"
REFRESH_ACCESS_TOKEN="$(jq_value "$REFRESH_BODY" '.accessToken // empty')"
REFRESH_REFRESH_TOKEN="$(jq_value "$REFRESH_BODY" '.refreshToken // empty')"
assert "Refresh returns accessToken" "true" "$(if [ -n "$REFRESH_ACCESS_TOKEN" ] && [ "$REFRESH_ACCESS_TOKEN" != "null" ]; then echo "true"; else echo "false"; fi)"
assert "Refresh returns new refreshToken" "true" "$(if [ -n "$REFRESH_REFRESH_TOKEN" ] && [ "$REFRESH_REFRESH_TOKEN" != "null" ]; then echo "true"; else echo "false"; fi)"
LOGOUT_CODE="$(http_code -X POST "$GATEWAY_URL/auth/logout" -H 'Content-Type: application/json' -d '{"refreshToken":"'"$REFRESH_REFRESH_TOKEN"'"}')"
assert_status_one_of "Logout returns 200/204" "$LOGOUT_CODE" "200" "204"
REVOKED_REFRESH_CODE="$(http_code -X POST "$GATEWAY_URL/auth/refresh" -H 'Content-Type: application/json' -d '{"refreshToken":"'"$ADMIN_REFRESH_TOKEN"'"}')"
assert "Original refresh token rejected after logout chain" "401" "$REVOKED_REFRESH_CODE"

summary

#!/usr/bin/env bash
# ============================================================================
# AI Gateway — 主回归黑盒测试脚本
#
# 覆盖认证、Provider/Route/Client/User 管理、chat、key 生命周期、
#   限流/配额/预算/TPM、fallback/熔断/超时/WRR 负载均衡、
#   request log / usage / cost / dashboard 等核心串联路径。
#
# 用法:
#   ./scripts/regression.sh                         # 全流程: 重启 → 编译 → 启动 → 测试 → 清理
#   ./scripts/regression.sh --skip-build            # 跳过 Maven 编译
#   ./scripts/regression.sh --skip-setup            # 跳过启动，用已有实例
#   ./scripts/regression.sh --skip-teardown         # 测试后不清理进程
#   ./scripts/regression.sh --run-only              # 仅运行测试（隐含 --skip-setup --skip-teardown）
#   ./scripts/regression.sh --help
#
# 环境变量:
#   GATEWAY_URL   网关地址 (默认 http://localhost:8081)
#   MOCK_URL      Mock upstream 地址 (默认 http://localhost:18080)
#   BUILD_OPTS    编译选项 (默认 -DskipTests -q)
#
# 依赖: curl, jq, java(21+), node, lsof
# 不依赖: Docker / Testcontainers
# ============================================================================

set -euo pipefail

# ── Config ──
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_DIR/scripts/lib.sh"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
MOCK_URL="${MOCK_URL:-http://localhost:18080}"
BUILD_OPTS="${BUILD_OPTS:--DskipTests -q}"
MOCK_SCRIPT="$PROJECT_DIR/jmeter/mock_openai_server_node.mjs"
JAR_PATH="$PROJECT_DIR/bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar"
MAVEN="$PROJECT_DIR/mvnw"
LOG_DIR="$PROJECT_DIR/.regression-logs"
MOCK_LOG="$LOG_DIR/mock.log"
GATEWAY_LOG="$LOG_DIR/gateway.log"

GATEWAY_PID=""
MOCK_PID=""
ANTHROPIC_MOCK_PID=""

SKIP_BUILD=false
SKIP_SETUP=false
SKIP_TEARDOWN=false
RUN_ONLY=false

ADMIN_TOKEN=""
ADMIN_AUTH=""
USER_TOKEN=""
USER_AUTH=""
USER_API_KEY=""
PERSONAL_API_KEY=""
USER_LOGIN_REFRESH_TOKEN=""

TEST_USERNAME="testuser"
TEST_PASSWORD="testpass123"

show_help() {
    cat <<'EOF'
AI Gateway restart + regression black-box test script

Usage:
  ./scripts/regression.sh                         # Full: kill-existing → rebuild → start → test → cleanup
  ./scripts/regression.sh --skip-build            # Skip Maven rebuild
  ./scripts/regression.sh --skip-setup            # Skip startup, use already-running instance
  ./scripts/regression.sh --skip-teardown         # Keep processes running after tests
  ./scripts/regression.sh --run-only              # Just run tests (implies --skip-setup --skip-teardown)
  ./scripts/regression.sh --help

Environment Variables:
  GATEWAY_URL   default http://localhost:8081
  MOCK_URL      default http://localhost:18080
  BUILD_OPTS    default -DskipTests -q
EOF
}

build_project() {
    require_file "$MAVEN" "Maven wrapper"
    stage "Build"
    check "Maven wrapper executable" test -x "$MAVEN"
    info "Running: $MAVEN -pl bootstrap -am package -DskipFrontendBuild=true -Dmaven.test.skip=true ${BUILD_OPTS}"
    if "$MAVEN" -pl bootstrap -am package -DskipFrontendBuild=true -Dmaven.test.skip=true ${BUILD_OPTS}; then
        pass_build_result=true
    else
        log_line "$RED" "Build failed"
        exit 2
    fi
    check "Bootstrap JAR created" test -f "$JAR_PATH"
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

start_gateway() {
    require_file "$JAR_PATH" "bootstrap JAR"
    mkdir -p "$LOG_DIR"
    info "Starting gateway on $GATEWAY_URL"
    java -jar "$JAR_PATH" \
        --server.port="$(port_from_url "$GATEWAY_URL")" \
        --spring.profiles.active=local \
        --gateway.shared-state.backend=in_memory \
        --spring.flyway.enabled=false >"$GATEWAY_LOG" 2>&1 &
    GATEWAY_PID=$!
    if ! wait_for_gateway; then
        log_line "$RED" "Gateway failed to become ready; see $GATEWAY_LOG"
        exit 2
    fi
}

wait_for_gateway() {
    printf '[%s] Waiting for gateway readiness' "$(timestamp)"
    for _ in $(seq 1 30); do
        if [ "$(http_code "$GATEWAY_URL/healthz/live")" = "200" ]; then
            printf ' done\n'
            return 0
        fi
        printf '.'
        sleep 1
    done
    printf '\n'
    return 1
}

stop_all() {
    if [ "$SKIP_TEARDOWN" = true ]; then
        info "Skipping teardown (--skip-teardown)"
        return 0
    fi
    kill_pid_gracefully "$GATEWAY_PID" "gateway"
    kill_pid_gracefully "$MOCK_PID" "mock upstream"
    if [ -n "${ANTHROPIC_MOCK_PID:-}" ]; then
        kill_pid_gracefully "$ANTHROPIC_MOCK_PID" "Anthropic mock"
    fi
    if [ -n "${ERROR_MOCK_PID:-}" ]; then
        kill_pid_gracefully "$ERROR_MOCK_PID" "error mock"
    fi
    if [ -n "${SLOW_MOCK_PID:-}" ]; then
        kill_pid_gracefully "$SLOW_MOCK_PID" "slow mock"
    fi
}

cleanup() {
    stop_all
}

gateway_port="$(port_from_url "$GATEWAY_URL")"
mock_port="$(port_from_url "$MOCK_URL")"

# ── Arg Parsing ──
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build)
            SKIP_BUILD=true
            ;;
        --skip-setup)
            SKIP_SETUP=true
            ;;
        --skip-teardown)
            SKIP_TEARDOWN=true
            ;;
        --run-only)
            RUN_ONLY=true
            SKIP_SETUP=true
            SKIP_TEARDOWN=true
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

if [ "$SKIP_SETUP" = true ]; then
    SKIP_BUILD=true
fi

# ── Trap EXIT ──
trap cleanup EXIT

# ── Stage 0-12 ──
stage "Pre-flight"
check "curl installed" command -v curl
check "jq installed" command -v jq
check "java installed" command -v java
check "node installed" command -v node
JAVA_VERSION_OUTPUT="$(java -version 2>&1 || true)"
JAVA_MAJOR="0"
JAVA_VERSION_PATTERN='version[[:space:]]+"([0-9]+)'
if [[ "$JAVA_VERSION_OUTPUT" =~ $JAVA_VERSION_PATTERN ]]; then
    JAVA_MAJOR="${BASH_REMATCH[1]}"
fi
assert_gt "Java version is 21+" "${JAVA_MAJOR:-0}" 20 "got ${JAVA_MAJOR:-unknown}, expected >= 21"

if [ "$SKIP_SETUP" = false ]; then
    check "lsof installed for port checks" command -v lsof
    check "project scripts directory exists" test -d "$PROJECT_DIR/scripts"
    check "mock script exists" test -f "$MOCK_SCRIPT"
    check "Maven wrapper exists" test -f "$MAVEN"
    # Kill existing processes first, then verify ports are free
    kill_port_processes "$gateway_port" "gateway"
    kill_port_processes "$mock_port" "mock upstream"
    kill_port_processes 18081 "error mock"
    kill_port_processes 18082 "slow mock"
    kill_port_processes 18083 "slow mock"
    kill_port_processes 18084 "Anthropic mock"
    assert "Gateway port free after cleanup" "false" "$(if is_port_busy "$gateway_port"; then printf 'true'; else printf 'false'; fi)" "port $gateway_port is in use"
    assert "Mock port free after cleanup" "false" "$(if is_port_busy "$mock_port"; then printf 'true'; else printf 'false'; fi)" "port $mock_port is in use"
else
    check "existing gateway reachable" test "$(http_code "$GATEWAY_URL/healthz/live")" = "200"
    check "health endpoint reachable" test "$(http_code "$GATEWAY_URL/healthz")" = "200"
fi

if [ "$SKIP_BUILD" = false ] && [ "$SKIP_SETUP" = false ]; then
    build_project
fi

if [ "$SKIP_SETUP" = false ]; then
    stage "Start services"
    start_mock
    check "mock process started" kill -0 "$MOCK_PID"
    check "mock models endpoint responds" test "$(http_code "$MOCK_URL/v1/models")" = "200"
    # Anthropic mock
    ANTHROPIC_MOCK_PORT=18084
    ANTHROPIC_MOCK_LOG="$LOG_DIR/mock_anthropic_server.log"
    kill_port_processes "$ANTHROPIC_MOCK_PORT" "Anthropic mock"
    node "$PROJECT_DIR/jmeter/mock_anthropic_server_node.mjs" >"$ANTHROPIC_MOCK_LOG" 2>&1 &
    ANTHROPIC_MOCK_PID=$!
    start_gateway
    check "gateway process started" kill -0 "$GATEWAY_PID"
    check "gateway liveness ready within 30s" test "$(http_code "$GATEWAY_URL/healthz/live")" = "200"
else
    stage "Start services"
    check "reuse running gateway instance" test "$(http_code "$GATEWAY_URL/healthz/live")" = "200"
fi

stage "Health & Readiness"
HEALTH_BODY="$(http_body "$GATEWAY_URL/healthz")"
assert "Health check returns UP" "UP" "$(jq_value "$HEALTH_BODY" '.status // empty')" "got $(jq_value "$HEALTH_BODY" '.status // empty'), expected UP"
assert "Liveness probe 200" "200" "$(http_code "$GATEWAY_URL/healthz/live")"
assert "Readiness probe 200" "200" "$(http_code "$GATEWAY_URL/healthz/ready")"
MODELS_BODY="$(http_body "$GATEWAY_URL/v1/models")"
assert "Model list format" "list" "$(jq_value "$MODELS_BODY" '.object // empty')"
assert "Model list accessible" "list" "$(jq_value "$MODELS_BODY" '.object // empty')"

stage "Admin Login & Provider Setup"
LOGIN_BODY="$(http_body -X POST "$GATEWAY_URL/auth/login" -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}')"
ADMIN_TOKEN="$(jq_value "$LOGIN_BODY" '.accessToken // empty')"
ADMIN_AUTH="Authorization: Bearer $ADMIN_TOKEN"
STEP=$((STEP + 1)); printf '  [%02d] Admin login returns token ... ' "$STEP"; if [ -n "$ADMIN_TOKEN" ] && [ "$ADMIN_TOKEN" != "null" ]; then pass; else fail "accessToken empty"; fi

PROVIDER_PAYLOAD='{"type":"openai-compatible","baseUrl":"'"$MOCK_URL"'","apiKey":"sk-mock","timeout":"10s"}'
ADD_PROVIDER_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/providers/mock" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$PROVIDER_PAYLOAD")"
assert_2xx "Add mock provider" "$ADD_PROVIDER_CODE"
PROVIDER_LIST_BODY="$(http_body "$GATEWAY_URL/admin/providers" -H "$ADMIN_AUTH")"
assert "Provider list contains mock" "true" "$(jq_value "$PROVIDER_LIST_BODY" '.providers | has("mock")')"
UPDATE_PROVIDER_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/providers/mock" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$PROVIDER_PAYLOAD")"
assert_2xx "Update provider (same PUT)" "$UPDATE_PROVIDER_CODE"

stage "Provider Model Directory"
PROVIDER_TEST_BODY="$(http_body -X POST "$GATEWAY_URL/admin/providers/mock/test" -H "$ADMIN_AUTH")"
assert "Provider test status is ok/error" "true" "$(jq_value "$PROVIDER_TEST_BODY" '(.status == "ok") or (.status == "error")')" "unexpected status=$(jq_value "$PROVIDER_TEST_BODY" '.status // empty')"
PROVIDER_MODELS_BEFORE="$(http_body "$GATEWAY_URL/admin/providers/mock/models" -H "$ADMIN_AUTH")"
assert "Provider models GET returns provider name" "mock" "$(jq_value "$PROVIDER_MODELS_BEFORE" '.provider // empty')"
STEP=$((STEP + 1)); printf '  [%02d] Provider models GET returns generatedAt ... ' "$STEP"; if [ -n "$(jq_value "$PROVIDER_MODELS_BEFORE" '.generatedAt // empty')" ]; then pass; else fail "generatedAt empty"; fi
PROVIDER_MODELS_PUT_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/providers/mock/models" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"models":["gpt-4o-mini","gpt-4.1-mini"]}')"
assert "Provider models PUT returns 204" "204" "$PROVIDER_MODELS_PUT_CODE"
PROVIDER_MODELS_AFTER="$(http_body "$GATEWAY_URL/admin/providers/mock/models" -H "$ADMIN_AUTH")"
assert "Provider models GET after PUT still returns provider name" "mock" "$(jq_value "$PROVIDER_MODELS_AFTER" '.provider // empty')"
assert "Provider models GET after PUT still returns models array" "array" "$(jq_value "$PROVIDER_MODELS_AFTER" '.models | type')"
PROVIDER_FETCH_CODE="$(http_code -X POST "$GATEWAY_URL/admin/providers/mock/models/fetch" -H "$ADMIN_AUTH")"
assert "Provider models fetch returns 200" "200" "$PROVIDER_FETCH_CODE"
PROVIDER_FETCH_BODY="$(http_body -X POST "$GATEWAY_URL/admin/providers/mock/models/fetch" -H "$ADMIN_AUTH")"
assert "Provider models fetch returns provider name" "mock" "$(jq_value "$PROVIDER_FETCH_BODY" '.provider // empty')"
assert "Provider models fetch returns models array" "array" "$(jq_value "$PROVIDER_FETCH_BODY" '.models | type')"

stage "Route Management"
ROUTE_PAYLOAD='{"scene":"default-chat","provider":"mock","upstreamModel":"gpt-4o-mini"}'
ADD_ROUTE_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/routes/gpt-4o-mini" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$ROUTE_PAYLOAD")"
assert_2xx "Add route" "$ADD_ROUTE_CODE"
ROUTE_LIST_BODY="$(http_body "$GATEWAY_URL/admin/routes" -H "$ADMIN_AUTH")"
assert "Route list" "true" "$(jq_value "$ROUTE_LIST_BODY" '.routes | has("gpt-4o-mini")')"
UPDATE_ROUTE_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/routes/gpt-4o-mini" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$ROUTE_PAYLOAD")"
assert_2xx "Update route (same PUT)" "$UPDATE_ROUTE_CODE"

stage "Pricing Configuration"
PRICING_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/system/pricing" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"models":{"gpt-4o-mini":{"unitPrice":0.005}},"default":{"unitPrice":0.001}}')"
assert_2xx "Set model pricing returns 2xx" "$PRICING_CODE"

stage "Chat API - Static Key"
CHAT_PAYLOAD='{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}],"stream":false}'
STATIC_CHAT_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" -H 'Authorization: Bearer demo-client-key' -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert_gt "Static key chat returns choices" "$(jq_number "$STATIC_CHAT_BODY" '.choices | length')" 0 "no choices returned"
assert "Response has usage" "true" "$(jq_value "$STATIC_CHAT_BODY" '.usage | has("total_tokens")')"
assert "Response model matches" "gpt-4o-mini" "$(jq_value "$STATIC_CHAT_BODY" '.model // empty')"
STEP=$((STEP + 1)); printf '  [%02d] Response has id ... ' "$STEP"; if [ -n "$(jq_value "$STATIC_CHAT_BODY" '.id // empty')" ]; then pass; else fail "id empty"; fi

stage "SSE Streaming (E006)"
SSE_CODE=$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H 'Authorization: Bearer demo-client-key' \
  -H 'Content-Type: application/json' \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}],"stream":true}')
assert_2xx "SSE returns 2xx" "$SSE_CODE"
SSE_BODY=$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H 'Authorization: Bearer demo-client-key' \
  -H 'Content-Type: application/json' \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}],"stream":true}')
assert_contains "SSE has data events" "$SSE_BODY" "data:"
assert_contains "SSE has DONE terminator" "$SSE_BODY" "[DONE]"

stage "Anthropic Adapter"
ANTHROPIC_PROV_PAYLOAD='{
    "type":"anthropic",
    "baseUrl":"http://localhost:'"$ANTHROPIC_MOCK_PORT"'",
    "apiKey":"sk-anthropic-mock",
    "timeout":"10s"
}'
ADD_ANTHROPIC_PROV_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/providers/anthropic-mock" \
    -H "$ADMIN_AUTH" \
    -H 'Content-Type: application/json' \
    -d "$ANTHROPIC_PROV_PAYLOAD")
assert_2xx "Add Anthropic provider" "$ADD_ANTHROPIC_PROV_CODE"

ANTHROPIC_ROUTE_PAYLOAD='{"scene":"default-chat","provider":"anthropic-mock","upstreamModel":"claude-3-haiku-20240307"}'
ADD_ANTHROPIC_ROUTE_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/routes/anthropic-test" \
    -H "$ADMIN_AUTH" \
    -H 'Content-Type: application/json' \
    -d "$ANTHROPIC_ROUTE_PAYLOAD")
assert_2xx "Add Anthropic route" "$ADD_ANTHROPIC_ROUTE_CODE"

ANTHROPIC_CHAT_BODY=$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" \
    -H 'Authorization: Bearer demo-client-key' \
    -H 'Content-Type: application/json' \
    -d '{"model":"anthropic-test","messages":[{"role":"user","content":"Hi"}],"max_tokens":100}')
ANTHROPIC_CHOICES=$(jq_number "$ANTHROPIC_CHAT_BODY" '.choices | length')
assert_gt "Anthropic chat returns choices" "$ANTHROPIC_CHOICES" 0
assert_contains "Anthropic chat body contains mock response" "$ANTHROPIC_CHAT_BODY" "Hello from Anthropic mock"

ANTHROPIC_SSE_BODY=$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" \
    -H 'Authorization: Bearer demo-client-key' \
    -H 'Content-Type: application/json' \
    -d '{"model":"anthropic-test","messages":[{"role":"user","content":"Hi"}],"max_tokens":100,"stream":true}')
assert_contains "Anthropic SSE contains data:" "$ANTHROPIC_SSE_BODY" "data:"
assert_contains "Anthropic SSE contains [DONE]" "$ANTHROPIC_SSE_BODY" "[DONE]"

ANTHROPIC_DEL_ROUTE_CODE=$(http_code -X DELETE "$GATEWAY_URL/admin/routes/anthropic-test" -H "$ADMIN_AUTH")
assert_status_one_of "Delete Anthropic route" "$ANTHROPIC_DEL_ROUTE_CODE" "200" "204"
ANTHROPIC_DEL_PROV_CODE=$(http_code -X DELETE "$GATEWAY_URL/admin/providers/anthropic-mock" -H "$ADMIN_AUTH")
assert_status_one_of "Delete Anthropic provider" "$ANTHROPIC_DEL_PROV_CODE" "200" "204"

stage "Chat API - JWT"
JWT_CHAT_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert_gt "JWT chat returns choices" "$(jq_number "$JWT_CHAT_BODY" '.choices | length')" 0 "no choices returned"
REQUESTS_BODY="$(http_body "$GATEWAY_URL/admin/requests/recent" -H "$ADMIN_AUTH")"
assert_gt "Request log has records" "$(jq_number "$REQUESTS_BODY" '.requests | length')" 1 "recent request log too short"

stage "Fallback (E012)"
# Start error mock on port 18081 for fallback testing
ERROR_MOCK_LOG="$LOG_DIR/error_mock.log"
kill_port_processes 18081 "error mock" || true
sleep 1
node "$PROJECT_DIR/jmeter/mock_error_server_node.mjs" >"$ERROR_MOCK_LOG" 2>&1 &
ERROR_MOCK_PID=$!
sleep 1
check "error mock process started" kill -0 "$ERROR_MOCK_PID"
check "error mock responds with 500" test "$(http_code 'http://localhost:18081/v1/chat/completions' -X POST -H 'Content-Type: application/json' -d '{}')" = "500"

# Add fallback provider pointing to error mock
FALLBACK_PROVIDER_PAYLOAD='{"type":"openai-compatible","baseUrl":"http://localhost:18081","apiKey":"sk-fail","timeout":"5s"}'
FB_PROV_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/providers/mock-fail" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$FALLBACK_PROVIDER_PAYLOAD")"
assert_2xx "Add fallback provider" "$FB_PROV_CODE"

# Create fallback route: primary=mock-fail (500), fallback=gpt-4o-mini (mock returns 200)
# Note: NO scene field — scene would redirect to openai-primary via default-chat scene config
FB_ROUTE_PAYLOAD='{"provider":"mock-fail","upstreamModel":"gpt-4o-mini","fallbackRoutes":["gpt-4o-mini"]}'
FB_ROUTE_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/routes/fallback-test" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$FB_ROUTE_PAYLOAD")"
assert_2xx "Add fallback route" "$FB_ROUTE_CODE"

# Make chat request — should succeed via fallback (mock-fail 500 → fallback to gpt-4o-mini → 200)
# Use admin JWT auth (static key has explicit allowed-models that may not include fallback-test)
FB_CHAT_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H "$ADMIN_AUTH" \
  -H 'Content-Type: application/json' \
  -d '{"model":"fallback-test","messages":[{"role":"user","content":"hello"}],"stream":false}')"
assert "Fallback succeeds (200 via gpt-4o-mini)" "200" "$FB_CHAT_CODE"

# Clean up fallback resources
http_code -X DELETE "$GATEWAY_URL/admin/routes/fallback-test" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
http_code -X DELETE "$GATEWAY_URL/admin/providers/mock-fail" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
kill_pid_gracefully "$ERROR_MOCK_PID" "error mock" || true
ERROR_MOCK_PID=""

# ── Provider 无效化/恢复 (配置热更新生效) ──
stage "Provider Config Hot Update"
PROV_PRE_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert_gt "Provider works before change" "$(jq_number "$PROV_PRE_BODY" '.choices | length')" 0

# Point provider to unreachable address → upstream calls should fail
BAD_URL_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/providers/mock" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' \
  -d '{"type":"openai-compatible","baseUrl":"http://localhost:18999","apiKey":"sk-bad","timeout":"3s"}')"
assert_2xx "Point provider to bad URL" "$BAD_URL_CODE"
sleep 1
BAD_CHAT_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
STEP=$((STEP + 1)); printf '  [%02d] Bad URL: chat fails with error ... ' "$STEP"; if [ "$BAD_CHAT_CODE" != "200" ]; then pass; else fail "got 200, expected error"; fi

# Restore working URL
RESTORE_URL_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/providers/mock" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' \
  -d '{"type":"openai-compatible","baseUrl":"'"$MOCK_URL"'","apiKey":"sk-mock","timeout":"10s"}')"
assert_2xx "Restore working provider URL" "$RESTORE_URL_CODE"
sleep 1
RESTORE_CHAT_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert_gt "Restored URL: chat succeeds again" "$(jq_number "$RESTORE_CHAT_BODY" '.choices | length')" 0

stage "User Flow"
ADMIN_CREATE_USER="admin-created-$(date +%s)"
ADMIN_CREATE_RESP="$(curl -s -w '\n%{http_code}' -X POST "$GATEWAY_URL/admin/users" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"username":"'"$ADMIN_CREATE_USER"'","password":"adminflow123","role":"USER"}' 2>/dev/null || true)"
ADMIN_CREATE_BODY="$(printf '%s' "$ADMIN_CREATE_RESP" | sed '$d')"
ADMIN_CREATE_CODE="$(printf '%s' "$ADMIN_CREATE_RESP" | sed -n '$p')"
assert "Admin create user returns 201" "201" "$ADMIN_CREATE_CODE"
assert "Admin create user returns username" "$ADMIN_CREATE_USER" "$(jq_value "$ADMIN_CREATE_BODY" '.username // empty')"
assert "Admin create user returns role" "USER" "$(jq_value "$ADMIN_CREATE_BODY" '.role // empty')"
assert "Admin create user returns frozen=false" "false" "$(jq_value "$ADMIN_CREATE_BODY" '.frozen')"
ADMIN_UPDATE_RESP="$(curl -s -w '\n%{http_code}' -X PUT "$GATEWAY_URL/admin/users/$ADMIN_CREATE_USER" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"role":"admin","frozen":true}' 2>/dev/null || true)"
ADMIN_UPDATE_BODY="$(printf '%s' "$ADMIN_UPDATE_RESP" | sed '$d')"
ADMIN_UPDATE_CODE="$(printf '%s' "$ADMIN_UPDATE_RESP" | sed -n '$p')"
assert "Admin update user returns 200" "200" "$ADMIN_UPDATE_CODE"
assert "Admin update user role changed" "admin" "$(jq_value "$ADMIN_UPDATE_BODY" '.role // empty')"
assert "Admin update user frozen changed" "true" "$(jq_value "$ADMIN_UPDATE_BODY" '.frozen')"
REGISTER_BODY="$(http_body -X POST "$GATEWAY_URL/auth/register" -H 'Content-Type: application/json' -d '{"username":"'"$TEST_USERNAME"'","password":"'"$TEST_PASSWORD"'"}')"
USER_API_KEY="$(jq_value "$REGISTER_BODY" '.apiKey // empty')"
USER_TOKEN="$(jq_value "$REGISTER_BODY" '.accessToken // empty')"
USER_AUTH="Authorization: Bearer $USER_TOKEN"
STEP=$((STEP + 1)); printf '  [%02d] Register new user ... ' "$STEP"; if [ -n "$USER_API_KEY" ]; then pass; else fail "apiKey empty"; fi
STEP=$((STEP + 1)); printf '  [%02d] Returns accessToken ... ' "$STEP"; if [ -n "$USER_TOKEN" ]; then pass; else fail "accessToken empty"; fi
assert "API key starts with gw-" "gw-" "${USER_API_KEY:0:3}" "got ${USER_API_KEY:0:3}, expected gw-"
USER_LOGIN_BODY="$(http_body -X POST "$GATEWAY_URL/auth/login" -H 'Content-Type: application/json' -d '{"username":"'"$TEST_USERNAME"'","password":"'"$TEST_PASSWORD"'"}')"
USER_LOGIN_REFRESH_TOKEN="$(jq_value "$USER_LOGIN_BODY" '.refreshToken // empty')"
STEP=$((STEP + 1)); printf '  [%02d] User login returns refreshToken ... ' "$STEP"; if [ -n "$USER_LOGIN_REFRESH_TOKEN" ] && [ "$USER_LOGIN_REFRESH_TOKEN" != "null" ]; then pass; else fail "refreshToken empty"; fi
USER_LIMITS_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/users/$TEST_USERNAME/limits" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"dailyTokens":100000}')"
assert_2xx "Set user limits" "$USER_LIMITS_CODE"
USER_MODELS_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/users/$TEST_USERNAME/allowed-models" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"allowedModels":["gpt-4o-mini"]}')"
assert_2xx "Set allowed models" "$USER_MODELS_CODE"
USER_CHAT_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" -H "Authorization: Bearer $USER_API_KEY" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert_gt "User key chat" "$(jq_number "$USER_CHAT_BODY" '.choices | length')" 0 "no choices returned"
ME_BODY="$(http_body "$GATEWAY_URL/auth/me" -H "$USER_AUTH")"
assert " /auth/me returns username" "$TEST_USERNAME" "$(jq_value "$ME_BODY" '.username // empty')"
KEY_CREATE_BODY="$(http_body -X POST "$GATEWAY_URL/auth/keys" -H "$USER_AUTH" -H 'Content-Type: application/json' -d '{"name":"regression-key","allowedModels":["gpt-4o-mini"]}')"
PERSONAL_KEY_ID="$(jq_value "$KEY_CREATE_BODY" '.keyId // empty')"
PERSONAL_API_KEY="$(jq_value "$KEY_CREATE_BODY" '.apiKey // empty')"
STEP=$((STEP + 1)); printf '  [%02d] Create personal key ... ' "$STEP"; if [ -n "$PERSONAL_API_KEY" ]; then pass; else fail "apiKey empty"; fi
STEP=$((STEP + 1)); printf '  [%02d] Capture personal keyId ... ' "$STEP"; if [ -n "$PERSONAL_KEY_ID" ]; then pass; else fail "keyId empty"; fi

# ── API Key 生命周期（禁用/启用） ──
stage "API Key Lifecycle (disable/enable)"

# Verify key works before disable
KEY_PRE_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" -H "Authorization: Bearer $PERSONAL_API_KEY" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert_gt "Personal key works before disable" "$(jq_number "$KEY_PRE_BODY" '.choices | length')" 0

# Toggle key to disabled
TOGGLE_OFF_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/users/$TEST_USERNAME/api-keys/$PERSONAL_KEY_ID/toggle" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"enabled":false}')"
assert_2xx "Disable key (toggle off)" "$TOGGLE_OFF_CODE"

# Chat with disabled key should fail (401 or 403)
DISABLED_KEY_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H "Authorization: Bearer $PERSONAL_API_KEY" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
STEP=$((STEP + 1)); printf '  [%02d] Disabled key returns 401/403 ... ' "$STEP"; if [ "$DISABLED_KEY_CODE" = "401" ] || [ "$DISABLED_KEY_CODE" = "403" ]; then pass; else fail "got $DISABLED_KEY_CODE, expected 401/403"; fi

# Toggle key back to enabled
TOGGLE_ON_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/users/$TEST_USERNAME/api-keys/$PERSONAL_KEY_ID/toggle" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"enabled":true}')"
assert_2xx "Re-enable key (toggle on)" "$TOGGLE_ON_CODE"

# Chat with re-enabled key should succeed again
ENABLED_KEY_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" -H "Authorization: Bearer $PERSONAL_API_KEY" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert_gt "Re-enabled key: chat succeeds" "$(jq_number "$ENABLED_KEY_BODY" '.choices | length')" 0

stage "Auth Session Lifecycle"
REFRESH_BODY="$(http_body -X POST "$GATEWAY_URL/auth/refresh" -H 'Content-Type: application/json' -d '{"refreshToken":"'"$USER_LOGIN_REFRESH_TOKEN"'"}')"
REFRESH_ACCESS_TOKEN="$(jq_value "$REFRESH_BODY" '.accessToken // empty')"
REFRESH_REFRESH_TOKEN="$(jq_value "$REFRESH_BODY" '.refreshToken // empty')"
assert "Refresh returns accessToken" "true" "$(if [ -n "$REFRESH_ACCESS_TOKEN" ] && [ "$REFRESH_ACCESS_TOKEN" != "null" ]; then echo "true"; else echo "false"; fi)"
assert "Refresh returns new refreshToken" "true" "$(if [ -n "$REFRESH_REFRESH_TOKEN" ] && [ "$REFRESH_REFRESH_TOKEN" != "null" ]; then echo "true"; else echo "false"; fi)"
LOGOUT_CODE="$(http_code -X POST "$GATEWAY_URL/auth/logout" -H 'Content-Type: application/json' -d '{"refreshToken":"'"$REFRESH_REFRESH_TOKEN"'"}')"
assert_status_one_of "Logout returns 200/204" "$LOGOUT_CODE" "200" "204"
REVOKED_REFRESH_CODE="$(http_code -X POST "$GATEWAY_URL/auth/refresh" -H 'Content-Type: application/json' -d '{"refreshToken":"'"$USER_LOGIN_REFRESH_TOKEN"'"}')"
assert "Original refresh token rejected after logout chain" "401" "$REVOKED_REFRESH_CODE"

stage "Rate Limiting (E007)"
# Create temp client with very low rate limit (1 req/min) to trigger 429
RL_CLIENT="ratelimit-$(date +%s)"
RL_CLIENT_BODY='{"enabled":true,"allowedModels":["gpt-4o-mini"],"limits":{"requestsPerWindow":1,"window":"PT1M"}}'
http_code -X PUT "$GATEWAY_URL/admin/clients/$RL_CLIENT" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$RL_CLIENT_BODY" >/dev/null 2>&1 || true
sleep 1
# First request should succeed
RL_FIRST_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H 'Authorization: Bearer '"$RL_CLIENT" \
  -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert "Rate-limit: first request 200" "200" "$RL_FIRST_CODE"
# Second request should be rate limited (429)
RL_SECOND_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H 'Authorization: Bearer '"$RL_CLIENT" \
  -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert "Rate-limit: second request 429" "429" "$RL_SECOND_CODE"
# Clean up temp client
http_code -X DELETE "$GATEWAY_URL/admin/clients/$RL_CLIENT" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true

stage "Quota Exceeded (E008/E009)"
QT_USER="qt-$(date +%s)"
QT_PASS="qtpass123"
QT_REG="$(http_body -X POST "$GATEWAY_URL/auth/register" -H 'Content-Type: application/json' -d '{"username":"'"$QT_USER"'","password":"'"$QT_PASS"'"}')"
QT_API_KEY="$(jq_value "$QT_REG" '.apiKey // empty')"
STEP=$((STEP + 1)); printf '  [%02d] Create quota test user ... ' "$STEP"; if [ -n "$QT_API_KEY" ]; then pass; else fail "apiKey empty"; fi
# Set dailyTokens=0 so even first request is rejected
http_code -X PUT "$GATEWAY_URL/admin/users/$QT_USER/limits" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"dailyTokens":0}' >/dev/null 2>&1 || true
sleep 1
QT_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H 'Authorization: Bearer '"$QT_API_KEY" \
  -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
QT_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H 'Authorization: Bearer '"$QT_API_KEY" \
  -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert "Quota exceeded returns 429" "429" "$QT_CODE"
assert_contains "Error mentions quota" "$QT_BODY" "quota"
# Cleanup
http_code -X DELETE "$GATEWAY_URL/admin/users/$QT_USER" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true

stage "Budget Exceeded (E010/E011)"
BD_USER="bt-$(date +%s)"
BD_PASS="btpass123"
BD_REG="$(http_body -X POST "$GATEWAY_URL/auth/register" -H 'Content-Type: application/json' -d '{"username":"'"$BD_USER"'","password":"'"$BD_PASS"'"}')"
BD_API_KEY="$(jq_value "$BD_REG" '.apiKey // empty')"
STEP=$((STEP + 1)); printf '  [%02d] Create budget test user ... ' "$STEP"; if [ -n "$BD_API_KEY" ]; then pass; else fail "apiKey empty"; fi
# Set dailyCost=0 so even first request exceeds budget
http_code -X PUT "$GATEWAY_URL/admin/users/$BD_USER/limits" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"dailyCost":0}' >/dev/null 2>&1 || true
sleep 1
BD_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H 'Authorization: Bearer '"$BD_API_KEY" \
  -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
BD_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H 'Authorization: Bearer '"$BD_API_KEY" \
  -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert "Budget exceeded returns 429" "429" "$BD_CODE"
assert_contains "Error mentions budget" "$BD_BODY" "budget"
# Cleanup
http_code -X DELETE "$GATEWAY_URL/admin/users/$BD_USER" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true

# ── 月配额优先于日配额 ──
stage "Monthly Quota Priority"
MQ_USER="mq-$(date +%s)"
MQ_PASS="mqpass123"
MQ_REG="$(http_body -X POST "$GATEWAY_URL/auth/register" -H 'Content-Type: application/json' -d '{"username":"'"$MQ_USER"'","password":"'"$MQ_PASS"'"}')"
MQ_API_KEY="$(jq_value "$MQ_REG" '.apiKey // empty')"
STEP=$((STEP + 1)); printf '  [%02d] Create monthly quota test user ... ' "$STEP"; if [ -n "$MQ_API_KEY" ]; then pass; else fail "apiKey empty"; fi
# Set dailyTokens=high but monthlyTokens=0 → monthly quota should be hit first
http_code -X PUT "$GATEWAY_URL/admin/users/$MQ_USER/limits" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' \
  -d '{"dailyTokens":100000,"monthlyTokens":0}' >/dev/null 2>&1 || true
sleep 1
MQ_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H "Authorization: Bearer $MQ_API_KEY" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
MQ_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H "Authorization: Bearer $MQ_API_KEY" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert "Monthly quota returns 429" "429" "$MQ_CODE"
assert_contains "Error mentions monthly_quota" "$MQ_BODY" "monthly"
# Cleanup
http_code -X DELETE "$GATEWAY_URL/admin/users/$MQ_USER" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true

stage "Management"
USERS_BODY="$(http_body "$GATEWAY_URL/admin/users" -H "$ADMIN_AUTH")"
assert_gt "User list contains testuser" "$(jq_number "$USERS_BODY" '[.users[] | select(.username=="'"$TEST_USERNAME"'")] | length')" 0 "user not found"
CLIENTS_BODY="$(http_body "$GATEWAY_URL/admin/clients" -H "$ADMIN_AUTH")"
assert_gt "Client list non-empty" "$(jq_number "$CLIENTS_BODY" '.clients | length')" 0 "clients empty"
EXPORT_BODY="$(http_body "$GATEWAY_URL/admin/config/export" -H "$ADMIN_AUTH")"
assert "Config export has providers" "true" "$(jq_value "$EXPORT_BODY" '.providers | has("mock")')"
assert "Config export has routes" "true" "$(jq_value "$EXPORT_BODY" '.routes | has("gpt-4o-mini")')"

stage "SPA Routing"
FRONTEND_INDEX="$PROJECT_DIR/frontend/dist/index.html"
if [ -f "$FRONTEND_INDEX" ]; then
    ROOT_BODY="$(http_body "$GATEWAY_URL/" -H 'Accept: text/html')"
    ROOT_HTML="false"
    if [[ "$ROOT_BODY" == *'<!DOCTYPE html>'* || "$ROOT_BODY" == *'<html'* ]]; then ROOT_HTML="true"; fi
    assert "Root returns index" "true" "$ROOT_HTML" "root response is not HTML index"
    SPA_BODY="$(http_body "$GATEWAY_URL/nonexistent-spa-route" -H 'Accept: text/html')"
    SPA_HTML="false"
    if [[ "$SPA_BODY" == *'<!DOCTYPE html>'* || "$SPA_BODY" == *'<html'* ]]; then SPA_HTML="true"; fi
    assert "SPA route returns index" "true" "$SPA_HTML" "nonexistent spa route did not return index"
    OPERATIONS_BODY="$(http_body "$GATEWAY_URL/operations" -H 'Accept: text/html')"
    OPERATIONS_HTML="false"
    if [[ "$OPERATIONS_BODY" == *'<!DOCTYPE html>'* || "$OPERATIONS_BODY" == *'<html'* ]]; then OPERATIONS_HTML="true"; fi
    assert "SPA route /operations returns index" "true" "$OPERATIONS_HTML" "/operations did not return index"
    # API path without Accept: text/html — JSON error proves SPA filter did NOT intercept
    V1_NO_ACCEPT_BODY="$(http_body "$GATEWAY_URL/v1/nonexistent-endpoint")"
    V1_NO_ACCEPT_CODE="$(http_code "$GATEWAY_URL/v1/nonexistent-endpoint")"
    V1_IS_JSON="false"
    if echo "$V1_NO_ACCEPT_BODY" | jq . >/dev/null 2>&1; then V1_IS_JSON="true"; fi
    STEP=$((STEP + 1)); printf '  [%02d] API path not intercepted (JSON error) ... ' "$STEP"
    if [ "$V1_IS_JSON" = "true" ] && [ "$V1_NO_ACCEPT_CODE" != "000" ]; then pass; else fail "got code=$V1_NO_ACCEPT_CODE, body not JSON"; fi

    HEALTH_HTML_CODE="$(http_code "$GATEWAY_URL/healthz" -H 'Accept: text/html')"
    assert "Health path not SPA fallback (406=API refused)" "406" "$HEALTH_HTML_CODE"
    STEP=$((STEP + 1)); printf '  [%02d] /v1/ excluded from SPA fallback ... ' "$STEP"
    V1_HTML_CODE="$(http_code "$GATEWAY_URL/v1/" -H 'Accept: text/html')"
    if [ "$V1_HTML_CODE" = "406" ]; then pass; else fail "got $V1_HTML_CODE, expected 406 (handler refused HTML)"; fi
else
    STEP=$((STEP + 1)); printf '  [%02d] Root returns index ... ✓ SKIP (frontend not built)\n' "$STEP"
    STEP=$((STEP + 1)); printf '  [%02d] SPA route returns index ... ✓ SKIP (frontend not built)\n' "$STEP"
    STEP=$((STEP + 1)); printf '  [%02d] SPA route /operations returns index ... ✓ SKIP (frontend not built)\n' "$STEP"
    STEP=$((STEP + 1)); printf '  [%02d] API path not intercepted (JSON error) ... ✓ SKIP (frontend not built)\n' "$STEP"
    STEP=$((STEP + 1)); printf '  [%02d] Health path not SPA fallback ... ✓ SKIP (frontend not built)\n' "$STEP"
    STEP=$((STEP + 1)); printf '  [%02d] /v1/ excluded from SPA fallback ... ✓ SKIP (frontend not built)\n' "$STEP"
fi

stage "Edge Cases"
NO_AUTH_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert "No auth returns 401" "401" "$NO_AUTH_CODE"
INVALID_AUTH_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H 'Authorization: Bearer invalid-token-x' -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert "Invalid token returns 401" "401" "$INVALID_AUTH_CODE"
BAD_MODEL_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" -H 'Authorization: Bearer demo-client-key' -H 'Content-Type: application/json' -d '{"model":"nonexistent-model","messages":[{"role":"user","content":"hello"}],"stream":false}')"
BAD_MODEL_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H 'Authorization: Bearer demo-client-key' -H 'Content-Type: application/json' -d '{"model":"nonexistent-model","messages":[{"role":"user","content":"hello"}],"stream":false}')"
assert "Nonexistent model returns 403" "403" "$BAD_MODEL_CODE"
assert "Health check public" "200" "$(http_code "$GATEWAY_URL/healthz")"
assert "Models list public" "200" "$(http_code "$GATEWAY_URL/v1/models")"

stage "Aggregate Reporting Consistency"
TODAY="$(date +%Y-%m-%d)"
sleep 2
USAGE_SUMMARY="$(http_body "$GATEWAY_URL/internal/usage/summary?client=demo-client-key&day=$TODAY" -H "$ADMIN_AUTH")"
assert "Usage summary accessible" "200" "$(http_code "$GATEWAY_URL/internal/usage/summary?client=demo-client-key&day=$TODAY" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] Usage totalTokens > 0 ... ' "$STEP"
UTOKENS="$(printf '%s' "$USAGE_SUMMARY" | jq '[.clients[].tokens] | add // 0')"
if [ "$UTOKENS" -gt 0 ] 2>/dev/null; then pass; else fail "totalTokens=$UTOKENS, expected > 0"; fi
COST_SUMMARY="$(http_body "$GATEWAY_URL/internal/cost/summary?client=demo-client-key&day=$TODAY" -H "$ADMIN_AUTH")"
assert "Cost summary accessible" "200" "$(http_code "$GATEWAY_URL/internal/cost/summary?client=demo-client-key&day=$TODAY" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] Cost totalCost > 0 ... ' "$STEP"
COST="$(printf '%s' "$COST_SUMMARY" | jq '[.clients[].cost] | add // 0')"
if [ "$COST" != "0" ] 2>/dev/null; then pass; else fail "totalCost=$COST, expected > 0"; fi

stage "Observability Endpoints"
TODAY="$(date +%Y-%m-%d)"

# 1. Admin dashboard overview — verify success rate structure
DASHBOARD="$(http_body "$GATEWAY_URL/admin/dashboard/overview" -H "$ADMIN_AUTH")"
assert "Dashboard overview accessible" "200" "$(http_code "$GATEWAY_URL/admin/dashboard/overview" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] Dashboard totalRequests > 0 ... ' "$STEP"
DASH_REQS="$(printf '%s' "$DASHBOARD" | jq '.overview.totalRequests // 0')"
if [ "$DASH_REQS" -gt 0 ] 2>/dev/null; then pass; else fail "totalRequests=$DASH_REQS, expected > 0"; fi

# 2. Cost by-model — verify model-level cost
COST_BY_MODEL="$(http_body "$GATEWAY_URL/internal/cost/by-model?day=$TODAY" -H "$ADMIN_AUTH")"
assert "Cost by-model accessible" "200" "$(http_code "$GATEWAY_URL/internal/cost/by-model?day=$TODAY" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] Cost by-model has entries ... ' "$STEP"
CBM_COUNT="$(printf '%s' "$COST_BY_MODEL" | jq '.models | length // 0')"
if [ "$CBM_COUNT" -gt 0 ] 2>/dev/null; then pass; else fail "models count=$CBM_COUNT, expected > 0"; fi

# 3. Cost by-client — verify per-client cost detail
COST_BY_CLIENT="$(http_body "$GATEWAY_URL/internal/cost/client?client=demo-client-key&from=$TODAY&to=$TODAY" -H "$ADMIN_AUTH")"
assert "Cost by-client accessible" "200" "$(http_code "$GATEWAY_URL/internal/cost/client?client=demo-client-key&from=$TODAY&to=$TODAY" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] Cost by-client has model entries ... ' "$STEP"
CBC_COUNT="$(printf '%s' "$COST_BY_CLIENT" | jq '.models | length // 0')"
if [ "$CBC_COUNT" -gt 0 ] 2>/dev/null; then pass; else fail "models count=$CBC_COUNT, expected > 0"; fi

# 4. Provider reporting
PROV_REPORT="$(http_body "$GATEWAY_URL/internal/reporting/providers?period=day&date=$TODAY" -H "$ADMIN_AUTH")"
assert "Provider reporting accessible" "200" "$(http_code "$GATEWAY_URL/internal/reporting/providers?period=day&date=$TODAY" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] Provider reporting has items ... ' "$STEP"
PROV_COUNT="$(printf '%s' "$PROV_REPORT" | jq '.items | length // 0')"
if [ "$PROV_COUNT" -gt 0 ] 2>/dev/null; then pass; else fail "items count=$PROV_COUNT, expected > 0"; fi

# 5. User reporting
USER_REPORT="$(http_body "$GATEWAY_URL/internal/reporting/users?period=day&date=$TODAY" -H "$ADMIN_AUTH")"
assert "User reporting accessible" "200" "$(http_code "$GATEWAY_URL/internal/reporting/users?period=day&date=$TODAY" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] User reporting has items ... ' "$STEP"
USER_COUNT="$(printf '%s' "$USER_REPORT" | jq '.items | length // 0')"
if [ "$USER_COUNT" -gt 0 ] 2>/dev/null; then pass; else fail "items count=$USER_COUNT, expected > 0"; fi

# 6. TPM overview
TPM_OVERVIEW="$(http_body "$GATEWAY_URL/internal/usage/tpm" -H "$ADMIN_AUTH")"
assert "TPM overview accessible" "200" "$(http_code "$GATEWAY_URL/internal/usage/tpm" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] TPM overview has windowStartedAt ... ' "$STEP"
TPM_WINDOW="$(printf '%s' "$TPM_OVERVIEW" | jq -r '.windowStartedAt // empty')"
if [ -n "$TPM_WINDOW" ]; then pass; else fail "windowStartedAt missing"; fi

# 7. System status
SYS_STATUS="$(http_body "$GATEWAY_URL/internal/system/status" -H "$ADMIN_AUTH")"
assert "System status accessible" "200" "$(http_code "$GATEWAY_URL/internal/system/status" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] System status has maintenanceActive ... ' "$STEP"
MAINT="$(printf '%s' "$SYS_STATUS" | jq -r '.maintenanceActive | type')"
if [ "$MAINT" = "boolean" ]; then pass; else fail "maintenanceActive not boolean ($MAINT)"; fi

# 8. Config audit endpoint — verify reachable
AUDIT_CODE="$(http_code "$GATEWAY_URL/internal/config/audit" -H "$ADMIN_AUTH")"
assert "Config audit accessible" "200" "$AUDIT_CODE"

# 9. Offset pagination — verify offset param works
OFFSET_REQ="$(http_body "$GATEWAY_URL/admin/requests/recent?limit=1&offset=0" -H "$ADMIN_AUTH")"
assert "Offset pagination accessible" "200" "$(http_code "$GATEWAY_URL/admin/requests/recent?limit=1&offset=0" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] Offset response has total field ... ' "$STEP"
OFFSET_TOTAL="$(printf '%s' "$OFFSET_REQ" | jq '.total // empty')"
if [ -n "$OFFSET_TOTAL" ]; then pass; else fail "total field missing"; fi

# 10. Request detail — verify /admin/requests/recent returns records then fetch one detail
STEP=$((STEP + 1)); printf '  [%02d] Request detail retrievable ... ' "$STEP"
FIRST_ID="$(printf '%s' "$OFFSET_REQ" | jq -r '.requests[0].requestId // empty')"
if [ -n "$FIRST_ID" ]; then
    DETAIL_BODY="$(http_body "$GATEWAY_URL/internal/requests/$FIRST_ID" -H "$ADMIN_AUTH")"
    DETAIL_CODE="$(http_code "$GATEWAY_URL/internal/requests/$FIRST_ID" -H "$ADMIN_AUTH")"
    assert "Request detail accessible" "200" "$DETAIL_CODE"
    assert "Request detail requestId matches" "$FIRST_ID" "$(jq_value "$DETAIL_BODY" '.request.requestId // empty')"
    EXPECTED_REQUEST_STATUS="$(printf '%s' "$OFFSET_REQ" | jq -r '.requests[0].status // empty')"
    if [ -n "$EXPECTED_REQUEST_STATUS" ] && [ "$EXPECTED_REQUEST_STATUS" != "null" ]; then
        assert "Request detail status matches recent entry" "$EXPECTED_REQUEST_STATUS" "$(jq_value "$DETAIL_BODY" '.request.status // empty')"
    fi
    EXPECTED_REQUEST_MODEL="$(printf '%s' "$OFFSET_REQ" | jq -r '.requests[0].model // empty')"
    if [ -n "$EXPECTED_REQUEST_MODEL" ] && [ "$EXPECTED_REQUEST_MODEL" != "null" ]; then
        assert "Request detail model matches recent entry" "$EXPECTED_REQUEST_MODEL" "$(jq_value "$DETAIL_BODY" '.request.model // empty')"
    fi
    STEP=$((STEP + 1)); printf '  [%02d] Request detail trace aligns when present ... ' "$STEP"
    DETAIL_TRACE_ID="$(jq_value "$DETAIL_BODY" '.trace.requestId // empty')"
    DETAIL_TRACE_STATUS="$(jq_value "$DETAIL_BODY" '.trace.status // empty')"
    if [ -z "$DETAIL_TRACE_ID" ] || { [ "$DETAIL_TRACE_ID" = "$FIRST_ID" ] && { [ -z "$DETAIL_TRACE_STATUS" ] || [ "$DETAIL_TRACE_STATUS" = "$EXPECTED_REQUEST_STATUS" ]; }; }; then
        pass
    else
        fail "trace requestId/status mismatch: traceId=$DETAIL_TRACE_ID traceStatus=$DETAIL_TRACE_STATUS expectedId=$FIRST_ID expectedStatus=$EXPECTED_REQUEST_STATUS"
    fi
else
    pass  # no records to test detail on — not a failure
fi

stage "TPM Exceeded (S020)"
TPM_USER="tpm-$(date +%s)"
TPM_PASS="tpmpass123"
TPM_REG="$(http_body -X POST "$GATEWAY_URL/auth/register" -H 'Content-Type: application/json' -d '{"username":"'"$TPM_USER"'","password":"'"$TPM_PASS"'"}')"
TPM_KEY="$(jq_value "$TPM_REG" '.apiKey // empty')"
STEP=$((STEP + 1)); printf '  [%02d] Create TPM test user ... ' "$STEP"; if [ -n "$TPM_KEY" ]; then pass; else fail "apiKey empty"; fi
http_code -X PUT "$GATEWAY_URL/admin/users/$TPM_USER/limits" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"tokensPerMinute":1}' >/dev/null 2>&1 || true
sleep 1
TPM_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H "Authorization: Bearer $TPM_KEY" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert "TPM exceeded returns 429" "429" "$TPM_CODE"
TPM_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" -H "Authorization: Bearer $TPM_KEY" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
assert_contains "Error mentions tpm" "$TPM_BODY" "tpm"
http_code -X DELETE "$GATEWAY_URL/admin/users/$TPM_USER" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true

stage "Upstream Timeout (S023)"
SLOW_PORT=18083
SLOW_LOG="$LOG_DIR/slow_mock.log"
kill_port_processes "$SLOW_PORT" "slow mock" || true
sleep 1
MOCK_SLOW_DELAY_MS=8000 node "$PROJECT_DIR/jmeter/mock_slow_server_node.mjs" >"$SLOW_LOG" 2>&1 &
SLOW_MOCK_PID=$!
sleep 1
check "slow mock process started" kill -0 "$SLOW_MOCK_PID"
check "slow mock responds with 200" test "$(http_code "http://localhost:$SLOW_PORT/v1/models")" = "200"

TIMEOUT_PROV_PAYLOAD='{"type":"openai-compatible","baseUrl":"http://localhost:'"$SLOW_PORT"'","apiKey":"sk-slow","timeout":"2s"}'
http_code -X PUT "$GATEWAY_URL/admin/providers/mock-slow" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$TIMEOUT_PROV_PAYLOAD" >/dev/null 2>&1 || true
sleep 1
TIMEOUT_ROUTE_PAYLOAD='{"provider":"mock-slow","upstreamModel":"gpt-4o-mini"}'
http_code -X PUT "$GATEWAY_URL/admin/routes/timeout-test" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$TIMEOUT_ROUTE_PAYLOAD" >/dev/null 2>&1 || true
sleep 1

TIMEOUT_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H 'Authorization: Bearer demo-client-key' -H 'Content-Type: application/json' -d '{"model":"timeout-test","messages":[{"role":"user","content":"hello"}],"stream":false}')"
assert "Upstream timeout returns 504" "504" "$TIMEOUT_CODE"
TIMEOUT_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" -H 'Authorization: Bearer demo-client-key' -H 'Content-Type: application/json' -d '{"model":"timeout-test","messages":[{"role":"user","content":"hello"}],"stream":false}')"
assert_contains "Error mentions upstream_timeout" "$TIMEOUT_BODY" "upstream_timeout"

http_code -X DELETE "$GATEWAY_URL/admin/routes/timeout-test" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
http_code -X DELETE "$GATEWAY_URL/admin/providers/mock-slow" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
kill_pid_gracefully "$SLOW_MOCK_PID" "slow mock" || true
SLOW_MOCK_PID=""

stage "Circuit Breaker Open (S022)"
# Restart error mock on 18081 (was cleaned up after Fallback stage)
ERROR_MOCK_LOG="$LOG_DIR/error_mock.log"
ERROR_MOCK_PORT=18081
kill_port_processes "$ERROR_MOCK_PORT" "error mock" || true
sleep 1
node "$PROJECT_DIR/jmeter/mock_error_server_node.mjs" >"$ERROR_MOCK_LOG" 2>&1 &
ERROR_MOCK_PID=$!
sleep 1
check "cb error mock process started" kill -0 "$ERROR_MOCK_PID"
check "cb error mock responds with 500" test "$(http_code 'http://localhost:18081/v1/chat/completions' -X POST -H 'Content-Type: application/json' -d '{}')" = "500"

CB_PROV_PAYLOAD='{"type":"openai-compatible","baseUrl":"http://localhost:18081","apiKey":"sk-cb","timeout":"5s"}'
http_code -X PUT "$GATEWAY_URL/admin/providers/mock-cb" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$CB_PROV_PAYLOAD" >/dev/null 2>&1 || true
CB_ROUTE_PAYLOAD='{"provider":"mock-cb","upstreamModel":"gpt-4o-mini"}'
http_code -X PUT "$GATEWAY_URL/admin/routes/cb-test" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$CB_ROUTE_PAYLOAD" >/dev/null 2>&1 || true
sleep 1

# Make enough requests to trigger circuit breaker (min 10 calls, 50% failure = ~5 fails)
# Each request to error mock returns 500, which counts as a failure
for _ in $(seq 1 10); do
  http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
    -H 'Authorization: Bearer demo-client-key' \
    -H 'Content-Type: application/json' \
    -d '{"model":"cb-test","messages":[{"role":"user","content":"hello"}],"stream":false}' >/dev/null 2>&1 || true
done

# After enough failures, the circuit breaker should open and return 503 circuit_breaker_open
CB_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H 'Authorization: Bearer demo-client-key' \
  -H 'Content-Type: application/json' \
  -d '{"model":"cb-test","messages":[{"role":"user","content":"hello"}],"stream":false}')"
assert "Circuit breaker open returns 503" "503" "$CB_CODE"
CB_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H 'Authorization: Bearer demo-client-key' \
  -H 'Content-Type: application/json' \
  -d '{"model":"cb-test","messages":[{"role":"user","content":"hello"}],"stream":false}')"
assert_contains "Error mentions circuit_breaker" "$CB_BODY" "circuit_breaker"

http_code -X DELETE "$GATEWAY_URL/admin/routes/cb-test" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
http_code -X DELETE "$GATEWAY_URL/admin/providers/mock-cb" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true

stage "WRR Load Balancing"

WRA_PROV_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/providers/wrr-a" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"type":"openai-compatible","baseUrl":"'"$MOCK_URL"'","apiKey":"sk-wrr-a","timeout":"5s"}')"
assert_2xx "wrr-a provider created" "$WRA_PROV_CODE"

WRB_PROV_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/providers/wrr-b" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"type":"openai-compatible","baseUrl":"'"$MOCK_URL"'","apiKey":"sk-wrr-b","timeout":"5s"}')"
assert_2xx "wrr-b provider created" "$WRB_PROV_CODE"

WRR_HEAVY_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/routes/wrr-heavy" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"provider":"wrr-a","upstreamModel":"gpt-4o-mini","fallbackRoutes":["wrr-light"],"weight":3}')"
assert_2xx "wrr-heavy route created" "$WRR_HEAVY_CODE"

WRR_LIGHT_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/routes/wrr-light" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"provider":"wrr-b","upstreamModel":"gpt-4o-mini","fallbackRoutes":["wrr-heavy"],"weight":1}')"
assert_2xx "wrr-light route created" "$WRR_LIGHT_CODE"

WRR_ENABLE_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/system/load-balancer" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"enabled":true}')"
assert "Load balancer enabled" "200" "$WRR_ENABLE_CODE"

STEP=$((STEP + 1)); printf '  [%02d] Call chat 20 times and collect WRR distribution ... ' "$STEP"
WRR_HEAVY_COUNT=0
WRR_LIGHT_COUNT=0
WRR_LOOP_ERROR=""
for _ in $(seq 1 20); do
    WRR_CHAT_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
      -H 'Authorization: Bearer demo-client-key' \
      -H 'Content-Type: application/json' \
      -d '{"model":"wrr-heavy","messages":[{"role":"user","content":"hello"}],"stream":false}')"
    if [ "$WRR_CHAT_CODE" != "200" ]; then
        WRR_LOOP_ERROR="wrr chat returned $WRR_CHAT_CODE"
        break
    fi

    WRR_RECENT_BODY="$(http_body "$GATEWAY_URL/admin/requests/recent?limit=1&model=wrr-heavy" -H "$ADMIN_AUTH")"
    WRR_ROUTE_ID="$(jq_value "$WRR_RECENT_BODY" '.requests[0].routeId // empty')"
    if [ "$WRR_ROUTE_ID" = "wrr-heavy" ]; then
        WRR_HEAVY_COUNT=$((WRR_HEAVY_COUNT + 1))
    elif [ "$WRR_ROUTE_ID" = "wrr-light" ]; then
        WRR_LIGHT_COUNT=$((WRR_LIGHT_COUNT + 1))
    else
        WRR_LOOP_ERROR="unexpected WRR routeId=$WRR_ROUTE_ID"
        break
    fi
done

if [ -z "$WRR_LOOP_ERROR" ] && [ $((WRR_HEAVY_COUNT + WRR_LIGHT_COUNT)) -eq 20 ]; then
    pass
else
    fail "${WRR_LOOP_ERROR:-heavy=$WRR_HEAVY_COUNT light=$WRR_LIGHT_COUNT total=$((WRR_HEAVY_COUNT + WRR_LIGHT_COUNT)), expected total=20}"
fi

STEP=$((STEP + 1)); printf '  [%02d] WRR heavy route selected more often ... ' "$STEP"
if [ "$WRR_HEAVY_COUNT" -ge 12 ] && [ "$WRR_LIGHT_COUNT" -le 8 ] && [ "$WRR_HEAVY_COUNT" -gt "$WRR_LIGHT_COUNT" ]; then
    pass
else
    fail "heavy=$WRR_HEAVY_COUNT light=$WRR_LIGHT_COUNT, expected heavy>=12 light<=8 and heavy>light"
fi

WRR_DISABLE_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/system/load-balancer" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"enabled":false}')"
assert "Load balancer disabled" "200" "$WRR_DISABLE_CODE"

WRR_DEL_HEAVY_ROUTE="$(http_code -X DELETE "$GATEWAY_URL/admin/routes/wrr-heavy" -H "$ADMIN_AUTH")"
WRR_DEL_LIGHT_ROUTE="$(http_code -X DELETE "$GATEWAY_URL/admin/routes/wrr-light" -H "$ADMIN_AUTH")"
WRR_DEL_A_PROVIDER="$(http_code -X DELETE "$GATEWAY_URL/admin/providers/wrr-a" -H "$ADMIN_AUTH")"
WRR_DEL_B_PROVIDER="$(http_code -X DELETE "$GATEWAY_URL/admin/providers/wrr-b" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] Cleanup WRR resources ... ' "$STEP"
if [ "$WRR_DEL_HEAVY_ROUTE" -lt 300 ] && [ "$WRR_DEL_LIGHT_ROUTE" -lt 300 ] && [ "$WRR_DEL_A_PROVIDER" -lt 300 ] && [ "$WRR_DEL_B_PROVIDER" -lt 300 ]; then
    pass
else
    fail "routeHeavy=$WRR_DEL_HEAVY_ROUTE routeLight=$WRR_DEL_LIGHT_ROUTE providerA=$WRR_DEL_A_PROVIDER providerB=$WRR_DEL_B_PROVIDER"
fi

# ── 全部 Provider 不可用（主路由 + 回退全部失败） ──
stage "All Providers Unavailable"
# Error mock on 18081 is still running from Circuit Breaker test
ALL_FAIL_PROV='{"type":"openai-compatible","baseUrl":"http://localhost:18081","apiKey":"sk-allfail","timeout":"5s"}'
http_code -X PUT "$GATEWAY_URL/admin/providers/mock-allfail" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$ALL_FAIL_PROV" >/dev/null 2>&1 || true
# Route where both primary and fallback point to error mock → all fail
ALL_FAIL_ROUTE='{"provider":"mock-allfail","upstreamModel":"gpt-4o-mini","fallbackRoutes":["gpt-4o-mini"]}'
http_code -X PUT "$GATEWAY_URL/admin/routes/all-fail" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$ALL_FAIL_ROUTE" >/dev/null 2>&1 || true
sleep 1

ALL_FAIL_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H "$ADMIN_AUTH" -H 'Content-Type: application/json' \
  -d '{"model":"all-fail","messages":[{"role":"user","content":"hello"}],"stream":false}')"
STEP=$((STEP + 1)); printf '  [%02d] All providers unavailable returns error ... ' "$STEP"; if [ "$ALL_FAIL_CODE" != "200" ] && [ "$ALL_FAIL_CODE" != "000" ]; then pass; else fail "got $ALL_FAIL_CODE, expected non-200"; fi
ALL_FAIL_BODY="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H "$ADMIN_AUTH" -H 'Content-Type: application/json' \
  -d '{"model":"all-fail","messages":[{"role":"user","content":"hello"}],"stream":false}')"
STEP=$((STEP + 1)); printf '  [%02d] Error body contains upstream_error or circuit_breaker ... ' "$STEP"; if [[ "$ALL_FAIL_BODY" == *"upstream"* ]] || [[ "$ALL_FAIL_BODY" == *"circuit"* ]]; then pass; else fail "body=${ALL_FAIL_BODY:0:80}"; fi

# Cleanup all-fail resources
http_code -X DELETE "$GATEWAY_URL/admin/routes/all-fail" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
http_code -X DELETE "$GATEWAY_URL/admin/providers/mock-allfail" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true

stage "Hot Route Reload (S027)"
HOT_PROV_PAYLOAD='{"type":"openai-compatible","baseUrl":"'"$MOCK_URL"'","apiKey":"sk-hot","timeout":"5s"}'
http_code -X PUT "$GATEWAY_URL/admin/providers/mock-hot" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$HOT_PROV_PAYLOAD" >/dev/null 2>&1 || true
HOT_ROUTE_PAYLOAD='{"provider":"mock-hot","upstreamModel":"gpt-4o-mini"}'
http_code -X PUT "$GATEWAY_URL/admin/routes/hot-test" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$HOT_ROUTE_PAYLOAD" >/dev/null 2>&1 || true
sleep 1

# Route was added at runtime (no restart). Immediately use it.
HOT_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H 'Authorization: Bearer demo-client-key' \
  -H 'Content-Type: application/json' \
  -d '{"model":"hot-test","messages":[{"role":"user","content":"hello"}],"stream":false}')"
assert "Hot-reloaded route works immediately" "200" "$HOT_CODE"

# Delete route and add it back with a different upstream model to test update
http_code -X DELETE "$GATEWAY_URL/admin/routes/hot-test" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
HOT_ROUTE_V2='{"provider":"mock-hot","upstreamModel":"gpt-4o-mini","fallbackRoutes":["gpt-4o-mini"]}'
http_code -X PUT "$GATEWAY_URL/admin/routes/hot-test" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$HOT_ROUTE_V2" >/dev/null 2>&1 || true
sleep 1
HOT_V2_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
  -H 'Authorization: Bearer demo-client-key' \
  -H 'Content-Type: application/json' \
  -d '{"model":"hot-test","messages":[{"role":"user","content":"hello"}],"stream":false}')"
assert "Hot-reloaded route survives delete+recreate" "200" "$HOT_V2_CODE"

http_code -X DELETE "$GATEWAY_URL/admin/routes/hot-test" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
http_code -X DELETE "$GATEWAY_URL/admin/providers/mock-hot" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true

stage "System Config APIs"
# Test admin API endpoints for system configs (persist-only, runtime effect requires restart)
SYS_PRICING_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/system/pricing" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' \
  -d '{"models":{"gpt-4o-mini":{"inputUnitPrice":0.001,"outputUnitPrice":0.004}},"default":{"unitPrice":0.002}}')"
assert "System pricing API persists" "200" "$SYS_PRICING_CODE"

SYS_RESILIENCE_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/system/resilience" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' \
  -d '{"maxAttempts":3,"retryableFailureThreshold":3}')"
assert "System resilience API persists" "200" "$SYS_RESILIENCE_CODE"

SYS_OPERATIONAL_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/system/operational" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' \
  -d '{"maintenanceMode":false,"emergencyRateLimit":{"enabled":false,"maxRequestsPerMinute":100}}')"
assert "System operational API persists" "200" "$SYS_OPERATIONAL_CODE"

# Reset pricing to original so cost verification still works
http_code -X PUT "$GATEWAY_URL/admin/system/pricing" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' \
  -d '{"models":{"gpt-4o-mini":{"unitPrice":0.005}},"default":{"unitPrice":0.001}}' >/dev/null 2>&1 || true

stage "Cleanup Resources"
DELETE_ADMIN_CREATE_CODE="$(http_code -X DELETE "$GATEWAY_URL/admin/users/$ADMIN_CREATE_USER" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] Delete admin-created user ... ' "$STEP"; if [[ "$DELETE_ADMIN_CREATE_CODE" =~ ^[0-9]+$ ]] && [ "$DELETE_ADMIN_CREATE_CODE" -lt 300 ]; then pass; else fail "got $DELETE_ADMIN_CREATE_CODE, expected < 300"; fi
RESET_PASSWORD_BODY="$(http_body -X POST "$GATEWAY_URL/admin/users/$TEST_USERNAME/reset-password" -H "$ADMIN_AUTH")"
RESET_TEMP_PASSWORD="$(jq_value "$RESET_PASSWORD_BODY" '.temporaryPassword // empty')"
assert "Reset password returns temporaryPassword" "true" "$(if [ -n "$RESET_TEMP_PASSWORD" ] && [ "$RESET_TEMP_PASSWORD" != "null" ]; then echo "true"; else echo "false"; fi)"
OLD_PASSWORD_LOGIN_CODE="$(http_code -X POST "$GATEWAY_URL/auth/login" -H 'Content-Type: application/json' -d '{"username":"'"$TEST_USERNAME"'","password":"'"$TEST_PASSWORD"'"}')"
assert "Old password rejected after reset" "401" "$OLD_PASSWORD_LOGIN_CODE"
TEMP_PASSWORD_LOGIN_BODY="$(http_body -X POST "$GATEWAY_URL/auth/login" -H 'Content-Type: application/json' -d '{"username":"'"$TEST_USERNAME"'","password":"'"$RESET_TEMP_PASSWORD"'"}')"
assert "Temporary password login returns accessToken" "true" "$(if [ -n "$(jq_value "$TEMP_PASSWORD_LOGIN_BODY" '.accessToken // empty')" ]; then echo "true"; else echo "false"; fi)"
DELETE_USER_CODE="$(http_code -X DELETE "$GATEWAY_URL/admin/users/$TEST_USERNAME" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] Delete test user ... ' "$STEP"; if [[ "$DELETE_USER_CODE" =~ ^[0-9]+$ ]] && [ "$DELETE_USER_CODE" -lt 300 ]; then pass; else fail "got $DELETE_USER_CODE, expected < 300"; fi
DELETE_ROUTE_CODE="$(http_code -X DELETE "$GATEWAY_URL/admin/routes/gpt-4o-mini" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] Delete route ... ' "$STEP"; if [[ "$DELETE_ROUTE_CODE" =~ ^[0-9]+$ ]] && [ "$DELETE_ROUTE_CODE" -lt 300 ]; then pass; else fail "got $DELETE_ROUTE_CODE, expected < 300"; fi
DELETE_PROVIDER_CODE="$(http_code -X DELETE "$GATEWAY_URL/admin/providers/mock" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] Delete provider ... ' "$STEP"; if [[ "$DELETE_PROVIDER_CODE" =~ ^[0-9]+$ ]] && [ "$DELETE_PROVIDER_CODE" -lt 300 ]; then pass; else fail "got $DELETE_PROVIDER_CODE, expected < 300"; fi

# Additional regression coverage to meet comprehensive target
stage "Post-cleanup Verification"
assert "Deleted admin-created user no longer listed" "0" "$(jq_number "$(http_body "$GATEWAY_URL/admin/users" -H "$ADMIN_AUTH")" '[.users[] | select(.username=="'"$ADMIN_CREATE_USER"'")] | length')"
assert "Deleted user no longer listed" "0" "$(jq_number "$(http_body "$GATEWAY_URL/admin/users" -H "$ADMIN_AUTH")" '[.users[] | select(.username=="'"$TEST_USERNAME"'")] | length')"
assert "Deleted route no longer listed" "false" "$(jq_value "$(http_body "$GATEWAY_URL/admin/routes" -H "$ADMIN_AUTH")" '.routes | has("gpt-4o-mini")')"
assert "Deleted provider no longer listed" "false" "$(jq_value "$(http_body "$GATEWAY_URL/admin/providers" -H "$ADMIN_AUTH")" '.providers | has("mock")')"
assert "Admin token still valid for /auth/me" "admin" "$(jq_value "$(http_body "$GATEWAY_URL/auth/me" -H "$ADMIN_AUTH")" '.username // empty')"
assert "Personal key format is gw-" "gw-" "${PERSONAL_API_KEY:0:3}" "got ${PERSONAL_API_KEY:0:3}, expected gw-"
assert_gt "/auth/keys lists at least one key" "$(jq_number "$(http_body "$GATEWAY_URL/auth/keys" -H "$USER_AUTH")" '.keys | length')" 0 "no keys found"
assert "Request log endpoint remains accessible" "200" "$(http_code "$GATEWAY_URL/admin/requests/recent?limit=5" -H "$ADMIN_AUTH")"
assert "Config export still returns system block" "true" "$(jq_value "$(http_body "$GATEWAY_URL/admin/config/export" -H "$ADMIN_AUTH")" 'has("system")')"
assert "Health live remains 200 after cleanup" "200" "$(http_code "$GATEWAY_URL/healthz/live")"
PUBLIC_MODEL_COUNT="$(jq_number "$(http_body "$GATEWAY_URL/v1/models")" '.data | length')"
STEP=$((STEP + 1)); printf '  [%02d] Public models list accessible (count=%s) ... ' "$STEP" "$PUBLIC_MODEL_COUNT"
if [ "$PUBLIC_MODEL_COUNT" -ge 0 ] 2>/dev/null; then pass; else fail "model list inaccessible"; fi

# ── Summary ──
printf '\n══ Summary ══\n'
printf '  PASS: %s\n' "$PASS"
printf '  FAIL: %s\n' "$FAIL"
printf '  TOTAL: %s\n' "$TOTAL"

if [ "$FAIL" -eq 0 ]; then
    exit 0
fi
exit 1

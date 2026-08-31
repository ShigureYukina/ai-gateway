#!/usr/bin/env bash
# ============================================================================
# AI Gateway — 历史问题回归模板脚本（纯后端）
#
# 目标：提供一个"可直接复用"的黑盒回归模板，优先覆盖历史上容易复发的问题类型。
# 覆盖模板：
#   1. 空集合/空字段校验：模型分组空 members 应返回 400，而不是 500
#   2. 整体替换 vs 局部更新：/auth/keys PATCH 仅更新指定字段，不误伤 allowedModels
#   3. 状态码一致性：create / update / delete 各覆盖 1 个点，接受仓库当前真实语义
#   4. 热更新后即时生效：用户 allowed-models 变更后，后续调用行为立即变化
#   5. 路径/版本类接口：internal/config 回滚非法版本返回 4xx，而不是 500
#
# 用法：
#   ./scripts/verify-regression-patterns.sh
#   ./scripts/verify-regression-patterns.sh --skip-setup
#   ./scripts/verify-regression-patterns.sh --skip-teardown
#   ./scripts/verify-regression-patterns.sh --help
#
# 环境变量：
#   GATEWAY_URL   网关地址（默认 http://localhost:8081）
#   MOCK_URL      Mock upstream 地址（默认 http://localhost:18080）
#
# 依赖：curl, jq, java(21+), node
# 不依赖：Docker / Testcontainers
# ============================================================================

set -euo pipefail
# AI Gateway — 历史问题回归模板脚本（纯后端）
#
# 目标：提供一个“可直接复用”的黑盒回归模板，优先覆盖历史上容易复发的问题类型。
# 覆盖模板：
#   1. 空集合/空字段校验：模型分组空 members 应返回 400，而不是 500
#   2. 整体替换 vs 局部更新：/auth/keys PATCH 仅更新指定字段，不误伤 allowedModels
#   3. 状态码一致性：create / update / delete 各覆盖 1 个点，接受仓库当前真实语义
#   4. 热更新后即时生效：用户 allowed-models 变更后，后续调用行为立即变化
#   5. 路径/版本类接口：internal/config 回滚非法版本返回 4xx，而不是 500
#
# 用法：
#   ./scripts/verify-regression-patterns.sh
#   ./scripts/verify-regression-patterns.sh --skip-setup
#   ./scripts/verify-regression-patterns.sh --skip-teardown
#   ./scripts/verify-regression-patterns.sh --help
#
# 环境变量：
#   GATEWAY_URL   网关地址（默认 http://localhost:8081）
#   MOCK_URL      Mock upstream 地址（默认 http://localhost:18080）
#
# 依赖：curl, jq, java(21+), node
# 不依赖：Docker / Testcontainers
# ============================================================================

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_DIR/scripts/lib.sh"

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
MOCK_URL="${MOCK_URL:-http://localhost:18080}"
MOCK_SCRIPT="$PROJECT_DIR/jmeter/mock_openai_server_node.mjs"
JAR_PATH="$PROJECT_DIR/bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar"
LOG_DIR="$PROJECT_DIR/.verify-regression-patterns-logs"
MOCK_LOG="$LOG_DIR/mock.log"
GATEWAY_LOG="$LOG_DIR/gateway.log"

SKIP_SETUP=false
SKIP_TEARDOWN=false

GATEWAY_PID=""
MOCK_PID=""

ADMIN_TOKEN=""
ADMIN_AUTH=""

PROVIDER_NAME="pattern-mock"
ROUTE_A="pattern-route-a"
ROUTE_B="pattern-route-b"
GROUP_EMPTY_NAME="pattern-empty-members"
TEST_USERNAME="pattern-user"
TEST_PASSWORD="pattern-pass123"
USER_API_KEY=""
USER_TOKEN=""
USER_AUTH=""
PERSONAL_KEY_ID=""

CHAT_A_PAYLOAD='{"model":"'"$ROUTE_A"'","messages":[{"role":"user","content":"请回复 pattern-a"}],"stream":false}'
CHAT_B_PAYLOAD='{"model":"'"$ROUTE_B"'","messages":[{"role":"user","content":"请回复 pattern-b"}],"stream":false}'

show_help() {
    cat <<'EOF'
AI Gateway 历史问题回归模板脚本（纯后端）

用法：
  ./scripts/verify-regression-patterns.sh
  ./scripts/verify-regression-patterns.sh --skip-setup
  ./scripts/verify-regression-patterns.sh --skip-teardown
  ./scripts/verify-regression-patterns.sh --help

环境变量：
  GATEWAY_URL   默认 http://localhost:8081
  MOCK_URL      默认 http://localhost:18080
EOF
}

assert_status_one_of() {
    local desc="$1"
    local actual="$2"
    shift 2
    local expected_list="$*"
    local expected

    STEP=$((STEP + 1))
    printf '  [%02d] %s ... ' "$STEP" "$desc"
    for expected in "$@"; do
        if [ "$actual" = "$expected" ]; then
            pass
            return 0
        fi
    done
    fail "got $actual, expected one of: $expected_list"
}

wait_for_gateway() {
    printf '[%s] 等待 gateway 就绪' "$(timestamp)"
    for _ in $(seq 1 30); do
        if [ "$(http_code "$GATEWAY_URL/healthz/live")" = "200" ]; then
            printf ' 完成\n'
            return 0
        fi
        printf '.'
        sleep 1
    done
    printf '\n'
    return 1
}

start_mock() {
    require_file "$MOCK_SCRIPT" "mock upstream 脚本"
    mkdir -p "$LOG_DIR"
    info "启动 mock upstream：$MOCK_URL"
    node "$MOCK_SCRIPT" >"$MOCK_LOG" 2>&1 &
    MOCK_PID=$!
    sleep 2
    if ! kill -0 "$MOCK_PID" 2>/dev/null; then
        log_line "$RED" "mock upstream 启动失败，请查看 $MOCK_LOG"
        exit 2
    fi
}

start_gateway() {
    require_file "$JAR_PATH" "bootstrap JAR"
    mkdir -p "$LOG_DIR"
    info "启动 gateway：$GATEWAY_URL"
    java -jar "$JAR_PATH" \
        --server.port="$(port_from_url "$GATEWAY_URL")" \
        --spring.profiles.active=local \
        --gateway.shared-state.backend=in_memory \
        --spring.flyway.enabled=false >"$GATEWAY_LOG" 2>&1 &
    GATEWAY_PID=$!
    if ! wait_for_gateway; then
        log_line "$RED" "gateway 启动超时，请查看 $GATEWAY_LOG"
        exit 2
    fi
}

stop_all() {
    if [ "$SKIP_TEARDOWN" = true ]; then
        info "跳过进程清理（--skip-teardown）"
        return 0
    fi
    kill_pid_gracefully "$GATEWAY_PID" "gateway"
    kill_pid_gracefully "$MOCK_PID" "mock upstream"
}

cleanup_remote_resources() {
    if [ -n "$ADMIN_AUTH" ]; then
        http_code -X DELETE "$GATEWAY_URL/admin/users/$TEST_USERNAME" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
        http_code -X DELETE "$GATEWAY_URL/admin/routes/$ROUTE_A" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
        http_code -X DELETE "$GATEWAY_URL/admin/routes/$ROUTE_B" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
        http_code -X DELETE "$GATEWAY_URL/admin/providers/$PROVIDER_NAME" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
        http_code -X DELETE "$GATEWAY_URL/admin/model-groups/$GROUP_EMPTY_NAME" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
    fi
}

cleanup() {
    cleanup_remote_resources
    stop_all
}

login_admin() {
    local body
    body="$(http_body -X POST "$GATEWAY_URL/auth/login" -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}')"
    ADMIN_TOKEN="$(jq_value "$body" '.accessToken // empty')"
    ADMIN_AUTH="Authorization: Bearer $ADMIN_TOKEN"
    check "管理员登录成功" test -n "$ADMIN_TOKEN"
}

register_user() {
    local body
    body="$(http_body -X POST "$GATEWAY_URL/auth/register" -H 'Content-Type: application/json' -d '{"username":"'"$TEST_USERNAME"'","password":"'"$TEST_PASSWORD"'"}')"
    USER_API_KEY="$(jq_value "$body" '.apiKey // empty')"
    USER_TOKEN="$(jq_value "$body" '.accessToken // empty')"
    USER_AUTH="Authorization: Bearer $USER_TOKEN"
    check "注册测试用户成功" test -n "$USER_API_KEY"
    check "注册返回 accessToken" test -n "$USER_TOKEN"
}

prepare_provider_and_routes() {
    local provider_code route_a_code route_b_code
    provider_code="$(http_code -X PUT "$GATEWAY_URL/admin/providers/$PROVIDER_NAME" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"type":"openai-compatible","baseUrl":"'"$MOCK_URL"'","apiKey":"sk-pattern","timeout":"10s"}')"
    assert_status_one_of "创建测试 Provider" "$provider_code" 200 201

    route_a_code="$(http_code -X PUT "$GATEWAY_URL/admin/routes/$ROUTE_A" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"scene":"default-chat","provider":"'"$PROVIDER_NAME"'","upstreamModel":"gpt-4o-mini"}')"
    assert_status_one_of "创建 Route A" "$route_a_code" 200 201

    route_b_code="$(http_code -X PUT "$GATEWAY_URL/admin/routes/$ROUTE_B" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"scene":"default-chat","provider":"'"$PROVIDER_NAME"'","upstreamModel":"gpt-4o-mini"}')"
    assert_status_one_of "创建 Route B" "$route_b_code" 200 201
}

prepare_user_access() {
    local limits_code models_code
    limits_code="$(http_code -X PUT "$GATEWAY_URL/admin/users/$TEST_USERNAME/limits" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"dailyTokens":100000,"dailyCost":50.0}')"
    assert "设置测试用户额度返回 200" "200" "$limits_code"

    models_code="$(http_code -X PUT "$GATEWAY_URL/admin/users/$TEST_USERNAME/allowed-models" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"allowedModels":["'"$ROUTE_A"'"]}')"
    assert "初始化用户 allowed-models 返回 200" "200" "$models_code"
}

chat_code_with_key() {
    local api_key="$1"
    local payload="$2"
    http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
        -H "Authorization: Bearer $api_key" \
        -H 'Content-Type: application/json' \
        -d "$payload"
}

gateway_port="$(port_from_url "$GATEWAY_URL")"
mock_port="$(port_from_url "$MOCK_URL")"

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-setup)
            SKIP_SETUP=true
            ;;
        --skip-teardown)
            SKIP_TEARDOWN=true
            ;;
        --help)
            show_help
            exit 0
            ;;
        *)
            log_line "$RED" "未知参数：$1"
            exit 1
            ;;
    esac
    shift
done

trap cleanup EXIT

stage "环境检查"
check "curl 已安装" command -v curl
check "jq 已安装" command -v jq
check "java 已安装" command -v java
check "node 已安装" command -v node

JAVA_VERSION_OUTPUT="$(java -version 2>&1 || true)"
JAVA_MAJOR="0"
JAVA_VERSION_PATTERN='version[[:space:]]+"([0-9]+)'
if [[ "$JAVA_VERSION_OUTPUT" =~ $JAVA_VERSION_PATTERN ]]; then
    JAVA_MAJOR="${BASH_REMATCH[1]}"
fi
assert_gt "Java 版本为 21+" "${JAVA_MAJOR:-0}" 20 "当前版本 ${JAVA_MAJOR:-unknown}，期望 >= 21"

if [ "$SKIP_SETUP" = false ]; then
    check "lsof 已安装" command -v lsof
    check "scripts 目录存在" test -d "$PROJECT_DIR/scripts"
    check "mock 脚本存在" test -f "$MOCK_SCRIPT"
    kill_port_processes "$gateway_port" "gateway"
    kill_port_processes "$mock_port" "mock upstream"
    assert "gateway 端口空闲" "false" "$(if is_port_busy "$gateway_port"; then printf 'true'; else printf 'false'; fi)" "端口 $gateway_port 被占用"
    assert "mock 端口空闲" "false" "$(if is_port_busy "$mock_port"; then printf 'true'; else printf 'false'; fi)" "端口 $mock_port 被占用"
else
    check "复用中的 gateway 可达" test "$(http_code "$GATEWAY_URL/healthz/live")" = "200"
fi

if [ "$SKIP_SETUP" = false ]; then
    stage "启动服务"
    start_mock
    check "mock 进程启动成功" kill -0 "$MOCK_PID"
    assert "mock /v1/models 可访问" "200" "$(http_code "$MOCK_URL/v1/models")"
    start_gateway
    check "gateway 进程启动成功" kill -0 "$GATEWAY_PID"
else
    stage "启动服务"
    assert "gateway 存活探针正常" "200" "$(http_code "$GATEWAY_URL/healthz/live")"
fi

stage "基础准备"
assert "健康检查接口返回 200" "200" "$(http_code "$GATEWAY_URL/healthz")"
login_admin
cleanup_remote_resources
prepare_provider_and_routes
register_user
prepare_user_access

stage "模板1：空集合校验不应返回 500"
EMPTY_GROUP_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/model-groups/$GROUP_EMPTY_NAME" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"members":[]}')"
assert "模型分组空 members 返回 400" "400" "$EMPTY_GROUP_CODE"

stage "模板2：局部更新语义不应错位"
USER_LOGIN_BODY="$(http_body -X POST "$GATEWAY_URL/auth/login" -H 'Content-Type: application/json' -d '{"username":"'"$TEST_USERNAME"'","password":"'"$TEST_PASSWORD"'"}')"
USER_TOKEN="$(jq_value "$USER_LOGIN_BODY" '.accessToken // empty')"
USER_AUTH="Authorization: Bearer $USER_TOKEN"
check "局部更新模板使用 fresh JWT 登录成功" test -n "$USER_TOKEN"
CREATE_KEY_BODY="$(http_body -X POST "$GATEWAY_URL/auth/keys" -H "$USER_AUTH" -H 'Content-Type: application/json' -d '{"name":"pattern-self-key","allowedModels":["'"$ROUTE_A"'"]}')"
PERSONAL_KEY_ID="$(jq_value "$CREATE_KEY_BODY" '.keyId // empty')"
CREATE_KEY_CODE="$(printf '%s' "$CREATE_KEY_BODY" | jq -r 'if (.keyId // "") != "" then "200" else "500" end' 2>/dev/null || printf '500')"
assert_status_one_of "create 状态码符合当前语义" "$CREATE_KEY_CODE" 200 201
check "局部更新模板已创建自助 Key" test -n "$PERSONAL_KEY_ID"

PATCH_DISABLE_CODE="$(http_code -X PATCH "$GATEWAY_URL/auth/keys/$PERSONAL_KEY_ID" -H "$USER_AUTH" -H 'Content-Type: application/json' -d '{"enabled":false}')"
assert_status_one_of "update 状态码符合当前语义" "$PATCH_DISABLE_CODE" 200 204

KEYS_AFTER_PATCH="$(http_body "$GATEWAY_URL/auth/keys" -H "$USER_AUTH")"
PATCH_ENABLED_VALUE="$(printf '%s' "$KEYS_AFTER_PATCH" | jq -r --arg id "$PERSONAL_KEY_ID" '.keys[] | select(.keyId == $id) | .enabled' 2>/dev/null || true)"
PATCH_ALLOWED_MODELS="$(printf '%s' "$KEYS_AFTER_PATCH" | jq -c --arg id "$PERSONAL_KEY_ID" '.keys[] | select(.keyId == $id) | .allowedModels' 2>/dev/null || true)"
assert "PATCH 仅修改 enabled 后状态已禁用" "false" "$PATCH_ENABLED_VALUE"
assert "PATCH 未误清空 allowedModels" '["'"$ROUTE_A"'"]' "$PATCH_ALLOWED_MODELS"

PATCH_RENAME_CODE="$(http_code -X PATCH "$GATEWAY_URL/auth/keys/$PERSONAL_KEY_ID" -H "$USER_AUTH" -H 'Content-Type: application/json' -d '{"enabled":true,"name":"pattern-self-key-renamed","allowedModels":["'"$ROUTE_B"'"]}')"
assert_status_one_of "再次 PATCH 返回成功状态码" "$PATCH_RENAME_CODE" 200 204

KEYS_AFTER_PATCH2="$(http_body "$GATEWAY_URL/auth/keys" -H "$USER_AUTH")"
PATCH2_NAME="$(printf '%s' "$KEYS_AFTER_PATCH2" | jq -r --arg id "$PERSONAL_KEY_ID" '.keys[] | select(.keyId == $id) | .name' 2>/dev/null || true)"
PATCH2_ALLOWED_MODELS="$(printf '%s' "$KEYS_AFTER_PATCH2" | jq -c --arg id "$PERSONAL_KEY_ID" '.keys[] | select(.keyId == $id) | .allowedModels' 2>/dev/null || true)"
assert "PATCH 修改名称已生效" "pattern-self-key-renamed" "$PATCH2_NAME"
assert "PATCH 修改 allowedModels 已生效" '["'"$ROUTE_B"'"]' "$PATCH2_ALLOWED_MODELS"

stage "模板3：create/update/delete 状态码一致性"
DELETE_KEY_CODE="$(http_code -X DELETE "$GATEWAY_URL/auth/keys/$PERSONAL_KEY_ID" -H "$USER_AUTH")"
assert_status_one_of "delete 状态码符合当前语义" "$DELETE_KEY_CODE" 200 204

stage "模板4：热更新后即时生效"
HOT_KEY_CREATE_BODY="$(http_body -X POST "$GATEWAY_URL/auth/keys" -H "$USER_AUTH" -H 'Content-Type: application/json' -d '{"name":"pattern-hot-key","allowedModels":["'"$ROUTE_A"'"]}')"
HOT_KEY_ID="$(jq_value "$HOT_KEY_CREATE_BODY" '.keyId // empty')"
HOT_KEY_VALUE="$(jq_value "$HOT_KEY_CREATE_BODY" '.apiKey // empty')"
check "热更新模板已创建 scoped key" test -n "$HOT_KEY_ID"
check "热更新模板返回 raw apiKey" test -n "$HOT_KEY_VALUE"

CHAT_A_BEFORE_CODE="$(chat_code_with_key "$HOT_KEY_VALUE" "$CHAT_A_PAYLOAD")"
assert "初始 key 白名单下 Route A 可调用" "200" "$CHAT_A_BEFORE_CODE"

HOT_KEY_SWITCH_CODE="$(http_code -X PATCH "$GATEWAY_URL/auth/keys/$HOT_KEY_ID" -H "$USER_AUTH" -H 'Content-Type: application/json' -d '{"allowedModels":["'"$ROUTE_B"'"]}')"
assert_status_one_of "切换 key allowedModels 返回成功" "$HOT_KEY_SWITCH_CODE" 200 204

CHAT_A_AFTER_CODE="$(chat_code_with_key "$HOT_KEY_VALUE" "$CHAT_A_PAYLOAD")"
CHAT_B_AFTER_CODE="$(chat_code_with_key "$HOT_KEY_VALUE" "$CHAT_B_PAYLOAD")"
assert "热更新后旧 Route A 立即不可用" "403" "$CHAT_A_AFTER_CODE"
assert "热更新后新 Route B 立即可用" "200" "$CHAT_B_AFTER_CODE"

stage "模板5：路径/版本类接口错误入参返回 4xx"
PROVIDER_UPDATE_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/providers/$PROVIDER_NAME" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"type":"openai-compatible","baseUrl":"'"$MOCK_URL"'","apiKey":"sk-pattern-v2","timeout":"10s"}')"
assert_status_one_of "先制造 provider 版本记录" "$PROVIDER_UPDATE_CODE" 200 201

ROLLBACK_404_CODE="$(http_code -X POST "$GATEWAY_URL/internal/config/rollback/providers/$PROVIDER_NAME/99999" -H "$ADMIN_AUTH")"
assert_status_one_of "不存在版本回滚返回 4xx 而非 500" "$ROLLBACK_404_CODE" 400 404

summary

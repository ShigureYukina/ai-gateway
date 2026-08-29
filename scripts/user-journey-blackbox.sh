#!/usr/bin/env bash
# ============================================================================
# AI Gateway — 无 Docker 真实用户旅程黑盒脚本
#
# 覆盖 7 条高价值真实旅程：
#   1. 外部用户：注册 -> /auth/me -> 用注册得到的 apiKey 调 chat
#   2. 外部用户：登录 -> 创建个人 API key -> 查看 key 列表与详情 -> 新 key 调 chat -> rotate -> 新 key 可用 -> 删除后不可用
#   3. 用户自助查询：真实 chat 后 -> /auth/usage/recent 与 /auth/usage/costs
#   4. 管理员：重置用户密码 -> 旧密码失效 -> 临时密码可登录
#   5. 管理员：代管用户 API key 生命周期（创建/禁用/轮换/删除）
#   6. 管理员：登录 -> 新增 provider -> 新增 route -> /v1/models 可见且 chat 可调用
#   7. 管理员可观测：真实 chat 后 -> recent / dashboard / usage summary 核心字段存在
#
# 用法：
#   ./scripts/user-journey-blackbox.sh
#   ./scripts/user-journey-blackbox.sh --skip-setup
#   ./scripts/user-journey-blackbox.sh --skip-teardown
#   ./scripts/user-journey-blackbox.sh --backend <in_memory|postgresql>
#   ./scripts/user-journey-blackbox.sh --help
#
# 依赖：curl, jq, java(21+), node, lsof
# 不依赖：Docker / Testcontainers
# ============================================================================

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_DIR/scripts/lib.sh"

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
MOCK_URL="${MOCK_URL:-http://localhost:18080}"
BACKEND="${BACKEND:-in_memory}"
MOCK_SCRIPT="$PROJECT_DIR/jmeter/mock_openai_server_node.mjs"
JAR_PATH="$PROJECT_DIR/bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar"
LOG_DIR="$PROJECT_DIR/.user-journey-logs"
MOCK_LOG="$LOG_DIR/mock.log"
GATEWAY_LOG="$LOG_DIR/gateway.log"

PGHOST_DEFAULT="${PGHOST:-/var/run/postgresql}"
PGPORT_DEFAULT="${PGPORT:-5433}"
PGDATABASE_DEFAULT="${PGDATABASE:-llm_gateway}"
PGUSER_DEFAULT="${PGUSER:-llm_user}"
PGPASSWORD_DEFAULT="${PGPASSWORD:-llm_password}"
REDIS_HOST_DEFAULT="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT_DEFAULT="${REDIS_PORT:-6379}"
RUN_SCHEMA="journey_$(date +%s)"

SKIP_SETUP=false
SKIP_TEARDOWN=false

GATEWAY_PID=""
MOCK_PID=""
PGHOST_RESOLVED=""

ADMIN_TOKEN=""
ADMIN_AUTH=""

USER1_NAME=""
USER1_PASS=""
USER1_TOKEN=""
USER1_AUTH=""
USER1_API_KEY=""

USER2_NAME=""
USER2_PASS=""
USER2_TOKEN=""
USER2_AUTH=""
USER2_API_KEY=""
USER2_PERSONAL_KEY_ID=""
USER2_PERSONAL_API_KEY=""
USER2_ROTATED_KEY_ID=""
USER2_ROTATED_API_KEY=""

USER3_NAME=""
USER3_PASS=""
USER3_MANAGED_KEY_ID=""
USER3_MANAGED_API_KEY=""
USER3_ROTATED_KEY_ID=""
USER3_ROTATED_API_KEY=""

PROVIDER_NAME="journey-mock"
ROUTE_NAME="gpt-4o-mini"
# content 中的中文必须用 JSON \u 转义：Windows 下 bash 向原生 curl 传参会把非 ASCII
# 参数按本地代码页转码，导致服务端 JSON 解码失败（400）。请求体保持纯 ASCII，服务端解码后为“请回复 journey-ok”。
CHAT_PAYLOAD='{"model":"'"$ROUTE_NAME"'","messages":[{"role":"user","content":"\u8bf7\u56de\u590d journey-ok"}],"stream":false}'

show_help() {
    cat <<'EOF'
AI Gateway 无 Docker 真实用户旅程黑盒脚本

用法：
  ./scripts/user-journey-blackbox.sh
  ./scripts/user-journey-blackbox.sh --backend postgresql
  ./scripts/user-journey-blackbox.sh --skip-setup
  ./scripts/user-journey-blackbox.sh --skip-teardown
  ./scripts/user-journey-blackbox.sh --help

环境变量：
  BACKEND       默认 in_memory，可选 postgresql
  GATEWAY_URL   默认 http://localhost:8081
  MOCK_URL      默认 http://localhost:18080
  PGHOST        默认 /var/run/postgresql
  PGPORT        默认 5433
  PGDATABASE    默认 llm_gateway
  PGUSER        默认 llm_user
  PGPASSWORD    默认 llm_password
  REDIS_HOST    默认 127.0.0.1
  REDIS_PORT    默认 6379
EOF
}

resolve_pg_host() {
    local socket_host="$PGHOST_DEFAULT"
    local tcp_host="127.0.0.1"

    if command -v psql >/dev/null 2>&1 && PGPASSWORD="$PGPASSWORD_DEFAULT" psql -U "$PGUSER_DEFAULT" -h "$tcp_host" -p "$PGPORT_DEFAULT" -d postgres -c 'select 1' >/dev/null 2>&1; then
        printf '%s\n' "$tcp_host"
        return 0
    fi
    if command -v psql >/dev/null 2>&1 && PGPASSWORD="$PGPASSWORD_DEFAULT" psql -U "$PGUSER_DEFAULT" -h "$socket_host" -p "$PGPORT_DEFAULT" -d postgres -c 'select 1' >/dev/null 2>&1; then
        printf '%s\n' "$socket_host"
        return 0
    fi
    printf '%s\n' "$tcp_host"
}

ensure_backends_running() {
    local redis_host="$REDIS_HOST_DEFAULT"
    local redis_port="$REDIS_PORT_DEFAULT"
    local pg_ready=false
    local redis_ready=false

    if command -v pg_isready >/dev/null 2>&1; then
        if pg_isready -h "$PGHOST_DEFAULT" -p "$PGPORT_DEFAULT" -U "$PGUSER_DEFAULT" -d "$PGDATABASE_DEFAULT" >/dev/null 2>&1 || \
            pg_isready -h 127.0.0.1 -p "$PGPORT_DEFAULT" -U "$PGUSER_DEFAULT" -d "$PGDATABASE_DEFAULT" >/dev/null 2>&1; then
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

    log_line "$RED" "PostgreSQL/Redis 未就绪：无法执行 --backend postgresql 路径"
    exit 2
}

ensure_database() {
    local pg_host="$1"
    if ! command -v psql >/dev/null 2>&1; then
        log_line "$RED" "未安装 psql，无法校验/创建 PostgreSQL 数据库"
        exit 2
    fi

    info "确保 PostgreSQL 数据库 $PGDATABASE_DEFAULT 存在"
    PGPASSWORD="$PGPASSWORD_DEFAULT" psql -U "$PGUSER_DEFAULT" -h "$pg_host" -p "$PGPORT_DEFAULT" -d postgres -tc "SELECT 1 FROM pg_database WHERE datname='$PGDATABASE_DEFAULT'" | grep -q 1 || \
        PGPASSWORD="$PGPASSWORD_DEFAULT" psql -U "$PGUSER_DEFAULT" -h "$pg_host" -p "$PGPORT_DEFAULT" -d postgres -c "CREATE DATABASE $PGDATABASE_DEFAULT" >/dev/null
}

ensure_schema() {
    local pg_host="$1"
    PGPASSWORD="$PGPASSWORD_DEFAULT" psql -U "$PGUSER_DEFAULT" -h "$pg_host" -p "$PGPORT_DEFAULT" -d "$PGDATABASE_DEFAULT" -c "CREATE SCHEMA IF NOT EXISTS \"${RUN_SCHEMA}\"" >/dev/null
}

drop_schema() {
    local pg_host="$1"
    PGPASSWORD="$PGPASSWORD_DEFAULT" psql -U "$PGUSER_DEFAULT" -h "$pg_host" -p "$PGPORT_DEFAULT" -d "$PGDATABASE_DEFAULT" -c "DROP SCHEMA IF EXISTS \"${RUN_SCHEMA}\" CASCADE" >/dev/null 2>&1 || true
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
    if [ ! -f "$JAR_PATH" ]; then
        log_line "$RED" "未找到 bootstrap JAR：$JAR_PATH"
        info "请先执行：./mvnw -pl bootstrap -am package -DskipTests"
        exit 2
    fi
    mkdir -p "$LOG_DIR"
    info "启动 gateway：$GATEWAY_URL"
    if [ "$BACKEND" = "postgresql" ]; then
        local db_url
        db_url="jdbc:postgresql://${PGHOST_RESOLVED}:${PGPORT_DEFAULT}/${PGDATABASE_DEFAULT}?currentSchema=${RUN_SCHEMA}"
        java -jar "$JAR_PATH" \
            --server.port="$(port_from_url "$GATEWAY_URL")" \
            --spring.profiles.active=local,test-pg \
            --spring.autoconfigure.exclude= \
            --gateway.shared-state.backend=postgresql \
            --spring.datasource.url="$db_url" \
            --spring.datasource.driver-class-name=org.postgresql.Driver \
            --spring.datasource.username="$PGUSER_DEFAULT" \
            --spring.datasource.password="$PGPASSWORD_DEFAULT" \
            --spring.jpa.hibernate.ddl-auto=none \
            --spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect \
            --spring.flyway.enabled=true \
            --spring.flyway.default-schema="$RUN_SCHEMA" \
            --spring.flyway.schemas[0]="$RUN_SCHEMA" \
            --spring.jpa.properties.hibernate.default_schema="$RUN_SCHEMA" \
            --spring.data.redis.host="$REDIS_HOST_DEFAULT" \
            --spring.data.redis.port="$REDIS_PORT_DEFAULT" \
            --spring.data.redis.password= >"$GATEWAY_LOG" 2>&1 &
    else
        java -jar "$JAR_PATH" \
            --server.port="$(port_from_url "$GATEWAY_URL")" \
            --spring.profiles.active=local \
            --gateway.shared-state.backend=in_memory \
            --spring.flyway.enabled=false >"$GATEWAY_LOG" 2>&1 &
    fi
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
    if [ "$SKIP_TEARDOWN" != true ] && [ "$BACKEND" = "postgresql" ] && [ -n "$PGHOST_RESOLVED" ]; then
        drop_schema "$PGHOST_RESOLVED"
    fi
}

cleanup_remote_resources() {
    if [ -n "$ADMIN_AUTH" ]; then
        http_code -X DELETE "$GATEWAY_URL/admin/users/$USER1_NAME" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
        http_code -X DELETE "$GATEWAY_URL/admin/users/$USER2_NAME" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
        http_code -X DELETE "$GATEWAY_URL/admin/users/$USER3_NAME" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
        http_code -X DELETE "$GATEWAY_URL/admin/routes/$ROUTE_NAME" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
        http_code -X DELETE "$GATEWAY_URL/admin/providers/$PROVIDER_NAME" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
    fi
}

cleanup() {
    cleanup_remote_resources
    stop_all
}

prepare_user_access() {
    local username="$1"
    local limits_code models_code
    limits_code="$(http_code -X PUT "$GATEWAY_URL/admin/users/$username/limits" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"dailyTokens":100000,"dailyCost":50.0}')"
    assert "为用户 $username 设置额度" "200" "$limits_code"
    models_code="$(http_code -X PUT "$GATEWAY_URL/admin/users/$username/allowed-models" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"allowedModels":["'"$ROUTE_NAME"'"]}')"
    assert "为用户 $username 设置模型白名单" "200" "$models_code"
}

register_user() {
    local username="$1"
    local password="$2"
    http_body -X POST "$GATEWAY_URL/auth/register" \
        -H 'Content-Type: application/json' \
        -d '{"username":"'"$username"'","password":"'"$password"'"}'
}

login_user() {
    local username="$1"
    local password="$2"
    http_body -X POST "$GATEWAY_URL/auth/login" \
        -H 'Content-Type: application/json' \
        -d '{"username":"'"$username"'","password":"'"$password"'"}'
}

journey_chat_assert() {
    local desc="$1"
    local auth_header="$2"
    local body
    body="$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" -H "$auth_header" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
    assert_gt "$desc" "$(jq_number "$body" '.choices | length')" 0 "未返回有效 choices"
    assert "$desc 返回 object 正确" "chat.completion" "$(jq_value "$body" '.object // empty')"
    assert "$desc 返回模型正确" "$ROUTE_NAME" "$(jq_value "$body" '.model // empty')"
    assert "$desc 返回角色正确" "assistant" "$(jq_value "$body" '.choices[0].message.role // empty')"
    assert "$desc 返回内容正确" "Hello from mock!" "$(jq_value "$body" '.choices[0].message.content // empty')"
    assert "$desc 返回 prompt_tokens 正确" "10" "$(jq_value "$body" '.usage.prompt_tokens // empty')"
    assert "$desc 返回 completion_tokens 正确" "5" "$(jq_value "$body" '.usage.completion_tokens // empty')"
    assert "$desc 返回 total_tokens 正确" "15" "$(jq_value "$body" '.usage.total_tokens // empty')"
}

assert_models_contains() {
    local desc="$1"
    local body="$2"
    local model_id="$3"
    assert_gt "$desc" "$(jq_number "$body" '.data | map(select(.id == "'"$model_id"'")) | length')" 0 "模型列表未找到 $model_id"
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
        --backend)
            shift
            if [ $# -eq 0 ]; then
                log_line "$RED" "--backend 需要指定 in_memory 或 postgresql"
                exit 1
            fi
            BACKEND="$1"
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

if [ "$BACKEND" != "in_memory" ] && [ "$BACKEND" != "postgresql" ]; then
    log_line "$RED" "不支持的 BACKEND：$BACKEND（仅支持 in_memory / postgresql）"
    exit 1
fi

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
    if [ "$BACKEND" = "postgresql" ]; then
        check "psql 已安装" command -v psql
        check "pg_isready 已安装" command -v pg_isready
        check "redis-cli 已安装" command -v redis-cli
        ensure_backends_running
        PGHOST_RESOLVED="$(resolve_pg_host)"
        ensure_database "$PGHOST_RESOLVED"
        ensure_schema "$PGHOST_RESOLVED"
    fi
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

stage "基础健康检查"
HEALTH_BODY="$(http_body "$GATEWAY_URL/healthz")"
assert "健康检查接口返回 200" "200" "$(http_code "$GATEWAY_URL/healthz")"
STEP=$((STEP + 1)); printf '  [%02d] 健康检查包含 status 字段 ... ' "$STEP"
if [ -n "$(jq_value "$HEALTH_BODY" '.status // empty')" ]; then pass; else fail "status 字段为空"; fi
assert "就绪探针返回 200" "200" "$(http_code "$GATEWAY_URL/healthz/ready")"

stage "公共前置：管理员登录与路由准备"
ADMIN_LOGIN_BODY="$(login_user "admin" "admin123")"
ADMIN_TOKEN="$(jq_value "$ADMIN_LOGIN_BODY" '.accessToken // empty')"
ADMIN_AUTH="Authorization: Bearer $ADMIN_TOKEN"
STEP=$((STEP + 1)); printf '  [%02d] 管理员登录成功 ... ' "$STEP"; if [ -n "$ADMIN_TOKEN" ] && [ "$ADMIN_TOKEN" != "null" ]; then pass; else fail "accessToken 为空"; fi

PROVIDER_PAYLOAD='{"type":"openai-compatible","baseUrl":"'"$MOCK_URL"'","apiKey":"sk-mock","timeout":"10s"}'
STEP=$((STEP + 1)); printf '  [%02d] 新增 mock provider 成功 ... ' "$STEP"
PROVIDER_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/providers/$PROVIDER_NAME" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$PROVIDER_PAYLOAD")"
if [[ "$PROVIDER_CODE" =~ ^[0-9]+$ ]] && [ "$PROVIDER_CODE" -lt 300 ]; then pass; else fail "got $PROVIDER_CODE, expected < 300"; fi

ROUTE_PAYLOAD='{"provider":"'"$PROVIDER_NAME"'","upstreamModel":"gpt-4o-mini"}'
STEP=$((STEP + 1)); printf '  [%02d] 新增 route 成功 ... ' "$STEP"
ROUTE_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/routes/$ROUTE_NAME" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$ROUTE_PAYLOAD")"
if [[ "$ROUTE_CODE" =~ ^[0-9]+$ ]] && [ "$ROUTE_CODE" -lt 300 ]; then pass; else fail "got $ROUTE_CODE, expected < 300"; fi

MODELS_BODY="$(http_body "$GATEWAY_URL/v1/models")"
assert_models_contains "模型列表包含新 route" "$MODELS_BODY" "$ROUTE_NAME"
journey_chat_assert "管理员可直接调用新 route chat" "$ADMIN_AUTH"

stage "旅程 1：外部用户注册后直接使用"
USER1_NAME="journey-user-a-$(date +%s)"
USER1_PASS="JourneyPassA123"
USER1_REGISTER_BODY="$(register_user "$USER1_NAME" "$USER1_PASS")"
USER1_API_KEY="$(jq_value "$USER1_REGISTER_BODY" '.apiKey // empty')"
USER1_TOKEN="$(jq_value "$USER1_REGISTER_BODY" '.accessToken // empty')"
USER1_AUTH="Authorization: Bearer $USER1_TOKEN"
STEP=$((STEP + 1)); printf '  [%02d] 注册返回 apiKey ... ' "$STEP"; if [ -n "$USER1_API_KEY" ]; then pass; else fail "apiKey 为空"; fi
STEP=$((STEP + 1)); printf '  [%02d] 注册返回 accessToken ... ' "$STEP"; if [ -n "$USER1_TOKEN" ]; then pass; else fail "accessToken 为空"; fi
assert "注册 key 前缀正确" "gw-" "${USER1_API_KEY:0:3}" "got ${USER1_API_KEY:0:3}, expected gw-"
prepare_user_access "$USER1_NAME"
USER1_ME_BODY="$(http_body "$GATEWAY_URL/auth/me" -H "$USER1_AUTH")"
assert "/auth/me 返回注册用户名" "$USER1_NAME" "$(jq_value "$USER1_ME_BODY" '.username // empty')"
journey_chat_assert "注册得到的 apiKey 可调用 chat" "Authorization: Bearer $USER1_API_KEY"

stage "旅程 2：外部用户登录与个人 API Key 生命周期"
USER2_NAME="journey-user-b-$(date +%s)"
USER2_PASS="JourneyPassB123"
USER2_REGISTER_BODY="$(register_user "$USER2_NAME" "$USER2_PASS")"
USER2_API_KEY="$(jq_value "$USER2_REGISTER_BODY" '.apiKey // empty')"
STEP=$((STEP + 1)); printf '  [%02d] 第二个测试用户注册成功 ... ' "$STEP"; if [ -n "$USER2_API_KEY" ]; then pass; else fail "注册 apiKey 为空"; fi
prepare_user_access "$USER2_NAME"

USER2_LOGIN_BODY="$(login_user "$USER2_NAME" "$USER2_PASS")"
USER2_TOKEN="$(jq_value "$USER2_LOGIN_BODY" '.accessToken // empty')"
USER2_AUTH="Authorization: Bearer $USER2_TOKEN"
STEP=$((STEP + 1)); printf '  [%02d] 用户登录成功 ... ' "$STEP"; if [ -n "$USER2_TOKEN" ]; then pass; else fail "登录 token 为空"; fi

USER2_CREATE_KEY_BODY="$(http_body -X POST "$GATEWAY_URL/auth/keys" -H "$USER2_AUTH" -H 'Content-Type: application/json' -d '{"name":"journey-key","allowedModels":["'"$ROUTE_NAME"'"]}')"
USER2_PERSONAL_KEY_ID="$(jq_value "$USER2_CREATE_KEY_BODY" '.keyId // empty')"
USER2_PERSONAL_API_KEY="$(jq_value "$USER2_CREATE_KEY_BODY" '.apiKey // empty')"
STEP=$((STEP + 1)); printf '  [%02d] 创建个人 API key 成功 ... ' "$STEP"; if [ -n "$USER2_PERSONAL_KEY_ID" ] && [ -n "$USER2_PERSONAL_API_KEY" ]; then pass; else fail "keyId 或 apiKey 为空"; fi
journey_chat_assert "新建个人 key 可调用 chat" "Authorization: Bearer $USER2_PERSONAL_API_KEY"

# 用户自查个人 API key 列表
KEYS_LIST_BODY="$(http_body "$GATEWAY_URL/auth/keys" -H "$USER2_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] 用户可查看个人 key 列表（含新建的 key） ... ' "$STEP"
if jq -e ".keys | map(select(.keyId == \"$USER2_PERSONAL_KEY_ID\")) | length > 0" <<< "$KEYS_LIST_BODY" >/dev/null 2>&1; then pass; else fail "新建 key 不在列表中"; fi

STEP=$((STEP + 1)); printf '  [%02d] 用户可从列表查看个人 key 元信息 ... ' "$STEP"
if jq -e ".keys | map(select(.keyId == \"$USER2_PERSONAL_KEY_ID\" and .name == \"journey-key\" and .enabled == true)) | length > 0" <<< "$KEYS_LIST_BODY" >/dev/null 2>&1; then pass; else fail "列表中缺少 keyId=$USER2_PERSONAL_KEY_ID 对应的元信息"; fi

USER2_ROTATE_BODY="$(http_body -X POST "$GATEWAY_URL/auth/keys/$USER2_PERSONAL_KEY_ID/rotate" -H "$USER2_AUTH")"
USER2_ROTATED_KEY_ID="$(jq_value "$USER2_ROTATE_BODY" '.keyId // empty')"
USER2_ROTATED_API_KEY="$(jq_value "$USER2_ROTATE_BODY" '.apiKey // empty')"
STEP=$((STEP + 1)); printf '  [%02d] 轮换 key 成功 ... ' "$STEP"; if [ -n "$USER2_ROTATED_KEY_ID" ] && [ -n "$USER2_ROTATED_API_KEY" ]; then pass; else fail "轮换结果为空"; fi
journey_chat_assert "轮换后的新 key 可调用 chat" "Authorization: Bearer $USER2_ROTATED_API_KEY"

OLD_KEY_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H "Authorization: Bearer $USER2_PERSONAL_API_KEY" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
STEP=$((STEP + 1)); printf '  [%02d] 轮换前旧 key 不可再用 ... ' "$STEP"; if [ "$OLD_KEY_CODE" = "401" ] || [ "$OLD_KEY_CODE" = "403" ]; then pass; else fail "got $OLD_KEY_CODE, expected 401/403"; fi

DELETE_ROTATED_CODE="$(http_code -X DELETE "$GATEWAY_URL/auth/keys/$USER2_ROTATED_KEY_ID" -H "$USER2_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] 删除轮换后的 key 成功 ... ' "$STEP"; if [ "$DELETE_ROTATED_CODE" = "204" ] || [ "$DELETE_ROTATED_CODE" = "200" ]; then pass; else fail "got $DELETE_ROTATED_CODE, expected 204/200"; fi
DELETED_KEY_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H "Authorization: Bearer $USER2_ROTATED_API_KEY" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
STEP=$((STEP + 1)); printf '  [%02d] 删除后的旧 key 不可用 ... ' "$STEP"; if [ "$DELETED_KEY_CODE" = "401" ] || [ "$DELETED_KEY_CODE" = "403" ]; then pass; else fail "got $DELETED_KEY_CODE, expected 401/403"; fi

stage "旅程 3：用户自助查询 recent / costs"
sleep 2
USER_RECENT_BODY="$(http_body "$GATEWAY_URL/auth/usage/recent?limit=10" -H "$USER2_AUTH")"
assert_gt "用户 recent 至少有 1 条记录" "$(jq_number "$USER_RECENT_BODY" '.requests | length')" 0 "recent requests 为空"
assert_gt "用户 recent 包含当前模型请求" "$(jq_number "$USER_RECENT_BODY" '.requests | map(select(.model == "'$ROUTE_NAME'" or .routeId == "'$ROUTE_NAME'")) | length')" 0 "recent 中未找到当前模型或 route 请求"
assert_gt "用户 recent 包含成功请求明细" "$(jq_number "$USER_RECENT_BODY" '.requests | map(select(.model == "'$ROUTE_NAME'" and .status == 200 and .usageTokens == 15 and .promptTokens == 10 and .completionTokens == 5)) | length')" 0 "recent 中未找到完整成功请求明细"

TODAY_UTC="$(date -u +%Y-%m-%d)"
USER_COSTS_BODY="$(http_body "$GATEWAY_URL/auth/usage/costs?from=$TODAY_UTC&to=$TODAY_UTC" -H "$USER2_AUTH")"
assert "用户 costs 返回 from 正确" "$TODAY_UTC" "$(jq_value "$USER_COSTS_BODY" '.from // empty')"
assert "用户 costs 返回 to 正确" "$TODAY_UTC" "$(jq_value "$USER_COSTS_BODY" '.to // empty')"
assert_gt "用户 costs 包含当前模型聚合" "$(jq_number "$USER_COSTS_BODY" '.models | map(select(.model == "'$ROUTE_NAME'")) | length')" 0 "models 中未找到当前模型聚合"
assert_gt "用户 costs 当前模型请求数至少为 2" "$(jq_number "$USER_COSTS_BODY" '.models | map(select(.model == "'$ROUTE_NAME'"))[0].requests // 0')" 1 "requests < 2"
assert_gt "用户 costs 当前模型 totalTokens 至少为 30" "$(jq_number "$USER_COSTS_BODY" '.models | map(select(.model == "'$ROUTE_NAME'"))[0].totalTokens // 0')" 29 "totalTokens < 30"
assert_gt "用户 costs 当前模型 promptTokens 至少为 20" "$(jq_number "$USER_COSTS_BODY" '.models | map(select(.model == "'$ROUTE_NAME'"))[0].promptTokens // 0')" 19 "promptTokens < 20"
assert_gt "用户 costs 当前模型 completionTokens 至少为 10" "$(jq_number "$USER_COSTS_BODY" '.models | map(select(.model == "'$ROUTE_NAME'"))[0].completionTokens // 0')" 9 "completionTokens < 10"

stage "旅程 4：管理员重置密码后旧密码失效、临时密码可登录"
RESET_PASSWORD_BODY="$(http_body -X POST "$GATEWAY_URL/admin/users/$USER1_NAME/reset-password" -H "$ADMIN_AUTH")"
USER1_TEMP_PASSWORD="$(jq_value "$RESET_PASSWORD_BODY" '.temporaryPassword // empty')"
STEP=$((STEP + 1)); printf '  [%02d] 重置密码返回 temporaryPassword ... ' "$STEP"; if [ -n "$USER1_TEMP_PASSWORD" ]; then pass; else fail "temporaryPassword 为空"; fi
STEP=$((STEP + 1)); printf '  [%02d] 临时密码不同于旧密码 ... ' "$STEP"; if [ "$USER1_TEMP_PASSWORD" != "$USER1_PASS" ]; then pass; else fail "temporaryPassword 与旧密码相同"; fi
OLD_PASSWORD_LOGIN_CODE="$(http_code -X POST "$GATEWAY_URL/auth/login" -H 'Content-Type: application/json' -d '{"username":"'$USER1_NAME'","password":"'$USER1_PASS'"}')"
STEP=$((STEP + 1)); printf '  [%02d] 旧密码登录失败 ... ' "$STEP"; if [ "$OLD_PASSWORD_LOGIN_CODE" = "401" ]; then pass; else fail "got $OLD_PASSWORD_LOGIN_CODE, expected 401"; fi
USER1_TEMP_LOGIN_BODY="$(login_user "$USER1_NAME" "$USER1_TEMP_PASSWORD")"
USER1_TEMP_TOKEN="$(jq_value "$USER1_TEMP_LOGIN_BODY" '.accessToken // empty')"
STEP=$((STEP + 1)); printf '  [%02d] 临时密码登录成功 ... ' "$STEP"; if [ -n "$USER1_TEMP_TOKEN" ]; then pass; else fail "accessToken 为空"; fi
USER1_TEMP_ME_BODY="$(http_body "$GATEWAY_URL/auth/me" -H "Authorization: Bearer $USER1_TEMP_TOKEN")"
assert "临时密码登录后 /auth/me 返回正确用户" "$USER1_NAME" "$(jq_value "$USER1_TEMP_ME_BODY" '.username // empty')"

stage "旅程 5：管理员代管用户 API Key 生命周期"
USER3_NAME="journey-user-c-$(date +%s)"
USER3_PASS="JourneyPassC123"
USER3_REGISTER_BODY="$(register_user "$USER3_NAME" "$USER3_PASS")"
STEP=$((STEP + 1)); printf '  [%02d] 第三个测试用户注册成功 ... ' "$STEP"; if [ -n "$(jq_value "$USER3_REGISTER_BODY" '.apiKey // empty')" ]; then pass; else fail "注册 apiKey 为空"; fi
prepare_user_access "$USER3_NAME"

USER3_CREATE_KEY_BODY="$(http_body -X POST "$GATEWAY_URL/admin/users/$USER3_NAME/api-keys" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"name":"admin-managed-key","allowedModels":["'$ROUTE_NAME'"]}')"
USER3_MANAGED_KEY_ID="$(jq_value "$USER3_CREATE_KEY_BODY" '.keyId // empty')"
USER3_MANAGED_API_KEY="$(jq_value "$USER3_CREATE_KEY_BODY" '.apiKeyMasked // empty')"
STEP=$((STEP + 1)); printf '  [%02d] 管理员创建代管 key 成功 ... ' "$STEP"; if [ -n "$USER3_MANAGED_KEY_ID" ] && [ -n "$USER3_MANAGED_API_KEY" ]; then pass; else fail "keyId 或 apiKeyMasked 为空"; fi
journey_chat_assert "管理员代管新 key 可调用 chat" "Authorization: Bearer $USER3_MANAGED_API_KEY"

USER3_DISABLE_CODE="$(http_code -X PATCH "$GATEWAY_URL/admin/users/$USER3_NAME/api-keys/$USER3_MANAGED_KEY_ID" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"enabled":false}')"
STEP=$((STEP + 1)); printf '  [%02d] 管理员禁用代管 key 成功 ... ' "$STEP"; if [ "$USER3_DISABLE_CODE" = "204" ] || [ "$USER3_DISABLE_CODE" = "200" ]; then pass; else fail "got $USER3_DISABLE_CODE, expected 204/200"; fi
USER3_DISABLED_KEY_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H "Authorization: Bearer $USER3_MANAGED_API_KEY" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
STEP=$((STEP + 1)); printf '  [%02d] 禁用后的代管 key 不可用 ... ' "$STEP"; if [ "$USER3_DISABLED_KEY_CODE" = "401" ] || [ "$USER3_DISABLED_KEY_CODE" = "403" ]; then pass; else fail "got $USER3_DISABLED_KEY_CODE, expected 401/403"; fi

# 以仓库实际实现为准：管理员代管 key 的禁用走 PATCH，轮换要求 key 处于 enabled 状态。
USER3_REENABLE_CODE="$(http_code -X PATCH "$GATEWAY_URL/admin/users/$USER3_NAME/api-keys/$USER3_MANAGED_KEY_ID" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"enabled":true}')"
STEP=$((STEP + 1)); printf '  [%02d] 轮换前重新启用代管 key 成功 ... ' "$STEP"; if [ "$USER3_REENABLE_CODE" = "204" ] || [ "$USER3_REENABLE_CODE" = "200" ]; then pass; else fail "got $USER3_REENABLE_CODE, expected 204/200"; fi

USER3_ROTATE_BODY="$(http_body -X POST "$GATEWAY_URL/admin/users/$USER3_NAME/api-keys/$USER3_MANAGED_KEY_ID/rotate" -H "$ADMIN_AUTH")"
USER3_ROTATED_KEY_ID="$(jq_value "$USER3_ROTATE_BODY" '.keyId // empty')"
USER3_ROTATED_API_KEY="$(jq_value "$USER3_ROTATE_BODY" '.apiKey // empty')"
STEP=$((STEP + 1)); printf '  [%02d] 管理员轮换代管 key 成功 ... ' "$STEP"; if [ -n "$USER3_ROTATED_KEY_ID" ] && [ -n "$USER3_ROTATED_API_KEY" ]; then pass; else fail "轮换结果为空"; fi
journey_chat_assert "轮换后的代管新 key 可调用 chat" "Authorization: Bearer $USER3_ROTATED_API_KEY"

USER3_OLD_KEY_AFTER_ROTATE_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H "Authorization: Bearer $USER3_MANAGED_API_KEY" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
STEP=$((STEP + 1)); printf '  [%02d] 轮换前旧代管 key 不可再用 ... ' "$STEP"; if [ "$USER3_OLD_KEY_AFTER_ROTATE_CODE" = "401" ] || [ "$USER3_OLD_KEY_AFTER_ROTATE_CODE" = "403" ]; then pass; else fail "got $USER3_OLD_KEY_AFTER_ROTATE_CODE, expected 401/403"; fi

USER3_DELETE_ROTATED_CODE="$(http_code -X DELETE "$GATEWAY_URL/admin/users/$USER3_NAME/api-keys/$USER3_ROTATED_KEY_ID" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] 删除轮换后的代管 key 成功 ... ' "$STEP"; if [ "$USER3_DELETE_ROTATED_CODE" = "204" ] || [ "$USER3_DELETE_ROTATED_CODE" = "200" ]; then pass; else fail "got $USER3_DELETE_ROTATED_CODE, expected 204/200"; fi
USER3_DELETED_KEY_CODE="$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" -H "Authorization: Bearer $USER3_ROTATED_API_KEY" -H 'Content-Type: application/json' -d "$CHAT_PAYLOAD")"
STEP=$((STEP + 1)); printf '  [%02d] 删除后的代管 key 不可用 ... ' "$STEP"; if [ "$USER3_DELETED_KEY_CODE" = "401" ] || [ "$USER3_DELETED_KEY_CODE" = "403" ]; then pass; else fail "got $USER3_DELETED_KEY_CODE, expected 401/403"; fi

stage "旅程 6：管理员新增 provider/route 后可被真实访问"
assert "管理员查看 provider 列表可见新 provider" "true" "$(jq_value "$(http_body "$GATEWAY_URL/admin/providers" -H "$ADMIN_AUTH")" '.providers | has("'"$PROVIDER_NAME"'")')"
assert "管理员查看 route 列表可见新 route" "true" "$(jq_value "$(http_body "$GATEWAY_URL/admin/routes" -H "$ADMIN_AUTH")" '.routes | has("'"$ROUTE_NAME"'")')"
assert_models_contains "公开模型列表可见新 route" "$(http_body "$GATEWAY_URL/v1/models")" "$ROUTE_NAME"
journey_chat_assert "管理员新增 route 后 chat 可成功调用" "$ADMIN_AUTH"

stage "旅程 7：管理员可观测接口验证"
sleep 2
RECENT_BODY="$(http_body "$GATEWAY_URL/admin/requests/recent?limit=5" -H "$ADMIN_AUTH")"
assert_gt "recent 请求列表至少有 1 条记录" "$(jq_number "$RECENT_BODY" '.requests | length')" 0 "recent requests 为空"
STEP=$((STEP + 1)); printf '  [%02d] recent 核心字段存在 ... ' "$STEP"
if [ -n "$(jq_value "$RECENT_BODY" '.requests[0].requestId // empty')" ] && [ -n "$(jq_value "$RECENT_BODY" '.total // empty')" ]; then pass; else fail "缺少 requestId 或 total"; fi

DASHBOARD_BODY="$(http_body "$GATEWAY_URL/admin/dashboard/overview" -H "$ADMIN_AUTH")"
STEP=$((STEP + 1)); printf '  [%02d] dashboard 核心字段存在 ... ' "$STEP"
if [ -n "$(jq_value "$DASHBOARD_BODY" '.overview.totalRequests // empty')" ] && [ -n "$(jq_value "$DASHBOARD_BODY" '.systemStatus.hasAvailableRoute // empty')" ]; then pass; else fail "缺少 overview.totalRequests 或 systemStatus.hasAvailableRoute"; fi

TODAY="$(date +%Y-%m-%d)"
USAGE_BODY="$(http_body "$GATEWAY_URL/internal/usage/summary?day=$TODAY" -H "$ADMIN_AUTH")"
assert_gt "usage summary 客户端条目非空" "$(jq_number "$USAGE_BODY" '.clients | length')" 0 "clients 为空"
STEP=$((STEP + 1)); printf '  [%02d] usage summary 核心字段存在 ... ' "$STEP"
if [ -n "$(jq_value "$USAGE_BODY" '.day // empty')" ] && [ -n "$(jq_value "$USAGE_BODY" '.clients[0].client // empty')" ]; then pass; else fail "缺少 day 或 clients[0].client"; fi

cleanup_remote_resources
summary

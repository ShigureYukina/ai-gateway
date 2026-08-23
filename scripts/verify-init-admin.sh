#!/usr/bin/env bash
# ============================================================================
# AI Gateway — 正式环境管理员初始化黑盒脚本
#
# 覆盖最小闭环：
#   1. 非 local/dev/test 环境无动态管理员时，启动被拦截
#   2. 一次性 init-admin 命令可成功创建动态管理员
#   3. 同库再次正常启动成功，且可用新管理员登录
#
# 用法：
#   ./scripts/verify-init-admin.sh
#   ./scripts/verify-init-admin.sh --skip-teardown
#   ./scripts/verify-init-admin.sh --help
#
# 依赖：curl, jq, java(21+), lsof
# 不依赖：Docker / mock upstream
# ============================================================================

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_DIR/scripts/lib.sh"

GATEWAY_URL="${GATEWAY_URL:-http://localhost:18091}"
SKIP_TEARDOWN=false
JAR_PATH="$PROJECT_DIR/bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar"
LOG_DIR="$PROJECT_DIR/.init-admin-logs"
FAIL_LOG="$LOG_DIR/start-without-dynamic-admin.log"
INIT_LOG="$LOG_DIR/init-admin-command.log"
SUCCESS_LOG="$LOG_DIR/start-after-init-admin.log"
PGHOST_DEFAULT="${PGHOST:-/var/run/postgresql}"
PGPORT_DEFAULT="${PGPORT:-5433}"
PGUSER_DEFAULT="${PGUSER:-llm_user}"
PGPASSWORD_DEFAULT="${PGPASSWORD:-llm_password}"
PGDATABASE="${PGDATABASE:-llm_gateway}"
RUN_SCHEMA="init_admin_$(date +%s)"
ADMIN_USERNAME="root-admin"
ADMIN_PASSWORD="Secret#123"
ADMIN_DISPLAY_NAME="Root Admin"
ADMIN_EMAIL="root-admin@example.com"

GATEWAY_PID=""
PGHOST_RESOLVED=""
DB_URL=""

show_help() {
    cat <<'EOF'
AI Gateway 正式环境管理员初始化黑盒脚本

用法：
  ./scripts/verify-init-admin.sh
  ./scripts/verify-init-admin.sh --skip-teardown
  ./scripts/verify-init-admin.sh --help

环境变量：
  GATEWAY_URL   默认 http://localhost:18091
EOF
}

build_common_args() {
    COMMON_ARGS=(
        "--server.port=$(port_from_url "$GATEWAY_URL")"
        "--spring.profiles.active=prodlike"
        "--gateway.shared-state.backend=postgresql"
        "--spring.flyway.enabled=true"
        "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        "--spring.datasource.url=$DB_URL"
        "--spring.datasource.driver-class-name=org.postgresql.Driver"
        "--spring.datasource.username=$PGUSER_DEFAULT"
        "--spring.datasource.password=$PGPASSWORD_DEFAULT"
        "--spring.flyway.default-schema=$RUN_SCHEMA"
        "--spring.flyway.schemas[0]=$RUN_SCHEMA"
        "--spring.jpa.properties.hibernate.default_schema=$RUN_SCHEMA"
        "--gateway.auth.enabled=true"
        "--gateway.auth.jwt.secret=prod-secret-key-at-least-32-chars"
        "--gateway.auth.users.admin.password=admin123"
        "--gateway.auth.users.user.password=user123"
        "--gateway.providers.openai.api-key=sk-test"
        "--gateway.providers.anthropic.api-key=sk-test"
    )
}

wait_for_process_exit() {
    local pid="$1"
    local seconds="${2:-20}"
    for _ in $(seq 1 "$seconds"); do
        if ! kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    return 1
}

wait_for_gateway_ready() {
    printf '[%s] 等待 gateway 就绪' "$(timestamp)"
    for _ in $(seq 1 45); do
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

resolve_pg_host() {
    local socket_host="$PGHOST_DEFAULT"
    local tcp_host="127.0.0.1"

    if PGPASSWORD="$PGPASSWORD_DEFAULT" psql -U "$PGUSER_DEFAULT" -h "$tcp_host" -p "$PGPORT_DEFAULT" -d postgres -c 'select 1' >/dev/null 2>&1; then
        printf '%s\n' "$tcp_host"
        return 0
    fi
    if PGPASSWORD="$PGPASSWORD_DEFAULT" psql -U "$PGUSER_DEFAULT" -h "$socket_host" -p "$PGPORT_DEFAULT" -d postgres -c 'select 1' >/dev/null 2>&1; then
        printf '%s\n' "$socket_host"
        return 0
    fi
    printf '%s\n' "$tcp_host"
}

ensure_schema() {
    local pg_host="$1"

    PGPASSWORD="$PGPASSWORD_DEFAULT" psql -U "$PGUSER_DEFAULT" -h "$pg_host" -p "$PGPORT_DEFAULT" -d "$PGDATABASE" -c "CREATE SCHEMA IF NOT EXISTS \"${RUN_SCHEMA}\"" >/dev/null
}

drop_schema() {
    local pg_host="$1"

    PGPASSWORD="$PGPASSWORD_DEFAULT" psql -U "$PGUSER_DEFAULT" -h "$pg_host" -p "$PGPORT_DEFAULT" -d "$PGDATABASE" -c "DROP SCHEMA IF EXISTS \"${RUN_SCHEMA}\" CASCADE" >/dev/null 2>&1 || true
}

start_gateway_background() {
    local log_file="$1"
    shift
    mkdir -p "$LOG_DIR"
    build_common_args
    java -jar "$JAR_PATH" "${COMMON_ARGS[@]}" "$@" >"$log_file" 2>&1 &
    GATEWAY_PID=$!
}

stop_gateway() {
    if [ "$SKIP_TEARDOWN" = true ]; then
        info "跳过进程清理（--skip-teardown）"
        return 0
    fi
    kill_pid_gracefully "$GATEWAY_PID" "gateway"
}

cleanup() {
    stop_gateway
    if [ "$SKIP_TEARDOWN" != true ] && [ -n "$PGHOST_RESOLVED" ]; then
        drop_schema "$PGHOST_RESOLVED"
    fi
}

while [ $# -gt 0 ]; do
    case "$1" in
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
check "lsof 已安装" command -v lsof
check "psql 已安装" command -v psql
check "pg_isready 已安装" command -v pg_isready
check "bootstrap JAR 已存在" test -f "$JAR_PATH"

JAVA_VERSION_OUTPUT="$(java -version 2>&1 || true)"
JAVA_MAJOR="0"
JAVA_VERSION_PATTERN='version[[:space:]]+"([0-9]+)'
if [[ "$JAVA_VERSION_OUTPUT" =~ $JAVA_VERSION_PATTERN ]]; then
    JAVA_MAJOR="${BASH_REMATCH[1]}"
fi
assert_gt "Java 版本为 21+" "${JAVA_MAJOR:-0}" 20 "当前版本 ${JAVA_MAJOR:-unknown}，期望 >= 21"

gateway_port="$(port_from_url "$GATEWAY_URL")"
kill_port_processes "$gateway_port" "gateway"
assert "目标端口空闲" "false" "$(if is_port_busy "$gateway_port"; then printf 'true'; else printf 'false'; fi)" "端口 $gateway_port 被占用"

PGHOST_RESOLVED="$(resolve_pg_host)"
DB_URL="jdbc:postgresql://${PGHOST_RESOLVED}:${PGPORT_DEFAULT}/${PGDATABASE}?currentSchema=${RUN_SCHEMA}"
check "PostgreSQL 可连接" bash -lc "PGPASSWORD='$PGPASSWORD_DEFAULT' psql -U '$PGUSER_DEFAULT' -h '$PGHOST_RESOLVED' -p '$PGPORT_DEFAULT' -d '$PGDATABASE' -c 'select 1' >/dev/null"
ensure_schema "$PGHOST_RESOLVED"

stage "步骤1：未初始化时正式环境启动失败"
start_gateway_background "$FAIL_LOG"
assert "首次启动进程已拉起" "true" "$(if kill -0 "$GATEWAY_PID" 2>/dev/null; then echo "true"; else echo "false"; fi)"
if wait_for_process_exit "$GATEWAY_PID" 30; then
    pass
else
    fail "30s 内未因缺少动态管理员而退出"
    exit 1
fi
GATEWAY_PID=""

FAIL_BODY="$(<"$FAIL_LOG")"
assert_contains "启动失败日志包含动态管理员校验" "$FAIL_BODY" "不存在动态 admin 账户"
assert_contains "启动失败日志提示 init-admin 命令" "$FAIL_BODY" "gateway.bootstrap.init-admin.enabled=true"

stage "步骤2：执行一次性 init-admin 命令"
mkdir -p "$LOG_DIR"
build_common_args
if java -Dgateway.bootstrap.init-admin.enabled=true -jar "$JAR_PATH" \
    "${COMMON_ARGS[@]}" \
    --gateway.bootstrap.init-admin.enabled=true \
    --gateway.bootstrap.init-admin.username="$ADMIN_USERNAME" \
    --gateway.bootstrap.init-admin.password="$ADMIN_PASSWORD" \
    --gateway.bootstrap.init-admin.display-name="$ADMIN_DISPLAY_NAME" \
    --gateway.bootstrap.init-admin.email="$ADMIN_EMAIL" >"$INIT_LOG" 2>&1; then
    pass
else
    fail "init-admin 命令执行失败，请查看 $INIT_LOG"
    exit 1
fi

INIT_BODY="$(<"$INIT_LOG")"
assert_contains "init-admin 日志包含初始化完成标记" "$INIT_BODY" "dynamic_admin_initialized username=$ADMIN_USERNAME"

stage "步骤3：初始化后正常启动并验证登录"
start_gateway_background "$SUCCESS_LOG"
if wait_for_gateway_ready; then
    pass
else
    fail "初始化后 gateway 启动超时，请查看 $SUCCESS_LOG"
    exit 1
fi

LOGIN_BODY="$(http_body -X POST "$GATEWAY_URL/auth/login" -H 'Content-Type: application/json' -d '{"username":"'"$ADMIN_USERNAME"'","password":"'"$ADMIN_PASSWORD"'"}')"
LOGIN_TOKEN="$(jq_value "$LOGIN_BODY" '.accessToken // empty')"
assert "动态管理员登录成功" "true" "$(if [ -n "$LOGIN_TOKEN" ] && [ "$LOGIN_TOKEN" != "null" ]; then echo "true"; else echo "false"; fi)"

ME_BODY="$(http_body "$GATEWAY_URL/auth/me" -H "Authorization: Bearer $LOGIN_TOKEN")"
assert "/auth/me 返回动态管理员用户名" "$ADMIN_USERNAME" "$(jq_value "$ME_BODY" '.username // empty')"
assert "/auth/me 返回管理员角色" "admin" "$(jq_value "$ME_BODY" '.role // empty')"
assert "/auth/me 返回 displayName" "$ADMIN_DISPLAY_NAME" "$(jq_value "$ME_BODY" '.displayName // empty')"
assert "/auth/me 返回 email" "$ADMIN_EMAIL" "$(jq_value "$ME_BODY" '.email // empty')"

summary

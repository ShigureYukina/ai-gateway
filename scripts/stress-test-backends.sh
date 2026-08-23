#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_DIR/scripts/lib.sh"

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
MOCK_URL="${MOCK_URL:-http://localhost:18080}"
BUILD_OPTS="${BUILD_OPTS:--DskipTests -q}"

LOG_DIR="$PROJECT_DIR/build"
GATEWAY_LOG="$LOG_DIR/gateway-stress-backends.log"
MOCK_LOG="$LOG_DIR/mock-stress-backends.log"
JMETER_LOG="$LOG_DIR/jmeter-stress-backends.log"

JMETER_BIN="${JMETER_BIN:-jmeter}"
STRESS_PLAN="$PROJECT_DIR/scripts/stress-plan.jmx"
RESULTS_CSV="$LOG_DIR/stress-backends-results.csv"
WARMUP_CSV="$LOG_DIR/stress-warmup-results.csv"
JFR_FILE="$LOG_DIR/stress-profile.jfr"
PERF_REPORT="$LOG_DIR/perf-backends-report.txt"

OPENAI_MOCK_SCRIPT="$PROJECT_DIR/jmeter/mock_openai_server_node.mjs"
BOOTSTRAP_JAR="$PROJECT_DIR/bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar"
MAVEN="$PROJECT_DIR/mvnw"

STATIC_KEY="demo-client-key"
CHAT_ENDPOINT="$GATEWAY_URL/v1/chat/completions"
STRESS_MAIN_CLIENT="$STATIC_KEY"
STRESS_MAIN_DAILY_COST="1000"
STRESS_MAIN_MONTHLY_COST="10000"

# PostgreSQL/Redis 连接配置（与 regression-backends.sh 保持一致）
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-5433}"
PGDATABASE="${PGDATABASE:-llm_gateway}"
PGUSER="${PGUSER:-llm_user}"
PGPASSWORD="${PGPASSWORD:-llm_password}"
RUN_SCHEMA="${RUN_SCHEMA:-stress_$(date +%s)}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"

SHOULD_BUILD=false
KEEP_RUNNING=false
ECHO_MODE=false
ECHO_PORT=18089
ECHO_MODE=false

GATEWAY_PID=""
MOCK_PID=""
ADMIN_TOKEN=""
ADMIN_AUTH=""

declare -a PERF_SUMMARY=()

show_help() {
    cat <<'EOF'
AI Gateway 并发压测脚本（PostgreSQL + Redis + HYBRID，JMeter）

用法:
  ./scripts/stress-test-backends.sh
  ./scripts/stress-test-backends.sh --build
  ./scripts/stress-test-backends.sh --keep-running
  ./scripts/stress-test-backends.sh --with-echo     # in-JVM mock 消除上游变量
  ./scripts/stress-test-backends.sh --help

说明:
  该脚本是当前唯一保留的压测入口，
  用于 PostgreSQL + Redis + HYBRID shared-state 场景下的高压吞吐确认，不包含功能回归断言。

参数:
  --build         启动前构建 bootstrap JAR
  --keep-running  压测结束后保留 gateway 与 mock 进程
  --with-echo     使用 in-JVM echo endpoint 替代 Node.js mock，消除上游变量影响
  --help          显示帮助
EOF
}

skip_script() {
    info "$1"
    printf '\n═══════════════════════════════════════════\n'
    printf '  结果: SKIP\n'
    printf '═══════════════════════════════════════════\n'
    exit 0
}

cleanup() {
    if [ "$KEEP_RUNNING" = true ]; then
        info "Skipping teardown (--keep-running)"
        return 0
    fi

    stage "Cleanup"

    if [ -n "$ADMIN_AUTH" ]; then
        http_code -X DELETE "$GATEWAY_URL/admin/routes/gpt-4.1-mini" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
        http_code -X DELETE "$GATEWAY_URL/admin/routes/gpt-4o-mini" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
        http_code -X DELETE "$GATEWAY_URL/admin/providers/mock" -H "$ADMIN_AUTH" >/dev/null 2>&1 || true
    fi

    if [ -n "${RUN_SCHEMA:-}" ]; then
        drop_schema "$PGHOST"
    fi

    kill_pid_gracefully "$GATEWAY_PID" "gateway"
    if [ "$ECHO_MODE" = false ]; then
        kill_pid_gracefully "$MOCK_PID" "mock upstream"
    fi
}

trap cleanup EXIT

# ---- JMeter CSV 结果解析 ----

jmeter_sample_count() {
    local file="$1" label="$2" status="$3"
    awk -F, -v l="$label" -v s="$status" 'NR>1 && $3==l && $4==s {count++} END {print count+0}' "$file"
}

jmeter_total_count() {
    local file="$1" label="$2"
    awk -F, -v l="$label" 'NR>1 && $3==l {count++} END {print count+0}' "$file"
}

jmeter_metric_value() {
    local file="$1" label="$2" key="$3"
    case "$key" in
        total) jmeter_total_count "$file" "$label" ;;
        200|429|500|503) jmeter_sample_count "$file" "$label" "$key" ;;
    esac
}

parse_jmeter_latency() {
    local file="$1" label="$2" percentile="$3"
    awk -F, -v l="$label" -v p="$percentile" '
        NR>1 && $3==l {
            vals[count++]=$2+0
        }
        END {
            if (count == 0) { print "0"; exit 0 }
            n = asort(vals)
            idx = int(n * p / 100)
            if (idx >= n) idx = n - 1
            printf "%.2f", vals[idx]
        }
    ' "$file"
}

assert_float_ge() {
    local desc="$1" actual="$2" expected="$3" detail="${4:-got $actual, expected >= $expected}"
    STEP=$((STEP + 1))
    printf '  [%02d] %s ... ' "$STEP" "$desc"
    if awk -v a="$actual" -v b="$expected" 'BEGIN { exit !((a + 0) >= (b + 0)) }'; then
        pass
    else
        fail "$detail"
    fi
}

append_perf_summary() {
    local label="$1" file="$2" scenario="$3"
    local total p50 p99 s200 s429 s503 s429_ratio
    total="$(jmeter_metric_value "$file" "$scenario" total)"
    s200="$(jmeter_metric_value "$file" "$scenario" 200)"
    s429="$(jmeter_metric_value "$file" "$scenario" 429)"
    s503="$(jmeter_metric_value "$file" "$scenario" 503)"
    p50="$(parse_jmeter_latency "$file" "$scenario" 50)"
    p99="$(parse_jmeter_latency "$file" "$scenario" 99)"

    # 单一高压吞吐场景只保留最关键的吞吐与延迟指标。
    case "$label" in
        high-pressure)
            PERF_SUMMARY+=("$label | total=$total | p50=${p50:-n/a}ms | p99=${p99:-n/a}ms | 200=$s200")
            ;;
    esac
}

jmeter_sample_window_seconds() {
    local file="$1" label="$2"
    awk -F, -v l="$label" '
        NR>1 && $3==l {
            if (min == "" || $1 < min) min = $1
            if (max == "" || $1 > max) max = $1
            count++
        }
        END {
            if (count <= 1) {
                print "0.00"
                exit 0
            }
            printf "%.2f", (max - min) / 1000
        }
    ' "$file"
}

resolve_pg_host() {
    local socket="${PGHOST:-/var/run/postgresql}"
    local tcp="127.0.0.1"
    local port="${PGPORT:-5433}"
    local user="${PGUSER:-llm_user}"
    local db="${PGDATABASE:-llm_gateway}"

    if command -v pg_isready >/dev/null 2>&1; then
        if pg_isready -h "$socket" -p "$port" -U "$user" -d "$db" >/dev/null 2>&1; then
            printf '%s\n' "$socket"
            return 0
        fi
        if pg_isready -h "$tcp" -p "$port" -U "$user" -d "$db" >/dev/null 2>&1; then
            printf '%s\n' "$tcp"
            return 0
        fi
    fi

    printf '%s\n' "$socket"
}

ensure_database() {
    local pg_host="$1"
    local port="${PGPORT:-5433}"
    local user="${PGUSER:-llm_user}"
    local database="${PGDATABASE:-llm_gateway}"

    if ! command -v psql >/dev/null 2>&1; then
        skip_script "psql not found; cannot initialize PostgreSQL database for backend stress test"
    fi

    if ! PGPASSWORD="${PGPASSWORD:-llm_password}" psql -U "$user" -h "$pg_host" -p "$port" -tc "SELECT 1 FROM pg_database WHERE datname='${database}'" | grep -q 1; then
        PGPASSWORD="${PGPASSWORD:-llm_password}" psql -U "$user" -h "$pg_host" -p "$port" -c "CREATE DATABASE ${database}"
    fi
}

ensure_schema() {
    local pg_host="$1"
    PGPASSWORD="${PGPASSWORD:-llm_password}" psql -U "$PGUSER" -h "$pg_host" -p "$PGPORT" -d "$PGDATABASE" -c "CREATE SCHEMA IF NOT EXISTS \"${RUN_SCHEMA}\" AUTHORIZATION \"${PGUSER}\"" >/dev/null
}

drop_schema() {
    local pg_host="$1"
    PGPASSWORD="${PGPASSWORD:-llm_password}" psql -U "$PGUSER" -h "$pg_host" -p "$PGPORT" -d "$PGDATABASE" -c "DROP SCHEMA IF EXISTS \"${RUN_SCHEMA}\" CASCADE" >/dev/null 2>&1 || true
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

    skip_script "PostgreSQL/Redis not available; skipping backend stress test"
}

wait_for_gateway() {
    printf '[%s] Waiting for gateway readiness' "$(timestamp)"
    for _ in $(seq 1 30); do
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

build_gateway() {
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

start_openai_mock() {
    require_file "$OPENAI_MOCK_SCRIPT" "OpenAI mock script"
    info "Starting OpenAI mock on $MOCK_URL"
    node "$OPENAI_MOCK_SCRIPT" >"$MOCK_LOG" 2>&1 &
    MOCK_PID=$!
    sleep 2
    if ! kill -0 "$MOCK_PID" 2>/dev/null; then
        log_line "$RED" "OpenAI mock failed to start; see $MOCK_LOG"
        exit 2
    fi
}

start_gateway() {
    require_file "$BOOTSTRAP_JAR" "bootstrap JAR"
    local profiles="local,test-pg"
    if [ "$ECHO_MODE" = true ]; then
        profiles="${profiles},stress-test"
        info "  Profiles: ${profiles} (echo mode)"
    else
        info "  Profiles: ${profiles}"
    fi
    info "Starting gateway with PostgreSQL + Redis + HYBRID shared-state..."
    info "  Tuning: boundedElastic=200, HikariCP=100, reWriteBatchedInserts=true"
    info "  Isolated schema: ${RUN_SCHEMA}"
    info "  JFR profiling enabled: $JFR_FILE"
    java -Dreactor.schedulers.defaultBoundedElasticSize=200 \
        -Xms512m -Xmx1024m -XX:+UseG1GC \
        -XX:StartFlightRecording=name=stress,filename="$JFR_FILE",settings=profile,dumponexit=true,maxsize=250m \
        -XX:FlightRecorderOptions:stackdepth=256 \
        -jar "$BOOTSTRAP_JAR" \
        --server.port="$(port_from_url "$GATEWAY_URL")" \
        --spring.profiles.active="${profiles}" \
        --gateway.shared-state.backend=hybrid \
        --spring.datasource.url="jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}?currentSchema=${RUN_SCHEMA}&reWriteBatchedInserts=true" \
        --spring.datasource.username="${PGUSER}" \
        --spring.datasource.password="${PGPASSWORD}" \
        --spring.datasource.driver-class-name=org.postgresql.Driver \
        --spring.datasource.hikari.maximum-pool-size=100 \
        --spring.flyway.enabled=true \
        --spring.flyway.default-schema="${RUN_SCHEMA}" \
        --spring.flyway.schemas[0]="${RUN_SCHEMA}" \
        --spring.jpa.properties.hibernate.default_schema="${RUN_SCHEMA}" \
        --spring.data.redis.host="${REDIS_HOST}" \
        --spring.data.redis.port="${REDIS_PORT}" \
        --spring.data.redis.password= \
        >"$GATEWAY_LOG" 2>&1 &
    GATEWAY_PID=$!
    if ! wait_for_gateway; then
        log_line "$RED" "Gateway failed to become ready; see $GATEWAY_LOG"
        exit 2
    fi
}

login_admin() {
    stage "Setup: admin login & provider/route"
    local login_body provider_payload alias_route_payload direct_route_payload primary_route_payload fallback_route_payload login_token

    login_body="$(http_body -X POST "$GATEWAY_URL/auth/login" -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}')"
    login_token="$(jq_value "$login_body" '.accessToken // empty')"
    ADMIN_TOKEN="$login_token"
    ADMIN_AUTH="Authorization: Bearer $ADMIN_TOKEN"
    STEP=$((STEP + 1))
    printf '  [%02d] Admin login returns token ... ' "$STEP"
    if [ -n "$ADMIN_TOKEN" ] && [ "$ADMIN_TOKEN" != "null" ]; then
        pass
    else
        fail "accessToken empty"
    fi

    provider_payload='{"type":"openai-compatible","baseUrl":"'"$MOCK_URL"'","apiKey":"sk-mock","timeout":"10s"}'
    check "Create mock provider" test "$(http_code -X PUT "$GATEWAY_URL/admin/providers/mock" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$provider_payload")" -lt 300

    primary_route_payload='{"provider":"mock","upstreamModel":"gpt-4o-mini"}'
    fallback_route_payload='{"provider":"mock","upstreamModel":"gpt-4o-mini"}'
    alias_route_payload='{"scene":"default-chat"}'
    direct_route_payload='{"provider":"mock","upstreamModel":"gpt-4.1-mini"}'
    check "Create mock primary route" test "$(http_code -X PUT "$GATEWAY_URL/admin/routes/openai-primary" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$primary_route_payload")" -lt 300
    check "Create mock fallback route" test "$(http_code -X PUT "$GATEWAY_URL/admin/routes/openai-fallback" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$fallback_route_payload")" -lt 300
    check "Create mock alias route" test "$(http_code -X PUT "$GATEWAY_URL/admin/routes/gpt-4o-mini" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$alias_route_payload")" -lt 300
    check "Create mock direct route" test "$(http_code -X PUT "$GATEWAY_URL/admin/routes/gpt-4.1-mini" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$direct_route_payload")" -lt 300
}

smoke_check_chat() {
    local smoke_body smoke_code

    stage "Smoke check"
    smoke_body='{"model":"gpt-4o-mini","messages":[{"role":"user","content":"ping"}],"max_tokens":16}'
    smoke_code="$(http_code -X POST "$CHAT_ENDPOINT" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -d "$smoke_body")"
    assert_2xx "Smoke chat completes successfully" "$smoke_code"
    if [[ ! "$smoke_code" =~ ^2[0-9][0-9]$ ]]; then
        info "Smoke check failed; see $GATEWAY_LOG"
        exit 2
    fi
}

ensure_stress_main_client_cost_budget() {
    local clients_body client_body client_payload current_daily current_monthly put_code

    STEP=$((STEP + 1))
    printf '  [%02d] Stress 主 client 存在 ... ' "$STEP"
    clients_body="$(http_body "$GATEWAY_URL/admin/clients" -H "$ADMIN_AUTH")"
    if [ "$(jq_value "$clients_body" '.clients | has("****-key")')" = "true" ]; then
        pass
    else
        fail "masked stress client not found in admin clients list"
        return 1
    fi

    client_body="$(http_body "$GATEWAY_URL/admin/clients" -H "$ADMIN_AUTH")"
    client_payload="$(printf '%s' "$client_body" | jq -c '.clients["****-key"] // empty')"
    if [ -z "$client_payload" ] || [ "$client_payload" = "null" ]; then
        fail "client $STRESS_MAIN_CLIENT details missing in admin clients list"
        return 1
    fi

    current_daily="$(printf '%s' "$client_payload" | jq -r '.limits.dailyCost // empty')"
    current_monthly="$(printf '%s' "$client_payload" | jq -r '.limits.monthlyCost // empty')"

    if [ -n "$current_daily" ] && [ "$current_daily" != "null" ] && [ -n "$current_monthly" ] && [ "$current_monthly" != "null" ]; then
        info "Stress 主 client 已存在 cost budget，跳过补齐"
        return 0
    fi

    # 主压测流量使用 admin JWT，会映射到 demo-client-key；这里直接通过 admin client 写接口补齐 cost budget，
    # 并补齐 mixed-model 压测需要的 allowedModels，避免继续走 addDailyCost 快路径，同时不依赖
    # /admin/config/export 的脱敏 key 结构。
    client_payload="$(printf '%s' "$client_payload" | jq -c \
        --argjson dailyCost "$STRESS_MAIN_DAILY_COST" \
        --argjson monthlyCost "$STRESS_MAIN_MONTHLY_COST" \
        '.allowedModels = (((.allowedModels // []) + ["gpt-4o-mini", "gpt-4.1-mini"]) | unique)
        | .limits = ((.limits // {}) + {dailyCost: $dailyCost, monthlyCost: $monthlyCost})')"
    put_code="$(http_code -X PUT "$GATEWAY_URL/admin/clients/$STRESS_MAIN_CLIENT" -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d "$client_payload")"
    assert_status_one_of "补齐 stress 主 client cost budget" "$put_code" "200" "201"
    if [ "$put_code" != "200" ] && [ "$put_code" != "201" ]; then
        return 1
    fi
}

# ---- 参数解析 ----
while [ $# -gt 0 ]; do
    case "$1" in
        --build) SHOULD_BUILD=true ;;
        --keep-running) KEEP_RUNNING=true ;;
        --with-echo) ECHO_MODE=true ;;
        --help) show_help; exit 0 ;;
        *) log_line "$RED" "Unknown argument: $1"; exit 1 ;;
    esac
    shift
done

mkdir -p "$LOG_DIR"

# ---- PRE-FLIGHT ----
stage "Pre-flight"
for cmd in curl jq java node lsof jmeter; do
    check "$cmd installed" command -v "$cmd"
done
check "Stress plan exists" test -f "$STRESS_PLAN"

JAVA_VERSION_OUTPUT="$(java -version 2>&1 || true)"
JAVA_MAJOR="0"
JAVA_VERSION_PATTERN='version[[:space:]]+"([0-9]+)'
if [[ "$JAVA_VERSION_OUTPUT" =~ $JAVA_VERSION_PATTERN ]]; then
    JAVA_MAJOR="${BASH_REMATCH[1]}"
fi
assert_gt "Java version is 21+" "${JAVA_MAJOR:-0}" 20 "got ${JAVA_MAJOR:-unknown}, expected >= 21"

JMETER_VERSION="$($JMETER_BIN --version 2>&1 | sed -n '1p')"
STEP=$((STEP + 1))
printf '  [%02d] JMeter version ... ' "$STEP"
if [ -n "$JMETER_VERSION" ]; then
    printf '%s\n' "$JMETER_VERSION"
    pass
else
    fail "unable to detect JMeter version"
fi

kill_port_processes 8081 "gateway"
kill_port_processes 18080 "openai mock"

if [ "$SHOULD_BUILD" = true ]; then
    build_gateway
else
    check "Bootstrap JAR exists" test -f "$BOOTSTRAP_JAR"
fi

# 检查后端前置依赖
check "pg_isready installed" command -v pg_isready
check "redis-cli installed" command -v redis-cli
ensure_backends_running
PGHOST="$(resolve_pg_host)"
export PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD REDIS_HOST REDIS_PORT
ensure_database "$PGHOST"
ensure_schema "$PGHOST"

# ---- START SERVICES ----
stage "Start services"
if [ "$ECHO_MODE" = true ]; then
    info "Echo mode enabled — using in-JVM echo endpoint (port ${ECHO_PORT})"
    export ECHO_DIAGNOSTIC_PORT="$ECHO_PORT"
    start_gateway
    # 等待 echo server 就绪（gateway @PostConstruct 里会自动启动）
    info "Waiting for echo server on port ${ECHO_PORT}..."
    wait_for_url "http://localhost:${ECHO_PORT}/v1/models" 10 "echo upstream"
    MOCK_URL="http://localhost:${ECHO_PORT}"
    info "Echo mode: MOCK_URL set to ${MOCK_URL}"
else
    start_openai_mock
    check "OpenAI mock models endpoint responds" test "$(http_code "$MOCK_URL/v1/models")" = "200"
    start_gateway
fi
check "Gateway /healthz returns 200" test "$(http_code "$GATEWAY_URL/healthz")" = "200"

# ---- HEALTH ----
stage "Health"
HEALTH_BODY="$(http_body "$GATEWAY_URL/healthz")"
assert "Health check returns UP" "UP" "$(jq_value "$HEALTH_BODY" '.status // empty')"

login_admin
ensure_stress_main_client_cost_budget
smoke_check_chat

# ---- WARM-UP (results discarded) ----
stage "Warm-up"
rm -f "$WARMUP_CSV"
info "Running warm-up pass to stabilize JIT / connection pools / caches..."
"$JMETER_BIN" -n \
    -t "$STRESS_PLAN" \
    -l "$WARMUP_CSV" \
    -Jauth.token="$ADMIN_TOKEN" \
    >"$JMETER_LOG" 2>&1 && \
    info "Warm-up complete (results discarded)" || \
    log_line "$RED" "Warm-up pass had errors (continuing anyway)"
rm -f "$WARMUP_CSV"

# ---- RUN JMETER STRESS PLAN (measurement) ----
stage "JMeter stress test (measurement)"
rm -f "$RESULTS_CSV"
info "Running stress-plan.jmx (single high-pressure throughput scenario)..."
info "  - Mixed-model steady traffic with burst overlay"

JMETER_START="$(date +%s)"
set +e
"$JMETER_BIN" -n \
    -t "$STRESS_PLAN" \
    -l "$RESULTS_CSV" \
    -Jauth.token="$ADMIN_TOKEN" \
    >"$JMETER_LOG" 2>&1
JMETER_EXIT=$?
set -e
JMETER_ELAPSED="$(( $(date +%s) - JMETER_START ))"

if [ "$JMETER_EXIT" -ne 0 ]; then
    log_line "$RED" "JMeter exited with code $JMETER_EXIT"
    info "JMeter output: $JMETER_LOG"
fi
info "JMeter completed in ${JMETER_ELAPSED}s"
info "Results: $RESULTS_CSV"

# ---- VALIDATE RESULTS ----
stage "Validation"

HIGH_PRESSURE_TOTAL=$(jmeter_metric_value "$RESULTS_CSV" "high-pressure-chat" total)
HIGH_PRESSURE_200=$(jmeter_metric_value "$RESULTS_CSV" "high-pressure-chat" 200)
STEP=$((STEP + 1))
printf '  [%02d] High-pressure total=%d, 200=%d ... ' "$STEP" "$HIGH_PRESSURE_TOTAL" "$HIGH_PRESSURE_200"
if [ "$HIGH_PRESSURE_TOTAL" -gt 0 ] && [ "$HIGH_PRESSURE_200" -gt 0 ]; then
    pass
elif [ "$HIGH_PRESSURE_TOTAL" -le 0 ]; then
    fail "high-pressure scenario produced no requests"
else
    fail "high-pressure scenario produced no 2xx responses"
fi
append_perf_summary "high-pressure" "$RESULTS_CSV" "high-pressure-chat"

# ---- SUMMARY ----
stage "Performance summary"
TOTAL_REQUESTS=0
SAMPLE_WINDOW_SECONDS="$(jmeter_sample_window_seconds "$RESULTS_CSV" "high-pressure-chat")"
for scenario in high-pressure-chat; do
    TOTAL_REQUESTS=$((TOTAL_REQUESTS + $(jmeter_metric_value "$RESULTS_CSV" "$scenario" total)))
done
for line in "${PERF_SUMMARY[@]}"; do
    printf '  - %s\n' "$line"
done
printf '  - total-requests=%s\n' "$TOTAL_REQUESTS"
printf '  - sample-window=%ss\n' "$SAMPLE_WINDOW_SECONDS"
printf '  - elapsed=%ss\n' "$JMETER_ELAPSED"

{
    echo "=== Stress Test Backends Report ==="
    echo "Backend: PostgreSQL + Redis + HYBRID shared-state (single high-pressure chat throughput scenario)"
    echo "Date: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "SampleWindowSeconds=$SAMPLE_WINDOW_SECONDS"
    echo "ElapsedSeconds=$JMETER_ELAPSED"
    echo "TotalRequests=$TOTAL_REQUESTS"
    echo ""
    for line in "${PERF_SUMMARY[@]}"; do
        echo "$line"
    done
} > "$PERF_REPORT"

# ---- JFR PROFILE SUMMARY ----
if [ -f "$JFR_FILE" ]; then
    JFR_SIZE=$(stat -c%s "$JFR_FILE" 2>/dev/null || stat -f%z "$JFR_FILE" 2>/dev/null || echo "0")
    JFR_SIZE_MB=$(( JFR_SIZE / 1048576 ))
    JFR_SIZE_GB=$(( JFR_SIZE_MB / 1024 ))
    if [ "$JFR_SIZE_MB" -gt 0 ]; then
        stage "JFR profile (${JFR_SIZE_MB}MB — use JDK Mission Control to open)"
        info "File: $JFR_FILE"
        jfr summary "$JFR_FILE" 2>/dev/null || info "(jfr summary unavailable)"
    fi
fi

summary

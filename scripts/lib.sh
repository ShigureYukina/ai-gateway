#!/usr/bin/env bash
# ============================================================================
# AI Gateway — 黑盒测试公共函数库
#
# 被 regression.sh / verify.sh / verify-supplement.sh source 使用。
# 需要在 source 前定义 PROJECT_DIR 变量。
# ============================================================================

# ── Colors ──
GREEN='\033[0;32m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

# ── State (在每个 source 处重置) ──
PASS=0
FAIL=0
STEP=0
TOTAL=0

# ── Utility ──

timestamp() {
    date '+%Y-%m-%d %H:%M:%S'
}

log_line() {
    local color="$1"
    local msg="$2"
    printf '[%s] %b%s%b\n' "$(timestamp)" "$color" "$msg" "$NC"
}

info() {
    printf '[%s] %s\n' "$(timestamp)" "$1"
}

stage() {
    printf '\n[%s] %b── Stage: %s ──%b\n' "$(timestamp)" "$CYAN" "$1" "$NC"
}

# ── Assertion ──

pass() {
    PASS=$((PASS + 1))
    TOTAL=$((TOTAL + 1))
    printf '%b✓ PASS%b\n' "$GREEN" "$NC"
}

fail() {
    local msg="${1:-}"
    FAIL=$((FAIL + 1))
    TOTAL=$((TOTAL + 1))
    if [ -n "$msg" ]; then
        printf '%b✗ FAIL%b (%s)\n' "$RED" "$NC" "$msg"
    else
        printf '%b✗ FAIL%b\n' "$RED" "$NC"
    fi
}

assert() {
    local desc="$1"
    local expected="$2"
    local actual="$3"
    local detail="${4:-got $actual, expected $expected}"
    STEP=$((STEP + 1))
    printf '  [%02d] %s ... ' "$STEP" "$desc"
    if [ "$actual" = "$expected" ]; then
        pass
    else
        fail "$detail"
    fi
}

assert_gt() {
    local desc="$1"
    local actual="$2"
    local min="$3"
    local detail="${4:-got $actual, expected > $min}"
    STEP=$((STEP + 1))
    printf '  [%02d] %s ... ' "$STEP" "$desc"
    if [[ "$actual" =~ ^-?[0-9]+$ ]] && [[ "$min" =~ ^-?[0-9]+$ ]] && [ "$actual" -gt "$min" ]; then
        pass
    else
        fail "$detail"
    fi
}

assert_contains() {
    local desc="$1"
    local body="$2"
    local expected="$3"
    local detail="${4:-body does not contain $expected}"
    STEP=$((STEP + 1))
    printf '  [%02d] %s ... ' "$STEP" "$desc"
    if [[ "$body" == *"$expected"* ]]; then
        pass
    else
        fail "$detail"
    fi
}

assert_status_one_of() {
    local desc="$1"
    local actual="$2"
    shift 2
    local expected_list=("$@")
    local expected joined
    STEP=$((STEP + 1))
    printf '  [%02d] %s ... ' "$STEP" "$desc"
    for expected in "${expected_list[@]}"; do
        if [ "$actual" = "$expected" ]; then
            pass
            return 0
        fi
    done
    joined="${expected_list[*]}"
    fail "got $actual, expected one of: $joined"
}

assert_2xx() {
    local desc="$1"
    local actual="$2"
    STEP=$((STEP + 1))
    printf '  [%02d] %s ... ' "$STEP" "$desc"
    if [[ "$actual" =~ ^2[0-9][0-9]$ ]]; then
        pass
    else
        fail "got $actual, expected 2xx"
    fi
}

check() {
    local desc="$1"
    shift
    STEP=$((STEP + 1))
    printf '  [%02d] %s ... ' "$STEP" "$desc"
    if "$@" >/dev/null 2>&1; then
        pass
    else
        fail "command failed: $*"
    fi
}

# ── HTTP helpers ──

http_code() {
    curl -s -o /dev/null -w "%{http_code}" "$@" 2>/dev/null || printf '000'
}

http_body() {
    curl -s "$@" 2>/dev/null || true
}

jq_value() {
    local body="$1"
    local expr="$2"
    printf '%s' "$body" | jq -r "$expr" 2>/dev/null || true
}

jq_number() {
    local body="$1"
    local expr="$2"
    printf '%s' "$body" | jq -r "$expr" 2>/dev/null || printf 'NaN'
}

# ── File / Port / Process ──

require_file() {
    local path="$1"
    local desc="$2"
    if [ ! -f "$path" ]; then
        log_line "$RED" "Missing $desc: $path"
        exit 2
    fi
}

port_from_url() {
    local url="$1"
    local rest hostport
    rest="${url#*://}"
    hostport="${rest%%/*}"
    if [[ "$hostport" == *:* ]]; then
        printf '%s\n' "${hostport##*:}"
    else
        printf '80\n'
    fi
}

list_port_pids() {
    local port="$1"
    lsof -ti ":$port" 2>/dev/null || true
}

is_port_busy() {
    local port="$1"
    [ -n "$(list_port_pids "$port")" ]
}

kill_pid_gracefully() {
    local pid="$1"
    local label="$2"
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
        return 0
    fi
    info "Stopping $label (PID $pid)"
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 10); do
        if ! kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    info "$label did not exit in time, forcing kill"
    kill -9 "$pid" 2>/dev/null || true
}

# ── Wait helpers ──

# Wait for a server to start accepting HTTP connections.
# Returns once curl gets any response (even 4xx/5xx — just means the server is up).
wait_for_url() {
    local url="$1"
    local timeout="$2"
    local desc="${3:-$url}"
    local i
    echo -n "  等待 $desc 就绪 (最长 ${timeout}s)..."
    for i in $(seq 1 "$timeout"); do
        local code
        code=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")
        if [ "$code" != "000" ]; then
            echo " 就绪 (${i}s)"
            return 0
        fi
        sleep 1
        echo -n "."
    done
    echo " 超时"
    return 1
}

kill_port_processes() {
    local port="$1"
    local label="$2"
    local pids pid
    pids="$(list_port_pids "$port")"
    if [ -z "$pids" ]; then
        return 0
    fi
    info "Cleaning existing process(es) on port $port for $label"
    for pid in $pids; do
        kill_pid_gracefully "$pid" "$label"
    done
    sleep 1
    if is_port_busy "$port"; then
        log_line "$RED" "Port $port still busy after cleanup"
        exit 2
    fi
}

# ── Summary ──

summary() {
    printf '\n═══════════════════════════════════════════\n'
    printf '  结果: %d 通过, %d 失败, 共 %d 项\n' "$PASS" "$FAIL" "$TOTAL"
    printf '═══════════════════════════════════════════\n'
    if [ "$FAIL" -gt 0 ]; then
        log_line "$RED" "Regression test FAILED"
        exit 1
    fi
    info "All tests passed!"
}

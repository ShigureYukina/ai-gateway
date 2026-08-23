#!/usr/bin/env bash
# ============================================================================
# AI Gateway — 补充黑盒验证脚本
#
# 覆盖 verify.sh / regression.sh 未覆盖的场景:
#   1. Webhook CRUD + alert trigger + delivery verification
#   2. Provider runtime state endpoint
#   3. Provider discovery endpoint
#   4. Alerts endpoint
#   5. Config audit log
#   6. System config representative endpoint (system/limit)
#
# 用法:
#   ./scripts/verify-supplement.sh                    # 完整验证
#   ./scripts/verify-supplement.sh --skip-setup       # 跳过启动
#   ./scripts/verify-supplement.sh --skip-teardown    # 验证结束后不停止进程
#   ./scripts/verify-supplement.sh --help
#
# 环境变量:
#   GATEWAY_URL   网关地址 (默认 http://localhost:8081)
#   MOCK_URL      Mock upstream 地址 (默认 http://localhost:18080)
#
# 依赖: curl, jq, node (内置 http 模块启动 webhook receiver)
# ============================================================================

set -euo pipefail

# ── 配置 ──
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
MOCK_URL="${MOCK_URL:-http://localhost:18080}"
SKIP_SETUP=false
SKIP_TEARDOWN=false
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_DIR/scripts/lib.sh"
JAR_PATH="$PROJECT_DIR/bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar"
MOCK_SCRIPT="$PROJECT_DIR/jmeter/mock_openai_server_node.mjs"

# webhook receiver
WEBHOOK_RECEIVER_PORT=18083
WEBHOOK_RECEIVER_PID=""

# ── 启动/停止 webhook receiver ──

start_webhook_receiver() {
    # 用 Node.js 内置 http 模块启动一个极简的 webhook 接收器
    node -e "
const http = require('http');
const fs = require('fs');
const LOG = '/tmp/webhook-receiver.log';
const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', c => body += c);
  req.on('end', () => {
    const entry = JSON.stringify({
      time: new Date().toISOString(),
      method: req.method,
      url: req.url,
      headers: req.headers,
      body: body
    });
    fs.appendFileSync(LOG, entry + '\n');
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
  });
});
server.listen($WEBHOOK_RECEIVER_PORT, () => {
  console.log('webhook receiver listening on ' + $WEBHOOK_RECEIVER_PORT);
});
" > /tmp/webhook-receiver-node.log 2>&1 &
    WEBHOOK_RECEIVER_PID=$!
    sleep 2
    if ! kill -0 "$WEBHOOK_RECEIVER_PID" 2>/dev/null; then
        echo "错误: webhook receiver 未能启动" >&2
        exit 1
    fi
    echo "  webhook receiver PID: $WEBHOOK_RECEIVER_PID"
}

stop_webhook_receiver() {
    if [ -n "$WEBHOOK_RECEIVER_PID" ]; then
        kill "$WEBHOOK_RECEIVER_PID" 2>/dev/null || true
        WEBHOOK_RECEIVER_PID=""
    fi
    rm -f /tmp/webhook-hits.json /tmp/webhook-receiver.log
}

cleanup() {
    echo ""
    echo "── 清理 ──"
    if [ "$SKIP_TEARDOWN" = false ]; then
        stop_webhook_receiver
        [ -n "${GATEWAY_PID:-}" ] && kill "$GATEWAY_PID" 2>/dev/null && echo "  停止 gateway (PID $GATEWAY_PID)" || true
        [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null && echo "  停止 mock upstream (PID $MOCK_PID)" || true
        echo "  清理完成"
    else
        echo "  跳过清理 (--skip-teardown)"
    fi
}

# ── 参数解析 ──

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-setup)    SKIP_SETUP=true ;;
        --skip-teardown) SKIP_TEARDOWN=true ;;
        --help)
            sed -n '3,12p' "$0"
            exit 0
            ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
    shift
done

trap cleanup EXIT

# ═══════════════════════════════════════════════════════════════════════════
# 第一阶段：环境准备
# ═══════════════════════════════════════════════════════════════════════════

echo "╔══════════════════════════════════════════╗"
echo "║  AI Gateway  补充黑盒验证脚本             ║"
echo "╚══════════════════════════════════════════╝"
echo ""

for cmd in curl jq node; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "错误: 未找到 $cmd，请先安装" >&2
        exit 1
    fi
done

if [ "$SKIP_SETUP" = false ]; then
    echo "── 阶段：启动服务 ──"

    for port in 8081 18080; do
        if lsof -i ":$port" &>/dev/null 2>&1; then
            echo "错误: 端口 $port 已被占用" >&2
            exit 1
        fi
    done

    if [ ! -f "$MOCK_SCRIPT" ]; then
        echo "错误: 未找到 mock 脚本: $MOCK_SCRIPT" >&2
        exit 1
    fi

    if [ ! -f "$JAR_PATH" ]; then
        echo "错误: 未找到 bootstrap JAR: $JAR_PATH" >&2
        echo "请先执行 ./mvnw -pl bootstrap -am package -DskipTests 构建。" >&2
        exit 1
    fi

    echo "  启动 mock upstream (端口 18080)..."
    node "$MOCK_SCRIPT" &
    MOCK_PID=$!
    sleep 2
    if ! kill -0 "$MOCK_PID" 2>/dev/null; then
        echo "错误: mock upstream 启动失败" >&2
        exit 1
    fi
    echo "  mock upstream PID: $MOCK_PID"

    echo "  启动 gateway (端口 8081)..."
    java -jar "$JAR_PATH" \
        --server.port=8081 \
        --spring.profiles.active=local \
        --gateway.shared-state.backend=in_memory \
        --spring.flyway.enabled=false &
    GATEWAY_PID=$!

    echo -n "  等待 gateway 就绪..."
    for i in $(seq 1 30); do
        if curl -sf "$GATEWAY_URL/healthz/live" &>/dev/null; then
            echo " 就绪 (${i}s)"
            break
        fi
        if [ "$i" -eq 30 ]; then
            echo " 超时"
            echo "错误: gateway 启动超时" >&2
            exit 1
        fi
        sleep 1
        echo -n "."
    done
    echo "  gateway PID: $GATEWAY_PID"
else
    echo "── 阶段：跳过启动 (--skip-setup) ──"
    check "gateway 可达" curl -sf "$GATEWAY_URL/healthz/live"
fi

# ── 管理员登录（所有场景共用） ──

echo ""
echo "── 阶段：管理员登录 ──"

LOGIN_RESP=$(http_body -X POST "$GATEWAY_URL/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}')

ADMIN_TOKEN=$(echo "$LOGIN_RESP" | jq -r '.accessToken // empty')
check "管理员登录成功" test -n "$ADMIN_TOKEN"
if [ -z "$ADMIN_TOKEN" ]; then exit 1; fi
AUTH="Authorization: Bearer $ADMIN_TOKEN"

# ═══════════════════════════════════════════════════════════════════════════
# 场景 1: Webhook CRUD + alert trigger + delivery verification
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 场景 1: Webhook CRUD + alert trigger ──"

# 1a. 启动 webhook receiver
start_webhook_receiver

WEBHOOK_URL="http://host.docker.internal:$WEBHOOK_RECEIVER_PORT"
# 如果不在 docker 环境，用 localhost
if ! curl -sf --connect-timeout 2 "$WEBHOOK_URL" &>/dev/null; then
    WEBHOOK_URL="http://localhost:$WEBHOOK_RECEIVER_PORT"
fi

# 1b. 创建 webhook endpoint (POST)
WEBHOOK_NAME="supplement-test-wh"
WEBHOOK_RESP=$(http_body -X POST "$GATEWAY_URL/admin/webhooks" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "$(cat <<EOF
{
    "name": "$WEBHOOK_NAME",
    "url": "$WEBHOOK_URL",
    "secret": "whsec-test",
    "enabled": true,
    "eventTypes": ["alert.triggered"],
    "retryMax": 1,
    "timeoutMs": 5000
}
EOF
)")

WEBHOOK_ID=$(echo "$WEBHOOK_RESP" | jq -r '.id // empty')
check "Webhook 创建成功返回 id" test -n "$WEBHOOK_ID"
assert "Webhook name 正确" "$WEBHOOK_NAME" "$(echo "$WEBHOOK_RESP" | jq -r '.name // empty')"

# 1c. 列出 webhooks
WH_LIST=$(http_body "$GATEWAY_URL/admin/webhooks" -H "$AUTH")
WH_EXISTS=$(echo "$WH_LIST" | jq --arg id "$WEBHOOK_ID" '[.endpoints[] | select(.id == ($id | tonumber))] | length > 0' 2>/dev/null || echo "false")
assert "Webhook 列表包含新创建的 webhook" "true" "$WH_EXISTS"

# 1c-2. 查询单个 webhook
WEBHOOK_GET_BODY=$(http_body "$GATEWAY_URL/admin/webhooks/$WEBHOOK_ID" -H "$AUTH")
assert "Webhook 单资源读取返回 id" "$WEBHOOK_ID" "$(echo "$WEBHOOK_GET_BODY" | jq -r '.id // empty')"
assert "Webhook 单资源读取 name 正确" "$WEBHOOK_NAME" "$(echo "$WEBHOOK_GET_BODY" | jq -r '.name // empty')"

# 1d. 触发 alert（GET /admin/alerts 会触发 webhook 调度）
# 注意：在 webhook 仍为 enabled 时触发，否则不会产生 delivery
echo "  触发 alert..."
ALERTS_RESP=$(http_body "$GATEWAY_URL/admin/alerts" -H "$AUTH")

# 1e. 等待 webhook dispatch 完成
sleep 3

# 1f. 查看 delivery 日志
DELIVERIES=$(http_body "$GATEWAY_URL/admin/webhooks/deliveries" -H "$AUTH")
DELIVERY_COUNT=$(echo "$DELIVERIES" | jq '[.deliveries[] | select(.endpointId == '"$WEBHOOK_ID"')] | length' 2>/dev/null || echo "0")

STEP=$((STEP + 1)); printf "  [%02d] Webhook delivery 日志有记录 ... " "$STEP"
if [ "$DELIVERY_COUNT" -gt 0 ]; then
    # 检查是否有 delivered 状态的记录
    DELIVERED_COUNT=$(echo "$DELIVERIES" | jq '[.deliveries[] | select(.endpointId == '"$WEBHOOK_ID"' and .status == "delivered")] | length' 2>/dev/null || echo "0")
    STEP_SAVE=$STEP
    STEP=$((STEP - 1))
    assert "Webhook delivery 有 delivered 状态" "true" "$(if [ "$DELIVERED_COUNT" -gt 0 ]; then echo "true"; else echo "false"; fi)" "deliveries=$DELIVERY_COUNT, delivered=$DELIVERED_COUNT"
    STEP=$STEP_SAVE
    pass
else
    fail "无 delivery 记录 (可能存在 pending 状态, 尝试检查状态)"
    DELIVERIES_ALL=$(echo "$DELIVERIES" | jq -r '.deliveries | length' 2>/dev/null || echo "0")
    echo "       deliveries 总数: $DELIVERIES_ALL"
fi

# 1c-3. 更新单个 webhook（先完成 delivery 验证再禁用 webhook）
WEBHOOK_UPDATED_NAME="${WEBHOOK_NAME}-updated"
WEBHOOK_UPDATE_RESP=$(curl -s -w '\n%{http_code}' -X PUT "$GATEWAY_URL/admin/webhooks/$WEBHOOK_ID" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "$(cat <<EOF
{
    "name": "$WEBHOOK_UPDATED_NAME",
    "url": "$WEBHOOK_URL",
    "secret": "whsec-test-updated",
    "enabled": false,
    "eventTypes": ["alert.triggered"],
    "retryMax": 2,
    "timeoutMs": 6000
}
EOF
)")
WEBHOOK_UPDATE_BODY=$(printf '%s' "$WEBHOOK_UPDATE_RESP" | sed '$d')
WEBHOOK_UPDATE_CODE=$(printf '%s' "$WEBHOOK_UPDATE_RESP" | sed -n '$p')
assert "Webhook 单资源更新返回 200" "200" "$WEBHOOK_UPDATE_CODE"
assert "Webhook 更新后 name 正确" "$WEBHOOK_UPDATED_NAME" "$(echo "$WEBHOOK_UPDATE_BODY" | jq -r '.name // empty')"
assert "Webhook 更新后 enabled=false" "false" "$(echo "$WEBHOOK_UPDATE_BODY" | jq -r '.enabled')"
assert "Webhook 更新后 retryMax=2" "2" "$(echo "$WEBHOOK_UPDATE_BODY" | jq -r '.retryMax // empty')"

WEBHOOK_GET_UPDATED_BODY=$(http_body "$GATEWAY_URL/admin/webhooks/$WEBHOOK_ID" -H "$AUTH")
assert "Webhook 更新后单资源读取 name 正确" "$WEBHOOK_UPDATED_NAME" "$(echo "$WEBHOOK_GET_UPDATED_BODY" | jq -r '.name // empty')"
assert "Webhook 更新后单资源读取 enabled=false" "false" "$(echo "$WEBHOOK_GET_UPDATED_BODY" | jq -r '.enabled')"

# 1g. 删除 webhook
DEL_WH_CODE=$(http_code -X DELETE "$GATEWAY_URL/admin/webhooks/$WEBHOOK_ID" -H "$AUTH")
assert_status_one_of "删除 webhook 返回 200/204" "$DEL_WH_CODE" "200" "204"

stop_webhook_receiver

# ═══════════════════════════════════════════════════════════════════════════
# 场景 2: Provider runtime state endpoint
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 场景 2: Provider runtime state ──"

RUN_RESP=$(http_body "$GATEWAY_URL/admin/providers/runtime" -H "$AUTH")
RUN_GENERATED=$(echo "$RUN_RESP" | jq -r '.generatedAt // empty')
RUN_HAS_PROVIDERS=$(echo "$RUN_RESP" | jq -r '.providers | type' 2>/dev/null || echo "")

assert "runtime 端点返回 generatedAt" "true" "$(if [ -n "$RUN_GENERATED" ]; then echo "true"; else echo "false"; fi)"
assert "runtime 端点返回 providers 对象" "object" "$RUN_HAS_PROVIDERS"

# ═══════════════════════════════════════════════════════════════════════════
# 场景 3: Provider discovery endpoint
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 场景 3: Provider discovery ──"

DISC_RESP=$(http_body "$GATEWAY_URL/admin/providers/discovery" -H "$AUTH")
DISC_HAS_PROVIDERS=$(echo "$DISC_RESP" | jq -r '.providers | type' 2>/dev/null || echo "")
DISC_VERSION=$(echo "$DISC_RESP" | jq -r '.version // empty')

assert "discovery 端点返回 providers 对象" "object" "$DISC_HAS_PROVIDERS"
assert "discovery 端点返回 version" "true" "$(if [ -n "$DISC_VERSION" ]; then echo "true"; else echo "false"; fi)"

# ═══════════════════════════════════════════════════════════════════════════
# 场景 4: Alerts endpoint
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 场景 4: Alerts ──"

ALERTS_CODE=$(http_code "$GATEWAY_URL/admin/alerts" -H "$AUTH")
assert_2xx "alerts 端点返回 2xx" "$ALERTS_CODE"

ALERTS_BODY=$(http_body "$GATEWAY_URL/admin/alerts" -H "$AUTH")
ALERTS_ACTIVE=$(echo "$ALERTS_BODY" | jq -r '.active | type' 2>/dev/null || echo "")
ALERTS_RECENT=$(echo "$ALERTS_BODY" | jq -r '.recent | type' 2>/dev/null || echo "")
ALERTS_GENERATED=$(echo "$ALERTS_BODY" | jq -r '.generatedAt // empty')

assert "alerts 返回 active 数组" "array" "$ALERTS_ACTIVE"
assert "alerts 返回 recent 数组" "array" "$ALERTS_RECENT"
assert "alerts 返回 generatedAt" "true" "$(if [ -n "$ALERTS_GENERATED" ]; then echo "true"; else echo "false"; fi)"

# 查看 alerts 中包含 section 类型（route-disabled, circuit-open 等）
ACTIVE_IDS=$(echo "$ALERTS_BODY" | jq -r '.active[].id // empty' 2>/dev/null | tr '\n' ' ')
STEP=$((STEP + 1)); printf "  [%02d] alerts 含预计的 section 类型 ... " "$STEP"
if echo "$ACTIVE_IDS" | grep -q "route-disabled" || echo "$ACTIVE_IDS" | grep -q "circuit-open"; then
    pass
else
    # 没有活跃 alert 也没关系 — 至少检查结构
    ALERTS_EMPTY=$(echo "$ALERTS_BODY" | jq '.active | length' 2>/dev/null || echo "0")
    if [ "$ALERTS_EMPTY" -eq 0 ]; then
        echo "       (无活跃 alert, 结构正确)"
        pass
    else
        fail "预期 alert id 包含 route-disabled 或 circuit-open, 实际: $ACTIVE_IDS"
    fi
fi

# ═══════════════════════════════════════════════════════════════════════════
# 场景 5: System config — system/limit
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 场景 5: System config (system/limit) ──"

LIMIT_PAYLOAD='{"requestsPerWindow": 100, "window": "PT2M"}'
LIMIT_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/system/limit" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "$LIMIT_PAYLOAD")
assert_2xx "system/limit 返回 2xx" "$LIMIT_CODE"

LIMIT_BODY=$(http_body -X PUT "$GATEWAY_URL/admin/system/limit" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "$LIMIT_PAYLOAD")

LIMIT_REQUESTS=$(echo "$LIMIT_BODY" | jq -r '.requestsPerWindow // empty')
assert "system/limit 返回 requestsPerWindow" "100" "$LIMIT_REQUESTS"

# ═══════════════════════════════════════════════════════════════════════════
# 场景 6: Config audit log（放在 system/limit 之后，确保审计记录已生成）
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 场景 6: Config audit log ──"

AUDIT_CODE=$(http_code "$GATEWAY_URL/internal/config/audit" -H "$AUTH")
assert_2xx "config audit 端点返回 2xx" "$AUDIT_CODE"

AUDIT_BODY=$(http_body "$GATEWAY_URL/internal/config/audit" -H "$AUTH")
AUDIT_ENTRIES=$(echo "$AUDIT_BODY" | jq -r '.entries | type' 2>/dev/null || echo "")
AUDIT_GENERATED=$(echo "$AUDIT_BODY" | jq -r '.generatedAt // empty')

assert "audit 返回 entries 数组" "array" "$AUDIT_ENTRIES"
assert "audit 返回 generatedAt" "true" "$(if [ -n "$AUDIT_GENERATED" ]; then echo "true"; else echo "false"; fi)"

# 检查 audit entries 非空（之前的配置操作应产生审计记录）
AUDIT_COUNT=$(echo "$AUDIT_BODY" | jq '.entries | length' 2>/dev/null || echo "0")
assert_gt "audit 有记录" "$AUDIT_COUNT" 0

# 检查 audit-center（统一审计视图）
AUDIT_CENTER_CODE=$(http_code "$GATEWAY_URL/internal/config/audit-center?limit=10" -H "$AUTH")
assert_2xx "audit-center 端点返回 2xx" "$AUDIT_CENTER_CODE"

AUDIT_CENTER_BODY=$(http_body "$GATEWAY_URL/internal/config/audit-center?limit=10" -H "$AUTH")
AC_ENTRIES_TYPE=$(echo "$AUDIT_CENTER_BODY" | jq -r '.entries | type' 2>/dev/null || echo "")
assert "audit-center 返回 entries 数组" "array" "$AC_ENTRIES_TYPE"

AUDIT_CENTER_FILTER_BODY=$(http_body "$GATEWAY_URL/internal/config/audit-center?configType=system&configKey=limit&action=save&limit=10" -H "$AUTH")
assert_gt "audit-center 带过滤参数后仍有记录" "$(echo "$AUDIT_CENTER_FILTER_BODY" | jq '.entries | length' 2>/dev/null || echo "0")" 0
assert "audit-center 过滤结果均为 system/limit" "true" "$(echo "$AUDIT_CENTER_FILTER_BODY" | jq 'all(.entries[]; .resourceType == "system" and .resourceId == "limit")' 2>/dev/null || echo "false")"
assert "audit-center 过滤结果均为 save 动作" "true" "$(echo "$AUDIT_CENTER_FILTER_BODY" | jq 'all(.entries[]; .action == "save")' 2>/dev/null || echo "false")"

# ═══════════════════════════════════════════════════════════════════════════
# 结果
# ═══════════════════════════════════════════════════════════════════════════

summary

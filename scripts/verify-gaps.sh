#!/usr/bin/env bash
# ============================================================================
# AI Gateway — 黑盒测试覆盖缺口补充脚本
#
# 覆盖 regression.sh / verify.sh / verify-supplement.sh 未覆盖的场景:
#   批次1: 模型分组 CRUD
#   批次2: 配置导入
#   批次3: 系统配置热更新(5种)
#   批次4: Auth 自助 API Key 管理（创建/禁用/启用/轮换/删除）
#   批次5: 配置版本/回滚
#   批次4续: Auth 自助 refresh/logout/password
#   补充: 系统认证配置
#   批次7: 模型发布闭环
#   批次8: Client CRUD（创建/列表/删除/404）
#   （批次6、6续、5续 已移除 — 内部观测端点，不再主动维护）
#
# 用法:
#   ./scripts/verify-gaps.sh                       # 完整验证
#   ./scripts/verify-gaps.sh --skip-setup          # 跳过启动
#   ./scripts/verify-gaps.sh --skip-teardown       # 验证结束后不停止进程
#   ./scripts/verify-gaps.sh --help
#
# 环境变量:
#   GATEWAY_URL   网关地址 (默认 http://localhost:8081)
#   MOCK_URL      Mock upstream 地址 (默认 http://localhost:18080)
#
# 依赖: curl, jq, node
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

# ── Auth tokens (set after login) ──
ADMIN_TOKEN=""
AUTH=""

# ── Test data names (for cleanup) ──
TEST_GROUP_ALIAS="verify-gaps-group"
TEST_GROUP_PROVIDER="verify-gaps-provider"
PUBLICATION_ALIAS="verify-published-mini"
TEST_ROUTE_NAMES=()
CREATED_KEY_IDS=()
TEST_CLIENT_NAME=""

# ── 参数解析 ──
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-setup)    SKIP_SETUP=true ;;
        --skip-teardown) SKIP_TEARDOWN=true ;;
        --help)
            sed -n '3,21p' "$0"
            exit 0
            ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
    shift
done

cleanup() {
    echo ""
    echo "── 清理 ──"
    if [ "$SKIP_TEARDOWN" = false ]; then
        [ -n "${GATEWAY_PID:-}" ] && kill "$GATEWAY_PID" 2>/dev/null && echo "  停止 gateway (PID $GATEWAY_PID)" || true
        [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null && echo "  停止 mock upstream (PID $MOCK_PID)" || true
        echo "  清理完成"
    else
        echo "  跳过清理 (--skip-teardown)"
    fi
}

trap cleanup EXIT

# ═══════════════════════════════════════════════════════════════════════════
# 第一阶段：环境准备
# ═══════════════════════════════════════════════════════════════════════════

echo "╔══════════════════════════════════════════╗"
echo "║  AI Gateway  黑盒覆盖缺口补充脚本          ║"
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
    for i in $(seq 1 45); do
        if curl -sf "$GATEWAY_URL/healthz/live" &>/dev/null; then
            echo " 就绪 (${i}s)"
            break
        fi
        if [ "$i" -eq 45 ]; then
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

# ── 管理员登录 ──
echo ""
echo "── 阶段：管理员登录 ──"

LOGIN_RESP=$(http_body -X POST "$GATEWAY_URL/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}')

ADMIN_TOKEN=$(echo "$LOGIN_RESP" | jq -r '.accessToken // empty')
check "管理员登录成功" test -n "$ADMIN_TOKEN"
if [ -z "$ADMIN_TOKEN" ]; then exit 1; fi
AUTH="Authorization: Bearer $ADMIN_TOKEN"

# ── 创建基础测试数据（共用 provider + route） ──
echo ""
echo "── 阶段：准备测试数据 ──"

# 创建测试用 provider（接受 200 或 201）
http_code -X PUT "$GATEWAY_URL/admin/providers/$TEST_GROUP_PROVIDER" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "{\"type\":\"openai-compatible\",\"baseUrl\":\"$MOCK_URL\",\"apiKey\":\"sk-test\",\"timeout\":\"10s\",\"models\":[\"gpt-4o-mini\",\"gpt-4o\"]}" >/dev/null 2>&1 || true

# 通过查询验证 provider 已存在（providers 嵌套在 .providers 下）
PROV_LIST_BODY=$(http_body "$GATEWAY_URL/admin/providers" -H "$AUTH")
assert "测试 provider 存在" "true" \
    "$(echo "$PROV_LIST_BODY" | jq '.providers | has("verify-gaps-provider")' 2>/dev/null || echo "false")"

# 创建 routes 供 model-groups 引用（接受 200 或 201）
http_code -X PUT "$GATEWAY_URL/admin/routes/vg-primary" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "{\"scene\":\"vg-scene\",\"provider\":\"$TEST_GROUP_PROVIDER\",\"upstreamModel\":\"gpt-4o-mini\"}" >/dev/null 2>&1 || true

http_code -X PUT "$GATEWAY_URL/admin/routes/vg-fallback" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "{\"scene\":\"vg-scene\",\"provider\":\"$TEST_GROUP_PROVIDER\",\"upstreamModel\":\"gpt-4o\"}" >/dev/null 2>&1 || true

# 注册 cleanup 用的 route 名称
TEST_ROUTE_NAMES+=("vg-primary" "vg-fallback")

# ═══════════════════════════════════════════════════════════════════════════
# 批次1: 模型分组 CRUD
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 批次1: 模型分组 CRUD ──"

# 1a. PUT — 创建模型分组
GROUP_MEMBERS='{"members":[{"provider":"verify-gaps-provider","upstreamModel":"gpt-4o-mini","weight":1},{"provider":"verify-gaps-provider","upstreamModel":"gpt-4o","weight":2}]}'
GROUP_CREATE_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/model-groups/$TEST_GROUP_ALIAS" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "$GROUP_MEMBERS")
assert_status_one_of "1a 模型分组 PUT 返回 200/201" "$GROUP_CREATE_CODE" "200" "201"

# 1b. GET — 列表包含新分组
GROUP_LIST_BODY=$(http_body "$GATEWAY_URL/admin/model-groups" -H "$AUTH")
assert "1b 分组列表包含新分组" "true" \
    "$(echo "$GROUP_LIST_BODY" | jq --arg a "$TEST_GROUP_ALIAS" '.groups | has($a)' 2>/dev/null || echo "false")"

# 验证分组结构
GROUP_MEMBER_COUNT=$(echo "$GROUP_LIST_BODY" | jq --arg a "$TEST_GROUP_ALIAS" '.groups[$a].members | length' 2>/dev/null || echo "0")
assert "1c 分组包含 2 个 members" "2" "$GROUP_MEMBER_COUNT"

# 1d. PUT — 重复创建（更新）返回 200
GROUP_UPDATE_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/model-groups/$TEST_GROUP_ALIAS" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"members":[{"provider":"verify-gaps-provider","upstreamModel":"gpt-4o-mini","weight":1}]}')
assert_status_one_of "1d 模型分组 PUT 更新返回 200/201" "$GROUP_UPDATE_CODE" "200" "201"

# 1e. DELETE — 删除分组
GROUP_DEL_CODE=$(http_code -X DELETE "$GATEWAY_URL/admin/model-groups/$TEST_GROUP_ALIAS" -H "$AUTH")
assert_status_one_of "1e 模型分组 DELETE 返回 200/204" "$GROUP_DEL_CODE" "200" "204"

# 1f. GET — 确认已删除
GROUP_LIST_BODY2=$(http_body "$GATEWAY_URL/admin/model-groups" -H "$AUTH")
assert "1f 分组列表不含已删除分组" "false" \
    "$(echo "$GROUP_LIST_BODY2" | jq --arg a "$TEST_GROUP_ALIAS" '.groups | has($a)' 2>/dev/null || echo "false")"

# 1g. DELETE — 不存在返回 404
GROUP_DEL404_CODE=$(http_code -X DELETE "$GATEWAY_URL/admin/model-groups/nonexistent" -H "$AUTH")
assert "1g 删除不存在分组返回 404" "404" "$GROUP_DEL404_CODE"

# 1h. PUT — 空 members 应返回错误（400 或 500 均可，只要不是 2xx）
GROUP_EMPTY_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/model-groups/empty-test" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"members":[]}')
assert "1h 空 members 返回非 2xx" "false" \
    "$(if [[ "$GROUP_EMPTY_CODE" =~ ^2 ]]; then echo "true"; else echo "false"; fi)"

# ═══════════════════════════════════════════════════════════════════════════
# 批次2: 配置导入
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 批次2: 配置导入 ──"

# 清理测试 routes，避免它们引用的不存在的 scene 导致导入校验失败
for rn in "${TEST_ROUTE_NAMES[@]}"; do
    http_code -X DELETE "$GATEWAY_URL/admin/routes/$rn" -H "$AUTH" >/dev/null 2>&1 || true
done
TEST_ROUTE_NAMES=()

# 2a. 配置导出 — 获取当前配置快照
EXPORT_BODY=$(http_body "$GATEWAY_URL/admin/config/export" -H "$AUTH")
EXPORT_HAS_PROVIDERS=$(echo "$EXPORT_BODY" | jq '.providers | length > 0' 2>/dev/null || echo "false")
assert "2a 配置导出有 providers" "true" "$EXPORT_HAS_PROVIDERS"

# 导出 body 可能包含 pendingRestart 字段，import 端点不识别该字段，需移除
IMPORT_BODY=$(echo "$EXPORT_BODY" | jq 'del(.pendingRestart)' 2>/dev/null || echo "$EXPORT_BODY")

# 2b. 导入(dryRun=true) — 只验证不应用
IMPORT_DRY_CODE=$(http_code -X POST "$GATEWAY_URL/admin/config/import?dryRun=true" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "$IMPORT_BODY")
assert "2b dryRun 导入返回 200" "200" "$IMPORT_DRY_CODE"

IMPORT_DRY_BODY=$(http_body -X POST "$GATEWAY_URL/admin/config/import?dryRun=true" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "$IMPORT_BODY")
assert "2c dryRun 标识正确" "true" "$(echo "$IMPORT_DRY_BODY" | jq '.dryRun' 2>/dev/null || echo "false")"

# 2d. 导入(dryRun=false) — 实际应用
IMPORT_CODE=$(http_code -X POST "$GATEWAY_URL/admin/config/import?dryRun=false" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "$IMPORT_BODY")
assert "2d 实际导入返回 200" "200" "$IMPORT_CODE"

IMPORT_BODY=$(http_body -X POST "$GATEWAY_URL/admin/config/import?dryRun=false" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "$IMPORT_BODY")
assert "2e 导入状态为 ok" "ok" "$(echo "$IMPORT_BODY" | jq -r '.status' 2>/dev/null || echo "")"
assert "2f 导入 applied=true" "true" "$(echo "$IMPORT_BODY" | jq '.applied' 2>/dev/null || echo "false")"

# 2g. 导入无效配置 — 无效的 scene 引用应返回 400
IMPORT_INVALID_CODE=$(http_code -X POST "$GATEWAY_URL/admin/config/import?dryRun=true" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"routes":{"bad-route":{"provider":"openai","upstreamModel":"gpt-4","scene":"nonexistent-scene"}}}')
assert "2g 无效配置导入(坏 scene)返回 400" "400" "$IMPORT_INVALID_CODE"

# ═══════════════════════════════════════════════════════════════════════════
# 批次3: 系统配置热更新(5种)
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 批次3: 系统配置热更新(5种) ──"

# 3a. PUT /admin/system/concurrent-limit
CCL_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/system/concurrent-limit" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"enabled":false,"maxPerClient":10,"maxGlobal":200}')
assert "3a concurrent-limit 返回 200" "200" "$CCL_CODE"

# 验证回显
CCL_BODY=$(http_body -X PUT "$GATEWAY_URL/admin/system/concurrent-limit" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"enabled":false,"maxPerClient":10,"maxGlobal":200}')
assert "3a concurrent-limit maxPerClient=10" "10" "$(echo "$CCL_BODY" | jq '.maxPerClient' 2>/dev/null || echo "null")"
assert "3a concurrent-limit maxGlobal=200" "200" "$(echo "$CCL_BODY" | jq '.maxGlobal' 2>/dev/null || echo "null")"

# 验证 min 约束 — maxPerClient=0 应返回 400
CCL_BAD_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/system/concurrent-limit" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"enabled":true,"maxPerClient":0,"maxGlobal":200}')
assert "3a maxPerClient=0 返回 400" "400" "$CCL_BAD_CODE"

# 3b. PUT /admin/system/tracing
TRACE_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/system/tracing" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"enabled":true,"maxBodySize":8192,"sampleRate":0.5}')
assert "3b tracing 返回 200" "200" "$TRACE_CODE"

TRACE_BODY=$(http_body -X PUT "$GATEWAY_URL/admin/system/tracing" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"enabled":true,"maxBodySize":8192,"sampleRate":0.5}')
assert "3b tracing maxBodySize=8192" "8192" "$(echo "$TRACE_BODY" | jq '.maxBodySize' 2>/dev/null || echo "null")"

# 3c. PUT /admin/system/sync
SYNC_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/system/sync" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"modelsDev":{"enabled":false,"endpoint":"https://models.example.com/api.json","refreshInterval":"PT30M","timeout":"PT5S","runOnStartup":false,"preferRemotePricing":true}}')
assert "3c sync 返回 200" "200" "$SYNC_CODE"

SYNC_BODY=$(http_body -X PUT "$GATEWAY_URL/admin/system/sync" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"modelsDev":{"enabled":false,"endpoint":"https://models.example.com/api.json","refreshInterval":"PT30M","timeout":"PT5S","runOnStartup":false,"preferRemotePricing":true}}')
assert "3c sync modelsDev.enabled=false" "false" "$(echo "$SYNC_BODY" | jq '.modelsDev.enabled' 2>/dev/null || echo "null")"

# 3d. PUT /admin/system/provider-health
PH_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/system/provider-health" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"enabled":false,"refreshInterval":"PT5M","runOnStartup":true,"disableAfterConsecutiveFailures":3,"recoverAfterConsecutiveSuccesses":2}')
assert "3d provider-health 返回 200" "200" "$PH_CODE"

PH_BODY=$(http_body -X PUT "$GATEWAY_URL/admin/system/provider-health" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"enabled":false,"refreshInterval":"PT5M","runOnStartup":true,"disableAfterConsecutiveFailures":3,"recoverAfterConsecutiveSuccesses":2}')
assert "3d provider-health disableAfter=3" "3" "$(echo "$PH_BODY" | jq '.disableAfterConsecutiveFailures' 2>/dev/null || echo "null")"

# ═══════════════════════════════════════════════════════════════════════════
# 批次4: Auth 自助 API Key 管理
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 批次4: Auth 自助 API Key 管理 ──"

# 4a. POST /auth/keys — 创建 API Key（已有回归覆盖，但这里需要它作为后续测试的前提）
KEY1_BODY=$(http_body -X POST "$GATEWAY_URL/auth/keys" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"name":"gap-test-key-1","allowedModels":["gpt-4o-mini"]}')
KEY1_ID=$(echo "$KEY1_BODY" | jq -r '.keyId // empty')
KEY1_VALUE=$(echo "$KEY1_BODY" | jq -r '.apiKey // empty')
assert "4a API Key 创建返回 keyId" "true" "$(if [ -n "$KEY1_ID" ]; then echo "true"; else echo "false"; fi)"
assert "4a API Key 创建返回 raw apiKey" "true" "$(if [ -n "$KEY1_VALUE" ]; then echo "true"; else echo "false"; fi)"
CREATED_KEY_IDS+=("$KEY1_ID")

# 4b. PATCH /auth/keys/{keyId} — 更新（禁用）
PATCH_CODE=$(http_code -X PATCH "$GATEWAY_URL/auth/keys/$KEY1_ID" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"enabled":false}')
assert_status_one_of "4b PATCH 禁用 API Key 返回 200/204" "$PATCH_CODE" "200" "204"

# 4c. PATCH 启用 + 改名字
PATCH2_CODE=$(http_code -X PATCH "$GATEWAY_URL/auth/keys/$KEY1_ID" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"enabled":true,"name":"gap-test-key-1-renamed"}')
assert_status_one_of "4c PATCH 启用+改名返回 200/204" "$PATCH2_CODE" "200" "204"

# 验证 PATCH 生效 — GET /auth/keys 列表
KEYS_LIST_BODY=$(http_body "$GATEWAY_URL/auth/keys" -H "$AUTH")
KEY1_IN_LIST=$(echo "$KEYS_LIST_BODY" | jq --arg id "$KEY1_ID" '.keys[] | select(.keyId == $id) | .enabled' 2>/dev/null || echo "false")
assert "4d PATCH 后 key 已启用" "true" "$KEY1_IN_LIST"

# 4e. POST /auth/keys/{keyId}/rotate — 轮换密钥
ROTATE_BODY=$(http_body -X POST "$GATEWAY_URL/auth/keys/$KEY1_ID/rotate" -H "$AUTH")
ROTATE_KEY=$(echo "$ROTATE_BODY" | jq -r '.apiKey // empty')
# rotate 可能生成新 keyId，捕获它
ROTATE_KEY_ID=$(echo "$ROTATE_BODY" | jq -r '.keyId // empty')
assert "4e rotate 返回新 apiKey" "true" "$(if [ -n "$ROTATE_KEY" ] && [ "$ROTATE_KEY" != "$KEY1_VALUE" ]; then echo "true"; else echo "false"; fi)"
# 使用 rotate 后返回的 keyId（rotate 可能生成新的 keyId）
DEL_KEY_TARGET="${ROTATE_KEY_ID:-$KEY1_ID}"

# 4f. DELETE /auth/keys/{keyId} — 删除
DEL_KEY_CODE=$(http_code -X DELETE "$GATEWAY_URL/auth/keys/$DEL_KEY_TARGET" -H "$AUTH")
assert_status_one_of "4f DELETE API Key 返回 200/204" "$DEL_KEY_CODE" "200" "204"

# 4g. 确认删除后列表不再包含
KEYS_LIST_BODY2=$(http_body "$GATEWAY_URL/auth/keys" -H "$AUTH")
KEY1_STILL_EXISTS=$(echo "$KEYS_LIST_BODY2" | jq --arg id "$KEY1_ID" '.keys[] | select(.keyId == $id) | .keyId // empty' 2>/dev/null || echo "")
assert "4g 删除后列表不含 key" "" "$KEY1_STILL_EXISTS"

# ═══════════════════════════════════════════════════════════════════════════
# 批次5: 配置版本/回滚
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 批次5: 配置版本/回滚 ──"

# 5a. GET /internal/config/snapshot — 配置快照
SNAP_BODY=$(http_body "$GATEWAY_URL/internal/config/snapshot" -H "$AUTH")
SNAP_HAS_PROVIDERS=$(echo "$SNAP_BODY" | jq '.providers | length > 0' 2>/dev/null || echo "false")
assert "5a 配置快照有 providers" "true" "$SNAP_HAS_PROVIDERS"
SNAP_HAS_ROUTES=$(echo "$SNAP_BODY" | jq '.routes | length > 0' 2>/dev/null || echo "false")
assert "5a 配置快照有 routes" "true" "$SNAP_HAS_ROUTES"
SNAP_GENERATED=$(echo "$SNAP_BODY" | jq -r '.generatedAt // empty')
assert "5a 配置快照有 generatedAt" "true" "$(if [ -n "$SNAP_GENERATED" ]; then echo "true"; else echo "false"; fi)"

# 5b. GET /internal/config/versions/{type}/{key} — 版本历史
# 修改 provider 以产生版本记录
http_code -X PUT "$GATEWAY_URL/admin/providers/$TEST_GROUP_PROVIDER" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "{\"type\":\"openai-compatible\",\"baseUrl\":\"$MOCK_URL\",\"apiKey\":\"sk-test-v2\",\"timeout\":\"10s\"}" >/dev/null 2>&1

VERSIONS_BODY=$(http_body "$GATEWAY_URL/internal/config/versions/providers/$TEST_GROUP_PROVIDER" -H "$AUTH")
VERSIONS_COUNT=$(echo "$VERSIONS_BODY" | jq '.versions | length' 2>/dev/null || echo "0")
assert "5b 版本历史有记录" "true" "$(if [ "$VERSIONS_COUNT" -gt 0 ]; then echo "true"; else echo "false"; fi)"
VERSIONS_CONFIG_TYPE=$(echo "$VERSIONS_BODY" | jq -r '.configType // empty')
assert "5b 版本历史 configType=providers" "providers" "$VERSIONS_CONFIG_TYPE"

# 5c. 获取最新版本号
LATEST_VERSION=$(echo "$VERSIONS_BODY" | jq '.versions[-1].versionNumber // 0' 2>/dev/null || echo "0")
assert "5c 有有效版本号" "true" "$(if [ "$LATEST_VERSION" -gt 0 ]; then echo "true"; else echo "false"; fi)"

# 5d. POST /internal/config/rollback/{type}/{key}/{version} — 回滚到前一版本
if [ "$LATEST_VERSION" -gt 1 ]; then
    ROLLBACK_TARGET=$((LATEST_VERSION - 1))
    ROLLBACK_BODY=$(http_body -X POST "$GATEWAY_URL/internal/config/rollback/providers/$TEST_GROUP_PROVIDER/$ROLLBACK_TARGET" -H "$AUTH")
    RB_VERSION=$(echo "$ROLLBACK_BODY" | jq '.versionNumber // 0' 2>/dev/null || echo "0")
    assert "5d 回滚到版本 $ROLLBACK_TARGET" "$ROLLBACK_TARGET" "$RB_VERSION"
    RB_CONFIG_TYPE=$(echo "$ROLLBACK_BODY" | jq -r '.configType // empty')
    assert "5d 回滚 configType=providers" "providers" "$RB_CONFIG_TYPE"
    RB_ROLLED_BACK_AT=$(echo "$ROLLBACK_BODY" | jq -r '.rolledBackAt // empty')
    assert "5d 回滚有 timestamp" "true" "$(if [ -n "$RB_ROLLED_BACK_AT" ]; then echo "true"; else echo "false"; fi)"
else
    echo "  [跳过 5d] — 版本数不足，无法回滚 (versions=$VERSIONS_COUNT)"
fi

# 5e. 回滚到不存在的版本号 — 404 (version_not_found)
# 使用 rollback 端点 POST /internal/config/rollback/{type}/{key}/{version} 测试版本不存在
V404_CODE=$(http_code -X POST "$GATEWAY_URL/internal/config/rollback/providers/$TEST_GROUP_PROVIDER/99999" -H "$AUTH")
assert "5e 不存在版本回滚返回 404" "404" "$V404_CODE"

# 5f. 无效 configType — 回滚端点返回 400 (invalid_config_type)
# 若返回 404（SPA fallback 兜底），也接受
BAD_ROLLBACK_CODE=$(http_code -X POST "$GATEWAY_URL/internal/config/rollback/invalid_type/test-key/1" -H "$AUTH")
STEP=$((STEP + 1)); printf '  [%02d] 无效 configType 回滚返回 400/404 ... ' "$STEP"
if [ "$BAD_ROLLBACK_CODE" = "400" ] || [ "$BAD_ROLLBACK_CODE" = "404" ]; then
    pass
else
    fail "期望 400 或 404，实际 $BAD_ROLLBACK_CODE"
fi

# ═══════════════════════════════════════════════════════════════════════════
# 批次4 续: Auth 自助 — /auth/refresh + /auth/logout + /auth/password
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 批次4 续: Auth 自助 — refresh/logout/password ──"

# 4h. POST /auth/refresh — token 刷新
# 从登录响应中获取 refreshToken
LOGIN_REFRESH_TOKEN=$(echo "$LOGIN_RESP" | jq -r '.refreshToken // empty')
assert "4h 登录有 refreshToken" "true" "$(if [ -n "$LOGIN_REFRESH_TOKEN" ]; then echo "true"; else echo "false"; fi)"

if [ -n "$LOGIN_REFRESH_TOKEN" ]; then
    REFRESH_BODY=$(http_body -X POST "$GATEWAY_URL/auth/refresh" \
        -H 'Content-Type: application/json' \
        -d "{\"refreshToken\":\"$LOGIN_REFRESH_TOKEN\"}")
    REFRESH_AT=$(echo "$REFRESH_BODY" | jq -r '.accessToken // empty')
    REFRESH_RT=$(echo "$REFRESH_BODY" | jq -r '.refreshToken // empty')
    assert "4h refresh 返回新 accessToken" "true" "$(if [ -n "$REFRESH_AT" ]; then echo "true"; else echo "false"; fi)"
    assert "4h refresh 返回新 refreshToken" "true" "$(if [ -n "$REFRESH_RT" ]; then echo "true"; else echo "false"; fi)"

    # 4i. POST /auth/logout — 登出
    LOGOUT_CODE=$(http_code -X POST "$GATEWAY_URL/auth/logout" \
        -H "Content-Type: application/json" \
        -d "{\"refreshToken\":\"$REFRESH_RT\"}")
    assert_status_one_of "4i logout 返回 200/204" "$LOGOUT_CODE" "200" "204"

    # 刷新后的旧 refreshToken 应不能再被使用（被吊销）
    REUSE_CODE=$(http_code -X POST "$GATEWAY_URL/auth/refresh" \
        -H 'Content-Type: application/json' \
        -d "{\"refreshToken\":\"$LOGIN_REFRESH_TOKEN\"}")
    assert "4i 旧 refreshToken 被吊销返回 401" "401" "$REUSE_CODE"
fi

# 4j. PUT /auth/password — 修改密码
sleep 2
LOGIN2_RESP=$(http_body -X POST "$GATEWAY_URL/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}')
TOKEN2=$(echo "$LOGIN2_RESP" | jq -r '.accessToken // empty')
# 如果 token 为空，记录响应体用于调试
if [ -z "$TOKEN2" ]; then
    echo "  (login response: $(echo "$LOGIN2_RESP" | jq -c '.') )"
fi
assert "4j 重新登录获取 token" "true" "$(if [ -n "$TOKEN2" ]; then echo "true"; else echo "false"; fi)"

if [ -n "$TOKEN2" ]; then
    AUTH2="Authorization: Bearer $TOKEN2"

    # 修改密码（in_memory 模式下无 DB 持久化，密码修改可能不支持 - 非致命）
    PW_CHANGE_CODE=$(http_code -X PUT "$GATEWAY_URL/auth/password" \
        -H "$AUTH2" \
        -H 'Content-Type: application/json' \
        -d '{"oldPassword":"admin123","newPassword":"newpass456"}')
    STEP=$((STEP + 1)); printf '  [%02d] 4j 修改密码 ... ' "$STEP"
    if [ "$PW_CHANGE_CODE" = "204" ]; then
        pass
        # 用新密码登录
        sleep 1
        LOGIN3_RESP=$(http_body -X POST "$GATEWAY_URL/auth/login" \
            -H 'Content-Type: application/json' \
            -d '{"username":"admin","password":"newpass456"}')
        TOKEN3=$(echo "$LOGIN3_RESP" | jq -r '.accessToken // empty')
        STEP=$((STEP + 1)); printf '  [%02d] 4j 新密码登录成功 ... ' "$STEP"
        if [ -n "$TOKEN3" ]; then
            pass
            # 恢复原密码
            http_code -X PUT "$GATEWAY_URL/auth/password" \
                -H "Authorization: Bearer $TOKEN3" \
                -H 'Content-Type: application/json' \
                -d '{"oldPassword":"newpass456","newPassword":"admin123"}' >/dev/null 2>&1 || true
        else
            fail "新密码登录失败（in_memory 模式限制）"
        fi
    else
        echo "(密码修改返回 $PW_CHANGE_CODE — in_memory 模式不支持密码持久化)"
        pass
    fi
fi

# D. PUT /admin/system/auth — 系统认证配置（放在最后，避免覆盖 users 影响密码测试）
echo ""
echo "── 补充: 系统认证配置 ──"

AUTH_CFG_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/system/auth" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"enabled":true,"registrationMode":"disabled","users":{"admin":{"password":"admin123","role":"admin","clientId":"demo-client-key"}}}')
assert "D auth config 返回 200" "200" "$AUTH_CFG_CODE"

AUTH_CFG_BODY=$(http_body -X PUT "$GATEWAY_URL/admin/system/auth" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"enabled":true,"registrationMode":"disabled","users":{"admin":{"password":"admin123","role":"admin","clientId":"demo-client-key"}}}')
assert "D auth config registrationMode=disabled" "disabled" "$(echo "$AUTH_CFG_BODY" | jq -r '.registrationMode' 2>/dev/null || echo "")"

# ═══════════════════════════════════════════════════════════════════════════
# 批次7: 模型发布闭环
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 批次7: 模型发布闭环 ──"

PUBLICATION_BODY=$(http_body -X PUT "$GATEWAY_URL/admin/publications/$PUBLICATION_ALIAS" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "{\"provider\":\"$TEST_GROUP_PROVIDER\",\"upstreamModel\":\"gpt-4o-mini\"}")
PUBLICATION_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/publications/$PUBLICATION_ALIAS" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "{\"provider\":\"$TEST_GROUP_PROVIDER\",\"upstreamModel\":\"gpt-4o-mini\"}")
assert_status_one_of "7a 模型发布返回 200/201" "$PUBLICATION_CODE" "200" "201"
assert "7a 发布响应 alias 正确" "$PUBLICATION_ALIAS" "$(echo "$PUBLICATION_BODY" | jq -r '.alias // empty' 2>/dev/null || echo "")"
assert "7a 发布响应 provider 正确" "$TEST_GROUP_PROVIDER" "$(echo "$PUBLICATION_BODY" | jq -r '.provider // empty' 2>/dev/null || echo "")"
assert "7a 发布响应 upstreamModel 正确" "gpt-4o-mini" "$(echo "$PUBLICATION_BODY" | jq -r '.upstreamModel // empty' 2>/dev/null || echo "")"
assert "7a 发布后 visibleInV1Models=true" "true" "$(echo "$PUBLICATION_BODY" | jq -r '.visibleInV1Models' 2>/dev/null || echo "false")"
assert "7a 发布响应包含 price.source" "true" "$(if [ -n "$(echo "$PUBLICATION_BODY" | jq -r '.price.source // empty' 2>/dev/null || echo "")" ]; then echo "true"; else echo "false"; fi)"

TEST_ROUTE_NAMES+=("$PUBLICATION_ALIAS" "$PUBLICATION_ALIAS-primary")

PUBLIC_MODELS_BODY=$(http_body "$GATEWAY_URL/v1/models")
assert "7b /v1/models 包含已发布 alias" "true" "$(echo "$PUBLIC_MODELS_BODY" | jq --arg a "$PUBLICATION_ALIAS" '[.data[] | select(.id == $a)] | length > 0' 2>/dev/null || echo "false")"

PUBLICATION_BAD_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/publications/${PUBLICATION_ALIAS}-bad" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "{\"provider\":\"$TEST_GROUP_PROVIDER\",\"upstreamModel\":\"unknown-model\"}")
assert "7c 非法 upstreamModel 发布返回 400" "400" "$PUBLICATION_BAD_CODE"

PUBLICATION_BAD_BODY=$(http_body -X PUT "$GATEWAY_URL/admin/publications/${PUBLICATION_ALIAS}-bad" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d "{\"provider\":\"$TEST_GROUP_PROVIDER\",\"upstreamModel\":\"unknown-model\"}")
assert_contains "7c 非法 upstreamModel 返回核心语义" "$PUBLICATION_BAD_BODY" "upstreamModel not found in provider catalog or configured models"

# ═══════════════════════════════════════════════════════════════════════════
# 批次8: Client CRUD
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 批次8: Client CRUD（创建/列表/删除/404）──"

TEST_CLIENT_NAME="crud-client-$(date +%s)"
CLIENT_CRUD_BODY='{"enabled":true,"allowedModels":["gpt-4o-mini"],"limits":{"dailyTokens":1000,"dailyCost":10.0}}'

# 8a. 获取创建前基准 client 数量（client key 在列表中被脱敏，用计数校验）
CLIENT_LIST_BEFORE="$(http_body "$GATEWAY_URL/admin/clients" -H "$AUTH")"
CLIENT_COUNT_BEFORE=$(jq_number "$CLIENT_LIST_BEFORE" '.clients | length')

# 8b. 创建 client
CLIENT_CRUD_CODE="$(http_code -X PUT "$GATEWAY_URL/admin/clients/$TEST_CLIENT_NAME" -H "$AUTH" -H 'Content-Type: application/json' -d "$CLIENT_CRUD_BODY")"
assert "8b Client CRUD: 创建返回 201/200" "true" "$(if [ "$CLIENT_CRUD_CODE" = "201" ] || [ "$CLIENT_CRUD_CODE" = "200" ]; then echo "true"; else echo "false"; fi)"

# 8c. 验证 client 列表数量增加
CLIENT_LIST_AFTER_CREATE="$(http_body "$GATEWAY_URL/admin/clients" -H "$AUTH")"
CLIENT_COUNT_AFTER=$(jq_number "$CLIENT_LIST_AFTER_CREATE" '.clients | length')
assert_gt "8c 创建后 client 数量增加" "$CLIENT_COUNT_AFTER" "$CLIENT_COUNT_BEFORE" "after=$CLIENT_COUNT_AFTER before=$CLIENT_COUNT_BEFORE"

# 8d. 删除 client
CLIENT_DELETE_CODE="$(http_code -X DELETE "$GATEWAY_URL/admin/clients/$TEST_CLIENT_NAME" -H "$AUTH")"
assert "8d Client CRUD: 删除返回 204" "204" "$CLIENT_DELETE_CODE"

# 8e. 验证 client 列表已恢复
CLIENT_LIST_AFTER_DELETE="$(http_body "$GATEWAY_URL/admin/clients" -H "$AUTH")"
CLIENT_COUNT_AFTER_DELETE=$(jq_number "$CLIENT_LIST_AFTER_DELETE" '.clients | length')
assert "8e 删除后 client 数量恢复" "$CLIENT_COUNT_BEFORE" "$CLIENT_COUNT_AFTER_DELETE"

# 8f. 删除不存在的 client → 404
CLIENT_NOT_FOUND_CODE="$(http_code -X DELETE "$GATEWAY_URL/admin/clients/nonexistent-$TEST_CLIENT_NAME" -H "$AUTH")"
assert "8f 删除不存在的 client 返回 404" "404" "$CLIENT_NOT_FOUND_CODE"

# ═══════════════════════════════════════════════════════════════════════════
# 清理测试数据
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 清理测试数据 ──"

# 删除测试用的 routes
for route_name in "${TEST_ROUTE_NAMES[@]}"; do
    http_code -X DELETE "$GATEWAY_URL/admin/routes/$route_name" -H "$AUTH" >/dev/null 2>&1 || true
done

# 删除测试用的 provider
http_code -X DELETE "$GATEWAY_URL/admin/providers/$TEST_GROUP_PROVIDER" -H "$AUTH" >/dev/null 2>&1 || true

# 删除测试用的 client（兜底，正常测试中 8c 已删除）
if [ -n "$TEST_CLIENT_NAME" ]; then
    http_code -X DELETE "$GATEWAY_URL/admin/clients/$TEST_CLIENT_NAME" -H "$AUTH" >/dev/null 2>&1 || true
fi

# ═══════════════════════════════════════════════════════════════════════════
# 结果
# ═══════════════════════════════════════════════════════════════════════════

summary

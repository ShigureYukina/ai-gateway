#!/usr/bin/env bash
# ============================================================================
# AI Gateway — 最小主链路冒烟黑盒脚本
#
# 覆盖:
#   - health / admin 登录 / 注册登录
#   - /v1/models / /v1/chat/completions
#   - 基础额度与白名单前置
#   适用于日常本地验证和 CI 默认门禁。
#
# 用法:
#   ./scripts/verify.sh                    # 完整验证
#   ./scripts/verify.sh --skip-setup       # 跳过启动，用已有实例
#   ./scripts/verify.sh --skip-teardown    # 验证结束后不停止进程
#   ./scripts/verify.sh --help
#
# 环境变量:
#   GATEWAY_URL   网关地址 (默认 http://localhost:8081)
#   MOCK_URL      Mock upstream 地址 (默认 http://localhost:18080)
#
# 高级用法（需要数据持久化）:
#   --spring.profiles.active=local-file  改用 H2 文件模式，重启不丢失数据
#   详见 bootstrap/src/main/resources/application-local-file.yml
#
# 依赖: curl, jq, java(21+), node
# 不依赖: Docker / Testcontainers
# ============================================================================

set -euo pipefail

# ── 配置 ──
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8081}"
MOCK_URL="${MOCK_URL:-http://localhost:18080}"
STARTUP_TIMEOUT_SECONDS="${STARTUP_TIMEOUT_SECONDS:-60}"
SKIP_SETUP=false
SKIP_TEARDOWN=false
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source "$PROJECT_DIR/scripts/lib.sh"
MOCK_SCRIPT="$PROJECT_DIR/jmeter/mock_openai_server_node.mjs"
JAR_PATH="$PROJECT_DIR/bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar"

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

# ── 解析参数 ──

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-setup)    SKIP_SETUP=true ;;
        --skip-teardown) SKIP_TEARDOWN=true ;;
        --help)
            sed -n '2,12p' "$0"
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
echo "║  Simple AI Gateway  黑盒验证脚本         ║"
echo "╚══════════════════════════════════════════╝"
echo ""

# 检查依赖
for cmd in curl jq; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "错误: 未找到 $cmd，请先安装" >&2
        exit 1
    fi
done

if [ "$SKIP_SETUP" = false ]; then
    echo "── 阶段：启动服务 ──"

    # 检查端口
    for port in 8081 18080; do
        if lsof -i ":$port" &>/dev/null 2>&1; then
            echo "错误: 端口 $port 已被占用" >&2
            exit 1
        fi
    done

    if [ ! -f "$MOCK_SCRIPT" ]; then
        echo "错误: 未找到 mock 脚本: $MOCK_SCRIPT" >&2
        echo "请先提供 OpenAI 兼容 mock 服务，或使用 --skip-setup 连接已启动环境。" >&2
        exit 1
    fi

    if [ ! -f "$JAR_PATH" ]; then
        echo "错误: 未找到 bootstrap JAR: $JAR_PATH" >&2
        echo "请先执行 ./mvnw -pl bootstrap -am package -DskipTests 构建。" >&2
        exit 1
    fi

    # 启动 mock upstream
    echo "  启动 mock upstream (端口 18080)..."
    node "$MOCK_SCRIPT" &
    MOCK_PID=$!
    wait_for_url "http://localhost:18080/v1/models" 10 "mock upstream" || {
        echo "错误: mock upstream 启动失败" >&2
        exit 1
    }
    echo "  mock upstream PID: $MOCK_PID"

    # 启动 gateway（本地开发配置通过 local profile 注入）
    # 如需数据持久化，改 --spring.profiles.active=local-file
    echo "  启动 gateway (端口 8081)..."
    java -jar "$JAR_PATH" \
        --server.port=8081 \
        --spring.profiles.active=local \
        --gateway.shared-state.backend=in_memory \
        --spring.flyway.enabled=false &
    GATEWAY_PID=$!

    # 等待 gateway 就绪
    echo -n "  等待 gateway 就绪 (最长 ${STARTUP_TIMEOUT_SECONDS}s)..."
    for i in $(seq 1 "$STARTUP_TIMEOUT_SECONDS"); do
        if curl -sf "$GATEWAY_URL/healthz/live" &>/dev/null; then
            echo " 就绪 (${i}s)"
            break
        fi
        if [ "$i" -eq "$STARTUP_TIMEOUT_SECONDS" ]; then
            echo " 超时"
            echo "错误: gateway 启动超时 (${STARTUP_TIMEOUT_SECONDS}s)" >&2
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

# ═══════════════════════════════════════════════════════════════════════════
# 第二阶段：健康检查
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 阶段：健康检查 ──"

HEALTH=$(http_body "$GATEWAY_URL/healthz")
HEALTH_STATUS=$(echo "$HEALTH" | jq -r '.status' 2>/dev/null || echo "")
assert "健康检查返回 UP" "UP" "$HEALTH_STATUS"

LIVE_CODE=$(http_code "$GATEWAY_URL/healthz/live")
assert "存活探针返回 200" "200" "$LIVE_CODE"

READY_CODE=$(http_code "$GATEWAY_URL/healthz/ready")
assert "就绪探针返回 200" "200" "$READY_CODE"

MODELS=$(http_body "$GATEWAY_URL/v1/models")
MODELS_OBJECT=$(echo "$MODELS" | jq -r '.object' 2>/dev/null || echo "")
assert "模型列表格式正确" "list" "$MODELS_OBJECT"

# ═══════════════════════════════════════════════════════════════════════════
# 第三阶段：管理员操作
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 阶段：管理员操作 ──"

# 3.1 管理员登录
LOGIN_RESP=$(http_body -X POST "$GATEWAY_URL/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}')

ADMIN_TOKEN=$(echo "$LOGIN_RESP" | jq -r '.accessToken // empty')
check "管理员登录成功" test -n "$ADMIN_TOKEN"
if [ -z "$ADMIN_TOKEN" ]; then exit 1; fi
AUTH="Authorization: Bearer $ADMIN_TOKEN"

# 3.2 添加 Provider
ADD_PROV_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/providers/mock" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{
        "type": "openai-compatible",
        "baseUrl": "http://localhost:18080",
        "apiKey": "sk-mock",
        "timeout": "10s"
    }')
assert_2xx "添加 Provider 成功" "$ADD_PROV_CODE"

# 3.3 查看 Provider 列表（返回格式为 {"providers": {"name": {...}}}）
PROV_LIST=$(http_body "$GATEWAY_URL/admin/providers" -H "$AUTH")
PROV_MOCK_EXISTS=$(echo "$PROV_LIST" | jq '.providers | has("mock")' 2>/dev/null || echo "false")
assert "Provider 列表包含 mock" "true" "$PROV_MOCK_EXISTS"

# 3.4 添加 Route
ADD_ROUTE_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/routes/gpt-4o-mini" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{
        "scene": "default-chat",
        "providers": ["mock"]
    }')
assert_2xx "添加 Route 成功" "$ADD_ROUTE_CODE"

# 3.5 查看 Route 列表
ROUTE_LIST=$(http_body "$GATEWAY_URL/admin/routes" -H "$AUTH")
ROUTE_EXISTS=$(echo "$ROUTE_LIST" | jq '.routes | has("gpt-4o-mini")' 2>/dev/null || echo "false")
assert "Route 列表包含 gpt-4o-mini" "true" "$ROUTE_EXISTS"

# ═══════════════════════════════════════════════════════════════════════════
# 第四阶段：API 调用测试
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 阶段：API 调用 ──"

# 4.1 用静态 API Key 调用（非流式）
CHAT_RESP=$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" \
    -H 'Authorization: Bearer demo-client-key' \
    -H 'Content-Type: application/json' \
    -d '{
        "model": "gpt-4o-mini",
        "messages": [{"role": "user", "content": "Reply with exactly: hello"}],
        "stream": false
    }')

CHAT_CHOICES=$(echo "$CHAT_RESP" | jq '.choices | length' 2>/dev/null || echo "0")
assert_gt "静态 API Key 调用返回 choices" "$CHAT_CHOICES" 0

HAS_USAGE=$(echo "$CHAT_RESP" | jq '.usage | has("total_tokens")' 2>/dev/null || echo "false")
assert "响应包含 usage 信息" "true" "$HAS_USAGE"

MODEL_NAME=$(echo "$CHAT_RESP" | jq -r '.model' 2>/dev/null || echo "")
assert "响应模型名为 gpt-4o-mini" "gpt-4o-mini" "$MODEL_NAME"

# 4.2 用 JWT 调用
JWT_CHAT=$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H 'Content-Type: application/json' \
    -d '{
        "model": "gpt-4o-mini",
        "messages": [{"role": "user", "content": "Reply with exactly: world"}],
        "stream": false
    }')

JWT_CHOICES=$(echo "$JWT_CHAT" | jq '.choices | length' 2>/dev/null || echo "0")
assert_gt "JWT 调用返回 choices" "$JWT_CHOICES" 0

# 4.3 请求日志
REQ_LOG=$(http_body "$GATEWAY_URL/admin/requests/recent" -H "$AUTH")
REQ_COUNT=$(echo "$REQ_LOG" | jq 'length' 2>/dev/null || echo "0")
assert_gt "请求日志有记录" "$REQ_COUNT" 1 "预期至少 2 条（静态 key + JWT 各 1 条）"

# ═══════════════════════════════════════════════════════════════════════════
# 第五阶段：用户流程
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 阶段：用户流程 ──"

# 5.1 注册新用户
REG_RESP=$(http_body -X POST "$GATEWAY_URL/auth/register" \
    -H 'Content-Type: application/json' \
    -d '{"username":"testuser","password":"testpass123"}')

USER_API_KEY=$(echo "$REG_RESP" | jq -r '.apiKey // empty')
USER_TOKEN=$(echo "$REG_RESP" | jq -r '.accessToken // empty')
check "注册成功返回 apiKey" test -n "$USER_API_KEY"
check "注册成功返回 accessToken" test -n "$USER_TOKEN"

if [ -z "$USER_API_KEY" ] || [ -z "$USER_TOKEN" ]; then
    echo "  警告: 注册失败，跳过用户测试"
else
    # 5.2 检查 API Key 格式
    GW_PREFIX=$(echo "$USER_API_KEY" | cut -c1-3)
    assert "个人 API Key 以 gw- 开头" "gw-" "$GW_PREFIX"

    # 5.3 设置用户限额
    LIMITS_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/users/testuser/limits" \
        -H "$AUTH" \
        -H 'Content-Type: application/json' \
        -d '{"dailyTokens": 100000, "dailyCost": 5.0}')
    assert_2xx "设置用户限额返回 2xx" "$LIMITS_CODE"

    # 5.4 设置模型白名单
    MODELS_CODE=$(http_code -X PUT "$GATEWAY_URL/admin/users/testuser/allowed-models" \
        -H "$AUTH" \
        -H 'Content-Type: application/json' \
        -d '{"allowedModels": ["gpt-4o-mini"]}')
    assert_2xx "设置模型白名单返回 2xx" "$MODELS_CODE"

    # 5.5 用个人 API Key 调用
    USER_CHAT=$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" \
        -H "Authorization: Bearer $USER_API_KEY" \
        -H 'Content-Type: application/json' \
        -d '{
            "model": "gpt-4o-mini",
            "messages": [{"role": "user", "content": "Reply with exactly: userkey"}],
            "stream": false
        }')
    USER_CHOICES=$(echo "$USER_CHAT" | jq '.choices | length' 2>/dev/null || echo "0")
    assert_gt "个人 API Key 调用返回 choices" "$USER_CHOICES" 0

    # 5.6 查看自身信息
    ME_USERNAME=$(http_body "$GATEWAY_URL/auth/me" -H "Authorization: Bearer $USER_TOKEN" | jq -r '.username' 2>/dev/null || echo "")
    assert "/auth/me 返回用户名" "testuser" "$ME_USERNAME"

    # 5.7 创建个人 API Key
    NEW_KEY_RESP=$(http_body -X POST "$GATEWAY_URL/auth/keys" \
        -H "Authorization: Bearer $USER_TOKEN" \
        -H 'Content-Type: application/json' \
        -d '{"name":"my-dev-key"}')
    NEW_API_KEY=$(echo "$NEW_KEY_RESP" | jq -r '.apiKey // empty')
    check "创建个人 Key 成功" test -n "$NEW_API_KEY"

    KEY_PREFIX=$(echo "$NEW_API_KEY" | cut -c1-3)
    assert "新 Key 以 gw- 开头" "gw-" "$KEY_PREFIX"

    # 5.8 用新 Key 调用
    NEW_KEY_CHAT=$(http_body -X POST "$GATEWAY_URL/v1/chat/completions" \
        -H "Authorization: Bearer $NEW_API_KEY" \
        -H 'Content-Type: application/json' \
        -d '{
            "model": "gpt-4o-mini",
            "messages": [{"role": "user", "content": "Reply with exactly: newkey"}],
            "stream": false
        }')
    NEW_KEY_CHOICES=$(echo "$NEW_KEY_CHAT" | jq '.choices | length' 2>/dev/null || echo "0")
    assert_gt "新建 API Key 调用返回 choices" "$NEW_KEY_CHOICES" 0
fi

# ═══════════════════════════════════════════════════════════════════════════
# 第六阶段：管理功能
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 阶段：管理功能 ──"

USERS_HAS_TEST=$(http_body "$GATEWAY_URL/admin/users" -H "$AUTH" | jq '[.users[] | select(.username == "testuser")] | length' 2>/dev/null || echo "0")
assert_gt "用户列表包含 testuser" "$USERS_HAS_TEST" 0

CLIENTS_COUNT=$(http_body "$GATEWAY_URL/admin/clients" -H "$AUTH" | jq '.clients | length' 2>/dev/null || echo "0")
assert_gt "客户端列表非空" "$CLIENTS_COUNT" 0

EXPORT_RESP=$(http_body "$GATEWAY_URL/admin/config/export" -H "$AUTH")
EXPORT_HAS_PROV=$(echo "$EXPORT_RESP" | jq '.providers | has("mock")' 2>/dev/null || echo "false")
EXPORT_HAS_ROUTE=$(echo "$EXPORT_RESP" | jq '.routes | has("gpt-4o-mini")' 2>/dev/null || echo "false")
assert "导出配置包含 mock provider" "true" "$EXPORT_HAS_PROV"
assert "导出配置包含 gpt-4o-mini route" "true" "$EXPORT_HAS_ROUTE"

# ═══════════════════════════════════════════════════════════════════════════
# 第七阶段：边界测试
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo "── 阶段：边界测试 ──"

# 7.1 无认证
UNAUTH_CODE=$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
    -H 'Content-Type: application/json' \
    -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"test"}]}')
assert "无认证返回 401" "401" "$UNAUTH_CODE"

# 7.2 无效 token
INVALID_CODE=$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
    -H 'Authorization: Bearer invalid-token-x' \
    -H 'Content-Type: application/json' \
    -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"test"}]}')
assert "无效 token 返回 401" "401" "$INVALID_CODE"

# 7.3 不存在模型
BAD_MODEL_CODE=$(http_code -X POST "$GATEWAY_URL/v1/chat/completions" \
    -H 'Authorization: Bearer demo-client-key' \
    -H 'Content-Type: application/json' \
    -d '{"model":"nonexistent-model","messages":[{"role":"user","content":"test"}]}')
assert "不存在模型返回 403" "403" "$BAD_MODEL_CODE"

# 7.4 公共端点无需认证
check "健康检查无需认证" curl -sf "$GATEWAY_URL/healthz"
check "模型列表无需认证" curl -sf "$GATEWAY_URL/v1/models"

# ═══════════════════════════════════════════════════════════════════════════
# 第八阶段：清理资源
# ═══════════════════════════════════════════════════════════════════════════

echo "── 阶段：清理资源 ──"

DEL_USER_CODE=$(http_code -X DELETE "$GATEWAY_URL/admin/users/testuser" -H "$AUTH")
assert_2xx "删除测试用户成功" "$DEL_USER_CODE"

DEL_ROUTE_CODE=$(http_code -X DELETE "$GATEWAY_URL/admin/routes/gpt-4o-mini" -H "$AUTH")
assert_2xx "删除测试 Route 成功" "$DEL_ROUTE_CODE"

DEL_PROV_CODE=$(http_code -X DELETE "$GATEWAY_URL/admin/providers/mock" -H "$AUTH")
assert_2xx "删除测试 Provider 成功" "$DEL_PROV_CODE"

# ═══════════════════════════════════════════════════════════════════════════
# 结果
# ═══════════════════════════════════════════════════════════════════════════

summary

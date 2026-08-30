#!/usr/bin/env bash
# 全量验证入口：后端三模块全量单测 + 前端 lint/test/build。
# 一次性回答"当前 HEAD 全量绿不绿"，避免只跑聚焦清单导致坏测试潜伏。
# 用法：bash scripts/verify-all.sh
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -z "${JAVA_HOME:-}" ]; then
    echo "[verify-all] JAVA_HOME 未设置；mvnw 需要 JDK 21。示例：export JAVA_HOME=/c/Users/<you>/jdk-21.0.12.1+1"
    exit 1
fi

FAILED=0

echo "=== [1/2] 后端全量单测（gateway-core -> gateway-admin -> bootstrap）==="
./mvnw test || FAILED=1

echo "=== [2/2] 前端（lint + vitest + build）==="
(
    cd frontend || exit 1
    npm run lint || exit 1
    npm run test || exit 1
    npm run build || exit 1
) || FAILED=1

if [ "$FAILED" -ne 0 ]; then
    echo "=== verify-all: FAILED ==="
    exit 1
fi
echo "=== verify-all: ALL GREEN ==="

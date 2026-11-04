#!/usr/bin/env bash
# 浏览器做题入口网关：/entry/<CODE>/ -> 本队容器
# 网关必须双网络：sacc-service-net（调后端 API）+ tracequest-runtime（直连题目容器）
# 前端 nginx 侧需存在：upstream tq_entry_gateway + location ^~ /entry/
set -eu
EVENT_ID=${EVENT_ID:?set EVENT_ID}
PLATFORM_HOST=${PLATFORM_HOST:-PLATFORM_HOST}
GATEWAY_SRC=${GATEWAY_SRC:-/home/sacc/entry}
SERVICE_NET=${SERVICE_NET:-sacc-service-net}
RUNTIME_NET=${RUNTIME_NET:-tracequest-runtime}
TAG=${TAG:-tq-entry-gateway:10}
cd "$GATEWAY_SRC"
docker build -q -t "$TAG" . 2>&1 | tail -2
docker rm -f tq-entry-gateway >/dev/null 2>&1 || true
docker run -d --name tq-entry-gateway --restart unless-stopped \
  --network "$SERVICE_NET" \
  -e TQ_EVENT_ID="$EVENT_ID" \
  -e TQ_API=http://tracequest-backend:8080 \
  "$TAG" >/dev/null
sleep 2
docker network connect "$RUNTIME_NET" tq-entry-gateway 2>/dev/null || true
docker logs --tail 5 tq-entry-gateway
curl -s -o /dev/null -w "gateway_http=%{http_code}\n" "http://$PLATFORM_HOST:8081/entry/HC-01/"

# 回滚：docker rm -f tq-entry-gateway，并把前端 nginx.conf 恢复为备份

#!/usr/bin/env bash
# 平台公网入口修复：补 TQ_PUBLIC_WSS_URL 的端口 + 前端 bundle 允许 http 页面同源 ws://
# 前置：平台对外 8081；镜像 tracequest-web:<tag>-tqws 已构建（见 Dockerfile.tqws）
set -u
PLATFORM_HOST=${PLATFORM_HOST:-PLATFORM_HOST}
WEB_IMAGE=${WEB_IMAGE:-tracequest-web:event-control-20261104-tqws}
APP_DIR=${APP_DIR:-/home/sacc/0xtrace-backend}
FE_COMPOSE=${FE_COMPOSE:?set FE_COMPOSE to the frontend compose yaml path}
TS=$(date +%Y%m%d%H%M%S)
cd "$APP_DIR"
cp -a .env .env.bak-$TS
sed -i "s#^TQ_PUBLIC_WSS_URL=.*#TQ_PUBLIC_WSS_URL=ws://$PLATFORM_HOST:8081/api/v1/challenge-connections#" .env
grep -n TQ_PUBLIC_WSS_URL .env
cp -a "$FE_COMPOSE" "$FE_COMPOSE.bak-$TS"
sed -i "s#^    image: .*tracequest-web.*#    image: $WEB_IMAGE#" "$FE_COMPOSE"
grep -n "image:" "$FE_COMPOSE"
docker compose -f docker-compose.app-only.yml up -d app 2>&1 | tail -4
for i in $(seq 1 40); do
  H=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/api/v1/health || true)
  [ "$H" = "200" ] && { echo "backend health 200 after ${i}s"; break; }
  sleep 1
done
FE_PROJECT=$(docker inspect tracequest-frontend --format "{{index .Config.Labels \"com.docker.compose.project\"}}")
docker compose -p "$FE_PROJECT" -f "$FE_COMPOSE" up -d 2>&1 | tail -4
sleep 4
curl -s -o /dev/null -w "public health=%{http_code}\n" "http://$PLATFORM_HOST:8081/api/v1/health"

# 回滚：cp -a .env.bak-$TS .env && cp -a "$FE_COMPOSE.bak-$TS" "$FE_COMPOSE" && 重新 up -d 两个服务

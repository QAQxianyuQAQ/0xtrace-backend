#!/usr/bin/env bash
set -u
TS=$(date +%Y%m%d%H%M%S)
echo "TS=$TS"
FE=/home/sacc/tracequest-frontend/releases/event-control-20261104/compose.candidate.yaml
echo "FE_PROJECT=$(docker inspect tracequest-frontend --format '{{index .Config.Labels "com.docker.compose.project"}}')"
echo "FE_DIR=$(docker inspect tracequest-frontend --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}')"

cd /home/sacc/0xtrace-backend
cp -a .env .env.bak-$TS
sed -i 's#^TQ_PUBLIC_WSS_URL=.*#TQ_PUBLIC_WSS_URL=ws://PLATFORM_HOST:8081/api/v1/challenge-connections#' .env
echo "--- .env now ---"; grep -n 'TQ_PUBLIC_WSS_URL' .env

cp -a $FE $FE.bak-$TS
sed -i 's#^    image: tracequest-web:event-control-20261104$#    image: tracequest-web:event-control-20261104-tqws#' $FE
echo "--- fe compose now ---"; grep -n 'image:' $FE

echo '=== 1) 重建 backend ==='
docker compose -f docker-compose.app-only.yml up -d app 2>&1 | tail -4
for i in $(seq 1 40); do
  H=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/v1/health || true)
  [ "$H" = "200" ] && { echo "backend health 200 after ${i}s"; break; }
  sleep 1
done
docker inspect tracequest-backend --format '{{.State.Status}} {{index .Config.Image}}'
docker exec tracequest-backend printenv TQ_PUBLIC_WSS_URL

echo '=== 2) 重建 frontend ==='
PW=$(docker inspect tracequest-frontend --format '{{index .Config.Labels "com.docker.compose.project"}}')
docker compose -p "$PW" -f $FE up -d 2>&1 | tail -4
sleep 4
docker inspect tracequest-frontend --format '{{.State.Status}} {{index .Config.Image}}'
curl -s -o /dev/null -w 'fe http=%{http_code}\n' http://127.0.0.1:18080/
curl -s http://127.0.0.1:18080/assets/index-CzK5Oaxk.js | grep -c 'n.protocol==="http:"&&true' || echo 'grep-new=0'
curl -s http://127.0.0.1:18080/assets/index-CzK5Oaxk.js | grep -c 'includes(n.hostname)' || echo 'grep-old=0'

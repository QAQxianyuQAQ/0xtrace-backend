#!/usr/bin/env bash
# 赛事状态快速切换（只改 events 一行，绕过 UI）
# 用法：event_toggle.sh open | close
set -eu
EVENT_ID=${EVENT_ID:?set EVENT_ID}
TQ_DB_PASSWORD=${TQ_DB_PASSWORD:?set TQ_DB_PASSWORD}
ACTION=${1:?open|close}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-mysql8}
DB_USER=${TQ_DB_USERNAME:-tracequest}
DB_NAME=${TQ_DB_NAME:-tracequest}
if [ "$ACTION" = "open" ]; then
  SQL="update events set status='OPEN', starts_at=now()-interval 1 hour, ends_at=now()+interval 6 hour where id='$EVENT_ID'"
else
  SQL="update events set status='SETUP', starts_at=NULL, ends_at=NULL where id='$EVENT_ID'"
fi
docker exec "$MYSQL_CONTAINER" mysql -u"$DB_USER" -p"$TQ_DB_PASSWORD" "$DB_NAME" -e "$SQL"
docker exec "$MYSQL_CONTAINER" mysql -u"$DB_USER" -p"$TQ_DB_PASSWORD" "$DB_NAME" -e "select id,status,starts_at,ends_at from events where id='$EVENT_ID'"

# 注意：容器题在 SETUP 状态下提交会被拒（409 SUBMISSION_CLOSED），端到端测试前需先 open

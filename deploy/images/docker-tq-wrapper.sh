#!/bin/sh
# TQ 离线部署补丁 v2
#  1) 镜像已存在时跳过 docker pull（离线环境必须）
#  2) docker run：只把容器 ID 交给标准输出，过滤 CLI 的 WARNING（内核不支持 swap 记账会往 stderr 打
#     警告，而平台 redirectErrorStream(true) 把 stderr 并进 stdout，导致首行警告被当成 container_id）
REAL=/usr/bin/docker
LOG=/tmp/tq-docker-cli.log
logline() { echo "$(date -Is) $*" >> "$LOG" 2>/dev/null; }

case "$1" in
  pull)
    img=""
    for a in "$@"; do
      case "$a" in
        pull|-*) ;;
        *) img="$a" ;;
      esac
    done
    if [ -n "$img" ] && $REAL image inspect "$img" >/dev/null 2>&1; then
      echo "[tq] image $img present locally, skip pull"
      exit 0
    fi
    exec $REAL "$@"
    ;;
  run)
    out=$(mktemp); err=$(mktemp)
    $REAL "$@" >"$out" 2>"$err"; rc=$?
    if [ $rc -eq 0 ]; then
      grep -E '^[0-9a-f]{64}$' "$out" || sed '/^$/d' "$out"
      grep -v '^WARNING' "$err" >&2 2>/dev/null
      logline "run ok $(tr -d '\n' <"$out")"
    else
      sed '/^$/d' "$out"
      grep -v '^WARNING' "$err" >&2 2>/dev/null
      logline "run fail rc=$rc $(tr '\n' ' ' <"$err")"
    fi
    rm -f "$out" "$err"
    exit $rc
    ;;
  *) exec $REAL "$@" ;;
esac

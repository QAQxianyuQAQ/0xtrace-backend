#!/usr/bin/env bash
# 在平台宿主机执行：用源码本地构建 6 道题的镜像（不需要任何 registry）
# 用法：tar xzf kit.tar.gz && cd kit && bash host_build.sh
set -euo pipefail
cd "$(dirname "$0")"

build() { echo "== 构建 $1"; docker build -q -t "$1" "$2" >/dev/null && echo "   ok  $1"; }

build sacc-humancheck:deploy      humancheck
build sacc/jigsaw-sudoku:1.0      jigsaw
build sacc/snake-ctf:latest       snake
build sacc-missing-square:tq      missing-square
build sacc-morse-md5:1.0.0-tq     morse-md5
build sacc-source-flag:tq         source-flag

echo '== 冒烟：注入测试 flag 起容器并探活'
FLAG='SACC{$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy}'
smoke() { # name image port probe
  docker rm -f "$1" >/dev/null 2>&1 || true
  docker run -d --rm --name "$1" -e FLAG="$FLAG" -p 127.0.0.1:$3:$4 "$2" >/dev/null
  sleep 3
  code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$3$5" || true)
  echo "   $1 -> $code"
  docker rm -f "$1" >/dev/null 2>&1 || true
}
smoke tqhc  sacc-humancheck:deploy     19081 8080 /api/health
smoke tqjg  sacc/jigsaw-sudoku:1.0     19082 8080 /api/health
smoke tqsn  sacc/snake-ctf:latest      19083 3000 /healthz
smoke tqms  sacc-missing-square:tq     19084 5000 /SACC
smoke tqmo  sacc-morse-md5:1.0.0-tq    19085 8080 /
smoke tqsf  sacc-source-flag:tq        19086 5000 /SACC

echo '== 完成。镜像已在本机 docker 中，tag 与平台配置一致。'

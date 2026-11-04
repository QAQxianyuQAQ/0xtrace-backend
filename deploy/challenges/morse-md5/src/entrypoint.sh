#!/bin/sh
# 每次容器启动生成全新的 flag，并写入 PID 1 的环境变量 FLAG。
# 因为是在启动后写入的，docker inspect / docker exec env 都看不到它，
# 需要用 /app/read-flag.sh 读取 /proc/1/environ。
set -eu

if [ "${FLAG:-}" = "" ]; then
    hex="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
    if [ "${#hex}" -ne 32 ]; then
        echo "[entrypoint] 随机数生成失败，退出。" >&2
        exit 1
    fi
    FLAG="SACC{${hex}}"
    echo "[entrypoint] 未收到平台注入的 FLAG，已回落到启动期随机 flag。" >&2
fi
export FLAG

echo "[entrypoint] 已生成新 flag 并写入环境变量 FLAG（读取：sh /app/read-flag.sh）。" >&2

exec node /app/server.js

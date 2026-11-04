#!/bin/sh
# 读取本轮（PID 1 环境里的）FLAG。
set -eu
tr '\000' '\n' < /proc/1/environ | sed -n 's/^FLAG=//p'

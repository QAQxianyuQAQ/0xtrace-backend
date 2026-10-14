#!/usr/bin/env bash
# 轻量 Maven 启动脚本：优先使用本地缓存的 Maven 发行版，其次回落到 PATH 中的 mvn。
# 说明：本仓库未提交 .mvn/wrapper/maven-wrapper.jar，因此这里直接定位已解压的 Maven。
set -euo pipefail

resolve_maven() {
  if command -v mvn >/dev/null 2>&1; then
    command -v mvn
    return 0
  fi
  local candidate
  candidate=$(find "${HOME}/.m2/wrapper/dists" -maxdepth 5 -type f -name mvn 2>/dev/null | sort | tail -n 1)
  if [ -n "${candidate}" ]; then
    echo "${candidate}"
    return 0
  fi
  return 1
}

MAVEN_BIN=$(resolve_maven || true)
if [ -z "${MAVEN_BIN}" ]; then
  echo "未找到 Maven：请安装 Maven 3.9+ 或将其加入 PATH" >&2
  exit 1
fi

exec "${MAVEN_BIN}" "$@"

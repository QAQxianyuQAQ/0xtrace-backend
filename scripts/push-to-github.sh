#!/usr/bin/env bash
# 将本项目推送到 GitHub（需在具备外网访问的机器上执行）。
#
# 用法：
#   export GITHUB_TOKEN=<具备 repo 权限的令牌>   # 不要把令牌写进脚本或提交到仓库
#   bash scripts/push-to-github.sh [owner/repo]  # 用 bash 调用，不依赖可执行位
#
# 说明：
#   - 令牌只通过本次命令的请求头传递，不会写入 .git/config；
#   - 推送前会先执行敏感信息扫描，扫描不通过则中止。
set -euo pipefail

repo_slug="${1:-${TQ_GITHUB_REPO:-QAQxianyuQAQ/0xtrace-backend}}"
: "${GITHUB_TOKEN:?请先导出 GITHUB_TOKEN=<具备 repo 权限的令牌>}"

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "${script_dir}/.."

# 1. 提交前扫描敏感信息
bash scripts/scan-secrets.sh

# 2. 初始化本地仓库并提交
if [ -d .git ] && ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "错误：当前目录存在 .git，但它不是可用的 Git 仓库：$(pwd)/.git" >&2
  echo "常见原因：项目目录中的 .git 是只读占位目录。" >&2
  echo "处理方式（任选其一）：" >&2
  echo "  1) 直接在可写副本上操作：bash scripts/prepare-pushable-copy.sh" >&2
  echo "  2) 把占位目录移走后在原地初始化：mv .git .git.placeholder && git init -b main" >&2
  exit 1
fi
if [ ! -d .git ]; then
  git init -b main
fi
git add -A
if ! git diff --cached --quiet; then
  # 未配置提交身份时给出兜底身份，避免首次提交失败
  if [ -z "$(git config user.email || true)" ]; then
    git config user.name "${TQ_GIT_NAME:-TraceQuest Maintainer}"
    git config user.email "${TQ_GIT_EMAIL:-tracequest-bot@users.noreply.github.com}"
    echo "未检测到 Git 提交身份，已在仓库内设置兜底身份（可用 TQ_GIT_NAME/TQ_GIT_EMAIL 覆盖）"
  fi
  git commit -m "chore: 初始化 TraceQuest 后端（第 8 章 /api/v1 契约实现）"
fi

# 3. 远端仓库不存在时通过 API 创建（已存在返回 422，可忽略；网络失败时继续尝试推送）
http_code=$(curl -L --silent --show-error -o /tmp/gh-create-repo.json -w '%{http_code}' \
  -X POST https://api.github.com/user/repos \
  -H "Authorization: Bearer ${GITHUB_TOKEN}" \
  -H "Accept: application/vnd.github+json" \
  -d "{\"name\":\"$(basename "$repo_slug")\",\"private\":false,\"description\":\"TraceQuest CTF 平台后端（Java 25 + Spring Boot + MyBatis-Plus）\"}" \
  || true)
case "${http_code}" in
  201) echo "已创建远端仓库：${repo_slug}" ;;
  422) echo "远端仓库已存在，直接推送：${repo_slug}" ;;
  401|403) echo "警告：令牌鉴权失败（HTTP ${http_code}），请检查令牌权限与有效期" >&2 ;;
  000|"") echo "警告：无法访问 GitHub API（网络不可达或代理未配置），将直接尝试推送" >&2 ;;
  *) echo "警告：创建远端仓库返回 HTTP ${http_code}，将直接尝试推送" >&2 ;;
esac

# 4. 推送：令牌仅作为一次性请求头，不落盘
git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/${repo_slug}.git"
git -c http.extraHeader="Authorization: Bearer ${GITHUB_TOKEN}" push -u origin main

echo "推送完成：https://github.com/${repo_slug}"

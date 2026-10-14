#!/usr/bin/env bash
# 生成一个“可推送副本”：把当前项目复制到可写目录并初始化 Git 仓库。
#
# 适用场景：项目目录中的 .git 是只读占位目录（无法 git init/commit），
# 或当前目录本身不可写。复制过程不会带上 .git 与 target。
#
# 用法：
#   bash scripts/prepare-pushable-copy.sh [目标目录]
# 默认目标目录：/tmp/0xtrace-push
set -euo pipefail

target="${1:-/tmp/0xtrace-push}"
source_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

echo "源目录：${source_dir}"
echo "目标目录：${target}"

# 1. 复制项目（排除 .git、构建产物与本地缓存）
mkdir -p "$target"
tar -C "$source_dir" \
  --exclude=./.git --exclude=./.git/ \
  --exclude=./target --exclude=./.m2 \
  --exclude=./node_modules \
  -cf - . | tar -C "$target" -xf -

# 2. 确保文件可写，并清理可能被复制过来的 .git 占位目录
chmod -R u+rwX "$target"
if [ -e "${target}/.git" ]; then
  mv "${target}/.git" "${target}/.git.placeholder"
  echo "已把只读占位目录移动为 .git.placeholder（可自行删除）"
fi

# 3. 初始化仓库并提交（提交前会先做敏感信息扫描）
cd "$target"
bash scripts/scan-secrets.sh
git init -b main >/dev/null
bash scripts/setup-git-hooks.sh
git add -A
if git diff --cached --quiet; then
  echo "没有需要提交的变更"
else
  # 未配置提交身份时给出可用的兜底身份，避免首次提交失败
  if [ -z "$(git config user.email || true)" ]; then
    git config user.name "${TQ_GIT_NAME:-TraceQuest Maintainer}"
    git config user.email "${TQ_GIT_EMAIL:-tracequest-bot@users.noreply.github.com}"
    echo "未检测到 Git 提交身份，已在本仓库内设置兜底身份（可用 TQ_GIT_NAME/TQ_GIT_EMAIL 覆盖）"
  fi
  git commit -q -m "chore: 初始化 TraceQuest 后端（第 8 章 /api/v1 契约实现）"
fi

echo ""
echo "副本已就绪：${target}（分支 $(git branch --show-current)，提交 $(git rev-parse --short HEAD)）"
echo "接下来执行："
echo "  cd ${target}"
echo "  export GITHUB_TOKEN=<具备 repo 权限的令牌>"
echo "  bash scripts/push-to-github.sh QAQxianyuQAQ/0xtrace-backend"

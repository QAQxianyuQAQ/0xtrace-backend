#!/usr/bin/env bash
# 启用仓库内自带的 Git 钩子（提交前自动执行敏感信息扫描）。
#
# 直接用 bash 调用即可，不依赖文件的可执行位：
#   bash scripts/setup-git-hooks.sh
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd "${script_dir}/.." && pwd)
cd "$project_dir"

# 1. 校验当前是否处于可用的 Git 仓库
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  if [ -e .git ]; then
    echo "错误：当前目录存在 .git，但它不是可用的 Git 仓库：${project_dir}/.git" >&2
    echo "常见原因：项目目录中的 .git 是只读占位目录。" >&2
    echo "处理方式（任选其一）：" >&2
    echo "  1) 直接在可写副本上操作：bash scripts/prepare-pushable-copy.sh" >&2
    echo "  2) 把占位目录移走后在原地初始化：mv .git .git.placeholder && git init -b main" >&2
    exit 1
  fi
  echo "当前目录不是 Git 仓库，正在初始化：git init -b main"
  git init -b main
fi

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

# 2. 启用仓库内置钩子目录，并尽量补齐可执行位（无写权限时只提示）
git config core.hooksPath .githooks
if ! chmod +x .githooks/* scripts/*.sh 2>/dev/null; then
  echo "提示：无法修改文件权限（可能是只读或 noexec 挂载），钩子仍会通过 bash 调用，功能不受影响。"
fi

echo "已启用 Git 钩子：core.hooksPath=.githooks（仓库根目录：${repo_root}）"
echo "提交前会自动运行 scripts/scan-secrets.sh --staged"

#!/usr/bin/env bash
# 敏感信息扫描：在提交前或 CI 中检查仓库内是否残留凭据。
#
# 用法：
#   scripts/scan-secrets.sh          # 扫描工作区（排除构建产物与 .git）
#   scripts/scan-secrets.sh --staged # 仅扫描已暂存内容（供 pre-commit 调用）
#
# 退出码：0 表示未发现疑似凭据，1 表示命中（CI 会因此失败）。
set -uo pipefail

# 允许出现“非生产占位值”的文件：本地开发配置、示例配置与文档
ALLOWLIST=(
  "src/main/resources/application-dev.yml"
  "src/test/resources/application-test.yml"
  ".env.example"
  ".env.deploy.example"
  "k8s/secret.example.yaml"
  "README.md"
  "DEPLOY.md"
  "scripts/scan-secrets.sh"
  ".githooks/pre-commit"
)

# 疑似凭据模式：GitHub 令牌、AWS AccessKey、私钥头、以及“关键字=长字面量”的赋值
PATTERNS=(
  'github_pat_[A-Za-z0-9_]{20,}'
  'gh[pousr]_[A-Za-z0-9]{20,}'
  'AKIA[0-9A-Z]{16}'
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'
  # 关键字后接“不含空格的中长字面量”才判定为凭据，避免误报文档中的中文说明
  '(password|passwd|pwd|secret|token|api[_-]?key)["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"'$<[:space:]][A-Za-z0-9_./+=-]{7,}["'"'"']'
)

is_allowed() {
  local file="$1"
  for allowed in "${ALLOWLIST[@]}"; do
    [[ "$file" == "$allowed" ]] && return 0
  done
  return 1
}

collect_files() {
  if [[ "${1:-}" == "--staged" ]]; then
    git diff --cached --name-only --diff-filter=ACM
  else
    find . -type f \
      -not -path './.git/*' -not -path './target/*' -not -path './.m2/*' \
      -not -path './node_modules/*' | sed 's|^\./||'
  fi
}

found=0
while IFS= read -r file; do
  [[ -z "$file" || ! -f "$file" ]] && continue
  is_allowed "$file" && continue
  for pattern in "${PATTERNS[@]}"; do
    while IFS= read -r hit; do
      [[ -z "$hit" ]] && continue
      echo "[疑似凭据] ${file}:${hit}"
      found=1
    # 统一忽略大小写，避免 "Password" / "SECRET" 等写法漏检
    done < <(grep -nEIi -- "$pattern" "$file" 2>/dev/null || true)
  done
done < <(collect_files "${1:-}")

if [[ "$found" -ne 0 ]]; then
  echo ""
  echo "扫描未通过：请将凭据移出仓库，改用环境变量或密钥管理服务注入。"
  exit 1
fi

echo "敏感信息扫描通过：未发现疑似凭据。"

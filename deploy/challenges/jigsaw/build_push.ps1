<#
  构建 / 推送容器题镜像（Windows PowerShell 5.1+ 或 pwsh 都可以）

  用法：
    powershell -File build_push.ps1                              # 只构建
    powershell -File build_push.ps1 -Push                        # 构建并推送
    powershell -File build_push.ps1 -Image myhub/jigsaw -Tag 1.0 -Push

  注意：Docker 守护进程没启动时会明确报错，不会静默跳过。
#>
param(
  [string]$Image = "sacc/jigsaw-sudoku",
  [string]$Tag   = "1.0",
  [switch]$Push
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

Write-Host "== 1/4 检查 docker 环境 ==" -ForegroundColor Cyan
$docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $docker) {
  throw "找不到 docker 命令：请先安装 Docker Desktop 并把它加入 PATH。"
}
docker version --format '{{.Server.Version}}' 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
  throw "Docker 守护进程没在运行（docker version 失败）。请先启动 Docker Desktop，等鲸鱼图标变绿后重试。"
}
Write-Host ("docker 可用：client {0}" -f (docker version --format '{{.Client.Version}}'))

Write-Host "== 2/4 检查构建所需文件 ==" -ForegroundColor Cyan
foreach ($f in @("Dockerfile", "app\server.py", "app\index.html", "data\puzzle.json", "data\answer.sha256")) {
  if (-not (Test-Path $f)) {
    throw "缺少 $f —— 先回到仓库根跑：python make_page.py 和 python 容器题\tools\make_puzzle.py"
  }
}
$digest = (Get-Content data\answer.sha256 -Raw).Trim()
Write-Host ("答案哈希：{0}…" -f $digest.Substring(0, 16))

Write-Host "== 3/4 构建镜像 ==" -ForegroundColor Cyan
docker build -t "${Image}:${Tag}" -t "${Image}:latest" .
if ($LASTEXITCODE -ne 0) { throw "docker build 失败" }
Write-Host ("已构建：{0}:{1} 与 {0}:latest" -f $Image, $Tag)

Write-Host "== 4/4 试运行自检 ==" -ForegroundColor Cyan
$probe = docker run --rm -d -p 18080:8080 -e FLAG='SACC{build-selfcheck}' "${Image}:${Tag}"
Start-Sleep -Seconds 3
try {
  $r = Invoke-RestMethod -Uri "http://127.0.0.1:18080/api/health" -TimeoutSec 5
  if (-not $r.ok) { throw "健康检查返回异常" }
  Write-Host ("容器自检通过：ttl_left={0}s" -f $r.ttl_left) -ForegroundColor Green
} catch {
  Write-Host ("容器自检失败：{0}" -f $_.Exception.Message) -ForegroundColor Red
  docker logs $probe
} finally {
  docker stop $probe | Out-Null
}

if ($Push) {
  Write-Host "== 推送镜像 ==" -ForegroundColor Cyan
  docker push "${Image}:${Tag}"
  docker push "${Image}:latest"
  if ($LASTEXITCODE -ne 0) { throw "docker push 失败：先 docker login" }
  Write-Host "推送完成。把下面的镜像与端口填到平台登记里：" -ForegroundColor Green
  Write-Host ("  image: {0}:{1}" -f $Image, $Tag)
  Write-Host  "  port:  8080 (HTTP)"
  Write-Host  "  env:   FLAG=<本题的正确 flag>"
} else {
  Write-Host "（没有加 -Push，只构建未推送）" -ForegroundColor Yellow
}

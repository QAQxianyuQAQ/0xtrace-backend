# CHANGELOG 2026-11-04 — 六题接入 + 平台侧四处修补

> 本文只记录**本次上线改了什么、为什么改、怎么回滚**。所有改动均在单机 Docker 环境完成。

## 一、六道容器题（新增，全部为 DOCKER 运行时）

| code | 标题 | points | 镜像 | 容器端口 |
|---|---|---|---|---|
| `HC-01` | 人机验证 | 100 | `sacc-humancheck:deploy` | 8080 |
| `SUDOKU` | 异形数独拼图 | 300 | `sacc/jigsaw-sudoku:1.0` | 8080 |
| `SNAKE` | 原型链贪吃蛇 | 200 | `sacc/snake-ctf:latest` | 3000 |
| `MISSING` | 消失的方块 | 300 | `sacc-missing-square:tq` | 5000 |
| `MORSE` | 摩斯电码与错误 MD5 | 200 | `sacc-morse-md5:1.0.0-tq` | 8080 |
| `SRCFLAG` | 查看源码 | 100 | `sacc-source-flag:tq` | 5000 |

建题参数统一：`flagEnvName=FLAG`、`flagPrefix=SACC`、`answer` 留空、先 DRAFT 再 `POST /publish`。
（平台规则：容器题若直接以 PUBLISHED 创建会被拒，必须草稿后单独发布。）

通关验证（每题都在真实容器上打通过）：

- HC-01：GET /api/session → POST /api/progress ×5 → POST /api/claim
- SUDOKU：POST /api/submit（placement + grid）
- SNAKE：POST /api/level/1/score 循环 → 原型链污染 __proto__.bestScore → GET /api/flag
- MISSING：POST /api/session → 两段 8 步 POST /api/move → solved
- MORSE：POST /api/level1（youaregreat）→ 从 level2 HTML 注释取 32hex → POST /api/flag
- SRCFLAG：GET /SACC 的 HTML 注释里直接给 flag

## 二、平台侧修补（四处，均可回滚）

### 2.1 backend 内 docker CLI 包装（修「一直显示正在启动」）

`DockerCliUtil.pull()` 无条件执行 pull；`run()` 合并 stderr，宿主内核不支持 swap 记账时首行是
`WARNING: Your kernel does not support swap limit capabilities...`，被当作容器 ID 写入
`container_id varchar(80)` → `Data too long` → 事务回滚 → 实例卡在 STARTING。

修法：容器内 `/usr/local/bin/docker` 包装脚本（pull 命中本地即跳过；run 只输出 64 位 ID），
并固化为派生镜像 `tracequest-backend:history-20261104-tqwrap`。详见 `platform/README.md`。

### 2.2 `TQ_PUBLIC_WSS_URL` 补端口

`ws://PLATFORM_HOST/...` → `ws://PLATFORM_HOST:8081/api/v1/challenge-connections`。

### 2.3 前端 bundle 放宽 ws 白名单

原判断只允许 localhost / 127.0.0.1 / [::1] 走 `ws://`；改为「http 页面允许同源 ws://」。
镜像 `tracequest-web:event-control-20261104-tqws`。

### 2.4 前端 nginx 增加 `/entry/` 前缀反代

配合入口网关，让选手在浏览器里直接做题；配置见 `platform/nginx.conf`（bind mount，非镜像内文件）。

## 三、入口网关（新增服务）

`entry/gateway.mjs`：按浏览器 `tq_session` 调 GET /runtime/instances/me 解析本队容器，自动启动/换容器，
反代并改写 Host / Origin / Referer 到容器地址，重写 Set-Cookie 的 Path。
部署见 `platform/deploy_entry_gateway.sh`；题址 `http://PLATFORM_HOST:8081/entry/<CODE>/`。

## 四、测试数据处置

端到端测试产生的 1 条真实提交记录（`sub_c33tb1z36jtg4f7k08`，跨队提交副作用）已连同其 solves 行删除，
相关队伍回到测试前分数。

## 五、回滚总表

| 改动 | 回滚 |
|---|---|
| backend 派生镜像 | compose image 换回 `tracequest-backend:history-20261104` |
| .env wss 端口 | 恢复 `.env.bak-<ts>` 后重建 app |
| 前端 ws 补丁 | 前端 compose image 换回 `tracequest-web:event-control-20261104` |
| nginx /entry/ | 恢复 `nginx.conf.bak-<ts>` 并重启 tracequest-frontend |
| 入口网关 | `docker rm -f tq-entry-gateway` |
| 六道题 | 后台逐题下线/删除，或 DELETE /api/v1/admin/challenges/{code} |

## 六、仍然存在的上游缺陷（未修，仅记录）

- `GET /api/v1/me/submissions` 曾报 SQL 语法错（BadSqlGrammar，报错片段 near )）；
  随后用零提交新账号复测返回 200 空页，且导出线上 jar 与仓库源码 225 个类逐一比对完全一致
  （仅多 4 个匿名内部类），判断为修复前旧构建的历史现象，未再改动。

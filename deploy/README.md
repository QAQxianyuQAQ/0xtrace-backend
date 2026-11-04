# 六道 CTF 题目部署产物（TraceQuest / 0xTrace 后端）

本目录是六道容器题的**完整可复现部署包**：题目源码、镜像构建脚本、后端 docker 包装补丁、浏览器入口网关、平台建题与验证脚本。
所有主机名、账号、密码、flag 均以占位符给出，实际值由部署者通过环境变量注入。

---

## 1. 目录结构

```
deploy/
├── challenges/                 六道题的完整源码（各自可 docker build）
│   ├── humancheck/             HC-01  人机验证        python:3.12-slim    :8080
│   ├── jigsaw/                 SUDOKU 异形数独拼图     python:3.12-slim    :8080
│   ├── snake/                  SNAKE  贪吃蛇           node:22-bookworm-slim :3000
│   ├── missing-square/         MISSING 消失的方块      python:3.12-slim    :5000
│   ├── morse-md5/              MORSE  摩斯电码与错误MD5 node:22-bookworm-slim :8080
│   └── source-flag/            SRCFLAG 查看源码        python:3.12-slim    :5000
├── images/
│   ├── host_build.sh           在平台宿主机一键构建上面六个镜像 + 冒烟探活（不需要任何 registry）
│   ├── docker-tq-wrapper.sh    ★ 后端容器内的 /usr/local/bin/docker 包装脚本（见 §3）
│   ├── Dockerfile.tqwrap       派生后端镜像：把上面的 wrapper 固化成镜像
│   ├── Dockerfile.tqws         派生前端镜像示例（改 bundle 里 WSS 白名单）
│   └── apply_ws_fix.sh         线上切换脚本示例（改 .env 的 TQ_PUBLIC_WSS_URL + 换镜像 tag）
├── entry/
│   ├── gateway.mjs             ★ 浏览器做题入口网关：/entry/<CODE>/ → 本队容器
│   └── Dockerfile              node:22-alpine
└── scripts/
    ├── accounts.json.example   平台账号表模板（复制成 accounts.json 再填）
    ├── create_challenges.mjs   登录管理员 → 建 6 道 DOCKER 题（草稿）→ 逐题 publish
    ├── platform_test.mjs       端到端：登录 → 开容器 → 解题 → 提交
    ├── tqsolve.mjs             六题统一通关器（在宿主机上对容器 IP 执行）
    ├── solve_humancheck.mjs / solve_snake.mjs / solve_morse.mjs   单题参考解
    └── cross_team.mjs          跨队 flag 校验（应返回 403 FLAG_OWNER_MISMATCH）
```

---

## 2. 部署前置条件（平台源码的硬约束，先读再动手）

后端仓库：`https://github.com/QAQxianyuQAQ/0xtrace-backend`

| 约束 | 源码位置 | 影响 |
|---|---|---|
| 起容器前**无条件** `docker pull` | `util/DockerCliUtil.java:82-84`（`run(docker, List.of("pull", image + ":" + tag))`） | 宿主机 `docker load` 的本地镜像**不够**，pull 非 0 退出直接抛 `IllegalStateException("Docker 命令执行失败: ...")` → 用 §3 的 wrapper |
| `dockerImage` 正则不含冒号 | `ChallengeCreateDTO`：`^[a-z0-9][a-z0-9._/-]*$` | 填不了 `127.0.0.1:5000/xxx`，只能用默认端口的域名型仓库（或本地镜像名 + wrapper 跳过 pull） |
| 容器题四字段必须同时给、answer 必须留空 | `ChallengeServiceImpl.resolveRuntimeType` | `dockerImage` / `dockerTag` / `flagEnvName` / `flagPrefix` 四项齐全 |
| 容器题**不能直接发布** | 同文件：`if (runtimeType == DOCKER && status == PUBLISHED) throw ...("容器题目应先保存为草稿，再单独发布")` | 必须 `POST /api/v1/admin/challenges/{code}/publish` |
| 提交必须是本队 flag | `SubmissionServiceImpl.validateContainerFlag` + `ChallengeContainerServiceImpl.resolveFlag` | flag = `flagPrefix + "{" + BCrypt(teamName) + "}"`，跨队提交 403 |
| 写接口校验 Origin | `SameOriginFilter` | 所有 POST/DELETE 必须带 `Origin: http://<平台地址>`，否则 403 `ORIGIN_REJECTED` |
| 每队同时只能跑一个容器 | `ChallengeContainerServiceImpl` `@DistributedLock(key="'team-instance:' + #team.id")` | 切题前先 `DELETE .../runtime/instances/me` |

平台默认限流（`src/main/resources/application.yml`）：`login-per-minute=10`、`submission-per-minute=30`、默认 60 —— 自动化测试要慢速，否则 `429 RATE_LIMITED`。

---

## 3. 部署步骤

### 步骤 1 · 在平台宿主机构建六个镜像

```bash
# 上传整个 deploy/ 到宿主机，例如 /home/<user>/tqdeploy
cd /home/<user>/tqdeploy
cp challenges/* -r /tmp/kit/ 2>/dev/null || true
mkdir -p /tmp/kit && cp -r challenges/* /tmp/kit/
cp images/host_build.sh /tmp/kit/
cd /tmp/kit && bash host_build.sh
```

脚本会用 `docker build` 产出与平台配置一致的 tag：

| 题 | 镜像 tag | 容器端口 |
|---|---|---|
| 人机验证 | `sacc-humancheck:deploy` | 8080 |
| 异形数独拼图 | `sacc/jigsaw-sudoku:1.0` | 8080 |
| 贪吃蛇 | `sacc/snake-ctf:latest` | 3000 |
| 消失的方块 | `sacc-missing-square:tq` | 5000 |
| 摩斯电码与错误 MD5 | `sacc-morse-md5:1.0.0-tq` | 8080 |
| 查看源码 | `sacc-source-flag:tq` | 5000 |

末尾会把每个镜像起一次容器（注入一个假 flag）并 curl 探活，全部 200 即构建通过。
> 只依赖 `python:3.12-slim`、`node:22-bookworm-slim`、`node:20-alpine` 等官方基础镜像，不需要任何私有 registry。

### 步骤 2 · 打后端 docker 包装补丁（否则容器永远「正在启动」）

**症状**：点启动一直转圈，后端日志 `Data truncation: Data too long for column 'container_id' at row 1`，实例卡在 STARTING。

**根因**：后端用 `ProcessBuilder(...).redirectErrorStream(true)` 合并 stderr；当宿主内核不支持 swap 记账时，docker CLI 会往 stderr 打一行
`WARNING: Your kernel does not support swap limit capabilities or the cgroup is not mounted. Memory limited without swap.`
`startContainer` 取 `lines().findFirst()` 当容器 ID，于是这行 warning 被写进 `container_id varchar(80)` → 截断 → 事务回滚。

**修法**（`images/docker-tq-wrapper.sh`，1648B 的 bash 包装）：

- `pull`：本地 `docker image inspect` 命中则打印一行提示并 `exit 0`（解决 §2 第一条的 registry 依赖）
- `run`：stdout/stderr 分流，**只把 64 位容器 ID 写 stdout**，WARNING 丢弃，原始输出记到 `/tmp/tq-docker-cli.log`
- 其余子命令 `exec /usr/bin/docker "$@"` 原样透传

固化进镜像（`images/Dockerfile.tqwrap`）：

```dockerfile
FROM tracequest-backend:<当前线上tag>
USER 0
COPY docker-tq-wrapper.sh /usr/local/bin/docker
RUN chmod 0755 /usr/local/bin/docker
USER 10001
```

```bash
docker build -f images/Dockerfile.tqwrap -t tracequest-backend:<tag>-tqwrap images/
# 改 docker-compose 里 backend 的 image 为新 tag，然后
docker compose -f <compose文件> up -d app
curl -s http://127.0.0.1:8080/api/v1/health   # {"ok":true,...}
```

应急（不改镜像）也可以只 `docker cp` 进运行中的容器，但容器重建即丢失，仅用于排障。

### 步骤 3 · 建题并发布

```bash
cp scripts/accounts.json.example scripts/accounts.json   # 填管理员账号
export TQ_BASE=http://PLATFORM_HOST:8081
export TQ_EVENT_ID=evt_xxxxxxxxxxxxxxxx
node scripts/create_challenges.mjs
```

脚本做的事：管理员登录（带 Origin）→ 逐题 `POST /api/v1/admin/events/{eventId}/challenges`（DRAFT，容器四字段齐全、answer 留空）→ 逐题 `POST /api/v1/admin/challenges/{code}/publish`。

六题的 code / 分值建议：

| code | 标题 | 分类 | 难度 | 分值 | flagPrefix | flagEnvName |
|---|---|---|---|---|---|---|
| HC-01 | 人机验证 | Web | 简单 | 100 | SACC | FLAG |
| SUDOKU | 异形数独拼图 | Misc | 困难 | 300 | SACC | FLAG |
| SNAKE | 贪吃蛇 | Web | 中等 | 200 | SACC | FLAG |
| MISSING | 消失的方块 | Web | 困难 | 300 | SACC | FLAG |
| MORSE | 摩斯电码与错误 MD5 | Web | 中等 | 200 | SACC | FLAG |
| SRCFLAG | 查看源码 | Web | 简单 | 100 | SACC | FLAG |

### 步骤 4 · 浏览器做题入口网关（平台面板给不了 HTML）

平台的「连接容器」面板是**裸 socket 通道**：它把你输入的原样发给容器、把容器的原始响应原样显示，**不会执行题目 HTML/JS**（前端文案原文：「显示原始 HTTP 响应，不会执行题目 HTML」）。六道都是 Web 题，选手需要一个真正的浏览器入口。

`entry/gateway.mjs` 补齐这一段：

```bash
docker build -t tq-entry-gateway:1 entry/
docker run -d --name tq-entry-gateway --restart unless-stopped \
  --network <与 backend 同网> \
  -e TQ_EVENT_ID=evt_xxxxxxxxxxxxxxxx \
  -e TQ_API=http://tracequest-backend:8080 \
  -e TQ_PUBLIC=http://PLATFORM_HOST:8081 \
  tq-entry-gateway:1
docker network connect <runtime网络> tq-entry-gateway   # 必须能直连题目容器
```

行为：

1. 读浏览器 cookie `tq_session` → 调 `GET {API}/api/v1/events/{EVENT}/challenges/{code}/runtime/instances/me` 得到本队容器 IP
2. 未启动 → 自动 `POST .../runtime/instances`（409 冲突时自动销毁本队其它题容器后重试一次）
3. READY → 反向代理到容器，**Host/Origin/Referer 折算成 `http://<ip>:<port>`**（多数题目容器做同源校验）
4. 文本响应重写绝对路径 `"/xxx"` → `"/entry/<CODE>/xxx"`，HTML 注入 `<base href="/entry/<CODE>/">`，`Set-Cookie` 的 Path 改写为前缀（避免污染平台会话）

对外只需要一条 nginx 规则（平台前端容器里加）：

```nginx
upstream tq_entry_gateway { zone tq_entry_gateway 64k; server tq-entry-gateway:8090 resolve; }
location ^~ /entry/ {
    proxy_pass http://tq_entry_gateway;
    proxy_http_version 1.1;
    proxy_set_header Host $http_host;
    proxy_buffering off;
    proxy_read_timeout 120s;
    client_max_body_size 30m;
}
```

题目地址即 `http://<平台地址>/entry/HC-01/`、`/entry/SUDOKU/` …… `/entry/SRCFLAG/`（该题首页是 `/SACC`，网关已做 302）。
每队各自解析到自己队伍的容器，flag 也各自独立。

### 步骤 5 · 验证

```bash
export TQ_BASE=http://PLATFORM_HOST:8081 TQ_PASS='<选手密码>'
node scripts/platform_test.mjs        # 登录 → 逐题开容器 → 解题 → 提交 → 销毁
```

单题手工验证（在**平台宿主机**上，容器 IP 只在运行网络内可达）：

```bash
node scripts/tqsolve.mjs hc-01    http://172.23.0.4:8080
node scripts/tqsolve.mjs morse   http://172.23.0.5:8080
node scripts/tqsolve.mjs srcflag http://172.23.0.6:5000
node scripts/tqsolve.mjs snake   http://172.23.0.7:3000
node scripts/tqsolve.mjs jigsaw  http://172.23.0.8:8080 ./jigsaw_payload.json
node scripts/tqsolve.mjs missing http://172.23.0.9:5000 ./missing_fixtures.json
```

通过标准：六题各返回本队 flag（形如 `SACC{$2a$10$...}`）→ `POST /api/v1/challenges/{code}/submissions` 返回 `result: correct` 且榜单出现首杀加分；换另一个队伍的账号提交同一 flag 应 403 `FLAG_OWNER_MISMATCH`。

---

## 4. 六道题的解法要点（出题/回归用）

| 题 | 通过路径 |
|---|---|
| HC-01 | `GET /api/session` 取 sid/code/hidden/crash/phrase → `POST /api/progress` 5 步 → `POST /api/claim` |
| SUDOKU | 用 `tools/make_puzzle.py` 的 `answer_payload()` 生成 placement+grid → `POST /api/submit` |
| SNAKE | 刷分到 target → `POST /api/profile` 原型链污染 `{preferences:{__proto__:{bestScore:99999}}}` → `POST /api/level/2/score` → `GET /api/flag` |
| MISSING | 两阶段 8 步还原 → `solved:true` 返回 flag（`verification-fixtures.json` 里有回归用例） |
| MORSE | `POST /api/level1 {answer:'youaregreat'}` → 二级页 HTML 注释里的 base64（每 4 位插了垃圾字符）→ 剔非 hex 得 32 位 → `POST /api/flag` |
| SRCFLAG | 访问 `/SACC`，flag 写在 index.html 的 `<!-- {{FLAG}} -->` 注释里（「查看源码」即解） |

MORSE 与 SNAKE、SRCFLAG 相对原版做过适配：flag 由平台注入 `FLAG` 环境变量；MORSE 的校验值改为 `sha256(flag内层).slice(0,32)`，同时保留原 `TRUE_HEX` 常量分支，`/api/flag` 接受 `FLAG` / 32 位 hex / `SACC{hex}` 三种输入。

---

## 5. 常见坑

1. **容器一直「正在启动」** → §3 步骤 2 的 container_id 截断；先看后端日志有没有 `Data truncation`。
2. **`429 RATE_LIMITED`** → 平台限流；实例 POST/DELETE 很快烧完配额，等 60–90 秒，或让脚本串行慢跑。
3. **`403 ORIGIN_REJECTED`** → 忘带 `Origin`。直连 backend 写 `Origin: http://127.0.0.1:8080`，走公网写 `http://<平台地址>`（`SameOriginFilter` 比较 Origin.host 与请求 Host 头，不带端口）。
4. **`409 CONFLICT`** → 本队已有其它题的容器；先 `DELETE .../runtime/instances/me`。
5. **flag 里的 `$2a$10$...` 被 shell 吃掉** → 提交脚本必须从文件或 .mjs 常量读 flag，不要用 `node -e "...{flag{'}'}..."` 内联。
6. **前端 WSS 报「连接地址必须使用受信的 WSS 服务」** → 前端 bundle 里 `["localhost","127.0.0.1","[::1]"].includes(n.hostname)` 白名单不含公网 IP；同时 `TQ_PUBLIC_WSS_URL` 必须带对外端口（如 `ws://PLATFORM_HOST:8081/api/v1/challenge-connections`）。见 `images/Dockerfile.tqws`、`images/apply_ws_fix.sh`。
7. **前端 nginx 配置是 bind mount，不是镜像内文件** → 改镜像里 `default.conf` 无效，必须改宿主机挂载的那个文件再 `docker restart`。

---

## 6. 已知未修问题（如实记录）

`GET /api/v1/me/submissions` 在早期版本抛 `SQLSyntaxErrorException ... near ')'`（疑似 MyBatis-Plus 空集合生成 `IN ()`）。核对结论：公开仓库 `SubmissionServiceImpl` 已有 `if (eventChallengeIds.isEmpty()) return 空页;` 短路，线上 jar 与仓库 225/225 类完全一致，且新账号实测返回 200 空列表 —— **当前版本不再复现**，无需重编译。

---

## 7. 回滚

| 改动 | 回滚 |
|---|---|
| backend 镜像换成 `...-tqwrap` | compose 里改回原 tag 后 `docker compose up -d app` |
| frontend 镜像 / nginx.conf | 恢复备份的 `nginx.conf.bak-*` 后 `docker restart tracequest-frontend` |
| 入口网关 | `docker rm -f tq-entry-gateway` |
| 建的六道题 | `POST /api/v1/admin/challenges/{code}/unpublish` 或后台删除（草稿态不影响选手） |

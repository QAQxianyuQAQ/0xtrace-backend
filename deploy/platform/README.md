# platform — 平台侧改动与运维脚本

本目录记录为让六道容器题在 0xtrace 平台跑通而对**平台本体**做的四处改动，以及配套运维脚本。
全部改动都可回滚，回滚方式见每节末尾与 `../CHANGELOG-20261104.md`。

## 1. 后端容器内的 docker CLI 包装（必须）

**为什么**：`DockerCliUtil.java` 里 `pull()` 是无条件执行的（本地已有镜像也会失败）；
`run()` 用 `redirectErrorStream(true)` 合并 stderr，而 DOCKER 宿主内核不支持 swap 记账时会先打一行
`WARNING: Your kernel does not support swap limit capabilities...`，该行被 `startContainer` 的
`lines().findFirst()` 当容器 ID 写进 `container_id varchar(80)` → `Data too long` → 事务回滚，
实例永远停在 STARTING（前端显示「正在启动」）。

**做法**：在 backend 容器内放一个 `/usr/local/bin/docker` 包装脚本（`../images/docker-tq-wrapper.sh`）：
`pull` 命中本地镜像直接 exit 0；`run` 只把 64 位容器 ID 写 stdout、丢弃 WARNING；其余命令透传。
再用 `../images/Dockerfile.tqwrap` 固化成派生镜像，避免容器重建后丢失。

```bash
docker build -f images/Dockerfile.tqwrap -t tracequest-backend:history-20261104-tqwrap images/
# 把 docker-compose.app-only.yml 里 app 的 image 换成新 tag，再 up -d app
```

**回滚**：镜像 tag 换回 `tracequest-backend:history-20261104` 并 `docker compose up -d app`。

## 2. `TQ_PUBLIC_WSS_URL` 必须带端口

默认值 `ws://PLATFORM_HOST/api/v1/challenge-connections` 少了端口，而 80 端口由别的 nginx 占用，
浏览器 WebSocket 连不上 → 前端报「连接地址必须使用受信的 WSS 服务」。
改为 `ws://PLATFORM_HOST:8081/api/v1/challenge-connections`（见 `apply_ws_fix.sh`）。

## 3. 前端 bundle 的 ws 白名单

前端只允许 `localhost/127.0.0.1/[::1]` 走 `ws://`，其余一律要求 `wss://`。
内网部署没有证书时，把该判断改为「http 页面即允许同源 ws://」，
镜像见 `../images/Dockerfile.tqws`（`tracequest-web:<tag>-tqws`）。

## 4. 前端 nginx 加 `/entry/` 前缀反代

平台自带的「连接容器」面板是裸 socket 通道（只收发字节，不渲染 HTML），Web 题需要真正的浏览器入口。
`nginx.conf` 是最终的前端配置（bind mount 到容器 `/etc/nginx/conf.d/default.conf`）：
`upstream tq_entry_gateway` + `location ^~ /entry/`，把请求转给入口网关。
注意前端容器是 `read_only: true`，改配置只能改宿主机上那一份再 restart。

> `/tq-guide.js` 的悬浮球注入只是临时方案，正式前端交付后应整段删除。

## 脚本

| 脚本 | 用途 |
|---|---|
| `apply_ws_fix.sh` | 补 `.env` 的 wss 端口 + 切前端镜像 |
| `deploy_entry_gateway.sh` | 构建并启动 `/entry/` 网关（双网络） |
| `event_toggle.sh open\|close` | 赛事开关（绕过 UI 直接改 events 一行） |
| `nginx.conf` | 前端 nginx 配置参考（含 `/entry/`） |

所有脚本都不含明文口令，数据库口令走 `TQ_DB_PASSWORD` 环境变量。

# TraceQuest 单机 Docker 部署指南

> 更新时间：2026-10-28
>
> 适用架构：单台内网 Docker Engine + Docker Compose；REST、MinIO 使用独立公网端口，公网 443 专用于题目容器 WSS

本文描述 TraceQuest 后端当前的正式部署方式。项目只支持单台 Docker Engine 和单个应用实例，不支持 Kubernetes。

## 1. 部署拓扑

```text
选手浏览器
    ├── REST :8080 ─────────────────────────► tracequest-backend
    ├── MinIO :9000/9001 ───────────────────► tracequest-minio
    └── WSS :443
          ▼
        公网 frps
          │
          ▼
        内网 frpc
          │
          ▼
        tracequest-backend
          │ TCP
          ▼
        选手容器实例
          └── tracequest-runtime 内部 Docker 网络
```

基础 Compose 容器：

| 容器 | 作用 | 端口策略 |
|---|---|---|
| `tracequest-backend` | Spring Boot 应用 | 默认开放宿主机 8080 提供 REST 和 WSS 网关 |
| `tracequest-mysql` | MySQL 8.0，权威数据源 | 不发布宿主机端口 |
| `tracequest-redis` | 缓存、限流、会话黑名单、分布式锁 | 不发布宿主机端口 |
| `tracequest-minio` | 附件、封面等对象存储 | 默认开放宿主机 9000/9001 |

动态容器：

- Docker 题目由后端通过宿主机 Docker Socket 创建。
- 每个队伍同时只能运行一个题目容器。
- 题目容器加入 `tracequest-runtime` 内部网络。
- 容器默认存活 1 小时，剩余不超过 30 分钟时可延长 1 小时。
- 选手只能通过 `/api/v1/challenge-connections` 的二进制 WSS 连接容器，不能直接访问容器 IP 或随机端口。

## 2. 前置条件

建议服务器配置：

- 最低：2 核 CPU / 4 GB 内存 / 20 GB 可用磁盘。
- 推荐：4 核 CPU / 8 GB 内存 / 50 GB 可用磁盘。
- 题目容器会额外消耗内存、CPU、PID 和磁盘资源，资源上限通过 `.env` 调整。

软件要求：

- Ubuntu 22.04/24.04 或其他可安装 Docker Engine 的 Linux。
- Docker Engine 24+。
- Docker Compose v2。
- 公网侧已部署可用的 frps。
- 已准备两个域名，例如 `tq.example.com` 和 `minio.example.com`。

网络要求：

- 内网服务器可以访问 Docker Hub、Maven 中央仓库和系统软件源。
- 内网 frpc 可以主动连接公网 frps。
- 公网 443 专用于 frp 的题目容器 WSS 转发。
- REST API 默认使用公网 8080。
- MinIO 默认使用公网 9000，控制台使用 9001。
- MySQL 和 Redis 不发布公网端口。

## 3. 安装 Docker Engine 与 Compose

使用 Docker 官方软件源：

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings

sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y \
  docker-ce docker-ce-cli containerd.io \
  docker-buildx-plugin docker-compose-plugin
```

验证：

```bash
sudo docker version
sudo docker compose version
sudo docker run --rm hello-world
```

## 4. 获取项目

```bash
sudo mkdir -p /opt/tracequest
sudo chown "$USER":"$USER" /opt/tracequest

git clone https://github.com/QAQxianyuQAQ/0xtrace-backend.git /opt/tracequest
cd /opt/tracequest
```

如果仓库需要私钥认证，请使用 SSH Key 或 Git Credential Manager。不要把 GitHub PAT 写进仓库地址或 `.git/config`。

## 5. 准备环境变量

```bash
cd /opt/tracequest
cp .env.deploy.example .env
chmod 600 .env
```

生成密钥：

```bash
openssl rand -base64 48  # TQ_JWT_SECRET
openssl rand -base64 48  # TQ_ANSWER_SECRET
openssl rand -base64 32  # TQ_DB_PASSWORD
openssl rand -base64 32  # TQ_DB_ROOT_PASSWORD
openssl rand -base64 32  # TQ_REDIS_PASSWORD
openssl rand -base64 32  # TQ_MINIO_ACCESS_KEY
openssl rand -base64 48  # TQ_MINIO_SECRET_KEY
openssl rand -base64 32  # TQ_ADMIN_PASSWORD
```

关键变量：

| 变量 | 说明 |
|---|---|
| `TQ_IMAGE` | 应用镜像名称 |
| `TQ_SERVER_PORT` | REST API 公网端口，默认 8080 |
| `TQ_DOCKER_GID` | 宿主机 Docker Socket 的组 ID |
| `TQ_DB_*` | MySQL 地址、账号、密码与数据库名 |
| `TQ_REDIS_*` | Redis 地址、密码 |
| `TQ_MINIO_ENDPOINT` | 必须是浏览器可访问的 MinIO 公网地址，例如 `http://<服务器IP>:9000` |
| `TQ_MINIO_ACCESS_KEY` | MinIO 访问密钥 |
| `TQ_MINIO_SECRET_KEY` | MinIO 私钥 |
| `TQ_JWT_SECRET` | JWT 签名密钥 |
| `TQ_ANSWER_SECRET` | 题目 Flag 的 AES-GCM 加密密钥 |
| `TQ_ADMIN_PASSWORD` | 管理员初始密码 |
| `TQ_COOKIE_SECURE` | 正式 HTTPS 环境必须为 `true` |
| `TQ_ENFORCE_SAME_ORIGIN` | 正式环境必须为 `true` |
| `TQ_PUBLIC_WSS_URL` | 公网 WSS 地址 |
| `TQ_RUNTIME_NETWORK` | 题目容器内部网络，默认 `tracequest-runtime` |
| `TQ_RUNTIME_INSTANCE_TTL` | 容器存活时间，默认 `1h` |
| `TQ_RUNTIME_EXTENSION_DURATION` | 单次延长时间，默认 `1h` |
| `TQ_RUNTIME_EXTENSION_WINDOW` | 允许延长的时间窗口，默认 `30m` |

查看 Docker Socket 组 ID：

```bash
stat -c '%g' /var/run/docker.sock
```

把结果写入：

```dotenv
TQ_DOCKER_GID=<Socket组ID>
```

应用镜像以非 root UID 10001 运行，依靠补充组访问 Docker Socket。Docker Socket 等价于宿主机高权限接口，生产环境建议在前面增加只允许镜像拉取、容器创建、检查、停止和删除的 Docker Socket Proxy。

## 6. 配置 frp：公网 443 到题目容器 WSS

参考 [deploy/frp/frpc.toml.example](deploy/frp/frpc.toml.example)，公网 443 只配置题目容器 WebSocket 转发：

```toml
serverAddr = "frp.example.com"
serverPort = 443

auth.method = "token"
auth.token = "<frp-token>"

[[proxies]]
name = "tracequest-wss"
type = "https"
localIP = "127.0.0.1"
localPort = 8080
customDomains = ["tq.example.com"]
```

要求在 frps 侧完成：

1. 为 `tq.example.com` 配置 TLS 证书。
2. 将 `tq.example.com` 解析到公网 frps。
3. 确保 HTTPS 代理透传 WebSocket Upgrade 请求。
4. 配置文件级反向代理时只允许 `/api/v1/challenge-connections`。

对应环境变量：

```dotenv
TQ_PUBLIC_WSS_URL=wss://tq.example.com/api/v1/challenge-connections
TQ_MINIO_ENDPOINT=http://<服务器IP>:9000
# REST 直接使用 HTTP 8080 时为 false；REST 通过 HTTPS 端口/反向代理时为 true
TQ_COOKIE_SECURE=false
```

REST API 使用公网 8080，MinIO 使用公网 9000/9001。它们都不经过 frp 的 443 端口。

## 7. 数据库迁移

DDL 位于 `src/main/resources/sql/`：

| 顺序 | 文件 | 内容 |
|---|---|---|
| 1 | `V1__init_schema.sql` | 基础表 |
| 2 | `V2__event_scoped_teams.sql` | 赛事内队伍 |
| 3 | `V3__challenge_container_runtime.sql` | Docker 题目与容器实例 |
| 4 | `V4__container_ttl_and_team_limit.sql` | 每队单容器、TTL、随机端口约束 |

### 7.1 全新数据库

使用 `docker-compose.yml` 时，MySQL 首次初始化数据卷会自动按 V1–V4 顺序执行。

MySQL 数据卷初始化完成后不会再次执行这些脚本。不要通过删除整个数据卷来升级已有环境。

### 7.2 已有数据库

只执行尚未应用的迁移，且必须保持顺序。例如：

```bash
cd /opt/tracequest

docker compose exec -T mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  < src/main/resources/sql/V2__event_scoped_teams.sql

docker compose exec -T mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  < src/main/resources/sql/V3__challenge_container_runtime.sql

docker compose exec -T mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  < src/main/resources/sql/V4__container_ttl_and_team_limit.sql
```

如果 V1 尚未执行或数据库为空，先执行 V1。

## 8. 首次启动

首次启动需要临时创建管理员：

```bash
cd /opt/tracequest

sed -i \
  's/^TQ_BOOTSTRAP_ENABLED=.*/TQ_BOOTSTRAP_ENABLED=true/; s/^TQ_STRICT_SECRET_CHECK=.*/TQ_STRICT_SECRET_CHECK=false/' \
  .env

docker compose config --quiet
docker compose up -d --build
docker compose ps
docker compose logs -f app
```

首次构建会：

1. 拉取 Maven + JDK 25 和 JRE 25 镜像。
2. 编译 Java 25 项目。
3. 在运行镜像中安装 Docker CLI。
4. 启动应用、MySQL、Redis 和 MinIO。
5. 初始化数据库表。
6. 创建管理员和默认赛事。
7. 加入 `tracequest-runtime` 内部网络。

看到以下日志表示初始化完成：

```text
已创建默认管理员账号: admin
已创建默认赛事: TraceQuest Season 01
```

## 9. 验证

### 9.1 健康检查

```bash
curl -s http://127.0.0.1:8080/api/v1/health
```

预期：

```json
{"ok":true,"service":"tracequest-backend"}
```

### 9.2 管理员登录

```bash
curl -i -c /tmp/tq-cookie.txt \
  -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -H 'Origin: http://127.0.0.1:8080' \
  -d '{"username":"admin","password":"<管理员密码>"}'
```

### 9.3 后台接口

```bash
curl -s -b /tmp/tq-cookie.txt \
  -H 'Origin: http://127.0.0.1:8080' \
  http://127.0.0.1:8080/api/v1/admin/dashboard
```

### 9.4 Docker 运行时网络

```bash
docker network inspect tracequest-runtime
docker inspect tracequest-backend \
  --format '{{json .NetworkSettings.Networks}}'
```

应用容器和后续创建的题目容器都必须位于 `tracequest-runtime`。

## 10. 切换到正式运行

管理员创建完成后，恢复生产开关：

```bash
cd /opt/tracequest

sed -i \
  's/^TQ_BOOTSTRAP_ENABLED=.*/TQ_BOOTSTRAP_ENABLED=false/; s/^TQ_STRICT_SECRET_CHECK=.*/TQ_STRICT_SECRET_CHECK=true/' \
  .env

docker compose up -d app
docker compose logs -f app --tail=200
```

强校验打开后，以下情况会导致应用拒绝启动：

- 密钥缺失或过弱。
- `TQ_BOOTSTRAP_ENABLED` 仍为 `true`。
- JWT、Flag 加密密钥、管理员密码未正确注入。

## 11. Docker 题目运行检查

创建并发布 Docker 题目后，可做以下检查：

1. 镜像必须声明 TCP `EXPOSE` 端口。
2. 后台启动容器后，容器状态应为 `READY`。
3. 同一队伍再次启动其他题目容器应返回 `409`。
4. 容器状态响应应包含 `expiresAt` 和 `remainingSeconds`。
5. 剩余时间不超过 30 分钟时，延长接口应增加 1 小时。
6. 到期后应由定时任务自动删除。
7. 公网客户端只连接 WSS，不能直接访问容器地址。

查看后端托管容器：

```bash
docker ps \
  --filter 'label=tracequest.managed=true' \
  --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
```

查看内部网络：

```bash
docker network inspect tracequest-runtime \
  --format '{{range .Containers}}{{println .Name .IPv4Address}}{{end}}'
```

应用日志中不应出现 Flag 明文。数据库中的队伍 Flag 应为 AES-GCM 密文。

## 12. 防火墙与来源校验

公网防火墙建议：

```bash
sudo ufw default deny incoming
sudo ufw allow 22/tcp
sudo ufw allow 443/tcp
sudo ufw allow 8080/tcp
sudo ufw allow 9000/tcp
sudo ufw allow 9001/tcp
sudo ufw enable
```

端口用途：

- `443`：仅用于 frp 的题目容器 WSS 转发。
- `8080`：REST API。
- `9000`：MinIO S3 API 和预签名下载。
- `9001`：MinIO 管理控制台，可按需限制为管理员 IP。
- MySQL 和 Redis 不发布公网端口。

前端建议与 API 使用同一公网域名，通过同域路径访问。当前 CORS 默认关闭，Cookie 使用 `SameSite=Strict`，因此不建议把前端和 API 部署成互不相关的跨站域名。

若确需额外的同站来源，可配置：

```dotenv
TRACEQUEST_SECURITY_ALLOWED_ORIGINS=https://app.example.com
TQ_ENFORCE_SAME_ORIGIN=true
```

## 13. 复用已有 MySQL / Redis / MinIO

如果 MySQL、Redis、MinIO 已由其他 Docker Compose 项目维护：

```bash
docker compose -f docker-compose.app-only.yml up -d --build
```

使用前需要：

1. 在 `.env` 填写已有中间件地址和凭据。
2. 把 `TQ_MINIO_ENDPOINT` 设置为浏览器可访问的 MinIO 公网地址和端口。
3. 手动执行尚未应用的 V1–V4 迁移。
4. 确保应用加入 `tracequest-runtime` 内部网络。
5. 确保应用有 Docker Socket 访问权限。

常见地址填写：

| 已有中间件暴露方式 | 配置 |
|---|---|
| 发布到宿主机 `0.0.0.0` | 填宿主机内网 IP |
| 只发布到宿主机 `127.0.0.1` | 使用 `host.docker.internal` |
| 只在对方 Docker 网络内 | 将应用加入对方外部网络并填写容器服务名 |

Redis 的键统一使用 `tq:` 前缀。共享 Redis 时应避免使用会随意驱逐键的 `maxmemory-policy`。

MinIO 必须使用独立桶和独立凭据。`TQ_MINIO_ENDPOINT` 的 Host 必须与浏览器访问 MinIO 时的 Host 完全一致，否则预签名地址会出现 `SignatureDoesNotMatch`。

## 14. 日常运维

```bash
cd /opt/tracequest

# 状态
docker compose ps
docker ps --filter 'label=tracequest.managed=true'

# 应用日志
docker compose logs -f app --tail=200

# 重启应用
docker compose restart app

# 升级代码
git pull
docker compose build app
docker compose up -d app
docker image prune -f
```

升级前先备份数据库和 `.env`。已有数据库升级时，还要按顺序执行新增迁移脚本。

## 15. 备份与恢复

### 15.1 MySQL

```bash
docker compose exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --default-character-set=utf8mb4 "$MYSQL_DATABASE"' \
  > "backup-$(date +%F).sql"
```

恢复：

```bash
docker compose exec -T mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  < backup-2026-10-28.sql
```

### 15.2 MinIO

```bash
minio_volume=$(docker volume ls \
  --format '{{.Name}}' | grep '_minio-data$' | head -1)

docker run --rm \
  -v "$minio_volume":/data:ro \
  -v "$PWD:/backup" \
  alpine \
  tar czf "/backup/minio-$(date +%F).tar.gz" -C /data .
```

### 15.3 必须备份 `.env`

`.env` 中包含 JWT 签名密钥和 Flag 的 AES-GCM 加密密钥。丢失 `TQ_ANSWER_SECRET` 后，已有题目密文无法恢复。

`.env` 必须保存在权限为 `600` 的安全位置，不得提交到 Git。

## 16. 常见问题

| 现象 | 处理 |
|---|---|
| `permission denied /var/run/docker.sock` | `TQ_DOCKER_GID` 与宿主机 Socket 组 ID 不一致；重新执行 `stat -c '%g' /var/run/docker.sock` |
| 应用无法创建题目容器 | 检查 Docker Socket 挂载、`tracequest-runtime` 网络和应用日志 |
| 题目镜像启动失败 | 确认镜像存在、`dockerImage:dockerTag` 正确并声明 TCP `EXPOSE` 端口 |
| 同一队伍启动第二道题返回 `409` | 每队只允许一个运行容器；先调用摧毁接口 |
| 无法延长容器 | 只有剩余时间不超过 30 分钟时可延长 |
| WSS 握手失败 | 检查一次性票据、Origin、公网 frp 域名和 WebSocket Upgrade 透传 |
| 附件下载 403 | `TQ_MINIO_ENDPOINT` 与浏览器访问 MinIO 的 Host 不一致 |
| 登录后请求 401 | HTTPS 环境应设置 `TQ_COOKIE_SECURE=true`，HTTP 调试环境应设为 `false` |
| 写请求返回 `ORIGIN_REJECTED` | 前端、frp 和 API 的 Host/Origin 不一致；保持同源并透传 Host |
| MySQL 启动后提示表不存在 | 数据卷已存在时初始化脚本不会重跑，手动执行缺失的 V1–V4 迁移 |
| 应用启动失败并提示密钥不安全 | 修复 `.env`，并确认生产环境 `TQ_BOOTSTRAP_ENABLED=false`、`TQ_STRICT_SECRET_CHECK=true` |

## 17. 上线检查清单

- [ ] 只存在一台 Docker Engine 和一个应用实例。
- [ ] Docker Socket 仅授予应用运行所需权限。
- [ ] `tracequest-runtime` 是内部网络。
- [ ] REST API 在配置的公网端口 8080 可访问。
- [ ] MinIO 在配置的公网端口 9000 可访问，9001 按需限制访问来源。
- [ ] 公网 443 只用于题目容器 WSS 转发。
- [ ] frp 已透传 WebSocket Upgrade。
- [ ] `TQ_PUBLIC_WSS_URL` 使用公网 WSS 域名。
- [ ] `TQ_MINIO_ENDPOINT` 使用浏览器实际访问的 MinIO 公网地址，默认包含 `:9000`。
- [ ] V1–V4 数据库迁移已全部应用。
- [ ] `.env` 权限为 `600`，未进入 Git。
- [ ] 管理员初始化完成后关闭 `TQ_BOOTSTRAP_ENABLED`。
- [ ] `TQ_STRICT_SECRET_CHECK=true`。
- [ ] 20 个测试和 `/api/v1/health` 已验证。
- [ ] 镜像、日志和数据库备份策略已配置。

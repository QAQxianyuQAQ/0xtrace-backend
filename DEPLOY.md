# 部署到 Ubuntu 20.04（Docker Compose）

本文档描述把 TraceQuest 后端以 **单机 Docker Compose** 方式部署到 Ubuntu 20.04 的完整流程。
宿主机不需要安装 JDK 25：Java 25 的编译与运行都发生在容器内（见 [`Dockerfile`](Dockerfile)）。

## 1. 部署形态与前置条件

编排文件 [`docker-compose.yml`](docker-compose.yml) 会拉起四个容器：

| 容器 | 作用 | 端口策略 |
| --- | --- | --- |
| `tracequest-backend` | 后端应用（Spring Boot，监听 8080） | 发布到宿主机 `${TQ_SERVER_PORT}`（默认 8080） |
| `tracequest-mysql` | MySQL 8.0，唯一数据源 | 仅容器网络内可达，不发布端口 |
| `tracequest-redis` | 限流计数、令牌黑名单、缓存 | 仅容器网络内可达，不发布端口 |
| `tracequest-minio` | 附件与封面对象存储 | 发布 9000（S3 API）与 9001（控制台） |

建议配置：2 核 CPU / 4 GB 内存 / 20 GB 以上空闲磁盘（首次构建镜像需额外 3～5 GB，拉取 Maven 依赖与 JDK 25 基础镜像约 1.5～2 GB）。部署过程需要外网访问 Docker Hub、Maven 中央仓库与 apt 源。

> 单个应用实例已足够承载赛事使用；若要横向扩容或多副本，请改用 Kubernetes（见第 11 节）。

## 2. 安装 Docker Engine 与 Compose 插件

Ubuntu 20.04 软件源里的 `docker.io` 版本较旧，建议使用 Docker 官方源安装：

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

安装后确认版本，并把当前用户加入 `docker` 组（重新登录后生效，之后可省略 `sudo`）：

```bash
sudo docker compose version        # 需要 v2.x（支持 depends_on 健康检查条件）
sudo docker run --rm hello-world
sudo usermod -aG docker "$USER"
```

注意事项：

- 如果之前用 `snap` 装过 Docker（`snap list docker` 有输出），先 `sudo snap remove docker`，避免两个 CLI 互相干扰。
- 若宿主机访问 Docker Hub 较慢，可配置镜像加速器：写入 `/etc/docker/daemon.json` 的 `registry-mirrors` 字段（地址请换成你自己可用的加速器），然后 `sudo systemctl restart docker`。

## 3. 获取代码

```bash
sudo mkdir -p /opt/tracequest
sudo chown "$USER":"$USER" /opt/tracequest
git clone https://github.com/QAQxianyuQAQ/0xtrace-backend.git /opt/tracequest
cd /opt/tracequest
```

仓库当前是私有仓库，`git clone` 会要求鉴权，可用个人访问令牌（PAT）：
`git clone https://<GitHub用户名>:<PAT>@github.com/QAQxianyuQAQ/0xtrace-backend.git /opt/tracequest`。
这种写法会把令牌写进 `.git/config`，克隆完成后建议执行
`git remote set-url origin https://github.com/QAQxianyuQAQ/0xtrace-backend.git` 清除。
也可以直接把本地目录用 `rsync`/`scp` 上传到 `/opt/tracequest`，效果相同。

## 4. 准备环境变量文件

所有凭据只从 `.env` 读取，仓库内不保存任何真实密钥：

```bash
cd /opt/tracequest
cp .env.deploy.example .env
chmod 600 .env

openssl rand -base64 48   # 执行 4 次，分别填入 TQ_JWT_SECRET、TQ_ANSWER_SECRET、TQ_DB_PASSWORD、TQ_MINIO_SECRET_KEY
openssl rand -base64 24   # 执行 2 次，分别填入 TQ_DB_ROOT_PASSWORD、TQ_REDIS_PASSWORD
```

必填项一览：

| 变量 | 说明 |
| --- | --- |
| `TQ_DB_PASSWORD` / `TQ_DB_ROOT_PASSWORD` | MySQL 应用账号与 root 口令 |
| `TQ_REDIS_PASSWORD` | Redis 口令（不可为空） |
| `TQ_MINIO_ACCESS_KEY` / `TQ_MINIO_SECRET_KEY` | MinIO 凭据，其中 secret 至少 8 位（MinIO 自身限制） |
| `TQ_MINIO_ENDPOINT` | **必须是浏览器可直接访问的地址**，如 `http://<服务器IP>:9000` 或 `https://minio.example.com`；附件下载会 302 跳转到用它生成的预签名地址 |
| `TQ_JWT_SECRET` / `TQ_ANSWER_SECRET` | JWT 签名密钥与题目答案 AES-GCM 加密密钥，≥32 字节随机值 |
| `TQ_ADMIN_PASSWORD` | 管理员口令。**即使关闭初始化装配，生产强校验也会校验它**，请填 ≥16 位强口令 |

另外确认宿主机端口未被占用：

```bash
ss -lntp | grep -E ':8080|:9000|:9001' || echo "端口空闲"
```

## 5. 首次启动（自动建表 + 创建管理员）

应用启动时不会自动改表，建表脚本由 MySQL 容器**首次初始化数据卷时**执行
（按顺序挂载 [`V1__init_schema.sql`](src/main/resources/sql/V1__init_schema.sql) 与
[`V2__event_scoped_teams.sql`](src/main/resources/sql/V2__event_scoped_teams.sql)）。
管理员账号通过「只开启一次的初始化数据装配」创建，这也是项目推荐的首次初始化方式：

```bash
cd /opt/tracequest
# 临时允许初始化数据装配（创建默认管理员与一条 open 状态的赛事记录）
sed -i 's/^TQ_BOOTSTRAP_ENABLED=.*/TQ_BOOTSTRAP_ENABLED=true/; s/^TQ_STRICT_SECRET_CHECK=.*/TQ_STRICT_SECRET_CHECK=false/' .env

# 首次构建镜像并启动全部容器（构建约 5～10 分钟）
docker compose up -d --build

docker compose ps
docker compose logs -f app
```

在日志中看到以下两行即表示初始化完成：

```
已创建默认管理员账号: admin
已创建默认赛事: TraceQuest Season 01
```

> 为什么要临时关掉强校验：`TQ_STRICT_SECRET_CHECK=true` 时，只要初始化装配仍为开启状态，应用会按“生产环境”标准直接拒绝启动（这是设计上的保护，避免把开发默认账号带上线）。

## 6. 验证部署

```bash
# 1) 存活探针（该接口公开、无需鉴权）
curl -s http://127.0.0.1:8080/api/v1/health
# => 形如 {"ok":true,"service":"tracequest-backend"}

# 2) 登录（写请求必须带 Origin 头，且其主机名要与请求 Host 一致，否则返回 403 ORIGIN_REJECTED）
curl -i -c /tmp/tq-cookie.txt -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' -H 'Origin: http://127.0.0.1:8080' \
  -d '{"username":"admin","password":"<TQ_ADMIN_PASSWORD>"}'

# 3) 用登录得到的 Cookie 访问后台接口
curl -s -b /tmp/tq-cookie.txt -H 'Origin: http://127.0.0.1:8080' \
  http://127.0.0.1:8080/api/v1/admin/dashboard

# 4) 确认 MinIO 存储桶已创建
docker compose logs app | grep "存储桶已就绪"
```

## 7. 切换到正式运行模式

管理员创建完成后，关闭初始化装配并打开生产强校验：

```bash
cd /opt/tracequest
sed -i 's/^TQ_BOOTSTRAP_ENABLED=.*/TQ_BOOTSTRAP_ENABLED=false/; s/^TQ_STRICT_SECRET_CHECK=.*/TQ_STRICT_SECRET_CHECK=true/' .env
docker compose up -d app      # 只重建并重启应用容器
docker compose logs -f app
```

强校验开启后，密钥缺失、长度不足 16 位、命中示例值清单，或初始化装配仍为 `true`，都会导致启动失败（fail-fast）。这正是上线前的最后一道体检。

## 8. 配置反向代理与 HTTPS（推荐）

两类场景需要 HTTPS：浏览器从 `https` 页面访问、以及 Cookie 需要 `Secure` 属性。
下面是 Nginx + Let's Encrypt 的做法，同时反代应用与 MinIO（MinIO 走独立子域，不要用路径前缀，否则预签名地址会失效）。

```bash
sudo apt-get install -y nginx certbot python3-certbot-nginx
```

```nginx
# /etc/nginx/sites-available/tracequest
server {
    listen 80;
    server_name tq.example.com minio.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name tq.example.com;

    ssl_certificate     /etc/letsencrypt/live/tq.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/tq.example.com/privkey.pem;

    client_max_body_size 64m;          # 需大于应用侧上传上限（spring.servlet.multipart 为 50MB/60MB）

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        # 必须透传浏览器原始 Host：同源校验比较的是 Origin 主机名与 Host 头
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

server {
    listen 443 ssl http2;
    server_name minio.example.com;

    ssl_certificate     /etc/letsencrypt/live/minio.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/minio.example.com/privkey.pem;

    client_max_body_size 64m;
    ignore_invalid_headers off;        # 透传 MinIO 的签名请求头，避免签名校验失败

    location / {
        proxy_pass http://127.0.0.1:9000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/tracequest /etc/nginx/sites-enabled/tracequest
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d tq.example.com -d minio.example.com
```

同时修改 `.env` 并重启应用：

```bash
sed -i 's#^TQ_MINIO_ENDPOINT=.*#TQ_MINIO_ENDPOINT=https://minio.example.com#' .env
sed -i 's/^TQ_COOKIE_SECURE=.*/TQ_COOKIE_SECURE=true/' .env
docker compose up -d app
```

可选补丁：`TQ_MINIO_ENDPOINT` 用了公网域名后，应用容器需要能解析并回连该域名。若云主机不支持从容器回环访问自身公网 IP，可在 `docker-compose.yml` 的 `app` 服务下加一行，让容器把该域名指向宿主机（需要 Docker 20.10+）：

```yaml
    extra_hosts:
      - "minio.example.com:host-gateway"
```

防火墙只放行必要端口（8080/9000/9001 建议仅对反向代理或内网开放）：

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80,443/tcp
sudo ufw enable
```

前端如果部署在**另一个域名**（例如 `app.example.com` 调 `tq.example.com`），因为会话走 `SameSite=Strict` Cookie，需要把前端也放到同一站点下，或在 `.env` 中把前端来源加入白名单：

```bash
TRACEQUEST_SECURITY_ALLOWED_ORIGINS=https://app.example.com
```

## 9. 日常运维

```bash
cd /opt/tracequest

# 状态与日志
docker compose ps
docker compose logs -f app --tail=200

# 重启应用
docker compose restart app

# 升级：拉取新代码后只重建应用镜像（数据卷不动）
git pull
docker compose build app
docker compose up -d app
docker image prune -f

# 备份 MySQL（容器内已注入 MYSQL_ROOT_PASSWORD / MYSQL_DATABASE）
docker compose exec -T mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --default-character-set=utf8mb4 "$MYSQL_DATABASE"' > "backup-$(date +%F).sql"

# 恢复 MySQL
docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < backup-2026-10-14.sql

# 备份 MinIO 对象数据（卷名前缀取自 Compose 项目名，默认等于部署目录名）
minio_vol=$(docker volume ls --format '{{.Name}}' | grep '_minio-data$' | head -1)
docker run --rm -v "$minio_vol":/data:ro -v "$PWD:/backup" alpine \
  tar czf "/backup/minio-$(date +%F).tar.gz" -C /data .
```

别忘了把 `.env` 也备份到安全位置（它保存了全部密钥），否则恢复数据后旧密文无法解密。

> Compose 项目名默认取部署目录名（本文按 `/opt/tracequest` 部署，命名卷即为 `tracequest_mysql-data` 等）。若在同一台机器上部署多套环境，可在每条命令前加 `COMPOSE_PROJECT_NAME=xxx`，或在 `.env` 中设置同名变量来固定项目名。

## 10. 常见问题排查

| 现象 | 原因与处理 |
| --- | --- |
| 应用反复重启，日志出现 `检测到不安全的密钥配置，已拒绝启动` | 密钥缺失/过短/命中示例值清单，或 `TQ_BOOTSTRAP_ENABLED=true` 与强校验同时开启。按日志列出的问题逐项修正 `.env` |
| 日志报 `Table 'tracequest.users' doesn't exist` | MySQL 数据卷不是空的，初始化脚本会被跳过。按 V1、V2 顺序手动导入 DDL；已有 V1 数据只执行 V2 |
| 登录接口返回 200，但后续请求 401 | Cookie 没被浏览器保存：HTTP 环境需 `TQ_COOKIE_SECURE=false`，HTTPS 环境需 `true`；两者不匹配时会话会直接丢失 |
| 写请求返回 403，响应体含 `ORIGIN_REJECTED` | 请求未带 `Origin` 头，或前后端不同源。反向代理要透传 `Host $host`；跨域前端需配置 `TRACEQUEST_SECURITY_ALLOWED_ORIGINS` |
| 附件下载 302 到一个打不开的地址 | `TQ_MINIO_ENDPOINT` 用了容器内部地址（如 `http://minio:9000`）。它必须是浏览器可达地址，改完重启 app 容器 |
| 日志出现 `MinIO 初始化失败，文件上传能力暂不可用` | 首次启动时 MinIO 尚未就绪。`docker compose restart app` 即可，应用会重新建桶（`TQ_MINIO_INIT=true`） |
| 登录频繁返回 429 | 限流生效：登录接口默认每 IP 每分钟 10 次，失败 8 次后进入 15 分钟窗口（见 `application.yml` 的 `tracequest.rate-limit`） |
| `docker compose up` 报变量未设置 | `.env` 里对应项为空。`${VAR:?}` 写法会在变量缺失时直接报错，这是刻意设计 |

## 11. 附：其它部署方式

不用 Compose，直接跑单容器（MySQL/Redis/MinIO 需自行准备）：

```bash
docker build -t tracequest-backend:1.0.0 .
docker run -d --name tracequest-backend -p 8080:8080 \
  -e TQ_DB_HOST=<mysql地址> -e TQ_DB_USERNAME=tracequest -e TQ_DB_PASSWORD=<口令> -e TQ_DB_NAME=tracequest \
  -e TQ_REDIS_HOST=<redis地址> -e TQ_REDIS_PASSWORD=<口令> \
  -e TQ_MINIO_ENDPOINT=https://minio.example.com -e TQ_MINIO_ACCESS_KEY=<key> -e TQ_MINIO_SECRET_KEY=<secret> \
  -e TQ_JWT_SECRET=<随机值> -e TQ_ANSWER_SECRET=<随机值> \
  -e TQ_ADMIN_PASSWORD=<强口令> -e TQ_BOOTSTRAP_ENABLED=false -e TQ_STRICT_SECRET_CHECK=true \
  -e TQ_COOKIE_SECURE=true \
  tracequest-backend:1.0.0
```

Kubernetes：仓库 [`k8s/`](k8s) 目录已提供 ConfigMap、Secret 示例、Deployment（探针指向 `/api/v1/health`）、Service 与 HPA，镜像由 [`.github/workflows/release.yml`](.github/workflows/release.yml) 在打 `v*` 标签时推送到 GHCR。集群部署前请确认 Secret 中已补齐 `TQ_JWT_SECRET`、`TQ_ANSWER_SECRET`、`TQ_ADMIN_PASSWORD` 等全部密钥。

## 12. 复用已有的 MySQL / Redis / MinIO

如果宿主机上已经有其它 Compose 项目维护的 MySQL、Redis、MinIO，**不要用 [`docker-compose.yml`](docker-compose.yml) 再起一套**——那份编排是给「完全从零开始」准备的。复用已有中间件请改用 [`docker-compose.app-only.yml`](docker-compose.app-only.yml)，它只发布应用容器。

### 12.1 什么情况复用、什么情况另起一套

| 情况 | 建议 |
| --- | --- |
| 已有实例就是为本平台准备的，或希望数据只有一份、统一备份 | **复用**，用 `docker-compose.app-only.yml` |
| 已有实例属于其它业务的生产库，不想混数据、怕相互影响 | 另起一套，用 `docker-compose.yml`（配合改端口） |
| 已有 MySQL 不是 8.x，或不是 utf8mb4 | 另起一套 |
| 赛事期间要求资源隔离（限流、慢查询互不干扰） | 另起一套 |

复用的主要收益是省心：不用再管第二份数据库备份、不用重复占端口；代价是资源与故障域和别的业务共享。

### 12.2 第一步：确认应用能否连上已有中间件

```bash
docker ps --format 'table {{.Names}}\t{{.Ports}}'
ss -lntp | grep -E ':3306|:6379|:9000'
```

| 已有中间件的暴露方式 | `.env` 里怎么填 |
| --- | --- |
| 端口发布到 `0.0.0.0`（`0.0.0.0:3306->3306/tcp`） | `TQ_DB_HOST=<宿主机内网IP>`（Redis 同理填 `TQ_REDIS_HOST`） |
| 只发布到 `127.0.0.1`（`127.0.0.1:3306->3306/tcp`） | `TQ_DB_HOST=host.docker.internal`（app-only 编排已配好 `extra_hosts` 映射） |
| 完全没发布端口，只在对方容器网络内 | 加入对方网络：打开 app-only 文件底部的 `networks` 注释，把名称换成 `docker network ls` 里的网络名，`TQ_DB_HOST` 填对方 compose 的服务名（如 `mysql`） |

同时把 `TQ_MINIO_ENDPOINT` 指向已有 MinIO 的**对外**地址（预签名地址就是用它生成的）。

### 12.3 第二步：把建表脚本导入已有 MySQL

应用不会自动改表（`spring.sql.init.mode=never`），复用别人的 MySQL 时要自己导入一次：

```bash
cd /opt/tracequest

# 1) 建库与专用账号（utf8mb4；账号主机用 % 以允许来自容器的连接）
docker exec -it <已有mysql容器名> mysql -uroot -p -e "
CREATE DATABASE IF NOT EXISTS tracequest DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'tracequest'@'%' IDENTIFIED BY '<TQ_DB_PASSWORD>';
GRANT ALL PRIVILEGES ON tracequest.* TO 'tracequest'@'%';
FLUSH PRIVILEGES;"

# 2) 导入 DDL
docker exec -i <已有mysql容器名> sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" tracequest' \
  < src/main/resources/sql/V1__init_schema.sql
docker exec -i <已有mysql容器名> sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" tracequest' \
  < src/main/resources/sql/V2__event_scoped_teams.sql
```

### 12.4 第三步：启动应用

```bash
cd /opt/tracequest
cp .env.deploy.example .env      # 已有 .env 则只补 TQ_DB_HOST / TQ_REDIS_HOST 等几项
chmod 600 .env

# 首次启动仍需创建管理员：临时 TQ_BOOTSTRAP_ENABLED=true + TQ_STRICT_SECRET_CHECK=false
docker compose -f docker-compose.app-only.yml up -d --build
docker compose -f docker-compose.app-only.yml logs -f app
```

创建完管理员后再把这两项改回 `false` / `true`，执行 `docker compose -f docker-compose.app-only.yml up -d app`。
后续的验证、反向代理、升级步骤与第 6～9 节完全一致，只把 `docker compose` 换成 `docker compose -f docker-compose.app-only.yml`。

### 12.5 复用 Redis 的注意点

- 应用的键统一带 `tq:` 前缀（见 `util/CacheKeys`），与同实例上的其它应用不会冲突，共享 DB 0 是安全的。
- 想进一步隔离可指定库序号：在 compose 中给 app 增加 `SPRING_DATA_REDIS_DATABASE=1`（取值 0～15，**不要留空**，空值会导致绑定失败）。
- 共享实例请确认 `maxmemory-policy` 不是 `allkeys-lru` / `allkeys-random`，并保证内存有余量：应用把**解题计分的分布式锁**与**登出令牌黑名单**放在 Redis 里，键被提前驱逐会导致锁失效或已登出的令牌复活。对这两个路径要求高的场景，建议单独实例。

### 12.6 复用 MinIO 的注意点

- 用**独立桶**（`TQ_MINIO_BUCKET`，默认 `tracequest`）和**独立凭据**，不要把已有实例的 root 凭据交给应用。
- `TQ_MINIO_ENDPOINT` 必须与浏览器访问 MinIO 时使用的 Host 完全一致：MinIO 的 SigV4 签名覆盖 Host 头，地址不一致时预签名下载会返回 403 `SignatureDoesNotMatch`。
- 凭据若没有建桶权限，请先手动建桶或设 `TQ_MINIO_INIT=false`；有权限时保持 `true`，应用启动会自动 `ensureBucket`。

### 12.7 复用模式下的备份

数据都在对方的实例里，备份沿用对方既有策略即可；本仓库这一侧只需要备份 `.env`（保存着 JWT/答案加密密钥，丢失后题库密文无法解密）。

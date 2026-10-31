# TraceQuest 后端（/api/v1）

CTF 竞赛平台后端，按 [`API.md`](API.md) 第 8 章「正式后端 REST API」契约实现：选手端赛事/题目/提交/榜单、管理员后台（赛事、题目、账号、附件与封面、位置二维码、审计）等全部 `/api/v1` 路由。

## 技术栈

| 项 | 取值 |
| --- | --- |
| 语言/构建 | Java 25、Maven 3.9+ |
| 框架 | Spring Boot 3.5（Web、Security、Validation、AOP） |
| 持久化 | MySQL 8.x + MyBatis-Plus 3.5.7 |
| 缓存 | Redis（挑战载荷、榜单、限流、令牌黑名单） |
| 对象存储 | MinIO（题目附件与封面） |
| 认证 | Spring Security + JWT（HS256，令牌放在 HttpOnly Cookie） |

## 快速开始

```bash
# 1. 构建（自动校验 Java 25 工具链）
./mvnw -DskipTests package

# 2. 运行单元测试（surefire）
./mvnw test

# 3. 运行集成测试（H2 内存库，不依赖 MySQL/Redis/MinIO）
./mvnw test -Dtest.excluded.groups=none

# 4. 本地启动：dev profile 内置本地开发默认值（MySQL root/root、MinIO minioadmin 等）
java -jar target/backend-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev

# 5. 生产启动：全部凭据由环境变量注入（缺失即启动失败，避免弱口令上线）
export TQ_DB_USERNAME=... TQ_DB_PASSWORD=... TQ_JWT_SECRET=... TQ_ANSWER_SECRET=...
java -jar target/backend-1.0.0-SNAPSHOT.jar --spring.profiles.active=prod
```

> `mvnw` 与 `scripts/*.sh` 均为 Bash 脚本。若直接执行提示 `权限不够`，改用 `bash` 调用即可：
> `bash mvnw -DskipTests package`、`bash scripts/scan-secrets.sh`；也可先 `chmod +x mvnw scripts/*.sh .githooks/pre-commit`。

数据库脚本：新库按顺序执行 [`V1__init_schema.sql`](src/main/resources/sql/V1__init_schema.sql)、[`V2__event_scoped_teams.sql`](src/main/resources/sql/V2__event_scoped_teams.sql)、[`V3__challenge_container_runtime.sql`](src/main/resources/sql/V3__challenge_container_runtime.sql) 与 [`V4__container_ttl_and_team_limit.sql`](src/main/resources/sql/V4__container_ttl_and_team_limit.sql)；已有库按顺序补齐尚未执行的迁移。测试环境使用 [`schema-h2.sql`](src/main/resources/sql/schema-h2.sql)（由 `application-test.yml` 自动执行）。

dev profile 首次启动（`tracequest.bootstrap.enabled=true`）会创建默认管理员 `admin / admin12345` 与一条状态为 `open` 的赛事记录；生产环境必须关闭该开关（启动期密钥体检会强制拦截）并通过运维流程创建账号。

## 安全与密钥管理

- **仓库零凭据**：`application.yml` 不内置任何密码或密钥，敏感项一律通过环境变量注入（`TQ_JWT_SECRET`、`TQ_ANSWER_SECRET`、`TQ_DB_PASSWORD`、`TQ_MINIO_SECRET_KEY` 等），缺失时启动失败（fail-fast）。样例见 [`.env.example`](.env.example)。
- **仅 dev/test profile 持有非生产默认值**：`application-dev.yml` 与 `application-test.yml` 中的取值仅供本地开发与自动化测试，`scripts/scan-secrets.sh` 是唯一放行它们的白名单。
- **启动期密钥体检**：`config/SecretHygieneValidator` 在容器启动前校验签名密钥、答案加密密钥与初始管理员口令；`prod` profile（或 `TQ_STRICT_SECRET_CHECK=true`）下命中弱密钥/示例值即拒绝启动，并强制 `tracequest.bootstrap.enabled=false`。
- **提交前扫描**：`scripts/scan-secrets.sh` 检查 GitHub 令牌、AWS AccessKey、私钥头与“关键字=字面量”赋值；执行一次 `scripts/setup-git-hooks.sh` 即可启用 `.githooks/pre-commit`，CI 也会在 lint 阶段运行同一脚本。
- **Cookie 与 CSRF**：会话令牌仅存于 `HttpOnly; SameSite=Strict` Cookie，写请求强制同源校验；生产必须保持 `TQ_COOKIE_SECURE=true`。
- **答案与凭证**：题目答案以 AES-GCM 密文落库，二维码令牌只存 SHA-256 哈希，MinIO 下载走短效预签名地址。

## 关键环境变量

| 变量 | 说明 |
| --- | --- |
| `TQ_DB_HOST` / `TQ_DB_PORT` / `TQ_DB_NAME` / `TQ_DB_USERNAME` / `TQ_DB_PASSWORD` | MySQL 连接参数 |
| `TQ_REDIS_HOST` / `TQ_REDIS_PORT` / `TQ_REDIS_PASSWORD` | Redis 连接参数 |
| `TQ_MINIO_ENDPOINT` / `TQ_MINIO_ACCESS_KEY` / `TQ_MINIO_SECRET_KEY` / `TQ_MINIO_BUCKET` | MinIO 连接参数与桶名 |
| `TQ_JWT_SECRET` | JWT 签名密钥（生产必须覆盖为高强度随机值） |
| `TQ_ANSWER_SECRET` | 题目答案 AES-GCM 加密密钥（生产必须覆盖） |
| `TQ_SESSION_COOKIE` / `TQ_COOKIE_SECURE` / `TQ_COOKIE_SAME_SITE` | 会话 Cookie 名称与属性（生产要求 `Secure=true`） |
| `TQ_ENFORCE_SAME_ORIGIN` | 写请求同源校验开关（默认开启） |
| `TQ_BOOTSTRAP_ENABLED` | 是否创建默认管理员与赛事（生产建议 `false`） |
| `TQ_RUNTIME_ENABLED` | 是否启用单机 Docker 题目容器 |
| `TQ_RUNTIME_NETWORK` | 队伍容器内部网络，默认 `tracequest-runtime` |
| `TQ_PUBLIC_WSS_URL` | frp 443 暴露的公网 WSS 地址 |
| `TQ_DOCKER_GID` | 宿主机 Docker Socket 所属组 ID |

## 接口一览（`/api/v1`）

认证与个人资料

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| POST | `/auth/login` | public（下发 HttpOnly Cookie） |
| POST | `/auth/logout` | authenticated |
| GET | `/auth/me` | authenticated |
| PATCH | `/me/profile` | contestant |
| GET | `/me/progress` | contestant |
| GET | `/me/submissions` | contestant |
| GET | `/me/submissions/{submissionId}` | contestant |
| POST/GET | `/events/{eventId}/teams`、`/events/{eventId}/teams/join`、`/events/{eventId}/teams/me` | contestant（队伍关系仅对单赛事生效） |

赛事、题目、提交与排名

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/event` | public |
| GET | `/challenges`、`/challenges/{code}` | public/contestant（不含 `answer`） |
| POST | `/events/{eventId}/challenges/{code}/runtime/instances` | contestant（启动或复用本队容器） |
| GET/DELETE | `/events/{eventId}/challenges/{code}/runtime/instances/me` | contestant |
| POST | `/events/{eventId}/challenges/{code}/runtime/connection-ticket` | contestant（签发一次性 WSS 票据） |
| POST | `/events/{eventId}/challenges/{code}/runtime/instances/me/extend` | contestant（剩余 ≤30 分钟时延长 1 小时） |
| GET | `/challenge-connections?ticket=...` | 一次性票据握手，之后为二进制 WebSocket |
| GET | `/leaderboard?eventId=...` | public/contestant（仅队伍排名，登录返回 `isMe`） |
| GET | `/files/{fileId}` | contestant（302 跳转 MinIO 预签名地址） |
| POST | `/challenges/{code}/submissions` | contestant（支持 `Idempotency-Key`） |
| POST | `/location-verifications` | contestant（支持 `Idempotency-Key`） |
| GET | `/health` | public（容器健康检查） |

后台管理

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/admin/dashboard`、`/admin/event`、`/admin/audit-log` | admin |
| POST/PATCH | `/admin/event` | admin（创建赛事必须设置 `maxTeamSize`） |
| GET/POST | `/admin/challenges` | admin |
| POST | `/admin/events/{eventId}/challenges` | admin（题目独占指定赛事） |
| GET/PATCH/DELETE | `/admin/challenges/{code}` | admin |
| POST | `/admin/challenges/{code}/publish`、`pause`、`resume` | admin |
| POST/DELETE | `/admin/challenges/{code}/location-qr` | admin |
| GET | `/admin/challenges/{code}/location-qr/image` | admin（当前返回 501，见下） |
| POST/GET | `/admin/challenges/{code}/attachments` | admin（字段名 `files` 或 `file`） |
| DELETE | `/admin/challenges/{code}/attachments/{fileId}` | admin |
| POST/GET/DELETE | `/admin/challenges/{code}/cover` | admin |
| GET/POST | `/admin/contestants` | admin |
| GET/PATCH/DELETE | `/admin/contestants/{userId}` | admin |
| PUT | `/admin/contestants/{userId}/password` | admin |
| POST | `/admin/contestants/{userId}/disable`、`enable` | admin |
| POST/GET | `/admin/contestants/import`、`export` | admin（CSV，导出不含密码） |
| GET | `/admin/leaderboard`、`/admin/leaderboard/{teamId}`、`/admin/score-events` | admin（`teamId` 详情含全部成员与分页提交日志） |
| POST | `/admin/score-adjustments` | admin（必须填写原因） |

统一错误响应（与 8.1 一致）：

```json
{
  "error": {"code": "VALIDATION_ERROR", "message": "请求字段无效", "details": [{"field": "answer", "reason": "required"}]},
  "requestId": "req_01..."
}
```

## 工程结构

```
com.qaqxianyuqaq.backend
├── config/                       # Spring、Security、MyBatis-Plus、MinIO、Jackson、初始化数据
├── controller/                   # MVC 的 C 层，仅做参数校验与响应封装，按功能分目录
│   ├── auth/    me/    event/    challenge/    leaderboard/
│   ├── contestant/   admin/      file/         health/
├── service/                      # 业务接口，按功能分目录；实现放在同名 impl 子包
│   ├── auth/AuthService + auth/impl/AuthServiceImpl
│   ├── challenge/{ChallengeService, FileStorageService, LocationService} + impl/
│   ├── submission/{SubmissionService, ScoringService} + impl/
│   ├── cache/{CacheService, SessionService, RateLimitService, IdempotencyService} + impl/
│   └── profile/  event/  leaderboard/  contestant/  audit/  admin/
├── domain/                       # entity / enums / dto（按模块分目录）/ vo（按模块分目录）
├── repository/                   # MyBatis-Plus Mapper，按功能分目录
│   └── user/  event/  challenge/  submission/  leaderboard/  audit/  common/
├── aspect/                       # 操作日志、审计、限流、幂等、分布式锁
├── util/                         # CacheKeys、IdGenerator、DigestUtil、CryptoUtil、MinioUtil、CsvUtil 等
├── exception/                    # 错误码、业务异常、全局异常处理器
└── security/                     # JWT 组件、认证过滤器、同源过滤器、权限断言、答案加解密
```

controller、service、repository 三层与 dto/vo 一样按功能模块分目录，测试包与生产包结构一一对应（例如
`service/submission/impl/ScoringServiceImplTest`）。

资源目录：`src/main/resources/sql/` 存放全部 DDL；`application.yml` 通过环境变量覆盖敏感配置。

## 关键实现说明

- **队伍与计分**：队伍和成员关系都带 `event_id`，同一账号可为不同赛事加入不同队伍；提交时必须属于题目所属赛事的队伍，`solves(subject_key, challenge_id)` 按队伍主体唯一计分，榜单只提供队伍维度。
- **Docker 题目**：每个队伍同时只能运行一个容器，启动时注入 `flag头{BCrypt(队伍名称)}`；容器内部端口从镜像 `EXPOSE` 自动解析，宿主端口由 Docker 随机分配。容器默认存活 1 小时，仅在剩余不超过 30 分钟时允许再延长 1 小时；选手提交非本队 Flag 时记录 WARN 并返回 `403 FLAG_OWNER_MISMATCH`，内容为“串通flag并不是好习惯”。
- **公网连接**：整套系统运行在内网，REST、WSS 和 MinIO 全部由本机 frpc 主动穿透到公网 frps。443 用于 WSS，8080 用于 REST，9000/9001 用于 MinIO。
- **内部数据服务**：MySQL 和 Redis 只保留在内部 Docker 网络，不配置 frp 代理。MinIO 预签名地址必须与通过 frpc 暴露的公网 Host 和端口完全一致。frp 示例见 [`deploy/frp/frpc.toml.example`](deploy/frp/frpc.toml.example)。
- **幂等**：`Idempotency-Key` 请求头由 `IdempotentAspect` 统一处理，命中同键同内容时回放首次响应，同键不同内容返回 `409 IDEMPOTENCY_CONFLICT`；请求体中的 `requestId` 亦可用于重放定位。
- **并发控制**：`solves` 唯一约束 + 基于 Redis 的分布式锁（`DistributedLockAspect`）串行化同一账号的解题写路径。
- **限流**：登录、提交、位置核验与后台写操作通过 `RateLimitAspect` 与 Redis 计数器限流，超限返回 `429` 与 `Retry-After`。
- **审计**：管理端写操作通过 `@AuditAction` + `AuditAspect` 记录操作者、对象、请求 ID 与响应快照，`GET /api/v1/admin/audit-log` 可分页检索。
- **会话失效**：账号停用或重置密码时自增 `users.token_version`，旧 JWT 立即失效；登出令牌进入 Redis 黑名单。
- **同源校验**：因会话走 Cookie，写请求必须携带与 `Host` 匹配的 `Origin`（`SameOriginFilter`），等价于契约 8.1 的 CSRF 防护，测试环境可通过 `tracequest.security.enforce-same-origin=false` 关闭。
- **时间类型**：全站使用 `LocalDateTime` 表示 UTC 墙钟时间（数据库 `DATETIME`/`TIMESTAMP` 直存），JSON 统一序列化为 `2026-10-01T06:24:36Z`；反序列化兼容 `+08:00` 这类带偏移量写法并自动换算为 UTC。
- **缓存降级**：Redis 不可用时 `CacheService` 自动降级为进程内缓存，保证单实例演示环境可用（多副本生产部署必须提供 Redis）。

## 与契约/工程约定的已知差异

1. `GET /api/v1/admin/challenges/{code}/location-qr/image` 返回 `501 NOT_IMPLEMENTED`：后端未引入二维码渲染库，二维码 PNG 仍由管理端浏览器依据 `payload` 绘制（与 `API.md` 8.5 的现状一致）。引入渲染库后可直接补齐该接口。
2. 错误响应采用第 8 章定义的 `{error:{...},requestId}` 结构（而非 `AGENTS.md` 第 9 节的 `{code,message,data,traceId}`），因为本次任务以第 8 章契约为准；`requestId` 同时写入 `X-Request-Id` 响应头与日志 MDC。
3. 集成测试使用 H2（MySQL 兼容模式）而非 Testcontainers：当前构建环境为离线环境，无法拉取测试容器镜像与模块。CI 具备网络后可将 `TraceQuestApiIntegrationTest` 切换为 Testcontainers（MySQL/Redis/MinIO）而无需改动业务代码。
4. 榜单缓存为「按分页结果的短时 JSON 缓存」（30s TTL + 写路径失效），未使用 Redis 有序集合，以严格保证总分 → 解题数 → 达标时间的三级排序语义。
5. `POST /admin/contestants` 的 `teamId`/`teamName` 均限定到当前赛事；选手自助组队使用 `/api/v1/events/{eventId}/teams`，队伍标识码用于同赛事加入。

## 部署

- 单机 Docker Compose（Ubuntu 20.04 等）：见 [`DEPLOY.md`](DEPLOY.md)；自带全部中间件用 [`docker-compose.yml`](docker-compose.yml)，复用已有的 MySQL/Redis/MinIO 用 [`docker-compose.app-only.yml`](docker-compose.app-only.yml)，变量模板为 [`.env.deploy.example`](.env.deploy.example)。
- 镜像：多阶段构建，运行阶段基于 slim JRE 25，非 root 用户（见 [`Dockerfile`](Dockerfile)）。
- 支持的部署形态只有单台 Docker Engine + Docker Compose；应用接入 `tracequest-runtime` 内部网络，并通过受限 Docker Socket 管理队伍容器。
- 流水线：
  - GitHub Actions：[`.github/workflows/ci.yml`](.github/workflows/ci.yml) 负责密钥扫描 → 编译 → 单元测试 → 集成测试 → 镜像构建校验；[`.github/workflows/release.yml`](.github/workflows/release.yml) 在打 tag（`v*`）时推送镜像到 GHCR。
  - GitLab CI：[`.gitlab-ci.yml`](.gitlab-ci.yml) 提供等价的构建、测试与镜像推送流水线；生产部署由受控运维流程执行 Docker Compose。

## 测试

```bash
./mvnw test                                        # 单元测试：计分、队伍、Flag 归属、CSV 与同源过滤器
./mvnw test -Dtest.excluded.groups=none            # 追加集成测试：TraceQuestApiIntegrationTest
```

`TraceQuestApiIntegrationTest` 覆盖：管理员建题发布、赛事内 Docker 题目字段校验、赛事内队伍创建/标识码加入/人数上限、跨赛事独立队伍、队伍成员计分与后台提交日志、首解加分与重复提交幂等、`Idempotency-Key` 冲突、榜单 `isMe`、个人进度、后台总览与审计、位置二维码签发/核验/撤销、账号导入导出与停用后登录失败、401/403 权限边界。

## 推送到 GitHub

当前工作区的 `.git` 为只读挂载占位目录（无法 `git init`/`git commit`），因此仓库初始化与推送建议在可写目录中执行。
`scripts/prepare-pushable-copy.sh` 会把项目复制到可写目录、排除 `.git`/`target`、初始化 `main` 分支并完成首次提交：

```bash
# 1) 生成可推送副本（默认 /tmp/0xtrace-push，可传入其它目录）
bash scripts/prepare-pushable-copy.sh

# 2) 推送（令牌只作一次性请求头，不写入 .git/config）
cd /tmp/0xtrace-push
export GITHUB_TOKEN=<具备 repo 权限的令牌>
bash scripts/push-to-github.sh QAQxianyuQAQ/0xtrace-backend
```

若项目目录本身可写，也可以原地操作：

```bash
mv .git .git.placeholder            # 仅当存在只读占位目录时
bash scripts/setup-git-hooks.sh      # 启用提交前密钥扫描
git init -b main && git add -A
git commit -m "chore: 初始化 TraceQuest 后端"
git remote add origin https://github.com/QAQxianyuQAQ/0xtrace-backend.git
git push -u origin main             # 使用 PAT/SSH 完成鉴权
```

可用环境变量：`TQ_GITHUB_REPO`（默认 `QAQxianyuQAQ/0xtrace-backend`）、`TQ_GIT_NAME`/`TQ_GIT_EMAIL`（首次提交身份）。

常见问题：

| 现象 | 原因与处理 |
| --- | --- |
| `fatal: 不是 Git 仓库（或者任何父目录）：.git` | 目录中的 `.git` 是只读占位目录。执行 `bash scripts/prepare-pushable-copy.sh`，或先 `mv .git .git.placeholder` 再初始化 |
| `bash: scripts/xxx.sh: 权限不够` | 文件缺少可执行位，改用 `bash scripts/xxx.sh`，或 `chmod +x scripts/*.sh` |
| 提交时提示 `Please tell me who you are` | 未配置提交身份，脚本会自动写入兜底身份；也可用 `TQ_GIT_NAME`/`TQ_GIT_EMAIL` 指定 |
| `curl: (7) failed to open socket` | 当前终端无外网（例如仍在受限沙箱内），请在可联网的终端重跑推送 |

安全提示：任何在聊天、日志或工单中出现过的令牌都应视为已泄露，请立即在 GitHub 设置中吊销并重新签发。

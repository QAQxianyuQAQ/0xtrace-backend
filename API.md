> 当前稳定契约：2026-11-11 runtime-safety 后端 / security-hardening 前端与网关，见 [版本说明](docs/STABLE_RELEASE_2026-11-11.md)。本文按日期追加，末尾同日安全/判题/实例保留规则优先于旧版段落；“本地候选”是历史记录。

# TraceQuest 前端 API 文档

## 2026-11-05：南邮统一身份认证登录（本次新增）

**实现状态：** Java 后端与 Vue 入口已实现并启用于本地 Docker 预览。学校认证已完成本地测试；Docker 浏览器已检查真实本地登录、手机排版及拦截响应。学校测试使用本地 HTTP fixture，不使用真实学校账号。尚未进行学校生产联调、生产数据库迁移或远端部署。本节为现有认证契约的增量；历史原型章节不能覆盖本节或真实 Java Controller。

### 入口与会话

| 方法 / 路径 | 权限 | 行为 |
|---|---|---|
| GET /api/v1/auth/providers | 公开 | 返回 {"local":true,"njupt":false}；njupt 由服务器开关决定 |
| POST /api/v1/auth/njupt/login | 公开、受登录限流约束 | 接收学号与学校密码，经学校认证成功后签发本站 HttpOnly Cookie |
| POST /api/v1/auth/login | 公开、受登录限流约束 | 保留原赛事账号和管理员登录；njupt: 前缀的学校身份禁止走本地密码入口 |
| GET /api/v1/auth/me | 已登录 | 返回本站账号角色、停用状态和 `isSuperAdmin`；最高管理员仍以兼容值 `role=admin` 返回 |

学校登录 JSON 请求：

```json
{"username":"B25000000","password":"学校统一认证密码"}
```

username 为 1–32 个字母、数字、点、下划线或连字符，首位为字母或数字；服务端去除首尾空白并转大写。密码必须非空且不超过 256 个 Java UTF-16 字符，不 trim，不使用本地 BCrypt 的 72 字节上限。原本地请求契约保持不变。

成功响应沿用 LoginVO：userId、username、role=contestant、teamId（首次为 null）、expiresAt；响应体不包含 token 或密码。本站 HttpOnly Cookie 的 Secure / SameSite 继续由既有配置控制。学校 token 仅用于确认上游成功，不写 Cookie、不返回浏览器、不持久化；本站 JWT 使用本站密钥签发。

### 学校请求协议

- 后端仅请求固定 HTTPS https://i.njupt.edu.cn/ssoLogin/login，保留系统证书校验，不跟随重定向。
- checkKey 默认 1629428467008；key = IV = ASCII("iam" + checkKey)，默认恰为 16 字节。
- username / password 使用 AES-128-CBC / PKCS7 加密并输出小写 hex；captchaVerification=null、appId=common、mode=none；请求头 Tenant-Id: 0 并设置学校 Origin / Referer。
- 不获取或绕过滑块验证，不接受客户端提供任意上游地址或学校 token。学校要求二次验证时停止认证。
- 连接超时 5 秒、读取超时 10 秒、总调用超时 15 秒；响应上限 64 KiB。成功必须有布尔 success=true、整数 code=200、非空 result.token；若有 userInfo.username，必须与学号一致。
- 给定向量 B25041114 → d731f8de34f229e1ad49cde8eac4f8ac 已纳入回归。固定 AES 参数仅兼容学校协议，不能代替 HTTPS。

### 身份、权限与限流

- 新增 njupt_identities(subject,user_id) 独立关联；subject 为主键，user_id 唯一。
- 首次学校认证成功后，事务内原子创建 username=njupt:学号 的普通选手及关联，初始 role=CONTESTANT、status=ACTIVE。密码列仅存随机占位密码的 BCrypt hash。
- 后续按关联复用同一 userId，保留既有角色、队伍、积分、解题记录与停用状态；学校认证不会升级或降级本站角色，也不按同名学号接管已有本地账号。有效学校认证可为该关联的任意本站角色签发会话，接口权限仍完全由本站角色与服务端授权规则决定。
- 并发首登由唯一约束去重，失败事务整体回滚；冲突且无身份关联时返回 409，不接管预占用户名。
- 学校身份被重置本地密码后也不能走本地登录；已停用的关联账号不能通过学校认证登录。首次绑定仍只创建普通选手。
- 学校和本地登录共用按 IP 的 login 配额，密码失败累计到 njupt:学号。验证码、网络故障不计作密码错误；只有认证成功后才建立账号。

### 错误响应

沿用 {"error":{"code":"...","message":"...","details":[]},"requestId":"..."} 包络，敏感上游正文不透传。

| HTTP / code | 含义 |
|---|---|
| 400 VALIDATION_ERROR | 学号或密码字段无效 |
| 401 INVALID_CREDENTIALS | 学校拒绝学号/密码 |
| 403 FORBIDDEN | 学校认证入口未启用 |
| 403 ACCOUNT_DISABLED | 本站账号已停用 |
| 409 NJUPT_CAPTCHA_REQUIRED | 学校要求滑块；当前未集成 AJ-Captcha，停止登录 |
| 409 NJUPT_ACCOUNT_ACTION_REQUIRED | 先完成学校账号设置（code 511/512） |
| 409 CONFLICT | 身份关联冲突，须管理员处理 |
| 429 RATE_LIMITED | 本站限流、失败锁定或学校 429 |
| 503 NJUPT_AUTH_UNAVAILABLE | 学校超时、HTTP 错误、异常响应或身份不一致 |


### 2026-11-05：生产入口兼容 HTTP / HTTPS

生产 Nginx 使用前端仓库 deploy/nginx.public.conf：公网同一 TCP :8081 转发至前端容器 :8080，TLS 请求正常处理；明文请求通过 497 的命名 location 保留原方法和请求体，转交仅容器回环监听的 :8082。没有新增公网或宿主端口。

- HTTP GET /api/v1/auth/providers 固定返回 {"local":true,"njupt":false}；HTTPS 返回后端开关，启用时为 {"local":true,"njupt":true}。学校登录只在 HTTPS 登录页显示。
- HTTP /api/v1/auth/njupt/* 在 Nginx 直接返回 403 HTTPS_REQUIRED，保留统一 error 与 requestId 包络，不将学校凭据转发到后端；伪造 Forwarded / X-Forwarded-Proto 不能绕过。HTTPS 上游转发也清除外来 Forwarded，重写来源头。
- 后端继续 TQ_COOKIE_SECURE=true。HTTPS 登录保留 Secure / HttpOnly / SameSite=Strict；HTTP 赛事账号通过 Nginx 仅移除名为 tq_session 的 Secure 标志以支持本地赛事账号会话，其余 Cookie 属性保留。学校认证路径在该 HTTP 入口被拒绝，因此不会降级学校登录 Cookie。
- 浏览器不会向 HTTP 发送 HTTPS 的 Secure 会话；学校账号请始终使用 HTTPS 登录及退出。HTTP 与 HTTPS 的会话行为不可视为可互换。
- 保留 /entry/、引导脚本与 WebSocket 路由；命名 HTTP 转发和 WebSocket location 均关闭票据 URL 日志。
- 此规则由生产 Nginx 入口执行，后端端口必须继续仅绑定宿主回环且只接入受信任的内部网络，不能绕过入口暴露到公网。

### 配置与上线前置条件

默认 TQ_NJUPT_AUTH_ENABLED=false；在可信 HTTPS 与 Secure Cookie 就绪后设置为 true 开启。docker-compose.yml 与 docker-compose.app-only.yml 均显式透传这三个 NJUPT 变量；仅修改 .env 不会更新已有容器，需要仅重建后端 app 服务。TQ_NJUPT_CHECK_KEY 默认 1629428467008（须为 13 位数字）；TQ_NJUPT_APP_ID 默认 common。这是公开协议参数，不是本站密钥或学校密码。

已有数据库必须先备份并手工应用 src/main/resources/sql/V2__njupt_identities.sql，再开启新后端；应用不会自动迁移既有数据库。本地 Docker 库与 2026-11-05 的生产部署均已备份并手工添加此表，原有账号和业务数据保留；其他既有安装须逐一核实，不能据此跳过迁移检查。新建库 schema 与 H2 fixture 已包含此表。

部署容器须具备访问学校 HTTPS 443 的出口；仅接入 internal Docker 网络时无法认证。本地预览仅给 backend 增加既有 gateway 网络，未发布新的后端端口。

生产须先配置可信 HTTPS 与 Secure Cookie。前端学校入口仅允许 HTTPS 或 HTTP localhost / 127.0.0.1 / ::1 本机开发，不应在公网 HTTP 地址输入学校密码。前端只请求同源 /api/v1，不储存学校凭据；模式切换、提交结束后清空密码，不把学校登录失败自动重试到本地登录。

Vue 根据 providers 选择入口；返回 404 的旧后端明确提示“尚未接入学校认证”，保留原赛事账号登录。其他读取失败禁用提交并提供重试，不静默切换认证方式。



> 适用版本：当前 `main` 工作区
> 盘点日期：2026-10-28
> API 根路径：`/api/v1`
> 部署拓扑：整套系统运行在内网；REST 8080、WSS 443、MinIO 9000/9001 均由本机 frpc 穿透到公网 frps，MySQL 与 Redis 不做穿透

本文档以当前后端代码为准。旧版原型 API 和已废弃字段不应继续使用。

## 1. 通用约定

### 1.1 请求格式

- 普通请求使用 `application/json; charset=UTF-8`。
- 文件上传使用 `multipart/form-data`。
- 所有接口都以 `/api/v1/` 开头。
- 未传字段与 `null` 通常表示不修改；Jackson 配置为 `NON_NULL`，响应中的空字段可能被省略。
- 时间使用 UTC 墙钟时间并序列化为带 `Z` 的 ISO-8601，例如 `2026-10-28T13:30:00Z`。

### 1.2 登录态

登录成功后，后端通过 `Set-Cookie` 下发 HttpOnly JWT Cookie，响应体不包含 JWT。

浏览器请求示例：

```js
const response = await fetch("/api/v1/challenges", {
  credentials: "include"
});
```

前端不能读取 JWT，也不能把它写入 localStorage。前端和后端应保持同源，或通过同域反向代理转发 `/api`。

所有 `POST`、`PUT`、`PATCH`、`DELETE` 请求必须满足同源要求。浏览器通常会自动发送正确的 `Origin`；生产环境不要关闭后端的同源校验。

### 1.3 权限

| 标记 | 含义 |
|---|---|
| public | 未登录也可访问 |
| authenticated | 必须登录 |
| contestant | 选手或管理员 |
| admin | 仅管理员 |
| ticket | 使用一次性 WebSocket 连接票据 |

### 1.4 统一错误结构

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "请求字段无效",
    "details": [
      {
        "field": "title",
        "reason": "题目标题过长"
      }
    ]
  },
  "requestId": "req_abc123"
}
```

响应头同时包含 `X-Request-Id`。前端可在错误提示或日志面板中展示该值。

常见状态码：

| 状态码 | 含义 |
|---|---|
| 200 | 查询、更新、登录成功 |
| 201 | 创建或启动资源成功 |
| 204 | 删除成功，无响应体 |
| 400 | 字段校验失败 |
| 401 | 未登录或 Cookie 已失效 |
| 403 | 权限不足、来源校验失败、非本队 Flag |
| 404 | 资源不存在或不可见 |
| 405 | 不支持该 HTTP 方法，响应包含 Allow |
| 409 | 状态冲突、重复操作、容器未就绪或同一队伍已有容器 |
| 422 | 不支持的文件类型或业务规则失败 |
| 429 | 请求触发限流 |
| 500 | 服务端错误 |
| 503 | Docker 题目容器运行时不可用 |

### 1.5 分页

请求参数：

| 参数 | 默认值 | 约束 |
|---|---:|---|
| `page` | 1 | 从 1 开始 |
| `pageSize` | 20 | 最大 100 |

分页响应：

```json
{
  "items": [],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "total": 0,
    "totalPages": 0
  }
}
```

## 2. 认证与个人资料

### 2.1 登录

`POST /api/v1/auth/login`

权限：public

```json
{
  "username": "field_129",
  "password": "<password>"
}
```

成功：`200`

```json
{
  "userId": "usr_01",
  "username": "field_129",
  "role": "contestant",
  "teamId": "team_01",
  "expiresAt": "2026-10-28T20:00:00Z"
}
```

`role` 为 `contestant` 或 `admin`。登录成功后的 Cookie 会自动保存登录态。

### 2.2 登出

`POST /api/v1/auth/logout`

权限：authenticated

成功：`200`

```json
{
  "ok": true
}
```

服务端会使当前 JWT 失效并删除 Cookie。

### 2.3 当前账号

`GET /api/v1/auth/me`

权限：authenticated

```json
{
  "userId": "usr_01",
  "username": "field_129",
  "role": "contestant",
  "isSuperAdmin": false,
  "teamId": "team_01",
  "teamName": "North Signal",
  "nickname": "North",
  "avatarUrl": "https://example.com/avatar.webp",
  "status": "active",
  "lastLoginAt": "2026-10-28T12:00:00Z"
}
```

普通管理员与最高管理员的 `role` 均为 `admin`；仅最高管理员的 `isSuperAdmin` 为 `true`，用于区分管理操作范围。此字段只提供身份标识，不替代服务端授权。

### 2.4 修改个人资料

`PATCH /api/v1/me/profile`

权限：authenticated

```json
{
  "nickname": "North",
  "avatarUrl": "https://example.com/avatar.webp"
}
```

成功：`200`

```json
{
  "username": "field_129",
  "nickname": "North",
  "avatarUrl": "https://example.com/avatar.webp"
}
```

## 3. 赛事与队伍

### 3.1 当前赛事

`GET /api/v1/event`

权限：authenticated（必须登录）

```json
{
  "id": "evt_01",
  "name": "TraceQuest Season 01",
  "status": "open",
  "timezone": "Asia/Shanghai",
  "startsAt": "2026-10-28T01:00:00Z",
  "endsAt": "2026-10-28T10:00:00Z",
  "maxTeamSize": 3,
  "updatedAt": "2026-10-20T08:00:00Z"
}
```

赛事状态：

- `setup`
- `open`
- `paused`
- `closed`

### 3.2 查看当前赛事的本队

`GET /api/v1/events/{eventId}/teams/me`

权限：contestant

```json
{
  "id": "team_01",
  "eventId": "evt_01",
  "name": "North Signal",
  "joinCode": "TQ-7K9M2Q4XW8",
  "captain": true,
  "memberCount": 2,
  "maxMembers": 3
}
```

未加入队伍时返回 `200` 空响应体（客户端归一为 `null`）。

### 3.3 创建队伍

`POST /api/v1/events/{eventId}/teams`

权限：contestant

```json
{
  "name": "North Signal"
}
```

成功：`201`，返回 `TeamMembershipVO`。创建者自动成为队长。

### 3.4 使用标识码加入队伍

`POST /api/v1/events/{eventId}/teams/join`

权限：contestant

```json
{
  "code": "TQ-7K9M2Q4XW8"
}
```

成功：`200`。

队伍关系按赛事隔离。同一账号可以在不同赛事加入不同队伍；同一赛事中只能属于一个队伍。

### 3.5 退出当前队伍（2026-11-10）

`DELETE /api/v1/events/{eventId}/teams/me`

- 权限：contestant，仅处理登录者本人，不能指定其他用户或队伍。
- 成功：`204 No Content`；重复退出仍返回 204。赛事不存在 404，未认证 401，管理员 403。
- 退出后可创建或加入其他队伍；仍不能加入已结束赛事。原队伍、历史提交、已获积分及运行实例不删除、不转移。
- 创建者也可退出。`captain` 仍表示该队原创建者身份，不新增队长移交或踢人权限。
- 所有入队入口（包括管理员分配）均在事务内锁定目标队伍并用当前读统计人数，防止并发超员。
- 全站硬上限 3 人；赛事可设置更小值（1–3）。旧配置大于 3 按 3 执行，已有超员队伍不自动踢人，只禁止继续入队。
- 创建/更新赛事的 `maxTeamSize` 超过 3 返回 400 校验错误；容量冲突返回 409。
- 退出受 `team-write` 限流约束并写入 `team.left` 审计记录。

## 4. 选手端题目

### 4.1 题目列表

`GET /api/v1/challenges`

权限：authenticated（必须登录）

查询参数：

| 参数 | 必填 | 说明 |
|---|---|---|
| `eventId` | 否 | 不传时使用当前赛事 |
| `category` | 否 | 分类过滤 |
| `difficulty` | 否 | 难度过滤 |
| `page` | 否 | 默认 1 |
| `pageSize` | 否 | 默认 20，最大 100 |

```json
{
  "items": [
    {
      "code": "PWN-01",
      "title": "Heap Garden",
      "category": "PWN",
      "difficulty": "困难",
      "points": 600,
      "description": "题目描述",
      "coverUrl": null,
      "attachments": [
        {
          "id": "file_01",
          "name": "challenge.zip",
          "size": 1048576,
          "contentType": "application/zip",
          "downloadUrl": "/api/v1/files/file_01",
          "createdAt": "2026-10-28T12:00:00Z",
          "kind": "attachment"
        }
      ],
      "myStatus": "unsolved",
      "containerEnabled": true
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "total": 1,
    "totalPages": 1
  }
}
```

注意：

- `myStatus` 为 `solved` 或 `unsolved`。
- `containerEnabled=true` 表示该题需要当前队伍启动 Docker 容器。
- 选手端永远不返回标准 Flag、Flag 环境变量、Docker 镜像或容器内部地址。
- 草稿和归档题目不会出现在列表中。

### 4.2 题目详情

`GET /api/v1/challenges/{code}`

权限：authenticated（必须登录）

响应字段与列表项相同。

## 5. Docker 题目容器

### 5.1 核心规则

- 只有后台已发布、赛事处于 `open` 且未超过起止时间的 Docker 题才能启动容器。
- 每个队伍同时只能运行一个题目容器。
- 容器内部端口不填写，由服务端解析镜像 `EXPOSE`；宿主端口由 Docker 随机分配。
- 容器默认存活 1 小时，`expiresAt` 是权威到期时间。
- 剩余时间不超过 30 分钟时，允许延长 1 小时。
- 容器过期后由后端定时清理。
- 前端不要直接使用 `containerIp`、`hostIp` 或 `hostPort` 连接容器，只使用后端签发的 WSS 地址。
- 后端必须从 Docker CLI 输出中提取合法容器标识，忽略内核告警；历史上已创建但仍为 `STARTING` 且缺少标识的实例，状态查询会按容器名称恢复，端口可连接后转为 `READY`。

容器状态：

- `PENDING`
- `PULLING`
- `STARTING`
- `READY`
- `FAILED`
- `STOPPED`

### 5.2 启动或复用本队容器

`POST /api/v1/events/{eventId}/challenges/{code}/runtime/instances`

权限：contestant

请求体：无。

成功：`201`

```json
{
  "instanceId": "cinst_01",
  "challengeCode": "PWN-01",
  "teamId": "team_01",
  "status": "READY",
  "containerIp": "172.20.0.8",
  "hostIp": "127.0.0.1",
  "hostPort": 43127,
  "containerPort": 8080,
  "wssUrl": "wss://tq.example.com/api/v1/challenge-connections",
  "lastError": null,
  "readyAt": "2026-10-28T12:10:00Z",
  "expiresAt": "2026-10-28T13:10:00Z",
  "remainingSeconds": 3600,
  "entryPath": "/entry/PWN-01/"
}
```

有效 READY 响应的 `entryPath` 由既有同源鉴权网关承接，题号按单段 URL 编码；未就绪、停止、失败、到期或无实例时不返回有效路径。浏览器应解析该路径，而不是从内网 IP 或宿主端口猜网址。

同一队伍已经运行该题容器时，接口会复用并返回原实例。

实现说明（2026-11-10）：启动、续期、组队和计分的分布式锁使用稳定的参数索引解析，
不依赖构建时保留 Java 参数名称；本次修复不改变请求、响应或鉴权约定。

同一队伍正在运行另一道题的容器时返回 `409`：

```json
{
  "error": {
    "code": "CONFLICT",
    "message": "每个队伍同时只能运行一个题目容器，请先摧毁当前容器"
  },
  "requestId": "req_01"
}
```

### 5.3 查询本队容器状态

`GET /api/v1/events/{eventId}/challenges/{code}/runtime/instances/me`

权限：contestant

返回与启动接口相同的 `ChallengeRuntimeVO`。

当前题目没有容器时返回：

```json
{
  "instanceId": null,
  "challengeCode": "PWN-01",
  "teamId": "team_01",
  "status": "STOPPED",
  "remainingSeconds": 0
}
```

### 5.4 摧毁本队容器

`DELETE /api/v1/events/{eventId}/challenges/{code}/runtime/instances/me`

权限：contestant

成功：`204`，无响应体。

摧毁后可以启动另一道 Docker 题。

### 5.5 延长本队容器

`POST /api/v1/events/{eventId}/challenges/{code}/runtime/instances/me/extend`

权限：contestant

请求体：无。

成功：`200`，返回更新后的 `ChallengeRuntimeVO`。

前端应先检查 `remainingSeconds`：

```js
const canExtend =
  runtime.status === "READY" &&
  runtime.remainingSeconds > 0 &&
  runtime.remainingSeconds <= 30 * 60;
```

剩余时间超过 30 分钟返回 `409`：

```json
{
  "error": {
    "code": "CONFLICT",
    "message": "剩余时间超过30分钟，暂不能延长容器"
  },
  "requestId": "req_02"
}
```

延长成功后，新的 `expiresAt` 等于原 `expiresAt` 加 1 小时。

### 5.6 获取一次性 WSS 连接票据

`POST /api/v1/events/{eventId}/challenges/{code}/runtime/connection-ticket`

权限：contestant

请求体：无。

成功：`200`

```json
{
  "ticket": "ct_7m7m0v3z8m4g5j9p2q",
  "wssUrl": "wss://tq.example.com/api/v1/challenge-connections",
  "expiresAt": "2026-10-28T12:11:00Z"
}
```

票据规则：

- 默认 60 秒有效。
- 只能使用一次。
- 票据绑定当前账号、队伍、题目和容器实例。
- 容器未就绪时返回 `409 CONTAINER_NOT_READY`。

## 6. WebSocket 二进制转发

### 6.1 建立连接

票据只能通过查询参数提交：

```js
const socket = new WebSocket(
  `${ticketResponse.wssUrl}?ticket=${encodeURIComponent(ticketResponse.ticket)}`
);
socket.binaryType = "arraybuffer";
```

浏览器会自动携带 `Origin`。后端同时校验票据和来源，不接受长期 JWT 直接连接 WebSocket。

### 6.2 数据协议

- 只接受二进制帧。
- 发送文本帧会被关闭，状态码为 `1003 NOT_ACCEPTABLE`。
- 每个二进制帧的内容就是发送给容器 TCP 的原始字节。
- 容器返回的 TCP 字节会作为 WebSocket 二进制帧返回。
- 单帧最大默认 1 MiB。
- 后端空闲超时默认 5 分钟。

示例：

```js
socket.addEventListener("open", () => {
  const payload = new TextEncoder().encode("hello\n");
  socket.send(payload.buffer);
});

socket.addEventListener("message", (event) => {
  const bytes = new Uint8Array(event.data);
  console.log("container response:", bytes);
});

socket.addEventListener("close", (event) => {
  console.log("closed", event.code, event.reason);
});
```

如果题目使用 HTTP、JSON、Telnet 或自定义二进制协议，前端需要实现相应的协议适配层。

## 7. 提交、进度与排行榜

### 7.1 提交 Flag

`POST /api/v1/challenges/{code}/submissions`

权限：contestant

请求头建议：

```text
Idempotency-Key: <uuid>
```

```json
{
  "answer": "sacc{$2a$10$...}",
  "requestId": "client-generated-request-id"
}
```

手工 Flag 题填写标准 Flag。Docker 题必须提交当前队伍容器实际获得的完整 Flag。

非本队 Flag 返回 `403 FLAG_OWNER_MISMATCH`：

```json
{
  "error": {
    "code": "FLAG_OWNER_MISMATCH",
    "message": "串通flag并不是好习惯"
  },
  "requestId": "req_03"
}
```

成功：`200`

```json
{
  "submissionId": "sub_01",
  "challengeCode": "PWN-01",
  "result": "correct",
  "completion": "first_solve",
  "solvedAt": "2026-10-28T12:20:00Z",
  "basePoints": 600,
  "bonusAwarded": 100,
  "pointsAwarded": 700,
  "solvePlace": 1,
  "progress": {
    "solved": 1,
    "total": 8
  },
  "source": "answer"
}
```

字段值：

| 字段 | 值 |
|---|---|
| `result` | `correct` / `incorrect` |
| `completion` | `first_solve` / `already_solved` / `null` |
| `source` | `answer` / `location` |

重复提交已解出的题目不会重复计分。

### 7.2 我的进度

`GET /api/v1/me/progress?eventId={eventId}`

权限：contestant

```json
{
  "solved": 3,
  "total": 8,
  "score": 1700,
  "teamId": "team_01",
  "teamName": "North Signal",
  "solvedChallengeCodes": ["WEB-01", "PWN-01", "MISC-01"]
}
```

### 7.3 我的提交记录

`GET /api/v1/me/submissions`

查询参数：

| 参数 | 说明 |
|---|---|
| `eventId` | 赛事 |
| `challengeCode` | 题目编号 |
| `result` | `correct` / `incorrect` |
| `page` | 页码 |
| `pageSize` | 每页数量 |

没有提交、筛选无匹配记录或请求页没有记录时返回 `200` 和 `items: []`，不会将空结果视为服务端错误。分页保留请求的 `page/pageSize` 与实际 `total/totalPages`；例如总共 2 条记录、请求第 2 页且每页 100 条时返回 `{"items":[],"pagination":{"page":2,"pageSize":100,"total":2,"totalPages":1}}`。仍只允许查询本人或当前队伍在指定赛事的记录；匿名返回 `401`。响应不包含明文答案。

### 7.4 单条提交记录

`GET /api/v1/me/submissions/{submissionId}`

```json
{
  "submissionId": "sub_01",
  "challengeCode": "PWN-01",
  "challengeTitle": "Heap Garden",
  "result": "correct",
  "pointsAwarded": 700,
  "solvePlace": 1,
  "source": "answer",
  "submittedAt": "2026-10-28T12:20:00Z"
}
```

### 7.5 参赛排行榜

`GET /api/v1/leaderboard?eventId={eventId}&scope=team&page=1&pageSize=50`

权限：authenticated（必须登录）

当前只支持 `scope=team`。

```json
{
  "updatedAt": "2026-10-28T12:25:00Z",
  "stats": {
    "teams": 12,
    "contestants": 36,
    "solvedChallenges": 6,
    "endsAt": "2026-10-28T18:00:00Z"
  },
  "items": [
    {
      "rank": 1,
      "teamId": "team_01",
      "userId": null,
      "teamName": "North Signal",
      "solvedCount": 3,
      "score": 1700,
      "isMe": true,
      "lastSolvedAt": "2026-10-28T12:20:00Z"
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 50,
    "total": 12,
    "totalPages": 1
  }
}
```

## 8. 文件与位置题

### 8.1 下载文件

`GET /api/v1/files/{fileId}`

权限：authenticated

成功返回 `200` 同源二进制流，不再跳转到 MinIO。附件为 `application/octet-stream` 并保留原文件名；封面为保存的图片 MIME 类型并使用 `inline`。响应含 `Content-Length` 与 `Cache-Control: no-store`。匿名请求 `401`；不存在或选手不可见的文件 `404`。管理员可预览未发布题目的文件。

推荐使用链接或 `location.assign()` 下载：

```js
window.location.assign("/api/v1/files/file_01");
```

### 8.2 位置题二维码核验

`POST /api/v1/location-verifications`

```json
{
  "payload": "TRACEQUEST|LOCATION|GEO-01|opaque-token",
  "requestId": "client-request-id"
}
```

返回 `SubmissionResultVO`。

不要由前端拼接二维码载荷，直接使用后台签发接口返回的 `payload`。

## 9. 后台：赛事与题目

### 9.1 后台赛事

`GET /api/v1/admin/event`

读取当前完整赛事配置。

`POST /api/v1/admin/event`

```json
{
  "name": "TraceQuest Season 02",
  "timezone": "Asia/Shanghai",
  "startsAt": "2026-11-01T09:00:00+08:00",
  "endsAt": "2026-11-01T18:00:00+08:00",
  "maxTeamSize": 3
}
```

`PATCH /api/v1/admin/event`

```json
{
  "status": "open",
  "maxTeamSize": 3
}
```

### 9.2 后台题目列表

`GET /api/v1/admin/challenges`

查询参数：`q`、`status`、`category`、`page`、`pageSize`。

### 9.3 在赛事中创建题目

推荐接口：

`POST /api/v1/admin/events/{eventId}/challenges`

兼容旧接口：`POST /api/v1/admin/challenges`，会绑定当前赛事。

手工 Flag 题：

```json
{
  "code": "WEB-01",
  "title": "JWT Playground",
  "category": "Web",
  "difficulty": "中等",
  "points": 400,
  "bonuses": {
    "first": 100,
    "second": 60,
    "third": 30,
    "other": 0
  },
  "description": "题目描述",
  "answer": "flag{replace-me}",
  "status": "draft"
}
```

Docker 题：

```json
{
  "code": "PWN-01",
  "title": "Heap Garden",
  "category": "PWN",
  "difficulty": "困难",
  "points": 600,
  "bonuses": {
    "first": 100,
    "second": 60,
    "third": 30,
    "other": 0
  },
  "description": "题目描述",
  "dockerImage": "qaqxianyuqaq/heap-garden",
  "dockerTag": "1.0.0",
  "flagEnvName": "CHALLENGE_FLAG",
  "flagPrefix": "sacc",
  "status": "draft"
}
```

规则：

- `dockerImage`、`dockerTag`、`flagEnvName`、`flagPrefix` 必须同时填写。
- Docker 题不能再填写 `answer`。
- 手工 Flag 题不能填写任何 Docker 字段。
- 前端不能提交 `containerPort`。
- Docker 镜像必须声明 TCP `EXPOSE` 端口。
- `flagPrefix` 只填写头，例如 `sacc`；完整注入值为 `sacc{BCrypt密文}`。

成功：`201`，返回完整 `AdminChallengeVO`。

### 9.4 后台题目详情

`GET /api/v1/admin/challenges/{code}`

返回字段包括：

- `id`
- `eventId`
- `code`
- `title`
- `category`
- `difficulty`
- `points`
- `bonuses`
- `description`
- `answer`
- `coverUrl`
- `status`
- `version`
- `publishedAt`
- `createdAt`
- `updatedAt`
- `attachments`
- `containerEnabled`
- `runtimeType`
- `dockerImage`
- `dockerTag`
- `flagEnvName`
- `flagPrefix`

Docker 题的 `answer` 通常为空。管理端仍不应把环境变量实际值或生成后的 Flag 显示到前端页面。

### 9.5 编辑题目

`PATCH /api/v1/admin/challenges/{code}`

请求为部分更新。推荐携带当前 `version` 防止并发覆盖：

```json
{
  "title": "New title",
  "points": 500,
  "version": 3
}
```

切换到 Docker 模式时，一次提交完整的四个 Docker 字段，并不要同时提交 `answer`。

### 9.6 发布、暂停、恢复、删除

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/admin/challenges/{code}/publish` | 发布 |
| `POST` | `/api/v1/admin/challenges/{code}/pause` | 暂停，仍可看见但不能提交 |
| `POST` | `/api/v1/admin/challenges/{code}/resume` | 恢复发布 |
| `DELETE` | `/api/v1/admin/challenges/{code}` | 删除；已有解题时返回 `409` |

题目状态：`draft`、`published`、`paused`、`archived`。

### 9.7 附件上传

创建题目成功后上传附件：

`POST /api/v1/admin/challenges/{code}/attachments`

请求使用 `multipart/form-data`，字段名支持：

- `files`：多个文件
- `file`：单个文件

```js
const form = new FormData();
form.append("files", file1);
form.append("files", file2);

await fetch(`/api/v1/admin/challenges/WEB-01/attachments`, {
  method: "POST",
  credentials: "include",
  body: form
});
```

成功：`201`

```json
{
  "items": [
    {
      "id": "file_01",
      "name": "challenge.zip",
      "size": 1048576,
      "contentType": "application/zip",
      "downloadUrl": "/api/v1/files/file_01",
      "createdAt": "2026-10-28T12:00:00Z",
      "kind": "attachment"
    }
  ]
}
```

查看附件：

`GET /api/v1/admin/challenges/{code}/attachments`

删除附件：

`DELETE /api/v1/admin/challenges/{code}/attachments/{fileId}`

### 9.8 封面

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/admin/challenges/{code}/cover` | multipart 字段 `file`，上传或替换 |
| `GET` | `/api/v1/admin/challenges/{code}/cover` | 获取封面元数据 |
| `DELETE` | `/api/v1/admin/challenges/{code}/cover` | 移除封面 |

附件单文件默认上限 50 MB，封面默认上限 10 MB。服务端禁止常见可执行文件扩展名。

### 9.9 位置二维码

`POST /api/v1/admin/challenges/{code}/location-qr`

```json
{
  "expiresAt": "2026-10-28T18:00:00Z",
  "rotate": true
}
```

成功：`201`

```json
{
  "challengeCode": "GEO-01",
  "payload": "TRACEQUEST|LOCATION|GEO-01|opaque-token",
  "imageUrl": "/api/v1/admin/challenges/GEO-01/location-qr/image",
  "createdAt": "2026-10-28T12:30:00Z",
  "expiresAt": "2026-10-28T18:00:00Z",
  "generation": 1
}
```

当前 `imageUrl` 返回 `501 NOT_IMPLEMENTED`。前端应使用 `payload` 自行绘制二维码。

撤销：

`DELETE /api/v1/admin/challenges/{code}/location-qr`

## 10. 后台：选手、榜单与审计

### 10.1 选手账号

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/admin/contestants` | 分页检索，支持 `q`、`status`、`teamId` |
| `POST` | `/api/v1/admin/contestants` | 创建账号 |
| `GET` | `/api/v1/admin/contestants/{userId}` | 账号详情 |
| `PATCH` | `/api/v1/admin/contestants/{userId}` | 修改资料和队伍 |
| `PUT` | `/api/v1/admin/contestants/{userId}/password` | 重置密码 |
| `POST` | `/api/v1/admin/contestants/{userId}/disable` | 停用 |
| `POST` | `/api/v1/admin/contestants/{userId}/enable` | 启用 |
| `DELETE` | `/api/v1/admin/contestants/{userId}` | 删除 |
| `POST` | `/api/v1/admin/contestants/import` | multipart 字段 `file` 导入 CSV |
| `GET` | `/api/v1/admin/contestants/export` | 下载 CSV |

创建账号：

```json
{
  "username": "field_200",
  "password": "<password>",
  "teamId": "team_01",
  "teamName": "North Signal",
  "note": "现场选手",
  "nickname": "North"
}
```

成功：`201`

```json
{
  "id": "usr_02",
  "username": "field_200",
  "team": {
    "id": "team_01",
    "name": "North Signal"
  },
  "nickname": "North",
  "note": "现场选手",
  "status": "active",
  "createdAt": "2026-10-28T12:00:00Z",
  "lastLoginAt": null
}
```

响应绝不包含密码或密码哈希。

### 10.2 管理员权限与队伍管理

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| `GET` | `/api/v1/admin/accounts/administrators` | 最高管理员 | 分页查询普通管理员，支持 `q`、`page`、`pageSize` |
| `PATCH` | `/api/v1/admin/accounts/{userId}/role` | 最高管理员 | 将普通账号设为 `admin` 或撤销为 `contestant` |
| `GET` | `/api/v1/admin/teams` | 管理员 | 分页查询当前赛事队伍及成员摘要，支持 `q`、`page`、`pageSize` |
| `PATCH` | `/api/v1/admin/teams/{teamId}` | 管理员 | 修改当前赛事队伍名称 |

角色变更请求：

```json
{"role":"admin"}
```

撤销管理员权限时将 `role` 设为 `contestant`。角色变更会递增账号令牌版本，使该账号现有会话立即失效；最高管理员不能通过此接口被降权，操作者也不能修改自己的角色。普通管理员不能查询或变更管理员角色。

队伍改名请求：

```json
{"name":"North Signal"}
```

队伍列表和改名仅作用于当前赛事。队伍名在同一赛事内不可重复，目标队伍不属于当前赛事时返回 `404`。

### 10.3 后台排行榜

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/admin/leaderboard` | 队伍排行榜，支持 `eventId`、`q` |
| `GET` | `/api/v1/admin/leaderboard/{teamId}` | 队伍成员、提交日志和积分事件 |
| `GET` | `/api/v1/admin/score-events` | 查询积分事件 |
| `POST` | `/api/v1/admin/score-adjustments` | 手工调整积分 |

手工调整：

```json
{
  "teamId": "team_01",
  "delta": -50,
  "reason": "赛事裁定"
}
```

`delta` 必须非空，正数加分，负数扣分。

### 10.4 后台总览与审计

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/admin/dashboard` | 赛事、题目、账号、提交与近期活动 |
| `GET` | `/api/v1/admin/audit-log` | 分页审计日志 |

完整审计仅最高管理员可查；筛选、敏感摘要处理及本地候选发布边界见文末“管理后台信息增强”。

## 11. 重要前端行为

### 11.1 Docker 题推荐流程

```text
题目详情
  -> containerEnabled=true
  -> POST runtime/instances
  -> 等待 status=READY
  -> POST connection-ticket
  -> new WebSocket(wssUrl + "?ticket=...")
  -> 二进制收发
  -> 从容器获得本队完整 Flag
  -> POST /challenges/{code}/submissions
```

### 11.2 倒计时

不要只依赖前端本地计时。应以服务端返回的 `expiresAt` 校正倒计时，并定期调用状态接口刷新：

```js
const expiresAt = new Date(runtime.expiresAt).getTime();
const remaining = Math.max(0, Math.floor((expiresAt - Date.now()) / 1000));
```

`remainingSeconds` 是响应生成时的快照。

### 11.3 延长按钮

只在以下条件同时成立时显示：

```text
status === "READY"
remainingSeconds > 0
remainingSeconds <= 1800
```

### 11.4 摧毁按钮

用户主动摧毁容器前应二次确认。操作成功后清除前端 WebSocket、倒计时和连接票据。

### 11.5 不要做的事情

- 不要把 JWT 存入 localStorage。
- 不要手填 `containerPort`。
- 不要直接连接 `containerIp:containerPort` 或 `127.0.0.1:hostPort`。
- 不要缓存或展示其他队伍的 Flag。
- 不要假设一个队伍可以同时启动多道 Docker 题。
- 不要把前端本地计时当作容器过期依据。
- 不要使用文本 WebSocket 消息与容器通信。

## 12. 健康检查

`GET /api/v1/health`

权限：public

```json
{
  "ok": true,
  "service": "tracequest-backend"
}
```


## 2026-11-04：题目文件同源下载修复

`GET /api/v1/files/{fileId}` 保持登录鉴权，成功响应由 `302` 预签名跳转改为 `200` 同源二进制流。内容仍存放在 MinIO；不对浏览器返回存储地址、凭据或可转赠的签名链接。响应带 `Content-Length` 和 `Cache-Control: no-store`；附件使用 `application/octet-stream` 及包含原始 UTF-8 文件名的 `Content-Disposition: attachment`，封面使用保存的图片 MIME 类型及 `inline`。匿名请求 `401`；文件不存在、题目不存在或选手无权查看该题时 `404`；管理员可预览未发布题目的文件。文件读取失败返回既有存储错误，流式传输中断由客户端判为下载失败。

题目列表/详情中的 `coverUrl` 与 `attachments[].downloadUrl` 均继续使用 `/api/v1/files/{fileId}`，无需变更请求字段。前端下载继续通过同源会话读取；上传封面读取失败时不再替换成默认自然素材，避免显示不属于该题的图片。未登录时受保护封面不可读取，登录后重新进入题目列表加载。


## 个人头像文件上传 · 2026-11-05

本节为当前新增契约，替代旧版“后端不支持个人头像上传”的说明。所有路径均以 /api/v1 为前缀。

| 方法 | 路径 | 权限 | 成功响应 |
|---|---|---|---|
| POST | /api/v1/me/avatar | 已登录的 ADMIN、CONTESTANT，仅本人 | 201，ProfileVO |
| GET | /api/v1/avatars/{userId}/{avatarId} | 已登录；只读当前有效版本 | 200，image/png 图片字节 |

上传请求使用 multipart/form-data，必填字段为 file。请让浏览器生成 multipart boundary，不要手动写 Content-Type。服务端从会话取得目标账号，不接受 userId、对象键或文件路径参数。成功后已经将 URL 保存到 users.avatar_url，无须再 PATCH 一次头像地址。

成功响应示例：

```json
{
  "username": "example",
  "nickname": "示例选手",
  "avatarUrl": "/api/v1/avatars/usr_example123/avatar_0123456789abcdefgh"
}
```

- 服务端接收 PNG/JPEG，单文件最多 8 MiB；MIME 声明必须匹配实际可解码内容，禁止 SVG、HTML、伪装图片。文件名不参与存储键生成。
- 解码前校验单边不超过 8192 像素、总像素不超过 16777216。服务端居中裁剪、重编码为 512×512 PNG，不保留原文件的元数据或尾随内容；使用内存图像流，二进制持久化只经过 MinIO。
- 前端允许选取 PNG/JPEG/WebP/AVIF，经现有裁剪器导出 JPEG 文件后上传；不把 Data URL 写入个人资料接口或 localStorage。
- MinIO 对象键使用 challenges/profile-avatars/{userId}/{avatarId}.png，复用现有应用账号的 challenges/* 授权；不扩张桶策略，也不修改现有题目文件。以后迁移独立头像授权时需同步迁移该前缀。
- 返回同源版本 URL，不暴露 MinIO 内网地址、密钥或原始上传文件。GET 要求登录，不允许匿名读取；Content-Type 为 image/png，含 nosniff、inline 和 Cache-Control: no-store。
- 每个账号每分钟默认最多 6 次上传，失败请求也计入；配置项为 tracequest.rate-limit.avatar-upload-per-minute，超限返回 429 和 Retry-After。继承 Cookie、同源写入校验与审计规则。
- 同一账号并发头像替换以数据库行锁串行处理，仅更新 avatar_url 字段，不覆盖昵称、角色、队伍或密码。
- 新对象保存失败时不改变原地址；数据库回滚时回收新对象；提交成功后回收旧的受管理头像。旧版本 URL 返回 404。存储回收失败写服务端告警，可能遗留待运维清理的孤立对象，不删除当前有效头像或任何外部 URL 指向的文件。
- 现有 PATCH /api/v1/me/profile 的昵称和头像 URL 契约保持兼容。关于页面的出题人头像管理属于另一套内容接口，本次不新增。

| 状态 | 错误码 | 场景 |
|---|---|---|
| 400 | VALIDATION_ERROR | file 缺失、空文件或像素尺寸超限 |
| 401 | UNAUTHORIZED | 未登录或会话过期 |
| 403 | ORIGIN_REJECTED | 写入请求来源被拒绝 |
| 404 | NOT_FOUND | 头像不存在、旧版本、非法路径或停用账号 |
| 413 | PAYLOAD_TOO_LARGE | 单文件超过 8 MiB 或上传请求超过全局体积限制 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 请求未使用 multipart/form-data |
| 422 | UNSUPPORTED_FILE_TYPE | 非 PNG/JPEG、内容与 MIME 不符或图片无法解码 |
| 429 | RATE_LIMITED | 超过账号上传频率限制 |
| 500 | STORAGE_ERROR | MinIO 写入或读取失败 |

错误沿用 {error:{code,message,details},requestId}，上传失败时前端保留裁剪状态并显示服务端错误。昵称保存与文件上传为两次独立请求；若昵称已保存而文件失败，前端明确显示上传失败并允许重试，不声称整个资料操作具有跨请求原子性。

### 2026-11-11 靶机启动可靠性修复

- 启动接口路径和响应结构不变；先落库 PENDING，再进入 PULLING / STARTING / READY，失败保存 FAILED。状态查询不再重复执行启动任务；正常启动窗口内快速返回已保存状态。
- 优先复用服务器本地题目镜像；只有本地缺失才拉取，远端拉取最多 45 秒。更新同名标签时由管理员预先加载新版镜像；本接口不会强制刷新已有标签。启动命令禁止隐式拉取。
- 镜像准备失败返回 HTTP 503 / CONTAINER_RUNTIME_ERROR 和可操作的说明，不再返回未处理异常 500；lastError 不包含 Docker 原始输出或注入 Flag。已就绪后才开始一小时计时，重复启动复用同一实例。
- 本次不变更数据库 schema、共享服务或前端。

### 2026-11-11 靶机浏览器入口字段

- `POST /api/v1/events/{eventId}/challenges/{code}/runtime/instances`、`GET /api/v1/events/{eventId}/challenges/{code}/runtime/instances/me` 及续期响应新增只读可选字段 `entryPath`。例如有效 READY 的 MISSING 实例返回 `"entryPath": "/entry/MISSING/"`。字段采用 URL path-segment 编码，与页面同源解析，不含令牌、Flag 或 Docker 宿主端口。
- 未就绪、停止、失败、到期或无实例时字段为空；其余已有字段保留。选手沿用现有前端链接进入 /entry/，网关继续以当前会话查验队伍并转发到该队靶机；此路径不是匿名公开入口。
- 无需重新启动现有 READY 容器；刷新状态即可得到入口。本次不改数据库、网关配置或前端脚本。

## 2026-11-11 版本汇总

上次提交以来的完整变更、比较基线、V6 升级前置条件及验证边界见 [版本变更说明](docs/CHANGELOG_2026-11-11.md)。


### 2026-11-11 稳定化候选：来源、重试和计分事务（尚未上线）

基线为后端 release/deployed-version-20261111 的 24c5187；接口仍为 /api/v1，无新增路由、字段或数据库迁移。以下描述仅适用于本轮候选，生产仍保留基线。

- HTTP 写请求和 /api/v1/challenge-connections WebSocket 握手统一校验 Origin 的协议、主机、有效端口；省略默认 :80/:443 视为相同端口。同主机但不同协议或端口仍为跨源，返回 403 ORIGIN_REJECTED（HTTP）或 403 握手拒绝（WebSocket），不提前消费票据。畸形 Origin、用户信息、路径、查询和片段一律拒绝。仅完整 Origin 的既有显式白名单可放行额外来源，不接受主机名通配。
- 来源比较使用框架还原后的完整请求 URL，不混用原始内部 Host 与转发后的协议。代理必须保留可信 Host，并覆盖来自客户端的 Forwarded / X-Forwarded-*；不通过关闭同源校验修复代理错误。浏览器自动附带 Origin，非浏览器 WebSocket 验收客户端也必须附带正确 Origin。
- 答案提交 JSON requestId 在当前账号内绑定原题目及答案摘要；重用相同 requestId 换答案或换题目返回 409 IDEMPOTENCY_CONFLICT，不借用另一条提交的结果。客户端改变题目或答案时必须生成新的 requestId。
- 答案与二维码提交沿用分布式锁，在当前账号内串行完成幂等查验、业务和响应快照；再由原有题目锁序列化奖励位次。等待账号锁超过既有 3 秒上限时返回 409 CONFLICT，可稍后重试；不会先计分再返回键冲突。锁仍使用既有 10 秒租期，不声称支持任意长数据库停顿。
- Idempotency-Key 沿用账号与作用域隔离及内容摘要匹配；并发插入同一快照发生唯一键竞争时，返回已提交的首个快照。内容不同仍为 409；无关主键冲突或其他存储错误不吞掉。
- 答案和二维码校验不提前开启包含等待锁过程的数据库事务；提交记录、有效解题、基础分和奖励分仍在同一个 award 事务中写入，并在释放题目锁前提交。原始解题时间和奖励规则不变；同队重复正确提交不重复加分。
- 二维码撤销后核验沿用 400 VALIDATION_ERROR，并非 422；二维码 PNG 渲染和摄像头扫描契约未改。
- 本轮不恢复此前撤回的治理功能或五人上限代码；不批量修改正式成绩、账号角色或队伍成员。前端仅同步契约文档，不改 UI 或构建资源。

### 管理后台信息增强（2026-11-11，本地候选）

**权限**：继续使用服务端已验证的账号角色与 JWT 会话。普通管理员/超管可查看队伍和本节判题记录；完整操作审计仅 SUPER_ADMIN。普通选手直接调用管理接口返回 403，未登录返回 401。普通管理员不自动升为超管，本轮不新增或改动 NJUPT 身份绑定、权限分配规则、按赛事授权模型。

#### GET /api/v1/admin/submissions

- 分页：page 从 1 开始，pageSize 默认 20、最大 100；响应 {items, pagination:{page,pageSize,total,totalPages}}，超出末页返回空 items 但保留真实 total。
- 筛选：eventId（省略时当前赛事）、teamId、userId、challengeCode、result（correct/incorrect）、source（answer/location）、requestId、from、to。除时间区间外均为精确匹配。
- from/to 为带时区的 ISO-8601；转成 UTC 闭区间查询，非法或倒置范围返回 400。不存在赛事返回 404；teamId 不属于指定赛事返回 404，不跨赛事混查。普通管理员沿用既有全站赛事管理权限，本轮没有引入赛事范围分配。
- 条目字段：submissionId、eventId、userId、username、nickname、teamId、teamName、challengeCode、challengeTitle、result、pointsAwarded、solvePlace、source、requestId、submittedAt。可选未知值可能省略。
- pointsAwarded 是当次实际加分，solvePlace 为该次记录保存的解题位次；本轮不重排、不调整奖励。requestId 是客户端提交幂等编号，不等同于服务端 req_… 故障追踪编号。
- 仅返回 submissions 中已落库的判题。相同幂等请求或已完成后的正确重复提交沿用基线返回既有结果，不人为生成新的判题记录；认证/限流/参数校验等提前拒绝的请求不冒充已判题记录。
- 历史 teamId 固定为提交时归属；后台改队不搬移提交或成绩。teamName、username、nickname 是当前显示资料，不声称是历史姓名快照。答案、answerDigest、二维码密钥、动态 Flag 一律不返回。

#### GET /api/v1/admin/audit-log（权限收紧）

- 仅超管；控制器与服务层均校验。GET /api/v1/admin/dashboard 对普通管理员仍可用，但 recentActivity 为空，不能从总览旁路获取完整操作记录。
- 保留 action、entityType、actor（操作者名称包含匹配）、page、pageSize，新增 actorId、entityId、requestId、from、to。时间规则与上文相同。
- action、entityType、actorId、requestId、一般 entityId 均精确匹配。entityType=team 且传 entityId 时，还匹配本轮保存的 before/after 中的 teamId，所以改队前后两支队伍均能检索该变更；查询值以绑定参数和 LIKE 转义处理。
- 响应保留 id、actor、actorId、action、entityType、entityId、requestId、clientIp、before、after、createdAt；requestId 是服务端请求编号。新队伍变动不额外收集 IP，未记录字段保持缺失。
- before/after 为脱敏 JSON 字符串；只保留身份/赛事/队伍、状态、题目编号标题、分值等允许摘要，不保存答案、密码、Cookie、令牌、邀请码、完整二维码载荷、原始文件地址等未知字段。对既有快照读取也应用同一允许列表，无法解析时不返回原文。旧数据库/既有备份中的原始内容没有被本轮删除或重写。
- 新队伍事件：team.created、team.joined、team.left、team.member.assigned、team.member.removed、team.renamed。before/after 含 eventId、userId（适用时）、teamId、teamName、captainId；空归属为 null。
- 这些队伍变动与审计在同一现有事务内提交，审计写入失败则回滚变动；重复退出或无变化改名不伪造新记录。现有其他 AuditAction 仍为成功后尽力记录，本轮未宣称所有写操作都已原子审计。
- 不提供审计编辑/删除接口，不补造上线前未记录的历史；本轮没有增加登录失败、全部下载、全部靶机动作的完整日志采集或受控导出。

#### 后台交互与发布边界

- 新增“提交记录”“操作审计”入口，仅打开时按页请求，不一次拉取全部日志。筛选错误、读取失败、空列表分开显示；乱序响应不得覆盖较新筛选。
- 队伍页显示账号 ID、账号名称、昵称、队长和加入时间，直接跳转本队提交；超管可直接查看本队变动。总览使用服务端真实累计提交/解题数量，明确为全平台口径。
- 无 DDL/迁移，无新依赖，不改变计分、靶机规则、比赛状态、账号角色、报名关系或队伍人数上限。新增 GET 查询无写副作用、无需幂等键；沿用 Cookie 会话、CSRF/同源校验及角色撤销即时失效机制。
- 本节描述本地候选实现；不是生产已发布证明。生产需要另行授权、版本预检与镜像回退点。


### 管理后台信息增强：生产上线记录（2026-11-11）

- 上述本地候选已获明确授权并完成服务器部署。后端 tracequest-backend:admin-observability-20261111（625783fe9a38…），前端 tracequest-web:admin-observability-20261111（4fd8cd588141…）。
- 为保护其他已上线修复，前端在当时线上 location-scan-only 镜像上仅覆盖 admin-console.html/admin-console.js；index、其他 assets、API helper、图寻扫码、题目入口和网关均保留。服务端运行时沿用原镜像，仅替换已验收的同摘要应用包。
- 公网可信 TLS 真实 NJUPT 登录、本人身份、超管角色、管理员列表、队伍列表、新提交查询、脱敏审计、总览、本人历史/进度与探针退出均通过；匿名管理接口与靶机网关保持 401。只退出本次新建的探针会话，没有创建或提权生产账号。
- 无迁移，无历史成绩或成员关系回写；数据库已 single-transaction 备份并 CRC 校验，权限 600。Env、只读挂载、端口、网络及共享容器快照一致；现有比赛继续接受真实提交，计数允许正常增长。
- 原始发布契约保存在服务器 release/payload/docs，当前发布补充见 API_PUBLICATION_20261111.md 和 SERVER_OPERATIONS.md；回执及回退路径见本地 0xTrace-ops/artifacts/admin-observability-deploy-20261111/DEPLOYMENT_RECEIPT.json。
- 生产未执行普通管理员真实账号登录、改队/调分、真实选手靶机启停及长时间压力验收；相应授权拒绝和变动流程仅有此前本地验证。本次没有 Git push 或 PR。


## 2026-11-11 安全修复契约

- `GET /api/v1/event`、`GET /api/v1/challenges`、`GET /api/v1/challenges/{code}`、`GET /api/v1/leaderboard` 必须登录，匿名返回 401；健康检查及登录方式查询仍公开。前端未登录时不加载赛事数据。
- 新增 `GET /api/v1/auth/admin-access`：管理员/最高管理员返回 204，普通选手 403，匿名 401。Nginx 对 `/admin-console.html` 和 `/admin-console.js`（含查询参数）执行该校验，响应 `private, no-store`；内部鉴权路径不公开。管理 API 自身权限校验继续保留。
- 答题 DTO 的 `answer` 仅接受非空 JSON 字符串，最长 512 字符；数字、布尔、对象、数组、null 均返回 400。已解题再次提交错误答案返回 `incorrect`、`pointsAwarded: 0` 并记录错误；相同幂等键重放仍返回原响应，正确重交继续返回首次完成数据。历史解题成绩不变。
- 已登录用户使用不支持的 HTTP 方法返回 405/METHOD_NOT_ALLOWED 和 Allow；路径或查询参数类型错误返回 400/VALIDATION_ERROR；不存在题目的正常 GET 返回 404。
- 网关 `/entry/{code}/` 仅查询和转发已有环境，GET/HEAD 不自动启动环境，不删除其他靶机。未启动返回 409，选手在详情页显式 POST 启动；停止仍显式 DELETE。过期实例状态查询返回 STOPPED，回收由调度或显式生命周期操作处理。
- 入口网关后台查询最长 5 秒；上游错误文本 HTML 转义。向靶题转发时移除本站 tq_session、Authorization 和 Proxy-Authorization，并拒绝靶题覆盖 tq_session Cookie。
- 网关与靶题仍共用浏览器来源；上述凭据过滤不等于浏览器来源隔离。完整的独立来源部署及历史成绩审计未包含在本次补丁。


### 2026-11-11 图寻仅扫码与判题重试校验

- 图寻题的文本答案接口始终返回 409 `LOCATION_QR_REQUIRED`；历史标准答案、哈希和任意文字均不进入判题或计分，不新增提交/解题记录。前端仍使用 `/api/v1/location-verifications`。
- 所有提交每次先校验当前题型、赛事开放时间窗、赛事队伍、当前容器 Flag 或二维码凭证，再进行幂等回放与计分。二维码撤销、过期、轮换或赛事关闭后，即使携带曾成功的 Idempotency-Key/requestId，也不回放旧成功响应。
- 幂等快照在计分入口使用新作用域 `validated-scoring-v1`，指纹包含题目、当前队伍、提交来源和判题结果。旧作用域记录保留，读取路径不再使用它们。requestId 还校验原队伍、来源和结果，跨题/跨队/换内容或规则变化导致的冲突返回 409 `IDEMPOTENCY_CONFLICT`。
- 文本题与位置题共用账号锁和题目锁，计分仍在同一事务中提交，同队同题只计一次。Web 容器题只能使用当前本队实例的 Flag；其他队伍、其他题目和旧实例 Flag 均不被当作当前凭证。
- 无数据库迁移，不修改既有成绩或二维码，不改前端、附件、共享服务或参赛者容器。本次测试覆盖旧通用答案、历史缓存绕过、二维码失效、跨队 Flag 与并发奖励；静态二维码被他人拍摄转发不等同于现场定位证明。


### 2026-11-11 运行时实例保留与删除确认

- 重复启动 READY 容器时，Docker 状态查询异常必须只读确认是否真正不存在。容器仍存在或 Docker 不可用时返回 503 `CONTAINER_RUNTIME_ERROR`，保留原实例、Flag、期限和容器，禁止自动销毁重建。状态查询命令采用不超过 5 秒的短预算。
- 显式删除或定时清理必须在 Docker 删除成功，或只读查询确认完整名称的容器确实不存在后，才删除实例记录/标记停止。删除失败或无法确认时返回/报告受控错误并保留记录；批量任务继续处理其他实例。
- 启动失败后的清理再次失败时保留明确的 FAILED 状态供重试；归档/删除题目遇到未确认的容器清理时明确报错，避免假成功或资源丢失。
- 无数据库迁移、不改变判题/角色/题目规则；主动销毁、到期重建或靶题自身进程重启后的内存进度并不由平台持久化。

# TraceQuest 前端 API 文档

## 2026-11-05：南邮统一身份认证登录（本次新增）

**实现状态：** Java 后端与 Vue 入口已实现并启用于本地 Docker 预览。学校认证已完成本地测试；Docker 浏览器已检查真实本地登录、手机排版及拦截响应。学校测试使用本地 HTTP fixture，不使用真实学校账号。尚未进行学校生产联调、生产数据库迁移或远端部署。本节为现有认证契约的增量；历史原型章节不能覆盖本节或真实 Java Controller。

### 入口与会话

| 方法 / 路径 | 权限 | 行为 |
|---|---|---|
| GET /api/v1/auth/providers | 公开 | 返回 {"local":true,"njupt":false}；njupt 由服务器开关决定 |
| POST /api/v1/auth/njupt/login | 公开、受登录限流约束 | 接收学号与学校密码，经学校认证成功后签发本站 HttpOnly Cookie |
| POST /api/v1/auth/login | 公开、受登录限流约束 | 保留原赛事账号和管理员登录；njupt: 前缀的学校身份禁止走本地密码入口 |
| GET /api/v1/auth/me | 已登录 | 契约保持不变，以本站账号角色和停用状态为准 |

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
- 后续按关联复用同一 userId，保留队伍、积分、解题记录与停用状态，不按同名学号接管已有本地账号。
- 并发首登由唯一约束去重，失败事务整体回滚；冲突且无身份关联时返回 409，不接管预占用户名。
- 学校身份被重置本地密码后也不能走本地登录；停用账号或已改为管理员角色的关联账号不能走学校入口。
- 学校和本地登录共用按 IP 的 login 配额，密码失败累计到 njupt:学号。验证码、网络故障不计作密码错误；只有认证成功后才建立账号。

### 错误响应

沿用 {"error":{"code":"...","message":"...","details":[]},"requestId":"..."} 包络，敏感上游正文不透传。

| HTTP / code | 含义 |
|---|---|
| 400 VALIDATION_ERROR | 学号或密码字段无效 |
| 401 INVALID_CREDENTIALS | 学校拒绝学号/密码 |
| 403 FORBIDDEN | 学校入口未启用，或关联角色不允许 |
| 403 ACCOUNT_DISABLED | 本站账号已停用 |
| 409 NJUPT_CAPTCHA_REQUIRED | 学校要求滑块；当前未集成 AJ-Captcha，停止登录 |
| 409 NJUPT_ACCOUNT_ACTION_REQUIRED | 先完成学校账号设置（code 511/512） |
| 409 CONFLICT | 身份关联冲突，须管理员处理 |
| 429 RATE_LIMITED | 本站限流、失败锁定或学校 429 |
| 503 NJUPT_AUTH_UNAVAILABLE | 学校超时、HTTP 错误、异常响应或身份不一致 |

### 配置与上线前置条件

默认 TQ_NJUPT_AUTH_ENABLED=false；设置为 true 开启。TQ_NJUPT_CHECK_KEY 默认 1629428467008（须为 13 位数字）；TQ_NJUPT_APP_ID 默认 common。这是公开协议参数，不是本站密钥或学校密码。

已有数据库必须先备份并手工应用 src/main/resources/sql/V2__njupt_identities.sql，再开启新后端；应用不会自动迁移既有数据库。本地 Docker 库已备份并手工添加此表，原有账号和业务数据保留；生产库尚未迁移。新建库 schema 与 H2 fixture 已包含此表。

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
  "teamId": "team_01",
  "teamName": "North Signal",
  "nickname": "North",
  "avatarUrl": "https://example.com/avatar.webp",
  "status": "active",
  "lastLoginAt": "2026-10-28T12:00:00Z"
}
```

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

权限：public

```json
{
  "id": "evt_01",
  "name": "TraceQuest Season 01",
  "status": "open",
  "timezone": "Asia/Shanghai",
  "startsAt": "2026-10-28T01:00:00Z",
  "endsAt": "2026-10-28T10:00:00Z",
  "maxTeamSize": 4,
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
  "maxMembers": 4
}
```

未加入队伍时返回 `404`。

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

## 4. 选手端题目

### 4.1 题目列表

`GET /api/v1/challenges`

权限：public / contestant

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

权限：public / contestant

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
  "remainingSeconds": 3600
}
```

同一队伍已经运行该题容器时，接口会复用并返回原实例。

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

### 7.5 公开排行榜

`GET /api/v1/leaderboard?eventId={eventId}&scope=team&page=1&pageSize=50`

权限：public / contestant

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
  "maxTeamSize": 4
}
```

`PATCH /api/v1/admin/event`

```json
{
  "status": "open",
  "maxTeamSize": 5
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

### 10.2 后台排行榜

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

### 10.3 后台总览与审计

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/admin/dashboard` | 赛事、题目、账号、提交与近期活动 |
| `GET` | `/api/v1/admin/audit-log` | 分页审计日志 |

审计查询参数：`action`、`entityType`、`actor`、`page`、`pageSize`。

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

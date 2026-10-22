# TraceQuest 接口与数据契约文档

> 文档版本：1.5
> 盘点日期：2026-10-22
> 适用代码：当前仓库 `tracequest-app`（Vue + Vite 前端、Node.js 轻量 API）

## 1. 当前实现范围

> **接口文档维护约定：** 后续凡新增、修改、废弃接口，或改变请求/响应字段、权限、幂等/计分行为，必须在同一变更中更新本文件对应章节及版本/变更记录；若仅改 UI 而未改变接口契约，应在“当前实现范围”或功能现状中说明，不得把规划接口写成已实现接口。

仓库仍是以 Vue/Vite 为主的前端原型，但已新增 `server.mjs` 提供认证会话和图寻二维码签发/核验 API。其他题库、答案判题、排行榜、账号管理与附件数据仍主要依赖浏览器本地存储，不能把整站视为已生产化。

- 二维码 API 通过生产 Nginx `/api/*` 代理到 Node 服务；开发由 Vite 代理。
- 管理员/选手登录由服务端校验并建立 HttpOnly 会话 Cookie；前端存储的角色标记仅用于界面显示，不是授权依据。
- 二维码凭证哈希、二维码核验记录及审计条目持久化在 Docker 命名卷的 JSON 文件中。
- 二维码核验对同队同题幂等；其他题目答题及排行榜逻辑尚未迁移到服务端。

## 2. 运行环境与入口

| 项目 | 当前值 |
|---|---|
| 前端 | Vue 3.5 + Vite 5，入口 `src/main.js` → `src/App.vue` |
| 管理控制台 | `public/admin-console.html`，由主应用 iframe 嵌入 |
| 开发地址 | `http://localhost:5174/` |
| 生产静态站点 | Docker Nginx 容器端口 8080；默认路径 `/` |
| 应用页状态 | 通过 URL 查询参数 `?view=` 选择，不是服务端路由 |
| 支持的 view | `home`、`login`、`guide`、`tasks`、`detail`、`leaderboard`、`history`、`about`、`admin`（管理员角色才进入） |
| 持久化 | Web Storage + IndexedDB；无服务器数据库 |

示例：`/?view=tasks`、`/?view=detail`、`/?view=history`、`/?view=about`。页面跳转主要由 Vue 状态控制，刷新时读取 `view` 参数初始化。`about` 是选手端「关于寻迹」页面，呈现出题人的想法；页面文案由管理员后台编辑。

## 3. 当前 HTTP 请求/响应清单

### 3.1 已实现的服务端 API（非 `/api/v1`）

以下是当前 `server.mjs` 实际提供的接口。请求均为同源；JSON 写请求需发送 `Content-Type: application/json`。错误响应目前采用简单形状 `{ "error": "error_code" }`，并非第 8 节目标契约中的统一错误对象。

| Method | Path | 权限 | 说明 |
|---|---|---|---|
| `GET` | `/api/health` | public | 健康检查，成功返回 `{"ok":true}`。 |
| `POST` | `/api/auth/login` | public | 使用预配置管理员或选手账号登录，设置 HttpOnly 会话 Cookie。 |
| `GET` | `/api/auth/session` | public/session | 返回当前会话 `{username, role, teamId}`；无有效会话返回 `401`。 |
| `POST` | `/api/auth/logout` | public/session | 删除当前进程中的会话并清除 Cookie，返回 `{"ok":true}`。 |
| `POST` | `/api/admin/location-qr` | admin | 为已发布图寻题签发/轮换现场二维码凭证。 |
| `DELETE` | `/api/admin/location-qr?code={code}` | admin | **当前不可用**：虽然有路由处理代码，但 `/api/*` 全局方法门禁只允许 GET/POST，会在到达该处理器前返回 `403 origin_rejected`。 |
| `POST` | `/api/location-verifications` | contestant | 验证现场二维码，并按队伍/题目幂等记录完成与积分。 |

登录请求示例：

```http
POST /api/auth/login
Content-Type: application/json

{"username":"field_129","password":"<password>"}
```

成功返回 `200`：`{"username":"field_129","role":"contestant","teamId":"team-01"}`，并通过 `Set-Cookie` 设置会话；凭据错误返回 `401 {"error":"invalid_credentials"}`。字段校验失败为 `400 invalid_credentials_format`，登录限流/临时锁定为 `429`。

`/api/admin/location-qr` 请求字段：`code`（2–24 位 `[A-Z0-9_-]`）、`title`、`category`（必须为 `图寻`）、`status`（必须为 `published`）、`points`、`firstSolveBonus`、`secondSolveBonus`、`thirdSolveBonus`、`otherSolveBonus`，以及可选未来时间 `expiresAt`。成功返回 `201`：

```json
{"challengeCode":"GEO-01","title":"示例题目","payload":"TRACEQUEST|LOCATION|GEO-01|<随机凭证>","createdAt":"2026-10-01T00:00:00.000Z","expiresAt":null,"generation":1}
```

明文 payload 只在签发响应中返回，持久化文件只保存凭证的 SHA-256 哈希。轮换会使旧 token 失效。撤销请求成功返回 `{"ok":true}`。

`/api/location-verifications` 请求为 `{"payload":"TRACEQUEST|LOCATION|GEO-01|<token>","requestId":"<UUID>"}`。成功返回 `result: "verified"` 或幂等重复时 `result: "already_verified"`，并包含 `verificationId`、`challengeCode`、`username`、`teamId`、`solvePlace`、`basePoints`、`bonusPoints`、`pointsAwarded`、`solvedAt`。同一队伍同一题只计一次；同一 `requestId` 重放返回既有结果。

常见错误码包括 `400`（字段/凭证无效或过期）、`401`（未登录或角色不符）、`403 origin_rejected`（来源检查失败）、`404`（路由/题目不存在）、`413`（JSON 体过大）、`415`（非 JSON）、`429`（限流或临时锁定）。API 对带 `/api/` 的请求检查 `Origin`：写请求必须有与 `Host` 匹配的来源；GET 允许不带 `Origin`。当前 `/api/*` 全局门禁仅放行 GET/POST；虽然代码定义了 DELETE 撤销处理器，但该处理器在全局门禁后面，实际 DELETE 会先返回 `403 origin_rejected`，因此目前无法通过此 API 撤销二维码。这是实现与文档目标的已知差异，需修复服务端方法门禁或改为 POST 撤销后，才可视为可用。

### 3.2 尚未实现的应用数据接口

以下能力目前没有服务端业务 API：赛事数据、题目列表/详情、附件上传与下载、常规文本/Flag 答案判题、提交历史、服务端排行榜、正式选手账号 CRUD、赛事配置 CRUD、后台审计查询。相关演示数据仍在浏览器本地或前端代码中；不得将第 8 节目标接口误认为已部署能力。

静态资源由 Vite 开发服务器或 Nginx 提供；它们不是业务 API。

### 3.3 认证与输入安全约定（当前实现）

- **登录字段：** `username`、`password` 必须为 JSON 字符串。用户名先 `trim()`，仅允许 `[A-Za-z0-9_.-]` 且长度 1–64；密码非空且 UTF-8 长度不超过 256 字节；请求体上限 4 KiB。超限/格式错误会拒绝，不会静默截断。登录密码只参与服务端验证，成功后前端清空密码输入，不保存到 Web Storage。
- **密码验证：** 生产密码使用异步 Node `scrypt`（64 字节派生值），常量时间比较；未知账号执行 dummy scrypt 校验以减轻账号枚举时序差异。明文密码仅在本机演示配置可用。
- **登录防护：** 每客户端地址登录接口最多 10 次/分钟；同一客户端地址与用户名组合在 15 分钟窗口内 8 次失败后临时锁定。其他限制为二维码签发 30 次/分钟、二维码核验 20 次/分钟。触发限流返回 `429` 和 `Retry-After`。计数器为进程内存数据，重启会清空；不能替代生产级共享限流/WAF。
- **来源与 Cookie：** `/api/*` 的写请求必须带与请求 `Host` 匹配的 `Origin`，缺失或不匹配即拒绝；GET 可无 `Origin`。会话凭证只放 HttpOnly Cookie，不暴露给 JavaScript；生产使用 `Secure`，SameSite 为 `Strict`。
- **输入处理：** 登录、二维码 JSON 请求限制为 4 KiB；验证码/二维码字段做格式验证；题目编号、标题、奖金、过期时间均在服务端校验。二维码 token 为 32 字节随机值，只返回一次明文，持久化 SHA-256 哈希。
- **账号/授权：** 没有注册 API；后台账号资料表只是浏览器端演示数据。后台 API 必须依赖服务端角色会话，不能信任 `sessionStorage`、URL 参数或前端隐藏菜单。

**实现边界：** 当前认证会话和限流计数器存在单个 Node 进程内存；二维码状态/审计以 JSON 文件原子替换后存储在数据卷中。单副本部署适合当前演示，不保证多副本并发一致性、跨重启会话持久性或高可用。位置二维码核验可信度高于前端自报答案，但仍不能证明选手的设备实际位于二维码张贴点（二维码照片/转发仍可能作弊）。常规文本答案、解题记录、排行榜仍由前端演示实现，不是可信判题。

## 4. 当前浏览器端存储契约

### 4.1 `sessionStorage`

| Key | 类型/示例 | 用途 |
|---|---|---|
| `tracequest-auth-session-v1` | JSON：`{"username":"...","role":"admin|contestant"}` | 当前标签页登录态；关闭标签页后通常清除 |
| `tracequest-admin-session-v1` | 字符串 `authorized` | 前端管理员入口标记；仅 UI 级门禁，不是安全授权 |

登录凭据由 `server.mjs` 校验，成功后设置 `tq_session` Cookie（`HttpOnly; SameSite=Strict; Max-Age=28800`；生产环境加 `Secure`）。会话在 Node 进程内存中维护，访问时滑动续期为 8 小时；进程重启会使已有会话失效。浏览器不把密码写入 Web Storage。`tracequest-auth-session-v1` 中的角色信息只是 UI 缓存，真正权限由服务端 Cookie 会话校验。

**注册关闭：** 当前没有自助注册接口。有效账号由部署配置提供：管理员通过 `TQ_ADMIN_USERNAME` 与 `TQ_ADMIN_PASSWORD_HASH`，选手通过 `TQ_CONTESTANTS_JSON`。本机 Docker 开发配置可设 `TQ_DEMO_AUTH=true` 并使用管理员 `1 / 1`；该演示模式仅用于本机开发，不得暴露到公网。生产环境强制禁用演示模式，管理员和每位选手均需使用 `scripts/hash-password.mjs` 生成的 `scrypt$...` 哈希；不配置生产选手账号时服务启动失败。

### 4.2 `localStorage`

| Key | 推荐值形状 | 当前读写方 | 用途及注意事项 |
|---|---|---|---|
| `tq-admin-challenges-v1` | `Challenge[]` | 管理页写入；前台读取 | 题目配置；明文保存 `answer`，任何同源脚本/用户都能读取 |
| `tq-admin-accounts-v1` | `Account[]` | 管理页 | 本地选手资料演示，不创建服务端认证账号；读取时会剔除历史 `password` 字段，CSV 不应包含密码 |
| `tq-admin-event-v1` | `EventConfig` | 管理页 | 赛事名称、状态、时区、起止时间 |
| `tq-admin-activity-v1` | `Activity[]`，最多保留约 30 条 | 管理页 | 本地管理活动日志 |
| `tracequest-about-content-v1` | `AboutPageContent`（见下方 JSON） | 管理页写入；前台「关于」页读取 | 关于页面文案；仅当前浏览器来源下的 localStorage，不是服务端配置 |
| `tracequest-profile-v1:{encodeURIComponent(username)}` | `Profile` | 前台 | 每个用户名的昵称、头像 Data URL、选手 ID |
| `tracequest-submission-history-v1` | `SubmissionRecord[]` | 前台 | 当前浏览器的提交记录 |
| `tracequest-solved-tasks-v1` | `string[]` | 前台 | 已完成题目的 ID 列表；目前是浏览器本地演示状态 |
| `tracequest-luminary-card-profiles-v1` | `{[challengeId]: {palette, pattern, angle, intensity, scale}}` | 前台 | 已完成题目的 Luminary Card 随机视觉参数；首次完成时生成并固定在当前浏览器，刷新与再次访问保持一致。仅用于前端视觉，不作为解题、身份或计分凭证 |
| `tracequest-solve-order-v1` | `{[challengeId]: SolveEntry[]}` | 前台 | 本地计算“第几名解出”；不同设备不一致 |

`AboutPageContent` 结构如下。页面不再显示独立的眉题、页面名或学校副标题；主要字段是写给新生的开场文字、三位出题人的头像/昵称/个人寄语，以及落款：

```json
{
  "noteLabel": "A NOTE FOR THE ROAD AHEAD",
  "headlinePrimary": "欢迎来到南邮，",
  "headlineSecondary": "愿你在这里找到自己的坐标。",
  "intro": "你好，欢迎来到南邮。我们是三位大二学生，也是这场图寻与 CTF 的出题人……",
  "cards": [
    { "avatar": "data:image/jpeg;base64,...", "label": "出题人 01", "title": "昵称一", "body": "出题人一想对新生说的话" },
    { "avatar": "data:image/jpeg;base64,...", "label": "出题人 02", "title": "昵称二", "body": "出题人二想对新生说的话" },
    { "avatar": "data:image/jpeg;base64,...", "label": "出题人 03", "title": "昵称三", "body": "出题人三想对新生说的话" }
  ],
  "signoffPrimary": "愿你在南邮有新的朋友，",
  "signoffSecondary": "也有慢慢成为自己的勇气。",
  "signature": "— 三位大二出题人",
  "cta": "开始探索"
}
```

头像为可选字段；管理员通过前台账号头像设置组件选择本地图片、方形裁剪并导出 JPEG Data URL，上传文件限制为 8 MB，裁剪结果限制约 1.5 MB。空值时页面以昵称首字作为头像占位。历史 HTTPS/同源头像地址仍可读取。

当前后台路径：管理员登录后进入「后台管理 → 关于页面」，编辑上述字段（含三位出题人的头像文件、昵称和寄语）并点击「保存页面文案」。头像上传时，管理页 iframe 通过同源 `postMessage` 请求 Vue 前台打开与选手账号共用的头像裁剪组件，裁剪完成后回传对应头像 Data URL。保存后，管理页将整个对象写入 `tracequest-about-content-v1`；前台挂载/切换到 `about` 时读取，并通过 `storage` 事件响应另一个同源页面的更新。前台对缺失、类型不符或无法解析的数据回退默认文案，并限制读取字符串长度；前端 Vue 模板按文本插值显示，不将其作为 HTML 执行。该功能**没有新增 HTTP API**：localStorage 是浏览器本地数据，同浏览器/同源可见，不会写入服务器数据库，也不会自动跨设备、浏览器或选手端设备同步。当前管理员门禁与保存能力不能替代服务端鉴权；正式部署若要全体选手同步，应迁移至服务端管理员 API 与数据库，并对每次写操作做授权和审计。

**共享状态限制：** 管理员和选手的本地存储没有按用户隔离。多人共用同一浏览器时，记录和进度可能互相影响。

### 4.3 IndexedDB

数据库：`tracequest-admin-files`，版本 `2`。

| Object store | Key path | 记录形状 | 用途 |
|---|---|---|---|
| `files` | `id` | `{id, code, name, size, type, lastModified, blob}` | 管理员上传的题目附件 |
| `covers` | `id` | `{id, name, size, type, lastModified, blob}` | 管理员上传的题目封面 |

附件/封面二进制目前存储在**管理员当前浏览器**。题目 `files` 元数据没有可靠地与 IndexedDB 里的二进制同步发布到其他选手设备。因此当前跨设备附件下载并未形成服务端能力。生产环境应改为上传到对象存储/文件服务，题目接口只返回文件元数据和授权下载地址。

## 5. 核心数据模型（现状）

### 5.1 Challenge（题目）

```json
{
  "code": "GEO-01",
  "title": "折射场",
  "category": "图寻",
  "difficulty": "中等",
  "points": 400,
  "firstSolveBonus": 100,
  "secondSolveBonus": 60,
  "thirdSolveBonus": 30,
  "otherSolveBonus": 0,
  "status": "published",
  "qrToken": "可选的随机令牌",
  "cover": "/images/natural/example.png",
  "coverAsset": "可选的 IndexedDB 封面 ID",
  "desc": "题目描述",
  "answer": "答案（当前明文，不安全）",
  "files": []
}
```

- `code`：2–24 位大写英数、`_`、`-`，题目唯一标识。
- `category`、`difficulty`：自由文本；`图寻` 表示位置验证题，其他类别通常作为 CTF 文本答案题处理。
- `status`：`draft`、`published`、`paused`。
- `points` 为基础分；正确提交时当前实现按解题顺序额外加分。
- 前台内置了 12 道演示题，题目对象中含明文答案。当前 `applyAdminChallengeSettings()` 仅从管理员存储同步匹配题目的 `points` 和四档 bonus；不会用管理页数据完整替换题库，也没有可靠地按 `published` 过滤。

### 5.2 Account（管理页演示账号）

```json
{
  "user": "field_001",
  "password": "仅演示字段；禁止生产明文保存",
  "team": "North Signal",
  "note": "",
  "created": "2026.10.01",
  "last": "—",
  "status": "active"
}
```

账号状态：`active`、`disabled`。当前管理员账号 `1/1` 是硬编码的演示入口，不应暴露给公网生产系统。

### 5.3 EventConfig（赛事配置）

```json
{
  "name": "TraceQuest",
  "status": "setup",
  "timezone": "Asia/Shanghai",
  "start": "2026-10-01T09:00",
  "end": "2026-10-01T18:00"
}
```

状态：`setup`、`open`、`paused`、`closed`。当前仅前端展示/保存；不阻止用户请求或服务端判题。

### 5.4 SubmissionRecord（本地提交记录）

```json
{
  "time": "14:24:36",
  "date": "2026.10.01",
  "task": "折射场",
  "code": "GEO-01 / GEO LOCATION",
  "answer": "提交内容",
  "correct": true,
  "points": 500,
  "bonus": 100,
  "solvePlace": 1
}
```

错误答案记录 `correct=false`、`points=0`、`bonus=0`。此模型缺少稳定的服务端 `submissionId`、用户 ID、题目 ID 和服务端时间戳，建议 API 化时补齐。

### 5.5 Profile

```json
{
  "nickname": "选手昵称",
  "avatar": "data:image/jpeg;base64,...",
  "id": "TQ-0123ABCD"
}
```

头像当前限制 8 MB 输入图像，前端裁剪后 JPEG Data URL 保存至 `localStorage`；生产建议上传文件并存储 URL。

## 6. 当前交互接口与业务流程

### 6.1 选手登录（本地模拟）

- 输入用户名/密码后由前端处理，无网络请求。
- 演示管理员：用户名 `1`、密码 `1`。
- 其余非空用户名目前走选手身份模拟流程（请以页面校验为准）。
- 成功后写入 `sessionStorage`，管理员进入 `?view=admin`，选手进入题目列表。

### 6.2 答案提交与完成结果（当前为本地模拟，非后端接口）

- 输入答案仍由前端与题目对象中的明文 `answer` 做 `trim + lowercase` 精确比较；该流程仅供原型演示，不能用于正式比赛判题。
- 首次答对时，前端本地写入解题顺序、提交记录、已完成题目；得分按 `points + bonus` 计算，并留在题目详情页展示“恭喜完成”、本次题目得分与解题名次。
- 已完成题目不再显示答案输入/提交控件；重复提交不会新增提交记录或积分。前端的本地防重仅用于演示，不是可信的安全或多设备幂等保障。
- 错误答案仍记录一次提交，得分为 0，并进入提交记录页。
- 尚无服务端答案判题、提交频率限制、队伍维度事务或跨设备进度同步。生产环境必须按 8.3 的服务端契约完成判题、并发排名、幂等计分，并由服务端返回已持久化的首解结果供详情页展示。

### 6.3 图寻位置二维码

管理员 iframe 通过同源 `postMessage` 请求主应用生成二维码；主应用再通过管理员 HttpOnly 会话调用服务端签发接口。iframe 只传题目代码，不再自行生成或写入二维码令牌。

二维码内容格式：

```text
TRACEQUEST|LOCATION|{CODE}|{URL_SAFE_RANDOM_TOKEN}
```

服务端接口：

- `POST /api/admin/location-qr`：仅 admin 会话可用。签发新 256-bit 随机令牌；同题重新签发时立即替换旧凭证。数据库/文件只存 SHA-256 哈希，令牌只在签发响应中返回一次。
- `DELETE /api/admin/location-qr?code=GEO-01`：立即撤销当前凭证。
- `POST /api/location-verifications`：仅 contestant 会话可用。核对令牌、有效期、撤销状态，并以队伍/题目维度幂等记录成绩。

验证流程：摄像头由 HTTPS/localhost `getUserMedia` 获取视频，`jsqr` 在浏览器本地解码；识别到字符串后只将原始 payload 发给同源 API。客户端不自行认定“正确”，不再比较 localStorage 中的 QR token。

**防作弊边界：** 静态实物二维码可拍照/复制/转发，服务端令牌能阻止猜测、篡改、撤销后复用和同队重复领奖，但不能证明扫描发生在现场。严格比赛需结合现场监督或定时轮换动态码；GPS/蓝牙/NFC 可辅助但不能单独视作不可伪造凭证。项目其余前端答案/排行榜仍是演示实现。

### 6.4 题目附件/封面

- 管理端支持本地选择/拖放文件；附件以 Blob 存 IndexedDB。
- 封面支持 PNG/JPEG/WebP/AVIF，最大 10 MB；输入地址或本地上传。
- 管理页面的下载由 `URL.createObjectURL(blob)` 触发浏览器下载。
- 这不是 HTTP 上传，也不能让远程选手设备获得管理员浏览器内的 Blob。

## 7. 管理控制台本地操作

管理控制台目前通过 `public/admin-console.html` 执行这些本地操作：

- 题目：创建、编辑、草稿/上线/暂停、删除、封面/附件管理、位置二维码生成。
- 选手账号：创建、编辑、启用/停用、删除、CSV 导入/导出。
- 赛事：配置名称、状态、时区与起止时间。
- 操作记录：本地保留最近活动。

上述题目、账号资料、赛事设置和活动记录都写入本地浏览器存储，没有对应服务端 CRUD endpoint。账号管理仅维护资料展示，不会创建可登录账号；不得在此保存密码。唯一已接入的服务端管理操作是位置二维码签发与撤销（详见 3.1）。

## 8. 正式后端 REST API（目标契约）

本节列出选手端、排名、账号管理、后台总览、题目 CRUD、附件与封面、赛事和审计等接口契约。**第 3.1 节列出的非版本化路由由 Node 原型提供；本节 `/api/v1` 路由已由本仓库的 Spring Boot 后端（Java 25 + MyBatis-Plus）实现**，落地状态与已知差异如下：

- 已实现：8.2 节接口目录中的全部 `/api/v1` 路由（含后台题目 CRUD、附件/封面上传、账号导入导出、榜单与手动积分调整）。
- 认证实现：采用 Spring Security + JWT，令牌仅通过 `HttpOnly; SameSite=Strict` 的 `tq_session` Cookie 下发，写请求叠加同源校验（等价满足 8.1 的会话与 CSRF 要求）。
- 下载实现：`GET /api/v1/files/{fileId}` 返回 302 跳转到 MinIO 短效预签名地址，不向客户端暴露对象存储凭证。
- 尚未实现：`GET /api/v1/admin/challenges/{code}/location-qr/image` 返回 `501 NOT_IMPLEMENTED`，因为后端未内置二维码渲染库，二维码 PNG 仍由管理端浏览器根据签发响应中的 `payload` 绘制。

### 8.1 通用约定

- 前缀：`/api/v1`；所有时间为 UTC ISO-8601，例如 `2026-10-01T06:24:36Z`。
- 请求/响应：`application/json; charset=utf-8`；上传使用 `multipart/form-data`。
- 目标认证：服务端会话 Cookie（`HttpOnly; Secure; SameSite=Strict`），写操作需同源/CSRF 防护。当前非版本化 API 使用 `tq_session` Cookie 与 `Origin`/`Host` 校验；目标接口需保持或强化此防护，不要在浏览器 localStorage 保存令牌。
- 分页：列表统一支持 `page`（从 1 开始）、`pageSize`（默认 20，最大 100），返回 `{items, pagination:{page,pageSize,total,totalPages}}`。
- 排序：由接口显式定义允许的 `sort` 字段和 `order=asc|desc`，不直接拼接客户端提供的 SQL 字段。
- 角色：`public` 公开、`contestant` 选手登录、`admin` 管理员。每个 Admin 接口都必须服务端执行角色校验。
- 幂等：答案提交、位置核验和支付/加分等写请求接受 `Idempotency-Key`，避免重试重复计分。

统一错误响应：

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "请求字段无效",
    "details": [{"field":"answer","reason":"required"}]
  },
  "requestId": "req_01..."
}
```

建议使用 HTTP 状态：`400` 参数错误、`401` 未登录、`403` 权限不足、`404` 不存在、`409` 状态冲突/重复编号、`413` 文件过大、`422` 业务校验失败、`429` 限流、`500` 服务端错误。

### 8.2 接口目录

#### 认证与个人资料

| Method | Path | 权限 | 说明 |
|---|---|---|---|
| `POST` | `/auth/login` | public | **目标接口**；当前对应 `/api/auth/login`，具体契约见 3.1 |
| `POST` | `/auth/logout` | authenticated | **目标接口**；当前对应 `/api/auth/logout` |
| `GET` | `/auth/me` | authenticated | 目标接口；当前对应 `/api/auth/session` |
| `PATCH` | `/me/profile` | contestant | 更新昵称、头像 URL |
| `GET` | `/me/progress?eventId=...` | contestant | 当前赛事队伍解题进度、累计分数 |
| `GET` | `/me/submissions?eventId=...` | contestant | 当前赛事个人/队伍提交记录，支持分页/筛选 |

#### 赛事与公开数据

| Method | Path | 权限 | 说明 |
|---|---|---|---|
| `GET` | `/event` | public | 当前赛事名称、状态、时间与队伍人数上限 |
| `GET` | `/challenges?eventId=...` | public/contestant | 只返回指定赛事的已发布题目，不得带标准答案或二维码密钥 |
| `GET` | `/challenges/{code}` | public/contestant | 题目详情、附件元数据、选手可见的解题状态 |
| `GET` | `/leaderboard?eventId=...` | public/contestant | 指定赛事的队伍排名与统计；`scope` 仅支持 `team` |
| `GET` | `/events/{eventId}/teams/me` | contestant | 查看当前选手在该赛事的队伍及唯一标识码 |
| `POST` | `/events/{eventId}/teams` | contestant | 在该赛事创建队伍，创建者自动入队并取得标识码 |
| `POST` | `/events/{eventId}/teams/join` | contestant | 使用 `code` 加入该赛事队伍；每个赛事每人只能加入一队 |
| `GET` | `/files/{fileId}` | contestant | 鉴权后的附件下载或短效重定向 |

#### 提交、计分与位置核验

| Method | Path | 权限 | 说明 |
|---|---|---|---|
| `POST` | `/challenges/{code}/submissions` | contestant | 提交 CTF 文本/Flag，由服务端判题并原子记分 |
| `POST` | `/location-verifications` | contestant | 提交现场二维码内容，由服务端验证图寻完成 |
| `GET` | `/me/submissions/{submissionId}` | contestant | 查询本人/本队提交结果 |

#### 后台总览与赛事配置

| Method | Path | 权限 | 说明 |
|---|---|---|---|
| `GET` | `/admin/dashboard` | admin | 总览卡片：题目数、上线题目数、账号数、启用账号数、近期活动摘要 |
| `GET` | `/admin/event` | admin | 获取完整赛事配置 |
| `POST` | `/admin/event` | admin | 创建赛事；`maxTeamSize` 必填且范围为 1–100 |
| `PATCH` | `/admin/event` | admin | 更新赛事名称、状态、时区、起止时间与队伍人数上限 |
| `GET` | `/admin/audit-log` | admin | 分页检索后台操作审计记录 |

#### 题目后台管理（列表、创建、编辑、删除、发布）

| Method | Path | 权限 | 说明 |
|---|---|---|---|
| `GET` | `/admin/challenges` | admin | 分页/搜索/状态筛选题目；建议 `q`、`status`、`category`、`page`、`pageSize` |
| `POST` | `/admin/challenges` | admin | 创建题目，题目编号唯一 |
| `GET` | `/admin/challenges/{code}` | admin | 获取完整题目编辑数据（含答案及私有字段） |
| `PATCH` | `/admin/challenges/{code}` | admin | 编辑题目字段；可选择乐观锁 `version` |
| `DELETE` | `/admin/challenges/{code}` | admin | 删除题目；若已有提交，建议默认拒绝硬删除，使用归档 |
| `POST` | `/admin/challenges/{code}/publish` | admin | 发布/上线题目 |
| `POST` | `/admin/challenges/{code}/pause` | admin | 暂停题目 |
| `POST` | `/admin/challenges/{code}/resume` | admin | 重新开放题目 |
| `POST` | `/admin/challenges/{code}/location-qr` | admin | 为已发布图寻题目生成或轮换位置凭证 |
| `DELETE` | `/admin/challenges/{code}/location-qr` | admin | 撤销当前二维码凭证 |

建议的创建/编辑请求模型：

```json
{
  "code": "GEO-01",
  "title": "折射场",
  "category": "图寻",
  "difficulty": "中等",
  "points": 400,
  "bonuses": {"first":100,"second":60,"third":30,"other":0},
  "description": "题目说明与线索",
  "answer": "服务端私有字段，仅管理接口可写/读",
  "coverUrl": "https://cdn.example/covers/geo-01.webp",
  "status": "draft"
}
```

规则建议：`code` 2–24 位大写英数/`_`/`-` 且唯一；分数为正整数；额外加分为非负整数；`category` 和 `difficulty` 允许自定义文本；只允许管理员读取答案字段。选手端 `GET /challenges*` 永远不能返回 `answer`。

选手端题目响应示例：

```json
{
  "code": "GEO-01",
  "title": "折射场",
  "category": "图寻",
  "difficulty": "中等",
  "points": 400,
  "description": "题目说明",
  "coverUrl": "https://cdn.example/covers/geo-01.webp",
  "attachments": [
    {"id":"file_123","name":"field-notes.txt","size":246,"contentType":"text/plain","downloadUrl":"/api/v1/files/file_123"}
  ],
  "myStatus": "unsolved"
}
```

#### 题目附件与封面上传

| Method | Path | 权限 | 说明 |
|---|---|---|---|
| `POST` | `/admin/challenges/{code}/attachments` | admin | 多部分上传一个/多个题目附件 |
| `GET` | `/admin/challenges/{code}/attachments` | admin | 查看题目附件列表 |
| `DELETE` | `/admin/challenges/{code}/attachments/{fileId}` | admin | 删除附件 |
| `POST` | `/admin/challenges/{code}/cover` | admin | 上传或替换题目封面 |
| `DELETE` | `/admin/challenges/{code}/cover` | admin | 移除封面 |
| `GET` | `/admin/challenges/{code}/cover` | admin | 获取封面管理元数据/下载地址 |

建议上传使用 `multipart/form-data` 字段 `file`；响应返回 `{id,name,size,contentType,downloadUrl,createdAt}`。服务端需限制文件大小、扩展名与 MIME，采用随机存储名并隔离执行权限；选手下载时检查题目发布状态与参赛权限，可返回短时 signed URL。原型当前封面上限 10 MB，类型 PNG/JPEG/WebP/AVIF；题目附件当前未实现服务端上限，正式 API 必须配置限制。

#### 选手账号管理

| Method | Path | 权限 | 说明 |
|---|---|---|---|
| `GET` | `/admin/contestants` | admin | 分页/搜索/状态筛选账号，可按用户名/队伍搜索 |
| `POST` | `/admin/contestants` | admin | 创建账号；密码由服务端安全哈希 |
| `GET` | `/admin/contestants/{userId}` | admin | 获取账号资料和状态，不返回密码/哈希 |
| `PATCH` | `/admin/contestants/{userId}` | admin | 更新队伍、备注、昵称等资料 |
| `PUT` | `/admin/contestants/{userId}/password` | admin | 重置密码；不得在普通账号响应中返回密码 |
| `POST` | `/admin/contestants/{userId}/disable` | admin | 停用账号并使现有会话失效 |
| `POST` | `/admin/contestants/{userId}/enable` | admin | 重新启用账号 |
| `DELETE` | `/admin/contestants/{userId}` | admin | 删除或归档账号；建议保留历史解题审计 |
| `POST` | `/admin/contestants/import` | admin | CSV 批量导入，返回新增/跳过/失败行摘要 |
| `GET` | `/admin/contestants/export` | admin | 导出 CSV，绝不导出密码或凭证 |

建议账号模型：`{id,username,team,note,status,createdAt,lastLoginAt}`；状态 `active|disabled`。批量 CSV 可接受 `username,password,team,note`，密码只用于导入时初始化，导出中禁止含密码。当前原型导出列为 `username,team,status,created,note`（不含密码）；导入要求 `username`、`password` 两列，密码最少 8 位，重复用户名会跳过。

#### 排名与计分管理

| Method | Path | 权限 | 说明 |
|---|---|---|---|
| `GET` | `/leaderboard` | public/contestant | 按 `eventId` 分页返回队伍排名 |
| `GET` | `/admin/leaderboard` | admin | 查看指定赛事全部队伍（含尚未得分的队伍）并按名称筛选 |
| `GET` | `/admin/leaderboard/{teamId}` | admin | 查看队伍积分、全部成员及分页提交日志，支持 `eventId/page/pageSize` |
| `GET` | `/admin/score-events` | admin | 查询指定赛事队伍或账号的得分事件 |
| `POST` | `/admin/score-adjustments` | admin | 赛事规则允许时手工调整积分，必须填写原因并落审计 |

公开榜单建议响应：

```json
{
  "updatedAt": "2026-10-01T06:30:00Z",
  "stats": {"teams":128,"solvedChallenges":9,"endsAt":"2026-10-01T08:44:38Z"},
  "items": [
    {"rank":1,"teamId":"team_01","teamName":"Null Island","solvedCount":9,"score":3650,"isMe":false}
  ],
  "pagination":{"page":1,"pageSize":50,"total":128,"totalPages":3}
}
```

建议排名规则由服务端固定并公开：总分降序、已解题数量降序、达到该分数的时间升序；并发首解必须通过数据库事务/锁决定。后台手动加减分要记录操作者、对象、原因、原值/变更值、时间与请求 ID。

**当前排名实现状态：** 选手端排行榜是 `src/App.vue` 内硬编码的演示数组（含统计数字与时间），没有读取提交记录、账号列表或 `tracequest-solve-order-v1`，也没有排名 HTTP 接口。后台目前没有可持久化、可维护的排行榜/手动改分功能；上面的排名管理路由是后续目标契约。

### 8.2.1 后台总览、赛事、题目、账号与上传的请求/响应样例

以下均是建议的目标协议，实际落地时 API 根路径完整形式为 `/api/v1` 加下列 Path。

**管理员总览** `GET /api/v1/admin/dashboard`：

```json
{
  "event": {"id":"evt_01","name":"TraceQuest Season 01","status":"open","startsAt":"2026-10-01T01:00:00Z","endsAt":"2026-10-01T10:00:00Z"},
  "metrics": {"challengeCount":12,"publishedChallengeCount":9,"contestantCount":128,"activeContestantCount":126,"solvedCount":73,"submissionCount":402},
  "recentActivity": [
    {"id":"audit_01","actor":"admin","action":"challenge.published","entityType":"challenge","entityId":"GEO-01","createdAt":"2026-10-01T06:20:00Z"}
  ]
}
```

**赛事配置** `PATCH /api/v1/admin/event`：

```json
{"name":"TraceQuest Season 01","status":"open","timezone":"Asia/Shanghai","startsAt":"2026-10-01T09:00:00+08:00","endsAt":"2026-10-01T18:00:00+08:00","maxTeamSize":4}
```

响应返回规范化后的完整事件对象；开始时间必须早于结束时间。状态转换建议限制为 `setup → open → paused → open → closed`，具体按赛事管理策略开放。

**创建赛事** `POST /api/v1/admin/event` 使用相同字段，`maxTeamSize` 必填，新赛事默认状态为 `setup`。缩小已有赛事的队伍上限时，如果低于任何现有队伍成员数，服务端返回 `409`。

**创建/加入赛事队伍**：

```json
{"name":"North Signal"}
```

```json
{"code":"TQ-7K9M2Q4XW8"}
```

创建成功后返回 `201`，两个接口均返回：

```json
{"id":"team_01","eventId":"evt_01","name":"North Signal","joinCode":"TQ-7K9M2Q4XW8",
 "captain":true,"memberCount":1,"maxMembers":4}
```

标识码在同一赛事内用于加入，同一账号可在不同赛事加入不同队伍；超员、重复加入或跨赛事使用标识码会被拒绝。

**题目管理** `POST /api/v1/admin/challenges` 或 `PATCH /api/v1/admin/challenges/{code}` 请求：

```json
{
  "code":"GEO-01","title":"折射场","category":"图寻","difficulty":"中等",
  "points":400,"bonuses":{"first":100,"second":60,"third":30,"other":0},
  "description":"从线索定位现场地点。","answer":"管理员设置的答案或核验规则",
  "status":"draft","coverUrl":null
}
```

成功创建返回 `201` 和管理端完整题目对象（可包含答案字段，但不可回传其他凭证）；更新成功返回 `200`。修改 `code` 建议禁止，使用独立不可变 `id` 作为数据库主键。若题目已有人解出，不应物理级联删除历史提交，应改为归档或返回 `409 CHALLENGE_HAS_SOLVES`。

**账号管理** `POST /api/v1/admin/contestants` 请求：

```json
{"username":"field_129","password":"仅创建时提交","teamId":"team_01","note":"现场选手"}
```

成功 `201` 响应：

```json
{"id":"usr_129","username":"field_129","team":{"id":"team_01","name":"North Signal"},"note":"现场选手","status":"active","createdAt":"2026-10-01T06:30:00Z","lastLoginAt":null}
```

响应绝不包含明文密码或密码哈希。`PATCH /admin/contestants/{userId}` 只接受白名单资料字段；改密码独立走 `PUT .../password`。停用账号应使活跃会话失效并保留历史记录。

**附件上传** `POST /api/v1/admin/challenges/GEO-01/attachments` 使用 multipart 字段 `files`（可多文件），返回：

```json
{"items":[{"id":"file_01","name":"field-notes.txt","size":246,"contentType":"text/plain","downloadUrl":"/api/v1/files/file_01","createdAt":"2026-10-01T06:30:00Z"}]}
```

单文件上传也可以用字段 `file`；若采用多文件接口需固定字段名并在前后端保持一致。部分失败时建议返回逐文件成功/失败列表，而不是静默丢弃。

**位置二维码创建/轮换** `POST /api/v1/admin/challenges/GEO-01/location-qr` 请求：

```json
{"expiresAt":"2026-10-01T10:00:00Z","rotate":true}
```

响应示例：

```json
{"challengeCode":"GEO-01","payload":"TRACEQUEST|LOCATION|GEO-01|opaque-token","imageUrl":"/api/v1/admin/challenges/GEO-01/location-qr/image","createdAt":"2026-10-01T06:30:00Z","expiresAt":"2026-10-01T10:00:00Z"}
```

建议二维码原始令牌只在创建时返回，数据库仅存令牌哈希；图片下载需管理员鉴权。重新生成时旧令牌立即撤销。

### 8.2.2 排名查询/后台积分明细样例

`GET /api/v1/leaderboard?eventId=evt_01&scope=team&page=1&pageSize=50` 只支持 `scope=team`。匿名公开时可隐藏账号标识；登录后可返回 `isMe`，不得返回个人隐私字段。

`GET /api/v1/admin/leaderboard/team_01` 可返回积分分解：

```json
{
  "team":{"id":"team_01","name":"North Signal","rank":3,"score":3150,"solvedCount":8},
  "members":[
    {"userId":"usr_01","username":"field_129","nickname":"North","captain":true,"joinedAt":"2026-10-01T06:10:00Z"}
  ],
  "submissionLogs":{
    "items":[
      {"submissionId":"sub_01","userId":"usr_01","username":"field_129","challengeCode":"GEO-01",
       "challengeTitle":"折射场","result":"correct","pointsAwarded":500,"solvePlace":1,
       "source":"answer","submittedAt":"2026-10-01T06:24:36Z"}
    ],
    "pagination":{"page":1,"pageSize":20,"total":1,"totalPages":1}
  },
  "scoreEvents":[
    {"id":"score_01","type":"challenge_solve","challengeCode":"GEO-01","basePoints":400,"bonusPoints":100,"delta":500,"awardedAt":"2026-10-01T06:24:36Z"},
    {"id":"score_02","type":"admin_adjustment","delta":-50,"reason":"赛事裁定","actor":"admin","awardedAt":"2026-10-01T06:25:00Z"}
  ]
}
```

手动积分调整请求 `POST /api/v1/admin/score-adjustments`：

```json
{"teamId":"team_01","delta":50,"reason":"经裁判组复核的补偿分"}
```

限制：`delta` 必须为非零整数、`reason` 必填，记录操作者和调整前后分数；建议双人复核或操作二次确认。

### 8.3 答案提交接口建议（尚未实现）

本次前端完成结果交互没有新增 HTTP 请求；当前仍是 6.2 所述本地模拟。正式接入时，应实现本节建议接口，确保首解排名、积分和重复提交结果由服务端权威返回，不能信任客户端传入的答案正确状态、分数、排名或已完成标记。

请求 `POST /api/v1/challenges/{code}/submissions`：

```json
{
  "answer": "flag{example}",
  "requestId": "客户端生成的 UUID"
}
```

响应（不泄漏标准答案）：

```json
{
  "submissionId": "sub_01...",
  "challengeCode": "CRY-02",
  "result": "correct",
  "completion": "first_solve",
  "solvedAt": "2026-10-01T06:24:36Z",
  "basePoints": 400,
  "bonusAwarded": 100,
  "pointsAwarded": 500,
  "solvePlace": 1,
  "progress": {"solved": 3, "total": 12}
}
```

`result` 为 `correct` / `incorrect`。成功首解响应 `completion` 为 `first_solve`；同一赛事队伍、同一题再次正确提交应返回 `result: "correct"`、`completion: "already_solved"`，并回传首次完成时保存的 `solvedAt`、`basePoints`、`bonusAwarded`、`pointsAwarded`、`solvePlace`，但不得再次发奖或创建第二条有效解题记录。未加入题目所属赛事队伍的账号不能提交。同一 `Idempotency-Key` 重放也必须返回原结果，不重复计分。客户端以此结果显示“恭喜完成”、该队伍本题实际获得分数和解题排名；若题目已完成，应隐藏再次提交入口。错误答案是否入库、是否限次由赛事策略决定。

推荐响应语义：

| HTTP | 条件 | 客户端处理 |
|---|---|---|
| `200` | 判题结果（首次正确、错误、或已完成幂等重放） | 按响应中权威积分/名次更新完成态；`already_solved` 不增加分数 |
| `400` | 请求字段/答案格式不合法 | 显示字段校验错误 |
| `401` / `403` | 未登录或无参赛权限 | 重新登录或提示无权限 |
| `404` | 题目不存在或不可见 | 提示题目不可用 |
| `409` | 幂等键复用但请求内容冲突（可选约定） | 不重试该键，提示刷新/重新提交 |
| `429` | 判题频率超限 | 提示稍后重试 |

### 8.4 二维码接口：当前实现与目标契约

当前接口并非本节 REST 路径：签发/轮换为 `POST /api/admin/location-qr`，选手核验为 `POST /api/location-verifications`，详细请求/响应见 3.1。撤销虽定义为 `DELETE /api/admin/location-qr?code=...`，但被当前全局方法门禁拒绝，实际不可用。下面的 `/api/v1` 形式仅为未来版本化 API 建议，尚未实现。

扫描端目标请求：

```http
POST /api/v1/location-verifications
Content-Type: application/json
Idempotency-Key: <uuid>
```

```json
{"payload":"TRACEQUEST|LOCATION|GEO-01|<opaque-token>"}
```

服务端返回与答案提交相同的积分/首解结构，并验证令牌是否对应已发布图寻题、是否撤销/过期、是否重放。二维码令牌不得作为单独前端授权条件。

### 8.5 排名、账号、后台功能与现状对照

| 功能 | 当前前端原型 | 服务端接口状态 |
|---|---|---|
| 公开排行榜 | 硬编码演示内容 | 尚未实现；见 `GET /leaderboard` |
| 排名后台维护 | 没有真实管理页数据源 | 尚未实现；见 `/admin/leaderboard*`、`/admin/score-adjustments` |
| 账号列表、创建、编辑、启停、删除 | 管理页面写 `localStorage` | 尚未实现；见 `/admin/contestants*` |
| 账号 CSV 导入/导出 | 浏览器本地解析/生成文件 | 尚未实现；建议服务端导入和审计 |
| 总览统计 | 从本地题目/账号数组统计 | 尚未实现；见 `GET /admin/dashboard` |
| 赛事配置 | 本地写 `tq-admin-event-v1` | 尚未实现；见 `/admin/event` |
| 关于页面文案 | 管理后台「关于页面」编辑器写入 `tracequest-about-content-v1`，选手端 `?view=about` 读取 | 尚未实现服务端读写 API；本地浏览器存储，不跨设备同步 |
| 题目新增/编辑/删除/发布 | 本地写 `tq-admin-challenges-v1` | 尚未实现；见 `/admin/challenges*` |
| 题目答案 | 管理员本地明文配置，选手端静态演示答案亦在前端 | 必须迁移至服务端私有字段 |
| 附件/封面 | 管理员浏览器 IndexedDB | 尚未实现；见上传/删除路由 |
| 现场二维码 | 服务端签发/核验、SHA-256 凭证哈希、队伍幂等与审计；PNG 在浏览器绘制 | 已实现轻量单 API 实例，JSON 命名卷存储；不具备多实例事务/现场证明 |
| 活动审计 | 浏览器本地最多保留约 30 条 | 尚未实现；见 `GET /admin/audit-log` |

### 8.6 服务端与数据库建议实体

为完整支持上述接口，建议至少建立以下实体（字段类型按实际数据库调整）：

- `users`：`id, username, password_hash, role, status, created_at, last_login_at`
- `teams`：`id, event_id, name, join_code, created_by, created_at`；唯一约束为 `(event_id, name)` 与 `join_code`
- `team_members`：`id, event_id, team_id, user_id, joined_at`；唯一约束为 `(event_id, user_id)`
- `events`：`id, name, status, timezone, starts_at, ends_at, max_team_size`
- `challenges`：`id, event_id, code, title, category, difficulty, points, bonuses_json, description, answer_secret, status, version, published_at`
- `challenge_files`：`id, challenge_id, storage_key, original_name, size, content_type, created_at`
- `submissions`：`id, user_id, team_id, challenge_id, answer_digest_or_encrypted, result, points_awarded, submitted_at, request_id`
- `solves`：`id, user_id/team_id, challenge_id, solved_at, solve_place, base_points, bonus_points`；对队伍/题目建立唯一约束
- `location_tokens`：`id, challenge_id, token_hash, expires_at, revoked_at, created_by`
- `score_adjustments`：`id, team_id/user_id, delta, reason, admin_id, created_at`
- `audit_logs`：`id, actor_id, action, entity_type, entity_id, before_json, after_json, request_id, created_at`

## 9. 生产迁移与安全验收清单

1. 增加后端服务与数据库；把题目答案从前端 bundle/localStorage 移除。
2. 实现密码哈希（Argon2id/bcrypt）、安全会话 Cookie（HttpOnly、Secure、SameSite）或合适的短期令牌方案；管理员每个请求都在服务端鉴权。
3. 服务器端按赛事内队伍持久化解题记录，并在事务中执行判题、首解排名和计分。
4. 二维码仅提交随机凭证，服务端验证题目状态、过期/撤销/重放策略，成功后幂等发奖。
5. 文件与封面改为服务端上传或对象存储；附件下载检查赛事权限，限制大小、类型并生成安全文件名。
6. 对登录、答案提交、二维码验证添加速率限制、审计与异常监测。
7. 管理员操作记录、赛事配置、账号禁用状态必须写入服务端数据库。
8. 增加接口测试、权限测试、并发首解积分测试、备份/恢复与迁移流程。

## 9.1 Luminary Card 卡片素材与状态说明

- 已完成题目的卡面层级、箔面、纹理与星形标记移植自用户提供的 [Johnlzx/luminary-card](https://github.com/Johnlzx/luminary-card) 项目；本项目保留其 `public/vendor/luminary-card/THIRD_PARTY.md` 来源与字体许可文件。
- 任务卡片仍采用原有竖向 **3:4** 尺寸与任务列表网格，仅完成态卡片替换为该项目卡面层；未完成题目继续显示自然素材封面。
- 随机配置只保存在当前浏览器 `localStorage`。跨设备同步、可信完成状态和排行榜分数仍需服务端接口实现；不得信任客户端的完成状态或随机参数。

## 9.2 接口文档变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.5 | 2026-10-22 | 赛事队伍改为赛事内作用域，新增赛事创建队伍人数上限、选手建房/标识码入队、仅队伍排名，以及后台队伍成员与提交日志接口。 |
| 1.4 | 2026-10-08 | 第 8 章 `/api/v1` 契约由 Spring Boot 后端（Java 25、MyBatis-Plus、MinIO、Redis）实现：新增认证、赛事、题目、提交计分、位置核验、榜单、账号管理与审计接口及统一错误结构；记录二维码图片接口返回 501 等已知差异。 |
| 1.3 | 2026-10-02 | 记录题目完成后详情页的得分/排名/恭喜结果展示与本地重复提交不重复计分；明确该能力当前仍属前端模拟，并补充未来服务端答案提交的幂等响应契约及文档同步约定。 |
| 1.2 | 2026-10-02 | 盘点认证与图寻二维码服务端接口、请求/响应、安全边界及未实现能力。 |

## 10. 代码位置索引

| 主题 | 文件/符号 |
|---|---|
| 登录态、角色、页面状态 | `src/App.vue` 顶部状态定义、登录与退出函数；服务端会话在 `server.mjs` |
| 演示题库与题目合并 | `src/App.vue` `tasks`、`loadChallenge...` 相关逻辑 |
| 本地答案提交/计分 | `src/App.vue` `submitAnswer()`、`saveSubmission()` |
| 扫码与 QR payload | `src/App.vue` `openQrScanner()`、`scanQrFrame()`、`verifyLocationQr()`；服务端在 `server.mjs` |
| iframe 二维码消息 | `src/App.vue` `handleAdminMessage()`；`public/admin-console.html` `generateChallengeQr()` |
| 管理员本地 CRUD | `public/admin-console.html` inline script |
| 关于页面与编辑器 | `src/App.vue` `defaultAboutContent`、`readAboutContent()`；`public/admin-console.html` `loadAboutEditor()`、`saveAboutContent()` |
| IndexedDB 文件/封面 | `public/admin-console.html` `attachmentDBPromise`、`attachmentTransaction()` |
| 前端入口 | `src/main.js` |
| 容器部署 | `Dockerfile`、`compose.yaml`、`compose.production.yaml`、`nginx.conf` |

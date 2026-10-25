# TraceQuest 后端接口契约

> 版本：2.4 · 更新：2026-10-25
> 后端：QAQxianyuQAQ/0xtrace-backend，前端：Levia808/0xtrace；本次均为 fix/reliability-20261025 分支，未合并 main。

## 维护规则

任何接口新增、修改、废弃，或权限、请求/响应字段、错误码、幂等/计分行为变化，必须与代码在同一变更中更新本文及变更记录。必须区分“已接入”“后端存在但 UI 未使用”“缺失/待实现”“未经联调验证”，不得以本地模拟成功代替后端成功。

本文取代旧的 Node 模拟 API 与 v1 规划文档。后端完整字段契约见相邻仓库 `API.md`、Controller、DTO、VO；真实实现优先于旧规划描述。

## 1. 路径、认证和传输

- 全部业务接口前缀为 **`/api/v1`**，包括 `/api/v1/health`。旧 `/api/auth/*`、`/api/admin/location-qr` 已退出运行路径，不做静默兼容。
- 前端使用同源 Cookie：`credentials: same-origin`。JWT 仅在 HttpOnly Cookie 中，不写入 localStorage/sessionStorage，不用浏览器角色标记授权。
- 写请求由浏览器携带 Origin，网关保留原始 Host，后端执行同源校验。JSON 请求发送 `Content-Type: application/json`；multipart 不手设该头，由浏览器生成 boundary。
- 开发通过 Vite `/api/v1` 代理 Java 8080；生产 Nginx 使用同一路径代理。不能同时运行旧 Node 模拟认证/二维码服务。
- 时间为 UTC ISO-8601；后台 datetime-local 保存时转为 UTC，显示时转为本地时间。
- JSON 成功响应直接返回对象，不是 `{data:…}` 包装。DELETE 成功通常为 `204`，客户端不能强行解析 JSON。
- 分页参数：`page`（从 1 开始）、`pageSize`（最大 100），响应：

```json
{"items":[],"pagination":{"page":1,"pageSize":100,"total":0,"totalPages":0}}
```

题目/后台账号/提交记录通过统一 `allPages()` 读取后续页，避免原界面只显示前 20 或 100 条；榜单当前显示第一页，未伪装为全量榜单。

统一错误响应：

```json
{"error":{"code":"VALIDATION_ERROR","message":"请求字段无效","details":[{"field":"answer","reason":"required"}]},"requestId":"req_..."}
```

客户端按 HTTP 状态处理失败，保留 `code` 和 `requestId`；不会把 HTML 代理兜底页面当成成功数据。401 会清除当前账号的界面数据，403 不代表未登录；429 提示稍后再试。504/网络异常不能触发本地假判题或假保存。

## 2. 选手端：已接入

以下 Path 均省略 `/api/v1`。

| Method | Path | 对接用途 |
|---|---|---|
| GET | `/health` | 真实后端健康检查 |
| POST | `/auth/login` | `{username,password}` 登录；成功只返回账号字段与 Cookie |
| GET | `/auth/me` | 替代旧 `/auth/session`；恢复身份、昵称、头像 URL、真实 userId |
| POST | `/auth/logout` | 服务端登出成功后清空本地 UI；失败不假装退出成功 |
| GET | `/event` | 赛事名称、状态、起止时间 |
| GET | `/challenges` | 题目列表；只渲染后端允许展示的题目，不混入演示题 |
| GET | `/challenges/{code}` | 详情与真实附件；URL `?view=detail&code=…` 可刷新恢复 |
| POST | `/challenges/{code}/submissions` | 由后端判题与计分，禁止前端下载/比较标准答案 |
| POST | `/location-verifications` | 现场二维码核验，与文本提交共用结果结构 |
| GET | `/me/progress` | 本人/本队已解题编号与累计分数 |
| GET | `/me/submissions` | 本人/本队提交记录，所有分页；没有明文提交答案字段 |
| PATCH | `/me/profile` | 保存昵称（最多 32 字符）；后端支持 avatarUrl，但没有头像文件上传接口 |
| GET | `/leaderboard` | 真实榜单、统计与 isMe；当前 UI 显示第一页 |
| GET | `/files/{fileId}` | 逐请求鉴权后流式返回原文件；200 / 单范围 206 / 416，不再返回302 |

### 2.1 字段适配

| 后端字段 | 当前 UI |
|---|---|
| `ChallengeVO.code / title` | `task.id / name` |
| `description / coverUrl / attachments` | `teaser / cover / files` |
| `myStatus: solved / unsolved` | `已完成 / 未开始`；后端未提供“进行中”，不虚构进行中题目 |
| `SubmissionVO.pointsAwarded / solvePlace` | 完成结果的本题实际得分 / 本题解题名次 |
| `SubmissionVO.source: answer / location` | 提交方式：答案提交 / 现场二维码 |
| `LeaderboardItemVO.isMe` | 标记本人/本队，不再硬编码第六名为自己 |
| `MeVO.userId / nickname / avatarUrl` | 账号资料，不再生成随机用户 ID |

注意：队伍模式下完成与得分以队伍为主体，不是每个队员分别领奖；页面的解题排名是**该题的解出顺序**，不是赛事总排名。进度、任务列表、提交记录切换账号后重新读取，旧账号数据不会直接沿用。

### 2.2 判题、幂等与完成展示

```http
POST /api/v1/challenges/CRY-02/submissions
Content-Type: application/json
Idempotency-Key: <uuid>
```

```json
{"answer":"Flag{example}","requestId":"<同一个 UUID>"}
```

前端提交原始答案，不再统一 lowercase；大小写/trim 规则由服务器决定。答案最长 512 字符。发送期间禁用再次提交。

```json
{
  "submissionId":"sub_...","challengeCode":"CRY-02","result":"correct",
  "completion":"first_solve","solvedAt":"2026-10-15T01:00:00Z",
  "basePoints":400,"bonusAwarded":100,"pointsAwarded":500,"solvePlace":1,
  "progress":{"solved":1,"total":12},"source":"answer"
}
```

- `incorrect`：不加分，详情页显示未匹配，重新读取提交记录。
- `correct + first_solve`：留在详情页，显示“恭喜完成”、**本题历史实际所得总分**和该题解题名次，并隐藏输入/提交控件。
- `correct + already_solved`：返回原始完成数据，不新增积分、解题记录或有效提交记录。`pointsAwarded` 是该题原始奖励，不是本次再加分，客户端不得执行累加。
- 同一 `Idempotency-Key` 重放返回原结果；同键不同内容返回 409 `IDEMPOTENCY_CONFLICT`。
- 重新登录或刷新后，从 `/me/progress`、`/me/submissions` 恢复完成态与名次，不信任旧 localStorage。
- 镭射卡片的随机样式仍可存本机，但只在服务端确认已完成后显示；样式参数不具备判题或计分权限。

### 2.3 二维码差异

签发/轮换：`POST /admin/challenges/{code}/location-qr`，body 为 `{rotate:true,expiresAt?:ISO8601}`。题目代码位于路径，不再发送旧扁平 `title/category/points/firstSolveBonus` 等字段作为计分依据。奖励来自服务器保存的题目。

撤销：`DELETE /admin/challenges/{code}/location-qr` → `204`，不再存在旧 Node 全局方法门禁阻断 DELETE 的问题。

核验：`POST /location-verifications`，body 为 `{payload,requestId}`，同时传 `Idempotency-Key`。结果是上节 `SubmissionResultVO`（`result:correct`、`source:location`），**不是**旧 `verified/already_verified + verificationId/bonusPoints` 结构。

二维码仍由前端 QRCode 根据签发时返回一次的 payload 生成 PNG。后端 `GET /admin/challenges/{code}/location-qr/image` 返回 501，不能请求它作为可用图片。

静态二维码可被拍照转发，令牌与幂等不能证明选手在现场。仍需组织方现场管理/轮换，不得宣传为完全防作弊。

## 3. 管理端：已接入的操作

管理端每次进入通过 `/auth/me` 确认 admin，敏感读写由后端再次鉴权；前端隐藏按钮不是授权机制。

| Method | Path | 用途 / 差异 |
|---|---|---|
| GET | `/admin/dashboard` | 真实赛事概况与近期管理动态 |
| GET / POST | `/admin/challenges` | 分页读取 / 创建题目 |
| GET / PATCH / DELETE | `/admin/challenges/{code}` | 编辑、乐观锁 version、删除；已有解题记录时后端拒绝删除 |
| POST | `/admin/challenges/{code}/publish`、`pause` | 上线/暂停 |
| POST / DELETE | `/admin/challenges/{code}/location-qr` | 生成/轮换/撤销位置码 |
| GET / POST | `/admin/challenges/{code}/attachments` | 真实元数据 / multipart `files` 上传 |
| DELETE | `/admin/challenges/{code}/attachments/{fileId}` | 删除附件 |
| POST / GET / DELETE | `/admin/challenges/{code}/cover` | multipart `file` 上传、元数据、移除封面 |
| GET / POST | `/admin/contestants` | 读取 / 创建真实可登录账号，不再仅维护本地资料 |
| PATCH / DELETE | `/admin/contestants/{userId}` | 按真实用户 ID 编辑/删除，不能用 username 代替 |
| PUT | `/admin/contestants/{userId}/password` | 可选重置密码；密码不写浏览器存储、不预填、不导出 |
| POST | `/admin/contestants/{userId}/disable`、`enable` | 服务端停用/启用账号 |
| POST / GET | `/admin/contestants/import`、`export` | multipart CSV 导入 / 无密码导出，导入结果为 imported/skipped/failed |
| PATCH | `/admin/event` | 真实修改赛事状态和时间 |

题目编辑请求：

```json
{"title":"题目","category":"自定义分类","difficulty":"自定义难度","points":400,"bonuses":{"first":100,"second":60,"third":30,"other":0},"description":"题面","answer":"Flag{example}","coverUrl":"/images/natural/wave-photo.png","status":"draft","version":1}
```

创建时另带 code，更新不允许修改 code。已发布题目取消发布勾选默认保存为草稿，若原状态 paused 则保留 paused。分类/难度最长 32 字符。只有管理响应包含答案；选手响应及前端 bundle 不内置答案。

- 首次保存先创建题目，再逐项上传附件/封面。上传失败会保留已创建题目的 code/version 和未上传文件，重试不会再创建同名题目；错误明确显示，取消不自动删除已入库的题目。
- 编辑已有附件/封面的删除立即作用于后端，不由取消编辑回滚；删除前应自行确认，不能假设后端提供跨请求事务。
- 后端没有题目重命名接口，编辑时编号只读。
- 创建/重置账号密码至少 8 位且最多 72 个 UTF-8 字节（汉字通常占 3 字节）；登录非空且最多 72 字节，所有入口均拒绝超限而不截断。创建账号需符合上述要求的初始密码；编辑时留空不修改，填写则额外调用 PUT 重置接口。
- 队伍名称编辑只支持切换到已存在队伍；新建账号可按 teamName 创建队伍。后端没有独立队伍 CRUD，不应把未知队名的空返回视为成功创建。

## 4. 功能差异与未实现能力

| 项目 | 当前处理 / 限制 |
|---|---|
| 关于页文案、三位出题人头像 | 后端没有内容存取接口；前台保留随站点发布的默认内容，后台相关输入禁用并显示缺失说明，不再伪装本地保存为全站发布 |
| 个人头像文件上传 | 后端只支持 <=512 字符 avatarUrl，不能接受裁剪 data URL；上传按钮禁用，昵称可正常保存 |
| 赛事时间清除 | 后端 PATCH 的 null 表示保持原值，不是清除；后台禁止将已有起止时间清空后误报成功 |
| 开放注册 | 后端没有注册接口；管理员创建真实账号，移除旧“任意账号”演示登录提示 |
| 题目“进行中”状态 | 后端只有 solved/unsolved；筛选仍保留，但无伪造进行中数据 |
| 暂停题目仍可见 | 后端允许查询 paused 题，但 ChallengeVO 不暴露发布状态；提交由服务端拒绝并显示错误，不能把 paused 伪造成可作答状态 |
| 提交的明文答案 | 后端只保留摘要，前端记录栏改为“提交方式”，不伪造原文 |
| 本机旧题库/文件 | 不自动导入、不删除；请备份后用正式后台录入，跨设备以 MySQL/MinIO 为准 |
| 后台榜单维护、积分人工调整、完整审计查询 | 后端路由存在，现有 UI 尚无对应工作台，不声称已完成其 UI |
| MinIO 文件链路 | 本机Docker真实上传/下载及浏览器流程已验收；失败补偿、删除故障和封面验证仍有已复现问题，见2026-10-25报告 |
| 多选手同时首解名次 | 当前发布副本使用题目行锁与READ_COMMITTED；真实MySQL四队并发名次1/2/3/4、同队只计一次已验收；不等于无限负载无竞争 |

新增关于页保存/头像上传 API 时，应另行补齐服务端 MVC、存储与权限契约，不能用现有 `/me/profile` 塞入巨大 base64 文本或临时拼接不存在的接口。

## 5. 网关、存储与安全边界

- API 保留 `/api/v1` 原路径，无跨域 token 传递。前端只处理服务端允许公开的题面。
- MinIO 仅在容器内网；后端用服务端身份读取对象流。每次 GET /api/v1/files/{id} 均重新检查会话、角色、题目发布状态和赛事 OPEN/起止时间。管理员可检查未公开文件；选手不可见时404，匿名401。
- `/storage/` 旧签名代理在生产网关关闭（404），Vite 亦不再代理 S3。MinIO 不开放宿主端口；不可另建公开 S3 入口绕过鉴权。文件响应 private,no-store、nosniff、sandbox；附件保留 UTF-8 原名 Content-Disposition，封面 inline。已下载的数据及已开始的响应流无法远程收回。
- 生产必须 HTTPS、Secure Cookie、真实 JWT/答案加密密钥、关闭 bootstrap。开发凭据不能移植到服务器。
- 本次回归不等于完整安全审计或生产压测；摄像头硬件、现场位置证明与生产并发容量未验证。

## 6. 验证与代码位置

- API 客户端：`public/api-client.js`；前台调用与状态：`src/App.vue`。
- 管理端：`public/admin-console.html` + `public/admin-console.js`；不再使用 localStorage/IndexedDB 模拟 CRUD。
- 完整 Java 全栈部署以 `deploy/compose.full.yaml` 和 `deploy/nginx.conf` 为准，迁移与镜像要求见 `RELIABILITY_FIXES.md`；旧 Node api 构建目标和历史 Compose 不适用于当前 Java 全栈。
- 客户端检查：`npm test`（multipart、204、错误结构、分页、字段映射、去除标准答案），`npm run build`。
- 真实服务联调：`TQ_INTEGRATION_URL=http://localhost:5174 node scripts/smoke-integration.mjs`，仅限可写的一次性开发赛事，会留存测试题目/解题记录。覆盖认证权限、空记录、首解奖励、重复提交、QR、撤销、资料与登出。
- 联调已定位并修复后端新账号空提交/零进度触发 `IN ()` SQL 错误；回归测试加入后端 `TraceQuestApiIntegrationTest`。具体最终执行结果见本次交付，不以文档描述代替已执行测试。

## 7. 变更记录

| 版本 | 日期 | 内容 |
|---|---|---|
| 2.4 | 2026-10-25 | 修复 TQ-01–14；下载改逐请求鉴权流、关闭旧签名代理；真实解码封面；清理任务、空值更新、CSV/密码/奖励/分页边界；进度含调分；事务认领幂等并绑定 body 键；详见第8节 |
| 2.0 | 2026-10-15 | 以真实 Java 后端为准迁移 `/api/v1`；移除前端判题/计分/账号模拟；接入题库、进度、提交、榜单、资料、管理 CRUD、附件封面、账号与赛事设置、题目级二维码；明确缺失能力和联调边界；修复后端空列表查询错误 |
| 1.3 | 2026-10-02 | 原型完成结果展示、重复提交不计分约定及文档同步规则（已被 2.0 实际对接说明取代） |


## 8. 2.4 修复契约及部署要求

- **下载**：GET /files/{fileId} 返回文件而非302。支持一个 bytes 范围（包括后缀），无效/多范围/越界416并含 Content-Range: bytes */size；正常206含 Content-Range，完整200。权限先于范围检查。客户端不能依赖 Location、S3查询参数或可转发签名。
- **封面**：上限10MiB，PNG/JPEG/WebP/AVIF必须与真实文件头一致并通过 FFmpeg 单帧解码；最多16,777,216像素、15秒、2个并行解码进程，只准pipe输入、无网络/本地文件读取、无上传落盘。伪造/损坏/超像素422，过大413。运行镜像必须安装ffmpeg；缺少解码器时不会降级为只信MIME。
- **文件名**：规范化为末级名称，最多255个UTF-8字节，禁止控制字符，扩展名限1–24位ASCII英数；非法400，批量在任何S3写入前整体校验。附件50MiB、整个请求60MiB限制不变。
- **文件生命周期**：新增V3__storage_cleanup.sql（仅CREATE TABLE IF NOT EXISTS）。上传或批量数据库事务回滚后，新对象的精确键写入独立清理事务；封面替换先写新文件，数据库提交后才清理旧对象。后台每30秒重试，引用仍存在的对象不删除；失败任务保留。文件删除若对象存储失败返回500/STORAGE_ERROR并保留元数据，不能假报204。正常删除204。
- **清理边界**：不能让数据库与S3拥有分布式原子事务。若数据库在写入补偿任务时也不可用，服务端会记录需人工重试的精确对象键，运维须跟进；不扫描清空桶。既存历史孤儿对象及悬空引用不自动删除或修复。数据库提交失败前已物理删除的对象无法凭事务恢复，发布前应保留数据库及对象备份。
- **账号/密码**：JSON建号、重置、CSV共用Bean Validation；用户名1–64位英数/_.-，密码至少8位且UTF-8最多72字节；登录非空且最多72字节。ASCII72/汉字24合法，ASCII73/汉字25受控400。备注512、昵称32、队伍名128字符。BCrypt哈希不变，绝不截断或导出密码。
- **CSV**：UTF-8、最多2MiB；逐行校验错误计failed，重复用户名计skipped，合法行imported；业务写入的意外失败回滚整批，不返回假成功摘要。导出在公式危险前缀（含前导空白的=+-@及制表/回车/换行）之前加单引号，再做CSV结构转义。单引号是安全文本的一部分，不保证表格软件用户手工去除后仍安全。
- **可清资料**：PATCH /me/profile 和管理员编辑账号的昵称/头像/备注等可清字段，空串/纯空白显式写NULL，省略字段不变。封面删除显式清空cover_file_id和cover_url并失效缓存。
- **分数**：/me/progress.score与赛事队伍榜单口径一致，包含该赛事队伍的解题基础分、奖励和人工调整，队友相同、跨赛事隔离。建题/编辑的points为1–1,000,000，bonuses.first/second/third/other为0–1,000,000；嵌套级联校验400，单题相加不会int溢出。历史解题奖励不追溯修改。
- **查询参数**：非整数、越界整数、非法类型绑定统一400/VALIDATION_ERROR，含requestId和字段，不返回堆栈；合法负页码/超pageSize仍按既有规则归一化。
- **幂等**：Idempotency-Key最多128位、无控制字符。头按作用域和账号隔离，body.requestId另按账号全局隔离，并绑定来源、路由/题目及请求内容。头与body均在业务写入前认领；同键不同内容409/IDEMPOTENCY_CONFLICT，不能先产生第二次副作用。
- **并发/回放**：数据库唯一键认领、业务写入及成功响应快照在同一READ_COMMITTED事务提交。竞争者等待提交后返回同一首次快照；进程退出/业务失败回滚占位，允许原键重试，不遗留永久pending。body同请求也回放首次快照（可能仍为first_solve）；换新键重复答对返回already_solved。两者都只保留一次解题和一次积分；pointsAwarded是首次所得，不是每次新增分。升级前旧算法保存的幂等键可能409，应使用新的UUID，不应删除旧审计数据。
- **范围未扩充**：自助注册、关于页CRUD、头像文件上传、服务端二维码PNG仍非本次修复范围；静态码不能证明到场。

验证结果见RELIABILITY_FIXES.md。新HTTP字段/权限/错误/幂等变化继续要求同一提交更新本文件。

# 0xTrace / TraceQuest 前端改造交接文档（给 Claude）

> 交接人：前一位 AI（DeepSeek 会话）。接手人：Claude。
> 目标：**把平台题目详情页里那套「连接容器」的 socket 收发面板，按需隐藏掉，直接给选手「进入题目」的入口；并且 UI 必须和平台现有视觉风格一致。**
> 本文所有事实均为线上实测，含路径、类名、接口、命令，可直接照做。

---

## 0. 用户原始需求（逐字）

1. 「既然现在都不需要这个 nc 连的界面，那么就在不需要的时候不显示这些输入输出框，而是去直接显示做题的入口链接」
2. 「你的前端效果太差，和当前前端风格也不一致」→ 前一位 AI 做的悬浮球 + 自绘面板不合格，**全部推翻重做**。

用户已明确：接下来前端不用前一位 AI 做，由 Claude 接手。

---

## 1. 验收标准（做到这些算完成）

| # | 验收项 | 判定方式 |
|---|---|---|
| A1 | 六道 Web 题详情页 **不再出现** `发送格式 / 接收格式 / 发送数据 / 发送二进制数据 / 清空输出 / 输出框` 这套 socket 界面 | 登录态打开 `/?view=detail&code=HC-01`，DOM 内不应有 `.runtime-console`、`.runtime-controls`、`.runtime-send`、`.runtime-http`、`.runtime-output` |
| A2 | 同页面直接出现「进入题目」主按钮/链接，点击进 `http://PLATFORM_HOST:8081/entry/<CODE>/` | 点击后真能看到题目页（网关自动为本队起容器，首次 10–40 秒） |
| A3 | 「启动环境 / 摧毁环境 / 延长 1 小时 / 刷新状态」**保留**（Web 题也要起容器、看剩余时间） | 仍在 `.runtime-actions` 内 |
| A4 | 视觉与平台原生一致：字体/字号/圆角/描边/配色全取平台既有类名与 CSS 变量 | 与 `/assets/index-BkikzMND.css` 中 `.runtime-*` 同风格；不得出现自创荧光绿、大圆角气泡 |
| A5 | 非 Web 题（若以后有 nc 类题）**保持原 socket 面板** | 判定表外的 code 不注入任何改动 |
| A6 | 全站不再有常驻悬浮球、不再自动弹窗打断选手 | 右下角无「怎么做题？」按钮；无自动展开面板 |
| A7 |（加分）任务列表卡片也能进 | `?view=tasks` 的 `.task-card[data-luminary-task="<CODE>"]` 上有入口 |
| A8 | 刷新 / Vue 重渲染 / 切题后注入仍生效且不重复 | 连续切 3 道题，每处注入仅 1 份 |

---

## 2. 线上拓扑（改之前必须看懂）

### 2.1 公网与端口（最大约束）

~~~
玩家浏览器
  └─ http://PLATFORM_HOST:8081        ← 云安全组只放行 8081（80/443 被别的 nginx 占；8082/8090-8096 全 timeout）
       └─ frps（serverPort 7000，token Sacc+404@）
            └─ my-frpc 容器（host 网络）remotePort 8081 → 127.0.0.1:18080
                 └─ tracequest-frontend:8080（nginx，容器内 listen 8080）
                      ├─ location /              → 平台 SPA（Vue 3）
                      ├─ location = /tq-guide.js → tq-entry-gateway:8090（旧悬浮球，建议删）
                      ├─ location ^~ /entry/     → tq-entry-gateway:8090（题目入口，**必须保留**）
                      └─ location ^~ /storage/   → minio
~~~

* **结论：新入口只能挂在 8081 的路径前缀下；不能新开端口，也没有可用子域名**（用户原话「没有可用的域名」）。
* 后端 API：`http://PLATFORM_HOST:8081/api/v1/...`（与前端同源，前端直接打）。

### 2.2 容器与网络

| 容器 | 镜像 | 网络 | 端口 |
|---|---|---|---|
| `tracequest-frontend` | `tracequest-web:event-control-20261104-tqentry` | sacc-service-net | 127.0.0.1:18080:8080 |
| `tracequest-backend` | `tracequest-backend:history-20261104-tqwrap` | 0xtrace-backend_default + sacc-service-net + tracequest-runtime | 127.0.0.1:8080:8080 |
| `tq-entry-gateway` | `tq-entry-gateway:10` | sacc-service-net + tracequest-runtime | 8090（前缀模式）/ 8091-8096（端口模式，公网不通） |
| 题目容器 | `sacc/*`、`sacc-*` 六个镜像 | tracequest-runtime | 动态 172.23.0.x:PORT，仅 backend / 网关可达 |

### 2.3 前端 = 镜像里的 dist + 一个 bind mount 的 nginx 配置

* compose：`/home/sacc/tracequest-frontend/releases/event-control-20261104/compose.candidate.yaml`
  * project `tracequest-frontend`、service `frontend`、container `tracequest-frontend`
  * `read_only: true`、`cap_drop: [ALL]`、`tmpfs /tmp`
  * **唯一 bind mount**：`/home/sacc/tracequest-frontend/releases/20261102T204207-d11b22cd0330/nginx.conf` → `/etc/nginx/conf.d/default.conf`（宿主可读可改）
  * 静态资源全在镜像内 `/usr/share/nginx/html/`，**宿主上没有 dist 源码树**（release 目录只有 image.tar.gz / config.tar.gz / compose / nginx.conf）
* 前端 index.html（镜像内，实际下发的 HTML）关键内容：

~~~html
<title>0xTrace · 图寻 / CTF</title>
<meta name="color-scheme" content="dark" />
<div id="app"></div>
<script type="module" crossorigin src="/assets/index-CzK5Oaxk.js"></script>
<link rel="stylesheet" crossorigin href="/assets/index-BkikzMND.css">
<!-- body 末尾：平台自带的扩展脚本（既有先例，见 7.1） -->
<script src="/api-client.js?v=event-control-20261104"></script>
~~~

* SPA 路由：`new URLSearchParams(location.search).get("view")`，合法值 `home / login / guide / about / tasks / detail / leaderboard / history`；详情页 = `?view=detail&code=<CODE>`。框架是 **Vue 3**（不是 React），带 `data-v-83391471` 这类作用域属性。

---

## 3. 平台自带的「连接容器」面板是什么

### 3.1 它是裸 socket 调试器，不是做题入口

登录后、容器连上时出现：「已连接 / 断开连接」「发送格式：UTF-8 原始字节 / 十六进制字节 / HTTP 请求」「接收格式：UTF-8」「发送数据（换行也会原样发送）」「发送二进制数据」「清空输出」「等待容器返回数据…」。

它把输入字节经 WebSocket 桥接原样发给容器，**不会渲染容器返回的 HTML/JS**，所以 Web 题在里面永远空着。它是给 nc 类交互题用的 —— 这就是用户要求「不需要时隐藏、直接给做题入口」的根因。

### 3.2 详情页 runtime 区块真实 DOM（未启动，实测）

~~~html
<section data-v-83391471 class="runtime-panel" aria-labelledby="runtime-heading">
  <header class="runtime-heading">
    <h2 id="runtime-heading">题目环境</h2>
    <span class="runtime-state" role="status">未运行</span>
  </header>
  <div class="runtime-meta"><span>每队同时运行一道容器题</span></div>
  <div class="runtime-actions">
    <button class="runtime-primary">启动环境</button>
    <button>刷新状态</button>
  </div>
</section>
~~~

连接成功后，会在 `.runtime-panel` **末尾追加**一个 `.runtime-console` 块 —— 内部就是要隐藏的全部东西：

~~~
.runtime-console
  ├─ .runtime-connection     ← 「已连接」+「连接容器 / 断开连接」按钮
  ├─ .runtime-controls       ← 发送格式 / 接收格式 两个 <select>
  ├─ .runtime-http           ← 选「HTTP 请求」时的 方法/路径/Host 输入框
  ├─ .runtime-input-label    ← 「发送数据（换行也会原样发送）」
  ├─ .runtime-send           ← textarea +「发送二进制数据」「清空输出」
  └─ .runtime-output         ← 输出框（「等待容器返回数据…」）
~~~

`.runtime-error`（「环境启动失败，可以重试或联系管理员。」）是错误提示，**要保留**。

Vue 渲染位置（bundle `assets/index-CzK5Oaxk.js`，仅 `?view=detail` 组件内）：整个连接块由 `v-if` 控制，形如 `Z.value?(...) : Ct("",!0)`，内部依次渲染 `runtime-connection / runtime-controls / runtime-http / runtime-send / runtime-output`。**这是 Vue 管理的 DOM，直接 insertAdjacentHTML 会被下一次 patch 吞掉 → 必须 MutationObserver 幂等重注入（见 4.3）。**

---

## 4. 需求一：按需隐藏 + 直接给做题入口

### 4.1 判定表：哪些题是 Web 题

~~~
HC-01    人机验证              100 分  → /entry/HC-01/
SUDOKU   异形数独拼图          300 分  → /entry/SUDOKU/
SNAKE    贪吃蛇                200 分  → /entry/SNAKE/
MISSING  消失的方块            300 分  → /entry/MISSING/
MORSE    摩斯电码与错误 MD5    200 分  → /entry/MORSE/
SRCFLAG  查看源码              100 分  → /entry/SRCFLAG/   （该题只有 /SACC 路由，网关已 302）
~~~

另有 `111`「风刻沙丘」（MANUAL，无容器，300 分）—— **不要动，不要给它入口**。

判定方式二选一：
* **A（推荐）**：写死上面这张 code → entry 路径表。稳定可预测。
* B：运行时 `fetch('/api/v1/challenges')` 取 `containerEnabled === true` 自动映射。加题免改代码，但未来若有 nc 类题会被误判。**建议 A 为主，B 仅做日志/兜底。**

### 4.2 三处落点

1. **详情页 runtime 区块（必做）**
   * 对表内 code：`document.querySelector('.runtime-panel .runtime-console')?.remove()`，并持续观察防 Vue 重建（4.3）。
   * 在 `.runtime-actions` 里、**「启动环境」之前**插主 CTA：
     ~~~html
     <a class="runtime-primary tq-entry-cta" href="/entry/HC-01/" target="_blank" rel="noopener">进入题目</a>
     ~~~
     用平台自己的 `.runtime-primary` → 自动获得 `background:#e7e7e7; color:#111; border-color:#e7e7e7` 与 hover `#fff`，和「启动环境」同款（满足 A4 最省事的路径）。
   * 在 `.runtime-meta` 补一行说明（用 `.runtime-note` 类）：
     「本题是 Web 题，点「进入题目」在浏览器作答；下方「启动环境」只负责为本队开容器。」
2. **任务列表页卡片（强烈建议）**
   * 卡片结构：`<button class="task-row task-card task-card-ctf is-luminary luminary-rose"><span class="luminary-card" data-luminary-task="HC-01">…</span><span class="task-card-top"><span class="task-state">…</span></span><span class="task-title"><b>人机验证</b></span><span class="task-card-action">…</span></button>`
   * **`data-luminary-task="<CODE>"` 就是天然 code 锚点**，据此追加「进入题目 →」。注意卡片本身是 `<button>`，内部再放 `<a>` 要 `event.stopPropagation()`，否则会顺带跳详情。
3. **首页 / 顶部导航（可选）**：若要全局入口，用 `.liquid-group` 胶囊导航同款样式，别用悬浮球。

### 4.3 Vue 重渲染防护（关键工程点）

~~~js
const CODES = { 'HC-01':'/entry/HC-01/', SUDOKU:'/entry/SUDOKU/', SNAKE:'/entry/SNAKE/',
                MISSING:'/entry/MISSING/', MORSE:'/entry/MORSE/', SRCFLAG:'/entry/SRCFLAG/' };
const code = new URLSearchParams(location.search).get('code');

function apply() {
  if (!code || !CODES[code]) return;              // 非 Web 题：什么都不做（A5）
  const panel = document.querySelector('.runtime-panel');
  if (!panel) return;
  panel.querySelectorAll('.runtime-console').forEach(n => n.remove());   // 1) 干掉 socket 收发界面
  const actions = panel.querySelector('.runtime-actions');
  if (!actions || actions.querySelector('.tq-entry-cta')) return;       // 2) 幂等注入
  const a = document.createElement('a');
  a.className = 'runtime-primary tq-entry-cta';
  a.href = CODES[code]; a.target = '_blank'; a.rel = 'noopener';
  a.textContent = '进入题目';
  actions.prepend(a);
}

apply();
new MutationObserver(apply).observe(document.body, { childList: true, subtree: true });
window.addEventListener('popstate', apply);
window.addEventListener('hashchange', apply);
~~~

> 建议对 observer 加 100ms 节流，或只观察 `#app` 子树。

### 4.4 文案建议（用户在意「选手知道怎么做题」）

不要再做弹窗，把说明写进详情页正文（`.runtime-note`）：

> 题目环境：本题在浏览器作答，点「进入题目」会自动为本队启动容器；每队各自独立容器、独立 flag；一队同时只能运行一道容器题，切题会重建旧容器。
> 拿到 `SACC{…}` 后回本页在「提交你获得的 flag」里交。

---

## 5. 前一位 AI 的现有实现（**建议全部推翻**）

| 产物 | 位置 | 处置 |
|---|---|---|
| 悬浮球 + 自绘半透明面板「怎么做题？点这里」 | 源码 `F:\Desktop\test\_work\entry\guide.js`；线上 `http://PLATFORM_HOST:8081/tq-guide.js?v=10` | **删**（A6：不得有悬浮球/自动弹窗） |
| nginx 往 index.html 注入脚本 | `nginx.conf` 的 `location / { … sub_filter '</body>' '<script src="/tq-guide.js?v=10" defer></script></body>'; }` | 换成注入你自己的脚本/样式（路径文件名你定） |
| 网关的 `/tq-guide.js` 路由 | `gateway.mjs` 里 `if (url === '/tq-guide.js')` | 删掉，不影响 `/entry/` |
| 自建 UI 的配色（荧光绿 #7ee787、圆角气泡、系统字体） | guide.js 内联样式 | **正是用户批评的点**：与平台 `#5ed29c / #f3f3f0 / DM Mono / Space Grotesk / 12–17px 圆角` 体系完全不符 |

---

## 6. 设计规范（从线上 CSS 挖出的真实 token，照抄就「原生」）

### 6.1 全局变量（`/assets/index-BkikzMND.css` 的 `:root`）

~~~css
:root{
  color-scheme: only dark;
  font-family: "Noto Sans SC", ui-sans-serif, system-ui, sans-serif;
  color:#f3f3f0; background:#000;
  --black:#000; --surface:#080808; --line:#202020;
  --white:#f3f3f0; --muted:#858585;
  --mono:"DM Mono", monospace;
  --display:"Space Grotesk","Noto Sans SC",sans-serif;
  --page-pad: clamp(22px,6.3vw,96px);   /* 断点降级 28px / 17px */
}
~~~

调色板真实用量：`#070b0a` 20 次、`#5ed29c` 11（唯一强调绿）、`#050505` 11、`#242424` 9、`#000` 8、`#252525` 7、`#292929` 5、`#202020` 5、`#1b1b1b` 5、`#ecece8` 4（浅色实心按钮）、`#858580` 4、`#101010` 4、`#080808` 4、`#efefeb` 3。

### 6.2 runtime 区块既有样式

~~~css
.runtime-panel        { margin-block:40px 12px; padding-block:30px; border-top:1px solid #242424; color:#ededed }
.runtime-heading      { display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap }
.runtime-heading h2   { font-size:22px; font-weight:500; letter-spacing:-.02em; margin:0 }   /* 移动端 20px */
.runtime-state,
.runtime-meta,
.runtime-note         { font-size:13px; color:#aaa; line-height:1.8 }
.runtime-actions      { display:flex; gap:10px; flex-wrap:wrap; margin-top:18px }
.runtime-actions button { flex:1 }
.runtime-primary      { background:#e7e7e7; color:#111; border-color:#e7e7e7 }
.runtime-primary:hover:not(:disabled) { background:#fff }
.runtime-link         { margin-left:10px; background:transparent; text-decoration:underline; text-underline-offset:4px }
.runtime-output       { margin-top:20px; white-space:pre-wrap; max-height:340px; overflow:auto;
                        background:#0c0c0c; border:1px solid #292929; border-radius:12px;
                        padding:18px; font-size:13px; line-height:1.7; min-height:100px }
~~~

### 6.3 详情页其它可复用件

~~~css
.detail-view            { max-width:1120px; padding:29px 0 12px }
.detail-view .panel     { padding:25px; border-color:#1b1b1b; border-radius:17px; background:#050505 }
.submit-button          { width:100%; height:47px; padding:0 15px; border:1px solid #ecece8; border-radius:12px;
                          background:#ecece8; color:#111; font-size:11px; font-weight:600 }
.panel-label            { color:#737373; font-size:8px }
.answer-panel label     { font:9px var(--mono); letter-spacing:.13em; color:#8f8f8b }
.task-state             { padding:6px 10px; border-radius:999px; background:#171717; color:#c2c2be; font-size:9px }
.task-state.done        { background:#eee; color:#111 }
.page-foot              { color:#535353; font-size:8px; display:flex; justify-content:space-between; padding-top:27px }
~~~

### 6.4 风格红线（用户就是嫌这个）

* 不新增配色：强调色只有 `#5ed29c`，实心按钮只有 `#e7e7e7 / #ecece8`（深字）。
* 正文 13px / 行高 1.8；小标签 8–9px + `letter-spacing:.13em` + `var(--mono)`。
* 圆角：面板 17px、按钮/输入 12px、胶囊 999px。别用 20px+ 气泡。
* 不要满屏 `backdrop-filter`、不要发光投影、不要自造图标 —— 平台就是纯文字按钮。
* 字体必须 `Noto Sans SC`（标题可用 `Space Grotesk`），禁用默认 system-ui。

---

## 7. 三条可行路线（推荐 A；开发期用 A′）

### 7.1 A —— 沿用平台既有扩展点（推荐）

平台 index.html 末尾**本来就有** `<script src="/api-client.js?v=event-control-20261104"></script>`（5678B，IIFE，含 `createRequestId()`），这是上一轮运维（event-control 版本）留下的扩展机制。照此模式加你自己的：

~~~bash
# 宿主上
docker create --name tqx tracequest-web:event-control-20261104-tqentry
docker cp tqx:/usr/share/nginx/html/index.html ./dist/index.html
docker rm tqx
# 改 dist/index.html：在 api-client.js 之后加
#   <script src="/tq-entry-ui.js?v=1" defer></script>
#   <link rel="stylesheet" href="/tq-entry-ui.css?v=1">
~~~

~~~dockerfile
FROM tracequest-web:event-control-20261104-tqentry
USER 0
COPY dist/index.html   /usr/share/nginx/html/index.html
COPY tq-entry-ui.js    /usr/share/nginx/html/tq-entry-ui.js
COPY tq-entry-ui.css   /usr/share/nginx/html/tq-entry-ui.css
RUN chmod 0644 /usr/share/nginx/html/tq-entry-ui.*
USER 101
~~~

打 tag `tracequest-web:tq-entry-ui-v1` → 改 `compose.candidate.yaml` 的 `image:` → `docker compose -f compose.candidate.yaml up -d`。
注意 `read_only:true`，**不能** docker cp 进运行中的容器，必须走镜像（或 bind mount，见 A′）。

### 7.2 A′ —— 免重建迭代（改一行 5 秒生效，强烈建议开发期用）

compose 加一个 bind mount：

~~~yaml
    volumes:
      - type: bind
        source: /home/sacc/tracequest-frontend/releases/20261102T204207-d11b22cd0330/nginx.conf
        target: /etc/nginx/conf.d/default.conf
        read_only: true
      - type: bind                       # 新增：UI 资源目录
        source: /home/sacc/tq-ui
        target: /usr/share/nginx/html/tq-ui
        read_only: true
~~~

宿主 `/home/sacc/tq-ui/` 放 `tq-entry-ui.js` / `tq-entry-ui.css`。首轮仍需把 `<script src="/tq-ui/tq-entry-ui.js">` 写进 index.html（走 A 重建一次）；之后只改宿主文件 + `docker restart tracequest-frontend` 即生效。

### 7.3 B —— 继续用 nginx sub_filter 注入（不推荐，但最省事）

改 bind mount 的 `nginx.conf`：

~~~nginx
sub_filter '</body>' '<script src="/tq-ui/tq-entry-ui.js?v=2" defer></script></body>';
sub_filter_once on;
~~~

缺点：注入点脆弱；`sub_filter` 会打断 `Content-Length`（nginx 自动转 chunked，健康检查 `wget /index.html` 实测没问题）。

### 7.4 C —— 让网关改写平台首页（不通，别选）

平台首页不经过 `tq-entry-gateway`（nginx 直达静态文件），所以改网关没用。网关只改写 `/entry/<CODE>/` 下的题目页面（注入 `<base>` + 前缀化）。

---

## 8. 后端 API 契约

基址 `http://PLATFORM_HOST:8081/api/v1`，会话 cookie 名 **`tq_session`**（HttpOnly JWT）。

| 用途 | 请求 |
|---|---|
| 登录 | `POST /auth/login` `{username,password}` → 200 + `Set-Cookie: tq_session=…` |
| 当前用户 | `GET /me` |
| 题目列表 | `GET /challenges`（字段含 `code/title/points/containerEnabled/solved`） |
| 本题运行时状态 | `GET /events/{eventId}/challenges/{code}/runtime/instances/me` → `{instanceId,status,containerIp,hostPort,containerPort,wssUrl,expiresAt,remainingSeconds,lastError}` |
| 启动容器 | `POST /events/{eventId}/challenges/{code}/runtime/instances` → 201；每队只能 1 个，占用中 409 `CONFLICT` |
| 销毁容器 | `DELETE /events/{eventId}/challenges/{code}/runtime/instances/me` → 204 |
| 延期 1h | `POST …/runtime/instances/me/extend` → 200 |
| 连接票据 | `POST …/runtime/connection-ticket` |
| 交 flag | `POST /challenges/{code}/submissions` `{answer:"SACC{…}"}` → 200 `{result:"correct"}`；错 flag 403 `FLAG_OWNER_MISMATCH`「串通flag并不是好习惯」 |

事件 id：`evt_3m5wyvbmhkzza6fpww`（name `TraceQuest`，status **open**）。

**三条硬规矩**
1. **写方法必须带 `Origin`**：`SameOriginFilter` 比 `Origin` 的 host 与请求 `Host` 头。公网 `Origin: http://PLATFORM_HOST:8081`，直连后端 `http://127.0.0.1:8080`，否则 403 `{"code":"ORIGIN_REJECTED","message":"请求来源校验失败"}`。（浏览器同源请求天然满足，前端不用管。）
2. **限流**（`application.yml:75-81`）：login 10/min、submission 30/min、admin-write 60/min、未登记默认 60/min → 实例 start/stop 连点会 429，需 60–90s 冷却。按钮要 disable + 友好提示。
3. 容器题 flag **每队独立** `SACC{ BCrypt(teamName) }`（60 字符哈希），跨队抄不了。

---

## 9. 题目入口网关 `/entry/<CODE>/`（已可用，别重写）

* 源码：`F:\Desktop\test\_work\entry\gateway.mjs`（约 188 行，Node 22 原生 http）
* 镜像/容器：`tq-entry-gateway:10` / `tq-entry-gateway`，启动：

~~~bash
docker run -d --name tq-entry-gateway --restart unless-stopped \
  --network sacc-service-net \
  -e TQ_EVENT_ID=evt_3m5wyvbmhkzza6fpww \
  -e TQ_API=http://tracequest-backend:8080 \
  tq-entry-gateway:10
docker network connect tracequest-runtime tq-entry-gateway   # 必须双网络
~~~

* 逻辑：
  1. 监听 8090，匹配 `^/entry/([A-Za-z0-9_-]+)(/.*)?$`；
  2. 用浏览器带来的 `tq_session` 调 `GET …/runtime/instances/me`：
     * 404 / STOPPED / 409 → 自动 `POST …/instances`（409 时先遍历其它 5 题 `DELETE …/instances/me` 再重试一次，20s 防抖）
     * PENDING / PULLING / STARTING → 200「容器启动中…」+ `Refresh: 2` 自刷新
     * READY → 反代到 `containerIp:containerPort`
     * 401 → 「请先登录」；403 → 「赛事未开放」；FAILED → 502 显示 `lastError`
  3. 反代细节：剥前缀；`Host` 改写为 `ip:port`；**`Origin`/`Referer` 折算成 `http://ip:port`**（容器普遍校验同源，不改会 403「只接受同源请求」）；删 `accept-encoding / if-none-match / if-modified-since`；
  4. 文本响应（html/js/css/json/svg）前缀化 `s.replace(/(["'])\/(?!\/)/g, '$1'+prefix)`（原文还包含反引号一类引号，一起处理更稳）；HTML 额外注入 `<base href="/entry/CODE/">`；`Set-Cookie` 的 `Path` 全改写成 `/entry/CODE/`（避免污染平台会话）；
  5. `HOME = { SRCFLAG:'/SACC' }`：`/entry/SRCFLAG` 302 到 `/SACC`（该题根路径本就 404）。
* 六题入口（现在就能用，可直接作为新 UI 链接目标）：
  ~~~
  http://PLATFORM_HOST:8081/entry/HC-01/
  http://PLATFORM_HOST:8081/entry/SUDOKU/
  http://PLATFORM_HOST:8081/entry/SNAKE/
  http://PLATFORM_HOST:8081/entry/MISSING/
  http://PLATFORM_HOST:8081/entry/MORSE/
  http://PLATFORM_HOST:8081/entry/SRCFLAG/
  ~~~

---

## 10. 测试与验收

### 10.1 结构验证（不用点鼠标）

~~~bash
curl -s http://PLATFORM_HOST:8081/ | grep -o 'tq-entry-ui[^"]*'                     # 新脚本已注入
curl -s -o /dev/null -w '%{http_code} %{size_download}\n' http://PLATFORM_HOST:8081/tq-entry-ui.js
for c in HC-01 SUDOKU SNAKE MISSING MORSE SRCFLAG; do
  printf '%s ' $c; curl -s -o /dev/null -w '%{http_code}\n' http://PLATFORM_HOST:8081/entry/$c/
done
~~~

### 10.2 登录态 DOM 验证（无头 Chrome + CDP，已验证可行）

> 本机 Chrome 156，Node 24 有全局 `WebSocket`，可直接说 CDP，无需 puppeteer。

流程：`auth/login` 取 `tq_session` → `Network.setCookie`（domain `PLATFORM_HOST`）→ `Page.navigate` → `Runtime.evaluate('document.documentElement.outerHTML')` → 断言。

断言：
* `/?view=detail&code=HC-01`：无 `.runtime-console / .runtime-controls / .runtime-send`；有 `.tq-entry-cta`；`.runtime-actions` 内仍有「启动环境」。
* `/?view=detail&code=111`（风刻沙丘）：与改造前一致，无入口注入。
* 现成产物：`F:\Desktop\test\_work\shots\detail_loggedin.html`（23689B，改造前）、`tasks_loggedin.html`（22190B）、脚本 `cdp_dump.mjs` / `cdp_tasks.mjs`（可复用）。

### 10.3 端到端通关

~~~bash
# 宿主上：ssh sacc@frp-sea.com -p 18915
node /home/sacc/tqsolve.mjs <kind> http://<containerIp>:<port>
# kind ∈ humancheck | jigsaw | snake | missing | morse | srcflag
# 输出 RESULT {...} 内含 flag；回平台交 flag 应 200 result=correct
~~~
六题逻辑均已验证可解（前一位 AI 六题全部 first blood 拿分）。

---

## 11. 环境与账号

~~~
SSH     ：ssh sacc@frp-sea.com -p 18915        密码 <SSH_PASSWORD>（sudo 同密码）
宿主    ：Ubuntu 5.4.0-216 / Docker 28.1.1 / node v24.21.0 / python3.8.10
MySQL   ：docker exec mysql8 mysql -utracequest -p'<TQ_DB_PASSWORD>' tracequest -e '...'
          表：events / challenges / teams / team_members / challenge_container_instances / submissions / solves
前端    ：/home/sacc/tracequest-frontend/…（见 2.3）；nginx.conf 改完 docker restart tracequest-frontend
后端    ：/home/sacc/0xtrace-backend/docker-compose.app-only.yml（service app，容器 tracequest-backend）
          容器内 /usr/local/bin/docker 是包装脚本（跳过 pull、过滤 CLI 警告），删了容器题起不来
~~~

| 角色 | 账号 | 密码 |
|---|---|---|
| 管理员 | tq-admin-01 | <ADMIN_01_PASSWORD> |
| 管理员 | tq-admin-02 | <ADMIN_02_PASSWORD> |
| 管理员 | tq-admin-03 | <ADMIN_03_PASSWORD> |
| 选手 | tq-player-01（测试一队 team_zth2mmc9m6ccd79cc7，joinCode TQ-X3D3EFWM7Y） | <PLAYER_01_PASSWORD> |
| 选手 | tq-player-02（队伍「111」team_p10pq9jdke6pv0gryv） | <PLAYER_02_PASSWORD> |
| 探针 | tq-probe-01（无队无提交，DOM 测试推荐） | Probe#2026test |

> **改动边界**：动平台内容（赛事、题目、账号、数据库）前必须先跟用户汇报；纯前端展示层改造在已授权范围内。

---

## 12. 已知坑

1. 平台写接口不带 `Origin` → 403 `ORIGIN_REJECTED`；`Origin` 的 host 必须等于 `Host` 头。
2. 前端容器 `read_only:true` → 不能 docker cp 进去；只能重建镜像或 bind mount。
3. 前端 nginx 配置是 bind mount 的单文件，**改镜像内同路径文件无效**（镜像改了、运行的还是宿主那份）。
4. `docker compose up -d` 后要确认容器真换了镜像（`pull_policy: never` 不会拉，但 recreate 要显式）。
5. 平台静态资源带 hash 文件名；改 index.html 引用要带 `?v=` 版本号，否则浏览器缓存让你以为没生效。
6. `SRCFLAG` 只有 `/SACC` 路由（根路径 404），网关已 302；直接写 `/entry/SRCFLAG/` 会 302 到正确页，属正常。
7. 每队同时只能 1 个容器；测多题前先 `DELETE …/instances/me`，否则 409 `CONFLICT`。
8. 实例状态字符串：`PENDING / PULLING / STARTING / READY / FAILED / STOPPED`；「未运行」判定 = `!instanceId || status in (FAILED, STOPPED)`。
9. 六个题目镜像只存在于宿主 Docker（无 registry），靠后端容器内 `/usr/local/bin/docker` 包装脚本跳过 `docker pull` 才起得来 —— 别动它。
10. 宿主到 GitHub 不通（`curl github.com` 返回 000），`FROM ghcr.io/...` 的构建必失败；可用镜像源 `docker.1ms.run / docker.xuanyuan.me / docker.m.daocloud.io`。
11. 无头 Chrome 截图必须给绝对 Windows 路径（`--screenshot="F:\...\x.png"`），相对路径报 0x5 拒绝访问。
12. 命令行内联含 `$2a$10$…` 的 flag 会被 shell 吃掉 → flag 一律从文件读或写进 `.mjs` 常量。

---

## 13. 回滚清单

~~~bash
# 前端 nginx（旧备份在同目录）
cp nginx.conf.bak-2-20261104124148 nginx.conf && docker restart tracequest-frontend
# 前端镜像退回改造前
sed -i 's#image: tracequest-web:.*#image: tracequest-web:event-control-20261104-tqentry#' \
  /home/sacc/tracequest-frontend/releases/event-control-20261104/compose.candidate.yaml
docker compose -p tracequest-frontend -f …/compose.candidate.yaml up -d
# 只删旧悬浮球链路（注意：网关容器一并删会让 /entry/ 全失效，慎用）
docker rm -f tq-entry-gateway
~~~

---

## 14. 一句话总结要做的事

1. 写 `/entry` 映射表 + 一段注入脚本（index.html 加一个 `<script>`，走镜像重建或 bind mount）。
2. 详情页删 `.runtime-console`，在 `.runtime-actions` 前插一个 `.runtime-primary` 的「进入题目」链接（指向 `/entry/<CODE>/`），说明用 `.runtime-note`。
3. 顺手把任务卡片 `data-luminary-task` 也接上入口。
4. 删掉旧悬浮球/弹窗，配色字号圆角全用第 6 节 token。
5. 按第 10 节验收，重点 A1 / A2 / A3 / A5 / A6。

'use strict';

/*
 * SACC 趣味闯关 · 两关谜题
 *
 * 第一关：摩斯电码 -> youaregreat
 * 第二关：F12 看源码 -> base64 解码 -> 删掉 md5 里不该出现的字符 -> SACC{32位hex}
 *
 * 真正的 flag 由容器入口脚本在每次启动时随机生成，写入 PID 1 的环境变量 FLAG；
 * 第二关的源码线索按它现算，页面、源码、日志都不会主动吐出它。
 */

const http = require('node:http');
const crypto = require('node:crypto');

const PORT = Number.parseInt(process.env.PORT || '8080', 10);

// ---------------------------------------------------------------- 题目数据

const LEVEL1_ANSWER = 'youaregreat';

/**
 * 每次启动的 flag 形如 SACC{32 位小写十六进制}，由 entrypoint.sh 随机生成。
 * 这里把它按 4 位一组切开，中间塞进 md5 里绝不该出现的字符
 * （o l z q x i s t），得到这一轮“错误的 md5”。
 * 解题者把非 [0-9a-f] 的字符全删掉，剩下的就是那 32 位十六进制。
 */
const FLAG = String(process.env.FLAG || '').trim();
// 平台生成的 flag 形如 SACC{BCrypt 串}，内层不是十六进制；谜题本体改用 SHA-256(内层) 前 32 位十六进制承载
const FLAG_MATCH = /^SACC\{([^{}]{1,240})\}$/.exec(FLAG);

if (!FLAG_MATCH) {
  console.error(
    [
      '[fatal] 环境变量 FLAG 缺失或格式不对（应形如 SACC{32 位小写十六进制}）。',
      '正式运行请通过入口脚本启动：sh /app/entrypoint.sh（每次启动随机生成）。',
      '本地调试也可以手动指定：FLAG=\'SACC{...}\' node src/server.js',
    ].join('\n'),
  );
  process.exit(1);
}

const TRUE_HEX = crypto.createHash('sha256').update(FLAG_MATCH[1]).digest('hex').slice(0, 32);

const JUNK_CHARS = ['o', 'l', 'z', 'q', 'x', 'i', 's', 't'];

let CORRUPTED_MD5 = '';
for (let i = 0; i < TRUE_HEX.length; i += 4) {
  CORRUPTED_MD5 += TRUE_HEX.slice(i, i + 4) + JUNK_CHARS[i / 4];
}

if (CORRUPTED_MD5.replace(/[^0-9a-f]/g, '') !== TRUE_HEX) {
  console.error('[fatal] 题目数据生成失败：清洗后与 flag 不一致。');
  process.exit(1);
}

// 源码注释里给出的 base64（解码后就是这一轮错误的 md5）
const BASE64_HINT = Buffer.from(CORRUPTED_MD5, 'utf8').toString('base64');

// ---------------------------------------------------------------- 会话

const SESSION_COOKIE = 'sacc_sid';
const SESSION_TTL_MS = 2 * 60 * 60 * 1000;
const MAX_SESSIONS = 5000;
const sessions = new Map();

function createSession() {
  if (sessions.size >= MAX_SESSIONS) {
    const oldest = sessions.keys().next().value;
    if (oldest !== undefined) sessions.delete(oldest);
  }
  const id = crypto.randomBytes(24).toString('hex');
  sessions.set(id, {
    level: 2,
    expires: Date.now() + SESSION_TTL_MS,
  });
  return id;
}

function readSession(req) {
  const id = parseCookie(req.headers.cookie, SESSION_COOKIE);
  if (!id || !/^[0-9a-f]{48}$/.test(id)) return null;

  const session = sessions.get(id);
  if (!session) return null;

  if (session.expires <= Date.now()) {
    sessions.delete(id);
    return null;
  }

  session.expires = Date.now() + SESSION_TTL_MS;
  return { id, session };
}

setInterval(() => {
  const now = Date.now();
  for (const [id, session] of sessions) {
    if (session.expires <= now) sessions.delete(id);
  }
}, 10 * 60 * 1000).unref();

function parseCookie(header, name) {
  if (!header) return '';
  for (const part of String(header).split(';')) {
    const index = part.indexOf('=');
    if (index === -1) continue;
    if (part.slice(0, index).trim() !== name) continue;
    return part.slice(index + 1).trim();
  }
  return '';
}

// ---------------------------------------------------------------- 限流

const RATE_WINDOW_MS = 60 * 1000;
const RATE_LIMIT = 80;
const buckets = new Map();

function rateLimited(ip) {
  const now = Date.now();
  const bucket = buckets.get(ip);

  if (!bucket || bucket.reset <= now) {
    buckets.set(ip, { count: 1, reset: now + RATE_WINDOW_MS });
    return false;
  }

  bucket.count += 1;
  return bucket.count > RATE_LIMIT;
}

setInterval(() => {
  const now = Date.now();
  for (const [ip, bucket] of buckets) {
    if (bucket.reset <= now) buckets.delete(ip);
  }
}, RATE_WINDOW_MS).unref();

// ---------------------------------------------------------------- 工具

function safeEqual(a, b) {
  const left = crypto.createHash('sha256').update(String(a)).digest();
  const right = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(left, right);
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function send(res, status, body, type) {
  const payload = Buffer.from(body, 'utf8');
  res.writeHead(status, {
    'Content-Type': type || 'text/html; charset=utf-8',
    'Content-Length': payload.length,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy':
      "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
      "connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
  });
  res.end(payload);
}

function sendJson(res, status, data) {
  send(res, status, JSON.stringify(data), 'application/json; charset=utf-8');
}

function readJson(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;

    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error('too-large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });

    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8').trim();
      if (!raw) return resolve({});
      try {
        resolve(JSON.parse(raw));
      } catch {
        reject(new Error('bad-json'));
      }
    });

    req.on('error', reject);
  });
}

// ---------------------------------------------------------------- 页面

const CSS = `
:root {
  --bg: #0a0e13;
  --panel: #101821;
  --panel-2: #0d141c;
  --line: #1e2b3a;
  --fg: #d9e4f0;
  --dim: #8aa0b6;
  --acc: #53e08a;
  --warn: #ff7b8a;
  --mono: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace;
}
* { box-sizing: border-box; }
html, body { height: 100%; }
body {
  margin: 0;
  color: var(--fg);
  background:
    radial-gradient(1100px 520px at 12% -12%, #12232f 0%, transparent 62%),
    radial-gradient(900px 480px at 108% 4%, #102b26 0%, transparent 58%),
    var(--bg);
  font: 16px/1.7 system-ui, -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  -webkit-font-smoothing: antialiased;
}
.wrap { max-width: 720px; margin: 0 auto; padding: 64px 20px 80px; }
.card {
  border: 1px solid var(--line);
  border-radius: 18px;
  background: linear-gradient(180deg, var(--panel), var(--panel-2));
  padding: 34px 30px 28px;
  box-shadow: 0 30px 70px -40px rgba(0, 0, 0, .9);
}
h1 { margin: 0 0 24px; font-size: 26px; letter-spacing: .02em; line-height: 1.4; }
h1.warn { color: var(--warn); }
.morse {
  margin: 0 0 22px;
  padding: 22px 18px;
  border: 1px dashed rgba(83, 224, 138, .45);
  border-radius: 14px;
  background: rgba(83, 224, 138, .05);
  color: var(--acc);
  font-family: var(--mono);
  font-size: 19px;
  letter-spacing: .18em;
  word-break: break-all;
  line-height: 1.9;
}
.lead { margin: 0 0 16px; color: #b8c8d9; }
.row { display: flex; gap: 10px; }
input[type="text"] {
  flex: 1;
  min-width: 0;
  padding: 13px 15px;
  color: var(--fg);
  background: #070c11;
  border: 1px solid var(--line);
  border-radius: 12px;
  font: 15px/1.4 var(--mono);
  outline: none;
  transition: border-color .15s, box-shadow .15s;
}
input[type="text"]:focus { border-color: rgba(83, 224, 138, .7); box-shadow: 0 0 0 3px rgba(83, 224, 138, .12); }
button {
  padding: 13px 22px;
  border: 0;
  border-radius: 12px;
  background: var(--acc);
  color: #06130c;
  font: 700 15px/1.4 system-ui, sans-serif;
  cursor: pointer;
  white-space: nowrap;
  transition: transform .12s, filter .15s;
}
button:hover { filter: brightness(1.08); }
button:active { transform: translateY(1px); }
button[disabled] { opacity: .55; cursor: default; }
.msg { min-height: 24px; margin: 14px 0 0; font-size: 14.5px; color: var(--dim); }
.msg.bad { color: var(--warn); }
.msg.ok { color: var(--acc); }
.success { text-align: center; padding: 8px 4px 4px; }
.success .tick {
  width: 64px; height: 64px; margin: 0 auto 16px;
  display: grid; place-items: center;
  border-radius: 50%;
  background: rgba(83, 224, 138, .12);
  border: 1px solid rgba(83, 224, 138, .5);
  color: var(--acc);
  font-size: 30px;
}
.success h2 { margin: 0 0 8px; font-size: 23px; }
.flag {
  display: inline-block;
  margin: 8px 0 2px;
  padding: 12px 16px;
  border-radius: 12px;
  border: 1px solid rgba(83, 224, 138, .45);
  background: rgba(83, 224, 138, .08);
  color: var(--acc);
  font-family: var(--mono);
  font-size: 17px;
  word-break: break-all;
}
@media (max-width: 560px) {
  .wrap { padding: 32px 14px 56px; }
  .card { padding: 26px 18px 22px; border-radius: 16px; }
  h1 { font-size: 22px; }
  .morse { font-size: 15px; letter-spacing: .12em; }
  .row { flex-direction: column; }
  button { width: 100%; }
}
`;

function page(title, body, bodyClass) {
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${escapeHtml(title)}</title>
<style>${CSS}</style>
</head>
<body class="${bodyClass || ''}">
<main class="wrap">
${body}
</main>
</body>
</html>
`;
}

function level1Page() {
  return page(
    '第一关',
    `  <section class="card">
    <h1>第一关</h1>
    <pre class="morse">-.--/---/..-/.-/.-././--./.-././.-/-</pre>
    <form id="f" class="row" autocomplete="off">
      <input id="a" type="text" name="answer" aria-label="答案" spellcheck="false" autofocus>
      <button type="submit" id="go">提交</button>
    </form>
    <p id="msg" class="msg" role="status"></p>
  </section>

  <script>
  (function () {
    var form = document.getElementById('f');
    var input = document.getElementById('a');
    var msg = document.getElementById('msg');
    var button = document.getElementById('go');

    form.addEventListener('submit', function (event) {
      event.preventDefault();
      button.disabled = true;
      msg.className = 'msg';
      msg.textContent = '解码中…';

      fetch('/api/level1', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ answer: input.value })
      })
        .then(function (response) { return response.json(); })
        .then(function (data) {
          if (data.ok) {
            msg.className = 'msg ok';
            msg.textContent = '对了，正在进入第二关…';
            location.href = '/level2';
            return;
          }
          msg.className = 'msg bad';
          msg.textContent = data.message || '不对，再试一次。';
          button.disabled = false;
        })
        .catch(function () {
          msg.className = 'msg bad';
          msg.textContent = '网络开小差了，再提交一次。';
          button.disabled = false;
        });
    });
  })();
  </script>`,
  );
}

function level2Page() {
  return page(
    '第二关',
    `  <section class="card">
    <h1 class="warn">这是个错误的md5</h1>
    <form id="f" class="row" autocomplete="off">
      <input id="a" type="text" name="flag" aria-label="flag" spellcheck="false" autofocus>
      <button type="submit" id="go">提交</button>
    </form>
    <p id="msg" class="msg" role="status"></p>
  </section>

  <!-- 抄录备份（base64）：${BASE64_HINT} -->

  <script>
  (function () {
    var form = document.getElementById('f');
    var input = document.getElementById('a');
    var msg = document.getElementById('msg');
    var button = document.getElementById('go');

    form.addEventListener('submit', function (event) {
      event.preventDefault();
      button.disabled = true;
      msg.className = 'msg';
      msg.textContent = '校验中…';

      fetch('/api/flag', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ flag: input.value })
      })
        .then(function (response) { return response.json(); })
        .then(function (data) {
          button.disabled = false;
          if (data.ok) {
            msg.textContent = '';
            var card = document.querySelector('.card');
            card.innerHTML = '';
            var box = document.createElement('div');
            box.className = 'success';

            var tick = document.createElement('div');
            tick.className = 'tick';
            tick.textContent = '✓';

            var title = document.createElement('h2');
            title.textContent = '通关了';

            var text = document.createElement('p');
            text.className = 'lead';
            text.textContent = '你把那条错误的 md5 修好了。拿去提交吧：';

            var flag = document.createElement('div');
            flag.className = 'flag';
            flag.textContent = String(data.flag || input.value).trim();

            box.appendChild(tick);
            box.appendChild(title);
            box.appendChild(text);
            box.appendChild(flag);
            card.appendChild(box);
            return;
          }
          msg.className = 'msg bad';
          msg.textContent = data.message || 'flag 不对，再想想。';
        })
        .catch(function () {
          button.disabled = false;
          msg.className = 'msg bad';
          msg.textContent = '网络开小差了，再提交一次。';
        });
    });
  })();
  </script>`,
  );
}

// ---------------------------------------------------------------- 路由

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  const path = url.pathname;
  const ip = req.socket.remoteAddress || 'unknown';

  try {
    if (req.method === 'GET' || req.method === 'HEAD') {
      if (path === '/') return send(res, 200, level1Page());

      if (path === '/level2') {
        if (!readSession(req)) {
          res.writeHead(302, { Location: '/', 'Cache-Control': 'no-store' });
          return res.end();
        }
        return send(res, 200, level2Page());
      }

      if (path === '/healthz') return send(res, 200, 'ok', 'text/plain; charset=utf-8');
      if (path === '/favicon.ico') {
        res.writeHead(204);
        return res.end();
      }

      return send(res, 404, page('404', '<section class="card"><h1>404</h1><p class="lead">这里什么都没有。<a href="/">回第一关</a></p></section>'));
    }

    if (req.method === 'POST') {
      if (rateLimited(ip)) {
        return sendJson(res, 429, { ok: false, message: '提交太频繁了，歇一分钟再来。' });
      }

      if (path === '/api/level1') {
        const body = await readJson(req, 2048);
        const answer = String(body.answer || '')
          .toLowerCase()
          .replace(/[\s\-_]/g, '');

        if (!answer) return sendJson(res, 200, { ok: false, message: '先输入点内容再提交。' });

        if (safeEqual(answer, LEVEL1_ANSWER)) {
          const id = createSession();
          res.setHeader(
            'Set-Cookie',
            `${SESSION_COOKIE}=${id}; Path=/; Max-Age=${Math.floor(SESSION_TTL_MS / 1000)}; HttpOnly; SameSite=Lax`,
          );
          return sendJson(res, 200, { ok: true });
        }

        return sendJson(res, 200, {
          ok: false,
          message: '不对，再试一次。',
        });
      }

      if (path === '/api/flag') {
        const current = readSession(req);
        if (!current) {
          return sendJson(res, 403, { ok: false, message: '会话过期了，请回第一关重新开始。' });
        }

        const body = await readJson(req, 4096);
        const submitted = String(body.flag || '').trim();

        if (!submitted) return sendJson(res, 200, { ok: false, message: '先输入 flag 再提交。' });

        // 提交 32 位 hex（谜题解）或平台 flag 本身都判定通关，并把平台 flag 回显给选手
        if (safeEqual(submitted, FLAG) || safeEqual(submitted, TRUE_HEX)
            || safeEqual(submitted, SACC_PREFIX + TRUE_HEX + '}')) {
          current.session.level = 3;
          return sendJson(res, 200, { ok: true, flag: FLAG });
        }

        const inner = submitted.replace(/^SACC\{/i, '').replace(/\}$/, '');
        const hasShell = /^SACC\{[^}]*\}$/.test(submitted);
        if (!hasShell && /[0-9a-zA-Z]/.test(inner)) {
          return sendJson(res, 200, {
            ok: false,
            message: '格式不对哦：flag 应该长这样 —— SACC{...}。',
          });
        }

        return sendJson(res, 200, {
          ok: false,
          message: '不对，再试一次。',
        });
      }

      return sendJson(res, 404, { ok: false, message: '没有这个接口。' });
    }

    res.writeHead(405, { Allow: 'GET, HEAD, POST' });
    return res.end();
  } catch (error) {
    if (error && error.message === 'too-large') {
      return sendJson(res, 413, { ok: false, message: '提交内容太长了。' });
    }
    if (error && error.message === 'bad-json') {
      return sendJson(res, 400, { ok: false, message: '请求格式不对。' });
    }
    console.error('[error]', error && error.stack ? error.stack : error);
    if (!res.headersSent) {
      res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
    }
    return res.end('internal error');
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('[sacc] 趣味闯关已启动：http://0.0.0.0:' + PORT);
  console.log('[sacc] 本轮 flag 已注入环境变量 FLAG（来源：启动时随机生成）。');
});

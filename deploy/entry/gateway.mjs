import http from 'node:http';
import fs from 'node:fs';

const EVENT = process.env.TQ_EVENT_ID || 'evt_3m5wyvbmhkzza6fpww';
const API = process.env.TQ_API || 'http://tracequest-backend:8080';
const PUBLIC = process.env.TQ_PUBLIC || 'http://PLATFORM_HOST:8081';
const CODES = ['HC-01', 'SUDOKU', 'SNAKE', 'MISSING', 'MORSE', 'SRCFLAG'];
const PORTMAP = { 'HC-01': 8091, SUDOKU: 8092, SNAKE: 8093, MISSING: 8094, MORSE: 8095, SRCFLAG: 8096 };
const HOME = { SRCFLAG: '/SACC' };

const upCache = new Map();
const startCache = new Map();
const now = () => Date.now();

function apiFetch(url, init) {
  const headers = { ...(init && init.headers) };
  headers.Origin = API;
  headers.Referer = API + '/';
  return fetch(url, { ...(init || {}), headers });
}

async function resolveTarget(code, cookie) {
  const key = code + '|' + cookie;
  const hit = upCache.get(key);
  if (hit && now() - hit.at < 4000) return hit.val;
  let out;
  try {
    const r = await apiFetch(API + '/api/v1/events/' + EVENT + '/challenges/' + code + '/runtime/instances/me', { headers: { Cookie: cookie } });
    if (r.status === 401) out = { kind: 'login' };
    else if (r.status === 200) {
      const b = await r.json();
      if (b.status === 'READY' && b.containerIp) out = { kind: 'ok', ip: b.containerIp, port: b.containerPort, exp: b.expiresAt };
      else if (b.status === 'FAILED') out = { kind: 'failed', msg: b.lastError || '容器启动失败' };
      else if (b.status === 'STOPPED') out = { kind: 'none' };
      else out = { kind: 'starting', status: b.status, msg: b.lastError };
    } else if (r.status === 404 || r.status === 409) out = { kind: 'none' };
    else if (r.status === 403) out = { kind: 'closed' };
    else out = { kind: 'error', msg: 'HTTP ' + r.status };
  } catch (e) { out = { kind: 'error', msg: String(e.message) }; }
  if (out.kind === 'ok') upCache.set(key, { val: out, at: now() });
  return out;
}

/** 没有实例时自动向平台申请启动一个（同一用户 20 秒内只尝试一次，避免触发限流）。 */
async function postInstance(code, cookie) {
  const r = await apiFetch(API + '/api/v1/events/' + EVENT + '/challenges/' + code + '/runtime/instances', { method: 'POST', headers: { Cookie: cookie } });
  let body = {};
  try { body = await r.json(); } catch (e) { body = {}; }
  return { status: r.status, body };
}

/** 队伍同时只能开一个容器：若被别的题占用，先把那个容器销毁再重试（点开哪道题就是哪道题）。 */
async function autoStart(code, cookie) {
  const key = code + '|' + cookie;
  const hit = startCache.get(key);
  if (hit && now() - hit.at < 20000) return hit.val;
  let out;
  try {
    out = await postInstance(code, cookie);
    if (out.status === 409) {
      for (const other of CODES) {
        if (other === code) continue;
        await apiFetch(API + '/api/v1/events/' + EVENT + '/challenges/' + other + '/runtime/instances/me', { method: 'DELETE', headers: { Cookie: cookie } });
      }
      await new Promise((r) => setTimeout(r, 1200));
      out = await postInstance(code, cookie);
      if (out.status === 201) out.swapped = true;
    }
  } catch (e) { out = { status: 0, body: { message: String(e.message) } }; }
  startCache.set(key, { val: out, at: now() });
  return out;
}

const CSS = '<style>body{background:#0b0d10;color:#e6e6e6;font:15px/1.7 system-ui,"Microsoft YaHei",sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0}'
  + '.b{max-width:680px;padding:32px 36px;border:1px solid #2a2f36;border-radius:14px;background:#12161b}h1{font-size:19px;margin:0 0 12px;font-weight:600}'
  + 'a{color:#7cc4ff}code{background:#1b2027;padding:2px 6px;border-radius:5px;font-size:13px}.s{color:#8b95a1;font-size:13px;margin-top:16px}'
  + '.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#3fb950;margin-right:8px;animation:p 1.2s infinite}@keyframes p{50%{opacity:.25}}</style>';
const PAGE = (title, body) => '<!doctype html><meta charset=utf-8><title>' + title + '</title>' + CSS + '<div class=b><h1>' + title + '</h1>' + body + '</div>';
const sendPage = (res, status, title, body) => {
  res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(PAGE(title, body));
};

const TEXTUAL = /^(text\/html|application\/javascript|text\/javascript|application\/json|text\/css|image\/svg\+xml)/i;

function proxyTo(code, prefix, target, req, res) {
  let path = req.url;
  if (prefix && path.startsWith(prefix)) path = path.slice(prefix.length - 1) || '/';
  if (!path.startsWith('/')) path = '/' + path;
  const headers = { ...req.headers };
  headers.host = target.ip + ':' + target.port;
  // 容器普遍做同源校验（Origin.host === Host）：在网关侧把 Origin/Referer 折算到容器自身地址
  const origin = 'http://' + target.ip + ':' + target.port;
  if (headers.origin) headers.origin = origin;
  if (headers.referer) headers.referer = headers.referer.replace(/^https?:\/\/[^/]+/, origin);
  delete headers['accept-encoding'];
  delete headers['if-none-match'];
  delete headers['if-modified-since'];

  const up = http.request({ host: target.ip, port: target.port, method: req.method, path, headers, timeout: 30000 }, (ur) => {
    const h = { ...ur.headers };
    if (h.location && h.location.startsWith('/') && prefix) h.location = prefix + h.location.slice(1);
    if (prefix && h['set-cookie']) {
      h['set-cookie'] = [].concat(h['set-cookie']).map((c) =>
        /path=\/[^;]*/i.test(c) ? c.replace(/path=\/[^;]*/i, 'Path=' + prefix) : c + '; Path=' + prefix);
    }
    delete h['content-length'];
    delete h['transfer-encoding'];
    const ct = h['content-type'] || '';
    if (prefix && TEXTUAL.test(ct)) {
      const chunks = [];
      ur.on('data', (c) => chunks.push(c));
      ur.on('end', () => {
        let s = Buffer.concat(chunks).toString('utf8');
        s = s.replace(/(["'`])\/(?!\/)/g, '$1' + prefix);
        if (/text\/html/i.test(ct) && !/<base\s/i.test(s)) s = s.replace(/<head(\s[^>]*)?>/i, (m) => m + '<base href="' + prefix + '">');
        const buf = Buffer.from(s, 'utf8');
        h['content-length'] = String(buf.length);
        res.writeHead(ur.statusCode || 200, h);
        res.end(buf);
      });
      ur.on('error', () => { if (!res.headersSent) sendPage(res, 502, '上游中断', '<p>容器返回异常。</p>'); else res.end(); });
      return;
    }
    res.writeHead(ur.statusCode || 502, h);
    ur.pipe(res);
  });
  up.on('timeout', () => up.destroy(new Error('upstream timeout')));
  up.on('error', (e) => {
    if (!res.headersSent) sendPage(res, 502, '容器暂时不可达', '<p>' + code + ' 的容器连不上：<code>' + e.message + '</code></p><p class=s>回平台把容器销毁后重新启动，再刷新本页。</p>');
    else res.end();
  });
  req.pipe(up);
}

const STARTING_PAGE = (code, status) => '<p><span class=dot></span>正在为你启动 <code>' + code + '</code> 的题目容器（当前 <code>' + (status || 'STARTING') + '</code>）。</p>'
  + '<p>首次启动需要拉取并运行镜像，通常 10–40 秒。本页会自动刷新，启动完成后直接进入题目。</p>'
  + '<p class=s>如超过 2 分钟仍未就绪，回 <a href="' + PUBLIC + '/" target=_top>平台</a> 打开本题查看容器状态。</p>';

async function serve(code, prefix, req, res) {
  const url = req.url;
  const cookie = req.headers.cookie || '';
  if (!cookie.includes('tq_session=')) {
    return sendPage(res, 401, '请先登录平台',
      '<p>这里是你本队 <code>' + code + '</code> 题目的容器入口，需要平台登录态。</p>'
      + '<p>先打开 <a href="' + PUBLIC + '/" target=_top>平台</a> 登录（同一浏览器），再回来刷新本页。</p>');
  }
  // 首页默认路径（如 SRCFLAG 的题目页在 /SACC）
  if (prefix && HOME[code] && (url === prefix || url === prefix.slice(0, -1))) {
    res.writeHead(302, { Location: prefix + HOME[code].replace(/^\//, ''), 'Cache-Control': 'no-store' });
    return res.end();
  }
  let t = await resolveTarget(code, cookie);
  if (t.kind === 'none') {
    const started = await autoStart(code, cookie);
    if (started.status === 409) {
      const other = started.body && started.body.error && started.body.error.message || '';
      return sendPage(res, 409, '一个队伍同时只能开一个容器',
        '<p>' + (other || '你已经有一个题目容器在运行。') + '</p>'
        + '<p>回 <a href="' + PUBLIC + '/" target=_top>平台</a> 把当前容器销毁，再回来刷新本页即可自动启动本题容器。</p>');
    }
    if (started.status === 201) t = { kind: 'starting', status: (started.body && started.body.status) || 'PENDING' };
    else if (started.status === 403) return sendPage(res, 403, '当前不可作答', '<p>赛事未开放或你没有参赛资格。</p>');
    else if (started.status === 429) return sendPage(res, 429, '请求过于频繁', '<p>平台限流，请等 1 分钟后刷新本页。</p>');
    else return sendPage(res, 502, '启动容器失败', '<p>' + JSON.stringify(started.body).slice(0, 200) + '</p>');
  }
  if (t.kind === 'ok') return proxyTo(code, prefix, t, req, res);
  if (t.kind === 'login') return sendPage(res, 401, '登录已失效', '<p>平台登录态过期，回 <a href="' + PUBLIC + '/" target=_top>平台</a> 重新登录。</p>');
  if (t.kind === 'starting') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', Refresh: '2; url=' + (prefix || '/') });
    return res.end('<!doctype html><meta charset=utf-8><meta http-equiv=refresh content="2"><title>容器启动中</title>' + CSS
      + '<div class=b><h1>容器启动中</h1>' + STARTING_PAGE(code, t.status) + '</div>');
  }
  if (t.kind === 'failed') return sendPage(res, 502, '容器启动失败', '<p><code>' + (t.msg || '') + '</code></p><p class=s>回 <a href="' + PUBLIC + '/" target=_top>平台</a> 销毁后重试。</p>');
  if (t.kind === 'closed') return sendPage(res, 403, '当前不可作答', '<p>赛事未开放或你不在参赛名单内。</p>');
  return sendPage(res, 502, '网关异常', '<p>' + (t.msg || 'unknown') + '</p>');
}

const GUIDE = (() => {
  try { return fs.readFileSync(new URL('./guide.js', import.meta.url), 'utf8'); } catch (e) { return ''; }
})();

http.createServer((req, res) => {
  if (req.url.split('?')[0] === '/tq-guide.js') {
    res.writeHead(200, { 'Content-Type': 'application/javascript; charset=utf-8', 'Cache-Control': 'no-cache' });
    return res.end(GUIDE || '/* guide missing */');
  }
  const m = /^\/entry\/([A-Za-z0-9_-]+)(\/.*)?$/.exec(req.url.split('?')[0]);
  if (!m || !CODES.includes(m[1])) return sendPage(res, 404, '入口不存在', '<p>可用入口：' + CODES.join('、') + '</p>');
  serve(m[1], '/entry/' + m[1] + '/', req, res);
}).listen(8090, '0.0.0.0', () => console.log('listen 8090 prefix-mode'));

for (const code of CODES) {
  http.createServer((req, res) => serve(code, '', req, res))
    .listen(PORTMAP[code], '0.0.0.0', () => console.log('listen ' + PORTMAP[code] + ' -> ' + code));
}
console.log('gateway v3 up, event=' + EVENT + ' api=' + API);
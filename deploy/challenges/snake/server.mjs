import http from 'node:http';
import { randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { createContext, Script } from 'node:vm';

export const LEVEL_ONE_TARGET = 999;
export const LEVEL_TWO_TARGET = 99999;

function createUuidV7() {
  const bytes = randomBytes(16);
  const timestamp = BigInt(Date.now());
  for (let index = 5; index >= 0; index--) {
    bytes[index] = Number((timestamp >> BigInt((5 - index) * 8)) & 0xffn);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

// One flag is created when this Node process starts and shared by its sessions.
export const STARTUP_FLAG = `SACC{${createUuidV7()}}`;
const realmSource = await readFile(new URL('./lib/level-two.js', import.meta.url), 'utf8');
const realmScript = new Script(realmSource, { filename: 'level-two.js' });
const BODY_LIMIT = 8192;
const SESSION_TTL = 4 * 60 * 60 * 1000;
const assets = new Map(await Promise.all([
  ['/', './public/index.html', 'text/html; charset=utf-8'],
  ['/assets/style.css', './public/style.css', 'text/css; charset=utf-8'],
  ['/assets/app.js', './public/app.js', 'text/javascript; charset=utf-8'],
  ['/favicon.svg', './public/favicon.svg', 'image/svg+xml']
].map(async ([route, path, type]) => [route, { type, body: await readFile(new URL(path, import.meta.url)) }])));

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function reply(res, status, data, type = 'application/json; charset=utf-8', headers = {}) {
  const body = Buffer.isBuffer(data) || typeof data === 'string' ? data : JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': type,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'same-origin',
    'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    ...headers
  });
  res.end(body);
}

async function jsonBody(req) {
  if (req.headers['content-type']?.split(';')[0].trim() !== 'application/json') {
    throw new HttpError(415, '请使用 application/json。');
  }
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > BODY_LIMIT) throw new HttpError(413, '请求体过大。');
    chunks.push(chunk);
  }
  try {
    const raw = Buffer.concat(chunks).toString('utf8');
    const value = JSON.parse(raw);
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error();
    return { value, raw };
  } catch { throw new HttpError(400, '请求体必须是 JSON 对象。'); }
}

function validateScore(value) {
  if (Object.keys(value).length !== 1 || !Object.hasOwn(value, 'delta') || value.delta !== 1) {
    throw new HttpError(400, '每次只能提交 {"delta":1}。');
  }
}

function validateRound(value) {
  if (Object.keys(value).length !== 1 || !Object.hasOwn(value, 'level') || ![1, 2].includes(value.level)) {
    throw new HttpError(400, '关卡编号无效。');
  }
}

function validateProfile(value) {
  if (Object.keys(value).length !== 1 || !Object.hasOwn(value, 'preferences') ||
      value.preferences === null || typeof value.preferences !== 'object' || Array.isArray(value.preferences)) {
    throw new HttpError(400, '只接受 preferences 配置对象。');
  }
  let keys = 0;
  const walk = (item, depth) => {
    if (depth > 12) throw new HttpError(400, '配置层级过深。');
    if (item !== null && typeof item === 'object') {
      if (Array.isArray(item)) throw new HttpError(400, '配置不接受数组。');
      for (const key of Object.keys(item)) {
        if (++keys > 256) throw new HttpError(400, '配置字段过多。');
        walk(item[key], depth + 1);
      }
    }
  };
  walk(value, 0);
}

function newRealm() {
  // Separate JS intrinsics per player. Only this fixed script runs; input is JSON.
  // node:vm is used for realm separation, NOT to execute untrusted JavaScript.
  const context = createContext(Object.create(null), {
    codeGeneration: { strings: false, wasm: false }
  });
  return realmScript.runInContext(context, { timeout: 1000 });
}

export function createChallengeServer(options = {}) {
  // 平台通过 flagEnvName 注入本轮队伍 flag；未注入时回落到启动期随机值
  const flag = options.flag ?? (process.env.FLAG?.trim() || STARTUP_FLAG);
  const maxSessions = options.maxSessions ?? 200;
  const sessions = new Map();
  const sweep = () => {
    const now = Date.now();
    for (const [id, session] of sessions) if (now - session.touched > SESSION_TTL) sessions.delete(id);
  };
  const janitor = setInterval(sweep, 60000);
  janitor.unref();

  function findSession(req) {
    const id = /(?:^|;\s*)nibble_sid=([a-f0-9]{48})(?:;|$)/.exec(req.headers.cookie ?? '')?.[1];
    const session = id ? sessions.get(id) : undefined;
    if (!session || Date.now() - session.touched > SESSION_TTL) {
      if (id) sessions.delete(id);
      return undefined;
    }
    session.touched = Date.now();
    return session;
  }

  function state(session) {
    const stage2 = session.realm ? JSON.parse(session.realm.snapshot()) : null;
    if (stage2 && (stage2.cleared === true || stage2.score >= LEVEL_TWO_TARGET)) {
      stage2.cleared = true;
      session.passed2 = true;
    }
    return {
      target: session.passed1 ? LEVEL_TWO_TARGET : LEVEL_ONE_TARGET,
      targets: { 1: LEVEL_ONE_TARGET, 2: LEVEL_TWO_TARGET },
      level: session.passed1 ? 2 : 1,
      stage1: { score: session.score1, rawScore: session.score1, cleared: session.passed1 },
      stage2,
      flag: session.passed2 ? flag : null
    };
  }

  const server = http.createServer(async (req, res) => {
    try {
      const path = new URL(req.url, 'http://nibble.local').pathname;
      const method = req.method;
      if (method === 'GET' && assets.has(path)) {
        const asset = assets.get(path);
        return reply(res, 200, asset.body, asset.type);
      }
      if (method === 'GET' && path === '/healthz') return reply(res, 200, { ok: true });
      if (method === 'GET' && path === '/api/session') {
        let session = findSession(req);
        let headers = {};
        if (!session) {
          sweep();
          if (sessions.size >= maxSessions) throw new HttpError(503, '当前挑战人数已满，请稍后再试。');
          const id = randomBytes(24).toString('hex');
          session = { score1: 0, passed1: false, passed2: false, realm: null, touched: Date.now() };
          sessions.set(id, session);
          const secure = req.socket.encrypted || req.headers['x-forwarded-proto'] === 'https';
          headers = { 'Set-Cookie': `nibble_sid=${id}; HttpOnly; SameSite=Strict; Path=/; Max-Age=14400${secure ? '; Secure' : ''}` };
        }
        return reply(res, 200, state(session), undefined, headers);
      }
      const routes = new Set(['/api/level/1/score', '/api/level/2/score', '/api/profile', '/api/round/reset', '/api/reset', '/api/flag', '/assets/score-service.js']);
      if (!routes.has(path)) throw new HttpError(404, '没有这个接口。');
      if ((['/api/flag', '/assets/score-service.js'].includes(path) ? 'GET' : 'POST') !== method) {
        throw new HttpError(405, '请求方法不支持。');
      }
      const session = findSession(req);
      if (!session) throw new HttpError(401, '会话已过期，请刷新页面。');
      if (method === 'POST' && req.headers.origin) {
        let origin;
        try { origin = new URL(req.headers.origin); } catch { throw new HttpError(403, '来源无效。'); }
        if (origin.host !== req.headers.host) throw new HttpError(403, '只接受同源请求。');
      }
      if (path === '/api/reset') {
        await jsonBody(req);
        session.score1 = 0;
        session.passed1 = false;
        session.passed2 = false;
        session.realm = null;
        return reply(res, 200, state(session));
      }
      if (path === '/api/round/reset') {
        const { value } = await jsonBody(req);
        validateRound(value);
        if (value.level === 2 && !session.passed1) throw new HttpError(403, '请先完成第一关。');
        if (value.level === 1) {
          session.score1 = 0;
        } else {
          session.realm = newRealm();
          session.passed2 = false;
        }
        return reply(res, 200, state(session));
      }
      if (path === '/api/flag') {
        if (!session.passed2) throw new HttpError(403, '请先完成两关挑战。');
        return reply(res, 200, { flag });
      }
      if (path !== '/api/level/1/score' && !session.passed1) throw new HttpError(403, '请先完成第一关。');
      if (path === '/assets/score-service.js') return reply(res, 200, realmSource, 'text/javascript; charset=utf-8');
      const { value, raw } = await jsonBody(req);
      if (path === '/api/profile') {
        validateProfile(value);
        try { session.realm.updatePreferences(raw); }
        catch { throw new HttpError(400, '这份配置无法合并；可重置挑战后重试。'); }
        return reply(res, 200, state(session));
      }
      validateScore(value);
      if (path === '/api/level/1/score') {
        // Intentionally trusts replayed client events, with no movement proof.
        session.score1 = Math.min(session.score1 + 1, LEVEL_ONE_TARGET);
        if (session.score1 === LEVEL_ONE_TARGET && !session.passed1) {
          session.realm = newRealm();
          session.passed1 = true;
        }
        // The intended solution replays 999 small, valid scoring requests.
        return reply(res, 200, { level: 1, score: session.score1, target: LEVEL_ONE_TARGET, cleared: session.passed1 });
      }
      const result = JSON.parse(session.realm.scoreOne());
      if (result.cleared === true || result.score >= LEVEL_TWO_TARGET) {
        result.cleared = true;
        session.passed2 = true;
      }
      return reply(res, 200, { level: 2, ...result, flag: session.passed2 ? flag : null });
    } catch (error) {
      if (!res.headersSent) reply(res, error.status ?? 500, { error: error.status ? error.message : '计分服务暂时不可用。' });
      else res.end();
      if (!error.status) console.error('Request failed:', error.message);
    }
  });
  server.requestTimeout = 15000;
  server.headersTimeout = 10000;
  server.on('close', () => clearInterval(janitor));
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const port = Number(process.env.PORT ?? 3000);
  createChallengeServer().listen(port, process.env.HOST ?? '0.0.0.0', () => {
    console.log(`NIBBLE CTF ready: http://localhost:${port}`);
  });
}

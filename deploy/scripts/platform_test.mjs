import { spawnSync } from 'node:child_process';
const BASE = 'http://127.0.0.1:8080';
const EVENT = 'evt_3m5wyvbmhkzza6fpww';
const USER = 'tq-player-01';
const PASS = process.env.TQ_PASS || 'CHANGE_ME';   // 导出为环境变量，不要写进仓库
const H = { 'Content-Type': 'application/json', Origin: BASE, Referer: BASE + '/' };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function login() {
  const r = await fetch(BASE + '/api/v1/auth/login', { method: 'POST', headers: H, body: JSON.stringify({ username: USER, password: PASS }) });
  let tk = null;
  for (const c of r.headers.getSetCookie()) { const m = /^tq_session=([^;]+)/.exec(c); if (m) tk = m[1]; }
  if (!tk) throw new Error('login failed ' + r.status + ' ' + (await r.text()).slice(0, 200));
  return tk;
}

const ITEMS = [
  ['SUDOKU', 'jigsaw', '/home/sacc/jigsaw_payload.json'],
  ['SNAKE', 'snake', ''],
  ['MISSING', 'missing', '/home/sacc/missing_fixtures.json'],
  ['MORSE', 'morse', ''],
];

const tk = await login();
const A = { ...H, Authorization: 'Bearer ' + tk };
async function api(method, path, body) {
  const r = await fetch(BASE + path, { method, headers: A, body: body ? JSON.stringify(body) : undefined });
  const t = await r.text();
  return { status: r.status, text: t };
}

let prev = 'HC-01';
for (const [code, kind, data] of ITEMS) {
  await api('DELETE', '/api/v1/events/' + EVENT + '/challenges/' + prev + '/runtime/instances/me');
  await sleep(1500);
  let s = await api('POST', '/api/v1/events/' + EVENT + '/challenges/' + code + '/runtime/instances');
  if (s.status >= 300) { console.log(JSON.stringify({ code, stage: 'start', status: s.status, body: s.text.slice(0, 200) })); continue; }
  let vo = JSON.parse(s.text);
  for (let i = 0; i < 60 && vo.status !== 'READY'; i++) {
    await sleep(2000);
    const g = await api('GET', '/api/v1/events/' + EVENT + '/challenges/' + code + '/runtime/instances/me');
    if (g.status === 200) vo = JSON.parse(g.text);
  }
  if (vo.status !== 'READY') { console.log(JSON.stringify({ code, stage: 'ready', status: vo.status })); prev = code; continue; }
  const url = 'http://' + vo.containerIp + ':' + vo.containerPort;
  const r = spawnSync('node', ['/home/sacc/tqsolve.mjs', kind, url, ...(data ? [data] : [])], { encoding: 'utf8', timeout: 120000 });
  const m = /SACC\{[^}]*\}/.exec((r.stdout || '') + (r.stderr || ''));
  if (!m) { console.log(JSON.stringify({ code, stage: 'solve', out: (r.stdout || '').slice(-200) })); prev = code; continue; }
  const sub = await api('POST', '/api/v1/challenges/' + code + '/submissions', { answer: m[0] });
  console.log(JSON.stringify({ code, solve: 'ok', submit: sub.status, body: sub.text.slice(0, 220) }));
  prev = code;
  await sleep(1200);
}
await api('DELETE', '/api/v1/events/' + EVENT + '/challenges/' + prev + '/runtime/instances/me');

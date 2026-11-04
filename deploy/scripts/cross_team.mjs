import { spawnSync } from 'node:child_process';
const BASE = 'http://127.0.0.1:8080';
const EVENT = 'evt_3m5wyvbmhkzza6fpww';
const H = { 'Content-Type': 'application/json', Origin: BASE, Referer: BASE + '/' };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
async function token(u, p) {
  const r = await fetch(BASE + '/api/v1/auth/login', { method: 'POST', headers: H, body: JSON.stringify({ username: u, password: p }) });
  for (const c of r.headers.getSetCookie()) { const m = /^tq_session=([^;]+)/.exec(c); if (m) return m[1]; }
  throw new Error('login ' + u + ' ' + r.status);
}
async function call(tk, method, path, body) {
  const r = await fetch(BASE + path, { method, headers: { ...H, Authorization: 'Bearer ' + tk }, body: body ? JSON.stringify(body) : undefined });
  return { status: r.status, text: await r.text() };
}
const out = {};
const p1 = await token('tq-player-01', process.env.TQ_PASS1 || 'CHANGE_ME');
const p2 = await token('tq-player-02', process.env.TQ_PASS2 || 'CHANGE_ME');
out.team2 = (await call(p2, 'POST', '/api/v1/events/' + EVENT + '/teams', { name: '测试二队' })).text.slice(0, 200);
await sleep(800);
await call(p2, 'POST', '/api/v1/events/' + EVENT + '/challenges/MORSE/runtime/instances');
let vo = null;
for (let i = 0; i < 40; i++) {
  await sleep(2000);
  const g = await call(p2, 'GET', '/api/v1/events/' + EVENT + '/challenges/MORSE/runtime/instances/me');
  if (g.status === 200) { vo = JSON.parse(g.text); if (vo.status === 'READY') break; }
}
out.p2inst = vo ? vo.status + ' ' + vo.containerIp + ':' + vo.containerPort : 'none';
if (vo && vo.status === 'READY') {
  const r = spawnSync('node', ['/home/sacc/tqsolve.mjs', 'morse', 'http://' + vo.containerIp + ':' + vo.containerPort], { encoding: 'utf8', timeout: 120000 });
  const m = /SACC\{[^}]*\}/.exec(r.stdout || '');
  out.flagB = m ? m[0].slice(0, 40) + '...' : 'solve-fail';
  if (m) {
    out.crossSubmit = (await call(p1, 'POST', '/api/v1/challenges/MORSE/submissions', { answer: m[0] })).text.slice(0, 240);
    out.wrongSubmit = (await call(p2, 'POST', '/api/v1/challenges/MORSE/submissions', { answer: 'SACC{definitely-wrong}' })).text.slice(0, 240);
    out.ownSubmit = (await call(p2, 'POST', '/api/v1/challenges/MORSE/submissions', { answer: m[0] })).text.slice(0, 240);
  }
}
out.lb = (await call(p1, 'GET', '/api/v1/leaderboard?eventId=' + EVENT)).text.slice(0, 700);
console.log(JSON.stringify(out, null, 1));

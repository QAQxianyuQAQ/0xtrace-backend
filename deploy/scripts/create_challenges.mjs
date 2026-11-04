
import fs from 'node:fs';
import path from 'node:path';
const BASE = process.env.TQ_BASE || 'http://PLATFORM_HOST:8081';
const ORIGIN = BASE;
const dir = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const cred = JSON.parse(fs.readFileSync(path.join(dir, 'accounts.json'), 'utf8'));
const EVENT = 'evt_3m5wyvbmhkzza6fpww';

async function login(u) {
  const res = await fetch(BASE + '/api/v1/auth/login', { method: 'POST',
    headers: { 'Content-Type': 'application/json', Origin: ORIGIN, Referer: ORIGIN + '/' },
    body: JSON.stringify({ username: u, password: cred[u] }) });
  const t = await res.text();
  if (!res.ok) throw new Error('login ' + res.status + ' ' + t);
  const cookies = res.headers.getSetCookie() || [];
  for (const c of cookies) { const m = /^tq_session=([^;]+)/.exec(c); if (m) return m[1]; }
  throw new Error('no tq_session: ' + JSON.stringify(cookies));
}
async function call(token, method, p, body) {
  const res = await fetch(BASE + p, { method,
    headers: { Origin: ORIGIN, Referer: ORIGIN + '/', Authorization: 'Bearer ' + token, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined });
  const t = await res.text();
  let j = null; try { j = t ? JSON.parse(t) : null; } catch { j = t; }
  return { status: res.status, body: j };
}

const defs = [
  { code: 'HC-01', title: '人机验证', category: '杂项', difficulty: '简单', points: 100,
    dockerImage: 'sacc-humancheck', dockerTag: 'deploy',
    description: '你报名了 SACC 2026，收到一条补录链接。点开是一个再普通不过的报名页面：「请完成人机验证」。一个「我不是机器人」的复选框、一串看不清的验证码、一个「验证并继续」按钮。右侧的审核员小安已经上线，看起来不是很想让你通过。而且你会发现，通过第一道之后，后面还有五道。\n\n启动容器后在浏览器打开地址开始作答。' },
  { code: 'SUDOKU', title: '异形数独拼图', category: '杂项', difficulty: '中等', points: 300,
    dockerImage: 'sacc/jigsaw-sudoku', dockerTag: '1.0',
    description: '9 块形状各异的碎片，每块印着几个数字。第一步把 9 块碎片拼成一个 9×9 正方形（碎片不能旋转，只能平移，不能重叠也不能留空）；第二步填数独 —— 每行、每列、每块碎片内部的数字恰好是 1–9 各一次。\n\n启动容器后打开地址作答。' },
  { code: 'SNAKE', title: '贪吃蛇', category: 'Web', difficulty: '中等', points: 200,
    dockerImage: 'sacc/snake-ctf', dockerTag: 'latest',
    description: '一个用 Node 写的贪吃蛇小游戏。分数被服务器信任，等级被服务器信任，连对象原型也被服务器信任了。\n\n启动容器后打开地址，拿到 SACC{...} 形式的 flag。' },
  { code: 'MISSING', title: '消失的方块', category: '杂项', difficulty: '困难', points: 300,
    dockerImage: 'sacc-missing-square', dockerTag: 'tq',
    description: '一个会「消失」的方块。你必须让它在正确的位置、以正确的顺序出现两次，服务端才会承认你真的解开了它。\n\n启动容器后打开地址作答。' },
  { code: 'MORSE', title: '摩斯电码与错误 MD5', category: '杂项', difficulty: '中等', points: 200,
    dockerImage: 'sacc-morse-md5', dockerTag: '1.0.0-tq',
    description: '第一关是摩斯电码，第二关藏在源码注释里的一个「错误的 MD5」。把它修正成正确的 32 位十六进制摘要，交给服务端。\n\n启动容器后打开地址作答。' },
  { code: 'SRCFLAG', title: '查看源码', category: 'Web', difficulty: '简单', points: 100,
    dockerImage: 'sacc-source-flag', dockerTag: 'tq',
    description: 'flag 就写在页面源码里 —— 前提是你先找到正确的那个路由。盯着首页看是看不出来的。\n\n启动容器后打开地址作答。' },
];

const token = await login('tq-admin-01');
const out = [];
for (const d of defs) {
  const body = { ...d, flagEnvName: 'FLAG', flagPrefix: 'SACC', bonuses: { first: Math.round(d.points * 0.3), second: Math.round(d.points * 0.15), third: Math.round(d.points * 0.05) } };
  const r = await call(token, 'POST', '/api/v1/admin/events/' + EVENT + '/challenges', body);
  out.push({ code: d.code, status: r.status, id: r.body?.id, code2: r.body?.code, err: r.status >= 400 ? r.body : undefined });
}
console.log(JSON.stringify(out));

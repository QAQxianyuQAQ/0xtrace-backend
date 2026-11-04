
import fs from 'node:fs';
const [kind, BASE, dataFile] = process.argv.slice(2);
const j = async (p, body, opt = {}) => {
  const headers = { ...(opt.cookie ? { Cookie: opt.cookie } : {}) };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const r = await fetch(BASE + p, { method: body === undefined ? 'GET' : (opt.method || 'POST'), headers, body: body === undefined ? undefined : JSON.stringify(body), redirect: 'follow' });
  const sc = r.headers.getSetCookie?.() || [];
  const txt = await r.text(); let d; try { d = JSON.parse(txt); } catch { d = txt; }
  return { status: r.status, d, txt, cookie: (opt.cookie || '') + sc.map(c => c.split(';')[0] + '; ').join('') };
};
const flagOf = (o) => typeof o === 'string' ? (o.match(/SACC\{[^}]*\}/) || [])[0] : (o && (o.flag || o.answer)) ;

async function humancheck() {
  const s = await j('/api/session');
  const { sid, code, hidden, crash, phrase } = s.d;
  const answers = { 1: '', 2: code, 3: hidden, 4: phrase, 5: crash };
  for (const step of [1, 2, 3, 4, 5]) await j('/api/progress', { sid, step, answer: answers[step] });
  const c = await j('/api/claim', { sid });
  return { status: c.status, flag: flagOf(c.d), raw: c.d };
}
async function snake() {
  let cookie = '';
  const rq = async (p, opt = {}) => {
    const headers = {}; if (cookie) headers.Cookie = cookie;
    if (opt.body !== undefined) headers['Content-Type'] = 'application/json';
    const r = await fetch(BASE + p, { method: opt.method || 'GET', headers, body: opt.body === undefined ? undefined : JSON.stringify(opt.body) });
    const sc = r.headers.getSetCookie?.() || []; if (sc.length) cookie = sc.map(c => c.split(';')[0]).join('; ');
    const txt = await r.text(); let d; try { d = JSON.parse(txt); } catch { d = txt; }
    return { status: r.status, d };
  };
  const s = await rq('/api/session');
  const t1 = s.d.target;
  for (let i = 0; i < t1; i++) { const r = await rq('/api/level/1/score', { method: 'POST', body: { delta: 1 } }); if (r.status !== 200) return { status: r.status, err: r.d, at: i }; }
  await rq('/api/profile', { method: 'POST', body: { preferences: { ['__proto__']: { bestScore: s.d.targets[2] } } } });
  await rq('/api/level/2/score', { method: 'POST', body: { delta: 1 } });
  const f = await rq('/api/flag');
  return { status: f.status, flag: flagOf(f.d), raw: f.d };
}
async function morse() {
  let cookie = '';
  const rq = async (p, opt = {}) => {
    const headers = {}; if (cookie) headers.Cookie = cookie;
    if (opt.body !== undefined) headers['Content-Type'] = 'application/json';
    const r = await fetch(BASE + p, { method: opt.method || 'GET', headers, body: opt.body === undefined ? undefined : JSON.stringify(opt.body) });
    const sc = r.headers.getSetCookie?.() || []; if (sc.length) cookie = sc.map(c => c.split(';')[0]).join('; ');
    const txt = await r.text(); let d; try { d = JSON.parse(txt); } catch { d = txt; }
    return { status: r.status, d, txt };
  };
  await rq('/api/level1', { method: 'POST', body: { answer: 'youaregreat' } });
  const p2 = await rq('/level2');
  const m = /base64）：([A-Za-z0-9+/=]+)/.exec(p2.txt) || /([A-Za-z0-9+/=]{40,})/.exec(p2.txt);
  const hint = Buffer.from(m[1], 'base64').toString('utf8');
  const hex = hint.replace(/[^0-9a-f]/g, '');
  const ok = await rq('/api/flag', { method: 'POST', body: { flag: hex } });
  return { status: ok.status, hexLen: hex.length, flag: flagOf(ok.d), raw: ok.d };
}
async function jigsaw() {
  const payload = JSON.parse(fs.readFileSync(dataFile, 'utf8'));
  const r = await j('/api/submit', payload);
  return { status: r.status, flag: flagOf(r.d), raw: r.d };
}
async function missing() {
  const fx = JSON.parse(fs.readFileSync(dataFile, 'utf8'));
  let cookie = '';
  const rq = async (p, body) => {
    const headers = {}; if (cookie) headers.Cookie = cookie;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const r = await fetch(BASE + p, { method: body === undefined ? 'GET' : 'POST', headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const sc = r.headers.getSetCookie?.() || []; if (sc.length) cookie = sc.map(c => c.split(';')[0]).join('; ');
    const txt = await r.text(); let d; try { d = JSON.parse(txt); } catch { d = txt; }
    return { status: r.status, d };
  };
  let st = (await rq('/api/puzzle')).d;
  const act = async (action, move) => {
    const body = { token: st.token, revision: st.revision };
    if (move !== undefined) body.move = move;
    const r = await rq('/api/' + action, body);
    if (r.status !== 200) throw new Error(action + ' -> ' + r.status + ' ' + JSON.stringify(r.d));
    st = r.d; return st;
  };
  for (const mv of fx['1'][0]) await act('move', mv);
  for (const mv of fx['2'][0]) await act('move', mv);
  const chk = await act('check');
  return { status: 200, solved: st.solved, flag: flagOf(chk), raw: chk };
}
async function srcflag() {
  const r = await fetch(BASE + '/SACC');
  const t = await r.text();
  return { status: r.status, flag: flagOf(t), found: /SACC\{/.test(t) };
}
const map = { humancheck, snake, morse, jigsaw, missing, srcflag };
try {
  const res = await map[kind]();
  console.log('RESULT ' + JSON.stringify({ kind, ...res }));
} catch (e) { console.log('RESULT ' + JSON.stringify({ kind, error: String(e && e.message || e) })); }

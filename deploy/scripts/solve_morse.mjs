const BASE = 'http://127.0.0.1:18085';
let cookie = '';
async function req(p, opt = {}) {
  const headers = {}; if (cookie) headers.Cookie = cookie;
  if (opt.body !== undefined) headers['Content-Type'] = 'application/json';
  const r = await fetch(BASE + p, { method: opt.method || 'GET', headers, body: opt.body === undefined ? undefined : JSON.stringify(opt.body) });
  const sc = r.headers.get('set-cookie'); if (sc) cookie = sc.split(';')[0];
  const txt = await r.text(); let data; try { data = JSON.parse(txt); } catch { data = txt; }
  return { status: r.status, data, txt };
}
const l1 = await req('/api/level1', { method: 'POST', body: { answer: 'youaregreat' } });
console.log('level1', l1.status, JSON.stringify(l1.data));
const p2 = await req('/level2');
const m = /base64）：([A-Za-z0-9+/=]+)/.exec(p2.txt);
const hint = Buffer.from(m[1], 'base64').toString('utf8');
console.log('hint', hint);
const hex = hint.replace(/[^0-9a-f]/g, '');
const sha = (await import('node:crypto')).createHash('sha256');
console.log('hexlen', hex.length, hex);
// 用 sha256(注入 flag 内层) 前 32 位交叉验证谜题本体已随平台 flag 改变
const inner = process.env.TESTFLAG.replace(/^SACC\{/, '').replace(/\}$/, '');
const expect = sha.update(inner).digest('hex').slice(0, 32);
console.log('expect', expect, 'match', expect === hex);
const ok = await req('/api/flag', { method: 'POST', body: { flag: hex } });
console.log('submit-hex', ok.status, JSON.stringify(ok.data));
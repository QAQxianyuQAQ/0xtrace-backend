const BASE = 'http://127.0.0.1:18081';
const j = async (p, body) => {
  const r = await fetch(BASE + p, body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const t = await r.text(); let d; try { d = JSON.parse(t); } catch { d = t; }
  return { status: r.status, d };
};
const s = await j('/api/session');
console.log('session', s.status, JSON.stringify(s.d));
const { sid, code, hidden, crash, phrase } = s.d;
const answers = { 1: '', 2: code, 3: hidden, 4: phrase, 5: crash };
for (const step of [1,2,3,4,5]) {
  const r = await j('/api/progress', { sid, step, answer: answers[step] });
  console.log('step', step, r.status, JSON.stringify(r.d));
}
const c = await j('/api/claim', { sid });
console.log('CLAIM', c.status, JSON.stringify(c.d));
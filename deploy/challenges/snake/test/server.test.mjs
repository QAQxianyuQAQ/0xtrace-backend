import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createChallengeServer,
  LEVEL_ONE_TARGET,
  LEVEL_TWO_TARGET,
  STARTUP_FLAG
} from '../server.mjs';

async function startChallenge(t) {
  const server = createChallengeServer({
    flag: 'flag{test-nibble}',
    maxSessions: 4
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())));

  const address = server.address();
  assert.equal(typeof address, 'object');
  return `http://127.0.0.1:${address.port}`;
}

function cookieFrom(response) {
  return response.headers.get('set-cookie')?.split(';', 1)[0];
}

test('creates one startup-scoped SACC UUID v7 flag', () => {
  assert.match(STARTUP_FLAG, /^SACC\{[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\}$/);
});

async function request(base, path, { cookie, method = 'GET', body, format = 'json' } = {}) {
  const headers = {};
  if (cookie) headers.Cookie = cookie;
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(`${base}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const data = format === 'text' ? await response.text() : await response.json();
  return { response, data };
}

test('creates a strict session cookie and serves the health check', async (t) => {
  const base = await startChallenge(t);
  const health = await request(base, '/healthz');
  assert.equal(health.response.status, 200);
  assert.deepEqual(health.data, { ok: true });

  const session = await request(base, '/api/session');
  const cookie = cookieFrom(session.response);
  assert.equal(session.response.status, 200);
  assert.match(cookie, /^nibble_sid=[a-f0-9]{48}$/);
  assert.match(session.response.headers.get('set-cookie'), /HttpOnly/);
  assert.match(session.response.headers.get('set-cookie'), /SameSite=Strict/);
  assert.deepEqual(session.data, {
    target: LEVEL_ONE_TARGET,
    targets: { 1: LEVEL_ONE_TARGET, 2: LEVEL_TWO_TARGET },
    level: 1,
    stage1: { score: 0, rawScore: 0, cleared: false },
    stage2: null,
    flag: null
  });

  const sameSession = await request(base, '/api/session', { cookie });
  assert.equal(sameSession.response.status, 200);
  assert.equal(sameSession.response.headers.has('set-cookie'), false);
  assert.deepEqual(sameSession.data, session.data);
});

test('enforces the score and origin contracts', async (t) => {
  const base = await startChallenge(t);
  const session = await request(base, '/api/session');
  const cookie = cookieFrom(session.response);

  const wrongBody = await request(base, '/api/level/1/score', {
    cookie,
    method: 'POST',
    body: { delta: 2 }
  });
  assert.equal(wrongBody.response.status, 400);
  assert.match(wrongBody.data.error, /只能提交/);

  const wrongOrigin = await fetch(`${base}/api/level/1/score`, {
    method: 'POST',
    headers: {
      Cookie: cookie,
      Origin: 'https://attacker.invalid',
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ delta: 1 })
  });
  assert.equal(wrongOrigin.status, 403);
});

test('completes both levels through the intended replay and prototype-pollution chain', async (t) => {
  const base = await startChallenge(t);
  const session = await request(base, '/api/session');
  const cookie = cookieFrom(session.response);

  for (let score = 0; score < LEVEL_ONE_TARGET; score++) {
    const result = await request(base, '/api/level/1/score', {
      cookie,
      method: 'POST',
      body: { delta: 1 }
    });
    assert.equal(result.response.status, 200);
    if (score === LEVEL_ONE_TARGET - 1) {
      assert.equal(result.data.score, LEVEL_ONE_TARGET);
      assert.equal(result.data.cleared, true);
    }
  }

  const source = await request(base, '/assets/score-service.js', { cookie, format: 'text' });
  assert.equal(source.response.status, 200);
  assert.match(source.response.headers.get('content-type'), /^text\/javascript/);
  assert.match(source.data, /function mergePreferences/);

  const polluted = await request(base, '/api/profile', {
    cookie,
    method: 'POST',
    body: { preferences: { ['__proto__']: { bestScore: LEVEL_TWO_TARGET } } }
  });
  assert.equal(polluted.response.status, 200);
  assert.equal(polluted.data.stage2.score, LEVEL_TWO_TARGET);
  assert.equal(polluted.data.stage2.rawScore, 0);
  assert.equal(polluted.data.stage2.cleared, true);
  assert.equal(polluted.data.flag, 'flag{test-nibble}');

  const finalScore = await request(base, '/api/level/2/score', {
    cookie,
    method: 'POST',
    body: { delta: 1 }
  });
  assert.equal(finalScore.response.status, 200);
  assert.equal(finalScore.data.rawScore, 1);
  assert.equal(finalScore.data.score, LEVEL_TWO_TARGET);
  assert.equal(finalScore.data.cleared, true);
  assert.equal(finalScore.data.flag, 'flag{test-nibble}');

  const flag = await request(base, '/api/flag', { cookie });
  assert.deepEqual(flag.data, { flag: 'flag{test-nibble}' });

  const roundReset = await request(base, '/api/round/reset', {
    cookie,
    method: 'POST',
    body: { level: 2 }
  });
  assert.equal(roundReset.response.status, 200);
  assert.deepEqual(roundReset.data.stage2, {
    rawScore: 0,
    score: 0,
    target: LEVEL_TWO_TARGET,
    cleared: false,
    palette: 'mint'
  });
  assert.equal(roundReset.data.flag, null);
});

test('reset starts a fresh challenge session', async (t) => {
  const base = await startChallenge(t);
  const session = await request(base, '/api/session');
  const cookie = cookieFrom(session.response);

  await request(base, '/api/level/1/score', {
    cookie,
    method: 'POST',
    body: { delta: 1 }
  });
  const roundReset = await request(base, '/api/round/reset', {
    cookie,
    method: 'POST',
    body: { level: 1 }
  });
  assert.equal(roundReset.response.status, 200);
  assert.deepEqual(roundReset.data.stage1, { score: 0, rawScore: 0, cleared: false });

  const reset = await request(base, '/api/reset', {
    cookie,
    method: 'POST',
    body: {}
  });
  assert.equal(reset.response.status, 200);
  assert.deepEqual(reset.data.stage1, { score: 0, rawScore: 0, cleared: false });
  assert.equal(reset.data.stage2, null);
  assert.equal(reset.data.flag, null);
});

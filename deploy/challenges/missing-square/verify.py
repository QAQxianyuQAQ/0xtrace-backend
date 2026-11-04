"""Organizer verification. Fixtures are excluded from the Docker image."""
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from http.cookiejar import CookieJar
import json
from pathlib import Path
import sys
from urllib.error import HTTPError
from urllib.request import Request, build_opener, HTTPCookieProcessor
import uuid
from puzzle import PIECES, BOARDS, solved, can_place
from sessions import Sessions, SessionError

FIXTURES = json.loads(Path(__file__).with_name('verification-fixtures.json').read_text())


def local_checks():
    assert len(PIECES) == 8
    assert all(len(board['cells']) == 40 for board in BOARDS.values())
    assert len(FIXTURES['1']) == 40 and len(FIXTURES['2']) == 1
    for stage in (1, 2):
        for moves in FIXTURES[str(stage)]:
            assert solved(moves, stage)
            for move in moves:
                assert can_place(move, moves, stage)
            assert not solved(moves[:-1], stage)
            assert not solved(moves[:-1] + [moves[0]], stage)
    # Every first-stage solution advances, with no client-controlled stage field.
    for moves in FIXTURES['1']:
        sessions = Sessions()
        sid, state = sessions.get(None)
        for i, move in enumerate(moves):
            state = sessions.update(sid, 'move', {k:state[k] for k in ('token','revision')} | {'move':move})
            assert not state['solved']
            assert state['stage'] == (2 if i == 7 else 1)
        assert all(p['zone'] == 'source' for p in state['placements'])
    print('PASS: all 40 rectangle layouts advance; key layout valid; missing/duplicate pieces rejected')


class Client:
    def __init__(self, base):
        self.base = base
        self.opener = build_opener(HTTPCookieProcessor(CookieJar()))
        self.state = {}

    def get(self, path):
        with self.opener.open(self.base+path, timeout=5) as r:
            return r.read().decode()

    def start(self):
        self.state = json.loads(self.get('/api/puzzle'))

    def body(self, move=None):
        body = {k:self.state[k] for k in ('token','revision')}
        if move is not None:
            body['move'] = move
        return body

    def post(self, action, body, raw=None):
        req = Request(self.base+'/api/'+action, data=raw if raw is not None else json.dumps(body).encode(),
                      headers={'Content-Type':'application/json'})
        try:
            with self.opener.open(req, timeout=5) as r:
                return r.status, json.load(r)
        except HTTPError as e:
            return e.code, json.load(e)

    def action(self, action, move=None):
        code, state = self.post(action, self.body(move))
        assert code == 200, (code, state)
        self.state = state
        return state


def http_checks(base):
    a, b = Client(base), Client(base)
    assert a.post('check', {'placements':FIXTURES['2'][0]})[0] == 403
    a.start(); b.start()
    assert a.state['stage'] == 1 and 'flag' not in a.state
    for path in ('/SACC','/api/puzzle','/assets/game.js','/assets/style.css'):
        page = a.get(path)
        assert 'SACC{' not in page and 'board_b_solution' not in page
    assert a.get('/SACC').count('用全部拼片填满矩形，再用同样的拼片拼出钥匙。') == 1
    for path in ('/puzzle.py','/sessions.py','/read_flag.py','/flag','/api/flag',
                 '/proc/1/environ','/assets/../puzzle.py','/assets/%2e%2e/puzzle.py',
                 '/.env','/source/puzzle.py','/verification-fixtures.json','/verified-key.json','/verify.py'):
        try: a.get(path)
        except HTTPError as e: assert e.code == 404
        else: raise AssertionError('Private file exposed: '+path)
    for field, value in [('stage',2),('solved',True),('placements',FIXTURES['2'][0]),('flag','anything')]:
        assert a.post('check', a.body() | {field:value})[0] == 400
    assert not a.action('check')['solved']
    assert a.post('check', b.body())[0] == 403
    assert a.post('check', a.body() | {'revision':a.state['revision']+1})[0] == 409
    assert a.post('check', a.body() | {'token':'中文'})[0] == 403
    first = FIXTURES['1'][0][0]
    for key,value in [('x',True),('x',float('nan')),('x',float('inf')),('x',10**500),
                      ('x','0'),('x',.5),('x',-1),('id',[]),('id','unknown'),('zone',[]),
                      ('rotation',True),('rotation',4),('rotation',-1),('rotation',1.0),
                      ('flipped',1),('flipped','false')]:
        code,response = a.post('move',a.body(first | {key:value}))
        assert code == 400 and 'flag' not in response, (key,code,response)
    assert a.post('check',None,raw=b'{"token":"x","token":"y","revision":0}')[0] == 400
    assert a.post('move',a.body(FIXTURES['2'][0][0]))[0] == 400  # outside stage-one outline
    old = a.body(first)
    assert a.action('move',first)['stage'] == 1
    assert a.post('move',old)[0] == 403
    assert b.post('move',a.body(first))[0] == 403
    # Deliberately overlap a different piece.
    overlap = FIXTURES['1'][0][1] | {'x':0,'y':0}
    assert a.post('move',a.body(overlap))[0] == 400
    assert json.loads(a.get('/api/puzzle'))['placements'] == a.state['placements']
    for move in FIXTURES['1'][0][1:]:
        state = a.action('move',move)
        assert 'flag' not in state
    assert state['stage'] == 2 and state['advanced'] and not state['solved']
    assert a.action('reset')['stage'] == 2
    # The notch is not a valid cell, even inside the board's rectangular bounds.
    invalid = {'id':'I','zone':'target','x':4,'y':4,'rotation':0,'flipped':False}
    assert a.post('move',a.body(invalid))[0] == 400
    for i,move in enumerate(FIXTURES['2'][0]):
        state = a.action('move',move)
        if i < 7:
            assert not state['solved'] and 'flag' not in state
    assert state['solved']
    flag = state['flag']
    assert flag.startswith('SACC{') and uuid.UUID(flag[5:-1]).version == 1
    assert a.action('check')['flag'] == flag
    assert 'flag' not in json.loads(a.get('/api/puzzle'))
    assert b.action('check')['stage'] == 1 and 'flag' not in b.state
    assert a.action('reset')['stage'] == 2 and not a.state['solved']
    assert 'flag' not in a.action('check')
    body = a.body(FIXTURES['2'][0][0])
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda _:a.post('move',body), range(2)))
    assert sorted(code for code,_ in responses) == [200,403]
    print('PASS: no public flag or answer; stage skip, forged state, overlap, notch, malformed values and replay rejected')
    print('PASS: both stages required; valid completion returns stable UUID v1 flag; reset clears completion')


if __name__ == '__main__':
    local_checks()
    if len(sys.argv) > 1:
        http_checks(sys.argv[1].rstrip('/'))

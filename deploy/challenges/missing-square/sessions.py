"""Server-owned puzzle states; no client-provided final state is trusted."""
from copy import deepcopy
import secrets
import threading
import time
from puzzle import BOARDS, PIECES, can_place, solved


class SessionError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


class Sessions:
    def __init__(self):
        self.items = {}
        self.lock = threading.Lock()

    @staticmethod
    def initial():
        return {p['id']: {'id': p['id'], 'zone': 'source', 'x': 0, 'y': 0,
                         'rotation': 0, 'flipped': False} for p in PIECES}

    @staticmethod
    def public(state):
        return {'token': state['token'], 'revision': state['revision'],
                'stage': state['stage'], 'board': deepcopy(BOARDS[state['stage']]),
                'solved': state['stage'] == 2 and solved(list(state['placements'].values()), 2),
                'placements': deepcopy(list(state['placements'].values()))}

    def get(self, session_id):
        with self.lock:
            now = time.monotonic()
            for key in [k for k, v in self.items.items() if now-v['seen'] > 3600]:
                del self.items[key]
            state = self.items.get(session_id)
            if state is None:
                if len(self.items) >= 4096:
                    raise SessionError(503, 'Try again later')
                session_id = secrets.token_urlsafe(32)
                state = {'token': secrets.token_urlsafe(32), 'revision': 0,
                         'placements': self.initial(), 'stage': 1, 'seen': now}
                self.items[session_id] = state
            state['seen'] = now
            return session_id, self.public(state)

    def update(self, session_id, action, payload):
        with self.lock:
            if action not in ('move', 'check', 'reset'):
                raise SessionError(400, 'Invalid action')
            state = self.items.get(session_id)
            if state is None or time.monotonic()-state['seen'] > 3600:
                raise SessionError(403, 'Session required')
            expected_keys = {'token', 'revision', 'move'} if action == 'move' else {'token', 'revision'}
            if not isinstance(payload, dict) or set(payload) != expected_keys:
                raise SessionError(400, 'Invalid request')
            token = payload['token']
            if not isinstance(token, str) or not token.isascii() or not secrets.compare_digest(token, state['token']):
                raise SessionError(403, 'Invalid token')
            if type(payload['revision']) is not int or payload['revision'] != state['revision']:
                raise SessionError(409, 'Stale revision')
            if action == 'move':
                move = payload['move']
                if not can_place(move, state['placements'].values(), state['stage']):
                    raise SessionError(400, 'Invalid move')
                state['placements'][move['id']] = dict(move)
            elif action == 'reset':
                state['placements'] = self.initial()
            advanced = state['stage'] == 1 and solved(list(state['placements'].values()), 1)
            if advanced:
                state['stage'] = 2
                state['placements'] = self.initial()
            state['seen'] = time.monotonic()
            state['revision'] += 1
            state['token'] = secrets.token_urlsafe(32)
            result = self.public(state)
            result['advanced'] = advanced
            return result

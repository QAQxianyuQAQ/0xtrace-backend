from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http.cookies import SimpleCookie, CookieError
import json
import os
from pathlib import Path
from urllib.parse import urlsplit
from puzzle import PIECES
from sessions import Sessions, SessionError

FLAG = os.environ["FLAG"]
SESSIONS = Sessions()
ROOT = Path(__file__).with_name("web")
ASSETS = {"/SACC": ("index.html", "text/html; charset=utf-8"),
          "/assets/game.js": ("game.js", "text/javascript; charset=utf-8"),
          "/assets/style.css": ("style.css", "text/css; charset=utf-8")}


class Handler(BaseHTTPRequestHandler):
    server_version = "SACC"
    sys_version = ""

    def session_id(self):
        try:
            cookie = SimpleCookie(self.headers.get('Cookie', ''))
            return cookie['sacc_session'].value if 'sacc_session' in cookie else None
        except CookieError:
            return None

    def respond(self, code, body, content_type="application/json; charset=utf-8", cookie=None):
        if isinstance(body, dict):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if cookie:
            self.send_header('Set-Cookie', f'sacc_session={cookie}; HttpOnly; SameSite=Strict; Path=/api; Max-Age=3600')
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/":
            self.send_response(302)
            self.send_header("Location", "/SACC")
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif path in ASSETS:
            filename, kind = ASSETS[path]
            self.respond(200, (ROOT / filename).read_bytes(), kind)
        elif path == "/api/puzzle":
            if self.command == 'HEAD':
                self.respond(200, {})
                return
            try:
                sid, state = SESSIONS.get(self.session_id())
                self.respond(200, {"pieces": PIECES, **state}, cookie=sid)
            except SessionError as exc:
                self.respond(exc.status, {'error': exc.message})
        else:
            self.respond(404, {"error": "Not found"})

    do_HEAD = do_GET

    def do_POST(self):
        path = urlsplit(self.path).path
        if path not in ('/api/move', '/api/check', '/api/reset'):
            self.respond(404, {"error": "Not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.respond(400, {"error": "Invalid Content-Length"})
            return
        if length < 1 or length > 16384:
            self.respond(413, {"error": "Invalid body size"})
            return
        if self.headers.get_content_type() != "application/json":
            self.respond(415, {"error": "Expected application/json"})
            return
        try:
            payload = json.loads(self.rfile.read(length), parse_constant=reject_constant, object_pairs_hook=unique_keys)
        except (ValueError, UnicodeDecodeError, RecursionError):
            self.respond(400, {"error": "Invalid JSON"})
            return
        try:
            result = SESSIONS.update(self.session_id(), path.rsplit('/', 1)[1], payload)
            if result['solved']:
                result['flag'] = FLAG
            self.respond(200, result)
        except SessionError as exc:
            self.respond(exc.status, {'error': exc.message})


def reject_constant(value):
    raise ValueError('Non-finite JSON number')


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON field')
        result[key] = value
    return result


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", int(os.getenv("PORT", "5000"))), Handler)
    server.socket.settimeout(None)
    print(f"SACC puzzle listening on port {server.server_port}", flush=True)
    server.serve_forever()

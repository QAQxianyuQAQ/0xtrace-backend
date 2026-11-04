#!/usr/bin/env python3
"""《人机验证》服务端。

职责很窄：发页面、记进度、发凭据。
- 凭据只存在于环境变量（比赛平台注入），前端源码里没有。
- 玩家每完成一步，页面调 /api/progress 上报；五步齐了 /api/claim 才发凭据。
- 不依赖第三方库，Python 3.12 标准库。
"""
import json
import os
import re
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

FLAG_RE = re.compile(r"^SACC\{[^{}\r\n]{1,240}\}$")
FLAG = os.environ.get("FLAG", "").strip()
if not FLAG_RE.match(FLAG):
    print("FLAG 缺失或格式不合法（应为 SACC{...}），拒绝启动。", file=sys.stderr)
    sys.exit(2)

HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", "8080"))
TTL_SECONDS = int(os.environ.get("TTL_SECONDS", "7200"))
RATE_MAX = int(os.environ.get("RATE_MAX", "40"))
RATE_WINDOW = 60
TOTAL_STEPS = 5
MAX_BODY = 4096

ROOT = Path(__file__).resolve().parent
PAGE_PATH = ROOT / "public" / "index.html"
if not PAGE_PATH.is_file():
    print("缺少 public/index.html（先运行 tools/build.py 生成）", file=sys.stderr)
    sys.exit(2)
PAGE = PAGE_PATH.read_bytes()
STARTED = time.monotonic()

_lock = threading.Lock()
_sessions = {}
_buckets = {}

ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
PHRASE = "I AM NOT A ROBOT"


def new_challenge():
    """每个会话一套新谜底：静态源码里读不到任何一局的答案。"""
    return {
        "code": "".join(secrets.choice(ALNUM) for _ in range(5)),
        "hidden": "h_" + secrets.token_hex(4),
        "crash": "0x%08X" % (secrets.randbelow(0x0FFFFFF0) + 0x0F000000),
    }


def norm(text):
    return " ".join(str(text or "").upper().split())


def now():
    return time.monotonic()


def allow(ip):
    with _lock:
        bucket = _buckets.setdefault(ip, [])
        cutoff = time.time() - RATE_WINDOW
        while bucket and bucket[0] < cutoff:
            bucket.pop(0)
        if len(bucket) >= RATE_MAX:
            return False
        bucket.append(time.time())
        return True


class Handler(BaseHTTPRequestHandler):
    server_version = "humancheck/1.0"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, body, ctype="application/json; charset=utf-8", extra=None):
        data = body if isinstance(body, bytes) else json.dumps(
            body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def _page(self):
        self._send(200, PAGE, "text/html; charset=utf-8", {
            "Content-Security-Policy": ("default-src 'none'; style-src 'unsafe-inline'; "
                                        "script-src 'unsafe-inline' 'unsafe-eval'; "
                                        "connect-src 'self'; img-src data:; "
                                        "base-uri 'none'; frame-ancestors 'none'"),
            "X-Frame-Options": "DENY",
        })

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY:
            return None, "too_large"
        raw = self.rfile.read(length) if length else b""
        try:
            body = json.loads(raw.decode("utf-8") or "{}")
        except (ValueError, UnicodeDecodeError):
            return None, "bad_json"
        return (body if isinstance(body, dict) else None), None

    def do_GET(self):
        path = self.path.split("?")[0]
        if path in ("/", "/index.html"):
            return self._page()
        if path == "/favicon.ico":
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if not allow(self.client_address[0]):
            return self._send(429, {"error": "太快了，歇一下"}, {"Retry-After": "60"})
        if path == "/api/health":
            remaining = max(0, int(TTL_SECONDS - (now() - STARTED)))
            return self._send(200, {"ok": True, "remaining_seconds": remaining})
        if path == "/api/session":
            sid = secrets.token_hex(16)
            challenge = new_challenge()
            with _lock:
                _sessions[sid] = {"steps": 0, "created": now(),
                                  "code": challenge["code"],
                                  "hidden": challenge["hidden"],
                                  "crash": challenge["crash"]}
            return self._send(200, {"sid": sid, "steps": 0,
                                    "code": challenge["code"],
                                    "hidden": challenge["hidden"],
                                    "crash": challenge["crash"],
                                    "phrase": PHRASE})
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?")[0]
        if not allow(self.client_address[0]):
            return self._send(429, {"error": "太快了，歇一下"}, {"Retry-After": "60"})
        body, err = self._read_json()
        if err:
            return self._send(413 if err == "too_large" else 400,
                              {"error": "请求不合法"})
        sid = body.get("sid")
        with _lock:
            session = _sessions.get(sid) if isinstance(sid, str) else None
        if not session:
            return self._send(403, {"error": "会话无效"})

        if path == "/api/progress":
            try:
                step = int(body.get("step"))
            except (TypeError, ValueError):
                return self._send(400, {"error": "step 不合法"})
            answer = norm(body.get("answer"))
            wanted = {2: norm(session["code"]), 3: norm(session["hidden"]),
                      4: norm(PHRASE), 5: norm(session["crash"])}
            with _lock:
                expected = session["steps"] + 1
                if step != expected or not (1 <= step <= TOTAL_STEPS):
                    return self._send(409, {"error": "进度不对"})
                if step in wanted and answer != wanted[step]:
                    return self._send(409, {"error": "答案不对"})
                session["steps"] = step
            return self._send(200, {"ok": True, "steps": session["steps"]})

        if path == "/api/claim":
            if session["steps"] < TOTAL_STEPS:
                return self._send(403, {"error": "还没走完"})
            return self._send(200, {"flag": FLAG, "steps": session["steps"]})

        return self._send(404, {"error": "not found"})


def watchdog():
    while True:
        time.sleep(5)
        if now() - STARTED >= TTL_SECONDS:
            os._exit(0)


def main():
    threading.Thread(target=watchdog, daemon=True).start()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print("service_ready port=%d lifetime=%ds" % (PORT, TTL_SECONDS), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""异形数独拼图 · 容器题服务端（只用 Python 标准库，无第三方依赖）

路由：
  GET  /              玩家页面（题面数据内联在页面里，页面里没有答案）
  GET  /api/health    健康检查 + 容器剩余时间
  GET  /api/puzzle    公开题面数据
  POST /api/submit    {"placement":[[dr,dc]×9], "grid":[81×数字]} → 校验 → {ok,flag} / {ok:false,msg}

环境变量：
  FLAG_ENV             放着 flag 的环境变量名（默认 FLAG）—— 平台可换成任意名字
  PORT                 监听端口（默认 8080）
  BIND                 监听地址（默认 0.0.0.0）
  TTL_SECONDS          容器存活秒数，到点自动退出（默认 7200 = 2 小时）
  SUBMIT_LIMIT_PER_MIN 每 IP 每分钟最多提交次数（默认 20）
  DEV_RELOAD=1         每次请求重新读 index.html（本机调试用）
  DEV_LOG_ANSWER=1     出题人本机测试用：启动时把答案读进来，便于 --selftest

安全设计：
  * 镜像里**没有明文答案**，只有 data/answer.sha256；flag 只在提交正确时返回。
  * flag 不写进镜像层，只从环境变量读（启动时校验存在性，提交时再读一次，
    这样平台后注入的环境变量也能生效）。
  * 不做“提交后锁定”，重复提交无副作用；平台侧按首个正确提交计分。
  * 到 TTL 自动退出（平台会回收容器），避免长期挂机。
"""
import hashlib
import hmac
import json
import os
import socket
import sys
import threading
import time
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

FLAG_ENV = os.environ.get("FLAG_ENV", "FLAG").strip() or "FLAG"
PORT = int(os.environ.get("PORT", "8080"))
BIND = os.environ.get("BIND", "0.0.0.0").strip() or "0.0.0.0"
TTL = int(os.environ.get("TTL_SECONDS", "7200"))
LIMIT = int(os.environ.get("SUBMIT_LIMIT_PER_MIN", "20"))
DEV_RELOAD = os.environ.get("DEV_RELOAD", "") == "1"
MAX_BODY = 64 * 1024

START = time.time()
DEADLINE = START + TTL
_lock = threading.Lock()
_hits = defaultdict(deque)          # ip -> 提交时间戳
_stats = {"submit": 0, "ok": 0, "bad": 0, "blocked": 0}


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def fatal(msg):
    log("FATAL " + msg)
    sys.exit(2)


# ---------------------------------------------------------------- 题面 / 答案
def load_puzzle():
    path = os.path.join(ROOT, "data", "puzzle.json")
    if not os.path.exists(path):
        fatal("找不到 %s —— 请在出题机上先跑 tools/make_puzzle.py" % path)
    with open(path, encoding="utf-8") as f:
        pub = json.load(f)
    if not isinstance(pub.get("pieces"), list) or not pub.get("N"):
        fatal("data/puzzle.json 内容不合法")
    return pub


def load_digest():
    path = os.path.join(ROOT, "data", "answer.sha256")
    if not os.path.exists(path):
        fatal("找不到 %s —— 请在出题机上先跑 tools/make_puzzle.py" % path)
    d = open(path, encoding="utf-8").read().strip().split()[0].lower()
    if len(d) != 64:
        fatal("data/answer.sha256 不是合法的 sha256")
    return d


PUBLIC = load_puzzle()
N = PUBLIC["N"]
PIECES = PUBLIC["pieces"]
DIGEST = load_digest()


def canonical(n, placement, grid):
    """规范化答案串 —— 必须与 tools/make_puzzle.py 里的 canonical() 完全一致。"""
    pl = ",".join("%d:%d:%d" % (i, dr, dc) for i, (dr, dc) in enumerate(placement))
    gd = ",".join(str(v) for row in grid for v in row)
    return "sacc-jigsaw-sudoku-v1|N=%d|%s|%s" % (n, pl, gd)


def check_payload(obj):
    """结构 + 拼图合法性检查（全部基于公开数据，不泄露答案）。返回 (parsed, 错误信息)。"""
    if not isinstance(obj, dict):
        return None, "请求格式不对"
    pl, gd = obj.get("placement"), obj.get("grid")
    if not isinstance(pl, list) or len(pl) != len(PIECES):
        return None, "碎片落位数据不完整（需要 %d 块）" % len(PIECES)
    owner = {}
    for pi, item in enumerate(pl):
        if not (isinstance(item, list) and len(item) == 2):
            return None, "第 %d 块的落位格式不对" % (pi + 1)
        dr, dc = item
        if not (isinstance(dr, int) and isinstance(dc, int)) or isinstance(dr, bool) or isinstance(dc, bool):
            return None, "第 %d 块的落位必须是整数" % (pi + 1)
        for r, c in PIECES[pi]["cells"]:
            y, x = r + dr, c + dc
            if not (0 <= y < N and 0 <= x < N):
                return None, "第 %d 块放到了棋盘外面" % (pi + 1)
            if (y, x) in owner:
                return None, "第 %d 块和第 %d 块重叠了" % (pi + 1, owner[(y, x)] + 1)
            owner[(y, x)] = pi
    if len(owner) != N * N:
        return None, "碎片没有铺满整个棋盘"
    if not isinstance(gd, list):
        return None, "答案数据缺失"
    if len(gd) == N and all(isinstance(row, list) for row in gd):
        flat = [v for row in gd for v in row]
    elif len(gd) == N * N:
        flat = list(gd)
    else:
        return None, "答案数据格式不对（需要 %d×%d）" % (N, N)
    for v in flat:
        if not isinstance(v, int) or isinstance(v, bool) or not (1 <= v <= N):
            return None, "答案里出现了非法数字"
    return (pl, [flat[i * N:(i + 1) * N] for i in range(N)]), None


# ---------------------------------------------------------------- 限流
def rate_ok(ip):
    now = time.time()
    with _lock:
        q = _hits[ip]
        while q and now - q[0] > 60:
            q.popleft()
        if len(q) >= LIMIT:
            return False
        q.append(now)
        return True


# ---------------------------------------------------------------- TTL 看门狗
def watchdog():
    while True:
        left = DEADLINE - time.time()
        if left <= 0:
            log("TTL 到点（%d 秒），容器自动退出" % TTL)
            os._exit(0)
        time.sleep(min(5, max(1, left)))


# ---------------------------------------------------------------- HTTP
class Handler(BaseHTTPRequestHandler):
    server_version = "jigsaw-sudoku/1.0"
    protocol_version = "HTTP/1.1"

    # ---- 工具
    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, code, obj):
        self._send(code, json.dumps(obj, ensure_ascii=False))

    def log_message(self, fmt, *args):
        log("%s %s" % (self.address_string(), fmt % args))

    # ---- GET
    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ("/", "/index.html"):
            return self._send_page()
        if path == "/api/health":
            return self._json(200, {
                "ok": True, "ttl_left": max(0, int(DEADLINE - time.time())),
                "deadline": DEADLINE, "server_time": time.time(),
                "started": START, "stats": dict(_stats),
            })
        if path == "/api/puzzle":
            return self._json(200, PUBLIC)
        if path == "/favicon.ico":
            return self._send(204, b"")
        return self._json(404, {"ok": False, "msg": "没有这个地址"})

    def do_HEAD(self):
        self.do_GET()

    def _send_page(self):
        path = os.path.join(HERE, "index.html")
        if not os.path.exists(path):
            return self._json(500, {"ok": False, "msg": "index.html 缺失，镜像打包不完整"})
        try:
            html = open(path, encoding="utf-8").read()
        except OSError as e:
            return self._json(500, {"ok": False, "msg": "读取页面失败：%s" % e})
        self._send(200, html, "text/html; charset=utf-8")

    # ---- POST
    def do_POST(self):
        if self.path.split("?", 1)[0] != "/api/submit":
            return self._json(404, {"ok": False, "msg": "没有这个地址"})
        ip = self.client_address[0]
        if not rate_ok(ip):
            with _lock:
                _stats["blocked"] += 1
            return self._json(429, {"ok": False, "msg": "提交过于频繁，请等一分钟再试"})
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0 or n > MAX_BODY:
            return self._json(400, {"ok": False, "msg": "请求体大小不对"})
        raw = self.rfile.read(n)
        try:
            obj = json.loads(raw.decode("utf-8"))
        except Exception:
            return self._json(400, {"ok": False, "msg": "请求不是合法 JSON"})

        with _lock:
            _stats["submit"] += 1
        parsed, err = check_payload(obj)
        if err:
            with _lock:
                _stats["bad"] += 1
            log("提交被拒（%s）：%s" % (ip, err))
            return self._json(200, {"ok": False, "msg": err})

        pl, grid = parsed
        got = hashlib.sha256(canonical(N, pl, grid).encode("utf-8")).hexdigest()
        if not hmac.compare_digest(got, DIGEST):
            with _lock:
                _stats["bad"] += 1
            log("提交不正确（%s）" % ip)
            return self._json(200, {
                "ok": False,
                "msg": "拼法与答案不正确。检查：① 9 块是否铺满且不重叠；② 每行 / 每列 / 每块内 1–9 是否各出现一次。",
            })

        flag = (os.environ.get(FLAG_ENV) or "").strip()
        if not flag:
            # 理论上启动时已经挡掉，这里再兜一层，避免把空 flag 发出去
            log("ERROR 环境变量 %s 为空，无法返回 flag" % FLAG_ENV)
            return self._json(500, {"ok": False, "msg": "服务端未配置 flag，请联系出题人"})
        with _lock:
            _stats["ok"] += 1
        log("✅ 提交正确（%s），返回 flag" % ip)
        return self._json(200, {
            "ok": True, "flag": flag,
            "msg": "拼法与答案都正确！",
            "used": round(time.time() - START),
        })


def preflight():
    """启动自检：flag 环境变量必须存在 —— 容器开启失败要能立刻报错退出。"""
    flag = os.environ.get(FLAG_ENV)
    if flag is None:
        fatal("环境变量 %s 没有设置：启动命令需要形如 `-e %s=SACC{...}`。"
              "（平台登记的正确 flag 环境变量名必须与 FLAG_ENV 一致）" % (FLAG_ENV, FLAG_ENV))
    if not flag.strip():
        fatal("环境变量 %s 是空的，请填入本题的正确 flag。" % FLAG_ENV)
    if TTL <= 0:
        fatal("TTL_SECONDS 必须大于 0")
    return flag


def main():
    flag = preflight()
    log("=" * 62)
    log("异形数独拼图 · 容器题服务端启动")
    log("监听        http://%s:%d/" % (BIND, PORT))
    log("题面        %d×%d，%d 块碎片，%d 个提示数"
        % (N, N, len(PIECES), sum(len(p["digits"]) for p in PIECES)))
    log("flag 来源   环境变量 %s（长度 %d，已就绪；不打印明文）" % (FLAG_ENV, len(flag)))
    log("答案校验    sha256(%s…)，镜像内无明文答案" % DIGEST[:12])
    log("存活时间    %d 秒（%.1f 小时），到点自动退出" % (TTL, TTL / 3600.0))
    log("限流        每 IP 每分钟 %d 次提交" % LIMIT)
    log("=" * 62)
    threading.Thread(target=watchdog, daemon=True).start()
    srv = ThreadingHTTPServer((BIND, PORT), Handler)
    srv.daemon_threads = True
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("收到中断，退出")
    finally:
        srv.server_close()


if __name__ == "__main__":
    main()

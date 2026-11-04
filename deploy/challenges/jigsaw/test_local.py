# -*- coding: utf-8 -*-
"""容器题端到端自测（不需要 Docker）：直接起 app/server.py，验证

  T1  启动自检：没给 FLAG 环境变量 → 立刻报错退出（容器开启失败要能看见）
  T2  /api/health 正常，返回剩余时间
  T3  GET / 是玩家页面，且页面里没有答案、没有 flag
  T4  提交重叠/越界的拼法 → 被拒，并给出可读原因
  T5  拼法对但答案错 → 被拒（走 sha256 校验）
  T6  完全正确 → 返回 flag
  T7  自定义 flag 环境变量名（FLAG_ENV=MY_FLAG）也能拿到 flag
  T8  提交限流生效（超过每 IP 每分钟上限返回 429）
  T9  TTL 到点容器自动退出

用法：python 容器题\\test_local.py
"""
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "tools"))
from make_puzzle import answer_payload, build  # noqa: E402  出题人工具（顺带交叉验证 canonical 一致）

SERVER = os.path.join(HERE, "app", "server.py")
LOGDIR = tempfile.mkdtemp(prefix="jigsaw-test-")   # 日志丢临时目录，不污染题目文件夹
FAILS = []


def ok(name, cond, extra=""):
    print(("  OK   " if cond else "  FAIL ") + name + (" | " + str(extra) if extra != "" else ""))
    if not cond:
        FAILS.append(name)


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Proc:
    def __init__(self, env_extra, log_path):
        env = dict(os.environ)
        env.pop("FLAG", None)
        env.pop("MY_FLAG", None)
        env.update(env_extra)
        self.log_path = log_path
        self.fh = open(log_path, "w+", encoding="utf-8", errors="replace")
        self.p = subprocess.Popen([sys.executable, "-u", SERVER], env=env,
                                  stdout=self.fh, stderr=subprocess.STDOUT,
                                  cwd=HERE, text=True)

    def wait_http(self, port, secs=12):
        end = time.time() + secs
        while time.time() < end:
            if self.p.poll() is not None:
                return False
            try:
                with urllib.request.urlopen("http://127.0.0.1:%d/api/health" % port, timeout=1) as r:
                    if r.status == 200:
                        return True
            except Exception:
                time.sleep(0.2)
        return False

    def output(self):
        self.fh.flush()
        return open(self.log_path, encoding="utf-8", errors="replace").read()

    def stop(self):
        if self.p.poll() is None:
            self.p.terminate()
            try:
                self.p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.p.kill()
        self.fh.close()


def get(port, path, timeout=5):
    with urllib.request.urlopen("http://127.0.0.1:%d%s" % (port, path), timeout=timeout) as r:
        return r.status, r.read().decode("utf-8", "replace")


def post(port, path, obj, timeout=5):
    req = urllib.request.Request("http://127.0.0.1:%d%s" % (port, path),
                                 data=json.dumps(obj).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, {"raw": body}


def main():
    n, placement, grid = answer_payload()
    public, digest, canon = build()
    correct = {"placement": placement, "grid": grid}
    print("题面：%d×%d，%d 块，答案 sha256 %s…" % (n, n, len(public["pieces"]), digest[:16]))

    # ---------------------------------------------------------------- T1 缺 FLAG
    print("\n[T1] 启动自检：没有 FLAG 环境变量")
    p1 = Proc({"PORT": str(free_port()), "TTL_SECONDS": "60"}, os.path.join(LOGDIR, "t1.log"))
    try:
        p1.p.wait(timeout=15)
        out = p1.output()
        ok("T1a 进程自己退出且退出码非 0", p1.p.returncode not in (0, None), "code=%s" % p1.p.returncode)
        ok("T1b 日志点名了缺失的环境变量", "FLAG" in out and "FATAL" in out, out.strip().splitlines()[-1][:90])
    except subprocess.TimeoutExpired:
        ok("T1a 进程自己退出且退出码非 0", False, "20 秒还没退出（应该立刻报错退出）")
        ok("T1b 日志点名了缺失的环境变量", False, "进程没退出")
    finally:
        p1.stop()

    # ---------------------------------------------------------------- 正常实例
    flag = "SACC{local-e2e-8f3a1c}"
    port = free_port()
    srv = Proc({"PORT": str(port), "TTL_SECONDS": "90", "FLAG": flag,
                "SUBMIT_LIMIT_PER_MIN": "50"}, os.path.join(LOGDIR, "srv.log"))
    try:
        up = srv.wait_http(port)
        ok("T2a 服务能起来并响应 /api/health", up, "port=%d" % port)
        if not up:
            print(srv.output()[-1500:])
            raise SystemExit("服务没起来，后续测试跳过")
        st, body = get(port, "/api/health")
        j = json.loads(body)
        ok("T2b health 返回 ok 与剩余时间", st == 200 and j.get("ok") and 0 < j["ttl_left"] <= 90,
           "ttl_left=%s" % j.get("ttl_left"))

        # ------------------------------------------------------------ T3 页面不泄题
        st, html = get(port, "/")
        flat_answer = "".join(str(v) for v in grid)
        ok("T3a 页面能打开且是本题", st == 200 and "异形数独拼图" in html, "len=%d" % len(html))
        ok("T3b 页面里有全部 9 块碎片的形状", all(("\"cells\"" in html) for _ in [0]) and html.count("\"cells\"") == 9,
           "cells 出现 %d 次" % html.count("\"cells\""))
        ok("T3c 页面不含 flag 明文", flag not in html)
        ok("T3d 页面不含 solution / regions 字段", ("\"solution\"" not in html) and ("\"regions\"" not in html))
        ok("T3e 页面不含完整答案（81 位连排）", flat_answer not in html.replace(",", "").replace(" ", ""))
        ok("T3f 容器页面按钮是「提交答案」", "提交答案" in html and "显示答案" not in html)

        # ------------------------------------------------------------ T4 非法拼法
        bad1 = {"placement": [[0, 0]] * len(placement), "grid": grid}
        st, j = post(port, "/api/submit", bad1)
        ok("T4a 碎片全部叠在一起 → 被拒且说明重叠", j.get("ok") is False and "重叠" in j.get("msg", ""),
           j.get("msg"))
        bad2 = json.loads(json.dumps(correct))
        bad2["placement"][0] = [99, 99]
        st, j = post(port, "/api/submit", bad2)
        ok("T4b 碎片放到棋盘外 → 被拒", j.get("ok") is False and "外面" in j.get("msg", ""), j.get("msg"))
        bad3 = {"placement": placement, "grid": grid[:8]}
        st, j = post(port, "/api/submit", bad3)
        ok("T4c 答案行数不对 → 被拒", j.get("ok") is False, j.get("msg"))

        # ------------------------------------------------------------ T5 答案错
        bad4 = json.loads(json.dumps(correct))
        bad4["grid"][0][0] = (bad4["grid"][0][0] % n) + 1
        st, j = post(port, "/api/submit", bad4)
        ok("T5a 拼法对但答案错 → 被拒（sha256 校验）", j.get("ok") is False and "不正确" in j.get("msg", ""),
           j.get("msg"))

        # ------------------------------------------------------------ T6 正确
        st, j = post(port, "/api/submit", correct)
        ok("T6a 完全正确 → 返回 flag", j.get("ok") is True and j.get("flag") == flag, "flag=%s" % j.get("flag"))
        ok("T6b 正确提交里没有别的敏感字段", set(j.keys()) <= {"ok", "flag", "msg", "used"}, list(j.keys()))
        st, j2 = post(port, "/api/submit", correct)
        ok("T6c 重复提交仍然返回同一个 flag（可重试，平台按首个计分）",
           j2.get("ok") is True and j2.get("flag") == flag)

        # ------------------------------------------------------------ T8 限流
        limited = False
        for _ in range(60):
            st, j = post(port, "/api/submit", bad4)
            if st == 429 and j.get("ok") is False:
                limited = True
                break
        ok("T8a 提交频率超限会被 429 挡住", limited, "上限 50 次/分钟")
    finally:
        log = srv.output()
        srv.stop()

    ok("T6d 服务端日志里出现过「提交正确」但没有打印 flag 明文",
       "提交正确" in log and flag not in log, "")

    # ---------------------------------------------------------------- T7 自定义环境变量名
    flag2 = "SACC{renamed-env-f00d}"
    port2 = free_port()
    srv2 = Proc({"PORT": str(port2), "TTL_SECONDS": "60", "FLAG_ENV": "MY_FLAG",
                 "MY_FLAG": flag2}, os.path.join(LOGDIR, "srv2.log"))
    try:
        if srv2.wait_http(port2):
            st, j = post(port2, "/api/submit", correct)
            ok("T7a FLAG_ENV=MY_FLAG 时可从自定义环境变量取 flag",
               j.get("ok") is True and j.get("flag") == flag2, "flag=%s" % j.get("flag"))
        else:
            ok("T7a FLAG_ENV=MY_FLAG 时可从自定义环境变量取 flag", False, "服务没起来")
    finally:
        srv2.stop()

    # ---------------------------------------------------------------- T9 TTL 自动退出
    port3 = free_port()
    srv3 = Proc({"PORT": str(port3), "TTL_SECONDS": "3", "FLAG": "SACC{ttl}"},
                os.path.join(LOGDIR, "srv3.log"))
    t0 = time.time()
    try:
        exited = False
        try:
            srv3.p.wait(timeout=20)
            exited = srv3.p.returncode == 0
        except subprocess.TimeoutExpired:
            pass
        dt = time.time() - t0
        ok("T9a TTL 到点容器自己退出（退出码 0）", exited and 2 <= dt <= 15, "%.1fs code=%s" % (dt, srv3.p.returncode))
        ok("T9b 退出日志有说明", "TTL" in srv3.output())
    finally:
        srv3.stop()

    print("\n" + "=" * 60)
    if FAILS:
        print("FAILS=%d" % len(FAILS))
        for f in FAILS:
            print("  - " + f)
    else:
        print("FAILS=0 —— 容器题端到端全部通过")
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())

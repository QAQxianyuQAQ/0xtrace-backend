"""Generate one time-based flag, then replace PID 1 with the web server."""
import os
from pathlib import Path
import sys
import uuid


if __name__ == "__main__":
    # 平台注入本轮队伍 flag 时优先采用；未注入才回落到启动期随机 UUID
    injected = os.environ.get("FLAG", "").strip()
    if not injected:
        injected = f"SACC{{{uuid.uuid1()}}}"
    os.environ["FLAG"] = injected
    app = Path(__file__).resolve().with_name("app.py")
    os.execv(sys.executable, [sys.executable, str(app)])

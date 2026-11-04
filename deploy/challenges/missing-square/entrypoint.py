import os
import secrets
from pathlib import Path
import sys
import uuid

if __name__ == "__main__":
    # 平台注入本轮队伍 flag 时优先采用；未注入才回落到启动期随机 UUID
    injected = os.environ.get("FLAG", "").strip()
    if not injected:
        value = uuid.uuid1(node=secrets.randbits(48) | (1 << 40), clock_seq=secrets.randbits(14))
        injected = f"SACC{{{value}}}"
    os.environ["FLAG"] = injected
    os.execv(sys.executable, [sys.executable, str(Path(__file__).with_name("app.py"))])

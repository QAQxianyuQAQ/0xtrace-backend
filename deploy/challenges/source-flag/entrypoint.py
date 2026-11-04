"""Generate one time-based flag, then replace PID 1 with the web server."""
import os
from pathlib import Path
import sys
import uuid


if __name__ == "__main__":
    os.environ["FLAG"] = f"SACC{{{uuid.uuid1()}}}"
    app = Path(__file__).resolve().with_name("app.py")
    os.execv(sys.executable, [sys.executable, str(app)])

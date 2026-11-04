from pathlib import Path

for entry in Path("/proc/1/environ").read_bytes().split(b"\0"):
    if entry.startswith(b"FLAG="):
        print(entry[5:].decode())
        break
else:
    raise SystemExit("FLAG not found in PID 1 environment")

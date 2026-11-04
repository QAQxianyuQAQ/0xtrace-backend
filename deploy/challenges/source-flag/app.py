"""Single-route CTF challenge; no third-party dependencies."""
import html
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
from urllib.parse import urlsplit


PAGE = Path(__file__).with_name("index.html").read_text(encoding="utf-8").replace(
    "{{FLAG}}", html.escape(os.environ["FLAG"])
).encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    server_version = "SACC"
    sys_version = ""

    def serve_page(self, head=False):
        if urlsplit(self.path).path != "/SACC":
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(PAGE)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if not head:
            self.wfile.write(PAGE)

    def do_GET(self):
        self.serve_page()

    def do_HEAD(self):
        self.serve_page(head=True)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", int(os.getenv("PORT", "5000"))), Handler)
    print(f"SACC listening on port {server.server_port}", flush=True)
    server.serve_forever()

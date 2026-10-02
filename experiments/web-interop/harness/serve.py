#!/usr/bin/env python3
"""Static file server for the PR-6 harness that also collects the results.

The harness is opened in a real browser; the page POSTs its measurements back to
/result, and this server prints them and writes results.json. That keeps the
numbers in the repository instead of in a screenshot of a devtools console.

Usage: python3 serve.py [port]
"""

import http.server
import json
import pathlib
import socketserver
import sys

HERE = pathlib.Path(__file__).resolve().parent
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(HERE), **kwargs)

    def end_headers(self):
        # Kotlin/Wasm glue is served as an ES module; make sure nothing is cached
        # between runs of the experiment.
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def do_POST(self):
        if self.path != "/result":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8")
        try:
            mode = json.loads(body).get("mode", "unknown")
        except json.JSONDecodeError:
            mode = "unknown"
        (HERE / f"results-{mode}.json").write_text(body)
        print("\n===== RESULTS =====")
        try:
            print(json.dumps(json.loads(body), indent=2))
        except json.JSONDecodeError:
            print(body)
        print("===== END RESULTS =====\n", flush=True)
        self.send_response(204)
        self.end_headers()

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


def main():
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("127.0.0.1", PORT), Handler) as httpd:
        print(f"serving {HERE} at http://127.0.0.1:{PORT}/", flush=True)
        httpd.serve_forever()


if __name__ == "__main__":
    main()

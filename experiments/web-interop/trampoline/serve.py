#!/usr/bin/env python3
"""Serves dist/ and collects the harness results.

    uv run serve.py [port]

The harness POSTs its JSON results to /results; they are written to
`results.json` next to this script and the server then exits.
"""
import http.server
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).parent
DIST = HERE / "dist"
done = False


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(DIST), **kw)

    def do_POST(self):
        global done
        n = int(self.headers.get("Content-Length", 0))
        payload = self.rfile.read(n).decode()
        (HERE / "results.json").write_text(payload)
        self.send_response(204)
        self.end_headers()
        try:
            print("\n".join(json.loads(payload).get("log", [])))
        except json.JSONDecodeError:
            print(payload)
        done = True

    def log_message(self, *a):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"serving {DIST} on http://127.0.0.1:{port}/")
    while not done:
        srv.handle_request()
    print("results written to results.json")


if __name__ == "__main__":
    main()

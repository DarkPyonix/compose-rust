#!/usr/bin/env python3
"""Runs the harness in a browser several times over WebDriver and records every run.

    python3 run-bench.py safari [--runs 5]            # `open -a Safari`, no WebDriver
    python3 run-bench.py safari-webdriver [--runs 5]   # needs Allow Remote Automation
    python3 run-bench.py chrome --chrome <Chrome or chrome-headless-shell binary> [--headless]

Each run is a fresh page load, so each run instantiates all four modules from
scratch. The page POSTs its results here; they are collected into
`results/<label>.json` as a list, one entry per run, and a summary of the net
per-call cost is printed.

`safari` loads each run with `open -a Safari <url>`, which needs no setting
and is how the original measurement was taken. `safari-webdriver` drives
Safari over safaridriver instead and needs "Allow Remote Automation" (Safari > Settings > Advanced > Show
features for web developers, then Develop > Allow Remote Automation), which
`safaridriver --enable` also switches on.
"""
import argparse
import http.server
import json
import pathlib
import socket
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = pathlib.Path(__file__).parent
DIST = HERE / "dist"
RESULTS = HERE / "results"

received = []
received_lock = threading.Condition()


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(DIST), **kw)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        payload = json.loads(self.rfile.read(n).decode())
        self.send_response(204)
        self.end_headers()
        with received_lock:
            received.append(payload)
            received_lock.notify_all()

    def log_message(self, *a):
        pass


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wd(base, method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"WebDriver {method} {path}: {e.code} {e.read().decode()}")


def safari_version():
    out = subprocess.run(["defaults", "read", "/Applications/Safari.app/Contents/Info",
                          "CFBundleShortVersionString"], capture_output=True, text=True)
    return out.stdout.strip() or "?"


def chrome_version(binary):
    out = subprocess.run([binary, "--version"], capture_output=True, text=True)
    return out.stdout.strip() or "?"


def launch_chrome(args, url):
    """One Chrome process per run, so every run starts from a fresh engine."""
    profile = HERE / ".chrome-profile"
    chrome_args = [args.chrome, f"--user-data-dir={profile}", "--no-first-run",
                   "--no-default-browser-check", "--use-mock-keychain"]
    if args.headless:
        chrome_args.append("--headless=new")
    return subprocess.Popen(chrome_args + [url], stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL)


def start_driver(args):
    port = free_port()
    if args.browser == "safari":
        return None, None, None, safari_version()
    if args.browser == "safari-webdriver":
        proc = subprocess.Popen(["safaridriver", "-p", str(port)])
        caps = {"browserName": "safari"}
    else:
        return None, None, None, chrome_version(args.chrome)
    base = f"http://127.0.0.1:{port}"
    for _ in range(100):
        try:
            wd(base, "GET", "/status")
            break
        except OSError:
            time.sleep(0.1)
    session = wd(base, "POST", "/session", {"capabilities": {"alwaysMatch": caps}})
    sid = session["value"]["sessionId"]
    version = session["value"]["capabilities"].get("browserVersion", "?")
    return proc, base, sid, version


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("browser", choices=["safari", "safari-webdriver", "chrome"])
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--n", type=int, default=20_000_000, help="calls per sample")
    ap.add_argument("--reps", type=int, default=9, help="samples per edge per run")
    ap.add_argument("--label")
    ap.add_argument("--chrome")
    ap.add_argument("--headless", action="store_true")
    args = ap.parse_args()
    label = args.label or (args.browser + ("-headless" if args.headless else ""))

    port = free_port()
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    proc, base, sid, version = start_driver(args)
    print(f"{args.browser} {version}, {args.runs} runs, n={args.n}, reps={args.reps}")
    try:
        for run in range(args.runs):
            q = urllib.parse.urlencode({"label": label, "run": run, "n": args.n, "reps": args.reps})
            url = f"http://127.0.0.1:{port}/index.html?{q}"
            with received_lock:
                before = len(received)
            page = None
            if args.browser == "chrome":
                page = launch_chrome(args, url)
            elif sid is None:
                subprocess.run(["open", "-a", "Safari", url], check=True)
            else:
                wd(base, "POST", f"/session/{sid}/url", {"url": url})
            with received_lock:
                if not received_lock.wait_for(lambda: len(received) > before, timeout=900):
                    raise SystemExit(f"run {run}: the page never posted its results")
                r = received[-1]
            if page is not None:
                page.terminate()
                page.wait()
            r["run"] = run
            r["browserVersion"] = version
            failed = [c["name"] for c in r["checks"] if not c["ok"]]
            d = r["derived"]
            print(f"run {run}: checks {'FAIL ' + str(failed) if failed else 'pass'}; "
                  f"kotlin->rust trampoline {d['kotlinToRustDirectNs']:.2f} ns, "
                  f"kotlin->js->rust {d['kotlinToRustViaJsNs']:.2f} ns")
    finally:
        try:
            if sid is not None:
                wd(base, "DELETE", f"/session/{sid}")
        finally:
            if proc is not None:
                proc.terminate()
            srv.shutdown()

    RESULTS.mkdir(exist_ok=True)
    runs = [r for r in received if r.get("label") == label]
    (RESULTS / f"{label}.json").write_text(json.dumps(runs, indent=2) + "\n")

    print(f"\nnet ns per call, median over {len(runs)} runs (min .. max of the per-run medians)")
    for key in runs[0]["derived"]:
        vals = [r["derived"][key] for r in runs]
        print(f"  {key:30} {statistics.median(vals):7.2f}   ({min(vals):.2f} .. {max(vals):.2f})")
    print(f"written to {RESULTS / (label + '.json')}")


if __name__ == "__main__":
    main()

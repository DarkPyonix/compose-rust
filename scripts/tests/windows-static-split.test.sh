#!/usr/bin/env bash
# The Windows static renderer has its own workflow on pull requests (test-windows-static.yml),
# apart from test-native-renderer.yml, so either can be re-run alone. Three things have to hold:
#
#   1. Between them the two workflows start for every path the one workflow used to start for:
#      a path that test-native-renderer.yml reacts to and the Windows workflow does not must be
#      on the list of paths that cannot change the Windows renderer.
#   2. test-native-renderer.yml no longer runs Windows on a pull request but keeps it on every
#      other event, which the release job relies on.
#   3. The merge gate counts the new workflow like any other heavy one: it passes only with a
#      successful run, whose request job succeeded, on the current head.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
command -v python3 >/dev/null || { echo "FAIL - python3 is needed"; exit 1; }
cd "$repo_root" || exit 1
python3 -I - "$repo_root" <<'PY'
import importlib.util, os, subprocess, sys
from pathlib import Path

root = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("heavy_checks", root / ".github/scripts/heavy-checks.py")
hc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hc)

failures = 0
def ok(msg): print("ok   -", msg)
def bad(msg):
    global failures
    print("FAIL -", msg); failures += 1

by_file = {w.file: w for w in hc.workflows()}
native, windows = by_file["test-native-renderer.yml"], by_file["test-windows-static.yml"]

# 1. Coverage over every tracked file.
unrelated = (
    "renderer/macos/", "renderer/ios/", "renderer/linux/", "renderer/staticlib-macos/",
    "renderer/staticlib-linux/", "scripts/parity/", "scripts/install-nik.sh",
    ".github/scripts/check-linux-input-access.sh", ".github/scripts/atspi-walk.py",
    ".github/workflows/static-renderer.yml", ".github/workflows/fork-desktop-modules.yml",
)
files = subprocess.run(["git", "ls-files"], capture_output=True, text=True, cwd=root).stdout.split("\n")
files = [f for f in files if f]
uncovered = [f for f in files
             if hc.applies(native, [f]) and not hc.applies(windows, [f]) and not f.startswith(unrelated)]
if uncovered:
    bad("paths that start the native test but not the Windows one, and are not listed as unrelated: " + ", ".join(uncovered[:5]))
else:
    ok(f"every tracked path that starts the native test starts Windows too, or is listed as unrelated ({len(files)} files)")
for path, expected in [("renderer/windows/src/Main.kt", True), ("renderer/desktop/src/Main.kt", True),
                       ("compose-rust/build.rs", True), (".github/scripts/check-single-executable.sh", True),
                       ("renderer/macos/src/Main.kt", False), ("renderer/ios/src/shared/A.kt", False),
                       ("renderer/linux/src/shared/A.kt", False), ("docs/INTENT.md", False),
                       ("renderer/some-new-directory/A.kt", True)]:
    got = hc.applies(windows, [path])
    (ok if got == expected else bad)(f"Windows workflow {'starts' if expected else 'does not start'} for {path}")

# 2. Wiring in test-native-renderer.yml, read as text.
text = (root / ".github/workflows/test-native-renderer.yml").read_text()
block = text[text.index("  windows-static:\n"):]
block = block[:block.index("\n\n")]
(ok if "github.event_name != 'pull_request'" in block else bad)("native workflow skips Windows on pull requests")
(ok if "needs.changed.outputs.renderer == 'true'" in block else bad)("native workflow still runs Windows on tags, main, nightly and dispatch")
matrix = next(l for l in text.split("\n") if "matrix: ${{ fromJSON(" in l and "windows-latest" in l)
(ok if "github.event_name == 'pull_request'" in matrix and '"windows-x64"' in matrix else bad)(
    "the Windows single-executable leg leaves the native matrix on pull requests and stays for other events")

# 3. The gate counts the new workflow. Fake API, no network.
env = dict(GITHUB_TOKEN="t", GITHUB_REPOSITORY="o/r", PR_NUMBER="1", HEAD_SHA="abc",
           HAS_LABEL="false", POLL_SECONDS="0", GRACE_SECONDS="0")
os.environ.update(env)
def run_gate(changed, runs, requested_ok=True):
    def fake_api_all(path, token, key):
        return [{"filename": f} for f in changed] if "/files" in path else runs
    hc.api_all = fake_api_all
    hc.requested = lambda run, repo, token: requested_ok
    hc.summary = lambda text: None
    import io, contextlib
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        return hc.gate()
def run(file, conclusion="success", status="completed"):
    return {"path": f".github/workflows/{file}", "head_sha": "abc", "status": status,
            "conclusion": conclusion, "html_url": "u/" + file, "id": hash(file) % 1000, "run_attempt": 1}
changed = ["renderer/windows/src/Main.kt"]
needed = [w.file for w in hc.workflows() if w.is_heavy() and hc.applies(w, changed)]
if "test-windows-static.yml" not in needed:
    bad("a Windows source change does not require test-windows-static.yml")
all_ok = [run(f) for f in needed]
(ok if run_gate(changed, all_ok) == 0 else bad)("gate passes when every required workflow succeeded")
(ok if run_gate(changed, [r for r in all_ok if "windows-static" not in r["path"]]) == 1 else bad)(
    "gate fails when the Windows workflow has no run on this head")
(ok if run_gate(changed, [r if "windows-static" not in r["path"] else run("test-windows-static.yml", "failure") for r in all_ok]) == 1 else bad)(
    "gate fails when the Windows workflow failed")
(ok if run_gate(changed, all_ok, requested_ok=False) == 1 else bad)(
    "gate fails when the request job was skipped (the run was not asked for)")
mac_only = ["renderer/macos/src/Main.kt"]
needed_mac = [w.file for w in hc.workflows() if w.is_heavy() and hc.applies(w, mac_only)]
(ok if "test-windows-static.yml" not in needed_mac else bad)("a macOS-only change does not require the Windows workflow")
(ok if run_gate(mac_only, [run(f) for f in needed_mac]) == 0 else bad)("gate passes a macOS-only change without a Windows run")
sys.exit(1 if failures else 0)
PY
rc=$?
[[ $rc -eq 0 ]] && echo "all passed"
exit $rc

#!/usr/bin/env bash
# The fork's Compose build takes about an hour on macOS, so its publication is cached and a
# run whose pin is unchanged restores it instead of building. This pins that: the restore
# comes before the build, its key carries every input that decides the publication, the
# build is skipped on a hit, and the save never runs on a pull request or outside develop
# and main (a cache saved on another branch is invisible to develop).
#
# Read with the standard library only, as cache-pr-restore-only.test.sh does: PyYAML is not
# on every runner.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
command -v python3 >/dev/null || { echo "FAIL - python3 is needed to read the workflow"; exit 1; }
python3 -I - "$repo_root/.github/workflows/test-compose-from-fork.yml" <<'PY'
import re, sys

text = open(sys.argv[1]).read()
failures = 0
def check(ok, msg):
    global failures
    print(("ok   - " if ok else "FAIL - ") + msg)
    if not ok: failures += 1

steps = re.split(r"\n(?=      - )", text)
def find(pred):
    for i, s in enumerate(steps):
        if pred(s): return i, s
    return -1, ""
def name(s):
    m = re.search(r"name:\s*(.*)", s); return m.group(1).strip() if m else ""

ri, restore = find(lambda s: "actions/cache/restore@" in s and "id: fork-build\n" in s)
bi, build = find(lambda s: name(s) == "Build Compose from the fork")
si, save = find(lambda s: "actions/cache/save@" in s and "steps.fork-build.outputs.cache-primary-key" in s)

check(ri >= 0, "a restore step with id fork-build exists")
check(bi >= 0, "the fork build step exists")
check(0 <= ri < bi, "the restore comes before the fork build")
key = (re.search(r"\n\s+key:\s*(.*)", restore) or re.search("$", "")).group(0)
for part in ["runner.os", "matrix.target", "hashFiles('renderer/scripts/build-compose.sh')",
             "env.LEGACY_REF", "env.EQUIVALENT_REVISION"]:
    check(part in key, f"the restore key contains {part}")
check("path: ${{ env.FORK_REPO }}" in restore, "the restore puts the publication back at FORK_REPO")
check("if: steps.fork-build.outputs.cache-hit != 'true'" in build, "the fork build is skipped on a cache hit")
check("id: fork-build-run" in build, "the fork build has an id the save can check")
check(si > bi, "the save comes after the fork build")
cond = (re.search(r"\n\s+if:\s*(.*)", save) or re.search("$", "")).group(0)
check("github.event_name != 'pull_request'" in cond, "the save never runs on a pull request")
check("github.ref == 'refs/heads/develop'" in cond and "github.ref == 'refs/heads/main'" in cond,
      "the save runs only from develop or main")
check("steps.fork-build.outputs.cache-hit != 'true'" in cond, "the save skips a cache hit")
check("steps.fork-build-run.outcome == 'success'" in cond, "the save needs a successful build")
check("path: ${{ env.FORK_REPO }}" in save, "the save stores FORK_REPO")
sys.exit(1 if failures else 0)
PY
rc=$?
[[ $rc -eq 0 ]] && echo "all passed"
exit $rc

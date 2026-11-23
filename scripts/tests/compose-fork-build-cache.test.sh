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
for part in ["runner.os", "matrix.target", "hashFiles('renderer/scripts/build-compose.sh')"]:
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
def pin(label, rid, step_name, path, key_parts):
    ri, restore = find(lambda s: "actions/cache/restore@" in s and f"id: {rid}\n" in s)
    bi, build = find(lambda s: name(s) == step_name)
    si, save = find(lambda s: "actions/cache/save@" in s and f"steps.{rid}.outputs.cache-primary-key" in s)
    check(0 <= ri < bi, f"{label}: the restore comes before '{step_name}'")
    key = (re.search(r"\n\s+key:\s*(.*)", restore) or re.search("$", "")).group(0)
    for part in key_parts:
        check(part in key, f"{label}: the restore key contains {part}")
    check(f"path: {path}" in restore and f"path: {path}" in save, f"{label}: restore and save use {path}")
    check(f"steps.{rid}.outputs.cache-hit != 'true'" in build, f"{label}: the step is skipped on a cache hit")
    check(f"id: {rid}-run" in build, f"{label}: the step has an id the save can check")
    check(si > bi, f"{label}: the save comes after the step")
    cond = (re.search(r"\n\s+if:\s*(.*)", save) or re.search("$", "")).group(0)
    check("github.event_name != 'pull_request'" in cond, f"{label}: the save never runs on a pull request")
    check("github.ref == 'refs/heads/develop'" in cond and "github.ref == 'refs/heads/main'" in cond,
          f"{label}: the save runs only from develop or main")
    check(f"steps.{rid}-run.outcome == 'success'" in cond, f"{label}: the save needs a successful build")
    return ri, bi

check("LEGACY_REF" not in text and "patches" not in text, "the old patched build is gone from the workflow")
check("compose-baseline.py check" in text and "continue-on-error: true" in text and "steps.compare.outcome == 'failure'" in text,
      "the baseline comparison runs before the renderer and fails the job at the end")
lri, lbi = pin("Linux set", "linux-build", "Publish both Linux architectures for the renderer",
    "~/.m2/repository", ["runner.os", "-linux-", "hashFiles('renderer/scripts/build-compose.sh')"])
pi, _ = find(lambda s: name(s) == "Put the fork's publication back")
check(0 <= pi < lri, "the fork's publication is put back before the Linux set is restored over it")
_, putback = find(lambda s: name(s) == "Put the fork's publication back")
check("if:" not in putback, "the fork's publication is put back whether or not the build ran")
sys.exit(1 if failures else 0)
PY
rc=$?
[[ $rc -eq 0 ]] && echo "all passed"
exit $rc

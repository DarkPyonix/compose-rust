#!/usr/bin/env bash
# A pull request run only restores caches. Saving happens on push, schedule and dispatch,
# on the default branch, where every pull request can read what it saved. A pull request that
# saved its own copy filled the repository's 10 GB limit and pushed the others out.
#
# Read from the workflow files, since no tool here runs a workflow: no step uses the combined
# actions/cache (it saves after the job whatever the event), every actions/cache/save step is
# off on pull requests, and setup-gradle is read-only there.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 - "$repo_root/.github/workflows" <<'PY'
import sys, glob, yaml
failures = 0
def bad(msg):
    global failures
    print("FAIL -", msg); failures += 1
saves = 0
for path in sorted(glob.glob(sys.argv[1] + "/*.yml")):
    doc = yaml.safe_load(open(path))
    for job, body in (doc.get("jobs") or {}).items():
        for step in body.get("steps") or []:
            uses = str(step.get("uses", ""))
            where = f"{path.split('/')[-1]}:{job}:{step.get('name', uses)}"
            if uses.startswith("actions/cache@"):
                bad(f"{where} uses the combined actions/cache, which saves on pull requests")
            if uses.startswith("actions/cache/save@"):
                saves += 1
                cond = str(step.get("if", ""))
                if "github.event_name != 'pull_request'" not in cond:
                    bad(f"{where} can save on a pull request: if: {cond!r}")
            if uses.startswith("gradle/actions/setup-gradle@"):
                ro = str(step.get("with", {}).get("cache-read-only", ""))
                if "pull_request" not in ro:
                    bad(f"{where} is not read-only on pull requests: {ro!r}")
if saves == 0:
    bad("no actions/cache/save step found; the check is looking at nothing")
if failures:
    sys.exit(1)
print(f"ok   - {saves} save steps are off on pull requests, no combined cache, gradle read-only there")
PY
rc=$?
[[ $rc -eq 0 ]] && echo "all passed"
exit $rc

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
import re
for path in sorted(glob.glob(sys.argv[1] + "/*.yml")):
    doc = yaml.safe_load(open(path))
    for job, body in (doc.get("jobs") or {}).items():
        steps = body.get("steps") or []
        restore_ids, saved_ids = {}, set()
        for pos, step in enumerate(steps):
            uses = str(step.get("uses", ""))
            where = f"{path.split('/')[-1]}:{job}:{step.get('name', uses)}"
            if uses.startswith("actions/cache@"):
                bad(f"{where} uses the combined actions/cache, which saves on pull requests")
            if uses.startswith("actions/cache/restore@"):
                if not step.get("id"):
                    bad(f"{where} has no id, so nothing can save its key")
                else:
                    restore_ids[step["id"]] = (where, pos)
            if uses.startswith("actions/cache/save@"):
                saves += 1
                cond = " ".join(str(step.get("if", "")).split())
                if "github.event_name != 'pull_request'" not in cond:
                    bad(f"{where} can save on a pull request: if: {cond!r}")
                m = re.search(r"steps\.([\w-]+)\.outputs\.cache-hit != 'true'", cond)
                if not m:
                    bad(f"{where} does not require cache-hit != 'true': if: {cond!r}")
                if "||" in cond.replace("(", "").replace(")", "") and "||" in re.sub(r"\([^()]*\)", "", cond):
                    bad(f"{where} has a top-level || in its condition, which can bypass the guards: {cond!r}")
                key = str(step.get("with", {}).get("key", ""))
                k = re.search(r"steps\.([\w-]+)\.outputs\.cache-primary-key", key)
                if not k:
                    bad(f"{where} does not save under the restore step's cache-primary-key: {key!r}")
                elif m and k.group(1) != m.group(1):
                    bad(f"{where} checks {m.group(1)} but saves {k.group(1)}'s key")
                if k:
                    saved_ids.add(k.group(1))
                    if k.group(1) in restore_ids and restore_ids[k.group(1)][1] > pos:
                        bad(f"{where} comes before its restore step")
            if uses.startswith("gradle/actions/setup-gradle@"):
                ro = str(step.get("with", {}).get("cache-read-only", ""))
                if "pull_request" not in ro:
                    bad(f"{where} is not read-only on pull requests: {ro!r}")
        for rid, (where, _) in restore_ids.items():
            if rid not in saved_ids:
                bad(f"{where} is restored but never saved by a step in its job")
if saves == 0:
    bad("no actions/cache/save step found; the check is looking at nothing")
if failures:
    sys.exit(1)
print(f"ok   - {saves} save steps are off on pull requests, no combined cache, gradle read-only there")
PY
rc=$?
[[ $rc -eq 0 ]] && echo "all passed"
exit $rc

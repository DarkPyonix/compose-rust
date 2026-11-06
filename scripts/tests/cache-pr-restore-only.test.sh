#!/usr/bin/env bash
# A pull request run only restores caches. Saving happens on push, schedule and dispatch,
# on the default branch, where every pull request can read what it saved. A pull request that
# saved its own copy filled the repository's 10 GB limit and pushed the others out.
#
# Read from the workflow files, since no tool here runs a workflow: no step uses the combined
# actions/cache (it saves after the job whatever the event), every restore has a save in its
# job that needs both "not a pull request" and "no cache hit" and saves under that restore's
# primary key, and setup-gradle is read-only on pull requests.
#
# The workflows are read line by line with the standard library only: PyYAML is not on every
# runner (the macOS one lacks it), and a test that skips without it checks nothing there.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
command -v python3 >/dev/null || { echo "FAIL - python3 is needed to read the workflows"; exit 1; }
python3 -I - "$repo_root/.github/workflows" <<'PY'
import glob, re, sys

failures = 0
def bad(msg):
    global failures
    print("FAIL -", msg); failures += 1

def steps_of(lines):
    """Yields (job, step lines) for every step of every job: steps sit at six spaces."""
    job, current = None, None
    for line in lines + ["x:"]:
        m = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if m:
            if current: yield job, current
            job, current = m.group(1), None
            continue
        if re.match(r"^      -( |$)", line):
            if current: yield job, current
            current = [line]
        elif current is not None:
            if line.strip() and not line.startswith("        ") and not line.lstrip().startswith("#"):
                yield job, current
                current = None
            else:
                current.append(line)
    if current: yield job, current

def field(step, name):
    for line in step:
        m = re.match(rf"^(?:      - |        ){name}:\s*(.*)$", line)
        if m: return m.group(1).strip()
    return ""

def with_field(step, name):
    for line in step:
        m = re.match(rf"^          {name}:\s*(.*)$", line)
        if m: return m.group(1).strip()
    return ""

saves = 0
for path in sorted(glob.glob(sys.argv[1] + "/*.yml")):
    lines = open(path).read().split("\n")
    per_job = {}
    for job, step in steps_of(lines):
        per_job.setdefault(job, []).append(step)
    for job, steps in per_job.items():
        restore_ids, saved_ids = {}, set()
        for pos, step in enumerate(steps):
            uses = field(step, "uses")
            name = field(step, "name") or uses
            where = f"{path.split('/')[-1]}:{job}:{name}"
            if uses.startswith("actions/cache@"):
                bad(f"{where} uses the combined actions/cache, which saves on pull requests")
            if uses.startswith("actions/cache/restore@"):
                sid = field(step, "id")
                if not sid: bad(f"{where} has no id, so nothing can save its key")
                else: restore_ids[sid] = (where, pos)
            if uses.startswith("actions/cache/save@"):
                saves += 1
                cond = " ".join(field(step, "if").split())
                if "github.event_name != 'pull_request'" not in cond:
                    bad(f"{where} can save on a pull request: if: {cond!r}")
                hit = re.search(r"steps\.([\w-]+)\.outputs\.cache-hit != 'true'", cond)
                if not hit: bad(f"{where} does not require cache-hit != 'true': if: {cond!r}")
                if "||" in re.sub(r"\([^()]*\)", "", cond):
                    bad(f"{where} has a top-level || in its condition, which can bypass the guards: {cond!r}")
                key = with_field(step, "key")
                k = re.search(r"steps\.([\w-]+)\.outputs\.cache-primary-key", key)
                if not k: bad(f"{where} does not save under a restore step's cache-primary-key: {key!r}")
                elif hit and k.group(1) != hit.group(1): bad(f"{where} checks {hit.group(1)} but saves {k.group(1)}'s key")
                if k:
                    saved_ids.add(k.group(1))
                    if k.group(1) in restore_ids and restore_ids[k.group(1)][1] > pos:
                        bad(f"{where} comes before its restore step")
            if uses.startswith("gradle/actions/setup-gradle@"):
                ro = with_field(step, "cache-read-only")
                if "pull_request" not in ro: bad(f"{where} is not read-only on pull requests: {ro!r}")
        for rid, (where, _) in restore_ids.items():
            if rid not in saved_ids: bad(f"{where} is restored but never saved by a step in its job")
if saves < 10: bad(f"only {saves} save steps found; the reader is looking at nothing")
if failures: sys.exit(1)
print(f"ok   - {saves} save steps are guarded, no combined cache, gradle read-only on pull requests")
PY
rc=$?
[[ $rc -eq 0 ]] && echo "all passed"
exit $rc

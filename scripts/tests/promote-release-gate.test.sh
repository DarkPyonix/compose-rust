#!/usr/bin/env bash
# The release job of test-native-renderer.yml: when it may attach renderers to a release.
#
# Without promotion, every job before it has to have succeeded, as it always had to. With
# renderers promoted from renderer-dev, the build jobs are skipped, and what has to have
# succeeded is the promotion and the single-executable check of the promoted tarballs. This
# reads the job's condition out of the workflow and evaluates it for each case, so a change
# to either branch of it fails here. It builds nothing and calls nothing.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/test-native-renderer.yml"

nfr16_release_gate_needs_every_job_unless_promoted() {
    python3 -I - "$workflow" <<'PY'
import re, sys

# The workflow is read as text: the runners' Python has no YAML module, and this needs only
# three things from each job, which the file writes in a regular way. `needs` is one name or
# an inline list, `if` is one line or a folded block, and a step's id is on a line of its own.
def read_jobs(path):
    jobs, name, folding = {}, None, False
    in_jobs = False
    for line in open(path).read().splitlines():
        if not in_jobs:
            in_jobs = line.rstrip() == "jobs:"
            continue
        if line and not line.startswith(" "):
            break
        job = re.match(r"  ([\w-]+):\s*$", line)
        if job:
            name, folding = job.group(1), False
            jobs[name] = {"needs": [], "if": "", "step_ids": []}
            continue
        if name is None:
            continue
        if folding:
            if line.startswith("      ") or not line.strip():
                jobs[name]["if"] += " " + line.strip()
                continue
            folding = False
        needs = re.match(r"    needs:\s*(.+)$", line)
        if needs:
            jobs[name]["needs"] = [n.strip() for n in needs.group(1).strip("[] ").split(",")]
            continue
        condition = re.match(r"    if:\s*(.*)$", line)
        if condition:
            text = condition.group(1).strip()
            if text in (">-", ">", "|", "|-"):
                folding = True
            else:
                jobs[name]["if"] = text
            continue
        step_id = re.match(r"\s+(?:- )?id:\s*([\w-]+)\s*$", line)
        if step_id:
            jobs[name]["step_ids"].append(step_id.group(1))
    return jobs

jobs = read_jobs(sys.argv[1])
release = jobs["release"]
needs = release["needs"]
condition = " ".join(release["if"].split())
if not needs or not condition:
    print("FAIL - the release job's needs and condition could not be read from the workflow")
    sys.exit(1)

# GitHub's expression syntax, as far as this condition uses it, turned into Python.
expr = condition
expr = expr.replace("needs.*.result", "ALL")
expr = re.sub(r"needs\.([\w-]+)\.outputs\.([\w-]+)", r'O("\1", "\2")', expr)
expr = re.sub(r"needs\.([\w-]+)\.result", r'R("\1")', expr)
expr = expr.replace("startsWith(github.ref,", "STARTS(REF,")
expr = expr.replace("!cancelled()", "(not CANCELLED)")
expr = expr.replace("&&", " and ").replace("||", " or ")
expr = re.sub(r"!(?!=)", " not ", expr)
expr = expr.replace("contains(", "CONTAINS(")

def evaluate(results, promoted, ref="refs/tags/v0.0.13"):
    scope = {
        "ALL": list(results.values()),
        "R": lambda job: results[job],
        "O": lambda job, key: promoted if (job, key) == ("changed", "promoted") else "",
        "STARTS": lambda value, prefix: value.startswith(prefix),
        "CONTAINS": lambda values, item: item in values,
        "REF": ref,
        "CANCELLED": False,
    }
    return bool(eval(expr, {"__builtins__": {}}, scope))

builds = [job for job in needs if job not in ("changed", "single-executable")]
everything = {job: "success" for job in needs}
failures = []

def check(what, got, want):
    print(("ok   - " if got == want else "FAIL - ") + what)
    if got != want:
        failures.append(what)

check("nothing promoted, every job succeeded: released", evaluate(everything, ""), True)
for job in needs:
    for result in ("failure", "skipped", "cancelled"):
        check(f"nothing promoted, {job} {result}: not released",
              evaluate({**everything, job: result}, ""), False)

promoted = {**everything, **{job: "skipped" for job in builds}}
check("promoted, builds skipped, single-executable succeeded: released",
      evaluate(promoted, "true"), True)
check("promoted, single-executable failed: not released",
      evaluate({**promoted, "single-executable": "failure"}, "true"), False)
check("promoted, single-executable skipped: not released",
      evaluate({**promoted, "single-executable": "skipped"}, "true"), False)
check("not a tag: not released", evaluate(everything, "", ref="refs/heads/develop"), False)

# Every build the release would otherwise need is skipped when the tag was promoted.
for job in builds:
    check(f"{job} is skipped on promotion",
          "needs.changed.outputs.promoted != 'true'" in " ".join(jobs[job]["if"].split()),
          True)
check("the changed job promotes on a tag", "promote" in jobs["changed"]["step_ids"], True)

sys.exit(1 if failures else 0)
PY
}

if nfr16_release_gate_needs_every_job_unless_promoted; then
    echo "all passed"
else
    exit 1
fi

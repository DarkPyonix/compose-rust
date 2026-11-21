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
import re, sys, yaml

doc = yaml.safe_load(open(sys.argv[1]))
jobs = doc["jobs"]
release = jobs["release"]
needs = release["needs"]
condition = " ".join(release["if"].split())

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
for job in builds + ["fork-desktop"]:
    check(f"{job} is skipped on promotion",
          "needs.changed.outputs.promoted != 'true'" in " ".join(str(jobs[job].get("if", "")).split()),
          True)
check("the changed job promotes on a tag", "promote" in [s.get("id") for s in jobs["changed"]["steps"]], True)

sys.exit(1 if failures else 0)
PY
}

if nfr16_release_gate_needs_every_job_unless_promoted; then
    echo "all passed"
else
    exit 1
fi

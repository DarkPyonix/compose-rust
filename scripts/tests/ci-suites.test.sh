#!/usr/bin/env bash
# Check that a ci.yml dispatch can run a chosen part of the suite, and that the default is the
# whole of it.
#
# - ci.yml takes a `suites` input whose default is `all`, so a dispatch that says nothing (a
#   release check) still runs every job.
# - Its plan job runs scripts/ci-suites.sh, and every other job runs only when the plan says so,
#   and so every job is named in the script and every name in the script is a job.
# - The script's answers: `all` is every job but the measurements, `measure` those, `fast` the
#   three short ones, a list exactly the suites and jobs it names plus the Windows kit for a job
#   that downloads it, and an unknown name is an error, not a smaller run.
set -eu
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
suites="$root/scripts/ci-suites.sh"
failures=0
ok() { echo "ok    $1"; }
fail() { echo "FAIL  $1" >&2; failures=$((failures + 1)); }

python3 -I - "$root" <<'PY' || failures=$((failures + 1))
import pathlib, re, subprocess, sys
root = pathlib.Path(sys.argv[1])
ci = (root / '.github' / 'workflows' / 'ci.yml').read_text()
script = (root / 'scripts' / 'ci-suites.sh').read_text()
problems = []

dispatch = re.search(r'^  workflow_dispatch:\n(.*?)(?=^[a-z]|\Z)', ci, re.M | re.S)
if not dispatch or not re.search(r'^      suites:\n(?:        .*\n)*?        default: all$', dispatch.group(1), re.M):
    problems.append('ci.yml has no `suites` dispatch input whose default is all')

body = ci[ci.index('\njobs:\n'):]
jobs = re.findall(r'^  ([a-z_]+):$', body, re.M)
listed = re.search(r'^jobs=\(\n(.*?)^\)', script, re.M | re.S)
listed = listed.group(1).split() if listed else []
work = [job for job in jobs if job != 'plan']
if 'plan' not in jobs:
    problems.append('ci.yml has no plan job')
if work != listed:
    problems.append(f'the jobs scripts/ci-suites.sh lists ({listed}) are not ci.yml\'s ({work})')
if 'scripts/ci-suites.sh "$SUITES"' not in body:
    problems.append('the plan job does not run scripts/ci-suites.sh on the input')
for job in work:
    block = re.search(r'^  ' + job + r':\n((?:    .*\n|\n)*)', body, re.M).group(1)
    if f"if: needs.plan.outputs.{job} == 'true'" not in block:
        problems.append(f'{job} does not run on its plan output')
    needs = re.search(r'^    needs: (.*)$', block, re.M)
    if not needs or 'plan' not in needs.group(1):
        problems.append(f'{job} does not need the plan')
    if f'      {job}: ${{{{ steps.choose.outputs.{job} }}}}' not in body:
        problems.append(f'the plan does not output {job}')

for problem in problems:
    print('FAIL  ' + problem, file=sys.stderr)
if problems:
    sys.exit(1)
print('ok    ci.yml runs each job on the plan, the default is all, and the script names every job')
PY

on() { "$suites" "$1" | sed -n 's/=true$//p' | sort | tr '\n' ' '; }
every_job="$("$suites" all | sed 's/=.*//' | sort | tr '\n' ' ')"
measuring="test_windows_resize_metrics "
gating="$(printf '%s\n' $every_job | grep -vxE 'test_windows_resize_metrics' | tr '\n' ' ')"
[[ "$(on all)" == "$gating" ]] && ok "all runs every job but the measurements" || fail "all ran $(on all), not $gating"
[[ "$(on '')" == "$gating" ]] && ok "an empty input is all" || fail "an empty input ran $(on '')"
[[ "$(on measure)" == "$measuring" ]] && ok "measure runs the jobs that only report numbers" || fail "measure ran $(on measure)"
[[ "$(on all,measure)" == "$every_job" ]] && ok "all,measure is every job" || fail "all,measure ran $(on all,measure)"
[[ "$(on fast)" == "test test_graalvm_renderer test_windows_c " ]] &&
    ok "fast is the tests, the GraalVM renderer smoke and the Windows C compile" || fail "fast ran $(on fast)"
[[ "$(on 'test_windows_c, test')" == "test test_windows_c " ]] &&
    ok "a list runs exactly the jobs it names" || fail "the list ran $(on 'test_windows_c, test')"
[[ "$(on test_samples)" == "test_samples windows_static_kit " ]] &&
    ok "a job that takes the Windows kit brings the kit" || fail "test_samples ran $(on test_samples)"
if "$suites" not_a_job >/dev/null 2>&1; then
    fail "an unknown name was accepted"
else
    ok "an unknown name is an error"
fi

if [[ "$failures" -gt 0 ]]; then
    echo "$failures problem(s) in the suite selection" >&2
    exit 1
fi
echo "all passed"

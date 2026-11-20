#!/usr/bin/env bash
# Check that a ci.yml dispatch can run a chosen part of the suite, and that the default is the
# whole of it.
#
# - ci.yml takes a `suites` input whose default is `all`, so a dispatch that says nothing (a
#   release check) still runs every job.
# - Its plan job runs scripts/ci-suites.sh, and every other job runs only when the plan says so,
#   and so every job is named in the script and every name in the script is a job.
# - The script's answers: `all` is every job with the resize timings off (the window-pin probe
#   gates, so it is in) except the on-request ones, which gate nothing and run only when named
#   (the Windows drag kit), `measure` test_windows_resize and the Windows kit it resizes with
#   the timings on, `all,measure` both, `fast` the three short ones, a list exactly the suites
#   and jobs it names plus the Windows kit for a job that downloads it, and an unknown name is
#   an error, not a smaller run.
# - The jobs the script brings the kit for are exactly the ci.yml jobs that need
#   windows_static_kit, so a job that takes the kit never runs without it being built.
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
if '      measure: ${{ steps.choose.outputs.measure }}' not in body:
    problems.append('the plan does not output measure')
if "      metrics: ${{ needs.plan.outputs.measure == 'true' }}" not in body:
    problems.append('test_windows_resize does not take its metrics input from the plan')

kit_list = re.search(r'^needs_windows_kit=\(([^)]*)\)$', script, re.M)
kit_list = sorted(kit_list.group(1).split()) if kit_list else []
kit_needers = []
for job in work:
    block = re.search(r'^  ' + job + r':\n((?:    .*\n|\n)*)', body, re.M).group(1)
    needs = re.search(r'^    needs: (.*)$', block, re.M)
    if needs and re.search(r'\bwindows_static_kit\b', needs.group(1)):
        kit_needers.append(job)
if sorted(kit_needers) != kit_list:
    problems.append(f'the jobs that need windows_static_kit in ci.yml ({sorted(kit_needers)}) are not '
                    f'the ones scripts/ci-suites.sh brings the kit for ({kit_list})')

for problem in problems:
    print('FAIL  ' + problem, file=sys.stderr)
if problems:
    sys.exit(1)
print('ok    ci.yml runs each job on the plan, the default is all, and the script names every job')
PY

on() { "$suites" "$1" | grep -v '^measure=' | sed -n 's/=true$//p' | sort | tr '\n' ' '; }
measures() { "$suites" "$1" | sed -n 's/^measure=//p'; }
# The jobs that gate nothing and run only when named.
on_request="windows_drag_kit"
every_job="$("$suites" all | grep -v '^measure=' | sed 's/=.*//' | sort | tr '\n' ' ')"
gating="$(tr ' ' '\n' <<< "$every_job" | grep -vxF -e "$on_request" | grep . | tr '\n' ' ')"
[[ "$(on all)" == "$gating" ]] && ok "all runs every job but the on-request ones" || fail "all ran $(on all), not $gating"
[[ "$(on all)" != *" windows_drag_kit "* && "$(on all)" != "windows_drag_kit "* ]] &&
    ok "all leaves the Windows drag kit out" || fail "all ran windows_drag_kit"
[[ "$(on windows_drag_kit)" == "windows_drag_kit windows_static_kit " && "$(measures windows_drag_kit)" == false ]] &&
    ok "windows_drag_kit alone brings the Windows kit it links" || fail "windows_drag_kit ran $(on windows_drag_kit)"
[[ "$(on all,windows_drag_kit)" == "$every_job" ]] &&
    ok "all,windows_drag_kit is every job" || fail "all,windows_drag_kit ran $(on all,windows_drag_kit)"
[[ "$(on all)" == *" test_windows_resize "* ]] && ok "all runs the window-pin probe" || fail "all did not run test_windows_resize"
[[ "$(measures all)" == false ]] && ok "all leaves the resize timings off" || fail "all measured: $(measures all)"
[[ "$(on '')" == "$gating" && "$(measures '')" == false ]] && ok "an empty input is all" || fail "an empty input ran $(on '')"
[[ "$(on measure)" == "test_windows_resize windows_static_kit " && "$(measures measure)" == true ]] &&
    ok "measure runs test_windows_resize and the kit it resizes with the timings on" || fail "measure ran $(on measure), measure=$(measures measure)"
[[ "$(on all,measure)" == "$gating" && "$(measures all,measure)" == true ]] &&
    ok "all,measure is every gating job with the timings on" || fail "all,measure ran $(on all,measure), measure=$(measures all,measure)"
[[ "$(measures fast)" == false ]] && ok "fast leaves the resize timings off" || fail "fast measured"
[[ "$(on fast)" == "test test_graalvm_renderer test_windows_c " ]] &&
    ok "fast is the tests, the GraalVM renderer smoke and the Windows C compile" || fail "fast ran $(on fast)"
[[ "$(on 'test_windows_c, test')" == "test test_windows_c " ]] &&
    ok "a list runs exactly the jobs it names" || fail "the list ran $(on 'test_windows_c, test')"
[[ "$(on test_samples)" == "test_samples windows_static_kit " ]] &&
    ok "a job that takes the Windows kit brings the kit" || fail "test_samples ran $(on test_samples)"
[[ "$(on test_windows_resize)" == "test_windows_resize windows_static_kit " && "$(measures test_windows_resize)" == false ]] &&
    ok "test_windows_resize alone brings the kit it resizes, with the timings off" ||
    fail "test_windows_resize ran $(on test_windows_resize), measure=$(measures test_windows_resize)"
for name in not_a_job test_windows_resize_metrics test_windows_resize_probe; do
    if "$suites" "$name" >/dev/null 2>&1; then
        fail "the unknown name $name was accepted"
    else
        ok "$name is an unknown name, an error"
    fi
done

if [[ "$failures" -gt 0 ]]; then
    echo "$failures problem(s) in the suite selection" >&2
    exit 1
fi
echo "all passed"

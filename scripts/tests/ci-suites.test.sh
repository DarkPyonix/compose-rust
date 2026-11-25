#!/usr/bin/env bash
# Check that a ci.yml dispatch can run a chosen part of the suite, and that the default is the
# whole of it.
#
# - ci.yml takes a `suites` input whose default is `all`, so a dispatch that says nothing (a
#   release check) still runs every job.
# - Its plan job runs scripts/ci-suites.sh, and every other job runs only when the plan says so,
#   and so every job is named in the script and every name in the script is a job.
# - The script's answers: `all` is every job with the resize timings off (the window-pin probe
#   gates, so it is in), `measure` test_windows_resize and the Windows kit it resizes with the
#   timings on, `all,measure` both, `fast` the three short ones, a list exactly the suites and
#   jobs it names plus the Windows kit for a job that downloads it, and an unknown name is an
#   error, not a smaller run.
# - The jobs the script brings the kit for are exactly the ci.yml jobs that need
#   windows_static_kit, so a job that takes the kit never runs without it being built.
# - `owner_checks` builds the owner-checks sample alone, on the desktop only: test_samples and
#   the Windows kit, with `samples=owner-checks` and `samples_desktop_only=true`, which ci.yml
#   hands to test-samples.yml. Asked for with all or test_samples it narrows nothing: every
#   sample on every platform is still built.
# - `memory_report` runs test_memory_report (the blank window's memory, report only) and the
#   Windows kit it links, and nothing else. It is in no default: all, an empty input, fast
#   and measure leave it out, so a dispatch that does not name it never spends a run on it.
#   all,memory_report is every job but publish_schema_renderer.
# - `publish_schema_renderer` uploads the renderers for the commit's schema to the renderer-dev
#   release, so it runs only when named: all, an empty input, fast, measure, owner_checks and
#   memory_report leave it out. Named alone it runs that job and nothing else (it builds its
#   own Windows kit, so it brings no windows_static_kit), and a list with it adds it.
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
for output in ('samples', 'samples_desktop_only'):
    if f'      {output}: ${{{{ steps.choose.outputs.{output} }}}}' not in body:
        problems.append(f'the plan does not output {output}')
samples_job = re.search(r'^  test_samples:\n((?:    .*\n|\n)*)', body, re.M)
samples_job = samples_job.group(1) if samples_job else ''
if '      samples: ${{ needs.plan.outputs.samples }}' not in samples_job:
    problems.append('test_samples does not take its samples input from the plan')
if "      desktop_only: ${{ needs.plan.outputs.samples_desktop_only == 'true' }}" not in samples_job:
    problems.append('test_samples does not take desktop_only from the plan')
called = (root / '.github' / 'workflows' / 'test-samples.yml').read_text()
call = re.search(r'^  workflow_call:\n((?:    .*\n)*)', called, re.M)
if not call or not re.search(r'^      samples:$', call.group(1), re.M):
    problems.append('test-samples.yml takes no samples input under workflow_call')

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

# The lines that are not jobs: whether the timings are on, and which samples to build.
not_jobs='^(measure|samples|samples_desktop_only)='
on() { "$suites" "$1" | grep -vE "$not_jobs" | sed -n 's/=true$//p' | sort | tr '\n' ' '; }
measures() { "$suites" "$1" | sed -n 's/^measure=//p'; }
samples_of() { "$suites" "$1" | sed -n 's/^samples=//p'; }
desktop_only() { "$suites" "$1" | sed -n 's/^samples_desktop_only=//p'; }
# The jobs that run only when named and are never part of a default suite.
report_only=" test_memory_report publish_schema_renderer "
every_name="$("$suites" all | grep -vE "$not_jobs" | sed 's/=.*//' | sort | tr '\n' ' ')"
every_job=""
for job in $every_name; do
    [[ "$report_only" == *" $job "* ]] || every_job="$every_job$job "
done
[[ "$every_name" == *" test_memory_report "* || "$every_name" == "test_memory_report "* ]] &&
    ok "the script names test_memory_report" || fail "the script has no test_memory_report line: $every_name"
[[ "$(on all)" == "$every_job" ]] && ok "all runs every job" || fail "all ran $(on all), not $every_job"
[[ "$(on all)" == *" test_windows_resize "* ]] && ok "all runs the window-pin probe" || fail "all did not run test_windows_resize"
[[ "$(measures all)" == false ]] && ok "all leaves the resize timings off" || fail "all measured: $(measures all)"
[[ "$(on '')" == "$every_job" && "$(measures '')" == false ]] && ok "an empty input is all" || fail "an empty input ran $(on '')"
[[ "$(on measure)" == "test_windows_resize windows_static_kit " && "$(measures measure)" == true ]] &&
    ok "measure runs test_windows_resize and the kit it resizes with the timings on" || fail "measure ran $(on measure), measure=$(measures measure)"
[[ "$(on all,measure)" == "$every_job" && "$(measures all,measure)" == true ]] &&
    ok "all,measure is every job with the timings on" || fail "all,measure ran $(on all,measure), measure=$(measures all,measure)"
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
for default in all '' fast measure all,measure owner_checks; do
    [[ "$(on "$default")" != *"test_memory_report"* ]] &&
        ok "'$default' leaves the memory report out" || fail "'$default' ran the memory report: $(on "$default")"
done
[[ "$(on memory_report)" == "test_memory_report windows_static_kit " && "$(measures memory_report)" == false ]] &&
    ok "memory_report runs test_memory_report and the Windows kit it links, with the timings off" ||
    fail "memory_report ran $(on memory_report), measure=$(measures memory_report)"
[[ "$(on test_memory_report)" == "test_memory_report windows_static_kit " ]] &&
    ok "test_memory_report by name runs the same" || fail "test_memory_report ran $(on test_memory_report)"
[[ "$(on all,memory_report,publish_schema_renderer)" == "$every_name" ]] &&
    ok "all,memory_report,publish_schema_renderer is every job" ||
    fail "all,memory_report,publish_schema_renderer ran $(on all,memory_report,publish_schema_renderer), not $every_name"
every_but_publish=""
for job in $every_name; do
    [[ "$job" == publish_schema_renderer ]] || every_but_publish="$every_but_publish$job "
done
[[ "$(on all,memory_report)" == "$every_but_publish" ]] &&
    ok "all,memory_report is every job but publish_schema_renderer" ||
    fail "all,memory_report ran $(on all,memory_report), not $every_but_publish"
[[ "$every_name" == *"publish_schema_renderer "* ]] &&
    ok "the script names publish_schema_renderer" || fail "the script has no publish_schema_renderer line: $every_name"
for default in all '' fast measure all,measure owner_checks memory_report test_memory_report; do
    [[ "$(on "$default")" != *"publish_schema_renderer"* ]] &&
        ok "'$default' leaves the renderer-dev upload out" || fail "'$default' ran publish_schema_renderer: $(on "$default")"
done
[[ "$(on publish_schema_renderer)" == "publish_schema_renderer " && "$(measures publish_schema_renderer)" == false ]] &&
    ok "publish_schema_renderer alone runs only that job, without the Windows kit" ||
    fail "publish_schema_renderer ran $(on publish_schema_renderer), measure=$(measures publish_schema_renderer)"
[[ "$(on 'test, publish_schema_renderer')" == "publish_schema_renderer test " ]] &&
    ok "a list with publish_schema_renderer adds it" || fail "the list ran $(on 'test, publish_schema_renderer')"
grep -qE '^    uses: \./\.github/workflows/publish-schema-renderer\.yml$' "$root/.github/workflows/ci.yml" &&
    ok "ci.yml calls publish-schema-renderer.yml" || fail "ci.yml does not call publish-schema-renderer.yml"
grep -qE '^    uses: \./\.github/workflows/test-memory-report\.yml$' "$root/.github/workflows/ci.yml" &&
    ok "ci.yml calls test-memory-report.yml" || fail "ci.yml does not call test-memory-report.yml"
[[ "$(on owner_checks)" == "test_samples windows_static_kit " && "$(measures owner_checks)" == false ]] &&
    ok "owner_checks runs test_samples and the Windows kit it links" || fail "owner_checks ran $(on owner_checks)"
[[ "$(samples_of owner_checks)" == owner-checks && "$(desktop_only owner_checks)" == true ]] &&
    ok "owner_checks builds the owner-checks sample alone, on the desktop" ||
    fail "owner_checks gave samples=$(samples_of owner_checks) desktop_only=$(desktop_only owner_checks)"
for wide in all test_samples all,owner_checks owner_checks,test_samples ''; do
    [[ -z "$(samples_of "$wide")" && "$(desktop_only "$wide")" == false ]] &&
        ok "'$wide' builds every sample on every platform" ||
        fail "'$wide' gave samples=$(samples_of "$wide") desktop_only=$(desktop_only "$wide")"
done
for name in not_a_job test_windows_resize_metrics test_windows_resize_probe memory-report publish-schema-renderer; do
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

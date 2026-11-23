#!/usr/bin/env bash
# The memory report: what a blank window costs on the Kotlin/Native renderer, read on Linux
# and Windows by a dispatch of ci.yml that names memory_report.
#
# What this checks, without a renderer:
#   - the blank-window fixture opens one 800 by 600 dp window with one empty box and builds
#     against the renderer the same way hand-check does;
#   - the Linux script reads PSS, its parts and RSS from smaps_rollup and status as they are
#     written (fixtures copied from a real /proc), and the summary takes the median of the
#     launches for each reading, marks the metric, says which methods the readings used, and
#     fails on a report with no reading of the metric;
#   - on Linux, the script starts an application, reads it at both times, closes it by its
#     pid and leaves nothing running, and fails when the application is gone at its first
#     reading;
#   - neither script closes anything by name;
#   - the workflow is called only by ci.yml, measures both systems with these scripts, and
#     judges no number.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/.github/scripts/blank-window-memory.sh"
ps1="$root/.github/scripts/blank-window-memory.ps1"
summary="$root/.github/scripts/blank-window-memory-summary.py"
workflow="$root/.github/workflows/test-memory-report.yml"
fixture="$root/compose-rust/tests/fixtures/blank-window"
data="$root/scripts/tests/fixtures/blank-window-memory"
failures=0
ok() { echo "ok    $1"; }
fail() { echo "FAIL  $1" >&2; failures=$((failures + 1)); }

# Throwaway files stay inside the repository.
work="$root/.scratch/blank-window-memory-test"
rm -rf "$work"
mkdir -p "$work"
trap 'rm -rf "$work"' EXIT

# The fixture.
main="$fixture/src/main.rs"
if [[ -f "$main" && -f "$fixture/Cargo.toml" ]]; then
    grep -q 'const WIDTH: u16 = 800;' "$main" && grep -q 'const HEIGHT: u16 = 600;' "$main" &&
        ok "the blank window asks for 800 by 600 dp" || fail "the blank window does not ask for 800 by 600 dp"
    creates="$(grep -c 'Mutation::Create' "$main")"
    [[ "$creates" == 1 ]] && grep -q 'widget: WidgetKind::Box' "$main" &&
        ok "the blank window creates one node, an empty box" || fail "the blank window creates $creates nodes"
    grep -q '^\[workspace\]$' "$fixture/Cargo.toml" && grep -q 'compose-rust = { path = "../../.." }' "$fixture/Cargo.toml" &&
        ok "the fixture is its own workspace on the crate, like hand-check" || fail "the fixture's Cargo.toml is not shaped like hand-check's"
else
    fail "compose-rust/tests/fixtures/blank-window is missing"
fi

# Reading smaps_rollup and status.
read_out="$("$script" --read "$data/smaps_rollup" "$data/status")"
expected=$'pss_kb=61234\npss_anon_kb=38000\npss_file_kb=23000\npss_shmem_kb=234\nprivate_dirty_kb=40100\nrss_kb=98312'
[[ "$read_out" == "$expected" ]] && ok "one reading takes PSS, its parts and RSS, in kB" ||
    fail "the reading was: $(tr '\n' ' ' <<<"$read_out")"

# The summary.
linux_md="$(python3 -I "$summary" "$data/linux-samples.json" "Linux")"
rc=$?
[[ $rc == 0 ]] && ok "the summary of the Linux samples passes" || fail "the summary of the Linux samples exited $rc"
grep -qF '| +10 s | **PSS** | **65.0** | 60.0 | 70.0 | 3 of 3 |' <<<"$linux_md" &&
    ok "the median of three launches is the middle one, in MB" || fail "no +10 s PSS row of 65.0 MB: $linux_md"
grep -qF '| +60 s | **PSS** | **58.5** | 58.0 | 59.0 | 2 of 3 |' <<<"$linux_md" &&
    ok "a launch with no reading is counted out and said so" || fail "no +60 s PSS row of 58.5 MB from 2 of 3: $linux_md"
grep -qF '| +10 s | RSS (reference) | 105.0 |' <<<"$linux_md" &&
    ok "RSS is shown beside PSS, not marked as the metric" || fail "no RSS reference row: $linux_md"

windows_md="$(python3 -I "$summary" "$data/windows-samples.json" "Windows")"
rc=$?
[[ $rc == 0 ]] && ok "the summary of the Windows samples passes" || fail "the summary of the Windows samples exited $rc"
grep -qF '| +60 s | **Private working set** | **147.0** |' <<<"$windows_md" &&
    ok "the Windows table reads its columns from the objects PowerShell writes" || fail "no Windows private row: $windows_md"
grep -qF 'used: counter: Working Set - Private, fallback: PrivateMemorySize64 (private bytes)' <<<"$windows_md" &&
    grep -qF 'The readings mix methods' <<<"$windows_md" &&
    ok "the summary says which methods the readings used, and that they mix" || fail "the methods are not printed: $windows_md"

if python3 -I "$summary" "$data/empty-samples.json" >/dev/null 2>&1; then
    fail "a report with no reading passed"
else
    ok "a report with no reading of the metric fails"
fi

# Starting, reading and closing, where there is a /proc to read.
if [[ -r /proc/self/smaps_rollup ]]; then
    app="$work/idle-app"
    printf '#!/bin/sh\necho "idle app $$"\nexec sleep 60\n' >"$app"
    chmod +x "$app"
    out="$work/run"
    if BLANK_MEMORY_FIRST_S=1 BLANK_MEMORY_SECOND_S=2 "$script" "$app" "$out" 2 >"$work/run.log" 2>&1; then
        ok "two launches of an application that stays up pass"
    else
        fail "two launches failed: $(cat "$work/run.log")"
    fi
    readings="$(python3 -I -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["samples"]))' "$out/samples.json" 2>/dev/null)"
    [[ "$readings" == 4 ]] && ok "each launch is read twice" || fail "$readings readings, not 4"
    left=0
    for pid in $(sed -n 's/^launch [0-9]*: pid //p' "$work/run.log"); do
        kill -0 "$pid" 2>/dev/null && { left=$((left + 1)); kill -KILL "$pid" 2>/dev/null; }
    done
    [[ $left == 0 ]] && ok "every launch is closed by its pid" || fail "$left launches were still running"

    quitter="$work/quitter"
    printf '#!/bin/sh\necho "no window today"\nexit 3\n' >"$quitter"
    chmod +x "$quitter"
    if BLANK_MEMORY_FIRST_S=1 BLANK_MEMORY_SECOND_S=2 "$script" "$quitter" "$work/quit" 1 >"$work/quit.log" 2>&1; then
        fail "an application that never stayed up passed"
    else
        grep -q 'did not start' "$work/quit.log" && grep -q 'no window today' "$work/quit.log" &&
            ok "an application gone at its first reading fails, with what it printed" ||
            fail "the failure did not say why: $(cat "$work/quit.log")"
    fi
else
    echo "skip  no /proc/self/smaps_rollup here; the start, read and close run is checked on Linux"
fi

# Closing by pid, never by name.
if grep -nE '\b(pkill|killall)\b|kill -[A-Z]+ -?[^"$ ]*\$\(pgrep' "$script"; then
    fail "the Linux script closes something by name"
else
    ok "the Linux script closes only the pid it started"
fi
if grep -niE 'Stop-Process +-Name|taskkill|Get-Process +-Name' "$ps1"; then
    fail "the Windows script closes or picks a process by name"
else
    ok "the Windows script closes only the process it started"
fi
grep -qF '\Process($instance)\Working Set - Private' "$ps1" && grep -qF 'PrivateMemorySize64' "$ps1" &&
    grep -qF '\Process($instance)\ID Process' "$ps1" &&
    ok "Windows reads the private working set counter, checks its pid, and falls back to PrivateMemorySize64" ||
    fail "the Windows script does not read the counter with a fallback"

# The workflow.
if [[ -f "$workflow" ]]; then
    on="$(sed -n '/^on:/,/^[a-z]/p' "$workflow")"
    grep -qE '^  workflow_call:' <<<"$on" && ! grep -qE '^  (push|pull_request|workflow_dispatch|schedule):' <<<"$on" &&
        ok "the memory report is only called, never started on its own" || fail "the memory report has a trigger of its own: $on"
    grep -qE '^name: Memory report$' "$workflow" && ok "the workflow is named Memory report" || fail "the workflow is not named Memory report"
    grep -qF 'blank-window-memory.sh' "$workflow" && grep -qF 'blank-window-memory.ps1' "$workflow" &&
        grep -qF 'blank-window-memory-summary.py' "$workflow" && grep -qF 'GITHUB_STEP_SUMMARY' "$workflow" &&
        ok "both systems are measured by these scripts and summarised in the job summary" || fail "the workflow does not run the scripts"
    grep -qF "xvfb-run -a -s '-screen 0 1280x1024x24'" "$workflow" && grep -qF 'runs-on: ubuntu-latest' "$workflow" &&
        grep -qF 'runs-on: windows-latest' "$workflow" &&
        ok "Linux runs on ubuntu-latest under Xvfb, Windows on windows-latest" || fail "the runners are not ubuntu-latest and windows-latest"
    grep -qF 'name: windows-static-renderer-kit' "$workflow" &&
        ok "Windows links the kit this run built" || fail "Windows does not take the windows-static-renderer-kit artifact"
    grep -qF 'memory-report-linux-x64' "$workflow" && grep -qF 'memory-report-windows-x64' "$workflow" &&
        ok "every sample is kept as an artifact per system" || fail "the samples are not uploaded"
    grep -qE 'continue-on-error|resize-probe-gate|--gate' "$workflow" &&
        fail "the memory report has a gate or hides a failure" || ok "the memory report has no gate and hides no failure"
else
    fail ".github/workflows/test-memory-report.yml is missing"
fi

if [[ "$failures" -gt 0 ]]; then
    echo "$failures problem(s) in the memory report" >&2
    exit 1
fi
echo "all passed"

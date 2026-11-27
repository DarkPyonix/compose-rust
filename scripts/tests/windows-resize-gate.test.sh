#!/usr/bin/env bash
# The step that judges the resize probe runs .github/scripts/resize-probe-gate.sh, and that
# script had never run to its end before it was relied on (it was then the step's own body).
# The first run that got there failed a clean windows-2025 leg without a word: the script asked
# every label of the CPU path for at least one CPU step, and the window states draw none,
# because a maximise or a snap does not go through the move-size loop.
#
# So this runs that script here, as the probe jobs call it, against probe logs made up for the
# purpose, for hand-check (kn) and for the GraalVM smoke host (graalvm):
#   - a clean run passes on both legs (windows-2025 and windows-2022);
#   - a single faulty drag of the 24 fails the job on both windows-2025 and windows-2022,
#     and a control that passes, a drag with no CPU step and a step that was not validated
#     fail it on both legs as well;
#   - the GraalVM gate fails when the smoke host did not draw the marker scene, when too many
#     window state changes were faulty, and when it is handed hand-check's logs instead.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/test-windows-resize-metrics.yml"
gate="$repo_root/.github/scripts/resize-probe-gate.sh"
[[ -f "$workflow" ]] || { echo "missing $workflow"; exit 1; }
[[ -f "$gate" ]] || { echo "missing $gate"; exit 1; }
red=0
ok() { echo "ok    $1"; }
fail() { echo "FAIL  $1: $2"; red=1; }

work="$(mktemp -d "${TMPDIR:-/tmp}/resize-gate.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# The step runs the script for hand-check, with the display scale's status and the runner.
step="The probe can tell late from prompt, and the CPU path shows no fault"
step_block="$(awk -v name="      - name: $step" '$0 == name { found = 1 } found && /^      - name: / && $0 != name { exit } found { print }' "$workflow")"
if [[ -z "$step_block" ]]; then
    fail "the gate step" "the step \"$step\" was not found in the workflow"
elif ! grep -qE '^        run: bash \.github/scripts/resize-probe-gate\.sh kn$' <<<"$step_block"; then
    fail "the gate step" "it does not run resize-probe-gate.sh for kn"
elif ! grep -qF 'SCALE_STATUS: ${{ steps.scale.outputs.status }}' <<<"$step_block" ||
    ! grep -qF 'RUNNER_OS_LABEL: ${{ matrix.os }}' <<<"$step_block"; then
    fail "the gate step" "it does not hand the script the display scale's status and the runner"
fi
# The GraalVM probe job runs the same script for the GraalVM renderer, the same way.
graalvm_step="The probe can tell late from prompt, and the GraalVM CPU path shows no fault"
graalvm_block="$(awk -v name="      - name: $graalvm_step" '$0 == name { found = 1 } found && /^      - name: / && $0 != name { exit } found { print }' "$workflow")"
if [[ -z "$graalvm_block" ]]; then
    fail "fr19_8_4_the_graalvm_gate_step" "the step \"$graalvm_step\" was not found in the workflow"
elif ! grep -qE '^        run: bash \.github/scripts/resize-probe-gate\.sh graalvm --report-only$' <<<"$graalvm_block"; then
    fail "fr19_8_4_the_graalvm_gate_step" "it does not run resize-probe-gate.sh for graalvm with --report-only"
elif ! grep -qF 'SCALE_STATUS: ${{ steps.scale.outputs.status }}' <<<"$graalvm_block" ||
    ! grep -qF 'RUNNER_OS_LABEL: ${{ matrix.os }}' <<<"$graalvm_block"; then
    fail "fr19_8_4_the_graalvm_gate_step" "it does not hand the script the display scale's status and the runner"
elif grep -qE '^        (if|continue-on-error):' <<<"$graalvm_block"; then
    fail "fr19_8_4_the_graalvm_gate_step" "it has a condition, or can fail without failing the job"
else
    ok "fr19_8_4_the_graalvm_gate_step"
fi
# The shipped Kotlin/Native window stays a gate: nothing in its probe job, and no job but the
# GraalVM one, may pass --report-only, and the GraalVM job says in its name that it reports.
kn_job="$(awk '/^  probe:$/ { on = 1; print; next } on && /^  [a-z-]+:$/ { exit } on { print }' "$workflow")"
if [[ -z "$kn_job" ]]; then
    fail "fr19_8_4_the_kotlin_native_probe_is_never_report_only" "the probe job was not found in the workflow"
elif grep -qF 'report-only' <<<"$kn_job"; then
    fail "fr19_8_4_the_kotlin_native_probe_is_never_report_only" "the Kotlin/Native probe job passes --report-only, so its faults would not fail the suite"
elif [[ "$(grep -c -- '--report-only' "$workflow")" != 3 ]]; then
    fail "fr19_8_4_the_kotlin_native_probe_is_never_report_only" "--report-only is named other than in the GraalVM gate step and its two comments"
else
    ok "fr19_8_4_the_kotlin_native_probe_is_never_report_only"
fi
if ! grep -qE '^    name: GraalVM renderer, .*, reported, not gating \(Windows, \$\{\{ matrix\.os \}\}\)$' "$workflow"; then
    fail "fr19_8_4_the_graalvm_probe_job_says_it_is_reported" "the probe-graalvm job name does not say reported, not gating"
else
    ok "fr19_8_4_the_graalvm_probe_job_says_it_is_reported"
fi
# Both legs are strict for the drags: report-only is a flag of the gate for one renderer, not a leg.
if grep -qE "DRAGS_GATE|KNOWN FAULT|drags_limit_percent" "$gate" - <<<"$step_block$graalvm_block"; then
    fail "both legs are strict" "the gate still has a DRAGS_GATE or KNOWN FAULT exception"
fi

mkdir -p "$work/bin"
printf '#!/usr/bin/env bash\n# cygpath -u <path>: here the path is already the one to use.\nprintf "%%s\\n" "${@: -1}"\n' > "$work/bin/cygpath"
chmod +x "$work/bin/cygpath"

# The renderer the logs are made for and the gate is run for (kn or graalvm), the line its
# application prints when it draws the marker scene (empty: it printed none), and how many of
# each window states run's 4 size changes had a faulty frame.
renderer=kn
marker_line="hand-check: marker scene"
states_faulty=0
# "--report-only" when the gate is run in that mode.
gate_flag=""

# One set of probe logs under $1. $2 names the labels of the CPU path whose drags show a fault,
# $3 a label that drew no CPU step, $4 a label with a step that was not validated, and $5 is
# "control-passes" when the late control is to pass. Empty means none.
make_logs() {
    local root="$1/probe-logs" faulty="$2" stepless="$3" unvalidated="$4" control="$5"
    rm -rf "$root"; mkdir -p "$root"
    echo "set-display-scale: now 200%, effective dpi 192 (want 192)" > "$root/set-display-scale.log"
    local scale want app edge rate label run
    for scale in 1x 2x; do
        want="dpi 96 (1.00x)"; [[ $scale = 2x ]] && want="dpi 192 (2.00x)"
        mkdir -p "$root/$scale"
        for app in "$renderer-new" "$renderer-old" control-late; do
            for edge in right bottom corner left states; do
                for rate in 16 8 1; do
                    label="$app-$edge-$rate"; [[ $edge = states ]] && label="$app-states"
                    {
                        echo "resize-probe: os windows 10.0.26100"
                        echo "resize-probe: window 640x480 px, $want"
                        echo "resize-probe: markers at rest found"
                        if [[ $edge = states ]]; then
                            echo "resize-probe: state maximise qpc=1 sent"
                            echo "resize-probe: state restore qpc=2 sent"
                            echo "resize-probe: state left-half qpc=3 set"
                            echo "resize-probe: state back qpc=4 set"
                            echo "resize-probe: states changes=4 faulty=$states_faulty missed=0"
                        else
                            echo "resize-probe: move-size loop entered"
                            echo "resize-probe: lag frames=12"
                        fi
                        if [[ $app = control-late && $control != control-passes ]] ||
                            [[ $app = "$renderer-new" && $scale = 2x && " $faulty " = *" $label "* ]]; then
                            echo "resize-probe: verdict FAIL ($edge, 2 with a gap)"
                        else
                            echo "resize-probe: verdict PASS ($edge)"
                        fi
                    } > "$root/$scale/$label-probe.log"
                    {
                        [[ -z "$marker_line" ]] || echo "$marker_line (DXC_RESIZE_MARKER)"
                        case "$app" in
                            "$renderer-new")
                                echo "resize-mode: resize frames on the CPU (Skia raster, GDI blit)"
                                if [[ $edge != states && $label != "$stepless" ]]; then
                                    echo "resize-proof step n=1 presents=0 child_visible=0 blit=1 validated=1 at 2.5 ms"
                                    if [[ $label = "$unvalidated" ]]; then
                                        echo "resize-proof step n=2 presents=0 child_visible=0 blit=1 validated=0"
                                    else
                                        echo "resize-proof step n=2 presents=0 child_visible=0 blit=1 validated=1"
                                    fi
                                fi
                                ;;
                            "$renderer-old") echo "resize-mode: resize frames on the GPU" ;;
                            control-late) echo "resize-control: step n=1 late_ms=50.2"; echo "resize-control: step n=2 late_ms=51.0" ;;
                        esac
                    } > "$root/$scale/$label.all.log"
                    [[ $edge = states ]] && break
                done
            done
        done
        # The repeated runs of the window states, which are judged together by rate.
        for run in $(seq 2 25); do cp "$root/$scale/$renderer-new-states-probe.log" "$root/$scale/$renderer-new-states-r$run-probe.log"; done
    done
}

# Runs the gate on the logs under $work/case as the leg $1. Leaves its output in $work/out.
run_gate() {
    local status=0
    PATH="$work/bin:$PATH" RUNNER_TEMP="$work/case" DRAGS_GATE="$1" RUNNER_OS_LABEL="$2" \
        SCALE_STATUS=0 bash --noprofile --norc "$gate" "$renderer" ${gate_flag:+"$gate_flag"} > "$work/out" 2>&1 || status=$?
    return "$status"
}

expect() {
    local name="$1" want="$2" leg="$3" os="$4" needle="${5:-}" status=0
    run_gate "$leg" "$os" || status=$?
    if [[ $want = pass && $status -ne 0 ]]; then
        fail "$name" "the job failed (status $status): $(grep -vE 'verdict (PASS|FAIL)|steps: [0-9]+,|-new-states: runs' "$work/out" | head -3 | tr '\n' '|')"
    elif [[ $want = fail && $status -eq 0 ]]; then
        fail "$name" "the job passed"
    elif [[ -n $needle ]] && ! grep -qF "$needle" "$work/out"; then
        fail "$name" "the log does not say \"$needle\""
    else
        ok "$name"
    fi
}

make_logs "$work/case" "" "" "" ""
expect "fr19_8_4_a_clean_run_passes_on_windows_2025" pass strict windows-2025 "windows-2025: 0 of 24 drags of the CPU resize path showed a fault"
expect "fr19_8_4_a_clean_run_passes_on_windows_2022" pass strict windows-2022 "windows-2022: 0 of 24 drags of the CPU resize path showed a fault"

make_logs "$work/case" "kn-new-left-8" "" "" ""
expect "fr19_8_4_one_faulty_drag_fails_windows_2025" fail strict windows-2025 "  FAULT: the CPU resize path shows a fault"
expect "fr19_8_4_one_faulty_drag_fails_windows_2022" fail strict windows-2022 "  FAULT: the CPU resize path shows a fault"
grep -qF "windows-2022: 1 of 24 drags of the CPU resize path showed a fault" "$work/out" ||
    fail "fr19_8_4_the_strict_leg_counts_its_faulty_drags" "the closing line does not count the one faulty drag"

make_logs "$work/case" "" "" "" control-passes
expect "fr19_8_4_a_control_that_passes_fails_windows_2025" fail strict windows-2025 "NO DISCRIMINATION"
expect "fr19_8_4_a_control_that_passes_fails_windows_2022" fail strict windows-2022 "NO DISCRIMINATION"

make_logs "$work/case" "" "kn-new-right-16" "" ""
expect "fr19_8_4_a_drag_without_a_cpu_step_fails_windows_2025" fail strict windows-2025 "FAULT: the drag drew no CPU step"
expect "fr19_8_4_a_drag_without_a_cpu_step_fails_windows_2022" fail strict windows-2022 "FAULT: the drag drew no CPU step"

make_logs "$work/case" "" "" "kn-new-corner-1" ""
expect "fr19_8_4_a_step_that_was_not_validated_fails_windows_2022" fail strict windows-2022 "FAULT: a CPU step was not blitted and validated"

# The GraalVM renderer, through the C smoke host that draws the same marker scene.
renderer=graalvm
marker_line="smoke host: marker scene"
make_logs "$work/case" "" "" "" ""
expect "fr19_8_4_graalvm_a_clean_run_passes_on_windows_2025" pass strict windows-2025 "windows-2025: 0 of 24 drags of the CPU resize path showed a fault (graalvm)"
expect "fr19_8_4_graalvm_a_clean_run_passes_on_windows_2022" pass strict windows-2022 "windows-2022: 0 of 24 drags of the CPU resize path showed a fault (graalvm)"

make_logs "$work/case" "graalvm-new-bottom-1" "" "" ""
expect "fr19_8_4_graalvm_one_faulty_drag_fails_windows_2025" fail strict windows-2025 "  FAULT: the CPU resize path shows a fault"
expect "fr19_8_4_graalvm_one_faulty_drag_fails_windows_2022" fail strict windows-2022 "windows-2022: 1 of 24 drags of the CPU resize path showed a fault (graalvm)"

make_logs "$work/case" "" "" "" control-passes
expect "fr19_8_4_graalvm_a_control_that_passes_fails" fail strict windows-2025 "NO DISCRIMINATION"

make_logs "$work/case" "" "graalvm-new-left-16" "" ""
expect "fr19_8_4_graalvm_a_drag_without_a_cpu_step_fails" fail strict windows-2025 "FAULT: the drag drew no CPU step"

states_faulty=1
make_logs "$work/case" "" "" "" ""
expect "fr19_8_4_graalvm_window_states_over_the_limit_fail" fail strict windows-2025 "more than 10% of the window state changes showed a faulty frame"
states_faulty=0

marker_line=""
make_logs "$work/case" "" "" "" ""
expect "fr19_8_4_graalvm_no_marker_scene_fails" fail strict windows-2025 "the GraalVM smoke host did not draw the marker scene"
marker_line="smoke host: marker scene"

# hand-check's logs, or hand-check's marker line, do not pass for the GraalVM renderer.
renderer=kn
marker_line="hand-check: marker scene"
make_logs "$work/case" "" "" "" ""
renderer=graalvm
expect "fr19_8_4_graalvm_gate_does_not_read_hand_checks_drags" fail strict windows-2025 "graalvm-new-right-16: no verdict"
marker_line="hand-check: marker scene"
make_logs "$work/case" "" "" "" ""
expect "fr19_8_4_graalvm_gate_wants_the_smoke_hosts_marker_line" fail strict windows-2025 "the GraalVM smoke host did not draw the marker scene"


# --report-only, for the GraalVM renderer: the window's faults are reported and do not fail the
# run, the probe not having run still does, and the shipped Kotlin/Native window cannot use it.
gate_flag="--report-only"
renderer=graalvm
marker_line="smoke host: marker scene"
make_logs "$work/case" "" "" "" ""
expect "fr19_8_4_report_only_a_clean_run_passes" pass strict windows-2025 "REPORT ONLY: 0 of 48 drags"

make_logs "$work/case" "graalvm-new-right-16 graalvm-new-corner-1" "" "" ""
expect "fr19_8_4_report_only_faulty_drags_do_not_fail_the_run" pass strict windows-2025 "REPORT ONLY: 2 of 48 drags"
grep -qF "FAULT: the CPU resize path shows a fault" "$work/out" ||
    fail "fr19_8_4_report_only_prints_every_finding" "the faulty drag's finding is not printed"
grep -qF "This renderer is reported and does not fail the run" "$work/out" ||
    fail "fr19_8_4_report_only_says_it_does_not_gate" "the closing line does not say the renderer is reported and does not fail the run"

# The GraalVM window today: it prints no CPU resize path lines at all.
make_logs "$work/case" "" "" "" ""
for f in "$work"/case/probe-logs/*/graalvm-new-*.all.log; do grep -vE "^resize-(mode|proof)" "$f" > "$f.tmp"; mv "$f.tmp" "$f"; done
expect "fr19_8_4_report_only_a_window_with_no_cpu_resize_path_does_not_fail_the_run" pass strict windows-2025 "did not take the CPU resize path"
grep -qF "FAULT: the drag drew no CPU step" "$work/out" || fail "fr19_8_4_report_only_a_window_with_no_cpu_resize_path_does_not_fail_the_run" "the missing CPU steps are not printed"

states_faulty=1
make_logs "$work/case" "" "" "" ""
expect "fr19_8_4_report_only_window_states_over_the_limit_do_not_fail_the_run" pass strict windows-2025 "more than 10% of the window state changes"
states_faulty=0

# The step summary gets the same closing words.
make_logs "$work/case" "graalvm-new-left-8" "" "" ""
summary="$work/summary.md"; : > "$summary"
GITHUB_STEP_SUMMARY="$summary" run_gate strict windows-2022
grep -qF "REPORT ONLY: 1 of 48 drags" "$summary" || fail "fr19_8_4_report_only_writes_the_step_summary" "GITHUB_STEP_SUMMARY has no REPORT ONLY line"

# What is not a window fault stays a failure: a report of nothing must not pass.
make_logs "$work/case" "" "" "" ""
rm "$work/case/probe-logs/2x/graalvm-new-left-8-probe.log"
expect "fr19_8_4_report_only_a_probe_that_did_not_run_still_fails" fail strict windows-2025 "the probe did not run"
make_logs "$work/case" "" "" "" ""
rm -rf "$work/case/probe-logs/1x" "$work/case/probe-logs/2x"
expect "fr19_8_4_report_only_no_logs_still_fails" fail strict windows-2025 "no verdict"
make_logs "$work/case" "" "" "" control-passes
expect "fr19_8_4_report_only_a_control_that_passes_still_fails" fail strict windows-2025 "NO DISCRIMINATION"
marker_line=""
make_logs "$work/case" "" "" "" ""
expect "fr19_8_4_report_only_no_marker_scene_still_fails" fail strict windows-2025 "did not draw the marker scene"
marker_line="smoke host: marker scene"

# A window that faulted and a probe that did not run: the run fails and says so.
make_logs "$work/case" "graalvm-new-left-8" "" "" ""
rm "$work/case/probe-logs/1x/graalvm-new-right-1-probe.log"
expect "fr19_8_4_report_only_says_when_the_run_still_fails" fail strict windows-2025 "The run still fails"

# The shipped window is refused.
renderer=kn
marker_line="hand-check: marker scene"
make_logs "$work/case" "" "" "" ""
status=0; run_gate strict windows-2025 || status=$?
if [[ $status -eq 0 ]]; then
    fail "fr19_8_4_kn_report_only_is_refused" "the Kotlin/Native gate accepted --report-only"
elif ! grep -qF "only for graalvm" "$work/out"; then
    fail "fr19_8_4_kn_report_only_is_refused" "the refusal does not say why: $(head -2 "$work/out")"
else
    ok "fr19_8_4_kn_report_only_is_refused"
fi
status=0; PATH="$work/bin:$PATH" RUNNER_TEMP="$work/case" RUNNER_OS_LABEL=x bash "$gate" graalvm --nonsense > "$work/out" 2>&1 || status=$?
if [[ $status -ne 0 ]]; then ok "fr19_8_4_an_unknown_flag_is_refused"; else fail "fr19_8_4_an_unknown_flag_is_refused" "it ran"; fi

exit "$red"

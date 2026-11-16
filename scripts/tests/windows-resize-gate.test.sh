#!/usr/bin/env bash
# The step that judges the resize probe is a shell script inside a workflow, and it had never
# run to its end before it was relied on. The first run that got there failed a clean
# windows-2025 leg without a word: the script asked every label of the CPU path for at least
# one CPU step, and the window states draw none, because a maximise or a snap does not go
# through the move-size loop.
#
# So this runs that script here, taken from the workflow as it is, against probe logs made
# up for the purpose:
#   - a clean run passes, on the leg judged by rate and on the reported one;
#   - on the leg judged by rate (windows-2025) one or two faulty drags of the 24 are counted
#     and pass, and three fail the job: the fork's window shows a stray frame with a gap now
#     and then, and the limit is there to catch a window that got worse;
#   - on the reported leg (windows-2022) the same drag is named as a known fault, counted,
#     and does not fail the job, while a control that passes, a drag with no CPU step and a
#     step that was not validated still fail it there.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/test-windows-resize-metrics.yml"
[[ -f "$workflow" ]] || { echo "missing $workflow"; exit 1; }
red=0
ok() { echo "ok    $1"; }
fail() { echo "FAIL  $1: $2"; red=1; }

work="$(mktemp -d "${TMPDIR:-/tmp}/resize-gate.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# The step's script: the lines of its `run: |` block, without their indent.
step="The probe can tell late from prompt, and the CPU path shows no fault"
awk -v name="      - name: $step" '
    $0 == name { found = 1; next }
    found && /^      - name: / { exit }
    found && /^        run: \|$/ { body = 1; next }
    body { sub(/^          /, ""); print }
' "$workflow" > "$work/gate.sh"
[[ -s "$work/gate.sh" ]] || { echo "FAIL  the step \"$step\" was not found in the workflow"; exit 1; }
# The one expression of the workflow in it: the display did go to 200%.
sed -i.bak 's/\${{ steps.scale.outputs.status }}/0/' "$work/gate.sh"
if grep -q '\${{' "$work/gate.sh"; then
    fail "the gate script" "it holds a workflow expression this test does not fill in: $(grep -o '\${{[^}]*}}' "$work/gate.sh" | head -1)"
fi
# The leg is chosen by the runner's name, and windows-2022 is the reported one.
step_block="$(awk -v name="      - name: $step" '$0 == name { found = 1 } found && /^      - name: / && $0 != name { exit } found { print }' "$workflow")"
grep -qF "DRAGS_GATE: \${{ matrix.os == 'windows-2022' && 'reported' || 'rate' }}" <<<"$step_block" ||
    fail "the leg that is reported" "DRAGS_GATE is not 'reported' on windows-2022 and 'rate' elsewhere"

mkdir -p "$work/bin"
printf '#!/usr/bin/env bash\n# cygpath -u <path>: here the path is already the one to use.\nprintf "%%s\\n" "${@: -1}"\n' > "$work/bin/cygpath"
chmod +x "$work/bin/cygpath"

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
        for app in kn-new kn-old control-late; do
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
                            echo "resize-probe: states changes=4 faulty=0 missed=0"
                        else
                            echo "resize-probe: move-size loop entered"
                            echo "resize-probe: lag frames=12"
                        fi
                        if [[ $app = control-late && $control != control-passes ]] ||
                            [[ $app = kn-new && $scale = 2x && " $faulty " = *" $label "* ]]; then
                            echo "resize-probe: verdict FAIL ($edge, 2 with a gap)"
                        else
                            echo "resize-probe: verdict PASS ($edge)"
                        fi
                    } > "$root/$scale/$label-probe.log"
                    {
                        echo "hand-check: marker scene"
                        case "$app" in
                            kn-new)
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
                            kn-old) echo "resize-mode: resize frames on the GPU" ;;
                            control-late) echo "resize-control: step n=1 late_ms=50.2"; echo "resize-control: step n=2 late_ms=51.0" ;;
                        esac
                    } > "$root/$scale/$label.all.log"
                    [[ $edge = states ]] && break
                done
            done
        done
        # The repeated runs of the window states, which are judged together by rate.
        for run in $(seq 2 25); do cp "$root/$scale/kn-new-states-probe.log" "$root/$scale/kn-new-states-r$run-probe.log"; done
    done
}

# Runs the gate on the logs under $work/case as the leg $1. Leaves its output in $work/out.
run_gate() {
    local status=0
    PATH="$work/bin:$PATH" RUNNER_TEMP="$work/case" DRAGS_GATE="$1" RUNNER_OS_LABEL="$2" \
        bash --noprofile --norc -o pipefail "$work/gate.sh" > "$work/out" 2>&1 || status=$?
    return "$status"
}

expect() {
    local name="$1" want="$2" leg="$3" os="$4" needle="${5:-}" status=0
    run_gate "$leg" "$os" || status=$?
    if [[ $want = pass && $status -ne 0 ]]; then
        fail "$name" "the job failed (status $status): $(grep -vE 'verdict (PASS|FAIL)|steps: [0-9]+,|kn-new-states: runs' "$work/out" | head -3 | tr '\n' '|')"
    elif [[ $want = fail && $status -eq 0 ]]; then
        fail "$name" "the job passed"
    elif [[ -n $needle ]] && ! grep -qF "$needle" "$work/out"; then
        fail "$name" "the log does not say \"$needle\""
    else
        ok "$name"
    fi
}

make_logs "$work/case" "" "" "" ""
expect "fr19_8_4_a_clean_run_passes_on_the_rate_leg" pass rate windows-2025
expect "fr19_8_4_a_clean_run_passes_on_the_reported_leg" pass reported windows-2022 "0 drags of the CPU resize path showed a fault"

make_logs "$work/case" "kn-new-left-8" "" "" ""
expect "fr19_8_4_one_faulty_drag_of_24_is_counted_and_passes_the_rate_leg" pass rate windows-2025 "windows-2025: 1 of 24 drags of the CPU resize path showed a fault, limit 10% (at most 2)"
expect "fr19_8_4_a_faulty_drag_is_reported_and_does_not_fail_windows_2022" pass reported windows-2022 "KNOWN FAULT, reported and not failing the job on windows-2022"
grep -qF "windows-2022: 1 drags of the CPU resize path showed a fault. They are reported and do not fail this job" "$work/out" ||
    fail "fr19_8_4_the_reported_leg_counts_its_known_faults" "the closing line does not count the one faulty drag"

make_logs "$work/case" "kn-new-left-8 kn-new-right-16" "" "" ""
expect "fr19_8_4_two_faulty_drags_of_24_still_pass_the_rate_leg" pass rate windows-2025 "windows-2025: 2 of 24 drags"

make_logs "$work/case" "kn-new-left-8 kn-new-right-16 kn-new-corner-1" "" "" ""
expect "fr19_8_4_three_faulty_drags_of_24_fail_the_rate_leg" fail rate windows-2025 "FAULT: more than 10% of the drags of the CPU resize path showed a fault"
expect "fr19_8_4_three_faulty_drags_do_not_fail_windows_2022" pass reported windows-2022 "windows-2022: 3 drags of the CPU resize path showed a fault"

make_logs "$work/case" "" "" "" control-passes
expect "fr19_8_4_a_control_that_passes_fails_the_rate_leg" fail rate windows-2025 "NO DISCRIMINATION"
expect "fr19_8_4_a_control_that_passes_fails_the_reported_leg_too" fail reported windows-2022 "NO DISCRIMINATION"

make_logs "$work/case" "" "kn-new-right-16" "" ""
expect "fr19_8_4_a_drag_without_a_cpu_step_fails_the_rate_leg" fail rate windows-2025 "FAULT: the drag drew no CPU step"
expect "fr19_8_4_a_drag_without_a_cpu_step_fails_the_reported_leg_too" fail reported windows-2022 "FAULT: the drag drew no CPU step"

make_logs "$work/case" "" "" "kn-new-corner-1" ""
expect "fr19_8_4_a_step_that_was_not_validated_fails_the_reported_leg_too" fail reported windows-2022 "FAULT: a CPU step was not blitted and validated"

exit "$red"

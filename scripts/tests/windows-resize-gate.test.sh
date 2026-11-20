#!/usr/bin/env bash
# The step that judges the resize probe runs .github/scripts/resize-probe-gate.sh, and that
# script had never run to its end before it was relied on (it was then the step's own body). The first run that got there failed a clean
# windows-2025 leg without a word: the script asked every label of the CPU path for at least
# one CPU step, and the window states draw none, because a maximise or a snap does not go
# through the move-size loop.
#
# So this runs that script here, as the probe job calls it, against probe logs made up for the
# purpose:
#   - a clean run passes on both legs (windows-2025 and windows-2022);
#   - a single faulty drag of the 24 fails the job on both windows-2025 and windows-2022,
#     and a control that passes, a drag with no CPU step and a step that was not validated
#     fail it on both legs as well.
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
# Both legs are strict: neither windows-2022 nor windows-2025 uses a reported or rate gate for drags.
if grep -qE "DRAGS_GATE|KNOWN FAULT|drags_limit_percent" "$gate" - <<<"$step_block"; then
    fail "both legs are strict" "the gate still has a DRAGS_GATE or KNOWN FAULT exception"
fi

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
        SCALE_STATUS=0 bash --noprofile --norc "$gate" kn > "$work/out" 2>&1 || status=$?
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

exit "$red"

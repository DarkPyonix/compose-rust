#!/usr/bin/env bash
# The Windows resize check is only worth what its probe can see, and only means something
# when it runs against the window code this commit pins. Two things have gone wrong before:
# the probe test dragged a hand-check built months earlier, from an artifact that expires, so
# a new window pin was never dragged at all; and a frame whose border followed a picture that
# was too small showed no backdrop and read as a narrower window, not as a fault.
#
# What this checks, without Windows:
#   - the probe job drags the hand-check its own run built, and every dispatch of ci.yml
#     runs it, so a moved pin is dragged by the run that checks the move;
#   - every edge, the window states, the markers and the frames are asked for, at 100% and
#     200%, and the job fails when the late control passes or a CPU path drag shows a
#     fault, or when more than 10% of the CPU path's window state changes show one;
#   - hand-check and the control draw the markers the probe looks for, in the colours it
#     takes for them. That half compiles the probe's colour tests with whatever C compiler is
#     here, and skips without one.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/test-windows-resize-metrics.yml"
ci="$repo_root/.github/workflows/ci.yml"
probe="$repo_root/.github/scripts/resize-probe.c"
control="$repo_root/.github/scripts/resize-control.c"
kit_script="$repo_root/.github/scripts/run-probe-v2.ps1"
sheets="$repo_root/.github/scripts/resize-contact-sheet.ps1"
hand_check="$repo_root/compose-rust/tests/fixtures/hand-check/src/main.rs"
compose_pin="$repo_root/renderer/scripts/build-compose.sh"
red=0

ok() { echo "ok    $1"; }
fail() { echo "FAIL  $1: $2"; red=1; }

for file in "$workflow" "$ci" "$probe" "$control" "$kit_script" "$sheets" "$hand_check" "$compose_pin"; do
    [[ -f "$file" ]] || { echo "missing $file"; exit 1; }
done

# The probe job, from its key to the next job at the same indent.
probe_job="$(awk '/^  probe:$/ { on = 1; print; next } on && /^  [a-z][a-z-]*:$/ { exit } on { print }' "$workflow")"

name=fr19_8_4_probe_drags_the_hand_check_built_in_the_same_run
if [[ -z "$probe_job" ]]; then
    fail "$name" "the resize metrics workflow has no probe job"
elif ! grep -qE '^    needs: windows$' <<<"$probe_job"; then
    fail "$name" "the probe job does not wait for the windows job that builds hand-check"
elif ! grep -qF 'name: hand-check-windows-kn' <<<"$probe_job"; then
    fail "$name" "the probe job does not take the hand-check-windows-kn artifact"
elif grep -qE 'run-id:|HAND_CHECK_RUN' "$workflow" "$repo_root"/.github/workflows/*.yml; then
    fail "$name" "a workflow still takes hand-check from another run"
else
    ok "$name"
fi

# Development CI is one manual dispatch of ci.yml, which calls every suite on the commit it is
# dispatched for. So the pin that commit carries is dragged when the resize suite is one of
# those calls, with no condition but the plan's (scripts/ci-suites.sh puts it in `all`, the
# default), and when nothing in the suite lets the probe job,
# or a job it waits for, be skipped while the run goes on. The suite's metrics input only
# turns the measurements on and off: the probe and its builds must never read it, and the
# measuring parts must. A trigger or a path filter in the suite would be a second way in that
# the dispatch does not cover.
name=fr19_8_4_every_ci_dispatch_drags_the_window_pin
ci_job="$(awk '/^  test_windows_resize:$/ { on = 1; print; next } on && /^  [a-z][a-z_]*:/ { exit } on { print }' "$ci")"
workflow_on="$(awk '/^on:/ { on = 1; next } /^[^ #]/ { on = 0 } on' "$workflow")"
job() { awk -v key="  $1:" '$0 == key { on = 1; print; next } on && /^  [a-z][a-z-]*:$/ { exit } on { print }' "$workflow"; }
# Each step of a job, flattened to one line per step: its name and its if, if any.
step_ifs() { awk '/^      - (name|uses):/ { if (n) print n " | " c; n = $0; c = ""; next } /^        if:/ { c = $0 } END { if (n) print n " | " c }' <<<"$1"; }
gate_problem=""
for key in compose windows probe; do
    body="$(job "$key")"
    [[ -n "$body" ]] || { gate_problem="the $key job is missing"; break; }
    if grep -qE '^    (if|continue-on-error):' <<<"$body"; then
        gate_problem="the $key job can be skipped, or can fail without failing the run"; break
    fi
done
grep -qF 'inputs.metrics' <<<"$(job requested)" && gate_problem="the requested job reads inputs.metrics"
grep -qE '^    needs: requested$' <<<"$(job compose)" || gate_problem="${gate_problem:-the compose job does not wait for the requested job}"
grep -qE '^    needs: compose$' <<<"$(job windows)" || gate_problem="${gate_problem:-the windows job does not wait for compose}"
# The steps the gate judges, and the steps whose output it judges, read no switch.
for step in "Build the static renderer" "Build hand-check against the renderer" "Scripted resize, measured" \
    "What is on screen during a resize must be the frame drawn for it" "CPU and GPU frames must match"; do
    line="$(step_ifs "$(job windows)" | grep -F "name: $step |")"
    [[ -n "$line" ]] || { gate_problem="${gate_problem:-the windows job has no step \"$step\"}"; continue; }
    grep -qE '\| .+' <<<"$line" && gate_problem="${gate_problem:-the windows step \"$step\" has a condition}"
done
for step in "Real drags at 100%" "Set the display to 200%" "Real drags at 200%" \
    "The probe can tell late from prompt, and the CPU path shows no fault"; do
    line="$(step_ifs "$(job probe)" | grep -F "name: $step |")"
    [[ -n "$line" ]] || { gate_problem="${gate_problem:-the probe job has no step \"$step\"}"; continue; }
    grep -qE '\| .+' <<<"$line" && gate_problem="${gate_problem:-the probe step \"$step\" has a condition}"
done
# The scripted step may only cut its own timing runs short, after the real drags.
scripted="$(awk '/^      - name: Scripted resize, measured$/ { on = 1; print; next } on && /^      - / { exit } on { print }' <<<"$(job windows)")"
if [[ "$(grep -n 'METRICS" = true' <<<"$scripted" | tail -1 | cut -d: -f1)" -lt "$(grep -n 'resize-real-drag-run.sh' <<<"$scripted" | head -1 | cut -d: -f1)" ]] 2>/dev/null; then
    gate_problem="${gate_problem:-the scripted resize step stops before the real drags it is judged on}"
fi
# The measuring parts are behind the switch.
measure_problem=""
grep -qE '^    if: inputs\.metrics$' <<<"$(job macos)" || measure_problem="the macos job runs without metrics"
for pair in "windows|Resize frames on the CPU at 200%, each way of drawing them" "probe|Report the drags" "probe|Report memory"; do
    line="$(step_ifs "$(job "${pair%%|*}")" | grep -F "name: ${pair#*|} |")"
    grep -qF 'inputs.metrics' <<<"$line" || measure_problem="${measure_problem:-the ${pair%%|*} step \"${pair#*|}\" runs without metrics}"
done
grep -qF 'METRICS: ${{ inputs.metrics }}' <<<"$scripted" || measure_problem="${measure_problem:-the scripted resize step does not read metrics}"
if ! grep -qE '^WINDOW_REVISION=' "$compose_pin"; then
    fail "$name" "renderer/scripts/build-compose.sh no longer sets WINDOW_REVISION"
elif ! grep -qE '^    uses: \./\.github/workflows/test-windows-resize-metrics\.yml$' <<<"$ci_job"; then
    fail "$name" "ci.yml does not call the resize workflow, so a dispatch drags nothing"
elif [[ "$(grep -E '^    (if|needs):' <<<"$ci_job")" != "    if: needs.plan.outputs.test_windows_resize == 'true'"$'\n'"    needs: plan" ]]; then
    fail "$name" "ci.yml calls the resize workflow on a condition other than the plan, or after another suite"
elif ! grep -qx 'test_windows_resize=true' <<<"$("$repo_root/scripts/ci-suites.sh" all)"; then
    fail "$name" "scripts/ci-suites.sh leaves the resize workflow out of all, the default dispatch"
elif ! grep -qE '^  workflow_call:' <<<"$workflow_on"; then
    fail "$name" "the resize workflow cannot be called by ci.yml"
elif ! grep -qE '^      metrics:' <<<"$workflow_on" || ! grep -qE '^        default: false$' <<<"$workflow_on"; then
    fail "$name" "the resize workflow has no metrics input that defaults to off"
elif grep -qE '^  (pull_request|pull_request_target|push|schedule|workflow_dispatch):' <<<"$workflow_on"; then
    fail "$name" "the resize workflow has a trigger of its own; only a ci.yml dispatch starts it"
elif [[ -n "$gate_problem" ]]; then
    fail "$name" "$gate_problem"
elif [[ -n "$measure_problem" ]]; then
    fail "$name" "$measure_problem"
else
    ok "$name"
fi

name=fr19_8_2_drags_at_100_and_200_percent_on_both_server_builds
if ! grep -qF 'os: [windows-2022, windows-2025]' <<<"$probe_job"; then
    fail "$name" "the probe job does not run on Server 2022 and 2025"
elif ! grep -qF 'set-display-scale.exe" 200' <<<"$probe_job" || ! grep -qF 'probe-logs/2x' <<<"$probe_job"; then
    fail "$name" "the probe job does not drag at 200%"
else
    ok "$name"
fi

name=fr19_8_4_every_edge_and_the_window_states_with_markers_and_frames
missing=""
for edge in right bottom corner left states; do
    grep -qE "\\\$Edges = @\\(.*'$edge'" "$kit_script" || missing="$missing $edge"
done
for flag in "'--markers'" "'--frames'" "'--edge'" "'--no-animation'" "DXC_RESIZE_MARKER = '1'" "resize-contact-sheet.ps1"; do
    grep -qF -- "$flag" "$kit_script" || missing="$missing $flag"
done
for edge in right left bottom corner states; do
    grep -qF "strcmp(edge_name, \"$edge\")" "$probe" || missing="$missing probe:$edge"
done
if [[ -n "$missing" ]]; then
    fail "$name" "missing:$missing"
else
    ok "$name"
fi

name=fr19_8_4_a_marker_gap_or_stretch_fails_the_drag
if ! grep -qE 'stale_in_drag > 0 \|\| marker_gap_in_drag > 0 \|\|' "$probe" || ! grep -qF 'marker_stretch_in_drag > 0;' "$probe"; then
    fail "$name" "the probe's verdict does not count marker gaps and stretches"
else
    ok "$name"
fi

name=fr19_8_4_the_job_fails_when_the_late_control_passes_or_the_cpu_path_has_a_fault
gate="$(awk '/name: The probe can tell late from prompt, and the CPU path shows no fault/ { on = 1 } on' <<<"$probe_job")"
control_branch="$(awk '/control-late-\*\)/ { on = 1 } on { print } on && /^ *;;$/ { exit }' <<<"$gate")"
cpu_branch="$(awk '/kn-new-\*\)/ { on = 1 } on { print } on && /^ *;;$/ { exit }' <<<"$gate")"
if [[ -z "$gate" ]]; then
    fail "$name" "the probe job has no gate step"
elif ! grep -q 'NO DISCRIMINATION' <<<"$control_branch" || ! grep -q 'status=1' <<<"$control_branch"; then
    fail "$name" "a passing control does not fail the job"
elif ! grep -q 'verdict PASS' <<<"$cpu_branch" || ! grep -q 'FAULT' <<<"$cpu_branch"; then
    fail "$name" "a fault on the CPU path does not fail the job"
elif ! grep -qF 'for edge in right bottom corner left' <<<"$gate" || ! grep -qF -- '-states"' <<<"$gate"; then
    fail "$name" "the gate does not look at every edge and the window states"
elif ! grep -qF 'exit $status' <<<"$gate"; then
    fail "$name" "the gate does not exit with what it found"
else
    ok "$name"
fi

name=fr19_8_4_the_window_states_are_judged_by_rate_and_the_drags_strictly
# The window states fail above 10% of their size changes with a faulty frame, and a short
# count fails too; every drag on the CPU path still has to PASS outright.
states_case="$(grep -F 'case "$label:$verdict" in' -A 3 <<<"$cpu_branch")"
if ! grep -qF 'states_limit_percent=10' <<<"$gate" || ! grep -qF 'states_runs=25' <<<"$gate" ||
    ! grep -qF 'states_changes_per_run=4' <<<"$gate"; then
    fail "$name" "the gate does not judge 25 runs of 4 window state changes at a 10% limit"
elif ! grep -qF '[ $((faulty * 100)) -le $((changes * states_limit_percent)) ]' <<<"$gate"; then
    fail "$name" "the gate does not fail above the states limit"
elif ! grep -qF '[ "$changes" -ge "$expected" ] || {' <<<"$gate" ||
    ! grep -qF 'fewer size changes were counted' <<<"$gate"; then
    fail "$name" "a short count of window state changes does not fail the job"
elif [[ "$(grep -c -- '-StatesRuns 25 ' <<<"$probe_job")" != 2 ]] || ! grep -qF '[int]$StatesRuns = 1' "$kit_script" ||
    ! grep -qF 'kn-new-states-r' "$kit_script"; then
    fail "$name" "the kit is not asked for 25 window state runs at both scales"
elif ! grep -qF 'say("states changes=%ld faulty=%d' "$probe" || ! grep -qF 'note_state_change();' "$probe"; then
    fail "$name" "the probe does not count faulty window state changes"
elif ! grep -qF 'kn-new-states:*|*"verdict PASS"*) ;;' <<<"$states_case" ||
    grep -qE 'kn-new-\*:|\*:\*\|' <<<"$states_case"; then
    fail "$name" "the drags on the CPU path are no longer held to verdict PASS"
else
    ok "$name"
fi

name=fr19_8_2_frames_and_contact_sheets_are_kept
if ! grep -qF 'name: resize-probe-frames-${{ matrix.os }}' <<<"$probe_job" || ! grep -qF 'contact-sheet.png' <<<"$probe_job"; then
    fail "$name" "the probe job does not upload the frames and the contact sheets"
elif ! grep -qF "ImageFormat]::Png" "$sheets" || ! grep -qF "contact-sheet.png" "$sheets"; then
    fail "$name" "resize-contact-sheet.ps1 does not write PNG frames and a contact sheet"
else
    ok "$name"
fi

name=fr19_8_4_hand_check_and_the_control_draw_the_markers
if ! grep -qF 'std::env::var("DXC_RESIZE_MARKER")' "$hand_check" || ! grep -qF 'hand-check: marker scene' "$hand_check"; then
    fail "$name" "hand-check does not draw a marker scene for DXC_RESIZE_MARKER"
elif ! grep -qF 'const MARKER_GREEN: u32 = 0x00ff00;' "$hand_check"; then
    fail "$name" "hand-check's marker is not 0x00ff00"
elif ! grep -qF 'const float marker[4] = {0.0f, 1.0f, 0.0f, 1.0f};' "$control"; then
    fail "$name" "the control's marker is not pure green"
else
    ok "$name"
fi

# Both side markers have to be inside the window. The renderer gives a weighted child at
# least its minimum intrinsic width, and a weighted ruler of 160 fixed periods took all of it
# and pushed the right marker out: every hand-check drag ended with no markers at rest. So the
# ruler is a plain row under the markers, and the markers are a row spaced between its ends,
# drawn after the ruler so they sit on top.
name=fr19_8_4_hand_check_keeps_both_side_markers_in_the_window
scene="$(awk '/fn rebuild_marker_scene/,/^    }$/' "$hand_check")"
weights="$(grep -c 'Modifier::Weight' <<<"$scene")"
if [[ -z "$scene" ]]; then
    fail "$name" "hand-check has no rebuild_marker_scene"
elif [[ "$weights" != 1 ]] || ! grep -qF '&[Modifier::Weight(1.0), Modifier::FillMaxWidth]' <<<"$scene"; then
    fail "$name" "the marker scene weights something other than the body in the column ($weights weights); a weighted ruler pushes the right marker out"
elif ! grep -qF 'Arrangement::SpaceBetween' <<<"$scene" ||
    ! grep -qF 'self.insert(MARKER_EDGES, &[MARKER_LEFT, MARKER_RIGHT]);' <<<"$scene"; then
    fail "$name" "the left and right markers are not a row spaced between the body's ends"
elif ! grep -qF 'self.insert(MARKER_BODY, &[MARKER_RULER, MARKER_EDGES]);' <<<"$scene"; then
    fail "$name" "the markers are not drawn over the ruler"
else
    ok "$name"
fi

# The colour tests themselves, compiled out of the probe: the markers' green is a marker and
# nothing else the scene, the frame or the backdrop draws is.
name=fr19_8_4_the_probe_tells_the_markers_from_everything_else
cc="$(command -v cc || command -v clang || command -v gcc || true)"
if [[ -z "$cc" ]]; then
    echo "skip  $name: no C compiler"
else
    scratch="$repo_root/.scratch/windows-resize-probe-test"
    rm -rf "$scratch"
    mkdir -p "$scratch"
    extract() { awk -v name="$1" '$0 ~ "^static int " name "\\(" { on = 1 } on { print } on && /^}/ { exit }' "$probe"; }
    {
        echo '#include <stdint.h>'
        echo '#include <stdio.h>'
        extract luminance
        extract is_backdropish
        extract is_marker
        extract is_ruler_dark
        cat <<'C'
int main(void) {
    int bad = 0;
    struct { uint32_t pixel; int marker; int dark; const char *what; } cases[] = {
        {0xff00ff00u, 1, 0, "the marker green"},
        {0xff10f010u, 1, 0, "the marker green, not bit exact"},
        {0xffff00ffu, 0, 0, "the probe's magenta backdrop"},
        {0xff800080u, 0, 0, "the window's shadow over the backdrop"},
        {0xffe0e0e0u, 0, 0, "the field"},
        {0xff404040u, 0, 1, "a ruler line"},
        {0xff000000u, 0, 1, "black"},
        {0xffffffffu, 0, 0, "white"},
        {0xff1a59ccu, 0, 1, "a blue accent border (dark, though only the ruler between the markers is read)"},
    };
    for (unsigned i = 0; i < sizeof cases / sizeof *cases; i++) {
        if (is_marker(cases[i].pixel) != cases[i].marker || is_ruler_dark(cases[i].pixel) != cases[i].dark) {
            printf("%s (%08x): marker %d dark %d\n", cases[i].what, cases[i].pixel, is_marker(cases[i].pixel),
                   is_ruler_dark(cases[i].pixel));
            bad = 1;
        }
    }
    return bad;
}
C
    } > "$scratch/colours.c"
    if ! "$cc" -std=c99 -o "$scratch/colours" "$scratch/colours.c" 2> "$scratch/cc.log"; then
        fail "$name" "the probe's colour tests do not compile: $(head -5 "$scratch/cc.log")"
    elif ! output="$("$scratch/colours")"; then
        fail "$name" "$output"
    else
        ok "$name"
    fi
    rm -rf "$scratch"
fi

exit $red

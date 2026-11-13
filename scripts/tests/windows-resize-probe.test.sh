#!/usr/bin/env bash
# The Windows resize check is only worth what its probe can see, and only means something
# when it runs against the window code this commit pins. Two things have gone wrong before:
# the probe test dragged a hand-check built months earlier, from an artifact that expires, so
# a new window pin was never dragged at all; and a frame whose border followed a picture that
# was too small showed no backdrop and read as a narrower window, not as a fault.
#
# What this checks, without Windows:
#   - the probe job drags the hand-check its own run built, and moving the pin runs it;
#   - every edge, the window states, the markers and the frames are asked for, at 100% and
#     200%, and the job fails when the late control passes or the CPU path shows a fault;
#   - hand-check and the control draw the markers the probe looks for, in the colours it
#     takes for them. That half compiles the probe's colour tests with whatever C compiler is
#     here, and skips without one.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/test-windows-resize-metrics.yml"
probe="$repo_root/.github/scripts/resize-probe.c"
control="$repo_root/.github/scripts/resize-control.c"
kit_script="$repo_root/.github/scripts/run-probe-v2.ps1"
sheets="$repo_root/.github/scripts/resize-contact-sheet.ps1"
hand_check="$repo_root/compose-rust/tests/fixtures/hand-check/src/main.rs"
compose_pin="$repo_root/renderer/scripts/build-compose.sh"
red=0

ok() { echo "ok    $1"; }
fail() { echo "FAIL  $1: $2"; red=1; }

for file in "$workflow" "$probe" "$control" "$kit_script" "$sheets" "$hand_check" "$compose_pin"; do
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

name=fr19_8_4_moving_the_window_pin_runs_the_drags
if ! grep -qE '^WINDOW_REVISION=' "$compose_pin"; then
    fail "$name" "renderer/scripts/build-compose.sh no longer sets WINDOW_REVISION"
elif ! awk '/^  pull_request:/ { on = 1 } on && /^concurrency:/ { exit } on' "$workflow" |
        grep -qF '"renderer/scripts/build-compose.sh"'; then
    fail "$name" "the workflow's pull_request paths leave out renderer/scripts/build-compose.sh"
elif ! awk '/^  pull_request:/ { on = 1 } on && /^concurrency:/ { exit } on' "$workflow" |
        grep -qF '"compose-rust/tests/fixtures/hand-check/**"'; then
    fail "$name" "the workflow's pull_request paths leave out the hand-check fixture"
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

#!/usr/bin/env bash
# Usage: resize-probe-gate.sh <app> [--report-only]
#
# The verdict of the Windows resize probe on one renderer, read from the logs run-probe-v2.ps1
# left under $RUNNER_TEMP/probe-logs/1x and 2x. <app> is the name run-probe-v2 gives it:
#
#   kn       hand-check.exe, linked to the Kotlin/Native renderer kit;
#   graalvm  graalvm\bin\hand-check.exe, the C smoke host linked to the GraalVM native image,
#            drawing the same marker scene.
#
# Environment: RUNNER_TEMP; RUNNER_OS_LABEL, the runner, for the closing line; SCALE_STATUS, the
# exit status of `set-display-scale.exe 200 --fit`.
#
# Every check here counts and goes on, so the script lists every failure, not the first.
#
# Every drag ran: a verdict that is not ERROR, the Windows version logged, the window at the
# scale asked for, the move-size loop entered and the lag measured (or, for the window states,
# each state sent).
#
# The control (50 ms late on purpose) must FAIL in every drag, at every rate and scale. Where it
# passes, the probe cannot tell a late frame from a prompt one on that runner, and nothing it
# says about the renderer there means anything: that fails the job, it is not reported and
# passed over. Its frames must also have been late as asked.
#
# The renderer with resize frames on the CPU (the default) must PASS every drag: no hole, black,
# torn, stale, marker gap or stretched frame, judged by the markers, and every CPU step blitted
# and validated in the same words from the same C. With resize frames on the GPU it must have
# taken that path; its verdicts are in the summary, not gated.
#
# Its window states (maximise, restore, left half, back) are judged by rate instead: 25 runs of
# 4 size changes at each scale, and the gate fails when more than 10% of the size changes had a
# faulty frame, or when fewer than 100 were counted, since a probe that did not finish has
# judged nothing. It is a rate and not zero because the fork's window still shows the previous
# picture for one composed frame after some restores and snaps
# (compose-multiplatform-core-extended#223): on every one at the ext.3 pin, on about 2.6% of
# them (6.4% in the worst run) at ext.4. A strict zero would fail every run at ext.3 and about
# one in three at ext.4, for a fault this repository cannot fix. The fork's own probe gates it
# at the same 10%. It becomes zero once the fork's window has one surface that takes the size
# and the picture in one step (#223); the drags stay strict throughout.
#
# --report-only (graalvm only): the verdicts are judged exactly as above and every finding is
# printed, but a fault of the window does not fail the run: the closing lines say how many drags
# showed one and that this renderer is reported and does not gate. The GraalVM Windows window is
# not the one a release ships (the Kotlin/Native one is), and it has faults that live in the
# Compose fork's window code (compose-rust#687). kn --report-only is refused: the shipped window
# stays a gate.
#
# What a report-only run still fails on is "the probe did not run", because a report of nothing
# must not pass: the display not at 200%, no verdict line, no Windows version, the window not at
# the scale asked for, no markers at rest, no move-size loop or lag, a window state not sent,
# too few size changes counted, no drag judged, the smoke host not drawing the marker scene, and
# a control that passes (the probe cannot tell late from prompt). What it reports instead is
# "the window faulted": a drag with a faulty frame, window states over the limit, no CPU resize
# path (mode line, CPU steps, steps blitted and validated) and no GPU resize path for the old
# path. Those lines are the ones that call window_fault below.
set -o pipefail

app="${1:-}"
report_only=0
case "${2:-}" in
    "") ;;
    --report-only) report_only=1 ;;
    *) echo "usage: resize-probe-gate.sh kn|graalvm [--report-only]" >&2; exit 2 ;;
esac
case "$app" in
    kn)
        who="hand-check"
        marker_line="hand-check: marker scene"
        ;;
    graalvm)
        who="the GraalVM smoke host"
        marker_line="smoke host: marker scene"
        ;;
    *)
        echo "usage: resize-probe-gate.sh kn|graalvm [--report-only]" >&2
        exit 2
        ;;
esac
if [ "$report_only" = 1 ] && [ "$app" != graalvm ]; then
    echo "resize-probe-gate.sh: --report-only is only for graalvm; the $app window is the one a release ships, so its faults fail the run" >&2
    exit 2
fi

out="$(cygpath -u "$RUNNER_TEMP")/probe-logs"
status=0
faulty_drags=0
drags=0
window_faults=0
faulty_labels=0
# A fault of the window: fails the run, except under --report-only, where it is counted.
window_fault() {
    window_faults=$((window_faults + 1))
    [ "$report_only" = 1 ] || status=1
}
[ "${SCALE_STATUS:-}" = 0 ] || { echo "the display did not go to 200%"; status=1; }
grep -q "effective dpi 192" "$out/set-display-scale.log" || { echo "the display is not at 192 dpi"; status=1; }
labels=""
for app_path in "$app-new" "$app-old" control-late; do
    for edge in right bottom corner left; do
        for rate in 16 8 1; do labels="$labels $app_path-$edge-$rate"; done
    done
    labels="$labels $app_path-states"
done
states_runs=25
states_changes_per_run=4
states_limit_percent=10
for scale in 1x 2x; do
    want="$([ $scale = 1x ] && echo "dpi 96 (1.00x)" || echo "dpi 192 (2.00x)")"
    # The repeated runs of the window states on the CPU path, judged together by rate.
    runs=0; changes=0; faulty=0
    for run in $(seq 1 $states_runs); do
        label="$app-new-states"; [ "$run" = 1 ] || label="$app-new-states-r$run"
        probe="$out/$scale/$label-probe.log"
        line="$(grep -h "^resize-probe: states changes=" "$probe" 2>/dev/null | tail -1 || true)"
        if [ -z "$line" ]; then echo "$scale $label: no states count (the probe did not finish)"; continue; fi
        for state in maximise restore; do
            grep -qE "^resize-probe: state $state qpc=[0-9]+ sent" "$probe" || { echo "$scale $label: $state was not sent"; status=1; }
        done
        for state in left-half back; do
            grep -qE "^resize-probe: state $state qpc=.* set$" "$probe" || { echo "$scale $label: $state was not set"; status=1; }
        done
        runs=$((runs + 1))
        changes=$((changes + $(sed -n 's/.* changes=\([0-9]*\) .*/\1/p' <<<"$line")))
        faulty=$((faulty + $(sed -n 's/.* faulty=\([0-9]*\) .*/\1/p' <<<"$line")))
    done
    expected=$((states_runs * states_changes_per_run))
    echo "$scale $app-new-states: runs $runs/$states_runs, size changes $changes/$expected, faulty $faulty, limit $states_limit_percent% (at most $((changes * states_limit_percent / 100)))"
    [ "$changes" -ge "$expected" ] || { echo "  FAULT: fewer size changes were counted than were asked for"; status=1; }
    [ $((faulty * 100)) -le $((changes * states_limit_percent)) ] ||
        { echo "  FAULT: more than $states_limit_percent% of the window state changes showed a faulty frame (see the frames)"; window_fault; }
    for label in $labels; do
        dir="$out/$scale"
        probe="$dir/$label-probe.log"
        log="$dir/$label.all.log"
        [ -f "$log" ] || cat "$dir/$label.log" "$dir/$label.log.err" > "$log" 2>/dev/null
        faults_before=$window_faults
        verdict="$(grep -h "resize-probe: verdict" "$probe" 2>/dev/null || echo "no verdict")"
        echo "$scale $label: $verdict"
        case "$verdict" in *"verdict PASS"*|*"verdict FAIL"*) ;; *) echo "  the probe did not run"; status=1 ;; esac
        grep -q "^resize-probe: os windows [1-9]" "$probe" 2>/dev/null || { echo "  no Windows version in the probe log"; status=1; }
        grep -qF "px, $want" "$probe" 2>/dev/null || { echo "  the window was not at $want"; status=1; }
        grep -q "^resize-probe: markers at rest" "$probe" 2>/dev/null || { echo "  the markers were not found at rest"; status=1; }
        case "$label" in
            *-states)
                for state in maximise restore; do
                    grep -qE "^resize-probe: state $state qpc=[0-9]+ sent" "$probe" 2>/dev/null || { echo "  $state was not sent"; status=1; }
                done
                for state in left-half back; do
                    grep -qE "^resize-probe: state $state qpc=.* set$" "$probe" 2>/dev/null || { echo "  $state was not set"; status=1; }
                done
                ;;
            *)
                grep -q "move-size loop entered" "$probe" 2>/dev/null || { echo "  no move-size loop"; status=1; }
                grep -q "^resize-probe: lag frames=[1-9]" "$probe" 2>/dev/null || { echo "  no lag measured"; status=1; }
                ;;
        esac
        case "$label" in
            "$app"-new-*)
                # The window states are judged by rate above, every drag here.
                case "$label:$verdict" in
                    "$app-new-states":*|*"verdict PASS"*) ;;
                    *)
                        faulty_drags=$((faulty_drags + 1))
                        echo "  FAULT: the CPU resize path shows a fault (see the frames)"
                        window_fault ;;
                esac
                case "$label" in "$app-new-states") ;; *) drags=$((drags + 1)) ;; esac
                grep -qF "$marker_line" "$log" || { echo "  $who did not draw the marker scene"; status=1; }
                grep -qF "resize-mode: resize frames on the CPU (Skia raster, GDI blit)" "$log" ||
                    { echo "  $who did not take the CPU resize path"; window_fault; }
                steps="$(grep -c "resize-proof step" "$log" || true)"
                bad="$(grep "resize-proof step" "$log" | grep -vcE "presents=0 child_visible=0 blit=1 validated=1( at [0-9.]+ ms)?$" || true)"
                echo "  resize-proof steps: $steps, not blitted and validated: $bad"
                # The window states do not go through the move-size loop, so they draw no CPU
                # step: none is asked of them, and one that is there must still be good.
                case "$label" in
                    "$app-new-states") [ "$bad" = 0 ] || { echo "  FAULT: a CPU step was not blitted and validated"; window_fault; } ;;
                    *)
                        [ "$steps" -gt 0 ] || { echo "  FAULT: the drag drew no CPU step"; window_fault; }
                        [ "$bad" = 0 ] || { echo "  FAULT: a CPU step was not blitted and validated"; window_fault; }
                        ;;
                esac
                ;;
            "$app"-old-*)
                grep -qF "resize-mode: resize frames on the GPU" "$log" ||
                    { echo "  $who did not take the GPU resize path"; window_fault; }
                ! grep -q "resize-proof step" "$log" || { echo "  $who drew CPU steps with DXC_RASTER_RESIZE=0"; window_fault; }
                ;;
            control-late-*)
                case "$verdict" in
                    *"verdict FAIL"*) ;;
                    *) echo "  NO DISCRIMINATION: the 50 ms late control passed, so this runner cannot tell late frames from prompt ones here"; status=1 ;;
                esac
                steps="$(grep -c "resize-control: step n=" "$log" || true)"
                prompt="$(grep "resize-control: step n=" "$log" | awk '{ for (i = 1; i <= NF; i++) if ($i ~ /^late_ms=/) { split($i, v, "="); if (v[2] + 0 < 45) n++ } } END { print n + 0 }')"
                echo "  control steps: $steps, less than 45 ms late: $prompt"
                { [ "$steps" -gt 0 ] && [ "$prompt" = 0 ]; } || status=1
                ;;
        esac
        case "$label" in
            *-states) ;;
            "$app"-*) [ "$window_faults" -eq "$faults_before" ] || faulty_labels=$((faulty_labels + 1)) ;;
        esac
    done
done
echo "$RUNNER_OS_LABEL: $faulty_drags of $drags drags of the CPU resize path showed a fault ($app)"
[ "$drags" -gt 0 ] || { echo "  FAULT: no drag of the CPU resize path was judged"; status=1; }
if [ "$report_only" = 1 ]; then
    report="REPORT ONLY: $faulty_labels of $((drags * 2)) drags (new and old resize path) of the $app window showed a fault, $faulty_drags of $drags drags of the CPU resize path had a faulty frame, $window_faults faults in all. This renderer is reported and does not fail the run (compose-rust#687)."
    [ "$status" = 0 ] || report="$report The run still fails: the probe did not run as it should have, see the lines above."
    echo "$report"
    [ -z "${GITHUB_STEP_SUMMARY:-}" ] || { echo; echo "$RUNNER_OS_LABEL ($app): $report"; } >> "$GITHUB_STEP_SUMMARY"
fi
exit $status

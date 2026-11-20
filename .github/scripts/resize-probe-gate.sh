#!/usr/bin/env bash
# Usage: resize-probe-gate.sh <app>
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
set -o pipefail

app="${1:-}"
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
        echo "usage: resize-probe-gate.sh kn|graalvm" >&2
        exit 2
        ;;
esac

out="$(cygpath -u "$RUNNER_TEMP")/probe-logs"
status=0
faulty_drags=0
drags=0
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
        { echo "  FAULT: more than $states_limit_percent% of the window state changes showed a faulty frame (see the frames)"; status=1; }
    for label in $labels; do
        dir="$out/$scale"
        probe="$dir/$label-probe.log"
        log="$dir/$label.all.log"
        [ -f "$log" ] || cat "$dir/$label.log" "$dir/$label.log.err" > "$log" 2>/dev/null
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
                        status=1 ;;
                esac
                case "$label" in "$app-new-states") ;; *) drags=$((drags + 1)) ;; esac
                grep -qF "$marker_line" "$log" || { echo "  $who did not draw the marker scene"; status=1; }
                grep -qF "resize-mode: resize frames on the CPU (Skia raster, GDI blit)" "$log" ||
                    { echo "  $who did not take the CPU resize path"; status=1; }
                steps="$(grep -c "resize-proof step" "$log" || true)"
                bad="$(grep "resize-proof step" "$log" | grep -vcE "presents=0 child_visible=0 blit=1 validated=1( at [0-9.]+ ms)?$" || true)"
                echo "  resize-proof steps: $steps, not blitted and validated: $bad"
                # The window states do not go through the move-size loop, so they draw no CPU
                # step: none is asked of them, and one that is there must still be good.
                case "$label" in
                    "$app-new-states") [ "$bad" = 0 ] || { echo "  FAULT: a CPU step was not blitted and validated"; status=1; } ;;
                    *)
                        [ "$steps" -gt 0 ] || { echo "  FAULT: the drag drew no CPU step"; status=1; }
                        [ "$bad" = 0 ] || { echo "  FAULT: a CPU step was not blitted and validated"; status=1; }
                        ;;
                esac
                ;;
            "$app"-old-*)
                grep -qF "resize-mode: resize frames on the GPU" "$log" ||
                    { echo "  $who did not take the GPU resize path"; status=1; }
                ! grep -q "resize-proof step" "$log" || { echo "  $who drew CPU steps with DXC_RASTER_RESIZE=0"; status=1; }
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
    done
done
echo "$RUNNER_OS_LABEL: $faulty_drags of $drags drags of the CPU resize path showed a fault ($app)"
[ "$drags" -gt 0 ] || { echo "  FAULT: no drag of the CPU resize path was judged"; status=1; }
exit $status

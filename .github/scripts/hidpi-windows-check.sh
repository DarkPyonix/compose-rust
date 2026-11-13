#!/usr/bin/env bash
# Whether the Kotlin/Native Windows renderer draws in device pixels at every display scale and
# redraws at the new scale when the scale changes while it runs. On a Windows runner, from Git
# Bash; a hosted runner has no graphics card, so the window draws with WARP.
#
# Usage: hidpi-windows-check.sh <output directory> <set-display-scale.exe> <hidpi.exe> <hidpi.exe without the manifest>
#
# The checks are the renderer's own (renderer/desktop/src/HiDpiCheck.kt and
# renderer/windows/src/Fr82WindowsCheck.kt). The fixture compose-rust/tests/fixtures/hidpi, run
# with COMPOSE_RUST_FR82_CHECK=1, opens a 320 x 240 dp window with a marker scene (white, 1 dp
# black bars at whole dp, a circle, a rounded clip and "Hg"), copies its client area from the
# desktop DWM composed, and prints one line per check:
#
#   compose-rust: <check> scale=<s>: ok|FAILED <what it measured>
#
#   fr82_surface_is_device_pixels_at_every_scale     the swapchain buffer is the client area in
#                                                    pixels and the scene's density is
#                                                    GetDpiForWindow / 96
#   fr82_screen_matches_the_drawn_frame              the screen equals the frame drawn: a mean
#                                                    difference of at most 1 level, at most 0.1%
#                                                    of the pixels over 8 levels
#   fr82_one_dp_line_is_whole_device_pixels_at_integer_scales
#                                                    at 100% and 200%, each 1 dp bar is 1 or 2
#                                                    pixels of black with white beside it
#   fr82_fractional_scale_matches_offscreen_compose  at 125% and 150%, the screen equals the scene
#                                                    Compose draws off the screen at that density,
#                                                    and the scene drawn at 1x and stretched does not
#   fr82_curved_edges_are_antialiased                the circle and the clip's corners have
#                                                    antialiased edges 0.5 to 2 pixels wide
#   fr82_glyph_edges_are_not_blurred                 the strokes of "Hg" go from white to the
#                                                    stroke in at most 2 pixels
#   fr82_windows_process_is_per_monitor_v2_before_the_first_window
#                                                    the window's DPI awareness context is
#                                                    per-monitor v2, with and without the manifest
#   fr82_scale_change_redraws_at_the_new_scale       after the scale changed with no input, a frame
#                                                    at the new scale within 2 s, the window's dp
#                                                    size kept, the scene's density changed once
#
# Each scale is set with set-display-scale.exe before the program starts (100, 125, 150, 200),
# both builds run at each, and then one run starts at 200% and has the scale changed under it
# to 125% and back to 200%, which sends its window WM_DPICHANGED, the message a move to another
# monitor sends. Every check named for a scale has to say ok there; the step fails otherwise
# and lists what did not.
set -uo pipefail

if [[ $# -ne 4 ]]; then
    echo "usage: $0 <output directory> <set-display-scale.exe> <hidpi.exe> <hidpi.exe without the manifest>" >&2
    exit 2
fi
out="$1"
scale_tool="$2"
app="$3"
bare="$4"
mkdir -p "$out"
export DXC_D3D12_WARP="${DXC_D3D12_WARP:-1}"
failures=()

every=(
    fr82_surface_is_device_pixels_at_every_scale
    fr82_screen_matches_the_drawn_frame
    fr82_curved_edges_are_antialiased
    fr82_glyph_edges_are_not_blurred
    fr82_windows_process_is_per_monitor_v2_before_the_first_window
)
whole=fr82_one_dp_line_is_whole_device_pixels_at_integer_scales
fractional=fr82_fractional_scale_matches_offscreen_compose

# Sets the display to $1 percent; the rest are the tool's own options.
set_scale() {
    local percent="$1" log="$out/set-display-scale-$1.log" status=0
    "$scale_tool" "$@" > "$log" 2>&1 || status=$?
    cat "$log"
    if [[ "$status" -ne 0 ]]; then
        failures+=("the display did not go to $percent% (set-display-scale exit $status)")
        return 1
    fi
}

# How many times the run has said it is waiting for the scale to change.
waiting() {
    local count
    count="$(grep -c "^compose-rust: fr82 waiting for a scale change" "$1" 2>/dev/null)"
    echo "${count:-0}"
}

# Each named check said ok at that scale in that log.
expect() {
    local log="$1" scale="$2" name
    shift 2
    for name in "$@"; do
        grep -q "^compose-rust: $name scale=$scale: ok" "$log" ||
            failures+=("$name at $scale ($(basename "$log"))")
    done
}

# The checks a round at this scale runs.
expect_round() {
    local log="$1" scale="$2"
    expect "$log" "$scale" "${every[@]}"
    case "$scale" in
        1.00|2.00) expect "$log" "$scale" "$whole" ;;
        *) expect "$log" "$scale" "$fractional" ;;
    esac
}

# Says what the run printed and whether it ended well.
verdict() {
    local label="$1" log="$2" status="$3"
    echo "-- $label"
    grep -E "^compose-rust: (fr82|a window of our own)" "$log" || tail -40 "$log"
    [[ "$status" -eq 0 ]] || failures+=("$label ended with status $status")
    grep -q "^compose-rust: fr82 checks: ok" "$log" || failures+=("$label: not every check passed")
}

for step in "100 1.00" "125 1.25" "150 1.50" "200 2.00"; do
    read -r percent scale <<< "$step"
    echo "== $percent%"
    # The runner's 1024x768 offers 100% only, and 1920x1080 stops at 175%: --fit takes the
    # largest resolution that offers the scale.
    if [[ "$percent" == 100 ]]; then set_scale 100 || continue; else set_scale "$percent" --fit || continue; fi
    grep -q "effective dpi $((percent * 96 / 100))" "$out/set-display-scale-$percent.log" ||
        failures+=("set-display-scale did not report $((percent * 96 / 100)) dpi at $percent%")
    for build in manifest bare; do
        binary="$app"
        [[ "$build" == bare ]] && binary="$bare"
        log="$out/$build-$percent.log"
        status=0
        COMPOSE_RUST_FR82_CHECK=1 timeout 300 "$binary" --check > "$log" 2>&1 || status=$?
        verdict "$build build at $percent%" "$log" "$status"
        expect_round "$log" "$scale"
    done
done

echo "== 200% to 125% and back while it runs"
log="$out/live.log"
COMPOSE_RUST_FR82_CHECK=1 COMPOSE_RUST_FR82_SCALE_CHANGES=2 timeout 600 "$app" --check > "$log" 2>&1 &
pid=$!
asked=0
for percent in 125 200; do
    asked=$((asked + 1))
    waited=0
    while (($(waiting "$log") < asked)); do
        kill -0 "$pid" 2>/dev/null || break
        ((waited++ < 240)) || break
        sleep 0.5
    done
    # The same resolution: only the scale changes, as it does when a monitor's setting does.
    set_scale "$percent"
done
status=0
wait "$pid" || status=$?
verdict "the run whose scale changed" "$log" "$status"
expect_round "$log" 2.00
expect_round "$log" 1.25
expect "$log" 1.25 fr82_scale_change_redraws_at_the_new_scale
expect "$log" 2.00 fr82_scale_change_redraws_at_the_new_scale

echo
if [[ "${#failures[@]}" -ne 0 ]]; then
    echo "FAIL  these did not pass:"
    printf '        %s\n' "${failures[@]}"
    exit 1
fi
echo "ok    sharp at 100%, 125%, 150% and 200% with and without the manifest, and redrawn at the new scale when it changed"

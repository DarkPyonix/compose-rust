#!/usr/bin/env bash
# Whether the Kotlin/Native Linux renderer draws in device pixels at every Xft.dpi and redraws at
# the new scale when Xft.dpi changes while it runs. Under an X server (run it under xvfb-run),
# with xrdb.
#
# Usage: hidpi-linux-check.sh <output directory> <hidpi>
#
# The checks are the renderer's own (renderer/desktop/src/HiDpiCheck.kt and
# renderer/linux/src/Fr82LinuxCheck.kt), and the lines they print are the ones
# hidpi-windows-check.sh describes, less the Windows DPI awareness one. The scale is Xft.dpi over
# 96, set in the root window's resources with `xrdb -merge`: 96, 120, 144 and 192 before the
# program starts, and then one run starts at 192 and has it changed under it to 120 and back,
# which the window hears as a change of the root window's RESOURCE_MANAGER. The capture is
# XGetImage of the window: with no compositor that is what the screen shows.
set -uo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <output directory> <hidpi>" >&2
    exit 2
fi
out="$1"
app="$2"
mkdir -p "$out"
[[ -n "${DISPLAY:-}" ]] || { echo "FAIL no X display; run this under xvfb-run" >&2; exit 1; }
command -v xrdb > /dev/null || { echo "FAIL xrdb is not installed (x11-xserver-utils)" >&2; exit 1; }
failures=()

every=(
    fr82_surface_is_device_pixels_at_every_scale
    fr82_screen_matches_the_drawn_frame
    fr82_curved_edges_are_antialiased
    fr82_glyph_edges_are_not_blurred
)
whole=fr82_one_dp_line_is_whole_device_pixels_at_integer_scales
fractional=fr82_fractional_scale_matches_offscreen_compose

# Writes Xft.dpi into the root window's resources. -nocpp: the line needs no preprocessor.
set_dpi() {
    echo "Xft.dpi: $1" | xrdb -nocpp -merge
    local now
    now="$(xrdb -query | grep -i 'xft.dpi' || true)"
    echo "xrdb: ${now:-no Xft.dpi}"
    # A setting that did not stay would leave every run at scale 1 and pass for the wrong reason.
    [[ -n "$now" ]] || { echo "FAIL Xft.dpi $1 did not stay in the root window's resources (is Xvfb running with -noreset?)"; exit 1; }
}

# How many times the run has said it is waiting for the scale to change.
waiting() {
    local count
    count="$(grep -c "^compose-rust: fr82 waiting for a scale change" "$1" 2>/dev/null)"
    echo "${count:-0}"
}

expect() {
    local log="$1" scale="$2" name
    shift 2
    for name in "$@"; do
        grep -q "^compose-rust: $name scale=$scale: ok" "$log" ||
            failures+=("$name at $scale ($(basename "$log"))")
    done
}

expect_round() {
    local log="$1" scale="$2"
    expect "$log" "$scale" "${every[@]}"
    case "$scale" in
        1.00|2.00) expect "$log" "$scale" "$whole" ;;
        *) expect "$log" "$scale" "$fractional" ;;
    esac
}

verdict() {
    local label="$1" log="$2" status="$3"
    echo "-- $label"
    grep -E "^compose-rust: (fr82|a window of our own)" "$log" || tail -40 "$log"
    [[ "$status" -eq 0 ]] || failures+=("$label ended with status $status")
    grep -q "^compose-rust: fr82 checks: ok" "$log" || failures+=("$label: not every check passed")
}

for step in "96 1.00" "120 1.25" "144 1.50" "192 2.00"; do
    read -r dpi scale <<< "$step"
    echo "== Xft.dpi $dpi"
    set_dpi "$dpi"
    log="$out/dpi-$dpi.log"
    status=0
    COMPOSE_RUST_FR82_CHECK=1 timeout 300 "$app" --check > "$log" 2>&1 || status=$?
    verdict "Xft.dpi $dpi" "$log" "$status"
    expect_round "$log" "$scale"
done

echo "== Xft.dpi 192 to 120 and back while it runs"
set_dpi 192
log="$out/live.log"
COMPOSE_RUST_FR82_CHECK=1 COMPOSE_RUST_FR82_SCALE_CHANGES=2 timeout 600 "$app" --check > "$log" 2>&1 &
pid=$!
asked=0
for dpi in 120 192; do
    asked=$((asked + 1))
    waited=0
    while (($(waiting "$log") < asked)); do
        kill -0 "$pid" 2>/dev/null || break
        ((waited++ < 240)) || break
        sleep 0.5
    done
    set_dpi "$dpi"
done
status=0
wait "$pid" || status=$?
verdict "the run whose Xft.dpi changed" "$log" "$status"
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
echo "ok    sharp at Xft.dpi 96, 120, 144 and 192, and redrawn at the new scale when it changed"

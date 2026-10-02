#!/usr/bin/env bash
# Links the same minimal C host the desktop smoke test uses against the iOS static library,
# wraps it in an app bundle, and runs it on the simulator (after build-ios.sh).
#
# The host source is shared with the desktop test on purpose: `desktop/c/smoke_host.c` is the
# stand-in for the Rust Host, it implements the five `dioxus_compose_host_*` functions and
# calls `dioxus_compose_renderer_run` from `main`, and none of that changes on iOS. If the
# same file draws the same tree on both platforms, the C ABI really is the same.
#
# Usage: ios-smoke-test.sh [--device-name "iPhone 16"] [--screenshot <path>]
#                          [--await-click [seconds]] [--navigation]
#
# --navigation wraps the smoke host's screen in a navigation with two destinations. On
# iOS 26 that is what the renderer turns into the system's own tab bar and title bar, and
# the default tree has no navigation at all, so a screenshot taken without this flag shows
# nothing of that chrome.
#
# --await-click keeps the app up and waits for someone to tap the button, then
# checks that the tap reached the Rust handler. simctl has no tap command, so
# event dispatch on iOS cannot be checked without a person; CI runs without
# this flag and proves startup and rendering only.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_DIR="$(cd "$NATIVE_DIR/.." && pwd)"
BUNDLE_ID="dioxus.compose.smoke"

die() {
    echo "error: $1" >&2
    shift
    local line
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

device_name="iPhone 16"
screenshot=""
await_click=0
await_seconds=120
navigation=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --device-name) device_name="${2:-}"; shift 2 ;;
        --screenshot) screenshot="${2:-}"; shift 2 ;;
        --navigation) navigation=1; shift ;;
        --await-click)
            await_click=1
            if [[ "${2:-}" =~ ^[0-9]+$ ]]; then await_seconds="$2"; shift 2; else shift; fi
            ;;
        -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
        *) die "unknown argument '$1'" ;;
    esac
done

archive="$PROJECT_DIR/build/ios/simulator/libdioxus_compose_renderer.a"
[[ -f "$archive" ]] || die "$archive not found" \
    "fix: run $SCRIPT_DIR/build-ios.sh first"
[[ -f "$NATIVE_DIR/c/smoke_host.c" ]] || die "missing $NATIVE_DIR/c/smoke_host.c"
command -v xcrun >/dev/null 2>&1 || die "xcrun not found" "fix: xcode-select --install"

sdk_path="$(xcrun --sdk iphonesimulator --show-sdk-path)" ||
    die "the iphonesimulator SDK is not installed" \
        "fix: install Xcode, then xcodebuild -downloadPlatform iOS"

app="$PROJECT_DIR/build/ios/DioxusComposeSmoke.app"
rm -rf "$app"
mkdir -p "$app"

echo "==> linking the smoke host against the renderer"
# -export_dynamic keeps the host's five functions in the executable's dynamic symbol table.
# The renderer resolves them with dlsym at run time (see IosHostConnection.kt), and without
# this the linker has no reason to list functions that nothing in the image references.
xcrun clang \
    -target arm64-apple-ios15.0-simulator \
    -isysroot "$sdk_path" \
    -O2 \
    -o "$app/DioxusComposeSmoke" \
    "$NATIVE_DIR/c/smoke_host.c" \
    "$archive" \
    -Wl,-export_dynamic \
    -lc++ -lsqlite3 -lz \
    -framework UIKit -framework Foundation -framework CoreGraphics -framework CoreText \
    -framework CoreFoundation -framework QuartzCore -framework Metal -framework MetalKit \
    -framework CoreServices -framework ImageIO -framework UniformTypeIdentifiers \
    -framework AudioToolbox -framework AVFoundation -framework CoreMedia -framework Security

cat > "$app/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>DioxusComposeSmoke</string>
    <key>CFBundleIdentifier</key><string>$BUNDLE_ID</string>
    <key>CFBundleName</key><string>DioxusComposeSmoke</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSRequiresIPhoneOS</key><true/>
    <key>MinimumOSVersion</key><string>15.0</string>
    <key>UILaunchScreen</key><dict/>
    <!-- Compose for iOS refuses to start without it: it caps the frame rate at 60Hz. -->
    <key>CADisableMinimumFrameDurationOnPhone</key><true/>
    <key>UIDeviceFamily</key><array><integer>1</integer></array>
</dict>
</plist>
PLIST

# Every one of these pipelines can legitimately match nothing, and this script runs under
# `set -e` with `pipefail`, which turns "grep found no lines" into a silent abort with no
# message at all. That is not hypothetical: a runner image whose newest simulator was an
# iPhone 17 killed this script here, two seconds after a successful link, printing nothing,
# and the failure read as a linker problem for a day. `|| true` keeps the lookup a lookup,
# so the explanation below is the thing that actually reports the problem.
requested_device_name="$device_name"
available="$(xcrun simctl list devices available || true)"
device="$(printf '%s\n' "$available" | grep -F "$device_name (" | head -1 |
    sed -E 's/.*\(([-0-9A-F]{36})\).*/\1/' || true)"

if [[ -z "$device" ]]; then
    # Any iPhone will do: this test proves the C ABI and that the renderer draws, neither
    # of which depends on the model. Pinning a model that the runner image has since
    # dropped would fail for a reason that has nothing to do with the code under test.
    fallback_line="$(printf '%s\n' "$available" | grep -E '^    iPhone .*\(' | head -1 || true)"
    device="$(printf '%s\n' "$fallback_line" |
        sed -E 's/.*\(([-0-9A-F]{36})\).*/\1/' || true)"
    if [[ -n "$device" ]]; then
        device_name="$(printf '%s\n' "$fallback_line" | sed -E 's/^ *(.*) \(([-0-9A-F]{36})\).*/\1/')"
        echo "==> '$requested_device_name' is not available here, using $device_name" >&2
    fi
fi

[[ -n "$device" ]] || die "no iPhone simulator is available" \
    "Wanted '$requested_device_name' and found no iPhone at all. Available devices:" \
    "$(printf '%s\n' "$available")"

echo "==> booting $device_name ($device)"
xcrun simctl boot "$device" 2>/dev/null || true
xcrun simctl bootstatus "$device" -b >/dev/null

# `simctl boot` boots the device headless. Nothing is on screen until Simulator.app
# is running and showing this device, which is invisible to a screenshot-only run
# and the reason --await-click had nothing to tap.
if (( await_click )); then
    open -a Simulator --args -CurrentDeviceUDID "$device"
    for _ in $(seq 1 20); do
        pgrep -q -x Simulator && break
        sleep 1
    done
    pgrep -q -x Simulator || die "Simulator.app did not start, so there is no window to tap"
fi

echo "==> installing and launching"
xcrun simctl uninstall "$device" "$BUNDLE_ID" >/dev/null 2>&1 || true
xcrun simctl install "$device" "$app"
log="$PROJECT_DIR/build/ios/smoke-console.log"
# simctl passes anything prefixed SIMCTL_CHILD_ through to the app it launches.
if (( navigation )); then
    export SIMCTL_CHILD_DIOXUS_COMPOSE_SMOKE_NAVIGATION=1
fi
xcrun simctl launch --console-pty --terminate-running-process "$device" "$BUNDLE_ID" \
    >"$log" 2>&1 &
launch_pid=$!

# The renderer never returns from run() on iOS (UIApplicationMain owns the process), so the
# test waits for the window to come up rather than for the process to exit.
for _ in $(seq 1 30); do
    grep -q "dioxus_compose_host_init" "$log" 2>/dev/null && break
    sleep 1
done
sleep 3

if (( await_click )); then
    echo
    echo "==> tap 'click me' in the simulator (waiting up to ${await_seconds}s)"
    for _ in $(seq 1 "$await_seconds"); do
        grep -q "dispatch_event" "$log" 2>/dev/null && break
        sleep 1
    done
fi

if [[ -n "$screenshot" ]]; then
    xcrun simctl io "$device" screenshot "$screenshot"
    echo "screenshot: $screenshot"
fi

kill "$launch_pid" 2>/dev/null || true
xcrun simctl terminate "$device" "$BUNDLE_ID" >/dev/null 2>&1 || true

echo
cat "$log"
grep -q "dioxus_compose_host_init" "$log" || die \
    "the renderer never called dioxus_compose_host_init" \
    "Console log: $log"
if (( await_click )); then
    grep -q "dispatch_event" "$log" || die \
        "no tap reached the host within ${await_seconds}s" \
        "Either nobody tapped the button, or hit testing does not reach dioxus_compose_host_dispatch_event on iOS." \
        "Console log: $log"
    echo
    echo "ok: a tap reached the host handler on iOS"
fi

echo
echo "ok: the renderer ran on the simulator and called back into the host"

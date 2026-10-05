#!/usr/bin/env bash
# Both macOS title bar styles, opened as real windows, measured the way each renderer
# measures them.
#
# A window can ask for the plain title bar (no toolbar) or the toolbar one. macOS gives the
# two different corner radii, bar heights and button positions, and every one of those
# numbers is the system's: the renderers read them from the window instead of keeping a
# design system constant, because a constant is right for one style on one release.
#
# Both windows are the Compose fork's (extended/window), fetched at the pinned revision.
# The native image's window is `appkit_window.m`. The Kotlin/Native one is `MacosWindow.kt`,
# which this script cannot compile, so its `applyChrome` is checked line for line against
# the window built here in its place. Each style is opened twice, once by the C window and
# once the Kotlin/Native way, and the two must report the same geometry: frame height,
# the height below the bar, where the buttons start and end, and the corner radius.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fork_window="$("$repo_root/scripts/fetch-fork-window.sh")/extended/window" ||
    { echo "FAIL: could not fetch the fork's window modules"; exit 1; }
source_dir="$fork_window/graalvm/graalvm-macos/native"
native_kt="$fork_window/native/macos/src/org/thisisthepy/compose/window/macos/MacosWindow.kt"
chrome_kt="$repo_root/renderer/desktop/src/MacosWindowChrome.kt"
status=0
fail() { echo "FAIL: $1"; status=1; }

for file in "$source_dir/appkit_window.m" "$native_kt" "$chrome_kt"; do
    [[ -f "$file" ]] || { echo "FAIL: $file is missing"; exit 1; }
done

# Both windows read the radius under one key, and neither keeps a number of its own.
key="$(sed -n 's/^const val MACOS_CORNER_RADIUS_KEY: String = "\(.*\)"$/\1/p' "$chrome_kt")"
[[ -n "$key" ]] || fail "MacosWindowChrome.kt names no key for the corner radius"
grep -q "valueForKey:@\"$key\"" "$source_dir/appkit_window.m" ||
    fail "the C window reads the corner radius under a different key from $key"
grep -q 'MACOS_CORNER_RADIUS_KEY' "$native_kt" ||
    fail "the Kotlin/Native window does not read the corner radius under the shared key"
for rules in "$repo_root/renderer/desktop/src/renderer/ComponentRules.kt" \
    "$repo_root/renderer/desktop/src/renderer/DesignSystem.kt"; do
    if grep -q 'windowCornerRadius\|platformButtonInset' "$rules"; then
        fail "$(basename "$rules") still carries a window radius or button inset of its own"
    fi
done

# What the stand-in below does to a window must be what MacosWindow.kt's applyChrome does.
apply_body="$(awk '/^internal fun applyChrome\(/{on=1} on{print} on && /^}/{exit}' "$native_kt")"
for line in \
    'window.titlebarAppearsTransparent = chrome.titlebarAppearsTransparent' \
    'window.titleVisibility = if (chrome.titleHidden) NSWindowTitleHidden else NSWindowTitleVisible' \
    'toolbar.showsBaselineSeparator = false' \
    'window.toolbar = toolbar' \
    'NSWindowToolbarStyleUnified' \
    'window.toolbar = null'; do
    grep -qF "$line" <<< "$apply_body" ||
        fail "MacosWindow.kt's applyChrome no longer does '$line', so the stand-in here is stale"
done
[[ "$(grep -c 'window\.' <<< "$apply_body")" -eq 5 ]] ||
    fail "MacosWindow.kt's applyChrome sets something the stand-in here does not"

if [[ "$(uname -s)" != Darwin ]]; then
    [[ $status -eq 0 ]] && echo "skipped the windows: AppKit is only available on macOS"
    exit $status
fi

mkdir -p "$repo_root/.scratch"
work="$(mktemp -d "$repo_root/.scratch/macos-window-styles.XXXXXX")"
trap 'rm -rf "$work"' EXIT

cat > "$work/styles.m" <<'EOF'
#include "appkit_window.m"

// The five numbers each renderer hands macosWindowCaption.
struct geometry { float frame, layout, close, zoom, radius; };

// The Kotlin/Native window: its style mask, then MacosWindow.kt's applyChrome.
static struct geometry kotlin_native(int toolbar) {
    NSWindow *window = [[NSWindow alloc]
        initWithContentRect:NSMakeRect(0, 0, 480, 640)
                  styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskMiniaturizable |
                            NSWindowStyleMaskClosable | NSWindowStyleMaskResizable |
                            NSWindowStyleMaskFullSizeContentView
                    backing:NSBackingStoreBuffered
                      defer:NO];
    window.releasedWhenClosed = NO;
    window.titlebarAppearsTransparent = YES;
    window.titleVisibility = NSWindowTitleHidden;
    if (toolbar) {
        NSToolbar *bar = [[NSToolbar alloc] initWithIdentifier:@"compose-rust"];
        bar.showsBaselineSeparator = NO;
        window.toolbar = bar;
        window.toolbarStyle = NSWindowToolbarStyleUnified;
    } else {
        window.toolbar = nil;
    }
    [window orderFront:nil];
    dxc_native_pump(0.1);
    NSButton *close = [window standardWindowButton:NSWindowCloseButton];
    NSButton *zoom = [window standardWindowButton:NSWindowZoomButton];
    struct geometry g = {
        (float)window.frame.size.height,
        (float)window.contentLayoutRect.size.height,
        close ? (float)close.frame.origin.x : -1,
        zoom ? (float)NSMaxX(zoom.frame) : -1,
        (float)dxc_window_corner_radius(window),
    };
    [window close];
    return g;
}

// The native image's window, built by appkit_window.m from the same chrome.
static int native_image(int toolbar, struct geometry *g) {
    dxc_native_window_chrome(1, 1, 1, toolbar);
    struct dxc_native_window native = {0};
    if (dxc_native_window_open("title bar styles", 480, 640, &native) != 0) return 1;
    dxc_native_pump(0.1);
    float out[5];
    dxc_native_window_title_bar(native.view, out);
    *g = (struct geometry){out[0], out[1], out[2], out[3], out[4]};
    [(__bridge NSWindow *)native.window close];
    return 0;
}

int main(void) {
    @autoreleasepool {
        const char *names[] = {"simple", "toolbar"};
        struct geometry c[2], k[2];
        for (int toolbar = 0; toolbar < 2; toolbar++) {
            if (native_image(toolbar, &c[toolbar]) != 0) {
                puts("SKIP this machine has no Metal device to open the window with");
                return 3;
            }
            k[toolbar] = kotlin_native(toolbar);
            printf("%s c %.1f %.1f %.1f %.1f %.1f k %.1f %.1f %.1f %.1f %.1f\n",
                   names[toolbar],
                   c[toolbar].frame, c[toolbar].layout, c[toolbar].close, c[toolbar].zoom,
                   c[toolbar].radius,
                   k[toolbar].frame, k[toolbar].layout, k[toolbar].close, k[toolbar].zoom,
                   k[toolbar].radius);
        }
        printf("os %ld\n", (long)NSProcessInfo.processInfo.operatingSystemVersion.majorVersion);
        return 0;
    }
}
EOF

cc -Wno-deprecated-declarations -fobjc-arc -fblocks -I "$source_dir" \
    "$work/styles.m" -framework AppKit -framework Carbon -framework CoreGraphics \
    -framework Metal -framework QuartzCore -o "$work/styles" ||
    { echo "FAIL: the probe did not compile"; exit 1; }

run_status=0
output="$("$work/styles")" || run_status=$?
echo "$output"
if [[ $run_status -eq 3 ]]; then
    [[ $status -eq 0 ]] && echo "skipped the windows: ${output#SKIP }"
    exit $status
fi
[[ $run_status -eq 0 ]] || { echo "FAIL: the probe exited $run_status"; exit 1; }

read -r _ _ s_frame s_layout s_close s_zoom s_radius _ ks_frame ks_layout ks_close ks_zoom ks_radius \
    <<< "$(grep '^simple ' <<< "$output")"
read -r _ _ t_frame t_layout t_close t_zoom t_radius _ kt_frame kt_layout kt_close kt_zoom kt_radius \
    <<< "$(grep '^toolbar ' <<< "$output")"
os="$(sed -n 's/^os //p' <<< "$output")"

# fr19_7_both_paths_open_the_same_window_per_style
[[ "$s_frame $s_layout $s_close $s_zoom $s_radius" == "$ks_frame $ks_layout $ks_close $ks_zoom $ks_radius" ]] ||
    fail "fr19_7 the simple title bar differs between the native image and Kotlin/Native windows"
[[ "$t_frame $t_layout $t_close $t_zoom $t_radius" == "$kt_frame $kt_layout $kt_close $kt_zoom $kt_radius" ]] ||
    fail "fr19_7 the toolbar title bar differs between the native image and Kotlin/Native windows"

# fr19_7_the_content_top_follows_the_style
awk -v t="$t_frame" -v tl="$t_layout" -v s="$s_frame" -v sl="$s_layout" \
    'BEGIN { exit !((t - tl) > (s - sl) && (s - sl) > 0) }' ||
    fail "fr19_7 the toolbar bar is not taller than the plain one ($t_frame-$t_layout against $s_frame-$s_layout)"

# fr19_7_the_radius_is_the_systems
awk -v s="$s_radius" -v t="$t_radius" 'BEGIN { exit !(s > 0 && t >= s) }' ||
    fail "fr19_7 the system reported no radius, or a toolbar window rounder by less than a plain one ($t_radius against $s_radius)"
# macOS 26 is the release that rounds a toolbar window more and sets its buttons further in.
if [[ "${os:-0}" -ge 26 ]]; then
    awk -v s="$s_radius" -v t="$t_radius" 'BEGIN { exit !(t > s) }' ||
        fail "fr19_7 on macOS $os the toolbar window is not rounder than the plain one"
    awk -v s="$s_close" -v t="$t_close" 'BEGIN { exit !(t > s) }' ||
        fail "fr19_7 on macOS $os the toolbar window's buttons are not further in"
fi

[[ $status -eq 0 ]] && echo "both title bar styles open the same window on both macOS paths: ok"
exit $status

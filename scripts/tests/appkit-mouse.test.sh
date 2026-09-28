#!/usr/bin/env bash
# Exercise foreground registration and AppKit mouse dispatch without building the renderer.
set -euo pipefail

if [[ "$(uname -s)" != Darwin ]]; then
    echo 'skipped: AppKit is only available on macOS'
    exit 0
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_dir="${DXC_APPKIT_SOURCE_DIR:-$repo_root/dioxus-compose-renderer/desktop/c}"
work="$(mktemp -d "$repo_root/.appkit-mouse-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

cat > "$work/mouse.m" <<'EOF'
#import <AppKit/AppKit.h>
#import <Carbon/Carbon.h>
#include <stdio.h>

static int foreground_registrations;
static OSStatus trace_process_transform(ProcessSerialNumber *process,
                                        ProcessApplicationTransformState state) {
    if (state == kProcessTransformToForegroundApplication) foreground_registrations++;
    return TransformProcessType(process, state);
}
#define TransformProcessType trace_process_transform
#include "appkit_window.m"
#undef TransformProcessType

int main(void) {
    @autoreleasepool {
        struct dxc_native_window native = {0};
        if (dxc_native_window_open("mouse delivery test", 320, 240, &native) != 0) {
            fprintf(stderr, "could not open AppKit window\n");
            return 1;
        }
        if (foreground_registrations != 1) {
            fprintf(stderr, "native window did not register its process as foreground\n");
            return 1;
        }

        ProcessSerialNumber process = {0, kCurrentProcess};
        ProcessInfoRec info = {0};
        info.processInfoLength = sizeof(info);
        if (GetProcessInformation(&process, &info) != noErr ||
            (info.processMode & modeOnlyBackground) != 0) {
            fprintf(stderr, "window process remains background-only\n");
            return 1;
        }
        NSWindow *window = (__bridge NSWindow *)native.window;
        for (int attempt = 0; attempt < 100 && (!NSApp.isActive || !window.isKeyWindow);
             attempt++) {
            dxc_native_pump(0.02);
        }
        if (!NSApp.isActive || !window.isKeyWindow) {
            fprintf(stderr, "AppKit window did not become active and key\n");
            return 1;
        }
        NSPoint center = NSMakePoint(NSMidX(window.contentView.bounds),
                                     NSMidY(window.contentView.bounds));
        NSEvent *down = [NSEvent mouseEventWithType:NSEventTypeLeftMouseDown
                                         location:center modifierFlags:0 timestamp:0
                                     windowNumber:window.windowNumber context:nil
                                      eventNumber:1 clickCount:1 pressure:1];
        NSEvent *up = [NSEvent mouseEventWithType:NSEventTypeLeftMouseUp
                                       location:center modifierFlags:0 timestamp:0
                                   windowNumber:window.windowNumber context:nil
                                    eventNumber:2 clickCount:1 pressure:0];
        [NSApp postEvent:down atStart:NO];
        [NSApp postEvent:up atStart:NO];

        bool pressed = false;
        bool released = false;
        for (int attempt = 0; attempt < 100 && !(pressed && released); attempt++) {
            dxc_native_pump(0.02);
            struct dxc_event event;
            while (dxc_native_poll_event(&event)) {
                pressed |= event.kind == DXC_EVENT_POINTER_DOWN;
                released |= event.kind == DXC_EVENT_POINTER_UP;
            }
        }
        if (!pressed || !released) {
            fprintf(stderr, "AppKit did not dispatch the posted mouse press and release\n");
            return 1;
        }
        puts("ok: foreground registration and AppKit mouse dispatch");
        return 0;
    }
}
EOF

cc -Wno-deprecated-declarations -fobjc-arc -fblocks -I "$source_dir" \
    "$work/mouse.m" -framework AppKit -framework Carbon -framework Metal \
    -framework QuartzCore -o "$work/mouse"
"$work/mouse"

#!/usr/bin/env bash
# Exercise window-server mouse routing into the AppKit window without building the renderer.
set -euo pipefail

if [[ "$(uname -s)" != Darwin ]]; then
    echo 'skipped: AppKit is only available on macOS'
    exit 0
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_dir="${DXC_APPKIT_SOURCE_DIR:-$repo_root/renderer/desktop/c}"
work="$(mktemp -d "$repo_root/.appkit-mouse-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

cat > "$work/mouse.m" <<'EOF'
#import <AppKit/AppKit.h>
#import <Carbon/Carbon.h>
#import <CoreGraphics/CoreGraphics.h>
#include <stdio.h>
#include <spawn.h>
#include <sys/wait.h>
extern char **environ;

static int foreground_registrations;
static OSStatus trace_process_transform(ProcessSerialNumber *process,
                                        ProcessApplicationTransformState state) {
    if (state == kProcessTransformToForegroundApplication) foreground_registrations++;
    return TransformProcessType(process, state);
}
#define TransformProcessType trace_process_transform
#include "appkit_window.m"
#undef TransformProcessType

int main(int argc, const char **argv) {
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
        void *texture_pointer = NULL;
        if (dxc_native_frame_begin(native.layer, &texture_pointer) != 0) {
            fprintf(stderr, "could not acquire Metal drawable\n");
            return 1;
        }
        id<MTLTexture> texture = (__bridge id<MTLTexture>)texture_pointer;
        size_t pixel_count = texture.width * texture.height;
        uint32_t *pixels = calloc(pixel_count, sizeof(uint32_t));
        for (size_t index = 0; index < pixel_count; index++) pixels[index] = 0xff336699;
        [texture replaceRegion:MTLRegionMake2D(0, 0, texture.width, texture.height)
                  mipmapLevel:0 withBytes:pixels bytesPerRow:texture.width * 4];
        free(pixels);
        dxc_native_frame_end(native.queue);
        dxc_native_pump(0.05);
        NSPoint center = NSMakePoint(NSMidX(window.contentView.bounds),
                                     NSMidY(window.contentView.bounds));
        NSPoint screen = [window convertPointToScreen:
            [window.contentView convertPoint:center toView:nil]];
        CGPoint quartz = CGPointMake(screen.x,
            CGRectGetMaxY(CGDisplayBounds(CGMainDisplayID())) - screen.y);
        char x[32], y[32];
        snprintf(x, sizeof x, "%.3f", quartz.x);
        snprintf(y, sizeof y, "%.3f", quartz.y);
        pid_t child;
        const char *args[] = {argv[1], x, y, NULL};
        if (posix_spawn(&child, argv[1], NULL, NULL, (char *const *)args, environ) != 0) {
            fprintf(stderr, "could not launch HID event poster\n");
            return 1;
        }

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
        int child_status = 0;
        waitpid(child, &child_status, 0);
        if (!WIFEXITED(child_status) || WEXITSTATUS(child_status) != 0 ||
            !pressed || !released) {
            fprintf(stderr, "AppKit did not receive the HID mouse press and release: active=%d key=%d pressed=%d released=%d\n",
                    NSApp.isActive, window.isKeyWindow, pressed, released);
            return 1;
        }
        puts("ok: foreground registration and HID mouse dispatch");
        return 0;
    }
}
EOF

cat > "$work/poster.m" <<'EOF'
#import <CoreGraphics/CoreGraphics.h>
#include <stdlib.h>
#include <unistd.h>

int main(int argc, const char **argv) {
    CGPoint point = CGPointMake(atof(argv[1]), atof(argv[2]));
    if (!CGPreflightPostEventAccess()) return 2;
    const CGEventType types[] = {
        kCGEventMouseMoved, kCGEventLeftMouseDown, kCGEventLeftMouseUp
    };
    for (int index = 0; index < 3; index++) {
        CGEventRef event = CGEventCreateMouseEvent(NULL, types[index], point,
                                                   kCGMouseButtonLeft);
        CGEventPost(kCGHIDEventTap, event);
        CFRelease(event);
        // The window server must see the down before the up. Back-to-back posts can
        // discard the release before any application's event queue sees it. Set
        // DXC_UNPACED_CLICK=1 to reproduce the original external poster's timing.
        if (index == 1 && getenv("DXC_UNPACED_CLICK") == NULL) usleep(20000);
    }
    return 0;
}
EOF

cc -Wno-deprecated-declarations -fobjc-arc -fblocks -I "$source_dir" \
    "$work/mouse.m" -framework AppKit -framework Carbon -framework CoreGraphics -framework Metal \
    -framework QuartzCore -o "$work/mouse"
cc "$work/poster.m" -framework CoreGraphics -framework CoreFoundation -o "$work/poster"
"$work/mouse" "$work/poster"

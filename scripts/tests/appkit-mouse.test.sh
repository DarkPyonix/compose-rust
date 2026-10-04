#!/usr/bin/env bash
# Exercise window-server mouse routing into the AppKit window without building the renderer.
#
# Two programs are judged, in order. The first is a control: an ordinary AppKit window
# with nothing of ours in it, driven by the application's own run loop, sent the same
# click by the same poster. It answers a question about the machine rather than about the
# renderer: can a process started from here take the foreground and be sent a click at
# all? Where it cannot, nothing the second program does would mean anything, so the test
# says skipped and why. Where it can, the renderer's window gets the same click, and any
# difference between the two is ours.
#
# The skip is decided before the renderer's window is opened, never from its failure. A
# test that turned its own red into a skip would pass on every machine, including the
# ones where the renderer is broken.
set -euo pipefail

if [[ "$(uname -s)" != Darwin ]]; then
    echo 'skipped: AppKit is only available on macOS'
    exit 0
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ -z "${DXC_APPKIT_SOURCE_DIR:-}" ]]; then
    # The window is the Compose fork's graalvm-macos module, at the commit the renderer pins.
    source "$repo_root/scripts/tests/fork-window.sh"
    fork_window_or_skip "$repo_root"
fi
source_dir="${DXC_APPKIT_SOURCE_DIR:-$fork_window/graalvm/graalvm-macos/native}"
work="$(mktemp -d "$repo_root/.appkit-mouse-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

cat > "$work/probe.m" <<'EOF'
// The control. Nothing from the renderer is compiled in, and the loop is AppKit's own
// `-run`, so a click this window misses is a click the machine never delivered.
#import <AppKit/AppKit.h>
#import <Carbon/Carbon.h>
#import <CoreGraphics/CoreGraphics.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <sys/wait.h>
extern char **environ;

// Exit codes: 0 when the machine can run the real test, 3 when it cannot (with the reason
// on stdout), anything else when the probe itself went wrong, which is a failure.
enum { PROBE_OK = 0, PROBE_BROKEN = 1, PROBE_SKIP = 3 };

static int presses;
static int releases;

@interface ProbeView : NSView
@end

@implementation ProbeView
- (BOOL)acceptsFirstMouse:(NSEvent *)event { return YES; }
- (void)mouseDown:(NSEvent *)event { presses++; }
- (void)mouseUp:(NSEvent *)event { releases++; }
@end

static int verdict = PROBE_BROKEN;
static char reason[256] = "the probe stopped before it reached a verdict";
static bool finishing;

static void finish(int code) {
    // The timer can fire again before `-run` returns; the first verdict is the one.
    if (finishing) return;
    finishing = true;
    verdict = code;
    [NSApp stop:nil];
    // `-stop:` takes effect after the next event, and a timer firing is not one. This is.
    [NSApp postEvent:[NSEvent otherEventWithType:NSEventTypeApplicationDefined
                                        location:NSZeroPoint
                                   modifierFlags:0
                                       timestamp:0
                                    windowNumber:0
                                         context:nil
                                         subtype:0
                                           data1:0
                                           data2:0]
             atStart:NO];
}

int main(int argc, const char **argv) {
    @autoreleasepool {
        // A process outside the console's window server session (ssh, a launch daemon, a
        // fast-user-switched login) can open no window anyone sees, and the window server
        // sends it nothing.
        CFDictionaryRef session = CGSessionCopyCurrentDictionary();
        if (session == NULL) {
            puts("no window server session for this process");
            return PROBE_SKIP;
        }
        CFBooleanRef on_console = CFDictionaryGetValue(session, kCGSessionOnConsoleKey);
        bool console = on_console != NULL && CFBooleanGetValue(on_console);
        CFRelease(session);
        if (!console) {
            puts("this process's window server session is not the one on the console");
            return PROBE_SKIP;
        }
        // Posting into the HID stream is a privacy permission granted to whatever launched
        // this. Without it the poster has nothing to send.
        if (!CGPreflightPostEventAccess()) {
            puts("this process is not allowed to post HID events");
            return PROBE_SKIP;
        }

        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];

        __block NSWindow *window = nil;
        __block pid_t child = 0;
        __block bool poster_done = false;
        __block int poster_status = 0;
        __block NSDate *stage_deadline = nil;
        __block NSDate *settle = nil;
        NSDate *started = NSDate.date;

        // Driven from a timer so that every step happens inside `-run`, after launch,
        // the way an application's own code would.
        [NSTimer scheduledTimerWithTimeInterval:0.02 repeats:YES block:^(NSTimer *timer) {
            if (finishing) return;
            NSDate *now = NSDate.date;
            if (window == nil) {
                // The same requests the renderer's window makes of the system, so a
                // refusal here is a refusal the renderer would get too.
                ProcessSerialNumber process = {0, kCurrentProcess};
                TransformProcessType(&process, kProcessTransformToForegroundApplication);
                NSRect frame = NSMakeRect(0, 0, 320, 240);
                window = [[NSWindow alloc] initWithContentRect:frame
                                                     styleMask:NSWindowStyleMaskTitled
                                                       backing:NSBackingStoreBuffered
                                                         defer:NO];
                window.releasedWhenClosed = NO;
                window.contentView = [[ProbeView alloc] initWithFrame:frame];
                [window center];
                [window makeKeyAndOrderFront:nil];
                [NSApp activateIgnoringOtherApps:YES];
                // The same two seconds the real test gives its own window.
                stage_deadline = [now dateByAddingTimeInterval:2];
                return;
            }
            if (child == 0) {
                if (!NSApp.isActive || !window.isKeyWindow) {
                    if ([now compare:stage_deadline] != NSOrderedAscending) {
                        snprintf(reason, sizeof reason,
                                 "no process started from here can take the foreground: "
                                 "an ordinary AppKit window did not become active and key "
                                 "(active=%d key=%d)",
                                 NSApp.isActive, window.isKeyWindow);
                        finish(PROBE_SKIP);
                    }
                    return;
                }
                NSView *content = window.contentView;
                NSPoint center = NSMakePoint(NSMidX(content.bounds), NSMidY(content.bounds));
                NSPoint screen = [window convertPointToScreen:
                    [content convertPoint:center toView:nil]];
                CGPoint quartz = CGPointMake(screen.x,
                    CGRectGetMaxY(CGDisplayBounds(CGMainDisplayID())) - screen.y);
                char x[32], y[32];
                snprintf(x, sizeof x, "%.3f", quartz.x);
                snprintf(y, sizeof y, "%.3f", quartz.y);
                const char *args[] = {argv[1], x, y, NULL};
                if (posix_spawn(&child, argv[1], NULL, NULL, (char *const *)args,
                                environ) != 0) {
                    snprintf(reason, sizeof reason, "could not launch the HID event poster");
                    finish(PROBE_BROKEN);
                    return;
                }
                stage_deadline = [now dateByAddingTimeInterval:10];
                return;
            }
            if (!poster_done && waitpid(child, &poster_status, WNOHANG) == child) {
                poster_done = true;
                // The window server may still be handing over what the poster sent after
                // the poster has gone. A second is long past any delivery that will come.
                settle = [now dateByAddingTimeInterval:1];
            }
            bool settled = settle != nil && [now compare:settle] != NSOrderedAscending;
            bool expired = [now compare:stage_deadline] != NSOrderedAscending;
            if ((presses > 0 && releases > 0 && poster_done) || settled || expired) {
                finish(PROBE_OK);
            }
        }];
        [NSApp run];

        if (verdict != PROBE_OK) {
            puts(reason);
            if (child != 0 && !poster_done) {
                kill(child, SIGKILL);
                waitpid(child, &poster_status, 0);
            }
            return verdict;
        }
        if (!poster_done) {
            kill(child, SIGKILL);
            waitpid(child, &poster_status, 0);
            printf("the HID event poster did not finish within %.0f seconds\n",
                   [NSDate.date timeIntervalSinceDate:started]);
            return PROBE_BROKEN;
        }
        if (WIFEXITED(poster_status) && WEXITSTATUS(poster_status) == 2) {
            // The poster asks for itself, and the system may answer it differently from
            // the probe that launched it.
            puts("the HID event poster is not allowed to post HID events");
            return PROBE_SKIP;
        }
        if (!WIFEXITED(poster_status) || WEXITSTATUS(poster_status) != 0) {
            printf("the HID event poster failed (status %d)\n", poster_status);
            return PROBE_BROKEN;
        }
        if (presses == 0 || releases == 0) {
            printf("this window server does not deliver a posted HID click to a foreground "
                   "window: an ordinary AppKit window saw presses=%d releases=%d\n",
                   presses, releases);
            return PROBE_SKIP;
        }
        return PROBE_OK;
    }
}
EOF

cat > "$work/mouse.m" <<'EOF'
#import <AppKit/AppKit.h>
#import <Carbon/Carbon.h>
#import <CoreGraphics/CoreGraphics.h>
#include <signal.h>
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

        // Timed from the poster's exit, not from its launch. The first run of a binary
        // linked a moment ago can spend seconds being looked at by the system before its
        // first line runs, and a budget counted from the launch would end before the
        // release was ever sent, reading as a window that lost it.
        bool pressed = false;
        bool released = false;
        bool poster_done = false;
        int child_status = 0;
        NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:10];
        NSDate *settle = nil;
        while (!(pressed && released && poster_done)) {
            dxc_native_pump(0.02);
            struct dxc_event event;
            while (dxc_native_poll_event(&event)) {
                pressed |= event.kind == DXC_EVENT_POINTER_DOWN;
                released |= event.kind == DXC_EVENT_POINTER_UP;
            }
            if (!poster_done && waitpid(child, &child_status, WNOHANG) == child) {
                poster_done = true;
                settle = [NSDate dateWithTimeIntervalSinceNow:1];
            }
            NSDate *now = NSDate.date;
            if ((settle != nil && [now compare:settle] != NSOrderedAscending) ||
                [now compare:deadline] != NSOrderedAscending) {
                break;
            }
        }
        if (!poster_done) {
            kill(child, SIGKILL);
            waitpid(child, &child_status, 0);
            fprintf(stderr, "the HID event poster did not finish\n");
            return 1;
        }
        if (!WIFEXITED(child_status) || WEXITSTATUS(child_status) != 0) {
            fprintf(stderr, "the HID event poster failed (status %d)\n", child_status);
            return 1;
        }
        if (!pressed || !released) {
            // The control window was sent the same click on this machine moments ago and
            // heard both halves of it, so this is the renderer's window, not the machine.
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

cc -Wno-deprecated-declarations -fobjc-arc -fblocks \
    "$work/probe.m" -framework AppKit -framework Carbon -framework CoreGraphics \
    -o "$work/probe"
cc -Wno-deprecated-declarations -fobjc-arc -fblocks -I "$source_dir" \
    "$work/mouse.m" -framework AppKit -framework Carbon -framework CoreGraphics -framework Metal \
    -framework QuartzCore -o "$work/mouse"
cc "$work/poster.m" -framework CoreGraphics -framework CoreFoundation -o "$work/poster"

# `set -e` would end the script on the probe's skip code before it could be read.
probe_status=0
probe_output="$("$work/probe" "$work/poster")" || probe_status=$?
case "$probe_status" in
    0) ;;
    3)
        echo "skipped: $probe_output"
        exit 0
        ;;
    *)
        echo "the control window's probe failed (exit $probe_status): $probe_output" >&2
        exit 1
        ;;
esac

"$work/mouse" "$work/poster"

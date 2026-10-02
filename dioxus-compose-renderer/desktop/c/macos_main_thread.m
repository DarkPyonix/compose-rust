/*
 * macOS main-thread handling for the renderer.
 *
 * AppKit must run on the main thread, and AWT waits for it there. If Java code occupies the
 * main thread the process hangs, so the renderer runs on a second thread (as the JDK
 * launcher does) and the main thread runs AppKit.
 *
 * The main thread creates and runs NSApplication itself instead of letting AWT do it. AWT
 * then starts in embedded mode, the way it does inside an SWT or JavaFX host. Left to
 * itself, AWT would enter `[NSApp run]` in a loop that never returns, and the Host would
 * never get control back after the window closes.
 */
#import <AppKit/AppKit.h>
#include <stdatomic.h>

/* Called on the main thread before the renderer thread starts, so AWT finds NSApp already
   created and does not install its own run loop. */
void dioxus_compose_prepare_main_thread(void) {
    [NSApplication sharedApplication];
    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
}

/* Runs AppKit until dioxus_compose_stop_main_thread. */
void dioxus_compose_park_main_thread(atomic_bool *finished) {
    @autoreleasepool {
        [NSApp activateIgnoringOtherApps:YES];
        /* The renderer may already be done if it failed early. */
        if (!atomic_load(finished)) {
            [NSApp run];
        }
    }
}

void dioxus_compose_stop_main_thread(void) {
    dispatch_async(dispatch_get_main_queue(), ^{
        [NSApp stop:nil];
        /* `stop:` takes effect after the next event, so post one. */
        NSEvent *wake = [NSEvent otherEventWithType:NSEventTypeApplicationDefined
                                           location:NSZeroPoint
                                      modifierFlags:0
                                          timestamp:0
                                       windowNumber:0
                                            context:nil
                                            subtype:0
                                              data1:0
                                              data2:0];
        [NSApp postEvent:wake atStart:YES];
    });
}

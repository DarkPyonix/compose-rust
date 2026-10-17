/*
 * macOS main-thread handling for the renderer.
 *
 * AppKit must run on the main thread, and the renderer runs on it: the window is its own,
 * drawn and driven from the thread AppKit answers on. The application object is created
 * here, before the renderer starts, so nothing finds it missing.
 */
#import <AppKit/AppKit.h>
#include <stdatomic.h>

/* Called on the main thread before the renderer thread starts, so nothing finds NSApp missing
   and the renderer, which runs on this thread, finds it ready. */
void compose_rust_prepare_main_thread(void) {
    [NSApplication sharedApplication];
    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
}

/*
 * macos-display-modes: which modes a Mac's main display offers, and whether one of them is a
 * HiDPI mode (two pixels to a point), for a CI runner.
 *
 *   clang -framework ApplicationServices macos-display-modes.c -o macos-display-modes
 *
 * The HiDPI check on macOS can run on a runner only if its virtual display can be switched
 * between one and two pixels to a point, with CGConfigureDisplayWithDisplayMode, which is public
 * API. Whether a runner offers such a mode is not documented, so this asks: it lists every mode
 * CGDisplayCopyAllDisplayModes returns with kCGDisplayShowDuplicateLowResolutionModes (the
 * option that includes the scaled ones) and says whether any has a pixel width twice its
 * width in points. It changes nothing and always exits 0 once it has answered.
 */
#include <ApplicationServices/ApplicationServices.h>
#include <stdio.h>

int main(void) {
    CGDirectDisplayID display = CGMainDisplayID();
    CGDisplayModeRef current = CGDisplayCopyDisplayMode(display);
    if (current != NULL) {
        printf("macos-display-modes: main display %u, now %zux%zu points in %zux%zu pixels\n", display,
               CGDisplayModeGetWidth(current), CGDisplayModeGetHeight(current),
               CGDisplayModeGetPixelWidth(current), CGDisplayModeGetPixelHeight(current));
    }
    const void *keys[] = {kCGDisplayShowDuplicateLowResolutionModes};
    const void *values[] = {kCFBooleanTrue};
    CFDictionaryRef options = CFDictionaryCreate(NULL, keys, values, 1, &kCFTypeDictionaryKeyCallBacks,
                                                 &kCFTypeDictionaryValueCallBacks);
    CFArrayRef modes = CGDisplayCopyAllDisplayModes(display, options);
    CFIndex count = modes == NULL ? 0 : CFArrayGetCount(modes);
    CFIndex hidpi = 0;
    for (CFIndex index = 0; index < count; index++) {
        CGDisplayModeRef mode = (CGDisplayModeRef)CFArrayGetValueAtIndex(modes, index);
        size_t width = CGDisplayModeGetWidth(mode);
        size_t pixels = CGDisplayModeGetPixelWidth(mode);
        int twice = width > 0 && pixels == 2 * width;
        if (twice) hidpi++;
        printf("macos-display-modes: mode %ld: %zux%zu points, %zux%zu pixels, %.0f Hz, usable for desktop: %s%s\n",
               (long)index, width, CGDisplayModeGetHeight(mode), pixels, CGDisplayModeGetPixelHeight(mode),
               CGDisplayModeGetRefreshRate(mode), CGDisplayModeIsUsableForDesktopGUI(mode) ? "yes" : "no",
               twice ? ", HiDPI" : "");
    }
    printf("macos-display-modes: %ld modes, %ld of them HiDPI\n", (long)count, (long)hidpi);
    printf("macos-display-modes: HiDPI mode offered: %s\n", hidpi > 0 ? "yes" : "no");
    if (modes != NULL) CFRelease(modes);
    CFRelease(options);
    if (current != NULL) CGDisplayModeRelease(current);
    return 0;
}

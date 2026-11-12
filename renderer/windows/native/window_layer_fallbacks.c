// Calls of the Win32 window layer that the Compose fork commit this build pins may not
// have yet, answered "not here" until it does.
//
// The window's C is the fork's extended/window/windows-shared/c/win32_window.c, and a change
// to a window is made there and nowhere else. The renderer's Kotlin reaches that C by symbol
// name, and an MSVC link fails on a name nothing defines. So each call the renderer makes
// that is newer than the pinned commit has a stand-in below, and the linker is told to use
// the stand-in only when the real function is missing (/alternatename). Once the pin moves to
// a commit that defines the function, the fork's definition is the one linked and the
// stand-in is dead; it is then deleted from here together with its /alternatename line.
//
// A stand-in changes nothing about a window. It returns 0, which every one of these calls
// defines as "not applied", and the renderer says so instead of pretending.
#include <stdint.h>

// int32_t dxc_native_window_set_resizable(int32_t resizable)
// Gives the selected window the thick frame and the maximize box, or takes them away.
// Returns 1 when applied.
int32_t dxc_fallback_window_set_resizable(int32_t resizable) {
    (void)resizable;
    return 0;
}
#pragma comment(linker, "/alternatename:dxc_native_window_set_resizable=dxc_fallback_window_set_resizable")

// int32_t dxc_native_window_set_color_scheme(int32_t scheme)
// Draws the selected window's caption and frame light or dark: 0 follows the system's apps
// theme and keeps following it, 1 is light, 2 is dark. Returns 1 when applied.
int32_t dxc_fallback_window_set_color_scheme(int32_t scheme) {
    (void)scheme;
    return 0;
}
#pragma comment(linker, "/alternatename:dxc_native_window_set_color_scheme=dxc_fallback_window_set_color_scheme")

// int32_t dxc_native_window_caption_dark(int32_t *dark)
// Writes 1 to *dark when the selected window's caption is drawn dark now and 0 when light.
// Returns 1 when it wrote.
int32_t dxc_fallback_window_caption_dark(int32_t *dark) {
    (void)dark;
    return 0;
}
#pragma comment(linker, "/alternatename:dxc_native_window_caption_dark=dxc_fallback_window_caption_dark")

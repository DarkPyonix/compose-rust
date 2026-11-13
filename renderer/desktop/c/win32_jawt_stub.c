/*
 * jawt.dll for the Windows renderer, which carries no Java toolkit.
 *
 * skiko's Windows library imports JAWT_GetAWT, for drawing into a toolkit window, so Windows
 * will not load it unless a jawt.dll is beside it. The JDK's jawt.dll loads awt.dll, which is
 * the toolkit. This one answers that there is no toolkit, which is true: the renderer opens
 * its own Win32 window and never asks skiko for a toolkit surface.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

/* Read by scripts/tests/renderer/windows-image-has-no-awt.test.sh to tell this from the JDK's. */
__declspec(dllexport) const char dxc_jawt_stub[] = "compose-rust: no Java toolkit";

/* JNI_FALSE: no AWT. The signature is jawt.h's, with the JNI types as the pointers they are. */
__declspec(dllexport) unsigned char JAWT_GetAWT(void *env, void *awt) {
    (void)env;
    (void)awt;
    return 0;
}

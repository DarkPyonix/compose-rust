#ifndef DXC_WIN32_DCOMP_H
#define DXC_WIN32_DCOMP_H

// A plain C ABI onto DirectComposition, for win32_window.c to call without including
// dcomp.h itself. dcomp.h declares overloaded COM methods (several SetOffsetX, for
// instance), which is C++ only; the SDK gives it no C-mode vtable struct the way d3d12.h
// and dxgi1_4.h have one. Everything that actually names a DComp type lives in
// win32_dcomp.cpp instead, built as C++, and crosses this boundary as HWND, IUnknown* or
// a plain int.

#include <windows.h>
#include <unknwn.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Makes the composition device, if this machine has one. Non-zero where it does; the
 * window this renderer opens reads it to decide its own extended style, before the
 * window exists, so this has to be callable first.
 */
int dxc_dcomp_make_device(void);

/**
 * Puts a swapchain on a window through the composition device made above. Zero on
 * success. Non-zero leaves nothing half made, so a caller that gets one back is a caller
 * that falls back to the window's own swapchain without releasing anything itself.
 */
int dxc_dcomp_attach(HWND window, IUnknown *swapchain);

/** Whether a swapchain is on a window through composition right now. */
int dxc_dcomp_active(void);

/** Commits whatever is pending on the visual. Zero on success. */
int dxc_dcomp_commit(void);

/** Lets go of the device, the target and the visual, in the order that matters. */
void dxc_dcomp_release(void);

#ifdef __cplusplus
}
#endif

#endif

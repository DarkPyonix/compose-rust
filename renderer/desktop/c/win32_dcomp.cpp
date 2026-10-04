// The DirectComposition half of the Win32 window, kept to one translation unit so the
// rest of the renderer never has to be C++. No exceptions, no RTTI, no STL: the calls
// here are COM, which is plain function pointers in a vtable either way, and the plain C
// surface in win32_dcomp.h is what win32_window.c actually calls.

#include "win32_dcomp.h"

#include <dcomp.h>

namespace {

IDCompositionDevice *g_device;
IDCompositionTarget *g_target;
IDCompositionVisual *g_visual;

}  // namespace

int dxc_dcomp_make_device(void) {
    dxc_dcomp_release();
    IDCompositionDevice *device = nullptr;
    if (FAILED(DCompositionCreateDevice(nullptr, IID_PPV_ARGS(&device)))) {
        return 0;
    }
    g_device = device;
    return 1;
}

int dxc_dcomp_attach(HWND window, IUnknown *swapchain) {
    if (g_device == nullptr) {
        return 1;
    }
    if (FAILED(g_device->CreateTargetForHwnd(window, TRUE, &g_target)) ||
        FAILED(g_device->CreateVisual(&g_visual)) ||
        FAILED(g_visual->SetContent(swapchain)) ||
        FAILED(g_target->SetRoot(g_visual)) ||
        FAILED(g_device->Commit())) {
        // The device stays. Only the target and the visual were this attempt's, and a
        // caller that gets a failure back is free to try again on another window.
        if (g_visual != nullptr) {
            g_visual->Release();
            g_visual = nullptr;
        }
        if (g_target != nullptr) {
            g_target->Release();
            g_target = nullptr;
        }
        return 2;
    }
    return 0;
}

int dxc_dcomp_active(void) {
    return g_visual != nullptr;
}

int dxc_dcomp_commit(void) {
    if (g_device == nullptr) {
        return 1;
    }
    return SUCCEEDED(g_device->Commit()) ? 0 : 1;
}

namespace {

typedef DWORD(WINAPI *wait_for_clock_fn)(UINT, const HANDLE *, DWORD);
typedef HRESULT(WINAPI *dwm_flush_fn)(void);

// Looked up at run time: the compositor clock is absent on Windows 10, and linking it
// would keep the renderer from loading there. DwmFlush is looked up the same way so the
// build links nothing new.
wait_for_clock_fn g_wait_for_clock;
dwm_flush_fn g_dwm_flush;
bool g_looked_up;

void look_up_waits() {
    if (g_looked_up) {
        return;
    }
    g_looked_up = true;
    HMODULE dcomp = GetModuleHandleW(L"dcomp.dll");
    if (dcomp != nullptr) {
        g_wait_for_clock = reinterpret_cast<wait_for_clock_fn>(
            reinterpret_cast<void *>(GetProcAddress(dcomp, "DCompositionWaitForCompositorClock")));
    }
    HMODULE dwm = LoadLibraryW(L"dwmapi.dll");
    if (dwm != nullptr) {
        g_dwm_flush = reinterpret_cast<dwm_flush_fn>(
            reinterpret_cast<void *>(GetProcAddress(dwm, "DwmFlush")));
    }
}

}  // namespace

void dxc_dcomp_wait_for_compositor(void) {
    if (g_device == nullptr) {
        return;
    }
    g_device->WaitForCommitCompletion();
    look_up_waits();
    if (g_wait_for_clock != nullptr) {
        // A frame at 60 Hz and a little over, so a stalled compositor cannot hold the
        // window's message loop for longer than that.
        g_wait_for_clock(0, nullptr, 17);
    } else if (g_dwm_flush != nullptr) {
        g_dwm_flush();
    }
}

void dxc_dcomp_release(void) {
    if (g_visual != nullptr) {
        g_visual->Release();
        g_visual = nullptr;
    }
    if (g_target != nullptr) {
        g_target->Release();
        g_target = nullptr;
    }
    if (g_device != nullptr) {
        g_device->Release();
        g_device = nullptr;
    }
}

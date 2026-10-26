/*
 * resize-sync-control: a window that is late on purpose, and asks Windows to wait for it.
 *
 * resize-control.exe shows that on Windows 11 26H2 an ordinary Win32 window is not waited for:
 * the frame is on screen at the new size while the window still draws (the probe FAILs it). The
 * Settings app is waited for: the frame stays where it was until the app has drawn the new
 * size. This window uses the private resize synchronization Settings (XAML) uses, the way
 * EpicGames/raddebugger#1033 does for a plain Win32 window, to find out whether a window that
 * is 50 ms late then reaches the screen with no black, no hole and no stale picture.
 *
 * The mechanism (all private, resolved at run time, nothing linked):
 *   - user32 ordinal 2615 EnableResizeLayoutSynchronization(hwnd, enable), on the top-level
 *     window and on a hidden, zero-size child window of it. Windows issues no token for a
 *     window that has no such child.
 *   - user32 ordinal 2614 GetResizeDCompositionSynchronizationObject(hwnd, &handle), called in
 *     WM_WINDOWPOSCHANGED, gives the token of the resize. It can succeed with a null handle.
 *   - the picture is drawn into a Windows.UI.Composition surface (mode "surface": a drawing
 *     surface, BeginDraw / EndDraw) or into a composition swapchain given to the compositor
 *     (mode "swapchain": CreateSwapChainForComposition, CreateCompositionSurfaceForSwapChain),
 *     on a compositor made by IInteropCompositorFactoryPartner::CreateInteropCompositor.
 *   - IDCompositionDesktopDevicePartner6::SynchronizedCommit(token) commits the new picture
 *     together with the token, so that Windows shows the new frame and the new picture at once,
 *     and user32 ordinal 2538 LayoutCompleted(hwnd) tells Windows the layout is done.
 *   - the frame is drawn from a posted message, after WM_WINDOWPOSCHANGED has returned.
 *
 * Where any of it is missing (an ordinal, the partner factory, the partner interface) this
 * program still runs, on whatever is left, and says so on standard error: "sync=off" and the
 * HRESULT that stopped it. It never exits because of it, so the probe still drags it and the
 * log shows how far the mechanism got on that build of Windows.
 *
 * Like resize-control.exe it is late on purpose during a drag: DXC_RESIZE_PRESENT_DELAY_MS
 * milliseconds (50 when not set; 0 makes it prompt) between receiving a new size and drawing
 * it. What it draws is the same as resize-control.exe draws: a light grey field with a dark 2 px
 * line every 24 px from the left and a blue band along the right edge, for the probe's stale
 * check. The window opens at the primary monitor's work area's top left, 640x480 pixels, and
 * closes itself after --seconds (default 300).
 *
 *   resize-sync-control.exe [--delay-ms N] [--seconds N] [--mode surface|swapchain] [--device plain|on12]
 *
 * --device on12 (or RESIZE_SYNC_DEVICE=on12) makes the composition graphics device out of a Direct3D 11
 * device made by D3D11On12 on a Direct3D 12 device and queue, as the renderer's window has it, and
 * copies each frame into the drawing surface from a wrapped Direct3D 12 texture, the way the
 * renderer's frame_end copies into its swapchain buffer. Only with --mode surface.
 *
 * --mode overrides RESIZE_SYNC_MODE (surface when not set). DXC_D3D12_WARP=1 draws with WARP.
 * Lines go to standard error. Linked as a GUI program, like resize-control.exe.
 */
#define _WIN32_WINNT 0x0A00
#define WIN32_LEAN_AND_MEAN
#define UNICODE
#define _UNICODE
#define COBJMACROS
#define _CRT_SECURE_NO_WARNINGS
#include <windows.h>
#include <d3d11_1.h>
#include <d3d12.h>
#include <d3d11on12.h>
#include <dxgi1_4.h>
#include <roapi.h>
#include <winstring.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "resize-os-version.h"

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "d3d12.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "dxguid.lib")
#pragma comment(lib, "runtimeobject.lib")

#define CONTROL_CLASS L"ResizeSyncControlWindow"
#define CHILD_CLASS L"ResizeSyncControlChild"
#define RULER_STEP 24
#define RULER_WIDTH 2
#define BAND_WIDTH 8
#define MAX_LINES 512
#define RENDER_MESSAGE (WM_APP + 0x3F2)

/* Interface IDs and vtable slots, as raddebugger#1033 uses them (slots 0-2 are IUnknown's, 3-5 IInspectable's). */
static const GUID iid_compositor = {0xb403ca50, 0x7f8c, 0x4e83, {0x98, 0x5f, 0xcc, 0x45, 0x06, 0x00, 0x36, 0xd8}};
static const GUID iid_compositor_interop = {0x25297d5c, 0x3ad4, 0x4c9c, {0xb5, 0xcf, 0xe3, 0x6a, 0x38, 0x51, 0x23, 0x30}};
static const GUID iid_compositor_desktop = {0x29e691fa, 0x4567, 0x4dca, {0xb3, 0x19, 0xd0, 0xf2, 0x07, 0xeb, 0x68, 0x07}};
static const GUID iid_factory_partner = {0x22118adf, 0x23f1, 0x4801, {0xbc, 0xfa, 0x66, 0xcb, 0xf4, 0x8c, 0xc5, 0x1b}};
static const GUID iid_device_partner6 = {0xe01eb649, 0x787e, 0x4560, {0xb3, 0x98, 0x0d, 0xe7, 0xa2, 0x06, 0x5d, 0x8b}};
static const GUID iid_visual = {0x117e202d, 0xa859, 0x4c89, {0x87, 0x3b, 0xc2, 0xaa, 0x56, 0x67, 0x88, 0xe3}};
static const GUID iid_composition_target = {0xa1bea8ba, 0xd726, 0x4663, {0x81, 0x29, 0x6b, 0x5e, 0x79, 0x27, 0xff, 0xa6}};
static const GUID iid_composition_surface = {0x1527540d, 0x42c7, 0x47a6, {0xa4, 0x08, 0x66, 0x8f, 0x79, 0xa9, 0x0d, 0xfb}};
static const GUID iid_composition_brush = {0xab0d7608, 0x30c0, 0x40e9, {0xb5, 0x68, 0xb6, 0x0a, 0x6b, 0xd1, 0xfb, 0x46}};
static const GUID iid_drawing_interop = {0xfd04e6e3, 0xfe0c, 0x4c3c, {0xab, 0x19, 0xa0, 0x76, 0x01, 0xa5, 0x76, 0xee}};

#define SLOT_FACTORY_PARTNER_CREATE_INTEROP_COMPOSITOR 6
#define SLOT_DEVICE_PARTNER6_SYNCHRONIZED_COMMIT 69
#define SLOT_COMPOSITOR_CREATE_SPRITE_VISUAL 22
#define SLOT_COMPOSITOR_CREATE_SURFACE_BRUSH 24
#define SLOT_COMPOSITOR_INTEROP_CREATE_SURFACE_FOR_SWAPCHAIN 4
#define SLOT_COMPOSITOR_INTEROP_CREATE_GRAPHICS_DEVICE 5
#define SLOT_COMPOSITOR_DESKTOP_CREATE_TARGET 3
#define SLOT_GRAPHICS_DEVICE_CREATE_DRAWING_SURFACE 6
#define SLOT_VISUAL_PUT_SIZE 36
#define SLOT_SPRITE_VISUAL_PUT_BRUSH 7
#define SLOT_TARGET_PUT_ROOT 7
#define SLOT_DRAWING_INTEROP_BEGIN_DRAW 3
#define SLOT_DRAWING_INTEROP_END_DRAW 4
#define SLOT_DRAWING_INTEROP_RESIZE 5

#define PIXEL_FORMAT_B8G8R8A8_UNORM 87
#define ALPHA_MODE_PREMULTIPLIED 1

typedef struct { float x, y; } Vec2;
typedef HRESULT(STDMETHODCALLTYPE *GetFn)(void *self, void **out);
typedef HRESULT(STDMETHODCALLTYPE *PutFn)(void *self, void *value);
typedef HRESULT(STDMETHODCALLTYPE *CallFn)(void *self);
typedef HRESULT(STDMETHODCALLTYPE *PutGetFn)(void *self, void *value, void **out);
typedef HRESULT(STDMETHODCALLTYPE *CreateInteropCompositorFn)(void *self, void *device, void *callback, const GUID *iid, void **out);
typedef HRESULT(STDMETHODCALLTYPE *CreateTargetFn)(void *self, HWND hwnd, BOOL topmost, void **out);
typedef HRESULT(STDMETHODCALLTYPE *CreateDrawingSurfaceFn)(void *self, Vec2 size, int format, int alpha, void **out);
typedef HRESULT(STDMETHODCALLTYPE *PutVec2Fn)(void *self, Vec2 value);
typedef HRESULT(STDMETHODCALLTYPE *BeginDrawFn)(void *self, const RECT *update, const GUID *iid, void **out, POINT *offset);
typedef HRESULT(STDMETHODCALLTYPE *ResizeFn)(void *self, SIZE size);
typedef struct { DWORD size; int thread_type; int apartment_type; } QueueOptions;
typedef HRESULT(WINAPI *CreateQueueFn)(QueueOptions options, void **out);

#define SLOT(object, slot, type) ((type)((*(void ***)(object))[slot]))

typedef BOOL(WINAPI *EnableSyncFn)(HWND, BOOL);
typedef BOOL(WINAPI *GetSyncFn)(HWND, HANDLE *);
typedef BOOL(WINAPI *LayoutCompletedFn)(HWND);

static HWND window;
static HWND sync_child;
static ID3D11Device *device;
static ID3D11DeviceContext *context;
static ID3D11DeviceContext1 *context1;
static IDXGIFactory2 *factory;
static IDXGISwapChain1 *swapchain;
static int use_swapchain;
static int use_on12;
/* --device on12: the renderer's arrangement, Direct3D 12 under Direct3D 11. */
static ID3D12Device *device12;
static ID3D12CommandQueue *queue12;
static ID3D12Fence *fence12;
static HANDLE fence_event;
static UINT64 fence_value;
static ID3D11On12Device *on12;
static ID3D11Resource *wrapped;
static int wrapped_width, wrapped_height;
static const GUID iid_on12_device = {0x85611e73, 0x70a9, 0x490e, {0x96, 0x14, 0xa9, 0xe3, 0x02, 0x77, 0x79, 0x04}};
static void *compositor;
static void *device_partner;
static void *graphics_device;
static void *drawing_interop;
static void *visual;
static int composition_ready;
static int sync_on;
static EnableSyncFn enable_sync;
static GetSyncFn get_sync;
static LayoutCompletedFn layout_completed;
static int surface_width, surface_height;
static int delay_ms = 50;
static int sizing;
static int queued;
static HANDLE pending_token;
static long steps, tokens, commits_ok, layouts_ok, draw_failures;
static LARGE_INTEGER frequency;
static long long queued_qpc;

static long long now_qpc(void) {
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    return now.QuadPart;
}

static int step_failed(HRESULT result, const char *what) {
    if (FAILED(result)) {
        fprintf(stderr, "resize-sync-control: %s failed (0x%08lx)\n", what, (unsigned long)result);
        return 1;
    }
    return 0;
}

/* Clears rect of the render target to color with ClearView, or the whole target when there is no ID3D11DeviceContext1. */
static void clear_rects(ID3D11RenderTargetView *target, const float color[4], const D3D11_RECT *rects, UINT count) {
    if (context1 != NULL) {
        if (count > 0) ID3D11DeviceContext1_ClearView(context1, (ID3D11View *)target, color, rects, count);
    } else {
        ID3D11DeviceContext_ClearRenderTargetView(context, target, color);
    }
}

/* The ruler, in the rectangle of width x height pixels that starts at (left, top) of the target. */
static void paint(ID3D11RenderTargetView *target, int left, int top, int width, int height) {
    const float field[4] = {0.88f, 0.88f, 0.88f, 1.0f};
    const float line[4] = {0.25f, 0.25f, 0.25f, 1.0f};
    const float band[4] = {0.10f, 0.35f, 0.80f, 1.0f};
    D3D11_RECT all = {left, top, left + width, top + height};
    clear_rects(target, field, &all, 1);
    D3D11_RECT lines[MAX_LINES];
    UINT count = 0;
    for (int x = RULER_STEP; x + RULER_WIDTH <= width - BAND_WIDTH - 4 && count < MAX_LINES; x += RULER_STEP) {
        lines[count].left = left + x;
        lines[count].top = top;
        lines[count].right = left + x + RULER_WIDTH;
        lines[count].bottom = top + height;
        count++;
    }
    if (context1 != NULL) clear_rects(target, line, lines, count);
    D3D11_RECT edge = {left + (width > BAND_WIDTH ? width - BAND_WIDTH : 0), top, left + width, top + height};
    if (context1 != NULL) clear_rects(target, band, &edge, 1);
}

static void wait_for_queue(void) {
    if (queue12 == NULL || fence12 == NULL) return;
    UINT64 mark = ++fence_value;
    if (FAILED(ID3D12CommandQueue_Signal(queue12, fence12, mark))) return;
    if (ID3D12Fence_GetCompletedValue(fence12) < mark &&
        SUCCEEDED(ID3D12Fence_SetEventOnCompletion(fence12, mark, fence_event))) {
        WaitForSingleObject(fence_event, INFINITE);
    }
}

/* The Direct3D 12 texture the picture is drawn into first, and its Direct3D 11 wrapper. */
static int make_wrapped(int width, int height) {
    if (wrapped != NULL && width <= wrapped_width && height <= wrapped_height) return 1;
    if (wrapped != NULL) {
        ID3D11Resource_Release(wrapped);
        wrapped = NULL;
    }
    int room_w = GetSystemMetrics(SM_CXVIRTUALSCREEN);
    int room_h = GetSystemMetrics(SM_CYVIRTUALSCREEN);
    if (room_w < width) room_w = width;
    if (room_h < height) room_h = height;
    room_w = (room_w + 255) / 256 * 256;
    room_h = (room_h + 255) / 256 * 256;
    D3D12_HEAP_PROPERTIES heap;
    memset(&heap, 0, sizeof heap);
    heap.Type = D3D12_HEAP_TYPE_DEFAULT;
    D3D12_RESOURCE_DESC description;
    memset(&description, 0, sizeof description);
    description.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    description.Width = (UINT64)room_w;
    description.Height = (UINT)room_h;
    description.DepthOrArraySize = 1;
    description.MipLevels = 1;
    description.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    description.SampleDesc.Count = 1;
    description.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
    description.Flags = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET;
    ID3D12Resource *texture = NULL;
    if (step_failed(ID3D12Device_CreateCommittedResource(device12, &heap, D3D12_HEAP_FLAG_NONE, &description,
                    D3D12_RESOURCE_STATE_PRESENT, NULL, &IID_ID3D12Resource, (void **)&texture), "CreateCommittedResource"))
        return 0;
    D3D11_RESOURCE_FLAGS flags;
    memset(&flags, 0, sizeof flags);
    flags.BindFlags = D3D11_BIND_RENDER_TARGET;
    HRESULT made = ID3D11On12Device_CreateWrappedResource(on12, (IUnknown *)texture, &flags, D3D12_RESOURCE_STATE_PRESENT,
                                                          D3D12_RESOURCE_STATE_PRESENT, &IID_ID3D11Resource, (void **)&wrapped);
    ID3D12Resource_Release(texture);
    if (step_failed(made, "CreateWrappedResource")) {
        wrapped = NULL;
        return 0;
    }
    wrapped_width = room_w;
    wrapped_height = room_h;
    return 1;
}

static void set_visual_size(int width, int height) {
    Vec2 size = {(float)width, (float)height};
    step_failed(SLOT(visual, SLOT_VISUAL_PUT_SIZE, PutVec2Fn)(visual, size), "set the visual's size");
}

/* Draws the picture for width x height, into the composition surface or the composition swapchain. */
static int draw(int width, int height) {
    if (!composition_ready || width <= 0 || height <= 0) return 0;
    ID3D11Texture2D *texture = NULL;
    POINT offset = {0, 0};
    if (use_swapchain) {
        if (width != surface_width || height != surface_height) {
            if (step_failed(IDXGISwapChain1_ResizeBuffers(swapchain, 0, (UINT)width, (UINT)height, DXGI_FORMAT_UNKNOWN, 0),
                            "ResizeBuffers")) return 0;
            set_visual_size(width, height);
            surface_width = width;
            surface_height = height;
        }
        if (step_failed(IDXGISwapChain1_GetBuffer(swapchain, 0, &IID_ID3D11Texture2D, (void **)&texture), "GetBuffer")) return 0;
    } else {
        if (width != surface_width || height != surface_height) {
            SIZE size = {width, height};
            if (step_failed(SLOT(drawing_interop, SLOT_DRAWING_INTEROP_RESIZE, ResizeFn)(drawing_interop, size),
                            "resize the drawing surface")) return 0;
            set_visual_size(width, height);
            surface_width = width;
            surface_height = height;
        }
        if (use_on12 && !make_wrapped(width, height)) return 0;
        if (use_on12) {
            /* The picture first, into the wrapped Direct3D 12 texture, as Skia would draw it. */
            ID3D11On12Device_AcquireWrappedResources(on12, &wrapped, 1);
            ID3D11RenderTargetView *source = NULL;
            if (!FAILED(ID3D11Device_CreateRenderTargetView(device, wrapped, NULL, &source))) {
                paint(source, 0, 0, width, height);
                ID3D11RenderTargetView_Release(source);
            }
            ID3D11On12Device_ReleaseWrappedResources(on12, &wrapped, 1);
            ID3D11DeviceContext_Flush(context);
        }
        if (step_failed(SLOT(drawing_interop, SLOT_DRAWING_INTEROP_BEGIN_DRAW, BeginDrawFn)(drawing_interop, NULL,
                        &IID_ID3D11Texture2D, (void **)&texture, &offset), "BeginDraw")) return 0;
    }
    if (use_on12 && !use_swapchain) {
        /* Then the copy into the drawing surface, as frame_end copies into its swapchain buffer. */
        ID3D11On12Device_AcquireWrappedResources(on12, &wrapped, 1);
        D3D11_BOX box = {0, 0, 0, (UINT)width, (UINT)height, 1};
        ID3D11DeviceContext_CopySubresourceRegion(context, (ID3D11Resource *)texture, 0, (UINT)offset.x, (UINT)offset.y, 0,
                                                  wrapped, 0, &box);
        ID3D11On12Device_ReleaseWrappedResources(on12, &wrapped, 1);
        ID3D11DeviceContext_Flush(context);
        wait_for_queue();
        ID3D11Texture2D_Release(texture);
        return !step_failed(SLOT(drawing_interop, SLOT_DRAWING_INTEROP_END_DRAW, CallFn)(drawing_interop), "EndDraw");
    }
    ID3D11RenderTargetView *target = NULL;
    HRESULT made = ID3D11Device_CreateRenderTargetView(device, (ID3D11Resource *)texture, NULL, &target);
    if (!FAILED(made)) {
        paint(target, offset.x, offset.y, width, height);
        ID3D11RenderTargetView_Release(target);
    }
    ID3D11Texture2D_Release(texture);
    if (use_swapchain) {
        return !step_failed(IDXGISwapChain1_Present(swapchain, 0, 0), "Present") && !FAILED(made);
    }
    return !step_failed(SLOT(drawing_interop, SLOT_DRAWING_INTEROP_END_DRAW, CallFn)(drawing_interop), "EndDraw") && !FAILED(made);
}

/* Commits what was drawn, with the resize's token when there is one. */
static int commit(HANDLE token) {
    if (device_partner == NULL) return 0;
    HRESULT result = SLOT(device_partner, SLOT_DEVICE_PARTNER6_SYNCHRONIZED_COMMIT, PutFn)(device_partner, token);
    return !step_failed(result, "SynchronizedCommit");
}

/* The frame for the window's size now, as the posted message draws it. */
static void render_resize(void) {
    HANDLE token = pending_token;
    pending_token = NULL;
    queued = 0;
    RECT client;
    GetClientRect(window, &client);
    int late = sizing && delay_ms > 0;
    if (late) Sleep((DWORD)delay_ms);
    int drawn = IsIconic(window) ? 1 : draw(client.right, client.bottom);
    if (!drawn) draw_failures++;
    int committed = commit(token);
    if (token != NULL) CloseHandle(token);
    int laid_out = 0;
    if (sync_on && layout_completed != NULL) laid_out = layout_completed(window) ? 1 : 0;
    long long presented = now_qpc();
    if (committed) commits_ok++;
    if (laid_out) layouts_ok++;
    if (sizing) {
        steps++;
        fprintf(stderr,
                "resize-sync-control: step n=%ld %ldx%ld qpc_size=%lld qpc_present=%lld late_ms=%.1f drawn=%d token=%d commit=%d layout=%d\n",
                steps, client.right, client.bottom, queued_qpc, presented,
                (double)(presented - queued_qpc) * 1000.0 / (double)frequency.QuadPart, drawn, token != NULL, committed,
                laid_out);
    }
}

static LRESULT CALLBACK control_proc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
    case WM_ENTERSIZEMOVE:
        sizing = 1;
        return 0;
    case WM_EXITSIZEMOVE:
        sizing = 0;
        fprintf(stderr, "resize-sync-control: drag done steps=%ld tokens=%ld commits_ok=%ld layouts_ok=%ld draw_failures=%ld\n",
                steps, tokens, commits_ok, layouts_ok, draw_failures);
        return 0;
    case WM_WINDOWPOSCHANGED: {
        /* No DefWindowProc, so no WM_SIZE: the size is handled here. The token is taken now and the frame is
         * drawn after this returns, from the posted message. */
        WINDOWPOS *position = (WINDOWPOS *)lparam;
        if (composition_ready && !(position->flags & SWP_NOSIZE)) {
            HANDLE token = NULL;
            if (sync_on && get_sync != NULL) {
                if (!get_sync(hwnd, &token)) {
                    if (token != NULL) CloseHandle(token);
                    token = NULL;
                    fprintf(stderr, "resize-sync-control: GetResizeDCompositionSynchronizationObject failed (%lu)\n",
                            (unsigned long)GetLastError());
                }
            }
            if (token != NULL) tokens++;
            if (pending_token != NULL) CloseHandle(pending_token);
            pending_token = token;
            if (!queued) {
                queued = 1;
                queued_qpc = now_qpc();
                PostMessageW(hwnd, RENDER_MESSAGE, 0, 0);
            }
        }
        return 0;
    }
    case RENDER_MESSAGE:
        if (queued) render_resize();
        return 0;
    case WM_PAINT: {
        PAINTSTRUCT paint_struct;
        BeginPaint(hwnd, &paint_struct);
        EndPaint(hwnd, &paint_struct);
        if (!sizing && !queued && composition_ready) {
            RECT client;
            GetClientRect(hwnd, &client);
            if (client.right != surface_width || client.bottom != surface_height) {
                draw(client.right, client.bottom);
                commit(NULL);
            }
        }
        return 0;
    }
    case WM_ERASEBKGND:
        return 1;
    case WM_TIMER:
        DestroyWindow(hwnd);
        return 0;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    default:
        return DefWindowProcW(hwnd, message, wparam, lparam);
    }
}

static const char *argument(int argc, char **argv, const char *name, const char *fallback) {
    for (int index = 1; index + 1 < argc; index++) {
        if (strcmp(argv[index], name) == 0) return argv[index + 1];
    }
    return fallback;
}

static void *query(void *object, const GUID *iid, const char *what) {
    void *result = NULL;
    HRESULT made = ((IUnknown *)object)->lpVtbl->QueryInterface((IUnknown *)object, iid, &result);
    if (step_failed(made, what)) return NULL;
    return result;
}

/*
 * The compositor, the graphics device and the window's tree: target -> sprite visual -> surface brush -> surface.
 * Returns 1 when the window can show a picture. Sets sync_on when the resize synchronization is also in place.
 */
static int set_up_composition(int width, int height) {
    HRESULT initialized = RoInitialize(RO_INIT_SINGLETHREADED);
    fprintf(stderr, "resize-sync-control: RoInitialize 0x%08lx\n", (unsigned long)initialized);
    HMODULE messaging = LoadLibraryW(L"CoreMessaging.dll");
    CreateQueueFn create_queue = messaging != NULL ? (CreateQueueFn)(void *)GetProcAddress(messaging, "CreateDispatcherQueueController") : NULL;
    if (create_queue == NULL) {
        fprintf(stderr, "resize-sync-control: composition=failed CreateDispatcherQueueController is missing\n");
        return 0;
    }
    QueueOptions options = {sizeof options, 2, 2};
    void *queue = NULL;
    if (step_failed(create_queue(options, &queue), "CreateDispatcherQueueController")) return 0;

    static const WCHAR class_name[] = L"Windows.UI.Composition.Compositor";
    HSTRING_HEADER header;
    HSTRING name = NULL;
    if (step_failed(WindowsCreateStringReference(class_name, (UINT32)(sizeof class_name / sizeof(WCHAR) - 1), &header, &name),
                    "WindowsCreateStringReference")) return 0;
    void *partner_factory = NULL;
    HRESULT got = RoGetActivationFactory(name, &iid_factory_partner, &partner_factory);
    fprintf(stderr, "resize-sync-control: interop compositor factory 0x%08lx\n", (unsigned long)got);
    if (!FAILED(got) && partner_factory != NULL) {
        got = SLOT(partner_factory, SLOT_FACTORY_PARTNER_CREATE_INTEROP_COMPOSITOR, CreateInteropCompositorFn)(
            partner_factory, NULL, NULL, &iid_compositor, &compositor);
        fprintf(stderr, "resize-sync-control: CreateInteropCompositor 0x%08lx\n", (unsigned long)got);
        if (FAILED(got)) compositor = NULL;
    }
    if (compositor == NULL) {
        /* The stock compositor: pictures can be shown, but a commit cannot carry a token. */
        void *instance = NULL;
        got = RoActivateInstance(name, (IInspectable **)&instance);
        if (step_failed(got, "RoActivateInstance(Compositor)")) return 0;
        compositor = query(instance, &iid_compositor, "query ICompositor");
        if (compositor == NULL) return 0;
        fprintf(stderr, "resize-sync-control: using the stock compositor\n");
    }
    device_partner = query(compositor, &iid_device_partner6, "query IDCompositionDesktopDevicePartner6");

    void *compositor_interop = query(compositor, &iid_compositor_interop, "query ICompositorInterop");
    if (compositor_interop == NULL) return 0;
    if (step_failed(SLOT(compositor_interop, SLOT_COMPOSITOR_INTEROP_CREATE_GRAPHICS_DEVICE, PutGetFn)(compositor_interop, device,
                    &graphics_device), "CreateGraphicsDevice")) return 0;

    void *desktop_interop = query(compositor, &iid_compositor_desktop, "query ICompositorDesktopInterop");
    if (desktop_interop == NULL) return 0;
    void *desktop_target = NULL;
    if (step_failed(SLOT(desktop_interop, SLOT_COMPOSITOR_DESKTOP_CREATE_TARGET, CreateTargetFn)(desktop_interop, window, TRUE,
                    &desktop_target), "CreateDesktopWindowTarget")) return 0;
    void *target = query(desktop_target, &iid_composition_target, "query ICompositionTarget");
    void *sprite = NULL;
    if (target == NULL || step_failed(SLOT(compositor, SLOT_COMPOSITOR_CREATE_SPRITE_VISUAL, GetFn)(compositor, &sprite),
                                      "CreateSpriteVisual")) return 0;
    visual = query(sprite, &iid_visual, "query IVisual");
    if (visual == NULL) return 0;

    void *surface = NULL;
    if (use_swapchain) {
        DXGI_SWAP_CHAIN_DESC1 description;
        memset(&description, 0, sizeof description);
        description.Width = (UINT)width;
        description.Height = (UINT)height;
        description.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        description.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
        description.BufferCount = 2;
        description.SampleDesc.Count = 1;
        description.SwapEffect = DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL;
        description.Scaling = DXGI_SCALING_STRETCH;
        description.AlphaMode = DXGI_ALPHA_MODE_PREMULTIPLIED;
        if (step_failed(IDXGIFactory2_CreateSwapChainForComposition(factory, (IUnknown *)device, &description, NULL, &swapchain),
                        "CreateSwapChainForComposition")) return 0;
        if (step_failed(SLOT(compositor_interop, SLOT_COMPOSITOR_INTEROP_CREATE_SURFACE_FOR_SWAPCHAIN, PutGetFn)(
                        compositor_interop, swapchain, &surface), "CreateCompositionSurfaceForSwapChain")) return 0;
    } else {
        Vec2 one = {1, 1};
        void *drawing_surface = NULL;
        if (step_failed(SLOT(graphics_device, SLOT_GRAPHICS_DEVICE_CREATE_DRAWING_SURFACE, CreateDrawingSurfaceFn)(
                        graphics_device, one, PIXEL_FORMAT_B8G8R8A8_UNORM, ALPHA_MODE_PREMULTIPLIED, &drawing_surface),
                        "CreateDrawingSurface")) return 0;
        surface = query(drawing_surface, &iid_composition_surface, "query ICompositionSurface");
        drawing_interop = query(drawing_surface, &iid_drawing_interop, "query ICompositionDrawingSurfaceInterop");
        if (surface == NULL || drawing_interop == NULL) return 0;
    }
    void *surface_brush = NULL;
    if (step_failed(SLOT(compositor, SLOT_COMPOSITOR_CREATE_SURFACE_BRUSH, PutGetFn)(compositor, surface, &surface_brush),
                    "CreateSurfaceBrush")) return 0;
    void *brush = query(surface_brush, &iid_composition_brush, "query ICompositionBrush");
    if (brush == NULL) return 0;
    if (step_failed(SLOT(sprite, SLOT_SPRITE_VISUAL_PUT_BRUSH, PutFn)(sprite, brush), "put the brush")) return 0;
    if (step_failed(SLOT(target, SLOT_TARGET_PUT_ROOT, PutFn)(target, visual), "put the root visual")) return 0;
    return 1;
}

/* The private resize synchronization: the three ordinals, the partner interface, and the hidden child. */
static void set_up_sync(void) {
    HMODULE user32 = GetModuleHandleW(L"user32.dll");
    get_sync = (GetSyncFn)(void *)GetProcAddress(user32, MAKEINTRESOURCEA(2614));
    enable_sync = (EnableSyncFn)(void *)GetProcAddress(user32, MAKEINTRESOURCEA(2615));
    layout_completed = (LayoutCompletedFn)(void *)GetProcAddress(user32, MAKEINTRESOURCEA(2538));
    fprintf(stderr, "resize-sync-control: ordinals 2615=%s 2614=%s 2538=%s partner6=%s\n", enable_sync ? "yes" : "no",
            get_sync ? "yes" : "no", layout_completed ? "yes" : "no", device_partner ? "yes" : "no");
    if (enable_sync == NULL || get_sync == NULL || layout_completed == NULL || device_partner == NULL) return;
    sync_child = CreateWindowExW(0, CHILD_CLASS, L"", WS_CHILD, 0, 0, 0, 0, window, NULL, GetModuleHandleW(NULL), NULL);
    if (sync_child == NULL) {
        fprintf(stderr, "resize-sync-control: could not make the synchronization child (%lu)\n", (unsigned long)GetLastError());
        return;
    }
    BOOL top = enable_sync(window, TRUE);
    BOOL child = enable_sync(sync_child, TRUE);
    fprintf(stderr, "resize-sync-control: EnableResizeLayoutSynchronization top-level=%d child=%d\n", top, child);
    sync_on = top && child;
}

int main(int argc, char **argv) {
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    QueryPerformanceFrequency(&frequency);
    const char *asked = getenv("DXC_RESIZE_PRESENT_DELAY_MS");
    if (asked != NULL && asked[0] != 0) delay_ms = atoi(asked);
    const char *flag = argument(argc, argv, "--delay-ms", NULL);
    if (flag != NULL) delay_ms = atoi(flag);
    int seconds = atoi(argument(argc, argv, "--seconds", "300"));
    const char *mode = getenv("RESIZE_SYNC_MODE");
    mode = argument(argc, argv, "--mode", mode != NULL && mode[0] != 0 ? mode : "surface");
    use_swapchain = strcmp(mode, "swapchain") == 0;
    const char *kind = getenv("RESIZE_SYNC_DEVICE");
    kind = argument(argc, argv, "--device", kind != NULL && kind[0] != 0 ? kind : "plain");
    use_on12 = strcmp(kind, "on12") == 0 && !use_swapchain;
    const char *warp = getenv("DXC_D3D12_WARP");
    int use_warp = warp != NULL && strcmp(warp, "1") == 0;

    char os_line[512];
    resize_os_version(os_line, sizeof os_line);
    fprintf(stderr, "resize-sync-control: os %s\n", os_line);
    fprintf(stderr, "resize-sync-control: start qpc_frequency=%lld present_delay_ms=%d mode=%s device=%s adapter=%s\n",
            (long long)frequency.QuadPart, delay_ms, use_swapchain ? "swapchain" : "surface", use_on12 ? "on12" : "plain",
            use_warp ? "warp" : "hardware");

    WNDCLASSEXW description;
    memset(&description, 0, sizeof description);
    description.cbSize = sizeof description;
    description.lpfnWndProc = control_proc;
    description.hInstance = GetModuleHandleW(NULL);
    description.hCursor = LoadCursorW(NULL, IDC_ARROW);
    description.lpszClassName = CONTROL_CLASS;
    if (RegisterClassExW(&description) == 0) return 2;
    description.lpfnWndProc = DefWindowProcW;
    description.lpszClassName = CHILD_CLASS;
    if (RegisterClassExW(&description) == 0) return 2;

    POINT origin = {0, 0};
    MONITORINFO monitor;
    memset(&monitor, 0, sizeof monitor);
    monitor.cbSize = sizeof monitor;
    GetMonitorInfoW(MonitorFromPoint(origin, MONITOR_DEFAULTTOPRIMARY), &monitor);
    /* No redirection bitmap: all the window shows is what the compositor draws for it. */
    window = CreateWindowExW(WS_EX_NOREDIRECTIONBITMAP, CONTROL_CLASS, L"resize sync control", WS_OVERLAPPEDWINDOW,
                             monitor.rcWork.left, monitor.rcWork.top, 640, 480, NULL, NULL, GetModuleHandleW(NULL), NULL);
    if (window == NULL) {
        fprintf(stderr, "resize-sync-control: could not make the window (%lu)\n", (unsigned long)GetLastError());
        return 2;
    }
    RECT client;
    GetClientRect(window, &client);

    D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
    HRESULT made;
    if (use_on12) {
        IDXGIFactory4 *factory4 = NULL;
        IDXGIAdapter *chosen = NULL;
        made = CreateDXGIFactory1(&IID_IDXGIFactory4, (void **)&factory4);
        if (!FAILED(made)) {
            made = use_warp ? IDXGIFactory4_EnumWarpAdapter(factory4, &IID_IDXGIAdapter, (void **)&chosen)
                            : IDXGIFactory4_EnumAdapters(factory4, 0, &chosen);
        }
        if (!FAILED(made)) made = D3D12CreateDevice((IUnknown *)chosen, D3D_FEATURE_LEVEL_11_0, &IID_ID3D12Device, (void **)&device12);
        D3D12_COMMAND_QUEUE_DESC queue_description;
        memset(&queue_description, 0, sizeof queue_description);
        queue_description.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
        if (!FAILED(made)) made = ID3D12Device_CreateCommandQueue(device12, &queue_description, &IID_ID3D12CommandQueue, (void **)&queue12);
        if (!FAILED(made)) made = ID3D12Device_CreateFence(device12, 0, D3D12_FENCE_FLAG_NONE, &IID_ID3D12Fence, (void **)&fence12);
        fence_event = CreateEventW(NULL, FALSE, FALSE, NULL);
        IUnknown *queues[1] = {(IUnknown *)queue12};
        if (!FAILED(made)) {
            made = D3D11On12CreateDevice((IUnknown *)device12, D3D11_CREATE_DEVICE_BGRA_SUPPORT, levels, 2, queues, 1, 0, &device,
                                         &context, NULL);
        }
        if (!FAILED(made)) made = ID3D11Device_QueryInterface(device, &iid_on12_device, (void **)&on12);
        fprintf(stderr, "resize-sync-control: Direct3D 12 under Direct3D 11 (D3D11On12) 0x%08lx\n", (unsigned long)made);
        if (FAILED(made)) {
            use_on12 = 0;
            device = NULL;
            context = NULL;
        }
    }
    if (device == NULL) {
        made = D3D11CreateDevice(NULL, use_warp ? D3D_DRIVER_TYPE_WARP : D3D_DRIVER_TYPE_HARDWARE, NULL,
                                 D3D11_CREATE_DEVICE_BGRA_SUPPORT, levels, 2, D3D11_SDK_VERSION, &device, NULL, &context);
    } else {
        made = S_OK;
    }
    if (FAILED(made)) {
        fprintf(stderr, "resize-sync-control: no Direct3D 11 device (0x%08lx)\n", (unsigned long)made);
        return 2;
    }
    if (FAILED(ID3D11DeviceContext_QueryInterface(context, &IID_ID3D11DeviceContext1, (void **)&context1))) {
        context1 = NULL;
        fprintf(stderr, "resize-sync-control: no ID3D11DeviceContext1, so no ruler: the stale check has nothing to see\n");
    }
    IDXGIDevice *dxgi_device = NULL;
    IDXGIAdapter *adapter = NULL;
    ID3D11Device_QueryInterface(device, &IID_IDXGIDevice, (void **)&dxgi_device);
    if (dxgi_device != NULL) IDXGIDevice_GetAdapter(dxgi_device, &adapter);
    if (adapter != NULL) IDXGIAdapter_GetParent(adapter, &IID_IDXGIFactory2, (void **)&factory);
    if (factory == NULL) {
        fprintf(stderr, "resize-sync-control: no DXGI factory\n");
        return 2;
    }

    composition_ready = set_up_composition(client.right, client.bottom);
    fprintf(stderr, "resize-sync-control: composition=%s\n", composition_ready ? "ok" : "failed");
    if (composition_ready) set_up_sync();
    fprintf(stderr, "resize-sync-control: sync=%s mode=%s\n", sync_on ? "on" : "off", use_swapchain ? "swapchain" : "surface");

    ShowWindow(window, SW_SHOWNORMAL);
    if (composition_ready) {
        int drawn = draw(client.right, client.bottom);
        int committed = commit(NULL);
        fprintf(stderr, "resize-sync-control: first frame drawn=%d commit=%d\n", drawn, committed);
    }
    UpdateWindow(window);
    SetForegroundWindow(window);
    if (seconds > 0) SetTimer(window, 1, (UINT)seconds * 1000u, NULL);
    RECT outer;
    GetWindowRect(window, &outer);
    fprintf(stderr, "resize-sync-control: ready at %ld,%ld %ldx%ld px, dpi %u, frames late by %d ms during a drag\n",
            outer.left, outer.top, outer.right - outer.left, outer.bottom - outer.top, GetDpiForWindow(window), delay_ms);

    MSG message;
    while (GetMessageW(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    fprintf(stderr, "resize-sync-control: closed after %ld drag steps, tokens=%ld commits_ok=%ld layouts_ok=%ld sync=%s\n", steps,
            tokens, commits_ok, layouts_ok, sync_on ? "on" : "off");
    return 0;
}

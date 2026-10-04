// A window of our own on Windows, with a Direct3D 12 swapchain in it and no toolkit
// between.
//
// The pair of `appkit_window.m`. The renderer draws with Skia into a swapchain buffer;
// everything between that buffer and the screen belongs to Win32 and DXGI, and this file
// is what talks to them: a window class, a window, an adapter, a device, a queue and a
// swapchain. What the toolkit was doing here was translating the same few things into
// Java and back, and each translation has been somewhere a frame went wrong.
//
// Nothing here draws. The pixels are Skia's, as they already were.
//
// The same native symbols the macOS file exports, because the Kotlin side reaches them by
// name and only one of the two files is ever compiled into an image. What the five
// pointers in `struct dxc_native_window` mean is this platform's business; what
// `struct dxc_event` looks like is not, and it is declared here field for field as the
// macOS file declares it so that one piece of Kotlin can read either.
//
// Two of the four walls the macOS window ran into are not here. A window may be created
// on any thread on Windows, and the thread that created it is the thread its messages are
// delivered to, so there is no main-thread hop: the renderer opens its own window on the
// thread it draws from. Nor is there a view that hands out a drawable only inside its own
// callback; a swapchain answers whenever it is asked. The other two walls stand. Skia's
// recording is not its submission, and a word value lives only in straight-line code, and
// both of those are the Kotlin side's to keep.

// Windows 10, because the per-monitor DPI calls below arrived with it and a window that
// asks the monitor how big a point is was the whole reason for asking.
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#define WIN32_LEAN_AND_MEAN
// Every call here is the wide one by name. This is for the few things that are constants
// rather than calls, `IDC_ARROW` among them, which resolve to one width or the other
// through a macro and would otherwise hand a narrow string to a wide function.
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
// The Direct3D and DXGI interfaces as C macros. Without this the headers offer only the
// C++ member functions, and there is no C++ in this project's boundary.
#define COBJMACROS

#include <windows.h>
#include <windowsx.h>
#include <imm.h>
#include <d3d12.h>
#include <dxgi1_4.h>
#include <d3d11.h>
#include <d3d11on12.h>
#include <ole2.h>
#include <shellapi.h>
#include <psapi.h>
#include <uiautomation.h>
#include <uiautomationcoreapi.h>
#include <oleauto.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdlib.h>
#include <stddef.h>

#include "win32_resize.h"
#include "win32_ime_text.h"

// What happened in the window, waiting to be read.
//
// A queue and not a call. Calling Kotlin from here would mean the shell deciding when the
// Host runs, and the Host's state belongs to the thread that draws; it is also a call
// that can arrive in the middle of a message the window is still handling, which is a
// place no renderer wants to be resumed. The window writes events down and the renderer
// empties them once a frame, which is the same shape the macOS side already has.
enum {
    DXC_EVENT_POINTER_MOVE = 1,
    DXC_EVENT_POINTER_DOWN = 2,
    DXC_EVENT_POINTER_UP = 3,
    DXC_EVENT_SCROLL = 4,
    DXC_EVENT_KEY_DOWN = 5,
    DXC_EVENT_KEY_UP = 6,
    DXC_EVENT_TEXT_COMMIT = 7,
    DXC_EVENT_TEXT_COMPOSE = 8,
    // Files over the window, let go on it, and gone from it without being let go.
    DXC_EVENT_FILES_ENTERED = 10,
    DXC_EVENT_FILES_DROPPED = 11,
    DXC_EVENT_FILES_EXITED = 12,
};

// Room for what an input method is composing, which is a syllable or a word and never a
// document. Declared here as well as on the other desktop because one Kotlin reader reads
// both, and a test compares the two declarations for exactly that reason.
#define DXC_TEXT_BYTES 96

struct dxc_event {
    int32_t kind;
    // In pixels from the top left of the client area, which is what a scene measures in.
    float x;
    float y;
    int32_t buttons;
    int32_t modifiers;
    // The platform's own key number, and the character it would type. Which Compose key
    // that is gets decided on the other side, where the table lives.
    int32_t key_code;
    int32_t code_point;
    // UTF-8, ending at the first zero. Empty for everything that is not text.
    //
    char text[DXC_TEXT_BYTES];
};

// Room for a burst rather than for a session. A queue that fills is a queue nobody is
// draining, and holding a thousand stale mouse moves helps no one.
#define DXC_EVENT_CAPACITY 256

// One buffer, as Flutter's window swapchain has (ANGLE's NativeWindow11Win32 for a
// window without DirectComposition). It is a copy model swapchain: Present copies the
// buffer into the window's own redirection surface, which DWM resizes with the window,
// so the window's size and its content reach the screen together. Skia draws into a
// Direct3D 12 texture of the same size, which is copied into that buffer each frame.
#define DXC_BUFFER_COUNT 1

// The format the swapchain and Skia have to agree on. Named here as a number because the
// Kotlin side has to pass the same one to Skia and cannot see this header.
#define DXC_SWAPCHAIN_FORMAT DXGI_FORMAT_R8G8B8A8_UNORM

// No lock. On macOS the events arrive on AppKit's thread and are read on the renderer's,
// so that queue is guarded; here the window belongs to the thread that draws and the
// message pump runs inside the read below, so one thread writes and the same one reads.
// A modal loop (the user dragging the window's edge) dispatches on that thread too.
static struct dxc_event dxc_events[DXC_EVENT_CAPACITY];
static int dxc_event_head;
static int dxc_event_count;

static HWND dxc_window;
static IDXGISwapChain1 *dxc_swapchain;
// The Direct3D 11 device the swapchain belongs to, made on the renderer's Direct3D 12
// device and queue through D3D11On12, and the texture Skia draws into, wrapped for it.
static ID3D11Device *dxc_d3d11;
static ID3D11DeviceContext *dxc_d3d11_context;
static ID3D11On12Device *dxc_on12;
static ID3D11Resource *dxc_wrapped;
// ID3D11On12Device, named here because not every SDK's dxguid.lib carries it.
static const IID dxc_iid_on12_device =
    {0x85611e73, 0x70a9, 0x490e, {0x96, 0x14, 0xa9, 0xe3, 0x02, 0x77, 0x79, 0x04}};
// What the swapchain was made with, and what every later refit has to say again: a refit
// that names other flags is refused.
static UINT dxc_swapchain_flags;
// Signalled when the swapchain will take another frame without queueing it. NULL where
// the swapchain was made without the latency flag.
static HANDLE dxc_latency_wait;
// A frame is being drawn on this call stack. A resize message that arrives while it is
// draws nothing: the frame in flight already reads the size that message wrote down.
static int dxc_drawing;
static ID3D12Device *dxc_device;
static ID3D12CommandQueue *dxc_queue;
static ID3D12Resource *dxc_buffers[DXC_BUFFER_COUNT];
static ID3D12CommandAllocator *dxc_allocator;
static ID3D12GraphicsCommandList *dxc_commands;
static ID3D12Fence *dxc_fence;
static HANDLE dxc_fence_signalled;
static UINT64 dxc_fence_value;
// The fence value signalled after the barrier list last executed.
static UINT64 dxc_last_list_mark;
static UINT dxc_frame_index;
// The size the window has been given and the size it is drawn at, which are the same
// except while a resize is being taken. A swapchain cannot be refitted while the buffer
// being refitted is the one being drawn into, so the size is written down here and acted
// on where a frame begins.
static struct dxc_resize dxc_sizing;
// Set when the window has gone, so the frame loop stops rather than drawing into nothing.
static int dxc_window_gone;
// A resize is being drawn: WM_SIZE has recorded a size and is drawing it before it
// returns. The frame presents only if it was drawn at exactly that size, and only once.
static int dxc_resizing;
static int32_t dxc_resize_target_width;
static int32_t dxc_resize_target_height;
// The size last presented, so one size is never presented twice by a resize.
static int32_t dxc_presented_width;
static int32_t dxc_presented_height;
// DXC_REPORT_LATENCY: each resize step prints how long the refit, the draw, the present
// and the DwmFlush took together.
static int dxc_report_latency;
/**
 * One line on stderr for every change of a state that decides how resize frames are
 * produced. On by default: these happen a handful of times a session, and they are what
 * tells a capture that went wrong apart from one that did not.
 */
static void dxc_mode(const char *what, const char *from, const char *to, const char *reason) {
    fprintf(stderr, "compose-rust: resize-mode: %s %s -> %s because %s\n", what, from, to, reason);
}

// Load resilience, compared with DXC_RESIZE_PRIORITY: unset, the documented-safe ones are
// on (UI thread above normal during a drag, a high priority command queue); "0" turns
// everything off; "1" also raises the GPU thread priority of the Direct3D 11 device,
// which the docs warn can slow rendering if misused, so it is never on by default.
static int dxc_priority_level = -1;

static int dxc_priority(void) {
    if (dxc_priority_level < 0) {
        const char *asked = getenv("DXC_RESIZE_PRIORITY");
        dxc_priority_level = asked == NULL ? 1 : (asked[0] == '0' ? 0 : (asked[0] == '1' ? 2 : 1));
    }
    return dxc_priority_level;
}

// The UI thread's priority before a drag raised it.
static int dxc_thread_priority_before = THREAD_PRIORITY_ERROR_RETURN;

// The draw texture's size, defined with the texture further down.
static int32_t dxc_texture_width;
static int32_t dxc_texture_height;

// The adapter the device was made on, kept for asking it how much video memory is in use.
static IDXGIAdapter1 *dxc_adapter;

/** Megabytes of video memory this process uses in a segment group, or -1 where it cannot say. */
static double dxc_video_mb(DXGI_MEMORY_SEGMENT_GROUP group) {
    IDXGIAdapter3 *adapter3 = NULL;
    double answer = -1.0;
    if (dxc_adapter != NULL &&
        SUCCEEDED(IDXGIAdapter1_QueryInterface(dxc_adapter, &IID_IDXGIAdapter3, (void **)&adapter3))) {
        DXGI_QUERY_VIDEO_MEMORY_INFO info;
        if (SUCCEEDED(IDXGIAdapter3_QueryVideoMemoryInfo(adapter3, 0, group, &info))) {
            answer = (double)info.CurrentUsage / (1024.0 * 1024.0);
        }
        IDXGIAdapter3_Release(adapter3);
    }
    return answer;
}

/**
 * One line of what this process holds, for measuring: working set and private bytes, and
 * the video memory it uses on and off the adapter.
 */
void dxc_native_report_metrics(const char *label) {
    PROCESS_MEMORY_COUNTERS_EX counters;
    memset(&counters, 0, sizeof counters);
    counters.cb = sizeof counters;
    K32GetProcessMemoryInfo(GetCurrentProcess(), (PROCESS_MEMORY_COUNTERS *)&counters, sizeof counters);
    fprintf(stderr,
            "compose-rust: metrics memory %s working_set_mb=%.1f private_mb=%.1f "
            "video_local_mb=%.1f video_nonlocal_mb=%.1f draw_texture=%dx%d\n",
            label == NULL ? "" : label,
            (double)counters.WorkingSetSize / (1024.0 * 1024.0),
            (double)counters.PrivateUsage / (1024.0 * 1024.0),
            dxc_video_mb(DXGI_MEMORY_SEGMENT_GROUP_LOCAL),
            dxc_video_mb(DXGI_MEMORY_SEGMENT_GROUP_NON_LOCAL),
            (int)dxc_texture_width, (int)dxc_texture_height);
}

// The adapter the Direct3D 12 device was made on, and the monitor the window was last on.
static LUID dxc_device_luid;
static HMONITOR dxc_last_monitor;

/**
 * Logs the window's monitor, its DPI, the adapter driving that monitor and the adapter
 * the device draws with. When the two adapters differ, every present crosses adapters
 * before DWM can compose it.
 */
static void dxc_log_monitor(HWND window, const char *reason) {
    HMONITOR monitor = MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST);
    dxc_last_monitor = monitor;
    LUID output_luid = {0, 0};
    int found = 0;
    IDXGIFactory1 *factory = NULL;
    if (SUCCEEDED(CreateDXGIFactory1(&IID_IDXGIFactory1, (void **)&factory))) {
        IDXGIAdapter1 *adapter = NULL;
        for (UINT a = 0; !found && IDXGIFactory1_EnumAdapters1(factory, a, &adapter) != DXGI_ERROR_NOT_FOUND; a++) {
            IDXGIOutput *output = NULL;
            for (UINT o = 0; !found && IDXGIAdapter1_EnumOutputs(adapter, o, &output) != DXGI_ERROR_NOT_FOUND; o++) {
                DXGI_OUTPUT_DESC described;
                if (SUCCEEDED(IDXGIOutput_GetDesc(output, &described)) && described.Monitor == monitor) {
                    DXGI_ADAPTER_DESC1 adapter_described;
                    if (SUCCEEDED(IDXGIAdapter1_GetDesc1(adapter, &adapter_described))) {
                        output_luid = adapter_described.AdapterLuid;
                        found = 1;
                    }
                }
                IDXGIOutput_Release(output);
            }
            IDXGIAdapter1_Release(adapter);
        }
        IDXGIFactory1_Release(factory);
    }
    int same = found && output_luid.LowPart == dxc_device_luid.LowPart &&
               output_luid.HighPart == dxc_device_luid.HighPart;
    fprintf(stderr,
            "compose-rust: resize-mode: monitor %p dpi %u, monitor adapter %08lx:%08lx, "
            "device adapter %08lx:%08lx (%s) because %s\n",
            (void *)monitor, (unsigned)GetDpiForWindow(window),
            (unsigned long)output_luid.HighPart, (unsigned long)output_luid.LowPart,
            (unsigned long)dxc_device_luid.HighPart, (unsigned long)dxc_device_luid.LowPart,
            !found ? "monitor adapter not found" : (same ? "same adapter" : "DIFFERENT adapter"),
            reason);
}

// The last WM_SIZE kind, for logging maximise, minimise and restore.
static WPARAM dxc_last_size_kind = SIZE_RESTORED;

static const char *dxc_size_kind_name(WPARAM kind) {
    switch (kind) {
    case SIZE_MAXIMIZED: return "maximised";
    case SIZE_MINIMIZED: return "minimised";
    case SIZE_RESTORED: return "restored";
    default: return "other";
    }
}

/** Logs a failed DXGI call, with the device removed reason when that is what it was. */
static void dxc_log_dxgi_failure(const char *call, HRESULT result) {
    fprintf(stderr, "compose-rust: resize-mode: %s failed with 0x%08lx\n", call, (unsigned long)result);
    if ((result == DXGI_ERROR_DEVICE_REMOVED || result == DXGI_ERROR_DEVICE_RESET) &&
        dxc_device != NULL) {
        fprintf(stderr, "compose-rust: resize-mode: device ok -> removed because 0x%08lx\n",
                (unsigned long)ID3D12Device_GetDeviceRemovedReason(dxc_device));
    }
}

// A resize step that takes longer than this is logged. It is not cut short: returning
// before the frame is presented and flushed lets DWM show the resized redirection surface,
// which is black, so a slow step is slow rather than wrong.
#define DXC_RESIZE_STEP_CAP_MS 100.0
// Where the time of the step in flight went, in ms, for DXC_REPORT_LATENCY.
static double dxc_step_gpu_idle_ms;
static double dxc_step_refit_ms;
static double dxc_step_draw_ms;
static double dxc_step_present_ms;
static double dxc_step_copy_ms;
static LARGE_INTEGER dxc_step_mark;

// A frame, asked for by the window rather than by the loop that usually draws them.
//
// Registered by the renderer, which is the only thing that can draw: the pixels are
// Skia's and this file has never had any. It is needed because Windows runs a loop of its
// own inside `DefWindowProc` while the reader drags the window's edge, and for the whole
// of that drag the renderer's frame loop is stopped inside the message that began it. A
// size written down there is a size nothing draws until the drag ends.
//
// This is the renderer's platform code calling the renderer, on the one thread both live
// on. Nothing of the Host's crosses here and no new boundary entry point is involved: the
// window asks its own renderer to draw, which is what the frame loop would have done had
// it been given a turn.
typedef void (*dxc_draw_frame_fn)(void *isolate_thread);
static dxc_draw_frame_fn dxc_draw_frame;
// The renderer's thread, as the renderer named it when it registered. Handed back with
// every call because the other side is a Java runtime and cannot be entered without it.
static void *dxc_draw_thread;

/**
 * Lets the renderer be asked for a frame from inside a message.
 *
 * A null callback is how it is taken away again, which the renderer does before it closes
 * the scene: a message arriving after that would be a frame drawn into a scene that has
 * gone.
 */
void dxc_native_set_draw_callback(dxc_draw_frame_fn callback, void *isolate_thread) {
    dxc_draw_frame = callback;
    dxc_draw_thread = isolate_thread;
}

/**
 * Asks for a frame now, where a frame can be drawn at all.
 *
 * Nothing happens before the renderer has registered, which covers the first few messages
 * a window receives while it is still being built.
 */
static void dxc_draw_one_frame(void) {
    if (dxc_draw_frame != NULL && !dxc_drawing) {
        dxc_drawing = 1;
        dxc_draw_frame(dxc_draw_thread);
        dxc_drawing = 0;
    }
}

static int dxc_ime_composing;
static WCHAR dxc_pending_high_surrogate;
static LPCWSTR dxc_cursor = IDC_ARROW;

void dxc_native_set_cursor(int32_t shape) {
    switch (shape) {
    case 1: dxc_cursor = IDC_HAND; break;
    case 2: dxc_cursor = IDC_IBEAM; break;
    case 3: dxc_cursor = IDC_CROSS; break;
    case 4: dxc_cursor = IDC_SIZEWE; break;
    case 5: dxc_cursor = IDC_SIZENS; break;
    default: dxc_cursor = IDC_ARROW; break;
    }
    SetCursor(LoadCursorW(NULL, dxc_cursor));
}

static void dxc_push_event(struct dxc_event event) {
    if (dxc_event_count < DXC_EVENT_CAPACITY) {
        int slot = (dxc_event_head + dxc_event_count) % DXC_EVENT_CAPACITY;
        dxc_events[slot] = event;
        dxc_event_count++;
    } else {
        // Full: the oldest goes. A dropped move from a while ago is a position that has
        // already been overtaken, and dropping the newest would leave the pointer
        // somewhere it no longer is.
        dxc_events[dxc_event_head] = event;
        dxc_event_head = (dxc_event_head + 1) % DXC_EVENT_CAPACITY;
    }
}

// ---------------------------------------------------------------------------
// Accessibility: UI Automation
// ---------------------------------------------------------------------------
//
// What the window tells a reader who cannot see it.
//
// A snapshot rather than a question. UI Automation asks for elements on the
// thread the window was made on, at moments nobody chose, and the tree it
// describes lives where the scene does. The scene pushes what it has whenever
// it changes, and the window procedure answers from that.

struct dxc_element {
    int32_t role;
    // In pixels from the top left of the client area, which is what the scene
    // measures in on this platform.
    float x;
    float y;
    float width;
    float height;
    char label[DXC_TEXT_BYTES];
};

// The roles a scene can describe, as numbers, because a name would be a string
// crossing for every element on every push. Which UIA control type each one is
// is decided here, in one place.
enum {
    DXC_ROLE_GROUP = 0,
    DXC_ROLE_BUTTON = 1,
    DXC_ROLE_TEXT = 2,
    DXC_ROLE_FIELD = 3,
    DXC_ROLE_CHECKBOX = 4,
    DXC_ROLE_IMAGE = 5,
};

static int dxc_uia_control_type(int32_t role) {
    switch (role) {
        case DXC_ROLE_BUTTON:   return UIA_ButtonControlTypeId;
        case DXC_ROLE_TEXT:     return UIA_TextControlTypeId;
        case DXC_ROLE_FIELD:    return UIA_EditControlTypeId;
        case DXC_ROLE_CHECKBOX: return UIA_CheckBoxControlTypeId;
        case DXC_ROLE_IMAGE:    return UIA_ImageControlTypeId;
        default:                return UIA_GroupControlTypeId;
    }
}

// How many things a screen may say it has. Enough for a screen and not for a
// document, because a list of ten thousand rows is windowed before it reaches
// the scene.
#define DXC_MAX_ELEMENTS 256

// The current accessibility snapshot. Written by the push and read by UIA
// callbacks. Both happen on the same thread (the one that owns the window and
// pumps its messages), so no lock is needed.
static struct dxc_element dxc_a11y_elements[DXC_MAX_ELEMENTS];
static int32_t dxc_a11y_count;
static int dxc_a11y_dirty;
static int dxc_a11y_update_posted;
#define DXC_WM_ACCESSIBILITY_UPDATE (WM_APP + 1)

// ---------------------------------------------------------------------------
// COM plumbing
// ---------------------------------------------------------------------------
//
// UI Automation talks to this window through COM interfaces. Each element the
// scene describes becomes an object that answers for itself, and the window
// itself is the root of the tree. COM in C means vtables built by hand: a
// struct of function pointers, pointed to by the object, with `this` as the
// first argument to every one of them.
//
// Three interfaces are implemented:
//   IRawElementProviderSimple      -- identity and properties
//   IRawElementProviderFragment    -- navigation (parent, siblings, bounds)
//   IRawElementProviderFragmentRoot -- finding elements at a point or by focus

// Forward declarations so the vtables can refer to the types.
typedef struct DxcProvider DxcProvider;
typedef struct DxcRootProvider DxcRootProvider;

// ---------------------------------------------------------------------------
// DxcProvider: one element in the flat list
// ---------------------------------------------------------------------------

struct DxcProvider {
    IRawElementProviderSimpleVtbl *simple_vtbl;
    IRawElementProviderFragmentVtbl *fragment_vtbl;
    LONG ref_count;
    int32_t index;   // position in the snapshot at the time this was built
    struct dxc_element snapshot; // copied at construction time
    DxcRootProvider *root;
};

// ---------------------------------------------------------------------------
// DxcRootProvider: the window itself as the root of the UIA tree
// ---------------------------------------------------------------------------

struct DxcRootProvider {
    IRawElementProviderSimpleVtbl *simple_vtbl;
    IRawElementProviderFragmentVtbl *fragment_vtbl;
    IRawElementProviderFragmentRootVtbl *fragment_root_vtbl;
    LONG ref_count;
    HWND window;
    // The children, rebuilt every time the scene pushes.
    DxcProvider **children;
    int32_t child_count;
};

// The one root. One window means one root, and the root lives as long as the
// window does.
static DxcRootProvider *dxc_root_provider;

// Forward declarations of vtable functions.
static HRESULT STDMETHODCALLTYPE dxc_provider_qi(IRawElementProviderSimple *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_provider_addref(IRawElementProviderSimple *self);
static ULONG STDMETHODCALLTYPE dxc_provider_release(IRawElementProviderSimple *self);
static HRESULT STDMETHODCALLTYPE dxc_provider_get_provider_options(IRawElementProviderSimple *self, enum ProviderOptions *out);
static HRESULT STDMETHODCALLTYPE dxc_provider_get_pattern_provider(IRawElementProviderSimple *self, PATTERNID id, IUnknown **out);
static HRESULT STDMETHODCALLTYPE dxc_provider_get_property_value(IRawElementProviderSimple *self, PROPERTYID id, VARIANT *out);
static HRESULT STDMETHODCALLTYPE dxc_provider_get_host_raw_element_provider(IRawElementProviderSimple *self, IRawElementProviderSimple **out);

static HRESULT STDMETHODCALLTYPE dxc_frag_qi(IRawElementProviderFragment *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_frag_addref(IRawElementProviderFragment *self);
static ULONG STDMETHODCALLTYPE dxc_frag_release(IRawElementProviderFragment *self);
static HRESULT STDMETHODCALLTYPE dxc_frag_navigate(IRawElementProviderFragment *self, enum NavigateDirection dir, IRawElementProviderFragment **out);
static HRESULT STDMETHODCALLTYPE dxc_frag_get_runtime_id(IRawElementProviderFragment *self, SAFEARRAY **out);
static HRESULT STDMETHODCALLTYPE dxc_frag_get_bounding_rect(IRawElementProviderFragment *self, struct UiaRect *out);
static HRESULT STDMETHODCALLTYPE dxc_frag_get_embedded_fragment_roots(IRawElementProviderFragment *self, SAFEARRAY **out);
static HRESULT STDMETHODCALLTYPE dxc_frag_set_focus(IRawElementProviderFragment *self);
static HRESULT STDMETHODCALLTYPE dxc_frag_get_fragment_root(IRawElementProviderFragment *self, IRawElementProviderFragmentRoot **out);

// Root-specific forward declarations.
static HRESULT STDMETHODCALLTYPE dxc_root_qi(IRawElementProviderSimple *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_root_addref(IRawElementProviderSimple *self);
static ULONG STDMETHODCALLTYPE dxc_root_release(IRawElementProviderSimple *self);
static HRESULT STDMETHODCALLTYPE dxc_root_get_provider_options(IRawElementProviderSimple *self, enum ProviderOptions *out);
static HRESULT STDMETHODCALLTYPE dxc_root_get_pattern_provider(IRawElementProviderSimple *self, PATTERNID id, IUnknown **out);
static HRESULT STDMETHODCALLTYPE dxc_root_get_property_value(IRawElementProviderSimple *self, PROPERTYID id, VARIANT *out);
static HRESULT STDMETHODCALLTYPE dxc_root_get_host_raw_element_provider(IRawElementProviderSimple *self, IRawElementProviderSimple **out);

static HRESULT STDMETHODCALLTYPE dxc_root_frag_qi(IRawElementProviderFragment *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_root_frag_addref(IRawElementProviderFragment *self);
static ULONG STDMETHODCALLTYPE dxc_root_frag_release(IRawElementProviderFragment *self);
static HRESULT STDMETHODCALLTYPE dxc_root_frag_navigate(IRawElementProviderFragment *self, enum NavigateDirection dir, IRawElementProviderFragment **out);
static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_runtime_id(IRawElementProviderFragment *self, SAFEARRAY **out);
static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_bounding_rect(IRawElementProviderFragment *self, struct UiaRect *out);
static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_embedded_fragment_roots(IRawElementProviderFragment *self, SAFEARRAY **out);
static HRESULT STDMETHODCALLTYPE dxc_root_frag_set_focus(IRawElementProviderFragment *self);
static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_fragment_root(IRawElementProviderFragment *self, IRawElementProviderFragmentRoot **out);

static HRESULT STDMETHODCALLTYPE dxc_root_fr_qi(IRawElementProviderFragmentRoot *self, REFIID riid, void **out);
static ULONG STDMETHODCALLTYPE dxc_root_fr_addref(IRawElementProviderFragmentRoot *self);
static ULONG STDMETHODCALLTYPE dxc_root_fr_release(IRawElementProviderFragmentRoot *self);
static HRESULT STDMETHODCALLTYPE dxc_root_fr_element_from_point(IRawElementProviderFragmentRoot *self, double x, double y, IRawElementProviderFragment **out);
static HRESULT STDMETHODCALLTYPE dxc_root_fr_get_focus(IRawElementProviderFragmentRoot *self, IRawElementProviderFragment **out);

// ---------------------------------------------------------------------------
// Vtable instances
// ---------------------------------------------------------------------------

static IRawElementProviderSimpleVtbl dxc_provider_simple_vtbl = {
    dxc_provider_qi,
    dxc_provider_addref,
    dxc_provider_release,
    dxc_provider_get_provider_options,
    dxc_provider_get_pattern_provider,
    dxc_provider_get_property_value,
    dxc_provider_get_host_raw_element_provider,
};

static IRawElementProviderFragmentVtbl dxc_provider_fragment_vtbl = {
    dxc_frag_qi,
    dxc_frag_addref,
    dxc_frag_release,
    dxc_frag_navigate,
    dxc_frag_get_runtime_id,
    dxc_frag_get_bounding_rect,
    dxc_frag_get_embedded_fragment_roots,
    dxc_frag_set_focus,
    dxc_frag_get_fragment_root,
};

static IRawElementProviderSimpleVtbl dxc_root_simple_vtbl = {
    dxc_root_qi,
    dxc_root_addref,
    dxc_root_release,
    dxc_root_get_provider_options,
    dxc_root_get_pattern_provider,
    dxc_root_get_property_value,
    dxc_root_get_host_raw_element_provider,
};

static IRawElementProviderFragmentVtbl dxc_root_fragment_vtbl = {
    dxc_root_frag_qi,
    dxc_root_frag_addref,
    dxc_root_frag_release,
    dxc_root_frag_navigate,
    dxc_root_frag_get_runtime_id,
    dxc_root_frag_get_bounding_rect,
    dxc_root_frag_get_embedded_fragment_roots,
    dxc_root_frag_set_focus,
    dxc_root_frag_get_fragment_root,
};

static IRawElementProviderFragmentRootVtbl dxc_root_fragment_root_vtbl = {
    dxc_root_fr_qi,
    dxc_root_fr_addref,
    dxc_root_fr_release,
    dxc_root_fr_element_from_point,
    dxc_root_fr_get_focus,
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

// Recovers the DxcProvider from a pointer to one of its vtable-pointer fields.
// The fragment vtable pointer sits right after the simple vtable pointer.
static DxcProvider *dxc_provider_from_simple(IRawElementProviderSimple *iface) {
    return (DxcProvider *)iface;
}

static DxcProvider *dxc_provider_from_fragment(IRawElementProviderFragment *iface) {
    return (DxcProvider *)((char *)iface - offsetof(DxcProvider, fragment_vtbl));
}

static DxcRootProvider *dxc_root_from_simple(IRawElementProviderSimple *iface) {
    return (DxcRootProvider *)iface;
}

static DxcRootProvider *dxc_root_from_fragment(IRawElementProviderFragment *iface) {
    return (DxcRootProvider *)((char *)iface - offsetof(DxcRootProvider, fragment_vtbl));
}

static DxcRootProvider *dxc_root_from_fragment_root(IRawElementProviderFragmentRoot *iface) {
    return (DxcRootProvider *)((char *)iface - offsetof(DxcRootProvider, fragment_root_vtbl));
}

// Converts a UTF-8 label to a BSTR for UIA property answers. The caller owns
// the result and must free it with SysFreeString.
static BSTR dxc_bstr_from_utf8(const char *utf8) {
    if (utf8 == NULL || utf8[0] == '\0') {
        return SysAllocString(L"");
    }
    int wide_len = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, NULL, 0);
    if (wide_len <= 0) {
        return SysAllocString(L"");
    }
    BSTR result = SysAllocStringLen(NULL, (UINT)(wide_len - 1));
    if (result != NULL) {
        MultiByteToWideChar(CP_UTF8, 0, utf8, -1, result, wide_len);
    }
    return result;
}

// Converts a client-area rectangle to screen coordinates. The elements carry
// positions in client-area pixels, and UIA wants screen coordinates.
static void dxc_client_rect_to_screen(HWND window, const struct dxc_element *el,
                                       struct UiaRect *out) {
    POINT top_left;
    top_left.x = (LONG)el->x;
    top_left.y = (LONG)el->y;
    ClientToScreen(window, &top_left);
    out->left = (double)top_left.x;
    out->top = (double)top_left.y;
    out->width = (double)el->width;
    out->height = (double)el->height;
}

// ---------------------------------------------------------------------------
// DxcProvider: IRawElementProviderSimple
// ---------------------------------------------------------------------------

static HRESULT STDMETHODCALLTYPE dxc_provider_qi(
    IRawElementProviderSimple *self, REFIID riid, void **out
) {
    DxcProvider *provider = dxc_provider_from_simple(self);
    if (IsEqualIID(riid, &IID_IUnknown) ||
        IsEqualIID(riid, &IID_IRawElementProviderSimple)) {
        *out = &provider->simple_vtbl;
        InterlockedIncrement(&provider->ref_count);
        return S_OK;
    }
    if (IsEqualIID(riid, &IID_IRawElementProviderFragment)) {
        *out = &provider->fragment_vtbl;
        InterlockedIncrement(&provider->ref_count);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static ULONG STDMETHODCALLTYPE dxc_provider_addref(IRawElementProviderSimple *self) {
    DxcProvider *provider = dxc_provider_from_simple(self);
    return (ULONG)InterlockedIncrement(&provider->ref_count);
}

static ULONG STDMETHODCALLTYPE dxc_provider_release(IRawElementProviderSimple *self) {
    DxcProvider *provider = dxc_provider_from_simple(self);
    LONG count = InterlockedDecrement(&provider->ref_count);
    if (count <= 0) {
        free(provider);
    }
    return (ULONG)count;
}

static HRESULT STDMETHODCALLTYPE dxc_provider_get_provider_options(
    IRawElementProviderSimple *self, enum ProviderOptions *out
) {
    (void)self;
    *out = ProviderOptions_ServerSideProvider;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_provider_get_pattern_provider(
    IRawElementProviderSimple *self, PATTERNID id, IUnknown **out
) {
    (void)self;
    (void)id;
    *out = NULL;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_provider_get_property_value(
    IRawElementProviderSimple *self, PROPERTYID id, VARIANT *out
) {
    DxcProvider *provider = dxc_provider_from_simple(self);
    VariantInit(out);
    // An if chain rather than a switch: the SDK declares the property ids as const
    // variables in C, which a case label cannot name.
    if (id == UIA_ControlTypePropertyId) {
        out->vt = VT_I4;
        out->lVal = dxc_uia_control_type(provider->snapshot.role);
    } else if (id == UIA_NamePropertyId) {
        out->vt = VT_BSTR;
        out->bstrVal = dxc_bstr_from_utf8(provider->snapshot.label);
    } else if (id == UIA_IsControlElementPropertyId || id == UIA_IsContentElementPropertyId) {
        out->vt = VT_BOOL;
        out->boolVal = VARIANT_TRUE;
    } else if (id == UIA_IsKeyboardFocusablePropertyId) {
        out->vt = VT_BOOL;
        out->boolVal = VARIANT_FALSE;
    } else if (id == UIA_ProviderDescriptionPropertyId) {
        out->vt = VT_BSTR;
        out->bstrVal = SysAllocString(L"compose-rust element");
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_provider_get_host_raw_element_provider(
    IRawElementProviderSimple *self, IRawElementProviderSimple **out
) {
    (void)self;
    *out = NULL;
    return S_OK;
}

// ---------------------------------------------------------------------------
// DxcProvider: IRawElementProviderFragment
// ---------------------------------------------------------------------------

static HRESULT STDMETHODCALLTYPE dxc_frag_qi(
    IRawElementProviderFragment *self, REFIID riid, void **out
) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    return dxc_provider_qi((IRawElementProviderSimple *)&provider->simple_vtbl, riid, out);
}

static ULONG STDMETHODCALLTYPE dxc_frag_addref(IRawElementProviderFragment *self) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    return (ULONG)InterlockedIncrement(&provider->ref_count);
}

static ULONG STDMETHODCALLTYPE dxc_frag_release(IRawElementProviderFragment *self) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    return dxc_provider_release((IRawElementProviderSimple *)&provider->simple_vtbl);
}

static HRESULT STDMETHODCALLTYPE dxc_frag_navigate(
    IRawElementProviderFragment *self, enum NavigateDirection dir,
    IRawElementProviderFragment **out
) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    *out = NULL;
    if (provider->root == NULL) return S_OK;
    switch (dir) {
    case NavigateDirection_Parent:
        // The parent of every child is the root.
        *out = (IRawElementProviderFragment *)&provider->root->fragment_vtbl;
        InterlockedIncrement(&provider->root->ref_count);
        return S_OK;
    case NavigateDirection_NextSibling:
        if (provider->index >= 0 && provider->index < provider->root->child_count &&
            provider->root->children[provider->index] == provider &&
            provider->index + 1 < provider->root->child_count) {
            DxcProvider *next = provider->root->children[provider->index + 1];
            *out = (IRawElementProviderFragment *)&next->fragment_vtbl;
            InterlockedIncrement(&next->ref_count);
        }
        return S_OK;
    case NavigateDirection_PreviousSibling:
        if (provider->index > 0 && provider->index < provider->root->child_count &&
            provider->root->children[provider->index] == provider) {
            DxcProvider *prev = provider->root->children[provider->index - 1];
            *out = (IRawElementProviderFragment *)&prev->fragment_vtbl;
            InterlockedIncrement(&prev->ref_count);
        }
        return S_OK;
    default:
        // Children are not expected: the tree is flat.
        return S_OK;
    }
}

static HRESULT STDMETHODCALLTYPE dxc_frag_get_runtime_id(
    IRawElementProviderFragment *self, SAFEARRAY **out
) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    // A runtime ID is two ints: UiaAppendRuntimeId followed by something
    // unique within this provider. The index is unique within a snapshot.
    int ids[2];
    ids[0] = UiaAppendRuntimeId;
    ids[1] = provider->index + 1;  // one-based so zero is never used
    SAFEARRAYBOUND bound;
    bound.lLbound = 0;
    bound.cElements = 2;
    SAFEARRAY *array = SafeArrayCreate(VT_I4, 1, &bound);
    if (array == NULL) {
        *out = NULL;
        return E_OUTOFMEMORY;
    }
    for (LONG i = 0; i < 2; i++) {
        SafeArrayPutElement(array, &i, &ids[i]);
    }
    *out = array;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_frag_get_bounding_rect(
    IRawElementProviderFragment *self, struct UiaRect *out
) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    if (provider->root != NULL && provider->root->window != NULL) {
        dxc_client_rect_to_screen(provider->root->window, &provider->snapshot, out);
    } else {
        memset(out, 0, sizeof *out);
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_frag_get_embedded_fragment_roots(
    IRawElementProviderFragment *self, SAFEARRAY **out
) {
    (void)self;
    *out = NULL;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_frag_set_focus(IRawElementProviderFragment *self) {
    (void)self;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_frag_get_fragment_root(
    IRawElementProviderFragment *self, IRawElementProviderFragmentRoot **out
) {
    DxcProvider *provider = dxc_provider_from_fragment(self);
    if (provider->root != NULL) {
        *out = (IRawElementProviderFragmentRoot *)&provider->root->fragment_root_vtbl;
        InterlockedIncrement(&provider->root->ref_count);
    } else {
        *out = NULL;
    }
    return S_OK;
}

// ---------------------------------------------------------------------------
// DxcRootProvider: IRawElementProviderSimple
// ---------------------------------------------------------------------------

static HRESULT STDMETHODCALLTYPE dxc_root_qi(
    IRawElementProviderSimple *self, REFIID riid, void **out
) {
    DxcRootProvider *root = dxc_root_from_simple(self);
    if (IsEqualIID(riid, &IID_IUnknown) ||
        IsEqualIID(riid, &IID_IRawElementProviderSimple)) {
        *out = &root->simple_vtbl;
        InterlockedIncrement(&root->ref_count);
        return S_OK;
    }
    if (IsEqualIID(riid, &IID_IRawElementProviderFragment)) {
        *out = &root->fragment_vtbl;
        InterlockedIncrement(&root->ref_count);
        return S_OK;
    }
    if (IsEqualIID(riid, &IID_IRawElementProviderFragmentRoot)) {
        *out = &root->fragment_root_vtbl;
        InterlockedIncrement(&root->ref_count);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}

static ULONG STDMETHODCALLTYPE dxc_root_addref(IRawElementProviderSimple *self) {
    DxcRootProvider *root = dxc_root_from_simple(self);
    return (ULONG)InterlockedIncrement(&root->ref_count);
}

static ULONG STDMETHODCALLTYPE dxc_root_release(IRawElementProviderSimple *self) {
    DxcRootProvider *root = dxc_root_from_simple(self);
    LONG count = InterlockedDecrement(&root->ref_count);
    // The root is not freed here: it lives as long as the window does, and the
    // ref count going to zero just means UIA has let go of the reference it was
    // holding. It will come back.
    return (ULONG)count;
}

static HRESULT STDMETHODCALLTYPE dxc_root_get_provider_options(
    IRawElementProviderSimple *self, enum ProviderOptions *out
) {
    (void)self;
    *out = ProviderOptions_ServerSideProvider;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_get_pattern_provider(
    IRawElementProviderSimple *self, PATTERNID id, IUnknown **out
) {
    (void)self;
    (void)id;
    *out = NULL;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_get_property_value(
    IRawElementProviderSimple *self, PROPERTYID id, VARIANT *out
) {
    DxcRootProvider *root = dxc_root_from_simple(self);
    VariantInit(out);
    // An if chain for the same reason as the element provider's.
    if (id == UIA_ControlTypePropertyId) {
        out->vt = VT_I4;
        out->lVal = UIA_WindowControlTypeId;
    } else if (id == UIA_NamePropertyId) {
        wchar_t title[256] = L"";
        if (root->window != NULL) {
            GetWindowTextW(root->window, title, (int)(sizeof title / sizeof *title));
        }
        out->vt = VT_BSTR;
        out->bstrVal = SysAllocString(title);
    } else if (id == UIA_IsControlElementPropertyId || id == UIA_IsContentElementPropertyId) {
        out->vt = VT_BOOL;
        out->boolVal = VARIANT_TRUE;
    } else if (id == UIA_IsKeyboardFocusablePropertyId) {
        out->vt = VT_BOOL;
        out->boolVal = VARIANT_TRUE;
    } else if (id == UIA_ProviderDescriptionPropertyId) {
        out->vt = VT_BSTR;
        out->bstrVal = SysAllocString(L"compose-rust root");
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_get_host_raw_element_provider(
    IRawElementProviderSimple *self, IRawElementProviderSimple **out
) {
    DxcRootProvider *root = dxc_root_from_simple(self);
    *out = NULL;
    if (root->window == NULL) return UIA_E_ELEMENTNOTAVAILABLE;
    return UiaHostProviderFromHwnd(root->window, out);
}

// ---------------------------------------------------------------------------
// DxcRootProvider: IRawElementProviderFragment
// ---------------------------------------------------------------------------

static HRESULT STDMETHODCALLTYPE dxc_root_frag_qi(
    IRawElementProviderFragment *self, REFIID riid, void **out
) {
    DxcRootProvider *root = dxc_root_from_fragment(self);
    return dxc_root_qi((IRawElementProviderSimple *)&root->simple_vtbl, riid, out);
}

static ULONG STDMETHODCALLTYPE dxc_root_frag_addref(IRawElementProviderFragment *self) {
    DxcRootProvider *root = dxc_root_from_fragment(self);
    return (ULONG)InterlockedIncrement(&root->ref_count);
}

static ULONG STDMETHODCALLTYPE dxc_root_frag_release(IRawElementProviderFragment *self) {
    DxcRootProvider *root = dxc_root_from_fragment(self);
    return dxc_root_release((IRawElementProviderSimple *)&root->simple_vtbl);
}

static HRESULT STDMETHODCALLTYPE dxc_root_frag_navigate(
    IRawElementProviderFragment *self, enum NavigateDirection dir,
    IRawElementProviderFragment **out
) {
    DxcRootProvider *root = dxc_root_from_fragment(self);
    *out = NULL;
    switch (dir) {
    case NavigateDirection_FirstChild:
        if (root->child_count > 0) {
            DxcProvider *first = root->children[0];
            *out = (IRawElementProviderFragment *)&first->fragment_vtbl;
            InterlockedIncrement(&first->ref_count);
        }
        return S_OK;
    case NavigateDirection_LastChild:
        if (root->child_count > 0) {
            DxcProvider *last = root->children[root->child_count - 1];
            *out = (IRawElementProviderFragment *)&last->fragment_vtbl;
            InterlockedIncrement(&last->ref_count);
        }
        return S_OK;
    default:
        // The root has no parent and no siblings in our tree.
        return S_OK;
    }
}

static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_runtime_id(
    IRawElementProviderFragment *self, SAFEARRAY **out
) {
    (void)self;
    // The root is hosted by the HWND, so its runtime ID is NULL: the system
    // uses the HWND's own identity.
    *out = NULL;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_bounding_rect(
    IRawElementProviderFragment *self, struct UiaRect *out
) {
    DxcRootProvider *root = dxc_root_from_fragment(self);
    if (root->window != NULL) {
        RECT rc;
        GetClientRect(root->window, &rc);
        POINT pt = {0, 0};
        ClientToScreen(root->window, &pt);
        out->left = (double)pt.x;
        out->top = (double)pt.y;
        out->width = (double)(rc.right - rc.left);
        out->height = (double)(rc.bottom - rc.top);
    } else {
        memset(out, 0, sizeof *out);
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_embedded_fragment_roots(
    IRawElementProviderFragment *self, SAFEARRAY **out
) {
    (void)self;
    *out = NULL;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_frag_set_focus(IRawElementProviderFragment *self) {
    (void)self;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_frag_get_fragment_root(
    IRawElementProviderFragment *self, IRawElementProviderFragmentRoot **out
) {
    DxcRootProvider *root = dxc_root_from_fragment(self);
    *out = (IRawElementProviderFragmentRoot *)&root->fragment_root_vtbl;
    InterlockedIncrement(&root->ref_count);
    return S_OK;
}

// ---------------------------------------------------------------------------
// DxcRootProvider: IRawElementProviderFragmentRoot
// ---------------------------------------------------------------------------

static HRESULT STDMETHODCALLTYPE dxc_root_fr_qi(
    IRawElementProviderFragmentRoot *self, REFIID riid, void **out
) {
    DxcRootProvider *root = dxc_root_from_fragment_root(self);
    return dxc_root_qi((IRawElementProviderSimple *)&root->simple_vtbl, riid, out);
}

static ULONG STDMETHODCALLTYPE dxc_root_fr_addref(IRawElementProviderFragmentRoot *self) {
    DxcRootProvider *root = dxc_root_from_fragment_root(self);
    return (ULONG)InterlockedIncrement(&root->ref_count);
}

static ULONG STDMETHODCALLTYPE dxc_root_fr_release(IRawElementProviderFragmentRoot *self) {
    DxcRootProvider *root = dxc_root_from_fragment_root(self);
    return dxc_root_release((IRawElementProviderSimple *)&root->simple_vtbl);
}

static HRESULT STDMETHODCALLTYPE dxc_root_fr_element_from_point(
    IRawElementProviderFragmentRoot *self, double x, double y,
    IRawElementProviderFragment **out
) {
    DxcRootProvider *root = dxc_root_from_fragment_root(self);
    *out = NULL;
    if (root->window == NULL) return S_OK;
    // Convert screen coordinates to client coordinates for hit testing.
    POINT screen_pt;
    screen_pt.x = (LONG)x;
    screen_pt.y = (LONG)y;
    ScreenToClient(root->window, &screen_pt);
    float cx = (float)screen_pt.x;
    float cy = (float)screen_pt.y;
    // Walk the list from last to first: later elements are painted on top, so
    // the topmost one under the point is the one a reader should meet.
    for (int32_t i = root->child_count - 1; i >= 0; i--) {
        DxcProvider *child = root->children[i];
        const struct dxc_element *el = &child->snapshot;
        if (cx >= el->x && cx < el->x + el->width &&
            cy >= el->y && cy < el->y + el->height) {
            *out = (IRawElementProviderFragment *)&child->fragment_vtbl;
            InterlockedIncrement(&child->ref_count);
            return S_OK;
        }
    }
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_root_fr_get_focus(
    IRawElementProviderFragmentRoot *self, IRawElementProviderFragment **out
) {
    (void)self;
    // No element tracks focus yet. Returning NULL tells UIA that nothing inside
    // our fragment has the focus, which is honest rather than misleading.
    *out = NULL;
    return S_OK;
}

// ---------------------------------------------------------------------------
// Building and replacing the child list
// ---------------------------------------------------------------------------

static DxcProvider *dxc_create_provider(
    const struct dxc_element *element, int32_t index, DxcRootProvider *root
) {
    DxcProvider *provider = (DxcProvider *)calloc(1, sizeof(DxcProvider));
    if (provider == NULL) return NULL;
    provider->simple_vtbl = &dxc_provider_simple_vtbl;
    provider->fragment_vtbl = &dxc_provider_fragment_vtbl;
    provider->ref_count = 1;
    provider->index = index;
    provider->snapshot = *element;
    provider->root = root;
    return provider;
}

static void dxc_release_children(DxcRootProvider *root) {
    if (root->children != NULL) {
        for (int32_t i = 0; i < root->child_count; i++) {
            if (root->children[i] != NULL) {
                dxc_provider_release(
                    (IRawElementProviderSimple *)&root->children[i]->simple_vtbl);
            }
        }
        free(root->children);
        root->children = NULL;
    }
    root->child_count = 0;
}

static void dxc_rebuild_children(DxcRootProvider *root,
                                  const struct dxc_element *elements,
                                  int32_t count) {
    if (count <= 0) {
        dxc_release_children(root);
        return;
    }
    DxcProvider **children = (DxcProvider **)calloc((size_t)count, sizeof(DxcProvider *));
    if (children == NULL) return;
    for (int32_t i = 0; i < count; i++) {
        children[i] = dxc_create_provider(&elements[i], i, root);
        if (children[i] == NULL) {
            for (int32_t j = 0; j < i; j++) {
                dxc_provider_release((IRawElementProviderSimple *)&children[j]->simple_vtbl);
            }
            free(children);
            return;
        }
    }
    dxc_release_children(root);
    root->children = children;
    root->child_count = count;
}

static void dxc_publish_accessibility(DxcRootProvider *root) {
    if (!dxc_a11y_dirty || root == NULL) return;
    dxc_rebuild_children(root, dxc_a11y_elements, dxc_a11y_count);
    dxc_a11y_dirty = 0;
}

// ---------------------------------------------------------------------------
// The root provider, created lazily on the first push or the first
// WM_GETOBJECT, whichever comes first.
// ---------------------------------------------------------------------------

static DxcRootProvider *dxc_ensure_root_provider(HWND window) {
    if (dxc_root_provider != NULL) {
        dxc_root_provider->window = window;
        return dxc_root_provider;
    }
    DxcRootProvider *root = (DxcRootProvider *)calloc(1, sizeof(DxcRootProvider));
    if (root == NULL) return NULL;
    root->simple_vtbl = &dxc_root_simple_vtbl;
    root->fragment_vtbl = &dxc_root_fragment_vtbl;
    root->fragment_root_vtbl = &dxc_root_fragment_root_vtbl;
    root->ref_count = 1;
    root->window = window;
    dxc_root_provider = root;
    return root;
}

// ---------------------------------------------------------------------------
// dxc_native_set_accessibility
// ---------------------------------------------------------------------------
//
// Replaces what the window tells a reader who cannot see it.
//
// Called from the thread the scene lives on. The elements are copied into a
// snapshot and a message is posted to the window's thread so that the tree is
// rebuilt and UIA is notified after this call returns.
//
// The same name and the same signature as the macOS version. Kotlin calls one
// or the other depending on which file was compiled into the image.

void dxc_native_set_accessibility(const struct dxc_element *elements,
                                   int32_t count,
                                   void *window_pointer) {
    HWND window = (HWND)window_pointer;
    if (window == NULL) return;
    if (count < 0 || (count > 0 && elements == NULL)) return;
    // Cap to the maximum the static buffer can hold.
    if (count > DXC_MAX_ELEMENTS) count = DXC_MAX_ELEMENTS;
    // The scene and window share a thread. Copy the caller's stack now and
    // defer provider rebuilding and notification to the message pump.
    if (count > 0) {
        memcpy(dxc_a11y_elements, elements,
               (size_t)count * sizeof(struct dxc_element));
    }
    dxc_a11y_count = count;
    dxc_a11y_dirty = 1;
    if (!dxc_a11y_update_posted) {
        dxc_a11y_update_posted = PostMessageW(
            window, DXC_WM_ACCESSIBILITY_UPDATE, 0, 0) != 0;
    }
}

/** Which mouse buttons are down, as bit zero for the left one and bit one for the right. */
static int32_t dxc_pressed_buttons(void) {
    int32_t buttons = 0;
    if (GetKeyState(VK_LBUTTON) < 0) buttons |= 1;
    if (GetKeyState(VK_RBUTTON) < 0) buttons |= 2;
    if (GetKeyState(VK_MBUTTON) < 0) buttons |= 4;
    return buttons;
}

/**
 * Which modifier keys are held.
 *
 * Bits of our own choosing rather than a Win32 value, because there is no Win32 value:
 * the platform answers one key at a time. Shift, control, alt, then the Windows key.
 * What they mean to Compose is decided on the other side, where the table lives.
 */
static int32_t dxc_held_modifiers(void) {
    int32_t modifiers = 0;
    if (GetKeyState(VK_SHIFT) < 0) modifiers |= 1;
    if (GetKeyState(VK_CONTROL) < 0) modifiers |= 2;
    if (GetKeyState(VK_MENU) < 0) modifiers |= 4;
    if (GetKeyState(VK_LWIN) < 0 || GetKeyState(VK_RWIN) < 0) modifiers |= 8;
    return modifiers;
}

/** How far one notch of the wheel is meant to move, as the reader set it. */
static float dxc_wheel_lines(void) {
    UINT lines = 3;
    if (!SystemParametersInfoW(SPI_GETWHEELSCROLLLINES, 0, &lines, 0)) {
        lines = 3;
    }
    // A wheel set to move a page at a time answers with a sentinel rather than a count.
    if (lines == 0 || lines == WHEEL_PAGESCROLL) {
        lines = 3;
    }
    return (float)lines;
}

static void dxc_push_pointer(int32_t kind, LPARAM where) {
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = kind;
    record.x = (float)GET_X_LPARAM(where);
    record.y = (float)GET_Y_LPARAM(where);
    record.buttons = dxc_pressed_buttons();
    record.modifiers = dxc_held_modifiers();
    dxc_push_event(record);
}

static void dxc_push_scroll(float x, float y) {
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = DXC_EVENT_SCROLL;
    // The wheel's travel rides in the same two fields the pointer uses, because a scroll
    // has no position of its own beyond where the pointer already is.
    record.x = x;
    record.y = y;
    record.buttons = dxc_pressed_buttons();
    record.modifiers = dxc_held_modifiers();
    dxc_push_event(record);
}

static void dxc_push_key(int32_t kind, WPARAM key) {
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = kind;
    record.buttons = dxc_pressed_buttons();
    record.modifiers = dxc_held_modifiers();
    record.key_code = (int32_t)key;
    // The character the key carries with no modifier applied, which is what the macOS
    // side puts here. Windows delivers typed text as a separate message, so it is asked
    // for rather than waited for. A dead key answers with its top bit set, and the
    // character underneath is the part worth keeping.
    UINT typed = MapVirtualKeyW((UINT)key, MAPVK_VK_TO_CHAR);
    record.code_point = (int32_t)(typed & 0x7fffffffu);
    dxc_push_event(record);
}

static void dxc_push_text(int32_t kind, const uint16_t *text, size_t units) {
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = kind;
    dxc_utf16_to_utf8(text, units, record.text, sizeof record.text);
    dxc_push_event(record);
}

static void dxc_read_ime_text(HIMC context, DWORD part, int32_t kind) {
    LONG bytes = ImmGetCompositionStringW(context, part, NULL, 0);
    if (bytes < 0 || bytes % sizeof(WCHAR) != 0) return;
    if (bytes == 0) {
        if (kind == DXC_EVENT_TEXT_COMPOSE) dxc_push_text(kind, NULL, 0);
        return;
    }
    WCHAR *wide = (WCHAR *)malloc((size_t)bytes);
    if (wide == NULL) return;
    LONG copied = ImmGetCompositionStringW(context, part, wide, (DWORD)bytes);
    if (copied >= 0 && copied <= bytes && copied % sizeof(WCHAR) == 0) {
        dxc_push_text(kind, (const uint16_t *)wide, (size_t)copied / sizeof(WCHAR));
    }
    free(wide);
}

// Where the caret is in the client area, in pixels, as the renderer last said.
static LONG dxc_ime_spot_x;
static LONG dxc_ime_spot_y;

static void dxc_position_ime(HWND window) {
    HIMC context = ImmGetContext(window);
    if (context == NULL) return;
    COMPOSITIONFORM position;
    memset(&position, 0, sizeof position);
    position.dwStyle = CFS_POINT;
    // The caret position has not crossed from Compose yet. Keep the IME window at the
    // client area's top left, as the macOS text client does for the same reason.
    position.ptCurrentPos.x = dxc_ime_spot_x;
    position.ptCurrentPos.y = dxc_ime_spot_y;
    ImmSetCompositionWindow(context, &position);
    CANDIDATEFORM candidate;
    memset(&candidate, 0, sizeof candidate);
    candidate.dwStyle = CFS_CANDIDATEPOS;
    candidate.ptCurrentPos.x = dxc_ime_spot_x;
    candidate.ptCurrentPos.y = dxc_ime_spot_y + 20;
    ImmSetCandidateWindow(context, &candidate);
    ImmReleaseContext(window, context);
}

// What the application asked of its window, held until the window is made.
static struct {
    int32_t resizable;
    int32_t min_width;
    int32_t min_height;
    int32_t system_chrome;
    int32_t backdrop;
} dxc_options = {1, 0, 0, 0, 0};

/**
 * Says how the next window should be made. Called once, before it is opened.
 *
 * `system_chrome` keeps the system's caption. Without it the caption strip becomes part of
 * the client area and the renderer draws the title and the three buttons in it. `backdrop`
 * means nothing here: this window has no material to put behind the page.
 */
void dxc_native_window_configure(
    int32_t resizable,
    int32_t min_width,
    int32_t min_height,
    int32_t system_chrome,
    int32_t backdrop
) {
    dxc_options.resizable = resizable;
    dxc_options.min_width = min_width;
    dxc_options.min_height = min_height;
    dxc_options.system_chrome = system_chrome;
    dxc_options.backdrop = backdrop;
}

/** The window style the options come to. */
static DWORD dxc_window_style(void) {
    DWORD style = WS_OVERLAPPEDWINDOW;
    if (!dxc_options.resizable) {
        style &= ~(DWORD)(WS_THICKFRAME | WS_MAXIMIZEBOX);
    }
    return style;
}

/*
 * Taking the caption strip into the client area while the frame stays whole.
 *
 * The obvious way to draw your own title bar is an undecorated window, and on Windows
 * that is the wrong trade. The frame is not only the bar: it is the drop shadow, the
 * resize border, Snap Layouts and the animation when the window is restored. None of
 * those can be drawn from inside the window.
 *
 * What VS Code and Windows Terminal do instead is keep every one of those and take only
 * the caption. A window reports its client area in WM_NCCALCSIZE. Letting the default
 * handler compute the frame and then putting the top edge back where it started leaves
 * the sides and the bottom as the system's while the strip the caption occupied becomes
 * ours to draw in. The styles are untouched, so the shadow, the border and Snap are
 * untouched with them.
 *
 * Two details are not optional. A maximised window is deliberately laid out larger than
 * the monitor by the border thickness, so the same edges fall off screen; restoring the
 * top edge unchanged there puts the caption off screen too, and it has to be inset. And
 * the top resize band lived in the non-client area that no longer exists, so the hit test
 * has to answer for it or the window becomes the one window on the desktop that cannot be
 * resized from the top.
 *
 * The geometry below is duplicated in Kotlin, which draws into the same strip.
 * scripts/tests/windows-caption-metrics.test.sh fails if the two stop agreeing.
 */

// Windows 11 caption metrics, in device independent pixels.
#define DXC_CAPTION_HEIGHT_DIP 32
#define DXC_CAPTION_BUTTON_WIDTH_DIP 46
#define DXC_CAPTION_BUTTON_COUNT 3

static int dxc_scaled(HWND window, int dip) {
    UINT dpi = GetDpiForWindow(window);
    if (dpi == 0) {
        dpi = USER_DEFAULT_SCREEN_DPI;
    }
    return (int)MulDiv(dip, (int)dpi, USER_DEFAULT_SCREEN_DPI);
}

/** How far a maximised window hangs off every edge of its monitor. */
static int dxc_maximised_overhang(HWND window) {
    // At the window's own DPI: GetSystemMetrics answers for the primary monitor's, which
    // is wrong on a monitor of another scale.
    UINT dpi = GetDpiForWindow(window);
    if (dpi == 0) {
        dpi = USER_DEFAULT_SCREEN_DPI;
    }
    return GetSystemMetricsForDpi(SM_CYSIZEFRAME, dpi) + GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
}

static LRESULT dxc_caption_hit_test(HWND window, LPARAM lparam) {
    LRESULT where = DefWindowProcW(window, WM_NCHITTEST, 0, lparam);
    // Everywhere the frame still answers for keeps its answer: the sides, the bottom and
    // all four corners are still the system's.
    if (where != HTCLIENT) {
        return where;
    }
    POINT point = {GET_X_LPARAM(lparam), GET_Y_LPARAM(lparam)};
    RECT frame;
    if (!GetWindowRect(window, &frame)) {
        return where;
    }
    // The top resize band was in the non-client area this window gave up, so nothing else
    // will answer for it. A window that cannot be resized has no such band.
    int band = dxc_maximised_overhang(window);
    if (dxc_options.resizable && !IsZoomed(window) && point.y < frame.top + band) {
        return HTTOP;
    }
    if (point.y >= frame.top + dxc_scaled(window, DXC_CAPTION_HEIGHT_DIP)) {
        return HTCLIENT;
    }
    // The buttons are drawn by Kotlin and have to receive ordinary mouse input, so the
    // strip they occupy stays client area. Everything else in the caption drags the
    // window, which also brings back double click to maximise and the system menu on
    // right click.
    int buttons = dxc_scaled(window, DXC_CAPTION_BUTTON_WIDTH_DIP * DXC_CAPTION_BUTTON_COUNT);
    if (point.x >= frame.right - buttons) {
        return HTCLIENT;
    }
    return HTCAPTION;
}

// Named apart from the one in `renderer_entry.c`, which subclasses the toolkit's frame
// to reclaim its caption. That one goes looking for a window of AWT's class and will
// not find this one, so the two never meet; the names are kept distinct anyway,
// because a reader who found both would have every reason to think they were.

typedef HRESULT(WINAPI *dxc_dwm_flush_fn)(void);
typedef HRESULT(WINAPI *dxc_dwm_enabled_fn)(BOOL *);

/**
 * DwmFlush, where DWM composition is on. Looked up at run time so the build links
 * nothing new. Zero when it did not run.
 */
static int dxc_dwm_flush(void) {
    static int looked_up;
    static dxc_dwm_flush_fn flush;
    static dxc_dwm_enabled_fn enabled;
    if (!looked_up) {
        looked_up = 1;
        HMODULE dwm = LoadLibraryW(L"dwmapi.dll");
        if (dwm != NULL) {
            flush = (dxc_dwm_flush_fn)(void *)GetProcAddress(dwm, "DwmFlush");
            enabled = (dxc_dwm_enabled_fn)(void *)GetProcAddress(dwm, "DwmIsCompositionEnabled");
        }
    }
    BOOL on = FALSE;
    if (flush == NULL || enabled == NULL || FAILED(enabled(&on)) || !on) {
        return 0;
    }
    flush();
    return 1;
}

static double dxc_elapsed_ms(LARGE_INTEGER since) {
    LARGE_INTEGER now, frequency;
    QueryPerformanceCounter(&now);
    QueryPerformanceFrequency(&frequency);
    return (double)(now.QuadPart - since.QuadPart) * 1000.0 / (double)frequency.QuadPart;
}

/**
 * Draws one frame at the size WM_SIZE has just recorded and presents it before WM_SIZE
 * returns, then waits for DWM to take it.
 *
 * WM_SIZE runs inside the SetWindowPos that changed the window, and that call does not
 * return until this does, so the next step of a drag cannot begin before this frame is
 * presented. The window keeps its redirection surface, so until the present lands DWM
 * shows the last frame unscaled (the swapchain is DXGI_SCALING_NONE), never a gap. The
 * DwmFlush makes the next step wait until DWM has composed this one; without it steps
 * queue faster than DWM shows them and an older size can be on screen with a newer
 * rectangle.
 */
/*
 * Resize frames on the CPU.
 *
 * While the window is changing size its frames are drawn by Skia's raster backend into a
 * DIB section and copied into the window with BitBlt before WM_SIZE returns, the way a
 * GDI application paints. The copy lands in the window's redirection surface on this call
 * stack, so when DWM composes the new size the new picture is already in it; nothing waits
 * on a GPU queue or a present. Once the size stops changing the GPU path takes over again,
 * and its first frame refits the swapchain to the size the window has by then.
 *
 * Opt in from the renderer (dxc_native_set_raster_resize), because the renderer has to
 * draw the frame into the pixels this hands it rather than into the swapchain.
 */
static int dxc_raster_enabled;
static int dxc_cpu_mode;
static HDC dxc_dib_dc;
static HBITMAP dxc_dib;
static HGDIOBJ dxc_dib_previous;
static void *dxc_dib_bits;
static int32_t dxc_dib_width;
static int32_t dxc_dib_height;
static int32_t dxc_raster_width;
static int32_t dxc_raster_height;
static int dxc_gpu_return_posted;
static int32_t dxc_mode_switches;
#define DXC_WM_GPU_RETURN (WM_APP + 2)

void dxc_native_set_raster_resize(int32_t enabled) {
    dxc_raster_enabled = enabled != 0;
    fprintf(stderr, "compose-rust: resize-mode: resize frames %s\n",
            dxc_raster_enabled ? "on the CPU (Skia raster, GDI blit)" : "on the GPU");
}

/** 1 while resize frames are drawn on the CPU, so the renderer asks for raster pixels. */
int32_t dxc_native_frame_mode(void) {
    return dxc_cpu_mode ? 1 : 0;
}

static void dxc_switch_frames(int cpu, int32_t width, int32_t height, const char *reason) {
    if (dxc_cpu_mode == cpu) {
        return;
    }
    dxc_cpu_mode = cpu;
    dxc_mode_switches++;
    fprintf(stderr, "compose-rust: resize-mode: frames %s -> %s at %dx%d because %s (switch %d)\n",
            cpu ? "gpu" : "cpu", cpu ? "cpu" : "gpu", (int)width, (int)height, reason,
            (int)dxc_mode_switches);
}

/**
 * Makes sure the DIB holds width x height. Made once at the size of the monitor the
 * window is on and reused; it grows only for a window larger than that monitor.
 */
static int dxc_ensure_dib(int32_t width, int32_t height) {
    if (dxc_dib != NULL && width <= dxc_dib_width && height <= dxc_dib_height) {
        return 1;
    }
    int32_t dib_width = width;
    int32_t dib_height = height;
    MONITORINFO monitor;
    memset(&monitor, 0, sizeof monitor);
    monitor.cbSize = sizeof monitor;
    if (dxc_window != NULL &&
        GetMonitorInfoW(MonitorFromWindow(dxc_window, MONITOR_DEFAULTTONEAREST), &monitor)) {
        int32_t monitor_width = (int32_t)(monitor.rcMonitor.right - monitor.rcMonitor.left);
        int32_t monitor_height = (int32_t)(monitor.rcMonitor.bottom - monitor.rcMonitor.top);
        if (monitor_width > dib_width) dib_width = monitor_width;
        if (monitor_height > dib_height) dib_height = monitor_height;
    }
    if (dxc_dib_dc == NULL) {
        dxc_dib_dc = CreateCompatibleDC(NULL);
        if (dxc_dib_dc == NULL) {
            return 0;
        }
    }
    if (dxc_dib != NULL) {
        SelectObject(dxc_dib_dc, dxc_dib_previous);
        DeleteObject(dxc_dib);
        dxc_dib = NULL;
        dxc_dib_bits = NULL;
    }
    BITMAPINFO info;
    memset(&info, 0, sizeof info);
    info.bmiHeader.biSize = sizeof info.bmiHeader;
    info.bmiHeader.biWidth = dib_width;
    // Negative: top-down rows, the order Skia writes them in.
    info.bmiHeader.biHeight = -dib_height;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    dxc_dib = CreateDIBSection(dxc_dib_dc, &info, DIB_RGB_COLORS, &dxc_dib_bits, NULL, 0);
    if (dxc_dib == NULL || dxc_dib_bits == NULL) {
        dxc_dib = NULL;
        return 0;
    }
    dxc_dib_previous = SelectObject(dxc_dib_dc, dxc_dib);
    dxc_dib_width = dib_width;
    dxc_dib_height = dib_height;
    fprintf(stderr, "compose-rust: resize-mode: raster bitmap -> %dx%d because a frame needed room\n",
            (int)dib_width, (int)dib_height);
    return 1;
}

/**
 * The pixels the CPU frame draws into: the top left width x height of the DIB, 32-bit
 * BGRA, top-down, row_bytes apart. Zero on success.
 */
int32_t dxc_native_raster_begin(void **pixels, int32_t *row_bytes, int32_t *width, int32_t *height) {
    int32_t w = dxc_resize_target_width > 0 ? dxc_resize_target_width : dxc_sizing.fitted_width;
    int32_t h = dxc_resize_target_height > 0 ? dxc_resize_target_height : dxc_sizing.fitted_height;
    if (w <= 0 || h <= 0 || !dxc_ensure_dib(w, h)) {
        return 1;
    }
    GdiFlush();
    *pixels = dxc_dib_bits;
    *row_bytes = dxc_dib_width * 4;
    *width = w;
    *height = h;
    dxc_raster_width = w;
    dxc_raster_height = h;
    QueryPerformanceCounter(&dxc_step_mark);
    return 0;
}

/** Copies the CPU frame into the window, 1:1, before the message that asked for it returns. */
void dxc_native_raster_end(void) {
    if (dxc_window == NULL || dxc_dib_dc == NULL) {
        return;
    }
    dxc_step_draw_ms = dxc_elapsed_ms(dxc_step_mark);
    LARGE_INTEGER copy_started;
    QueryPerformanceCounter(&copy_started);
    HDC window_dc = GetDC(dxc_window);
    if (window_dc != NULL) {
        BitBlt(window_dc, 0, 0, dxc_raster_width, dxc_raster_height, dxc_dib_dc, 0, 0, SRCCOPY);
        GdiFlush();
        ReleaseDC(dxc_window, window_dc);
    }
    ValidateRect(dxc_window, NULL);
    dxc_step_copy_ms = dxc_elapsed_ms(copy_started);
    dxc_presented_width = dxc_raster_width;
    dxc_presented_height = dxc_raster_height;
}

static void dxc_draw_resize(int32_t width, int32_t height) {
    if (width == dxc_presented_width && height == dxc_presented_height) {
        return;
    }
    LARGE_INTEGER started;
    QueryPerformanceCounter(&started);
    dxc_resize_target_width = width;
    dxc_resize_target_height = height;
    if (dxc_drawing) {
        fprintf(stderr, "compose-rust: resize-mode: skipped present %dx%d because a frame was already being drawn\n",
                (int)width, (int)height);
    }
    if (dxc_raster_enabled && dxc_draw_frame != NULL && !dxc_drawing) {
        dxc_switch_frames(1, width, height, dxc_sizing.dragging ? "the edge is being dragged" : "the size changed");
    }
    dxc_resizing = 1;
    dxc_step_gpu_idle_ms = dxc_step_refit_ms = dxc_step_draw_ms = dxc_step_present_ms = 0.0;
    dxc_step_copy_ms = 0.0;
    dxc_draw_one_frame();
    dxc_resizing = 0;
    // Outside a drag the size has stopped changing once this message is done; the GPU
    // takes over on the next turn of the message loop.
    if (dxc_cpu_mode && !dxc_sizing.dragging && !dxc_gpu_return_posted && dxc_window != NULL) {
        dxc_gpu_return_posted = PostMessageW(dxc_window, DXC_WM_GPU_RETURN, 0, 0) != 0;
    }
    int presented = dxc_presented_width == width && dxc_presented_height == height;
    double drawn_ms = dxc_elapsed_ms(started);
    int flushed = presented ? dxc_dwm_flush() : 0;
    double total_ms = dxc_elapsed_ms(started);
    if (total_ms >= DXC_RESIZE_STEP_CAP_MS) {
        fprintf(stderr, "compose-rust: resize step %dx%d hit the %.0f ms cap (%.2f ms)\n",
                (int)width, (int)height, DXC_RESIZE_STEP_CAP_MS, total_ms);
    }
    if (dxc_report_latency) {
        fprintf(stderr,
                "compose-rust: resize step %dx%d %s, %s, took %.2f ms: gpu idle %.2f, refit %.2f, "
                "draw %.2f, copy %.2f, present %.2f, DwmFlush %.2f\n",
                (int)width, (int)height, presented ? "presented" : "not presented",
                flushed ? "flushed" : "not flushed", total_ms, dxc_step_gpu_idle_ms,
                dxc_step_refit_ms, dxc_step_draw_ms, dxc_step_copy_ms, dxc_step_present_ms,
                total_ms - drawn_ms);
    }
}

static LRESULT CALLBACK dxc_native_window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
    case WM_NCCALCSIZE: {
        if (dxc_options.system_chrome || wparam != TRUE) {
            break;
        }
        NCCALCSIZE_PARAMS *params = (NCCALCSIZE_PARAMS *)lparam;
        LONG requested_top = params->rgrc[0].top;
        DefWindowProcW(window, message, wparam, lparam);
        params->rgrc[0].top = IsZoomed(window) ? requested_top + dxc_maximised_overhang(window)
                                               : requested_top;
        return 0;
    }
    case WM_NCHITTEST:
        if (dxc_options.system_chrome) {
            break;
        }
        return dxc_caption_hit_test(window, lparam);
    case WM_GETMINMAXINFO:
        if (dxc_options.min_width > 0 || dxc_options.min_height > 0) {
            UINT dpi = GetDpiForWindow(window);
            if (dpi == 0) {
                dpi = USER_DEFAULT_SCREEN_DPI;
            }
            RECT wanted = {0, 0, MulDiv(dxc_options.min_width, (int)dpi, USER_DEFAULT_SCREEN_DPI),
                           MulDiv(dxc_options.min_height, (int)dpi, USER_DEFAULT_SCREEN_DPI)};
            AdjustWindowRectExForDpi(&wanted, dxc_window_style(), FALSE, 0, dpi);
            MINMAXINFO *info = (MINMAXINFO *)lparam;
            info->ptMinTrackSize.x = wanted.right - wanted.left;
            // The caption strip is client area here, so the top of the frame is not
            // outside the content.
            info->ptMinTrackSize.y = dxc_options.system_chrome ? wanted.bottom - wanted.top
                                                               : wanted.bottom;
            return 0;
        }
        break;
    case WM_MOUSEMOVE:
        // No tracking area. Windows delivers a move whenever the pointer is over the
        // client area, so hover, which is half of what a desktop control does, arrives
        // without having to be asked for.
        dxc_push_pointer(DXC_EVENT_POINTER_MOVE, lparam);
        return 0;
    case WM_SETCURSOR:
        if (LOWORD(lparam) == HTCLIENT) {
            SetCursor(LoadCursorW(NULL, dxc_cursor));
            return 1;
        }
        break;
    case WM_LBUTTONDOWN:
    case WM_RBUTTONDOWN:
    case WM_MBUTTONDOWN:
        // Held so that a drag leaving the window still reports where it went, and so the
        // release that ends it is heard at all.
        SetCapture(window);
        dxc_push_pointer(DXC_EVENT_POINTER_DOWN, lparam);
        return 0;
    case WM_LBUTTONUP:
    case WM_RBUTTONUP:
    case WM_MBUTTONUP:
        ReleaseCapture();
        dxc_push_pointer(DXC_EVENT_POINTER_UP, lparam);
        return 0;
    case WM_MOUSEWHEEL:
        dxc_push_scroll(0.0f,
            (float)GET_WHEEL_DELTA_WPARAM(wparam) / (float)WHEEL_DELTA * dxc_wheel_lines());
        return 0;
    case WM_MOUSEHWHEEL:
        dxc_push_scroll(
            (float)GET_WHEEL_DELTA_WPARAM(wparam) / (float)WHEEL_DELTA * dxc_wheel_lines(),
            0.0f);
        return 0;
    case WM_KEYDOWN:
    case WM_SYSKEYDOWN:
        // Anything held with alt, and F10, arrive as a system key. Answered here rather
        // than passed on, because the default handler puts the window into menu mode on a
        // keystroke the scene was meant to read. Alt and F4 together is the exception: it
        // is how a window is closed from the keyboard, and the handler that does that is
        // the one being stepped around.
        dxc_push_key(DXC_EVENT_KEY_DOWN, wparam);
        if (message == WM_SYSKEYDOWN && wparam == VK_F4) {
            break;
        }
        return 0;
    case WM_KEYUP:
    case WM_SYSKEYUP:
        dxc_push_key(DXC_EVENT_KEY_UP, wparam);
        return 0;
    case WM_ENTERSIZEMOVE:
        // The reader has taken hold of an edge, or of the title bar. From here until the
        // matching message below, everything this window hears is dispatched from a loop
        // inside `DefWindowProc` rather than from the renderer's frame loop, and that
        // loop does not return until the reader lets go.
        dxc_resize_begin_drag(&dxc_sizing);
        dxc_mode("size-move", "off", "on", "WM_ENTERSIZEMOVE");
        // Each step of the drag has to be drawn and presented before DWM shows the new
        // size, so the thread doing it should not lose the CPU to background load.
        if (dxc_priority() >= 1) {
            dxc_thread_priority_before = GetThreadPriority(GetCurrentThread());
            SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_ABOVE_NORMAL);
        }
        return 0;
    case WM_EXITSIZEMOVE:
        // Let go. The frame loop has its turns back, so a size arriving after this is
        // written down and taken by the next frame.
        dxc_resize_end_drag(&dxc_sizing);
        dxc_mode("size-move", "on", "off", "WM_EXITSIZEMOVE");
        if (dxc_cpu_mode) {
            RECT client;
            GetClientRect(window, &client);
            dxc_switch_frames(0, client.right - client.left, client.bottom - client.top,
                              "the drag ended");
            dxc_draw_one_frame();
        }
        if (dxc_thread_priority_before != THREAD_PRIORITY_ERROR_RETURN) {
            SetThreadPriority(GetCurrentThread(), dxc_thread_priority_before);
            dxc_thread_priority_before = THREAD_PRIORITY_ERROR_RETURN;
        }
        return 0;
    case WM_IME_STARTCOMPOSITION:
        dxc_ime_composing = 1;
        dxc_pending_high_surrogate = 0;
        dxc_position_ime(window);
        return 0;
    case WM_IME_COMPOSITION: {
        if (lparam == 0) {
            dxc_push_text(DXC_EVENT_TEXT_COMPOSE, NULL, 0);
            return 0;
        }
        HIMC context = ImmGetContext(window);
        if (context != NULL) {
            // A result replaces the old marked text; a new composition may follow it
            // in this same message. Preserve that order in the event queue.
            if (lparam & GCS_RESULTSTR) {
                dxc_read_ime_text(context, GCS_RESULTSTR, DXC_EVENT_TEXT_COMMIT);
                dxc_ime_composing = 0;
            }
            if (lparam & GCS_COMPSTR) {
                dxc_read_ime_text(context, GCS_COMPSTR, DXC_EVENT_TEXT_COMPOSE);
                dxc_ime_composing = 1;
            }
            ImmReleaseContext(window, context);
        }
        return 0;
    }
    case WM_IME_ENDCOMPOSITION:
        if (dxc_ime_composing) dxc_push_text(DXC_EVENT_TEXT_COMPOSE, NULL, 0);
        dxc_ime_composing = 0;
        return 0;
    case WM_IME_CHAR:
        // The result already arrived through GCS_RESULTSTR. The default handler can
        // turn this into WM_CHAR, which would commit it a second time.
        return 0;
    case WM_CHAR: {
        if (dxc_ime_composing) return 0;
        uint16_t unit = (uint16_t)wparam;
        if (unit >= 0xd800 && unit <= 0xdbff) {
            dxc_pending_high_surrogate = unit;
            return 0;
        }
        uint16_t text[2];
        size_t units = 1;
        if (unit >= 0xdc00 && unit <= 0xdfff && dxc_pending_high_surrogate != 0) {
            text[0] = dxc_pending_high_surrogate;
            text[1] = unit;
            units = 2;
        } else {
            text[0] = unit;
        }
        dxc_pending_high_surrogate = 0;
        if (unit >= 0x20 && unit != 0x7f) {
            dxc_push_text(DXC_EVENT_TEXT_COMMIT, text, units);
        }
        return 0;
    }
    case WM_DPICHANGED:
        fprintf(stderr, "compose-rust: resize-mode: dpi -> %u because WM_DPICHANGED\n",
                (unsigned)HIWORD(wparam));
        if (dxc_swapchain != NULL) {
            dxc_log_monitor(window, "WM_DPICHANGED");
        }
        // A per monitor aware window is not resized by the system when its DPI changes;
        // it is handed the rectangle that keeps its size in points and has to apply it.
        // Applied with SetWindowPos, so the new size goes through WM_SIZE and is drawn,
        // presented and flushed there like any other resize.
        {
            const RECT *suggested = (const RECT *)lparam;
            SetWindowPos(window, NULL, suggested->left, suggested->top,
                         suggested->right - suggested->left, suggested->bottom - suggested->top,
                         SWP_NOZORDER | SWP_NOACTIVATE);
        }
        return 0;
    case WM_DISPLAYCHANGE:
        if (dxc_swapchain != NULL) {
            dxc_log_monitor(window, "WM_DISPLAYCHANGE");
        }
        break;
    case WM_MOVE:
        if (dxc_swapchain != NULL &&
            MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST) != dxc_last_monitor) {
            dxc_log_monitor(window, "the window moved to another monitor");
        }
        break;
    case WM_SIZE:
        if (wparam != dxc_last_size_kind &&
            (wparam == SIZE_MAXIMIZED || wparam == SIZE_MINIMIZED || wparam == SIZE_RESTORED)) {
            dxc_mode("window", dxc_size_kind_name(dxc_last_size_kind), dxc_size_kind_name(wparam),
                     "WM_SIZE");
            dxc_last_size_kind = wparam;
        }
        // Written down, then drawn: the frame refits the swapchain where it begins, never
        // while a buffer is being drawn into. Nothing to do while minimised: the client
        // area is empty and a swapchain cannot have a zero dimension.
        if (dxc_swapchain != NULL && wparam != SIZE_MINIMIZED) {
            dxc_resize_note(&dxc_sizing, (int32_t)LOWORD(lparam), (int32_t)HIWORD(lparam));
            // Drawn here, inside the SetWindowPos that resized the window, whether or not
            // a drag is on: during one there is no other place that runs, and outside one
            // the same ordering keeps the frame ahead of the next size.
            dxc_draw_resize((int32_t)LOWORD(lparam), (int32_t)HIWORD(lparam));
        }
        return 0;
    case WM_PAINT: {
        // Validated so the window stops asking. Outside a drag the frame is drawn again:
        // the window's redirection surface can lose what was presented into it (restored
        // from minimised, uncovered, a display change), and the frame loop draws only
        // when the scene changed, so without this the window would stay black until it
        // did. During a drag WM_SIZE draws, and a second frame here would present the
        // same size twice.
        PAINTSTRUCT paint;
        BeginPaint(window, &paint);
        EndPaint(window, &paint);
        if (!dxc_resizing && !dxc_sizing.dragging) {
            dxc_draw_one_frame();
        }
        return 0;
    }
    case WM_ERASEBKGND:
        // Answered so the window is never painted white between frames. Every pixel of
        // the client area comes from the swapchain.
        return 1;
    case WM_CLOSE:
        DestroyWindow(window);
        return 0;
    case WM_DESTROY:
        if (dxc_root_provider != NULL && dxc_root_provider->window == window) {
            dxc_release_children(dxc_root_provider);
            dxc_root_provider->window = NULL;
        }
        dxc_a11y_count = 0;
        dxc_a11y_dirty = 0;
        dxc_a11y_update_posted = 0;
        dxc_window = NULL;
        dxc_window_gone = 1;
        PostQuitMessage(0);
        return 0;
    case DXC_WM_GPU_RETURN:
        dxc_gpu_return_posted = 0;
        if (dxc_cpu_mode && !dxc_sizing.dragging) {
            RECT client;
            GetClientRect(window, &client);
            dxc_switch_frames(0, client.right - client.left, client.bottom - client.top,
                              "the size stopped changing");
            dxc_draw_one_frame();
        }
        return 0;
    case DXC_WM_ACCESSIBILITY_UPDATE:
        dxc_a11y_update_posted = 0;
        {
            DxcRootProvider *root = dxc_ensure_root_provider(window);
            if (root == NULL) return 0;
            dxc_publish_accessibility(root);
            UiaRaiseStructureChangedEvent(
                (IRawElementProviderSimple *)&root->simple_vtbl,
                StructureChangeType_ChildrenInvalidated, NULL, 0);
        }
        return 0;
    case WM_GETOBJECT:
        // A reader is asking what is in the window. The answer is our root
        // provider, which holds whatever the scene last pushed.
        if ((LONG)lparam == UiaRootObjectId) {
            DxcRootProvider *root = dxc_ensure_root_provider(window);
            if (root != NULL) {
                dxc_publish_accessibility(root);
                return UiaReturnRawElementProvider(
                    window, wparam, lparam,
                    (IRawElementProviderSimple *)&root->simple_vtbl);
            }
        }
        break;
    default:
        break;
    }
    return DefWindowProcW(window, message, wparam, lparam);
}

/**
 * Hands Windows the messages it has been holding.
 *
 * Here rather than in a loop of its own because Windows delivers messages to the thread
 * that made the window, and that is the thread the renderer draws from: a pump anywhere
 * else would be a pump the window never hears. Run when the queue has nothing left, which
 * is twice a frame: once to fill it and once to find it empty.
 */
static void dxc_pump_messages(void) {
    MSG message;
    while (PeekMessageW(&message, NULL, 0, 0, PM_REMOVE)) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
}

/**
 * Lets the window answer for itself for a moment.
 *
 * Called once a frame. The thread that draws is the thread Windows delivers to, so a loop
 * that never gave it a turn would be a window that heard nothing.
 *
 * The wait is for something to arrive rather than for the clock. A frame that drew has
 * already waited for the screen inside `Present`, and the caller asks for no wait at all
 * in that case; a window with nothing happening is asked to rest for a frame's length,
 * and comes back the moment anything is pressed.
 */
void dxc_native_pump(double seconds) {
    if (dxc_window != NULL && seconds > 0.0) {
        DWORD wait = (DWORD)(seconds * 1000.0 + 0.5);
        if (wait > 0) {
            // Returns at once where something is already waiting, which is what the last
            // flag asks for. Without it a message that arrived before this call would be
            // paid for with a whole frame of sleeping.
            MsgWaitForMultipleObjectsEx(0, NULL, wait, QS_ALLINPUT, MWMO_INPUTAVAILABLE);
        }
    }
    dxc_pump_messages();
}

/** True once the reader has closed the window. */
int32_t dxc_native_window_closed(void) {
    return dxc_window_gone ? 1 : 0;
}

/**
 * The menu bar this platform does not have.
 *
 * Named because one piece of Kotlin drives both desktops and asks for this by name on
 * each. macOS keeps its application menu outside the window, and the shortcuts a reader
 * expects there do nothing without it. Windows keeps nothing outside the window: closing
 * is alt with F4 and the system menu, which the default handler already answers, and the
 * editing shortcuts belong to whatever holds focus, which is the scene.
 */
void dxc_native_install_menu(const char *application_name) {
    (void)application_name;
}


/** The caption strip is fixed on this platform and Kotlin knows its height, so nothing is measured. */
void dxc_native_window_caption(void *view_pointer, float *height, float *buttons_width) {
    (void)view_pointer;
    *height = 0;
    *buttons_width = 0;
}

/**
 * Does with the window what a button of the application's own caption asks: 0 minimises,
 * 1 maximises or restores, 2 closes.
 */
void dxc_native_window_action(int32_t action) {
    if (dxc_window == NULL) {
        return;
    }
    switch (action) {
    case 0:
        ShowWindow(dxc_window, SW_MINIMIZE);
        break;
    case 1:
        ShowWindow(dxc_window, IsZoomed(dxc_window) ? SW_RESTORE : SW_MAXIMIZE);
        break;
    case 2:
        PostMessageW(dxc_window, WM_CLOSE, 0, 0);
        break;
    default:
        break;
    }
}

/** The system moves the window itself where the caption is the system's, so nothing here. */
void dxc_native_window_begin_drag(int32_t edge) {
    (void)edge;
}

/**
 * Gives the window the picture it named, from its pixels: eight bits each of red, green,
 * blue and alpha, with the colour already multiplied by the alpha, row after row.
 */
void dxc_native_set_icon(const uint8_t *rgba, int32_t width, int32_t height) {
    if (dxc_window == NULL || rgba == NULL || width <= 0 || height <= 0) {
        return;
    }
    BITMAPV5HEADER header;
    memset(&header, 0, sizeof header);
    header.bV5Size = sizeof header;
    header.bV5Width = width;
    // Negative: the rows are top first.
    header.bV5Height = -height;
    header.bV5Planes = 1;
    header.bV5BitCount = 32;
    header.bV5Compression = BI_BITFIELDS;
    header.bV5RedMask = 0x00FF0000;
    header.bV5GreenMask = 0x0000FF00;
    header.bV5BlueMask = 0x000000FF;
    header.bV5AlphaMask = 0xFF000000;
    void *bits = NULL;
    HDC screen = GetDC(NULL);
    HBITMAP color = CreateDIBSection(screen, (BITMAPINFO *)&header, DIB_RGB_COLORS, &bits, NULL, 0);
    ReleaseDC(NULL, screen);
    if (color == NULL || bits == NULL) {
        if (color != NULL) DeleteObject(color);
        return;
    }
    uint8_t *out = (uint8_t *)bits;
    for (int64_t index = 0; index < (int64_t)width * height; index++) {
        uint8_t r = rgba[index * 4 + 0];
        uint8_t g = rgba[index * 4 + 1];
        uint8_t b = rgba[index * 4 + 2];
        uint8_t a = rgba[index * 4 + 3];
        // An icon's alpha is straight, so the colour is divided back out.
        if (a != 0 && a != 255) {
            r = (uint8_t)((r * 255 + a / 2) / a);
            g = (uint8_t)((g * 255 + a / 2) / a);
            b = (uint8_t)((b * 255 + a / 2) / a);
        }
        out[index * 4 + 0] = b;
        out[index * 4 + 1] = g;
        out[index * 4 + 2] = r;
        out[index * 4 + 3] = a;
    }
    HBITMAP mask = CreateBitmap(width, height, 1, 1, NULL);
    ICONINFO info;
    memset(&info, 0, sizeof info);
    info.fIcon = TRUE;
    info.hbmMask = mask;
    info.hbmColor = color;
    HICON icon = CreateIconIndirect(&info);
    DeleteObject(color);
    DeleteObject(mask);
    if (icon != NULL) {
        SendMessageW(dxc_window, WM_SETICON, ICON_BIG, (LPARAM)icon);
        SendMessageW(dxc_window, WM_SETICON, ICON_SMALL, (LPARAM)icon);
    }
}

/** What is on the clipboard as text, copied into [out] as UTF-8, and its length. */
int32_t dxc_native_clipboard_read(char *out, int32_t capacity) {
    if (!OpenClipboard(dxc_window)) {
        return 0;
    }
    int32_t length = 0;
    HANDLE data = GetClipboardData(CF_UNICODETEXT);
    if (data != NULL) {
        const wchar_t *wide = (const wchar_t *)GlobalLock(data);
        if (wide != NULL) {
            int written = WideCharToMultiByte(CP_UTF8, 0, wide, -1, out, capacity, NULL, NULL);
            // The count includes the terminator, and is zero where the text did not fit.
            if (written > 0) {
                length = written - 1;
            }
            GlobalUnlock(data);
        }
    }
    CloseClipboard();
    return length;
}

/** Replaces the clipboard's contents with [text], which is UTF-8. */
void dxc_native_clipboard_write(const char *text) {
    int units = MultiByteToWideChar(CP_UTF8, 0, text, -1, NULL, 0);
    if (units <= 0 || !OpenClipboard(dxc_window)) {
        return;
    }
    HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, (SIZE_T)units * sizeof(wchar_t));
    if (memory != NULL) {
        wchar_t *wide = (wchar_t *)GlobalLock(memory);
        if (wide != NULL) {
            MultiByteToWideChar(CP_UTF8, 0, text, -1, wide, units);
            GlobalUnlock(memory);
            EmptyClipboard();
            // The clipboard owns the memory from here on.
            if (SetClipboardData(CF_UNICODETEXT, memory) == NULL) {
                GlobalFree(memory);
            }
        } else {
            GlobalFree(memory);
        }
    }
    CloseClipboard();
}

/*
 * Files dragged over the window, through OLE, which is what says where they are and when
 * they leave. WM_DROPFILES says only that they were dropped.
 */
#define DXC_DROPPED_BYTES (64 * 1024)
static char dxc_dropped_paths[DXC_DROPPED_BYTES];
static int32_t dxc_dropped_length;
static int dxc_drag_has_files;

/** The paths of the files last dragged over the window, NUL between them, and the length. */
int32_t dxc_native_dropped_paths(char *out, int32_t capacity) {
    if (dxc_dropped_length <= 0 || dxc_dropped_length > capacity) {
        return 0;
    }
    memcpy(out, dxc_dropped_paths, (size_t)dxc_dropped_length);
    return dxc_dropped_length;
}

static FORMATETC dxc_hdrop_format(void) {
    FORMATETC format;
    format.cfFormat = CF_HDROP;
    format.ptd = NULL;
    format.dwAspect = DVASPECT_CONTENT;
    format.lindex = -1;
    format.tymed = TYMED_HGLOBAL;
    return format;
}

static void dxc_read_dragged_files(IDataObject *data) {
    dxc_dropped_length = 0;
    FORMATETC format = dxc_hdrop_format();
    STGMEDIUM medium;
    if (FAILED(IDataObject_GetData(data, &format, &medium))) {
        return;
    }
    HDROP drop = (HDROP)medium.hGlobal;
    UINT count = DragQueryFileW(drop, 0xFFFFFFFFu, NULL, 0);
    int32_t used = 0;
    for (UINT index = 0; index < count; index++) {
        wchar_t wide[MAX_PATH * 4];
        UINT units = DragQueryFileW(drop, index, wide, (UINT)(sizeof wide / sizeof *wide));
        if (units == 0) continue;
        int bytes = WideCharToMultiByte(CP_UTF8, 0, wide, (int)units, NULL, 0, NULL, NULL);
        // Room for the separator as well, and a path that does not fit is left out rather
        // than cut in half.
        if (bytes <= 0 || used + bytes + 1 > DXC_DROPPED_BYTES) continue;
        if (used > 0) dxc_dropped_paths[used++] = '\0';
        WideCharToMultiByte(CP_UTF8, 0, wide, (int)units, dxc_dropped_paths + used, bytes, NULL, NULL);
        used += bytes;
    }
    dxc_dropped_length = used;
    ReleaseStgMedium(&medium);
}

static void dxc_push_drag(int32_t kind, POINTL where) {
    POINT point = {where.x, where.y};
    if (dxc_window != NULL) {
        ScreenToClient(dxc_window, &point);
    }
    struct dxc_event record;
    memset(&record, 0, sizeof record);
    record.kind = kind;
    record.x = (float)point.x;
    record.y = (float)point.y;
    dxc_push_event(record);
}

static HRESULT STDMETHODCALLTYPE dxc_drop_query(IDropTarget *self, REFIID id, void **out) {
    if (IsEqualIID(id, &IID_IUnknown) || IsEqualIID(id, &IID_IDropTarget)) {
        *out = self;
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static ULONG STDMETHODCALLTYPE dxc_drop_add_ref(IDropTarget *self) { (void)self; return 1; }
static ULONG STDMETHODCALLTYPE dxc_drop_release(IDropTarget *self) { (void)self; return 1; }

static HRESULT STDMETHODCALLTYPE dxc_drop_enter(
    IDropTarget *self, IDataObject *data, DWORD keys, POINTL where, DWORD *effect) {
    (void)self;
    (void)keys;
    FORMATETC format = dxc_hdrop_format();
    dxc_drag_has_files = SUCCEEDED(IDataObject_QueryGetData(data, &format));
    if (!dxc_drag_has_files) {
        *effect = DROPEFFECT_NONE;
        return S_OK;
    }
    dxc_read_dragged_files(data);
    dxc_push_drag(DXC_EVENT_FILES_ENTERED, where);
    *effect = DROPEFFECT_COPY;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_drop_over(
    IDropTarget *self, DWORD keys, POINTL where, DWORD *effect) {
    (void)self;
    (void)keys;
    if (!dxc_drag_has_files) {
        *effect = DROPEFFECT_NONE;
        return S_OK;
    }
    dxc_push_drag(DXC_EVENT_FILES_ENTERED, where);
    *effect = DROPEFFECT_COPY;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_drop_leave(IDropTarget *self) {
    (void)self;
    if (dxc_drag_has_files) {
        struct dxc_event record;
        memset(&record, 0, sizeof record);
        record.kind = DXC_EVENT_FILES_EXITED;
        dxc_push_event(record);
    }
    dxc_drag_has_files = 0;
    return S_OK;
}

static HRESULT STDMETHODCALLTYPE dxc_drop_drop(
    IDropTarget *self, IDataObject *data, DWORD keys, POINTL where, DWORD *effect) {
    (void)self;
    (void)keys;
    if (!dxc_drag_has_files) {
        *effect = DROPEFFECT_NONE;
        return S_OK;
    }
    dxc_read_dragged_files(data);
    dxc_push_drag(DXC_EVENT_FILES_DROPPED, where);
    dxc_drag_has_files = 0;
    *effect = DROPEFFECT_COPY;
    return S_OK;
}

static IDropTargetVtbl dxc_drop_vtable = {
    dxc_drop_query, dxc_drop_add_ref, dxc_drop_release,
    dxc_drop_enter, dxc_drop_over, dxc_drop_leave, dxc_drop_drop,
};
static IDropTarget dxc_drop_target = {&dxc_drop_vtable};

/** Lets the window be dropped on. Drag and drop is OLE's, which needs a single threaded apartment. */
static void dxc_accept_files(HWND window) {
    OleInitialize(NULL);
    RegisterDragDrop(window, &dxc_drop_target);
}

/**
 * Takes the window through the sizes a drag would, for measuring. Used only when asked
 * for, by DXC_SYNTH.
 *
 * Sizes are client sizes in points, as on macOS. Each step is a SetWindowPos with the top
 * left held, between the two messages that bracket a real drag, so the frame for each
 * step comes through WM_NCCALCSIZE and WM_SIZE exactly as a dragged edge's does. Runs on
 * the window's own thread and returns when the last step has been taken.
 */
void dxc_native_debug_resize(void *window_pointer, void *view_pointer, int32_t from_width,
                             int32_t from_height, int32_t to_width, int32_t to_height,
                             int32_t steps, int32_t pause_micros) {
    (void)view_pointer;
    HWND window = (HWND)window_pointer;
    if (window == NULL || steps <= 0) {
        return;
    }
    UINT dpi = GetDpiForWindow(window);
    if (dpi == 0) {
        dpi = USER_DEFAULT_SCREEN_DPI;
    }
    SendMessageW(window, WM_ENTERSIZEMOVE, 0, 0);
    for (int32_t step = 1; step <= steps; step++) {
        double t = (double)step / steps;
        int points_width = (int)(from_width + (to_width - from_width) * t + 0.5);
        int points_height = (int)(from_height + (to_height - from_height) * t + 0.5);
        RECT outer;
        outer.left = 0;
        outer.top = 0;
        outer.right = MulDiv(points_width, (int)dpi, USER_DEFAULT_SCREEN_DPI);
        outer.bottom = MulDiv(points_height, (int)dpi, USER_DEFAULT_SCREEN_DPI);
        AdjustWindowRectExForDpi(&outer, dxc_window_style(), FALSE, 0, dpi);
        // Without the system caption the client area starts at the top of the window,
        // as where the window was first sized.
        int outer_height = dxc_options.system_chrome ? outer.bottom - outer.top : outer.bottom;
        SetWindowPos(window, NULL, 0, 0, outer.right - outer.left, outer_height,
                     SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
        if (pause_micros > 0) {
            Sleep((DWORD)((pause_micros + 999) / 1000));
        }
    }
    SendMessageW(window, WM_EXITSIZEMOVE, 0, 0);
}

/**
 * Posts a key press, the characters it types and its release to the window's own queue,
 * for measuring. Used only when asked for, by DXC_SYNTH.
 *
 * The key code the caller passes is macOS's, which means nothing here; the virtual key
 * is looked up from the first character instead. The characters go as WM_CHAR, one per
 * UTF-16 unit, which is what TranslateMessage would have posted for a real press.
 */
void dxc_native_debug_key(void *window_pointer, int32_t key_code, const char *characters) {
    (void)key_code;
    HWND window = (HWND)window_pointer;
    if (window == NULL || characters == NULL) {
        return;
    }
    WCHAR text[16];
    int units = MultiByteToWideChar(CP_UTF8, 0, characters, -1, text,
                                    (int)(sizeof text / sizeof *text));
    if (units <= 1) {
        return;
    }
    units--;
    SHORT scanned = VkKeyScanW(text[0]);
    UINT key = scanned == -1 ? 0 : (UINT)(scanned & 0xff);
    if (key != 0) {
        PostMessageW(window, WM_KEYDOWN, key, 1);
    }
    for (int index = 0; index < units; index++) {
        PostMessageW(window, WM_CHAR, text[index], 1);
    }
    if (key != 0) {
        PostMessageW(window, WM_KEYUP, key, (LPARAM)0xC0000001);
    }
}

/**
 * Shows the system's context menu at the pointer and answers with the index of the entry
 * chosen, or -1 when it was dismissed. Returns when the menu closes.
 *
 * [items] is one line per entry, fields separated by a tab: the index, enabled (0 or 1)
 * and the label in UTF-8, the format the macOS window reads (packMenu in
 * NativeContextMenu.kt). Which entries are enabled is the caller's to say: Compose
 * already disables Paste when the clipboard holds no text.
 */
int32_t dxc_native_context_menu(void *window_pointer, const char *items) {
    HWND window = window_pointer != NULL ? (HWND)window_pointer : dxc_window;
    if (window == NULL || items == NULL) {
        return -1;
    }
    HMENU menu = CreatePopupMenu();
    if (menu == NULL) {
        return -1;
    }
    int entries = 0;
    const char *line = items;
    while (*line != '\0') {
        const char *end = strchr(line, '\n');
        size_t length = end != NULL ? (size_t)(end - line) : strlen(line);
        char field[512];
        if (length >= sizeof field) length = sizeof field - 1;
        memcpy(field, line, length);
        field[length] = '\0';
        char *first_tab = strchr(field, '\t');
        char *second_tab = first_tab != NULL ? strchr(first_tab + 1, '\t') : NULL;
        if (second_tab != NULL) {
            *first_tab = '\0';
            *second_tab = '\0';
            int index = atoi(field);
            int enabled = atoi(first_tab + 1) != 0;
            WCHAR label[256];
            if (MultiByteToWideChar(CP_UTF8, 0, second_tab + 1, -1, label,
                                    (int)(sizeof label / sizeof *label)) == 0) {
                label[0] = L'\0';
            }
            // Command ids start at 1, because TrackPopupMenuEx answers 0 for "nothing".
            AppendMenuW(menu, MF_STRING | (enabled ? MF_ENABLED : MF_GRAYED),
                        (UINT_PTR)(index + 1), label);
            entries++;
        }
        if (end == NULL) break;
        line = end + 1;
    }
    int32_t chosen = -1;
    if (entries > 0) {
        POINT pointer;
        GetCursorPos(&pointer);
        // Without the window in front the menu does not close when the reader clicks
        // elsewhere; the posted message lets it finish closing (KB135788).
        SetForegroundWindow(window);
        UINT command = (UINT)TrackPopupMenuEx(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_NONOTIFY,
                                              pointer.x, pointer.y, window, NULL);
        PostMessageW(window, WM_NULL, 0, 0);
        chosen = command == 0 ? -1 : (int32_t)command - 1;
    }
    DestroyMenu(menu);
    return chosen;
}

/*
 * The names below are answered here and do nothing.
 *
 * One piece of Kotlin drives every desktop and reaches their windows by name, so each of
 * these files answers every name, including the ones that mean nothing on it. A missing
 * one is a warning on the linkers that look names up at load time and a failure on the
 * ones that do not, which is a defect that travels to whoever builds for the strictest
 * platform. It travelled three times before this was written down.
 */
void dxc_native_set_frame_callback(void *callback, void *isolate_thread) {
    (void)callback;
    (void)isolate_thread;
}

/** Takes the oldest event, or answers zero when there is none. */
int32_t dxc_native_poll_event(struct dxc_event *out) {
    if (dxc_event_count == 0) {
        dxc_pump_messages();
    }
    if (dxc_event_count == 0) {
        return 0;
    }
    *out = dxc_events[dxc_event_head];
    dxc_event_head = (dxc_event_head + 1) % DXC_EVENT_CAPACITY;
    dxc_event_count--;
    return 1;
}

struct dxc_native_window {
    void *window;
    void *device;
    void *queue;
    void *adapter;
    void *swapchain;
};

/** Waits until the queue has finished everything put on it. */
static void dxc_wait_for_gpu(void) {
    if (dxc_queue == NULL || dxc_fence == NULL) {
        return;
    }
    UINT64 mark = ++dxc_fence_value;
    if (FAILED(ID3D12CommandQueue_Signal(dxc_queue, dxc_fence, mark))) {
        return;
    }
    if (ID3D12Fence_GetCompletedValue(dxc_fence) < mark) {
        if (SUCCEEDED(ID3D12Fence_SetEventOnCompletion(dxc_fence, mark, dxc_fence_signalled))) {
            WaitForSingleObject(dxc_fence_signalled, INFINITE);
        }
    }
}

// The texture Skia draws into is kept across resizes and only grows: a new committed
// resource and a new Direct3D 11 wrapper on every drag step are work the step pays for
// before anything is presented. Skia is told the drawn size, and only that top-left
// region is copied into the swapchain.
static int32_t dxc_texture_width;
static int32_t dxc_texture_height;

static void dxc_release_texture(void) {
    if (dxc_wrapped != NULL) {
        ID3D11Resource_Release(dxc_wrapped);
        dxc_wrapped = NULL;
    }
    for (int index = 0; index < DXC_BUFFER_COUNT; index++) {
        if (dxc_buffers[index] != NULL) {
            ID3D12Resource_Release(dxc_buffers[index]);
            dxc_buffers[index] = NULL;
        }
    }
    dxc_texture_width = 0;
    dxc_texture_height = 0;
}

/** Lets go of everything holding the swapchain's buffer, so it can be refitted. */
static void dxc_release_buffers(void) {
    // Direct3D 11 defers destruction until its context is flushed, and the swapchain
    // will not resize while anything of the old size is still alive.
    if (dxc_d3d11_context != NULL) {
        ID3D11DeviceContext_ClearState(dxc_d3d11_context);
        ID3D11DeviceContext_Flush(dxc_d3d11_context);
    }
}

/**
 * Makes sure the texture Skia draws into holds width x height, wrapped for Direct3D 11.
 * Grows it only when it is too small, and then to at least the monitor the window is on,
 * rounded up to 256, so a drag grows it once at most.
 */
static int32_t dxc_acquire_buffers(int32_t width, int32_t height) {
    if (dxc_buffers[0] != NULL && dxc_wrapped != NULL && width <= dxc_texture_width &&
        height <= dxc_texture_height) {
        return 0;
    }
    dxc_release_texture();
    MONITORINFO monitor;
    memset(&monitor, 0, sizeof monitor);
    monitor.cbSize = sizeof monitor;
    if (dxc_window != NULL &&
        GetMonitorInfoW(MonitorFromWindow(dxc_window, MONITOR_DEFAULTTONEAREST), &monitor)) {
        int32_t monitor_width = (int32_t)(monitor.rcMonitor.right - monitor.rcMonitor.left);
        int32_t monitor_height = (int32_t)(monitor.rcMonitor.bottom - monitor.rcMonitor.top);
        if (monitor_width > width) width = monitor_width;
        if (monitor_height > height) height = monitor_height;
    }
    width = (width + 255) / 256 * 256;
    height = (height + 255) / 256 * 256;
    D3D12_HEAP_PROPERTIES heap;
    memset(&heap, 0, sizeof heap);
    heap.Type = D3D12_HEAP_TYPE_DEFAULT;
    D3D12_RESOURCE_DESC description;
    memset(&description, 0, sizeof description);
    description.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    description.Width = (UINT64)width;
    description.Height = (UINT)height;
    description.DepthOrArraySize = 1;
    description.MipLevels = 1;
    description.Format = DXC_SWAPCHAIN_FORMAT;
    description.SampleDesc.Count = 1;
    description.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
    description.Flags = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET;
    if (FAILED(ID3D12Device_CreateCommittedResource(
            dxc_device, &heap, D3D12_HEAP_FLAG_NONE, &description, D3D12_RESOURCE_STATE_PRESENT,
            NULL, &IID_ID3D12Resource, (void **)&dxc_buffers[0]))) {
        dxc_buffers[0] = NULL;
        return 1;
    }
    // In and out in the present state, which is where dxc_native_frame_end leaves the
    // texture after Skia, and where Skia is told to find it.
    D3D11_RESOURCE_FLAGS flags;
    memset(&flags, 0, sizeof flags);
    flags.BindFlags = D3D11_BIND_RENDER_TARGET;
    if (FAILED(ID3D11On12Device_CreateWrappedResource(
            dxc_on12, (IUnknown *)dxc_buffers[0], &flags, D3D12_RESOURCE_STATE_PRESENT,
            D3D12_RESOURCE_STATE_PRESENT, &IID_ID3D11Resource, (void **)&dxc_wrapped))) {
        dxc_wrapped = NULL;
        dxc_release_texture();
        return 1;
    }
    dxc_texture_width = width;
    dxc_texture_height = height;
    fprintf(stderr, "compose-rust: resize-mode: draw texture -> %dx%d because a frame needed more room\n",
            (int)width, (int)height);
    return 0;
}

/**
 * Lets go of a window that was only half made.
 *
 * Everything the caller had reached by the time it failed, and the globals with it, so
 * that a later frame finds no window rather than a window missing a piece of itself.
 */
static void dxc_abandon_window(IDXGIAdapter1 *adapter) {
    dxc_release_texture();
    dxc_release_buffers();
    if (dxc_commands != NULL) { ID3D12GraphicsCommandList_Release(dxc_commands); dxc_commands = NULL; }
    if (dxc_allocator != NULL) { ID3D12CommandAllocator_Release(dxc_allocator); dxc_allocator = NULL; }
    if (dxc_fence != NULL) { ID3D12Fence_Release(dxc_fence); dxc_fence = NULL; }
    if (dxc_fence_signalled != NULL) { CloseHandle(dxc_fence_signalled); dxc_fence_signalled = NULL; }
    dxc_latency_wait = NULL;
    if (dxc_swapchain != NULL) { IDXGISwapChain1_Release(dxc_swapchain); dxc_swapchain = NULL; }
    if (dxc_on12 != NULL) { ID3D11On12Device_Release(dxc_on12); dxc_on12 = NULL; }
    if (dxc_d3d11_context != NULL) { ID3D11DeviceContext_Release(dxc_d3d11_context); dxc_d3d11_context = NULL; }
    if (dxc_d3d11 != NULL) { ID3D11Device_Release(dxc_d3d11); dxc_d3d11 = NULL; }
    if (dxc_queue != NULL) { ID3D12CommandQueue_Release(dxc_queue); dxc_queue = NULL; }
    if (dxc_device != NULL) { ID3D12Device_Release(dxc_device); dxc_device = NULL; }
    if (adapter != NULL) { IDXGIAdapter1_Release(adapter); }
    if (dxc_window != NULL) { DestroyWindow(dxc_window); dxc_window = NULL; }
}

static float dxc_scale_of(HWND window) {
    UINT dpi = GetDpiForWindow(window);
    if (dpi == 0) {
        dpi = USER_DEFAULT_SCREEN_DPI;
    }
    return (float)dpi / (float)USER_DEFAULT_SCREEN_DPI;
}

static const wchar_t *DXC_WINDOW_CLASS = L"ComposeRustWindow";

static int32_t dxc_register_class(void) {
    static int registered;
    if (registered) {
        return 0;
    }
    WNDCLASSEXW description;
    memset(&description, 0, sizeof description);
    description.cbSize = sizeof description;
    // Redrawn whole on either axis changing, because the swapchain owns every pixel and
    // has no use for a partial invalidation.
    description.style = CS_HREDRAW | CS_VREDRAW;
    description.lpfnWndProc = dxc_native_window_proc;
    description.hInstance = GetModuleHandleW(NULL);
    description.hCursor = LoadCursorW(NULL, IDC_ARROW);
    // No background brush. Windows would otherwise fill the client area with it before
    // the first frame lands, which reads as a white flash on a dark scene.
    description.hbrBackground = NULL;
    description.lpszClassName = DXC_WINDOW_CLASS;
    if (RegisterClassExW(&description) == 0) {
        return 1;
    }
    registered = 1;
    return 0;
}

/**
 * Opens a window with a Direct3D 12 swapchain filling it.
 *
 * Returns zero on success. Anything else says which part of the machine did not answer,
 * and those are the failures here that are not a mistake of ours: a machine with no
 * Direct3D 12 adapter has nothing this path can use.
 */
int32_t dxc_native_window_open(
    const char *title,
    int32_t width,
    int32_t height,
    struct dxc_native_window *out
) {
    // Asked for before the window exists, so the sizes below are read in real pixels
    // rather than in the ones Windows would have stretched for us. The shim this library
    // is entered through asks for the same thing at startup, and asking twice costs a
    // refusal nobody reads; this file opening a window without it would cost a window at
    // the wrong size.
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);

    if (dxc_register_class() != 0) {
        return 1;
    }

    wchar_t wide_title[256];
    if (MultiByteToWideChar(CP_UTF8, 0, title, -1, wide_title,
                            (int)(sizeof wide_title / sizeof *wide_title)) == 0) {
        wide_title[0] = L'\0';
    }

    // With its redirection surface, as Flutter's view has one: while a resize is being
    // drawn, DWM keeps showing the last presented frame instead of the desktop.
    HWND window = CreateWindowExW(
        0,
        DXC_WINDOW_CLASS,
        wide_title,
        dxc_window_style(),
        CW_USEDEFAULT, CW_USEDEFAULT, width, height,
        NULL, NULL, GetModuleHandleW(NULL), NULL);
    if (window == NULL) {
        return 2;
    }

    // The size that was asked for is in points, and the window was made in whatever
    // Windows took those numbers to be. Now that there is a window there is a monitor to
    // ask, so the client area is set to the pixels those points come to.
    UINT dpi = GetDpiForWindow(window);
    if (dpi == 0) {
        dpi = USER_DEFAULT_SCREEN_DPI;
    }
    RECT wanted;
    wanted.left = 0;
    wanted.top = 0;
    wanted.right = MulDiv(width, (int)dpi, USER_DEFAULT_SCREEN_DPI);
    wanted.bottom = MulDiv(height, (int)dpi, USER_DEFAULT_SCREEN_DPI);
    AdjustWindowRectExForDpi(&wanted, dxc_window_style(), FALSE, 0, dpi);
    int outer_width = wanted.right - wanted.left;
    // Without the system caption the content starts at the top of the window, so the
    // frame above it is not part of the outside.
    int outer_height = dxc_options.system_chrome ? wanted.bottom - wanted.top : wanted.bottom;
    // Centred on the part of the screen a window is meant to sit in. Where that cannot be
    // asked for, the window keeps the place Windows chose for it rather than being moved
    // to a corner that was never a position.
    RECT work;
    UINT placement = SWP_NOZORDER | SWP_NOMOVE;
    int left = 0;
    int top = 0;
    if (SystemParametersInfoW(SPI_GETWORKAREA, 0, &work, 0)) {
        left = work.left + ((work.right - work.left) - outer_width) / 2;
        top = work.top + ((work.bottom - work.top) - outer_height) / 2;
        placement = SWP_NOZORDER;
    }
    SetWindowPos(window, NULL, left, top, outer_width, outer_height, placement | SWP_FRAMECHANGED);

    RECT client;
    GetClientRect(window, &client);
    UINT pixel_width = (UINT)(client.right - client.left);
    UINT pixel_height = (UINT)(client.bottom - client.top);
    if (pixel_width == 0 || pixel_height == 0) {
        DestroyWindow(window);
        return 3;
    }

    IDXGIFactory4 *factory = NULL;
    if (FAILED(CreateDXGIFactory2(0, &IID_IDXGIFactory4, (void **)&factory))) {
        DestroyWindow(window);
        return 4;
    }

    // The first adapter that is a real one and can make a device. A software adapter is
    // skipped rather than taken: it would draw, slowly, and hide the fact that the
    // machine has nothing to draw with.
    IDXGIAdapter1 *adapter = NULL;
    ID3D12Device *device = NULL;
    for (UINT index = 0;
         IDXGIFactory4_EnumAdapters1(factory, index, &adapter) != DXGI_ERROR_NOT_FOUND;
         index++) {
        DXGI_ADAPTER_DESC1 description;
        if (SUCCEEDED(IDXGIAdapter1_GetDesc1(adapter, &description)) &&
            (description.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) == 0 &&
            SUCCEEDED(D3D12CreateDevice((IUnknown *)adapter, D3D_FEATURE_LEVEL_11_0,
                                        &IID_ID3D12Device, (void **)&device))) {
            dxc_device_luid = description.AdapterLuid;
            break;
        }
        IDXGIAdapter1_Release(adapter);
        adapter = NULL;
    }
    // Unless whoever started this asked for the software one by name. A machine with no
    // graphics card at all, a virtual machine or a CI runner, has nothing else, and there
    // drawing slowly is the point: it is how a window that draws is told from one that does
    // not where nobody is watching. Asked for, never fallen back to, for the reason above.
    if (device == NULL) {
        const char *warp = getenv("DXC_D3D12_WARP");
        IDXGIAdapter *software = NULL;
        if (warp != NULL && warp[0] != '\0' &&
            SUCCEEDED(IDXGIFactory4_EnumWarpAdapter(factory, &IID_IDXGIAdapter1, (void **)&software))) {
            adapter = (IDXGIAdapter1 *)software;
            DXGI_ADAPTER_DESC1 warp_description;
            if (SUCCEEDED(IDXGIAdapter1_GetDesc1(adapter, &warp_description))) {
                dxc_device_luid = warp_description.AdapterLuid;
            }
            if (FAILED(D3D12CreateDevice((IUnknown *)adapter, D3D_FEATURE_LEVEL_11_0,
                                         &IID_ID3D12Device, (void **)&device))) {
                device = NULL;
            }
        }
    }
    if (device == NULL) {
        if (adapter != NULL) IDXGIAdapter1_Release(adapter);
        IDXGIFactory4_Release(factory);
        DestroyWindow(window);
        return 5;
    }

    D3D12_COMMAND_QUEUE_DESC queue_description;
    memset(&queue_description, 0, sizeof queue_description);
    queue_description.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    queue_description.Flags = D3D12_COMMAND_QUEUE_FLAG_NONE;
    // High, not global realtime: only realtime needs privileges.
    queue_description.Priority = dxc_priority() >= 1 ? D3D12_COMMAND_QUEUE_PRIORITY_HIGH
                                                     : D3D12_COMMAND_QUEUE_PRIORITY_NORMAL;
    ID3D12CommandQueue *queue = NULL;
    HRESULT queued = ID3D12Device_CreateCommandQueue(device, &queue_description,
                                                     &IID_ID3D12CommandQueue, (void **)&queue);
    if (FAILED(queued) && queue_description.Priority != D3D12_COMMAND_QUEUE_PRIORITY_NORMAL) {
        queue_description.Priority = D3D12_COMMAND_QUEUE_PRIORITY_NORMAL;
        queued = ID3D12Device_CreateCommandQueue(device, &queue_description,
                                                 &IID_ID3D12CommandQueue, (void **)&queue);
    }
    if (FAILED(queued)) {
        ID3D12Device_Release(device);
        IDXGIAdapter1_Release(adapter);
        IDXGIFactory4_Release(factory);
        DestroyWindow(window);
        return 6;
    }

    // Direct3D 11 on the renderer's Direct3D 12 device and queue, for the swapchain.
    // Looked up at run time so the build links nothing new.
    ID3D11Device *d3d11 = NULL;
    ID3D11DeviceContext *d3d11_context = NULL;
    ID3D11On12Device *on12 = NULL;
    HMODULE d3d11_library = LoadLibraryW(L"d3d11.dll");
    PFN_D3D11ON12_CREATE_DEVICE create_on12 = d3d11_library == NULL ? NULL
        : (PFN_D3D11ON12_CREATE_DEVICE)(void *)GetProcAddress(d3d11_library, "D3D11On12CreateDevice");
    IUnknown *queues[1] = {(IUnknown *)queue};
    if (create_on12 == NULL ||
        FAILED(create_on12((IUnknown *)device, 0, NULL, 0, queues, 1, 0, &d3d11,
                           &d3d11_context, NULL)) ||
        FAILED(ID3D11Device_QueryInterface(d3d11, &dxc_iid_on12_device, (void **)&on12))) {
        if (d3d11_context != NULL) ID3D11DeviceContext_Release(d3d11_context);
        if (d3d11 != NULL) ID3D11Device_Release(d3d11);
        ID3D12CommandQueue_Release(queue);
        ID3D12Device_Release(device);
        IDXGIAdapter1_Release(adapter);
        IDXGIFactory4_Release(factory);
        DestroyWindow(window);
        return 7;
    }

    // Flutter's window swapchain, as ANGLE makes it for a window without
    // DirectComposition: copy model (DXGI_SWAP_EFFECT_SEQUENTIAL), one buffer, STRETCH.
    // Every resize refits it to exactly the client size before anything is presented, so
    // the stretch is always 1:1.
    DXGI_SWAP_CHAIN_DESC1 swapchain_description;
    memset(&swapchain_description, 0, sizeof swapchain_description);
    swapchain_description.Width = pixel_width;
    swapchain_description.Height = pixel_height;
    swapchain_description.Format = DXC_SWAPCHAIN_FORMAT;
    swapchain_description.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    swapchain_description.BufferCount = DXC_BUFFER_COUNT;
    swapchain_description.SampleDesc.Count = 1;
    swapchain_description.SwapEffect = DXGI_SWAP_EFFECT_SEQUENTIAL;
    swapchain_description.Scaling = DXGI_SCALING_STRETCH;
    swapchain_description.AlphaMode = DXGI_ALPHA_MODE_UNSPECIFIED;
    swapchain_description.Flags = 0;
    IDXGISwapChain1 *swapchain = NULL;
    HRESULT made = IDXGIFactory4_CreateSwapChainForHwnd(
        factory, (IUnknown *)d3d11, window, &swapchain_description, NULL, NULL, &swapchain);
    if (FAILED(made)) {
        ID3D11On12Device_Release(on12);
        ID3D11DeviceContext_Release(d3d11_context);
        ID3D11Device_Release(d3d11);
        ID3D12CommandQueue_Release(queue);
        ID3D12Device_Release(device);
        IDXGIAdapter1_Release(adapter);
        IDXGIFactory4_Release(factory);
        DestroyWindow(window);
        return 7;
    }
    dxc_swapchain_flags = 0;
    int gpu_priority_set = 0;
    if (dxc_priority() >= 2) {
        IDXGIDevice *dxgi_device = NULL;
        if (SUCCEEDED(ID3D11Device_QueryInterface(d3d11, &IID_IDXGIDevice, (void **)&dxgi_device))) {
            gpu_priority_set = SUCCEEDED(IDXGIDevice_SetGPUThreadPriority(dxgi_device, 5));
            IDXGIDevice_Release(dxgi_device);
        }
    }
    fprintf(stderr,
            "compose-rust: resize priority: drag thread above normal %s, command queue %s, "
            "GPU thread priority %s, nothing deferred (accessibility and IME updates already run "
            "outside the resize step)\n",
            dxc_priority() >= 1 ? "on" : "off",
            queue_description.Priority == D3D12_COMMAND_QUEUE_PRIORITY_HIGH ? "high" : "normal",
            gpu_priority_set ? "+5" : (dxc_priority() >= 2 ? "refused" : "off"));
    fprintf(stderr, "compose-rust: swapchain for the window, copy model (sequential), 1 buffer, STRETCH, %ux%u\n",
            pixel_width, pixel_height);
    // DXGI answers alt-enter by putting the window into its own idea of full screen,
    // which is a mode nothing here knows how to draw in.
    IDXGIFactory4_MakeWindowAssociation(factory, window, DXGI_MWA_NO_ALT_ENTER);
    IDXGIFactory4_Release(factory);

    dxc_window = window;
    dxc_device = device;
    dxc_queue = queue;
    dxc_swapchain = swapchain;
    dxc_d3d11 = d3d11;
    dxc_d3d11_context = d3d11_context;
    dxc_on12 = on12;
    // The size frames are drawn at from here until something resizes the window. Written
    // down now so that the size the window reports as it is shown, which is this one, is
    // recognised as the size the swapchain already is.
    dxc_resize_fitted(&dxc_sizing, (int32_t)pixel_width, (int32_t)pixel_height);
    dxc_report_latency = getenv("DXC_REPORT_LATENCY") != NULL;
    dxc_log_monitor(window, "the window opened");
    dxc_presented_width = (int32_t)pixel_width;
    dxc_presented_height = (int32_t)pixel_height;

    if (dxc_acquire_buffers((int32_t)pixel_width, (int32_t)pixel_height) != 0) {
        dxc_abandon_window(adapter);
        return 9;
    }

    // A list of our own, holding one barrier and nothing else. Skia records and submits
    // its own work; what it does not do is put the buffer back into the state a swapchain
    // will accept for presenting, and a buffer presented from any other state is a buffer
    // the debug layer rejects and a driver is free to mishandle.
    if (FAILED(ID3D12Device_CreateCommandAllocator(device, D3D12_COMMAND_LIST_TYPE_DIRECT,
                                                   &IID_ID3D12CommandAllocator,
                                                   (void **)&dxc_allocator)) ||
        FAILED(ID3D12Device_CreateCommandList(device, 0, D3D12_COMMAND_LIST_TYPE_DIRECT,
                                              dxc_allocator, NULL,
                                              &IID_ID3D12GraphicsCommandList,
                                              (void **)&dxc_commands)) ||
        FAILED(ID3D12Device_CreateFence(device, 0, D3D12_FENCE_FLAG_NONE, &IID_ID3D12Fence,
                                        (void **)&dxc_fence))) {
        dxc_abandon_window(adapter);
        return 10;
    }
    ID3D12GraphicsCommandList_Close(dxc_commands);
    dxc_fence_signalled = CreateEventW(NULL, FALSE, FALSE, NULL);
    if (dxc_fence_signalled == NULL) {
        dxc_abandon_window(adapter);
        return 11;
    }

    dxc_accept_files(window);
    ShowWindow(window, SW_SHOW);
    SetForegroundWindow(window);
    SetFocus(window);

    out->window = (void *)window;
    out->device = (void *)device;
    out->queue = (void *)queue;
    out->adapter = (void *)adapter;
    dxc_adapter = adapter;
    out->swapchain = (void *)swapchain;
    return 0;
}

/**
 * What the window is drawn at, in pixels, and how many of them go to a point.
 *
 * The size the swapchain is drawn at and not the client area's. The two agree as soon as
 * a resize has been taken, and where one was refused they do not: a buffer described to
 * Skia as bigger than it is would be painted past its end. The client area answers only
 * before there is a swapchain to ask.
 */
void dxc_native_window_size(void *window_pointer, int32_t *width, int32_t *height, float *scale) {
    HWND window = (HWND)window_pointer;
    *width = 0;
    *height = 0;
    *scale = 1.0f;
    if (window == NULL) {
        return;
    }
    *scale = dxc_scale_of(window);
    // The size the swapchain was last refitted to, which is the size drawn at.
    if (dxc_swapchain != NULL && dxc_sizing.fitted_width > 0 && dxc_sizing.fitted_height > 0) {
        *width = dxc_sizing.fitted_width;
        *height = dxc_sizing.fitted_height;
        return;
    }
    RECT client;
    if (GetClientRect(window, &client)) {
        *width = (int32_t)(client.right - client.left);
        *height = (int32_t)(client.bottom - client.top);
    }
}

/**
 * Answers the buffer this frame paints into.
 *
 * Non-zero when there is nothing to paint into: the window has been closed, or it is
 * minimised, or the swapchain could not be made to fit a size it has just been given.
 * None of those is an error. The frame is skipped and the next one asks again.
 */
int32_t dxc_native_frame_begin(void *swapchain_pointer, void **texture_out) {
    IDXGISwapChain1 *swapchain = (IDXGISwapChain1 *)swapchain_pointer;
    if (swapchain == NULL || dxc_window == NULL) {
        return 1;
    }

    // The size the window was last given, where that is not the size it is already drawn
    // at. Showing the window reports a size as well, and it is the size the swapchain was
    // just made, so the common case costs a comparison rather than a round of releasing
    // and taking back every buffer.
    // One frame of latency. Inside a drag the wait is none at all: a frame skipped there
    // is the black this exists to avoid, so it is drawn whether or not the screen is ready.
    if (dxc_latency_wait != NULL) {
        WaitForSingleObjectEx(dxc_latency_wait,
                              dxc_sizing.dragging || dxc_resizing ? 0 : 100, FALSE);
    }
    int32_t wanted_width = 0;
    int32_t wanted_height = 0;
    if (dxc_resize_take(&dxc_sizing, &wanted_width, &wanted_height)) {
        // Refitted to exactly the size drawn, every step, so the buffer and the client
        // area always agree and STRETCH is always 1:1.
        //
        // Nothing may still be reading the buffers when they are let go, and a swapchain
        // refuses to be refitted while anything holds one.
        LARGE_INTEGER refit_started;
        QueryPerformanceCounter(&refit_started);
        dxc_wait_for_gpu();
        dxc_step_gpu_idle_ms = dxc_elapsed_ms(refit_started);
        dxc_release_buffers();
        HRESULT resized = IDXGISwapChain1_ResizeBuffers(
            swapchain, DXC_BUFFER_COUNT, (UINT)wanted_width, (UINT)wanted_height,
            DXC_SWAPCHAIN_FORMAT, dxc_swapchain_flags);
        // A refusal leaves the swapchain the size it was, so the old buffers are taken
        // back and the window carries on drawing at the size it had. Losing this frame
        // is a stretched image for a moment; not taking them back is a window that
        // stays black from here on.
        if (FAILED(resized)) {
            dxc_log_dxgi_failure("ResizeBuffers", resized);
            dxc_acquire_buffers(dxc_sizing.fitted_width, dxc_sizing.fitted_height);
            return 2;
        }
        if (dxc_acquire_buffers(wanted_width, wanted_height) != 0) {
            fprintf(stderr, "compose-rust: resize-mode: skipped frame %dx%d because the draw texture could not be made\n",
                    (int)wanted_width, (int)wanted_height);
            return 2;
        }
        dxc_resize_fitted(&dxc_sizing, wanted_width, wanted_height);
        dxc_step_refit_ms = dxc_elapsed_ms(refit_started) - dxc_step_gpu_idle_ms;
    }

    dxc_frame_index = 0;
    if (dxc_buffers[dxc_frame_index] == NULL || dxc_wrapped == NULL) {
        fprintf(stderr, "compose-rust: resize-mode: skipped frame because there is no draw texture\n");
        return 3;
    }
    *texture_out = (void *)dxc_buffers[dxc_frame_index];
    // From here until dxc_native_frame_end is the renderer's: the Skia surface, the
    // scene's layout and drawing, and its submit.
    QueryPerformanceCounter(&dxc_step_mark);
    return 0;
}

/** Puts the painted buffer on the screen. */
void dxc_native_frame_end(void *queue_pointer) {
    ID3D12CommandQueue *queue = (ID3D12CommandQueue *)queue_pointer;
    if (queue == NULL || dxc_swapchain == NULL || dxc_buffers[dxc_frame_index] == NULL ||
        dxc_allocator == NULL || dxc_commands == NULL) {
        return;
    }

    // Skia drew into this buffer, so it left it as a render target, and that is what the
    // barrier says it is coming from. The Kotlin side declares the buffer to Skia as
    // being ready to present, which is what it is put back to here, so the two
    // descriptions stay true of the same buffer frame after frame.
    dxc_step_draw_ms = dxc_elapsed_ms(dxc_step_mark);
    LARGE_INTEGER present_started;
    QueryPerformanceCounter(&present_started);
    // The allocator may be reset only once the list recorded from it last frame has run.
    // Usually it has (Skia's submit waited for work queued after it); this waits only
    // when it has not.
    if (dxc_last_list_mark != 0 && ID3D12Fence_GetCompletedValue(dxc_fence) < dxc_last_list_mark &&
        SUCCEEDED(ID3D12Fence_SetEventOnCompletion(dxc_fence, dxc_last_list_mark, dxc_fence_signalled))) {
        WaitForSingleObject(dxc_fence_signalled, INFINITE);
    }
    ID3D12CommandAllocator_Reset(dxc_allocator);
    ID3D12GraphicsCommandList_Reset(dxc_commands, dxc_allocator, NULL);
    D3D12_RESOURCE_BARRIER barrier;
    memset(&barrier, 0, sizeof barrier);
    barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    barrier.Flags = D3D12_RESOURCE_BARRIER_FLAG_NONE;
    barrier.Transition.pResource = dxc_buffers[dxc_frame_index];
    barrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    barrier.Transition.StateBefore = D3D12_RESOURCE_STATE_RENDER_TARGET;
    barrier.Transition.StateAfter = D3D12_RESOURCE_STATE_PRESENT;
    ID3D12GraphicsCommandList_ResourceBarrier(dxc_commands, 1, &barrier);
    ID3D12GraphicsCommandList_Close(dxc_commands);
    ID3D12CommandList *lists[1];
    lists[0] = (ID3D12CommandList *)(void *)dxc_commands;
    ID3D12CommandQueue_ExecuteCommandLists(queue, 1, lists);
    if (SUCCEEDED(ID3D12CommandQueue_Signal(queue, dxc_fence, dxc_fence_value + 1))) {
        dxc_last_list_mark = ++dxc_fence_value;
    }

    // A resize frame presents only at exactly the size WM_SIZE recorded, and only once
    // for that size: a frame of any other size would be shown cut or padded against the
    // new rectangle, and a second present of the same size is a frame out of order.
    if (dxc_resizing &&
        (dxc_sizing.fitted_width != dxc_resize_target_width ||
         dxc_sizing.fitted_height != dxc_resize_target_height ||
         (dxc_presented_width == dxc_resize_target_width &&
          dxc_presented_height == dxc_resize_target_height))) {
        fprintf(stderr, "compose-rust: resize-mode: skipped present %dx%d because %s (target %dx%d)\n",
                (int)dxc_sizing.fitted_width, (int)dxc_sizing.fitted_height,
                (dxc_sizing.fitted_width != dxc_resize_target_width ||
                 dxc_sizing.fitted_height != dxc_resize_target_height)
                    ? "the frame was drawn at another size"
                    : "that size was already presented",
                (int)dxc_resize_target_width, (int)dxc_resize_target_height);
        dxc_wait_for_gpu();
        return;
    }
    // The copy into the swapchain's buffer, on the same queue after the barrier above,
    // through Direct3D 11. One GPU copy of the frame per present.
    LARGE_INTEGER copy_started;
    QueryPerformanceCounter(&copy_started);
    ID3D11Resource *back = NULL;
    HRESULT got = IDXGISwapChain1_GetBuffer(dxc_swapchain, 0, &IID_ID3D11Resource, (void **)&back);
    if (FAILED(got)) {
        dxc_log_dxgi_failure("GetBuffer", got);
        dxc_wait_for_gpu();
        return;
    }
    ID3D11On12Device_AcquireWrappedResources(dxc_on12, &dxc_wrapped, 1);
    D3D11_BOX drawn = {0, 0, 0, (UINT)dxc_sizing.fitted_width, (UINT)dxc_sizing.fitted_height, 1};
    ID3D11DeviceContext_CopySubresourceRegion(dxc_d3d11_context, back, 0, 0, 0, 0,
                                              dxc_wrapped, 0, &drawn);
    ID3D11On12Device_ReleaseWrappedResources(dxc_on12, &dxc_wrapped, 1);
    ID3D11DeviceContext_Flush(dxc_d3d11_context);
    ID3D11Resource_Release(back);
    dxc_step_copy_ms = dxc_elapsed_ms(copy_started);
    // Interval one outside a resize, so the frame waits for the screen; a window that
    // presents without waiting spends a machine to draw frames nobody sees. Zero inside
    // one, where the DwmFlush that follows paces it instead and a vertical blank waited
    // for first would hold the drag for one more frame.
    HRESULT shown = IDXGISwapChain1_Present(dxc_swapchain, dxc_resizing ? 0 : 1, 0);
    if (FAILED(shown)) {
        dxc_log_dxgi_failure("Present", shown);
    }
    dxc_presented_width = dxc_sizing.fitted_width;
    dxc_presented_height = dxc_sizing.fitted_height;

    // No wait for the GPU here. The next frame's Skia work and the copy are on the same
    // queue, which runs them in order, and Skia's submit waits for its own work, which
    // follows this frame's barrier list, before the allocator is reset again. A refit
    // still waits, in dxc_native_frame_begin, before letting go of anything.
    dxc_step_present_ms = dxc_elapsed_ms(present_started) - dxc_step_copy_ms;
}

/** Says where the caret is, in pixels from the window's top left. */
void dxc_native_set_ime_spot(float x, float y) {
    dxc_ime_spot_x = (LONG)x;
    dxc_ime_spot_y = (LONG)y;
    if (dxc_window != NULL && dxc_ime_composing) {
        dxc_position_ime(dxc_window);
    }
}

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
#include <uiautomation.h>
#include <uiautomationcoreapi.h>
#include <oleauto.h>
#include <stdint.h>
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

// How many buffers the swapchain flips between. Two is what a window that waits for the
// vertical blank needs; a third only buys anything to a renderer that runs ahead of the
// screen, and this one does not.
#define DXC_BUFFER_COUNT 2

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
static IDXGISwapChain3 *dxc_swapchain;
static ID3D12Device *dxc_device;
static ID3D12CommandQueue *dxc_queue;
static ID3D12Resource *dxc_buffers[DXC_BUFFER_COUNT];
static ID3D12CommandAllocator *dxc_allocator;
static ID3D12GraphicsCommandList *dxc_commands;
static ID3D12Fence *dxc_fence;
static HANDLE dxc_fence_signalled;
static UINT64 dxc_fence_value;
static UINT dxc_frame_index;
// The size the window has been given and the size it is drawn at, which are the same
// except while a resize is being taken. A swapchain cannot be refitted while the buffer
// being refitted is the one being drawn into, so the size is written down here and acted
// on where a frame begins.
static struct dxc_resize dxc_sizing;
// Set when the window has gone, so the frame loop stops rather than drawing into nothing.
static int dxc_window_gone;

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
    if (dxc_draw_frame != NULL) {
        dxc_draw_frame(dxc_draw_thread);
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
        out->bstrVal = SysAllocString(L"dioxus-compose element");
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
        out->bstrVal = SysAllocString(L"dioxus-compose root");
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

static void dxc_position_ime(HWND window) {
    HIMC context = ImmGetContext(window);
    if (context == NULL) return;
    COMPOSITIONFORM position;
    memset(&position, 0, sizeof position);
    position.dwStyle = CFS_POINT;
    // The caret position has not crossed from Compose yet. Keep the IME window at the
    // client area's top left, as the macOS text client does for the same reason.
    position.ptCurrentPos.x = 0;
    position.ptCurrentPos.y = 0;
    ImmSetCompositionWindow(context, &position);
    ImmReleaseContext(window, context);
}

// Named apart from the one in `renderer_entry.c`, which subclasses the toolkit's frame
// to reclaim its caption. That one goes looking for a window of AWT's class and will
// not find this one, so the two never meet; the names are kept distinct anyway,
// because a reader who found both would have every reason to think they were.
static LRESULT CALLBACK dxc_native_window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
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
        return 0;
    case WM_EXITSIZEMOVE:
        // Let go. The frame loop has its turns back, so a size arriving after this is
        // written down and taken by the next frame.
        dxc_resize_end_drag(&dxc_sizing);
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
    case WM_SIZE:
        // Written down rather than acted on. The buffer being refitted may be the one the
        // frame in flight is drawing into, so the swapchain is refitted where a frame
        // begins instead. Nothing to do while minimised: the client area is empty and a
        // swapchain cannot have a zero dimension.
        if (dxc_swapchain != NULL && wparam != SIZE_MINIMIZED) {
            dxc_resize_note(&dxc_sizing, (int32_t)LOWORD(lparam), (int32_t)HIWORD(lparam));
            // Inside a drag the note is not enough. Nothing is going to come back and
            // read it: the frame loop is stopped several frames back inside the press
            // that began the drag, and what the screen shows meanwhile is the last frame
            // the window drew, stretched or cut to whatever size the window now is. The
            // frame is drawn here instead, inside this message, which is the only place
            // that runs while the reader is dragging.
            if (dxc_resize_draw_here(&dxc_sizing)) {
                dxc_draw_one_frame();
            }
        }
        return 0;
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

/*
 * The names below are answered here and do nothing.
 *
 * One piece of Kotlin drives every desktop and reaches their windows by name, so each of
 * these files answers every name, including the ones that mean nothing on it. A missing
 * one is a warning on the linkers that look names up at load time and a failure on the
 * ones that do not, which is a defect that travels to whoever builds for the strictest
 * platform. It travelled three times before this was written down.
 */
int32_t dxc_native_clipboard_read(char *out, int32_t capacity) {
    (void)out;
    (void)capacity;
    return 0;
}

void dxc_native_clipboard_write(const char *text) {
    (void)text;
}

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

static void dxc_release_buffers(void) {
    for (int index = 0; index < DXC_BUFFER_COUNT; index++) {
        if (dxc_buffers[index] != NULL) {
            ID3D12Resource_Release(dxc_buffers[index]);
            dxc_buffers[index] = NULL;
        }
    }
}

static int32_t dxc_acquire_buffers(void) {
    for (int index = 0; index < DXC_BUFFER_COUNT; index++) {
        HRESULT taken = IDXGISwapChain3_GetBuffer(
            dxc_swapchain, (UINT)index, &IID_ID3D12Resource, (void **)&dxc_buffers[index]);
        if (FAILED(taken)) {
            dxc_release_buffers();
            return 1;
        }
    }
    return 0;
}

/**
 * Lets go of a window that was only half made.
 *
 * Everything the caller had reached by the time it failed, and the globals with it, so
 * that a later frame finds no window rather than a window missing a piece of itself.
 */
static void dxc_abandon_window(IDXGIAdapter1 *adapter) {
    dxc_release_buffers();
    if (dxc_commands != NULL) { ID3D12GraphicsCommandList_Release(dxc_commands); dxc_commands = NULL; }
    if (dxc_allocator != NULL) { ID3D12CommandAllocator_Release(dxc_allocator); dxc_allocator = NULL; }
    if (dxc_fence != NULL) { ID3D12Fence_Release(dxc_fence); dxc_fence = NULL; }
    if (dxc_fence_signalled != NULL) { CloseHandle(dxc_fence_signalled); dxc_fence_signalled = NULL; }
    if (dxc_swapchain != NULL) { IDXGISwapChain3_Release(dxc_swapchain); dxc_swapchain = NULL; }
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

static const wchar_t *DXC_WINDOW_CLASS = L"DioxusComposeWindow";

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

    HWND window = CreateWindowExW(
        0,
        DXC_WINDOW_CLASS,
        wide_title,
        WS_OVERLAPPEDWINDOW,
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
    AdjustWindowRectExForDpi(&wanted, WS_OVERLAPPEDWINDOW, FALSE, 0, dpi);
    int outer_width = wanted.right - wanted.left;
    int outer_height = wanted.bottom - wanted.top;
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
    SetWindowPos(window, NULL, left, top, outer_width, outer_height, placement);

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
    ID3D12CommandQueue *queue = NULL;
    if (FAILED(ID3D12Device_CreateCommandQueue(device, &queue_description,
                                               &IID_ID3D12CommandQueue, (void **)&queue))) {
        ID3D12Device_Release(device);
        IDXGIAdapter1_Release(adapter);
        IDXGIFactory4_Release(factory);
        DestroyWindow(window);
        return 6;
    }

    DXGI_SWAP_CHAIN_DESC1 swapchain_description;
    memset(&swapchain_description, 0, sizeof swapchain_description);
    swapchain_description.Width = pixel_width;
    swapchain_description.Height = pixel_height;
    swapchain_description.Format = DXC_SWAPCHAIN_FORMAT;
    swapchain_description.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    swapchain_description.BufferCount = DXC_BUFFER_COUNT;
    swapchain_description.SampleDesc.Count = 1;
    swapchain_description.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    swapchain_description.AlphaMode = DXGI_ALPHA_MODE_IGNORE;
    IDXGISwapChain1 *first = NULL;
    HRESULT made = IDXGIFactory4_CreateSwapChainForHwnd(
        factory, (IUnknown *)queue, window, &swapchain_description, NULL, NULL, &first);
    if (FAILED(made)) {
        ID3D12CommandQueue_Release(queue);
        ID3D12Device_Release(device);
        IDXGIAdapter1_Release(adapter);
        IDXGIFactory4_Release(factory);
        DestroyWindow(window);
        return 7;
    }
    // DXGI answers alt-enter by putting the window into its own idea of full screen,
    // which is a mode nothing here knows how to draw in.
    IDXGIFactory4_MakeWindowAssociation(factory, window, DXGI_MWA_NO_ALT_ENTER);
    IDXGIFactory4_Release(factory);

    IDXGISwapChain3 *swapchain = NULL;
    // The third revision is the one that will say which buffer is next, and a swapchain
    // that flips has no other way of telling.
    HRESULT upgraded = IDXGISwapChain1_QueryInterface(first, &IID_IDXGISwapChain3,
                                                      (void **)&swapchain);
    IDXGISwapChain1_Release(first);
    if (FAILED(upgraded)) {
        ID3D12CommandQueue_Release(queue);
        ID3D12Device_Release(device);
        IDXGIAdapter1_Release(adapter);
        DestroyWindow(window);
        return 8;
    }

    dxc_window = window;
    dxc_device = device;
    dxc_queue = queue;
    dxc_swapchain = swapchain;
    // The size frames are drawn at from here until something resizes the window. Written
    // down now so that the size the window reports as it is shown, which is this one, is
    // recognised as the size the swapchain already is.
    dxc_resize_fitted(&dxc_sizing, (int32_t)pixel_width, (int32_t)pixel_height);

    if (dxc_acquire_buffers() != 0) {
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

    ShowWindow(window, SW_SHOW);
    SetForegroundWindow(window);
    SetFocus(window);

    out->window = (void *)window;
    out->device = (void *)device;
    out->queue = (void *)queue;
    out->adapter = (void *)adapter;
    out->swapchain = (void *)swapchain;
    return 0;
}

/**
 * What the window is drawn at, in pixels, and how many of them go to a point.
 *
 * The swapchain's size and not the client area's. The two agree as soon as a resize has
 * been taken, and where one was refused they do not: a buffer described to Skia as bigger
 * than it is would be painted past its end. The client area answers only before there is
 * a swapchain to ask.
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
    DXGI_SWAP_CHAIN_DESC1 description;
    if (dxc_swapchain != NULL &&
        SUCCEEDED(IDXGISwapChain3_GetDesc1(dxc_swapchain, &description))) {
        *width = (int32_t)description.Width;
        *height = (int32_t)description.Height;
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
    IDXGISwapChain3 *swapchain = (IDXGISwapChain3 *)swapchain_pointer;
    if (swapchain == NULL || dxc_window == NULL) {
        return 1;
    }

    // The size the window was last given, where that is not the size it is already drawn
    // at. Showing the window reports a size as well, and it is the size the swapchain was
    // just made, so the common case costs a comparison rather than a round of releasing
    // and taking back every buffer.
    int32_t wanted_width = 0;
    int32_t wanted_height = 0;
    if (dxc_resize_take(&dxc_sizing, &wanted_width, &wanted_height)) {
        // Nothing may still be reading the buffers when they are let go, and a swapchain
        // refuses to be refitted while anything holds one.
        dxc_wait_for_gpu();
        dxc_release_buffers();
        HRESULT resized = IDXGISwapChain3_ResizeBuffers(
            swapchain, DXC_BUFFER_COUNT, (UINT)wanted_width, (UINT)wanted_height,
            DXC_SWAPCHAIN_FORMAT, 0);
        // A refusal leaves the swapchain the size it was, so the old buffers are taken
        // back and the window carries on drawing at the size it had. Losing this frame
        // is a stretched image for a moment; not taking them back is a window that
        // stays black from here on.
        if (FAILED(resized)) {
            dxc_acquire_buffers();
            return 2;
        }
        if (dxc_acquire_buffers() != 0) {
            return 2;
        }
        dxc_resize_fitted(&dxc_sizing, wanted_width, wanted_height);
    }

    dxc_frame_index = IDXGISwapChain3_GetCurrentBackBufferIndex(swapchain);
    if (dxc_buffers[dxc_frame_index] == NULL) {
        return 3;
    }
    *texture_out = (void *)dxc_buffers[dxc_frame_index];
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

    // One, so the frame waits for the screen. A window that presents without waiting
    // spends a machine to draw frames nobody sees.
    IDXGISwapChain3_Present(dxc_swapchain, 1, 0);

    // The next frame will paint into a buffer this one may still be reading from, and a
    // swapchain two buffers deep comes back around immediately.
    dxc_wait_for_gpu();
}

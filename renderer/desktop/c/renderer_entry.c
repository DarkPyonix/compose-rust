#ifndef __APPLE__
#define _GNU_SOURCE
#endif

/*
 * Public C entry points of the renderer library.
 *
 * The Host/Renderer boundary is plain C: only primitives, pointers and lengths cross it.
 *
 * GraalVM @CEntryPoint functions need an isolate thread argument. The Host must not have
 * to know about isolates, so this shim owns the single isolate per process and exposes
 * argument-free symbols that forward to the Kotlin `_impl` entry points.
 */
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <windowsx.h>
#include <wchar.h>
#define PATH_MAX (32768 * 4)
#else
#include <dlfcn.h>
#include <libgen.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <unistd.h>
#endif

typedef struct graal_isolate_t graal_isolate_t;
typedef struct graal_isolatethread_t graal_isolatethread_t;

int graal_create_isolate(void *params, graal_isolate_t **isolate, graal_isolatethread_t **thread);
int graal_attach_thread(graal_isolate_t *isolate, graal_isolatethread_t **thread);
graal_isolatethread_t *graal_get_current_thread(graal_isolate_t *isolate);

int32_t compose_rust_renderer_run_impl(graal_isolatethread_t *thread, const char *library_dir);
void compose_rust_renderer_request_frame_impl(graal_isolatethread_t *thread);
int32_t compose_rust_renderer_run(void);

/**
 * Whether the application asked for a material behind its window.
 *
 * Off until the renderer says otherwise, because a window that did not ask must come up
 * the way it always did: no effect view is built for it and nothing about its drawing
 * changes. The renderer knows the answer only after the first batch has been applied,
 * and the macOS poller is still running by then, so a late yes is still acted on.
 *
 * Exported on every desktop so the renderer has one symbol to call. Only macOS has
 * anything to do with the answer today; Windows and Linux record it and draw as before.
 */
#ifdef _WIN32
// MSVC's C compiler has no <stdatomic.h> without an experimental switch, so Windows keeps
// the flag with the Interlocked functions, which are full barriers like atomic_store.
static volatile LONG dxc_window_material_asked;

void dxc_set_window_material(int32_t asked) {
    InterlockedExchange(&dxc_window_material_asked, asked != 0);
}
#else
static atomic_int dxc_window_material_asked;

void dxc_set_window_material(int32_t asked) {
    atomic_store(&dxc_window_material_asked, asked != 0);
}
#endif

#ifdef __APPLE__
#include <dispatch/dispatch.h>
#include <objc/message.h>
#include <objc/runtime.h>

void compose_rust_prepare_main_thread(void);
#endif

enum {
    RUN_OK = 0,
    RUN_ISOLATE_FAILED = -1,
    RUN_ALREADY_RUNNING = -2,
    RUN_LIBRARY_PATH_UNKNOWN = -3,
    RUN_NOT_MAIN_THREAD = -4,
    RUN_THREAD_FAILED = -5,
};

#ifdef _WIN32
static graal_isolate_t *volatile renderer_isolate;
#else
static _Atomic(graal_isolate_t *) renderer_isolate;
#endif

#ifndef _WIN32
/* strlcpy is available on macOS but not glibc. Keep path copying local and portable. */
static void copy_path(char *destination, size_t capacity, const char *source) {
    if (capacity > 0) {
        snprintf(destination, capacity, "%s", source);
    }
}
#endif

struct renderer_run {
    char library_dir[PATH_MAX];
    int32_t status;
#ifndef _WIN32
    atomic_bool finished;
#endif
};

#ifdef _WIN32
/*
 * PE/COFF cannot leave the Host symbols unresolved and bind them from the executable at
 * DLL load time, as the macOS linker does with dynamic_lookup. These definitions satisfy
 * the renderer link and forward the same C ABI, unchanged, to exports on the host
 * executable.
 * The host must export the five compose_rust_host_* functions.
 *
 * UNTESTED: this forwarding path has not been compiled or run in this repository. Verify it
 * on Windows x64 with desktop/scripts/smoke-test-windows.ps1 -RequireClick.
 */
typedef struct {
    const uint8_t *ptr;
    uint32_t len;
    int64_t result;
} MutationBatch;

typedef int32_t (__cdecl *host_init_fn)(const uint8_t *, uint32_t, MutationBatch *);
typedef int32_t (__cdecl *host_dispatch_event_fn)(const uint8_t *, uint32_t, MutationBatch *);
typedef int32_t (__cdecl *host_render_frame_fn)(uint64_t, MutationBatch *);
typedef void (__cdecl *host_release_batch_fn)(MutationBatch *);
typedef void (__cdecl *host_shutdown_fn)(void);

struct host_exports {
    host_init_fn init;
    host_dispatch_event_fn dispatch_event;
    host_render_frame_fn render_frame;
    host_release_batch_fn release_batch;
    host_shutdown_fn shutdown;
};

static INIT_ONCE host_exports_once = INIT_ONCE_STATIC_INIT;
static struct host_exports host_exports;

static BOOL CALLBACK load_host_exports(PINIT_ONCE once, PVOID parameter, PVOID *context) {
    HMODULE host = GetModuleHandleW(NULL);
    (void)once;
    (void)parameter;
    (void)context;
    if (host == NULL) {
        fprintf(stderr, "compose-rust: GetModuleHandleW(NULL) failed (%lu)\n", GetLastError());
        return FALSE;
    }

#define LOAD_HOST_EXPORT(field, name) \
    host_exports.field = (name##_fn)GetProcAddress(host, "compose_rust_host_" #field)
    LOAD_HOST_EXPORT(init, host_init);
    LOAD_HOST_EXPORT(dispatch_event, host_dispatch_event);
    LOAD_HOST_EXPORT(render_frame, host_render_frame);
    LOAD_HOST_EXPORT(release_batch, host_release_batch);
    LOAD_HOST_EXPORT(shutdown, host_shutdown);
#undef LOAD_HOST_EXPORT

    if (host_exports.init == NULL || host_exports.dispatch_event == NULL ||
        host_exports.render_frame == NULL || host_exports.release_batch == NULL ||
        host_exports.shutdown == NULL) {
        fprintf(stderr,
                "compose-rust: host executable must export all compose_rust_host_* functions\n");
        return FALSE;
    }
    return TRUE;
}

static int host_exports_available(void) {
    return InitOnceExecuteOnce(&host_exports_once, load_host_exports, NULL, NULL) != 0;
}

int32_t compose_rust_host_init(const uint8_t *handshake, uint32_t length, MutationBatch *out) {
    return host_exports_available() ? host_exports.init(handshake, length, out) : -1;
}

int32_t compose_rust_host_dispatch_event(
    const uint8_t *event, uint32_t length, MutationBatch *out
) {
    return host_exports_available() ? host_exports.dispatch_event(event, length, out) : -1;
}

int32_t compose_rust_host_render_frame(uint64_t frame_time_nanos, MutationBatch *out) {
    return host_exports_available() ? host_exports.render_frame(frame_time_nanos, out) : -1;
}

void compose_rust_host_release_batch(MutationBatch *batch) {
    if (host_exports_available()) {
        host_exports.release_batch(batch);
    }
}

void compose_rust_host_shutdown(void) {
    if (host_exports_available()) {
        host_exports.shutdown();
    }
}

static int renderer_library_dir(char *out, size_t out_size) {
    HMODULE module;
    wchar_t path[32768];
    DWORD length;
    wchar_t *separator;
    int bytes;

    if (!GetModuleHandleExW(
            GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            (LPCWSTR)(uintptr_t)&compose_rust_renderer_run,
            &module)) {
        return 0;
    }
    length = GetModuleFileNameW(module, path, (DWORD)(sizeof path / sizeof path[0]));
    if (length == 0 || length == sizeof path / sizeof path[0]) {
        return 0;
    }
    separator = wcsrchr(path, L'\\');
    if (separator == NULL) {
        return 0;
    }
    *separator = L'\0';
    bytes = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, path, -1,
                                out, (int)out_size, NULL, NULL);
    return bytes > 0;
}
#endif

#ifndef __APPLE__
/* Creates the isolate on the calling thread and runs the renderer until its window closes. */
static void *renderer_thread(void *arg) {
    struct renderer_run *run = arg;
    graal_isolate_t *isolate;
    graal_isolatethread_t *thread;
    if (graal_create_isolate(NULL, &isolate, &thread) != 0) {
        run->status = RUN_ISOLATE_FAILED;
    } else {
#ifdef _WIN32
        InterlockedExchangePointer((PVOID volatile *)&renderer_isolate, isolate);
#else
        atomic_store(&renderer_isolate, isolate);
#endif
        run->status = compose_rust_renderer_run_impl(thread, run->library_dir);
    }
#ifndef _WIN32
    atomic_store(&run->finished, true);
#endif
    return NULL;
}
#endif

#ifdef _WIN32
/**
 * Writes to the terminal this was started from, where there is one.
 *
 * The executable is linked for the window subsystem, so double clicking it does not open a
 * console beside the window. That is what anyone expects of an application and it costs
 * something: a process with no console has no standard error, and the messages that
 * explain an empty window, a renderer built from another schema among them, would go
 * nowhere.
 *
 * Attaching to the parent's console gives both. Started from a terminal, it writes there
 * as it always did. Started from Explorer there is no parent console, AttachConsole fails,
 * and nothing is opened.
 */
static void compose_rust_attach_parent_console(void) {
    if (!AttachConsole(ATTACH_PARENT_PROCESS)) {
        return;
    }
    // The streams were opened against a console that did not exist, so they are reopened
    // against the one just attached. Failures are ignored: the alternative to writing a
    // diagnostic is not writing one, and refusing to draw over it would be worse.
    FILE *stream = NULL;
    (void)freopen_s(&stream, "CONOUT$", "w", stderr);
    (void)freopen_s(&stream, "CONOUT$", "w", stdout);
}

/**
 * Says this process draws at the display's real resolution.
 *
 * Windows assumes a program does not understand scaling unless it says otherwise. For one
 * that does not, it renders the window at 96 DPI and stretches the result to the size the
 * display asks for, which is why an application on a scaled screen looks soft while
 * everything around it is sharp. There is no such mechanism on macOS, so this never showed
 * up there.
 *
 * Looked up rather than called directly, because the call this wants arrived in Windows 10
 * 1703 and linking it would refuse to start on anything older. The two fallbacks are the
 * same statement in the vocabulary of their own era.
 *
 * Must happen before anything creates a window or a device context, which is why it is the
 * first thing in the entry point rather than part of setting the window up.
 */
static void compose_rust_declare_dpi_awareness(void) {
    // The context type and its values are spelled out here rather than taken from the
    // SDK headers, where they appear only above a certain WINVER. The value is the one
    // the documentation gives for per-monitor v2.
    typedef void *dxc_dpi_context;
    const dxc_dpi_context per_monitor_v2 = (dxc_dpi_context)(intptr_t)-4;

    // Loaded rather than looked up. user32 is almost always in the process already, but
    // this runs before anything has drawn, and a GetModuleHandle that came back empty
    // would silently drop to the older call and leave a per-monitor display on one
    // scale.
    HMODULE user32 = LoadLibraryW(L"user32.dll");
    if (user32 != NULL) {
        typedef BOOL(WINAPI * set_context_fn)(dxc_dpi_context);
        set_context_fn set_context =
            (set_context_fn)(void *)GetProcAddress(user32, "SetProcessDpiAwarenessContext");
        if (set_context != NULL && set_context(per_monitor_v2)) {
            FreeLibrary(user32);
            return;
        }
        FreeLibrary(user32);
    }

    // Windows 8.1 knew about per-monitor scaling but not about the window moving between
    // monitors with different ones.
    HMODULE shcore = LoadLibraryW(L"shcore.dll");
    if (shcore != NULL) {
        typedef HRESULT(WINAPI * set_awareness_fn)(int);
        set_awareness_fn set_awareness =
            (set_awareness_fn)(void *)GetProcAddress(shcore, "SetProcessDpiAwareness");
        if (set_awareness != NULL && set_awareness(2) == S_OK) {
            FreeLibrary(shcore);
            return;
        }
        FreeLibrary(shcore);
    }

    // Everything older: one scale for the whole desktop, which is still sharper than
    // being stretched.
    SetProcessDPIAware();
}

/*
 * Taking the caption strip into the client area while the frame stays whole.
 *
 * The obvious way to draw your own title bar is an undecorated window, and on Windows
 * that is the wrong trade. The frame is not only the bar: it is the drop shadow, the
 * resize border, Snap Layouts and the animation when the window is restored. None of
 * those can be drawn from inside the window, and an application that gives them up looks
 * worse than one that kept the system bar. The first person to run this said the borders
 * looked crude, and they were.
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
 * scripts/tests/windows-caption-metrics.test.sh fails if the two stop agreeing, because
 * two numbers that have to match and live in different languages do drift.
 */

// Windows 11 caption metrics, in device independent pixels.
#define DXC_CAPTION_HEIGHT_DIP 32
#define DXC_CAPTION_BUTTON_WIDTH_DIP 46
#define DXC_CAPTION_BUTTON_COUNT 3

static WNDPROC dxc_inner_window_proc;

static int dxc_scale_for_window(HWND window, int dip) {
    // GetDpiForWindow arrived in Windows 10 1607. Linking it would refuse to start on
    // anything older, and the fallback is the desktop's own scale, which is what a
    // pre-1607 machine has anyway.
    typedef UINT(WINAPI * get_dpi_fn)(HWND);
    UINT dpi = 0;
    HMODULE user32 = LoadLibraryW(L"user32.dll");
    if (user32 != NULL) {
        get_dpi_fn get_dpi = (get_dpi_fn)(void *)GetProcAddress(user32, "GetDpiForWindow");
        if (get_dpi != NULL) {
            dpi = get_dpi(window);
        }
        FreeLibrary(user32);
    }
    if (dpi == 0) {
        HDC screen = GetDC(NULL);
        if (screen != NULL) {
            dpi = (UINT)GetDeviceCaps(screen, LOGPIXELSY);
            ReleaseDC(NULL, screen);
        }
    }
    if (dpi == 0) {
        dpi = 96;
    }
    return (int)MulDiv(dip, (int)dpi, 96);
}

/** How far a maximised window hangs off every edge of its monitor. */
static int dxc_maximised_overhang(HWND window) {
    (void)window;
    return GetSystemMetrics(SM_CYSIZEFRAME) + GetSystemMetrics(SM_CXPADDEDBORDER);
}

static LRESULT CALLBACK dxc_window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
    case WM_NCCALCSIZE: {
        // wparam FALSE asks only for a rectangle, with no frame to compute.
        if (wparam != TRUE) {
            break;
        }
        NCCALCSIZE_PARAMS *params = (NCCALCSIZE_PARAMS *)lparam;
        LONG requested_top = params->rgrc[0].top;
        CallWindowProcW(dxc_inner_window_proc, window, message, wparam, lparam);
        params->rgrc[0].top =
            IsZoomed(window) ? requested_top + dxc_maximised_overhang(window) : requested_top;
        return 0;
    }
    case WM_NCHITTEST: {
        LRESULT where = CallWindowProcW(dxc_inner_window_proc, window, message, wparam, lparam);
        // Everywhere the frame still answers for keeps its answer: the sides, the bottom
        // and all four corners are still the system's.
        if (where != HTCLIENT) {
            return where;
        }
        POINT point = {GET_X_LPARAM(lparam), GET_Y_LPARAM(lparam)};
        RECT frame;
        if (!GetWindowRect(window, &frame)) {
            return where;
        }
        // The top resize band was in the non-client area this window gave up, so nothing
        // else will answer for it.
        int band = dxc_maximised_overhang(window);
        if (!IsZoomed(window) && point.y < frame.top + band) {
            return HTTOP;
        }
        int caption = dxc_scale_for_window(window, DXC_CAPTION_HEIGHT_DIP);
        if (point.y >= frame.top + caption) {
            return HTCLIENT;
        }
        // The buttons are drawn by Kotlin and have to receive ordinary mouse input, so
        // the strip they occupy stays client area. Everything else in the caption drags
        // the window, which also brings back double click to maximise and the system
        // menu on right click.
        int buttons = dxc_scale_for_window(
            window, DXC_CAPTION_BUTTON_WIDTH_DIP * DXC_CAPTION_BUTTON_COUNT);
        if (point.x >= frame.right - buttons) {
            return HTCLIENT;
        }
        return HTCAPTION;
    }
    default:
        break;
    }
    return CallWindowProcW(dxc_inner_window_proc, window, message, wparam, lparam);
}

struct dxc_window_search {
    DWORD process;
    HWND found;
};

static BOOL CALLBACK dxc_find_frame(HWND window, LPARAM lparam) {
    struct dxc_window_search *search = (struct dxc_window_search *)lparam;
    DWORD owner = 0;
    GetWindowThreadProcessId(window, &owner);
    if (owner != search->process || !IsWindowVisible(window)) {
        return TRUE;
    }
    // AWT registers its top level frames under this class name. Matching on the class
    // rather than on being the first visible window of the process avoids catching a
    // splash, a tooltip or anything else that is ours and is not the frame.
    wchar_t name[64];
    if (GetClassNameW(window, name, 64) == 0 || wcscmp(name, L"SunAwtFrame") != 0) {
        return TRUE;
    }
    search->found = window;
    return FALSE;
}

static DWORD WINAPI dxc_reclaim_caption(LPVOID unused) {
    (void)unused;
    // The window is created by AWT, on AWT's thread, some time after the entry point
    // returns control to Kotlin. There is no callback to wait on from here that would not
    // mean a hand written bridge between Kotlin and this file, so this waits for the
    // window to appear instead. Ten seconds is far longer than a window takes and short
    // enough that a run which never opens one does not keep a thread forever.
    for (int attempt = 0; attempt < 1000; attempt++) {
        struct dxc_window_search search = {GetCurrentProcessId(), NULL};
        EnumWindows(dxc_find_frame, (LPARAM)&search);
        if (search.found != NULL) {
            LONG_PTR previous =
                SetWindowLongPtrW(search.found, GWLP_WNDPROC, (LONG_PTR)dxc_window_proc);
            if (previous == 0) {
                return 1;
            }
            dxc_inner_window_proc = (WNDPROC)previous;
            // Nothing recomputes the frame on its own, so the window is asked to.
            SetWindowPos(
                search.found, NULL, 0, 0, 0, 0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
            return 0;
        }
        Sleep(10);
    }
    return 1;
}
#endif


int32_t compose_rust_renderer_run(void) {
#ifdef _WIN32
    compose_rust_attach_parent_console();
    compose_rust_declare_dpi_awareness();
    // Runs alongside the renderer because the window it waits for is created by the
    // renderer. A failure here leaves an ordinary system title bar, which is a worse look
    // and a working window, so it is not worth refusing to start over.
    HANDLE reclaim = CreateThread(NULL, 0, dxc_reclaim_caption, NULL, 0, NULL);
    if (reclaim != NULL) {
        CloseHandle(reclaim);
    }
    static LONG started;
    if (InterlockedExchange(&started, 1)) {
        return RUN_ALREADY_RUNNING;
    }
#else
    static atomic_bool started;
    if (atomic_exchange(&started, true)) {
        return RUN_ALREADY_RUNNING;
    }
#endif
#ifdef __APPLE__
    if (!pthread_main_np()) {
        return RUN_NOT_MAIN_THREAD;
    }
#endif

    static struct renderer_run run;

    /* AWT and Skiko resolve their files relative to this library, not the executable. */
#ifdef _WIN32
    if (!renderer_library_dir(run.library_dir, sizeof run.library_dir)) {
        return RUN_LIBRARY_PATH_UNKNOWN;
    }
#else
    Dl_info info;
    char library_path[PATH_MAX];
    if (!dladdr((const void *)&compose_rust_renderer_run, &info) || info.dli_fname == NULL) {
        return RUN_LIBRARY_PATH_UNKNOWN;
    }
    copy_path(library_path, sizeof library_path, info.dli_fname);
    copy_path(run.library_dir, sizeof run.library_dir, dirname(library_path));
#endif


#ifdef __APPLE__
    compose_rust_prepare_main_thread();
    // The window is AppKit's own and runs on the main thread, which is the thread AppKit
    // answers on and the only one it will make a window from. Everything else follows from
    // that: the frame is drawn there, the scene is composed there, and the Host is started
    // there, so nothing ever waits on another thread to reach the platform. Three separate
    // faults came from a renderer thread waiting on this one, and the last of them left a
    // window that heard nothing because the thread that drains its events was waiting for
    // the thread delivering them.
    graal_isolate_t *isolate;
    graal_isolatethread_t *thread;
    if (graal_create_isolate(NULL, &isolate, &thread) != 0) {
        return RUN_ISOLATE_FAILED;
    }
    atomic_store(&renderer_isolate, isolate);
    return compose_rust_renderer_run_impl(thread, run.library_dir);
#else
    renderer_thread(&run);
#endif
    return run.status;
}

/*
 * Thread-safe. A Host worker thread is attached on its first call and stays attached,
 * so repeated requests do not pay the attach cost. Attaching per call would put that cost
 * on every frame request, which the frame budget does not have room for.
 */
void compose_rust_renderer_request_frame(void) {
#ifdef _WIN32
    graal_isolate_t *isolate = InterlockedCompareExchangePointer(
        (PVOID volatile *)&renderer_isolate, NULL, NULL
    );
#else
    graal_isolate_t *isolate = atomic_load(&renderer_isolate);
#endif
    if (isolate == NULL) {
        return;
    }
    graal_isolatethread_t *thread = graal_get_current_thread(isolate);
    if (thread == NULL && graal_attach_thread(isolate, &thread) != 0) {
        return;
    }
    compose_rust_renderer_request_frame_impl(thread);
}

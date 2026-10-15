/*
 * resize-probe: drags a window's right edge with real mouse input and records every frame the
 * desktop compositor puts on the screen while it does.
 *
 * Runs as its own process, next to the application, so nothing it does runs on the window's
 * thread or changes how the window's sizing loop steps. Two threads:
 *
 *   - capture: Desktop Duplication on the primary monitor. Every frame the compositor hands
 *     out is recorded with its present time (QPC), the compositor's frame counters at the
 *     moment it was taken, and two scan rows through the window. Each pixel of a row is
 *     compared with the same pixel of the desktop as it was before the window existed, so a
 *     pixel is either "desktop" (the window does not cover it in that frame) or "window"
 *     (content, frame border or shadow). A run of desktop pixels with window pixels on both
 *     sides is a hole: the window's rectangle is there and its surface is not.
 *   - drag (main thread): moves the cursor onto the right edge, presses the button, waits until
 *     the window's thread is in its move-size loop, then moves the mouse out and back on a
 *     fixed schedule with absolute SendInput, as a mouse does, and releases.
 *
 * Start it before the application, so the desktop it compares with has no window on it, then
 * start the application with DXC_REAL_DRAG=external. All times are QPC, the same clock the
 * application's DXC_RESIZE_TRACE lines use, so the two logs line up.
 *
 * Exit status: 0 PASS, 1 FAIL (no move-size loop, no step, a hole or black inside the window),
 * 2 could not run (no duplication, no window).
 */
#define _WIN32_WINNT 0x0A00
#define WIN32_LEAN_AND_MEAN
#define UNICODE
#define _UNICODE
#define COBJMACROS
#define _CRT_SECURE_NO_WARNINGS
#include <windows.h>
#include <d3d11.h>
#include <dxgi1_2.h>
#include <dwmapi.h>
#include <timeapi.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "dxguid.lib")
#pragma comment(lib, "dwmapi.lib")
#pragma comment(lib, "winmm.lib")

#define ROWS 2
#define TOLERANCE 12
#define MAX_FRAMES 20000

static FILE *out;
static LARGE_INTEGER frequency;

static long long now_qpc(void) {
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    return now.QuadPart;
}

static double ms_between(long long from, long long to) {
    return (double)(to - from) * 1000.0 / (double)frequency.QuadPart;
}

static void say(const char *format, ...) {
    va_list args;
    va_start(args, format);
    fprintf(out, "resize-probe: ");
    vfprintf(out, format, args);
    fprintf(out, "\n");
    va_end(args);
    fflush(out);
}

/* Desktop Duplication. */
static ID3D11Device *device;
static ID3D11DeviceContext *context;
static IDXGIOutputDuplication *duplication;
static RECT desktop;            /* the duplicated output, in desktop coordinates */
static int desktop_width, desktop_height;
static ID3D11Texture2D *latest;  /* the last frame handed out, kept on the GPU */
static ID3D11Texture2D *full_staging;
static ID3D11Texture2D *row_staging;
static uint32_t *background;     /* the desktop before the window existed */

static int start_duplication(void) {
    IDXGIFactory1 *factory = NULL;
    if (FAILED(CreateDXGIFactory1(&IID_IDXGIFactory1, (void **)&factory))) return 0;
    POINT origin_point = {0, 0};
    HMONITOR primary = MonitorFromPoint(origin_point, MONITOR_DEFAULTTOPRIMARY);
    IDXGIAdapter1 *adapter = NULL;
    int found = 0;
    for (UINT a = 0; !found && IDXGIFactory1_EnumAdapters1(factory, a, &adapter) != DXGI_ERROR_NOT_FOUND; a++) {
        DXGI_ADAPTER_DESC1 adapter_description;
        IDXGIAdapter1_GetDesc1(adapter, &adapter_description);
        IDXGIOutput *output = NULL;
        for (UINT o = 0; !found && IDXGIAdapter1_EnumOutputs(adapter, o, &output) != DXGI_ERROR_NOT_FOUND; o++) {
            DXGI_OUTPUT_DESC output_description;
            IDXGIOutput1 *output1 = NULL;
            if (SUCCEEDED(IDXGIOutput_GetDesc(output, &output_description)) && output_description.Monitor == primary &&
                SUCCEEDED(IDXGIOutput_QueryInterface(output, &IID_IDXGIOutput1, (void **)&output1))) {
                HRESULT made = D3D11CreateDevice((IDXGIAdapter *)adapter, D3D_DRIVER_TYPE_UNKNOWN, NULL, 0, NULL, 0,
                                                 D3D11_SDK_VERSION, &device, NULL, &context);
                HRESULT duplicated = FAILED(made) ? made : IDXGIOutput1_DuplicateOutput(output1, (IUnknown *)device, &duplication);
                if (SUCCEEDED(duplicated)) {
                    desktop = output_description.DesktopCoordinates;
                    desktop_width = desktop.right - desktop.left;
                    desktop_height = desktop.bottom - desktop.top;
                    say("duplicating %ls on %ls (vendor %04x device %04x), %dx%d at %ld,%ld",
                        output_description.DeviceName, adapter_description.Description,
                        adapter_description.VendorId, adapter_description.DeviceId, desktop_width,
                        desktop_height, desktop.left, desktop.top);
                    found = 1;
                } else {
                    say("duplication refused (0x%08lx)", (unsigned long)duplicated);
                }
                IDXGIOutput1_Release(output1);
            }
            IDXGIOutput_Release(output);
        }
        IDXGIAdapter1_Release(adapter);
    }
    IDXGIFactory1_Release(factory);
    return found;
}

static int make_textures(ID3D11Texture2D *frame) {
    D3D11_TEXTURE2D_DESC description;
    ID3D11Texture2D_GetDesc(frame, &description);
    description.MipLevels = 1;
    description.ArraySize = 1;
    description.SampleDesc.Count = 1;
    description.SampleDesc.Quality = 0;
    description.Usage = D3D11_USAGE_DEFAULT;
    description.BindFlags = 0;
    description.CPUAccessFlags = 0;
    description.MiscFlags = 0;
    if (FAILED(ID3D11Device_CreateTexture2D(device, &description, NULL, &latest))) return 0;
    description.Usage = D3D11_USAGE_STAGING;
    description.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    if (FAILED(ID3D11Device_CreateTexture2D(device, &description, NULL, &full_staging))) return 0;
    description.Height = ROWS;
    if (FAILED(ID3D11Device_CreateTexture2D(device, &description, NULL, &row_staging))) return 0;
    return 1;
}

/* What the drag thread tells the capture thread, and what it reads back. */
static volatile LONG stop;
static volatile LONG rows_ready;
static volatile LONG calibrate_request;
static volatile LONG calibrated;
static int row_y[ROWS];
static int scan_left;           /* the window's client left edge, screen x */
static volatile LONG last_right; /* rightmost window pixel in row 0 of the last analysed frame */
static int edge_offset;          /* rightmost window pixel minus client right edge, at rest */

/* Per-run tallies, written by the capture thread. */
static long frames_total, frames_analysed, frames_with_hole, frames_with_black;
static long long drag_from_qpc, drag_to_qpc;
static long frames_in_drag, holes_in_drag, black_in_drag;
static int widths_seen[4096];
static int widths_seen_count;

static void note_width(int width) {
    for (int index = 0; index < widths_seen_count; index++) {
        if (widths_seen[index] == width) return;
    }
    if (widths_seen_count < (int)(sizeof widths_seen / sizeof *widths_seen)) widths_seen[widths_seen_count++] = width;
}

static int is_desktop(uint32_t seen, uint32_t was) {
    for (int shift = 0; shift < 24; shift += 8) {
        int a = (int)((seen >> shift) & 0xFF);
        int b = (int)((was >> shift) & 0xFF);
        if (a - b > TOLERANCE || b - a > TOLERANCE) return 0;
    }
    return 1;
}

static int is_black(uint32_t pixel) {
    return (pixel & 0xFF) < 8 && ((pixel >> 8) & 0xFF) < 8 && ((pixel >> 16) & 0xFF) < 8;
}

/*
 * One row: where the window ends, desktop holes inside it, black inside it, and the classes
 * near the window's right end, run-length encoded, with the raw pixels around that end.
 */
struct row_result {
    int right;
    int holes;       /* desktop pixels in runs of 3 or more with window pixels on both sides */
    int hole_from;   /* first hole pixel, screen x, or -1 */
    int black;
    char runs[512];
    char raw[400];
};

static void analyse_row(const uint32_t *pixels, int y, struct row_result *result) {
    memset(result, 0, sizeof *result);
    result->right = -1;
    result->hole_from = -1;
    int left = scan_left - desktop.left;
    if (left < 0) left = 0;
    int width = desktop_width;
    const uint32_t *was = background + (size_t)(y - desktop.top) * desktop_width;
    for (int x = left; x < width; x++) {
        if (!is_desktop(pixels[x], was[x])) result->right = x;
    }
    if (result->right < 0) return;
    for (int x = left; x <= result->right; x++) {
        if (!is_desktop(pixels[x], was[x]) && is_black(pixels[x]) && !is_black(was[x])) result->black++;
    }
    /*
     * Holes: desktop pixels in the last 48 px of the window with window pixels after them. On
     * a dark desktop the window's right end is the content, then its one pixel border, and the
     * shadow does not show; a strip of desktop before that border is the window's rectangle
     * without its picture. Only the end of the row is judged, because the content further in
     * can hold pixels that match the desktop. The rows are kept off the cursor's height.
     */
    int from_hole = result->right - 48 > left ? result->right - 48 : left;
    for (int x = from_hole; x < result->right; x++) {
        if (is_desktop(pixels[x], was[x])) {
            if (result->hole_from < 0) result->hole_from = x + desktop.left;
            result->holes++;
        }
    }
    /* Classes from 64 px left of the right end to 8 px past it: W window, D desktop, K black. */
    int from = result->right - 64 > left ? result->right - 64 : left;
    int to = result->right + 8 < width ? result->right + 8 : width - 1;
    size_t used = 0;
    char current = 0;
    int run_start = from;
    for (int k = from; k <= to + 1 && used < sizeof result->runs - 32; k++) {
        char cls = 0;
        if (k <= to) cls = is_desktop(pixels[k], was[k]) ? 'D' : (is_black(pixels[k]) ? 'K' : 'W');
        if (cls != current) {
            if (current != 0) {
                used += (size_t)snprintf(result->runs + used, sizeof result->runs - used, "%c%d-%d ", current,
                                         run_start + desktop.left, k - 1 + desktop.left);
            }
            current = cls;
            run_start = k;
        }
    }
    used = 0;
    for (int k = result->right - 4; k <= result->right + 2 && used < sizeof result->raw - 24; k++) {
        if (k < 0 || k >= width) continue;
        used += (size_t)snprintf(result->raw + used, sizeof result->raw - used, "%06x/%06x ",
                                 pixels[k] & 0xFFFFFF, was[k] & 0xFFFFFF);
    }
}

typedef HRESULT(WINAPI *timing_fn)(HWND, DWM_TIMING_INFO *);

static void analyse_latest(long long present, UINT accumulated, int calibrating) {
    D3D11_BOX box;
    for (int row = 0; row < ROWS; row++) {
        box.left = 0;
        box.right = (UINT)desktop_width;
        box.top = (UINT)(row_y[row] - desktop.top);
        box.bottom = box.top + 1;
        box.front = 0;
        box.back = 1;
        ID3D11DeviceContext_CopySubresourceRegion(context, (ID3D11Resource *)row_staging, 0, 0, (UINT)row, 0,
                                                  (ID3D11Resource *)latest, 0, &box);
    }
    D3D11_MAPPED_SUBRESOURCE mapped;
    if (FAILED(ID3D11DeviceContext_Map(context, (ID3D11Resource *)row_staging, 0, D3D11_MAP_READ, 0, &mapped))) return;
    struct row_result results[ROWS];
    for (int row = 0; row < ROWS; row++) {
        analyse_row((const uint32_t *)((const uint8_t *)mapped.pData + (size_t)row * mapped.RowPitch), row_y[row],
                    &results[row]);
    }
    ID3D11DeviceContext_Unmap(context, (ID3D11Resource *)row_staging, 0);
    InterlockedExchange(&last_right, results[0].right);
    if (calibrating) {
        say("calibration right=%d runs=%s raw=%s", results[0].right + desktop.left, results[0].runs, results[0].raw);
        return;
    }
    frames_analysed++;
    int holes = results[0].holes + results[1].holes;
    int black = results[0].black + results[1].black;
    if (holes > 0) frames_with_hole++;
    if (black > 0) frames_with_black++;
    int in_drag = drag_from_qpc != 0 && present >= drag_from_qpc && (drag_to_qpc == 0 || present <= drag_to_qpc);
    /* The window's width as the frame shows it: from the client's left edge to its border. */
    int inferred = results[1].right >= 0 ? results[1].right + desktop.left - scan_left : -1;
    if (in_drag) {
        frames_in_drag++;
        holes_in_drag += holes > 0;
        black_in_drag += black > 0;
        if (inferred > 0) note_width(inferred);
    }
    static timing_fn timing;
    static int looked_up;
    if (!looked_up) {
        looked_up = 1;
        timing = (timing_fn)(void *)GetProcAddress(LoadLibraryW(L"dwmapi.dll"), "DwmGetCompositionTimingInfo");
    }
    DWM_TIMING_INFO info;
    memset(&info, 0, sizeof info);
    info.cbSize = sizeof info;
    if (timing != NULL) timing(NULL, &info);
    fprintf(out,
            "resize-probe: frame n=%ld qpc_present=%lld accumulated=%u qpc_taken=%lld cframe=%llu qpc_compose=%llu "
            "qpc_vblank=%llu crefresh=%llu cframe_displayed=%llu in_drag=%d width=%d right0=%d right1=%d "
            "holes0=%d holes1=%d hole_from0=%d hole_from1=%d black=%d runs0=%s| runs1=%s| raw0=%s| raw1=%s\n",
            frames_analysed, present, accumulated, now_qpc(), (unsigned long long)info.cFrame,
            (unsigned long long)info.qpcCompose, (unsigned long long)info.qpcVBlank,
            (unsigned long long)info.cRefresh, (unsigned long long)info.cFrameDisplayed, in_drag, inferred,
            results[0].right + desktop.left, results[1].right + desktop.left, results[0].holes, results[1].holes,
            results[0].hole_from, results[1].hole_from, black, results[0].runs, results[1].runs, results[0].raw,
            results[1].raw);
}

static DWORD WINAPI capture_thread(LPVOID unused) {
    (void)unused;
    while (!stop) {
        DXGI_OUTDUPL_FRAME_INFO info;
        IDXGIResource *resource = NULL;
        HRESULT acquired = IDXGIOutputDuplication_AcquireNextFrame(duplication, 20, &info, &resource);
        if (acquired == DXGI_ERROR_WAIT_TIMEOUT) {
            if (calibrate_request && rows_ready && latest != NULL) {
                analyse_latest(0, 0, 1);
                InterlockedExchange(&calibrate_request, 0);
            }
            continue;
        }
        if (FAILED(acquired)) {
            say("AcquireNextFrame failed (0x%08lx); stopping the capture", (unsigned long)acquired);
            break;
        }
        frames_total++;
        if (info.LastPresentTime.QuadPart != 0) {
            ID3D11Texture2D *frame = NULL;
            if (SUCCEEDED(IDXGIResource_QueryInterface(resource, &IID_ID3D11Texture2D, (void **)&frame))) {
                if (latest == NULL && !make_textures(frame)) {
                    say("could not make the copy textures");
                    ID3D11Texture2D_Release(frame);
                    IDXGIResource_Release(resource);
                    IDXGIOutputDuplication_ReleaseFrame(duplication);
                    break;
                }
                ID3D11DeviceContext_CopyResource(context, (ID3D11Resource *)latest, (ID3D11Resource *)frame);
                ID3D11Texture2D_Release(frame);
                if (background == NULL) {
                    D3D11_MAPPED_SUBRESOURCE mapped;
                    ID3D11DeviceContext_CopyResource(context, (ID3D11Resource *)full_staging, (ID3D11Resource *)latest);
                    if (SUCCEEDED(ID3D11DeviceContext_Map(context, (ID3D11Resource *)full_staging, 0, D3D11_MAP_READ, 0, &mapped))) {
                        background = (uint32_t *)malloc((size_t)desktop_width * desktop_height * 4);
                        for (int y = 0; y < desktop_height; y++) {
                            memcpy(background + (size_t)y * desktop_width,
                                   (const uint8_t *)mapped.pData + (size_t)y * mapped.RowPitch, (size_t)desktop_width * 4);
                        }
                        ID3D11DeviceContext_Unmap(context, (ID3D11Resource *)full_staging, 0);
                        say("background captured %dx%d at qpc %lld", desktop_width, desktop_height,
                            (long long)info.LastPresentTime.QuadPart);
                    }
                }
                if (rows_ready) {
                    analyse_latest(info.LastPresentTime.QuadPart, info.AccumulatedFrames, 0);
                }
            }
        }
        IDXGIResource_Release(resource);
        IDXGIOutputDuplication_ReleaseFrame(duplication);
    }
    return 0;
}

/* Mouse input. */
static void send_mouse(DWORD flags, int x, int y, long index) {
    INPUT input;
    memset(&input, 0, sizeof input);
    input.type = INPUT_MOUSE;
    int width = GetSystemMetrics(SM_CXVIRTUALSCREEN);
    int height = GetSystemMetrics(SM_CYVIRTUALSCREEN);
    int left = GetSystemMetrics(SM_XVIRTUALSCREEN);
    int top = GetSystemMetrics(SM_YVIRTUALSCREEN);
    input.mi.dx = (LONG)(((long long)(x - left) * 65535 + width / 2) / (width - 1));
    input.mi.dy = (LONG)(((long long)(y - top) * 65535 + height / 2) / (height - 1));
    input.mi.dwFlags = flags | MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
    long long before = now_qpc();
    UINT sent = SendInput(1, &input, sizeof input);
    POINT cursor;
    GetCursorPos(&cursor);
    fprintf(out, "resize-probe: send n=%ld qpc=%lld x=%d y=%d flags=%lu sent=%u cursor=%ld,%ld\n", index, before, x, y,
            (unsigned long)flags, sent, cursor.x, cursor.y);
}

static void wait_until(long long target) {
    for (;;) {
        long long now = now_qpc();
        if (now >= target) return;
        double left_ms = ms_between(now, target);
        if (left_ms > 2.0) Sleep(1);
    }
}

static int in_move_size(DWORD thread) {
    GUITHREADINFO info;
    memset(&info, 0, sizeof info);
    info.cbSize = sizeof info;
    return GetGUIThreadInfo(thread, &info) && (info.flags & GUI_INMOVESIZE) != 0;
}

static const char *argument(int argc, char **argv, const char *name, const char *fallback) {
    for (int index = 1; index + 1 < argc; index++) {
        if (strcmp(argv[index], name) == 0) return argv[index + 1];
    }
    return fallback;
}

int main(int argc, char **argv) {
    const char *path = argument(argc, argv, "--out", NULL);
    out = path != NULL ? fopen(path, "w") : stdout;
    if (out == NULL) out = stdout;
    int interval_ms = atoi(argument(argc, argv, "--interval-ms", "16"));
    int step_px = atoi(argument(argc, argv, "--step-px", "8"));
    int travel = atoi(argument(argc, argv, "--travel", "0"));
    int wait_seconds = atoi(argument(argc, argv, "--wait-seconds", "180"));
    const char *class_name = argument(argc, argv, "--class", "ComposeRustWindow");
    int turn_on_full_drag = 0;
    for (int index = 1; index < argc; index++) {
        if (strcmp(argv[index], "--turn-on-full-window-drag") == 0) turn_on_full_drag = 1;
    }
    QueryPerformanceFrequency(&frequency);
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    timeBeginPeriod(1);
    say("start qpc_frequency=%lld interval_ms=%d step_px=%d", (long long)frequency.QuadPart, interval_ms, step_px);

    if (!start_duplication()) {
        say("verdict ERROR: no desktop duplication on the primary monitor");
        return 2;
    }
    HANDLE capture = CreateThread(NULL, 0, capture_thread, NULL, 0, NULL);
    SetThreadPriority(capture, THREAD_PRIORITY_ABOVE_NORMAL);

    /* The background first: the desktop before the window. */
    for (int waited = 0; background == NULL && waited < 5000; waited += 10) Sleep(10);
    if (background == NULL) {
        say("verdict ERROR: no first frame for the background");
        return 2;
    }
    say("waiting for a %s window", class_name);

    wchar_t wide_class[128];
    MultiByteToWideChar(CP_UTF8, 0, class_name, -1, wide_class, 128);
    HWND window = NULL;
    for (int waited = 0; window == NULL && waited < wait_seconds * 1000; waited += 50) {
        window = FindWindowW(wide_class, NULL);
        if (window == NULL) Sleep(50);
    }
    if (window == NULL) {
        say("verdict ERROR: no window of class %s in %d s", class_name, wait_seconds);
        return 2;
    }
    DWORD thread = GetWindowThreadProcessId(window, NULL);
    /* Ready: the application puts the window at 640x480 for the drag; wait for that size to hold. */
    RECT outer;
    int held = 0;
    for (int waited = 0; waited < wait_seconds * 1000 && held < 1000; waited += 50) {
        GetWindowRect(window, &outer);
        held = outer.right - outer.left == 640 && outer.bottom - outer.top == 480 ? held + 50 : 0;
        Sleep(50);
    }
    GetWindowRect(window, &outer);
    say("window %p thread %lu at %ld,%ld %ldx%ld (%s)", (void *)window, (unsigned long)thread, outer.left, outer.top,
        outer.right - outer.left, outer.bottom - outer.top, held >= 1000 ? "ready" : "size never settled at 640x480");
    RECT client;
    GetClientRect(window, &client);
    POINT origin = {0, 0};
    ClientToScreen(window, &origin);
    scan_left = origin.x;
    /* Above and below the height the cursor is pressed at (the middle), which it would cover. */
    row_y[0] = origin.y + client.bottom / 4;
    row_y[1] = origin.y + client.bottom * 3 / 4;
    MONITORINFO monitor;
    memset(&monitor, 0, sizeof monitor);
    monitor.cbSize = sizeof monitor;
    GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &monitor);
    if (!EqualRect(&monitor.rcMonitor, &desktop)) {
        say("verdict ERROR: the window is not on the primary monitor, which is the one captured");
        return 2;
    }
    if (travel <= 0) travel = (int)(monitor.rcWork.right - outer.right) - 16;
    InterlockedExchange(&rows_ready, 1);
    InterlockedExchange(&calibrate_request, 1);
    for (int waited = 0; calibrate_request && waited < 2000; waited += 10) Sleep(10);
    int right = (int)last_right;
    if (right >= 0) {
        edge_offset = right + desktop.left - (origin.x + client.right);
        InterlockedExchange(&calibrated, 1);
    }
    say("client %ldx%ld at %ld,%ld rows y=%d,%d edge_offset=%d travel=%d", client.right, client.bottom, origin.x,
        origin.y, row_y[0], row_y[1], edge_offset, travel);

    /*
     * "Show window contents while dragging". With it off, the sizing loop draws an outline and
     * resizes the window once, on release: no WM_SIZE during the drag, so nothing about live
     * resize can be measured. Off on some machines (CI runners among them). Turned on only when
     * asked, for this session and not saved, and put back afterwards.
     */
    BOOL full_drag = FALSE;
    SystemParametersInfoW(SPI_GETDRAGFULLWINDOWS, 0, &full_drag, 0);
    int turned_on = 0;
    if (!full_drag) {
        if (!turn_on_full_drag) {
            say("verdict ERROR: \"show window contents while dragging\" is off, so the window would only be "
                "resized on release; turn it on (System Properties > Performance) or pass --turn-on-full-window-drag");
            return 2;
        }
        turned_on = SystemParametersInfoW(SPI_SETDRAGFULLWINDOWS, TRUE, NULL, SPIF_SENDCHANGE) != 0;
        SystemParametersInfoW(SPI_GETDRAGFULLWINDOWS, 0, &full_drag, 0);
        say("full-window drag was off; turned on for this drag: %s", full_drag ? "yes" : "FAILED");
        if (!full_drag) {
            say("verdict ERROR: could not turn on full-window drag");
            return 2;
        }
        Sleep(200);
    } else {
        say("full-window drag is on");
    }

    /* The drag. */
    int x = outer.right - 3;
    int y = origin.y + client.bottom / 2;
    long sends = 0;
    send_mouse(0, x, y, sends++);
    Sleep(200);
    drag_from_qpc = now_qpc();
    send_mouse(MOUSEEVENTF_LEFTDOWN, x, y, sends++);
    long long pressed = now_qpc();
    int looping = 0;
    while (!looping && ms_between(pressed, now_qpc()) < 2000.0) {
        looping = in_move_size(thread);
        if (!looping) Sleep(1);
    }
    long long entered = now_qpc();
    say("move-size loop %s %.1f ms after the press", looping ? "entered" : "NOT entered", ms_between(pressed, entered));
    if (looping) {
        long long period = frequency.QuadPart * interval_ms / 1000;
        long long target = now_qpc();
        for (int moved = step_px; moved <= travel; moved += step_px) {
            target += period;
            wait_until(target);
            send_mouse(0, x + moved, y, sends++);
        }
        for (int moved = travel - step_px; moved >= 0; moved -= step_px) {
            target += period;
            wait_until(target);
            send_mouse(0, x + moved, y, sends++);
        }
        Sleep(100);
    }
    send_mouse(MOUSEEVENTF_LEFTUP, x, y, sends++);
    long long released = now_qpc();
    int left_loop = 0;
    for (int waited = 0; waited < 2000; waited += 5) {
        if (!in_move_size(thread)) { left_loop = 1; break; }
        Sleep(5);
    }
    say("move-size loop %s; drag lasted %.1f ms with %ld sends", left_loop ? "left" : "STILL ON",
        ms_between(entered, released), sends);
    Sleep(300);
    drag_to_qpc = released + frequency.QuadPart / 10;
    Sleep(500);
    InterlockedExchange(&stop, 1);
    WaitForSingleObject(capture, 5000);
    if (turned_on) SystemParametersInfoW(SPI_SETDRAGFULLWINDOWS, FALSE, NULL, SPIF_SENDCHANGE);

    int failed = !looping || widths_seen_count < 2 || holes_in_drag > 0 || black_in_drag > 0;
    say("summary frames_total=%ld frames_analysed=%ld frames_in_drag=%ld distinct_widths_in_drag=%d "
        "frames_with_hole_in_drag=%ld frames_with_black_in_drag=%ld frames_with_hole_all=%ld",
        frames_total, frames_analysed, frames_in_drag, widths_seen_count, holes_in_drag, black_in_drag,
        frames_with_hole);
    say("verdict %s (loop %s, %d widths seen, %ld frames with desktop inside the window, %ld with black)",
        failed ? "FAIL" : "PASS", looping ? "entered" : "not entered", widths_seen_count, holes_in_drag,
        black_in_drag);
    if (out != stdout) fclose(out);
    return failed ? 1 : 0;
}

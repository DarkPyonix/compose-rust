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
 * Besides holes and black, each frame is checked for a stale picture: the left part of the
 * window's content does not move while its right edge is dragged, so every frame must show it
 * where it was at rest. A picture drawn for another width and stretched to this one (or the
 * old picture shown in the new rectangle) moves it, and the frame counts as stale. And each
 * frame's right edge is measured against where the cursor was when the frame was shown: how
 * many pixels and milliseconds the window lags the mouse. A window can have no blank area and
 * still lag; the lag says so.
 *
 * Whether the probe can tell a late frame from a prompt one at all depends on the build of
 * Windows (some hold the window's new size back until its content is ready), so the first line
 * is the Windows version, and resize-control.exe is the window to prove it with: one that
 * presents 50 ms late on purpose, which must fail wherever the probe can see lateness.
 *
 * Exit status: 0 PASS, 1 FAIL (no move-size loop, no step, a hole, black or a stale picture
 * inside the window), 2 could not run (no duplication, no window).
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
#include <psapi.h>
#include <pdh.h>
#include <pdhmsg.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "resize-os-version.h"

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "dxguid.lib")
#pragma comment(lib, "dwmapi.lib")
#pragma comment(lib, "winmm.lib")
#pragma comment(lib, "pdh.lib")

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
static HMONITOR desktop_monitor; /* and the monitor it shows */
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
                    desktop_monitor = output_description.Monitor;
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
static long torn_in_drag, strip_holes_in_drag, strip_black_in_drag, stale_in_drag;

/*
 * Every mouse move the drag sent, when and where, so that each frame's right edge can be set
 * against where the cursor was when the frame was shown. Written by the drag thread, read by
 * the capture thread; send_count is raised after the entry is written.
 */
#define MAX_SENDS 65536
static long long send_qpc[MAX_SENDS];
static int send_x[MAX_SENDS];
static volatile LONG send_count;
static int rest_offset;          /* row 0's right end minus the cursor's x, at rest */
static volatile LONG lag_ready;
static int lag_px_list[MAX_FRAMES];
static double lag_ms_list[MAX_FRAMES];
static int lag_count, lag_ms_count;

/*
 * The strip: every second row of the client area from 24 px below its top to 24 px above
 * its bottom, leaving out 24 px either side of the height the cursor is pressed at. Two rows
 * see a hole only where it crosses them; the strip sees one anywhere down the window's
 * height, and a frame whose rows end at different x (the top drawn at one width, the bottom
 * at another) is a frame torn between two sizes.
 */
static int strip_top, strip_bottom, cursor_y;
#define STRIP_MARGIN 24
#define TORN_PX 2

struct strip_result {
    int rows;
    int right_min, right_max;
    int hole_rows, black_rows;
    int first_hole_y, first_hole_x;
    int edge_rows, stale_rows;
};

/*
 * The stale check. While the right edge is dragged, the left part of the content stays where
 * it is: the zone from the client's left edge across 60% of its width at rest. At rest, every
 * strip row's sharp steps in brightness there (a rise or a fall of EDGE_STEP or more between
 * two neighbouring pixels) are recorded. In a frame, a row whose recorded steps are mostly
 * not found again (same direction, within a pixel) is stale, and a frame with STALE_SHARE of
 * its rows stale shows a picture that is not at the window's size: stretched, or the old one
 * shown in the new rectangle. Rows with fewer than EDGE_MIN steps are not judged. A pixel
 * either way is allowed, because the picture at rest comes from the GPU and the one during
 * the drag may be drawn on the CPU, and their antialiasing differs.
 */
#define EDGE_STEP 40
#define EDGE_MATCH 20
#define EDGE_MIN 6
#define ROW_MATCH_PERCENT 70
#define STALE_SHARE_PERCENT 30
static signed char *reference_edges; /* per strip row (every second row), per zone pixel */
static int reference_row_count;
static int zone_left;                /* screen x */
static int zone_width;
static volatile LONG reference_ready;

static int luminance(uint32_t pixel) {
    return (int)(((pixel >> 16) & 0xFF) * 2 + ((pixel >> 8) & 0xFF) * 5 + (pixel & 0xFF)) / 8;
}

static signed char edge_at(const uint32_t *pixels, int x, int step) {
    int d = luminance(pixels[x + 1]) - luminance(pixels[x]);
    return d >= step ? 1 : d <= -step ? -1 : 0;
}

/* Records the zone's steps on every strip row of the frame in `latest`, at rest. */
static void capture_reference(void) {
    if (strip_bottom <= strip_top || zone_width < 16) return;
    D3D11_BOX box;
    box.left = 0;
    box.right = (UINT)desktop_width;
    box.top = (UINT)(strip_top - desktop.top);
    box.bottom = (UINT)(strip_bottom - desktop.top);
    box.front = 0;
    box.back = 1;
    ID3D11DeviceContext_CopySubresourceRegion(context, (ID3D11Resource *)full_staging, 0, 0, box.top, 0,
                                              (ID3D11Resource *)latest, 0, &box);
    D3D11_MAPPED_SUBRESOURCE mapped;
    if (FAILED(ID3D11DeviceContext_Map(context, (ID3D11Resource *)full_staging, 0, D3D11_MAP_READ, 0, &mapped))) return;
    reference_row_count = (strip_bottom - strip_top + 1) / 2;
    reference_edges = (signed char *)calloc((size_t)reference_row_count * zone_width, 1);
    int left = zone_left - desktop.left;
    long steps = 0;
    int judged = 0;
    for (int index = 0; reference_edges != NULL && index < reference_row_count; index++) {
        int y = strip_top + index * 2;
        const uint32_t *pixels = (const uint32_t *)((const uint8_t *)mapped.pData + (size_t)(y - desktop.top) * mapped.RowPitch);
        signed char *row = reference_edges + (size_t)index * zone_width;
        int count = 0;
        for (int x = 0; x + 1 < zone_width; x++) {
            row[x] = edge_at(pixels, left + x, EDGE_STEP);
            count += row[x] != 0;
        }
        steps += count;
        judged += count >= EDGE_MIN;
    }
    ID3D11DeviceContext_Unmap(context, (ID3D11Resource *)full_staging, 0);
    if (reference_edges != NULL) InterlockedExchange(&reference_ready, 1);
    say("stale reference: zone x=%d..%d, %d rows, %d with at least %d steps, %ld steps", zone_left,
        zone_left + zone_width - 1, reference_row_count, judged, EDGE_MIN, steps);
}

/* 1 when the row at y has lost most of the steps it had at rest; -1 when it is not judged. */
static int row_stale(const uint32_t *pixels, int y) {
    int index = (y - strip_top) / 2;
    if (!reference_ready || index < 0 || index >= reference_row_count) return -1;
    const signed char *row = reference_edges + (size_t)index * zone_width;
    int left = zone_left - desktop.left;
    int expected = 0, found = 0;
    for (int x = 0; x + 1 < zone_width; x++) {
        if (row[x] == 0) continue;
        expected++;
        for (int dx = -1; dx <= 1; dx++) {
            int at = x + dx;
            if (at < 0 || at + 1 >= zone_width) continue;
            if (edge_at(pixels, left + at, EDGE_MATCH) == row[x]) {
                found++;
                break;
            }
        }
    }
    if (expected < EDGE_MIN) return -1;
    return found * 100 < expected * ROW_MATCH_PERCENT;
}
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

/* The backdrop's magenta, or the window's shadow darkening it: red and blue well above green. */
static int is_backdropish(uint32_t pixel) {
    int b = (int)(pixel & 0xFF), g = (int)((pixel >> 8) & 0xFF), r = (int)((pixel >> 16) & 0xFF);
    return r - g > 40 && b - g > 40;
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
    /*
     * Behind the window is the probe's magenta backdrop. The window's right end is its last
     * pixel that is not magenta-ish (its shadow over the backdrop is a darker magenta, so it
     * does not count). Inside that, any magenta-ish pixel is backdrop showing through the
     * window's rectangle: a hole. The rows are kept off the cursor's height.
     */
    for (int x = left; x < width; x++) {
        if (!is_backdropish(pixels[x])) result->right = x;
    }
    if (result->right < 0) return;
    for (int x = left; x <= result->right; x++) {
        if (is_black(pixels[x]) && !is_black(was[x])) result->black++;
        if (is_backdropish(pixels[x])) {
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
        if (k <= to) cls = is_desktop(pixels[k], was[k]) ? 'D' : is_backdropish(pixels[k]) ? 'S' : (is_black(pixels[k]) ? 'K' : 'W');
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

static void analyse_strip(struct strip_result *result) {
    memset(result, 0, sizeof *result);
    result->right_min = 1 << 30;
    result->right_max = -1;
    result->first_hole_y = result->first_hole_x = -1;
    if (strip_bottom <= strip_top) return;
    D3D11_BOX box;
    box.left = 0;
    box.right = (UINT)desktop_width;
    box.top = (UINT)(strip_top - desktop.top);
    box.bottom = (UINT)(strip_bottom - desktop.top);
    box.front = 0;
    box.back = 1;
    ID3D11DeviceContext_CopySubresourceRegion(context, (ID3D11Resource *)full_staging, 0, 0, box.top, 0,
                                              (ID3D11Resource *)latest, 0, &box);
    D3D11_MAPPED_SUBRESOURCE mapped;
    if (FAILED(ID3D11DeviceContext_Map(context, (ID3D11Resource *)full_staging, 0, D3D11_MAP_READ, 0, &mapped))) return;
    int left = scan_left - desktop.left;
    if (left < 0) left = 0;
    for (int y = strip_top; y < strip_bottom; y += 2) {
        if (y > cursor_y - STRIP_MARGIN && y < cursor_y + STRIP_MARGIN) continue;
        const uint32_t *pixels = (const uint32_t *)((const uint8_t *)mapped.pData + (size_t)(y - desktop.top) * mapped.RowPitch);
        const uint32_t *was = background + (size_t)(y - desktop.top) * desktop_width;
        int right = -1;
        for (int x = left; x < desktop_width; x++) {
            if (!is_backdropish(pixels[x])) right = x;
        }
        if (right < 0) continue;
        result->rows++;
        if (right < result->right_min) result->right_min = right;
        if (right > result->right_max) result->right_max = right;
        int hole = 0, black = 0;
        for (int x = left; x <= right; x++) {
            if (is_backdropish(pixels[x])) {
                if (!hole && result->first_hole_y < 0) {
                    result->first_hole_y = y;
                    result->first_hole_x = x + desktop.left;
                }
                hole = 1;
            }
            if (is_black(pixels[x]) && !is_black(was[x])) black = 1;
        }
        result->hole_rows += hole;
        result->black_rows += black;
        int stale = row_stale(pixels, y);
        if (stale >= 0) {
            result->edge_rows++;
            result->stale_rows += stale;
        }
    }
    ID3D11DeviceContext_Unmap(context, (ID3D11Resource *)full_staging, 0);
    if (result->rows == 0) result->right_min = result->right_max = -1 - desktop.left;
    result->right_min += desktop.left;
    result->right_max += desktop.left;
}

typedef HRESULT(WINAPI *timing_fn)(HWND, DWM_TIMING_INFO *);

/*
 * How far the window's right edge (row 0's right end) is behind the cursor in the frame shown
 * at `present`: in pixels, against the last move sent before it, and in milliseconds, back to
 * the most recent move that put the cursor where this frame's edge is (-1 when none in the
 * last two seconds). At rest the edge is rest_offset to the right of the cursor.
 */
static int lag_of(long long present, int observed, double *lag_ms) {
    LONG count = send_count;
    *lag_ms = -1.0;
    int last = -1;
    for (int low = 0, high = (int)count - 1; low <= high;) {
        int middle = (low + high) / 2;
        if (send_qpc[middle] <= present) {
            last = middle;
            low = middle + 1;
        } else {
            high = middle - 1;
        }
    }
    if (last < 0) return 0;
    for (int k = last; k >= 0 && ms_between(send_qpc[k], present) < 2000.0; k--) {
        int expected = send_x[k] + rest_offset;
        if (expected - observed <= 2 && observed - expected <= 2) {
            *lag_ms = ms_between(send_qpc[k], present);
            break;
        }
    }
    return send_x[last] + rest_offset - observed;
}

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
        capture_reference();
        return;
    }
    frames_analysed++;
    struct strip_result strip;
    analyse_strip(&strip);
    int torn = strip.rows > 0 && strip.right_max - strip.right_min > TORN_PX;
    int stale = strip.edge_rows >= 4 && strip.stale_rows * 100 >= strip.edge_rows * STALE_SHARE_PERCENT;
    int lag_px = 0;
    double lag_ms = -1.0;
    if (lag_ready && results[0].right >= 0) lag_px = lag_of(present, results[0].right + desktop.left, &lag_ms);
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
        torn_in_drag += torn;
        strip_holes_in_drag += strip.hole_rows > 0;
        strip_black_in_drag += strip.black_rows > 0;
        stale_in_drag += stale;
        if (inferred > 0) note_width(inferred);
        if (lag_ready && results[0].right >= 0 && lag_count < MAX_FRAMES) {
            /* Behind the cursor going out is positive, going back negative: the distance counts. */
            lag_px_list[lag_count++] = lag_px < 0 ? -lag_px : lag_px;
            if (lag_ms >= 0.0) lag_ms_list[lag_ms_count++] = lag_ms;
        }
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
            "holes0=%d holes1=%d hole_from0=%d hole_from1=%d black=%d strip_rows=%d strip_right=%d-%d torn=%d "
            "strip_hole_rows=%d strip_first_hole=%d,%d strip_black_rows=%d edge_rows=%d stale_rows=%d stale=%d "
            "lag_px=%d lag_ms=%.1f runs0=%s| runs1=%s| raw0=%s| raw1=%s\n",
            frames_analysed, present, accumulated, now_qpc(), (unsigned long long)info.cFrame,
            (unsigned long long)info.qpcCompose, (unsigned long long)info.qpcVBlank,
            (unsigned long long)info.cRefresh, (unsigned long long)info.cFrameDisplayed, in_drag, inferred,
            results[0].right + desktop.left, results[1].right + desktop.left, results[0].holes, results[1].holes,
            results[0].hole_from, results[1].hole_from, black, strip.rows, strip.right_min, strip.right_max, torn,
            strip.hole_rows, strip.first_hole_x, strip.first_hole_y, strip.black_rows, strip.edge_rows, strip.stale_rows,
            stale, lag_px, lag_ms, results[0].runs, results[1].runs,
            results[0].raw,
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

/* The backdrop window. */
static volatile HWND backdrop;

static LRESULT CALLBACK backdrop_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    if (message == WM_MOUSEACTIVATE) return MA_NOACTIVATE;
    return DefWindowProcW(window, message, wparam, lparam);
}

static DWORD WINAPI backdrop_main(LPVOID unused) {
    (void)unused;
    SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    WNDCLASSW description;
    memset(&description, 0, sizeof description);
    description.lpfnWndProc = backdrop_proc;
    description.hInstance = GetModuleHandleW(NULL);
    description.hbrBackground = CreateSolidBrush(RGB(255, 0, 255));
    description.lpszClassName = L"ResizeProbeBackdrop";
    RegisterClassW(&description);
    POINT origin_point = {0, 0};
    MONITORINFO monitor;
    memset(&monitor, 0, sizeof monitor);
    monitor.cbSize = sizeof monitor;
    GetMonitorInfoW(MonitorFromPoint(origin_point, MONITOR_DEFAULTTOPRIMARY), &monitor);
    HWND window = CreateWindowExW(WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW, L"ResizeProbeBackdrop", L"", WS_POPUP,
                                  monitor.rcMonitor.left, monitor.rcMonitor.top,
                                  monitor.rcMonitor.right - monitor.rcMonitor.left,
                                  monitor.rcMonitor.bottom - monitor.rcMonitor.top, NULL, NULL,
                                  GetModuleHandleW(NULL), NULL);
    if (window == NULL) return 1;
    ShowWindow(window, SW_SHOWNOACTIVATE);
    SetWindowPos(window, HWND_BOTTOM, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
    UpdateWindow(window);
    backdrop = window;
    MSG message;
    while (GetMessageW(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
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
    LONG slot = send_count;
    if (slot < MAX_SENDS) {
        send_qpc[slot] = before;
        send_x[slot] = x;
        InterlockedExchange(&send_count, slot + 1);
    }
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

/*
 * The application's memory, read from outside it, so both renderers are measured the same way:
 * its private bytes and working set, and the video memory the GPU counters give the process
 * (GPU Process Memory, local and non-local). Sampled every 50 ms on a thread of its own; the
 * drag's peak is the highest sample while the button is down.
 */
struct memory_sample {
    double private_mb, working_set_mb, gpu_local_mb, gpu_nonlocal_mb;
};
static HANDLE app_process;
static PDH_HQUERY gpu_query;
static PDH_HCOUNTER gpu_local_counter, gpu_nonlocal_counter;
static volatile LONG memory_stop, memory_tracking;
static struct memory_sample memory_peak;
static CRITICAL_SECTION memory_lock;

static double gpu_counter_mb(PDH_HCOUNTER counter) {
    DWORD size = 0, count = 0;
    if (counter == NULL) return -1.0;
    if (PdhGetFormattedCounterArrayW(counter, PDH_FMT_DOUBLE, &size, &count, NULL) != PDH_MORE_DATA) return -1.0;
    PDH_FMT_COUNTERVALUE_ITEM_W *items = (PDH_FMT_COUNTERVALUE_ITEM_W *)malloc(size);
    double total = 0.0;
    if (items != NULL && PdhGetFormattedCounterArrayW(counter, PDH_FMT_DOUBLE, &size, &count, items) == ERROR_SUCCESS) {
        for (DWORD index = 0; index < count; index++) total += items[index].FmtValue.doubleValue;
    } else {
        total = -1.0;
    }
    free(items);
    return total < 0.0 ? total : total / (1024.0 * 1024.0);
}

static struct memory_sample sample_memory(void) {
    struct memory_sample sample = {-1.0, -1.0, -1.0, -1.0};
    PROCESS_MEMORY_COUNTERS_EX counters;
    memset(&counters, 0, sizeof counters);
    counters.cb = sizeof counters;
    if (app_process != NULL && K32GetProcessMemoryInfo(app_process, (PROCESS_MEMORY_COUNTERS *)&counters, sizeof counters)) {
        sample.private_mb = (double)counters.PrivateUsage / (1024.0 * 1024.0);
        sample.working_set_mb = (double)counters.WorkingSetSize / (1024.0 * 1024.0);
    }
    if (gpu_query != NULL && PdhCollectQueryData(gpu_query) == ERROR_SUCCESS) {
        sample.gpu_local_mb = gpu_counter_mb(gpu_local_counter);
        sample.gpu_nonlocal_mb = gpu_counter_mb(gpu_nonlocal_counter);
    }
    return sample;
}

static void say_memory(const char *when, struct memory_sample sample) {
    say("memory %s private_mb=%.1f working_set_mb=%.1f gpu_local_mb=%.1f gpu_nonlocal_mb=%.1f", when,
        sample.private_mb, sample.working_set_mb, sample.gpu_local_mb, sample.gpu_nonlocal_mb);
}

static DWORD WINAPI memory_thread(LPVOID unused) {
    (void)unused;
    while (!memory_stop) {
        struct memory_sample sample = sample_memory();
        if (memory_tracking) {
            EnterCriticalSection(&memory_lock);
            if (sample.private_mb > memory_peak.private_mb) memory_peak.private_mb = sample.private_mb;
            if (sample.working_set_mb > memory_peak.working_set_mb) memory_peak.working_set_mb = sample.working_set_mb;
            if (sample.gpu_local_mb > memory_peak.gpu_local_mb) memory_peak.gpu_local_mb = sample.gpu_local_mb;
            if (sample.gpu_nonlocal_mb > memory_peak.gpu_nonlocal_mb) memory_peak.gpu_nonlocal_mb = sample.gpu_nonlocal_mb;
            LeaveCriticalSection(&memory_lock);
        }
        Sleep(50);
    }
    return 0;
}

static void start_memory(HWND window) {
    DWORD pid = 0;
    GetWindowThreadProcessId(window, &pid);
    app_process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_VM_READ, FALSE, pid);
    if (app_process == NULL) app_process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    wchar_t path[128];
    if (PdhOpenQueryW(NULL, 0, &gpu_query) == ERROR_SUCCESS) {
        swprintf(path, 128, L"\\GPU Process Memory(pid_%lu_*)\\Local Usage", (unsigned long)pid);
        if (PdhAddEnglishCounterW(gpu_query, path, 0, &gpu_local_counter) != ERROR_SUCCESS) gpu_local_counter = NULL;
        swprintf(path, 128, L"\\GPU Process Memory(pid_%lu_*)\\Non Local Usage", (unsigned long)pid);
        if (PdhAddEnglishCounterW(gpu_query, path, 0, &gpu_nonlocal_counter) != ERROR_SUCCESS) gpu_nonlocal_counter = NULL;
        PdhCollectQueryData(gpu_query);
    }
    InitializeCriticalSection(&memory_lock);
    memory_peak.private_mb = memory_peak.working_set_mb = memory_peak.gpu_local_mb = memory_peak.gpu_nonlocal_mb = -1.0;
    say("memory of process %lu (%s), gpu counters %s", (unsigned long)pid, app_process != NULL ? "opened" : "NOT opened",
        gpu_local_counter != NULL ? "found" : "NOT found");
    CreateThread(NULL, 0, memory_thread, NULL, 0, NULL);
}

static int compare_int(const void *a, const void *b) {
    int left = *(const int *)a, right = *(const int *)b;
    return (left > right) - (left < right);
}

static int compare_double(const void *a, const void *b) {
    double left = *(const double *)a, right = *(const double *)b;
    return (left > right) - (left < right);
}

static const char *argument(int argc, char **argv, const char *name, const char *fallback) {
    for (int index = 1; index + 1 < argc; index++) {
        if (strcmp(argv[index], name) == 0) return argv[index + 1];
    }
    return fallback;
}

static const char *awareness_name(DPI_AWARENESS awareness) {
    return awareness == DPI_AWARENESS_PER_MONITOR_AWARE ? "per-monitor"
           : awareness == DPI_AWARENESS_SYSTEM_AWARE    ? "system"
           : awareness == DPI_AWARENESS_UNAWARE         ? "unaware"
                                                        : "unknown";
}

static void say_rect(const char *name, const RECT *rect, int dpi) {
    say("  %s %ld,%ld-%ld,%ld px, %ldx%ld (%d,%d-%d,%d logical at dpi %d)", name, rect->left, rect->top, rect->right,
        rect->bottom, rect->right - rect->left, rect->bottom - rect->top, MulDiv(rect->left, 96, dpi),
        MulDiv(rect->top, 96, dpi), MulDiv(rect->right, 96, dpi), MulDiv(rect->bottom, 96, dpi), dpi);
}

int main(int argc, char **argv) {
    const char *path = argument(argc, argv, "--out", NULL);
    out = path != NULL ? fopen(path, "w") : stdout;
    if (out == NULL) out = stdout;
    int interval_ms = atoi(argument(argc, argv, "--interval-ms", "16"));
    int step_px = atoi(argument(argc, argv, "--step-px", "8"));
    int travel = atoi(argument(argc, argv, "--travel", "0"));
    int wait_seconds = atoi(argument(argc, argv, "--wait-seconds", "180"));
    int place_after_seconds = atoi(argument(argc, argv, "--place-after", "20"));
    const char *class_name = argument(argc, argv, "--class", "ComposeRustWindow");
    int turn_on_full_drag = 0, place = 0;
    for (int index = 1; index < argc; index++) {
        if (strcmp(argv[index], "--turn-on-full-window-drag") == 0) turn_on_full_drag = 1;
        if (strcmp(argv[index], "--place") == 0) place = 1;
    }
    /*
     * Physical pixels everywhere: Desktop Duplication, SendInput's absolute coordinates and the
     * window's rectangles must all be in the same units, and a process that is not per-monitor
     * aware is handed scaled ones on a monitor above 100%. The executable's manifest declares
     * per-monitor (v2) awareness, so this call normally finds it set already; it is kept for a
     * build without the manifest.
     */
    BOOL aware = SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    DWORD aware_error = aware ? 0 : GetLastError();
    QueryPerformanceFrequency(&frequency);
    timeBeginPeriod(1);
    char os_line[512];
    resize_os_version(os_line, sizeof os_line);
    say("os %s", os_line);
    say("start qpc_frequency=%lld interval_ms=%d step_px=%d", (long long)frequency.QuadPart, interval_ms, step_px);
    DPI_AWARENESS awareness = GetAwarenessFromDpiAwarenessContext(GetThreadDpiAwarenessContext());
    say("dpi awareness %s (SetProcessDpiAwarenessContext %s, error %lu), system dpi %u", awareness_name(awareness),
        aware ? "ok" : "refused", (unsigned long)aware_error, GetDpiForSystem());
    if (awareness != DPI_AWARENESS_PER_MONITOR_AWARE) {
        say("verdict ERROR: the probe is not per-monitor DPI aware, so its coordinates would not be physical pixels");
        return 2;
    }

    /*
     * A magenta backdrop over the whole primary monitor, made before the application's window
     * and kept below it, so that anything of the window's rectangle that is not drawn shows as
     * a colour nothing else on the screen has. Its own thread pumps it, so Windows never
     * replaces it with a "not responding" ghost.
     */
    HANDLE backdrop_thread = CreateThread(NULL, 0, backdrop_main, NULL, 0, NULL);
    for (int waited = 0; backdrop == NULL && waited < 5000; waited += 10) Sleep(10);
    if (backdrop == NULL) {
        say("verdict ERROR: could not make the backdrop window");
        return 2;
    }
    Sleep(500);
    say("backdrop %p shown", (void *)backdrop);
    (void)backdrop_thread;

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
    /*
     * Ready: the window sits on the primary monitor, the one captured, at its work area's top
     * left, at least 640x480 pixels, and has held that rectangle for a second.
     *
     * The application (DXC_REAL_DRAG=external) moves its window to the top left of the work
     * area of the monitor it opened on and asks for 640x480 pixels. What it gets can be larger,
     * because the window's minimum size is in logical pixels: at 200% the minimum is twice as
     * many physical pixels, and 640x480 is below it. So the probe takes whatever size the
     * window settles at. When the window opened on another monitor, or the application does not
     * place it at all (--place: the GraalVM renderer has no DXC_REAL_DRAG hook), the probe puts
     * it there itself, once it has held still for a second: on its own after --place-after
     * seconds (default 20), at once with --place. Moving to a monitor of another scale changes
     * the window's size as it arrives, so it is placed up to three times.
     */
    MONITORINFO work;
    memset(&work, 0, sizeof work);
    work.cbSize = sizeof work;
    GetMonitorInfoW(desktop_monitor, &work);
    RECT first, outer, last;
    GetWindowRect(window, &first);
    outer = last = first;
    int moved = 0, held = 0, still = 0, settled = 0, placements = 0, next_placement = 0;
    for (int waited = 0; waited < wait_seconds * 1000 && IsWindow(window); waited += 50) {
        Sleep(50);
        GetWindowRect(window, &outer);
        if (!EqualRect(&outer, &first)) moved = 1;
        int on_primary = MonitorFromWindow(window, MONITOR_DEFAULTTONULL) == desktop_monitor;
        int placed = on_primary && outer.left == work.rcWork.left && outer.top == work.rcWork.top &&
                     outer.right - outer.left >= 640 && outer.bottom - outer.top >= 480;
        int same = EqualRect(&outer, &last);
        still = IsWindowVisible(window) && same ? still + 50 : 0;
        held = placed && same ? held + 50 : 0;
        last = outer;
        /* Opened in place already: then only time tells that nothing is still to come. */
        if (held >= 1000 && (moved || placements > 0 || waited >= 5000)) {
            settled = 1;
            break;
        }
        int ours = place || waited >= place_after_seconds * 1000;
        if (!placed && ours && still >= 1000 && placements < 3 && waited >= next_placement) {
            BOOL done = SetWindowPos(window, NULL, work.rcWork.left, work.rcWork.top, 640, 480,
                                     SWP_NOZORDER | SWP_NOACTIVATE);
            placements++;
            next_placement = waited + 3000;
            still = 0;
            say("placed the window from the probe (attempt %d): at %ld,%ld on the primary monitor, asking for "
                "640x480 px: %s; it was at %ld,%ld %ldx%ld px on %s",
                placements, work.rcWork.left, work.rcWork.top, done ? "ok" : "FAILED", outer.left, outer.top,
                outer.right - outer.left, outer.bottom - outer.top, on_primary ? "the primary monitor" : "another monitor");
        }
    }
    if (!IsWindow(window)) {
        say("verdict ERROR: the window closed before it was ready for the drag");
        return 2;
    }
    UINT window_dpi = GetDpiForWindow(window);
    int scale = window_dpi > 0 ? (int)window_dpi : 96;
    say("window %p thread %lu at %ld,%ld %ldx%ld px, dpi %u (%.2fx), %dx%d logical, window awareness %s (%s)",
        (void *)window, (unsigned long)thread, outer.left, outer.top, outer.right - outer.left,
        outer.bottom - outer.top, window_dpi, scale / 96.0, MulDiv(outer.right - outer.left, 96, scale),
        MulDiv(outer.bottom - outer.top, 96, scale),
        awareness_name(GetAwarenessFromDpiAwarenessContext(GetWindowDpiAwarenessContext(window))),
        !settled ? "NOT SETTLED" : outer.right - outer.left == 640 && outer.bottom - outer.top == 480
                       ? "ready, at the 640x480 asked for"
                       : "ready, larger than the 640x480 asked for: the window's minimum size at this scale");
    RECT client;
    GetClientRect(window, &client);
    POINT origin = {0, 0};
    ClientToScreen(window, &origin);
    RECT client_screen = {origin.x, origin.y, origin.x + client.right, origin.y + client.bottom};
    RECT frame;
    HRESULT framed = DwmGetWindowAttribute(window, DWMWA_EXTENDED_FRAME_BOUNDS, &frame, sizeof frame);
    if (FAILED(framed)) SetRectEmpty(&frame);
    MONITORINFO monitor;
    memset(&monitor, 0, sizeof monitor);
    monitor.cbSize = sizeof monitor;
    HMONITOR window_monitor = MonitorFromWindow(window, MONITOR_DEFAULTTONULL);
    if (window_monitor != NULL) GetMonitorInfoW(window_monitor, &monitor);
    /*
     * Where everything is, in physical pixels and in the window's logical pixels. The outer
     * rectangle includes the invisible resize border at the left, right and bottom, so its
     * left can be off the monitor while everything visible is on it; the
     * visible frame (DWM's extended frame bounds) and the client are what the capture sees.
     */
    say("monitor of the window %p%s, captured monitor %p", (void *)window_monitor,
        window_monitor == NULL ? " (none)" : "", (void *)desktop_monitor);
    say_rect("rcMonitor", &monitor.rcMonitor, scale);
    say_rect("rcWork", &monitor.rcWork, scale);
    say_rect("captured desktop (DesktopCoordinates)", &desktop, scale);
    say_rect("GetWindowRect", &outer, scale);
    if (SUCCEEDED(framed)) {
        say_rect("DWMWA_EXTENDED_FRAME_BOUNDS", &frame, scale);
    } else {
        say("  DWMWA_EXTENDED_FRAME_BOUNDS unavailable (0x%08lx)", (unsigned long)framed);
    }
    say_rect("client (ClientToScreen)", &client_screen, scale);
    if (!settled) {
        say("verdict ERROR: the window never settled at the primary monitor's work area's top left (at least "
            "640x480 px, still for 1 s) in %d s, after %d placements from the probe",
            wait_seconds, placements);
        return 2;
    }
    /*
     * The capture is the monitor the duplication runs on. What has to be on it is the window's
     * monitor (by handle, not by comparing rectangles from two APIs) and the client area, which
     * is all the rows scan.
     */
    RECT overlap;
    int client_captured = IntersectRect(&overlap, &client_screen, &desktop) && EqualRect(&overlap, &client_screen);
    if (window_monitor == NULL || window_monitor != desktop_monitor || !client_captured) {
        say("verdict ERROR: the window is not on the captured monitor (%s)",
            window_monitor == NULL ? "it is on no monitor"
            : window_monitor != desktop_monitor ? "its monitor is another one"
                                                : "its client area reaches outside the captured desktop");
        return 2;
    }
    scan_left = origin.x;
    /* Above and below the height the cursor is pressed at (the middle), which it would cover. */
    row_y[0] = origin.y + client.bottom / 4;
    row_y[1] = origin.y + client.bottom * 3 / 4;
    cursor_y = origin.y + client.bottom / 2;
    strip_top = origin.y + STRIP_MARGIN;
    strip_bottom = origin.y + client.bottom - STRIP_MARGIN;
    if (strip_top < desktop.top) strip_top = desktop.top;
    if (strip_bottom > desktop.bottom) strip_bottom = desktop.bottom;
    zone_left = origin.x + 2;
    zone_width = client.right * 3 / 5;
    if (zone_left + zone_width > desktop.right - 1) zone_width = desktop.right - 1 - zone_left;
    if (travel <= 0) travel = (int)(monitor.rcWork.right - outer.right) - 16;
    /*
     * The backdrop goes directly behind the window, above everything else: the runner's
     * console window covers the desktop (run 38793375645 saw only its 0c0c0c), and a backdrop
     * at the bottom is behind that.
     */
    SetWindowPos(backdrop, window, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
    Sleep(500);
    InterlockedExchange(&rows_ready, 1);
    InterlockedExchange(&calibrate_request, 1);
    for (int waited = 0; calibrate_request && waited < 2000; waited += 10) Sleep(10);
    int right = (int)last_right;
    /* Where the drag presses: on the right edge's resize border, at the client's middle height. */
    int x = outer.right - 3;
    int y = origin.y + client.bottom / 2;
    if (right >= 0) {
        edge_offset = right + desktop.left - (origin.x + client.right);
        rest_offset = right + desktop.left - x;
        InterlockedExchange(&calibrated, 1);
        InterlockedExchange(&lag_ready, 1);
    }
    say("client %ldx%ld at %ld,%ld rows y=%d,%d edge_offset=%d travel=%d rest_offset=%d", client.right, client.bottom,
        origin.x, origin.y, row_y[0], row_y[1], edge_offset, travel, rest_offset);
    if (right < 0 || right + desktop.left >= desktop.right - 2) {
        say("verdict ERROR: the backdrop does not show beside the window (row ends at %d), so holes could not be seen",
            right + desktop.left);
        return 2;
    }

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

    /* The application's memory before the drag: what it holds once it has opened and drawn. */
    start_memory(window);
    Sleep(200);
    say_memory("start", sample_memory());

    /* The drag. */
    long sends = 0;
    send_mouse(0, x, y, sends++);
    Sleep(200);
    drag_from_qpc = now_qpc();
    InterlockedExchange(&memory_tracking, 1);
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
    InterlockedExchange(&memory_tracking, 0);
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
    EnterCriticalSection(&memory_lock);
    struct memory_sample peak = memory_peak;
    LeaveCriticalSection(&memory_lock);
    say_memory("peak during the drag", peak);
    /* Settled: two seconds after the button came up, when the window is back on the GPU. */
    Sleep(1200);
    say_memory("settled", sample_memory());
    InterlockedExchange(&memory_stop, 1);
    if (turned_on) SystemParametersInfoW(SPI_SETDRAGFULLWINDOWS, FALSE, NULL, SPIF_SENDCHANGE);

    int failed = !looping || widths_seen_count < 2 || holes_in_drag > 0 || black_in_drag > 0 || torn_in_drag > 0 ||
                 strip_holes_in_drag > 0 || strip_black_in_drag > 0 || stale_in_drag > 0;
    /*
     * The lag of the window's right edge behind the cursor over the drag's frames. A window
     * whose frame waits for its content shows no hole and still lags; this is where it shows.
     */
    qsort(lag_px_list, (size_t)lag_count, sizeof *lag_px_list, compare_int);
    qsort(lag_ms_list, (size_t)lag_ms_count, sizeof *lag_ms_list, compare_double);
    say("lag frames=%d px_median=%d px_p95=%d px_max=%d ms_frames=%d ms_median=%.1f ms_p95=%.1f ms_max=%.1f",
        lag_count, lag_count ? lag_px_list[lag_count / 2] : -1, lag_count ? lag_px_list[(lag_count * 95) / 100] : -1,
        lag_count ? lag_px_list[lag_count - 1] : -1, lag_ms_count, lag_ms_count ? lag_ms_list[lag_ms_count / 2] : -1.0,
        lag_ms_count ? lag_ms_list[(lag_ms_count * 95) / 100] : -1.0, lag_ms_count ? lag_ms_list[lag_ms_count - 1] : -1.0);
    say("summary frames_total=%ld frames_analysed=%ld frames_in_drag=%ld distinct_widths_in_drag=%d "
        "frames_with_hole_in_drag=%ld frames_with_black_in_drag=%ld frames_with_hole_all=%ld "
        "strip_frames_torn_in_drag=%ld strip_frames_with_hole_in_drag=%ld strip_frames_with_black_in_drag=%ld "
        "frames_stale_in_drag=%ld",
        frames_total, frames_analysed, frames_in_drag, widths_seen_count, holes_in_drag, black_in_drag,
        frames_with_hole, torn_in_drag, strip_holes_in_drag, strip_black_in_drag, stale_in_drag);
    say("verdict %s (loop %s, %d widths seen, %ld frames with desktop inside the window, %ld with black; "
        "full height: %ld torn, %ld with desktop, %ld with black; %ld stale)",
        failed ? "FAIL" : "PASS", looping ? "entered" : "not entered", widths_seen_count, holes_in_drag,
        black_in_drag, torn_in_drag, strip_holes_in_drag, strip_black_in_drag, stale_in_drag);
    if (out != stdout) fclose(out);
    return failed ? 1 : 0;
}

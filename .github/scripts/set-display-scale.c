/*
 * set-display-scale: sets the primary monitor's display scale ("Change the size of text, apps
 * and other items" in Settings > Display), for a CI runner, without signing out.
 *
 *   set-display-scale.exe <percent> [--resolution WxH | --largest]
 *
 * Windows only offers the scales the monitor's resolution allows: at 1024x768 that is 100% and
 * nothing else. So the resolution is raised first when asked to (--largest picks the mode with
 * the most pixels). The scale is then set the way the Settings page sets it, through
 * DisplayConfigSetDeviceInfo with the device-info types Settings uses (-3 reads the range,
 * -4 sets it). Those types are not in the SDK headers; they are how the per-user "DpiValue" in
 * HKCU\Control Panel\Desktop\PerMonitorSettings gets written and applied at once, which the
 * registry value alone does not do until the next sign-in.
 *
 * The scale is given relative to the monitor's recommended one, in steps of this list.
 *
 * Exit status: 0 the scale is in effect (the monitor's effective DPI reads it back), 1 it is
 * not, 2 bad arguments or no display configuration.
 */
#define _WIN32_WINNT 0x0A00
#define WIN32_LEAN_AND_MEAN
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <shellscalingapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "shcore.lib")

static const int scales[] = {100, 125, 150, 175, 200, 225, 250, 300, 350, 400, 450, 500};
#define SCALE_COUNT ((int)(sizeof scales / sizeof *scales))

typedef struct {
    DISPLAYCONFIG_DEVICE_INFO_HEADER header;
    INT32 min_relative;
    INT32 current_relative;
    INT32 max_relative;
} dpi_scale_get;

typedef struct {
    DISPLAYCONFIG_DEVICE_INFO_HEADER header;
    INT32 relative;
} dpi_scale_set;

#define GET_DPI_SCALE ((DISPLAYCONFIG_DEVICE_INFO_TYPE)-3)
#define SET_DPI_SCALE ((DISPLAYCONFIG_DEVICE_INFO_TYPE)-4)

static UINT effective_dpi(void) {
    POINT origin = {0, 0};
    UINT x = 0, y = 0;
    if (FAILED(GetDpiForMonitor(MonitorFromPoint(origin, MONITOR_DEFAULTTOPRIMARY), MDT_EFFECTIVE_DPI, &x, &y))) return 0;
    return x;
}

/* The primary monitor's source in the active display configuration: the one at 0,0. */
static int primary_source(LUID *adapter, UINT32 *source) {
    UINT32 path_count = 0, mode_count = 0;
    if (GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, &path_count, &mode_count) != ERROR_SUCCESS) return 0;
    DISPLAYCONFIG_PATH_INFO *paths = calloc(path_count, sizeof *paths);
    DISPLAYCONFIG_MODE_INFO *modes = calloc(mode_count, sizeof *modes);
    int found = 0;
    if (QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, &path_count, paths, &mode_count, modes, NULL) == ERROR_SUCCESS) {
        for (UINT32 p = 0; p < path_count && !found; p++) {
            UINT32 index = paths[p].sourceInfo.modeInfoIdx;
            int at_origin = path_count == 1;
            if (index < mode_count && modes[index].infoType == DISPLAYCONFIG_MODE_INFO_TYPE_SOURCE) {
                at_origin = modes[index].sourceMode.position.x == 0 && modes[index].sourceMode.position.y == 0;
            }
            if (at_origin) {
                *adapter = paths[p].sourceInfo.adapterId;
                *source = paths[p].sourceInfo.id;
                found = 1;
            }
        }
    }
    free(paths);
    free(modes);
    return found;
}

static int read_range(LUID adapter, UINT32 source, dpi_scale_get *range) {
    memset(range, 0, sizeof *range);
    range->header.type = GET_DPI_SCALE;
    range->header.size = sizeof *range;
    range->header.adapterId = adapter;
    range->header.id = source;
    return DisplayConfigGetDeviceInfo(&range->header) == ERROR_SUCCESS;
}

static int set_resolution(int width, int height, int largest) {
    DEVMODEW current;
    memset(&current, 0, sizeof current);
    current.dmSize = sizeof current;
    EnumDisplaySettingsW(NULL, ENUM_CURRENT_SETTINGS, &current);
    printf("set-display-scale: resolution now %lux%lu\n", current.dmPelsWidth, current.dmPelsHeight);
    DEVMODEW mode, best;
    memset(&best, 0, sizeof best);
    int listed = 0;
    printf("set-display-scale: modes offered:");
    for (DWORD index = 0;; index++) {
        memset(&mode, 0, sizeof mode);
        mode.dmSize = sizeof mode;
        if (!EnumDisplaySettingsW(NULL, index, &mode)) break;
        if (mode.dmBitsPerPel != current.dmBitsPerPel) continue;
        if (listed < 64) printf(" %lux%lu", mode.dmPelsWidth, mode.dmPelsHeight);
        listed++;
        int wanted = largest ? (unsigned long long)mode.dmPelsWidth * mode.dmPelsHeight >
                                   (unsigned long long)best.dmPelsWidth * best.dmPelsHeight
                             : mode.dmPelsWidth == (DWORD)width && mode.dmPelsHeight == (DWORD)height;
        if (wanted) best = mode;
    }
    printf("\n");
    if (best.dmSize == 0) {
        if (largest) {
            printf("set-display-scale: no mode listed\n");
            return 0;
        }
        /* Not listed: some virtual display adapters take any size anyway. */
        best = current;
        best.dmPelsWidth = (DWORD)width;
        best.dmPelsHeight = (DWORD)height;
    }
    best.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT;
    LONG changed = ChangeDisplaySettingsExW(NULL, &best, NULL, CDS_UPDATEREGISTRY, NULL);
    memset(&current, 0, sizeof current);
    current.dmSize = sizeof current;
    EnumDisplaySettingsW(NULL, ENUM_CURRENT_SETTINGS, &current);
    printf("set-display-scale: resolution %lux%lu asked, result %ld, now %lux%lu\n", best.dmPelsWidth,
           best.dmPelsHeight, changed, current.dmPelsWidth, current.dmPelsHeight);
    return changed == DISP_CHANGE_SUCCESSFUL;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: set-display-scale <percent> [--resolution WxH | --largest]\n");
        return 2;
    }
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    int percent = atoi(argv[1]);
    int target = -1;
    for (int index = 0; index < SCALE_COUNT; index++) {
        if (scales[index] == percent) target = index;
    }
    if (target < 0) {
        fprintf(stderr, "set-display-scale: %d%% is not one of the scales Windows offers\n", percent);
        return 2;
    }
    for (int index = 2; index < argc; index++) {
        if (strcmp(argv[index], "--largest") == 0) {
            set_resolution(0, 0, 1);
        } else if (strcmp(argv[index], "--resolution") == 0 && index + 1 < argc) {
            int width = 0, height = 0;
            if (sscanf(argv[++index], "%dx%d", &width, &height) != 2) return 2;
            set_resolution(width, height, 0);
        }
    }
    Sleep(1000);
    LUID adapter;
    UINT32 source = 0;
    if (!primary_source(&adapter, &source)) {
        printf("set-display-scale: no active display path at 0,0\n");
        return 2;
    }
    dpi_scale_get range;
    if (!read_range(adapter, source, &range)) {
        printf("set-display-scale: the display scale range could not be read\n");
        return 2;
    }
    /* The smallest scale offered is always 100%, so the recommended one is -min steps above it. */
    int recommended = -range.min_relative;
    int lowest = recommended + range.min_relative;
    int highest = recommended + range.max_relative;
    int current = recommended + range.current_relative;
    if (highest >= SCALE_COUNT) highest = SCALE_COUNT - 1;
    printf("set-display-scale: offered %d%% to %d%%, recommended %d%%, current %d%%, effective dpi %u\n",
           scales[lowest], scales[highest], scales[recommended], current >= 0 && current < SCALE_COUNT ? scales[current] : -1,
           effective_dpi());
    if (target > highest) {
        printf("set-display-scale: %d%% is above what this resolution offers (%d%%)\n", percent, scales[highest]);
        return 1;
    }
    dpi_scale_set wanted;
    memset(&wanted, 0, sizeof wanted);
    wanted.header.type = SET_DPI_SCALE;
    wanted.header.size = sizeof wanted;
    wanted.header.adapterId = adapter;
    wanted.header.id = source;
    wanted.relative = target - recommended;
    LONG set = DisplayConfigSetDeviceInfo(&wanted.header);
    UINT want_dpi = (UINT)(96 * percent / 100);
    UINT dpi = 0;
    for (int waited = 0; waited < 10000; waited += 100) {
        dpi = effective_dpi();
        if (dpi == want_dpi) break;
        Sleep(100);
    }
    read_range(adapter, source, &range);
    current = recommended + range.current_relative;
    printf("set-display-scale: set %d%% (relative %d), result %ld, now %d%%, effective dpi %u (want %u), system dpi %u\n",
           percent, wanted.relative, set, current >= 0 && current < SCALE_COUNT ? scales[current] : -1, dpi, want_dpi,
           GetDpiForSystem());
    return dpi == want_dpi ? 0 : 1;
}

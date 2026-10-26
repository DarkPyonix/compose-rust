/*
 * resize-control: a window that is late on purpose, to show whether resize-probe can see
 * lateness at all on the machine it runs on.
 *
 * It is built the way the renderer's window draws a resize on the GPU (DXC_RASTER_RESIZE=0):
 * a top-level window with its redirection surface and no background brush, a child window
 * over its client area, and on the child a copy-model swapchain (DXGI_SWAP_EFFECT_SEQUENTIAL,
 * one buffer, DXGI_SCALING_STRETCH). On WM_SIZE it refits the swapchain to the new client
 * size, draws, presents, fits the child and waits for DWM (DwmFlush), all before WM_SIZE
 * returns. The one difference: during a drag it first sleeps DXC_RESIZE_PRESENT_DELAY_MS
 * milliseconds (50 when not set), so every new size's picture is that much late.
 *
 * Where Windows shows the window at its new size before its picture is there, that lateness
 * is on screen (an unpainted strip at the right, the old picture stretched or in the wrong
 * place), and the probe must report FAIL for this window. Where it does not, Windows is
 * holding the window's new size back until the picture is ready, and a PASS from the probe
 * for any window on that machine says nothing about how promptly that window draws: the
 * probe cannot tell late from prompt there.
 *
 * What it draws is made for the probe's stale check: a light grey field with a dark 2 px line
 * every 24 px from the left, and a blue band along the right edge. A picture drawn for one
 * width and shown at another moves the lines.
 *
 * It opens at the primary monitor's work area's top left, 640x480 pixels, as hand-check does
 * with DXC_REAL_DRAG=external, and closes itself after --seconds (default 300) or when closed.
 *
 *   resize-control.exe [--delay-ms N] [--seconds N]
 *
 * --delay-ms overrides DXC_RESIZE_PRESENT_DELAY_MS. DXC_D3D12_WARP=1 draws with WARP, the
 * software adapter, as the renderer does with that variable. Lines go to standard error.
 */
#define _WIN32_WINNT 0x0A00
#define WIN32_LEAN_AND_MEAN
#define UNICODE
#define _UNICODE
#define COBJMACROS
#define _CRT_SECURE_NO_WARNINGS
#include <windows.h>
#include <d3d11_1.h>
#include <dxgi1_2.h>
#include <dwmapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "resize-os-version.h"

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "dxguid.lib")
#pragma comment(lib, "dwmapi.lib")

#define CONTROL_CLASS L"ResizeControlWindow"
#define SURFACE_CLASS L"ResizeControlSurface"
#define RULER_STEP 24
#define RULER_WIDTH 2
#define BAND_WIDTH 8
#define MAX_LINES 512

static HWND window;
static HWND surface;
static ID3D11Device *device;
static ID3D11DeviceContext *context;
static ID3D11DeviceContext1 *context1;
static IDXGISwapChain1 *swapchain;
static int buffer_width, buffer_height;
static int delay_ms = 50;
static int sizing;
static long steps;
static LARGE_INTEGER frequency;

static long long now_qpc(void) {
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    return now.QuadPart;
}

/* Refits the swapchain to width x height when it is another size, draws the ruler and presents. */
static int draw(int width, int height) {
    if (swapchain == NULL || width <= 0 || height <= 0) return 0;
    if (width != buffer_width || height != buffer_height) {
        HRESULT resized = IDXGISwapChain1_ResizeBuffers(swapchain, 0, (UINT)width, (UINT)height, DXGI_FORMAT_UNKNOWN, 0);
        if (FAILED(resized)) {
            fprintf(stderr, "resize-control: ResizeBuffers %dx%d failed (0x%08lx)\n", width, height, (unsigned long)resized);
            return 0;
        }
        buffer_width = width;
        buffer_height = height;
    }
    ID3D11Texture2D *buffer = NULL;
    if (FAILED(IDXGISwapChain1_GetBuffer(swapchain, 0, &IID_ID3D11Texture2D, (void **)&buffer))) return 0;
    ID3D11RenderTargetView *target = NULL;
    HRESULT made = ID3D11Device_CreateRenderTargetView(device, (ID3D11Resource *)buffer, NULL, &target);
    ID3D11Texture2D_Release(buffer);
    if (FAILED(made)) return 0;
    const float field[4] = {0.88f, 0.88f, 0.88f, 1.0f};
    const float line[4] = {0.25f, 0.25f, 0.25f, 1.0f};
    const float band[4] = {0.10f, 0.35f, 0.80f, 1.0f};
    ID3D11DeviceContext_ClearRenderTargetView(context, target, field);
    if (context1 != NULL) {
        D3D11_RECT lines[MAX_LINES];
        UINT count = 0;
        for (int x = RULER_STEP; x + RULER_WIDTH <= width - BAND_WIDTH - 4 && count < MAX_LINES; x += RULER_STEP) {
            lines[count].left = x;
            lines[count].top = 0;
            lines[count].right = x + RULER_WIDTH;
            lines[count].bottom = height;
            count++;
        }
        if (count > 0) ID3D11DeviceContext1_ClearView(context1, (ID3D11View *)target, line, lines, count);
        D3D11_RECT edge;
        edge.left = width > BAND_WIDTH ? width - BAND_WIDTH : 0;
        edge.top = 0;
        edge.right = width;
        edge.bottom = height;
        ID3D11DeviceContext1_ClearView(context1, (ID3D11View *)target, band, &edge, 1);
    }
    ID3D11RenderTargetView_Release(target);
    HRESULT shown = IDXGISwapChain1_Present(swapchain, 0, 0);
    if (FAILED(shown)) {
        fprintf(stderr, "resize-control: Present %dx%d failed (0x%08lx)\n", width, height, (unsigned long)shown);
        return 0;
    }
    return 1;
}

static LRESULT CALLBACK surface_proc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) {
    if (message == WM_PAINT) {
        ValidateRect(hwnd, NULL);
        return 0;
    }
    if (message == WM_ERASEBKGND) return 1;
    if (message == WM_NCHITTEST) return HTTRANSPARENT;
    return DefWindowProcW(hwnd, message, wparam, lparam);
}

static LRESULT CALLBACK control_proc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
    case WM_ENTERSIZEMOVE:
        sizing = 1;
        return 0;
    case WM_EXITSIZEMOVE:
        sizing = 0;
        fprintf(stderr, "resize-control: drag done steps=%ld\n", steps);
        return 0;
    case WM_SIZE:
        if (wparam != SIZE_MINIMIZED && swapchain != NULL) {
            int width = (int)LOWORD(lparam);
            int height = (int)HIWORD(lparam);
            long long asked = now_qpc();
            int late = sizing && delay_ms > 0;
            if (late) Sleep((DWORD)delay_ms);
            int drawn = draw(width, height);
            long long presented = now_qpc();
            if (surface != NULL) {
                SetWindowPos(surface, NULL, 0, 0, width, height, SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOMOVE);
            }
            DwmFlush();
            if (sizing) {
                steps++;
                fprintf(stderr, "resize-control: step n=%ld %dx%d qpc_size=%lld qpc_present=%lld late_ms=%.1f drawn=%d\n",
                        steps, width, height, asked, presented,
                        (double)(presented - asked) * 1000.0 / (double)frequency.QuadPart, drawn);
            }
        }
        return 0;
    case WM_PAINT: {
        PAINTSTRUCT paint;
        BeginPaint(hwnd, &paint);
        EndPaint(hwnd, &paint);
        RECT client;
        GetClientRect(hwnd, &client);
        if (!sizing) draw(client.right, client.bottom);
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

int main(int argc, char **argv) {
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    QueryPerformanceFrequency(&frequency);
    const char *asked = getenv("DXC_RESIZE_PRESENT_DELAY_MS");
    if (asked != NULL && asked[0] != 0) delay_ms = atoi(asked);
    const char *flag = argument(argc, argv, "--delay-ms", NULL);
    if (flag != NULL) delay_ms = atoi(flag);
    int seconds = atoi(argument(argc, argv, "--seconds", "300"));
    const char *warp = getenv("DXC_D3D12_WARP");
    int use_warp = warp != NULL && strcmp(warp, "1") == 0;

    char os_line[512];
    resize_os_version(os_line, sizeof os_line);
    fprintf(stderr, "resize-control: os %s\n", os_line);
    fprintf(stderr, "resize-control: start qpc_frequency=%lld present_delay_ms=%d adapter=%s\n",
            (long long)frequency.QuadPart, delay_ms, use_warp ? "warp" : "hardware");

    WNDCLASSEXW description;
    memset(&description, 0, sizeof description);
    description.cbSize = sizeof description;
    description.style = CS_HREDRAW | CS_VREDRAW;
    description.lpfnWndProc = control_proc;
    description.hInstance = GetModuleHandleW(NULL);
    description.hCursor = LoadCursorW(NULL, IDC_ARROW);
    description.hbrBackground = NULL;
    description.lpszClassName = CONTROL_CLASS;
    if (RegisterClassExW(&description) == 0) return 2;
    description.style = 0;
    description.lpfnWndProc = surface_proc;
    description.lpszClassName = SURFACE_CLASS;
    if (RegisterClassExW(&description) == 0) return 2;

    POINT origin = {0, 0};
    MONITORINFO monitor;
    memset(&monitor, 0, sizeof monitor);
    monitor.cbSize = sizeof monitor;
    GetMonitorInfoW(MonitorFromPoint(origin, MONITOR_DEFAULTTOPRIMARY), &monitor);
    window = CreateWindowExW(0, CONTROL_CLASS, L"resize control", WS_OVERLAPPEDWINDOW, monitor.rcWork.left,
                             monitor.rcWork.top, 640, 480, NULL, NULL, GetModuleHandleW(NULL), NULL);
    if (window == NULL) {
        fprintf(stderr, "resize-control: could not make the window (%lu)\n", (unsigned long)GetLastError());
        return 2;
    }
    RECT client;
    GetClientRect(window, &client);
    surface = CreateWindowExW(0, SURFACE_CLASS, L"", WS_CHILD | WS_VISIBLE, 0, 0, client.right, client.bottom, window,
                              NULL, GetModuleHandleW(NULL), NULL);

    D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
    HRESULT made = D3D11CreateDevice(NULL, use_warp ? D3D_DRIVER_TYPE_WARP : D3D_DRIVER_TYPE_HARDWARE, NULL,
                                     D3D11_CREATE_DEVICE_BGRA_SUPPORT, levels, 2, D3D11_SDK_VERSION, &device, NULL,
                                     &context);
    if (FAILED(made)) {
        fprintf(stderr, "resize-control: no Direct3D 11 device (0x%08lx)\n", (unsigned long)made);
        return 2;
    }
    if (FAILED(ID3D11DeviceContext_QueryInterface(context, &IID_ID3D11DeviceContext1, (void **)&context1))) {
        context1 = NULL;
        fprintf(stderr, "resize-control: no ID3D11DeviceContext1, so no ruler: the stale check has nothing to see\n");
    }
    IDXGIDevice *dxgi_device = NULL;
    IDXGIAdapter *adapter = NULL;
    IDXGIFactory2 *factory = NULL;
    ID3D11Device_QueryInterface(device, &IID_IDXGIDevice, (void **)&dxgi_device);
    if (dxgi_device != NULL) IDXGIDevice_GetAdapter(dxgi_device, &adapter);
    if (adapter != NULL) IDXGIAdapter_GetParent(adapter, &IID_IDXGIFactory2, (void **)&factory);
    if (factory == NULL || surface == NULL) {
        fprintf(stderr, "resize-control: no DXGI factory or child window\n");
        return 2;
    }
    DXGI_ADAPTER_DESC adapter_description;
    memset(&adapter_description, 0, sizeof adapter_description);
    IDXGIAdapter_GetDesc(adapter, &adapter_description);
    DXGI_SWAP_CHAIN_DESC1 swapchain_description;
    memset(&swapchain_description, 0, sizeof swapchain_description);
    swapchain_description.Width = (UINT)client.right;
    swapchain_description.Height = (UINT)client.bottom;
    swapchain_description.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    swapchain_description.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    swapchain_description.BufferCount = 1;
    swapchain_description.SampleDesc.Count = 1;
    swapchain_description.SwapEffect = DXGI_SWAP_EFFECT_SEQUENTIAL;
    swapchain_description.Scaling = DXGI_SCALING_STRETCH;
    swapchain_description.AlphaMode = DXGI_ALPHA_MODE_UNSPECIFIED;
    made = IDXGIFactory2_CreateSwapChainForHwnd(factory, (IUnknown *)device, surface, &swapchain_description, NULL, NULL,
                                                &swapchain);
    if (FAILED(made)) {
        fprintf(stderr, "resize-control: no swapchain (0x%08lx)\n", (unsigned long)made);
        return 2;
    }
    buffer_width = client.right;
    buffer_height = client.bottom;
    fprintf(stderr, "resize-control: swapchain on a child window, copy model (sequential), 1 buffer, STRETCH, %ldx%ld, on %ls\n",
            client.right, client.bottom, adapter_description.Description);

    ShowWindow(window, SW_SHOWNORMAL);
    draw(client.right, client.bottom);
    UpdateWindow(window);
    SetForegroundWindow(window);
    if (seconds > 0) SetTimer(window, 1, (UINT)seconds * 1000u, NULL);
    RECT outer;
    GetWindowRect(window, &outer);
    fprintf(stderr, "resize-control: ready at %ld,%ld %ldx%ld px, dpi %u, frames late by %d ms during a drag\n",
            outer.left, outer.top, outer.right - outer.left, outer.bottom - outer.top, GetDpiForWindow(window), delay_ms);

    MSG message;
    while (GetMessageW(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    fprintf(stderr, "resize-control: closed after %ld drag steps\n", steps);
    return 0;
}

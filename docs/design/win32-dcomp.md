# Win32 window: presenting through DirectComposition

Status: design, no code yet. Tracks the live-resize black areas in `renderer/desktop/c/win32_window.c`.

## Problem

During a live resize the newly exposed part of the window shows black. The window procedure
redraws on every `WM_SIZE` and Skia paints the new size, yet the black remains.

What was tried, and what it showed:

- `DXGI_SCALING_NONE` (e9e95f91) fixed the stretching. The field was zero from `memset`,
  which is `DXGI_SCALING_STRETCH`.
- `WS_EX_NOREDIRECTIONBITMAP` (ce8739fd) did not fix the black. The swap chain is bound
  to the HWND with `CreateSwapChainForHwnd`. Without DirectComposition, that style removes
  the redirection surface and nothing replaces it, so DWM has nothing to show for the
  area that has not been presented yet. The style is incomplete on its own and is expected
  to be reverted on #134, with interim mitigations (`WM_ERASEBKGND`, a background brush,
  `DwmFlush`) that live there, not here.

This document is the proper fix: present through a DirectComposition visual, as Chromium,
Edge and WinUI do.

## Where the swap chain comes from

`win32_window.c` creates the whole Direct3D 12 stack itself: factory, adapter, device,
direct queue and the swap chain (`dxc_native_window_open`). It is not created by skiko.
Skiko's Direct3D context receives only borrowed pointers:

- `DirectContext.makeDirect3D(adapter, device, queue)` once at start;
- per frame, `dxc_native_frame_begin` returns the current back buffer as an
  `ID3D12Resource*` and Kotlin wraps it in `BackendRenderTarget.makeDirect3D(width, height,
  resource, format, 1, 1)`; `dxc_native_frame_end` transitions it to `PRESENT`, calls
  `Present(1, 0)` and waits on a fence.

Skia never sees the swap chain, only `adapter`, `device`, `queue` and back buffers.
Changing how the swap chain is created and attached therefore does not touch the skiko
contract, as long as these stay true:

1. Buffers are `DXC_SWAPCHAIN_FORMAT` (`R8G8B8A8_UNORM`), `DXC_BUFFER_COUNT` of them,
   from `IDXGISwapChain3::GetBuffer`, in `RENDER_TARGET`-capable state when handed out and
   back in `PRESENT` state at `frame_end`.
2. The queue given to Skia is the queue the swap chain was created with.
3. `dxc_native_window_size` reports the swap chain's size, not the client area's.

The struct `dxc_native_window` keeps its fields (`window`, `device`, `queue`, `adapter`,
`swapchain`). No boundary entry point changes, and the Kotlin `@CFunction` and Graal
`@CEntryPoint` surfaces stay as they are.

## Both Windows paths

`win32_window.c` is one file linked by both Windows paths: the GraalVM renderer
(`build-native-windows.ps1`, which passes `d3d12.lib`, `dxgi.lib`, `user32.lib` and the
rest as linker options) and the Kotlin/Native Windows renderer (#119). The change is made
once, in this file, and both paths pick it up. The only per-path work is the link line:

- add `dcomp.lib` to the GraalVM linker options in `build-native-windows.ps1` and to the
  Kotlin/Native Windows link (mingw `-ldcomp`, or the equivalent in the K/N build script).
  `dcomp.dll` ships with Windows 8 and later, which already is the floor for flip-model
  swap chains.
- no new Kotlin or Java code. The draw callback (`dxc_native_set_draw_callback`) already
  exists for the modal sizing loop and is reused.

Anything that must differ between the paths is a defect in the design, not a reason to
fork the file.

## Target design

### Objects

Created in `dxc_native_window_open`, in this order, after the queue:

1. `DCompositionCreateDevice2` is not used. `DCompositionCreateDevice(NULL, IID_IDCompositionDevice, ...)`
   is enough, with a `NULL` DXGI device, because the only content is a swap chain.
2. `IDCompositionDevice::CreateTargetForHwnd(hwnd, topmost = TRUE, &target)`.
3. `IDCompositionDevice::CreateVisual(&visual)`.
4. `IDXGIFactory2::CreateSwapChainForComposition(queue, &desc, NULL, &first)`, then
   `QueryInterface` to `IDXGISwapChain3` as today.
5. `IDCompositionVisual::SetContent(swapchain)`, `IDCompositionTarget::SetRoot(visual)`,
   `IDCompositionDevice::Commit()`.

The factory must be `IDXGIFactory2` or later (`IDXGIFactory4` already is).

### Swap chain description

- `SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD`, `BufferCount = 2`, `SampleDesc.Count = 1`,
  `BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT`, same format as today.
- `Scaling`: `DXGI_SCALING_NONE` is not valid for `CreateSwapChainForComposition`. Creation
  fails with `DXGI_ERROR_INVALID_CALL`. Use `DXGI_SCALING_STRETCH`. This does not bring
  back the old stretch for two reasons. First, a composition swap chain has no window
  rectangle to stretch to: the visual shows the buffer at its own pixel size unless a
  transform or `IDXGISwapChain2::SetSourceSize` says otherwise, and this design sets
  neither. Second, the buffers are refitted and presented synchronously (below), so a
  buffer of the wrong size is never on screen for a frame. To be confirmed on hardware
  (see Verification).
- `AlphaMode = DXGI_ALPHA_MODE_IGNORE`. The window is opaque. `PREMULTIPLIED` is only needed
  for a transparent window and costs composition work, so it is out unless a transparent
  window is asked for.
- `Flags = DXGI_SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT`. After creation,
  `IDXGISwapChain2::SetMaximumFrameLatency(1)` and keep
  `GetFrameLatencyWaitableObject()`. Every later `ResizeBuffers` must pass the same flag
  value, or it fails. See "Frame latency" below for how the handle is used.

### Resize ordering

Today `WM_SIZE` only writes the size down (`dxc_resize_note`) and, inside a modal drag
(`dxc_resize_draw_here`), asks the renderer to draw. The buffer is refitted at the start
of the next frame in `dxc_native_frame_begin`, which already runs `wait_for_gpu`,
`release_buffers`, `ResizeBuffers`, `acquire_buffers`. That order is kept, because the
flip model refuses `ResizeBuffers` while any buffer is held and the fence wait guarantees
nothing is. The change is that the draw is now a complete present, and it happens before
`WM_SIZE` returns:

1. `WM_SIZE` (and `WM_SIZING`/`WM_WINDOWPOSCHANGED` if hardware testing shows that a size
   arrives there first) notes the new client size.
2. It calls `dxc_draw_one_frame()` for every size change, not only when a drag is on.
   The renderer runs `frame_begin` (refit at the new size), paints, `frame_end`.
3. `frame_end` does the existing barrier, `ExecuteCommandLists`, `Present(1, 0)`, and now
   `IDCompositionDevice::Commit()` straight after the present, then the fence wait.
   Present and Commit pair up one to one. A present without a commit, or the reverse,
   leaves the visual showing the previous buffer, which is the black this design removes.
4. `WM_SIZE` returns.

Minimised windows (`SIZE_MINIMIZED`, empty client area) are still skipped, since a swap
chain cannot be zero sized.

`Present(1, 0)` inside the modal loop blocks for the vertical blank on each message, which
caps the resize at the refresh rate. That is the intended pacing. If measurement shows the
drag feels slow, the first thing to try is `Present(0, 0)` during a drag only, not a second
thread.

### Frame latency

With latency 1 the waitable object is signalled when DXGI will accept the next present
without queueing. Two uses, in order of how much they matter:

- Normal frames: wait on it (with a short timeout) at the start of
  `dxc_native_frame_begin`, instead of relying on `Present(1, 0)` blocking. This keeps
  input latency at one frame.
- Inside the modal sizing loop: do not wait indefinitely. A zero or very short timeout,
  and draw anyway on timeout, because skipping a frame there is what shows black.

The existing `dxc_wait_for_gpu()` at the end of `frame_end` stays. It is what makes buffer
release in the next refit safe, and the waitable object does not replace it.

### Window styles

- Keep `WS_EX_NOREDIRECTIONBITMAP` on the window. With a DirectComposition target it is the
  correct pairing: DWM no longer allocates a GDI surface that grows ahead of the content.
  If #134 has reverted it, this change puts it back, together with the visual.
- `WM_ERASEBKGND` keeps returning 1 and the class keeps no background brush.
- No `DwmFlush` is needed: the commit is the synchronisation point.

### Failure and fallback

Every step in "Objects" can fail, for example on a machine with a remote or basic display
driver, in a Windows session without DWM composition, or under a stripped container image.
The order and the fallback are:

1. If any of `DCompositionCreateDevice`, `CreateTargetForHwnd`, `CreateVisual`,
   `CreateSwapChainForComposition` or `QueryInterface` to `IDXGISwapChain3` fails, release
   what was created (visual, target, device, in reverse order), and fall back to the
   existing `CreateSwapChainForHwnd` path with `DXGI_SCALING_NONE` and no frame latency
   flag, as it is on #134.
2. A fallback window must not use `WS_EX_NOREDIRECTIONBITMAP`, because that flag without
   DirectComposition is the bug. The window is created after DirectComposition support is
   known, or recreated, or the style is changed with `SetWindowLongPtr` plus
   `SetWindowPos(SWP_FRAMECHANGED)`. Which of the three is used is settled when the code
   is written. Creating the window first and probing `DCompositionCreateDevice` before
   `CreateWindowExW` is the simplest, since the device does not need an HWND.
3. If `Commit()` fails at run time, `frame_end` logs once and sets a flag so later frames
   keep presenting; a failed commit is not retried every frame.
4. `dxc_native_window_open` keeps its return codes; new codes are appended after the current
   ones. A fallback is not an error and returns 0. The chosen path is written to stderr once
   (`DXC_REPORT_INPUT`-style opt-in is not needed, it is one line at startup).
5. Teardown (`dxc_abandon_window` and the destroy path) releases the visual, the target
   and the device before the swap chain and before `DestroyWindow`, and zeroes the statics.

### COM review points

Written down so the review checks them explicitly:

- Every `QueryInterface` and `Create*` result is checked with `FAILED`, and each failure
  releases exactly the objects made before it (no leak, no double release).
- `IDCompositionVisual::SetContent` takes the swap chain as `IUnknown*`. Pass the
  `IDXGISwapChain1`/`3` pointer, and keep our own reference for `ResizeBuffers`; the visual
  holds its own.
- `ResizeBuffers` passes `DXC_BUFFER_COUNT`, the same format and the same flags value as at
  creation. A mismatch in flags returns `DXGI_ERROR_INVALID_CALL`.
- C calling convention: macros such as `IDXGISwapChain3_ResizeBuffers` and
  `IDCompositionDevice_Commit` are used, as in the existing code. The `dcomp.h` C macro
  set is complete, and `IID_IDCompositionDevice` comes from `dxguid.lib` (already linked).
- No call is made from a thread other than the window's. The DirectComposition device is
  created with the default threading, and everything here runs on the window thread.

## Out of scope for this change

- Transparent or acrylic windows (`PREMULTIPLIED`, backdrop effects).
- Anything about macOS or Linux windows.
- The black-pixel measurement tool and the interim mitigations on #134, which are separate
  work.

## Verification

Automated, in CI:

- the existing `scripts/tests/win32-live-resize.test.sh` and `win32-window-layout.test.sh`
  are extended for the new calls and for the ordering (commit directly after present);
- the Windows GraalVM build and smoke test stay green, which covers the link line;
- the Kotlin/Native Windows build stays green.

By hand, on real Windows hardware (no automated check can see the DWM output):

- the black-pixel ratio tool from #134 run before and after, with the numbers recorded here
  and in the pull request;
- drag every edge and corner, slowly and fast, and look for black or stretched content;
- maximise, restore, snap to half a screen, and drag across monitors of different scale;
- first frame at start is not black; minimise and restore works;
- input and IME still work, since the window procedure is otherwise unchanged;
- start with DirectComposition made to fail (a debug switch is not added; test by
  temporarily returning an error) and confirm the fallback window opens and resizes as it
  did on #134.

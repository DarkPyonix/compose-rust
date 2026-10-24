resize-probe: a real drag of the window's edge, and every frame the screen showed meanwhile

What it does
  resize-probe.exe presses the right edge of the hand-check window with real mouse input,
  waits until Windows has started its sizing loop for the window, drags the edge out to the
  right and back on a fixed schedule, and lets go. While it does, it records every frame the
  desktop compositor (DWM) puts on the primary monitor, through Desktop Duplication. For each
  frame it compares two rows through the window with the desktop as it was before the window
  opened. Before that it covers the primary monitor with a magenta backdrop, below every
  window, so that any part of the window's rectangle without its picture shows as magenta.
  Magenta inside the window (the window's frame is there, its picture is not) or black inside
  the window is a failure.

  Do not touch the mouse while it runs: the probe is moving it.

The quick way: both apps, both paths, all three rates
  Double-click run-probe-v2.cmd (or run run-probe-v2.ps1 in PowerShell) and do not touch the
  mouse until it says "Done". It drags hand-check.exe (the Kotlin/Native renderer) and then
  graalvm\bin\hand-check.exe (the GraalVM native image renderer), each with resize frames on
  the CPU (new) and on the GPU (old, DXC_RASTER_RESIZE=0), at 16, 8 and 1 ms per mouse move,
  and zips the logs into probe-logs.zip. Send that file.

  Then check by hand, in each app (hand-check.exe and graalvm\bin\hand-check.exe), in the
  first text field: type a few letters, press Ctrl+A (all of it is selected), Ctrl+C, click
  at the end and press Ctrl+V (the text appears again). Say for each app whether all three
  worked.

Memory, both apps
  Every probe log has three lines from outside the app, the same way for both renderers:
      memory start                 once the window has opened and drawn, before the drag
      memory peak during the drag  the highest of samples taken every 50 ms while the button is down
      memory settled               about two seconds after the button came up
  each with private_mb (private bytes), working_set_mb and gpu_local_mb / gpu_nonlocal_mb (the
  GPU Process Memory counters for the app's process). The app's own log (DXC_METRICS=1, which
  run-probe-v2 sets) adds "memory-breakdown" lines from the window: private bytes before the
  Direct3D device, with the device and its queue, with the swapchain, once the window opened,
  and at each metrics phase, with the sizes of what the window holds itself (draw texture, the
  CPU frames' bitmap, the swapchain), so the start value can be taken apart.
  Also note, for each app, Task Manager's Memory column once the window is open, while you drag
  its edge by hand, and a few seconds after you let go.

How to run one drag by hand (PowerShell, in the folder that holds hand-check.exe and resize-probe.exe)
  1. Put nothing over the top left of the primary monitor, and keep the window there.
     The probe compares against the desktop as it is when it starts, so start it first.
  2. Start the probe, then hand-check with the drag handed to the probe:

       Start-Process .\resize-probe.exe -ArgumentList '--interval-ms','16','--step-px','8','--out','probe-16.log'
       $env:DXC_METRICS = '1'; $env:DXC_REAL_DRAG = 'external'; $env:DXC_RESIZE_TRACE = '1'
       .\hand-check.exe 2>&1 | Tee-Object hand-check-16.log

     hand-check moves its window to the top left of the work area, asks for 640x480 pixels
     and prints "real drag ready". Above 100% the window's minimum size can be larger than
     that (at 200% the minimum is twice as many pixels), so the probe takes whatever size the
     window settles at, once it has held still for a second. The probe then drags. When hand-check prints "real drag done", close hand-check.
  3. Again at the other two mouse rates, each with its own pair of logs:
       --interval-ms 8 --step-px 4   (a 125 Hz mouse)       probe-8.log,  hand-check-8.log
       --interval-ms 1 --step-px 1   (a 1000 Hz mouse)      probe-1.log,  hand-check-1.log
  4. Send all six logs.

  "Show window contents while dragging" (System Properties > Advanced > Performance) must be
  on: with it off, Windows draws an outline during the drag and resizes the window only on
  release, and the probe stops with an error. --turn-on-full-window-drag turns it on for the
  session and back off afterwards (CI uses this).

  Options: --interval-ms N (time between mouse moves), --step-px N (pixels per move),
  --travel N (how far out, default to the right edge of the work area less 16 px),
  --out FILE (default: the console), --class NAME (window class, default ComposeRustWindow),
  --wait-seconds N (how long to wait for the window, default 180), --place (put the window at
  the work area's top left at 640x480 from the probe, for an application that does not do it
  itself: the GraalVM renderer, which has no DXC_REAL_DRAG hook).

What the output means (probe log)
  duplicating ...           the monitor and graphics adapter being recorded
  background captured ...   the desktop it compares against
  window ... (ready)        the window settled at the top left and the drag can start: its
                            size in physical and logical pixels and its dpi
  rcMonitor, rcWork, ...    where the monitor, the work area, the captured desktop, the
                            window's rectangle, its visible frame and its client area are,
                            in physical pixels and in the window's logical pixels. The window
                            must be on the captured monitor and its client area inside it.
  client ... edge_offset    where the rows are, and how far past the picture's right edge the
                            window's frame and shadow reach
  send n=... qpc=...        every mouse event, when it was sent (QPC) and where the cursor was
  move-size loop entered    Windows started its sizing loop; "NOT entered" is a failure of the
                            drag itself, not of the window
  frame n=...               one frame the compositor showed:
      qpc_present           when it was shown (QPC, the clock hand-check's trace uses)
      cframe, qpc_compose   DWM's frame counter and composition time when it was read
      width                 the window's client width the frame shows
      holes0/holes1         desktop pixels inside the window on each row (must be 0)
      black                 black pixels inside the window (must be 0)
      strip_rows            rows scanned down the whole client height (every second row,
                            24 px clear of its top, its bottom and the cursor's height)
      strip_right           the leftmost and rightmost x those rows end at; torn=1 when they
                            differ by more than 2 px, a frame showing two sizes at once
      strip_hole_rows       rows with backdrop inside the window, and the first such pixel
      strip_black_rows      rows with black inside the window
      runs0, runs1          each row near the window's right end: W window, D backdrop,
                            S shadow over the backdrop, K black
  summary / verdict         PASS when the loop was entered, the window changed size, and no
                            frame during the drag had desktop or black inside the window,
                            on the two rows or anywhere down the strip, or was torn
  Exit code: 0 PASS, 1 FAIL, 2 could not run.

hand-check log (with DXC_RESIZE_TRACE=1)
  resize-trace qpc=... kind=...   each message of the size change and each frame copy, with
                                  DWM's frame counter at that moment
  real drag done steps=N          how many sizes the window drew during the drag (0 fails)

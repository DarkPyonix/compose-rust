resize-probe: a real drag of the window's edge, and every frame the screen showed meanwhile

What it does
  resize-probe.exe presses the right edge of the hand-check window with real mouse input,
  waits until Windows has started its sizing loop for the window, drags the edge out to the
  right and back on a fixed schedule, and lets go. While it does, it records every frame the
  desktop compositor (DWM) puts on the primary monitor, through Desktop Duplication. For each
  frame it compares two rows through the window with the desktop as it was before the window
  opened. A stretch of desktop inside the window (the window's frame is there, its picture is
  not) or black inside the window is a failure.

  Do not touch the mouse while it runs: the probe is moving it.

How to run (PowerShell, in the folder that holds hand-check.exe and resize-probe.exe)
  1. Put nothing over the top left of the primary monitor, and keep the window there.
     The probe compares against the desktop as it is when it starts, so start it first.
  2. Start the probe, then hand-check with the drag handed to the probe:

       Start-Process .\resize-probe.exe -ArgumentList '--interval-ms','16','--step-px','8','--out','probe-16.log'
       $env:DXC_METRICS = '1'; $env:DXC_REAL_DRAG = 'external'; $env:DXC_RESIZE_TRACE = '1'
       .\hand-check.exe 2>&1 | Tee-Object hand-check-16.log

     hand-check moves its window to the top left at 640x480 and prints "real drag ready". The
     probe then drags. When hand-check prints "real drag done", close hand-check.
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
  --wait-seconds N (how long to wait for the window, default 180).

What the output means (probe log)
  duplicating ...           the monitor and graphics adapter being recorded
  background captured ...   the desktop it compares against
  window ... (ready)        the window is at 640x480 and the drag can start
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
      runs0                 the row near the window's right end: W window, D desktop, K black
  summary / verdict         PASS when the loop was entered, the window changed size, and no
                            frame during the drag had desktop or black inside the window
  Exit code: 0 PASS, 1 FAIL, 2 could not run.

hand-check log (with DXC_RESIZE_TRACE=1)
  resize-trace qpc=... kind=...   each message of the size change and each frame copy, with
                                  DWM's frame counter at that moment
  real drag done steps=N          how many sizes the window drew during the drag (0 fails)

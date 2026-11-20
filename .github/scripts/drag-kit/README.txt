compose-rust drag kit, Windows x64

Built from compose-rust commit @COMMIT@ (run @RUN@).
The window code is thisisthepy/compose-multiplatform-core-extended at @FORK_REVISION@,
the WINDOW_REVISION renderer/scripts/build-compose.sh pins at that commit.

What it is for: dragging a real application's window edge on a real GPU, on each of the
window's three routes, and comparing them by eye. The other .txt beside this one says how,
in Korean, for the person running it.

  store\sample-store.exe   the shop sample: pictures that scale with their tiles, the two-up
                           grid and the category strip, the bar of buttons along the bottom
  chat\sample-chat.exe     the chat sample: the empty conversation's paragraph and the replies
                           rewrapping, the message list, the composer row, the sidebar

Each folder holds the same three launchers. Each one sets or clears the route variables,
sets DXC_REPORT_LATENCY=1, starts the sample beside it, and writes its standard output and
error to <sample>-<route>-<time>.log beside it:

  1-gdi.cmd                DXC_DCOMP=0: the GDI route
  2-dcomp-cpu-drag.cmd     neither variable: DirectComposition, drag steps drawn on the CPU
                           (the default)
  3-dcomp-gpu-drag.cmd     DXC_DCOMP_GPU_DRAG=1: DirectComposition, drag steps rendered on the
                           GPU (experimental, unmerged fork pull requests #255 and #256)

A log starts with the route's variables. The window then says which route it took
("compose-rust: dcomp: route ..."), and with DXC_REPORT_LATENCY each drag step prints a
"resize step", "resize-mode: drag step" or "dcomp: gpu drag step" line and each drag ends with
"dcomp: drag timing", "dcomp: drag upload parts" or "dcomp: gpu drag timing".

CI started every launcher once on a runner with no graphics card (DXC_D3D12_WARP=1) and saw
the route it asks for and a first frame drawn. Nothing about how a drag looks was checked
there.

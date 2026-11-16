# hand-check

A window to check a renderer by hand on a real desktop. It has a click counter, two text
fields, a line showing the window's size class, and a scrolling list with Korean rows. What
the fields hold is echoed under each, and every change is printed to standard output as an
`event:` line.

Set `COMPOSE_RUST_RENDERER_DIR` to the renderer you want to try, then run it from this
directory. The commands are the same on every desktop apart from how the variable is set.

```sh
# macOS and Linux
COMPOSE_RUST_RENDERER_DIR=/path/to/renderer/dist cargo run --release
```

```powershell
# Windows (PowerShell). The renderer's bin folder has to be on PATH as well.
$env:COMPOSE_RUST_RENDERER_DIR = "C:\path\to\renderer\dist"
$env:PATH = "C:\path\to\renderer\dist\bin;$env:PATH"
cargo run --release
```

On Linux the X11 window is the default; nothing needs to be set.

## What to check

| Check | How | What you should see |
| --- | --- | --- |
| Text shortcuts | In the first field select with ctrl or cmd+A, then C, X, V and Z | The echo line under the field shows the paste, the cut and the undo; `event: first ...` lines print |
| Clipboard across apps | Copy in another app, paste here, and the other way round | The text, Korean included, arrives |
| Live resize | Drag an edge and a corner, at 100% and at 200% scale | Content follows the edge with no lag or flash; the `window:` line shows the size class and size when it changes |
| Input method | Type Korean (`han-geul`) in a field | Underlined text while composing, and the candidate window beside the caret, not at the corner |
| Focus | Click each field, press Tab | Only one field has the caret, and typing goes to it |
| Click | Press the button | The counter counts and `event: click N` prints |
| Scrolling | Scroll the list with the wheel and the scrollbar | 60 rows move smoothly |

## The marker scene

With `DXC_RESIZE_MARKER=1` the window shows a scene made for the Windows resize probe
(`.github/scripts/resize-probe.c`) instead of the controls above: a light field with a 2 dp
green column down its left and right edges, a 2 dp green row along its bottom edge, and a
dark 2 dp ruler line every 24 dp between the two columns. The probe reads it from a screen
capture. A marker that does not touch the window's border shows an area the application did
not draw, and a marker of the wrong width or a ruler line off its grid shows a stretched
picture. `run-probe-v2.ps1` sets the variable for every drag.

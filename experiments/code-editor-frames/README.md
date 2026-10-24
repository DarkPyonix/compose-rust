# Code editor frame times at a hundred thousand lines

The code editor has to open a 100,000 line file and keep scrolling and typing inside the
frame budget. Compose's `BasicTextField` over a `TextFieldState` lays out its whole text
at once, so there are three ways to build the editor, and this measures them against each
other and against plain Compose.

| Run | What it is |
| --- | --- |
| `windowed` | One `BasicTextField(TextFieldState)` holding only the lines around the screen, refilled as the view scrolls. The default `CodeEditorPath`. |
| `whole` | One `BasicTextField(TextFieldState)` holding all 100,000 lines. The baseline the windowed path exists to beat. |
| `drawn` | Lines drawn one by one with a `TextMeasurer`, input taken from the desktop's text input session directly. |
| `baseline` | Plain Compose: a `LazyColumn` of every line beside a small `BasicTextField`, for what the toolkit does on its own. |

Every path takes input through Compose's platform text input. The editor sources are the
renderer's own, linked from `renderer/desktop/src/`, so what is measured is
what ships; only `src/FrameBench.kt` belongs to this directory. `Protocol.gen.kt` is linked
too, so run the codegen binary first if the schema has moved.

## Running it

Not part of any build. From this directory, on a machine with a display:

```sh
../../renderer/kotlin run
# or one path at a time, with a different size or length:
../../renderer/kotlin run -- windowed --lines=100000 --frames=600
```

Each run opens a window, lets it settle for 60 frames, then posts real AWT events, one
per frame: 600 frames of wheel scrolling down, 300 back up, then 600 typed characters
with a line break every 40. Vertical sync is turned off, so the interval between frames is
the time a frame took. Keep the window in front and the machine otherwise idle.

The numbers are printed and written to `results/frames-<time>.json`: for each run the time
to the first frame, and for each phase the median, 95th and 99th percentile, worst and mean
frame, and how many frames went over 16.7 ms.

## Reading it

The budget is a frame under 16.7 ms at the 99th percentile while scrolling and typing,
and no more than 10 percent over `baseline` at the median. Record the result in the SPEC
item with the machine it was taken on; the choice of path follows from it, and switching is
one line: `defaultCodeEditorPath` in `CodeEditorSurface.kt`.

## Results

Not yet run. The harness was written in a worktree whose rules forbid running the renderer
build, so the first numbers come from whoever merges it.

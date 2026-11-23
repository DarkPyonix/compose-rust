# blank-window

One window of 800 by 600 dp with one empty box filling it, and nothing else. It is the
application the memory report measures: with no content, what the process holds is what the
renderer, its graphics surface and the window cost, the floor every application on the same
renderer starts from.

It takes no input and changes nothing after its first frame. It prints one line,
`blank-window: window <width>x<height> dp`, when the renderer first reports the window's size.

## Building it

Point the build at a renderer, then build in this directory.

```sh
# Linux, against a static renderer built by renderer/desktop/scripts/build-linux.sh
DXC_LINUX_NATIVE_LIB=/path/to/renderer/build/linux cargo build --release
```

```powershell
# Windows, against the renderer kit built by renderer/desktop/scripts/build-windows.sh
$env:DXC_WINDOWS_NATIVE_LIB = "C:\path\to\renderer\build\windows"
cargo build --release
```

A Windows machine with no graphics card, such as a virtual machine or a CI runner, draws with
the software adapter: set `DXC_D3D12_WARP=1` before starting it.

## The memory report

`ci.yml` dispatched with the `memory_report` suite builds this fixture against the renderer of
the same commit on Linux and Windows, starts it three times on each, reads its memory 10 and
60 seconds after each start, and closes it by its process id. The job summary has the median
of the three for each reading; the `memory-report-<os>` artifact has every sample.

| System | Metric | Read from | For reference |
| --- | --- | --- | --- |
| Linux (ubuntu-latest, under Xvfb) | PSS | `/proc/<pid>/smaps_rollup` | RSS from `/proc/<pid>/status` |
| Windows (windows-latest) | private working set | the `\Process(<name>)\Working Set - Private` counter, or `PrivateMemorySize64` when the counter cannot be read (the summary says which) | working set |

The report fails nothing on a number. It fails when the window does not come up or no reading
could be made. The scripts are `.github/scripts/blank-window-memory.sh` (Linux),
`.github/scripts/blank-window-memory.ps1` (Windows) and
`.github/scripts/blank-window-memory-summary.py`, which writes the table.

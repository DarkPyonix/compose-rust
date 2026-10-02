<#
.SYNOPSIS
Measures what a dioxus-compose application costs in memory on Windows.

.DESCRIPTION
The companion to measure-memory.sh, which does the same on macOS. The two do not print the
same metric, because the two systems do not have the same one, and pretending otherwise
would invite a comparison that means nothing.

macOS is measured by physical footprint. The nearest thing here is the PRIVATE WORKING SET:
the pages this process has in RAM that no other process shares. It is what Task Manager's
"Memory" column shows, so a number from this script is a number you can see in Task Manager.
Working set and commit are printed beside it because they answer different questions, and
because a gap between them is itself a finding.

The window's size is reported with every sample. The window surface scales with its area,
so a measurement that does not say how big the window was cannot be compared with anything.
On macOS this caught a run where the window never reached the size it was asked for and the
number was 40MB out.

.PARAMETER Exe
The application to measure. A sample from a release works, and so does
target\release\examples\memory_probe.exe, which takes DXC_PROBE_WIDTH, DXC_PROBE_HEIGHT and
DXC_PROBE_ROWS and is the one that isolates what the window costs from what the content
costs.

.PARAMETER Width
.PARAMETER Height
Passed to the probe. Ignored by an application that sets its own window size.

.PARAMETER Rows
How many rows of six buttons the probe draws. 0 is an empty window.

.PARAMETER Settle
Seconds to wait before the first sample. The renderer finishes starting and the first frame
lands well before this; shorter measurements have come out low.

.PARAMETER Samples
How many samples to take. The median is reported, because one sample lands anywhere.

.EXAMPLE
.\measure-memory.ps1 -Exe .\target\release\examples\memory_probe.exe -Width 800 -Height 600 -Rows 0

.EXAMPLE
.\measure-memory.ps1 -Exe .\sample-todo.exe
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Exe,
    [int]$Width = 800,
    [int]$Height = 600,
    [int]$Rows = 0,
    [int]$Settle = 15,
    [int]$Samples = 5,
    [string]$RendererDir
)

$ErrorActionPreference = 'Stop'

# GetWindowRect, so the report says how big the window actually was rather than how big it
# was asked to be. A measurement that cannot say that is not comparable with anything, and
# on macOS a run once came out 40MB low because the window never reached the size it was
# given.
#
# Declared once per session: the type stays loaded, and Add-Type refuses to define it twice.
if (-not ('DxcWin32' -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;
public class DxcWin32 {
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
}
'@
}

# The repository root, found by walking up from this script until the workspace manifest
# turns up. Cargo puts its output there rather than next to whatever directory you happened
# to type the command in, which is the first thing that goes wrong when running this.
$repoRoot = $PSScriptRoot
while ($repoRoot -and -not (Test-Path (Join-Path $repoRoot 'Cargo.toml'))) {
    $repoRoot = Split-Path $repoRoot -Parent
}

$resolved = $null
foreach ($candidate in @($Exe, (Join-Path $repoRoot $Exe))) {
    if ($candidate -and (Test-Path $candidate)) { $resolved = (Resolve-Path $candidate).Path; break }
}
if (-not $resolved) {
    Write-Host "no executable at $Exe"
    if ($repoRoot) {
        Write-Host ""
        Write-Host "cargo builds into the workspace root, not the directory you ran it from."
        Write-Host "The probe is at:"
        Write-Host "  $repoRoot\target\release\examples\memory_probe.exe"
        Write-Host "and a sample at:"
        Write-Host "  $repoRoot\target\release\sample-todo.exe"
    }
    exit 1
}
$Exe = $resolved

# The renderer is a DLL found on the loader's search path and nowhere else, so an
# application started without it on PATH prints that it cannot find it and exits. Guessing
# the path was not good enough: the first attempt checked three likely places, found none
# of them, and the run died anyway. So this looks for the file itself.
# The native image is named with a `lib` prefix on every platform, because that is what
# the import library beside it carries and what the crate links against. Both spellings
# are accepted so a rename does not turn this into a silent no-match.
$rendererDlls = @('libdioxus_compose_renderer.dll', 'dioxus_compose_renderer.dll')
$searched = @()
$rendererDir = $null

foreach ($candidate in @(
    $RendererDir,
    $env:DIOXUS_COMPOSE_RENDERER_DIR,
    # The build script copies the renderer next to the executables, so the first place to
    # look is where the executable actually is.
    (Split-Path $Exe -Parent),
    (Join-Path $repoRoot 'dioxus-compose-renderer\build\native-image\dist\bin'),
    (Join-Path $repoRoot 'dioxus-compose-renderer\build\native-image\dist\lib')
)) {
    if (-not $candidate) { continue }
    $searched += $candidate
    foreach ($name in $rendererDlls) {
        if (Test-Path (Join-Path $candidate $name)) { $rendererDir = $candidate; break }
    }
    if ($rendererDir) { break }
}

# The cache the build script unpacks into. Its version and target are in the path, and
# hardcoding either goes stale, so the DLL is searched for instead.
if (-not $rendererDir) {
    $cacheRoot = Join-Path $env:LOCALAPPDATA 'dioxus-compose'
    $searched += "$cacheRoot (searched)"
    if (Test-Path $cacheRoot) {
        $found = Get-ChildItem -Path $cacheRoot -Include $rendererDlls -Recurse -File -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($found) { $rendererDir = $found.DirectoryName }
    }
}

if ($rendererDir) {
    $env:PATH = "$rendererDir;$env:PATH"
    Write-Host "renderer       $rendererDir"

    # Windows looks in the executable's own directory before anything on PATH, and the
    # crate's build script copies the renderer it linked against to exactly there. So a
    # renderer named here loses to that copy, silently, and the measurement is of whatever
    # was linked rather than of what was asked for.
    #
    # The renderer is chosen at build time, not at run time. Saying so is the only useful
    # thing this can do about it.
    $besideExe = Split-Path $Exe -Parent
    if ($rendererDir -ne $besideExe) {
        $shadowing = $rendererDlls | Where-Object { Test-Path -LiteralPath (Join-Path $besideExe $_) }
        if ($shadowing) {
            Write-Host ''
            Write-Host "a renderer is already sitting next to the executable, and Windows"
            Write-Host "loads that one before anything on PATH. What you named here will be"
            Write-Host "ignored. To measure a particular renderer, build against it:"
            Write-Host ""
            Write-Host "  `$env:DIOXUS_COMPOSE_RENDERER_DIR = '$rendererDir'"
            Write-Host "  cargo clean -p dioxus-compose"
            Write-Host "  cargo build --release --example memory_probe --features native-renderer"
            Write-Host ''
        }
    }
} else {
    Write-Host ("no " + ($rendererDlls -join ' or ') + " found.")
    Write-Host "The application will not start without one on PATH. Looked in:"
    $searched | ForEach-Object { Write-Host "  $_" }
    Write-Host ''
    Write-Host 'cargo build prints the directory it unpacked the renderer into. Pass that'
    Write-Host 'with -RendererDir, or put it on PATH yourself.'
    exit 1
}

$env:DXC_PROBE_WIDTH = $Width
$env:DXC_PROBE_HEIGHT = $Height
$env:DXC_PROBE_ROWS = $Rows

Write-Host "starting $Exe"
$stdout = [System.IO.Path]::GetTempFileName()
$stderr = [System.IO.Path]::GetTempFileName()
$process = Start-Process -FilePath $Exe -PassThru `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr
try {
    Start-Sleep -Seconds $Settle
    if ($process.HasExited) {
        Write-Host "the process exited after $Settle seconds with code $($process.ExitCode)."
        Write-Host "It never got far enough to be measured. What it printed:"
        Write-Host ""
        Get-Content $stdout, $stderr -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $_" }
        exit 1
    }

    # The private working set, from the performance counter rather than from the Process
    # object: PrivateMemorySize64 is commit, which counts pages that were never brought
    # into RAM, and reads high.
    $instance = [System.IO.Path]::GetFileNameWithoutExtension($Exe)
    $privateSamples = @()
    $workingSamples = @()
    $commitSamples = @()
    for ($i = 0; $i -lt $Samples; $i++) {
        $process.Refresh()
        $workingSamples += $process.WorkingSet64
        $commitSamples += $process.PrivateMemorySize64
        try {
            $counter = Get-Counter "\Process V2($instance*)\Working Set - Private" -ErrorAction Stop
            $privateSamples += ($counter.CounterSamples | Select-Object -First 1).CookedValue
        } catch {
            # Older builds have no Process V2 counter set. The Process counter is the same
            # number without the instance disambiguation, which only matters when two of
            # these are running at once.
            try {
                $counter = Get-Counter "\Process($instance)\Working Set - Private" -ErrorAction Stop
                $privateSamples += ($counter.CounterSamples | Select-Object -First 1).CookedValue
            } catch {
                $privateSamples += 0
            }
        }
        Start-Sleep -Seconds 1
    }

    $rect = New-Object DxcWin32+RECT
    $size = 'unknown'
    if ($process.MainWindowHandle -ne 0 -and [DxcWin32]::GetWindowRect($process.MainWindowHandle, [ref]$rect)) {
        $size = "$($rect.Right - $rect.Left)x$($rect.Bottom - $rect.Top)"
    }

    function Median($values) {
        $sorted = $values | Sort-Object
        return $sorted[[int]($sorted.Count / 2)]
    }
    $private = Median $privateSamples
    $working = Median $workingSamples
    $commit = Median $commitSamples

    Write-Host ''
    Write-Host "exe            $Exe"
    Write-Host "window         $size (asked for ${Width}x${Height})"
    Write-Host "rows           $Rows"
    Write-Host ("private ws     {0:N1} MB   <- compare this one" -f ($private / 1MB))
    Write-Host ("working set    {0:N1} MB" -f ($working / 1MB))
    Write-Host ("commit         {0:N1} MB" -f ($commit / 1MB))
    if ($private -eq 0) {
        Write-Host ''
        Write-Host "the private working set counter was unavailable, so only the two below are real."
        Write-Host "run this in an elevated shell, or read the Memory column in Task Manager instead."
    }

    # Always, not only when the process died. An application that comes up with an empty
    # window is still running, and the reason it drew nothing is in here: the renderer says
    # so on stderr when it cannot reach the Host's functions. A measurement of a window
    # that never drew anything is a measurement of nothing, and that has already been
    # mistaken for a memory number once.
    $said = Get-Content $stdout, $stderr -ErrorAction SilentlyContinue
    if ($said) {
        Write-Host ''
        Write-Host 'the application said:'
        $said | ForEach-Object { Write-Host "  $_" }
    }

    if ($size -ne 'unknown' -and $size -ne "${Width}x${Height}") {
        Write-Host ''
        Write-Host "the window is $size and $Width x $Height was asked for."
        Write-Host "The size travels in the first batch from the Host, so a window that"
        Write-Host "ignored it is a window that received no batch at all. Nothing was drawn"
        Write-Host "and this number is not a measurement of an application."
    }
} finally {
    if (-not $process.HasExited) {
        $process.Kill()
        $process.WaitForExit(5000) | Out-Null
    }
}

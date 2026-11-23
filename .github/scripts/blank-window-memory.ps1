<#
.SYNOPSIS
Measures what an idle application holds in memory on Windows, for the memory report.

.DESCRIPTION
The Windows half of blank-window-memory.sh. Starts the application -Launches times (3 by
default), one after another. For each start it reads the process's memory 10 seconds after
the start and again 60 seconds after it, then closes the application by the process id it
started, never by name.

The number is the PRIVATE WORKING SET: the pages this process has in RAM that no other
process shares, the "Memory" column of Task Manager. It is read from the performance
counter \Process(<name>)\Working Set - Private, after checking that the counter's instance
is the process this started (its \Process(<name>)\ID Process). When the counter cannot be
read, the reading falls back to PrivateMemorySize64, which is private bytes (commit): it
also counts private pages that are not in RAM, so it reads higher. Each reading says which
of the two it is, and the summary prints the methods used.

The working set (WorkingSet64), which counts shared pages in full, is written beside it
for reference.

Writes into -Out:
  samples.json        every reading, for blank-window-memory-summary.py
  launch-<n>.out.log  and launch-<n>.err.log, what the application printed on that start

Exits 1 when the application is not running at its first reading (it did not start, or
died within 10 seconds), or when no reading could be made at all. A reading that is high
fails nothing: this measures, it does not judge.

.PARAMETER Exe
The application to measure.

.PARAMETER Out
The directory to write into. Made if missing.

.PARAMETER Launches
How many times to start it.

.PARAMETER First
.PARAMETER Second
Seconds after each start at which the two readings are taken.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Exe,
    [Parameter(Mandatory = $true)][string]$Out,
    [int]$Launches = 3,
    [int]$First = 10,
    [int]$Second = 60
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Exe -PathType Leaf)) {
    Write-Host "error: $Exe is not a file"
    exit 2
}
if ($Launches -lt 1 -or $First -ge $Second) {
    Write-Host "error: -Launches must be at least 1 and -First must come before -Second"
    exit 2
}
$Exe = (Resolve-Path -LiteralPath $Exe).Path
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Out = (Resolve-Path -LiteralPath $Out).Path

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class BlankWindowWin32 {
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")]
    public static extern bool GetClientRect(IntPtr hWnd, out RECT rect);
}
"@

# The counter instance is the executable's name without .exe. Only one copy runs at a time,
# and the ID Process counter is checked against the pid, so the instance read is this one.
$instance = [System.IO.Path]::GetFileNameWithoutExtension($Exe)

function Read-Private([int]$ProcessId) {
    try {
        $counters = Get-Counter -Counter @(
            "\Process($instance)\ID Process",
            "\Process($instance)\Working Set - Private"
        ) -ErrorAction Stop
        $id = $null
        $private = $null
        foreach ($sample in $counters.CounterSamples) {
            if ($sample.Path -like '*\id process') { $id = [int]$sample.CookedValue }
            if ($sample.Path -like '*\working set - private') { $private = [double]$sample.CookedValue }
        }
        if ($id -eq $ProcessId -and $null -ne $private) {
            return @{ kb = [math]::Round($private / 1024); method = 'counter: Working Set - Private' }
        }
        Write-Host "  the counter's instance $instance is pid $id, not $ProcessId; falling back"
    } catch {
        Write-Host "  Get-Counter failed: $($_.Exception.Message); falling back"
    }
    return $null
}

function Close-App([System.Diagnostics.Process]$Process) {
    if ($Process.HasExited) { return }
    # Asked first, as a person closing the window would; then ended by its id.
    [void]$Process.CloseMainWindow()
    if (-not $Process.WaitForExit(5000)) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        [void]$Process.WaitForExit(5000)
    }
}

$samples = New-Object System.Collections.Generic.List[object]
$window = 'unknown'
$notStarted = 0

for ($launch = 1; $launch -le $Launches; $launch++) {
    $stdout = Join-Path $Out "launch-$launch.out.log"
    $stderr = Join-Path $Out "launch-$launch.err.log"
    $process = Start-Process -FilePath $Exe -PassThru -WorkingDirectory (Split-Path -Parent $Exe) `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $started = Get-Date
    Write-Host "launch ${launch}: pid $($process.Id)"
    try {
        foreach ($at in @($First, $Second)) {
            $wait = ($started.AddSeconds($at) - (Get-Date)).TotalMilliseconds
            if ($wait -gt 0) { Start-Sleep -Milliseconds ([int]$wait) }
            $process.Refresh()
            if ($process.HasExited) {
                if ($at -eq $First) {
                    Write-Host "::error title=The application did not start::launch $launch (pid $($process.Id)) exited with code $($process.ExitCode) before $at s. What it printed:"
                    $notStarted++
                } else {
                    Write-Host "::warning title=No reading::launch $launch (pid $($process.Id)) exited with code $($process.ExitCode) before $at s. What it printed:"
                }
                Get-Content $stdout, $stderr -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $_" }
                break
            }
            $reading = Read-Private $process.Id
            $process.Refresh()
            if ($null -eq $reading) {
                $reading = @{
                    kb = [math]::Round($process.PrivateMemorySize64 / 1024)
                    method = 'fallback: PrivateMemorySize64 (private bytes)'
                }
            }
            $sample = [ordered]@{
                launch = $launch
                at_s = $at
                pid = $process.Id
                private_kb = $reading.kb
                method = $reading.method
                working_set_kb = [math]::Round($process.WorkingSet64 / 1024)
                private_bytes_kb = [math]::Round($process.PrivateMemorySize64 / 1024)
            }
            $samples.Add($sample)
            Write-Host "launch $launch at $at s: private $($sample.private_kb) kB ($($sample.method)), working set $($sample.working_set_kb) kB, private bytes $($sample.private_bytes_kb) kB"
            if ($launch -eq 1 -and $process.MainWindowHandle -ne [IntPtr]::Zero) {
                $rect = New-Object BlankWindowWin32+RECT
                if ([BlankWindowWin32]::GetClientRect($process.MainWindowHandle, [ref]$rect)) {
                    $window = "$($rect.Right - $rect.Left)x$($rect.Bottom - $rect.Top) client pixels"
                }
            }
        }
    } finally {
        Close-App $process
    }
}

$report = [ordered]@{
    os = 'windows'
    application = [System.IO.Path]::GetFileName($Exe)
    window = $window
    launches = $Launches
    readings_s = @($First, $Second)
    metric = 'private_kb'
    method = 'private working set from \Process(<name>)\Working Set - Private, or PrivateMemorySize64 where the counter could not be read'
    columns = @(
        [ordered]@{ key = 'private_kb'; label = 'Private working set' },
        [ordered]@{ key = 'working_set_kb'; label = 'Working set (reference)' },
        [ordered]@{ key = 'private_bytes_kb'; label = 'Private bytes (reference)' }
    )
    samples = $samples.ToArray()
}
$json = ConvertTo-Json -InputObject $report -Depth 5
Set-Content -LiteralPath (Join-Path $Out 'samples.json') -Value $json -Encoding utf8

Write-Host "wrote $(Join-Path $Out 'samples.json') ($($samples.Count) readings)"
if ($notStarted -gt 0) {
    Write-Host "error: the application did not start on $notStarted of $Launches launches"
    exit 1
}
if ($samples.Count -eq 0) {
    Write-Host "error: no reading could be made"
    exit 1
}
exit 0

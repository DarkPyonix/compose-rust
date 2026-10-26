# Real drags of a window's right edge driven by resize-probe, for both Windows renderers in
# turn: the Kotlin/Native one (hand-check.exe) and the GraalVM native image one
# (graalvm\bin\hand-check.exe), and for the control window (resize-control.exe). Each renderer
# is dragged with resize frames on the CPU (new) and on the GPU (old, DXC_RASTER_RESIZE=0); the
# control presents every size 50 ms late on purpose (late). All at three mouse rates. Logs go to
# probe-logs\ and probe-logs.zip, with summary.md, the table of results. Do not touch the mouse
# while it runs.
#
# The control decides what the renderers' results mean on this machine. Where the probe sees
# the control's lateness (the control FAILs), a renderer's PASS or FAIL at the same mouse rate
# stands. Where it does not (the control PASSes), Windows itself is hiding late frames here, so
# a renderer's result at that rate is INCONCLUSIVE: the probe cannot tell late from prompt.
#
# -NoPause skips the closing prompt (CI). -Out puts the logs somewhere else. -Apps picks which
# of kn, graalvm and control run (all that are present, by default).
param(
    [switch]$NoPause,
    [string]$Out = '',
    [string[]]$Apps = @('kn', 'graalvm', 'control', 'sync')
)
$ErrorActionPreference = 'Continue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = if ($Out -ne '') { $Out } else { Join-Path $here 'probe-logs' }
New-Item -ItemType Directory -Force -Path $out | Out-Null
$rates = @(@(16, 8), @(8, 4), @(1, 1))
# The GraalVM renderer has no hook that puts its window in place for the drag, so the probe
# places it (--place). The control places itself.
$all = @(
    @{ Name = 'kn'; Exe = (Join-Path $here 'hand-check.exe'); Place = $false; Class = 'ComposeRustWindow'; Paths = @('new', 'old', 'sync') },
    @{ Name = 'graalvm'; Exe = (Join-Path $here 'graalvm\bin\hand-check.exe'); Place = $true; Class = 'ComposeRustWindow'; Paths = @('new', 'old') },
    @{ Name = 'control'; Exe = (Join-Path $here 'resize-control.exe'); Place = $false; Class = 'ResizeControlWindow'; Paths = @('late') },
    # The window that asks Windows to wait for it (the private resize synchronization): late = 50 ms
    # late on purpose, prompt = not late (its lag is the cost of the mechanism), swapchain = 50 ms
    # late with a composition swapchain instead of a drawing surface, on12 = 50 ms late with the
    # renderer's arrangement of devices (Direct3D 12 under Direct3D 11, copied into the surface).
    @{ Name = 'sync'; Exe = (Join-Path $here 'resize-sync-control.exe'); Place = $false; Class = 'ResizeSyncControlWindow'; Paths = @('late', 'prompt', 'swapchain', 'on12') }
)
$runs = @()
foreach ($app in $all) {
    if ($Apps -notcontains $app.Name) { continue }
    if (-not (Test-Path $app.Exe)) {
        Write-Host "== $($app.Name): $($app.Exe) is missing, skipped"
        continue
    }
    foreach ($path in $app.Paths) {
        foreach ($r in $rates) {
            $label = "$($app.Name)-$path-$($r[0])"
            $probeLog = Join-Path $out "$label-probe.log"
            $appLog = Join-Path $out "$label.log"
            Write-Host "== $label"
            $arguments = @('--turn-on-full-window-drag', '--interval-ms', "$($r[0])", '--step-px', "$($r[1])",
                '--class', $app.Class, '--out', "`"$probeLog`"")
            if ($app.Place) { $arguments += '--place' }
            $probe = Start-Process -PassThru -WindowStyle Minimized -FilePath (Join-Path $here 'resize-probe.exe') `
                -ArgumentList $arguments
            for ($i = 0; $i -lt 60; $i++) {
                if ((Test-Path $probeLog) -and (Select-String -Path $probeLog -Pattern 'waiting for a' -Quiet)) { break }
                if ($probe.HasExited) { break }
                Start-Sleep -Milliseconds 500
            }
            $env:DXC_METRICS = '1'; $env:DXC_REAL_DRAG = 'external'; $env:DXC_RESIZE_TRACE = '1'
            if ($path -eq 'old') { $env:DXC_RASTER_RESIZE = '0' } else { Remove-Item Env:DXC_RASTER_RESIZE -ErrorAction SilentlyContinue }
            if ($path -eq 'late' -or $path -eq 'swapchain' -or $path -eq 'on12') { $env:DXC_RESIZE_PRESENT_DELAY_MS = '50' }
            elseif ($path -eq 'prompt') { $env:DXC_RESIZE_PRESENT_DELAY_MS = '0' }
            else { Remove-Item Env:DXC_RESIZE_PRESENT_DELAY_MS -ErrorAction SilentlyContinue }
            if ($path -eq 'swapchain') { $env:RESIZE_SYNC_MODE = 'swapchain' } else { Remove-Item Env:RESIZE_SYNC_MODE -ErrorAction SilentlyContinue }
            # sync = the renderer's opt-in path that makes Windows wait for its frame (DXC_RESIZE_SYNC);
            # force skips the Windows 11 build floor, so that it runs on the Server builds too.
            if ($path -eq 'sync' -and $app.Name -ne 'control') { $env:DXC_RESIZE_SYNC = 'force' } else { Remove-Item Env:DXC_RESIZE_SYNC -ErrorAction SilentlyContinue }
            if ($path -eq 'on12') { $env:RESIZE_SYNC_DEVICE = 'on12' } else { Remove-Item Env:RESIZE_SYNC_DEVICE -ErrorAction SilentlyContinue }
            $process = Start-Process -PassThru -FilePath $app.Exe -WorkingDirectory (Split-Path -Parent $app.Exe) `
                -RedirectStandardOutput $appLog -RedirectStandardError "$appLog.err"
            $probe.WaitForExit(180000) | Out-Null
            Start-Sleep -Seconds 2
            if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
            Start-Sleep -Seconds 1
            # Both of the application's logs (standard output, and standard error, where its resize
            # lines go) start with the Windows version it ran on, as the probe read it.
            $os = Select-String -Path $probeLog -Pattern '^resize-probe: os (.*)$' | Select-Object -First 1
            if ($os) {
                $line = "resize-kit: os $($os.Matches[0].Groups[1].Value) (read by resize-probe, written here by run-probe-v2)"
                foreach ($log in @($appLog, "$appLog.err")) {
                    $body = if (Test-Path $log) { Get-Content -Raw -Path $log } else { '' }
                    if ($body -eq $null) { $body = '' }
                    Set-Content -Path $log -Value ($line + "`r`n" + $body) -NoNewline -Encoding UTF8
                }
            }
            Select-String -Path $probeLog -Pattern 'verdict' | ForEach-Object { Write-Host $_.Line }
            $runs += @{ Log = $appLog; Label = $label; App = $app.Name; Path = $path; Rate = $r[0]; Probe = $probeLog }
        }
    }
}
Remove-Item Env:DXC_RASTER_RESIZE, Env:DXC_METRICS, Env:DXC_REAL_DRAG, Env:DXC_RESIZE_TRACE, Env:DXC_RESIZE_PRESENT_DELAY_MS, Env:RESIZE_SYNC_MODE, Env:RESIZE_SYNC_DEVICE, Env:DXC_RESIZE_SYNC -ErrorAction SilentlyContinue

# The table: every drag's verdict, what it saw, how far the window lagged the cursor, and what
# the verdict means here given the control's at the same rate.
function Read-Field($text, $name) {
    $m = [regex]::Match($text, "$name=(-?[0-9.]+)")
    if ($m.Success) { return $m.Groups[1].Value } else { return '-' }
}
function Read-Verdict($text) {
    $m = [regex]::Match($text, 'resize-probe: verdict (PASS|FAIL|ERROR)')
    if ($m.Success) { return $m.Groups[1].Value } else { return 'none' }
}
$controlVerdict = @{}
foreach ($run in $runs) {
    if ($run.App -eq 'control') {
        $text = if (Test-Path $run.Probe) { Get-Content -Raw -Path $run.Probe } else { '' }
        $controlVerdict[$run.Rate] = Read-Verdict $text
    }
}
$osLine = 'unknown'
$scaleLine = 'unknown'
$rows = @()
foreach ($run in $runs) {
    $text = if (Test-Path $run.Probe) { Get-Content -Raw -Path $run.Probe } else { '' }
    $m = [regex]::Match($text, 'resize-probe: os (.*)')
    if ($m.Success -and $osLine -eq 'unknown') { $osLine = $m.Groups[1].Value.Trim() }
    $m = [regex]::Match($text, 'px, dpi ([0-9]+) \(([0-9.]+)x\)')
    if ($m.Success -and $scaleLine -eq 'unknown') { $scaleLine = "dpi $($m.Groups[1].Value) ($($m.Groups[2].Value)x)" }
    $verdict = Read-Verdict $text
    $control = if ($controlVerdict.ContainsKey($run.Rate)) { $controlVerdict[$run.Rate] } else { 'none' }
    if ($run.App -eq 'sync') {
        # How far the mechanism got is in the window's own log: steps drawn, steps that had a token.
        $appText = ''
        foreach ($log in @($run.Log, "$($run.Log).err")) { if (Test-Path $log) { $appText += Get-Content -Raw -Path $log } }
        $stepCount = ([regex]::Matches($appText, 'resize-sync-control: step n=')).Count
        $tokenCount = ([regex]::Matches($appText, 'resize-sync-control: step n=.* token=1')).Count
        $syncLine = [regex]::Match($appText, 'resize-sync-control: sync=(on|off)')
        $syncState = if ($syncLine.Success) { $syncLine.Groups[1].Value } else { 'unknown' }
        $meaning = "sync=$syncState, tokens $tokenCount of $stepCount steps; " + $(switch ($verdict) {
            'PASS' { 'no hole, black or stale frame' }
            'FAIL' { 'frames were NOT held for the app' }
            default { 'the drag did not run' }
        })
    } elseif ($run.App -eq 'control') {
        $meaning = switch ($verdict) {
            'FAIL' { 'the probe sees 50 ms late frames here' }
            'PASS' { 'NO DISCRIMINATION: 50 ms late frames look prompt here' }
            default { 'the control did not run' }
        }
    } elseif ($verdict -ne 'PASS' -and $verdict -ne 'FAIL') {
        $meaning = 'the drag did not run'
    } elseif ($control -eq 'FAIL') {
        $meaning = $verdict
        if ($run.Path -eq 'sync') {
            # Whether hand-check took the sync path is in its own log.
            $appText = ''
            foreach ($log in @($run.Log, "$($run.Log).err")) { if (Test-Path $log) { $appText += Get-Content -Raw -Path $log } }
            $on = [regex]::Match($appText, 'resize-mode: resize-sync on')
            $done = [regex]::Matches($appText, 'resize-sync: drag done steps=([0-9]+) tokens=([0-9]+)')
            if ($on.Success -and $done.Count -gt 0) {
                $last = $done[$done.Count - 1]
                $meaning = "$verdict (sync on, tokens $($last.Groups[2].Value) of $($last.Groups[1].Value) steps)"
            } else {
                $meaning = "$verdict (sync NOT taken: this hand-check did not take the path; see its log)"
            }
        }
    } elseif ($control -eq 'PASS') {
        $meaning = "INCONCLUSIVE (no discrimination; probe said $verdict)"
    } else {
        $meaning = "INCONCLUSIVE (the control did not run; probe said $verdict)"
    }
    $holes = "$(Read-Field $text 'frames_with_hole_in_drag')/$(Read-Field $text 'strip_frames_with_hole_in_drag')"
    $black = "$(Read-Field $text 'frames_with_black_in_drag')/$(Read-Field $text 'strip_frames_with_black_in_drag')"
    $rows += "| $($run.App) | $($run.Path) | $($run.Rate) ms | $verdict | $(Read-Field $text 'frames_in_drag') | $holes | $black | " +
        "$(Read-Field $text 'strip_frames_torn_in_drag') | $(Read-Field $text 'frames_stale_in_drag') | " +
        "$(Read-Field $text 'px_median') / $(Read-Field $text 'px_p95') | $(Read-Field $text 'ms_median') / $(Read-Field $text 'ms_p95') | $meaning |"
}
$summary = @(
    "OS: $osLine",
    "Display: $scaleLine",
    '',
    '| app | path | mouse | probe | frames in drag | holes (rows/strip) | black (rows/strip) | torn | stale | lag px (median / p95) | lag ms (median / p95) | result here |',
    '|---|---|---|---|---|---|---|---|---|---|---|---|'
) + $rows
$summaryPath = Join-Path $out 'summary.md'
Set-Content -Path $summaryPath -Value $summary -Encoding UTF8
Write-Host ''
$summary | ForEach-Object { Write-Host $_ }
Compress-Archive -Force -Path (Join-Path $out '*') -DestinationPath (Join-Path (Split-Path -Parent $out) 'probe-logs.zip')
Write-Host "Done. Send probe-logs.zip"
if (-not $NoPause) { Read-Host "Press Enter to close" }

# Real drags of a window's edges driven by resize-probe, for both Windows renderers in turn:
# the Kotlin/Native one (hand-check.exe) and the GraalVM native image one
# (graalvm\bin\hand-check.exe), and for the control window (resize-control.exe). Each renderer
# is dragged with resize frames on the CPU (new) and on the GPU (old, DXC_RASTER_RESIZE=0); the
# control presents every size 50 ms late on purpose (late). Each is dragged by its right,
# bottom and left edges and its bottom right corner, at three mouse rates, and taken through
# the window states once: maximised, restored, set to the left half of the screen and back.
# Logs go to probe-logs\ and probe-logs.zip, with summary.md, the table of results, and the
# frames each drag kept under frames\, as PNG files with a contact sheet per drag and
# contact-sheet.png, one frame of each. Do not touch the mouse while it runs.
#
# Every app draws the marker scene (hand-check with DXC_RESIZE_MARKER=1; the control always
# does): green markers down the left and right edges and along the bottom, and a ruler. The
# probe judges each frame by them: a marker further from the window's border than at rest is
# an area the app did not draw, and a marker of another width or a ruler line off its grid is
# a stretched picture.
#
# The control decides what the renderers' results mean on this machine. Where the probe sees
# the control's lateness (the control FAILs), a renderer's PASS or FAIL in the same drag at the
# same mouse rate stands. Where it does not (the control PASSes), Windows itself is hiding late
# frames here, so a renderer's result there is INCONCLUSIVE: the probe cannot tell late from
# prompt.
#
# -NoPause skips the closing prompt (CI). -Out puts the logs somewhere else. -Apps picks which
# of kn, graalvm and control run (all that are present, by default). -Edges picks the drags
# (right, bottom, corner, left and states, by default).
param(
    [switch]$NoPause,
    [string]$Out = '',
    [string[]]$Apps = @('kn', 'graalvm', 'control'),
    [string[]]$Edges = @('right', 'bottom', 'corner', 'left', 'states')
)
$ErrorActionPreference = 'Continue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = if ($Out -ne '') { $Out } else { Join-Path $here 'probe-logs' }
New-Item -ItemType Directory -Force -Path $out | Out-Null
$rates = @(@(16, 8), @(8, 4), @(1, 1))
# The GraalVM renderer has no hook that puts its window in place for the drag, so the probe
# places it (--place). The control places itself.
$all = @(
    @{ Name = 'kn'; Exe = (Join-Path $here 'hand-check.exe'); Place = $false; Class = 'ComposeRustWindow'; Paths = @('new', 'old') },
    @{ Name = 'graalvm'; Exe = (Join-Path $here 'graalvm\bin\hand-check.exe'); Place = $true; Class = 'ComposeRustWindow'; Paths = @('new', 'old') },
    @{ Name = 'control'; Exe = (Join-Path $here 'resize-control.exe'); Place = $false; Class = 'ResizeControlWindow'; Paths = @('late') }
)
$runs = @()
foreach ($app in $all) {
    if ($Apps -notcontains $app.Name) { continue }
    if (-not (Test-Path $app.Exe)) {
        Write-Host "== $($app.Name): $($app.Exe) is missing, skipped"
        continue
    }
    foreach ($path in $app.Paths) {
        foreach ($edge in $Edges) {
            # The window states are not a drag, so they have no mouse rate: one run each.
            $edgeRates = $rates
            if ($edge -eq 'states') { $edgeRates = @(, @(16, 8)) }
            foreach ($r in $edgeRates) {
                $rate = if ($edge -eq 'states') { 'states' } else { "$($r[0])" }
                $label = if ($edge -eq 'states') { "$($app.Name)-$path-states" } else { "$($app.Name)-$path-$edge-$($r[0])" }
                $probeLog = Join-Path $out "$label-probe.log"
                $appLog = Join-Path $out "$label.log"
                $frames = Join-Path $out "frames\$label"
                New-Item -ItemType Directory -Force -Path $frames | Out-Null
                Write-Host "== $label"
                $arguments = @('--turn-on-full-window-drag', '--interval-ms', "$($r[0])", '--step-px', "$($r[1])",
                    '--class', $app.Class, '--edge', $edge, '--markers', '--frames', "`"$frames`"", '--out', "`"$probeLog`"")
                if ($app.Place) { $arguments += '--place' }
                if ($edge -eq 'states') { $arguments += '--no-animation' }
                $probe = Start-Process -PassThru -WindowStyle Minimized -FilePath (Join-Path $here 'resize-probe.exe') `
                    -ArgumentList $arguments
                for ($i = 0; $i -lt 60; $i++) {
                    if ((Test-Path $probeLog) -and (Select-String -Path $probeLog -Pattern 'waiting for a' -Quiet)) { break }
                    if ($probe.HasExited) { break }
                    Start-Sleep -Milliseconds 500
                }
                $env:DXC_METRICS = '1'; $env:DXC_REAL_DRAG = 'external'; $env:DXC_RESIZE_TRACE = '1'
                $env:DXC_RESIZE_MARKER = '1'
                if ($path -eq 'old') { $env:DXC_RASTER_RESIZE = '0' } else { Remove-Item Env:DXC_RASTER_RESIZE -ErrorAction SilentlyContinue }
                if ($path -eq 'late') { $env:DXC_RESIZE_PRESENT_DELAY_MS = '50' } else { Remove-Item Env:DXC_RESIZE_PRESENT_DELAY_MS -ErrorAction SilentlyContinue }
                $process = Start-Process -PassThru -FilePath $app.Exe -WorkingDirectory (Split-Path -Parent $app.Exe) `
                    -RedirectStandardOutput $appLog -RedirectStandardError "$appLog.err"
                $probe.WaitForExit(180000) | Out-Null
                Start-Sleep -Seconds 2
                if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
                Start-Sleep -Seconds 1
                # Both of the application's logs (standard output, and standard error, where its
                # resize lines go) start with the Windows version it ran on, as the probe read it.
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
                $runs += @{ Label = $label; App = $app.Name; Path = $path; Edge = $edge; Rate = $rate; Probe = $probeLog }
            }
        }
    }
}
Remove-Item Env:DXC_RASTER_RESIZE, Env:DXC_METRICS, Env:DXC_REAL_DRAG, Env:DXC_RESIZE_TRACE, Env:DXC_RESIZE_PRESENT_DELAY_MS, Env:DXC_RESIZE_MARKER -ErrorAction SilentlyContinue

# The table: every drag's verdict, what it saw, how far the window lagged the cursor, and what
# the verdict means here given the control's in the same drag at the same rate.
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
        $controlVerdict["$($run.Edge)-$($run.Rate)"] = Read-Verdict $text
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
    $key = "$($run.Edge)-$($run.Rate)"
    $control = if ($controlVerdict.ContainsKey($key)) { $controlVerdict[$key] } else { 'none' }
    if ($run.App -eq 'control') {
        $meaning = switch ($verdict) {
            'FAIL' { 'the probe sees 50 ms late frames here' }
            'PASS' { 'NO DISCRIMINATION: 50 ms late frames look prompt here' }
            default { 'the control did not run' }
        }
    } elseif ($verdict -ne 'PASS' -and $verdict -ne 'FAIL') {
        $meaning = 'the drag did not run'
    } elseif ($control -eq 'FAIL') {
        $meaning = $verdict
    } elseif ($control -eq 'PASS') {
        $meaning = "INCONCLUSIVE (no discrimination; probe said $verdict)"
    } else {
        $meaning = "INCONCLUSIVE (the control did not run; probe said $verdict)"
    }
    $holes = "$(Read-Field $text 'frames_with_hole_in_drag')/$(Read-Field $text 'strip_frames_with_hole_in_drag')"
    $black = "$(Read-Field $text 'frames_with_black_in_drag')/$(Read-Field $text 'strip_frames_with_black_in_drag')"
    $gap = "$(Read-Field $text 'frames_marker_gap_in_drag') (worst $(Read-Field $text 'worst_gap_px') px)"
    $mouse = if ($run.Rate -eq 'states') { '-' } else { "$($run.Rate) ms" }
    $rows += "| $($run.App) | $($run.Path) | $($run.Edge) | $mouse | $verdict | $(Read-Field $text 'frames_in_drag') | $holes | $black | " +
        "$gap | $(Read-Field $text 'frames_marker_stretch_in_drag') | " +
        "$(Read-Field $text 'strip_frames_torn_in_drag') | $(Read-Field $text 'frames_stale_in_drag') | " +
        "$(Read-Field $text 'px_median') / $(Read-Field $text 'px_p95') | $(Read-Field $text 'ms_median') / $(Read-Field $text 'ms_p95') | $meaning |"
}
$summary = @(
    "OS: $osLine",
    "Display: $scaleLine",
    '',
    '| app | path | drag | mouse | probe | frames in drag | holes (rows/strip) | black (rows/strip) | marker gap | stretched | torn | stale | lag px (median / p95) | lag ms (median / p95) | result here |',
    '|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|'
) + $rows
$summaryPath = Join-Path $out 'summary.md'
Set-Content -Path $summaryPath -Value $summary -Encoding UTF8
Write-Host ''
$summary | ForEach-Object { Write-Host $_ }
# The frames as PNG files, a contact sheet per drag, and one frame of each drag on one sheet.
& (Join-Path $here 'resize-contact-sheet.ps1') -Dir $out
Compress-Archive -Force -Path (Join-Path $out '*') -DestinationPath (Join-Path (Split-Path -Parent $out) 'probe-logs.zip')
Write-Host "Done. Send probe-logs.zip"
if (-not $NoPause) { Read-Host "Press Enter to close" }

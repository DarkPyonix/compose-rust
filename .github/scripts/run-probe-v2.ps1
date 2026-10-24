# Real drags of hand-check's right edge driven by resize-probe, for both Windows renderers in
# turn: the Kotlin/Native one (hand-check.exe) and the GraalVM native image one
# (graalvm\bin\hand-check.exe). Each is dragged with resize frames on the CPU (new) and on the
# GPU (old, DXC_RASTER_RESIZE=0), at three mouse rates. Logs go to probe-logs\ and
# probe-logs.zip. Do not touch the mouse while it runs.
#
# -NoPause skips the closing prompt (CI). -Out puts the logs somewhere else.
param(
    [switch]$NoPause,
    [string]$Out = ''
)
$ErrorActionPreference = 'Continue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = if ($Out -ne '') { $Out } else { Join-Path $here 'probe-logs' }
New-Item -ItemType Directory -Force -Path $out | Out-Null
$rates = @(@(16, 8), @(8, 4), @(1, 1))
# The GraalVM renderer has no hook that puts its window in place for the drag, so the probe
# places it (--place).
$apps = @(
    @{ Name = 'kn'; Exe = (Join-Path $here 'hand-check.exe'); Place = $false },
    @{ Name = 'graalvm'; Exe = (Join-Path $here 'graalvm\bin\hand-check.exe'); Place = $true }
)
foreach ($app in $apps) {
    if (-not (Test-Path $app.Exe)) {
        Write-Host "== $($app.Name): $($app.Exe) is missing, skipped"
        continue
    }
    foreach ($path in @('new', 'old')) {
        foreach ($r in $rates) {
            $label = "$($app.Name)-$path-$($r[0])"
            $probeLog = Join-Path $out "$label-probe.log"
            $appLog = Join-Path $out "$label.log"
            Write-Host "== $label"
            $arguments = @('--turn-on-full-window-drag', '--interval-ms', "$($r[0])", '--step-px', "$($r[1])", '--out', "`"$probeLog`"")
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
            $process = Start-Process -PassThru -FilePath $app.Exe -WorkingDirectory (Split-Path -Parent $app.Exe) `
                -RedirectStandardOutput $appLog -RedirectStandardError "$appLog.err"
            $probe.WaitForExit(180000) | Out-Null
            Start-Sleep -Seconds 2
            if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
            Start-Sleep -Seconds 1
            Select-String -Path $probeLog -Pattern 'verdict' | ForEach-Object { Write-Host $_.Line }
        }
    }
}
Remove-Item Env:DXC_RASTER_RESIZE, Env:DXC_METRICS, Env:DXC_REAL_DRAG, Env:DXC_RESIZE_TRACE -ErrorAction SilentlyContinue
Compress-Archive -Force -Path (Join-Path $out '*') -DestinationPath (Join-Path (Split-Path -Parent $out) 'probe-logs.zip')
Write-Host "Done. Send probe-logs.zip"
if (-not $NoPause) { Read-Host "Press Enter to close" }

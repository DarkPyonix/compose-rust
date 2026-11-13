# Turns the frames resize-probe kept (--frames, as BMP) into PNG files and contact sheets, so a
# person can look at what each verdict is about.
#
#   -Dir <probe-logs folder>   the folder run-probe-v2.ps1 wrote: <label>-probe.log for each
#                              drag and frames\<label>\frame-NNNNN-<kind>.bmp
#
# For each drag, frames\<label>\contact.png shows every frame it kept, captioned with its
# number and kind: rest (before the drag), drag (sampled through it), fault-<what> (the first
# frames with a fault: hole, black, torn, stale, gap, stretch), after (the first frame once it
# was over). contact-sheet.png in the folder shows one frame per drag, its first fault or else
# the middle of the drag, captioned with the drag and the probe's verdict.
#
# Windows PowerShell 5.1 and System.Drawing, which every Windows desktop has.
param(
    [Parameter(Mandatory = $true)][string]$Dir
)
$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Drawing

$cellWidth = 360
$cellHeight = 300
$captionHeight = 34
$columns = 4

function Convert-Frames($folder) {
    foreach ($bmp in @(Get-ChildItem -Path $folder -Filter '*.bmp' -File -ErrorAction SilentlyContinue)) {
        $png = [System.IO.Path]::ChangeExtension($bmp.FullName, '.png')
        try {
            $image = New-Object System.Drawing.Bitmap($bmp.FullName)
            $image.Save($png, [System.Drawing.Imaging.ImageFormat]::Png)
            $image.Dispose()
            Remove-Item -Path $bmp.FullName -Force
        } catch {
            Write-Host "could not convert $($bmp.FullName): $_"
        }
    }
}

# A sheet of cells, each an image scaled to fit with a caption under it.
function Write-Sheet($cells, $path, $title) {
    if ($cells.Count -eq 0) { return }
    $rowsNeeded = [int][Math]::Ceiling($cells.Count / $columns)
    $width = $columns * $cellWidth
    $height = 40 + $rowsNeeded * ($cellHeight + $captionHeight)
    $sheet = New-Object System.Drawing.Bitmap($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($sheet)
    $graphics.Clear([System.Drawing.Color]::White)
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $font = New-Object System.Drawing.Font('Segoe UI', 9)
    $titleFont = New-Object System.Drawing.Font('Segoe UI', 12, [System.Drawing.FontStyle]::Bold)
    $graphics.DrawString($title, $titleFont, [System.Drawing.Brushes]::Black, 8, 8)
    for ($index = 0; $index -lt $cells.Count; $index++) {
        $cell = $cells[$index]
        $x = [int](($index % $columns) * $cellWidth)
        $y = [int](40 + [Math]::Floor($index / $columns) * ($cellHeight + $captionHeight))
        try {
            $image = [System.Drawing.Image]::FromFile($cell.Path)
            $scale = [Math]::Min(($cellWidth - 8) / $image.Width, ($cellHeight - 8) / $image.Height)
            if ($scale -gt 1) { $scale = 1 }
            $w = [int]($image.Width * $scale)
            $h = [int]($image.Height * $scale)
            $graphics.DrawImage($image, [int]($x + 4), [int]($y + 4), $w, $h)
            $image.Dispose()
        } catch {
            $graphics.DrawString("unreadable: $($cell.Path)", $font, [System.Drawing.Brushes]::Red, $x + 4, $y + 4)
        }
        $brush = if ($cell.Fault) { [System.Drawing.Brushes]::Red } else { [System.Drawing.Brushes]::Black }
        $box = New-Object System.Drawing.RectangleF(($x + 4), ($y + $cellHeight), ($cellWidth - 8), $captionHeight)
        $graphics.DrawString($cell.Caption, $font, $brush, $box)
    }
    $graphics.Dispose()
    $sheet.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $sheet.Dispose()
}

$framesRoot = Join-Path $Dir 'frames'
$overview = @()
foreach ($folder in @(Get-ChildItem -Path $framesRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name)) {
    $label = $folder.Name
    Convert-Frames $folder.FullName
    $pngs = @(Get-ChildItem -Path $folder.FullName -Filter 'frame-*.png' -File | Sort-Object Name)
    $probeLog = Join-Path $Dir "$label-probe.log"
    $verdict = 'no verdict'
    if (Test-Path $probeLog) {
        $line = Select-String -Path $probeLog -Pattern 'resize-probe: verdict (.*)$' | Select-Object -First 1
        if ($line) { $verdict = $line.Matches[0].Groups[1].Value }
    }
    $cells = @()
    foreach ($png in $pngs) {
        $kind = $png.BaseName -replace '^frame-[0-9]+-', ''
        $number = $png.BaseName -replace '^frame-([0-9]+)-.*$', '$1'
        $cells += @{ Path = $png.FullName; Caption = "frame $number`: $kind"; Fault = ($kind -like '*fault*') }
    }
    Write-Sheet $cells (Join-Path $folder.FullName 'contact.png') "$label`: $verdict"
    if ($pngs.Count -gt 0) {
        $pick = $pngs | Where-Object { $_.BaseName -match '-fault-' } | Select-Object -First 1
        if (-not $pick) {
            $drag = @($pngs | Where-Object { $_.BaseName -match '-drag$' })
            if ($drag.Count -gt 0) { $pick = $drag[[int][Math]::Floor($drag.Count / 2)] } else { $pick = $pngs[0] }
        }
        $short = if ($verdict.Length -gt 90) { $verdict.Substring(0, 90) + '...' } else { $verdict }
        $overview += @{ Path = $pick.FullName; Caption = "$label ($($pick.BaseName -replace '^frame-', ''))`n$short";
            Fault = ($verdict -like 'FAIL*') }
    }
}
Write-Sheet $overview (Join-Path $Dir 'contact-sheet.png') "resize-probe frames: one per drag (its first fault, or the middle of the drag)"
Write-Host "contact sheets written under $Dir"

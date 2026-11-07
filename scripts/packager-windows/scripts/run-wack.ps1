<#
.SYNOPSIS
Runs the Windows App Certification Kit against a package and fails on a failed test.

.DESCRIPTION
The kit is the check Partner Center runs when a package is submitted, so a package that
passes it here is not refused for the same reasons there. It inspects the manifest, the
binaries and the package contents and writes an XML report; for a full-trust desktop
package run from the command line it does not launch the program. Sign the package
with a certificate this machine trusts first, as a machine installing it would.

Warnings and failures of tests the kit marks optional do not fail the run; they are
printed, and listed in the step summary, so that they are read. The Store accepts a
package with them. A required test failing fails the run, and so does any test named in
-RequirePass that did not pass.

The optional "Blocked executables" test reports only a file name and a short string.
That is not enough to answer a reviewer who asks about it, so for each such finding this
script opens the package, finds the bytes in the named file, and prints where they are
and what surrounds them: whether the string is an imported function, part of a longer
word, or a few bytes of compressed data that happen to spell a blocked name.

.PARAMETER Package
The signed .msix or .msixbundle.

.PARAMETER Report
Where the XML report is written. The explanations go beside it, in
<report>.findings.txt.

.PARAMETER RequirePass
Names of tests that must pass outright, not merely avoid failing (for example
DPIAwarenessValidation, which is otherwise only a warning).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Package,
    [Parameter(Mandatory)][string]$Report,
    [string[]]$RequirePass = @()
)
$ErrorActionPreference = 'Stop'

$appcert = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\App Certification Kit\appcert.exe'
if (-not (Test-Path $appcert)) {
    throw "the Windows App Certification Kit is not installed ($appcert); it ships with the Windows SDK"
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Report) | Out-Null
if (Test-Path $Report) { Remove-Item $Report }

& $appcert reset
& $appcert test -appxpackagepath (Resolve-Path $Package).Path -reportoutputpath $Report
$exit = $LASTEXITCODE
if (-not (Test-Path $Report)) {
    throw "the certification kit wrote no report (exit $exit)"
}

# --- Explaining "Blocked executables" findings -------------------------------------------

$script:unpacked = $null

function Get-UnpackedPackage {
    if ($script:unpacked) { return $script:unpacked }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $root = Join-Path ([System.IO.Path]::GetTempPath()) ("wack-" + [guid]::NewGuid().ToString('N'))
    [System.IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path $Package).Path, $root)
    # A bundle holds packages; open those too so a file name finds the file inside them.
    Get-ChildItem -Path $root -Filter '*.msix' -File | ForEach-Object {
        [System.IO.Compression.ZipFile]::ExtractToDirectory($_.FullName, (Join-Path $root $_.BaseName))
    }
    $script:unpacked = $root
    return $root
}

function Find-PackageFile([string]$name) {
    $root = Get-UnpackedPackage
    $leaf = Split-Path -Leaf $name
    $candidates = @(Get-ChildItem -Path $root -Recurse -File -Filter $leaf)
    $suffix = $name -replace '/', '\'
    $exact = @($candidates | Where-Object { $_.FullName.EndsWith($suffix, [StringComparison]::OrdinalIgnoreCase) })
    if ($exact.Count -gt 0) { return $exact[0] }
    if ($candidates.Count -gt 0) { return $candidates[0] }
    return $null
}

function Format-Context([byte[]]$bytes, [int]$at, [int]$length, [int]$step) {
    # Printable ASCII as itself, everything else as a dot. $step is 2 for UTF-16 text,
    # where every other byte is the zero half of a character.
    $from = [Math]::Max(0, $at - 32 * $step)
    $to = [Math]::Min($bytes.Length, $at + ($length + 32) * $step)
    $sb = [System.Text.StringBuilder]::new()
    for ($i = $from; $i -lt $to; $i += $step) {
        if ($i -eq $at) { [void]$sb.Append('[') }
        if ($i -eq $at + $length * $step) { [void]$sb.Append(']') }
        $b = $bytes[$i]
        if ($b -ge 0x20 -and $b -lt 0x7F) { [void]$sb.Append([char]$b) } else { [void]$sb.Append('.') }
    }
    return $sb.ToString()
}

function Get-Occurrences([byte[]]$bytes, [string]$needle) {
    $latin1 = [System.Text.Encoding]::Latin1
    $haystack = $latin1.GetString($bytes)
    $found = @()
    foreach ($encoding in @(@{ Name = 'ASCII'; Step = 1; Text = $needle },
                            @{ Name = 'UTF-16'; Step = 2; Text = $latin1.GetString([System.Text.Encoding]::Unicode.GetBytes($needle)) })) {
        $at = 0
        while ($found.Count -lt 3) {
            $at = $haystack.IndexOf($encoding.Text, $at, [StringComparison]::Ordinal)
            if ($at -lt 0) { break }
            $found += [pscustomobject]@{ Offset = $at; Encoding = $encoding.Name; Step = $encoding.Step }
            $at += 1
        }
    }
    return $found
}

function Get-Kind([byte[]]$bytes, $hit, [int]$length) {
    # A string that is a whole word between non-letters reads as a name; one with letters
    # on either side is part of a longer word; one with no text around it is data.
    $before = if ($hit.Offset - $hit.Step -ge 0) { $bytes[$hit.Offset - $hit.Step] } else { 0 }
    $afterAt = $hit.Offset + $length * $hit.Step
    $after = if ($afterAt -lt $bytes.Length) { $bytes[$afterAt] } else { 0 }
    $isLetter = { param($b) ($b -ge 0x41 -and $b -le 0x5A) -or ($b -ge 0x61 -and $b -le 0x7A) }
    $isText = { param($b) $b -ge 0x20 -and $b -lt 0x7F }
    if ((& $isLetter $before) -or (& $isLetter $after)) { return 'inside a longer word' }
    if (-not (& $isText $before) -and -not (& $isText $after)) { return 'binary data that happens to spell it' }
    return 'a standalone name'
}

function Explain-Message([string]$text) {
    $lines = @()
    if ($text -match '^File (.+?) contains a reference to a "Launch Process" related API (.+?)!(\S+)') {
        $file = $Matches[1]; $dll = $Matches[2]; $api = $Matches[3]
        $lines += "  $file imports $api from $dll. The kit flags any import of a process-launch API; it does not mean the function is called."
        $found = Find-PackageFile $file
        if ($found) {
            $bytes = [System.IO.File]::ReadAllBytes($found.FullName)
            foreach ($hit in (Get-Occurrences $bytes $api | Select-Object -First 1)) {
                $lines += ('    at 0x{0:X} ({1}): {2}' -f $hit.Offset, $hit.Encoding, (Format-Context $bytes $hit.Offset $api.Length $hit.Step))
            }
        }
    } elseif ($text -match '^File (.+?) contains a blocked executable reference to "(.+?)"') {
        $file = $Matches[1]; $needle = $Matches[2]
        $found = Find-PackageFile $file
        if (-not $found) {
            $lines += "  $file is not in the package as named; nothing to show."
        } else {
            $bytes = [System.IO.File]::ReadAllBytes($found.FullName)
            $hits = @(Get-Occurrences $bytes $needle)
            if ($hits.Count -eq 0) {
                $lines += "  `"$needle`" does not occur verbatim in $file; the kit matched it ignoring case or in a form this script does not search."
            }
            foreach ($hit in $hits) {
                $kind = Get-Kind $bytes $hit $needle.Length
                $lines += ('  "{0}" in {1} at 0x{2:X} ({3}, {4}): {5}' -f $needle, $file, $hit.Offset, $hit.Encoding, $kind,
                    (Format-Context $bytes $hit.Offset $needle.Length $hit.Step))
            }
        }
    }
    return $lines
}

# --- Reading the report ------------------------------------------------------------------

[xml]$xml = Get-Content -Raw -Path $Report
$overall = $xml.REPORT.OVERALL_RESULT
$failed = @()
$optionalFailed = @()
$notPassed = @{}
$findings = @()
foreach ($test in $xml.SelectNodes('//TEST')) {
    $resultNode = $test.SelectSingleNode('RESULT')
    $result = if ($resultNode) { $resultNode.InnerText.Trim() } else { '' }
    $optional = $test.OPTIONAL -eq 'TRUE'
    $label = if ($optional) { "$result (optional)" } else { $result }
    if ($result -ne 'PASS') { $notPassed[$test.NAME] = $result }
    if ($result -eq 'FAIL' -and -not $optional) {
        $failed += $test
        Write-Host "::error title=WACK::$($test.NAME)"
    } elseif ($result -eq 'FAIL') {
        $optionalFailed += $test
        Write-Host "::warning title=WACK optional test failed::$($test.NAME)"
    } elseif ($result -eq 'WARNING') {
        Write-Host "::warning title=WACK::$($test.NAME)"
    }
    Write-Host ('{0,-18} {1}' -f $label, $test.NAME)
    foreach ($message in $test.SelectNodes('MESSAGES/MESSAGE')) {
        Write-Host "                   $($message.TEXT)"
        if ($result -ne 'PASS') {
            $explained = @(Explain-Message $message.TEXT)
            foreach ($line in $explained) { Write-Host "                 $line" }
            if ($explained.Count -gt 0) {
                $findings += "$($test.NAME): $($message.TEXT)"
                $findings += $explained
            }
        }
    }
}

if ($findings.Count -gt 0) {
    $findingsPath = "$Report.findings.txt"
    $findings | Set-Content -Path $findingsPath -Encoding utf8
    Write-Host "explanations written to $findingsPath"
}
if ($script:unpacked) { Remove-Item -Recurse -Force $script:unpacked -ErrorAction SilentlyContinue }

Write-Host "overall result: $overall (appcert exit $exit)"
if ($env:GITHUB_STEP_SUMMARY) {
    $summary = @("WACK on $(Split-Path -Leaf $Package): **$overall**")
    if ($optionalFailed.Count -gt 0) {
        $summary[0] += " (optional tests failed: $(($optionalFailed | ForEach-Object { $_.NAME }) -join ', '))"
    }
    if ($findings.Count -gt 0) {
        $summary += ''
        $summary += '```'
        $summary += $findings
        $summary += '```'
    }
    Add-Content -Path $env:GITHUB_STEP_SUMMARY -Value $summary -Encoding utf8
}

$missing = @($RequirePass | Where-Object { $notPassed.ContainsKey($_) })
foreach ($name in $missing) {
    Write-Host "::error title=WACK::$name is $($notPassed[$name]), and this run requires it to pass"
}
# The kit marks some tests optional. Their failures make the overall result WARNING,
# and the Store accepts a package with them; a required test failing is a FAIL overall
# and is what a submission would be refused for.
if ($failed.Count -gt 0 -or ($overall -ne 'PASS' -and $overall -ne 'WARNING')) {
    throw "the certification kit reported $overall with $($failed.Count) failing required test(s)"
}
if ($missing.Count -gt 0) {
    throw "required to pass and did not: $($missing -join ', ')"
}

<#
.SYNOPSIS
Runs the Windows App Certification Kit against a package and fails on a failed test.

.DESCRIPTION
The kit is the check Partner Center runs when a package is submitted, so a package that
passes it here is not refused for the same reasons there. It installs the package,
launches it, inspects the binaries and the manifest, and writes an XML report. The
package has to be signed by a certificate the machine trusts, or the install step fails
before any test runs.

Warnings do not fail the run; they are printed so that they are read. The Store accepts
a package with warnings, and some of them (a test that does not apply to a full-trust
application) cannot be avoided.

.PARAMETER Package
The signed .msix or .msixbundle.

.PARAMETER Report
Where the XML report is written.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Package,
    [Parameter(Mandatory)][string]$Report
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

[xml]$xml = Get-Content -Raw -Path $Report
$overall = $xml.REPORT.OVERALL_RESULT
$failed = @()
foreach ($test in $xml.SelectNodes('//TEST')) {
    $resultNode = $test.SelectSingleNode('RESULT')
    $result = if ($resultNode) { $resultNode.InnerText.Trim() } else { '' }
    $line = '{0,-8} {1}' -f $result, $test.NAME
    if ($result -eq 'FAIL') {
        $failed += $test
        Write-Host "::error title=WACK::$($test.NAME)"
    } elseif ($result -eq 'WARNING') {
        Write-Host "::warning title=WACK::$($test.NAME)"
    }
    Write-Host $line
    foreach ($message in $test.SelectNodes('MESSAGES/MESSAGE')) {
        Write-Host "         $($message.TEXT)"
    }
}
Write-Host "overall result: $overall (appcert exit $exit)"
if ($env:GITHUB_STEP_SUMMARY) {
    Add-Content -Path $env:GITHUB_STEP_SUMMARY -Value "WACK on $(Split-Path -Leaf $Package): **$overall**" -Encoding utf8
}
if ($overall -ne 'PASS' -and $overall -ne 'WARNING') {
    throw "the certification kit reported $overall with $($failed.Count) failing test(s)"
}

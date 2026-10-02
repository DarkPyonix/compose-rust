<#
.SYNOPSIS
Runs the Windows App Certification Kit against a package and fails on a failed test.

.DESCRIPTION
The kit is the check Partner Center runs when a package is submitted, so a package that
passes it here is not refused for the same reasons there. It installs the package,
launches it, inspects the binaries and the manifest, and writes an XML report. The
package has to be signed by a certificate the machine trusts, or the install step fails
before any test runs.

Warnings and failures of tests the kit marks optional do not fail the run; they are
printed, and listed in the step summary, so that they are read. The Store accepts a
package with them. A required test failing fails the run.

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
$optionalFailed = @()
foreach ($test in $xml.SelectNodes('//TEST')) {
    $resultNode = $test.SelectSingleNode('RESULT')
    $result = if ($resultNode) { $resultNode.InnerText.Trim() } else { '' }
    $optional = $test.OPTIONAL -eq 'TRUE'
    $label = if ($optional) { "$result (optional)" } else { $result }
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
    }
}
Write-Host "overall result: $overall (appcert exit $exit)"
if ($env:GITHUB_STEP_SUMMARY) {
    $line = "WACK on $(Split-Path -Leaf $Package): **$overall**"
    if ($optionalFailed.Count -gt 0) {
        $line += " (optional tests failed: $(($optionalFailed | ForEach-Object { $_.NAME }) -join ', '))"
    }
    Add-Content -Path $env:GITHUB_STEP_SUMMARY -Value $line -Encoding utf8
}
# The kit marks some tests optional. Their failures make the overall result WARNING,
# and the Store accepts a package with them; a required test failing is a FAIL overall
# and is what a submission would be refused for.
if ($failed.Count -gt 0 -or ($overall -ne 'PASS' -and $overall -ne 'WARNING')) {
    throw "the certification kit reported $overall with $($failed.Count) failing required test(s)"
}

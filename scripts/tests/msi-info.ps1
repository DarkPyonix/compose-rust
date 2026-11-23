<#
.SYNOPSIS
Reads an .msi's Property table and fails when the package is not what was packed.

.DESCRIPTION
The Windows counterpart of `dpkg-deb --info` and `rpm -qp --info`: it opens the installer database through the Windows Installer COM object, prints
every property, and checks that the product name and manufacturer are there and that the
version is the one given to `vpk pack`. Nothing is installed.

.PARAMETER Path
The .msi that `vpk pack --msi` wrote.

.PARAMETER Version
The version given to `vpk pack --packVersion`. Velopack may add a fourth part, so the
product version must start with this.

.PARAMETER ExpectedScope
PerUser or PerMachine, as given to `vpk pack --instLocation`. Velopack's WiX template
spells PerMachine as ALLUSERS=1 and PerUser as no ALLUSERS at all (seen in compose-rust
run 45009251902), so PerMachine must have ALLUSERS=1 and PerUser must not.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Path,
    [Parameter(Mandatory)][string]$Version,
    [string]$ExpectedScope = ''
)
$ErrorActionPreference = 'Stop'

$file = Get-Item -LiteralPath $Path
if ($file.Length -le 0) { throw "$($file.Name) is empty" }

$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $installer, @($file.FullName, 0))
$view = $database.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $database, @('SELECT `Property`, `Value` FROM `Property`'))
$view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null) | Out-Null
$properties = [ordered]@{}
while ($true) {
    $record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
    if ($null -eq $record) { break }
    $name = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @(1))
    $value = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @(2))
    $properties[$name] = $value
}
$view.GetType().InvokeMember('Close', 'InvokeMethod', $null, $view, $null) | Out-Null

Write-Host "$($file.Name) ($($file.Length) bytes), $($properties.Count) properties:"
foreach ($key in $properties.Keys) { Write-Host ('  {0,-24} {1}' -f $key, $properties[$key]) }
if ($ExpectedScope) {
    $scope = @('ALLUSERS', 'MSIINSTALLPERUSER') | ForEach-Object { "$_=$($properties[$_])" }
    Write-Host "packed with --instLocation $ExpectedScope; table says $($scope -join ' ')"
}

$problems = @()
foreach ($required in 'ProductName', 'Manufacturer', 'ProductVersion', 'ProductCode', 'UpgradeCode') {
    if (-not $properties[$required]) { $problems += "the table has no $required" }
}
if ($ExpectedScope -eq 'PerMachine' -and $properties['ALLUSERS'] -ne '1') {
    $problems += "packed PerMachine but ALLUSERS is '$($properties['ALLUSERS'])', not 1"
}
if ($ExpectedScope -eq 'PerUser' -and $properties['ALLUSERS'] -eq '1') {
    $problems += 'packed PerUser but ALLUSERS is 1 (a per-machine install)'
}
if ($properties['ProductVersion'] -and -not ([string]$properties['ProductVersion']).StartsWith($Version)) {
    $problems += "ProductVersion is $($properties['ProductVersion']), packed as $Version"
}
if ($problems.Count -gt 0) { throw "$($file.Name): $($problems -join '; ')" }
Write-Host "ok  $($file.Name) is a $($properties['ProductName']) $($properties['ProductVersion']) installer"
if ($env:GITHUB_STEP_SUMMARY) {
    Add-Content -Path $env:GITHUB_STEP_SUMMARY -Encoding utf8 -Value "MSI $($file.Name): $($properties['ProductName']) $($properties['ProductVersion']) by $($properties['Manufacturer'])"
}

<#
.SYNOPSIS
Installs a package from an App Installer feed, publishes a newer one to the same feed,
and checks that Windows sees the update and replaces the installed package with it.

.DESCRIPTION
This is the update path a downloaded application takes, exercised end to end on one
machine. The feed is served over HTTP from a directory this script fills, standing in
for the web server a release would publish to:

  1. the older bundle and its feed go on the share, and the feed is installed;
  2. the newer bundle goes on the share and its feed replaces the older one;
  3. the installed package is asked whether an update is available, the question
     Windows asks on every launch, and has to answer yes;
  4. the feed is installed again, which is what Windows does with that answer, and the
     installed package has to be the newer version, with the same identity, alone.

The Store channel cannot be exercised here: only the Store delivers its updates. The
property it relies on is the one step 4 checks, that a package with the same name and
publisher and a greater version replaces the installed one.

Must run in Windows PowerShell 5.1, which can call the Windows Runtime directly.

.PARAMETER Older
Directory with the older .msixbundle and .appinstaller.

.PARAMETER Newer
Directory with the newer .msixbundle and .appinstaller.

.PARAMETER Serve
Directory the feed and bundles are served from.

.PARAMETER Port
The local port the feeds were written for (http://localhost:<Port>).

.PARAMETER Name
The package identity name.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Older,
    [Parameter(Mandatory)][string]$Newer,
    [Parameter(Mandatory)][string]$Serve,
    [Parameter(Mandatory)][int]$Port,
    [Parameter(Mandatory)][string]$Name
)
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Management.Deployment.PackageManager, Windows.Management.Deployment, ContentType = WindowsRuntime]
$null = [Windows.ApplicationModel.Package, Windows.ApplicationModel, ContentType = WindowsRuntime]
$asTaskGeneric = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1

function Wait-WinRt($operation, [Type]$resultType) {
    $task = $asTaskGeneric.MakeGenericMethod($resultType).Invoke($null, @($operation))
    $task.Wait() | Out-Null
    $task.Result
}

function Get-Installed {
    @(Get-AppxPackage -Name $Name)
}

function Publish-Feed([string]$from) {
    Copy-Item -Path (Join-Path $from '*.msixbundle') -Destination $Serve -Force
    Copy-Item -Path (Join-Path $from '*.appinstaller') -Destination $Serve -Force
}

New-Item -ItemType Directory -Force -Path $Serve | Out-Null
$python = (Get-Command python -ErrorAction Stop).Source
$server = Start-Process -FilePath $python -PassThru -WindowStyle Hidden `
    -ArgumentList '-m', 'http.server', "$Port", '--bind', '127.0.0.1', '--directory', $Serve
try {
    Start-Sleep -Seconds 2
    Get-Installed | Remove-AppxPackage

    Publish-Feed $Older
    $feedFile = Get-ChildItem -Path $Serve -Filter '*.appinstaller' | Select-Object -First 1
    $feed = "http://localhost:$Port/$($feedFile.Name)"
    Write-Host "installing from $feed"
    Add-AppxPackage -AppInstallerFile $feed
    $first = Get-Installed
    if ($first.Count -ne 1) { throw "expected one installed $Name, found $($first.Count)" }
    $firstVersion = [version]$first[0].Version
    Write-Host "installed $Name $firstVersion"

    Publish-Feed $Newer
    [xml]$newFeed = Get-Content -Raw -Path (Join-Path $Serve $feedFile.Name)
    $expected = [version]$newFeed.AppInstaller.MainBundle.Version
    if ($expected -le $firstVersion) { throw "the newer feed names $expected, not above $firstVersion" }

    $manager = New-Object Windows.Management.Deployment.PackageManager
    $package = $manager.FindPackagesForUser('') | Where-Object { $_.Id.Name -eq $Name } | Select-Object -First 1
    if (-not $package) { throw "the Windows Runtime does not see $Name" }
    $info = $package.GetAppInstallerInfo()
    if (-not $info) { throw "$Name was installed without its feed, so Windows would never look for an update" }
    Write-Host "the installed package watches $($info.Uri)"
    $availability = Wait-WinRt ($package.CheckUpdateAvailabilityAsync()) ([Windows.ApplicationModel.PackageUpdateAvailabilityResult])
    Write-Host "update availability: $($availability.Availability)"
    if ($availability.Availability -notin @('Available', 'Required')) {
        throw "Windows reports $($availability.Availability) after a newer bundle was published"
    }

    Add-AppxPackage -AppInstallerFile $feed
    $after = Get-Installed
    if ($after.Count -ne 1) { throw "expected the update to replace $Name, found $($after.Count) installed" }
    $afterVersion = [version]$after[0].Version
    if ($afterVersion -ne $expected) { throw "after the update $Name is $afterVersion, expected $expected" }
    if ($after[0].Publisher -ne $first[0].Publisher) { throw "the update changed the publisher" }
    Write-Host "updated $Name $firstVersion -> $afterVersion"
    if ($env:GITHUB_STEP_SUMMARY) {
        Add-Content -Path $env:GITHUB_STEP_SUMMARY -Value "App Installer update: $Name $firstVersion -> **$afterVersion**" -Encoding utf8
    }

    Get-Installed | Remove-AppxPackage
} finally {
    Stop-Process -Id $server.Id -ErrorAction SilentlyContinue
}

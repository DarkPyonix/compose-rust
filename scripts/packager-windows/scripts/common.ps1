<#
.SYNOPSIS
Helpers shared by the MSIX scripts: finding Windows SDK tools.
#>

function Find-SdkTool {
    param([Parameter(Mandatory)][string]$Name)
    if ($env:DXC_WINDOWS_SDK_BIN) {
        $candidate = Join-Path $env:DXC_WINDOWS_SDK_BIN $Name
        if (Test-Path $candidate) { return $candidate }
        throw "DXC_WINDOWS_SDK_BIN is set, and $candidate is not there"
    }
    $kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
    $found = Get-ChildItem -Path $kits -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending |
        ForEach-Object { Join-Path $_.FullName "x64\$Name" } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1
    if ($found) { return $found }
    $onPath = Get-Command $Name -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw "$Name was not found: install the Windows SDK, or set DXC_WINDOWS_SDK_BIN"
}

function Set-StepOutput {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][string]$Value)
    if ($env:GITHUB_OUTPUT) {
        Add-Content -Path $env:GITHUB_OUTPUT -Value "$Name=$Value" -Encoding utf8
    }
}

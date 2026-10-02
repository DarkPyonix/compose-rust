<#
.SYNOPSIS
Signs packages with SignTool.

.DESCRIPTION
Signs each file given with a PFX, using SHA-256 as MSIX requires. A bundle is signed as
a whole; the packages inside it do not need signatures of their own.

.PARAMETER Path
The .msix, .msixbundle or executable files to sign.

.PARAMETER Pfx
The certificate with its private key.

.PARAMETER Password
The PFX password.

.PARAMETER TimestampUrl
An RFC 3161 timestamp server. A release signature should be timestamped so that it
outlives the certificate; a test signature does not need to be.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string[]]$Path,
    [Parameter(Mandatory)][string]$Pfx,
    [Parameter(Mandatory)][string]$Password,
    [string]$TimestampUrl
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$signtool = Find-SdkTool 'signtool.exe'
foreach ($file in $Path) {
    $arguments = @('sign', '/fd', 'SHA256', '/f', $Pfx, '/p', $Password)
    if ($TimestampUrl) { $arguments += @('/tr', $TimestampUrl, '/td', 'SHA256') }
    $arguments += $file
    & $signtool @arguments
    if ($LASTEXITCODE -ne 0) { throw "signtool could not sign $file (exit $LASTEXITCODE)" }
    & $signtool verify /pa $file
    if ($LASTEXITCODE -ne 0) { throw "the signature on $file does not verify (exit $LASTEXITCODE)" }
}

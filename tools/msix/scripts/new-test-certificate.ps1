<#
.SYNOPSIS
Creates a throwaway code-signing certificate whose subject is the package publisher.

.DESCRIPTION
Windows installs a package only when its signature chains to a certificate the machine
trusts, and only when the certificate subject is exactly the Publisher in the manifest.
A release to the Store needs neither: Partner Center signs the package itself when it
takes it in. A test install and the certification kit need both, so this makes a
certificate that lives for a week, exports it, and with -Trust adds it to the machine's
TrustedPeople store.

Never use this for anything a person downloads. A self-signed certificate asks every
user to trust it by hand, and teaching people to do that is the problem signing exists
to stop.

.PARAMETER Publisher
The manifest's Publisher, for example CN=DarkPyonix.

.PARAMETER OutDir
Where test-signing.pfx and test-signing.cer are written.

.PARAMETER Trust
Also trust the certificate on this machine (needs an elevated shell).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Publisher,
    [Parameter(Mandatory)][string]$OutDir,
    [switch]$Trust
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$cert = New-SelfSignedCertificate -Type Custom -Subject $Publisher `
    -KeyUsage DigitalSignature -KeyAlgorithm RSA -KeyLength 2048 `
    -FriendlyName 'dioxus-compose MSIX test signing' `
    -CertStoreLocation 'Cert:\CurrentUser\My' `
    -NotAfter (Get-Date).AddDays(7) `
    -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3', '2.5.29.19={text}')

$password = [guid]::NewGuid().ToString('N')
if ($env:GITHUB_ACTIONS) { Write-Host "::add-mask::$password" }
$secure = ConvertTo-SecureString -String $password -Force -AsPlainText
$pfx = Join-Path $OutDir 'test-signing.pfx'
$cer = Join-Path $OutDir 'test-signing.cer'
Export-PfxCertificate -Cert $cert -FilePath $pfx -Password $secure | Out-Null
Export-Certificate -Cert $cert -FilePath $cer | Out-Null

if ($Trust) {
    Import-Certificate -FilePath $cer -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null
    Write-Host "trusted $($cert.Subject) ($($cert.Thumbprint)) on this machine"
}

Set-StepOutput -Name pfx -Value $pfx
Set-StepOutput -Name cer -Value $cer
Set-StepOutput -Name password -Value $password
Write-Host "certificate $($cert.Subject), thumbprint $($cert.Thumbprint)"
Write-Host "pfx $pfx"
Write-Host "cer $cer"

# Links the minimal C host to the Windows renderer and runs it.
#
# UNTESTED: authored on macOS. With -RequireClick, click the button before the 30 second
# timeout. The script fails unless the Host receives a dispatch event and run returns zero.
[CmdletBinding()]
param(
    [switch]$RequireClick
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# Every native command in this script goes through here. With ErrorActionPreference set to
# Stop, PowerShell turns anything a native program writes to stderr into a terminating
# error, so a single warning line kills the script and reports NativeCommandError with the
# pipeline as the culprit. The renderer prints two JDK warnings about restricted native
# access on every start, which is how a working smoke test came back red.
function Invoke-Native {
    param([scriptblock]$Command)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { & $Command } finally { $ErrorActionPreference = $previous }
}

function Fail([string]$Message) {
    [Console]::Error.WriteLine("error: $Message")
    exit 1
}

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    Fail "smoke-test-windows.ps1 must run on Windows x64"
}

$ScriptsDir = $PSScriptRoot
$NativeDir = Split-Path -Parent $ScriptsDir
$ProjectDir = Split-Path -Parent $NativeDir
$BuildDir = Join-Path $ProjectDir "build\native-image"
$BinDir = Join-Path $BuildDir "dist\bin"
$ObjDir = Join-Path $BuildDir "obj-windows"
$LibraryName = "libdioxus_compose_renderer"
$ImportLibrary = Join-Path $BinDir "$LibraryName.lib"
$SmokeSource = Join-Path $NativeDir "c\smoke_host.c"
$SmokeHost = Join-Path $ObjDir "smoke_host.exe"
$SmokeLog = Join-Path $BuildDir "smoke-windows.log"

if (-not (Test-Path -LiteralPath $ImportLibrary -PathType Leaf)) {
    Fail "$ImportLibrary not found; run build-native-windows.ps1 first"
}
$VsWhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path -LiteralPath $VsWhere -PathType Leaf)) {
    Fail "vswhere.exe is missing; install Visual Studio 2022 Build Tools with Desktop development with C++"
}
$VsInstall = (& $VsWhere -latest -products * `
    -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
    -property installationPath | Select-Object -First 1)
$VsDevCmd = Join-Path $VsInstall "Common7\Tools\VsDevCmd.bat"
if ([string]::IsNullOrWhiteSpace($VsInstall) -or
    -not (Test-Path -LiteralPath $VsDevCmd -PathType Leaf)) {
    Fail "MSVC x64 build tools are missing; install Desktop development with C++"
}
$DevEnvironment = & $env:ComSpec /d /s /c "`"$VsDevCmd`" -no_logo -arch=x64 -host_arch=x64 >nul && set"
if ($LASTEXITCODE -ne 0) {
    Fail "VsDevCmd.bat failed to initialise the x64 compiler environment"
}
foreach ($line in $DevEnvironment) {
    $separator = $line.IndexOf("=")
    if ($separator -gt 0) {
        [Environment]::SetEnvironmentVariable(
            $line.Substring(0, $separator),
            $line.Substring($separator + 1),
            "Process"
        )
    }
}
if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) {
    Fail "cl.exe is unavailable after running $VsDevCmd"
}
New-Item -ItemType Directory -Force -Path $ObjDir | Out-Null

# __declspec(dllexport) in smoke_host.c is required: the renderer DLL forwards its existing
# host imports through GetProcAddress(GetModuleHandle(NULL), ...).
Invoke-Native { & cl.exe /nologo /O2 "/Fe$SmokeHost" $SmokeSource "/link" "/LIBPATH:$BinDir" "$LibraryName.lib" }
if ($LASTEXITCODE -ne 0) {
    Fail "MSVC could not link the smoke host"
}

$OldPath = $env:PATH
$OldAutoExit = $env:DIOXUS_COMPOSE_AUTOEXIT_MS
try {
    $env:PATH = "$BinDir;$OldPath"
    $env:DIOXUS_COMPOSE_AUTOEXIT_MS = if ($RequireClick) { "30000" } else { "5000" }
    # ToString first: merging stderr makes each of those lines an ErrorRecord, and letting
    # Tee-Object format one writes the whole PowerShell error block into the log instead of
    # the line the renderer actually printed, which the checks below then fail to match.
    Invoke-Native { & $SmokeHost 2>&1 } |
        ForEach-Object { $_.ToString() } |
        Tee-Object -FilePath $SmokeLog
    $ExitCode = $LASTEXITCODE
} finally {
    $env:PATH = $OldPath
    $env:DIOXUS_COMPOSE_AUTOEXIT_MS = $OldAutoExit
}
if ($ExitCode -ne 0) {
    Fail "smoke host returned $ExitCode; see $SmokeLog"
}
$Output = Get-Content -LiteralPath $SmokeLog -Raw
if ($Output -notmatch "dioxus_compose_renderer_run returned 0") {
    Fail "renderer did not report a zero return; see $SmokeLog"
}
if ($RequireClick -and $Output -notmatch "dioxus_compose_host_dispatch_event: click") {
    Fail "no click reached the Host; click the button before the 30 second timeout and rerun"
}

Write-Host "ok    Windows renderer smoke test"
if ($RequireClick) {
    Write-Host "ok    Windows mouse input reached the Host"
}

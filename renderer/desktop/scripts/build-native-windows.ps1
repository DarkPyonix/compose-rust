# Builds and stages the renderer as a Windows x64 native shared library.
#
# UNTESTED: this script was authored on macOS and has never executed in this repository.
# Verify on Windows from the repository root with:
#   powershell -ExecutionPolicy Bypass -File .\renderer\desktop\scripts\build-native-windows.ps1
#   powershell -ExecutionPolicy Bypass -File .\renderer\desktop\scripts\smoke-test-windows.ps1 -RequireClick
#
# Distribution layout:
#   build\native-image\dist\
#     bin\libcompose_rust_renderer.dll  the renderer, and the JDK libraries Native Image stages
#     bin\skiko-windows-x64.dll           Skia loaded by Skiko by path
#     bin\jawt.dll                        a stub skiko's library can load (win32_jawt_stub.c)
#     bin\icudtl.dat                      Skia ICU data
#
# The window is the Win32 one this renderer makes itself (win32_window.c), so the image
# carries no Java toolkit, as the macOS and Linux images do not: no awt.dll beside it, no
# input method or accessibility registration, no preserved java.desktop module, and Compose's
# main-thread work on the window's own dispatcher rather than Swing's. Text input, the
# clipboard and UI Automation are the window's own C.
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# Runs a native command without letting its progress output count as a failure.
#
# With $ErrorActionPreference set to Stop, PowerShell turns anything a native command
# writes to stderr into a terminating error, even when the command exits 0. The Kotlin CLI
# reports its downloads there, and native-image reports its progress there, so both looked
# like failures on a machine that had never run them before. Exit codes are the only
# signal worth trusting here, and every caller already checks $LASTEXITCODE.
function Invoke-Native {
    param([scriptblock]$Command)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { & $Command } finally { $ErrorActionPreference = $previous }
}

function Fail([string]$Message, [string[]]$Hints = @()) {
    [Console]::Error.WriteLine("error: $Message")
    foreach ($hint in $Hints) {
        [Console]::Error.WriteLine("       $hint")
    }
    exit 1
}

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    Fail "build-native-windows.ps1 must run on Windows x64" @(
        "native-image does not cross-compile; use a Windows x64 machine or runner."
    )
}
if (-not [Environment]::Is64BitOperatingSystem -or -not [Environment]::Is64BitProcess) {
    Fail "Windows x64 and a 64-bit PowerShell process are required" @(
        "Open x64 PowerShell, not the x86 shell."
    )
}

$ScriptsDir = $PSScriptRoot
$NativeDir = Split-Path -Parent $ScriptsDir
$ProjectDir = Split-Path -Parent $NativeDir
$BuildDir = Join-Path $ProjectDir "build\native-image"
$DistDir = Join-Path $BuildDir "dist"
$BinDir = Join-Path $DistDir "bin"
$LibDir = Join-Path $DistDir "lib"
$ObjDir = Join-Path $BuildDir "obj-windows"
$MetadataDir = Join-Path $ScriptsDir "windows-metadata"
$LibraryName = "libcompose_rust_renderer"
$RendererSource = Join-Path $NativeDir "c\renderer_entry.c"
# The window and the toasts are the Compose fork's extended/window/windows-shared, fetched at the
# commit build-compose.sh pins (WINDOW_REVISION): the same C the Kotlin/Native renderer compiles.
# fetch-fork-window.sh puts that commit in .scratch\fork-window\<revision>; the path is built
# here rather than read from its output, which is a Git Bash path cl.exe cannot open.
$RepoDir = Split-Path -Parent $ProjectDir
$ComposeScript = Join-Path $ProjectDir "scripts\build-compose.sh"
$WindowRevision = Select-String -LiteralPath $ComposeScript -Pattern '^WINDOW_REVISION="([0-9a-f]{40})"$' |
    ForEach-Object { $_.Matches[0].Groups[1].Value } | Select-Object -First 1
if (-not $WindowRevision) {
    Fail "$ComposeScript names no 40 character WINDOW_REVISION"
}
& bash ((Join-Path $RepoDir "scripts\fetch-fork-window.sh") -replace '\\', '/') | Out-Null
if ($LASTEXITCODE -ne 0) {
    Fail "could not fetch the window modules from the Compose fork" @(
        "scripts/fetch-fork-window.sh fetches extended/window at the pinned WINDOW_REVISION; it needs git and the network."
    )
}
$ForkWindowDir = Join-Path $RepoDir ".scratch\fork-window\$WindowRevision"
$WindowCDir = Join-Path $ForkWindowDir "extended\window\windows-shared\c"
$WindowSource = Join-Path $WindowCDir "win32_window.c"
$NotificationsSource = Join-Path $WindowCDir "win32_notifications.c"
$KotlinWrapper = Join-Path $ProjectDir "kotlin.bat"
$ClasspathFile = Join-Path $BuildDir "classpath-windows.txt"
$JvmLog = Join-Path $BuildDir "jvm-run-windows.log"

if (-not (Test-Path -LiteralPath $RendererSource -PathType Leaf)) {
    Fail "missing $RendererSource"
}
if (-not (Test-Path -LiteralPath $WindowSource -PathType Leaf)) {
    Fail "missing $WindowSource"
}
if (-not (Test-Path -LiteralPath $KotlinWrapper -PathType Leaf)) {
    Fail "missing $KotlinWrapper" @(
        "The checked-in Kotlin Toolchain wrapper is required; no separate Gradle install is used."
    )
}
# The desktop module depends on the Compose fork's window modules, published to the local Maven
# repository from the commit build-compose.sh pins. The Windows window's C is compiled from the
# fetched checkout above, so only the shared logic and the other desktops' wrappers are needed.
# The Compose fork's desktop modules and its skiko-awt are asked for by versions only the fork
# publishes, to the local Maven repository. Resolved before they are there, the toolchain records
# the failed download in its own cache and keeps failing after they are published, so this checks
# first, before anything runs the toolchain, and stops with the command that publishes them.
$RequireForkArtifacts = Join-Path $ProjectDir "scripts\require-fork-artifacts.sh"
& bash $RequireForkArtifacts desktop
if ($LASTEXITCODE -ne 0) {
    Fail "the Compose fork's desktop modules are not in the local Maven repository" @(
        "require-fork-artifacts.sh printed what is missing and the command that publishes it."
    )
}
$PublishWindow = Join-Path $ProjectDir "scripts\publish-window.sh"
& bash $PublishWindow
if ($LASTEXITCODE -ne 0) {
    Fail "could not publish the window modules from the Compose fork"
}
# This carries the whole verified Compose desktop AWT stack, not just the sun.awt.windows
# classes. An earlier Windows-only selection kept the entries whose names mention Windows and
# dropped the rest, which removed the JNI registration of java.awt.Toolkit.getDefaultToolkit.
# Toolkit.initIDs looks that method up through JNI before any window exists, so the image
# built and then died at startup with NoSuchMethodError on a method the JDK plainly has.
# The reference project hit the identical error with no metadata at all and fixed it with
# this bundle. Entries naming classes this checkout's newer Compose and Skiko no longer have
# are left unresolved by native-image rather than failing the build.
#
# Two directories because native-image documents a configuration directory as holding either
# reachability-metadata.json or the older split files, not both, and the resource and bundle
# declarations are still in the older form.
$ReachabilityMetadata = Join-Path $MetadataDir "reachability-metadata.json"
$ResourceMetadataDir = Join-Path $MetadataDir "resources"
$ResourceMetadata = Join-Path $ResourceMetadataDir "resource-config.json"
$MetadataEvidence = Join-Path $MetadataDir "evidence.json"
if (-not (Test-Path -LiteralPath $ReachabilityMetadata -PathType Leaf) -or
    -not (Test-Path -LiteralPath $ResourceMetadata -PathType Leaf) -or
    -not (Test-Path -LiteralPath $MetadataEvidence -PathType Leaf)) {
    Fail "Windows GraalVM 25 reachability metadata is missing from $MetadataDir" @(
        "Do not replace it with an unattended tracing-agent run.",
        "The agent records only exercised paths and can produce an image that ignores clicks."
    )
}

$GraalHome = $env:GRAALVM_HOME
if ([string]::IsNullOrWhiteSpace($GraalHome)) {
    $GraalHome = $env:JAVA_HOME
}
if ([string]::IsNullOrWhiteSpace($GraalHome)) {
    Fail "GRAALVM_HOME is not set" @(
        "Install upstream GraalVM for JDK 25 with native-image, then set GRAALVM_HOME.",
        "Liberica NIK is not required on Windows because upstream GraalVM supports AWT there."
    )
}
if (-not (Test-Path -LiteralPath $GraalHome -PathType Container)) {
    Fail "GRAALVM_HOME=$GraalHome does not exist"
}
$NativeImage = Join-Path $GraalHome "bin\native-image.cmd"
if (-not (Test-Path -LiteralPath $NativeImage -PathType Leaf)) {
    $NativeImage = Join-Path $GraalHome "bin\native-image.exe"
}
if (-not (Test-Path -LiteralPath $NativeImage -PathType Leaf)) {
    Fail "GRAALVM_HOME=$GraalHome has no bin\native-image.cmd or bin\native-image.exe" @(
        "Install GraalVM for JDK 25, not a stock JDK."
    )
}
$VersionText = (& $NativeImage --version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $VersionText -notmatch "native-image 25\.") {
    Fail "the Windows metadata is pinned to GraalVM 25, but native-image reported: $($VersionText.Trim())" @(
        "Use GraalVM for JDK 25 or recollect and review a version-matched metadata bundle."
    )
}

$VsWhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path -LiteralPath $VsWhere -PathType Leaf)) {
    Fail "Visual Studio Installer's vswhere.exe was not found" @(
        "Install Visual Studio 2022 Build Tools with Desktop development with C++."
    )
}
$VsInstall = (& $VsWhere -latest -products * `
    -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
    -property installationPath | Select-Object -First 1)
if ([string]::IsNullOrWhiteSpace($VsInstall)) {
    Fail "MSVC x64 build tools were not found" @(
        "Install Visual Studio 2022 Build Tools with Desktop development with C++."
    )
}
$VsDevCmd = Join-Path $VsInstall "Common7\Tools\VsDevCmd.bat"
if (-not (Test-Path -LiteralPath $VsDevCmd -PathType Leaf)) {
    Fail "missing $VsDevCmd"
}
$WindowsSdkIncludes = Join-Path ${env:ProgramFiles(x86)} "Windows Kits\10\Include"
if (-not (Test-Path -LiteralPath $WindowsSdkIncludes -PathType Container)) {
    Fail "Windows 10/11 SDK headers were not found" @(
        "Add a Windows SDK through the Visual Studio Installer."
    )
}

# Import the x64 developer environment so both our C compile and native-image use the same
# MSVC and Windows SDK. Recent native-image can locate MSVC itself, but cl.exe cannot compile
# renderer_entry.c from a plain PowerShell session without INCLUDE and LIB.
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
    Fail "cl.exe is still unavailable after running $VsDevCmd"
}

New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
$OldJavaHome = $env:JAVA_HOME
$OldGraalHome = $env:GRAALVM_HOME
$OldAutoExit = $env:COMPOSE_RUST_AUTOEXIT_MS
try {
    $env:JAVA_HOME = $GraalHome
    $env:GRAALVM_HOME = $GraalHome
    $env:COMPOSE_RUST_AUTOEXIT_MS = "1"
    # The Kotlin CLI looks for project.yaml in the working directory and its parents, so it
    # has to be run from the Amper project rather than from wherever the build was started.
    # Pointing at the wrapper by full path is not enough: it found no project and said so.
    Push-Location $ProjectDir
    try {
        # The JVM prints its settings on stderr. `2>&1` turns each of those lines into an
        # ErrorRecord, and Tee-Object writes objects through PowerShell's formatter, which
        # is not the text the JVM emitted: the classpath lines came out unparseable. Force
        # every record back to its own string and write the file directly.
        $probe = Invoke-Native {
            & $KotlinWrapper run -m desktop --no-compose-hot-reload `
                "--jvm-args=-XshowSettings:properties" 2>&1
        } | ForEach-Object { $_.ToString() }
        Set-Content -LiteralPath $JvmLog -Value $probe -Encoding UTF8
        $probe | Write-Host
    } finally {
        Pop-Location
    }
    if ($LASTEXITCODE -ne 0) {
        Fail "the JVM classpath probe failed" @(
            "See $JvmLog",
            "The Kotlin CLI must run from $ProjectDir, which holds project.yaml."
        )
    }
} finally {
    $env:JAVA_HOME = $OldJavaHome
    $env:GRAALVM_HOME = $OldGraalHome
    $env:COMPOSE_RUST_AUTOEXIT_MS = $OldAutoExit
}

$ClasspathEntries = [System.Collections.Generic.List[string]]::new()
$Collecting = $false
foreach ($line in Get-Content -LiteralPath $JvmLog) {
    if ($line -match '^\s*java\.class\.path = (.*)$') {
        $ClasspathEntries.Add($Matches[1])
        $Collecting = $true
        continue
    }
    if ($Collecting -and $line -match '^\s{8,}(\S.*)$') {
        $ClasspathEntries.Add($Matches[1])
        continue
    }
    if ($Collecting) {
        break
    }
}
# skiko's native half for every desktop system is on the JVM class path (desktop\module.yaml
# names them all, because the toolchain can pick the system only for JetBrains' own desktop
# module). The image takes this system's alone, as it did when the toolchain picked it.
$ClasspathEntries = [System.Collections.Generic.List[string]]@($ClasspathEntries | Where-Object {
    $leaf = Split-Path -Leaf $_
    -not ($leaf -like "skiko-awt-runtime-*.jar") -or ($leaf -like "skiko-awt-runtime-windows-x64-*.jar")
})
# Swing's coroutine provider is left out of the image. It is a main dispatcher that wakes the
# Java toolkit, and the window here answers `Dispatchers.Main` itself (FrameMainDispatcher).
# With the provider on the class path it is still found, and everything it reaches comes with it.
$ClasspathEntries = [System.Collections.Generic.List[string]]@(
    $ClasspathEntries | Where-Object { (Split-Path -Leaf $_) -notlike "kotlinx-coroutines-swing*" }
)
$Classpath = $ClasspathEntries -join [IO.Path]::PathSeparator
if ([string]::IsNullOrWhiteSpace($Classpath)) {
    # Show what was actually captured. Reading this from a log the build then deletes, on
    # a machine nobody has, is how the previous two failures here cost a round trip each.
    [Console]::Error.WriteLine("--- first 40 lines of $JvmLog ---")
    Get-Content -LiteralPath $JvmLog -TotalCount 40 |
        ForEach-Object { [Console]::Error.WriteLine($_) }
    [Console]::Error.WriteLine("--- end ---")
    Fail "could not read java.class.path from $JvmLog" @(
        "The JVM prints its settings on stderr, so the capture has to keep them as text.",
        "A line should read '    java.class.path = <first entry>' with the rest indented."
    )
}
Set-Content -LiteralPath $ClasspathFile -Value $Classpath -NoNewline

Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $DistDir, $ObjDir
New-Item -ItemType Directory -Force -Path $BinDir, $LibDir, $ObjDir | Out-Null
$RendererObject = Join-Path $ObjDir "renderer_entry.obj"
Invoke-Native { & cl.exe /nologo /c /O2 /std:c11 "/Fo$RendererObject" $RendererSource }
if ($LASTEXITCODE -ne 0) {
    Fail "MSVC could not compile $RendererSource"
}

# The window the renderer opens for itself, with its Direct3D 12 swapchain. Compiled as
# C11 like the shim beside it; the Direct3D and DXGI interfaces are reached through the
# macros the Windows SDK provides for C, so no part of this needs a C++ compiler.
$WindowObject = Join-Path $ObjDir "win32_window.obj"
Invoke-Native { & cl.exe /nologo /c /O2 /std:c11 "/Fo$WindowObject" $WindowSource }
if ($LASTEXITCODE -ne 0) {
    Fail "MSVC could not compile $WindowSource"
}

# Toasts, through the Windows Runtime's notification manager. C11 like the two above: the
# runtime interfaces it calls are declared in the file itself, so it needs no C++ compiler
# and brings no C++ runtime with it.
$NotificationsObject = Join-Path $ObjDir "win32_notifications.obj"
Invoke-Native { & cl.exe /nologo /c /O2 /std:c11 "/Fo$NotificationsObject" $NotificationsSource }
if ($LASTEXITCODE -ne 0) {
    Fail "MSVC could not compile $NotificationsSource"
}

# PE/COFF requires the Host boundary to resolve at DLL link time. renderer_entry.obj supplies
# forwarding definitions that use GetProcAddress on the host executable. Only the two public
# Renderer functions are exported from the DLL.
$NativeImageArgs = @(
    "--shared",
    "-cp", $Classpath,
    "-o", $LibraryName,
    "--no-fallback",
    # The address the window calls to have a frame drawn while its edge is being dragged.
    # It is an entry point of this image taken as a C function pointer, and that address is
    # written in while the image is built: the object holding it has to be in the image for
    # there to be anywhere to write it. A class initialised at run time makes its
    # CEntryPointLiteral after the build is over, and what it then hands the window is a
    # null address, which is a drag that draws nothing and says nothing about why.
    "--initialize-at-build-time=org.thisisthepy.compose.window.graalvm.windows.Win32DrawCallback",
    # The toolkit switched off where the fork's desktop code and this renderer would name it:
    # no toolkit window (ToolkitWindow), no java.awt in Compose's desktop code (the fork's
    # ExtendedAwt and skiko's AwtSwitch), and the window's own clipboard.
    "-Ddxc.toolkit.window=false",
    "-Dcompose.awt=false",
    "-Ddxc.awt.clipboard=false",
    "-Djava.awt.headless=false",
    "-H:IncludeLocales=en,ko",
    "-Os",
    # The call tree, which scripts/tests/renderer/linux-image-toolkit-boundary.test.sh reads
    # for any frame of application code that calls into the toolkit. Written to bin\reports.
    "-H:+PrintAnalysisCallTree",
    "-H:PrintAnalysisCallTreeType=TXT",
    # The heap the macOS image has (build-native.sh), for the same reason: without a cap
    # Substrate VM sizes the heap from the machine's physical memory, and the process grew
    # to hundreds of MB on Windows against tens on macOS. A run can still raise it with
    # -Xmx (see DXC_SVM_OPTIONS in renderer_entry.c).
    "-R:MaxHeapSize=64m",
    "-R:MaxHeapFree=4m",
    "-R:MaximumYoungGenerationSizePercent=25",
    "-H:+UnlockExperimentalVMOptions",
    "-H:ReportAnalysisForbiddenType=java.awt.Toolkit",
    "-H:ReportAnalysisForbiddenType=java.awt.Component",
    # Only the resources: the toolkit's reachability registrations (windows-metadata's
    # reachability-metadata.json and toolkit-metadata) are for the development shell on a JVM.
    "-H:ConfigurationFileDirectories=$ResourceMetadataDir",
    "-H:NativeLinkerOption=$RendererObject",
    "-H:NativeLinkerOption=$WindowObject",
    "-H:NativeLinkerOption=$NotificationsObject",
    # Named rather than left to the linker. Direct3D and DXGI resolve nowhere else, and
    # dxguid carries the interface identifiers that C code has to name as values because
    # it cannot ask for them the way C++ does. user32 is where the window, its messages
    # and the per-monitor DPI calls live. UI Automation and BSTRs have their own
    # libraries, and imm32 supplies composition strings and where to put the candidates.
    "-H:NativeLinkerOption=d3d12.lib",
    "-H:NativeLinkerOption=dxgi.lib",
    "-H:NativeLinkerOption=dxguid.lib",
    "-H:NativeLinkerOption=user32.lib",
    # GetDeviceCaps, which the window's scale falls back to where per-monitor DPI is not
    # available.
    "-H:NativeLinkerOption=gdi32.lib",
    "-H:NativeLinkerOption=uiautomationcore.lib",
    "-H:NativeLinkerOption=oleaut32.lib",
    "-H:NativeLinkerOption=imm32.lib",
    # The toast manager and the strings it takes are the Windows Runtime's; the shortcut a
    # toast's identity is read from is the shell's, reached through COM.
    "-H:NativeLinkerOption=runtimeobject.lib",
    "-H:NativeLinkerOption=ole32.lib",
    "-H:NativeLinkerOption=shell32.lib",
    "-H:NativeLinkerOption=/EXPORT:compose_rust_renderer_run",
    "-H:NativeLinkerOption=/EXPORT:compose_rust_renderer_request_frame",
    "-H:NativeLinkerOption=/EXPORT:compose_rust_renderer_measure"
)
# Through an argument file, not the command line. The runtime classpath alone is tens of
# kilobytes of Maven cache paths and Windows caps a command line at 32767 characters, so
# passing it directly fails with "The command line is too long." before native-image runs.
# Java argument files treat a backslash inside quotes as an escape, so the paths go in with
# forward slashes, which every Windows API accepts.
$ArgumentFile = Join-Path $BuildDir "native-image-args.txt"
Set-Content -Path $ArgumentFile -Encoding ASCII -Value (
    $NativeImageArgs | ForEach-Object {
        $argument = $_ -replace '\\', '/'
        if ($argument -match '\s') { '"' + $argument + '"' } else { $argument }
    }
)

Push-Location $BinDir
try {
    Invoke-Native { & $NativeImage "@$ArgumentFile" }
    if ($LASTEXITCODE -ne 0) {
        Fail "native-image failed to build the Windows renderer"
    }
} finally {
    Pop-Location
}

$SkikoJar = $ClasspathEntries | Where-Object {
    (Split-Path -Leaf $_) -like "skiko-awt-runtime-windows-x64-*.jar"
} | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($SkikoJar) -or -not (Test-Path -LiteralPath $SkikoJar)) {
    Fail "no skiko-awt-runtime-windows-x64 jar was found on the runtime classpath" @(
        "Check $ClasspathFile and the compose dependency in native\module.yaml."
    )
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Archive = [IO.Compression.ZipFile]::OpenRead($SkikoJar)
try {
    foreach ($Name in @("skiko-windows-x64.dll", "icudtl.dat")) {
        $Entry = $Archive.Entries | Where-Object { $_.Name -eq $Name } | Select-Object -First 1
        if ($null -eq $Entry) {
            Fail "$Name is missing from $SkikoJar"
        }
        [IO.Compression.ZipFileExtensions]::ExtractToFile(
            $Entry, (Join-Path $BinDir $Name), $true
        )
    }
} finally {
    $Archive.Dispose()
}

# Native Image writes the JDK's desktop libraries beside an image whenever a java.awt class is
# reachable, and Compose's and Skiko's desktop classes name a few that nothing runs here. They
# are removed, so that an image that does reach the toolkit at run time fails at once with a
# missing library instead of quietly loading it. The smoke test is what proves nothing does.
foreach ($Name in @("awt.dll", "fontmanager.dll", "javaaccessbridge.dll", "javajpeg.dll", "lcms.dll", "jawt.dll")) {
    Remove-Item -LiteralPath (Join-Path $BinDir $Name) -Force -ErrorAction SilentlyContinue
}

# skiko's library imports JAWT_GetAWT. Windows refuses to load a library whose imports it
# cannot find, so when it does, a stub jawt.dll that answers "no toolkit" goes beside it.
$SkikoDependents = (Invoke-Native { & dumpbin.exe /nologo /dependents (Join-Path $BinDir "skiko-windows-x64.dll") }) -join "`n"
Write-Host "skiko-windows-x64.dll imports:"
Write-Host $SkikoDependents
if ($SkikoDependents -match "(?im)^\s*jawt\.dll\s*$") {
    $StubSource = Join-Path $NativeDir "c\win32_jawt_stub.c"
    Invoke-Native { & cl.exe /nologo /O2 /LD "/Fo$ObjDir\" "/Fe$(Join-Path $BinDir 'jawt.dll')" $StubSource }
    if ($LASTEXITCODE -ne 0) {
        Fail "MSVC could not build the jawt.dll stub from $StubSource"
    }
    Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $BinDir "jawt.lib"), (Join-Path $BinDir "jawt.exp")
}

$RuntimeFiles = @("$LibraryName.dll", "$LibraryName.lib", "skiko-windows-x64.dll", "icudtl.dat")
foreach ($Name in $RuntimeFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $BinDir $Name) -PathType Leaf)) {
        Fail "the build did not stage $Name in $BinDir" @(
            "This is unverified output naming. Record the directory listing before changing the script."
        )
    }
}
Write-Host "staged in ${BinDir}:"
Get-ChildItem -LiteralPath $BinDir -File | ForEach-Object { Write-Host ("  {0,12:N0}  {1}" -f $_.Length, $_.Name) }

# The schema this renderer was generated from, written beside it, so a build script that
# pairs a program with this distribution can see the two disagree before the program runs.
# The handshake catches it as well, but by then the window is open and empty, which is what
# a white window on Windows turned out to be.
$SchemaHashLine = Select-String -Path (Join-Path $ProjectDir "desktop\src\protocol\Protocol.gen.kt") `
    -Pattern 'const val SCHEMA_HASH: Long = (-?\d+)' | Select-Object -First 1
if (-not $SchemaHashLine) {
    Fail "no SCHEMA_HASH in the generated protocol" @("Run cargo run --bin codegen.")
}
$SchemaHash = [int64]$SchemaHashLine.Matches[0].Groups[1].Value
# Formatted as unsigned, because the hash fills all 64 bits and the other side writes it
# the same way.
"0x{0:x16}" -f $SchemaHash | Set-Content -Path (Join-Path $DistDir "schema-hash.txt") -Encoding ASCII

# Keep headers and diagnostic reports out of the runtime bin directory.
$IncludeDir = Join-Path $DistDir "include"
New-Item -ItemType Directory -Force -Path $IncludeDir | Out-Null
Get-ChildItem -LiteralPath $BinDir -Filter "*.h" -File | Move-Item -Destination $IncludeDir
Get-ChildItem -LiteralPath $BinDir -Filter "*.md" -File | Remove-Item

Get-ChildItem -LiteralPath $BinDir, $LibDir
Write-Host "UNTESTED build complete. Verify the window and click path with:"
Write-Host "  powershell -ExecutionPolicy Bypass -File $ScriptsDir\smoke-test-windows.ps1 -RequireClick"

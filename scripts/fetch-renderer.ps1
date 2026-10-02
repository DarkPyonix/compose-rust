<#
.SYNOPSIS
Fetches a renderer built from this source tree and points the next build at it.

.DESCRIPTION
A renderer has to be built from the same schema as the crate it is paired with, and the
one a plain `cargo build` downloads is the one published with the last release. On a
checkout whose schema has moved, that is the wrong one: the handshake refuses it, no batch
ever arrives, and the window comes up empty.

There is no way around fetching one. This exists because every step of doing it by hand
has gone wrong at least once:

  - `gh run download` says nothing when the run has not finished, and leaves an empty
    directory that the build then refuses with a longer message about a missing library.
  - the renderer is chosen when the crate is compiled, not when the application runs,
    because Windows looks in the executable's own directory before anything on PATH. So
    the variable has to be set before `cargo build`, and `cargo clean -p dioxus-compose`
    has to run or the build script does not look again.

.PARAMETER Run
The workflow run to take it from. Defaults to the most recent successful one on this
branch.
#>
[CmdletBinding()]
param(
    [string]$Run,
    [string]$Destination = "renderer-win"
)

$ErrorActionPreference = 'Stop'

$repoRoot = $PSScriptRoot
while ($repoRoot -and -not (Test-Path (Join-Path $repoRoot 'Cargo.toml'))) {
    $repoRoot = Split-Path $repoRoot -Parent
}
Set-Location $repoRoot

if (-not $Run) {
    Write-Host "looking for the most recent renderer build"
    $Run = (gh run list --workflow=native-renderer.yml --status=success --limit 1 --json databaseId --jq '.[0].databaseId')
    if (-not $Run) { throw "no successful run of native-renderer.yml to take a renderer from" }
}

$status = gh run view $Run --json status --jq '.status'
if ($status -ne 'completed') {
    throw "run $Run is $status. Its artifacts do not exist yet, and downloading now would " +
        "leave an empty directory that the build refuses with a message about a missing library."
}

$target = Join-Path $repoRoot $Destination
if (Test-Path $target) { Remove-Item -Recurse -Force $target }
gh run download $Run -n renderer-windows-x64 -D $target

$bin = Join-Path $target "bin"
$library = Join-Path $bin "libdioxus_compose_renderer.dll"
if (-not (Test-Path $library)) {
    throw "no $library after downloading from run $Run. The artifact is named " +
        "renderer-windows-x64 and unpacks to bin\ and lib\; check that the run has one."
}

$hashFile = Join-Path $target "schema-hash.txt"
$crateHash = Get-Content (Join-Path $repoRoot "dioxus-compose\schema-hash.txt") -ErrorAction SilentlyContinue
if (Test-Path $hashFile) {
    $rendererHash = (Get-Content $hashFile).Trim()
    if ($crateHash -and $rendererHash -ne $crateHash.Trim()) {
        Write-Host ""
        Write-Host "this renderer was built from a different schema than this checkout."
        Write-Host "  renderer: $rendererHash"
        Write-Host "  crate:    $($crateHash.Trim())"
        Write-Host "The build will refuse it. Trigger a run on the current commit:"
        Write-Host "  gh workflow run native-renderer.yml --ref develop"
        throw "schema mismatch"
    }
    Write-Host "schema     $rendererHash, which matches this checkout"
} else {
    Write-Host "this renderer does not say which schema it was built from, so the build"
    Write-Host "cannot check. If the window comes up empty, that is the first thing to suspect."
}

Write-Host ""
Write-Host "renderer   $bin"
Write-Host ""
Write-Host "Now build against it. The variable has to be set before cargo runs, because the"
Write-Host "renderer is linked in rather than found at startup:"
Write-Host ""
Write-Host "  `$env:DIOXUS_COMPOSE_RENDERER_DIR = '$bin'"
Write-Host "  cargo clean -p dioxus-compose"
Write-Host "  cargo build --release -p sample-minimal --features dioxus-compose/native-renderer"
Write-Host "  .\target\release\sample-minimal.exe"

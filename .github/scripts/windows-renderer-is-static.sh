#!/usr/bin/env bash
# Usage: windows-renderer-is-static.sh <compose-rust-renderer-v<version>-windows-x64.tar.gz>
#
# Prints "windows_static=true" or "windows_static=false" for GITHUB_OUTPUT. true means the
# tarball is the Kotlin/Native static kit the crate's build script links on Windows: the
# archive libcompose_rust_renderer.a at the root, with the window and C runtime bridge
# (native/dxc-windows-native.lib) beside it, as package-static-renderer.sh writes it.
#
# Releases up to v0.0.7 carry the GraalVM DLL under that name instead. The build script
# cannot link that, so a sample build that took it would fail on every sample; false sends
# the build to building the static kit itself.
set -uo pipefail

tarball="${1:?usage: $0 <windows-x64 renderer tarball>}"

if ! names="$(tar -tzf "$tarball" 2>/dev/null)"; then
    echo "::warning title=Unreadable Windows renderer::$(basename "$tarball") is not a gzip tarball"
    echo "windows_static=false"
    exit 0
fi
names="$(sed 's#^\./##' <<<"$names")"

static=true
for file in libcompose_rust_renderer.a native/dxc-windows-native.lib; do
    grep -qxF "$file" <<<"$names" || { echo "$(basename "$tarball") has no $file" >&2; static=false; }
done
echo "windows_static=$static"

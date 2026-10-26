#!/usr/bin/env bash
# Usage: .github/scripts/package-static-renderer.sh <renderer build directory> <target> <version> <output directory>
#
# Packages a static renderer build as the release artifact the crate's build script
# downloads:
#
#   <output>/compose-rust-renderer-v<version>-<target>.tar.gz
#   <output>/compose-rust-renderer-v<version>-<target>.tar.gz.sha256
#
# and inside the tarball:
#
#   lib/libcompose_rust_renderer.a        what an application links
#   include/libcompose_rust_renderer_api.h
#   schema-hash.txt                         the schema the renderer was generated from
#   renderer.version         the crate version it is for
#
# windows-x64 is the kit root as build-windows.sh writes renderer/build/windows, which the
# Host's build script reads (compose-rust/build.rs), so nothing is under lib/ or include/:
#
#   libcompose_rust_renderer.a, libcompose_rust_renderer_api.h, schema-hash.txt,
#   renderer.version, and
#   gcc/*.a                                 the GCC runtime the renderer object carries
#   native/*.lib                            the Win32 window, the MinGW bridge, the embedded
#                                           ICU loader, and the bridge for a static UCRT
#   skiko/*.lib                             skiko's C++ half and Skia, MSVC
#
#   <renderer build directory>  what build-macos.sh or build-linux.sh wrote
#                               (renderer/build/macos, build/linux, ...)
#   <target>                    macos-aarch64, linux-x64, linux-arm64 or windows-x64
#   <version>                   the crate version, without a leading v
#
# The name, the checksum file and the version file are the ones build/renderer_dir.rs reads,
# so this is the one place they are written for a static renderer.
set -euo pipefail

if [[ $# -ne 4 ]]; then
    echo "usage: $0 <renderer build directory> <target> <version> <output directory>" >&2
    exit 2
fi

fail() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

build_dir="$1"
target="$2"
version="${3#v}"
out="$4"

case "$target" in
    macos-aarch64|linux-x64|linux-arm64|windows-x64) ;;
    *) fail "no static renderer is packaged for '$target'" "known: macos-aarch64, linux-x64, linux-arm64, windows-x64" ;;
esac
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+].*)?$ ]] || fail "'$3' is not a crate version"

archive="$build_dir/libcompose_rust_renderer.a"
header="$build_dir/libcompose_rust_renderer_api.h"
hash="$build_dir/schema-hash.txt"
for file in "$archive" "$header" "$hash"; do
    [[ -f "$file" ]] || fail "no $(basename "$file") in $build_dir" \
        "Build the renderer first: renderer/desktop/scripts/build-macos.sh or build-linux.sh."
done

# The pieces of the Windows kit beyond the archive, header and schema hash. Listed by
# name, so a kit that lost one fails here and not in an application's link.
windows_kit=(
    gcc/libstdc++.a gcc/libgcc.a gcc/libgcc_eh.a gcc/libwinpthread.a
    native/dxc-windows-native.lib native/dxc-windows-static-ucrt.lib
    skiko/skiko-bridges.lib skiko/skia.lib
)
if [[ "$target" == windows-x64 ]]; then
    for file in "${windows_kit[@]}"; do
        [[ -f "$build_dir/$file" ]] || fail "no $file in $build_dir" \
            "Build the renderer first: renderer/desktop/scripts/build-windows.sh writes the whole kit."
    done
fi

name="compose-rust-renderer-v$version-$target"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
staging="$out/$name.staging"
rm -rf "$staging"
if [[ "$target" == windows-x64 ]]; then
    mkdir -p "$staging"
    cp "$archive" "$header" "$staging/"
else
    mkdir -p "$staging/lib" "$staging/include"
    cp "$archive" "$staging/lib/"
    cp "$header" "$staging/include/"
fi
cp "$hash" "$staging/schema-hash.txt"
echo "$version" > "$staging/renderer.version"
if [[ "$target" == windows-x64 ]]; then
    for file in "${windows_kit[@]}"; do
        mkdir -p "$staging/$(dirname "$file")"
        cp "$build_dir/$file" "$staging/$file"
    done
    # Any further library the kit holds in those directories (skia ships several .lib files).
    for dir in gcc native skiko; do
        for file in "$build_dir/$dir"/*.a "$build_dir/$dir"/*.lib; do
            [[ -f "$file" && ! -e "$staging/$dir/$(basename "$file")" ]] || continue
            cp "$file" "$staging/$dir/"
        done
    done
fi

# COPYFILE_DISABLE keeps macOS tar from adding ._ resource fork entries to the archive.
COPYFILE_DISABLE=1 tar -czf "$out/$name.tar.gz" -C "$staging" .
rm -rf "$staging"

# From inside the output directory, so the checksum file names the artifact without a
# path, which is how the release's checksums read and how the build script reads them.
(
    cd "$out"
    if command -v shasum >/dev/null; then
        shasum -a 256 "$name.tar.gz" > "$name.tar.gz.sha256"
    else
        sha256sum "$name.tar.gz" > "$name.tar.gz.sha256"
    fi
    cat "$name.tar.gz.sha256"
)
ls -la "$out/$name.tar.gz"

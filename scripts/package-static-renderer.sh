#!/usr/bin/env bash
# Usage: scripts/package-static-renderer.sh <renderer build directory> <target> <version> <output directory>
#
# Packages a static renderer build as the release artifact the crate's build script
# downloads:
#
#   <output>/dioxus-compose-renderer-v<version>-<target>.tar.gz
#   <output>/dioxus-compose-renderer-v<version>-<target>.tar.gz.sha256
#
# and inside the tarball:
#
#   lib/libdioxus_compose_renderer.a        what an application links
#   include/libdioxus_compose_renderer_api.h
#   schema-hash.txt                         the schema the renderer was generated from
#   dioxus-compose-renderer.version         the crate version it is for
#
#   <renderer build directory>  what build-macos.sh or build-linux.sh wrote
#                               (dioxus-compose-renderer/build/macos, build/linux, ...)
#   <target>                    macos-aarch64, linux-x64 or linux-arm64
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
    macos-aarch64|linux-x64|linux-arm64) ;;
    *) fail "no static renderer is packaged for '$target'" "known: macos-aarch64, linux-x64, linux-arm64" ;;
esac
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+].*)?$ ]] || fail "'$3' is not a crate version"

archive="$build_dir/libdioxus_compose_renderer.a"
header="$build_dir/libdioxus_compose_renderer_api.h"
hash="$build_dir/schema-hash.txt"
for file in "$archive" "$header" "$hash"; do
    [[ -f "$file" ]] || fail "no $(basename "$file") in $build_dir" \
        "Build the renderer first: dioxus-compose-renderer/desktop/scripts/build-macos.sh or build-linux.sh."
done

name="dioxus-compose-renderer-v$version-$target"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
staging="$out/$name.staging"
rm -rf "$staging"
mkdir -p "$staging/lib" "$staging/include"
cp "$archive" "$staging/lib/"
cp "$header" "$staging/include/"
cp "$hash" "$staging/schema-hash.txt"
echo "$version" > "$staging/dioxus-compose-renderer.version"

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

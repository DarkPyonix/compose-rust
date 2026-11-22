#!/usr/bin/env bash
# Usage: .github/scripts/package-ios-renderer.sh <name> <ios build directory> <output directory>
#
# Packages the iOS renderer as the release carries it: both slices in one XCFramework,
# which is the only form Xcode links directly. A bare .a would make every consumer pick the
# right slice by hand and would break the moment they switched between a device and the
# simulator.
#
#   <output>/<name>.tar.gz          holding <name>.xcframework
#   <output>/<name>.tar.gz.sha256
#
#   <name>                   compose-rust-renderer-v<version>-ios for a release
#   <ios build directory>    what build-ios.sh wrote for both slices (renderer/build/ios),
#                            with device/ and simulator/ under it
#
# test-native-renderer.yml packages a release with it and publish-schema-renderer.yml the
# renderer develop publishes for a schema, so the two are the same bundle.
set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "usage: $0 <name> <ios build directory> <output directory>" >&2
    exit 2
fi

name="$1"
[[ -d "$2" ]] || { echo "error: $2 is not a directory: build the renderer with renderer/desktop/scripts/build-ios.sh first" >&2; exit 1; }
# Absolute, because the bundle is written from inside the output directory.
build="$(cd "$2" && pwd)"
out="$3"

for slice in device simulator; do
    [[ -f "$build/$slice/libcompose_rust_renderer.a" ]] || {
        echo "error: no $slice slice in $build: renderer/desktop/scripts/build-ios.sh writes the simulator one, and with --target device the device one" >&2
        exit 1
    }
    mkdir -p "$build/$slice/include"
    cp "$build/$slice"/*.h "$build/$slice/include/"
done

mkdir -p "$out"
cd "$out"
rm -rf "$name.xcframework"
xcodebuild -create-xcframework \
    -library "$build/device/libcompose_rust_renderer.a" \
    -headers "$build/device/include" \
    -library "$build/simulator/libcompose_rust_renderer.a" \
    -headers "$build/simulator/include" \
    -output "$name.xcframework"
tar -czf "$name.tar.gz" "$name.xcframework"
if command -v shasum >/dev/null; then
    shasum -a 256 "$name.tar.gz" > "$name.tar.gz.sha256"
else
    sha256sum "$name.tar.gz" > "$name.tar.gz.sha256"
fi
cat "$name.tar.gz.sha256"

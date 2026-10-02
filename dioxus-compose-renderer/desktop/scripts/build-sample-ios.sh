#!/usr/bin/env bash
# Usage: ./build-sample-ios.sh <sample> [--release]
#
# Wraps one sample in an iOS application bundle for the simulator.
#
#   build/ios/samples/<sample>.app
#
# The renderer is a Kotlin/Native static archive rather than a shared library, so the
# sample and the renderer are linked into one executable here. That is why a sample is
# built as a staticlib for this target and as a cdylib for Android: Android's Activity
# loads a library, and an iOS application is the library.
#
# Simulator only. A bundle for a device has to be signed by a certificate Apple issued,
# against a provisioning profile naming that device, and a self-signed one is refused.
# The simulator does not check, so an ad-hoc signature is enough there.
#
# Run dioxus-compose-renderer/desktop/scripts/build-ios.sh --target simulator first.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
native_dir="$(cd "$script_dir/.." && pwd)"
project_dir="$(cd "$native_dir/.." && pwd)"
repo_root="$(cd "$project_dir/.." && pwd)"

die() {
    echo "error: $1" >&2
    shift
    local line
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

sample=""
profile=debug
cargo_profile=dev
for argument in "$@"; do
    case "$argument" in
        --release) profile=release; cargo_profile=release ;;
        -*) die "unknown option $argument" ;;
        *) sample="$argument" ;;
    esac
done
[[ -n "$sample" ]] || die "no sample named" "usage: $0 <sample> [--release]"
[[ -d "$repo_root/samples/$sample" ]] || die "no sample at samples/$sample"

archive="$project_dir/build/ios/simulator/libdioxus_compose_renderer.a"
[[ -f "$archive" ]] || die "the renderer is not built for the simulator" \
    "fix: $script_dir/build-ios.sh --target simulator"

sdk_path="$(xcrun --sdk iphonesimulator --show-sdk-path)" ||
    die "no iOS simulator SDK" "fix: install Xcode, then xcodebuild -downloadPlatform iOS"

target=aarch64-apple-ios-sim
echo "==> building sample-$sample for $target"
(cd "$repo_root" && cargo build --profile "$cargo_profile" --package "sample-$sample" --lib --target "$target")

host="$repo_root/target/$target/$profile/libsample_${sample//-/_}.a"
[[ -f "$host" ]] || die "no static library at $host" \
    "a sample reaches iOS through its library, so it needs a [lib] with crate-type" \
    "including staticlib."

# The executable carries the sample's own name, so two of them on one simulator are two
# applications rather than one that keeps being replaced.
executable="sample-$sample"
app="$project_dir/build/ios/samples/$executable.app"
rm -rf "$app"
mkdir -p "$app"

echo "==> linking $executable against the renderer"
# -export_dynamic keeps the Host's boundary functions in the executable's dynamic symbol
# table. The renderer resolves them with dlsym at run time, and without this the linker
# has no reason to keep functions nothing in the image references.
#
# The Rust side brings its own main: on iOS the Host owns the loop the way it does on a
# desktop, because there is no Activity here and the application is one executable.
xcrun clang \
    -target arm64-apple-ios15.0-simulator \
    -isysroot "$sdk_path" \
    -O2 \
    -o "$app/$executable" \
    "$repo_root/samples/$sample/ios/main.c" \
    "$host" \
    "$archive" \
    -Wl,-export_dynamic \
    -lc++ -lsqlite3 -lz \
    -framework UIKit -framework Foundation -framework CoreGraphics -framework CoreText \
    -framework CoreFoundation -framework QuartzCore -framework Metal -framework MetalKit \
    -framework CoreServices -framework ImageIO -framework UniformTypeIdentifiers \
    -framework AudioToolbox -framework AVFoundation -framework CoreMedia -framework Security

cat > "$app/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>$executable</string>
    <key>CFBundleIdentifier</key><string>dioxus.compose.sample.$sample</string>
    <key>CFBundleName</key><string>$sample</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSRequiresIPhoneOS</key><true/>
    <key>MinimumOSVersion</key><string>15.0</string>
    <key>UILaunchScreen</key><dict/>
    <!-- Compose for iOS refuses to start without it: it caps the frame rate at 60Hz. -->
    <key>CADisableMinimumFrameDurationOnPhone</key><true/>
    <key>UIDeviceFamily</key><array><integer>1</integer></array>
</dict>
</plist>
PLIST

# Ad-hoc. The simulator does not verify a signature, and there is no certificate here that
# a device would accept anyway.
codesign --force --sign - "$app" >/dev/null 2>&1 || true

echo "built $app"

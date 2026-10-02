#!/usr/bin/env bash
# Builds the renderer as a Kotlin/Native static library for iOS:
#
#   build/ios/<target>/
#     libdioxus_compose_renderer.a        the renderer (Compose, Skia, the interpreter, our code)
#     libdioxus_compose_renderer_api.h    the header Kotlin/Native generates for it
#
# The two symbols the Host calls are the same ones the desktop build exports, with the same
# names and the same signatures: dioxus_compose_renderer_run and
# dioxus_compose_renderer_request_frame. There is no isolate and therefore no C shim here;
# see ios/src/IosEntryPoints.kt.
#
# Usage: build-ios.sh [--target simulator|device] [--release]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
LIBRARY_NAME="libdioxus_compose_renderer"

die() {
    echo "error: $1" >&2
    shift
    local line
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

target="simulator"
optimization="-g"
build_type="debug"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --target) target="${2:-}"; shift 2 ;;
        --release) optimization="-opt"; build_type="release"; shift ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *) die "unknown argument '$1'" "usage: build-ios.sh [--target simulator|device] [--release]" ;;
    esac
done

case "$target" in
    simulator) konan_target="ios_simulator_arm64"; amper_platform="iosSimulatorArm64"; sdk="iphonesimulator" ;;
    device)    konan_target="ios_arm64";           amper_platform="iosArm64";          sdk="iphoneos" ;;
    *) die "unknown target '$target'" "Use --target simulator (arm64 simulator) or --target device (arm64 iPhone)." ;;
esac

# Kotlin/Native cross-compiles only from macOS, and the link step needs the iOS SDK.
if [[ "$(uname -s)" != "Darwin" ]]; then
    die "iOS builds run on macOS only (this is $(uname -s))" \
        "Apple does not ship the iOS SDK for other systems, and Kotlin/Native needs it to link."
fi
if [[ "$(uname -m)" != "arm64" ]]; then
    die "this script builds arm64 only (this machine is $(uname -m))" \
        "The x86_64 simulator is not covered; add ios_x64 here if you need it."
fi
if ! xcode-select -p >/dev/null 2>&1; then
    die "Xcode is not selected" \
        "Kotlin/Native links against the iOS SDK through xcrun." \
        "fix: xcode-select --install, or sudo xcode-select -s /Applications/Xcode.app"
fi
if ! xcrun --sdk "$sdk" --show-sdk-path >/dev/null 2>&1; then
    die "the $sdk SDK is not installed" \
        "The Xcode command line tools alone are not enough for iOS: install Xcode itself," \
        "open it once so it installs its components, then:" \
        "  sudo xcode-select -s /Applications/Xcode.app" \
        "  xcodebuild -downloadPlatform iOS"
fi

KOTLIN_WRAPPER="$PROJECT_DIR/kotlin"
[[ -x "$KOTLIN_WRAPPER" ]] || die \
    "$KOTLIN_WRAPPER is missing or not executable" \
    "It is the self-bootstrapping Kotlin Toolchain wrapper; no separate install is needed." \
    "fix: chmod +x $KOTLIN_WRAPPER"

BUILD_DIR="$PROJECT_DIR/build"
OUT_DIR="$BUILD_DIR/ios/$target"
LOG_DIR="$BUILD_DIR/ios-logs"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR" "$LOG_DIR"

# Compiling the module gives us the klib and, in the debug log, the exact set of dependency
# klibs the compiler resolved. Scraping the build's own log is how the desktop script gets
# its classpath too (build-native.sh reads java.class.path from the JVM run): the linking
# step must be handed exactly what the compile used, not a list maintained by hand.
build_log="$LOG_DIR/$amper_platform-build.log"
echo "==> kotlin build -m ios -m staticlib ($amper_platform)"
# The compile has to actually run: an up-to-date task logs no arguments, and its arguments
# are where the resolved klib list comes from.
rm -rf "$PROJECT_DIR/build/tasks/_ios_compile${amper_platform}Debug"
(cd "$PROJECT_DIR" && "$KOTLIN_WRAPPER" --log-level=debug build -m ios -m staticlib) >"$build_log" 2>&1 ||
    { cat "$build_log" >&2; die "the ios module did not compile" "Full log: $build_log"; }

klib="$PROJECT_DIR/build/tasks/_ios_compile${amper_platform}Debug/ios.klib"
[[ -f "$klib" ]] || die "the compiler produced no klib at $klib" \
    "Expected the :ios:compile${amper_platform}Debug task to run." \
    "Full log: $build_log"

# The compiler arguments are logged as one block per invocation, and the block names the
# target it belongs to, so the right block is the one containing -target=<this target>. The
# module's own klib is added below by path, so project outputs are dropped here: the staticlib
# block names the renderer klib under a task directory that differs in case from the one on
# disk, and two paths with one unique_name is an error rather than a duplicate.
libraries_file="$LOG_DIR/$amper_platform-libraries.txt"
awk -v target="-target=$konan_target" '
    /^[A-Z]+ / {
        if (wanted) { for (i = 1; i <= count; i++) print libraries[i] }
        wanted = 0; count = 0
        collecting = /Native metadata compilation args/
        next
    }
    collecting && $0 == target { wanted = 1 }
    collecting && /^-library=/ { libraries[++count] = substr($0, 10) }
    END { if (wanted) { for (i = 1; i <= count; i++) print libraries[i] } }
' "$build_log" | grep -v "/build/tasks/" | sort -u > "$libraries_file"
[[ -s "$libraries_file" ]] || die \
    "could not read the resolved klib list for $konan_target out of $build_log" \
    "The Kotlin Toolchain changed its debug output; update the awk block in this script."

# The Kotlin/Native compiler is provisioned by the toolchain wrapper, not installed
# separately, so it is found where the wrapper unpacked it.
konan_home=""
for candidate in "$HOME"/Library/Caches/JetBrains/Kotlin/extract.cache/*kotlin-native-prebuilt-*-macos-aarch64*.d; do
    [[ -x "$candidate/bin/konanc" ]] && konan_home="$candidate"
done
[[ -n "$konan_home" ]] || die \
    "no Kotlin/Native compiler in the toolchain cache" \
    "It is unpacked by the first 'kotlin build' of a native module." \
    "fix: cd $PROJECT_DIR && ./kotlin build -m ios"

output="$OUT_DIR/$LIBRARY_NAME"
entry_source="$PROJECT_DIR/staticlib/src/IosEntryPoints.kt"
[[ -f "$entry_source" ]] || die "missing $entry_source"

echo "==> konanc -produce static ($konan_target, $build_type)"
library_args=("-library=$klib")
while IFS= read -r line; do library_args+=("-library=$line"); done < "$libraries_file"

# The entry points are compiled here as the main module, with the renderer as a library, so
# that the generated C header holds the two boundary functions and nothing else. Compiling the
# renderer itself as the main module (with -Xinclude) asks Kotlin/Native to build a C adapter
# for every public Compose declaration, which fails: NullPointerException in CAdapterCodegen
# (Kotlin 2.4.10). The archive still contains the whole renderer, because -produce static
# links everything the entry points reach.
"$konan_home/bin/konanc" \
    -produce static \
    -target "$konan_target" \
    "$optimization" \
    -module-name dioxus_compose_renderer \
    -opt-in kotlin.experimental.ExperimentalNativeApi \
    "${library_args[@]}" \
    "$entry_source" \
    -o "$output" 2>&1 | tee "$LOG_DIR/$amper_platform-link.log"

archive="$output.a"
header="$OUT_DIR/${LIBRARY_NAME}_api.h"
[[ -f "$archive" ]] || die "konanc produced no $archive" "Full log: $LOG_DIR/$amper_platform-link.log"
# Kotlin/Native names the header after the module; keep the name predictable for the Host.
for generated in "$OUT_DIR"/*.h; do
    [[ -f "$generated" && "$generated" != "$header" ]] && mv "$generated" "$header"
done

# A static library that is missing an entry point links fine and fails at run time, so the
# two boundary symbols are checked here rather than in the app that links it.
# nm reports a non-zero status for archive members that hold no symbols, which under
# pipefail would look like a failed check, so its output is read from a file.
symbols_file="$LOG_DIR/$amper_platform-symbols.txt"
nm -g "$archive" >"$symbols_file" 2>/dev/null || true
for symbol in dioxus_compose_renderer_run dioxus_compose_renderer_request_frame; do
    grep -q " T _$symbol\$" "$symbols_file" ||
        die "$archive does not export $symbol" \
            "Check the @CName annotations in staticlib/src/IosEntryPoints.kt."
done

echo
echo "$archive"
ls -la "$OUT_DIR"
echo
echo "exported boundary symbols:"
grep " T _dioxus_compose_renderer" "$symbols_file"

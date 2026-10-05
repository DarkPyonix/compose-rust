#!/usr/bin/env bash
# Builds the renderer as a Kotlin/Native static library for macOS:
#
#   build/macos/
#     libcompose_rust_renderer.a        the renderer (Compose, Skia, the interpreter, our code)
#     libcompose_rust_renderer_api.h    the header Kotlin/Native generates for it
#     schema-hash.txt                     the schema it was generated from
#
# The archive is what the release ships and what an application links: the application is
# then one executable with the renderer, Skia and ICU inside, needing only the system's own
# frameworks.
#
# The two symbols the Host calls are the same ones the desktop build exports, with the same
# names and the same signatures: compose_rust_renderer_run and
# compose_rust_renderer_request_frame. There is no isolate and therefore no C shim here;
# see staticlib/src/IosEntryPoints.kt, which both platforms share.
#
# What this is instead of: the native image the desktop script builds carries a Java
# runtime, and most of what that weighs is the runtime rather than anything drawn. Compose
# publishes this platform as a target of its own, so here none of it is needed.
#
# Usage: build-macos.sh [--release]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
LIBRARY_NAME="libcompose_rust_renderer"

die() {
    echo "error: $1" >&2
    shift
    local line
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

target="macos"
optimization="-g"
build_type="debug"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --release) optimization="-opt"; build_type="release"; shift ;;
        -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
        *) die "unknown argument '$1'" "usage: build-macos.sh [--release]" ;;
    esac
done

konan_target="macos_arm64"
amper_platform="macosArm64"
sdk="macosx"

# Kotlin/Native builds this target on macOS only, and the link step needs the macOS SDK.
if [[ "$(uname -s)" != "Darwin" ]]; then
    die "macOS builds run on macOS only (this is $(uname -s))" \
        "Apple does not ship the macOS SDK for other systems, and Kotlin/Native needs it to link."
fi
if [[ "$(uname -m)" != "arm64" ]]; then
    die "this script builds arm64 only (this machine is $(uname -m))" \
        "Intel is not covered; add macos_x64 here if you need it."
fi
if ! xcode-select -p >/dev/null 2>&1; then
    die "Xcode is not selected" \
        "Kotlin/Native links against the macOS SDK through xcrun." \
        "fix: xcode-select --install, or sudo xcode-select -s /Applications/Xcode.app"
fi
if ! xcrun --sdk "$sdk" --show-sdk-path >/dev/null 2>&1; then
    die "the $sdk SDK is not installed" \
        "fix: xcode-select --install"
fi

KOTLIN_WRAPPER="$PROJECT_DIR/kotlin"
[[ -x "$KOTLIN_WRAPPER" ]] || die \
    "$KOTLIN_WRAPPER is missing or not executable" \
    "It is the self-bootstrapping Kotlin Toolchain wrapper; no separate install is needed." \
    "fix: chmod +x $KOTLIN_WRAPPER"

BUILD_DIR="$PROJECT_DIR/build"
OUT_DIR="$BUILD_DIR/macos"
LOG_DIR="$BUILD_DIR/macos-logs"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR" "$LOG_DIR"

# Compiling the module gives us the klib and, in the debug log, the exact set of dependency
# klibs the compiler resolved. Scraping the build's own log is how the desktop script gets
# its classpath too (build-native.sh reads java.class.path from the JVM run): the linking
# step must be handed exactly what the compile used, not a list maintained by hand.
build_log="$LOG_DIR/$amper_platform-build.log"
echo "==> kotlin build -m macos -m staticlib-macos ($amper_platform)"
# The task's output directory is found rather than spelled out. Its name follows the
# toolchain's task name, whose capitalisation is the toolchain's to choose, and a name written
# here that is wrong only in case still matches on the case-insensitive file system macOS uses
# by default. The mistake then shows up on Linux alone, as a klib that is "not there" after a
# compile that succeeded. Matching without regard to case finds it on both, and the error
# below lists what is there when it does not.
task_dir_name="_macos_compile${amper_platform}Debug"
compile_task_dirs() {
    [[ -d "$PROJECT_DIR/build/tasks" ]] || return 0
    find "$PROJECT_DIR/build/tasks" -mindepth 1 -maxdepth 1 -type d -iname "$task_dir_name"
}

# The compile has to actually run: an up-to-date task logs no arguments, and its arguments
# are where the resolved klib list comes from.
while IFS= read -r stale; do rm -rf "$stale"; done < <(compile_task_dirs)
(cd "$PROJECT_DIR" && "$KOTLIN_WRAPPER" --log-level=debug build -m macos -m staticlib-macos) >"$build_log" 2>&1 ||
    { cat "$build_log" >&2; die "the macos module did not compile" "Full log: $build_log"; }

# Exactly one: none means the compile did not run, and two would mean the toolchain wrote
# directories differing only in case, and picking one would be a guess.
klibs="$(while IFS= read -r dir; do
    if [[ -f "$dir/macos.klib" ]]; then echo "$dir/macos.klib"; fi
done < <(compile_task_dirs))"
klib_count="$(printf '%s' "$klibs" | grep -c . || true)"
if [[ "$klib_count" -ne 1 ]]; then
    die "expected one macos.klib in a build/tasks directory named $task_dir_name (any case), found $klib_count" \
        "Found: ${klibs:-nothing}" \
        "Directories under $PROJECT_DIR/build/tasks: $(ls "$PROJECT_DIR/build/tasks" 2>/dev/null | tr '\n' ' ')" \
        "Full log: $build_log"
fi
klib="$klibs"

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
    "fix: cd $PROJECT_DIR && ./kotlin build -m macos"

output="$OUT_DIR/$LIBRARY_NAME"
entry_source="$PROJECT_DIR/staticlib-macos/src/MacosEntryPoints.kt"
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
#
# objcDisposeOnMain=false: an Objective-C object a collection frees from Kotlin is released
# where the collector runs, not handed to the main run loop to release on its next turn. A
# window being resized draws frame after frame without that turn coming, and each frame's
# drawable, a texture the size of the window, waited for it: 100 sizes held 528 MB of Metal
# memory. Nothing this renderer holds from Kotlin has to be released on the main thread.
"$konan_home/bin/konanc" \
    -produce static \
    -target "$konan_target" \
    "$optimization" \
    -module-name compose_rust_renderer \
    -opt-in kotlin.experimental.ExperimentalNativeApi \
    -Xbinary=objcDisposeOnMain=false \
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
for symbol in compose_rust_renderer_run compose_rust_renderer_request_frame; do
    grep -q " T _$symbol\$" "$symbols_file" ||
        die "$archive does not export $symbol" \
            "Check the @CName annotations in staticlib/src/IosEntryPoints.kt."
done

# The schema this renderer was generated from, written beside it, so the Host's build script
# can see the two disagree before a program built from them opens an empty window.
schema_hash_decimal="$(
    grep -o 'const val SCHEMA_HASH: Long = -\?[0-9]*' \
        "$PROJECT_DIR/desktop/src/protocol/Protocol.gen.kt" |
        grep -o -- '-\?[0-9]*$'
)"
[[ -n "$schema_hash_decimal" ]] || die "could not read SCHEMA_HASH from desktop/src/protocol/Protocol.gen.kt"
# printf rather than awk: the hash fills all 64 bits and awk works in doubles.
printf '0x%016x\n' "$schema_hash_decimal" > "$OUT_DIR/schema-hash.txt"

echo
echo "$archive"
ls -la "$OUT_DIR"
echo
echo "exported boundary symbols:"
grep " T _compose_rust_renderer" "$symbols_file"

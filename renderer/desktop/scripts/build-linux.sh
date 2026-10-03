#!/usr/bin/env bash
# Builds the renderer as a Kotlin/Native static library for Linux:
#
#   build/linux/<target>/
#     libcompose_rust_renderer.a        the renderer (Compose, Skia, the interpreter, our code)
#     libcompose_rust_renderer_api.h    the header Kotlin/Native generates for it
#
# The two symbols the Host calls are the same ones the desktop build exports, with the same
# names and the same signatures: compose_rust_renderer_run and
# compose_rust_renderer_request_frame. There is no isolate and therefore no C shim here;
# see staticlib/src/IosEntryPoints.kt, which both platforms share.
#
# What this is instead of: the native image the desktop script builds carries a Java
# runtime, and most of what a Linux distribution of it weighs is that runtime rather than
# anything drawn. It also carries AWT and its X11 toolkit, which is a second window system
# underneath the one the renderer opens.
#
# Compose publishes no Kotlin/Native target for this platform, so the modules it draws with
# are built from source and published locally first. `scripts/build-compose.sh --target
# linuxX64` does that, and this script says so rather than doing it, because it is a Gradle
# build of someone else's repository and belongs in its own step.
#
# Usage: build-linux.sh [--release]
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

target="linux"
optimization="-g"
build_type="debug"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --release) optimization="-opt"; build_type="release"; shift ;;
        -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
        *) die "unknown argument '$1'" "usage: build-linux.sh [--release]" ;;
    esac
done

konan_target="linux_x64"
amper_platform="linuxX64"

# Built where the libraries it links against are. Kotlin/Native can cross compile the code,
# but the archive this produces is linked into an application by Cargo against the X11, Xext
# and GL of the machine doing that linking, so producing it anywhere else only moves the
# failure later.
if [[ "$(uname -s)" != "Linux" ]]; then
    die "Linux builds run on Linux (this is $(uname -s))" \
        "There is a container for it: tools/linux-box, which carries the headers as well."
fi
for library in x11 xext gl; do
    pkg-config --exists "$library" ||
        die "the $library development package is not installed" \
            "Debian and its derivatives: apt-get install libx11-dev libxext-dev libgl1-mesa-dev" \
            "Fedora and openSUSE: dnf install libX11-devel libXext-devel mesa-libGL-devel"
done

KOTLIN_WRAPPER="$PROJECT_DIR/kotlin"
[[ -x "$KOTLIN_WRAPPER" ]] || die \
    "$KOTLIN_WRAPPER is missing or not executable" \
    "It is the self-bootstrapping Kotlin Toolchain wrapper; no separate install is needed." \
    "fix: chmod +x $KOTLIN_WRAPPER"

BUILD_DIR="$PROJECT_DIR/build"
OUT_DIR="$BUILD_DIR/linux"
LOG_DIR="$BUILD_DIR/linux-logs"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR" "$LOG_DIR"

# Compiling the module gives us the klib and, in the debug log, the exact set of dependency
# klibs the compiler resolved. Scraping the build's own log is how the desktop script gets
# its classpath too (build-native.sh reads java.class.path from the JVM run): the linking
# step must be handed exactly what the compile used, not a list maintained by hand.
# The toolchain names a task after the platform with its first letter capitalised:
# :linux:compileLinuxX64Debug, built into _linux_compileLinuxX64Debug. Spelling it as the
# platform is written looked for a directory that is never made and reported a compile
# that had succeeded as one that produced nothing.
task_platform="${amper_platform^}"  # bash 4; set here, after the Linux check, as macOS ships bash 3
build_log="$LOG_DIR/$amper_platform-build.log"
echo "==> kotlin build -m linux -m staticlib-linux ($amper_platform)"
# The task's output directory is found rather than spelled out. Its name follows the
# toolchain's task name, whose capitalisation is the toolchain's to choose, and a name written
# here that is wrong only in case still matches on the case-insensitive file system macOS uses
# by default. The mistake then shows up on Linux alone, as a klib that is "not there" after a
# compile that succeeded. Matching without regard to case finds it on both, and the error
# below lists what is there when it does not.
task_dir_name="_linux_compile${amper_platform}Debug"
compile_task_dirs() {
    [[ -d "$PROJECT_DIR/build/tasks" ]] || return 0
    find "$PROJECT_DIR/build/tasks" -mindepth 1 -maxdepth 1 -type d -iname "$task_dir_name"
}

# The compile has to actually run: an up-to-date task logs no arguments, and its arguments
# are where the resolved klib list comes from.
while IFS= read -r stale; do rm -rf "$stale"; done < <(compile_task_dirs)
(cd "$PROJECT_DIR" && "$KOTLIN_WRAPPER" --log-level=debug build -m linux -m staticlib-linux) >"$build_log" 2>&1 ||
    { cat "$build_log" >&2; die "the linux module did not compile" "Full log: $build_log"; }

# Exactly one: none means the compile did not run, and two would mean the toolchain wrote
# directories differing only in case, and picking one would be a guess.
klibs="$(while IFS= read -r dir; do
    if [[ -f "$dir/linux.klib" ]]; then echo "$dir/linux.klib"; fi
done < <(compile_task_dirs))"
klib_count="$(printf '%s' "$klibs" | grep -c . || true)"
if [[ "$klib_count" -ne 1 ]]; then
    die "expected one linux.klib in a build/tasks directory named $task_dir_name (any case), found $klib_count" \
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
for candidate in "$HOME"/.cache/JetBrains/Kotlin/extract.cache/*kotlin-native-prebuilt-*-linux-x86_64*.d; do
    [[ -x "$candidate/bin/konanc" ]] && konan_home="$candidate"
done
[[ -n "$konan_home" ]] || die \
    "no Kotlin/Native compiler in the toolchain cache" \
    "It is unpacked by the first 'kotlin build' of a native module." \
    "fix: cd $PROJECT_DIR && ./kotlin build -m linux"

output="$OUT_DIR/$LIBRARY_NAME"
entry_source="$PROJECT_DIR/staticlib-linux/src/LinuxEntryPoints.kt"
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
    -module-name compose_rust_renderer \
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
# three boundary symbols are checked here rather than in the app that links it.
# nm reports a non-zero status for archive members that hold no symbols, which under
# pipefail would look like a failed check, so its output is read from a file.
symbols_file="$LOG_DIR/$amper_platform-symbols.txt"
nm -g "$archive" >"$symbols_file" 2>/dev/null || true
for symbol in compose_rust_renderer_run compose_rust_renderer_request_frame compose_rust_renderer_measure; do
    grep -q " T $symbol\$" "$symbols_file" ||
        die "$archive does not export $symbol" \
            "Check the @CName annotations in staticlib-linux/src/LinuxEntryPoints.kt."
done

# The library that makes an application export the Host's functions.
#
# The renderer looks them up with dlsym, so they have to be in the executable's dynamic
# symbol table, and nothing the Host crate can pass to an application's link puts them
# there except a shared library that needs them. desktop/c/linux_host_exports.c says why
# it takes a library rather than a flag, and why it defines one byte.
#
# The bare SONAME is a placeholder: the Host's build script renames it to the absolute path
# it finds the library at, as it does the desktop renderer, so an application records where
# it is. scripts/bundle-renderer.sh sets it back when an application is packaged.
exports_name="libcompose_rust_host_exports.so"
exports="$OUT_DIR/$exports_name"
c_dir="$PROJECT_DIR/desktop/c"
cc -shared -fPIC -O2 -o "$exports" -Wl,-soname,"$exports_name" \
    "$c_dir/linux_host_references.c" "$c_dir/linux_host_exports.c"

# Every Host function the renderer looks up has to be left undefined here, or an
# application does not export it and the renderer fails to find it at startup. The
# renderer names them as strings, so the list is read from linux_host_references.c, which
# scripts/tests/linux-host-references.test.sh holds to the Host and the renderer.
host_wanted="$(grep -oE '^ +compose_rust_host_[a-z_]+,' "$c_dir/linux_host_references.c" |
    tr -d ' ,' | sort -u)"
host_referenced="$(nm -D --undefined-only "$exports" |
    grep -oE 'compose_rust_host_[a-z_]+' | sort -u)"
[[ -n "$host_wanted" && "$host_wanted" == "$host_referenced" ]] || die \
    "$exports_name leaves '$(echo $host_referenced)' undefined, not '$(echo $host_wanted)'" \
    "An application exports exactly what this library needs, and the renderer looks up the rest in vain."
nm -D --defined-only "$exports" | grep -q ' compose_rust_renderer_host_exports$' || die \
    "$exports_name does not define compose_rust_renderer_host_exports" \
    "The Host refers to it so the linker keeps the library; without it the library is dropped."

echo
echo "$archive"
echo "$exports"
ls -la "$OUT_DIR"
echo
echo "exported boundary symbols:"
grep " T compose_rust_renderer" "$symbols_file"

#!/usr/bin/env bash
# Builds the Windows renderer as a Kotlin/Native static library, and everything the Host
# links beside it into one MSVC executable:
#
#   build/windows/
#     libcompose_rust_renderer.a      the renderer (Compose, skiko's Kotlin half, the
#                                       interpreter, our code): MinGW, rewritten for an MSVC link
#     libcompose_rust_renderer_api.h  the header Kotlin/Native generates for it
#     gcc/                              the GCC runtime the renderer object carries: libstdc++
#                                       (rewritten too), libgcc, libgcc_eh, winpthread
#     native/dxc-windows-native.lib     MSVC objects: the Win32 window, its toasts, the MinGW
#                                       bridge and the embedded ICU loader, linked whole
#     native/dxc-windows-static-ucrt.lib  the bridge's import pointers, for an application that
#                                       links the UCRT statically (+crt-static) and only then
#     skiko/                            skiko's C++ half and the prebuilt Skia libraries, MSVC
#
# Point DXC_WINDOWS_NATIVE_LIB at that directory and an application that depends on
# compose-rust links all of it into its own executable (see compose-rust/build.rs). The
# executable then needs nothing beside it: no Java runtime, no Visual C++ runtime DLL, no
# MinGW DLL and no icudtl.dat. Which C runtime it links is the Host's build script's choice
# (compose-rust/build/windows_crt.rs), so nothing here names one.
#
# Kotlin/Native's only Windows target is MinGW and the application is MSVC. The two halves
# meet in C calls only, and two MinGW conventions are rewritten so an MSVC link keeps their
# meaning: static constructors move to .CRT$XCU, and per-function unwind data becomes an
# associative COMDAT of its function (scripts/fix-mingw-objects.py says why each matters).
# MinGW goes no further than that object: the link is MSVC's, and the C of our own here is
# compiled for MSVC.
#
# Runs on Windows from Git Bash, with the MSVC tools and Windows SDK installed (Visual Studio
# or its Build Tools, "Desktop development with C++"). It also runs on macOS and Linux, where
# Kotlin/Native cross-compiles to mingwX64 and the MSVC objects are compiled with clang in
# MSVC mode against the runtime and SDK headers cargo-xwin or `xwin splat` lays out (set
# XWIN_DIR, or leave them where cargo-xwin puts them).
#
# Needs: scripts/build-compose.sh --target mingwX64 to have published skiko and Compose for
# mingwX64 to the local Maven repository first, and left skiko's C++ half in
# .scratch/skiko-build/out/windows-x64 (DXC_SKIKO_BUILD to look elsewhere); python3; and
# Kotlin/Native's LLVM, which the first mingwX64 compile downloads.
#
# Usage: build-windows.sh [--release]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPO_DIR="$(cd "$PROJECT_DIR/.." && pwd)"
LIBRARY_NAME="libcompose_rust_renderer"

die() {
    echo "error: $1" >&2
    shift
    local line
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

optimization="-g"
build_type="debug"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --release) optimization="-opt"; build_type="release"; shift ;;
        -h|--help) sed -n '2,36p' "$0"; exit 0 ;;
        *) die "unknown argument '$1'" "usage: build-windows.sh [--release]" ;;
    esac
done

konan_target="mingw_x64"
amper_platform="mingwX64"

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) host=windows ;;
    Darwin) host=macos ;;
    Linux) host=linux ;;
    *) die "this script does not know the host $(uname -s)" ;;
esac

# What a native Windows tool is handed: a path it can open. Forward slashes, because the
# compiler's argument files read a backslash as an escape.
tool_path() {
    if [[ "$host" == windows ]]; then cygpath -m "$1"; else echo "$1"; fi
}

# Windows puts an app execution alias named python3 on PATH when no interpreter is
# installed, which opens the Store rather than running anything: `command -v` finds it,
# and it answers to `--version` with nothing useful and exit code 0, so a plain existence
# check is not enough. A real interpreter is the one that prints its own version.
PYTHON=""
for candidate in python3 python; do
    found="$(command -v "$candidate" || true)"
    if [[ -n "$found" ]] && "$found" --version >/dev/null 2>&1; then
        PYTHON="$found"
        break
    fi
done
[[ -n "$PYTHON" ]] || die "no working python3 on PATH" \
    "fix-mingw-objects.py rewrites the renderer object with it." \
    "A python3 found on PATH that does nothing useful is the Store's app execution" \
    "alias; install Python and put it ahead of that alias."

KOTLIN_WRAPPER="$PROJECT_DIR/kotlin"
[[ -x "$KOTLIN_WRAPPER" ]] || die "$KOTLIN_WRAPPER is missing or not executable" \
    "It is the self-bootstrapping Kotlin Toolchain wrapper; no separate install is needed."

SKIKO_OUT="${DXC_SKIKO_BUILD:-$REPO_DIR/.scratch/skiko-build}/out/windows-x64"
for needed in skiko-bridges.lib skia/skia.lib skia/icudtl.dat embedded_icu.cpp; do
    [[ -f "$SKIKO_OUT/$needed" ]] || die "no $needed in $SKIKO_OUT" \
        "Run renderer/scripts/build-compose.sh --target mingwX64 first: it builds" \
        "skiko for Windows and leaves its C++ half there."
done

BUILD_DIR="$PROJECT_DIR/build"
OUT_DIR="$BUILD_DIR/windows"
LOG_DIR="$BUILD_DIR/windows-logs"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/gcc" "$OUT_DIR/native" "$OUT_DIR/skiko" "$LOG_DIR"

# 1. The module, with the debug log that names every klib the compile resolved. The link step
# has to be handed exactly what the compile used, not a list kept by hand. The task directory
# is found without regard to case, for the reason build-linux.sh gives.
build_log="$LOG_DIR/$amper_platform-build.log"
task_dir_name="_windows_compile${amper_platform}Debug"
compile_task_dirs() {
    [[ -d "$PROJECT_DIR/build/tasks" ]] || return 0
    find "$PROJECT_DIR/build/tasks" -mindepth 1 -maxdepth 1 -type d -iname "$task_dir_name"
}
# The compile has to actually run: an up-to-date task logs no arguments.
while IFS= read -r stale; do rm -rf "$stale"; done < <(compile_task_dirs)
echo "==> kotlin build -m windows -m staticlib-windows ($amper_platform)"
(cd "$PROJECT_DIR" && "$KOTLIN_WRAPPER" --log-level=debug build -m windows -m staticlib-windows) \
    >"$build_log" 2>&1 || { tail -80 "$build_log" >&2; die "the windows module did not compile" "Full log: $build_log"; }

klibs="$(while IFS= read -r dir; do
    if [[ -f "$dir/windows.klib" ]]; then echo "$dir/windows.klib"; fi
done < <(compile_task_dirs))"
klib_count="$(printf '%s' "$klibs" | grep -c . || true)"
[[ "$klib_count" -eq 1 ]] || die \
    "expected one windows.klib in a build/tasks directory named $task_dir_name (any case), found $klib_count" \
    "Found: ${klibs:-nothing}" \
    "Directories under $PROJECT_DIR/build/tasks: $(ls "$PROJECT_DIR/build/tasks" 2>/dev/null | tr '\n' ' ')" \
    "Full log: $build_log"
klib="$klibs"

# The block of compiler arguments that names this target, as build-linux.sh reads it. A log
# written on Windows ends its lines with a carriage return, which is taken off first, and
# names the project's own outputs with either slash.
libraries_file="$LOG_DIR/$amper_platform-libraries.txt"
tr -d '\r' <"$build_log" | awk -v target="-target=$konan_target" '
    /^[A-Z]+ / {
        if (wanted) { for (i = 1; i <= count; i++) print libraries[i] }
        wanted = 0; count = 0
        collecting = /Native metadata compilation args/
        next
    }
    collecting && $0 == target { wanted = 1 }
    collecting && /^-library=/ { libraries[++count] = substr($0, 10) }
    END { if (wanted) { for (i = 1; i <= count; i++) print libraries[i] } }
' | grep -vE '[/\\]build[/\\]tasks[/\\]' | sort -u > "$libraries_file" || true
[[ -s "$libraries_file" ]] || die "could not read the resolved klib list for $konan_target out of $build_log" \
    "The Kotlin Toolchain changed its debug output; update the awk block in this script."

# The Kotlin/Native compiler the toolchain wrapper unpacked, for whichever machine this is.
konan_home=""
case "$host" in
    macos) caches=("$HOME/Library/Caches/JetBrains/Kotlin/extract.cache") ;;
    linux) caches=("$HOME/.cache/JetBrains/Kotlin/extract.cache") ;;
    windows) caches=("$(cygpath -u "${LOCALAPPDATA:-$HOME/AppData/Local}")/JetBrains/Kotlin/extract.cache") ;;
esac
for cache in "${caches[@]}"; do
    for candidate in "$cache"/*kotlin-native-prebuilt-*.d; do
        [[ -f "$candidate/bin/konanc" || -f "$candidate/bin/konanc.bat" ]] && konan_home="$candidate"
    done
done
[[ -n "$konan_home" ]] || die "no Kotlin/Native compiler in the toolchain cache (${caches[*]})" \
    "It is unpacked by the first 'kotlin build' of a native module."
konanc="$konan_home/bin/konanc"
[[ "$host" == windows ]] && konanc="$konan_home/bin/konanc.bat"

# 2. The static library. The entry points are the main module and the renderer a library, so
# the generated header holds the two boundary functions and nothing else (build-linux.sh says
# why the other way round fails). The arguments go in a file: there are well over a hundred
# klibs, and a Windows command line holds eight thousand characters.
output="$OUT_DIR/$LIBRARY_NAME"
entry_source="$PROJECT_DIR/staticlib-windows/src/WindowsEntryPoints.kt"
[[ -f "$entry_source" ]] || die "missing $entry_source"
arguments="$LOG_DIR/$amper_platform-konanc.args"
{
    printf '%s\n' -produce static -target "$konan_target" "$optimization" \
        -module-name compose_rust_renderer -opt-in kotlin.experimental.ExperimentalNativeApi
    echo "\"-library=$(tool_path "$klib")\""
    while IFS= read -r line; do
        [[ "$host" == windows ]] && line="$(cygpath -m "$line")"
        echo "\"-library=$line\""
    done < "$libraries_file"
    echo "\"$(tool_path "$entry_source")\""
    echo "-o"
    echo "\"$(tool_path "$output")\""
} > "$arguments"
echo "==> konanc -produce static ($konan_target, $build_type)"
"$konanc" "@$(tool_path "$arguments")" >"$LOG_DIR/$amper_platform-link.log" 2>&1 ||
    { tail -60 "$LOG_DIR/$amper_platform-link.log" >&2; die "konanc did not produce the static library" \
        "Full log: $LOG_DIR/$amper_platform-link.log"; }
archive="$output.a"
header="$OUT_DIR/${LIBRARY_NAME}_api.h"
[[ -f "$archive" ]] || die "konanc produced no $archive" "Full log: $LOG_DIR/$amper_platform-link.log"
for generated in "$OUT_DIR"/*.h; do
    [[ -f "$generated" && "$generated" != "$header" ]] && mv "$generated" "$header"
done

# 3. The GCC runtime the renderer object was compiled against, from Kotlin/Native's own MinGW
# toolchain: the same files Kotlin/Native links statically into a MinGW executable of its own.
mingw=""
for candidate in "$HOME"/.konan/dependencies/msys2-mingw-w64-x86_64-*; do
    [[ -f "$candidate/x86_64-w64-mingw32/lib/libwinpthread.a" ]] && mingw="$candidate"
done
[[ -n "$mingw" ]] || die "no Kotlin/Native MinGW toolchain under ~/.konan/dependencies" \
    "Kotlin/Native downloads it the first time it compiles for mingwX64."
gcc_lib="$(ls -d "$mingw"/lib/gcc/x86_64-w64-mingw32/*/ | sort -V | tail -1)"
cp "$gcc_lib/libstdc++.a" "$gcc_lib/libgcc.a" "$gcc_lib/libgcc_eh.a" "$OUT_DIR/gcc/"
cp "$mingw/x86_64-w64-mingw32/lib/libwinpthread.a" "$OUT_DIR/gcc/"

# 4. The rewrite. The GCC runtime is rewritten with the renderer: libstdc++ has constructors
# of its own (its exception emergency pool among them), and winpthread TLS callbacks.
"$PYTHON" "$PROJECT_DIR/scripts/fix-mingw-objects.py" "$(tool_path "$archive")"
for runtime in libstdc++.a libgcc.a libgcc_eh.a libwinpthread.a; do
    "$PYTHON" "$PROJECT_DIR/scripts/fix-mingw-objects.py" "$(tool_path "$OUT_DIR/gcc/$runtime")"
done

# 5. The MSVC objects of our own. clang in MSVC mode rather than cl.exe, because the ICU loader
# compiles its data in with #embed and cl.exe has no #embed; the rest is compiled the same way
# so there is one compiler. Kotlin/Native's own LLVM, which every machine that got this far
# has, and the one on PATH where that has no clang in it.
llvm_bin=""
for candidate in $(ls -d "$HOME"/.konan/dependencies/llvm-*-essentials*/bin 2>/dev/null | sort -V -r); do
    if [[ -x "$candidate/clang" && -x "$candidate/llvm-ar" ]]; then llvm_bin="$candidate"; break; fi
done
if [[ -n "$llvm_bin" ]]; then
    clang="$llvm_bin/clang"
    llvm_ar="$llvm_bin/llvm-ar"
else
    clang="$(command -v clang || true)"
    llvm_ar="$(command -v llvm-ar || true)"
fi
[[ -n "$clang" && -n "$llvm_ar" ]] || die "no clang and llvm-ar: neither Kotlin/Native's LLVM under ~/.konan/dependencies nor one on PATH"
# Compiled for the static runtime's headers and naming no runtime library (/Zl), with the
# C++ library's runtime guard left out, so the objects are answered by whichever C runtime the
# application links: the Host's build script decides that, not these.
c_flags=(--driver-mode=cl --target=x86_64-pc-windows-msvc /O2 /MT /Zl /c /nologo
    -D_ALLOW_RUNTIME_LIBRARY_MISMATCH)
# On Windows clang finds the MSVC headers itself, from the environment Visual Studio's
# developer prompt sets or from the installation. Anywhere else it is told where they are.
if [[ "$host" != windows ]]; then
    xwin="${XWIN_DIR:-}"
    if [[ -z "$xwin" ]]; then
        for candidate in "$HOME/Library/Caches/cargo-xwin/xwin" "$HOME/.cache/cargo-xwin/xwin"; do
            [[ -d "$candidate/crt/include" ]] && { xwin="$candidate"; break; }
        done
    fi
    [[ -n "$xwin" && -d "$xwin/crt/include" ]] || die "no MSVC runtime and Windows SDK headers found" \
        "Run 'cargo xwin build --target x86_64-pc-windows-msvc' once, or set XWIN_DIR."
    c_flags+=(-imsvc "$xwin/crt/include" -imsvc "$xwin/sdk/include/ucrt"
        -imsvc "$xwin/sdk/include/um" -imsvc "$xwin/sdk/include/shared"
        -imsvc "$xwin/sdk/include/winrt")
fi
# MSYS_NO_PATHCONV, because Git Bash otherwise takes every MSVC option for a path and hands
# clang "/MT" as "C:/Program Files/Git/MT". Elsewhere it means nothing.
compile() {
    local source="$1" object="$2"
    shift 2
    MSYS_NO_PATHCONV=1 "$clang" "${c_flags[@]}" "$@" "$(tool_path "$source")" "/Fo$(tool_path "$object")" ||
        die "$source did not compile in MSVC mode"
}
c_dir="$PROJECT_DIR/desktop/c"
compile "$c_dir/win32_window.c" "$OUT_DIR/native/win32_window.obj" "/I$(tool_path "$c_dir")"
compile "$c_dir/win32_notifications.c" "$OUT_DIR/native/win32_notifications.obj"
compile "$PROJECT_DIR/windows/native/mingw_bridge.c" "$OUT_DIR/native/mingw_bridge.obj"
compile "$SKIKO_OUT/embedded_icu.cpp" "$OUT_DIR/native/embedded_icu.obj" \
    /std:c++17 /GR- -Wno-c23-extensions "/clang:--embed-dir=$(tool_path "$SKIKO_OUT/skia")"
# Apart from the rest: only an application that links the UCRT statically links it.
static_ucrt_object="$OUT_DIR/static-ucrt/mingw_bridge_static_ucrt.obj"
mkdir -p "$OUT_DIR/static-ucrt"
compile "$PROJECT_DIR/windows/native/mingw_bridge_static_ucrt.c" "$static_ucrt_object"
"$llvm_ar" rcs "$(tool_path "$OUT_DIR/native/dxc-windows-static-ucrt.lib")" "$(tool_path "$static_ucrt_object")"
rm -rf "$OUT_DIR/static-ucrt"
# One library holding the others, which the Host links whole. An object named on a link line would
# do, but a build script's link arguments stop at its own package, and a library it names
# does not: it travels in the rlib to every application. Whole, because nothing calls into
# the ICU loader or the initialiser the bridge registers, and a member nothing calls is a
# member the linker leaves out.
(cd "$OUT_DIR/native" && "$llvm_ar" rcs dxc-windows-native.lib win32_window.obj \
    win32_notifications.obj mingw_bridge.obj embedded_icu.obj)
cp "$SKIKO_OUT/skiko-bridges.lib" "$OUT_DIR/skiko/"
cp "$SKIKO_OUT"/skia/*.lib "$OUT_DIR/skiko/"

# 6. A static library missing an entry point links fine and fails at run time, so the two
# boundary symbols are checked here rather than in the application that links it.
nm_tool="$(command -v "${llvm_bin:+$llvm_bin/}llvm-nm" || command -v llvm-nm || command -v nm || true)"
[[ -n "$nm_tool" ]] || die "no llvm-nm or nm to read the archive's symbols with"
symbols_file="$LOG_DIR/$amper_platform-symbols.txt"
"$nm_tool" -g "$(tool_path "$archive")" 2>/dev/null | tr -d '\r' >"$symbols_file" || true
for symbol in compose_rust_renderer_run compose_rust_renderer_request_frame; do
    grep -q " T $symbol\$" "$symbols_file" ||
        die "$archive does not export $symbol" \
            "Check the @CName annotations in staticlib/src/IosEntryPoints.kt."
done

echo
echo "$OUT_DIR"
du -sh "$OUT_DIR"/* | sed 's|'"$OUT_DIR"'/||'

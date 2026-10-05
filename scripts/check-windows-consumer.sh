#!/usr/bin/env bash
# Usage: scripts/check-windows-consumer.sh <renderer> <scratch directory>
#
# Builds an application the way someone using this crate would, against the Kotlin/Native
# Windows renderer, checks that the executable needs nothing but Windows itself, moves it into
# an empty directory and starts it until the renderer has drawn. Runs on Windows, from Git Bash,
# with the MSVC tools installed (Visual Studio or its Build Tools, "Desktop development with
# C++"), and cl.exe and dumpbin.exe reachable on PATH or through vswhere.
#
#   <renderer>           the directory desktop/scripts/build-windows.sh writes; the application
#                        finds it through DXC_WINDOWS_NATIVE_LIB
#   <scratch directory>  where the applications are copied to; emptied first, and it should be
#                        somewhere that has nothing to do with the build
#
# What it proves, and why each part is here.
#
# 1. The application is built with nothing but its Cargo.toml line: no .cargo/config.toml and
#    no RUSTFLAGS. In particular nobody asks it for +crt-static, although Skia is built for the
#    static C runtime; the crate decides the runtime (compose-rust/build/windows_crt.rs).
# 2. The renderer is linked into the executable, so the executable is the whole application.
#    dumpbin says which DLLs the loader will look for, and every one has to be part of
#    Windows: no Java runtime, no VCRUNTIME140.dll or MSVCP140.dll, no MinGW DLL, no renderer
#    DLL. The UCRT's DLLs are part of Windows 10 and later and are allowed.
# 3. Copied alone into an empty directory it starts and draws its frames: no icudtl.dat, no
#    library beside it.
# 4. An application that also links a C++ library built for the runtime DLL (/MD), which is
#    what the `cc` crate builds by default, still links (the MSVC linker would otherwise stop
#    on LNK2038, a RuntimeLibrary mismatch), still needs only Windows, and runs.
# 5. The same application linked with vcruntime from its DLL
#    (DXC_WINDOWS_CRT=dynamic) and with everything static (+crt-static), measured beside the
#    default, so what each costs is a number and not a guess.
#
# A hosted runner has no graphics card. Windows' software adapter draws instead, which the
# window takes only when DXC_D3D12_WARP asks it to; this sets it unless the caller already has.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <renderer> <scratch directory>" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="$repo_root/compose-rust/tests/fixtures/consumer"
mixed_fixture="$repo_root/compose-rust/tests/fixtures/consumer-mixed-runtime"

fail() {
    echo "fail  $1" >&2
    shift
    for line in "$@"; do echo "      $line" >&2; done
    exit 1
}

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) ;;
    *) fail "this checks a Windows executable and runs on Windows only (this is $(uname -s))" ;;
esac
command -v cargo >/dev/null || fail "cargo is not on PATH"

renderer="$(cd "$1" && pwd)"
[[ -f "$renderer/libcompose_rust_renderer.a" ]] ||
    fail "no libcompose_rust_renderer.a in $renderer" "Run renderer/desktop/scripts/build-windows.sh first."

mkdir -p "$2"
scratch="$(cd "$2" && pwd)"
case "$scratch/" in
    "$repo_root"/*|"$renderer"/*)
        fail "the scratch directory is inside the build" \
            "Use one that is not, so nothing the build left can stand in for what the executable lacks." ;;
esac

# Visual Studio's tools, on PATH inside a developer prompt and found through vswhere otherwise.
vs_tool() {
    local name="$1" found vswhere
    found="$(command -v "$name" || true)"
    if [[ -z "$found" ]]; then
        vswhere="/c/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"
        [[ -f "$vswhere" ]] || fail "no $name on PATH and no vswhere to find Visual Studio with"
        found="$("$vswhere" -latest -products '*' -find "VC/Tools/MSVC/**/bin/Hostx64/x64/$name" | tr -d '\r' | head -1)"
        [[ -n "$found" ]] || fail "Visual Studio has no $name; install the C++ build tools"
        found="$(cygpath -u "$found")"
    fi
    echo "$found"
}
dumpbin="$(vs_tool dumpbin.exe)"
cl="$(vs_tool cl.exe)"
lib_tool="$(vs_tool lib.exe)"

# 1. Nothing but the Cargo.toml line. Cargo reads a config file from the directory it runs in
# and every one above it, so there must be none on the way up from where it is run, and none
# of the variables that carry flags.
unset RUSTFLAGS CARGO_ENCODED_RUSTFLAGS CARGO_BUILD_RUSTFLAGS
unset CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_RUSTFLAGS COMPOSE_RUST_RENDERER_DIR DXC_WINDOWS_CRT
directory="$repo_root"
while :; do
    for config in "$directory/.cargo/config.toml" "$directory/.cargo/config"; do
        [[ ! -f "$config" ]] || fail "$config exists" \
            "This proves an application needs no build settings, and Cargo would read that one."
    done
    parent="$(dirname "$directory")"
    [[ "$parent" != "$directory" ]] || break
    directory="$parent"
done

export DXC_WINDOWS_NATIVE_LIB
DXC_WINDOWS_NATIVE_LIB="$(cygpath -w "$renderer")"

# Target directories of its own inside the checkout, as the Linux check does, so this never
# shares a build with anything else running there. The builds with the same flags share one,
# so the dependencies are compiled once; each executable is measured and copied out before
# the next build writes over it.
build() {
    local manifest="$1" name="$2"
    CARGO_TARGET_DIR="$repo_root/target/windows-consumer-check/$name" \
        cargo build --manifest-path "$manifest" >&2
}
target="$repo_root/target/windows-consumer-check"

dependents() {
    "$dumpbin" -nologo -dependents "$(cygpath -w "$1")" | tr -d '\r' |
        sed -n 's/^ *\([A-Za-z0-9_.-]*\.[dD][lL][lL]\)$/\1/p' | sort -fu
}

system="$(cygpath -u "${SystemRoot:-C:\\Windows}")/System32"

# 2. Only Windows beneath it.
only_windows() {
    local binary="$1" dlls dll lower not_ours=()
    echo "-- dumpbin -dependents $(basename "$binary")"
    "$dumpbin" -nologo -dependents "$(cygpath -w "$binary")" | tr -d '\r' | sed -n '/Image has the following dependencies/,/Summary/p'
    dlls="$(dependents "$binary")"
    [[ -n "$dlls" ]] || fail "dumpbin named no DLL at all, which is not a Windows executable that opens a window"
    for dll in $dlls; do
        lower="$(echo "$dll" | tr '[:upper:]' '[:lower:]')"
        case "$lower" in
            vcruntime*|msvcp*|concrt*)
                fail "$(basename "$binary") needs $dll, the Visual C++ runtime" \
                    "vcruntime and the C++ library are to be linked in; see compose-rust/build/windows_crt.rs." ;;
            libgcc*|libstdc++*|libwinpthread*|*mingw*)
                fail "$(basename "$binary") needs $dll, part of MinGW" \
                    "MinGW is to stay inside the renderer's object; its runtime is linked in statically." ;;
            libcompose_rust_renderer*|*jvm*|*java*|awt*|skiko*|icu*)
                fail "$(basename "$binary") needs $dll, which is not part of Windows" \
                    "The renderer is meant to be inside the executable." ;;
            # API sets, which the loader resolves to Windows' own DLLs. The UCRT's among them
            # (api-ms-win-crt-*) are part of Windows 10 and later.
            api-ms-win-*|ext-ms-win-*) ;;
            *) [[ -f "$system/$dll" || -f "$system/$lower" ]] || not_ours+=("$dll") ;;
        esac
    done
    [[ "${#not_ours[@]}" -eq 0 ]] || fail "$(basename "$binary") needs DLLs Windows does not ship: ${not_ours[*]}"
    echo "-- $(echo "$dlls" | wc -l | tr -d ' ') DLLs, every one part of Windows: $(echo $dlls)"
}

size_of() { wc -c < "$1" | tr -d ' '; }
megabytes() { awk -v bytes="$1" 'BEGIN { printf "%.1f MB", bytes / 1048576 }'; }

echo "== 1. building the consumer with nothing but its Cargo.toml line"
build "$fixture/Cargo.toml" shared
binary="$target/shared/debug/consumer.exe"
[[ -f "$binary" ]] || fail "the build produced no $binary"

# The same application as a release build. rustc links an optimised build with /OPT:ICF, which
# folds identical functions, and the renderer object once failed there with LNK1223 while a
# debug build linked; fix-mingw-objects.py says why. Built so that cannot come back unseen.
echo "== 1b. the same application, release build"
CARGO_TARGET_DIR="$repo_root/target/windows-consumer-check/release" \
    cargo build --release --manifest-path "$fixture/Cargo.toml" >&2 ||
    fail "the release build did not link" "See fix-mingw-objects.py, point 3, for the LNK1223 case."
[[ -f "$target/release/release/consumer.exe" ]] || fail "the release build produced no consumer.exe"

echo "== 2. what it needs to start"
only_windows "$binary"
default_size="$(size_of "$binary")"

# Where a run that should have drawn crashed instead: the same executable, from where it was
# built so its symbols are beside it, under the console debugger Windows' SDK carries. Only
# ever on the way to failing, to say where, so a failure reads as a place and not a number.
explain_crash() {
    local binary="$1"
    shift
    local cdb="/c/Program Files (x86)/Windows Kits/10/Debuggers/x64/cdb.exe"
    [[ -f "$cdb" ]] || { echo "-- no cdb.exe to say where it crashed" >&2; return 0; }
    echo "-- where it crashed (cdb):" >&2
    ( cd "$(dirname "$binary")" &&
        MSYS_NO_PATHCONV=1 timeout 300 "$cdb" -lines -G -c "sxe av;g;.lastevent;kn 60;q" \
            "$(cygpath -w "$binary")" "$@" 2>&1 | tail -90 ) >&2 || true
}

run_alone() {
    local directory="$1" binary="$2"
    shift 2
    local status=0
    ( cd "$directory" && "./$(basename "$binary")" "$@" ) || status=$?
    if [[ "$status" -ne 0 ]]; then
        echo "-- $(basename "$binary") $* ended with status $status" >&2
        explain_crash "$binary" "$@"
        # Noted rather than stopped on, so the runs after this one still say what they say.
        failed+=("$(basename "$(dirname "$binary")")/$(basename "$binary") $* (status $status)")
    fi
}
failed=()

echo "== 3. starting it from an empty directory"
rm -rf "$scratch"
mkdir -p "$scratch/alone"
cp "$binary" "$scratch/alone/consumer.exe"
ls -la "$scratch/alone"
# The renderer cannot close its own window here, so the self-check ends the process once the
# frames are in.
unset COMPOSE_RUST_AUTOEXIT_MS
export DXC_D3D12_WARP="${DXC_D3D12_WARP:-1}"
# Started first without the window, which is everything before main returns: the loader,
# every static constructor of the renderer, Skia and the C++ runtimes. Then with it.
run_alone "$scratch/alone" "$binary"
run_alone "$scratch/alone" "$binary" --self-check

echo "== 4. beside a C++ library built for the runtime DLL (/MD)"
mixed_lib="$scratch/mixed-runtime-lib"
mkdir -p "$mixed_lib"
(
    cd "$mixed_lib"
    MSYS_NO_PATHCONV=1 "$cl" /nologo /c /MD /EHsc /O2 "$(cygpath -w "$mixed_fixture/mixed_runtime.cpp")" \
        /Fomixed_runtime.obj
    MSYS_NO_PATHCONV=1 "$lib_tool" /nologo /OUT:mixed_runtime.lib mixed_runtime.obj
) || fail "cl.exe could not build the /MD library"
# The library has to carry what makes the link fail when the runtime is not decided once,
# or this proves nothing.
grep -aq 'RuntimeLibrary=MD_DynamicRelease' "$mixed_lib/mixed_runtime.lib" ||
    fail "the /MD library carries no RuntimeLibrary guard, so it tests nothing"
MIXED_RUNTIME_LIB_DIR="$(cygpath -w "$mixed_lib")" build "$mixed_fixture/Cargo.toml" shared ||
    fail "an application with a C++ library built for the runtime DLL did not link" \
        "Look for LNK2038 (a RuntimeLibrary mismatch) above: some library still decides the runtime."
mixed_binary="$target/shared/debug/consumer-mixed-runtime.exe"
only_windows "$mixed_binary"
mkdir -p "$scratch/mixed"
cp "$mixed_binary" "$scratch/mixed/"
run_alone "$scratch/mixed" "$mixed_binary"

echo "== 5. the same application with the runtime from its DLLs, and with all of it static"
DXC_WINDOWS_CRT=dynamic build "$fixture/Cargo.toml" shared
dynamic_binary="$target/shared/debug/consumer.exe"
dependents "$dynamic_binary" | grep -qi '^vcruntime140' ||
    fail "the dynamic runtime build does not import VCRUNTIME140.dll, so it measured something else"
dynamic_size="$(size_of "$dynamic_binary")"
RUSTFLAGS="-C target-feature=+crt-static" build "$fixture/Cargo.toml" crt-static
static_binary="$target/crt-static/debug/consumer.exe"
only_windows "$static_binary"
mkdir -p "$scratch/crt-static"
cp "$static_binary" "$scratch/crt-static/consumer.exe"
run_alone "$scratch/crt-static" "$static_binary" --self-check
static_size="$(size_of "$static_binary")"

echo
echo "consumer.exe, debug build, by C runtime:"
echo "  vcruntime and C++ library linked in, UCRT from Windows (the default): $default_size bytes ($(megabytes "$default_size"))"
echo "  vcruntime from its DLL (DXC_WINDOWS_CRT=dynamic):                     $dynamic_size bytes ($(megabytes "$dynamic_size"))"
echo "  everything static, UCRT too (+crt-static):                           $static_size bytes ($(megabytes "$static_size"))"
if [[ "${#failed[@]}" -ne 0 ]]; then
    fail "these did not succeed from an empty directory: ${failed[*]}" \
        "Each one's output and, where it crashed, its stack are above."
fi
echo
echo "ok    an application depending only on compose-rust, with no build settings, links the"
echo "      Kotlin/Native renderer into one executable that needs only Windows, draws from an"
echo "      empty directory, and links beside a library built for the runtime DLL"

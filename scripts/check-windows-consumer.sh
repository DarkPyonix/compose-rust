#!/usr/bin/env bash
# Usage: scripts/check-windows-consumer.sh <renderer> <scratch directory>
#
# Builds an application the way someone using this crate would, against the Kotlin/Native
# Windows renderer, checks that the executable needs nothing but Windows itself, moves it into
# an empty directory and starts it until the renderer has drawn. Runs on Windows, from Git Bash,
# with the MSVC tools reachable (a Visual Studio developer environment, or vswhere to find one).
#
#   <renderer>           the directory desktop/scripts/build-windows.sh writes; the application
#                        finds it through DXC_WINDOWS_NATIVE_LIB
#   <scratch directory>  where the application is copied to; emptied first, and it should be
#                        somewhere that has nothing to do with the build
#
# What it proves, and why each part is here. The renderer is linked into the executable, so the
# executable is the whole application: it has to start from a directory holding nothing but
# itself, which is the test of every claim at once. No Java runtime, no Visual C++ runtime DLL
# (Skia is built with the C runtime linked in and so is the application), no MinGW DLL (the
# Kotlin/Native object's GCC runtime is linked in statically), and no icudtl.dat (Skia's ICU
# data is compiled in). dumpbin says which DLLs the loader will look for, and each has to be
# one Windows ships.
#
# A hosted runner has no graphics card. Windows' software adapter draws instead, which the
# window takes only when DXC_D3D12_WARP asks it to; this sets it unless the caller already has.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <renderer> <scratch directory>" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="$repo_root/dioxus-compose/tests/fixtures/consumer"

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
[[ -f "$renderer/libdioxus_compose_renderer.a" ]] ||
    fail "no libdioxus_compose_renderer.a in $renderer" "Run dioxus-compose-renderer/desktop/scripts/build-windows.sh first."

mkdir -p "$2"
scratch="$(cd "$2" && pwd)"
case "$scratch/" in
    "$repo_root"/*|"$renderer"/*)
        fail "the scratch directory is inside the build" \
            "Use one that is not, so nothing the build left can stand in for what the executable lacks." ;;
esac

# dumpbin is Visual Studio's. On PATH inside a developer prompt; found through vswhere
# otherwise.
dumpbin="$(command -v dumpbin || true)"
if [[ -z "$dumpbin" ]]; then
    vswhere="/c/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"
    [[ -f "$vswhere" ]] || fail "no dumpbin on PATH and no vswhere to find Visual Studio with"
    found="$("$vswhere" -latest -products '*' -find 'VC/Tools/MSVC/**/bin/Hostx64/x64/dumpbin.exe' | tr -d '\r' | head -1)"
    [[ -n "$found" ]] || fail "Visual Studio has no dumpbin.exe; install the C++ build tools"
    dumpbin="$(cygpath -u "$found")"
fi

# Its own target directory inside the checkout, as the Linux check does, so this never shares
# a build with anything else running there. The static C runtime is asked for the way an
# application asks for it, and the build script stops the build with that advice when it is not.
target_dir="$repo_root/target/windows-consumer-check"
echo "== building the consumer against $renderer"
unset DIOXUS_COMPOSE_RENDERER_DIR
DXC_WINDOWS_NATIVE_LIB="$(cygpath -w "$renderer")" \
    CARGO_TARGET_DIR="$target_dir" \
    RUSTFLAGS="-C target-feature=+crt-static" \
    cargo build --manifest-path "$fixture/Cargo.toml" >&2
binary="$target_dir/debug/consumer.exe"
[[ -f "$binary" ]] || fail "the build produced no $binary"

echo "== what it needs to start"
dependents="$("$dumpbin" -nologo -dependents "$(cygpath -w "$binary")" | tr -d '\r')"
echo "$dependents"
dlls="$(echo "$dependents" | sed -n 's/^ *\([A-Za-z0-9_.-]*\.[dD][lL][lL]\)$/\1/p' | sort -fu)"
[[ -n "$dlls" ]] || fail "dumpbin named no DLL at all, which is not a Windows executable that opens a window"
system="$(cygpath -u "${SystemRoot:-C:\\Windows}")/System32"
not_ours=()
for dll in $dlls; do
    lower="$(echo "$dll" | tr '[:upper:]' '[:lower:]')"
    case "$lower" in
        vcruntime*|msvcp*|ucrtbase*|api-ms-win-crt-*)
            fail "the executable needs $dll, the Visual C++ runtime" \
                "It should be linked in: build with -C target-feature=+crt-static." ;;
        libgcc*|libstdc++*|libwinpthread*|*mingw*)
            fail "the executable needs $dll, part of MinGW" \
                "MinGW is to stay inside the renderer's object; its runtime is linked in statically." ;;
        libdioxus_compose_renderer*|*jvm*|*java*|awt*|skiko*|icu*)
            fail "the executable needs $dll, which is not part of Windows" \
                "The renderer is meant to be inside the executable." ;;
        api-ms-win-*|ext-ms-win-*) ;;  # API sets the loader resolves to Windows' own DLLs
        *) [[ -f "$system/$dll" || -f "$system/$lower" ]] || not_ours+=("$dll") ;;
    esac
done
[[ "${#not_ours[@]}" -eq 0 ]] || fail "the executable needs DLLs Windows does not ship: ${not_ours[*]}"
size="$(wc -c < "$binary" | tr -d ' ')"
echo "-- $(echo "$dlls" | wc -l | tr -d ' ') DLLs, every one part of Windows: $(echo $dlls)"
echo "-- consumer.exe is $size bytes ($((size / 1048576)) MB)"

echo "== starting it from an empty directory"
rm -rf "$scratch"
mkdir -p "$scratch"
cp "$binary" "$scratch/consumer.exe"
ls -la "$scratch"
# The renderer cannot close its own window here, so the self-check ends the process once the
# frames are in.
unset DIOXUS_COMPOSE_AUTOEXIT_MS
export DXC_D3D12_WARP="${DXC_D3D12_WARP:-1}"
( cd "$scratch" && ./consumer.exe --self-check )

echo "ok    an application depending only on compose-rust links the Kotlin/Native renderer into"
echo "      one executable that needs only Windows, and draws from an empty directory"

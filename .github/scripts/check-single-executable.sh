#!/usr/bin/env bash
# Usage: .github/scripts/check-single-executable.sh <renderer artifact .tar.gz> <scratch directory>
#
# Builds an application the way someone using this crate would, from a renderer artifact
# shaped exactly like the one the release publishes, and proves the result is one
# executable: it needs nothing but the system's own libraries, it carries no renderer,
# Skia or Java runtime beside it, and copied alone into an empty directory it opens its
# window and draws. With no ICU data file on the machine, it also has to lay Korean out
# correctly: every glyph found, words found whole, lines broken between words.
#
#   <renderer artifact>  compose-rust-renderer-v<version>-<target>.tar.gz, with its
#                        .sha256 beside it. The version has to be this checkout's crate
#                        version, because that is the file the build script asks for.
#   <scratch directory>  emptied first; it must be outside the checkout, so the only
#                        renderer the application can find is the one inside it
#
# The artifact is not pointed at with a variable. It is put in a download cache of its
# own, which is where a build with no network looks, so the build script takes the same
# path a consumer's first build does: verify the checksum, unpack, link. The checkout this
# runs in must not have built a renderer of its own, or the workspace build would win.
#
# Needs a display. On Linux run it under `xvfb-run -a`; a macOS runner has one.
#
# On Windows (Git Bash, with the Visual Studio C++ tools installed) the tarball is the static
# kit build-windows.sh writes, packaged by package-static-renderer.sh. The build script has no
# download route for it yet, so the kit is unpacked into the scratch directory and handed to
# the build as DXC_WINDOWS_NATIVE_LIB, which links exactly what a consumer's build would.
# dumpbin (or llvm-readobj, or objdump) must then show Windows' own DLLs only; a hosted
# runner has no graphics card, so the window draws with Windows' software adapter.
#
# What it prints is the evidence: the executable's size, before and after stripping, and
# every library it names.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <renderer artifact .tar.gz> <scratch directory>" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture="$repo_root/compose-rust/tests/fixtures/consumer"
# check_window_start: what a run that came up prints, the same check every smoke run uses.
# shellcheck source=../../renderer/desktop/scripts/smoke-startup.sh
source "$repo_root/renderer/desktop/scripts/smoke-startup.sh"
# windows_dll_verdict, parse_dll_dependents: the rules for a Windows executable's imports.
# shellcheck source=windows-dlls.sh
source "$repo_root/.github/scripts/windows-dlls.sh"

fail() {
    echo "fail  $1" >&2
    shift
    for line in "$@"; do echo "      $line" >&2; done
    exit 1
}

os="$(uname -s)"
exe=""
case "$os" in
    Darwin|Linux) ;;
    MINGW*|MSYS*|CYGWIN*) os=Windows exe=.exe ;;
    *) fail "this checks macOS, Linux and Windows executables; $os is not one" ;;
esac
if [[ "$os" == Linux && -z "${DISPLAY:-}" ]]; then
    fail "DISPLAY is not set" "Run it under xvfb-run -a."
fi
command -v cargo >/dev/null || fail "cargo is not on PATH"

artifact="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
[[ -f "$artifact" ]] || fail "no artifact at $1"
[[ -f "$artifact.sha256" ]] || fail "no checksum beside it at $artifact.sha256" \
    "The build script verifies every artifact before unpacking it, as it will for a consumer."

crate_version="$(sed -n 's/^version = "\(.*\)"$/\1/p' "$repo_root/compose-rust/Cargo.toml" | head -1)"
case "$(basename "$artifact")" in
    "compose-rust-renderer-v$crate_version-"*.tar.gz) ;;
    *) fail "$(basename "$artifact") is not an artifact for compose-rust $crate_version" \
        "The build script downloads compose-rust-renderer-v$crate_version-<target>.tar.gz." ;;
esac

# The archive, not a shared library: one executable is what this checks.
# Listed into a variable first: grep -q stops reading early, and under pipefail the tar it
# cut off would fail the check.
contents="$(tar -tzf "$artifact")"
archive_pattern='^(\./)?lib/libcompose_rust_renderer\.a$'
[[ "$os" != Windows ]] || archive_pattern='^(\./)?libcompose_rust_renderer\.a$'
grep -qE "$archive_pattern" <<< "$contents" ||
    fail "$(basename "$artifact") does not carry the static renderer archive" \
        "Only the static renderer makes an application one executable."

# A workspace build in this checkout would be found before the cache, and then this would
# test that instead of the artifact.
for built in renderer/build/macos renderer/build/linux renderer/build/windows \
             renderer/build/linux-arm64 renderer/build/native-image/dist/lib; do
    [[ ! -e "$repo_root/$built" ]] || fail "$built exists in this checkout" \
        "The build script would link that instead of the artifact. Run this in a clean checkout."
done

mkdir -p "$2"
scratch="$(cd "$2" && pwd)"
case "$scratch/" in
    "$repo_root"/*) fail "the scratch directory is inside the checkout" \
        "Use one that is not, so the application cannot reach anything the build left." ;;
esac
rm -rf "$scratch"
mkdir -p "$scratch/cache/downloads" "$scratch/run"
cp "$artifact" "$artifact.sha256" "$scratch/cache/downloads/"

# Nothing may point the build anywhere else.
unset COMPOSE_RUST_RENDERER_DIR DIOXUS_COMPOSE_RENDERER_DIR DXC_MACOS_NATIVE_LIB DXC_LINUX_NATIVE_LIB
unset DXC_WINDOWS_NATIVE_LIB DXC_WINDOWS_CRT RUSTFLAGS CARGO_ENCODED_RUSTFLAGS CARGO_BUILD_RUSTFLAGS
# The Kotlin/Native renderer cannot close its own window, so the self-check ends the
# process once the frames are in.
unset COMPOSE_RUST_AUTOEXIT_MS
export COMPOSE_RUST_CACHE_DIR="$scratch/cache"
# The renderer lays Korean out before it opens the window and says whether every glyph was
# found, the word around a syllable was the whole word, and lines broke between words. It
# runs with no ICU data file on the machine, which is checked below, so what it proves is
# that the executable needs none.
export COMPOSE_RUST_TEXT_SELF_CHECK=1

# No ICU data file anywhere an application could be pointed at one. The executable must find
# words and line breaks with what is linked into it. A runner that ships one (a browser
# carries icudtl.dat) has to have it removed before this runs.
if [[ "$os" == Windows ]]; then
    # Git Bash sees the drives as /c, /d. The system and program directories are where a
    # browser or a runtime keeps one; the checkout and the scratch directory are not searched,
    # because the kit's own skia/icudtl.dat is not in the tarball and must not be.
    icu_roots=()
    for root in "/c/Program Files" "/c/Program Files (x86)" "/c/ProgramData" "/c/Users" \
                "/c/Windows" "/c/hostedtoolcache" "/c/msys64" "/d/a/_tool" "/d/a/_temp"; do
        [[ ! -d "$root" ]] || icu_roots+=("$root")
    done
    icu_files="$(find "${icu_roots[@]}" -name 'icudtl.dat' 2>/dev/null || true)"
else
    icu_files="$(find /usr /opt /Library /Applications /home /Users -name 'icudtl.dat' 2>/dev/null || true)"
fi
[[ -z "$icu_files" ]] || fail "an ICU data file is on this machine, so the text check would not prove anything" \
    "$icu_files"

if [[ "$os" == Windows ]]; then
    # The tarball root is the kit root, as build-windows.sh writes it and build.rs reads it.
    kit="$scratch/kit"
    mkdir -p "$kit"
    tar -xzf "$artifact" -C "$kit"
    [[ -f "$kit/libcompose_rust_renderer.a" ]] || fail "the unpacked kit has no libcompose_rust_renderer.a at its root"
    for piece in gcc/libstdc++.a native/dxc-windows-native.lib native/dxc-windows-static-ucrt.lib \
                 skiko/skiko-bridges.lib skiko/skia.lib; do
        [[ -f "$kit/$piece" ]] || fail "the kit has no $piece" \
            "package-static-renderer.sh packs the whole kit build-windows.sh writes for windows-x64."
    done
    find "$kit" -name 'icudtl.dat' | grep . && fail "the kit carries an icudtl.dat" \
        "The ICU data is to be inside the renderer's object, not beside it."
    DXC_WINDOWS_NATIVE_LIB="$(cygpath -w "$kit")"
    export DXC_WINDOWS_NATIVE_LIB
    # A hosted runner has no card: Windows' software adapter draws instead.
    export DXC_D3D12_WARP="${DXC_D3D12_WARP:-1}"
fi

# What every system the application may name lives under. Anything else is a file that
# would have to travel with it.
system_library() {
    case "$os" in
        Darwin) [[ "$1" == /usr/lib/* || "$1" == /System/Library/* ]] ;;
        Linux) [[ "$1" == /lib/* || "$1" == /lib64/* || "$1" == /usr/lib/* || "$1" == /usr/lib64/* ]] ;;
    esac
}

# Visual Studio's tools, on PATH inside a developer prompt and found through vswhere otherwise.
# Prints nothing when there is none.
vs_tool() {
    local name="$1" found vswhere
    found="$(command -v "$name" || true)"
    if [[ -z "$found" ]]; then
        vswhere="/c/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"
        [[ -f "$vswhere" ]] || return 0
        found="$("$vswhere" -latest -products '*' -find "VC/Tools/MSVC/**/bin/Hostx64/x64/$name" | tr -d '\r' | head -1)"
        [[ -z "$found" ]] || found="$(cygpath -u "$found")"
    fi
    echo "$found"
}

# What a Windows executable asks the loader for: dumpbin, else LLVM's readobj, else objdump.
windows_imports() {
    local binary="$1" tool windows_path
    windows_path="$(cygpath -w "$binary")"
    tool="$(vs_tool dumpbin.exe)"
    if [[ -n "$tool" ]]; then
        echo "-- dumpbin /dependents $(basename "$binary")" >&2
        "$tool" -nologo -dependents "$windows_path" | tr -d '\r' | tee /dev/stderr | parse_dll_dependents
        return
    fi
    tool="$(command -v llvm-readobj || true)"
    if [[ -n "$tool" ]]; then
        echo "-- llvm-readobj --coff-imports $(basename "$binary")" >&2
        "$tool" --coff-imports "$binary" | tee /dev/stderr | parse_dll_dependents
        return
    fi
    tool="$(command -v objdump || true)"
    [[ -n "$tool" ]] || fail "no dumpbin, llvm-readobj or objdump to list the executable's DLLs with"
    echo "-- objdump -p $(basename "$binary")" >&2
    "$tool" -p "$binary" | grep -a 'DLL Name' | tee /dev/stderr | parse_dll_dependents
}

check_windows_libraries() {
    local binary="$1" failures=0 dlls dll
    dlls="$(windows_imports "$binary")"
    [[ -n "$dlls" ]] || fail "no DLL was listed for $(basename "$binary"), which is not a Windows executable that opens a window"
    for dll in $dlls; do
        case "$(windows_dll_verdict "$dll")" in
            allowed) ;;
            forbidden)
                echo "      $dll is not Windows': a runtime, the renderer or a Java library that is to be inside the executable" >&2
                failures=$((failures + 1)) ;;
            *)
                echo "      $dll is not on the allowlist of Windows' own DLLs" >&2
                failures=$((failures + 1)) ;;
        esac
    done
    # A statically linked JNI library announces itself with JNI_OnLoad_<name>.
    if grep -aq 'JNI_OnLoad' "$binary"; then
        echo "      the executable carries a JNI library, so a Java runtime is linked into it" >&2
        failures=$((failures + 1))
    fi
    [[ "$failures" -eq 0 ]] || fail "$(basename "$binary") needs $failures thing(s) that are not Windows', from these DLLs:" $dlls
    echo "-- $(wc -w <<< "$dlls" | tr -d ' ') DLLs, every one part of Windows: $(echo $dlls)"
}

# Prints what the executable names and fails on anything that is not the system's.
check_libraries() {
    local binary="$1" failures=0 library resolved
    if [[ "$os" == Windows ]]; then
        check_windows_libraries "$binary"
        return
    fi
    if [[ "$os" == Darwin ]]; then
        echo "-- otool -L $(basename "$binary")"
        otool -L "$binary" | tail -n +2
        while read -r library; do
            system_library "$library" || {
                echo "      not a system library: $library" >&2
                failures=$((failures + 1))
            }
        done < <(otool -L "$binary" | tail -n +2 | awk '{ print $1 }')
        if otool -l "$binary" | grep -q LC_RPATH; then
            echo "      the executable carries an rpath, so it expects something beside it" >&2
            failures=$((failures + 1))
        fi
    else
        echo "-- readelf -d $(basename "$binary") (NEEDED, RPATH, RUNPATH)"
        readelf -d "$binary" | grep -E '\((NEEDED|RPATH|RUNPATH)\)' || true
        if readelf -d "$binary" | grep -qE '\((RPATH|RUNPATH)\)'; then
            echo "      the executable carries a search path, so it expects something beside it" >&2
            failures=$((failures + 1))
        fi
        while read -r library; do
            [[ "$library" != */* ]] || {
                echo "      named by path, so it is not found the way a system library is: $library" >&2
                failures=$((failures + 1))
                continue
            }
            # Where the loader finds it on this machine, which has to be the system's own
            # directories: a library only this build has would be found nowhere else.
            # `name => path`, or for the dynamic loader itself, the path alone.
            resolved="$(ldd "$binary" | awk -v name="$library" '
                $1 == name { print $3; exit }
                $1 ~ ("/" name "$") { print $1; exit }')"
            if [[ -z "$resolved" || "$resolved" == "not" ]] || ! system_library "$resolved"; then
                echo "      $library resolves to '${resolved:-nothing}', which is not a system directory" >&2
                failures=$((failures + 1))
            fi
        done < <(readelf -d "$binary" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
        echo "-- ldd $(basename "$binary")"
        ldd "$binary"
    fi
    # Named for what used to travel beside an application: the renderer, Skia, the Java
    # runtime's pieces, the Linux host exports library. (The system's own ICU, which macOS
    # keeps in /usr/lib, passed the check above and is not one of these.)
    if [[ "$os" == Darwin ]]; then
        otool -L "$binary" | tail -n +2 | awk '{ print $1 }'
    else
        readelf -d "$binary" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'
    fi | grep -iE 'dioxus|skiko|skia|jvm|jawt|awt|java' && {
        echo "      the executable still names a library of the renderer's" >&2
        failures=$((failures + 1))
    }
    # And nothing of a Java runtime linked into it either: a native image carries one,
    # and a statically linked JNI library announces itself with JNI_OnLoad_<name>.
    local symbols
    symbols="$(nm "$binary" 2>/dev/null || true)"
    if grep -E ' _?JNI_OnLoad' <<< "$symbols"; then
        echo "      the executable carries a JNI library, so a Java runtime is linked into it" >&2
        failures=$((failures + 1))
    fi
    [[ "$failures" -eq 0 ]] || fail "$(basename "$binary") needs $failures thing(s) that are not the system's"
}

size_of() {
    if [[ "$os" == Darwin ]]; then stat -f %z "$1"; else wc -c < "$1" | tr -d ' '; fi
}

megabytes() {
    awk -v bytes="$1" 'BEGIN { printf "%.2f MB (%d bytes)", bytes / 1000000, bytes }'
}

# Builds the fixture into a target directory of its own inside the checkout's target/, as
# consumer-crate.test.sh does, so it never shares a build with anything else.
build() {
    local name="$1" flags="$2" log
    log="$scratch/build-$name.log"
    echo "== building the consumer ($name) against the artifact" >&2
    if ! CARGO_TARGET_DIR="$repo_root/target/single-executable/$name" RUSTFLAGS="$flags" \
        cargo build --release --manifest-path "$fixture/Cargo.toml" 2>&1 | tee "$log" >&2; then
        fail "the consumer did not build against the artifact; the log is above"
    fi
    # The build script says where it unpacked the renderer from. It has to be the cache
    # this run made, which is the route a consumer's first build takes.
    grep -q "unpacked the renderer for v$crate_version" "$log" ||
        grep -q "$scratch/cache/renderer" "$log" ||
        echo "note  the build log does not say it unpacked the artifact (a cached build replays nothing)" >&2
    echo "$repo_root/target/single-executable/$name/release/consumer$exe"
}

run_alone() {
    local binary="$1" name="$2"
    local directory="$scratch/run/$name"
    rm -rf "$directory"
    mkdir -p "$directory"
    cp "$binary" "$directory/consumer$exe"
    echo "-- ls -la $directory"
    ls -la "$directory"
    [[ "$(ls -A "$directory")" == "consumer$exe" ]] || fail "something other than the executable is in $directory"
    echo "== running it alone in $directory"
    local log="$scratch/run-$name.log" status=0
    ( cd "$directory" && "./consumer$exe" --self-check ) > "$log" 2>&1 || status=$?
    cat "$log"
    grep -q "compose-rust text self-check: ok" "$log" ||
        fail "the renderer did not lay Korean text out correctly with no ICU data file" \
            "Its own report is above."
    [[ "$status" -eq 0 ]] || fail "the executable did not draw on its own (exit $status)"
    # And the renderer said so itself: its window opened, its scene was made on the main
    # dispatcher this platform has, and a frame reached the window.
    if [[ "$os" == Windows ]]; then
        # The Windows renderer's start lines are not the ones the other two print (it has no
        # main dispatcher of the platform's), so what proves it drew is the exit status of the
        # self-check, which the executable returns only once frames went through.
        return 0
    fi
    local dispatcher="$smoke_main_dispatcher_kn_linux"
    [[ "$os" != Darwin ]] || dispatcher="$smoke_main_dispatcher_kn_macos"
    check_window_start "$(cat "$log")" "$status" "$dispatcher" ||
        fail "the renderer did not report its window, its scene and a frame" "Its own output is above."
}

report() {
    local binary="$1" stripped="$scratch/consumer.stripped"
    if [[ "$os" == Windows ]]; then
        # A release MSVC executable keeps its symbols in a .pdb beside it, so it is as small
        # as it gets.
        echo "-- size: $(megabytes "$(size_of "$binary")")"
        return
    fi
    cp "$binary" "$stripped"
    if [[ "$os" == Darwin ]]; then strip -x "$stripped"; else strip "$stripped"; fi
    echo "-- size: $(megabytes "$(size_of "$binary")"), stripped $(megabytes "$(size_of "$stripped")")"
    rm -f "$stripped"
}

binary="$(build default "")"
[[ -x "$binary" ]] || fail "the build produced no $binary"
[[ "$os" == Windows ]] || ls "$scratch/cache/renderer"/v"$crate_version"/* >/dev/null 2>&1 ||
    fail "the build did not unpack the artifact into $scratch/cache/renderer" \
        "It found a renderer somewhere else, so this did not test the artifact."
check_libraries "$binary"
report "$binary"
run_alone "$binary" default

if [[ "$os" == Linux ]] && grep -qi 'lld' <<< "$(readelf -p .comment "$binary" 2>/dev/null || true)"; then
    # GNU ld and LLVM's lld do not agree on everything an archive's weak references are
    # owed, and an application may be linked with either. The toolchain linked the one
    # above with lld; this is GNU ld.
    second="$(build gnu-ld "-Clink-arg=-fuse-ld=bfd")"
    check_libraries "$second"
    run_alone "$second" gnu-ld
fi

echo "ok    an application depending only on compose-rust is one executable"
echo "      $(basename "$artifact"): $(megabytes "$(size_of "$artifact")")"

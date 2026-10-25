#!/usr/bin/env bash
# .github/scripts/package-static-renderer.sh: what a static kit tarball holds per target.
#
# The windows-x64 kit is the Kotlin/Native renderer plus the pieces an MSVC link needs beside
# it. A kit that loses one of them links nowhere, so the packager has to refuse it, and the
# tarball has to carry all of them with the same lib/ and include/ layout as the others.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
packager="$repo_root/.github/scripts/package-static-renderer.sh"
work="$(mktemp -d "${TMPDIR:-/tmp}/package-static.XXXXXX")"
trap 'rm -rf "$work"' EXIT

failures=0
pass() { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; failures=$((failures + 1)); }

make_unix_kit() {
    mkdir -p "$1"
    echo a > "$1/libcompose_rust_renderer.a"
    echo h > "$1/libcompose_rust_renderer_api.h"
    echo 0x0000000000000001 > "$1/schema-hash.txt"
}
make_windows_kit() {
    make_unix_kit "$1"
    mkdir -p "$1/gcc" "$1/native" "$1/skiko"
    for f in gcc/libstdc++.a gcc/libgcc.a gcc/libgcc_eh.a gcc/libwinpthread.a \
             native/dxc-windows-native.lib native/dxc-windows-static-ucrt.lib \
             skiko/skiko-bridges.lib skiko/skia.lib skiko/skparagraph.lib; do
        echo x > "$1/$f"
    done
    echo obj > "$1/native/win32_window.obj"
}

# windows-x64 packages the whole kit
make_windows_kit "$work/win"
if out="$("$packager" "$work/win" windows-x64 1.2.3 "$work/out" 2>&1)"; then
    tarball="$work/out/compose-rust-renderer-v1.2.3-windows-x64.tar.gz"
    list="$(tar -tzf "$tarball")"
    [[ -f "$tarball.sha256" ]] && pass "windows-x64 writes the checksum" || fail "no checksum"
    for entry in libcompose_rust_renderer.a libcompose_rust_renderer_api.h \
                 schema-hash.txt renderer.version gcc/libstdc++.a gcc/libwinpthread.a \
                 native/dxc-windows-native.lib native/dxc-windows-static-ucrt.lib \
                 skiko/skiko-bridges.lib skiko/skia.lib skiko/skparagraph.lib; do
        grep -qxF -e "$entry" -e "./$entry" <<< "$list" && pass "windows-x64 carries $entry" || fail "windows-x64 lacks $entry"
    done
    ! grep -qE '(^|/)(lib|include)/' <<< "$list" && pass "windows-x64 keeps the kit root layout" || fail "windows-x64 uses lib/ or include/"
    ! grep -q '\.obj$' <<< "$list" && pass "windows-x64 carries no loose objects" || fail "windows-x64 carries an .obj"
else
    fail "windows-x64 packaging failed: $out"
fi

# a Windows kit missing the static-UCRT bridge is refused
rm "$work/win/native/dxc-windows-static-ucrt.lib"
if "$packager" "$work/win" windows-x64 1.2.3 "$work/out2" >/dev/null 2>&1; then
    fail "a Windows kit with no dxc-windows-static-ucrt.lib was packaged"
else
    pass "a Windows kit with no dxc-windows-static-ucrt.lib is refused"
fi

# the other targets need none of it
make_unix_kit "$work/linux"
if "$packager" "$work/linux" linux-x64 1.2.3 "$work/out3" >/dev/null 2>&1; then
    pass "linux-x64 still packages the archive alone"
else
    fail "linux-x64 packaging broke"
fi

# an unknown target is still refused
if "$packager" "$work/linux" windows-arm64 1.2.3 "$work/out4" >/dev/null 2>&1; then
    fail "windows-arm64 was packaged"
else
    pass "an unknown target is refused"
fi

[[ "$failures" -eq 0 ]] || exit 1
echo "all passed"

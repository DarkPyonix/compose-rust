#!/usr/bin/env bash
# The sample build takes the renderer of the release for its crate version on every desktop
# platform, Windows included, and builds it only when the release does not carry it. For
# Windows that needs the release's windows-x64 tarball to be the Kotlin/Native static kit,
# so this also checks that a tag releases that kit and not the GraalVM DLL.
#
# Reads the workflows as text and runs .github/scripts/windows-renderer-is-static.sh on
# tarballs made here. Builds nothing.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
samples="$repo_root/.github/workflows/test-samples.yml"
native="$repo_root/.github/workflows/test-native-renderer.yml"
windows_static="$repo_root/.github/workflows/windows-static.yml"
is_static="$repo_root/.github/scripts/windows-renderer-is-static.sh"

work="$(mktemp -d "${TMPDIR:-/tmp}/samples-released.XXXXXX")"
trap 'rm -rf "$work"' EXIT
failures=0
ok() { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; failures=$((failures + 1)); }

# One job of a workflow: from "  <name>:" to the next job at the same indent.
job() { awk -v name="  $2:" '
    $0 == name { on = 1; print; next }
    on && /^  [A-Za-z0-9_-]+:$/ { exit }
    on { print }' "$1"; }

# One step of a workflow: from "- name: <name>" to the next step.
step() { awk -v name="      - name: $2" '
    $0 == name { on = 1; print; next }
    on && /^      - (name|uses):/ { exit }
    on && /^  [A-Za-z0-9_-]+:$/ { exit }
    on { print }' "$1"; }

nfr15_windows_kit_tarball_is_recognised() {
    local kit="$work/kit" graal="$work/graal"
    mkdir -p "$kit/native" "$kit/gcc" "$kit/skiko" "$graal/bin" "$graal/include"
    touch "$kit/libcompose_rust_renderer.a" "$kit/libcompose_rust_renderer_api.h" \
        "$kit/schema-hash.txt" "$kit/native/dxc-windows-native.lib" "$kit/skiko/skia.lib"
    touch "$graal/bin/compose_rust_renderer.dll" "$graal/include/compose_rust_renderer.h" \
        "$graal/awt.dll"
    tar -czf "$work/kit.tar.gz" -C "$kit" .
    tar -czf "$work/graal.tar.gz" -C "$graal" .
    echo "not a tarball" > "$work/broken.tar.gz"

    local out
    out="$("$is_static" "$work/kit.tar.gz" 2>/dev/null)"
    [[ "$out" == "windows_static=true" ]] && ok "the static kit is taken" \
        || fail "the static kit gave: $out"
    out="$("$is_static" "$work/graal.tar.gz" 2>/dev/null)"
    [[ "$out" == "windows_static=false" ]] && ok "a GraalVM tarball is not taken" \
        || fail "a GraalVM tarball gave: $out"
    out="$("$is_static" "$work/broken.tar.gz" 2>/dev/null)"
    [[ "$out" == *"windows_static=false"* && "$out" == *"::warning"* ]] \
        && ok "an unreadable tarball is not taken, and says so" \
        || fail "an unreadable tarball gave: $out"
}

nfr15_samples_skip_the_renderer_builds_when_released() {
    local resolve
    resolve="$(job "$samples" resolve)"
    grep -qF 'windows_released: ${{ steps.find.outputs.windows_released }}' <<<"$resolve" \
        && ok "the resolve job reports whether Windows is released" \
        || fail "the resolve job has no windows_released output"
    grep -qF 'windows-renderer-is-static.sh' <<<"$resolve" \
        && ok "the resolve job checks the Windows tarball is the static kit" \
        || fail "the resolve job does not look inside the Windows tarball"
    grep -qF 'find-released-renderer.sh' <<<"$resolve" \
        && ok "the resolve job asks for all four desktop assets" \
        || fail "the resolve job does not run find-released-renderer.sh"

    job "$samples" static-renderer | grep -qF "if: needs.resolve.outputs.released != 'true'" \
        && ok "static-renderer runs only without a release" \
        || fail "static-renderer is not skipped for a released renderer"
    local win
    win="$(job "$samples" windows-static)"
    grep -qF "if: needs.resolve.outputs.windows_released != 'true'" <<<"$win" \
        && grep -qE '^    needs: \[?(requested, )?resolve\]?$' <<<"$win" \
        && ok "windows-static runs only without a released static kit" \
        || fail "windows-static is not skipped for a released static kit"
}

nfr15_samples_build_windows_against_the_verified_release() {
    local get
    get="$(step "$samples" 'Download the released Windows renderer kit')"
    [[ -n "$get" ]] && ok "the build has a step that downloads the Windows kit" \
        || { fail "no step downloads the released Windows kit"; return; }
    grep -qF "needs.resolve.outputs.windows_released == 'true'" <<<"$get" \
        && ok "it runs when the release carries the kit" || fail "it is not gated on the release"
    grep -qF 'sha256sum -c' <<<"$get" \
        && ok "it checks the kit against its .sha256" || fail "it does not verify the checksum"
    grep -qF 'renderer-kit' <<<"$get" \
        && ok "it unpacks where DXC_WINDOWS_NATIVE_LIB points" || fail "it unpacks somewhere the build does not read"
    step "$samples" 'Take the Windows renderer kit' \
        | grep -qF "needs.resolve.outputs.windows_released != 'true'" \
        && ok "the built kit is taken only when nothing was released" \
        || fail "the built kit is taken even when the release carries one"
    step "$samples" 'Download the released renderer' | grep -qF 'sha256sum -c' \
        && ok "macOS and Linux verify the released renderer too" \
        || fail "macOS and Linux do not verify the released renderer"
}

nfr15_samples_dispatch_keeps_the_tag_input() {
    awk '/^  workflow_dispatch:/,/^concurrency:/' "$samples" | grep -qE '^      tag:$' \
        && ok "workflow_dispatch still takes a tag" || fail "workflow_dispatch lost its tag input"
    local refs
    refs="$(grep -c 'ref: \${{ inputs.tag || github.ref }}' "$samples")"
    [[ "$refs" -ge 2 ]] && ok "the resolve and build checkouts follow the tag" \
        || fail "only $refs checkouts follow the tag input"
}

nfr15_tag_releases_the_windows_static_kit() {
    grep -q 'Windows is left out of a tag' "$native" \
        && fail "a tag still leaves Windows out of the one-executable check" \
        || ok "a tag checks Windows as one executable"
    grep -qF 'compose-rust-renderer-${{ github.ref_name }}-windows-x64' "$native" \
        && fail "a tag still packages the GraalVM image as the windows-x64 release asset" \
        || ok "the GraalVM image is not a release asset"
    step "$windows_static" 'Package the kit the way the release carries it' \
        | grep -qF "!startsWith(github.ref, 'refs/tags/v')" \
        && fail "windows-static does not package the kit on a tag" \
        || ok "windows-static packages the kit on a tag"
    job "$native" release | grep -qE '^    needs: \[.*windows-static.*\]$' \
        && ok "the release waits for the Windows kit" \
        || fail "the release does not wait for windows-static"
}

nfr15_windows_kit_tarball_is_recognised
nfr15_samples_skip_the_renderer_builds_when_released
nfr15_samples_build_windows_against_the_verified_release
nfr15_samples_dispatch_keeps_the_tag_input
nfr15_tag_releases_the_windows_static_kit

[[ "$failures" -eq 0 ]] || { echo "$failures failed"; exit 1; }
echo "all passed"

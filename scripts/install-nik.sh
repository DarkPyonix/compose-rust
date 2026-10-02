#!/usr/bin/env bash
# Usage: ./scripts/install-nik.sh [--github-env]
#
# Installs Liberica NIK 25 Full, the only toolchain that builds the Compose
# Desktop renderer as a native image on macOS. Upstream GraalVM skips AWT
# support on Darwin, so its native-image cannot link Compose Desktop at all;
# Liberica NIK Full statically links AWT and can.
#
# The install is addressed by version, so a warm cache -- a developer's home
# directory, or a restored GitHub Actions cache -- makes this a no-op: the
# ~1GB download happens once per NIK version, not once per CI run. That
# idempotency is what scripts/tests/install-nik.test.sh pins down.
#
# The archive URL and its SHA-1 are pinned below. BellSoft publishes both at
# https://api.bell-sw.com/v1/nik/releases; refresh them together when bumping
# NIK_VERSION, and keep the version in step with the developer machines.
#
#   --github-env   also append GRAALVM_HOME to $GITHUB_ENV, for CI steps.
#
# Prints the resulting GRAALVM_HOME on stdout; every other message goes to
# stderr, so `GRAALVM_HOME="$(scripts/install-nik.sh)"` works.

set -euo pipefail

NIK_VERSION="25.0.4.1"
NIK_JDK_BUILD="25.0.4.1+2"
NIK_VM_BUILD="25.0.4.1+1"

github_env=0
case "${1:-}" in
    "") ;;
    --github-env) github_env=1 ;;
    *) echo "usage: $0 [--github-env]" >&2; exit 2 ;;
esac

case "$(uname -s)" in
    Darwin) os="macos" ;;
    *)
        echo "error: only macOS needs Liberica NIK today (it is the AWT-capable native-image toolchain); nothing to install on $(uname -s)" >&2
        exit 1
        ;;
esac

case "$(uname -m)" in
    arm64|aarch64) arch="aarch64"; sha1="d3d655788718174017879fb64b3a91679fcd1c31" ;;
    x86_64) arch="amd64"; sha1="ebffff946560504d19db0a011d0c1281ac3d901c" ;;
    *) echo "error: unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac

archive="bellsoft-liberica-vm-full-openjdk${NIK_JDK_BUILD}-${NIK_VM_BUILD}-${os}-${arch}.tar.gz"
url="https://github.com/bell-sw/LibericaNIK/releases/download/${NIK_VM_BUILD}-${NIK_JDK_BUILD}/${archive}"

install_root="${NIK_INSTALL_DIR:-$HOME/.cache/dioxus-compose/nik}"
graalvm_home="$install_root/${NIK_VERSION}-${os}-${arch}"

emit() {
    echo "$graalvm_home"
    if (( github_env )); then
        [[ -n "${GITHUB_ENV:-}" ]] || { echo "error: --github-env needs \$GITHUB_ENV" >&2; exit 1; }
        echo "GRAALVM_HOME=$graalvm_home" >> "$GITHUB_ENV"
    fi
}

# Check for the AWT static library, not just for native-image. An install can
# have a working native-image and still be useless here (build-native.sh needs
# lib/static/darwin-*/libawt_lwawt.a), and when that install is a restored CI
# cache, a short-circuit that trusts bin/native-image alone will keep handing
# back the broken directory on every run until the key changes.
nik_is_complete() {
    local home="$1"
    [[ -x "$home/bin/native-image" ]] || return 1
    compgen -G "$home/lib/static/darwin-*/libawt_lwawt.a" >/dev/null
}

if nik_is_complete "$graalvm_home"; then
    echo "Liberica NIK $NIK_VERSION already installed at $graalvm_home" >&2
    emit
    exit 0
fi

if [[ -d "$graalvm_home" ]]; then
    echo "Reinstalling: $graalvm_home exists but has no lib/static/darwin-*/libawt_lwawt.a" >&2
fi

echo "Downloading Liberica NIK $NIK_VERSION ($os/$arch)..." >&2
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl --fail --location --silent --show-error --retry 3 --output "$tmp/$archive" "$url"

echo "Verifying SHA-1..." >&2
actual="$(shasum -a 1 "$tmp/$archive" | cut -d' ' -f1)"
if [[ "$actual" != "$sha1" ]]; then
    echo "error: checksum mismatch for $archive" >&2
    echo "  expected $sha1" >&2
    echo "  actual   $actual" >&2
    exit 1
fi

# The macOS archive is a JDK bundle, so java.home sits a few levels down
# (.../Contents/Home). Locate it rather than guessing at --strip-components,
# then move it into place in a single rename: an interrupted run must never
# leave a half-populated $graalvm_home that the short-circuit above trusts.
mkdir -p "$tmp/stage" "$install_root"
tar -xzf "$tmp/$archive" -C "$tmp/stage"

# Locate java.home by the AWT static libraries, because they are the thing this
# project cannot build without. Do not search for bin/native-image instead: the
# bundle has two, and the only one `find -type f` matches is
# Contents/Home/lib/svm/bin/native-image (Contents/Home/bin/native-image is a
# symlink, which -type f skips). Treating lib/svm as java.home yields a
# GRAALVM_HOME that runs native-image and has no AWT at all.
static_dir="$(find "$tmp/stage" -type d -path '*/lib/static' -print -quit)"
[[ -n "$static_dir" ]] || { echo "error: no lib/static inside $archive; this is not the Full variant" >&2; exit 1; }
staged_home="$(cd "$static_dir/../.." && pwd)"
nik_is_complete "$staged_home" || {
    echo "error: $archive unpacked to $staged_home, which is not a usable NIK Full home" >&2
    exit 1
}

rm -rf "$graalvm_home" "$graalvm_home.partial"
mv "$staged_home" "$graalvm_home.partial"
mv "$graalvm_home.partial" "$graalvm_home"

echo "Installed Liberica NIK $NIK_VERSION at $graalvm_home" >&2
emit

#!/usr/bin/env bash
# Wraps a built application as an AppImage.
#
#     scripts/packager-linux/appimage.sh \
#         --dioxus-toml samples/calculator/Dioxus.toml --overlay linux.toml \
#         --payload staging/calculator --version 1.2.3 --out-dir dist
#
# writes dist/Calculator-1.2.3-x86_64.AppImage. The AppImage carries no update
# information: direct distribution is packaged and updated with Velopack, not here.
#
# Options:
#   --dioxus-toml, --overlay, --version, --date, --exec, --icon   as packager-linux takes them
#   --payload <dir|file>    the executable, or the directory holding it and its renderer
#   --sign-key <key id>     sign with this gpg key
#   --out-dir <dir>
#
# Tools: appimagetool and the AppImage runtime are downloaded at pinned versions and
# checked against pinned digests, into PACKAGER_LINUX_CACHE (default
# .scratch/packager-linux/tools in this repository).
# desktop-file-validate and appstreamcli are used when present.
#
# packager-linux itself is run through `cargo run` unless PACKAGER_LINUX names a built one.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"

fail() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

# Pinned tools. A digest that does not match stops the build rather than packaging with
# something nobody reviewed.
APPIMAGETOOL_VERSION=1.9.1
RUNTIME_VERSION=20251108
declare -A DIGESTS=(
    [appimagetool-x86_64.AppImage]=ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0
    [appimagetool-aarch64.AppImage]=f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158
    [runtime-x86_64]=2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d
    [runtime-aarch64]=00cbdfcf917cc6c0ff6d3347d59e0ca1f7f45a6df1a428a0d6d8a78664d87444
)

common=()
payload=""
sign_key=""
out_dir=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dioxus-toml | --overlay | --version | --date | --exec | --icon)
            [[ $# -ge 2 ]] || fail "$1 needs a value"
            common+=("$1" "$2")
            shift 2
            ;;
        --payload) payload="$2"; shift 2 ;;
        --sign-key) sign_key="$2"; shift 2 ;;
        --out-dir) out_dir="$2"; shift 2 ;;
        *) fail "unknown option $1" "See the comment at the top of $0." ;;
    esac
done
[[ -n "$payload" ]] || fail "--payload is required"
[[ -n "$out_dir" ]] || fail "--out-dir is required"

case "$(uname -m)" in
    x86_64) arch=x86_64 ;;
    aarch64 | arm64) arch=aarch64 ;;
    *) fail "no pinned AppImage tools for $(uname -m)" ;;
esac
[[ "$(uname -s)" == Linux ]] || fail "an AppImage is assembled on Linux"

packager() {
    if [[ -n "${PACKAGER_LINUX:-}" ]]; then
        "$PACKAGER_LINUX" "$@"
    else
        cargo run --quiet --release --manifest-path "$repo_root/Cargo.toml" \
            -p packager-linux -- "$@"
    fi
}

cache="${PACKAGER_LINUX_CACHE:-$repo_root/.scratch/packager-linux/tools}"
mkdir -p "$cache"

fetch() {
    local name="$1" url="$2" path="$cache/$1"
    local want="${DIGESTS[$name]}"
    if [[ ! -f "$path" ]] || [[ "$(sha256sum "$path" | cut -d' ' -f1)" != "$want" ]]; then
        echo "== fetching $name"
        curl --fail --location --silent --show-error --retry 3 -o "$path.part" "$url"
        local got
        got="$(sha256sum "$path.part" | cut -d' ' -f1)"
        [[ "$got" == "$want" ]] || fail "$name has digest $got, pinned is $want" \
            "Either the release was replaced or the download is damaged."
        mv "$path.part" "$path"
        chmod +x "$path"
    fi
}

fetch "appimagetool-$arch.AppImage" \
    "https://github.com/AppImage/appimagetool/releases/download/$APPIMAGETOOL_VERSION/appimagetool-$arch.AppImage"
fetch "runtime-$arch" \
    "https://github.com/AppImage/type2-runtime/releases/download/$RUNTIME_VERSION/runtime-$arch"

file_name="$(packager file-name "${common[@]}" --arch "$arch")"

mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd)"
appdir="$out_dir/${file_name%.AppImage}.AppDir"
rm -rf "$appdir" "$out_dir/$file_name"
packager appdir "${common[@]}" --arch "$arch" --payload "$payload" \
    --out "$appdir" >/dev/null

sign_args=()
if [[ -n "$sign_key" ]]; then
    sign_args=(--sign --sign-key "$sign_key")
fi

echo "== $file_name"
# appimagetool is itself an AppImage. Extract-and-run spares the build machine FUSE.
(
    cd "$out_dir"
    APPIMAGE_EXTRACT_AND_RUN=1 ARCH="$arch" "$cache/appimagetool-$arch.AppImage" \
        --runtime-file "$cache/runtime-$arch" \
        "${sign_args[@]}" \
        "$appdir" "$out_dir/$file_name"
)

rm -rf "$appdir"
(cd "$out_dir" && sha256sum "$file_name")

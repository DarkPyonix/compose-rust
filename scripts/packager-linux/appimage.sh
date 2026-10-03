#!/usr/bin/env bash
# Wraps a built application as an AppImage that updates itself, with the .zsync file to
# publish beside it.
#
#     scripts/packager-linux/appimage.sh \
#         --dioxus-toml samples/calculator/Dioxus.toml --overlay linux.toml \
#         --payload staging/calculator --version 1.2.3 \
#         --github darkpyonix/compose-rust --release samples-latest --out-dir dist
#
# writes dist/Calculator-1.2.3-x86_64.AppImage and dist/Calculator-1.2.3-x86_64.AppImage.zsync.
# Publish both as assets of that release; an installed AppImage finds the newer .zsync
# there through the update information embedded here.
#
# Options:
#   --dioxus-toml, --overlay, --version, --date, --exec, --icon   as packager-linux takes them
#   --payload <dir|file>    the executable, or the directory holding it and its renderer
#   --github <owner/repo>   update from a GitHub release of that repository
#   --release <tag>         with --github: the release's tag, or `latest` for the newest
#                           release that is not a pre-release
#   --zsync-url <url>       update from one fixed .zsync URL instead
#   --sign-key <key id>     sign with this gpg key; an installed AppImage that was signed
#                           refuses an update signed by any other key
#   --no-updater            do not carry appimageupdatetool inside
#   --out-dir <dir>
#
# Tools: appimagetool, the AppImage runtime and appimageupdatetool are downloaded at
# pinned versions and checked against pinned digests, into PACKAGER_LINUX_CACHE (default
# .scratch/packager-linux/tools in this repository). zsyncmake must be on PATH (Debian and
# Ubuntu: apt-get install zsync); appimagetool needs it to write the .zsync file and
# skips it silently otherwise, which would ship an AppImage that can never update.
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
UPDATER_VERSION=2.0.0-alpha-1-20251018
declare -A DIGESTS=(
    [appimagetool-x86_64.AppImage]=ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0
    [appimagetool-aarch64.AppImage]=f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158
    [runtime-x86_64]=2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d
    [runtime-aarch64]=00cbdfcf917cc6c0ff6d3347d59e0ca1f7f45a6df1a428a0d6d8a78664d87444
    [appimageupdatetool-x86_64.AppImage]=d976cdac667b03dee8cb23fb95ef74b042c406c5cbab3ff294d2b16efeaff84f
    [appimageupdatetool-aarch64.AppImage]=7aaf89dd4cf66ebd940d416c67e1c240c57a139cee38d9c0ed3bb9387bc435b0
)

common=()
payload=""
channel=()
release=()
sign_key=""
bundle_updater=1
out_dir=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dioxus-toml | --overlay | --version | --date | --exec | --icon)
            [[ $# -ge 2 ]] || fail "$1 needs a value"
            common+=("$1" "$2")
            shift 2
            ;;
        --payload) payload="$2"; shift 2 ;;
        --github | --zsync-url) channel=("$1" "$2"); shift 2 ;;
        --release) release=("$1" "$2"); shift 2 ;;
        --sign-key) sign_key="$2"; shift 2 ;;
        --no-updater) bundle_updater=0; shift ;;
        --out-dir) out_dir="$2"; shift 2 ;;
        *) fail "unknown option $1" "See the comment at the top of $0." ;;
    esac
done
[[ -n "$payload" ]] || fail "--payload is required"
[[ -n "$out_dir" ]] || fail "--out-dir is required"
[[ ${#channel[@]} -eq 2 ]] || fail "pass --github <owner/repo> or --zsync-url <url>" \
    "An AppImage without update information cannot update itself."
if [[ "${channel[0]}" == --github && ${#release[@]} -ne 2 ]]; then
    fail "--github needs --release <tag>" \
        "Name the release the AppImage looks in, or pass --release latest for the newest" \
        "release that is not a pre-release."
fi

case "$(uname -m)" in
    x86_64) arch=x86_64 ;;
    aarch64 | arm64) arch=aarch64 ;;
    *) fail "no pinned AppImage tools for $(uname -m)" ;;
esac
[[ "$(uname -s)" == Linux ]] || fail "an AppImage is assembled on Linux"
command -v zsyncmake >/dev/null || fail "zsyncmake is not on PATH" \
    "appimagetool writes the .zsync file with it and skips that step silently without it." \
    "Debian and Ubuntu: apt-get install zsync."

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

updater_args=()
if [[ $bundle_updater -eq 1 ]]; then
    fetch "appimageupdatetool-$arch.AppImage" \
        "https://github.com/AppImageCommunity/AppImageUpdate/releases/download/$UPDATER_VERSION/appimageupdatetool-$arch.AppImage"
    extracted="$cache/appimageupdatetool-$arch-$UPDATER_VERSION"
    if [[ ! -e "$extracted/AppRun" ]]; then
        rm -rf "$extracted" "$cache/squashfs-root"
        # Extracting reads the image without mounting it, so no FUSE is needed here.
        (cd "$cache" && "./appimageupdatetool-$arch.AppImage" --appimage-extract >/dev/null)
        mv "$cache/squashfs-root" "$extracted"
    fi
    updater_args=(--updater "$extracted")
fi

update_information="$(packager update-information "${common[@]}" --arch "$arch" "${channel[@]}" ${release[@]+"${release[@]}"})"
file_name="$(packager file-name "${common[@]}" --arch "$arch")"

mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd)"
appdir="$out_dir/${file_name%.AppImage}.AppDir"
rm -rf "$appdir" "$out_dir/$file_name" "$out_dir/$file_name.zsync"
packager appdir "${common[@]}" --arch "$arch" --payload "$payload" \
    "${updater_args[@]}" --out "$appdir" >/dev/null

sign_args=()
if [[ -n "$sign_key" ]]; then
    sign_args=(--sign --sign-key "$sign_key")
fi

echo "== $file_name"
echo "   update information: $update_information"
# appimagetool is itself an AppImage. Extract-and-run spares the build machine FUSE.
# It runs from the output directory because zsyncmake writes the .zsync file into the
# directory it is started in.
(
    cd "$out_dir"
    APPIMAGE_EXTRACT_AND_RUN=1 ARCH="$arch" "$cache/appimagetool-$arch.AppImage" \
        --runtime-file "$cache/runtime-$arch" \
        --updateinformation "$update_information" \
        "${sign_args[@]}" \
        "$appdir" "$out_dir/$file_name"
)

[[ -f "$out_dir/$file_name.zsync" ]] || fail "appimagetool wrote no $file_name.zsync" \
    "Without it the update information points at nothing."
rm -rf "$appdir"
(cd "$out_dir" && sha256sum "$file_name" "$file_name.zsync")

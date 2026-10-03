#!/usr/bin/env bash
# Generates the Flathub submission for Ember (dev.darkpyonix.Ember) from one of its releases.
#
#     tools/packager-linux/flathub/ember.sh --tag v1.0.0 --version 1.0.0 \
#         --screenshot docs/screenshot.png --out flathub-ember [--build repo] [--lint]
#
# writes into --out what the flathub/dev.darkpyonix.Ember repository holds: the manifest,
# the desktop entry, the metainfo and the icons. Nothing is submitted; opening the
# pull request is a separate, deliberate step (tools/packager-linux/FLATHUB.md).
#
# Options:
#   --tag <tag>              the Ember release to package
#   --version <version>      the version that release carries
#   --screenshot <path>      a screenshot's path inside the Ember repository; the listing
#                            points at it pinned to --tag (repeatable)
#   --screenshot-caption <text>   one per --screenshot
#   --x64-asset <name>       the release asset for x86_64 (default ember-linux-x64.tar.gz)
#   --arm64-asset <name>     the release asset for aarch64 (default ember-linux-arm64.tar.gz)
#   --icon <file>            overrides the icons fetched from the repository at --tag
#   --out <dir>, --build <repo>, --lint   as flatpak.sh takes them
#
# The archives are downloaded once to compute their digests, into PACKAGER_LINUX_CACHE
# (default .scratch/packager-linux/tools in this repository).

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"
ember=DarkPyonix/darkpyonix-ember
app_id=dev.darkpyonix.Ember

fail() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

tag=""
version=""
x64_asset=ember-linux-x64.tar.gz
arm64_asset=ember-linux-arm64.tar.gz
shots=()
icons=()
rest=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag) tag="$2"; shift 2 ;;
        --version) version="$2"; shift 2 ;;
        --x64-asset) x64_asset="$2"; shift 2 ;;
        --arm64-asset) arm64_asset="$2"; shift 2 ;;
        --screenshot) shots+=(--screenshot "https://raw.githubusercontent.com/$ember/$tag/$2"); shift 2 ;;
        --screenshot-caption) shots+=(--screenshot-caption "$2"); shift 2 ;;
        --icon) icons+=(--icon "$2"); shift 2 ;;
        --lint) rest+=("$1"); shift ;;
        --out | --build) rest+=("$1" "$2"); shift 2 ;;
        *) fail "unknown option $1" "See the comment at the top of $0." ;;
    esac
done
[[ -n "$tag" ]] || fail "--tag is required"
[[ -n "$version" ]] || fail "--version is required"
[[ ${#shots[@]} -gt 0 ]] || fail "--screenshot is required" \
    "Flathub's linter refuses a listing without one."
case " ${shots[*]} " in
    *"/$ember//"*) fail "--tag must come before --screenshot" ;;
esac

cache="${PACKAGER_LINUX_CACHE:-$repo_root/.scratch/packager-linux/tools}/ember-$tag"
mkdir -p "$cache"

digest() {
    local name="$1" url="https://github.com/$ember/releases/download/$tag/$1"
    if [[ ! -f "$cache/$name" ]]; then
        curl --fail --location --silent --show-error --retry 3 -o "$cache/$name.part" "$url" ||
            fail "could not download $url" "Is $name an asset of the $tag release?"
        mv "$cache/$name.part" "$cache/$name"
    fi
    sha256sum "$cache/$name" | cut -d' ' -f1
}

if [[ ${#icons[@]} -eq 0 ]]; then
    for icon in 128x128.png 128x128@2x.png; do
        curl --fail --location --silent --show-error --retry 3 -o "$cache/$icon" \
            "https://raw.githubusercontent.com/$ember/$tag/src-tauri/icons/$icon" ||
            fail "no src-tauri/icons/$icon in $ember at $tag" "Pass --icon instead."
        icons+=(--icon "$cache/$icon")
    done
fi

x64_sha="$(digest "$x64_asset")"
arm64_sha="$(digest "$arm64_asset")"

exec "$here/../flatpak.sh" \
    --dioxus-toml "$here/$app_id.toml" --app-id "$app_id" --version "$version" \
    "${icons[@]}" "${shots[@]}" \
    --prebuilt-url "https://github.com/$ember/releases/download/$tag/$x64_asset" \
    --prebuilt-sha256 "$x64_sha" --prebuilt-arch x86_64 \
    --prebuilt-url "https://github.com/$ember/releases/download/$tag/$arm64_asset" \
    --prebuilt-sha256 "$arm64_sha" --prebuilt-arch aarch64 \
    "${rest[@]}"

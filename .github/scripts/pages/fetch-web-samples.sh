#!/usr/bin/env bash
# Usage: .github/scripts/pages/fetch-web-samples.sh <directory> [release]
#
# Downloads the web sample archive the guide site publishes, and checks it against the
# checksum file released beside it.
#
# The site does not build the samples itself. A page per sample is the renderer's wasm
# module and the sample's, and building them takes tens of minutes, which a guide
# deployment would repeat on every edit to a paragraph. The samples workflow builds them
# for a `sample-v*` tag on main and releases them as one archive, so the pages the site
# serves are the ones from a tagged commit of main.
#
# Which release: the one named, else `samples-latest` (the samples workflow keeps it on the
# newest tag), else the newest `sample-v*` release, which is what exists before the first
# release that created `samples-latest`.
#
# Writes samples-web.tar.gz into <directory> and prints `release=<tag>` on stdout.
set -euo pipefail

dest="${1:?usage: fetch-web-samples.sh <directory> [release]}"
wanted="${2:-}"
archive=samples-web.tar.gz
sums=SHA256SUMS-web

# The asset list is read whole before it is searched: `grep -q` stops at the first match,
# and under pipefail the writer it leaves behind would turn a hit into a miss.
has_archive() {
    local names
    names="$(gh release view "$1" --json assets --jq '.assets[].name' 2>/dev/null)" || return 1
    grep -xF "$archive" <<<"$names" >/dev/null
}

release=
if [[ -n "$wanted" ]]; then
    has_archive "$wanted" || {
        echo "error: release $wanted has no $archive" >&2
        exit 1
    }
    release="$wanted"
elif has_archive samples-latest; then
    release=samples-latest
else
    while read -r tag; do
        [[ -n "$tag" ]] || continue
        if has_archive "$tag"; then
            release="$tag"
            break
        fi
    done < <(gh release list --limit 100 --json tagName --jq '.[].tagName' |
        grep -E '^sample-v' | sort -rV)
fi

if [[ -z "$release" ]]; then
    echo "error: no samples-latest or sample-v* release carries $archive." >&2
    echo "       Push a sample-v<version> tag on main so the samples workflow releases" >&2
    echo "       the web pages, then deploy the guide again." >&2
    exit 1
fi

mkdir -p "$dest"
gh release download "$release" --pattern "$archive" --pattern "$sums" \
    --dir "$dest" --clobber >&2

if command -v sha256sum >/dev/null 2>&1; then
    (cd "$dest" && sha256sum -c "$sums") >&2
else
    (cd "$dest" && shasum -a 256 -c "$sums") >&2
fi || {
    echo "error: $archive from $release does not match its $sums" >&2
    exit 1
}

echo "release=$release"

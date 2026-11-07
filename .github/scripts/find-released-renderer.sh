#!/usr/bin/env bash
# Usage: find-released-renderer.sh <crate version>
#
# Prints "released=true" or "released=false" for GITHUB_OUTPUT. true means the release
# v<version> carries the renderer tarball and its checksum for every desktop target.
#
# "The release has no such asset" and "gh failed" are different answers. A missing release
# or missing asset is false, which sends the build to building the renderer itself. Any
# other gh failure (authentication, rate limit, network) is reported as a warning and also
# false, but it is said, because otherwise a broken token turns into a silent rebuild that
# takes hours.
set -uo pipefail

version="${1:?usage: $0 <crate version>}"
repo="${GITHUB_REPOSITORY:-}"

err="$(mktemp)"
trap 'rm -f "$err"' EXIT
if names="$(gh release view "v$version" ${repo:+-R "$repo"} --json assets -q '.assets[].name' 2>"$err")"; then
    :
else
    if grep -qiE 'release not found|not found' "$err"; then
        echo "no release v$version yet" >&2
    else
        echo "::warning title=Could not read the release::gh failed: $(tr '\n' ' ' < "$err")"
    fi
    echo "released=false"
    exit 0
fi

released=true
for target in macos-aarch64 linux-x64 linux-arm64 windows-x64; do
    asset="compose-rust-renderer-v$version-$target.tar.gz"
    for n in "$asset" "$asset.sha256"; do
        grep -qxF "$n" <<<"$names" || { echo "missing $n" >&2; released=false; }
    done
done
echo "released=$released"

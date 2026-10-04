#!/usr/bin/env bash
# Fetches the fork's skiko directory at the pinned commit of the Compose fork.
#
# Usage: ./scripts/fetch-fork-skiko.sh
#
# extended/skiko holds the script that builds skiko's JVM natives as a static archive, with
# JAWT left out when asked (--no-jawt), so that a native image links Skia in and links no AWT.
# The same pin as the window modules, fetched the same way, into .scratch/fork-skiko/<revision>/.
# Prints the directory, which holds extended/skiko.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="$repo/renderer/scripts/build-compose.sh"
fork="$(sed -n 's/^FORK="\(.*\)"$/\1/p' "$script")"
revision="$(sed -n 's/^REVISION="\([0-9a-f]\{40\}\)"$/\1/p' "$script")"
if [[ -z "$fork" || -z "$revision" ]]; then
    echo "error: $script names no FORK or no 40 character REVISION to fetch" >&2
    exit 1
fi

dest="$repo/.scratch/fork-skiko/$revision"
if [[ -f "$dest/.complete" ]]; then
    echo "$dest"
    exit 0
fi
rm -rf "$dest"
mkdir -p "$dest"
git -C "$dest" init -q
git -C "$dest" remote add origin "$fork"
git -C "$dest" sparse-checkout set --no-cone /extended/skiko/
if ! git -C "$dest" fetch -q --depth 1 --filter=blob:none origin "$revision"; then
    rm -rf "$dest"
    echo "error: could not fetch $revision from $fork" >&2
    exit 1
fi
git -C "$dest" -c advice.detachedHead=false checkout -q "$revision"
[[ -f "$dest/extended/skiko/build-skiko-static-jvm.sh" ]] || {
    rm -rf "$dest"
    echo "error: $revision of $fork has no extended/skiko/build-skiko-static-jvm.sh" >&2
    exit 1
}
touch "$dest/.complete"
echo "$dest"

#!/usr/bin/env bash
# Fetches the window modules at the pinned commit of the Compose fork.
#
# Usage: ./scripts/fetch-fork-window.sh
#
# The window code lives in thisisthepy/compose-multiplatform-core-extended, under
# extended/window: the common logic, the Kotlin/Native windows, and the C and Kotlin of the
# GraalVM windows. The renderer depends on the Kotlin as published artifacts
# (renderer/scripts/publish-window.sh) and compiles the C files from this checkout when it
# links a native image, at the one commit renderer/scripts/build-compose.sh pins.
#
# Only that directory is fetched, into .scratch/fork-window/<revision>/. A directory per
# revision means a checkout from an older pin is never used by mistake: moving the pin asks
# for a directory that is not there yet.
#
# Prints the directory it fetched into, which holds extended/window. Running it again when
# that is already there costs nothing.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="$repo/renderer/scripts/build-compose.sh"

fork="$(sed -n 's/^FORK="\(.*\)"$/\1/p' "$script")"
revision="$(sed -n 's/^REVISION="\([0-9a-f]\{40\}\)"$/\1/p' "$script")"
if [[ -z "$fork" || -z "$revision" ]]; then
    echo "error: $script names no FORK or no 40 character REVISION to fetch" >&2
    exit 1
fi

dest="$repo/.scratch/fork-window/$revision"
if [[ -f "$dest/.complete" ]]; then
    echo "$dest"
    exit 0
fi

rm -rf "$dest"
mkdir -p "$dest"
git -C "$dest" init -q
git -C "$dest" remote add origin "$fork"
git -C "$dest" sparse-checkout set --no-cone /extended/window/
# Trees only, and the blobs of the sparse path when it is checked out: the fork is a whole
# copy of AndroidX and none of the rest of it is read here.
if ! git -C "$dest" fetch -q --depth 1 --filter=blob:none origin "$revision"; then
    rm -rf "$dest"
    echo "error: could not fetch $revision from $fork" >&2
    exit 1
fi
git -C "$dest" -c advice.detachedHead=false checkout -q "$revision"
if [[ ! -d "$dest/extended/window" ]]; then
    rm -rf "$dest"
    echo "error: $revision of $fork has no extended/window; pin a commit that does" >&2
    exit 1
fi
touch "$dest/.complete"
echo "$dest"

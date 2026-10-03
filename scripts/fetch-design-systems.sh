#!/usr/bin/env bash
# Fetches the design systems at the pinned commit of the Compose fork, for the token test.
#
# Usage: ./scripts/fetch-design-systems.sh
#
# The design systems live in thisisthepy/compose-multiplatform-core-extended, under
# extended/design-systems. compose-rust/tests/design_system_parity.rs compares the token
# tables in compose-rust/src/tokens.rs with the Kotlin tables there, and it compares them at
# the commit renderer/scripts/build-compose.sh pins, so that the tables the Host ships and the
# Compose the renderer is built from are the same revision of the same fork.
#
# Only that directory is fetched, at that one commit, into
# .scratch/design-systems/<revision>/. A directory per revision means a checkout from an older
# pin is never compared by mistake: moving the pin makes the test ask for a directory that
# is not there yet, and it says to run this.
#
# Prints the directory it fetched into. Running it again when that is already there costs
# nothing.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="$repo/renderer/scripts/build-compose.sh"

fork="$(sed -n 's/^FORK="\(.*\)"$/\1/p' "$script")"
revision="$(sed -n 's/^REVISION="\([0-9a-f]\{40\}\)"$/\1/p' "$script")"
if [[ -z "$fork" || -z "$revision" ]]; then
    echo "error: $script names no FORK or no 40 character REVISION to fetch" >&2
    exit 1
fi

dest="$repo/.scratch/design-systems/$revision"
if [[ -f "$dest/.complete" ]]; then
    echo "$dest"
    exit 0
fi

rm -rf "$dest"
mkdir -p "$dest"
git -C "$dest" init -q
git -C "$dest" remote add origin "$fork"
git -C "$dest" sparse-checkout set --no-cone /extended/design-systems/
# A partial fetch: trees only, and the blobs of the sparse paths when they are checked out.
# The fork is a whole copy of AndroidX, and none of the rest of it is read here.
if ! git -C "$dest" fetch -q --depth 1 --filter=blob:none origin "$revision"; then
    rm -rf "$dest"
    echo "error: could not fetch $revision from $fork" >&2
    exit 1
fi
git -C "$dest" -c advice.detachedHead=false checkout -q "$revision"
if [[ ! -d "$dest/extended/design-systems" ]]; then
    rm -rf "$dest"
    echo "error: $revision of $fork has no extended/design-systems; pin a commit that does" >&2
    exit 1
fi
touch "$dest/.complete"
echo "$dest"

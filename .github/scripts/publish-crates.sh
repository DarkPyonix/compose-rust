#!/usr/bin/env bash
# Publishes the workspace crates to crates.io in dependency order, skipping any version
# that is already there, so a publish that failed half way can be run again.
#
# Why: v0.0.7 uploaded compose-rust-macros and then timed out waiting for the index before
# compose-rust (run 43377202006). `cargo publish` refuses a version that already exists, so
# rerunning the old single call failed on the macros crate and could never reach the one
# that was missing. Each crate is now checked first and published only if missing, and the
# next one waits until the sparse index serves the previous one, which is what its
# dependency resolution reads.
#
# Usage: publish-crates.sh [--dry-run]
#   --dry-run  print what would be published and skip the uploads (for the shell test).
#
# Environment: CARGO_REGISTRY_TOKEN for the uploads. CRATES_IO_API and CRATES_INDEX can
# point at a stand-in server in tests.
set -euo pipefail

dry_run=0
[[ "${1:-}" == "--dry-run" ]] && dry_run=1

api="${CRATES_IO_API:-https://crates.io/api/v1}"
index="${CRATES_INDEX:-https://index.crates.io}"
# crates.io asks automated clients to name themselves.
agent="compose-rust-release-workflow"
# How long to wait for the index to serve a just-published version.
index_wait_seconds="${INDEX_WAIT_SECONDS:-900}"

# Dependency order: compose-rust names compose-rust-macros by version.
crates=(compose-rust-macros compose-rust)

version_of() {
    cargo metadata --no-deps --format-version 1 |
        python3 -c 'import json,sys; n=sys.argv[1]; print(next(p["version"] for p in json.load(sys.stdin)["packages"] if p["name"]==n))' "$1"
}

# The sparse index path of a crate name longer than three characters: first two, next two.
index_path() {
    local name="$1"
    echo "${name:0:2}/${name:2:2}/${name}"
}

on_crates_io() {
    local name="$1" version="$2" code
    code="$(curl -s -o /dev/null -w '%{http_code}' -A "$agent" "$api/crates/$name/$version")"
    [[ "$code" == "200" ]]
}

in_index() {
    local name="$1" version="$2"
    curl -sf -A "$agent" "$index/$(index_path "$name")" |
        python3 -c 'import json,sys; v=sys.argv[1]; sys.exit(0 if any(json.loads(l).get("vers")==v for l in sys.stdin if l.strip()) else 1)' "$version"
}

wait_for_index() {
    local name="$1" version="$2" waited=0
    until in_index "$name" "$version"; do
        if (( waited >= index_wait_seconds )); then
            echo "$name $version is on crates.io but the index has not served it after ${index_wait_seconds}s" >&2
            echo "Run this workflow again for the same tag: published crates are skipped." >&2
            return 1
        fi
        sleep 15
        waited=$(( waited + 15 ))
    done
    echo "$name $version is in the index"
}

for name in "${crates[@]}"; do
    version="$(version_of "$name")"
    if on_crates_io "$name" "$version"; then
        echo "$name $version is already on crates.io, skipping"
    elif (( dry_run )); then
        echo "would publish $name $version"
        continue
    else
        echo "publishing $name $version"
        # cargo waits for the index itself; give it as long as the loop below would.
        CARGO_PUBLISH_TIMEOUT="$index_wait_seconds" cargo publish -p "$name"
    fi
    (( dry_run )) || wait_for_index "$name" "$version"
done

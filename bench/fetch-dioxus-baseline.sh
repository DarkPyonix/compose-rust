#!/usr/bin/env bash
# Usage: bench/fetch-dioxus-baseline.sh
#
# Fetches the Dioxus baseline of the authoring-path comparison at the commit that
# bench/fr39-baseline.env pins, into .scratch/fr39-baseline/<rev>/, and prints the
# baseline crate's directory there.
#
# The Dioxus path is not in this repository. The comparison harness builds the baseline
# from this checkout of it, so what is measured is the pinned source and nothing that has
# changed since. A checkout that is already there and at the pinned commit is reused; one
# at any other commit, or with local changes, is refused rather than measured.
#
# Fetching is a git operation and nothing else: no build runs here. The harness builds the
# baseline when it measures.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pin="$repo_root/bench/fr39-baseline.env"

fail() {
    echo "fail  $1" >&2
    shift
    for line in "$@"; do echo "      $line" >&2; done
    exit 1
}

[[ -f "$pin" ]] || fail "no pin at $pin"
# shellcheck source=/dev/null
source "$pin"
for name in FR39_BASELINE_REPO FR39_BASELINE_REV FR39_BASELINE_PATH; do
    [[ -n "${!name:-}" ]] || fail "$pin does not set $name"
done
[[ "$FR39_BASELINE_REV" =~ ^[0-9a-f]{40}$ ]] ||
    fail "the pinned rev is not a full commit id: $FR39_BASELINE_REV" \
        "A branch or a short id can come to mean another commit, and the baseline must not."

checkout="$repo_root/.scratch/fr39-baseline/$FR39_BASELINE_REV"
if [[ -d "$checkout/.git" ]]; then
    actual="$(git -C "$checkout" rev-parse HEAD)"
    [[ "$actual" == "$FR39_BASELINE_REV" ]] ||
        fail "$checkout is at $actual, not the pinned $FR39_BASELINE_REV" \
            "Something moved it. Remove the directory and run this again."
    [[ -z "$(git -C "$checkout" status --porcelain)" ]] ||
        fail "$checkout has local changes" \
            "The baseline is measured as pinned. Remove the directory and run this again."
else
    mkdir -p "$checkout"
    git -C "$checkout" init -q
    git -C "$checkout" remote add origin "$FR39_BASELINE_REPO"
    git -C "$checkout" fetch -q --depth 1 origin "$FR39_BASELINE_REV" ||
        fail "could not fetch $FR39_BASELINE_REV from $FR39_BASELINE_REPO"
    git -C "$checkout" checkout -q --detach FETCH_HEAD
fi

baseline="$checkout/$FR39_BASELINE_PATH"
[[ -f "$baseline/Cargo.toml" ]] ||
    fail "the pinned commit has no baseline crate at $FR39_BASELINE_PATH" \
        "The pin and the path in $pin disagree."
if [[ -n "${FR39_BASELINE_ADAPTER_PATH:-}" ]]; then
    [[ -f "$checkout/$FR39_BASELINE_ADAPTER_PATH/Cargo.toml" ]] ||
        fail "the pinned commit has no adapter at $FR39_BASELINE_ADAPTER_PATH"
fi
echo "$baseline"

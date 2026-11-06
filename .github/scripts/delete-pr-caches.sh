#!/usr/bin/env bash
# Usage: delete-pr-caches.sh <pull request number>
#
# Deletes the Actions caches that runs of one pull request saved (ref refs/pull/<n>/merge).
# A pull request's caches can only be read by that pull request, so once it is merged or
# closed they are dead weight against the repository's 10 GB limit, and they push out the
# caches that other runs could use.
#
# It deletes nothing but that one ref, and it never fails the run: a cache that is already
# gone or a listing that errors is reported as a warning.
set -uo pipefail

number="${1:-}"
if [[ ! "$number" =~ ^[0-9]+$ ]]; then
    echo "::warning title=No cache cleanup::'$number' is not a pull request number"
    exit 0
fi
ref="refs/pull/$number/merge"

if ! ids="$(gh cache list --ref "$ref" --limit 100 --json id --jq '.[].id' 2>&1)"; then
    echo "::warning title=Could not list caches::$ids"
    exit 0
fi
[[ -n "$ids" ]] || { echo "no caches under $ref"; exit 0; }

for id in $ids; do
    [[ "$id" =~ ^[0-9]+$ ]] || continue
    if gh cache delete "$id" >/dev/null 2>&1; then echo "deleted cache $id of $ref"
    else echo "::warning title=Cache not deleted::cache $id of $ref"; fi
done
exit 0

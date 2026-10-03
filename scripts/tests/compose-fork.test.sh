#!/usr/bin/env bash
# The Compose fork the renderer is built with, and the one way a pin loses work silently.
#
# build-compose.sh fetches one commit of thisisthepy/compose-multiplatform-core-extended and
# builds whatever is there. If the pin moves to a commit that dropped one of this project's
# changes, nothing complains until a renderer build fails an hour later, or worse, until a
# context menu is empty again in an application that built fine. compose-fork.changes records
# the blob every changed path must hold, and this reads the pinned commit's tree and compares,
# path by path. Only trees are fetched, never file contents, so it costs a few megabytes.
#
# The fork is public and fetched over plain https, so neither this nor build-compose.sh needs a
# credential. Without a network this skips, except under CI, where a skip would be a pass
# nobody earned.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo/dioxus-compose-renderer/scripts/build-compose.sh"
changes="$repo/dioxus-compose-renderer/scripts/compose-fork.changes"
failures=0

fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "compose fork"

[ -f "$script" ] || { fail "no build-compose.sh"; exit 1; }
[ -f "$changes" ] || { fail "no compose-fork.changes"; exit 1; }

fork="$(sed -n 's/^FORK="\(.*\)"$/\1/p' "$script")"
revision="$(sed -n 's/^REVISION="\([0-9a-f]\{40\}\)"$/\1/p' "$script")"
[ -n "$revision" ] || fail "build-compose.sh pins no 40 character revision"
[ "$fork" = "https://github.com/thisisthepy/compose-multiplatform-core-extended.git" ] ||
    fail "build-compose.sh fetches from '$fork', not the public fork over https"

# The patches this replaced are gone, and nothing is to bring them back beside the fork: two
# sources for the same change are two answers to which one was built.
[ ! -e "$repo/dioxus-compose-renderer/patches" ] ||
    fail "dioxus-compose-renderer/patches exists again; the changes belong in the fork"
if grep -q 'git[^|]* apply' "$script"; then
    fail "build-compose.sh applies a patch on top of the fork"
fi

expected=()
while read -r blob path; do
    case "$blob" in ''|'#'*) continue ;; esac
    if ! [[ "$blob" =~ ^([0-9a-f]{40}|-)$ ]] || [ -z "$path" ]; then
        fail "compose-fork.changes has a line that is not '<blob or -> <path>': $blob $path"
        continue
    fi
    expected+=("$blob $path")
done < "$changes"
[ "${#expected[@]}" -gt 0 ] || fail "compose-fork.changes lists no paths at all"

if [ "$failures" -ne 0 ]; then
    echo "  $failures failed" >&2
    exit 1
fi

mkdir -p "$repo/.scratch"
work="$(mktemp -d "$repo/.scratch/compose-fork-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT
git -C "$work" init -q --bare
if ! git -C "$work" fetch -q --depth 1 --filter=blob:none "$fork" "$revision" 2>"$work/fetch.log"; then
    if [ -n "${CI:-}" ]; then
        cat "$work/fetch.log" >&2
        fail "could not fetch $revision from $fork"
        exit 1
    fi
    echo "  skip: cannot reach $fork to read $revision"
    exit 0
fi

paths=()
for line in "${expected[@]}"; do paths+=("${line#* }"); done
listing="$(git -C "$work" ls-tree -r "$revision" -- "${paths[@]}")"

for line in "${expected[@]}"; do
    blob="${line%% *}"
    path="${line#* }"
    actual="$(awk -v p="$path" -F '\t' '$2 == p { split($1, f, " "); print f[3] }' <<< "$listing")"
    if [ "$blob" = "-" ]; then
        [ -z "$actual" ] ||
            fail "$path is back in $revision; the fork's change removed it"
    elif [ -z "$actual" ]; then
        fail "$path is missing from $revision"
    elif [ "$actual" != "$blob" ]; then
        fail "$path in $revision is $actual, not the $blob the fork's change left there"
    fi
done

if [ "$failures" -eq 0 ]; then
    echo "  ok: ${#expected[@]} changed paths hold what they should in $revision"
else
    echo "  $failures failed" >&2
    exit 1
fi

#!/usr/bin/env bash
# The patches Compose is built with, and the two ways they rot without anyone noticing.
#
# Whether they apply is answered by build-compose.sh, loudly, every time it runs. What
# that cannot answer is asked here: that the revision they are pinned to is the same one
# written down for a reader, and that a patch says what it is for. A patch file with no
# explanation is a change to someone else's source that nobody can review, and this
# project keeps exactly three of them for that reason.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
patches="$repo/dioxus-compose-renderer/patches"
script="$repo/dioxus-compose-renderer/scripts/build-compose.sh"
failures=0

fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "compose patches"

[ -f "$script" ] || { fail "no build-compose.sh"; exit 1; }
[ -f "$patches/README.md" ] || fail "patches/README.md is missing"

in_script="$(sed -n 's/^REVISION="\([0-9a-f]\{40\}\)"$/\1/p' "$script")"
[ -n "$in_script" ] || fail "build-compose.sh pins no 40 character revision"
grep -q "$in_script" "$patches/README.md" ||
    fail "the revision build-compose.sh pins ($in_script) is not the one README.md records"

shopt -s nullglob
files=("$patches"/*.patch)
[ "${#files[@]}" -gt 0 ] || fail "no patches at all"

for patch in "${files[@]}"; do
    name="$(basename "$patch")"
    head -1 "$patch" | grep -q '^Subject: ' || fail "$name does not open with a Subject line"
    # The explanation sits between the subject and the `--` that ends it. Counted with
    # awk rather than a sed range, because a range whose end pattern is already on its
    # first line does not close there and goes on to count the diff.
    reason="$(awk 'NR == 1 { next } /^--$/ { exit } /[^[:space:]]/ { n++ } END { print n + 0 }' "$patch")"
    [ "$reason" -ge 3 ] || fail "$name says too little about why it exists"
    grep -q '^diff --git ' "$patch" || fail "$name holds no diff"
    grep -q "$name" "$patches/README.md" || fail "$name is not listed in README.md"
done

if [ "$failures" -eq 0 ]; then
    echo "  ok: ${#files[@]} patches, pinned to $in_script"
else
    echo "  $failures failed" >&2
    exit 1
fi

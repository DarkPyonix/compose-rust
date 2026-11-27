#!/usr/bin/env bash
# The packaging templates come from the Compose fork's extended/packaging at a pinned commit.
#
# scripts/packaging-templates/ is a copy and PIN names the fork, the revision and the blob id
# of every copied file. scripts/check-packaging-templates.py fails when the copy is not what
# PIN says (a hand edit, a file nobody pinned, a broken PIN), and with --fetch or --fork-dir
# when PIN is not what the fork holds at that revision. --bump moves the pin and the copy
# together.
#
# The fault cases run against a copy of the script and the templates in .scratch/ and a
# local fork served over file://, so they need no network. The comparison with the real fork
# on GitHub runs on CI (CI=true), or locally with PACKAGING_TEMPLATES_ONLINE=1.
set -uo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
failures=0
pass() { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; failures=$((failures + 1)); shift; local line; for line in "$@"; do echo "       $line"; done; }

mkdir -p "$repo/.scratch"
work="$(mktemp -d "$repo/.scratch/packaging-templates-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# expect <name> <ok|fail> <text the output must hold> <tree> [check arguments...]
expect() {
    local name="$1" want="$2" text="$3" tree="$4" out status=0
    shift 4
    out="$(cd "$tree" && python3 -I scripts/check-packaging-templates.py "$@" 2>&1)" || status=$?
    if [[ "$want" == ok && "$status" -ne 0 ]]; then fail "$name: refused" "$out"; return; fi
    if [[ "$want" == fail && "$status" -eq 0 ]]; then fail "$name: passed" "$out"; return; fi
    if [[ -n "$text" && "$out" != *"$text"* ]]; then fail "$name: did not say \"$text\"" "$out"; return; fi
    pass "$name"
}

# A copy of this repository's script and templates, in a tree of its own.
tree() {
    local dest="$work/$1"
    mkdir -p "$dest/scripts"
    cp "$repo/scripts/check-packaging-templates.py" "$dest/scripts/"
    cp -R "$repo/scripts/packaging-templates" "$dest/scripts/"
    printf '%s' "$dest"
}

expect fr22_6_11_the_copy_matches_its_pin ok "match PIN" "$repo"

t="$(tree edited)"
printf '\n<!-- edited here -->\n' >> "$t/scripts/packaging-templates/templates/Info.plist.in"
expect fr22_6_11_a_template_edited_here_fails fail "templates/Info.plist.in is not the pinned file" "$t"

t="$(tree unpinned)"
cp "$t/scripts/packaging-templates/templates/app.desktop.in" "$t/scripts/packaging-templates/templates/extra.desktop.in"
expect fr22_6_11_an_unpinned_template_fails fail "templates/extra.desktop.in is in" "$t"

t="$(tree missing)"
rm "$t/scripts/packaging-templates/templates/AppxManifest.xml.in"
expect fr22_6_11_a_missing_template_fails fail "templates/AppxManifest.xml.in is pinned and not in" "$t"

t="$(tree norevision)"
sed -i.bak '/^revision /d' "$t/scripts/packaging-templates/PIN" && rm "$t/scripts/packaging-templates/PIN.bak"
expect fr22_6_11_a_pin_without_a_revision_fails fail "PIN names no revision" "$t"

# A fork with the pinned directory, at a commit, over file:// like GitHub serves the real one.
fork="$work/fork"
git init -q "$fork"
mkdir -p "$fork/extended/packaging/templates" "$fork/other"
cp -R "$repo/scripts/packaging-templates/templates/." "$fork/extended/packaging/templates/"
cp "$repo/scripts/packaging-templates/metadata-map.md" "$fork/extended/packaging/"
echo "fork notes, not copied" > "$fork/extended/packaging/formats.md"
echo elsewhere > "$fork/other/file.txt"
commit() { git -C "$fork" add -A && git -C "$fork" -c user.name=test -c user.email=test@example.invalid commit -q -m "$1" && git -C "$fork" rev-parse HEAD; }
first="$(commit fork)"
git -C "$fork" config uploadpack.allowFilter true
git -C "$fork" config uploadpack.allowAnySHA1InWant true

# pointed <name>: a tree whose PIN names the local fork at `first`.
pointed() {
    local t
    t="$(tree "$1")"
    sed -i.bak -e "s#^fork .*#fork file://$fork#" -e "s#^revision .*#revision $first#" "$t/scripts/packaging-templates/PIN" && rm "$t/scripts/packaging-templates/PIN.bak"
    printf '%s' "$t"
}

t="$(pointed fetched)"
expect fr22_6_11_the_pin_matches_the_fork ok "match PIN and the fork" "$t" --fetch

t="$(pointed checkout)"
expect fr22_6_11_a_checkout_of_the_fork_is_compared ok "match PIN and the fork" "$t" --fork-dir "$fork/extended/packaging"

# The fork moves on: a template changes and one is added. The old pin still names the old
# blobs, so comparing it with the new checkout fails on both.
printf '\n# changed in the fork\n' >> "$fork/extended/packaging/templates/app.desktop.in"
cp "$fork/extended/packaging/templates/Info.plist.in" "$fork/extended/packaging/templates/new.plist.in"
second="$(commit "change a template")"
t="$(pointed drifted)"
out="$(cd "$t" && python3 -I scripts/check-packaging-templates.py --fork-dir "$fork/extended/packaging" 2>&1)"
if [[ "$out" == *"templates/app.desktop.in: PIN says blob"* && "$out" == *"the fork has extended/packaging/templates/new.plist.in"* ]]; then
    pass "fr22_6_11_a_template_changed_in_the_fork_without_the_pin_fails"
else
    fail "fr22_6_11_a_template_changed_in_the_fork_without_the_pin_fails" "$out"
fi

t="$(pointed bumped)"
expect fr22_6_11_bump_copies_the_fork_and_moves_the_pin ok "pinned 8 files of extended/packaging at $second" "$t" --bump "$second"
if grep -qx "revision $second" "$t/scripts/packaging-templates/PIN" \
    && cmp -s "$t/scripts/packaging-templates/templates/app.desktop.in" "$fork/extended/packaging/templates/app.desktop.in" \
    && [[ -f "$t/scripts/packaging-templates/templates/new.plist.in" && ! -e "$t/scripts/packaging-templates/formats.md" ]]; then
    expect fr22_6_11_a_bumped_copy_matches_the_fork ok "match PIN and the fork" "$t" --fetch
else
    fail "fr22_6_11_a_bumped_copy_matches_the_fork: --bump did not leave the fork's files and revision" \
        "$(cat "$t/scripts/packaging-templates/PIN")"
fi

if [[ "${CI:-}" == true || "${PACKAGING_TEMPLATES_ONLINE:-}" == 1 ]]; then
    expect fr22_6_11_the_pin_matches_the_fork_on_github ok "match PIN and the fork" "$repo" --fetch
else
    echo "note - the comparison with the fork on GitHub runs on CI (CI=true) or with PACKAGING_TEMPLATES_ONLINE=1"
fi

[[ "$failures" -eq 0 ]] || { echo "$failures case(s) failed"; exit 1; }
echo "all passed"

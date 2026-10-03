#!/usr/bin/env bash
# The published crate has to carry the README, and the README has to describe this crate.
#
# v0.0.0 went to crates.io with no readme at all, so its page was a bare list of
# dependencies. The cause is easy to reproduce and easy to miss: the crate lives in a
# subdirectory, the README lives at the repository root, and a package only contains files
# under its own directory unless the manifest says otherwise. Nothing failed. The upload
# succeeded and the page was simply empty.
#
# v0.0.1 then nearly went out with a README that still introduced the project it was split
# from: rsx!, hooks and a VirtualDom, none of which this crate contains. A README on
# crates.io cannot be changed after the version is published, so the text is checked here.
# Dioxus and rsx! may appear on one kind of line only: the pointer to dioxus-compose, the
# project for people who want them. Any other line that names them fails.
#
# Usage: crate-readme.test.sh [file...]
#   With files, only their text is checked. Without, both READMEs are checked and then
#   the packaged crate is listed to make sure it carries README.md.
set -euo pipefail

cd "$(dirname "$0")/../.."

# Prints every line of a file that names Dioxus or rsx outside the dioxus-compose pointer.
# A line is the pointer when it names dioxus-compose; it may then also say Dioxus and rsx!,
# because saying what dioxus-compose is for is the point of the pointer.
stray_mentions() {
    grep -n -i -E 'dioxus|rsx' "$1" | grep -v -i 'dioxus-compose' || true
}

check_text() {
    local file="$1" hits
    [[ -f "$file" ]] || {
        echo "error: $file does not exist" >&2
        return 1
    }
    hits="$(stray_mentions "$file")"
    if [[ -n "$hits" ]]; then
        echo "error: $file mentions Dioxus or rsx outside the dioxus-compose pointer" >&2
        echo "       compose-rust does not contain either. Describe compose-rust, and send" >&2
        echo "       readers who want them to dioxus-compose on a line that names it." >&2
        sed 's/^/       /' <<< "$hits" >&2
        return 1
    fi
}

if [[ $# -gt 0 ]]; then
    status=0
    for file in "$@"; do
        check_text "$file" || status=1
    done
    [[ $status -eq 0 ]] && echo "ok    readme text ($*)"
    exit $status
fi

status=0
for file in README.md docs/locales/README_ko.md; do
    check_text "$file" || status=1
done
[[ $status -eq 0 ]] || exit 1
echo "ok    readme text"

if ! command -v cargo >/dev/null; then
    echo "skip  crate readme (no cargo on PATH)"
    exit 0
fi

manifest="compose-rust/Cargo.toml"
grep -q '^readme = ' "$manifest" || {
    echo "error: $manifest declares no readme, so crates.io will show an empty page" >&2
    echo "       The README is at the repository root, outside this package, so it is" >&2
    echo "       included only when the manifest names it: readme = \"../README.md\"" >&2
    exit 1
}

listing="$(cargo package -p compose-rust --list --allow-dirty 2>/dev/null)"
grep -qx 'README.md' <<< "$listing" || {
    echo "error: the packaged crate does not contain README.md" >&2
    echo "       Files it would contain:" >&2
    sed 's/^/       /' <<< "$listing" >&2
    exit 1
}

echo "ok    crate readme"

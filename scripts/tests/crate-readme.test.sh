#!/usr/bin/env bash
# The published crate has to carry the README.
#
# v0.0.0 went to crates.io with no readme at all, so its page was a bare list of
# dependencies. The cause is easy to reproduce and easy to miss: the crate lives in a
# subdirectory, the README lives at the repository root, and a package only contains files
# under its own directory unless the manifest says otherwise. Nothing failed. The upload
# succeeded and the page was simply empty.
set -euo pipefail

cd "$(dirname "$0")/../.."

if ! command -v cargo >/dev/null; then
    echo "skip  crate readme (no cargo on PATH)"
    exit 0
fi

manifest="dioxus-compose/Cargo.toml"
grep -q '^readme = ' "$manifest" || {
    echo "error: $manifest declares no readme, so crates.io will show an empty page" >&2
    echo "       The README is at the repository root, outside this package, so it is" >&2
    echo "       included only when the manifest names it: readme = \"../README.md\"" >&2
    exit 1
}

listing="$(cargo package -p dioxus-compose --list --allow-dirty 2>/dev/null)"
grep -qx 'README.md' <<< "$listing" || {
    echo "error: the packaged crate does not contain README.md" >&2
    echo "       Files it would contain:" >&2
    sed 's/^/       /' <<< "$listing" >&2
    exit 1
}

echo "ok    crate readme"

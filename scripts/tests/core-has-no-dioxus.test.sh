#!/usr/bin/env bash
# Usage: ./scripts/tests/core-has-no-dioxus.test.sh
#
# compose-rust's own dependency graph has no Dioxus crate in it.
#
# compose-rust is the boundary, the protocol and the records, and an application that uses
# it without Dioxus must not find a dioxus-* crate in its build. The Dioxus authoring layer
# is a separate crate (adapters/dioxus) that depends on compose-rust, never the reverse.
# Nothing in the compiler stops someone from adding `dioxus-core` back to compose-rust's
# [dependencies] to reach for one convenient type, and the only symptom would be a longer
# build for every application, so this is where it is caught.
#
# What is read is the graph a consumer gets: normal and build dependencies, on every
# target, with every feature. Dev-dependencies are left out, because they never reach an
# application; a test of compose-rust that needed Dioxus would be a test that belongs to
# the adapter instead.
#
# It runs cargo, so it runs where the toolchain is: CI runs every scripts/tests/*.test.sh.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

fail() {
    echo "FAIL  $1" >&2
    shift
    for line in "$@"; do echo "        $line" >&2; done
    exit 1
}

command -v cargo >/dev/null ||
    fail "cargo is not on PATH" \
        "This reads compose-rust's dependency graph with cargo tree, so it needs the" \
        "toolchain. A check that skipped here would pass without having looked."

# --prefix none puts one crate on each line with nothing in front of it, so a line is a
# crate name, a version and maybe a source. --target all and --all-features take in every
# platform's and every feature's dependencies: a dioxus crate behind cfg(target_os =
# "android") or behind an optional feature is in some application's build all the same.
if ! tree="$(cargo tree --manifest-path "$repo_root/Cargo.toml" -p compose-rust \
    --edges normal,build --target all --all-features --prefix none 2>&1)"; then
    fail "cargo tree could not read compose-rust's dependency graph" \
        "Nothing was checked. What cargo said:" \
        "$tree"
fi

if [[ -z "$tree" ]] || ! grep -q '^compose-rust ' <<<"$tree"; then
    fail "cargo tree printed no line for compose-rust itself" \
        "The output's shape has changed, so the check below would pass by reading nothing."
fi

dioxus=()
while IFS= read -r line; do
    dioxus+=("$line")
done < <(grep -E '^dioxus-' <<<"$tree" | sed 's/ (\*)$//' | sort -u)
if [[ ${#dioxus[@]} -gt 0 ]]; then
    fail "compose-rust's dependency graph contains Dioxus crates:" \
        "${dioxus[@]}" \
        "compose-rust has to build without Dioxus. Whatever needed these belongs in the" \
        "Dioxus adapter (adapters/dioxus), which depends on compose-rust. To see which" \
        "dependency brought one in: cargo tree -p compose-rust --edges normal,build -i <name>"
fi

echo "ok    compose-rust's dependency graph has no dioxus-* crate"

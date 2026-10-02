#!/usr/bin/env bash
# Usage: ./scripts/tests/android-kotlin-travels.test.sh
#
# The renderer's Kotlin is inside the crate, and a published crate carries it.
#
# Android is the one platform where the Kotlin cannot be frozen ahead of time. The desktop
# ships a native-image shared library and iOS a Kotlin/Native archive; Android runs on ART
# and the Kotlin is compiled by the application's own Gradle build, so it has to arrive as
# source. dx's template takes Maven coordinates and nothing else for dependencies, so a
# compiled artifact could not be handed over even if one were built.
#
# Two ways this breaks quietly. The staged copy drifts from the renderer, so an
# application compiles an older interpreter than the one the Host was generated against
# and the handshake refuses it. Or `cargo package` leaves the directory out and an Android
# application built from the published crate has no renderer at all.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

staged="dioxus-compose/android-kotlin"
source_dir="dioxus-compose-renderer/android/src"

failures=0
fail() {
    failures=$((failures + 1))
    printf 'FAIL  %s\n' "$1" >&2
    shift
    local line
    for line in "$@"; do printf '        %s\n' "$line" >&2; done
}

if [[ ! -d "$staged" ]]; then
    fail "the crate carries no Android Kotlin" \
        "Run scripts/stage-android-kotlin.sh."
else
    # Resolved, not linked. cargo package cannot carry a symlink.
    links="$(find "$staged" -type l | wc -l | tr -d ' ')"
    [[ "$links" -eq 0 ]] || fail "$links symlinks under $staged" \
        "cargo package does not carry a symlink, so those files would arrive empty."

    # The same files, with the same contents, as the renderer's own directory.
    if ! diff -r -q <(cd "$source_dir" && find . -name '*.kt' | sort) \
        <(cd "$staged" && find . -name '*.kt' | sort) >/dev/null 2>&1; then
        fail "the staged Kotlin is not the same set of files as the renderer's" \
            "Run scripts/stage-android-kotlin.sh."
    else
        differing=0
        while read -r file; do
            cmp -s "$source_dir/$file" "$staged/$file" || differing=$((differing + 1))
        done < <(cd "$source_dir" && find . -name '*.kt')
        [[ "$differing" -eq 0 ]] || fail "$differing staged files differ from the renderer's" \
            "An application would compile an interpreter older than the Host it is" \
            "paired with, and the handshake would refuse it before anything drew." \
            "Run scripts/stage-android-kotlin.sh."
    fi
fi

# And the packaged crate has to contain it.
if command -v cargo >/dev/null 2>&1; then
    listing="$(cd dioxus-compose && cargo package --list --allow-dirty 2>/dev/null)"
    if [[ -z "$listing" ]]; then
        fail "cargo package --list said nothing, so packaging was not checked"
    elif ! grep -q "^android-kotlin/" <<<"$listing"; then
        fail "the packaged crate does not contain android-kotlin/" \
            "An Android application built from the published crate would have no" \
            "renderer, and would find that out at run time rather than at build time."
    fi
fi

if [[ $failures -eq 0 ]]; then
    echo "ok    the Android Kotlin travels with the crate"
fi
exit "$failures"

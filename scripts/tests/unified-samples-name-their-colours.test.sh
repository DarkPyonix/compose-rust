#!/usr/bin/env bash
# Fails if a unified sample stops drawing its reference picture's own colours.
#
# The seven unified samples each rebuild one published design, and the picture has its own
# accent and its own flat fills. A `ColorRole` hands those back to whichever design system
# is running: every unified sample was once drawn that way, the accents came out in the
# theme's blue and the cards came out of the accent containers in pale lilac and powder
# blue, none of which is in any of the pictures. So each of them names its colours in a
# palette module, pins the values with a test, draws its own bar rather than declaring a
# `Navigation`, and paints nothing with an accent container.
#
# This is the static half. Each sample's own tests check the wire: that nothing on screen
# is painted by a role and that the bar marks the destination in the accent.
#
# Comments are exempt, because explaining why a role was the wrong answer needs its name.
# The four adaptive samples are the opposite rule and are checked by
# samples-speak-in-roles.test.sh.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

unified=(minimal store statistics selfcare podcast academic social)
status=0

fail() {
    echo "error: $1" >&2
    shift
    local line
    for line in "$@"; do echo "       $line" >&2; done
    status=1
}

# Source lines with their comments taken out, as file:line:text.
code_of() {
    git ls-files "samples/$1/src/*.rs" | xargs grep -nH '' |
        grep -vE '^[^:]+:[0-9]+:\s*(//|///|//!)' || true
}

for sample in "${unified[@]}"; do
    code="$(code_of "$sample")"
    if [[ -z "$code" ]]; then
        fail "$sample has no source this test can read" \
            "The list above names the seven unified samples; a renamed one is unchecked."
        continue
    fi

    if ! grep -qE '(^|[^_])mod palette' <<< "$code"; then
        fail "$sample has no palette module" \
            "A unified sample names its reference's colours in one place."
    fi

    if ! grep -q 'fn fr22_the_palette_is_the_reference_colours_rather_than_the_theme' <<< "$code"; then
        fail "$sample does not pin its palette" \
            "Without the test, a palette edited back into a role or away from the picture" \
            "passes unnoticed."
    fi

    containers="$(grep -E 'ColorRole::(Primary|Secondary|Tertiary)Container' <<< "$code" || true)"
    if [[ -n "$containers" ]]; then
        fail "$sample paints something with an accent container" "$containers"
    fi

    navigation="$(grep -E '^\S+:[0-9]+:\s*(Navigation|NavigationItem) \{' <<< "$code" || true)"
    if [[ -n "$navigation" ]]; then
        fail "$sample declares a Navigation" \
            "Navigation draws the running design system's labelled bar with a selection" \
            "pill; the references' bars are bare icons, the one you are on in the accent." \
            "$navigation"
    fi
done

if [[ $status -eq 0 ]]; then
    echo "ok    unified samples name their reference's colours"
fi
exit $status

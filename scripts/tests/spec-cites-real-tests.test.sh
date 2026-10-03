#!/usr/bin/env bash
# Usage: ./scripts/tests/spec-cites-real-tests.test.sh
#
# Every test name the SPEC names in backticks is a test that exists.
#
# Acceptance criteria increasingly say which test stands behind them, which is what lets
# a reader check a claim instead of trusting it. A name that no longer matches anything
# turns that into the opposite: a requirement that reads as verified and is not. It is
# easy to do, because renaming a test does not touch the document, and because a name can
# be written from memory. One was, in the commit this test arrived with.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

failures=0
missing=()

# Tests the SPEC names that moved to dioxus-compose with the Dioxus adapter. Each one
# checks what the adapter's rsx! widgets write, so it went where those widgets went. They
# are named here one by one, with the file they were in, rather than by skipping a
# directory, so a test that is merely missing still fails. Where this checkout's history
# has the commit the FR-39 baseline is pinned to (bench/fr39-baseline.env), each is also
# checked to exist there. The SPEC criteria that cite them are still to be pointed at
# dioxus-compose.
moved_rev=22822c130a8211de7f65e0320db5e6a4db1a8bb1
moved=(
    "fr13_button_label_colour_is_sent_as_a_role adapters/dioxus/tests/design_primitives.rs"
    "fr13_separator_is_one_divider_so_the_design_system_sets_its_weight adapters/dioxus/tests/design_primitives.rs"
    "fr21_navigation_is_one_declaration_and_a_resize_creates_nothing adapters/dioxus/tests/navigation_sheets_messages.rs"
)
moved_file() {
    local entry
    for entry in "${moved[@]}"; do
        if [[ "${entry%% *}" == "$1" ]]; then
            echo "${entry#* }"
            return 0
        fi
    done
    return 1
}
have_moved_rev=false
if git cat-file -e "$moved_rev^{commit}" 2>/dev/null; then
    have_moved_rev=true
fi

# A test name in the SPEC looks like `fr14_something_or_other`: a requirement prefix, an
# underscore, and lowercase words. Anything else in backticks is a type, a path or a flag.
while read -r name; do
    if grep -rq "fn $name\b\|fun $name\b" \
        --include='*.rs' --include='*.kt' \
        compose-rust renderer samples 2>/dev/null; then
        continue
    fi
    if file="$(moved_file "$name")"; then
        if [[ "$have_moved_rev" == true ]] &&
            ! git show "$moved_rev:$file" 2>/dev/null | grep -q "fn $name\b"; then
            missing+=("$name (not in $file at $moved_rev either)")
        else
            echo "note  $name is in dioxus-compose, moved from $file"
        fi
        continue
    fi
    missing+=("$name")
done < <(grep -oE '`(fr|nfr|pr)[0-9]+(_[0-9]+)*_[a-z0-9_]+`' docs/SPEC.md |
    tr -d '`' | sort -u)

if [[ ${#missing[@]} -gt 0 ]]; then
    failures=1
    printf 'FAIL  the SPEC names %d tests that do not exist\n' "${#missing[@]}" >&2
    for name in "${missing[@]}"; do printf '        %s\n' "$name" >&2; done
    printf '        A criterion naming a test that is not there reads as verified and\n' >&2
    printf '        is not. Either the test was renamed, or the name was written from\n' >&2
    printf '        memory and never checked.\n' >&2
else
    echo "ok    every test the SPEC names exists"
fi
exit "$failures"

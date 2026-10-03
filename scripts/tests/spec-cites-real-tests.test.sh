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

# A test name in the SPEC looks like `fr14_something_or_other`: a requirement prefix, an
# underscore, and lowercase words. Anything else in backticks is a type, a path or a flag.
while read -r name; do
    if ! grep -rq "fn $name\b\|fun $name\b" \
        --include='*.rs' --include='*.kt' \
        dioxus-compose dioxus-compose-renderer samples 2>/dev/null; then
        missing+=("$name")
    fi
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

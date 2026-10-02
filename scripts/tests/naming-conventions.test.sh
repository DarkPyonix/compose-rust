#!/usr/bin/env bash
# Usage: ./scripts/tests/naming-conventions.test.sh
#
# The part of the naming rules a machine can check.
#
# Most of that requirement is a matter of judgement: whether a name is the one Compose or
# Dioxus would have used is not something a script can answer, and review answers it. One
# clause is exact, though, and it is the one that would break a consumer's build rather
# than merely read oddly: every symbol this crate exports to C carries the
# `dioxus_compose_` prefix. Two libraries in one process cannot both export `init`.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

failures=0

# Every `#[unsafe(no_mangle)] pub extern "C" fn` in the crate, by the name it exports.
# Six lines of context rather than three, and `unsafe` allowed between `pub` and
# `extern`. The first version of this read neither, found nine of the thirteen, and
# passed while a renamed export sat in the four it could not see.
exported="$(grep -rEh -A 6 'no_mangle' --include='*.rs' dioxus-compose/src |
    grep -oE 'pub (unsafe )?extern "C" fn [a-zA-Z0-9_]+' |
    sed -E 's/pub (unsafe )?extern "C" fn //' | sort -u)"

if [[ -z "$exported" ]]; then
    failures=1
    echo "FAIL  no C exports were found, so the shape this reads has changed" >&2
    echo "        and nothing was checked." >&2
else
    stray="$(grep -v '^dioxus_compose_' <<<"$exported" || true)"
    if [[ -n "$stray" ]]; then
        failures=1
        echo "FAIL  these C exports do not carry the dioxus_compose_ prefix:" >&2
        while read -r name; do printf '        %s\n' "$name" >&2; done <<<"$stray"
        echo "        A C symbol is global to the process. Two libraries exporting the" >&2
        echo "        same bare name is a link error for whoever uses both." >&2
    fi
fi

if [[ $failures -eq 0 ]]; then
    printf 'ok    every C export is prefixed (%s of them)\n' "$(wc -l <<<"$exported" | tr -d ' ')"
fi
exit "$failures"

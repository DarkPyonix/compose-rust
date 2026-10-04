#!/usr/bin/env bash
# The user guide has to describe compose-rust, and only compose-rust.
#
# Until October 2026 the guide taught the Dioxus layer this project was split from: rsx!,
# hooks and a VirtualDom on every page, none of which compose-rust contains. The README
# was rewritten and checked (crate-readme.test.sh) while the guide went on teaching the old
# project, because nothing looked at it. This looks at it.
#
# Three rules, for every page under docs/guide:
#   1. Dioxus and rsx! appear only on a line that names dioxus-compose, the project for
#      people who want them.
#   2. That pointer appears once per language, so it stays a pointer and does not grow
#      back into a second guide.
#   3. No requirement IDs (FR-1, NFR-9, PR-2, INTENT D2) and no links to SPEC.md,
#      INTENT.md or PROJECT.md. Those documents are stripped from the published branch,
#      so a reader of the published guide would be pointed at nothing.
#
# Usage: guide-text.test.sh [guide-dir]
#   Checks docs/guide by default. A directory argument checks a copy elsewhere, which is
#   how the rule was shown to fail on the guide it replaced.
set -euo pipefail

cd "$(dirname "$0")/../.."
guide="${1:-docs/guide}"

[[ -d "$guide" ]] || {
    echo "error: $guide does not exist" >&2
    exit 1
}

status=0

pages=()
while IFS= read -r page; do
    pages+=("$page")
done < <(find "$guide" -name '*.html' | sort)

[[ ${#pages[@]} -gt 0 ]] || {
    echo "error: no pages found under $guide" >&2
    exit 1
}

# Rule 1: every mention outside the pointer.
for page in "${pages[@]}"; do
    hits="$(grep -n -i -E 'dioxus|rsx' "$page" | grep -v -i 'dioxus-compose' || true)"
    if [[ -n "$hits" ]]; then
        echo "error: $page mentions Dioxus or rsx outside the dioxus-compose pointer" >&2
        echo "       The guide describes compose-rust, which contains neither. Readers who" >&2
        echo "       want them are sent to dioxus-compose from one line on the overview." >&2
        { sed 's/^/       /' <<< "$hits" | head -20 >&2; } || true
        status=1
    fi
done

# Rule 2: one pointer per language directory.
for dir in "$guide"/*/; do
    dir="${dir%/}"
    count=0
    for page in "$dir"/*.html; do
        [[ -f "$page" ]] || continue
        n="$(grep -c -i -E 'dioxus|rsx' "$page" || true)"
        count=$((count + n))
    done
    if [[ $count -gt 1 ]]; then
        echo "error: $dir names Dioxus or rsx on $count lines; the one dioxus-compose pointer is all" >&2
        echo "       the guide may have, so the guide stays about compose-rust" >&2
        { grep -n -i -E 'dioxus|rsx' "$dir"/*.html | sed 's/^/       /' | head -20 >&2; } || true
        status=1
    fi
done

# Rule 3: planning-document citations and links.
for page in "${pages[@]}"; do
    hits="$(grep -n -E '(^|[^A-Za-z])(N?FR|PR)-[0-9]+|INTENT D[0-9]+|SPEC §|(SPEC|INTENT|PROJECT)\.md' "$page" || true)"
    if [[ -n "$hits" ]]; then
        echo "error: $page cites the planning documents" >&2
        echo "       SPEC, INTENT and PROJECT are not published, so say the reason itself" >&2
        echo "       instead of naming the requirement that holds it." >&2
        { sed 's/^/       /' <<< "$hits" | head -20 >&2; } || true
        status=1
    fi
done

[[ $status -eq 0 ]] && echo "ok    guide text (${#pages[@]} pages)"
exit $status

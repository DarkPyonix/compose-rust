#!/usr/bin/env bash
# Fails if an em dash appears anywhere a human will read it.
#
# AGENTS.md forbids em dashes in docs, code comments, commit messages and UI copy.
# They kept coming back through merges from branches written before the rule, so the
# rule is checked rather than remembered.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

# U+2014 EM DASH. An en dash (U+2013) is allowed in numeric ranges.
dash=$'—'
# Every tracked text file, not a list of extensions: a list skipped the toolchain
# wrappers (`renderer/kotlin`, `kotlin.bat`), C, Swift, Python and generated files,
# and em dashes sat in them unnoticed. `-I` leaves out binaries such as images.
# The escaped forms catch a dash written as a string-literal escape or HTML entity.
hits="$(git grep -n -I -e "$dash" -e '\\u2014' -e '\\u{2014}' -e '&mdash;' -e '&#8212;' -e '&#x2014;' -- \
    ':!docs/guide/assets/*.min.*' ':!scripts/tests/no-em-dash.test.sh' || true)"

if [[ -n "$hits" ]]; then
    echo "error: em dashes found (CLAUDE.md forbids them; use a comma, a colon or parentheses)" >&2
    echo "$hits" >&2
    exit 1
fi
echo "ok    no em dashes"

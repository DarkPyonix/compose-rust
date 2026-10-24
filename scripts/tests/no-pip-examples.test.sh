#!/usr/bin/env bash
# Fails if a pip command appears in anything a reader might copy and run.
#
# Python-side tooling in this project's examples goes through uv, ppp (pypackpack) and
# tcl (toolchain-lite), never pip. The thisisthepy and darkpyonix projects share one
# toolchain story, and a single pip line in a guide sends a reader down a different one.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

# AGENTS.md states the rule and so names the command it forbids.
hits="$(git grep -n -I -E -e '(^|[^[:alnum:]_-])pip[3x]?[[:space:]]+install' \
    -e 'python[0-9.]*[[:space:]]+-m[[:space:]]+pip([^[:alnum:]_]|$)' -- \
    ':!AGENTS.md' ':!scripts/tests/no-pip-examples.test.sh' || true)"

if [[ -n "$hits" ]]; then
    echo "error: pip commands found; examples use uv, ppp or tcl (e.g. 'uv run', 'uv add')" >&2
    echo "$hits" >&2
    exit 1
fi
echo "ok    no pip examples"

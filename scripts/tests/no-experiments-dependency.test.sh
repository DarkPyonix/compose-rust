#!/usr/bin/env bash
# Fails if a file outside experiments/ runs or sources something under experiments/.
#
# The release and main branches are produced from develop with experiments/ removed
# (.github/scripts/release/sync-release.sh), so a build script that calls into it works on
# develop and breaks on the release tree. A comment may name experiments/ as being on develop;
# only a line that is not a comment is checked.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

failed=0
while IFS= read -r file; do
    [[ "$file" == "scripts/tests/no-experiments-dependency.test.sh" ]] && continue
    case "$file" in
        *.sh|*.yml|*.yaml|*.kts|*.gradle|*.toml|*.rs|*.py) ;;
        *) continue ;;
    esac
    [[ -f "$file" ]] || continue
    hits="$(grep -nE 'experiments/' "$file" | grep -vE '^[0-9]+:[[:space:]]*(#|//|\*|/\*)' || true)"
    if [[ -n "$hits" ]]; then
        # Workflow path filters name the folder without running it.
        hits="$(grep -vE "^[0-9]+:[[:space:]]*-[[:space:]]+'experiments/" <<< "$hits" || true)"
    fi
    # These describe the folder or skip when it is absent (accessibility-runtime checks
    # for the file and skips); none of them fails on a tree without it.
    case "$file" in
        .github/workflows/release-sync.yml|scripts/tests/renderer/accessibility-runtime.test.sh|scripts/tests/sync-release.test.sh|scripts/tests/core-has-no-dioxus.test.sh|.github/scripts/release/sync-release.sh) continue ;;
    esac
    if [[ -n "$hits" ]]; then
        echo "FAIL  $file uses experiments/:"
        echo "$hits" | sed 's/^/      /'
        failed=1
    fi
done < <(git ls-files -- . ':!experiments/')

if [[ "$failed" -ne 0 ]]; then
    echo "Files outside experiments/ must not depend on it; it is removed from release and main."
    exit 1
fi
echo "ok    nothing outside experiments/ runs or sources it"

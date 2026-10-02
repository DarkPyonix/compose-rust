#!/usr/bin/env bash
# Fails if the planning documents are missing from the working branch.
#
# They exist only on develop: scripts/publish-main.sh strips them when it builds the
# public branch. That makes them easy to lose by accident, and the loss is quiet. A
# worktree cut from main or release starts without them, and merging such a branch back
# into develop replays the deletion as if someone meant it. That has happened.
#
# Publishing is one way: develop to release to main. Nothing that has been through the
# publish script should ever be merged back.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

# Which branch this tree belongs to, which is not always a question git can answer.
#
# A CI checkout is detached, so `git rev-parse --abbrev-ref HEAD` says "HEAD". GitHub
# names the ref instead, but not the same way for every event: on a pull request
# GITHUB_REF_NAME is the merge ref, "8/merge", and the branch being proposed is in
# GITHUB_HEAD_REF. Reading the wrong one made a release-to-main proposal look like a
# branch called "8/merge", which is not a published branch and so was held to develop's
# rules and failed.
branch="${GITHUB_HEAD_REF:-}"
if [[ -z "$branch" ]]; then
    branch="${GITHUB_REF_NAME:-}"
fi
if [[ -z "$branch" || "$branch" == "HEAD" ]]; then
    branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo detached)"
fi
case "$branch" in
    main|release)
        echo "ok    $branch is a published branch; the planning documents belong on develop"
        exit 0
        ;;
esac

required=(CLAUDE.md PROJECT.md docs/INTENT.md docs/SPEC.md)
missing=()
for doc in "${required[@]}"; do
    [[ -s "$doc" ]] || missing+=("$doc")
done

if (( ${#missing[@]} )); then
    echo "error: the planning documents are missing from '$branch':" >&2
    printf '  %s\n' "${missing[@]}" >&2
    cat >&2 <<'HINT'

This usually means a branch cut from main or release was merged into develop, which
replays the deletion publish-main.sh made. Restore them from the last commit that had
them, and rebase the offending work onto develop instead of merging it:

  git log --diff-filter=D --oneline -- CLAUDE.md
  git checkout <commit-before-that> -- CLAUDE.md PROJECT.md docs/INTENT.md docs/SPEC.md
HINT
    exit 1
fi

echo "ok    planning documents present on $branch"

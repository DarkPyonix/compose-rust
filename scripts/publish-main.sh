#!/usr/bin/env bash
# Usage: ./scripts/publish-main.sh [--write] [--source BRANCH] [--target BRANCH]
#
# Produces or updates the public branch from `develop`.
#
# `develop` has everything. The public tree is the source code, the root
# README.md, and docs/guide/ (the guide site). The internal planning documents
# -- PROJECT.md, CLAUDE.md, and everything directly under docs/, which is where
# INTENT.md and SPEC.md live -- exist only on develop.
#
# The default target is `release`, not `main`: `main` is protected and only
# moves through a pull request, which .github/workflows/publish-main.yml opens
# from `release`. Pass `--target main` to write it directly, which works only
# where the protection does not apply.
#
#   --write            actually update the target branch. Without it, this is
#                      a dry run that only prints what it would do.
#   --source BRANCH    branch to take content from (default: develop)
#   --target BRANCH    branch to write (default: release)
#
#
# ## Approach: a merge commit built with plumbing, never a checkout
#
# The obvious implementations are both dangerous. Checking out `main`,
# deleting files and committing puts `rm` near a real working tree; and
# `git filter-branch`/`filter-repo` rewrites history, so every previously
# pushed `main` commit changes hash and the branch can only move by force.
#
# Instead this script never touches any working tree or any checkout. It
# builds the target tree entirely in memory:
#
#   1. read `develop`'s tree into a *temporary* index (GIT_INDEX_FILE), which
#      is a scratch file in $TMPDIR -- the repository's real index is not
#      opened, let alone modified;
#   2. drop the private paths from that temporary index with
#      `git rm --cached`, which by construction only edits the index;
#   3. `git write-tree` to turn it into a real tree object;
#   4. `git commit-tree` that tree with two parents -- the previous `main`
#      first, `develop` second -- and move refs/heads/main to the result.
#
# That makes the guarantees easy to state:
#
#   - Nothing in the working tree is ever created, modified or deleted. Not
#     on develop, not on main, not on the branch you happen to be standing
#     on. The only thing that changes is one ref.
#   - Re-runnable. Because `develop` is recorded as a parent, the next run
#     starts from the new develop and fast-forwards main's content; history
#     is append-only and `main` never needs a force push.
#   - Idempotent. If the filtered tree already equals the target's tree, the
#     script reports "up to date" and creates no commit.
#   - It refuses to run with uncommitted changes. Nothing here would lose
#     them, but a dirty tree usually means the state you think you are
#     publishing is not the state that is committed.
#   - It never pushes. It prints the command for a human to run.

set -euo pipefail

write=0
source_branch="develop"
target_branch="release"

while (( $# )); do
    case "$1" in
        --write) write=1 ;;
        --source) shift; source_branch="${1:-}" ;;
        --target) shift; target_branch="${1:-}" ;;
        -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "usage: $0 [--write] [--source BRANCH] [--target BRANCH]" >&2; exit 2 ;;
    esac
    shift
done
[[ -n "$source_branch" && -n "$target_branch" ]] \
    || { echo "error: --source and --target need a branch name" >&2; exit 2; }

git rev-parse --git-dir >/dev/null 2>&1 \
    || { echo "error: not inside a git repository" >&2; exit 1; }

if [[ -n "$(git status --porcelain)" ]]; then
    echo "error: the working tree has uncommitted changes; commit or set them aside first" >&2
    git status --short >&2
    exit 1
fi

git rev-parse --verify -q "refs/heads/$source_branch" >/dev/null \
    || { echo "error: no such branch: $source_branch (pass --source to use another)" >&2; exit 1; }
source_commit="$(git rev-parse "refs/heads/$source_branch")"

target_commit=""
# A fresh clone, and every CI checkout, has the remote-tracking ref but no local
# branch. Starting from nothing there would build a commit with no link to what is
# already published, and the push is then rejected as a non fast-forward.
if ! git rev-parse --verify -q "refs/heads/$target_branch" >/dev/null \
    && git rev-parse --verify -q "refs/remotes/origin/$target_branch" >/dev/null; then
    git update-ref "refs/heads/$target_branch" "refs/remotes/origin/$target_branch"
    echo "note: started $target_branch from origin/$target_branch"
fi

if git rev-parse --verify -q "refs/heads/$target_branch" >/dev/null; then
    target_commit="$(git rev-parse "refs/heads/$target_branch")"
fi

# --- work out what to remove ------------------------------------------------
#
# Named documents, plus every file *directly* under docs/. The depth rule is
# what keeps this correct as develop advances: a new planning document added
# to docs/ is excluded without editing this script, while docs/guide/ and any
# other subdirectory is published untouched.
private_paths=()
for path in PROJECT.md CLAUDE.md; do
    git cat-file -e "$source_commit:$path" 2>/dev/null && private_paths+=("$path")
done
while IFS= read -r name; do
    [[ -n "$name" ]] && private_paths+=("docs/$name")
done < <(git ls-tree --name-only "$source_commit:docs" 2>/dev/null | while IFS= read -r entry; do
    git cat-file -e "$source_commit:docs/$entry" 2>/dev/null \
        && [[ "$(git cat-file -t "$source_commit:docs/$entry")" == blob ]] \
        && echo "$entry"
done)

# --- build the filtered tree in a temporary index ---------------------------
tmp_index="$(mktemp -t dxc-publish-index.XXXXXX)"
trap 'rm -f "$tmp_index"' EXIT
rm -f "$tmp_index"   # git wants to create the index file itself

filtered_tree="$(
    export GIT_INDEX_FILE="$tmp_index"
    git read-tree "$source_commit"
    if (( ${#private_paths[@]} )); then
        # --cached edits only the index. With GIT_INDEX_FILE pointed at a
        # scratch file, that is a file in $TMPDIR: no working tree, and not
        # even the index of this repository, is reachable from here.
        #
        # --force is needed, and is safe precisely because of that: git
        # otherwise refuses to drop an entry that differs from HEAD, to
        # protect unsaved work. Here HEAD is whatever branch happens to be
        # checked out, which has nothing to do with the temporary index being
        # filtered, and there is no file on disk to lose.
        git rm --cached --force --quiet --ignore-unmatch -- "${private_paths[@]}"
    fi
    git write-tree
)"

echo "source: $source_branch ($(git rev-parse --short "$source_commit"))"
if [[ -n "$target_commit" ]]; then
    echo "target: $target_branch ($(git rev-parse --short "$target_commit"))"
else
    echo "target: $target_branch (does not exist yet; it will be created)"
fi
echo "excluded from $target_branch:"
for path in "${private_paths[@]}"; do
    echo "  - $path"
done

if [[ -n "$target_commit" && "$(git rev-parse "$target_commit^{tree}")" == "$filtered_tree" ]]; then
    echo "$target_branch is already up to date with $source_branch; nothing to do."
    exit 0
fi

if (( ! write )); then
    echo
    echo "This was a dry run. Nothing was written."
    echo "Re-run with --write to update $target_branch."
    exit 0
fi

# --- commit and move the ref ------------------------------------------------
#
# Parent order matters: the previous target first, so it keeps a linear
# first-parent history, and develop second, so it records exactly which
# develop commit it was published from.
parents=()
[[ -n "$target_commit" ]] && parents+=(-p "$target_commit")
parents+=(-p "$source_commit")

short_source="$(git rev-parse --short "$source_commit")"
excluded_list="$(printf '  %s\n' "${private_paths[@]}")"
message="Publish: $source_branch $short_source to $target_branch"
message+=$'\n\n'
message+="Generated by scripts/publish-main.sh. The internal planning documents"
message+=$'\n'
message+="listed below are kept on $source_branch only:"
message+=$'\n'
message+="$excluded_list"

new_commit="$(git commit-tree "$filtered_tree" "${parents[@]}" -m "$message")"
if [[ -n "$target_commit" ]]; then
    # The old value is asserted, so a concurrent update is an error rather
    # than something this script silently overwrites.
    git update-ref "refs/heads/$target_branch" "$new_commit" "$target_commit"
else
    git update-ref "refs/heads/$target_branch" "$new_commit" ""
fi

echo
echo "Updated $target_branch -> $(git rev-parse --short "$new_commit")"
echo "No working tree or checkout was modified."
echo
echo "This script does not push. To publish it, run:"
echo "  git push origin $target_branch"

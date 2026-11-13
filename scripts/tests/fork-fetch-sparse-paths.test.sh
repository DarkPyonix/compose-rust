#!/usr/bin/env bash
# The scripts that fetch one directory of the Compose fork (fetch-fork-window.sh,
# fetch-fork-skiko.sh, fetch-design-systems.sh) must check that directory out on every
# system the build runs on, Git Bash on Windows included.
#
# Each one asks git for a sparse checkout of an anchored pattern such as /extended/window/.
# Passed as a command line argument, Git Bash rewrites anything that looks like a POSIX path
# before a native program sees it, so git.exe was handed "C:/Program Files/Git/extended/window/".
# The fetch then succeeded, the checkout held nothing, and the Windows renderer build stopped
# with "has no extended/window; pin a commit that does" for a commit that does.
#
# This runs each script in a copy of the tree against a local fork that holds the three
# directories, and checks that each one checks its directory out and that git recorded the
# pattern as written. On Linux and macOS it passes either way; on Windows (Git Bash) it is the
# test that fails while the pattern goes through the command line.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "fork fetch scripts check out their directory (sparse pattern reaches git as written)"

mkdir -p "$repo/.scratch"
work="$(mktemp -d "$repo/.scratch/fork-fetch-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# A fork with the three directories, served over file:// the way GitHub serves the real one:
# a commit fetched by its id, at depth 1, without blobs outside the checkout.
fork="$work/fork"
git init -q "$fork"
mkdir -p "$fork/extended/window/common" "$fork/extended/skiko" "$fork/extended/design-systems" "$fork/other"
echo window > "$fork/extended/window/common/module.yaml"
echo skiko > "$fork/extended/skiko/build-skiko-static-jvm.sh"
echo tokens > "$fork/extended/design-systems/tokens.txt"
echo elsewhere > "$fork/other/file.txt"
git -C "$fork" add -A
git -C "$fork" -c user.name=test -c user.email=test@example.invalid commit -q -m fork
git -C "$fork" config uploadpack.allowFilter true
git -C "$fork" config uploadpack.allowAnySHA1InWant true
revision="$(git -C "$fork" rev-parse HEAD)"
# Git Bash's pwd -W gives C:/..., which a file URL needs as file:///C:/...
fork_path="$(cd "$fork" && { pwd -W 2>/dev/null || pwd; })"
case "$fork_path" in
    /*) fork_url="file://$fork_path" ;;
    *) fork_url="file:///$fork_path" ;;
esac

# What git records when an anchored pattern is passed on the command line here, printed so
# a failure below shows the rewrite itself.
probe="$work/probe"
git init -q "$probe"
git -C "$probe" sparse-checkout set --no-cone /extended/window/
echo "  git recorded the command line pattern /extended/window/ as: $(git -C "$probe" sparse-checkout list)"

# A copy of the tree with the scripts under test, pinned to the local fork.
tree="$work/tree"
mkdir -p "$tree/scripts" "$tree/renderer/scripts"
cp "$repo/scripts/fetch-fork-window.sh" "$repo/scripts/fetch-fork-skiko.sh" "$repo/scripts/fetch-design-systems.sh" \
    "$tree/scripts/"
sed -e "s|^FORK=\".*\"$|FORK=\"$fork_url\"|" \
    -e "s|^REVISION=\"[0-9a-f]\{40\}\"$|REVISION=\"$revision\"|" \
    -e "s|^WINDOW_REVISION=\"[0-9a-f]\{40\}\"$|WINDOW_REVISION=\"$revision\"|" \
    "$repo/renderer/scripts/build-compose.sh" > "$tree/renderer/scripts/build-compose.sh"
grep -q "^FORK=\"$fork_url\"$" "$tree/renderer/scripts/build-compose.sh" || fail "could not point the copy at the local fork"

check() {
    local script="$1" directory="$2" expected="$3" output dest
    if ! output="$("$tree/scripts/$script" 2>&1)"; then
        fail "$script failed: $output"
        dest="$(find "$tree/.scratch" -mindepth 2 -maxdepth 2 -type d -name "$revision" 2>/dev/null | head -1)"
        return
    fi
    dest="$(tail -1 <<< "$output")"
    if [[ -e "$dest/$directory/$expected" ]]; then
        echo "  ok: $script checked out $directory"
    else
        fail "$script printed $dest, which holds no $directory/$expected"
    fi
    if [[ -e "$dest/other/file.txt" ]]; then
        fail "$script checked out more than $directory"
    fi
    local patterns
    patterns="$(git -C "$dest" sparse-checkout list 2>/dev/null || true)"
    if [[ "$patterns" != "/$directory/" ]]; then
        fail "$script left the sparse pattern as '$patterns', not '/$directory/'"
    fi
}

check fetch-fork-window.sh extended/window common/module.yaml
check fetch-fork-skiko.sh extended/skiko build-skiko-static-jvm.sh
if grep -q "sparse-checkout" "$tree/scripts/fetch-design-systems.sh"; then
    check fetch-design-systems.sh extended/design-systems tokens.txt
fi

if (( failures > 0 )); then
    echo "fork-fetch-sparse-paths: $failures failure(s)" >&2
    exit 1
fi
echo "fork-fetch-sparse-paths: ok"

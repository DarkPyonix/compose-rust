#!/usr/bin/env bash
# Usage: ./scripts/setup-worktrees.sh [--all] [--check]
#
# Gives every git worktree its own build directory, which means leaving Cargo's default
# alone and removing anything that overrides it.
#
#   (no flag)  fix this worktree
#   --all      fix every worktree of this repository
#   --check    look at every worktree, change nothing, exit non-zero if any of them
#              overrides the build directory
#
# Two checkouts must not build into one directory. Cargo leaves the package path out of
# the unit hash for a path package, so two checkouts holding identical sources become a
# single cache entry, and the binary that runs is whichever one compiled first. Everything
# fixed at compile time then comes from that checkout: env!("CARGO_MANIFEST_DIR"),
# include_str!, whatever a build script left in OUT_DIR, the absolute install name burned
# into the renderer. A run in the main checkout has already generated into an agent's
# worktree this way, and a sample built there came up with a black window because the
# binary had been compiled somewhere else.
#
# Removing the override is not enough on its own. A directory two checkouts have already
# built into still holds their binaries, and cargo goes on reusing them: after the override
# came off, a cargo build --bin codegen in the main checkout rebuilt nothing and left a
# binary compiled in a different worktree in place. So a build directory an override named
# is discarded as well. It is a cache, and a cold build of this workspace is about 23
# seconds.
#
# The saving that override bought was real: ten worktrees each with their own target/
# filled a 349GB volume to 100% once. So this reports what each worktree costs and how
# much room is left, because the answer to that incident is fewer worktrees and pruning
# them, not one build directory shared by checkouts that overwrite each other.

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

all=0
check=0
for arg in "$@"; do
    case "$arg" in
        --all) all=1 ;;
        --check) check=1 ;;
        *) echo "usage: $0 [--all] [--check]" >&2; exit 2 ;;
    esac
done

failures=0
# Build directories an override named. Whatever is in them was compiled by more than one
# checkout, so none of it can be trusted and all of it is quick to rebuild.
poisoned=()

ok()   { printf 'ok    %s\n' "$1"; }
note() { printf '      %s\n' "$1"; }
fail() {
    failures=$((failures + 1))
    printf 'FAIL  %s\n' "$1" >&2
    shift
    local line
    for line in "$@"; do printf '        %s\n' "$line" >&2; done
}

# Every line of a cargo config that is neither blank nor a comment.
config_body() {
    grep -vE '^[[:space:]]*(#|$)' "$1"
}

# Whether a cargo config sets a build directory. Cargo spells it target-dir under [build];
# the underscore spelling is accepted too and is easy to write by accident.
sets_target_dir() {
    config_body "$1" | grep -qE '^[[:space:]]*target[-_]dir[[:space:]]*='
}

# Whether the file says nothing except which build directory to use, which is true of the
# one this script used to write and of nothing a person would have put there on purpose.
# A config with anything else in it gets reported rather than edited: it is not ours.
only_sets_target_dir() {
    ! config_body "$1" | grep -qvE '^[[:space:]]*(\[build\]|target[-_]dir[[:space:]]*=.*)$'
}

handle() {
    local tree="$1"
    local config="$tree/.cargo/config.toml"

    if [[ -f "$config" ]] && sets_target_dir "$config"; then
        local where
        where="$(config_body "$config" | grep -E '^[[:space:]]*target[-_]dir' | head -1)"
        if [[ $check -eq 1 ]]; then
            fail "$tree overrides the build directory" \
                 "$config says: $(echo "$where" | xargs)" \
                 "Two checkouts sharing one build directory run each other's binaries." \
                 "fix: $0 --all"
            return
        fi
        if only_sets_target_dir "$config"; then
            local named
            named="$(sed -E 's/^[^=]*=[[:space:]]*//; s/^"//; s/"[[:space:]]*$//' <<<"$(echo "$where" | xargs)")"
            [[ -n "$named" ]] && poisoned+=("$named")
            rm -f "$config"
            rmdir "$tree/.cargo" 2>/dev/null
            ok "$tree: removed the build directory override"
        else
            fail "$tree has a cargo config this script will not edit" \
                 "$config says: $(echo "$where" | xargs)" \
                 "It holds settings besides the build directory, so remove that one key by hand."
            return
        fi
    else
        ok "$tree: builds into its own target/"
    fi

    if [[ $check -eq 0 && -d "$tree/target" ]]; then
        note "$(du -sh "$tree/target" 2>/dev/null | cut -f1) in $tree/target"
    fi
}

if [[ $all -eq 1 || $check -eq 1 ]]; then
    trees=()
    while IFS= read -r line; do
        [[ "$line" == worktree\ * ]] && trees+=("${line#worktree }")
    done < <(git -C "$repo_root" worktree list --porcelain)
else
    trees=("$repo_root")
fi

for tree in "${trees[@]}"; do
    handle "$tree"
done

# Discard what the overrides were writing into. Two checkouts built there, so which one
# any given artifact came from is unknowable, and cargo will hand it back as fresh.
for dir in ${poisoned+"${poisoned[@]}"}; do
    [[ -d "$dir" ]] || continue
    size="$(du -sh "$dir" 2>/dev/null | cut -f1)"
    rm -rf "$dir"
    ok "discarded $dir ($size), built by more than one checkout"
done

# An override in the environment beats every config file, so it is worth the same look.
if [[ -n "${CARGO_TARGET_DIR:-}" ]]; then
    fail "CARGO_TARGET_DIR is set in this environment" \
         "value: $CARGO_TARGET_DIR" \
         "It overrides every cargo config, so unset it before building." \
         "A test that makes and removes its own temporary build directory may set it;" \
         "nothing that outlives a single command should."
fi

if [[ $check -eq 0 ]]; then
    note "$(df -h "$repo_root" | tail -1 | awk '{print $4}') free on the volume holding this repository"
    note "a worktree is about 1GB built and tested and about 2GB after scripts/check.sh, so prune the ones nobody is using"
fi

exit $((failures > 0))

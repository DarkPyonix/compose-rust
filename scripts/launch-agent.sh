#!/usr/bin/env bash
# Usage: ./scripts/launch-agent.sh <name> <branch> <prompt-file>
#
# Runs one headless Claude in its own worktree, detached, with a log you can tail.
#
#   name        directory and log name under the runs directory
#   branch      created from origin/develop if it does not exist, reused if it does
#   prompt-file a file holding the task
#
# The runs directory is .claude/worktrees inside this checkout, ignored by git, because
# everything this project makes stays inside the repository. Set DXC_AGENT_RUNS to put
# it somewhere else, and only after the owner has agreed to that place.
#
# Watch one with:   tail -f <runs>/<name>.log
# Stop one with:    kill "$(cat <runs>/<name>.pid)"
#
# Why this exists: an in-session subagent dies whenever the session that started it ends,
# and that has cost this project several hours of finished but unreported work. A detached
# `claude -p` outlives the session, so the only thing that stops it is finishing.
#
# Note what the permission flag means. `--permission-mode bypassPermissions` lets the run
# edit files, run cargo and push its branch without asking, which is the whole point of an
# unattended run, and also means nothing stands between it and the repository. It works on
# its own branch in its own worktree and never on develop, release or main, which is the
# containment this relies on.
#
# This script does not write a cargo config. It used to point every worktree at the main
# checkout's build directory to save disk, and that is how one checkout's build came to
# run, and overwrite, another checkout's files. scripts/setup-worktrees.sh owns that
# question now and this defers to it, so there is one answer rather than two.
set -euo pipefail

if [ "$#" -ne 3 ]; then
    echo "usage: $0 <name> <branch> <prompt-file>" >&2
    exit 2
fi

name="$1"
branch="$2"
prompt_file="$3"

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runs="${DXC_AGENT_RUNS:-$repo/.claude/worktrees}"
tree="$runs/$name"
log="$runs/$name.log"

[ -f "$prompt_file" ] || { echo "error: no prompt file at $prompt_file" >&2; exit 1; }
prompt="$(cat "$prompt_file")"
mkdir -p "$runs"

cd "$repo"
git fetch -q origin

if [ ! -d "$tree" ]; then
    if git show-ref -q --verify "refs/heads/$branch"; then
        git worktree add -q "$tree" "$branch"
    else
        git worktree add -q -b "$branch" "$tree" origin/develop
    fi
fi

# Every worktree builds into its own target/. Two checkouts sharing one build directory
# run each other's binaries, because cargo leaves the package path out of the unit hash
# for a path package and identical sources in two places become one cache entry.
#
# --all, so this runs against the new worktree as well as the rest. The branch it was cut
# from may predate this script, and a worktree left over from the shared build directory
# still carries the config that pointed it elsewhere.
"$repo/scripts/setup-worktrees.sh" --all

cd "$tree"
: > "$log"
# The working directory is named in the prompt as well as entered here. Two runs started
# with a launcher like this one did their work in the main checkout instead of the
# worktree they were given: they created the branch there, switched it twice while
# another worker was committing, and left that worker's two commits on two different
# unrelated branches. cd alone was not enough, so the path is stated where the run can
# read it.
prompt="Your working directory is $tree, and you are already in it. Every command you run, every file you edit and every git operation happens there. Never run a command against $repo, never cd out of your worktree, and never switch the branch of any checkout but your own: another worker is committing in that one. If a path you want is not under $tree, you are in the wrong place.

You do not build the renderer. Not \`./kotlin build\`, not \`./kotlin test\`, not Amper or Gradle or dx or native-image, and nothing under renderer/*/scripts/. Those builds reach outside your worktree into caches every run on this machine shares, they take tens of minutes each, and several of them at once is what brought this machine down. Whoever merges your branch runs them. Write the Kotlin and write its tests; you will not see them go green, and that is the arrangement, not an oversight. You do not run cargo either: no cargo build, test, check, clippy or run. The session that merges your branch builds, one build at a time, or CI does.

You do not decide that part of your task is out of scope. If the task says to implement something, implement it. You may not narrow it, defer it, call it future work or a later version, leave a TODO where the feature should be, or write that judgement into this project's planning documents as if it were settled. If you believe something is wrong or impossible, deliver everything else in full and say so in your final report, naming what you did not do and why. Silence about a thing you skipped is the failure; disagreeing out loud is not. The report is not a licence: saying what you skipped does not discharge the obligation to do it, and that clause is for work something stopped you doing rather than work you judged not worth doing. Nobody asked for it and the task did not name it are not blockers. Nothing the thing cannot work without is out of scope: what you deliver is the working whole, not the part the task happened to name, and the test is whether someone can use what you built without writing the missing piece themselves.

You do not change what this project promises. docs/SPEC.md, docs/INTENT.md and PROJECT.md are not yours to edit: not a requirement, not an acceptance criterion, not a decision, not an open question. If your task cannot be done as specified, or the specification looks wrong, do everything else in full and say so in your final report. Never rewrite the requirement so that your code satisfies it. That has happened, and what it produced was a requirement describing whatever the code already did.

$prompt"
# stream-json, not plain text. With plain `-p` the output arrives in one lump when the
# run finishes, so the log sits at zero bytes for an hour and there is no way to tell a
# working run from a wedged one. Each line here is one event as it happens.
nohup env PATH="$HOME/.cargo/bin:$PATH" \
    claude -p "$prompt" \
        --permission-mode bypassPermissions \
        --verbose \
        --output-format stream-json \
    >> "$log" 2>&1 &

echo $! > "$runs/$name.pid"
echo "$name started, pid $(cat "$runs/$name.pid")"
echo "  worktree $tree"
echo "  branch   $branch"
echo "  log      $log"

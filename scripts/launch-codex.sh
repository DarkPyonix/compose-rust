#!/usr/bin/env bash
# Usage: ./scripts/launch-codex.sh [--builder] <name> <branch> <prompt-file> [model]
#
# Runs one headless Codex in its own worktree, detached, with a log you can tail.
#
# The twin of launch-agent.sh, which runs Claude. Everything about the worktree, the
# branch and the build directory is the same; only the program differs, so a task can be
# given to whichever has capacity.
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
# a detached run outlives the session, so the only thing that stops it is finishing.
#
# Note what the sandbox flag means. `--dangerously-bypass-approvals-and-sandbox` lets the
# run edit files, run cargo and push its branch without asking, which is the whole point
# of an unattended run, and also means nothing stands between it and the repository. It
# works on its own branch in its own worktree and never on develop, release or main, which
# is the containment this relies on.
#
# This script does not write a cargo config. It used to point every worktree at the main
# checkout's build directory to save disk, and that is how one checkout's build came to
# run, and overwrite, another checkout's files. scripts/setup-worktrees.sh owns that
# question now and this defers to it, so there is one answer rather than two.
set -euo pipefail

# --builder, or DXC_AGENT_BUILDER=1, starts the one temporary builder instead of an
# ordinary run. Every other run is told it builds nothing; the builder is told it is the
# only one that builds, one command at a time. The owner's rule of 2026-10-03: the session
# does not hold builds itself, it hands them to one temporary builder sub-agent.
builder="${DXC_AGENT_BUILDER:-}"
if [ "${1:-}" = "--builder" ]; then
    builder=1
    shift
fi

if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then
    echo "usage: $0 [--builder] <name> <branch> <prompt-file> [model]" >&2
    exit 2
fi

name="$1"
branch="$2"
prompt_file="$3"
# Optional, so a task can be given to a particular model when the default is not the one
# wanted. Empty means whatever codex is configured to use.
model="${4:-}"

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

# What a run is told about building. An ordinary run builds nothing; the builder, and only
# the builder, builds. Kept to one line each so agent-launchers.test.sh can run them.
build_rule="You do not build the renderer. Not \`./kotlin build\`, not \`./kotlin test\`, not Amper or Gradle or dx or native-image, and nothing under renderer/*/scripts/. Those builds reach outside your worktree into caches every run on this machine shares, they take tens of minutes each, and several of them at once is what brought this machine down. Whoever merges your branch runs them. Write the Kotlin and write its tests; you will not see them go green, and that is the arrangement, not an oversight. You do not run cargo either: no cargo build, test, check, clippy or run. A single temporary builder agent that the session starts for it builds your branch, one build at a time, or CI does."
if [ -n "$builder" ]; then
    build_rule="You are the single temporary builder. The session that started you runs no build of its own and has handed the building to you, and no other agent builds while you do. Build and test one command at a time, with CARGO_BUILD_JOBS=2 set, in your own worktree and its own target/ directory. Never set or share CARGO_TARGET_DIR and never point a build at another checkout. Run the renderer build or the Kotlin tests only when your task names them, and then also one at a time. Do not edit code beyond the minimal fixes a build needs to go through, and name every such fix in your final report together with each command you ran and its actual output."
fi

cd "$tree"
: > "$log"
# The working directory is named in the prompt as well as entered here. Two runs started
# with a launcher like this one did their work in the main checkout instead of the
# worktree they were given: they created the branch there, switched it twice while
# another worker was committing, and left that worker's two commits on two different
# unrelated branches. cd alone was not enough, so the path is stated where the run can
# read it.
prompt="Your working directory is $tree, and you are already in it. Every command you run, every file you edit and every git operation happens there. Never run a command against $repo, never cd out of your worktree, and never switch the branch of any checkout but your own: another worker is committing in that one. If a path you want is not under $tree, you are in the wrong place.

$build_rule

You do not decide that part of your task is out of scope. If the task says to implement something, implement it. You may not narrow it, defer it, call it future work or a later version, leave a TODO where the feature should be, or write that judgement into this project's planning documents as if it were settled. If you believe something is wrong or impossible, deliver everything else in full and say so in your final report, naming what you did not do and why. Silence about a thing you skipped is the failure; disagreeing out loud is not. The report is not a licence: saying what you skipped does not discharge the obligation to do it, and that clause is for work something stopped you doing rather than work you judged not worth doing. Nobody asked for it and the task did not name it are not blockers. Nothing the thing cannot work without is out of scope: what you deliver is the working whole, not the part the task happened to name, and the test is whether someone can use what you built without writing the missing piece themselves.

You do not change what this project promises. docs/SPEC.md, docs/INTENT.md and PROJECT.md are not yours to edit: not a requirement, not an acceptance criterion, not a decision, not an open question. If your task cannot be done as specified, or the specification looks wrong, do everything else in full and say so in your final report. Never rewrite the requirement so that your code satisfies it. That has happened, and what it produced was a requirement describing whatever the code already did.

$prompt"
# stream-json, not plain text. With plain `-p` the output arrives in one lump when the
# run finishes, so the log sits at zero bytes for an hour and there is no way to tell a
# working run from a wedged one. Each line here is one event as it happens.
nohup env PATH="$HOME/.cargo/bin:$PATH" \
    codex exec "$prompt" \
        ${model:+--model "$model"} \
        --dangerously-bypass-approvals-and-sandbox \
        --json \
    < /dev/null >> "$log" 2>&1 &

echo $! > "$runs/$name.pid"
echo "$name started, pid $(cat "$runs/$name.pid")"
echo "  worktree $tree"
echo "  branch   $branch"
echo "  log      $log"

#!/usr/bin/env bash
# Every launcher states the same rules to the run it starts.
#
# The rules live here rather than in the prompt each task is written with, because the
# prompt is written fresh every time by whoever is dispatching the work, and that is the
# thing that keeps going wrong. A run was once told, in its own task prompt, to do the
# exact thing this file forbids: six of them built the renderer at once and took the
# machine down. A rule that depends on remembering to restate it is not a rule.
#
# There are three launchers and they must not drift apart. An agent started through the
# one that was missed is an agent that never heard the rule.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

launchers=(scripts/launch-agent.sh scripts/launch-agy.sh scripts/launch-codex.sh)
failures=0

note() {
    echo "FAIL: $1" >&2
    failures=$((failures + 1))
}

for launcher in "${launchers[@]}"; do
    [[ -f "$launcher" ]] || { note "$launcher is missing, so one kind of run hears nothing"; continue; }

    # The worktree. Two runs once did their work in the main checkout, created a branch
    # there and switched it twice while another worker was committing, which left that
    # worker's commits on two unrelated branches.
    grep -q 'Your working directory is' "$launcher" ||
        note "$launcher does not tell the run where it is working"

    # The renderer build. Shared caches, tens of minutes, and it has run several at once.
    grep -q 'You do not build the renderer' "$launcher" ||
        note "$launcher does not forbid building the renderer"
    for forbidden in 'kotlin build' 'kotlin test' 'native-image'; do
        grep -q "$forbidden" "$launcher" ||
            note "$launcher does not name '$forbidden' among what a run must not do"
    done

    # Scope. A run that quietly delivers less than it was asked for, and says nothing,
    # costs more than one that argues: the gap is found later by someone who assumed it
    # was there.
    grep -q 'You do not decide that part of your task is out of scope' "$launcher" ||
        note "$launcher does not forbid a run narrowing its own task"
    grep -q 'say so in your final report' "$launcher" ||
        note "$launcher does not tell a run to report what it did not do"
    # The report clause on its own is a licence to skip anything, so long as the skipping
    # is confessed. A run took it that way: it left out the build that turns its module
    # into something linkable, called that outside its task, and said so. The report was
    # honest and the work was unfinished.
    grep -q 'The report is not a licence' "$launcher" ||
        note "$launcher lets a run discharge the work by confessing it skipped it"
    grep -q 'Nothing the thing cannot work without is out of scope' "$launcher" ||
        note "$launcher does not say the deliverable is the working whole"

    # The planning documents. A run that cannot meet a requirement and edits the
    # requirement leaves a document that records whatever the code already did, and the
    # loss is invisible afterwards because the document agrees with the code.
    grep -q 'You do not change what this project promises' "$launcher" ||
        note "$launcher does not forbid a run editing SPEC, INTENT or PROJECT"
    for document in docs/SPEC.md docs/INTENT.md PROJECT.md; do
        grep -q "$document" "$launcher" ||
            note "$launcher does not name $document as out of a run's hands"
    done
done

if [[ "$failures" -gt 0 ]]; then
    echo "$failures problem(s) in the agent launchers" >&2
    exit 1
fi
echo "ok: all ${#launchers[@]} launchers state the working directory, the build ban, the scope rule and what it does not let a run do, and the planning documents"

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
    # Cargo too. Several agents compiling Rust at once overloaded the machine.
    grep -q 'You do not run cargo either' "$launcher" ||
        note "$launcher does not forbid running cargo"
    ! grep -q 'Cargo inside your own worktree' "$launcher" ||
        note "$launcher still tells the run that cargo is allowed"
    for forbidden in 'kotlin build' 'kotlin test' 'native-image'; do
        grep -q "$forbidden" "$launcher" ||
            note "$launcher does not name '$forbidden' among what a run must not do"
    done

    # No quotation marks in what is injected. The rules are assembled inside a double
    # quoted shell string, so a sentence that quotes a phrase closes that string and
    # hands the rest of the paragraph to the shell as commands. It is valid shell and it
    # is not a prompt: a launcher died with `asked: command not found` after a quoted
    # phrase was added to the scope rule, and every check below this one passed while it
    # did, because they read the file rather than run it.
    stray=$(awk '/^prompt="Your working directory/ , /^\$prompt"$/' "$launcher" |
        sed '1s/^prompt="//; $s/^\$prompt"$//' | tr -cd '"' | wc -c | tr -d ' ')
    [ "${stray:-0}" -eq 0 ] ||
        note "$launcher quotes something inside the prompt, which ends the string early"

    # Building. The rule is chosen before the prompt is assembled: an ordinary run is told
    # it builds nothing, and a run started with --builder is told it is the one temporary
    # builder. The owner's rule of 2026-10-03 is that the session does not hold builds
    # itself; it hands them to one builder sub-agent. These run the launcher's own lines,
    # so a quotation mark that breaks the string fails here rather than at launch.
    grep -qF -e '"${1:-}" = "--builder"' "$launcher" ||
        note "$launcher has no --builder flag"
    grep -q 'DXC_AGENT_BUILDER' "$launcher" ||
        note "$launcher cannot start a builder through DXC_AGENT_BUILDER"
    awk '/^prompt="Your working directory/ , /^\$prompt"$/' "$launcher" | grep -qx '\$build_rule' ||
        note "$launcher does not put the build rule into the prompt"
    rule_block=$(awk '/^build_rule="/ , /^fi$/' "$launcher")
    for line in $(printf '%s\n' "$rule_block" | grep -n 'build_rule="' | cut -d: -f1); do
        body=$(printf '%s\n' "$rule_block" | sed -n "${line}p" | sed 's/^ *build_rule="//; s/"$//')
        [ "$(printf '%s' "$body" | tr -cd '"' | wc -c | tr -d ' ')" -eq 0 ] ||
            note "$launcher quotes something inside a build rule, which ends the string early"
        ! printf '%s' "$body" | grep -q '[^\\]`' ||
            note "$launcher has an unescaped backtick in a build rule, which the shell runs"
    done
    normal=$(builder='' bash -c "$rule_block"$'\n''printf %s "$build_rule"' 2>&1)
    built=$(builder=1 bash -c "$rule_block"$'\n''printf %s "$build_rule"' 2>&1)
    for phrase in 'You do not build the renderer' 'You do not run cargo either' 'no cargo build, test, check, clippy or run'; do
        printf '%s' "$normal" | grep -q "$phrase" ||
            note "$launcher in normal mode no longer says '$phrase'"
    done
    ! printf '%s' "$normal" | grep -q 'You are the single temporary builder' ||
        note "$launcher tells an ordinary run that it is the builder"
    for phrase in 'You are the single temporary builder' 'one command at a time' 'CARGO_BUILD_JOBS=2' 'own target/' 'Never set or share CARGO_TARGET_DIR' 'minimal fixes'; do
        printf '%s' "$built" | grep -q "$phrase" ||
            note "$launcher in builder mode does not say '$phrase'"
    done
    ! printf '%s' "$built" | grep -q 'You do not run cargo either' ||
        note "$launcher tells the builder it may not build"

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
echo "ok: all ${#launchers[@]} launchers state the working directory, the build ban, the builder mode, the scope rule and what it does not let a run do, and the planning documents"

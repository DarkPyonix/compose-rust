#!/usr/bin/env bash
# Fails if a heavy workflow can run without being asked for, if a workflow that runs on its
# own has no concurrency group, or if the merge gate for the heavy workflows is gone.
#
# The heavy workflows (Compose from fork, the Kotlin/Native desktop renderers, the native
# renderer, the Windows resize runs) take half an hour to two hours each. Started on every
# push to every pull request, they queued behind each other for hours. They now run when a
# pull request carries the run-heavy-ci label or someone dispatches them, and
# heavy-checks.yml fails until each one that applies has passed on the pull request's head.
# Both halves are checked here by .github/scripts/heavy-checks.py: the lint on this tree,
# and the same lint on altered copies, to see that it notices each way of breaking the rule.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/.github/scripts/heavy-checks.py"
workflows="$repo_root/.github/workflows"
scratch="$repo_root/.scratch/ci-on-demand-test"
rm -rf "$scratch"
mkdir -p "$scratch"
trap 'rm -rf "$scratch"' EXIT

tests_failed=0
fail() {
    tests_failed=$((tests_failed + 1))
    printf 'FAIL %s\n' "$1" >&2
}

python3 -I "$script" self-test || fail "the path filter matcher or the workflow reader is wrong"
python3 -I "$script" lint || fail "the workflows in this tree break the on-demand rule"

# Each case copies the workflows, breaks one thing with a Python edit and expects the lint to
# fail with the given words.
expect_lint_failure() {
    local name="$1" file="$2" old="$3" new="$4" words="$5"
    local dir="$scratch/$name"
    mkdir -p "$dir"
    cp "$workflows"/*.yml "$dir/"
    if ! python3 -I - "$dir/$file" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1:4]
text = open(path).read()
if old not in text:
    sys.exit(f"{old!r} is not in {path}")
open(path, "w").write(text.replace(old, new, 1))
PY
    then
        fail "$name: could not set up the case"
        return
    fi
    local out
    if out="$(HEAVY_CHECKS_WORKFLOWS="$dir" python3 -I "$script" lint 2>&1)"; then
        fail "$name: the lint passed"
    elif ! grep -qF -- "$words" <<<"$out"; then
        fail "$name: the lint failed without saying '$words':"$'\n'"$out"
    else
        echo "ok    $name"
    fi
}

expect_lint_failure "a heavy job that does not wait for the request" \
    test-kn-desktop-renderer.yml "    needs: requested
" "" "does not depend on 'requested'"

expect_lint_failure "a heavy workflow without its request job" \
    test-compose-from-fork.yml "    name: Heavy checks requested" "    name: Requested" \
    "runs whether or not anyone asked"

expect_lint_failure "a request job that ignores the label" \
    test-compose-from-fork.yml "contains(github.event.pull_request.labels.*.name, 'run-heavy-ci')" \
    "true" "does not test for the 'run-heavy-ci' label"

expect_lint_failure "a heavy workflow that runs on pushes to develop" \
    test-native-renderer.yml "    branches: [main]" "    branches: [main, develop]" \
    "only main (the publication) and tags may"

expect_lint_failure "a heavy workflow that ignores new pushes" \
    test-kn-desktop-renderer.yml "types: [synchronize, reopened, labeled]" \
    "types: [reopened, labeled]" "pull_request types lack 'synchronize'"

expect_lint_failure "another label cancelling a heavy run" \
    test-compose-from-fork.yml "\${{ github.event.action == 'labeled' && github.event.label.name != 'run-heavy-ci' && format('-other-label-{0}', github.run_id) || '' }}" \
    "" "adding any label would cancel a heavy run"

expect_lint_failure "a workflow without concurrency" \
    test.yml "concurrency:" "x-concurrency:" "test.yml: no concurrency group"

expect_lint_failure "a pull request run that cancels pushes too" \
    test.yml "cancel-in-progress: \${{ github.event_name == 'pull_request' }}" \
    "cancel-in-progress: true" "test.yml: cancel-in-progress is 'true'"

expect_lint_failure "a gate that some pull requests skip" \
    heavy-checks.yml "types: [opened, synchronize, reopened, labeled, unlabeled]" \
    "types: [opened, synchronize, reopened, labeled, unlabeled]
    paths: ['renderer/**']" "filtered, so some pull requests would have no gate"

missing="$scratch/no-gate"
mkdir -p "$missing"
cp "$workflows"/*.yml "$missing/"
rm "$missing/heavy-checks.yml"
if out="$(HEAVY_CHECKS_WORKFLOWS="$missing" python3 -I "$script" lint 2>&1)"; then
    fail "no gate: the lint passed"
elif ! grep -qF "heavy-checks.yml is missing" <<<"$out"; then
    fail "no gate: the lint failed without naming the gate:"$'\n'"$out"
else
    echo "ok    no gate"
fi

# The gate's label and the label the heavy workflows test for are one name.
label="$(sed -n 's/^LABEL = "\(.*\)"$/\1/p' "$script")"
if ! grep -qF "'$label'" "$workflows/heavy-checks.yml"; then
    fail "heavy-checks.yml does not read the '$label' label the script expects"
fi

if (( tests_failed > 0 )); then
    echo "$tests_failed check(s) failed" >&2
    exit 1
fi
echo "ok    heavy CI runs on request and the gate holds the merge"

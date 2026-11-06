#!/usr/bin/env bash
# .github/scripts/delete-pr-caches.sh: deletes one pull request's caches and nothing else,
# and never fails the run. A fake gh records what it was asked.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/.github/scripts/delete-pr-caches.sh"
work="$(mktemp -d "${TMPDIR:-/tmp}/delete-caches.XXXXXX")"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"
cat > "$work/bin/gh" <<'GH'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_LOG"
case "$1 $2" in
    "cache list") [[ "${FAKE_LIST_FAILS:-}" ]] && { echo "HTTP 403" >&2; exit 1; }; printf '11\n12\n' ;;
    "cache delete") [[ "$3" == "${FAKE_DELETE_FAILS:-}" ]] && exit 1; exit 0 ;;
esac
GH
chmod +x "$work/bin/gh"
failures=0
ok() { echo "ok   - $1"; }
bad() { echo "FAIL - $1"; failures=$((failures + 1)); }
run() { : > "$work/log"; PATH="$work/bin:$PATH" FAKE_LOG="$work/log" "$@"; }

run "$script" 42 >/dev/null; rc=$?
[[ $rc -eq 0 ]] && ok "exits 0" || bad "exit $rc"
grep -q -- "--ref refs/pull/42/merge" "$work/log" && ok "lists only the pull request's ref" || bad "list ref"
[[ "$(grep -c 'cache delete' "$work/log")" -eq 2 ]] && ok "deletes each listed cache" || bad "delete count"
! grep -q 'refs/heads\|refs/tags' "$work/log" && ok "touches no branch or tag ref" || bad "other ref"

out="$(FAKE_LIST_FAILS=1 run "$script" 42)"; rc=$?
[[ $rc -eq 0 && "$out" == *"::warning"* ]] && ok "a failing listing warns and exits 0" || bad "list failure: $rc $out"
out="$(FAKE_DELETE_FAILS=11 run "$script" 42)"; rc=$?
[[ $rc -eq 0 && "$out" == *"::warning"* && "$out" == *"deleted cache 12"* ]] && ok "a failing delete warns, the rest go on" || bad "delete failure: $out"
for arg in "" "abc" "42; rm -rf /" "../x"; do
    out="$(run "$script" "$arg")"; rc=$?
    [[ $rc -eq 0 && "$out" == *"::warning"* && ! -s "$work/log" ]] && ok "refuses '$arg'" || bad "accepted '$arg'"
done
wf="$repo_root/.github/workflows/cache-cleanup.yml"
grep -q 'types: \[closed\]' "$wf" && ok "runs on closed pull requests" || bad "trigger"
grep -q 'actions: write' "$wf" && ! grep -qE 'contents: write|pull-requests: write' "$wf" && ok "only actions: write" || bad "permissions"
! grep -qE 'run:.*\$\{\{' "$wf" && ok "no expression inside a run line" || bad "expression in run"
[[ "$failures" -eq 0 ]] || exit 1
echo "all passed"

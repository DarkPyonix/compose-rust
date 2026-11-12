#!/usr/bin/env bash
# .github/scripts/find-released-renderer.sh: when the samples use the released renderer.
# A fake gh stands in for GitHub.
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/.github/scripts/find-released-renderer.sh"
work="$(mktemp -d "${TMPDIR:-/tmp}/find-released.XXXXXX")"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"
cat > "$work/bin/gh" <<'GH'
#!/usr/bin/env bash
case "$FAKE_MODE" in
    all) for t in macos-aarch64 linux-x64 linux-arm64 windows-x64; do
             echo "compose-rust-renderer-v1.0.0-$t.tar.gz"; echo "compose-rust-renderer-v1.0.0-$t.tar.gz.sha256"; done ;;
    partial) echo "compose-rust-renderer-v1.0.0-linux-x64.tar.gz" ;;
    missing) echo "release not found" >&2; exit 1 ;;
    broken) echo "HTTP 401: Bad credentials" >&2; exit 1 ;;
esac
GH
chmod +x "$work/bin/gh"
failures=0
check() { # mode expected_output expected_warning(yes|no)
    out="$(PATH="$work/bin:$PATH" FAKE_MODE="$1" "$script" 1.0.0 2>/dev/null)"
    if [[ "$out" == *"released=$2"* ]]; then echo "ok   - $1 -> $2"; else echo "FAIL - $1 gave: $out"; failures=$((failures + 1)); fi
    if [[ "$3" == yes ]]; then
        [[ "$out" == *"::warning"* ]] && echo "ok   - $1 warns" || { echo "FAIL - $1 did not warn"; failures=$((failures + 1)); }
    else
        [[ "$out" != *"::warning"* ]] && echo "ok   - $1 is quiet" || { echo "FAIL - $1 warned"; failures=$((failures + 1)); }
    fi
}
check all true no
check partial false no
check missing false no
check broken false yes
[[ "$failures" -eq 0 ]] || exit 1
echo "all passed"

#!/usr/bin/env bash
# Fails if the crates.io publish stops skipping versions that are already published.
#
# v0.0.7 uploaded compose-rust-macros, then timed out waiting for the index before
# compose-rust (run 43377202006). A rerun of `cargo publish -p compose-rust-macros -p
# compose-rust` failed on the macros version that was already there, so the missing crate
# could never be published. .github/scripts/publish-crates.sh checks each crate first.
# This runs it in --dry-run against a stand-in crates.io API served from a temporary
# directory, so nothing is uploaded and no network is used.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/.github/scripts/publish-crates.sh"
cd "$repo_root"
version="$(cargo metadata --no-deps --format-version 1 |
    python3 -c 'import json,sys; print(next(p["version"] for p in json.load(sys.stdin)["packages"] if p["name"]=="compose-rust"))')"

root="$(mktemp -d)"
trap 'kill "${server:-0}" 2>/dev/null || true; rm -rf "$root"' EXIT
port=$(( 20000 + RANDOM % 20000 ))
python3 -m http.server "$port" --bind 127.0.0.1 --directory "$root" >/dev/null 2>&1 &
server=$!
disown "$server" 2>/dev/null || true
for _ in $(seq 1 50); do curl -s -o /dev/null "http://127.0.0.1:$port/" && break; sleep 0.1; done

publish() { mkdir -p "$root/api/v1/crates/$1"; echo '{}' > "$root/api/v1/crates/$1/$version"; }
run() {
    CRATES_IO_API="http://127.0.0.1:$port/api/v1" CRATES_INDEX="http://127.0.0.1:$port/index" \
        bash "$script" --dry-run
}
fail() { echo "FAIL: $1" >&2; echo "$out" >&2; exit 1; }

out="$(run)"
grep -q "would publish compose-rust-macros $version" <<<"$out" || fail "nothing published: macros should be published"
grep -q "would publish compose-rust $version" <<<"$out" || fail "nothing published: compose-rust should be published"

publish compose-rust-macros
out="$(run)"
grep -q "compose-rust-macros $version is already on crates.io, skipping" <<<"$out" || fail "a published macros version must be skipped"
grep -q "would publish compose-rust $version" <<<"$out" || fail "compose-rust must still be published after the macros were"

publish compose-rust
out="$(run)"
grep -q "compose-rust $version is already on crates.io, skipping" <<<"$out" || fail "a published compose-rust version must be skipped"
! grep -q "would publish" <<<"$out" || fail "nothing should be published when both versions exist"

echo "ok    the crates.io publish skips versions that are already published"

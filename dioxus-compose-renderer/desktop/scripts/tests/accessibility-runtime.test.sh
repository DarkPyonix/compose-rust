#!/usr/bin/env bash
# Does the image survive what an assistive technology actually asks it?
#
# accessibility-link.test.sh checks that the role classes are in the binary. That is a
# necessary condition and not a sufficient one: with every symbol present and a full,
# healthy tree coming out of ax-dump, turning on VoiceOver or the Accessibility Keyboard
# still aborted the process, because AppKit answers accessibilityHitTest: and
# accessibilityFocusedUIElement by resolving java.awt.Window through JNI and that class
# was not registered. Walking the tree never calls either method, so the dump could not
# see the gap.
#
# This test attaches the way an assistive client does, asks for the focused element, and
# requires the process to still be alive afterwards.
#
# It needs the calling terminal to hold the Accessibility permission (System Settings >
# Privacy & Security > Accessibility). Without it the test skips rather than passing,
# because a run that cannot query anything proves nothing.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../../../.." && pwd)"
host="$repo_root/dioxus-compose-renderer/build/native-image/smoke_host"
ax_dump="$repo_root/experiments/accessibility/ax-dump.swift"

[[ "$(uname -s)" == "Darwin" ]] || { echo "skip: macOS only"; exit 0; }
[[ -x "$host" ]] || { echo "error: $host not built; run build-native.sh first" >&2; exit 1; }

log="$(mktemp)"
set +m   # no job-control notice when the host is killed at the end
DIOXUS_COMPOSE_SMOKE_IME=1 "$host" >"$log" 2>&1 &
host_pid=$!
cleanup() {
    kill "$host_pid" 2>/dev/null || true
    wait "$host_pid" 2>/dev/null || true
    rm -f "$log"
}
trap cleanup EXIT

for _ in $(seq 1 30); do
    grep -q "dioxus_compose_host_init" "$log" 2>/dev/null && break
    sleep 1
done
kill -0 "$host_pid" 2>/dev/null || {
    echo "fail: the host died before it could be queried" >&2
    cat "$log" >&2
    exit 1
}

out="$(swift "$ax_dump" "$host_pid" 2>&1 || true)"

case "$out" in
    *"-25211"*|*"kAXErrorAPIDisabled"*)
        echo "skip: this terminal has no Accessibility permission, so nothing could be queried"
        exit 0
        ;;
esac

# The point of the test: the queries above are what kills an unregistered image.
if ! kill -0 "$host_pid" 2>/dev/null; then
    echo "fail: the host aborted while an assistive client queried it" >&2
    echo "$out" >&2
    grep -E "Bad JNI lookup|NoClassDefFoundError|JNI Lookup Exception" "$log" >&2 || true
    exit 1
fi

grep -q "Bad JNI lookup" "$log" && {
    echo "fail: a JNI lookup missed while answering an accessibility query" >&2
    grep "Bad JNI lookup" "$log" >&2
    exit 1
}

case "$out" in
    *"AXFocusedUIElement:"*) ;;
    *) echo "fail: the focused-element query was never answered" >&2; echo "$out" >&2; exit 1 ;;
esac

echo "$out" | grep -E "^total elements:" || true
echo "ok    the image answers assistive-client queries and stays alive"

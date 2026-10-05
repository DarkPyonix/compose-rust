#!/usr/bin/env bash
# The fork's desktop and macOS modules are published under versions of their own, and the
# renderer modules ask for exactly those. If the two drift, a build resolves upstream's jars
# from a cache and nothing says so until the app starts. This checks the names agree.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo/renderer/scripts/build-compose.sh"
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "fork versions"

upstream="$(sed -n 's/^PUBLISHED_AS="\([^"]*\)"$/\1/p' "$script")"
compose_as="$(sed -n 's/^EXTENDED_AS="\([^"]*\)"$/\1/p' "$script" | head -1)"
skiko_as="$(sed -n 's/^SKIKO_AWT_EXTENDED_AS="\([^"]*\)"$/\1/p' "$script")"
[ -n "$compose_as" ] && [ -n "$skiko_as" ] || { fail "build-compose.sh names no EXTENDED_AS or SKIKO_AWT_EXTENDED_AS"; exit 1; }
[ "$compose_as" != "$upstream" ] || fail "EXTENDED_AS equals the upstream version $upstream"

check() {
    grep -q "$2" "$repo/$1" || fail "$1 does not ask for $2"
}
for module in ui:ui ui:ui-text foundation:foundation; do
    check renderer/desktop/module.yaml "org.jetbrains.compose.${module%%:*}:${module##*:}:$compose_as"
done
check renderer/desktop/module.yaml "org.jetbrains.skiko:skiko-awt:$skiko_as"
check renderer/macos/module.yaml "org.jetbrains.compose.ui:ui:$compose_as"
check renderer/macos/module.yaml "org.jetbrains.compose.foundation:foundation:$compose_as"

if [ "$failures" -eq 0 ]; then echo "  ok"; else exit 1; fi

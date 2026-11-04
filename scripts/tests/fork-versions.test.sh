#!/usr/bin/env bash
# The renderer's macOS, Linux and desktop modules ask for the Compose fork's own coordinates,
# org.thisisthepy.compose.* at the versions build-compose.sh says the pinned commit publishes,
# and for none of JetBrains' Compose modules that carry classes. If they drift, a build either
# asks for a coordinate nobody published or resolves JetBrains' jars from a cache next to the
# fork's, and nothing says so until the app starts. This checks the names agree.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo/renderer/scripts/build-compose.sh"
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "fork versions"

compose_as="$(sed -n 's/^COMPOSE_AS="\([^"]*\)"$/\1/p' "$script")"
material3_as="$(sed -n 's/^MATERIAL3_AS="\([^"]*\)"$/\1/p' "$script")"
skiko_as="$(sed -n 's/^SKIKO_AWT_AS="\([^"]*\)"$/\1/p' "$script")"
[ -n "$compose_as" ] && [ -n "$material3_as" ] && [ -n "$skiko_as" ] ||
    { fail "build-compose.sh names no COMPOSE_AS, MATERIAL3_AS or SKIKO_AWT_AS"; exit 1; }
[[ "$compose_as" =~ -ext\.[0-9]+$ ]] || fail "COMPOSE_AS $compose_as is not a fork version (<upstream>-ext.<N>)"
[[ "$material3_as" =~ -ext\.[0-9]+$ ]] || fail "MATERIAL3_AS $material3_as is not a fork version (<upstream>-ext.<N>)"
grep -q 'EXTENDED_AS\|DXC_COMPOSE_EXTENDED_AS\|fork-dependencies.sh" repair' "$script" &&
    fail "build-compose.sh still overrides the fork's version or rewrites what it published"

check() {
    grep -q -- "$2" "$repo/$1" || fail "$1 does not ask for $2"
}
for module in ui:ui ui:ui-text foundation:foundation desktop:desktop; do
    check renderer/desktop/module.yaml "org.thisisthepy.compose.${module%%:*}:${module##*:}:$compose_as"
done
check renderer/desktop/module.yaml "org.thisisthepy.compose.material3:material3:$material3_as"
check renderer/desktop/module.yaml "org.thisisthepy.compose.skiko:skiko-awt:$skiko_as"
check renderer/macos/module.yaml "org.thisisthepy.compose.ui:ui:$compose_as"
check renderer/macos/module.yaml "org.thisisthepy.compose.foundation:foundation:$compose_as"
check renderer/macos/module.yaml "org.thisisthepy.compose.material3:material3:$material3_as"
check renderer/linux/module.yaml "org.thisisthepy.compose.ui:ui-linuxx64:$compose_as"
check renderer/linux/module.yaml "org.thisisthepy.compose.material3:material3-linuxarm64:$material3_as"

# JetBrains' Compose next to the fork's would be a second copy of the same classes. The runtime
# alias stays: JetBrains' runtime is a stub that points at androidx, as the fork's is.
for module in renderer/desktop/module.yaml renderer/macos/module.yaml renderer/linux/module.yaml; do
    hits="$(grep -n -E '^\s*-\s*(\$compose\.(foundation|ui|material3|material|desktop|animation)|org\.jetbrains\.compose\.)' "$repo/$module" || true)"
    [ -z "$hits" ] || fail "$module names JetBrains' Compose: $hits"
done

# `compose: enabled` adds JetBrains' runtime and components-resources, and the latter brings
# JetBrains' foundation, UI and animation. These modules apply the compiler plugin through
# renderer/compose.module-template.yaml instead.
for module in renderer/desktop/module.yaml renderer/macos/module.yaml renderer/linux/module.yaml; do
    grep -qE '^\s*compose:\s*enabled|^\s*-\s*\$compose\.' "$repo/$module" && fail "$module enables Compose or names \$compose.*, which brings JetBrains' Compose"
    grep -q 'compose.module-template.yaml' "$repo/$module" || fail "$module does not apply compose.module-template.yaml"
done

if [ "$failures" -eq 0 ]; then echo "  ok"; else exit 1; fi

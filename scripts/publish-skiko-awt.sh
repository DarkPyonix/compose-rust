#!/usr/bin/env bash
# Builds the fork's patched skiko-awt and publishes it to the local Maven repository as
# SKIKO_AWT_AS (renderer/scripts/build-compose.sh).
#
# Usage: ./scripts/publish-skiko-awt.sh <work-dir>
#
# The fork's extended/skiko/build-skiko-awt.sh, at the pinned commit, builds skiko-awt with the
# AWT switch and publishes it as org.thisisthepy.compose.skiko:skiko-awt on the fork's version, with its natives still
# named at JetBrains' 0.144.6. Under JetBrains' own 0.144.6, a resolver that already holds
# JetBrains' jar (Amper's cache does) would never look at the local repository, and the image
# would carry an unpatched skiko-awt and keep the Java toolkit. This checks that what the fork
# published is the version the renderer's desktop module names.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="$repo/renderer/scripts/build-compose.sh"
version="$(sed -n 's/^SKIKO_AWT_AS="\([^"]*\)"$/\1/p' "$script")"
[[ -n "$version" ]] || { echo "error: $script has no SKIKO_AWT_AS" >&2; exit 1; }
[[ $# -eq 1 ]] || { echo "usage: publish-skiko-awt.sh <work-dir>" >&2; exit 1; }

fork="$("$repo/scripts/fetch-fork-skiko.sh")"
published="$(sed -n 's/^PUBLISHED_AS="\([^"]*\)"$/\1/p' "$fork/extended/skiko/build-skiko-awt.sh")"
[[ "$published" == "$version" ]] || {
    echo "error: the fork's build-skiko-awt.sh publishes skiko-awt as '$published', and the renderer asks for $version" >&2
    echo "       Pin a fork commit that publishes $version, or set SKIKO_AWT_AS and renderer/desktop/module.yaml to '$published'." >&2
    exit 1
}
"$fork/extended/skiko/build-skiko-awt.sh" "$1"

jar="$HOME/.m2/repository/org/thisisthepy/compose/skiko/skiko-awt/$version/skiko-awt-$version.jar"
[[ -f "$jar" ]] || { echo "error: no $jar after the build" >&2; exit 1; }
echo "published $jar"

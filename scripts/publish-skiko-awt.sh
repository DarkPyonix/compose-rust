#!/usr/bin/env bash
# Builds the fork's patched skiko-awt and publishes it to the local Maven repository under
# SKIKO_AWT_EXTENDED_AS (renderer/scripts/build-compose.sh), not under upstream's version.
#
# Usage: ./scripts/publish-skiko-awt.sh <work-dir>
#
# The fork's extended/skiko/build-skiko-awt.sh publishes as 0.144.6, upstream's coordinate. A
# resolver that already holds upstream's 0.144.6 (Amper's cache does) never looks at the local
# repository for it, so the image would carry an unpatched skiko-awt and keep the Java
# toolkit. This renames that publication to a version no cache holds. Its dependencies stay
# as they are: the natives are upstream's skiko-awt-runtime jars at 0.144.6.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="$repo/renderer/scripts/build-compose.sh"
version="$(sed -n 's/^SKIKO_AWT_EXTENDED_AS="\([^"]*\)"$/\1/p' "$script")"
[[ -n "$version" ]] || { echo "error: $script has no SKIKO_AWT_EXTENDED_AS" >&2; exit 1; }
[[ $# -eq 1 ]] || { echo "usage: publish-skiko-awt.sh <work-dir>" >&2; exit 1; }

fork="$("$repo/scripts/fetch-fork-skiko.sh")"
"$fork/extended/skiko/build-skiko-awt.sh" "$1"

base="$HOME/.m2/repository/org/jetbrains/skiko/skiko-awt"
from="$(sed -n 's/^PUBLISHED_AS="\([^"]*\)"$/\1/p' "$fork/extended/skiko/build-skiko-awt.sh")"
[[ -d "$base/$from" ]] || { echo "error: no $base/$from after the build" >&2; exit 1; }
rm -rf "$base/$version"
mkdir -p "$base/$version"
for file in "$base/$from"/*; do
    name="$(basename "$file")"
    target="$base/$version/${name/skiko-awt-$from/skiko-awt-$version}"
    case "$name" in
        *.pom|*.module)
            # Its own name and version only. The first version in each file is its own; the
            # runtime dependencies that follow keep upstream's.
            FROM="$from" TO="$version" perl -0777 -pe '
                s/(skiko-awt)-\Q$ENV{FROM}\E/$1-$ENV{TO}/g;
                s/(<version>)\Q$ENV{FROM}\E(<\/version>)/$1$ENV{TO}$2/;
                s/("version":\s*")\Q$ENV{FROM}\E(")/$1$ENV{TO}$2/;
            ' "$file" > "$target" ;;
        *) cp "$file" "$target" ;;
    esac
done
rm -rf "$base/$from"
jar="$base/$version/skiko-awt-$version.jar"
[[ -f "$jar" ]] || { echo "error: no $jar after renaming" >&2; exit 1; }
echo "published $jar"

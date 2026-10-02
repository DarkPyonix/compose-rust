#!/usr/bin/env bash
# Usage: ./scripts/stage-android-kotlin.sh [destination]
#
# Copies the Android renderer's Kotlin into the crate, so that publishing the crate
# publishes it.
#
# Android is the one platform where the Kotlin cannot be frozen ahead of time: the desktop
# ships a native-image shared library and iOS a Kotlin/Native archive, but Android runs on
# ART and the Kotlin has to be compiled by the application's own Gradle build. It has to
# travel as source, and dx's Gradle template only accepts Maven coordinates for
# dependencies, so there is no way to hand it a compiled artifact even if we built one.
#
# Most of `android/src` is symlinks into the desktop renderer's directory, which is how
# there is one copy of the interpreter. A symlink is not something `cargo package` can
# carry, so this resolves them.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

source_dir="dioxus-compose-renderer/android/src"
destination="${1:-dioxus-compose/android-kotlin}"

[[ -d "$source_dir" ]] || { echo "no Android sources at $source_dir" >&2; exit 1; }

rm -rf "$destination"
mkdir -p "$destination"
# -L to follow the symlinks rather than copy them.
cp -RL "$source_dir/." "$destination/"

count="$(find "$destination" -name '*.kt' | wc -l | tr -d ' ')"
links="$(find "$destination" -type l | wc -l | tr -d ' ')"
[[ "$links" -eq 0 ]] || { echo "$links symlinks survived the copy" >&2; exit 1; }
echo "staged $count Kotlin files into $destination"

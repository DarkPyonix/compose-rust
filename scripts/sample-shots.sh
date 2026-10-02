#!/usr/bin/env bash
# Usage: ./scripts/sample-shots.sh [output-directory] [name-filter]
#
# Pictures of the samples: both colour schemes, the three window widths each of them
# changes shape at, and every design system the sample can actually be drawn in. An
# adaptive sample is all six; a unified one is the one it names, because its other five
# are screens it will never show.
#
# Two steps, because the two halves of the screen live in two languages. The samples
# record the bytes their Host would have sent, and the Renderer draws those bytes in a
# window of the size the recording's name carries. Nothing is restated in Kotlin, so what
# comes out is what an application would really have produced.
#
# The name filter is a plain substring matched against the recording's file name, which is
# `<Sample>-<System>-<Scheme>-<class>-<width>x<height>`. `Cupertino-Light` is a scheme
# under one system, `Todo-` is one sample everywhere, and no filter is all 144.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

# Absolute, because the Kotlin half runs from the renderer project rather than from here
# and a relative path would land somewhere neither of them meant.
directory="${1:-$repo_root/target/sample-shots}"
case "$directory" in
    /*) ;;
    *) directory="$PWD/$directory" ;;
esac
filter="${2:-}"

mkdir -p "$directory"
rm -f "$directory"/*.bin "$directory"/*.png

# Every recording test says so in its name, whichever requirement it is named after.
DXC_FRAME_DIR="$directory" cargo test --workspace is_recorded --quiet
cd "$repo_root/dioxus-compose-renderer"
DXC_FRAME_DIR="$directory" DXC_FRAME_FILTER="$filter" \
    ./kotlin test -p jvm --include-module desktop --include-classes '*SampleScreenshotTest'

echo "wrote $(ls "$directory"/*.png | wc -l | tr -d ' ') pictures to $directory"

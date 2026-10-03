#!/usr/bin/env bash
# Builds Skia as a static archive, so it can go inside the renderer instead of beside it.
#
# Usage: ./build-static-skiko.sh [skiko-source-dir] [output-dir]
#
# Skiko publishes only a shared library. Its build compiles its own C++ bindings to object
# files and then links those together with Skia's static archives, and both halves are
# still on disk when it finishes, so the archive is made from exactly the same inputs the
# shared library is made from. Nothing in skiko's build is modified.
#
# Skia itself is not compiled here. Skiko's Gradle build downloads a prebuilt Skia, which
# is why this takes about two minutes rather than the afternoon it would take to build
# Skia from source.
set -euo pipefail

version="${DXC_SKIKO_VERSION:-0.144.6}"
source_dir="${1:-$(dirname "${BASH_SOURCE[0]}")/skiko-src}"
output_dir="${2:-$(dirname "${BASH_SOURCE[0]}")/out}"

case "$(uname -s)" in
    Darwin) target_task=MacosArm64; compile_dir_glob="Release-macos-jvm-arm64"; skia_glob="*-macos-Release-arm64" ;;
    *) echo "this script is macOS only so far; Windows and Linux need their own archiver" >&2; exit 2 ;;
esac

if [[ ! -d "$source_dir" ]]; then
    echo "==> cloning skiko $version"
    git clone -q --depth 1 --branch "v$version" https://github.com/JetBrains/skiko.git "$source_dir"
fi

echo "==> compiling skiko's bindings (Skia itself is downloaded prebuilt)"
(cd "$source_dir" && ./gradlew ":skiko:compileJvmBindings$target_task" --no-daemon -q)

objects=()
while IFS= read -r line; do objects+=("$line"); done < <(
    find "$source_dir/skiko/build/out/compile/$compile_dir_glob" -name '*.o'
)
[[ "${#objects[@]}" -gt 0 ]] || { echo "no object files; the compile task produced nothing" >&2; exit 1; }

skia_dir="$(find "$source_dir/skiko/dependencies/skia" -type d -name "$skia_glob" -print -quit)"
[[ -n "$skia_dir" ]] || { echo "no unpacked Skia under $source_dir/skiko/dependencies/skia" >&2; exit 1; }
archives=("$skia_dir"/out/*/*.a)
[[ "${#archives[@]}" -gt 0 ]] || { echo "no Skia archives under $skia_dir" >&2; exit 1; }

mkdir -p "$output_dir"
archive="$output_dir/libskiko.a"
echo "==> archiving ${#objects[@]} objects and ${#archives[@]} Skia libraries"
# Warnings about members with no symbols are normal: Skia ships translation units that are
# empty on this platform.
libtool -static -o "$archive" "${objects[@]}" "${archives[@]}" 2>/dev/null

entry_points="$(nm -g "$archive" | grep -c ' T _Java_org_jetbrains_ski' || true)"
[[ "$entry_points" -gt 0 ]] || { echo "the archive has no JNI entry points in it" >&2; exit 1; }
echo "built $archive"
echo "  $(wc -c < "$archive" | tr -d ' ') bytes"
echo "  $entry_points JNI entry points"

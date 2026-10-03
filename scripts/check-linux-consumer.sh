#!/usr/bin/env bash
# Usage: scripts/check-linux-consumer.sh <renderer distribution> <scratch directory>
#
# Builds an application the way someone using this crate would, moves it away from the
# build, and starts it on Linux until the renderer has drawn. Needs a display: run it under
# `xvfb-run -a` on a machine without one.
#
#   <renderer distribution>  what build-native-linux.sh stages (dist/, holding lib/), or
#                            the unpacked release artifact, which has the same layout
#   <scratch directory>      where the application is copied to; emptied first, and it
#                            should be somewhere that has nothing to do with the build
#
# Why this exists. The renderer's own smoke test links a C host with an rpath and
# --export-dynamic on its own command line, so it passed while an application that merely
# depends on compose-rust could not start on Linux, twice over:
#
#   1. it recorded the renderer by bare file name and the loader could not find it, and
#   2. it did not export the dioxus_compose_host_* functions, so the renderer died with
#      "undefined symbol: dioxus_compose_host_release_batch" once the window came up.
#
# Neither is visible until something outside this repository's own build links the
# renderer and runs it. The application here is dioxus-compose/tests/fixtures/consumer,
# which has no build script and depends on the crate by path, and it is run twice: once as
# built, from another directory, and once packaged with scripts/bundle-renderer.sh with
# the build's own renderer moved out of reach.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <renderer distribution> <scratch directory>" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="$repo_root/dioxus-compose/tests/fixtures/consumer"
library_name=libdioxus_compose_renderer.so

fail() {
    echo "fail  $1" >&2
    shift
    for line in "$@"; do echo "      $line" >&2; done
    exit 1
}

[[ "$(uname -s)" == "Linux" ]] || fail "this checks Linux linking and runs on Linux only"
[[ -n "${DISPLAY:-}" ]] || fail "DISPLAY is not set" "Run it under xvfb-run -a."
for tool in cargo readelf nm patchelf; do
    command -v "$tool" >/dev/null || fail "$tool is not on PATH"
done

distribution="$(cd "$1" && pwd)"
renderer_dir="$distribution/lib"
[[ -f "$renderer_dir/$library_name" ]] || fail "no renderer at $renderer_dir/$library_name"
mkdir -p "$2"
scratch="$(cd "$2" && pwd)"
case "$scratch/" in
    "$repo_root"/*|"$distribution"/*)
        fail "the scratch directory is inside the build" \
            "Use one that is not, so the only renderer the application can find is the one" \
            "it was pointed at." ;;
esac

# The Host functions, as the crate defines them. Every one has to be in the
# application's dynamic symbol table, because that is where the renderer looks.
host_functions="$(grep -oE 'fn dioxus_compose_host_[a-z_]+' "$repo_root/dioxus-compose/src/boundary.rs" |
    sed 's/fn //' | sort -u)"
[[ -n "$host_functions" ]] || fail "found no dioxus_compose_host_* definitions in the Host"

# Its own target directory inside the checkout, as consumer-crate.test.sh does, so this
# never shares a build with anything else running there.
export CARGO_TARGET_DIR="$repo_root/target/linux-consumer-check"
export DIOXUS_COMPOSE_RENDERER_DIR="$distribution"

echo "== building the consumer against $distribution"
cargo build --manifest-path "$fixture/Cargo.toml"
binary="$CARGO_TARGET_DIR/debug/consumer"
[[ -x "$binary" ]] || fail "the build produced no $binary"

needed_renderer() {
    readelf -d "$1" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | grep "$library_name$" || true
}

exported_host_functions() {
    nm -D --defined-only "$1" | awk '{ print $NF }' | grep '^dioxus_compose_host_' | sort -u || true
}

check_exports() {
    local executable="$1" missing
    echo "-- nm -D --defined-only $(basename "$executable") (dioxus_compose_host_*)"
    nm -D --defined-only "$executable" | grep ' dioxus_compose_host_' || true
    missing="$(comm -23 <(echo "$host_functions") <(exported_host_functions "$executable"))"
    [[ -z "$missing" ]] || fail "$executable does not export: $(echo $missing)" \
        "The renderer looks these up in the executable, and dies on the first one missing."
}

echo "== what the build recorded"
soname="$(readelf -d "$renderer_dir/$library_name" | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p')"
echo "-- readelf -d $library_name (SONAME): $soname"
[[ "$soname" == "$renderer_dir/$library_name" ]] || fail \
    "the renderer is named '$soname', not after where it sits" \
    "Acquiring the renderer sets its SONAME to its own absolute path, which is what every" \
    "binary linking it then records."

echo "-- nm -D --undefined-only $library_name (dioxus_compose_host_*)"
nm -D --undefined-only "$renderer_dir/$library_name" | grep dioxus_compose_host_ || true
missing="$(comm -23 <(echo "$host_functions") <(nm -D --undefined-only "$renderer_dir/$library_name" |
    awk '{ print $NF }' | grep '^dioxus_compose_host_' | sort -u))"
[[ -z "$missing" ]] || fail "$library_name does not leave $(echo $missing) undefined" \
    "Those references are what make an executable linking it export them."

needed="$(needed_renderer "$binary")"
echo "-- readelf -d consumer (NEEDED): $needed"
[[ "$needed" == "$renderer_dir/$library_name" ]] || fail \
    "the consumer looks for the renderer as '${needed:-nothing}'" \
    "It should record the renderer's absolute path, $renderer_dir/$library_name."
if readelf -d "$binary" | grep -qE '\((RPATH|RUNPATH)\)'; then
    fail "the consumer carries an rpath" \
        "An application that only depends on this crate gets none, so this would test a" \
        "route no application has."
fi
check_exports "$binary"

# The self-check needs the window to close on its own.
export DIOXUS_COMPOSE_AUTOEXIT_MS="${DIOXUS_COMPOSE_AUTOEXIT_MS:-8000}"

echo "== starting it, as built, from somewhere else"
rm -rf "$scratch"
mkdir -p "$scratch/as-built" "$scratch/bundled/lib"
cp "$binary" "$scratch/as-built/consumer"
( cd "$scratch/as-built" && ./consumer --self-check )

echo "== bundling it the way an application that ships is packaged"
cp "$binary" "$scratch/bundled/consumer"
cp -R "$renderer_dir/." "$scratch/bundled/lib/"
"$repo_root/scripts/bundle-renderer.sh" "$scratch/bundled/consumer" lib
bundled_needed="$(needed_renderer "$scratch/bundled/consumer")"
echo "-- readelf -d bundled consumer (NEEDED): $bundled_needed"
[[ "$bundled_needed" == "$library_name" ]] || fail \
    "the bundled consumer looks for the renderer as '$bundled_needed'"
readelf -d "$scratch/bundled/consumer" | grep -E '\((RPATH|RUNPATH)\)'
echo "-- readelf -d bundled $library_name (SONAME):" \
    "$(readelf -d "$scratch/bundled/lib/$library_name" | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p')"
check_exports "$scratch/bundled/consumer"

echo "== starting the bundled copy with the build's renderer out of reach"
# Moved rather than trusted to be unused: if the bundled copy still reached for it, this
# is where that shows.
hidden="$renderer_dir.out-of-reach"
mv "$renderer_dir" "$hidden"
status=0
( cd "$scratch/bundled" && ./consumer --self-check ) || status=$?
mv "$hidden" "$renderer_dir"
[[ "$status" -eq 0 ]] || fail "the bundled consumer did not draw (exit $status)"

echo "ok    an application depending only on compose-rust links, exports the Host, and draws"

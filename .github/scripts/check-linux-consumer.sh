#!/usr/bin/env bash
# Usage: .github/scripts/check-linux-consumer.sh <renderer> <scratch directory>
#
# Builds an application the way someone using this crate would, moves it away from the
# build, and starts it on Linux until the renderer has drawn. Needs a display: run it under
# `xvfb-run -a` on a machine without one.
#
#   <renderer>           either renderer this crate links on Linux:
#                          - the native image: what build-native-linux.sh stages (dist/,
#                            holding lib/), or the unpacked release artifact, which has
#                            the same layout; the application finds it through
#                            COMPOSE_RUST_RENDERER_DIR
#                          - the Kotlin/Native static library: the directory
#                            build-linux.sh writes (libcompose_rust_renderer.a beside
#                            libcompose_rust_host_exports.so); the application finds it
#                            through DXC_LINUX_NATIVE_LIB
#                        Which one it is is read from what is in the directory.
#   <scratch directory>  where the application is copied to; emptied first, and it should
#                        be somewhere that has nothing to do with the build
#
# Why this exists. The renderers' own smoke tests link a host with an rpath and
# --export-dynamic on their own command lines, so they passed while an application that
# merely depends on compose-rust could not start on Linux, twice over:
#
#   1. it recorded the renderer by bare file name and the loader could not find it, and
#   2. it did not export the compose_rust_host_* functions, so the renderer could not
#      call back into it ("undefined symbol: compose_rust_host_release_batch" from the
#      native image, "compose_rust_host_init is not in this image" from the static one).
#
# Neither is visible until something outside this repository's own build links the
# renderer and runs it. The application here is compose-rust/tests/fixtures/consumer,
# which has no build script and depends on the crate by path. It is linked twice, with the
# toolchain's default linker and with GNU ld, because the two do not agree on when a
# library's needs make an executable export something, and both are checked and run from
# another directory. The default build is then packaged with scripts/bundle-renderer.sh
# and run again with the build's own renderer moved out of reach.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <renderer> <scratch directory>" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture="$repo_root/compose-rust/tests/fixtures/consumer"

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
if [[ -f "$distribution/libcompose_rust_renderer.a" ]]; then
    # The static renderer is inside the application. What it names by path is the small
    # library that makes it export the Host's functions.
    kind="Kotlin/Native static library"
    library_dir="$distribution"
    library_name=libcompose_rust_host_exports.so
    unset COMPOSE_RUST_RENDERER_DIR
    export DXC_LINUX_NATIVE_LIB="$distribution"
    # It has no way to close its own window, so the self-check ends the process itself.
    unset COMPOSE_RUST_AUTOEXIT_MS
else
    kind="native image"
    library_dir="$distribution/lib"
    library_name=libcompose_rust_renderer.so
    unset DXC_LINUX_NATIVE_LIB
    export COMPOSE_RUST_RENDERER_DIR="$distribution"
    # The self-check waits for the window to close itself, so shutdown is checked too.
    export COMPOSE_RUST_AUTOEXIT_MS="${COMPOSE_RUST_AUTOEXIT_MS:-8000}"
fi
[[ -f "$library_dir/$library_name" ]] || fail "no $library_name in $library_dir" \
    "This is neither a native image distribution nor a static renderer directory."
echo "== renderer: $kind, $distribution"

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
host_functions="$(grep -oE 'fn compose_rust_host_[a-z_]+' "$repo_root/compose-rust/src/boundary.rs" |
    sed 's/fn //' | sort -u)"
[[ -n "$host_functions" ]] || fail "found no compose_rust_host_* definitions in the Host"

needed_library() {
    readelf -d "$1" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | grep "$library_name$" || true
}

exported_host_functions() {
    nm -D --defined-only "$1" | awk '{ print $NF }' | grep '^compose_rust_host_' | sort -u || true
}

check_exports() {
    local executable="$1" missing
    echo "-- nm -D --defined-only $(basename "$executable") (compose_rust_host_*)"
    nm -D --defined-only "$executable" | grep ' compose_rust_host_' || true
    missing="$(comm -23 <(echo "$host_functions") <(exported_host_functions "$executable"))"
    [[ -z "$missing" ]] || fail "$executable does not export: $(echo $missing)" \
        "The renderer looks these up in the executable, and fails on the first one missing."
}

# What every build has to have recorded: the library by its absolute path, no rpath, and
# the Host's functions exported.
check_as_built() {
    local binary="$1" needed
    needed="$(needed_library "$binary")"
    echo "-- readelf -d $(basename "$binary") (NEEDED): $needed"
    [[ "$needed" == "$library_dir/$library_name" ]] || fail \
        "the consumer looks for $library_name as '${needed:-nothing}'" \
        "It should record its absolute path, $library_dir/$library_name."
    if readelf -d "$binary" | grep -qE '\((RPATH|RUNPATH)\)'; then
        fail "the consumer carries an rpath" \
            "An application that only depends on this crate gets none, so this would test a" \
            "route no application has."
    fi
    check_exports "$binary"
}

run_self_check() {
    local directory="$1"
    ( cd "$directory" && ./consumer --self-check )
}

# Its own target directories inside the checkout, as consumer-crate.test.sh does, so this
# never shares a build with anything else running there.
build_consumer() {
    local name="$1" flags="$2"
    CARGO_TARGET_DIR="$repo_root/target/linux-consumer-check/$name" RUSTFLAGS="$flags" \
        cargo build --manifest-path "$fixture/Cargo.toml" >&2
    echo "$repo_root/target/linux-consumer-check/$name/debug/consumer"
}

echo "== building the consumer with the default linker"
binary="$(build_consumer default "")"
[[ -x "$binary" ]] || fail "the build produced no $binary"
readelf -p .comment "$binary" 2>/dev/null | grep -iE 'lld|linker' | head -2 || true

echo "== what the build recorded"
soname="$(readelf -d "$library_dir/$library_name" | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p')"
echo "-- readelf -d $library_name (SONAME): $soname"
[[ "$soname" == "$library_dir/$library_name" ]] || fail \
    "$library_name is named '$soname', not after where it sits" \
    "The Host's build script sets its SONAME to its own absolute path, which is what every" \
    "binary linking it then records."

echo "-- nm -D --undefined-only $library_name (compose_rust_host_*)"
nm -D --undefined-only "$library_dir/$library_name" | grep compose_rust_host_ || true
missing="$(comm -23 <(echo "$host_functions") <(nm -D --undefined-only "$library_dir/$library_name" |
    awk '{ print $NF }' | grep '^compose_rust_host_' | sort -u))"
[[ -z "$missing" ]] || fail "$library_name does not leave $(echo $missing) undefined" \
    "Those references are what make an executable linking it export them."

check_as_built "$binary"

rm -rf "$scratch"
mkdir -p "$scratch/as-built" "$scratch/gnu-ld" "$scratch/bundled/lib"

echo "== starting it, as built, from somewhere else"
cp "$binary" "$scratch/as-built/consumer"
run_self_check "$scratch/as-built"

echo "== building and starting it again, linked by GNU ld"
gnu_binary="$(build_consumer gnu-ld "-Clink-arg=-fuse-ld=bfd")"
[[ -x "$gnu_binary" ]] || fail "the build produced no $gnu_binary"
check_as_built "$gnu_binary"
cp "$gnu_binary" "$scratch/gnu-ld/consumer"
run_self_check "$scratch/gnu-ld"

echo "== bundling it the way an application that ships is packaged"
cp "$binary" "$scratch/bundled/consumer"
if [[ "$library_name" == libcompose_rust_renderer.so ]]; then
    cp -R "$library_dir/." "$scratch/bundled/lib/"
else
    # The renderer is inside the executable; the library beside it is all there is to carry.
    cp "$library_dir/$library_name" "$scratch/bundled/lib/"
fi
"$repo_root/scripts/bundle-renderer.sh" "$scratch/bundled/consumer" lib
bundled_needed="$(needed_library "$scratch/bundled/consumer")"
echo "-- readelf -d bundled consumer (NEEDED): $bundled_needed"
[[ "$bundled_needed" == "$library_name" ]] || fail \
    "the bundled consumer looks for $library_name as '$bundled_needed'"
readelf -d "$scratch/bundled/consumer" | grep -E '\((RPATH|RUNPATH)\)'
echo "-- readelf -d bundled $library_name (SONAME):" \
    "$(readelf -d "$scratch/bundled/lib/$library_name" | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p')"
check_exports "$scratch/bundled/consumer"

echo "== starting the bundled copy with the build's renderer out of reach"
# Moved rather than trusted to be unused: if the bundled copy still reached for it, this
# is where that shows.
hidden="$library_dir.out-of-reach"
mv "$library_dir" "$hidden"
status=0
run_self_check "$scratch/bundled" || status=$?
mv "$hidden" "$library_dir"
[[ "$status" -eq 0 ]] || fail "the bundled consumer did not draw (exit $status)"

echo "ok    an application depending only on compose-rust links, exports the Host, and draws"
echo "      renderer: $kind"

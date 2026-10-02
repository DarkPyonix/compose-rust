#!/usr/bin/env bash
# A crate whose Cargo.toml says only "dioxus-compose" builds, links, and starts.
#
# This is the case the rest of the test suite could not reach. Every sample in this
# repository used to carry a build script that repeated the library's rpath, so the
# samples passed while an application that merely depends on the crate linked cleanly and
# then died at start up with
#
#     Library not loaded: @rpath/libdioxus_compose_renderer.dylib, no LC_RPATH's found
#
# because Cargo does not pass a dependency's link arguments on to the binary that uses it.
# dioxus-compose/tests/fixtures/consumer/ is that application, and it has no build script.
#
# Why this is a shell script and not a cargo test. It has to run cargo, and a cargo test
# that runs cargo either shares the outer target directory, where it blocks on the build
# lock the test run itself is holding, or uses its own and rebuilds the whole dependency
# tree inside a test. What it then asserts on is the load commands of a linked executable,
# read with the host's own tools (otool, readelf), which is a shell job either way. CI runs
# every scripts/tests/*.test.sh, so this runs with the rest of them.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture="$repo_root/dioxus-compose/tests/fixtures/consumer"

fail() {
    echo "fail  $1" >&2
    shift
    for line in "$@"; do echo "      $line" >&2; done
    exit 1
}

case "$(uname -s)" in
    Darwin|Linux) ;;
    *)
        # Windows finds a DLL on the loader's search path, which no name inside the file
        # can affect, so the thing this checks does not exist there. The build script
        # prints what an application has to do instead.
        echo "skip  $(uname -s) resolves libraries through the loader's search path"
        exit 0
        ;;
esac

command -v cargo >/dev/null || fail "cargo is not on PATH" \
    "This builds a crate that depends on dioxus-compose, so it needs the toolchain."

[[ -f "$fixture/Cargo.toml" ]] || fail "no consumer fixture at $fixture" \
    "The regression this defends is a crate with no build.rs of its own, so the fixture" \
    "has to be a crate rather than a test in this one."

[[ ! -e "$fixture/build.rs" ]] || fail "the consumer fixture has grown a build.rs" \
    "It stands in for an application that has none. With one it proves nothing."

# Its own target directory: the fixture is a separate workspace, and pointing it at the
# repository's target directory would make this wait on any build already running there.
target="${TMPDIR:-/tmp}/dioxus-compose-consumer-crate"
export CARGO_TARGET_DIR="$target"

echo "== building a crate that depends on dioxus-compose and nothing else"
cargo build --manifest-path "$fixture/Cargo.toml" --quiet

binary="$target/debug/consumer"
[[ -x "$binary" ]] || fail "the build produced no $binary"

library_name() {
    [[ "$(uname -s)" == "Darwin" ]] && echo libdioxus_compose_renderer.dylib \
                                    || echo libdioxus_compose_renderer.so
}

# The recorded dependency has to be an absolute path to a file that is really there. That
# is the whole mechanism: with no rpath to resolve against, a relative or bare name has
# nowhere to be looked up.
recorded_renderer() {
    if [[ "$(uname -s)" == "Darwin" ]]; then
        otool -L "$binary" | awk '{ print $1 }'
    else
        readelf -d "$binary" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'
    fi | grep "$(library_name)$" || true
}

rpath_count() {
    if [[ "$(uname -s)" == "Darwin" ]]; then
        otool -l "$binary" | grep -c LC_RPATH || true
    else
        readelf -d "$binary" | grep -cE '\((RPATH|RUNPATH)\)' || true
    fi
}

renderer="$(recorded_renderer)"
[[ -n "$renderer" ]] || fail "the binary records no dependency on $(library_name)" \
    "Without one the renderer is not linked in and nothing would draw."

[[ "$renderer" == /* ]] || fail "the binary looks for the renderer as '$renderer'" \
    "That is not an absolute path, so the loader has to search for it, and an application" \
    "that only depends on this crate has no search path to find it on."

[[ -f "$renderer" ]] || fail "the binary looks for a renderer that is not there" \
    "$renderer"

count="$(rpath_count)"
[[ "$count" == "0" ]] || fail "the binary carries $count rpath entries" \
    "It must load the renderer without one, because that is all a consumer's binary gets." \
    "An rpath here means something put it there and the absolute name is not being tested."

echo "== starting it"
# Not --launch: this opens no window. By the time main runs, the loader has already found
# the renderer, mapped it, and bound the dioxus_compose_host_* symbols it calls back into.
# Those are the three things that used to fail and all three happen before main.
"$binary" >/dev/null

echo "ok    a crate depending only on dioxus-compose builds, has no rpath, and starts"
echo "      renderer: $renderer"

# ------------------------------------------------------------------------------------
# And the other half: what the absolute path above must not do is ship.
# ------------------------------------------------------------------------------------
#
# The path the executable records is a fact about the machine that built it. An
# application that ships has to carry its own renderer and point at it relatively, or it
# looks for a directory the person running it does not have and carries the build
# machine's home directory into the release. scripts/bundle-renderer.sh is the step that
# does that, and this is the check that it really did.

if [[ "$(uname -s)" == "Linux" ]] && ! command -v patchelf >/dev/null; then
    echo "skip  bundling: patchelf is not installed, and rewriting an ELF DT_NEEDED needs it"
    exit 0
fi

stage="$target/bundled"
rm -rf "$stage"
mkdir -p "$stage/lib"
cp "$binary" "$stage/app"
cp -R "$(dirname "$renderer")/." "$stage/lib/"

echo "== bundling it, the way an application that ships would"
"$repo_root/scripts/bundle-renderer.sh" "$stage/app" lib

bundled_renderer() {
    if [[ "$(uname -s)" == "Darwin" ]]; then
        otool -L "$stage/app" | awk '{ print $1 }'
    else
        readelf -d "$stage/app" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'
    fi | grep "$(library_name)$"
}

bundled="$(bundled_renderer)"
[[ "$bundled" != /* ]] || fail "the bundled application still looks in $bundled" \
    "That is a path on the machine that built it, so it would not be there for anyone else."

# From a directory that has nothing to do with the build, so the only renderer it can
# possibly be finding is the one inside it.
( cd "$stage" && ./app >/dev/null )

echo "ok    a bundled application carries its own renderer and no build machine path"
echo "      renderer: $bundled"

#!/usr/bin/env bash
# Usage: scripts/stand-in-renderer.sh <output directory>
#        scripts/stand-in-renderer.sh --if-refused <output directory>
#
# Build a stand-in for the renderer: a shared library with the renderer's file name and
# its two entry points, generated from this checkout's schema, that draws nothing.
#
# What it is for. A build of this crate links a renderer, and the build script refuses
# one generated from a different schema than the crate, which is right: the program would
# start and draw nothing. On a checkout whose schema has moved since the last release that
# leaves nothing to link at all, because the only renderer to be had without tens of
# minutes of native-image building is the published one, and it was built from the old
# schema. A version bump does the same from the other side: until that version is
# released there is no published renderer for it at all. A check about packaging or about
# how a binary finds its renderer then fails for a reason that has nothing to do with what
# it checks, and keeps failing until the next release.
#
# This answers those checks with a library that is what they are about: a file of the
# right name, in the layout a distribution has, that a binary links against and the
# loader has to find, carrying this checkout's schema hash so the build script accepts it
# on the same terms it accepts a real one. It is never a renderer anyone can use. Asked to
# run, it says it is a stand-in and fails.
#
# The layout is the one DIOXUS_COMPOSE_RENDERER_DIR accepts:
#
#     <output>/lib/libdioxus_compose_renderer.{dylib,so}
#     <output>/schema-hash.txt
#
# With --if-refused it first asks the build script, the way any build of this crate
# would, for the renderer it finds. Only if that renderer is refused for its schema, or
# the release has no renderer for this crate version at all, is the stand-in built, and then the one line `DIOXUS_COMPOSE_RENDERER_DIR=<output>` is written
# to standard output, ready to append to $GITHUB_ENV. When the renderer is accepted
# nothing is built and nothing is written there, so the real one stays in use. Any other
# failure of that build is reported and fails this script. Everything else this script
# says goes to standard error.

set -euo pipefail

usage() {
    echo "usage: $0 [--if-refused] <output directory>" >&2
    exit 2
}

if_refused=0
if [[ "${1:-}" == "--if-refused" ]]; then
    if_refused=1
    shift
fi
[[ $# -eq 1 ]] || usage

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$1"
[[ "$out" == /* ]] || out="$PWD/$out"

if [[ $if_refused -eq 1 ]]; then
    mkdir -p "$repo_root/target"
    log="$(mktemp "$repo_root/target/stand-in-probe.XXXXXX")"
    # A check is enough: the build script runs, finds a renderer and checks its schema,
    # and nothing is linked.
    if (cd "$repo_root" && cargo check -p compose-rust --lib --quiet) 2>"$log"; then
        rm -f "$log"
        echo "the renderer this build finds was generated from this checkout's schema" >&2
        exit 0
    fi
    # The two refusals that mean no published renderer fits this tree. The first is the
    # schema check. The second is a release that answered, with no artifact for this
    # version, which is what every version bump looks like until it is released. A network
    # failure, a bad checksum or anything else is not one of them and still fails.
    if grep -q "generated from a different schema" "$log"; then
        reason="the renderer this build finds was generated from a different schema than this checkout"
    elif grep -q "The server was reached and answered that there is no such file" "$log"; then
        reason="no renderer is published for this crate version yet"
    else
        cat "$log" >&2
        rm -f "$log"
        echo "error: building compose-rust failed for a reason other than having no published renderer that fits" >&2
        exit 1
    fi
    rm -f "$log"
    echo "$reason, so a stand-in with this checkout's schema is built in its place" >&2
fi

case "$(uname -s)" in
    Darwin) library=libdioxus_compose_renderer.dylib ;;
    Linux) library=libdioxus_compose_renderer.so ;;
    *)
        echo "error: no stand-in renderer for $(uname -s)" >&2
        exit 1
        ;;
esac

compiler="${CC:-cc}"
command -v "$compiler" >/dev/null || {
    echo "error: no C compiler ($compiler) on PATH to build the stand-in renderer with" >&2
    exit 1
}

rm -rf "$out"
mkdir -p "$out/lib"

# The two functions the Host declares and calls. Nothing else is asked of a renderer at
# link time.
cat > "$out/stand-in.c" <<'C'
#include <stdint.h>
#include <stdio.h>

int32_t dioxus_compose_renderer_run(void) {
    fputs("dioxus-compose: this is a stand-in renderer, built only so that a binary has a "
          "renderer to link against. It cannot draw. Build the real one for this platform "
          "and point DIOXUS_COMPOSE_RENDERER_DIR at it.\n",
          stderr);
    return 1;
}

void dioxus_compose_renderer_request_frame(void) {}
C

if [[ "$(uname -s)" == "Darwin" ]]; then
    # Room in the header for the absolute install name the build script writes into it.
    "$compiler" -dynamiclib -Wl,-headerpad_max_install_names \
        -o "$out/lib/$library" "$out/stand-in.c"
else
    # With the same table of Host references the real library carries, which is what
    # makes an application export the functions the renderer calls back into. No SONAME,
    # like the real one: the build script gives it one naming where it sits.
    "$compiler" -shared -fPIC -o "$out/lib/$library" "$out/stand-in.c" \
        "$repo_root/dioxus-compose-renderer/desktop/c/linux_host_references.c"
fi

cp "$repo_root/dioxus-compose/schema-hash.txt" "$out/schema-hash.txt"

echo "stand-in renderer: $out/lib/$library (schema $(cat "$out/schema-hash.txt"))" >&2
if [[ $if_refused -eq 1 ]]; then
    echo "DIOXUS_COMPOSE_RENDERER_DIR=$out"
fi

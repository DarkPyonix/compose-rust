#!/usr/bin/env bash
# Usage: scripts/stand-in-renderer.sh <output directory>
#
# Build a stand-in for the renderer: a shared library with the renderer's file name and
# its two entry points, generated from this checkout's schema, that draws nothing.
#
# What it is for. A build of this crate links a renderer, and the build script refuses
# one generated from a different schema than the crate, which is right: the program would
# start and draw nothing. On a checkout whose schema has moved since the last release that
# leaves nothing to link at all, because the only renderer to be had without tens of
# minutes of native-image building is the published one, and it was built from the old
# schema. A check about packaging or about how a binary finds its renderer then fails for
# a reason that has nothing to do with what it checks, and keeps failing until the next
# release.
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

set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "usage: $0 <output directory>" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$1"

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
    # No SONAME, like the real one, so a binary records the path it was linked at.
    "$compiler" -shared -fPIC -o "$out/lib/$library" "$out/stand-in.c"
fi

cp "$repo_root/dioxus-compose/schema-hash.txt" "$out/schema-hash.txt"

echo "stand-in renderer: $out/lib/$library (schema $(cat "$out/schema-hash.txt"))"

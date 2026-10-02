#!/usr/bin/env bash
# Usage: scripts/bundle-renderer.sh <staged executable> <renderer directory, relative to it>
#
# Make a staged application load the renderer sitting inside it rather than the one on the
# machine that built it.
#
# A build records the absolute path the renderer was at, because that is the only way a
# binary which merely depends on this crate can find it without an rpath. That path is a
# fact about the build machine. Shipping it would send the program looking in a directory
# the person running it does not have, and it would put whoever built it and the layout of
# their home directory into the binary.
#
# So the last step of packaging an application is this one: copy the renderer in, then
# point the executable at the copy by a path relative to itself. Do the copy first; this
# script only rewrites names, and it checks that what it points at is really there.
#
#     scripts/bundle-renderer.sh staging/todo/sample-todo .
#     scripts/bundle-renderer.sh MyApp.app/Contents/MacOS/MyApp ../Frameworks/lib
#
# Where to copy the renderer to is not free choice. It finds its own Skia and AWT
# companions from where it was loaded, and AWT reads them out of the parent of that
# directory plus "lib". Two layouts are checked and work on macOS: the renderer's files
# beside the executable, and the renderer's own `lib` directory carried across whole, at
# whatever depth. Flattening that directory somewhere that is neither does not: an
# application with the files in `Contents/Frameworks` starts, loads the renderer, and then
# fails looking for `Contents/lib/libjawt.dylib`. `Contents/Frameworks/lib` works.
#
# Windows needs none of this. A DLL is found on the loader's search path, so the renderer's
# files sitting beside the executable is already the whole arrangement, and no name inside
# either file is consulted.

set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <staged executable> <renderer directory, relative to it>" >&2
    exit 2
fi

executable="$1"
relative="$2"

fail() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

[[ -f "$executable" ]] || fail "no executable at $executable"

staged_dir="$(cd "$(dirname "$executable")" && pwd)"
case "$(uname -s)" in
    Darwin) library_name=libdioxus_compose_renderer.dylib ;;
    Linux) library_name=libdioxus_compose_renderer.so ;;
    *)
        echo "nothing to do on $(uname -s): a DLL is found on the loader's search path,"
        echo "so the renderer's files beside the executable is the whole arrangement."
        exit 0
        ;;
esac

# "." beside the executable is the common case, and "@executable_path/./libfoo.dylib" is
# the same path spelled worse. Everything below joins with this.
prefix="$relative/"
[[ "$relative" == "." ]] && prefix=""

staged_library="$staged_dir/$relative/$library_name"
[[ -f "$staged_library" ]] || fail \
    "no renderer at $staged_library" \
    "Copy the renderer's directory into the application first. This script only rewrites" \
    "names, and a name pointing at nothing would ship as a program that does not start."

# What the executable asks for today, which is the build machine's absolute path.
if [[ "$(uname -s)" == "Darwin" ]]; then
    recorded="$(otool -L "$executable" | awk '{ print $1 }' | grep "/$library_name$" || true)"
else
    recorded="$(readelf -d "$executable" |
        sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | grep "/$library_name$" || true)"
fi

if [[ -z "$recorded" ]]; then
    echo "nothing to rewrite: $executable does not name the renderer by path."
    exit 0
fi

echo "== $executable"
echo "   was: $recorded"

if [[ "$(uname -s)" == "Darwin" ]]; then
    wanted="@executable_path/$prefix$library_name"
    install_name_tool -change "$recorded" "$wanted" "$executable"
    # The copy carries the build machine's path as its own name too. Nothing consults it
    # once the load command above is relative, but it would still ship a directory listing
    # of somebody's computer inside the application.
    install_name_tool -id "$wanted" "$staged_library"
    after="$(otool -L "$executable" | awk '{ print $1 }' | grep "/$library_name$")"
else
    command -v patchelf >/dev/null || fail \
        "patchelf is not on PATH" \
        "Rewriting an ELF executable's DT_NEEDED entry needs it. Packaging an application" \
        "is something its author sets up once, so a tool here is a fair ask; a consumer's" \
        "plain cargo build never needs one." \
        "Debian and Ubuntu: apt-get install patchelf. Fedora: dnf install patchelf."
    wanted="$library_name"
    patchelf --replace-needed "$recorded" "$wanted" "$executable"
    runpath="\$ORIGIN"
    [[ -n "$prefix" ]] && runpath="\$ORIGIN/$relative"
    patchelf --set-rpath "$runpath" "$executable"
    after="$(readelf -d "$executable" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' |
        grep "$library_name$")"
fi

echo "   now: $after"

# The point of the whole exercise: nothing absolute is left to ship.
[[ "$after" != /* ]] || fail \
    "$executable still looks for the renderer at $after" \
    "That is a path on the machine that built it."

echo "ok    the application carries its own renderer"

#!/usr/bin/env bash
# The Linux renderer library an application links names every Host function the renderer
# calls, so that the application exports them.
#
# The renderer image finds compose_rust_host_* in the executable by name. A Rust
# executable exports nothing it was not asked to, and the Host crate has no way to ask on
# an application's behalf: a build script's link arguments reach that package's own
# binaries and stop. So the asking is done by libcompose_rust_renderer.so, which every
# application links: it leaves those functions undefined, and a linker exports from an
# executable whatever a shared library on its link line needs. A name missing from that
# list is a function the application does not export, and the renderer dies calling it
# with "undefined symbol" after the window has started to come up.
#
# Nothing here needs Linux. Three lists have to agree and all three are in the tree: what
# the C file references, what the Host defines, and what the renderer declares it calls.
# The Linux build checks the linked result against the image itself as well.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

references=renderer/desktop/c/linux_host_references.c
host=compose-rust/src/boundary.rs
renderer=renderer/desktop/src
build=renderer/desktop/scripts/build-native-linux.sh

for file in "$references" "$host" "$build"; do
    [[ -f "$file" ]] || { echo "fail  $file is missing" >&2; exit 1; }
done

# The table's entries: an indented name followed by a comma, which is how the
# initializer is written. The extern declarations above it are not counted, so a name
# declared and left out of the table is caught.
referenced="$(grep -oE '^ +compose_rust_host_[a-z_]+,' "$references" |
    tr -d ' ,' | sort -u)"
declared="$(grep -oE '^extern void compose_rust_host_[a-z_]+\(' "$references" |
    sed 's/^extern void //; s/($//' | sort -u)"
defined="$(grep -oE 'fn compose_rust_host_[a-z_]+' "$host" | sed 's/fn //' | sort -u)"
called="$(grep -rhoE '@CFunction\("compose_rust_host_[a-z_]+"\)' "$renderer" |
    sed 's/@CFunction("//; s/")//' | sort -u)"

failures=0
compare() {
    local what="$1" left="$2" right="$3" left_name="$4" right_name="$5"
    local only_left only_right
    only_left="$(comm -23 <(echo "$left") <(echo "$right"))"
    only_right="$(comm -13 <(echo "$left") <(echo "$right"))"
    if [[ -n "$only_left" || -n "$only_right" ]]; then
        echo "fail  $what" >&2
        [[ -z "$only_left" ]] || echo "      only in $left_name: $(echo $only_left)" >&2
        [[ -z "$only_right" ]] || echo "      only in $right_name: $(echo $only_right)" >&2
        failures=$((failures + 1))
    fi
}

[[ -n "$referenced" ]] || {
    echo "fail  $references references no Host function" >&2
    echo "      An application linking the renderer would then export none, and the" >&2
    echo "      renderer could not call back into it." >&2
    exit 1
}

compare "the table holds a different set than the file declares" \
    "$referenced" "$declared" "the table" "the declarations"
compare "the renderer calls a different set than the library references" \
    "$called" "$referenced" "the renderer's @CFunction declarations" "$references"
compare "the Host defines a different set than the library references" \
    "$defined" "$referenced" "the Host" "$references"

# Referenced in a file is nothing until the file is in the library. Both halves: compiled,
# and handed to the link that produces the library applications use.
grep -q 'c/linux_host_references\.c' "$build" || {
    echo "fail  $build does not compile linux_host_references.c" >&2
    failures=$((failures + 1))
}
sed 's/[[:space:]]*#.*$//' "$build" |
    awk '/cc -shared/ { inside = 1 } inside { print } inside && !/\\$/ { inside = 0 }' |
    grep -q 'linux_host_references\.o' || {
        echo "fail  $build does not link linux_host_references.o into the renderer library" >&2
        failures=$((failures + 1))
    }

# The Kotlin/Native renderer is a static archive inside the application, so it refers to
# the Host's functions at link time instead, through the generated cinterop definition, and
# no library travels beside the executable to make it export them. That definition has to
# name the same set, and the library that used to carry the references must stay gone: an
# application that names it is not one executable.
static_def=renderer/linux/cinterop/host.def
if [[ -f "$static_def" ]]; then
    static_referenced="$(grep -oE '^extern void compose_rust_host_[a-z_]+\(' "$static_def" |
        sed 's/^extern void //; s/($//' | sort -u)"
    compare "the static renderer refers to a different set than the Host defines" \
        "$static_referenced" "$defined" "$static_def" "the Host"
else
    echo "fail  $static_def is missing" >&2
    failures=$((failures + 1))
fi
if grep -q 'compose_rust_host_exports' compose-rust/build.rs compose-rust/src/boundary.rs; then
    echo "fail  the Host still links libcompose_rust_host_exports.so" >&2
    echo "      An application that needs a library beside it is not one executable." >&2
    failures=$((failures + 1))
fi

if [[ "$failures" -gt 0 ]]; then
    echo "a Host function missing here is one an application does not export on Linux" >&2
    exit 1
fi
echo "ok    $(echo "$referenced" | wc -l | tr -d ' ') host functions are referenced, called and defined alike"

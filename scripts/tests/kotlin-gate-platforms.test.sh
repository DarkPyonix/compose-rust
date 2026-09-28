#!/usr/bin/env bash
# Usage: ./scripts/tests/kotlin-gate-platforms.test.sh
#
# The Kotlin gate builds and tests the whole project where X11 is installed, and names the
# platforms one by one where it is not. The risk in naming them is that a platform added
# later is left out silently and stops being built at all, so what is checked here is that
# the list the gate uses is the project's own list minus exactly the one that needs X11.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
lister="$repo_root/dioxus-compose-renderer/scripts/gate-platforms.sh"
renderer_dir="$repo_root/dioxus-compose-renderer"

failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "kotlin gate platforms"

[[ -x "$lister" ]] || { fail "$lister is missing or not executable"; exit 1; }

listed="$("$lister" build 2>/dev/null | sort)"
testable="$("$lister" test 2>/dev/null | sort)"
[[ -n "$listed" ]] || fail "the lister printed nothing"

# Every platform any module declares, plus the one every module in the project happens to
# be for. Read straight out of the module files, which is where the lister reads it too:
# what is being checked is the difference between the two lists, not how either is parsed.
declared="$(sed -n 's/^[[:space:]]*platforms:[[:space:]]*\[\(.*\)\][[:space:]]*$/\1/p' \
        "$renderer_dir"/*/module.yaml |
    tr ',' '\n' | sed 's/[[:space:]]//g' | grep -v '^$' | sort -u)"
[[ -n "$declared" ]] || { fail "no module declares any platform"; exit 1; }

# linuxX64 is the one that has to be absent, and it has to be declared somewhere or this
# test is checking nothing.
echo "$declared" | grep -qx linuxX64 || fail "no module declares linuxX64 any more"
if [[ ! -e /usr/include/X11/Xlib.h && ! -e /opt/X11/include/X11/Xlib.h ]]; then
    echo "$listed" | grep -qx linuxX64 &&
        fail "this machine has no X11 header and the gate still names linuxX64"
else
    echo "$listed" | grep -qx linuxX64 ||
        fail "this machine has the X11 header and the gate leaves linuxX64 out"
fi

# A device target has no test task, so naming it is an error rather than a skip. It is
# still built.
echo "$listed" | grep -qx iosArm64 || fail "iosArm64 is not built"
echo "$testable" | grep -qx iosArm64 && fail "the gate runs tests on an iOS device target"

# Android's tests are instrumented. Building needs no device, so it is built either way.
echo "$listed" | grep -qx android || fail "android is not built"
if ! adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit !found }'; then
    echo "$testable" | grep -qx android &&
        fail "no Android device is attached and the gate still runs its tests"
fi

# Everything else a module declares has to be built.
while read -r platform; do
    [[ "$platform" == "linuxX64" ]] && continue
    echo "$listed" | grep -qx "$platform" ||
        fail "$platform is declared by a module and the gate does not name it"
done <<< "$declared"

# The two a product implies rather than declares. Both are built and both are tested.
for implied in jvm wasmJs; do
    echo "$listed" | grep -qx "$implied" || fail "the gate does not build $implied"
    echo "$testable" | grep -qx "$implied" || fail "the gate does not test $implied"
done

# The gate has to actually use it, or the list is a file nobody reads.
grep -q 'gate-platforms.sh build' "$repo_root/scripts/check.sh" ||
    fail "scripts/check.sh does not ask which platforms to build"
grep -q 'gate-platforms.sh test' "$repo_root/scripts/check.sh" ||
    fail "scripts/check.sh does not ask which platforms to test"

if [[ $failures -eq 0 ]]; then
    echo "ok    the gate names what this machine can build and what it can test"
else
    echo "$failures failure(s)" >&2
fi
[[ $failures -eq 0 ]]

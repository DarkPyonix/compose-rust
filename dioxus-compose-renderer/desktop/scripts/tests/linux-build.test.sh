#!/usr/bin/env bash
# Static contract tests for the Linux native-image scripts. These run on macOS too, while
# the build and smoke test themselves require Linux.
set -euo pipefail

# `! grep ...` cannot fail a script: the shell ignores `set -e` for any command preceded by
# the `!` reserved word, so every negative assertion written that way is inert and reports
# success no matter what the file contains. Say it the long way instead.
absent() {
    local pattern="$1" file="$2" why="$3"
    # Comments are stripped first. Half of what these scripts say about a flag is the
    # paragraph explaining why it is not used any more, and matching that would make the
    # assertion fire on its own explanation.
    if sed 's/[[:space:]]*#.*$//' "$file" | grep -q -- "$pattern"; then
        echo "FAIL: $(basename "$file") still uses '$pattern'." >&2
        echo "      $why" >&2
        exit 1
    fi
}

scripts_dir="$(cd "$(dirname "$0")/.." && pwd)"
native_dir="$(cd "$scripts_dir/.." && pwd)"

for script in env-linux.sh build-native-linux.sh smoke-test-linux.sh collect-metadata-linux.sh; do
    bash -n "$scripts_dir/$script"
done

cc -c -O2 -o "${TMPDIR:-/tmp}/dioxus-compose-renderer-entry-test.o" \
    "$native_dir/c/renderer_entry.c"
cc -c -O2 -D__linux__ -o "${TMPDIR:-/tmp}/dioxus-compose-smoke-linux-test.o" \
    "$native_dir/c/smoke_host.c"

build="$scripts_dir/build-native-linux.sh"
grep -q 'libawt_xawt\.so' "$build"
grep -q 'libawt_headless\.so' "$build"
grep -q 'libfontmanager\.so' "$build"
grep -q 'libjawt\.so' "$build"
grep -q 'libskiko-linux-' "$build"

# pr2_linux_entry_points_are_exported: the two public C entry points come from the shim, not
# from Kotlin, and Native Image's generated version script makes every symbol it did not
# generate itself local. The script must therefore link the public library itself rather than
# hand the shim object to native-image, and it must fail the build if either name is missing.
grep -q 'cc -shared .*renderer_entry\.o' "$build"
grep -q -- '-Wl,-soname,' "$build"
# The shim's object, and not every object. The window's own object goes through
# native-image on purpose: nothing outside the image calls into it, so the version script
# making its symbols local costs nothing, and the image's own code is what calls them.
absent 'NativeLinkerOption=$obj/renderer_entry.o' "$build" \
    "The C shim is linked in a second step now, because native-image's generated version script marks anything it did not produce as local and then strips it."
absent 'export-dynamic-symbol' "$build" \
    "That flag chooses among symbols the version script left global, so it cannot rescue a symbol the script made local"
for entry_point in dioxus_compose_renderer_run dioxus_compose_renderer_request_frame; do
    grep -q "$entry_point" "$build"
    grep -q "$entry_point" "$native_dir/c/renderer_entry.c"
done
grep -q 'readelf -d' "$build"
grep -q '#ifdef __linux__' "$native_dir/c/smoke_host.c"
grep -q 'WIDGET_TEXT_FIELD' "$native_dir/c/smoke_host.c"

if [[ "$(uname -s)" != "Linux" ]]; then
    if "$build" >"${TMPDIR:-/tmp}/dioxus-compose-linux-test.log" 2>&1; then
        echo "error: Linux build script unexpectedly ran on $(uname -s)" >&2
        exit 1
    fi
    grep -q 'requires Linux' "${TMPDIR:-/tmp}/dioxus-compose-linux-test.log"
fi

echo "linux build script contract: ok"

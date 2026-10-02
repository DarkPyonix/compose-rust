#!/usr/bin/env bash
# The Renderer asks the Windows executable for the Host's functions by name, and the
# executable has to have been told to export exactly those names.
#
# GetProcAddress reads the PE export table. A Rust executable has no export table unless
# the link is told to make one, and `#[unsafe(no_mangle)] pub extern "C"` does not do it:
# that puts the symbol in the object file, which is what the Unix loader needs and is not
# what this needs. When they are missing the renderer loads, the window opens, and every
# call into the Host returns -1, so no mutations arrive and the window stays blank. A
# white window is exactly what the first person to run this on Windows saw.
#
# Nothing here needs Windows. Three lists have to agree, and all three are in the tree:
# what the build script exports, what the C shim looks up, and what the Host defines.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

# The directive lives in the crate rather than in the build script, because a build
# script's link arguments reach that package's own binaries and stop there, and the
# binaries that matter belong to whoever depends on this one.
build_script=dioxus-compose/src/boundary.rs
shim=dioxus-compose-renderer/desktop/c/renderer_entry.c
host=dioxus-compose/src/boundary.rs

for file in "$build_script" "$shim" "$host"; do
    [[ -f "$file" ]] || { echo "fail  $file is missing" >&2; exit 1; }
done

# The section is what carries the names into a consumer's link. Without it the names
# below agree with each other and reach no binary anybody runs.
grep -q '\.drectve' "$build_script" || {
    echo "fail  the export directive is not in a .drectve section" >&2
    echo "      A build script's link arguments stop at this package's own binaries," >&2
    echo "      so an application built on this crate would link with no export table" >&2
    echo "      and its window would come up empty." >&2
    exit 1
}

# What the build script asks the linker to export.
exported="$(grep -oE '/EXPORT:dioxus_compose_host_[a-z_]+' "$build_script" |
    sed 's|/EXPORT:||' | sort -u)"
# What the shim looks up. `LOAD_HOST_EXPORT(init, ...)` means dioxus_compose_host_init.
# The macro's own definition names its parameter `field`, so only the call sites count.
looked_up="$(grep -oE '^ *LOAD_HOST_EXPORT\([a-z_]+' "$shim" |
    sed 's/^ *LOAD_HOST_EXPORT(/dioxus_compose_host_/' | grep -v '_field$' | sort -u)"
# What the Host actually defines.
defined="$(grep -oE 'fn dioxus_compose_host_[a-z_]+' "$host" |
    sed 's/fn //' | sort -u)"

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

[[ -n "$exported" ]] || {
    echo "fail  the build script exports nothing, so a Windows build has no export table" >&2
    exit 1
}

compare "the linker is told to export a different set than the renderer looks up" \
    "$exported" "$looked_up" "the build script" "the renderer shim"
compare "the Host defines a different set than the linker is told to export" \
    "$defined" "$exported" "the Host" "the build script"

if [[ "$failures" -gt 0 ]]; then
    echo "a name in one list and not another is a call that returns -1 at run time" >&2
    exit 1
fi
echo "ok    $(echo "$exported" | wc -l | tr -d ' ') host functions are exported, looked up and defined alike"

# A window application, not a console one.
#
# A Rust binary links for the console subsystem by default, so double clicking a sample
# opened a terminal beside the window and closing that terminal killed it.
#
# The application has to say this, not the library. Setting it from this crate's build
# script reached this package's own targets and no application at all, and broke the
# crate's own cdylib on the way: `/ENTRY:mainCRTStartup` points at a `main` a library does
# not have. So every sample carries the attribute, and anyone building on this crate has
# to as well.
missing=()
for main in samples/*/src/main.rs; do
    grep -q 'windows_subsystem = "windows"' "$main" || missing+=("$main")
done
if [[ "${#missing[@]}" -gt 0 ]]; then
    echo "fail  ${#missing[@]} sample(s) would open a console beside their window:" >&2
    printf '        %s\n' "${missing[@]}" >&2
    exit 1
fi

shim_attaches="$(grep -c 'AttachConsole' "$shim" || true)"
[[ "$shim_attaches" -gt 0 ]] || {
    echo "fail  the renderer does not attach to a parent console on Windows" >&2
    echo "      The window subsystem leaves the process without a standard error, so the" >&2
    echo "      messages explaining a window that drew nothing would go nowhere." >&2
    exit 1
}
echo "ok    the Windows link makes a window application that can still be run from a terminal"

# Windows scales a window it thinks does not understand scaling: it renders at 96 DPI and
# stretches the result, which is why an application on a scaled display looks soft while
# everything around it is sharp. There is no such mechanism on macOS, so this is a defect
# that can only be seen on the platform it belongs to, and only by someone looking.
#
# It has to be declared before anything creates a window, which is why the check is that
# it happens in the entry point rather than merely somewhere in the file.
grep -q 'dioxus_compose_declare_dpi_awareness' "$shim" || {
    echo "fail  the renderer does not declare DPI awareness on Windows" >&2
    echo "      Windows renders an unaware window at 96 DPI and stretches it, so the" >&2
    echo "      application looks blurred against everything else on a scaled display." >&2
    exit 1
}
grep -A 4 'int32_t dioxus_compose_renderer_run(void) {' "$shim" |
    grep -q 'dioxus_compose_declare_dpi_awareness();' || {
    echo "fail  DPI awareness is declared somewhere other than the entry point" >&2
    echo "      It has no effect once a window or a device context exists." >&2
    exit 1
}
echo "ok    the renderer says it draws at the display's real resolution"

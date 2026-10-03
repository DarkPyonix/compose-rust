#!/usr/bin/env bash
# The Kotlin/Native Windows renderer: the module, what it reaches by name, and the kit an
# MSVC link is handed.
#
# None of it can be compiled from here. The module needs Compose and skiko built for
# mingwX64 and published locally first, and what it produces is linked by an MSVC linker on
# Windows; both are the CI job's to do (.github/workflows/windows-static.yml). What is left is
# the wiring, and the wiring is where this renderer fails without saying so: a Compose module
# the build does not publish is an unresolvable coordinate an hour into a build, and a C name
# the Kotlin calls that no linked object defines is an undefined symbol at the very end of it.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
renderer="$repo_root/dioxus-compose-renderer"
project="$renderer/project.yaml"
module="$renderer/windows/module.yaml"
staticlib="$renderer/staticlib-windows/module.yaml"
compose_script="$renderer/scripts/build-compose.sh"

red=0
fail() {
    echo "fail: $1"
    red=1
}

for file in "$project" "$module" "$staticlib" "$compose_script"; do
    [[ -f "$file" ]] || fail "missing $file"
done
(( red == 0 )) || exit 1

# ---------------------------------------------------------------------------
# The module, and the one platform it is for.
# ---------------------------------------------------------------------------

grep -Eq '^ +- windows$' "$project" ||
    fail "project.yaml does not list the windows module, so nothing builds it"
grep -Eq '^ +- staticlib-windows$' "$project" ||
    fail "project.yaml does not list staticlib-windows, so the Host has no symbols to link"
grep -Fq 'platforms: [ mingwX64 ]' "$module" ||
    fail "the windows module does not declare mingwX64"
grep -Fq 'mavenLocal' "$module" ||
    fail "the windows module does not read the local Maven repository, which is the only place Compose for this target is"
[[ "$(readlink "$renderer/staticlib-windows/src/WindowsEntryPoints.kt")" == "../../staticlib/src/IosEntryPoints.kt" ]] ||
    fail "staticlib-windows does not share the entry points every other Kotlin/Native renderer uses"

for source in "$renderer"/windows/src/*.kt; do
    if grep -q 'org\.graalvm' "$source"; then
        fail "$(basename "$source") names GraalVM, which a Kotlin/Native module has not got"
    fi
done

# ---------------------------------------------------------------------------
# One copy of the interpreter, reached the way the other platforms reach it.
# ---------------------------------------------------------------------------

while IFS= read -r shared; do
    name="$(basename "$shared")"
    [[ -L "$renderer/windows/src/shared/$name" ]] ||
        fail "windows/src/shared/$name is not a symlink, so the module holds a second copy of the interpreter or does not compile"
done < <(find "$renderer/macos/src/shared" -maxdepth 1 -name '*.kt')
while IFS= read -r source; do
    [[ -L "$source" ]] || fail "windows/src/shared/$(basename "$source") is a copy rather than a symlink"
    [[ -e "$source" ]] || fail "windows/src/shared/$(basename "$source") points at nothing"
done < <(find "$renderer/windows/src/shared" -maxdepth 1 -name '*.kt')

# The Host is found through the executable's export table here, not dlsym.
grep -q 'GetProcAddress' "$renderer/windows/src/HostSymbolLookup.kt" ||
    fail "the windows module does not look the Host up with GetProcAddress"
[[ -L "$renderer/windows/src/HostSymbolLookup.kt" ]] &&
    fail "windows/src/HostSymbolLookup.kt is a link to the dlsym one, which Windows has not got"

# ---------------------------------------------------------------------------
# Every Compose module this target is given is one the build script publishes.
# ---------------------------------------------------------------------------

asked="$(grep -oE 'org\.jetbrains\.compose\.[a-z0-9]+:[a-z0-9-]+-mingwx64' "$module" |
    sed -E 's/^org\.jetbrains\.compose\.([a-z0-9]+):([a-z0-9-]+)-mingwx64$/compose:\1:\2/' | sort -u)"
published="$(awk '/^    mingwX64\)/ { inside = 1; next } inside && /;;/ { exit } inside' "$compose_script" |
    grep -oE 'compose:[a-z0-9]+:[a-z0-9-]+' | sort -u)"
[[ -n "$asked" ]] || fail "the windows module asks for no Compose module by its mingwx64 coordinate"
[[ -n "$published" ]] || fail "build-compose.sh publishes nothing for mingwX64"
if [[ -n "$asked" && -n "$published" && "$asked" != "$published" ]]; then
    fail "the windows module and build-compose.sh --target mingwX64 disagree:
       only asked for: $(comm -23 <(echo "$asked") <(echo "$published") | tr '\n' ' ')
       only published: $(comm -13 <(echo "$asked") <(echo "$published") | tr '\n' ' ')"
fi

# The Windows pin has to be one with the skiko build in it, and the script has to run that
# build before Compose, which resolves skiko from the local repository.
grep -Eq '^MINGW_REVISION="[0-9a-f]{40}"$' "$compose_script" ||
    fail "build-compose.sh pins no commit for the Windows build"
grep -q 'extended/skiko/build-skiko-mingw.sh' "$compose_script" ||
    fail "build-compose.sh does not build skiko for mingwX64 before Compose"

# ---------------------------------------------------------------------------
# A machine with no graphics card draws only when asked to.
# ---------------------------------------------------------------------------

# A hosted runner has no card, only Windows' software adapter, which the window skips so a
# machine with nothing to draw with is not hidden behind a slow window. The CI job asks for
# it by name; without the opt-in the job could not show the renderer drawing at all.
window_c="$renderer/desktop/c/win32_window.c"
grep -q 'getenv("DXC_D3D12_WARP")' "$window_c" ||
    fail "win32_window.c has no way to be asked for the software adapter"
grep -q 'EnumWarpAdapter' "$window_c" ||
    fail "win32_window.c reads DXC_D3D12_WARP and never takes the software adapter"

# ---------------------------------------------------------------------------
# Every C name the Kotlin calls is defined by a C file the build links.
# ---------------------------------------------------------------------------

# Called by symbol name, so nothing checks them until the MSVC link at the very end, and
# there a missing one is an unresolved external naming a Kotlin mangling nobody wrote.
build_script="$renderer/desktop/scripts/build-windows.sh"
if [[ ! -f "$build_script" ]]; then
    fail "missing $build_script"
else
    c_files=()
    while IFS= read -r relative; do
        c_files+=("$renderer/$relative")
    done < <(grep -oE '^compile "\$(c_dir|PROJECT_DIR)/[^"]+\.c"' "$build_script" |
        sed -E 's/^compile "\$c_dir\//desktop\/c\//; s/^compile "\$PROJECT_DIR\///; s/"$//')
    if [[ "${#c_files[@]}" -eq 0 ]]; then
        fail "build-windows.sh compiles no C file of ours"
    fi
    for file in "${c_files[@]}"; do
        [[ -f "$file" ]] || fail "build-windows.sh compiles $file, which is not there"
    done
    called="$(grep -rhoE '@SymbolName\("[a-z0-9_]+"\)' "$renderer/windows/src" |
        sed -E 's/@SymbolName\("(.*)"\)/\1/' | sort -u)"
    [[ -n "$called" ]] || fail "the windows module calls nothing by symbol name; the window is reached that way"
    for symbol in $called; do
        defined="$(cat "${c_files[@]}" 2>/dev/null | grep -cE "^[a-zA-Z_][a-zA-Z0-9_ *]*[ *]$symbol\(" || true)"
        [[ "$defined" -eq 1 ]] ||
            fail "$symbol is called from windows/src and defined $defined times in what build-windows.sh compiles"
    done
fi

# ---------------------------------------------------------------------------
# The Host links what the script leaves, by the names the script gives it.
# ---------------------------------------------------------------------------

host_build="$repo_root/dioxus-compose/build.rs"
grep -q 'DXC_WINDOWS_NATIVE_LIB' "$host_build" ||
    fail "the Host's build script never reads DXC_WINDOWS_NATIVE_LIB, so nothing links the Windows renderer"
grep -q 'static:+verbatim=libdioxus_compose_renderer.a' "$host_build" ||
    fail "the Host does not link libdioxus_compose_renderer.a by its exact name; MSVC would look for a .lib"
# Whole, or the ICU loader and the bridge's initialiser, which nothing calls by name, are
# left out and the application dies in Skia's paragraph builder.
grep -q 'static:+whole-archive,+verbatim=dxc-windows-native.lib' "$host_build" ||
    fail "the Host does not link dxc-windows-native.lib whole"
grep -q 'dxc-windows-native.lib' "$build_script" ||
    fail "build-windows.sh does not write the dxc-windows-native.lib the Host links"
# The application is not asked for any build setting: the Host decides the C runtime, from
# its build script and from directives in its own object, and an application that set
# +crt-static itself still links (with the bridge's import pointers).
grep -q 'include!("build/windows_crt.rs")' "$host_build" ||
    fail "the Host's build script does not decide the Windows C runtime"
grep -q 'windows_crt_linked_in' "$repo_root/dioxus-compose/src/boundary.rs" ||
    fail "the Host's object carries no C runtime directives, so an application would have to set them"
grep -q 'dxc-windows-static-ucrt.lib' "$build_script" ||
    fail "build-windows.sh does not write the bridge a statically linked UCRT needs"
grep -q '^unset RUSTFLAGS' "$repo_root/scripts/check-windows-consumer.sh" ||
    fail "check-windows-consumer.sh does not clear RUSTFLAGS, so it cannot show an application needs none"
grep -Eq '^build "\$fixture/Cargo.toml" shared$' "$repo_root/scripts/check-windows-consumer.sh" ||
    fail "check-windows-consumer.sh does not build the consumer with nothing set first"

# The CI job is the only place this renderer runs on Windows, and a hosted runner has no
# graphics card: the check has to ask for the software adapter, and the gate has to run it.
consumer_check="$repo_root/scripts/check-windows-consumer.sh"
grep -q 'DXC_D3D12_WARP' "$consumer_check" ||
    fail "check-windows-consumer.sh does not ask for the software adapter, so on a runner the window never opens"
grep -q 'dumpbin' "$consumer_check" ||
    fail "check-windows-consumer.sh does not read which DLLs the executable needs"
grep -q 'windows-static.yml' "$repo_root/.github/workflows/native-renderer.yml" ||
    fail "the native renderer workflow does not run the Windows static renderer job"

if (( red == 0 )); then
    echo "ok    the windows module is declared, shares the interpreter and asks for what is published"
fi
exit $red

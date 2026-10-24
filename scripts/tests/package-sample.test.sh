#!/usr/bin/env bash
# Usage: ./scripts/tests/package-sample.test.sh
#
# The desktop bundles the sample release hands out: what is in them, what they are called,
# and whether the program in one asks the loader for anything but the system's own
# libraries.
#
# A macOS or Linux bundle holds one executable, the icon and the metadata, because the
# renderer is linked into the executable. Every sample is laid out for all four desktop
# targets on whatever machine this runs on, with a stand-in program (and, for Windows,
# whose renderer is still a DLL, a stand-in renderer directory), because the layout and the
# metadata are file operations and text and need neither the renderer nor the platform.
# Then, on macOS and on Linux, a real program is packaged the whole way and the bundle is
# checked the way it would fail on someone else's machine: by what its program asks the
# loader for, and by running it away from where it was built.
#
# Nothing here builds the renderer or a sample. The stand-in is a few lines of C, built
# with the system's compiler when there is one; without one that half says so and skips.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
package="$repo_root/scripts/package-sample.sh"

failures=0
fail() {
    failures=$((failures + 1))
    printf 'FAIL  %s\n' "$1" >&2
    shift
    local line
    for line in "$@"; do printf '        %s\n' "$line" >&2; done
}
pass() { printf 'ok    %s\n' "$1"; }

# Inside the repository, because everything this project makes stays there.
mkdir -p "$repo_root/.scratch"
work="$(mktemp -d "$repo_root/.scratch/package-sample-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# One value from a sample's Dioxus.toml, read the plain way so the test does not share a
# parser with the script it is checking.
config() {
    grep -E "^$2 = \"" "samples/$1/Dioxus.toml" | head -1 | sed -E 's/^[a-z_]+ = "(.*)"$/\1/'
}

png_size() {
    # Width and height from the IHDR chunk, which always starts at byte 16.
    od -An -tu1 -j16 -N8 "$1" | head -1 | awk '{ printf "%dx%d", $1*16777216+$2*65536+$3*256+$4, $5*16777216+$6*65536+$7*256+$8 }'
}

samples=()
for manifest in samples/*/Cargo.toml; do
    sample="$(basename "$(dirname "$manifest")")"
    [[ -f "samples/$sample/src/main.rs" ]] || continue
    samples+=("$sample")
done

[[ ${#samples[@]} -ge 11 ]] || fail "found ${#samples[@]} samples with a program" \
    "There are eleven. A glob that finds fewer is checking less than it says."

# --- every sample says what a bundle needs --------------------------------------------
for sample in "${samples[@]}"; do
    for key in name identifier publisher short_description long_description; do
        [[ -n "$(config "$sample" "$key")" ]] ||
            fail "$sample: Dioxus.toml has no $key" \
                "A bundle is named, identified and described from that file."
    done
    if ! grep -qE '^icon = \["assets/icon-512.png"' "samples/$sample/Dioxus.toml"; then
        fail "$sample: Dioxus.toml does not name its bundle icon" \
            "dx bundle reads [bundle] icon, and a bundle without one wears a blank tile."
    fi
    icon="samples/$sample/assets/icon-512.png"
    if [[ ! -f "$icon" ]]; then
        fail "$sample: no assets/icon-512.png" "Draw it with scripts/make-sample-icons.py."
    elif [[ "$(png_size "$icon")" != 512x512 ]]; then
        fail "$sample: assets/icon-512.png is $(png_size "$icon")" \
            "A Dock or Start tile stretched from a smaller picture is visibly soft."
    fi
    identifier="$(config "$sample" identifier)"
    [[ "$identifier" =~ ^[A-Za-z0-9.-]{3,50}$ ]] ||
        fail "$sample: $identifier cannot name an MSIX package" \
            "Letters, digits, dots and hyphens, three to fifty of them."
done
[[ $failures -eq 0 ]] && pass "every sample has a name, identifier, description and bundle icon"

# --- the four layouts, for every sample, anywhere -------------------------------------
fake_windows_renderer() {
    # A renderer directory shaped like the one the Windows build leaves.
    local dir="$1"
    mkdir -p "$dir/bin" "$dir/lib"
    echo dll > "$dir/bin/compose_rust_renderer.dll"
    echo fonts > "$dir/lib/fontconfig.bfc"
}

# Every file under a directory, one per line and relative, so a bundle can be compared with
# what it is allowed to hold.
files_under() { (cd "$1" && find . -type f | sed 's#^\./##' | sort); }

layout_failures=$failures
for target in macos-aarch64 windows-x64 linux-x64 linux-arm64; do
    renderer_args=()
    if [[ "$target" == windows-x64 ]]; then
        renderer="$work/renderer-$target"
        fake_windows_renderer "$renderer"
        renderer_args=(--renderer "$renderer")
    fi
    for sample in "${samples[@]}"; do
        program="sample-$sample"
        [[ "$target" == windows-x64 ]] && program="$program.exe"
        exe="$work/bin/$program"
        mkdir -p "$work/bin"
        printf '#!/bin/sh\necho %s\n' "$sample" > "$exe"
        chmod +x "$exe"
        out="$work/out-$target"
        if ! output="$("$package" --stage-only ${renderer_args[@]+"${renderer_args[@]}"} "$sample" "$target" "$exe" "$out" 2>&1)"; then
            fail "$sample $target: staging failed" "$output"
            continue
        fi
        stage="$out/stage/$sample-$target"
        name="$(config "$sample" name)"
        identifier="$(config "$sample" identifier)"
        summary="$(config "$sample" short_description)"
        case "$target" in
            macos-aarch64)
                contents="$stage/$name.app/Contents"
                plist="$contents/Info.plist"
                for expected in \
                    "<key>CFBundleName</key> <string>$name</string>" \
                    "<key>CFBundleIdentifier</key> <string>$identifier</string>" \
                    "<key>CFBundleExecutable</key> <string>$program</string>" \
                    "<key>CFBundleGetInfoString</key> <string>$summary</string>" \
                    "<key>CFBundleIconFile</key> <string>$name.icns</string>" \
                    "<key>NSHighResolutionCapable</key> <true/>"; do
                    if ! tr -s ' \n\t' ' ' < "$plist" | grep -qF "$expected"; then
                        fail "$sample macOS: Info.plist lacks $expected"
                    fi
                done
                [[ -x "$contents/MacOS/$program" ]] || fail "$sample macOS: no program in Contents/MacOS"
                [[ -f "$contents/Resources/$name.png" ]] || fail "$sample macOS: no icon in Resources"
                held="$(files_under "$stage/$name.app" | grep -vE '^Contents/(Info.plist|MacOS/|Resources/)' || true)"
                [[ -z "$held" ]] || fail "$sample macOS: the bundle holds more than the executable, icon and metadata" "$held"
                ;;
            windows-x64)
                root="$stage/$name"
                manifest="$root/AppxManifest.xml"
                for expected in \
                    "<Identity Name=\"$identifier\"" \
                    "<DisplayName>$name</DisplayName>" \
                    "Executable=\"bin\\$program\"" \
                    "Description=\"$summary\"" \
                    "OID.2.25.311729368913984317654407730594956997722=1"; do
                    grep -qF "$expected" "$manifest" || fail "$sample Windows: manifest lacks $expected"
                done
                [[ -f "$root/bin/$program" && -f "$root/bin/compose_rust_renderer.dll" ]] ||
                    fail "$sample Windows: the program is not beside the renderer's DLL"
                [[ -f "$root/lib/fontconfig.bfc" ]] ||
                    fail "$sample Windows: the renderer's lib directory was not kept"
                for logo in StoreLogo Square44x44Logo Square150x150Logo; do
                    [[ -f "$root/Assets/$logo.png" ]] || fail "$sample Windows: no $logo"
                done
                ;;
            linux-*)
                appdir="$stage/$name.AppDir"
                desktop="$appdir/$identifier.desktop"
                for expected in "Name=$name" "Comment=$summary" "Exec=$program" "Icon=$identifier"; do
                    grep -qxF "$expected" "$desktop" || fail "$sample Linux: desktop entry lacks $expected"
                done
                [[ -x "$appdir/AppRun" ]] || fail "$sample Linux: no AppRun"
                [[ -f "$appdir/$identifier.png" && -e "$appdir/.DirIcon" ]] ||
                    fail "$sample Linux: no icon at the top of the AppDir"
                held="$(files_under "$appdir/usr/bin")"
                [[ "$held" == "$program" ]] ||
                    fail "$sample Linux: usr/bin holds more than the executable" "$held"
                ;;
        esac
    done
done
[[ $failures -eq $layout_failures ]] &&
    pass "every sample lays out as a .app, an MSIX and zip, and an AppImage, named from its Dioxus.toml"

# --- refuses a sample without the configuration a bundle is named from ----------------
if "$package" --stage-only no-such-sample macos-aarch64 /bin/sh "$work/none" >/dev/null 2>&1; then
    fail "packaging a sample with no Dioxus.toml succeeded" \
        "A bundle named by guesswork is worse than no bundle."
else
    pass "a sample with no configuration is refused"
fi

# --- the whole way, with a real program ------------------------------------------------
# Built here because the point is the loader: a program packaged, moved and run, which
# must ask for nothing but the system.
real_program() {
    cat > "$work/program.c" <<'C'
#include <stdio.h>
int main(void) { printf("started\n"); return 0; }
C
    cc -o "$work/sample-calculator" "$work/program.c"
}

case "$(uname -s)" in
    Darwin)
        if ! command -v cc >/dev/null; then
            echo "skip  no C compiler, so the loader half of the macOS bundle is not checked"
        elif ! real_program; then
            fail "could not build the stand-in program"
        else
            out="$work/real-macos"
            if ! output="$("$package" calculator macos-aarch64 "$work/sample-calculator" "$out" 2>&1)"; then
                fail "packaging for macOS failed" "$output"
            else
                app="$out/stage/calculator-macos-aarch64/Calculator.app"
                asked="$(otool -L "$app/Contents/MacOS/sample-calculator" | awk 'NR > 1 { print $1 }' | grep -vE '^(/usr/lib/|/System/)' || true)"
                [[ -z "$asked" ]] ||
                    fail "the bundled program asks for $asked" "Only the system's libraries may be named."
                [[ -f "$app/Contents/Resources/Calculator.icns" ]] || fail "the bundle has no .icns"
                codesign --verify --deep --strict "$app" 2>/dev/null ||
                    fail "the bundle's signature does not verify" "Apple silicon will not start it."
                [[ -f "$out/calculator-macos-aarch64.dmg" && -f "$out/calculator-macos-aarch64.app.zip" ]] ||
                    fail "no .dmg or .app.zip was written"
                rm -f "$work/sample-calculator"
                ran="$("$app/Contents/MacOS/sample-calculator" 2>&1)"
                [[ "$ran" == "started" ]] ||
                    fail "the bundled program did not start without the build tree" "$ran"
                [[ $failures -eq 0 ]] &&
                    pass "a macOS bundle is one signed executable that starts away from the build"
            fi
        fi
        ;;
    Linux)
        if ! command -v cc >/dev/null; then
            echo "skip  no C compiler, so the loader half of the AppImage is not checked"
        elif ! real_program; then
            fail "could not build the stand-in program"
        else
            # A stand-in for appimagetool that records what it was handed. The image
            # format is the tool's business; what goes into it is this script's.
            cat > "$work/appimagetool" <<'TOOL'
#!/bin/sh
for last; do :; done
echo "$@" > "$last.args"
touch "$last"
TOOL
            chmod +x "$work/appimagetool"
            out="$work/real-linux"
            if ! output="$(APPIMAGETOOL="$work/appimagetool" "$package" calculator linux-x64 \
                "$work/sample-calculator" "$out" 2>&1)"; then
                fail "packaging for Linux failed" "$output"
            else
                appdir="$out/stage/calculator-linux-x64/Calculator.AppDir"
                [[ -f "$out/calculator-linux-x64.AppImage" ]] || fail "no AppImage was written"
                rm -f "$work/sample-calculator"
                ran="$("$appdir/AppRun" 2>&1)"
                [[ "$ran" == "started" ]] ||
                    fail "the AppImage's program did not start without the build tree" "$ran"
                [[ $failures -eq 0 ]] &&
                    pass "an AppImage is one executable that starts away from the build"
            fi
        fi
        ;;
    *)
        echo "skip  the loader half runs on macOS and Linux"
        ;;
esac

exit "$failures"

#!/usr/bin/env bash
# Usage: scripts/package-sample.sh [--stage-only] <sample> <target> <executable> <renderer> <out>
#
#   <sample>      a directory under samples/, e.g. calculator
#   <target>      macos-aarch64, windows-x64, linux-x64 or linux-arm64
#   <executable>  the sample's built program
#   <renderer>    the renderer directory the program was linked against: the `dist/lib`
#                 a macOS or Linux build leaves, or the whole `dist` a Windows one leaves
#   <out>         where the finished files go
#
# Turns one built sample into what a person on that platform installs or runs:
#
#   macos-aarch64   <sample>-macos-aarch64.app.zip and <sample>-macos-aarch64.dmg
#   windows-x64     <sample>-windows-x64.zip and <sample>-windows-x64.msix (unsigned)
#   linux-*         <sample>-linux-x64.AppImage or <sample>-linux-arm64.AppImage
#
# The name, the identifier and the description come from the sample's Dioxus.toml, the
# same file `dx` reads, and the picture from its assets/icon-512.png. Nothing about a
# sample is restated here, so a sample renamed in its own configuration is renamed in
# every bundle.
#
# The renderer travels inside every bundle as the files it was built as. Each layout below
# keeps the renderer's directory whole, because the renderer finds its Skia and AWT
# companions from where it was loaded and reads its font configuration from the `lib`
# directory beside that. scripts/bundle-renderer.sh then points the program at the copy
# by a relative path, so nothing in a bundle names the machine that built it.
#
# --stage-only lays every bundle out as a directory and stops before the steps that need
# the platform's own tools: rewriting load commands, signing, and making the image or the
# package. A layout can then be checked on any machine, which is what
# scripts/tests/package-sample.test.sh does for all four targets.

set -euo pipefail

stage_only=0
if [[ "${1:-}" == "--stage-only" ]]; then
    stage_only=1
    shift
fi

if [[ $# -ne 5 ]]; then
    sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
fi

sample="$1"
target="$2"
executable="$3"
renderer="$4"
out="$5"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

fail() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

sample_dir="$repo_root/samples/$sample"
config="$sample_dir/Dioxus.toml"
manifest="$sample_dir/Cargo.toml"
icon="$sample_dir/assets/icon-512.png"

[[ -f "$config" ]] || fail "no Dioxus.toml at $config" \
    "The bundle's name, identifier and description are read from it."
[[ -f "$manifest" ]] || fail "no Cargo.toml at $manifest"
[[ -f "$icon" ]] || fail "no bundle icon at $icon" \
    "Draw it with scripts/make-sample-icons.py. The 64 pixel window icon is too small" \
    "for a Dock tile or a Start menu tile, and stretching it shows."
[[ -f "$executable" ]] || fail "no executable at $executable"
[[ -d "$renderer" ]] || fail "no renderer directory at $renderer"

# One `key = "value"` from one [section] of a TOML file. The configuration files this
# reads are flat tables of strings, and a TOML parser is not something every runner has
# (the Windows one ships a Python too old for tomllib), so this reads exactly that shape
# and fails rather than guessing at anything else.
toml_string() {
    local file="$1" section="$2" key="$3" value
    value="$(awk -v section="$section" -v key="$key" '
        /^[[:space:]]*\[/ {
            line = $0
            gsub(/^[[:space:]]*\[|\][[:space:]]*$/, "", line)
            current = line
            next
        }
        current == section {
            line = $0
            if (match(line, "^[[:space:]]*" key "[[:space:]]*=[[:space:]]*\"")) {
                rest = substr(line, RLENGTH + 1)
                sub(/"[[:space:]]*(#.*)?$/, "", rest)
                print rest
                exit
            }
        }
    ' "$file")"
    [[ -n "$value" ]] || fail "$file has no $key in [$section]" \
        "Every sample says it there, and a bundle without it would be named by guesswork."
    printf '%s' "$value"
}

name="$(toml_string "$config" application name)"
identifier="$(toml_string "$config" bundle identifier)"
publisher="$(toml_string "$config" bundle publisher)"
summary="$(toml_string "$config" bundle short_description)"
description="$(toml_string "$config" bundle long_description)"
version="$(toml_string "$manifest" package version)"

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail \
    "the version in $manifest is $version" \
    "Bundles carry a plain major.minor.patch: a Mac reads it as CFBundleVersion and an" \
    "MSIX needs four numbers, so a pre-release suffix has no place to go."

# Text that goes into a property list, a manifest or a desktop entry. The names here are
# plain words today; escaping is what keeps "Tom & Jerry" from producing an unreadable
# bundle tomorrow.
xml() {
    local text="$1"
    text="${text//&/&amp;}"
    text="${text//</&lt;}"
    text="${text//>/&gt;}"
    text="${text//\"/&quot;}"
    printf '%s' "$text"
}

program="$(basename "$executable")"
stage="$out/stage/$sample-$target"
rm -rf "$stage"
mkdir -p "$stage" "$out"
out="$(cd "$out" && pwd)"
stage="$(cd "$stage" && pwd)"

# ---------------------------------------------------------------------------------------
# macOS: an application bundle, and a disk image to hand it over in.
# ---------------------------------------------------------------------------------------
package_macos() {
    local app="$stage/$name.app"
    local contents="$app/Contents"
    mkdir -p "$contents/MacOS" "$contents/Frameworks/lib" "$contents/Resources"
    cp "$executable" "$contents/MacOS/$program"
    # The renderer's own `lib` directory, carried across whole. Flattened into Frameworks
    # it starts, loads the renderer, and then fails looking for Contents/lib/libjawt.dylib.
    cp -R "$renderer/." "$contents/Frameworks/lib/"
    cp "$icon" "$contents/Resources/$name.png"

    # NSHighResolutionCapable is what keeps the window sharp. An executable outside a
    # bundle is drawn at the display's scale by default, but a bundle that does not say
    # this is drawn at 1x and magnified, and every rounded corner shows its steps.
    cat > "$contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>CFBundleDisplayName</key>
    <string>$(xml "$name")</string>
    <key>CFBundleExecutable</key>
    <string>$(xml "$program")</string>
    <key>CFBundleGetInfoString</key>
    <string>$(xml "$summary")</string>
    <key>CFBundleIconFile</key>
    <string>$(xml "$name").icns</string>
    <key>CFBundleIdentifier</key>
    <string>$(xml "$identifier")</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>$(xml "$name")</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>$version</string>
    <key>CFBundleVersion</key>
    <string>$version</string>
    <key>LSApplicationCategoryType</key>
    <string>public.app-category.utilities</string>
    <key>LSMinimumSystemVersion</key>
    <string>13.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSHumanReadableCopyright</key>
    <string>$(xml "$publisher")</string>
</dict>
</plist>
PLIST

    [[ $stage_only -eq 1 ]] && return

    command -v sips >/dev/null && command -v iconutil >/dev/null || fail \
        "sips and iconutil are not on PATH" \
        "They come with macOS and turn the icon into the .icns a bundle carries."
    local iconset="$stage/$name.iconset"
    mkdir -p "$iconset"
    local size
    for size in 16 32 128 256; do
        sips -z "$size" "$size" "$icon" --out "$iconset/icon_${size}x${size}.png" >/dev/null
        sips -z $((size * 2)) $((size * 2)) "$icon" \
            --out "$iconset/icon_${size}x${size}@2x.png" >/dev/null
    done
    cp "$icon" "$iconset/icon_512x512.png"
    iconutil -c icns "$iconset" -o "$contents/Resources/$name.icns"
    rm -rf "$iconset" "$contents/Resources/$name.png"

    "$repo_root/scripts/bundle-renderer.sh" "$contents/MacOS/$program" ../Frameworks/lib

    # Rewriting a load command invalidates the signature the linker left, and Apple
    # silicon will not start an unsigned program. An ad hoc signature is what the linker
    # gave it in the first place; libraries first, because signing the bundle seals them.
    local library
    while IFS= read -r -d '' library; do
        if file "$library" | grep -q 'Mach-O'; then
            codesign --force --sign - "$library"
        fi
    done < <(find "$contents/Frameworks" -type f -print0)
    codesign --force --sign - "$app"

    local zip="$out/$sample-$target.app.zip"
    rm -f "$zip"
    # ditto rather than zip: it keeps the symbolic links, permissions and extended
    # attributes a bundle is made of, which is what Finder's own Compress does.
    ditto -c -k --sequesterRsrc --keepParent "$app" "$zip"

    local volume="$stage/volume"
    mkdir -p "$volume"
    cp -R "$app" "$volume/"
    ln -s /Applications "$volume/Applications"
    local dmg="$out/$sample-$target.dmg"
    rm -f "$dmg"
    hdiutil create -quiet -volname "$name" -srcfolder "$volume" -ov -format UDZO "$dmg"
    rm -rf "$volume"
    echo "ok    $zip"
    echo "ok    $dmg"
}

# ---------------------------------------------------------------------------------------
# Windows: a directory that runs where it is unpacked, and the same files as an MSIX.
# ---------------------------------------------------------------------------------------
package_windows() {
    local root="$stage/$name"
    mkdir -p "$root"
    # The renderer's `dist` as it is: the DLL in `bin`, the font configuration AWT reads
    # in `lib`, because the renderer sets java.home to their parent. The program goes in
    # beside the DLL, where the loader looks first.
    cp -R "$renderer/." "$root/"
    mkdir -p "$root/bin"
    cp "$executable" "$root/bin/$program"
    mkdir -p "$root/Assets"
    local logo
    for logo in StoreLogo Square44x44Logo Square150x150Logo; do
        cp "$icon" "$root/Assets/$logo.png"
    done

    # Identity Name allows letters, digits, dots and hyphens, three to fifty of them.
    [[ "$identifier" =~ ^[A-Za-z0-9.-]{3,50}$ ]] || fail \
        "$identifier cannot be an MSIX identity name" \
        "An MSIX names its package with letters, digits, dots and hyphens, 3 to 50 of them."

    # The publisher carries the attribute Windows requires before it will install an
    # unsigned package at all: Add-AppxPackage -AllowUnsigned refuses one without it.
    # Re-signing for a certificate of your own means writing that certificate's subject
    # here instead.
    cat > "$root/AppxManifest.xml" <<MANIFEST
<?xml version="1.0" encoding="utf-8"?>
<Package
  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
  IgnorableNamespaces="uap rescap">
  <Identity Name="$(xml "$identifier")"
    Publisher="CN=$(xml "$publisher"), OID.2.25.311729368913984317654407730594956997722=1"
    Version="$version.0"
    ProcessorArchitecture="x64" />
  <Properties>
    <DisplayName>$(xml "$name")</DisplayName>
    <PublisherDisplayName>$(xml "$publisher")</PublisherDisplayName>
    <Description>$(xml "$description")</Description>
    <Logo>Assets\\StoreLogo.png</Logo>
  </Properties>
  <Dependencies>
    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.17763.0" MaxVersionTested="10.0.22621.0" />
  </Dependencies>
  <Resources>
    <Resource Language="en-us" />
  </Resources>
  <Applications>
    <Application Id="App" Executable="bin\\$(xml "$program")" EntryPoint="Windows.FullTrustApplication">
      <uap:VisualElements
        DisplayName="$(xml "$name")"
        Description="$(xml "$summary")"
        BackgroundColor="transparent"
        Square150x150Logo="Assets\\Square150x150Logo.png"
        Square44x44Logo="Assets\\Square44x44Logo.png" />
    </Application>
  </Applications>
  <Capabilities>
    <rescap:Capability Name="runFullTrust" />
  </Capabilities>
</Package>
MANIFEST

    [[ $stage_only -eq 1 ]] && return

    local zip="$out/$sample-$target.zip"
    rm -f "$zip"
    # The zip carries the same files without the package's manifest and tiles, which
    # mean nothing outside one.
    local plain="$stage/zip/$name"
    mkdir -p "$plain"
    cp -R "$root/." "$plain/"
    rm -rf "$plain/AppxManifest.xml" "$plain/Assets"
    if command -v 7z >/dev/null; then
        (cd "$stage/zip" && 7z a -tzip -bd "$zip" "$name" >/dev/null)
    elif command -v zip >/dev/null; then
        (cd "$stage/zip" && zip -qr "$zip" "$name")
    else
        fail "neither 7z nor zip is on PATH" "GitHub's Windows runners carry 7z."
    fi

    local makeappx
    makeappx="$(command -v makeappx || command -v makeappx.exe || true)"
    if [[ -z "$makeappx" ]]; then
        makeappx="$(ls -d "/c/Program Files (x86)/Windows Kits/10/bin/"10.*/x64/makeappx.exe \
            2>/dev/null | sort -V | tail -1 || true)"
    fi
    [[ -n "$makeappx" ]] || fail "makeappx is not on PATH or in the Windows 10 SDK" \
        "It comes with the Windows SDK, which GitHub's Windows runners have."
    local msix="$out/$sample-$target.msix"
    rm -f "$msix"
    # Unsigned. Signing needs a certificate somebody trusts, and the release says how to
    # install one without it or re-sign it with one of your own.
    "$makeappx" pack -o -d "$(cygpath -w "$root" 2>/dev/null || echo "$root")" \
        -p "$(cygpath -w "$msix" 2>/dev/null || echo "$msix")"
    echo "ok    $zip"
    echo "ok    $msix"
}

# ---------------------------------------------------------------------------------------
# Linux: an AppImage, which runs from wherever it is downloaded to.
# ---------------------------------------------------------------------------------------
package_linux() {
    local appdir="$stage/$name.AppDir"
    local bin="$appdir/usr/bin"
    mkdir -p "$bin/lib" "$appdir/usr/share/applications" \
        "$appdir/usr/share/icons/hicolor/512x512/apps"
    cp "$executable" "$bin/$program"
    # A `lib` directory beside the program, holding the renderer's own `lib` whole.
    cp -R "$renderer/." "$bin/lib/"
    cp "$icon" "$appdir/usr/share/icons/hicolor/512x512/apps/$identifier.png"
    cp "$icon" "$appdir/$identifier.png"
    ln -sf "$identifier.png" "$appdir/.DirIcon"

    cat > "$appdir/usr/share/applications/$identifier.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=$name
Comment=$summary
Exec=$program
Icon=$identifier
Categories=Utility;
Terminal=false
StartupWMClass=$name
DESKTOP
    cp "$appdir/usr/share/applications/$identifier.desktop" "$appdir/$identifier.desktop"

    cat > "$appdir/AppRun" <<APPRUN
#!/bin/sh
# Starts the program from wherever the image was mounted. The program finds the renderer
# through its own run path, so nothing here sets a library path.
here="\$(dirname "\$(readlink -f "\$0")")"
exec "\$here/usr/bin/$program" "\$@"
APPRUN
    chmod +x "$appdir/AppRun" "$bin/$program"

    [[ $stage_only -eq 1 ]] && return

    "$repo_root/scripts/bundle-renderer.sh" "$bin/$program" lib

    local tool="${APPIMAGETOOL:-$(command -v appimagetool || true)}"
    [[ -n "$tool" ]] || fail "appimagetool is not on PATH and APPIMAGETOOL is not set" \
        "The sample workflow downloads a pinned release of it and checks its digest."
    local arch
    case "$target" in
        linux-x64) arch=x86_64 ;;
        linux-arm64) arch=aarch64 ;;
    esac
    local image="$out/$sample-$target.AppImage"
    rm -f "$image"
    # Extract-and-run, because a CI runner has no FUSE to mount the tool's own image with.
    ARCH="$arch" APPIMAGE_EXTRACT_AND_RUN=1 "$tool" --no-appstream "$appdir" "$image"
    echo "ok    $image"
}

case "$target" in
    macos-aarch64) package_macos ;;
    windows-x64) package_windows ;;
    linux-x64 | linux-arm64) package_linux ;;
    *) fail "no bundle for target $target" \
        "The desktop targets are macos-aarch64, windows-x64, linux-x64 and linux-arm64." ;;
esac

if [[ $stage_only -eq 1 ]]; then
    echo "ok    staged $stage"
fi

#!/usr/bin/env bash
# The renderer macOS ships opens its own AppKit window and draws with Skia, so it does not
# load the Java toolkit's window, input method or accessibility bridges.
#
# Windows and Linux still open the toolkit's window by default, so the sources that only
# those windows use stay where they are and are listed below. When a platform's own window
# becomes its default, its files leave the list and the check covers it too.
#
# This test fails when:
#   - the macOS build script force-loads the toolkit archive, roots its accessibility
#     classes or asks for the features that registered its input method and accessibility,
#   - a source that is not on the toolkit-window list names a toolkit type.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
desktop="renderer/desktop"
status=0
fail() { echo "FAIL: $1"; status=1; }

build="$desktop/scripts/build-native.sh"
[[ -f "$build" ]] || { echo "FAIL: $build is missing"; exit 1; }

code="$(grep -vE '^[[:space:]]*#' "$build")"
if grep -q 'libawt_lwawt\.a\|awt_archive' <<< "$code"; then
    fail "$build links the toolkit's macOS archive"
fi
if grep -q 'ImeReachabilityFeature\|AccessibilityReachabilityFeature' <<< "$code"; then
    fail "$build registers the toolkit's input method or accessibility bridge"
fi
if grep -q 'Accessibility\$\|a11y_classes' <<< "$code"; then
    fail "$build roots the toolkit's accessibility classes"
fi

# Sources only the toolkit window (Windows and Linux for now) reaches, and the one place
# macOS needs a toolkit type: Compose's ClipEntry is a java.awt.datatransfer type.
toolkit_only=(
    AccessibilityReachabilityFeature.kt ImeReachabilityFeature.kt ReachabilityRegistration.kt
    MetadataCollectionMain.kt Renderer.kt RendererEntryPoints.kt WindowChrome.kt WindowIcon.kt
    WindowResize.kt renderer/HostFileDrop.kt renderer/WindowTransparency.kt
    renderer/ToolkitResizeCursor.kt renderer/DevMain.kt renderer/DesignShowcase.kt
)
pattern='(^|[^"[:alnum:]_.])(java\.awt|javax\.swing|sun\.awt|sun\.lwawt|com\.apple\.eawt|com\.apple\.laf|javax\.accessibility)\.[A-Za-z]|awtTransferable|toAwtImage|kotlinx\.coroutines\.swing'
while IFS= read -r file; do
    rel="${file#$desktop/src/}"
    skip=0
    for name in "${toolkit_only[@]}"; do [[ "$rel" == "$name" ]] && skip=1; done
    [[ $skip -eq 1 ]] && continue
    hits="$(grep -nE "$pattern" "$file" | grep -vE '^[0-9]+:[[:space:]]*(//|\*|/\*)' || true)"
    if [[ "$rel" == "NativeClipboard.kt" ]]; then
        hits="$(grep -vE 'java\.awt\.datatransfer\.|asAwtTransferable' <<< "$hits" || true)"
    fi
    [[ -n "$hits" ]] && fail "$file names the Java toolkit outside the toolkit-window list:
$hits"
done < <(find "$desktop/src" -name '*.kt' | sort)

# The list must not name files that no longer exist, or it stops meaning anything.
for name in "${toolkit_only[@]}"; do
    [[ -f "$desktop/src/$name" ]] || fail "the toolkit-window list names $name, which does not exist"
done

[[ $status -eq 0 ]] && echo "the macOS path names no Java toolkit window: ok"
exit $status

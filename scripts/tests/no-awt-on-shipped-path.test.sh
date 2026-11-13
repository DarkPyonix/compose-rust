#!/usr/bin/env bash
# The renderer that ships opens its own window on every desktop and draws with Skia, so
# nothing it runs needs the Java toolkit: no java.awt window, no Swing, no toolkit peers.
#
# The sources split in two. renderer/desktop/src/devshell is the development shell that runs
# on a JVM with the toolkit's window, and the build-time tooling that served it, and is allowed
# to name the toolkit. Everything else under renderer/desktop/src is the shipped path and is
# not. This test fails when:
#   - a shipped source imports or names a toolkit type,
#   - the reachability metadata every image reads registers a toolkit class,
#   - a native image build asks for the features that registered the toolkit's input method
#     and accessibility bridges, or hands an image the toolkit's own metadata.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
desktop="renderer/desktop"
status=0
fail() { echo "FAIL: $1"; status=1; }

# 1. The shipped sources. Comments are not uses: a line has to name a toolkit package as code,
# with a capitalised type or a package segment after it.
pattern='(^|[^"[:alnum:]_.])(java\.awt|javax\.swing|sun\.awt|sun\.lwawt|com\.apple\.eawt|com\.apple\.laf|javax\.accessibility)\.[A-Za-z]|awtTransferable|toAwtImage|kotlinx\.coroutines\.swing'
while IFS= read -r file; do
    allowed=""
    if [[ "$file" == "$desktop/src/NativeClipboard.kt" ]]; then
        # Compose's ClipEntry is a java.awt.datatransfer.Transferable on desktop; only the
        # datatransfer package is allowed here, and only in this file.
        allowed='java\.awt\.datatransfer\.|asAwtTransferable'
    fi
    hits="$(grep -nE "$pattern" "$file" | grep -vE '^[0-9]+:[[:space:]]*(//|\*|/\*)' || true)"
    if [[ -n "$allowed" ]]; then
        hits="$(grep -vE "$allowed" <<< "$hits" || true)"
    fi
    if [[ -n "$hits" ]]; then
        fail "$file names the Java toolkit on the shipped path:
$hits"
    fi
done < <(find "$desktop/src" -name '*.kt' -not -path "$desktop/src/devshell/*" | sort)

# 2. The metadata every image reads from the class path.
shared="$desktop/resources/META-INF/native-image/dev.darkpyonix.composerust/renderer/reachability-metadata.json"
[[ -f "$shared" ]] || fail "$shared is missing"
if grep -qE '"(java\.awt|javax\.swing|sun\.awt|sun\.lwawt|sun\.java2d|javax\.accessibility)\.' "$shared"; then
    fail "$shared registers a toolkit class, which every image would then carry"
fi

# 3. The native image builds.
for build in "$desktop/scripts/build-native.sh" "$desktop/scripts/build-native-linux.sh" \
             "$desktop/scripts/build-native-windows.ps1"; do
    [[ -f "$build" ]] || { fail "$build is missing"; continue; }
    if grep -vE '^[[:space:]]*#' "$build" | grep -qE 'ImeReachabilityFeature|AccessibilityReachabilityFeature'; then
        fail "$build asks for the toolkit's input method or accessibility feature"
    fi
    if grep -vE '^[[:space:]]*#' "$build" | grep -qE 'toolkit-metadata|ToolkitMetadataDir,|,\$ToolkitMetadataDir|Preserve=module=java\.desktop'; then
        fail "$build hands the image the toolkit's metadata or the java.desktop module"
    fi
done

[[ $status -eq 0 ]] && echo "the shipped path names no Java toolkit: ok"
exit $status

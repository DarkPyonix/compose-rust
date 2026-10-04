#!/usr/bin/env bash
# The renderer that ships opens its own window on every desktop and draws with Skia, so
# nothing it runs needs the Java toolkit: no java.awt window, no Swing, no toolkit peers.
#
# The sources split in two. renderer/desktop/src/devshell is the development shell that runs
# on a JVM with the toolkit's window and is allowed to import it. Everything else under
# renderer/desktop/src is the shipped path and is not. This test fails when:
#   - a shipped source imports or names a toolkit type,
#   - the reachability metadata registers a toolkit window, input method or look and feel
#     class, or names an AWT class that is not on the list of what Compose still forces,
#   - a build script asks for the features that registered the toolkit's input method and
#     accessibility bridges.
#
# What Compose forces is written down in renderer/desktop/awt-forced.txt with the reason,
# and each name there is a debt owed to the fork (compose-multiplatform-core-extended #15).
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
desktop="renderer/desktop"
status=0
fail() { echo "FAIL: $1"; status=1; }

# 1. The shipped sources. Comments and string literals are not uses: a line has to name a
# toolkit package as code, with a capitalised type or a package segment after it.
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

# 2. The reachability metadata.
python3 - "$desktop" <<'PY' || status=1
import json, sys, re
desktop = sys.argv[1]
forced = set()
for line in open(f"{desktop}/awt-forced.txt"):
    line = line.split("#")[0].strip()
    if line:
        forced.add(line)
files = [
    f"{desktop}/resources/META-INF/native-image/dev.darkpyonix.composerust/renderer/reachability-metadata.json",
    f"{desktop}/scripts/windows-metadata/reachability-metadata.json",
]
banned = re.compile(r"^(javax\.swing|javax\.accessibility|com\.apple\.(eawt|laf)|sun\.lwawt\.macosx\.CPlatform|sun\.lwawt\.LW|sun\.java2d\.metal)")
toolkit = re.compile(r"^(java\.awt|sun\.awt|sun\.lwawt|sun\.java2d)")
failed = False
for path in files:
    data = json.load(open(path))
    for entry in data.get("reflection", []):
        name = entry.get("type") or entry.get("name") or ""
        if banned.match(name):
            print(f"FAIL: {path} registers {name}, a toolkit window or input method class"); failed = True
        elif toolkit.match(name) and name not in forced:
            print(f"FAIL: {path} registers {name}, which is not in awt-forced.txt"); failed = True
    for entry in data.get("resources", []):
        bundle = entry.get("bundle", "")
        if bundle.startswith(("com.apple.laf", "sun.awt.resources.awtosx")):
            print(f"FAIL: {path} keeps the resource bundle {bundle}"); failed = True
sys.exit(1 if failed else 0)
PY

# 3. The features that registered the toolkit's bridges are gone, except the input method one
# the Linux build keeps while Linux opens the toolkit's window by default.
if grep -rn 'ImeReachabilityFeature\|AccessibilityReachabilityFeature' "$desktop/scripts" --include='*.sh' --include='*.ps1' | grep -v 'build-native-linux.sh' | grep -v '^\S*:[0-9]*:\s*#' | grep -q .; then
    fail "a build script still names the toolkit's input method or accessibility feature"
fi

[[ $status -eq 0 ]] && echo "the shipped path names no Java toolkit window: ok"
exit $status

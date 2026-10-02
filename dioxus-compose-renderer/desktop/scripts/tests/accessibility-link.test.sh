#!/usr/bin/env bash
# Checks that the built renderer still carries the Objective-C accessibility classes
# Run it after build-native.sh.
#
# AppKit reaches those classes only through NSClassFromString, so no symbol in the image
# refers to them and the link is free to drop them as dead code. When it does, the lookup
# returns nil, the nil becomes a nil child, and the process aborts with
# "object cannot be nil" the first time VoiceOver or any other client reads the window's
# children. Nothing else notices: the build succeeds, the smoke test exits 0, and the
# failure appears only when someone turns VoiceOver on.
#
# This is the cheap half of the check. The other half is running
# experiments/accessibility/ax-dump.swift against the smoke test, which proves the tree is
# actually built rather than merely linkable.
set -euo pipefail
source "$(cd "$(dirname "$0")/.." && pwd)/env.sh"

library="$DIST_DIR/lib/$LIBRARY_NAME.dylib"
[[ -f "$library" ]] || die \
    "$library not found" \
    "fix: run $(dirname "$0")/../build-native.sh first"

# The expectation comes from the archive that defines the classes, not from a list written
# down here, so a JDK that adds a role is covered without anyone remembering to edit this.
missing=()
present="$(otool -oV "$library" | awk '$1 == "name" && $NF ~ /Accessibility$/ { print $NF }' | sort -u)"
while IFS= read -r symbol; do
    class="${symbol#_OBJC_CLASS_\$_}"
    grep -qx "$class" <<< "$present" || missing+=("$class")
done < <(nm -g "$AWT_STATIC_ARCHIVE" 2>/dev/null |
    awk '$2 == "S" && $3 ~ /^_OBJC_CLASS_\$_[A-Za-z]+Accessibility$/ { print $3 }' | sort -u)

if [[ ${#missing[@]} -gt 0 ]]; then
    die "the image is missing ${#missing[@]} accessibility class(es): ${missing[*]}" \
        "The link dropped them. build-native.sh makes each one a root with -Wl,-u;" \
        "check that the list it reads out of $AWT_STATIC_ARCHIVE is still being applied."
fi

echo "accessibility classes linked in: ok"

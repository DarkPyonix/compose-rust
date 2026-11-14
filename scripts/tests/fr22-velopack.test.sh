#!/usr/bin/env bash
# Usage: ./scripts/tests/fr22-velopack.test.sh
#
# Velopack packaging and feed generation tests.
#
# Tests that:
# 1. Velopack packager CLI builds full/delta nupkg and valid releases.{channel}.json feed.
# 2. Ad-hoc signed .app is re-signed after vpk pack embeds UpdateMac.
# 3. .dmg disk image carries Gatekeeper install instructions and signed .app.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

failures=0
fail() {
    failures=$((failures + 1))
    printf 'FAIL  %s\n' "$1" >&2
    shift
    local line
    for line in "$@"; do printf '        %s\n' "$line" >&2; done
}

pass() {
    printf 'ok    %s\n' "$1"
}

fr22_6_7_velopack_feed_and_packages_generate_full_and_delta() {
    # Runs the Python unittest suite covering metadata, dry-run commands, delta copy, and feed verification.
    python3 -m unittest scripts/packager-macos/tests/test_velopack.py >/dev/null 2>&1 || {
        fail "fr22_6_7_velopack_feed_and_packages_generate_full_and_delta" \
            "scripts/packager-macos/tests/test_velopack.py failed"
        return 1
    }
    pass "fr22_6_7_velopack_feed_and_packages_generate_full_and_delta"
}

fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches() {
    if ! command -v vpk >/dev/null 2>&1; then
        echo "note  fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches requires vpk (CI runner)"
        return 0
    fi
    if [[ "$(uname -s)" != "Darwin" ]]; then
        echo "note  fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches requires macOS"
        return 0
    fi

    local tmp
    tmp="$(mktemp -d -t fr22-velo-XXXXXX)"
    trap 'rm -rf "$tmp"' RETURN

    local app_dir="$tmp/fixture-app"
    local rel1="$tmp/releases-v1"
    local rel2="$tmp/releases-v2"
    mkdir -p "$app_dir" "$rel1" "$rel2"

    cp "$repo_root/samples/calculator/assets/icon.png" "$app_dir/icon.png"

    cat << 'EOF' > "$app_dir/Dioxus.toml"
[application]
name = "fixture-app"
default_platform = "desktop"

[bundle]
identifier = "dev.darkpyonix.fixtureapp"
publisher = "DarkPyonix"
icon = ["icon.png"]
EOF

    cat << 'EOF' > "$app_dir/Cargo.toml"
[package]
name = "fixture-app"
version = "1.0.0"
EOF

    # Compile v1.0.0 executable
    cc -o "$app_dir/fixture-app" -Wl,-headerpad_max_install_names -x c - << 'EOF'
#include <stdio.h>
int main(void) {
    printf("1.0.0\n");
    return 0;
}
EOF

    # Package v1.0.0
    python3 "$repo_root/scripts/package-velopack.py" \
        --project-dir "$app_dir" \
        --executable "$app_dir/fixture-app" \
        --pack-version 1.0.0 \
        --output-dir "$rel1" \
        --channel osx \
        --target-os osx >/dev/null 2>&1 || {
        fail "fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches" \
            "package-velopack.py failed for v1.0.0"
        return 1
    }

    local dmg
    dmg="$(find "$rel1" -name "*1.0.0*.dmg" | head -n 1)"
    if [[ -z "$dmg" || ! -f "$dmg" ]]; then
        fail "fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches" \
            "v1.0.0 .dmg was not generated"
        return 1
    fi

    # Compile v1.0.1 executable
    cc -o "$app_dir/fixture-app" -Wl,-headerpad_max_install_names -x c - << 'EOF'
#include <stdio.h>
int main(void) {
    printf("1.0.1\n");
    return 0;
}
EOF

    # Package v1.0.1 with delta
    python3 "$repo_root/scripts/package-velopack.py" \
        --project-dir "$app_dir" \
        --executable "$app_dir/fixture-app" \
        --pack-version 1.0.1 \
        --delta-dir "$rel1" \
        --output-dir "$rel2" \
        --channel osx \
        --target-os osx >/dev/null 2>&1 || {
        fail "fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches" \
            "package-velopack.py failed for v1.0.1"
        return 1
    }

    local delta_pkg
    delta_pkg="$(find "$rel2" -name "*1.0.1*-delta.nupkg" | head -n 1)"
    if [[ -z "$delta_pkg" || ! -f "$delta_pkg" ]]; then
        fail "fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches" \
            "v1.0.1 delta nupkg was not generated"
        return 1
    fi

    # Mount v1.0.0 DMG and install
    local mount_point="$tmp/mount"
    mkdir -p "$mount_point"
    hdiutil attach "$dmg" -mountpoint "$mount_point" -nobrowse -quiet
    if [[ ! -f "$mount_point/If macOS will not open the app.txt" ]]; then
        hdiutil detach "$mount_point" -quiet || true
        fail "fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches" \
            "DMG is missing Gatekeeper instructions (If macOS will not open the app.txt)"
        return 1
    fi

    local installed_app="$tmp/Installed.app"
    local app_source
    app_source="$(find "$mount_point" -name "*.app" -maxdepth 1 | head -n 1)"
    cp -R "$app_source" "$installed_app"
    hdiutil detach "$mount_point" -quiet

    # Set quarantine attribute (simulating Gatekeeper download)
    xattr -w com.apple.quarantine "0081;66000000;Safari;00000000-0000-0000-0000-000000000000" "$installed_app"

    # Verify v1.0.0 runs
    local v1_out
    v1_out="$("$installed_app/Contents/MacOS/fixture-app")"
    if [[ "$v1_out" != "1.0.0" ]]; then
        fail "fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches" \
            "Installed v1.0.0 app returned unexpected output: $v1_out"
        return 1
    fi

    # Verify codesign before update
    if ! codesign --verify --deep --strict "$installed_app" >/dev/null 2>&1; then
        fail "fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches" \
            "Installed app failed codesign verification before update"
        return 1
    fi

    # Apply Velopack delta update
    "$installed_app/Contents/MacOS/UpdateMac" apply --package "$delta_pkg" --norestart --verbose >/dev/null 2>&1 || {
        fail "fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches" \
            "UpdateMac apply failed"
        return 1
    }

    # Verify codesign after update
    if ! codesign --verify --deep --strict "$installed_app" >/dev/null 2>&1; then
        fail "fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches" \
            "App failed codesign verification after Velopack update"
        return 1
    fi

    # Verify updated app runs v1.0.1
    local v2_out
    v2_out="$("$installed_app/Contents/MacOS/fixture-app")"
    if [[ "$v2_out" != "1.0.1" ]]; then
        fail "fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches" \
            "Updated app returned unexpected output: $v2_out (expected 1.0.1)"
        return 1
    fi

    pass "fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches"
}

fr22_6_7_velopack_feed_and_packages_generate_full_and_delta
fr22_6_12_adhoc_signed_app_receives_velopack_update_and_launches

if [[ "$failures" -ne 0 ]]; then
    exit 1
fi

#!/usr/bin/env bash
# .github/scripts/schema-renderer.sh: the names of the renderers published by schema hash,
# when there is nothing to publish, which ones the release keeps, what happens when the
# renderer-dev release does not exist, and when a release reuses them instead of building.
#
# A fake gh stands in for GitHub, and records every call, so nothing here touches the
# network and the tests can say what was never asked for (a tag, a release).
set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/.github/scripts/schema-renderer.sh"
work="$(mktemp -d "${TMPDIR:-/tmp}/schema-renderer.XXXXXX")"
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/bin"
cat > "$work/bin/gh" <<'GH'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_LOG"
echo "$*" >> "$FAKE_ALL_LOG"
case "$1 $2" in
    "release view")
        if [[ -n "${FAKE_NO_RELEASE:-}" ]]; then echo "release not found" >&2; exit 1; fi
        if [[ "$*" == *"--json assets"* ]]; then cat "$FAKE_ASSETS"; fi
        exit 0 ;;
    "release download")
        # gh release download <tag> --pattern <name> --dir <dir>
        name="$5"; dir="$7"
        [[ -f "$FAKE_SERVED/$name" ]] || { echo "no assets match the file pattern" >&2; exit 1; }
        mkdir -p "$dir" && cp "$FAKE_SERVED/$name" "$dir/$name" ;;
    "release upload"|"release delete-asset") exit 0 ;;
    *) echo "the fake gh does not know '$*'" >&2; exit 1 ;;
esac
GH
chmod +x "$work/bin/gh"
export GH="$work/bin/gh"
export FAKE_LOG="$work/gh.log"
# Every call of every test, never cleared, for the check at the end.
export FAKE_ALL_LOG="$work/gh-all.log"
: > "$FAKE_ALL_LOG"

failures=0
ok() { echo "ok   - $1"; }
bad() { echo "FAIL - $1"; failures=$((failures + 1)); }
expect() { # description, then the command; passes when it succeeds
    local what="$1"
    shift
    if "$@"; then ok "$what"; else bad "$what"; fi
}

sha() {
    if command -v shasum >/dev/null; then shasum -a 256 "$1" | cut -d' ' -f1; else sha256sum "$1" | cut -d' ' -f1; fi
}

hash_n() { printf '%016x' "$((0x100 + $1))"; }

targets=(macos-aarch64 linux-x64 linux-arm64 windows-x64 ios)

nfr16_publish_asset_names() {
    local got
    got="$("$script" asset-name 0x62b9a55ed371acad macos-aarch64)"
    expect "asset name from a 0x hash" \
        test "$got" = compose-rust-renderer-schema-62b9a55ed371acad-macos-aarch64.tar.gz
    got="$("$script" asset-name 62B9A55ED371ACAD macos-aarch64)"
    expect "any spelling of the hash names the same asset" \
        test "$got" = compose-rust-renderer-schema-62b9a55ed371acad-macos-aarch64.tar.gz
    expect "the five targets a release carries" \
        test "$("$script" targets | tr '\n' ' ')" = "macos-aarch64 linux-x64 linux-arm64 windows-x64 ios "
    expect "something that is not a hash is refused" \
        bash -c "! '$script' asset-name not-a-hash linux-x64 2>/dev/null"
}

# The iOS bundle the release carries and the one develop publishes come from the same
# script. A fake xcodebuild makes the bundle directory, which is all the script needs of it.
nfr16_ios_package_names_the_release_asset() {
    local build="$work/ios-build" out="$work/ios-out" slice
    for slice in device simulator; do
        mkdir -p "$build/$slice"
        echo archive > "$build/$slice/libcompose_rust_renderer.a"
        echo "// header" > "$build/$slice/libcompose_rust_renderer_api.h"
    done
    mkdir -p "$work/ios-bin"
    cat > "$work/ios-bin/xcodebuild" <<'XC'
#!/usr/bin/env bash
while [[ $# -gt 0 ]]; do
    if [[ "$1" == -output ]]; then mkdir -p "$2" && echo framework > "$2/Info.plist"; fi
    shift
done
XC
    chmod +x "$work/ios-bin/xcodebuild"
    PATH="$work/ios-bin:$PATH" "$repo_root/.github/scripts/package-ios-renderer.sh" \
        compose-rust-renderer-v0.0.13-ios "$build" "$out" >/dev/null
    local tarball="$out/compose-rust-renderer-v0.0.13-ios.tar.gz"
    expect "the iOS bundle carries the release asset's name" test -f "$tarball"
    expect "its .sha256 matches it" \
        test "$(cut -d' ' -f1 < "$tarball.sha256")" = "$(sha "$tarball")"
    expect "it holds the XCFramework under the same name" \
        bash -c "tar -tzf '$tarball' | grep -q '^compose-rust-renderer-v0.0.13-ios.xcframework/'"
    expect "a missing slice is refused" \
        bash -c "rm '$build/device/libcompose_rust_renderer.a'; ! PATH='$work/ios-bin:$PATH' '$repo_root/.github/scripts/package-ios-renderer.sh' x '$build' '$work/ios-out-2' 2>/dev/null"
}

nfr16_publish_nothing_when_already_published() {
    local assets="$work/plan-assets" hash=62b9a55ed371acad target
    : > "$assets"
    for target in "${targets[@]}"; do
        echo "compose-rust-renderer-schema-$hash-$target.tar.gz" >> "$assets"
        echo "compose-rust-renderer-schema-$hash-$target.tar.gz.sha256" >> "$assets"
    done
    echo "compose-rust-renderer-schema-$hash.source" >> "$assets"
    expect "every asset there: nothing to publish" \
        test "$("$script" plan "0x$hash" "$assets" 2>/dev/null)" = publish=false
    grep -v -- "-ios.tar.gz.sha256" "$assets" > "$assets.partial"
    expect "one asset missing: publish" \
        test "$("$script" plan "0x$hash" "$assets.partial" 2>/dev/null)" = publish=true
    expect "another schema's assets do not count" \
        test "$("$script" plan 0x2106ffd038ad189b "$assets" 2>/dev/null)" = publish=true
}

nfr16_publish_renames_only_what_matches_its_checksum() {
    local in="$work/rename-in" out="$work/rename-out"
    mkdir -p "$in"
    echo "renderer" > "$in/compose-rust-renderer-v0.0.13-linux-x64.tar.gz"
    (cd "$in" && printf '%s  %s\n' "$(sha compose-rust-renderer-v0.0.13-linux-x64.tar.gz)" \
        compose-rust-renderer-v0.0.13-linux-x64.tar.gz > compose-rust-renderer-v0.0.13-linux-x64.tar.gz.sha256)
    "$script" rename 0x62b9a55ed371acad "$in" "$out" >/dev/null
    local renamed="$out/compose-rust-renderer-schema-62b9a55ed371acad-linux-x64.tar.gz"
    expect "the version artifact is published under its schema name" test -f "$renamed"
    expect "its .sha256 names it and matches it" \
        test "$(cat "$renamed.sha256")" = "$(sha "$renamed")  $(basename "$renamed")"
    echo "changed" >> "$in/compose-rust-renderer-v0.0.13-linux-x64.tar.gz"
    expect "an artifact that does not match its checksum is not published" \
        bash -c "! '$script' rename 0x62b9a55ed371acad '$in' '$work/rename-refused' 2>/dev/null"
    expect "and nothing of it is written" \
        bash -c "! ls '$work/rename-refused'/*.tar.gz >/dev/null 2>&1"
}

nfr16_prune_keeps_recent_and_released() {
    local assets="$work/prune-assets" released="$work/prune-released" n hash
    : > "$assets"
    # 25 hashes, published one a day: hash 1 is the oldest, hash 25 the newest.
    for n in $(seq 1 25); do
        hash="$(hash_n "$n")"
        printf 'compose-rust-renderer-schema-%s-linux-x64.tar.gz\t2026-10-%02dT10:00:00Z\n' "$hash" "$n" >> "$assets"
        printf 'compose-rust-renderer-schema-%s-linux-x64.tar.gz.sha256\t2026-10-%02dT10:00:01Z\n' "$hash" "$n" >> "$assets"
        printf 'compose-rust-renderer-schema-%s.source\t2026-10-%02dT10:00:02Z\n' "$hash" "$n" >> "$assets"
    done
    printf 'notes.txt\t2026-09-01T00:00:00Z\n' >> "$assets"
    # Two released versions: one of an old hash, one of a hash that is among the newest 20,
    # which is kept once and does not push another out. Spelled as schema-hash.txt has them.
    printf '0x%s\n0x%s\n' "$(hash_n 2)" "$(hash_n 24)" > "$released"

    local deleted
    deleted="$("$script" prune 20 "$assets" "$released")"
    local expected=""
    for n in 1 3 4 5; do
        hash="$(hash_n "$n")"
        expected+="compose-rust-renderer-schema-$hash-linux-x64.tar.gz"$'\n'
        expected+="compose-rust-renderer-schema-$hash-linux-x64.tar.gz.sha256"$'\n'
        expected+="compose-rust-renderer-schema-$hash.source"$'\n'
    done
    expect "the newest 20 hashes and both released hashes stay, the rest go" \
        test "$(sort <<< "$deleted")" = "$(sort <<< "${expected%$'\n'}")"
    expect "an asset that is not a schema renderer is never pruned" \
        bash -c "! grep -q notes.txt <<< '$deleted'"
}

nfr16_publish_skips_without_release() {
    local dir="$work/upload-none" out
    mkdir -p "$dir"
    echo x > "$dir/compose-rust-renderer-schema-62b9a55ed371acad-linux-x64.tar.gz"
    : > "$work/released"
    : > "$FAKE_LOG"
    out="$(FAKE_NO_RELEASE=1 "$script" upload "$dir" "$work/released" 2>&1)"
    expect "no renderer-dev release: the upload ends with 0" test $? -eq 0
    expect "and says so" grep -q "::notice::" <<< "$out"
    expect "and asks GitHub for nothing but whether the release exists" \
        test "$(grep -cv "^release view renderer-dev" "$FAKE_LOG")" -eq 0
}

nfr16_publish_uploads_with_clobber_and_prunes() {
    local dir="$work/upload-some" n hash
    mkdir -p "$dir"
    echo x > "$dir/compose-rust-renderer-schema-$(hash_n 30)-linux-x64.tar.gz"
    export FAKE_ASSETS="$work/upload-assets"
    : > "$FAKE_ASSETS"
    for n in $(seq 1 22); do
        hash="$(hash_n "$n")"
        printf 'compose-rust-renderer-schema-%s-linux-x64.tar.gz\t2026-10-%02dT10:00:00Z\n' "$hash" "$n" >> "$FAKE_ASSETS"
    done
    : > "$work/released"
    : > "$FAKE_LOG"
    "$script" upload "$dir" "$work/released" >/dev/null 2>&1
    expect "the upload replaces what is there" grep -q "^release upload renderer-dev .* --clobber$" "$FAKE_LOG"
    expect "the two oldest of 22 hashes are deleted" \
        test "$(grep -c "^release delete-asset renderer-dev" "$FAKE_LOG")" -eq 2
    expect "no tag or release is created, moved or deleted" \
        bash -c "! grep -qE '^(release (create|delete|edit) |tag|api)' '$FAKE_LOG'"
}

# Deleting assets is allowed on renderer-dev and nowhere else. Asked to work on another
# release, the script refuses before it calls GitHub at all.
nfr16_prune_only_on_renderer_dev() {
    local dir="$work/upload-elsewhere" out status
    mkdir -p "$dir"
    echo x > "$dir/compose-rust-renderer-schema-62b9a55ed371acad-linux-x64.tar.gz"
    : > "$work/released"
    local release
    for release in v0.0.13 renderer-dev2 ""; do
        : > "$FAKE_LOG"
        out="$(SCHEMA_RENDERER_RELEASE="$release" "$script" upload "$dir" "$work/released" 2>&1)"
        status=$?
        expect "upload refuses the release '$release'" test "$status" -ne 0
        expect "and says which release it works on" grep -q "renderer-dev" <<< "$out"
        expect "and calls GitHub for nothing" test ! -s "$FAKE_LOG"
        out="$(SCHEMA_RENDERER_RELEASE="$release" "$script" promote 0x62b9a55ed371acad v0.0.13 tree "$work/promoted-elsewhere" 2>&1)"
        expect "promote refuses the release '$release' too" test $? -ne 0
    done
}

# The workflow runs when the leader dispatches it, directly or as the publish_schema_renderer
# suite of ci.yml, and on nothing else.
nfr16_publish_runs_only_when_dispatched() {
    local triggers
    triggers="$(awk '/^on:/ { inside = 1; next } inside && /^[^ #]/ { exit } inside && /^  [a-z_]+:/ { sub(/:.*/, ""); sub(/^ +/, ""); print }' \
        "$repo_root/.github/workflows/publish-schema-renderer.yml" | tr '\n' ' ')"
    expect "Publish schema renderer has workflow_dispatch and workflow_call and no other trigger" \
        test "$triggers" = "workflow_dispatch workflow_call "
    expect "ci.yml calls it only on its plan's publish_schema_renderer output" \
        python3 -I -c 'import sys; sys.exit(sys.argv[2] not in open(sys.argv[1]).read())' \
        "$repo_root/.github/workflows/ci.yml" "  publish_schema_renderer:
    if: needs.plan.outputs.publish_schema_renderer == '"'true'"'
    needs: plan
    uses: ./.github/workflows/publish-schema-renderer.yml
"
}

# Over every call any test above made: none created, edited or deleted a tag or a release.
nfr16_no_tag_or_release_is_ever_changed() {
    expect "the fake gh recorded calls at all" test -s "$FAKE_ALL_LOG"
    expect "no tag or release was created, edited or deleted" \
        bash -c "! grep -qE '^(release (create|delete|edit) |tag |api )' '$FAKE_ALL_LOG'"
}

nfr16_promote_without_build() {
    local hash=62b9a55ed371acad target
    export FAKE_SERVED="$work/served"
    mkdir -p "$FAKE_SERVED"
    for target in "${targets[@]}"; do
        local name="compose-rust-renderer-schema-$hash-$target.tar.gz"
        echo "renderer for $target" > "$FAKE_SERVED/$name"
        (cd "$FAKE_SERVED" && printf '%s  %s\n' "$(sha "$name")" "$name" > "$name.sha256")
    done
    printf 'commit abc\nrenderer-tree tree-1\n' > "$FAKE_SERVED/compose-rust-renderer-schema-$hash.source"

    local out="$work/promoted" result
    result="$("$script" promote "0x$hash" v0.0.13 tree-1 "$out" 2>/dev/null)"
    expect "every renderer there and right: promoted" test "$result" = promoted=true
    for target in "${targets[@]}"; do
        local file="$out/compose-rust-renderer-v0.0.13-$target.tar.gz"
        expect "$target carries the release's name" test -f "$file"
        expect "$target's .sha256 names it and matches it" \
            test "$(cat "$file.sha256")" = "$(sha "$file")  $(basename "$file")"
    done

    result="$("$script" promote "0x$hash" v0.0.13 tree-2 "$work/promoted-other-tree" 2>/dev/null)"
    expect "built from another renderer tree: the release builds" test "$result" = promoted=false
    expect "and nothing is written" test ! -e "$work/promoted-other-tree"

    echo "changed" >> "$FAKE_SERVED/compose-rust-renderer-schema-$hash-windows-x64.tar.gz"
    result="$("$script" promote "0x$hash" v0.0.13 tree-1 "$work/promoted-bad-sha" 2>/dev/null)"
    expect "a checksum that does not match: the release builds" test "$result" = promoted=false
    expect "and nothing is written" test ! -e "$work/promoted-bad-sha"

    rm "$FAKE_SERVED/compose-rust-renderer-schema-$hash-ios.tar.gz"
    result="$("$script" promote "0x$hash" v0.0.13 tree-1 "$work/promoted-missing" 2>/dev/null)"
    expect "a renderer missing: the release builds" test "$result" = promoted=false
    expect "and nothing is written" test ! -e "$work/promoted-missing"
}

nfr16_publish_asset_names
nfr16_ios_package_names_the_release_asset
nfr16_publish_nothing_when_already_published
nfr16_publish_renames_only_what_matches_its_checksum
nfr16_prune_keeps_recent_and_released
nfr16_publish_skips_without_release
nfr16_publish_uploads_with_clobber_and_prunes
nfr16_promote_without_build
nfr16_prune_only_on_renderer_dev
nfr16_publish_runs_only_when_dispatched
nfr16_no_tag_or_release_is_ever_changed

[[ "$failures" -eq 0 ]] || exit 1
echo "all passed"

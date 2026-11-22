#!/usr/bin/env bash
# The renderers published by schema hash, on the one release that holds them.
#
# A crate version stays the same between two releases while its schema moves on, so a git
# dependency on a revision after a release gets a crate whose version names a renderer of
# another schema. The crate's build script then looks for the renderer published for its
# schema, on the release tagged renderer-dev, under these names:
#
#   compose-rust-renderer-schema-<hash>-<target>.tar.gz and .sha256
#
# <hash> is the value of compose-rust/schema-hash.txt without 0x, in sixteen lowercase hex
# digits. The contents are exactly the version artifact's. Beside them,
#
#   compose-rust-renderer-schema-<hash>.source
#
# says which commit they were built from and the git tree of renderer/ at that commit, which
# is what a release checks before it reuses them instead of building its own.
#
# The renderer-dev tag and release exist already (the owner made them once). Nothing here
# creates, moves or deletes a tag or a release: it uploads, replaces and deletes assets on
# that one release, and when the release is not there it says so and does nothing.
#
# Usage:
#   schema-renderer.sh asset-name <hash> <target>
#   schema-renderer.sh targets
#   schema-renderer.sh rename <hash> <in-dir> <out-dir>
#       Copy each compose-rust-renderer-v<version>-<target>.tar.gz in <in-dir>, after checking
#       it against its .sha256, to <out-dir> under its schema name with a .sha256 to match.
#   schema-renderer.sh source <hash> <commit> <renderer-tree> <out-dir>
#   schema-renderer.sh plan <hash> <assets-file>
#       Prints publish=false when every asset for <hash> is already in <assets-file> (one
#       asset name per line, as the release lists them), publish=true otherwise.
#   schema-renderer.sh prune <keep> <assets-file> <released-hashes-file>
#       <assets-file> holds "<name><TAB><created at>" lines. Prints the schema assets to
#       delete: everything but the <keep> most recently published hashes and the hash of
#       each released version.
#   schema-renderer.sh upload <dir> <released-hashes-file>
#       Uploads every file in <dir> to renderer-dev with --clobber, then prunes.
#   schema-renderer.sh promote <hash> <version> <renderer-tree> <out-dir>
#       Downloads the renderers for <hash> from renderer-dev, checks each against its
#       .sha256 and the .source against <renderer-tree>, and writes them to <out-dir> under
#       the names the release for <version> carries. Prints promoted=true when every one
#       was there and right, promoted=false (and writes nothing) otherwise.
#
# SCHEMA_RENDERER_RELEASE, when set, has to be renderer-dev; upload and promote refuse any
# other value. GH names the gh executable (default gh), so the tests can stand a fake in for GitHub.
set -euo pipefail

# The one release this script may change. SCHEMA_RENDERER_RELEASE can name another only to be
# refused: deleting assets is allowed on renderer-dev alone, so asking for any other release
# stops the script before it calls GitHub.
RELEASE=renderer-dev
REQUESTED_RELEASE="${SCHEMA_RENDERER_RELEASE-$RELEASE}"
KEEP_RECENT=20
TARGETS=(macos-aarch64 linux-x64 linux-arm64 windows-x64 ios)
GH="${GH:-gh}"

fail() {
    echo "error: $1" >&2
    exit 1
}

# The hash as file names spell it.
digits() {
    local hash="${1//[[:space:]]/}"
    hash="${hash#0x}"
    hash="${hash#0X}"
    hash="$(tr '[:upper:]' '[:lower:]' <<< "$hash")"
    [[ "$hash" =~ ^[0-9a-f]{1,16}$ ]] || fail "'$1' is not a schema hash (sixteen hex digits, with or without 0x)"
    printf '%16s' "$hash" | tr ' ' 0
}

asset_name() { # hash target
    local hash
    hash="$(digits "$1")" || exit 1
    echo "compose-rust-renderer-schema-$hash-$2.tar.gz"
}

source_name() { # hash
    local hash
    hash="$(digits "$1")" || exit 1
    echo "compose-rust-renderer-schema-$hash.source"
}

sha256() {
    if command -v shasum >/dev/null; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        sha256sum "$1" | cut -d' ' -f1
    fi
}

# Whether <file> hashes to what <file>.sha256 says. The name in the .sha256 is not read: it
# is whatever the packaging job had on its command line.
checksum_matches() {
    [[ -f "$1" && -f "$1.sha256" ]] || return 1
    local expected
    expected="$(cut -d' ' -f1 < "$1.sha256" | tr '[:upper:]' '[:lower:]')"
    [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || return 1
    [[ "$(sha256 "$1")" == "$expected" ]]
}

# <from> copied to <to-dir>/<to-name>, with a .sha256 that names it.
copy_as() { # from to-dir to-name
    cp "$1" "$2/$3"
    (cd "$2" && printf '%s  %s\n' "$(sha256 "$3")" "$3" > "$3.sha256")
}

all_assets() { # hash
    local target
    for target in "${TARGETS[@]}"; do
        asset_name "$1" "$target"
        echo "$(asset_name "$1" "$target").sha256"
    done
    source_name "$1"
}

cmd_rename() { # hash in out
    local hash="$1" in="$2" out="$3" file base target found=0
    mkdir -p "$out"
    for file in "$in"/compose-rust-renderer-v*.tar.gz; do
        [[ -f "$file" ]] || continue
        base="$(basename "$file" .tar.gz)"
        # The target is the published one the name ends with; a version can carry dashes
        # of its own (0.1.0-rc.1), so it is not split on them.
        target=""
        local candidate
        for candidate in "${TARGETS[@]}"; do
            [[ "$base" == compose-rust-renderer-v*-"$candidate" ]] && target="$candidate"
        done
        [[ -n "$target" ]] || fail "$file is not for a published target (${TARGETS[*]})"
        checksum_matches "$file" || fail "$file does not match $file.sha256, so it is not published"
        copy_as "$file" "$out" "$(asset_name "$hash" "$target")"
        found=$((found + 1))
    done
    [[ "$found" -gt 0 ]] || fail "no compose-rust-renderer-v*.tar.gz in $in"
}

cmd_source() { # hash commit tree out
    mkdir -p "$4"
    printf 'commit %s\nrenderer-tree %s\n' "$2" "$3" > "$4/$(source_name "$1")"
}

cmd_plan() { # hash assets-file
    local name
    while read -r name; do
        if ! grep -qxF "$name" "$2"; then
            echo "publish=true"
            return 0
        fi
    done < <(all_assets "$1")
    echo "::notice::every renderer for schema 0x$(digits "$1") is already on $RELEASE; nothing to publish" >&2
    echo "publish=false"
}

cmd_prune() { # keep assets-file released-hashes-file
    local keep="$1" assets="$2" released="$3"
    [[ "$keep" =~ ^[0-9]+$ ]] || fail "'$keep' is not a count"
    local name created hash stamped=""
    while IFS=$'\t' read -r name created; do
        hash="$(schema_of "$name")" || continue
        stamped+="$created $hash"$'\n'
    done < "$assets"
    # The hashes in order of their newest asset, newest first, the first <keep> of them.
    # The timestamps are ISO 8601, which sort as text.
    local kept
    kept="$(printf '%s' "$stamped" | sort -r |
        awk -v keep="$keep" '!seen[$2]++ && n < keep { print $2; n++ }')"
    # And each released version's, in the spelling file names use.
    local line
    while read -r line; do
        [[ -n "${line//[[:space:]]/}" ]] || continue
        # A tag whose schema-hash.txt cannot be read keeps nothing, and is said so; it does
        # not stop the upload that has already happened.
        if ! hash="$(digits "$line" 2>/dev/null)"; then
            echo "::warning::a released version's schema hash '$line' is not a hash; it keeps nothing on $RELEASE" >&2
            continue
        fi
        kept+=$'\n'"$hash"
    done < "$released"
    while IFS=$'\t' read -r name _; do
        hash="$(schema_of "$name")" || continue
        grep -qxF "$hash" <<< "$kept" || echo "$name"
    done < "$assets"
}

# The hash a schema asset is named after. Fails for any other asset, which is never pruned.
schema_of() { # name
    [[ "$1" =~ ^compose-rust-renderer-schema-([0-9a-f]{16})[-.] ]] || return 1
    echo "${BASH_REMATCH[1]}"
}

# Whether renderer-dev exists. A missing release is a notice and not a failure, because the
# owner makes it once and nothing here may create one; any other answer from gh is a failure.
release_exists() {
    local err
    if err="$("$GH" release view "$RELEASE" --json name 2>&1 >/dev/null)"; then
        return 0
    fi
    if grep -qi "not found" <<< "$err"; then
        return 1
    fi
    fail "gh could not read the $RELEASE release: $err"
}

# Refuse to work on any release but renderer-dev, before anything is asked of GitHub.
only_renderer_dev() {
    [[ "$REQUESTED_RELEASE" == "$RELEASE" ]] ||
        fail "this script uploads to and prunes only the $RELEASE release, and '$REQUESTED_RELEASE' was asked for. Version releases are attached by test-native-renderer.yml and are never pruned; unset SCHEMA_RENDERER_RELEASE."
}

cmd_upload() { # dir released-hashes-file
    local dir="$1" released="$2"
    only_renderer_dev
    if ! release_exists; then
        echo "::notice::the $RELEASE release does not exist, so nothing was uploaded. The owner creates it once; this workflow never creates a tag or a release."
        return 0
    fi
    local files=("$dir"/*)
    [[ -e "${files[0]}" ]] || fail "nothing to upload in $dir"
    "$GH" release upload "$RELEASE" "${files[@]}" --clobber
    local listing
    listing="$(mktemp)"
    "$GH" release view "$RELEASE" --json assets --jq '.assets[] | [.name, .createdAt] | @tsv' > "$listing"
    local name
    while read -r name; do
        [[ -n "$name" ]] || continue
        echo "removing $name"
        "$GH" release delete-asset "$RELEASE" "$name" --yes
    done < <(cmd_prune "$KEEP_RECENT" "$listing" "$released")
    rm -f "$listing"
}

cmd_promote() { # hash version tree out
    local hash="$1" version="${2#v}" tree="$3" out="$4"
    only_renderer_dev
    local work
    work="$(mktemp -d)"
    # Nothing is written to <out> until every renderer has been checked, so a release that
    # falls back to building does not also carry half of these.
    if ! promote_into "$hash" "$tree" "$work"; then
        rm -rf "$work"
        echo "promoted=false"
        return 0
    fi
    mkdir -p "$out"
    local target
    for target in "${TARGETS[@]}"; do
        copy_as "$work/$(asset_name "$hash" "$target")" "$out" "compose-rust-renderer-v$version-$target.tar.gz"
    done
    rm -rf "$work"
    echo "promoted=true"
}

promote_into() { # hash tree work
    local hash="$1" tree="$2" work="$3" name
    while read -r name; do
        if ! "$GH" release download "$RELEASE" --pattern "$name" --dir "$work" >/dev/null 2>&1 ||
            [[ ! -f "$work/$name" ]]; then
            echo "::notice::$RELEASE has no $name, so the release builds its renderers" >&2
            return 1
        fi
    done < <(all_assets "$hash")
    if ! grep -qxF "renderer-tree $tree" "$work/$(source_name "$hash")"; then
        echo "::notice::the renderers on $RELEASE for schema 0x$(digits "$hash") were built from another renderer/ tree than this commit's ($tree), so the release builds its own" >&2
        return 1
    fi
    local target
    for target in "${TARGETS[@]}"; do
        name="$(asset_name "$hash" "$target")"
        if ! checksum_matches "$work/$name"; then
            echo "::notice::$name on $RELEASE does not match its .sha256, so the release builds its renderers" >&2
            return 1
        fi
    done
}

[[ $# -ge 1 ]] || fail "usage: $0 asset-name|targets|rename|source|plan|prune|upload|promote ..."
command="$1"
shift
case "$command" in
    asset-name) [[ $# -eq 2 ]] || fail "usage: $0 asset-name <hash> <target>"; asset_name "$@" ;;
    targets) printf '%s\n' "${TARGETS[@]}" ;;
    rename) [[ $# -eq 3 ]] || fail "usage: $0 rename <hash> <in-dir> <out-dir>"; cmd_rename "$@" ;;
    source) [[ $# -eq 4 ]] || fail "usage: $0 source <hash> <commit> <renderer-tree> <out-dir>"; cmd_source "$@" ;;
    plan) [[ $# -eq 2 ]] || fail "usage: $0 plan <hash> <assets-file>"; cmd_plan "$@" ;;
    prune) [[ $# -eq 3 ]] || fail "usage: $0 prune <keep> <assets-file> <released-hashes-file>"; cmd_prune "$@" ;;
    upload) [[ $# -eq 2 ]] || fail "usage: $0 upload <dir> <released-hashes-file>"; cmd_upload "$@" ;;
    promote) [[ $# -eq 4 ]] || fail "usage: $0 promote <hash> <version> <renderer-tree> <out-dir>"; cmd_promote "$@" ;;
    *) fail "unknown command '$command'" ;;
esac

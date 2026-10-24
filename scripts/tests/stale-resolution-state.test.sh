#!/usr/bin/env bash
# When the Kotlin toolchain resolves a coordinate only the Compose fork publishes before the fork
# has published it, the resolution fails with "Unable to download checksums of file
# foundation-1.11.1-ext.1.pom" and the toolchain keeps that result in the renderer's
# build/incremental.state. The entry is keyed on the coordinates and repositories, not on what
# ~/.m2 holds, so every later build replays the failure, even once ~/.m2 has the files.
#
# require-fork-artifacts.sh runs before the toolchain. This checks that, once every fork artifact
# is in place, it removes the state files that record a failure to resolve a fork version and
# names each one, and that it leaves alone:
#
#   a state file with no such failure
#   a state file whose failure names some other version
#   everything, while the artifacts are still missing (the build stops there anyway)
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "a cached failure to resolve a fork version is removed once the fork has published it"

mkdir -p "$repo/.scratch"
work="$(mktemp -d "$repo/.scratch/stale-resolution-state-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

compose_script="$repo/renderer/scripts/build-compose.sh"
fork_as="$(sed -n 's/^COMPOSE_AS="\([^"]*\)"$/\1/p' "$compose_script")"
material3_as="$(sed -n 's/^MATERIAL3_AS="\([^"]*\)"$/\1/p' "$compose_script")"
[[ -n "$fork_as" && -n "$material3_as" ]] || { fail "build-compose.sh names no COMPOSE_AS or MATERIAL3_AS"; exit 1; }

tree="$work/tree"
mkdir -p "$tree/renderer/scripts" "$tree/renderer/macos"
cp "$repo"/renderer/scripts/*.sh "$tree/renderer/scripts/"
cp "$repo/renderer/macos/module.yaml" "$tree/renderer/macos/"

# What the toolchain writes for a resolution that failed: its serialized output, holding the
# diagnostic, as an escaped string inside the state file's JSON.
failure_state() {
    local type="$1" file_name="$2"
    printf '{\n    "codeVersion": "0.13.0-dev-4399",\n    "inputValues": {},\n    "inputFiles": [],\n'
    printf '    "serializedOutput": "{\\n  \\"diagnostics\\": [\\n    {\\n      \\"type\\": \\"org.jetbrains.amper.dependency.resolution.diagnostics.%s\\",\\n      \\"fileName\\": \\"%s\\",\\n      \\"severity\\": \\"ERROR\\"\\n    }\\n  ]\\n}"\n}\n' \
        "$type" "$file_name"
}

states="$tree/renderer/build/incremental.state"
plant_states() {
    rm -rf "$states"
    mkdir -p "$states"
    failure_state UnableToDownloadChecksums "foundation-$fork_as.pom" > "$states/stale-checksums"
    failure_state UnableToResolveDependency "ui-$fork_as.module" > "$states/stale-resolve"
    failure_state UnableToDownloadFile "kotlin-stdlib-0.0.1.pom" > "$states/other-version"
    printf '{\n    "inputValues": {"dependencies": "org.thisisthepy.compose.ui:ui:%s"},\n    "serializedOutput": "{}"\n}\n' "$fork_as" > "$states/resolved"
}

# A ~/.m2 holding every file the macos module names at the fork's versions.
home_complete="$work/home-complete"
while read -r group artifact version; do
    for name in "$artifact" "$artifact-macosarm64"; do
        directory="$home_complete/.m2/repository/$(tr . / <<< "$group")/$name/$version"
        mkdir -p "$directory"
        for extension in pom module; do echo stand-in > "$directory/$name-$version.$extension"; done
    done
done < <(sed -En "s/^[[:space:]]*-[[:space:]]*(org\.thisisthepy\.compose\.(ui|foundation|material3|runtime)[A-Za-z0-9.]*):([A-Za-z0-9._-]+):($fork_as|$material3_as)[[:space:]]*\$/\1 \3 \4/p" \
    "$tree/renderer/macos/module.yaml")
[[ -d "$home_complete/.m2" ]] || { fail "renderer/macos/module.yaml names nothing at $fork_as"; exit 1; }
home_empty="$work/home-empty"
mkdir -p "$home_empty/.m2/repository"

# Artifacts missing: the check stops the build and removes nothing.
plant_states
if HOME="$home_empty" bash "$tree/renderer/scripts/require-fork-artifacts.sh" macos > "$work/out" 2>&1; then
    fail "require-fork-artifacts.sh passed with the fork's artifacts missing"
fi
for state in stale-checksums stale-resolve other-version resolved; do
    [[ -f "$states/$state" ]] || fail "removed $state while the fork's artifacts were still missing"
done

# Artifacts present: the two failures that name the fork version go, and are named.
plant_states
if ! HOME="$home_complete" bash "$tree/renderer/scripts/require-fork-artifacts.sh" macos > "$work/out" 2>&1; then
    fail "require-fork-artifacts.sh refused a complete repository: $(tail -1 "$work/out")"
fi
for state in stale-checksums stale-resolve; do
    [[ ! -f "$states/$state" ]] || fail "kept $state, a cached failure to resolve $fork_as"
    grep -q "$states/$state" "$work/out" || fail "did not name $state when removing it"
done
for state in other-version resolved; do
    [[ -f "$states/$state" ]] || fail "removed $state, which records no failure to resolve $fork_as"
done

if [[ "$failures" -eq 0 ]]; then echo "  ok"; else exit 1; fi

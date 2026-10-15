#!/usr/bin/env bash
# Stops a renderer build before the Kotlin toolchain runs when the Compose fork's artifacts it
# needs are not in the local Maven repository yet.
#
# Usage: require-fork-artifacts.sh <macos|desktop|linux>...
#
# Each name is a renderer module. The modules ask for coordinates only the fork publishes, and
# only to ~/.m2: the patched Compose under EXTENDED_AS and skiko-awt under
# SKIKO_AWT_EXTENDED_AS (macos, desktop), and the Linux targets upstream does not publish at
# all (linux). They are put there by build-compose.sh and scripts/publish-skiko-awt.sh.
#
# Why this has to run first rather than letting the build fail: when the toolchain resolves one
# of those coordinates and the local repository does not have it, it asks the remote
# repositories, fails, and records the failure in its own cache (.m2.cache under the Kotlin
# toolchain's cache directory). From then on resolution keeps failing with "Unable to download
# checksums of file foundation-1.11.1001.pom" even once ~/.m2 has the files, and nothing in that
# message says where the stale entry is. Checking here means the toolchain never asks.
#
# What is checked, for each coordinate the module names: the .pom and the .module of the root
# publication and, for Compose, of the target's own publication beside it (foundation and
# foundation-macosarm64). A root without its target still sends the toolchain to the network
# for the target.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
renderer_dir="$(cd "$script_dir/.." && pwd)"
compose_script="$script_dir/build-compose.sh"
repository="$HOME/.m2/repository"

die() {
    echo "error: $1" >&2
    shift
    local line
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

[[ $# -gt 0 ]] || die "usage: require-fork-artifacts.sh <macos|desktop|linux>..."

compose_as="$(sed -n 's/^EXTENDED_AS="\([^"]*\)"$/\1/p' "$compose_script" | head -1)"
skiko_as="$(sed -n 's/^SKIKO_AWT_EXTENDED_AS="\([^"]*\)"$/\1/p' "$compose_script")"
[[ -n "$compose_as" && -n "$skiko_as" ]] || die "$compose_script names no EXTENDED_AS or SKIKO_AWT_EXTENDED_AS"

missing=()
fixes=()
add_fix() {
    local fix
    for fix in ${fixes[@]+"${fixes[@]}"}; do [[ "$fix" == "$1" ]] && return 0; done
    fixes+=("$1")
}

for module in "$@"; do
    case "$module" in
        macos)   variant="macosarm64"; compose_fix="renderer/scripts/build-compose.sh --target macosArm64" ;;
        desktop) variant="desktop";    compose_fix="renderer/scripts/build-compose.sh --target desktop" ;;
        # Both architectures: the toolchain resolves every platform a module declares, even
        # when it is asked to build one.
        linux)   variant="";           compose_fix="renderer/scripts/build-compose.sh --target linux" ;;
        *) die "unknown module '$module'" "known: macos, desktop, linux" ;;
    esac
    module_file="$renderer_dir/$module/module.yaml"
    [[ -f "$module_file" ]] || die "missing $module_file"

    # `- group:artifact:version` lines naming Compose or skiko, without a scope after them.
    while read -r group artifact version; do
        case "$version:$artifact" in
            "$compose_as":*|"$skiko_as":*|*:*-linuxx64|*:*-linuxarm64) ;;
            *) continue ;;
        esac
        names=("$artifact")
        if [[ -n "$variant" && "$group" == org.jetbrains.compose* ]]; then
            names+=("$artifact-$variant")
        fi
        for name in "${names[@]}"; do
            directory="$repository/$(tr . / <<< "$group")/$name/$version"
            for extension in pom module; do
                if [[ ! -s "$directory/$name-$version.$extension" ]]; then
                    missing+=("${directory#"$repository"/}/$name-$version.$extension")
                    if [[ "$group" == org.jetbrains.skiko ]]; then
                        add_fix "scripts/publish-skiko-awt.sh <work-dir>"
                    else
                        add_fix "$compose_fix"
                    fi
                fi
            done
        done
    done < <(sed -En 's/^[[:space:]]*-[[:space:]]*(org\.jetbrains\.(compose[A-Za-z0-9.]*|skiko)):([A-Za-z0-9._-]+):([A-Za-z0-9._-]+)[[:space:]]*$/\1 \3 \4/p' "$module_file")
done

[[ ${#missing[@]} -eq 0 ]] && exit 0

lines=("The renderer asks for these by version, and only the Compose fork publishes them, to the"
       "local Maven repository. The Kotlin toolchain was not started: asked for them now, it would"
       "fail to download them and remember that failure in its own cache, and keep failing after"
       "they are published.")
lines+=("missing, under $repository:")
for entry in "${missing[@]}"; do lines+=("  $entry"); done
lines+=("fix, from the repository root, then run this build again:")
for fix in "${fixes[@]}"; do lines+=("  $fix"); done
lines+=("If a build already ran without them, the toolchain's cache still holds the failed"
        "downloads: remove the directories of the versions above from .m2.cache in"
        "~/Library/Caches/JetBrains/Kotlin (macOS) or ~/.cache/JetBrains/Kotlin (Linux).")
die "the Compose fork's artifacts are not in the local Maven repository" "${lines[@]}"

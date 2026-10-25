#!/usr/bin/env bash
# Stops a renderer build before the Kotlin toolchain runs when the Compose fork's artifacts it
# needs are not in the local Maven repository yet.
#
# Usage: require-fork-artifacts.sh <macos|desktop|linux>...
#
# Each name is a renderer module. The modules ask for coordinates only the fork publishes, and
# only to ~/.m2: its Compose under org.thisisthepy.compose at COMPOSE_AS and MATERIAL3_AS, and
# its skiko-awt at SKIKO_AWT_AS (desktop). They are put there by build-compose.sh and
# scripts/publish-skiko-awt.sh.
#
# Why this has to run first rather than letting the build fail: when the toolchain resolves one
# of those coordinates and the local repository does not have it, it asks the remote
# repositories, fails with "Unable to download checksums of file foundation-1.11.1-ext.2.pom", and
# keeps that result in the renderer's own build directory (build/incremental.state). The entry is
# keyed on the coordinates and repositories alone, not on what ~/.m2 holds, so every later build
# replays it as up to date, even once ~/.m2 has the files, and nothing in the message says where
# it is. Checking here means the toolchain never asks.
#
# A checkout that asked before the artifacts were published still holds such an entry. Once
# every artifact is in place, this removes each state file that records a failure to resolve a
# fork version, and names it, so the next build resolves again.
#
# What is checked, for each coordinate the module names: the .pom and the .module of the root
# publication and, for Compose, of the target's own publication beside it (foundation and
# foundation-macosarm64). A root without its target still sends the toolchain to the network
# for the target.
#
# And, for every one of those publications, that the dependencies its .pom and .module name at a
# fork version are themselves published at that version (fork-dependencies.sh check). One that is
# not sends the toolchain to the network the same way.
#
# The window modules (org.thisisthepy.compose.window) are versioned on their own and published
# by publish-window.sh, which the build scripts run after this check.
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

compose_as="$(sed -n 's/^COMPOSE_AS="\([^"]*\)"$/\1/p' "$compose_script")"
material3_as="$(sed -n 's/^MATERIAL3_AS="\([^"]*\)"$/\1/p' "$compose_script")"
skiko_as="$(sed -n 's/^SKIKO_AWT_AS="\([^"]*\)"$/\1/p' "$compose_script")"
[[ -n "$compose_as" && -n "$material3_as" && -n "$skiko_as" ]] ||
    die "$compose_script names no COMPOSE_AS, MATERIAL3_AS or SKIKO_AWT_AS"

missing=()
fixes=()
checks=()
add_fix() {
    local fix
    for fix in ${fixes[@]+"${fixes[@]}"}; do [[ "$fix" == "$1" ]] && return 0; done
    fixes+=("$1")
}
# version|target|fix: the published metadata to read for dependencies nobody published.
add_check() {
    local check
    for check in ${checks[@]+"${checks[@]}"}; do [[ "$check" == "$1|$2|$3" ]] && return 0; done
    checks+=("$1|$2|$3")
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

    # `- group:artifact:version` lines naming the fork's Compose or skiko-awt, without a scope
    # after them.
    while read -r group artifact version; do
        case "$group:$version" in
            org.thisisthepy.compose.window*) continue ;;
            org.thisisthepy.compose.*:"$compose_as"|org.thisisthepy.compose.*:"$material3_as") ;;
            org.jetbrains.skiko:"$skiko_as") ;;
            *) continue ;;
        esac
        case "$version" in
            "$skiko_as") ;;
            *) add_check "$version" "$variant" "$compose_fix" ;;
        esac
        names=("$artifact")
        # The desktop module publishes one jar per system (desktop-jvm-<os>-<arch>), not one per
        # target, so its root is all there is to look for.
        if [[ -n "$variant" && "$group" == org.thisisthepy.compose.* && "$group" != org.thisisthepy.compose.desktop ]]; then
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
    done < <(sed -En 's/^[[:space:]]*-[[:space:]]*(org\.thisisthepy\.compose[A-Za-z0-9.]*|org\.jetbrains\.skiko):([A-Za-z0-9._-]+):([A-Za-z0-9._-]+)[[:space:]]*$/\1 \2 \3/p' "$module_file")
done

# The files being there is half of it. A .pom or .module the fork published names its siblings
# at the fork version, and a sibling nobody published at that version sends the toolchain to the
# network just as a missing file does: a build that stopped half way leaves exactly that.
for check in ${checks[@]+"${checks[@]}"}; do
    IFS="|" read -r version target fix <<< "$check"
    unpublished="$("$script_dir/fork-dependencies.sh" check "$version" "$target")" && continue
    while IFS= read -r entry; do
        [[ -n "$entry" ]] && missing+=("$entry")
    done <<< "$unpublished"
    add_fix "$fix"
done

if [[ ${#missing[@]} -eq 0 ]]; then
    state_dir="$renderer_dir/build/incremental.state"
    if [[ -d "$state_dir" ]]; then
        while IFS= read -r stale; do
            rm -f "$stale"
            echo "removed a cached resolution failure for a fork version: $stale"
        done < <(VERSIONS="$compose_as $material3_as $skiko_as" find "$state_dir" -maxdepth 1 -type f -exec perl -0777 -e '
            my @versions = split " ", $ENV{VERSIONS};
            for my $file (@ARGV) {
                open my $in, "<", $file or next;
                local $/;
                my $text = <$in>;
                while ($text =~ /diagnostics\.(?:UnableToDownloadChecksums|UnableToDownloadFile|UnableToResolveDependency)\b/g) {
                    my $window = substr($text, pos($text), 2000);
                    if (grep { index($window, $_) >= 0 } @versions) { print "$file\n"; last }
                }
            }
        ' {} +)
    fi
    exit 0
fi

lines=("The renderer asks for these by version, and only the Compose fork publishes them, to the"
       "local Maven repository. The Kotlin toolchain was not started: asked for them now, it would"
       "fail to download them and remember that failure in its own cache, and keep failing after"
       "they are published.")
lines+=("missing, under $repository:")
for entry in "${missing[@]}"; do lines+=("  $entry"); done
lines+=("fix, from the repository root, then run this build again:")
for fix in "${fixes[@]}"; do lines+=("  $fix"); done
lines+=("If a build already ran without them, its failure is kept in renderer/build/incremental.state;"
        "the next run of this check removes it once the artifacts above are published.")
die "the Compose fork's artifacts are not in the local Maven repository" "${lines[@]}"

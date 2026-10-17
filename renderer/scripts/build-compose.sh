#!/usr/bin/env bash
# Builds the Compose this renderer draws with from the Compose fork, and publishes it locally.
#
# Three things the renderer draws with are not in the build JetBrains publishes and cannot
# be reached from outside the module that holds them: the Kotlin/Native targets for Linux,
# the native text context menu, and the keys that copy. They are `internal actual`
# declarations, and an `expect` can only be answered inside its own module. The changes
# live as commits in a fork of Compose, thisisthepy/compose-multiplatform-core-extended,
# on its `extended` branch. This fetches one pinned commit of it into a checkout nobody
# edits by hand, and runs the fork's own extended/compose/publish-local.sh, which publishes
# every module the renderer draws with to the local Maven repository.
#
# The fork publishes under its own group and version, org.thisisthepy.compose.*:1.11.1-ext.1
# (its extended/COORDINATES.md), and the renderer modules ask for exactly those. Nothing
# JetBrains publishes sits at those coordinates, so a resolver that already holds JetBrains'
# Compose (Amper's cache does) cannot take JetBrains' jars in their place, and nothing here
# rewrites what the fork published.
#
# Usage: build-compose.sh [--target macosArm64|desktop|linux|linuxX64|linuxArm64] [--clean]
#
# The work directory is a sibling of the renderer called compose-build. Set
# DXC_COMPOSE_BUILD to put it elsewhere. It is not inside the renderer because it is a
# checkout of someone else's repository and a build of it costs several gigabytes.
set -euo pipefail

# The commit, not the branch. A branch that moves is a build that changes for a reason
# nobody chose here. This one is JetBrains release/1.11 at 73ac849 with the Linux targets, the
# native text context menu (opening at the pointer on macOS), the AWT-free copy, text direction
# and main dispatcher, the AppKit pump on the main thread, the window modules, the design systems
# under extended/design-systems (which scripts/fetch-design-systems.sh reads), and the
# org.thisisthepy.compose coordinates with extended/compose/publish-local.sh, which this runs.
# compose-fork.changes lists what this commit must hold at every path it changes, and
# scripts/tests/compose-fork.test.sh checks it.
FORK="https://github.com/thisisthepy/compose-multiplatform-core-extended.git"
REVISION="127311dc737d8badb68953291d248db8a7071cca"
# The window modules and skiko's builds are not Compose sources: the renderer builds them from
# this commit (scripts/fetch-fork-window.sh and fetch-fork-skiko.sh) into artifacts of its own,
# so they can move without moving the Compose build above. Today both are the same commit: the
# window modules compile against the fork's Compose coordinates, which that commit introduced.
WINDOW_REVISION="127311dc737d8badb68953291d248db8a7071cca"
# What the pinned commit publishes as: its gradle.properties gives every library line a version
# of the form <upstream version>-ext.<N>. The renderer modules ask for these by name, and
# scripts/tests/fork-versions.test.sh checks that they do; this script checks the pinned
# commit's gradle.properties says the same before it builds anything.
COMPOSE_AS="1.11.1-ext.1"
MATERIAL3_AS="1.11.0-alpha07-ext.1"
# The fork's patched skiko-awt, published by its extended/skiko/build-skiko-awt.sh (through
# scripts/publish-skiko-awt.sh). skiko keeps JetBrains' group, so it has a version of its own,
# which sorts above JetBrains' 0.144.6 in every resolver.
SKIKO_AWT_AS="0.144.6.1"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENDERER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK="${DXC_COMPOSE_BUILD:-$(dirname "$RENDERER_DIR")/compose-build}"

die() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

target="macosArm64"
clean=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --target) target="${2:-}"; shift 2 ;;
        --clean) clean=1; shift ;;
        -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
        *) die "unknown argument '$1'" "usage: build-compose.sh [--target <target>] [--clean]" ;;
    esac
done

# The fork's publish-local.sh targets. `linux` is both architectures, which is what building
# the renderer's Linux module takes: the toolchain resolves every platform a module declares,
# even when it is asked to build one. arm64 is cross compiled on an x86-64 machine, because
# Kotlin/Native has no arm64 Linux host.
case "$target" in
    macosArm64|desktop|linuxX64|linuxArm64) publish_targets=("$target") ;;
    linux) publish_targets=(linuxX64 linuxArm64) ;;
    *) die "unknown target '$target'" "known: macosArm64, desktop, linux (both of the next two), linuxX64, linuxArm64" ;;
esac

[[ $clean -eq 1 ]] && rm -rf "$WORK"

# Fetched at the revision alone rather than cloned whole: the history of this repository
# is large and none of it is read here. The remote is set every time because a work
# directory made before the fork existed still points at JetBrains, which does not have
# this commit.
if [[ ! -d "$WORK/.git" ]]; then
    mkdir -p "$WORK"
    git -C "$WORK" init -q
    git -C "$WORK" remote add origin "$FORK"
fi
git -C "$WORK" remote set-url origin "$FORK"
if ! git -C "$WORK" cat-file -e "$REVISION^{commit}" 2>/dev/null; then
    echo "==> fetching $REVISION"
    git -C "$WORK" fetch -q --depth 1 origin "$REVISION"
fi

# Reset rather than built on top of whatever is there. A tree somebody edited builds
# something nobody can reproduce, and a work directory from before the fork still holds
# the patches that used to be applied here.
echo "==> checking out $REVISION"
git -C "$WORK" -c advice.detachedHead=false checkout -q --force "$REVISION"
git -C "$WORK" clean -qfd -e build -e '.gradle' -e 'out'

[[ -n "${JAVA_HOME:-}" ]] || die "JAVA_HOME is not set" \
    "The Compose build needs a JDK 17 or 21; the toolchain wrapper's does not apply here."

publish="$WORK/extended/compose/publish-local.sh"
check="$WORK/extended/compose/check-local.py"
[[ -x "$publish" && -f "$check" ]] || die "$REVISION has no extended/compose/publish-local.sh" \
    "Pin a commit of the fork that publishes under org.thisisthepy.compose."

# The versions the pin publishes as are the ones the renderer modules name. Read before the
# build, so a pin that moved the versions fails in seconds rather than after an hour of Gradle.
for line in "COMPOSE=$COMPOSE_AS" "COMPOSE_MATERIAL3=$MATERIAL3_AS"; do
    grep -qx "jetbrains.publication.version.$line" "$WORK/gradle.properties" ||
        die "the fork at $REVISION does not publish jetbrains.publication.version.$line" \
            "Its gradle.properties holds: $(grep '^jetbrains.publication.version.COMPOSE' "$WORK/gradle.properties" | paste -sd' ' -)" \
            "Set COMPOSE_AS and MATERIAL3_AS here to what it publishes, and the renderer modules with them."
done

echo "==> publishing the fork's Compose for ${publish_targets[*]} as $COMPOSE_AS"
"$publish" "${publish_targets[@]}"

# Every sibling a published module names is there, nothing names JetBrains' copy of a module the
# fork publishes, and nothing at JetBrains' coordinates carries a fork version.
python3 -I "$check"

echo
echo "published to $HOME/.m2/repository/org/thisisthepy/compose as $COMPOSE_AS"
echo "the renderer's macos, linux and desktop modules read mavenLocal first, so the next build links these"

#!/usr/bin/env bash
# Builds the Compose modules this renderer needs changed, and publishes them locally.
#
# Three things the renderer draws with are not in the build JetBrains publishes and cannot
# be reached from outside the module that holds them: the Kotlin/Native targets for Linux,
# the native text context menu, and the keys that copy. They are `internal actual`
# declarations, and an `expect` can only be answered inside its own module. The changes
# live as commits in a fork of Compose, thisisthepy/compose-multiplatform-core-extended,
# on its `extended` branch. This fetches one pinned commit of it into a checkout nobody
# edits by hand and publishes the result under the version the renderer asks for, so that
# the modules that are not changed keep resolving from JetBrains.
#
# Usage: build-compose.sh [--target macosArm64|linux|linuxX64|linuxArm64] [--clean]
#
# The work directory is a sibling of the renderer called compose-build. Set
# DXC_COMPOSE_BUILD to put it elsewhere. It is not inside the renderer because it is a
# checkout of someone else's repository and a build of it costs several gigabytes.
set -euo pipefail

# The commit, not the branch. A branch that moves is a build that changes for a reason
# nobody chose here. This one is the head of `extended`: JetBrains release/1.11 at 73ac849
# with the Linux targets, the native text context menu (opening at the pointer on macOS),
# the AWT-free copy, text direction and main dispatcher, the AppKit pump on the main thread, the main dispatcher property,
# the published version and the design systems under extended/design-systems, which this
# build does not read and scripts/fetch-design-systems.sh does.
# compose-fork.changes lists what this commit must hold at every path it changes, and
# scripts/tests/compose-fork.test.sh checks it.
FORK="https://github.com/thisisthepy/compose-multiplatform-core-extended.git"
REVISION="f34e8bb9bdfeabb2e0b0885291a8ef9b32c0a538"
# The window modules and skiko's static build are newer than that and are not Compose sources:
# the renderer builds them from this commit (scripts/fetch-fork-window.sh and fetch-fork-skiko.sh)
# into artifacts of its own, so they move without moving the Compose build above.
WINDOW_REVISION="ace00947ab88d4de7fecef5fefa74a02240fd9c6"
PUBLISHED_AS="1.11.1"
# Material 3 is versioned on its own line and the renderer asks for it by that version, so
# publishing it as the others would leave a coordinate nobody looks for.
MATERIAL3_PUBLISHED_AS="1.11.0-alpha07"
# Targets whose modules JetBrains publishes too (desktop, and macOS for ui and foundation) get
# a version of their own. Under 1.11.1 the fork's jars sit at upstream's coordinates, and a
# resolver that already holds upstream's 1.11.1 (Amper's cache does) takes those and never
# looks in the local repository: the build links, and the app runs upstream's Compose. The
# fork's Gradle build takes only major.minor.patch with an optional tag, and a tag sorts below
# the release in Gradle and above it in Maven, so the patch number is raised instead: 1.11.1001
# sorts above 1.11.1 everywhere, and the fork's module also wins when upstream's is pulled in
# behind another dependency. The renderer modules ask for these
# versions by name, and scripts/tests/fork-versions.test.sh checks that they do.
EXTENDED_AS="1.11.1001"
EXTENDED_AS="${DXC_COMPOSE_EXTENDED_AS:-$EXTENDED_AS}"
# The fork's patched skiko-awt, published by scripts/publish-skiko-awt.sh under the same rule.
SKIKO_AWT_EXTENDED_AS="0.144.6.1"

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
        -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
        *) die "unknown argument '$1'" "usage: build-compose.sh [--target <target>] [--clean]" ;;
    esac
done

# Which Gradle publication to ask for, and which modules have to be published at all.
#
# These differ by target and not by accident. On macOS the only thing missing from what JetBrains
# published is the text context menu, which lives in two modules, so those two are rebuilt and
# everything else still resolves from upstream. On Linux there is no published Kotlin/Native target
# at all: `runtime` is the one module upstream builds for linuxX64, and every other module the
# renderer draws with has to be built here. A module left off this list is not a build failure in
# this script; it is an unresolvable dependency in the renderer's own build, tens of minutes later,
# naming a coordinate nobody recognises.
#
# What is left off, and why it has to be: the renderer's closure and nothing beyond it.
# Material 2's navigation, the adaptive family and the navigation suite each ask for a
# published artifact that has no Linux variant at all, so building them here is not slow,
# it is impossible. None of them is reachable from what the renderer draws, which is
# runtime, ui, foundation and material3.
case "$target" in
    macosArm64)
        publications=(MacosArm64)
        published_as="$EXTENDED_AS"
        modules=(
            compose:foundation:foundation
            compose:ui:ui
        )
        ;;
    linux|linuxArm64|linuxX64)
        # arm64 is the same list for the other architecture, cross compiled on an x86-64
        # machine: Kotlin/Native has no arm64 Linux host. `linux` is both, which is what
        # building the renderer's Linux module takes: the toolchain resolves every
        # platform a module declares, even when it is asked to build one.
        if [[ "$target" == linuxX64 ]]; then
            publications=(LinuxX64)
        elif [[ "$target" == linuxArm64 ]]; then
            publications=(LinuxArm64)
        else
            publications=(LinuxX64 LinuxArm64)
        fi
        modules=(
            compose:animation:animation
            compose:animation:animation-core
            compose:foundation:foundation
            compose:foundation:foundation-layout
            compose:material:material-ripple
            compose:material3:material3
            compose:ui:ui
            compose:ui:ui-backhandler
            compose:ui:ui-geometry
            compose:ui:ui-graphics
            compose:ui:ui-text
            compose:ui:ui-tooling-preview
            compose:ui:ui-unit
            compose:ui:ui-util
        )
        ;;
    desktop)
        # The Java side of Compose, for the native image renderers. Only what the fork changes
        # is rebuilt, under EXTENDED_AS; every other module is upstream's at the version it
        # always had. The roots are published as well, because upstream's root at this version
        # does not exist: they map a desktop consumer to these coordinates.
        publications=(Desktop)
        published_as="$EXTENDED_AS"
        modules=(
            compose:foundation:foundation
            compose:ui:ui
            compose:ui:ui-text
        )
        ;;
    *) die "unknown target '$target'" "known: macosArm64, linux (both of the next two), linuxX64, linuxArm64, desktop" ;;
esac

published_as="${published_as:-$PUBLISHED_AS}"

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
    "The Compose build needs a JDK 17; the toolchain wrapper's does not apply here."

echo "==> publishing ${#modules[@]} compose module(s) for $target as $published_as"
# Two publications per module, not one. The target's own carries the klib; the root one
# carries the metadata that says which targets exist. Without the root, a consumer asking
# for the module is told the library does not support this platform, which is true of what
# was published and not of what was built.
tasks=()
for module in "${modules[@]}"; do
    for publication in "${publications[@]}"; do
        tasks+=(":$module:publish${publication}PublicationToMavenLocal")
    done
    tasks+=(":$module:publishKotlinMultiplatformPublicationToMavenLocal")
    # The fork's build replaces the root publication with a redirecting one (a root that names
    # the target's artifacts), and disables the plain one, which is why that task reports SKIPPED.
    # A version nobody else published has no root unless this one is asked for too.
    [[ "$published_as" == "$PUBLISHED_AS" ]] || tasks+=(":$module:publishKotlinMultiplatformDecoratedPublicationToMavenLocal")
done
(
    cd "$WORK"
    # The fork declares mingwX64 on every UI module, and skiko publishes no mingwX64 artifact:
    # only the fork's own Windows build makes one. None of the targets here is Windows, so
    # leave that platform out rather than fail resolving skiko for it.
    ./gradlew --no-daemon --no-configuration-cache \
        "-Pandroidx.enabled.kmp.target.platforms=-windows" \
        "-Pjetbrains.publication.version.COMPOSE=$published_as" \
        "-Pjetbrains.publication.version.COMPOSE_MATERIAL3=$MATERIAL3_PUBLISHED_AS" \
        "${tasks[@]}"
)

# Every module here depends on its siblings at the version it was published as. Only some
# siblings were built, so a dependency on one that was not has to name the upstream module it
# stands for, or the consumer asks for a coordinate nobody published and the Kotlin toolchain
# goes to the network for it.
#
# This reads every .pom and .module under that version in ~/.m2, not only what this run wrote.
# A run whose Gradle tasks were up to date still overwrites the published files with Gradle's
# own, in directories that already existed, so asking which directories are newer than the
# run finds nothing; and a run that stopped before this step left files that still need it.
if [[ "$published_as" != "$PUBLISHED_AS" ]]; then
    repo="$HOME/.m2/repository/org/jetbrains/compose"
    published="$(find "$repo" -type f -path "*/$published_as/*-$published_as.module" -print -quit 2>/dev/null || true)"
    [[ -n "$published" ]] || die "nothing was published as $published_as under $repo"
    echo "==> pointing dependencies on modules that were not built back at $PUBLISHED_AS"
    "$SCRIPT_DIR/fork-dependencies.sh" repair "$published_as" "$PUBLISHED_AS"
    "$SCRIPT_DIR/fork-dependencies.sh" check "$published_as" >/dev/null ||
        die "dependencies at $published_as still name modules nobody published" \
            "run renderer/scripts/fork-dependencies.sh check $published_as to list them"
fi

echo
echo "published to $HOME/.m2/repository/org/jetbrains/compose as $published_as"
echo "the renderer's macos and linux modules read mavenLocal first, so the next build links these"

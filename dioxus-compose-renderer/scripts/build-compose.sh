#!/usr/bin/env bash
# Builds the Compose modules this renderer needs patched, and publishes them locally.
#
# Three things the renderer draws with are not in the published build and cannot be
# reached from outside the module that holds them. `patches/README.md` says which and
# why. This takes the pinned upstream revision, applies those patches to a checkout
# nobody edits by hand, and publishes the result under the version the renderer asks
# for, so that the modules that are not patched keep resolving from JetBrains.
#
# Usage: build-compose.sh [--target macosArm64|linuxX64] [--clean]
#
# The work directory is a sibling of the renderer called compose-build. Set
# DXC_COMPOSE_BUILD to put it elsewhere. It is not inside the renderer because it is a
# checkout of someone else's repository and a build of it costs several gigabytes.
set -euo pipefail

UPSTREAM="https://github.com/JetBrains/compose-multiplatform-core.git"
REVISION="73ac84978a9e4ddca7e062dc0ee357ad875450fa"
PUBLISHED_AS="1.11.1"
# Material 3 is versioned on its own line and the renderer asks for it by that version, so
# publishing it as the others would leave a coordinate nobody looks for.
MATERIAL3_PUBLISHED_AS="1.11.0-alpha07"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENDERER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PATCH_DIR="$RENDERER_DIR/patches"
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
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
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
        publication="MacosArm64"
        modules=(
            compose:foundation:foundation
            compose:ui:ui
        )
        ;;
    linuxX64)
        publication="LinuxX64"
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
    *) die "unknown target '$target'" "known: macosArm64, linuxX64" ;;
esac

[[ $clean -eq 1 ]] && rm -rf "$WORK"

# Fetched at the revision alone rather than cloned whole: the history of this repository
# is large and none of it is read here.
if [[ ! -d "$WORK/.git" ]]; then
    mkdir -p "$WORK"
    git -C "$WORK" init -q
    git -C "$WORK" remote add origin "$UPSTREAM"
fi
if ! git -C "$WORK" cat-file -e "$REVISION^{commit}" 2>/dev/null; then
    echo "==> fetching $REVISION"
    git -C "$WORK" fetch -q --depth 1 origin "$REVISION"
fi

# Reset rather than patched on top of whatever is there. A patch applied twice fails and
# a patch applied to a tree somebody edited succeeds in a way nobody can reproduce.
echo "==> checking out $REVISION"
git -C "$WORK" -c advice.detachedHead=false checkout -q --force "$REVISION"
git -C "$WORK" clean -qfd -e build -e '.gradle' -e 'out'

shopt -s nullglob
patches=("$PATCH_DIR"/*.patch)
[[ ${#patches[@]} -gt 0 ]] || die "no patches in $PATCH_DIR"
for patch in "${patches[@]}"; do
    echo "==> applying $(basename "$patch")"
    git -C "$WORK" apply --whitespace=nowarn "$patch" ||
        die "$(basename "$patch") does not apply to $REVISION" \
            "Upstream may have filled the same gap, in which case the patch is to be deleted." \
            "See $PATCH_DIR/README.md."
done

[[ -n "${JAVA_HOME:-}" ]] || die "JAVA_HOME is not set" \
    "The Compose build needs a JDK 17; the toolchain wrapper's does not apply here."

echo "==> publishing ${#modules[@]} compose module(s) for $target as $PUBLISHED_AS"
# Two publications per module, not one. The target's own carries the klib; the root one
# carries the metadata that says which targets exist. Without the root, a consumer asking
# for the module is told the library does not support this platform, which is true of what
# was published and not of what was built.
tasks=()
for module in "${modules[@]}"; do
    tasks+=(":$module:publish${publication}PublicationToMavenLocal")
    tasks+=(":$module:publishKotlinMultiplatformPublicationToMavenLocal")
done
(
    cd "$WORK"
    ./gradlew --no-daemon --no-configuration-cache \
        "-Pjetbrains.publication.version.COMPOSE=$PUBLISHED_AS" \
        "-Pjetbrains.publication.version.COMPOSE_MATERIAL3=$MATERIAL3_PUBLISHED_AS" \
        "${tasks[@]}"
)

echo
echo "published to $HOME/.m2/repository/org/jetbrains/compose as $PUBLISHED_AS"
echo "the renderer's macos and linux modules read mavenLocal first, so the next build links these"

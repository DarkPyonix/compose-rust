#!/usr/bin/env bash
# Builds the Compose the renderer and the design systems draw with, and publishes it locally.
#
# Three things the renderer draws with are not in the build JetBrains publishes and cannot
# be reached from outside the module that holds them: the Kotlin/Native targets for Linux,
# the native text context menu, and the keys that copy. They are `internal actual`
# declarations, and an `expect` can only be answered inside its own module. The changes
# live as commits in a fork of Compose, thisisthepy/compose-multiplatform-core-extended,
# on its `extended` branch. This fetches one pinned commit of it into a checkout nobody
# edits by hand and publishes the modules this repository asks for.
#
# The fork publishes under its own coordinates, org.thisisthepy.compose.* at
# <upstream version>-ext.<N> (the fork's extended/COORDINATES.md), not under JetBrains'. So
# nothing here stands in for an artifact JetBrains published, and nothing JetBrains published
# fills a gap: every module a target reaches has to be published here, root metadata
# included, until the fork's artifacts are published to a public repository.
#
# Usage: build-compose.sh [--target <target>]... [--clean]
#   targets: macosArm64 (the default), linuxX64, iosArm64, iosSimulatorArm64, wasmJs, jvm,
#            android. --target can be given more than once; one Gradle run builds them all.
#
# The work directory is a sibling of the renderer called compose-build. Set
# DXC_COMPOSE_BUILD to put it elsewhere. It is not inside the renderer because it is a
# checkout of someone else's repository and a build of it costs several gigabytes.
set -euo pipefail

# The commit, not the branch. A branch that moves is a build that changes for a reason
# nobody chose here. This one is JetBrains release/1.11 at 73ac849 with, on top: the Linux
# targets, the native text context menu, a mingwX64 target and the skiko builds for Windows
# and for a native image, and the fork's own coordinates and version rule.
# compose-fork.changes lists what this commit must hold at every path it changes, and
# scripts/tests/compose-fork.test.sh checks it.
FORK="https://github.com/thisisthepy/compose-multiplatform-core-extended.git"
REVISION="7fcf36c0ed8df649a08f3341570dacfd9be4440a"
# What the fork's library lines publish as, and what every module.yaml and libs.versions.toml
# in this repository asks for. They are the fork's own defaults at REVISION; they are passed
# anyway so that this file is the one place here that says which versions are built.
GROUP="org.thisisthepy.compose"
PUBLISHED_AS="1.11.1-ext.1"
# Material 3 is versioned on its own line and asked for by that version.
MATERIAL3_PUBLISHED_AS="1.11.0-alpha07-ext.1"
# The lifecycle libraries the JVM shell asks for. They carry no classes of their own and
# point at androidx.lifecycle.
LIFECYCLE_PUBLISHED_AS="2.11.0-beta01-ext.1"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENDERER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK="${DXC_COMPOSE_BUILD:-$(dirname "$RENDERER_DIR")/compose-build}"

die() {
    echo "error: $1" >&2
    shift
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

targets=()
clean=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --target) targets+=("${2:-}"); shift 2 ;;
        --clean) clean=1; shift ;;
        -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
        *) die "unknown argument '$1'" "usage: build-compose.sh [--target <target>]... [--clean]" ;;
    esac
done
[[ ${#targets[@]} -gt 0 ]] || targets=(macosArm64)

# The root publication of a module: the metadata that says which targets exist and where
# each one is. Without it a consumer asking for the module is told the library does not
# support its platform, which is true of what was published and not of what was built.
#
# A module whose Android target is redirected to androidx publishes its root as
# KotlinMultiplatformDecorated, and the plain KotlinMultiplatform task is disabled; asking
# for that one publishes nothing and says nothing. These three have no redirected Android
# target, so their root is the plain one.
root_publication() {
    case "$1" in
        compose:ui:ui-backhandler|compose:ui:ui-uikit|compose:desktop:desktop) echo "KotlinMultiplatform" ;;
        *) echo "KotlinMultiplatformDecorated" ;;
    esac
}

# Which Gradle publication to ask for, and which modules have to be published at all.
#
# Every target gets the renderer's closure: runtime, ui, foundation and material3, and every
# module of the fork those reach. A module left off a list is not a build failure in this
# script; it is an unresolvable dependency in the renderer's own build, tens of minutes later,
# naming a coordinate nobody recognises.
#
# What is left off, and why it has to be: the closure and nothing beyond it. Material 2's
# navigation, the adaptive family and the navigation suite each ask for a published artifact
# that has no Linux variant at all, so building them here is not slow, it is impossible.
# None of them is reachable from what the renderer draws with.
add_tasks() {
    local target="$1" publication modules=() roots_only=()
    case "$target" in
        macosArm64)
            publication="MacosArm64"
            modules=(
                compose:animation:animation
                compose:animation:animation-core
                compose:foundation:foundation
                compose:foundation:foundation-layout
                compose:material:material-ripple
                compose:material3:material3
                compose:runtime:runtime
                compose:runtime:runtime-saveable
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
        linuxX64)
            publication="LinuxX64"
            modules=(
                compose:animation:animation
                compose:animation:animation-core
                compose:foundation:foundation
                compose:foundation:foundation-layout
                compose:material:material-ripple
                compose:material3:material3
                compose:runtime:runtime
                compose:runtime:runtime-saveable
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
        iosArm64|iosSimulatorArm64)
            if [[ "$target" == iosArm64 ]]; then publication="IosArm64"; else publication="IosSimulatorArm64"; fi
            # ui-uikit is what ui reaches on iOS and nowhere else.
            modules=(
                compose:animation:animation
                compose:animation:animation-core
                compose:foundation:foundation
                compose:foundation:foundation-layout
                compose:material:material-ripple
                compose:material3:material3
                compose:runtime:runtime
                compose:runtime:runtime-saveable
                compose:ui:ui
                compose:ui:ui-backhandler
                compose:ui:ui-geometry
                compose:ui:ui-graphics
                compose:ui:ui-text
                compose:ui:ui-tooling-preview
                compose:ui:ui-uikit
                compose:ui:ui-unit
                compose:ui:ui-util
            )
            ;;
        wasmJs)
            publication="WasmJs"
            modules=(
                compose:animation:animation
                compose:animation:animation-core
                compose:foundation:foundation
                compose:foundation:foundation-layout
                compose:material:material-ripple
                compose:material3:material3
                compose:runtime:runtime
                compose:runtime:runtime-saveable
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
        jvm)
            # The JVM shell, its UI tests, and the design systems' JVM target. desktop asks for
            # Material 2 as well, and the shell for two lifecycle libraries whose desktop target
            # is not redirected; the lifecycle libraries those reach are, and need their root only.
            publication="Desktop"
            modules=(
                compose:animation:animation
                compose:animation:animation-core
                compose:foundation:foundation
                compose:foundation:foundation-layout
                compose:material:material
                compose:material:material-ripple
                compose:material3:material3
                compose:runtime:runtime
                compose:runtime:runtime-saveable
                compose:ui:ui
                compose:ui:ui-backhandler
                compose:ui:ui-geometry
                compose:ui:ui-graphics
                compose:ui:ui-test
                compose:ui:ui-test-junit4
                compose:ui:ui-text
                compose:ui:ui-tooling-preview
                compose:ui:ui-unit
                compose:ui:ui-util
                lifecycle:lifecycle-runtime-compose
                lifecycle:lifecycle-viewmodel-compose
            )
            roots_only=(
                lifecycle:lifecycle-common
                lifecycle:lifecycle-runtime
                lifecycle:lifecycle-viewmodel
                lifecycle:lifecycle-viewmodel-savedstate
            )
            # desktop publishes its JVM target, and one POM per desktop system that adds that
            # system's skiko: what compose.desktop.currentOs used to name.
            local system
            tasks+=(":compose:desktop:desktop:publishKotlinMultiplatformPublicationToMavenLocal")
            tasks+=(":compose:desktop:desktop:publishJvmPublicationToMavenLocal")
            for system in linux-x64 linux-arm64 macos-x64 macos-arm64 windows-x64 windows-arm64; do
                tasks+=(":compose:desktop:desktop:publishJvm${system}PublicationToMavenLocal")
            done
            ;;
        android)
            # Every Android variant points at the androidx artifact, so there is no Android
            # target to publish: what an Android build reads here is the root metadata that
            # says so.
            publication=""
            roots_only=(
                compose:animation:animation
                compose:animation:animation-core
                compose:foundation:foundation
                compose:foundation:foundation-layout
                compose:material:material-ripple
                compose:material3:material3
                compose:runtime:runtime
                compose:runtime:runtime-saveable
                compose:ui:ui
                compose:ui:ui-backhandler
                compose:ui:ui-geometry
                compose:ui:ui-graphics
                compose:ui:ui-text
                compose:ui:ui-tooling
                compose:ui:ui-tooling-preview
                compose:ui:ui-unit
                compose:ui:ui-util
            )
            ;;
        *) die "unknown target '$target'" "known: macosArm64, linuxX64, iosArm64, iosSimulatorArm64, wasmJs, jvm, android" ;;
    esac
    local module
    for module in ${modules[@]+"${modules[@]}"}; do
        tasks+=(":$module:publish${publication}PublicationToMavenLocal")
        tasks+=(":$module:publish$(root_publication "$module")PublicationToMavenLocal")
    done
    for module in ${roots_only[@]+"${roots_only[@]}"}; do
        tasks+=(":$module:publish$(root_publication "$module")PublicationToMavenLocal")
    done
}

tasks=()
for target in "${targets[@]}"; do
    add_tasks "$target"
done
# A root asked for by two targets is one task. Read line by line rather than with mapfile,
# which the bash macOS ships does not have.
unique=()
while IFS= read -r task; do unique+=("$task"); done < <(printf '%s\n' "${tasks[@]}" | awk '!seen[$0]++')
tasks=("${unique[@]}")

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

echo "==> publishing Compose for ${targets[*]} as $GROUP.* $PUBLISHED_AS (${#tasks[@]} tasks)"
(
    cd "$WORK"
    ./gradlew --no-daemon --no-configuration-cache \
        "-Pjetbrains.publication.version.COMPOSE=$PUBLISHED_AS" \
        "-Pjetbrains.publication.version.COMPOSE_MATERIAL3=$MATERIAL3_PUBLISHED_AS" \
        "-Pjetbrains.publication.version.LIFECYCLE=$LIFECYCLE_PUBLISHED_AS" \
        "${tasks[@]}"
)

echo
echo "published to $HOME/.m2/repository/${GROUP//.//} as $PUBLISHED_AS"
echo "the renderer's and the design systems' modules read mavenLocal, so the next build links these"

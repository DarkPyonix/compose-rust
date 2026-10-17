#!/usr/bin/env bash
# The renderer's macos, desktop and linux modules ask for Compose and skiko artifacts that only
# the fork publishes, and only to the local Maven repository (renderer/scripts/build-compose.sh,
# scripts/publish-skiko-awt.sh). When a build script runs the Kotlin toolchain before they are
# there, the toolchain tries to download them, fails, and records the failure in its own cache.
# From then on the build keeps failing with "Unable to download checksums of file
# foundation-1.11.1-ext.1.pom" even after ~/.m2 holds the files, until someone finds that cache.
#
# So every script that resolves those versions checks ~/.m2 first and stops before the
# toolchain runs. This proves it for each script by running it in a copy of the tree with the
# toolchain wrapper, the window publisher and the platform tools replaced by stubs that record
# being called, under a HOME whose ~/.m2 is empty, partly filled, or complete:
#
#   empty or partial  the script fails, names the publish command, and calls nothing
#   complete          the script gets as far as the toolchain wrapper
#
# The scripts that cannot run here (the Windows PowerShell build, the repository gate and the
# screenshot run, which start with cargo) are checked for the order of their lines instead.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "fork artifacts are checked before the Kotlin toolchain runs"

mkdir -p "$repo/.scratch"
work="$(mktemp -d "$repo/.scratch/fork-artifacts-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

compose_script="$repo/renderer/scripts/build-compose.sh"
compose_as="$(sed -n 's/^COMPOSE_AS="\([^"]*\)"$/\1/p' "$compose_script")"
material3_as="$(sed -n 's/^MATERIAL3_AS="\([^"]*\)"$/\1/p' "$compose_script")"
skiko_as="$(sed -n 's/^SKIKO_AWT_AS="\([^"]*\)"$/\1/p' "$compose_script")"
[[ -n "$compose_as" && -n "$material3_as" && -n "$skiko_as" ]] ||
    { fail "build-compose.sh names no COMPOSE_AS, MATERIAL3_AS or SKIKO_AWT_AS"; exit 1; }

# The files a complete local repository holds for one renderer module, relative to
# ~/.m2/repository: every coordinate of the fork's Compose (org.thisisthepy.compose at
# COMPOSE_AS or MATERIAL3_AS) and its skiko-awt (SKIKO_AWT_AS) the module names, and for Compose
# the target's own publication beside the root one. The window modules are published by
# publish-window.sh after the check, so they are not among them.
fork_files() {
    local module="$1" variant="$2" group artifact version base name
    while read -r group artifact version; do
        case "$group:$version" in
            org.thisisthepy.compose.window*) continue ;;
            org.thisisthepy.compose.*:"$compose_as"|org.thisisthepy.compose.*:"$material3_as") ;;
            org.jetbrains.skiko:"$skiko_as") ;;
            *) continue ;;
        esac
        base="$(tr . / <<< "$group")"
        for name in "$artifact" ${variant:+"$artifact-$variant"}; do
            [[ "$name" == "$artifact" || "$group" == org.thisisthepy.compose.* ]] || continue
            echo "$base/$name/$version/$name-$version.pom"
            echo "$base/$name/$version/$name-$version.module"
        done
    done < <(sed -En 's/^[[:space:]]*-[[:space:]]*(org\.thisisthepy\.compose[A-Za-z0-9.]*|org\.jetbrains\.skiko):([A-Za-z0-9._-]+):([A-Za-z0-9._-]+)[[:space:]]*$/\1 \2 \3/p' \
        "$repo/renderer/$module/module.yaml")
}

# A copy of the tree with the scripts under test and stubs for everything they would reach.
tree="$work/tree"
mkdir -p "$tree/renderer/desktop/scripts" "$tree/renderer/desktop/c" "$tree/renderer/scripts" \
    "$tree/scripts" "$tree/bin" "$tree/fork/extended/window/graalvm/graalvm-macos/native" \
    "$tree/fork/extended/window/graalvm/graalvm-linux/c"
cp "$repo"/renderer/desktop/scripts/*.sh "$tree/renderer/desktop/scripts/"
cp "$repo/renderer/scripts/build-compose.sh" "$tree/renderer/scripts/"
[[ -f "$repo/renderer/scripts/require-fork-artifacts.sh" ]] &&
    cp "$repo/renderer/scripts/require-fork-artifacts.sh" "$tree/renderer/scripts/"
[[ -f "$repo/renderer/scripts/fork-dependencies.sh" ]] &&
    cp "$repo/renderer/scripts/fork-dependencies.sh" "$tree/renderer/scripts/"
for module in macos desktop linux; do
    mkdir -p "$tree/renderer/$module"
    cp "$repo/renderer/$module/module.yaml" "$tree/renderer/$module/"
done
touch "$tree/renderer/desktop/c/renderer_entry.c" "$tree/renderer/desktop/c/macos_main_thread.m" \
    "$tree/renderer/desktop/c/macos_notifications.m" \
    "$tree/fork/extended/window/graalvm/graalvm-macos/native/appkit_window.m" \
    "$tree/fork/extended/window/graalvm/graalvm-linux/c/x11_window.c"

calls="$work/calls"
stub() {
    printf '#!/usr/bin/env bash\n%s\n' "$2" > "$1"
    chmod +x "$1"
}
stub "$tree/renderer/kotlin" "echo \"kotlin \$*\" >> \"$calls\"; exit 1"
stub "$tree/renderer/scripts/publish-window.sh" "echo publish-window >> \"$calls\""
stub "$tree/scripts/fetch-fork-window.sh" "echo \"$tree/fork\""
stub "$tree/bin/uname" 'case "${1:-}" in -m) echo "$FAKE_ARCH" ;; *) echo "$FAKE_OS" ;; esac'
for tool in xcode-select xcrun cc nm readelf unzip pkg-config fc-match; do
    stub "$tree/bin/$tool" 'exit 0'
done
graal="$work/graalvm"
mkdir -p "$graal/bin" "$graal/lib/static/darwin-arm64"
stub "$graal/bin/native-image" 'echo "native-image 25.0.1 2025-10-21"'
touch "$graal/lib/static/darwin-arm64/libawt_lwawt.a" "$graal/lib/libnative-image-agent.so"

# run <script> <os> <arch> <home>: runs one script in the copy, and leaves what it printed in
# $work/out and what it called in $calls.
run() {
    rm -f "$calls"
    touch "$calls"
    local status=0
    HOME="$4" PATH="$tree/bin:$PATH" FAKE_OS="$2" FAKE_ARCH="$3" GRAALVM_HOME="$graal" \
        DISPLAY=:99 bash "$tree/renderer/desktop/scripts/$1" > "$work/out" 2>&1 || status=$?
    return "$status"
}

# A HOME whose ~/.m2 holds the given files.
home_with() {
    local home="$work/home-$1" file
    shift
    rm -rf "$home"
    mkdir -p "$home/.m2/repository"
    for file in "$@"; do
        mkdir -p "$(dirname "$home/.m2/repository/$file")"
        echo stand-in > "$home/.m2/repository/$file"
    done
    echo "$home"
}

# script | os | arch | module | target variant | the publish command it must name
cases=(
    "build-macos.sh|Darwin|arm64|macos|macosarm64|build-compose.sh --target macosArm64"
    "build-native.sh|Darwin|arm64|desktop|desktop|build-compose.sh --target desktop"
    "collect-metadata.sh|Darwin|arm64|desktop|desktop|build-compose.sh --target desktop"
    "build-linux.sh|Linux|x86_64|linux||build-compose.sh --target linux"
    "build-native-linux.sh|Linux|x86_64|desktop|desktop|build-compose.sh --target desktop"
    "collect-metadata-linux.sh|Linux|x86_64|desktop|desktop|build-compose.sh --target desktop"
)

for case_line in "${cases[@]}"; do
    IFS='|' read -r script os arch module variant command <<< "$case_line"
    files=()
    while IFS= read -r file; do files+=("$file"); done < <(fork_files "$module" "$variant")
    [[ ${#files[@]} -gt 0 ]] || { fail "renderer/$module/module.yaml names no fork artifact"; continue; }

    # Nothing published, then everything but the last file: a root publication without the
    # target's own still sends the toolchain to the network for the target.
    partial=("${files[@]}")
    unset 'partial[${#partial[@]}-1]'
    for state in empty partial; do
        if [[ "$state" == empty ]]; then home="$(home_with "$state")"; else home="$(home_with "$state" "${partial[@]}")"; fi
        if run "$script" "$os" "$arch" "$home"; then
            fail "$script succeeded with the fork's artifacts missing ($state ~/.m2)"
        fi
        if [[ -s "$calls" ]]; then
            fail "$script ran $(paste -sd, - < "$calls") with the fork's artifacts missing ($state ~/.m2)"
            sed 's/^/        /' "$work/out" | tail -5 >&2
        fi
        # skiko-awt has a publish command of its own.
        expected="$command"
        [[ "$state" == partial && "${files[${#files[@]}-1]}" == org/jetbrains/skiko/* ]] &&
            expected="publish-skiko-awt.sh"
        grep -q -- "$expected" "$work/out" ||
            fail "$script did not name '$expected' with the fork's artifacts missing ($state ~/.m2)"
    done

    # Everything published: the script goes on to the toolchain. build-linux.sh needs bash 4
    # before it gets there, which macOS does not ship, so on bash 3 that one half is skipped.
    if [[ "$script" == build-linux.sh && "${BASH_VERSINFO[0]}" -lt 4 ]]; then
        echo "  skipped: $script with every artifact present needs bash 4 (this is $BASH_VERSION)"
        continue
    fi
    home="$(home_with complete "${files[@]}")"
    run "$script" "$os" "$arch" "$home" || true
    if ! grep -q '^kotlin ' "$calls"; then
        fail "$script never reached the Kotlin toolchain with every fork artifact present"
        sed 's/^/        /' "$work/out" | tail -5 >&2
    fi
done

# The scripts that cannot be run here: the check has to come before the first line that runs
# the toolchain or publishes the window modules.
check_order() {
    local file="$1" first_amper="$2" check_line amper_line
    check_line="$(grep -n 'require-fork-artifacts\.sh' "$repo/$file" | grep -v '^[0-9]*:[[:space:]]*#' | head -1 | cut -d: -f1)"
    amper_line="$(grep -nE "$first_amper" "$repo/$file" | grep -v '^[0-9]*:[[:space:]]*#' | head -1 | cut -d: -f1)"
    if [[ -z "$check_line" ]]; then
        fail "$file never runs require-fork-artifacts.sh"
    elif [[ -z "$amper_line" ]]; then
        fail "$file has no line matching '$first_amper' to order against"
    elif [[ "$check_line" -gt "$amper_line" ]]; then
        fail "$file checks the fork's artifacts on line $check_line, after the toolchain runs on line $amper_line"
    fi
}
check_order renderer/desktop/scripts/build-native-windows.ps1 'PublishWindow|KotlinWrapper[^=]*(run|build)'
check_order scripts/check.sh 'publish-window\.sh"|\./kotlin (build|test)'
check_order scripts/sample-shots.sh '\./kotlin (build|test|run)'

if [[ "$failures" -eq 0 ]]; then echo "  ok"; else exit 1; fi

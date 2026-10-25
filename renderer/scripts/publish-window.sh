#!/usr/bin/env bash
# Publishes the fork's window modules to the local Maven repository.
#
# Usage: publish-window.sh
#
# The window code is the Compose fork's, at the commit build-compose.sh pins. The renderer
# depends on it as org.thisisthepy.compose.window:<module>:1.11.1-ext.2, so the modules are built
# from that commit and published under ~/.m2 before the renderer is, the same way the patched
# Compose is. common is published on every machine. The GraalVM window modules and the scene
# they name are published wherever the fork's desktop Compose is in the local repository, which
# is every build of the renderer's desktop module, so a Linux or Windows checkout resolves the
# coordinates that module asks for. native/macos is published on a Mac and native/linux on
# Linux, because each is built from that system's headers, and windows-shared on Windows, the
# host the Windows renderer is built on.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$script_dir/../.." && pwd)"

fork_dir="$("$repo/scripts/fetch-fork-window.sh")"
window="$fork_dir/extended/window"

# Ensure the toolchain distribution is provisioned and its Ktor line limit is patched
# before entering the fork window directory.
if [[ -x "$repo/renderer/kotlin" ]]; then
    "$repo/renderer/kotlin" --version >/dev/null 2>&1 || true
fi

modules=(common)
# The GraalVM window modules name scene (compile-only, for the window surfaces runComposeWindow
# uses), and scene compiles against the fork's Compose for the desktop, which only a build that
# published it has (build-compose.sh, or the compose-fork-desktop artifact in CI). A
# Kotlin/Native build needs none of the three, and would fail resolving scene's Compose.
if [[ -d "$HOME/.m2/repository/org/thisisthepy/compose/ui/ui-desktop" ]]; then
    modules+=(scene graalvm/graalvm-macos graalvm/graalvm-linux)
fi
# native/macos compiles against the fork's Compose for macosArm64, which only a build that ran
# build-compose.sh --target macosArm64 has; a desktop-only build has no use for it.
case "$(uname -s)" in
    Darwin)
        if [[ -d "$HOME/.m2/repository/org/thisisthepy/compose/ui/ui-macosarm64" ]]; then
            modules+=(native/macos)
        fi ;;
    # The Windows window's Kotlin/Native layer, for mingwX64. Its C is compiled by MSVC from
    # the same fetched checkout (windows-shared/c) by the build that links it.
    MINGW*|MSYS*|CYGWIN*) modules+=(windows-shared) ;;
esac

# The fork's project lists every module, and a machine that cannot build one of them lists
# the rest. Written beside the fetched sources, which are this script's own.
cp "$window/project.yaml" "$window/project.yaml.all"
{
    echo "modules:"
    for module in "${modules[@]}"; do echo "  - $module"; done
} > "$window/project.yaml"
trap 'mv "$window/project.yaml.all" "$window/project.yaml"' EXIT

cd "$window"
# The common window module lists every platform the fork supports. A host cannot build native
# klibs for other operating systems, and compiling cross-OS native targets triggers native
# distribution commonization which requires those other toolchains. Tailor common's platforms
# to the current host: Darwin builds jvm and macosArm64; Linux builds jvm and linux targets;
# other systems build jvm alone.
case "$(uname -s)" in
    Darwin)
        if [[ -d "$HOME/.m2/repository/org/thisisthepy/compose/ui/ui-macosarm64" ]]; then
            sed -i.orig 's/^  platforms: \[.*\]$/  platforms: [ jvm, macosArm64 ]/' "$window/common/module.yaml"
        else
            sed -i.orig 's/^  platforms: \[.*\]$/  platforms: [ jvm ]/' "$window/common/module.yaml"
        fi
        rm -f "$window/common/module.yaml.orig"
        ;;
    Linux)
        if [[ "$(uname -m)" == "x86_64" && -d "$HOME/.m2/repository/org/thisisthepy/compose/ui/ui-linuxx64" ]]; then
            sed -i.orig 's/^  platforms: \[.*\]$/  platforms: [ jvm, linuxX64, linuxArm64 ]/' "$window/common/module.yaml"
        else
            sed -i.orig 's/^  platforms: \[.*\]$/  platforms: [ jvm ]/' "$window/common/module.yaml"
        fi
        rm -f "$window/common/module.yaml.orig"
        ;;
    MINGW*|MSYS*|CYGWIN*)
        sed -i.orig 's/^  platforms: \[.*\]$/  platforms: [ jvm, mingwX64 ]/' "$window/common/module.yaml"
        rm -f "$window/common/module.yaml.orig"
        ;;
    *)
        sed -i.orig 's/^  platforms: \[.*\]$/  platforms: [ jvm ]/' "$window/common/module.yaml"
        rm -f "$window/common/module.yaml.orig"
        ;;
esac
# scene is built for the JVM, which the GraalVM window modules compile against, and on a Mac that
# also has the fork's Compose for macosArm64 for macosArm64 as well, because native/macos depends
# on it. The Linux and Windows layers do not: native/linux-scene is published by
# publish-native-linux.sh with its own scene, and nothing else asks for scene's native halves.
if [[ " ${modules[*]} " == *" scene "* ]]; then
    scene_platforms="jvm"
    if [[ " ${modules[*]} " == *" native/macos "* ]]; then
        scene_platforms="jvm, macosArm64"
    fi
    # A suffix for -i and the copy removed after, because BSD sed on macOS takes the next
    # argument to -i as one.
    sed -i.orig "s/^  platforms: \\[.*\\]\$/  platforms: [ $scene_platforms ]/" "$window/scene/module.yaml"
    rm -f "$window/scene/module.yaml.orig"
fi
# The PATH kotlin.bat runs with on Windows: System32 first, and none of Git's own directories
# but Git\cmd (git itself). Started from Git Bash, kotlin.bat would otherwise unpack its toolchain
# with Git's GNU tar, which takes C:\... for a remote host, and the toolchain's launcher (busybox)
# would find Git's shasum, a Perl script it cannot run, and fail every download's checksum.
windows_tool_path() {
    local git_root entry native kept
    git_root="$(cygpath -w / | tr 'A-Z' 'a-z')"
    git_root="${git_root%\\}"
    kept="$(cygpath -u "${SYSTEMROOT:-C:\\Windows}")/System32"
    local IFS=:
    for entry in $PATH; do
        native="$(cygpath -w "$entry" 2>/dev/null | tr 'A-Z' 'a-z')"
        native="${native%\\}"
        if [[ "$native" == "$git_root" || "$native" == "$git_root"\\* ]] && [[ "$native" != "$git_root\\cmd" ]]; then
            continue
        fi
        kept="$kept:$entry"
    done
    echo "$kept"
}
"$script_dir/prefetch-github-assets.sh" || true
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) PATH="$(windows_tool_path)" cmd //c kotlin.bat publish mavenLocal ;;
    *) ./kotlin publish mavenLocal ;;
esac

# native/linux is published once for each architecture, under a coordinate of its own
# (native-linux-linuxx64 and native-linux-linuxarm64), the way the patched Compose is. Published as
# one module for both, the toolchain compiles the common metadata first, and that has none of the
# platform-specific dependencies the module names, so each architecture is built alone. It
# compiles against the patched Compose for linuxX64, which only a machine that ran build-compose.sh
# has, and the GraalVM image build has no use for it.
if [[ "$(uname -s)" == "Linux" && "$(uname -m)" == "x86_64" && -d "$window/native/linux" &&
      -d "$HOME/.m2/repository/org/thisisthepy/compose/ui/ui-linuxx64" ]]; then
    linux_module="$window/native/linux/module.yaml"
    cp "$linux_module" "$linux_module.all"
    trap 'mv "$linux_module.all" "$linux_module"; mv "$window/project.yaml.all" "$window/project.yaml"' EXIT
    printf 'modules:\n  - common\n  - native/linux\n' > "$window/project.yaml"
    for architecture in linuxX64 linuxArm64; do
        other=linuxArm64
        [[ "$architecture" == "linuxArm64" ]] && other=linuxX64
        # One platform, and without the other architecture's dependency block.
        awk -v drop="dependencies@$other:" -v arch="$architecture" '
            /^  platforms:/ { print "  platforms: [ " arch " ]"; next }
            $0 == drop { skipping = 1; next }
            skipping && NF == 0 { skipping = 0 }
            !skipping { print }
        ' "$linux_module.all" > "$linux_module"
        ./kotlin publish mavenLocal
    done
fi
echo "published the window modules from $fork_dir"

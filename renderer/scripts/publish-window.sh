#!/usr/bin/env bash
# Publishes the fork's window modules to the local Maven repository.
#
# Usage: publish-window.sh
#
# The window code is the Compose fork's, at the commit build-compose.sh pins. The renderer
# depends on it as org.thisisthepy.compose.window:<module>:0.1.0, so the modules are built
# from that commit and published under ~/.m2 before the renderer is, the same way the patched
# Compose is. The Kotlin/JVM modules are published on every machine, so a Linux or Windows
# checkout resolves the coordinates the renderer's desktop module asks for. native/macos is
# published on a Mac and native/linux on Linux, because each is built from that system's
# headers.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$script_dir/../.." && pwd)"

fork_dir="$("$repo/scripts/fetch-fork-window.sh")"
window="$fork_dir/extended/window"

modules=(common graalvm/graalvm-macos graalvm/graalvm-linux)
# native/macos compiles against the fork's Compose for macosArm64, which only a build that ran
# build-compose.sh --target macosArm64 has; a desktop-only build has no use for it.
case "$(uname -s)" in
    Darwin)
        if [[ -d "$HOME/.m2/repository/org/thisisthepy/compose/ui/ui-macosarm64" ]]; then
            modules+=(native/macos)
        fi ;;
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
# Kotlin/Native has no compiler for an arm64 Linux host, and the Kotlin toolchain has no way to
# publish only some targets of a module, so there the common module is published for the JVM
# alone. That is all the GraalVM image needs, and the fetched checkout is this script's own.
if [[ "$(uname -s)" == "Linux" && "$(uname -m)" != "x86_64" ]]; then
    sed -i 's/^  platforms: \[.*\]$/  platforms: [ jvm ]/' "$window/common/module.yaml"
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

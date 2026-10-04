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
case "$(uname -s)" in
    Darwin) modules+=(native/macos) ;;
    # native/linux compiles against the patched Compose for linuxX64, which only a machine that
    # ran build-compose.sh has. The GraalVM image build has no use for it.
    Linux)
        if [[ -d "$window/native/linux" && -d "$HOME/.m2/repository/org/jetbrains/compose/ui/ui-linuxx64" ]]; then
            modules+=(native/linux)
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
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) cmd //c kotlin.bat publish mavenLocal ;;
    *) ./kotlin publish mavenLocal ;;
esac
echo "published the window modules from $fork_dir"

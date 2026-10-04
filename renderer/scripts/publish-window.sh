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
    Linux) [[ -d "$window/native/linux" ]] && modules+=(native/linux) ;;
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
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) cmd //c kotlin.bat publish mavenLocal ;;
    *) ./kotlin publish mavenLocal ;;
esac
echo "published the window modules from $fork_dir"

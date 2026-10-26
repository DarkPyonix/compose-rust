#!/usr/bin/env bash
# renderer/scripts/publish-window.sh runs the fork's Kotlin toolchain wrapper. On Windows that
# is kotlin.bat, run from Git Bash (the Windows renderer build calls the script with bash).
# A batch file started from Git Bash inherits Git Bash's PATH, which puts Git's own Unix tools
# (/usr/bin) ahead of Windows'. kotlin.bat unpacks the toolchain with tar, and Git's GNU tar
# reads "C:\..." as a remote host called C: the first run on a fresh machine stopped with
# "tar (child): Cannot connect to C: resolve failed" and "could not publish the window modules
# from the Compose fork" (Native renderer run 38793394544).
#
# With System32 first that got further and stopped one step later (run 38810090140): the
# toolchain's launcher runs under busybox, finds Git's shasum (a Perl script) on the inherited
# PATH, cannot run it, and reports every download as a checksum mismatch ("Can't find shasum on
# PATH", "expected checksum ... but got "). So the wrapper must see none of Git's Unix tools.
#
# This runs publish-window.sh in a copy of the tree, with the fork fetch stubbed to a fork whose
# kotlin.bat (and ./kotlin elsewhere) only records which tar the wrapper would run, and checks:
#   - the script reaches the wrapper and succeeds;
#   - on Windows, the tar the wrapper finds first is Windows' own (System32), not Git's;
#   - on Windows, none of Git's Unix tools (shasum, sha256sum, sh) are on the wrapper's PATH.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
failures=0
fail() { echo "  FAIL: $1" >&2; failures=$((failures + 1)); }

echo "publish-window.sh runs the toolchain wrapper with the system's own tools first"

mkdir -p "$repo/.scratch"
work="$(mktemp -d "$repo/.scratch/publish-window-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

tree="$work/tree"
fork="$work/fork"
window="$fork/extended/window"
mkdir -p "$tree/renderer/scripts" "$tree/scripts" "$window/common" "$window/graalvm/graalvm-macos" \
    "$window/graalvm/graalvm-linux" "$window/native/macos" "$window/scene"
cp "$repo/renderer/scripts/publish-window.sh" "$tree/renderer/scripts/"
printf '#!/usr/bin/env bash\necho "%s"\n' "$fork" > "$tree/scripts/fetch-fork-window.sh"
chmod +x "$tree/scripts/fetch-fork-window.sh"
printf 'modules:\n  - common\n' > "$window/project.yaml"
printf 'product:\n  type: lib\n  platforms: [ jvm ]\n' > "$window/common/module.yaml"
# scene is published wherever the fork's desktop Compose is in the local repository, which this
# machine may have, so the fixture has the module the script edits.
printf 'product:\n  type: lib\n  platforms: [ jvm, macosArm64, linuxX64, linuxArm64 ]\n' > "$window/scene/module.yaml"
record="$work/wrapper.txt"
# The wrapper stubs: say they ran, and (on Windows) which tar.exe comes first on PATH.
printf '#!/usr/bin/env bash\necho "ran ./kotlin $*" > "%s"\n' "$record" > "$window/kotlin"
chmod +x "$window/kotlin"
native_record="$record"
if command -v cygpath > /dev/null; then native_record="$(cygpath -w "$record")"; fi
{
    printf '@echo off\r\necho ran kotlin.bat %%*> "%s"\r\n' "$native_record"
    for tool in tar.exe shasum sha256sum.exe sh.exe; do
        printf 'for %%%%i in (%s) do @echo %s=%%%%~$PATH:i>> "%s"\r\n' "$tool" "$tool" "$native_record"
    done
} > "$window/kotlin.bat"

if ! output="$(bash "$tree/renderer/scripts/publish-window.sh" 2>&1)"; then
    fail "publish-window.sh failed: $output"
fi
if [[ ! -f "$record" ]]; then
    fail "publish-window.sh never ran the toolchain wrapper"
else
    sed 's/^/  wrapper: /' "$record" | tr -d '\r'
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*)
            tar_line="$(grep '^tar.exe=' "$record" | tr -d '\r' | sed 's/^tar.exe/tar/')"
            system32="$(cygpath -w "${SYSTEMROOT:-C:\\Windows}\\System32" | tr 'A-Z' 'a-z')"
            found="$(tr 'A-Z' 'a-z' <<< "${tar_line#tar=}")"
            if [[ "$found" != "$system32\\tar.exe" ]]; then
                fail "the wrapper would run '${tar_line#tar=}', not $system32\\tar.exe"
            else
                echo "  ok: the wrapper runs Windows' tar"
            fi
            for tool in shasum sha256sum.exe sh.exe; do
                found="$(grep "^$tool=" "$record" | tr -d '\r')"
                found="${found#$tool=}"
                git_root="$(cygpath -w / | tr 'A-Z' 'a-z')"
                git_root="${git_root%\\}"
                if [[ "$(tr 'A-Z' 'a-z' <<< "$found")" == "$git_root"\\* ]]; then
                    fail "the wrapper would find Git's $tool at '$found'"
                else
                    echo "  ok: the wrapper does not find Git's $tool (found: '${found:-nothing}')"
                fi
            done
            ;;
        *) echo "  ok: not Windows, the wrapper is ./kotlin" ;;
    esac
fi
if [[ "$(cat "$window/project.yaml")" != "$(printf 'modules:\n  - common')" ]]; then
    fail "publish-window.sh left the fork's project.yaml changed"
fi

if (( failures > 0 )); then
    echo "publish-window-windows-tools: $failures failure(s)" >&2
    exit 1
fi
echo "publish-window-windows-tools: ok"

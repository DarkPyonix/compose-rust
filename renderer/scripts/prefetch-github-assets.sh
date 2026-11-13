#!/usr/bin/env bash
# Prefetches toolchain downloads hosted on GitHub Releases directly into Amper's
# download.cache using curl.
#
# GitHub Releases redirects with a Content-Security-Policy header line exceeding
# 8192 characters, which triggers io.ktor.utils.io.charsets.TooLongLineException
# inside Amper's Ktor HTTP client. Placing the archives in download.cache before
# Amper runs lets Amper use the cached files without contacting GitHub Releases.
set -euo pipefail

case "$(uname -s)" in
    Darwin)
        cache_dir="${1:-$HOME/Library/Caches/JetBrains/Kotlin/download.cache}"
        items=(
            "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_aarch64_mac_hotspot_25.0.5_8.tar.gz|f8362503fc-OpenJDK25U-jdk_aarch64_mac_hotspot_25.0.5_8.tar.gz"
            "https://github.com/pnpm/pnpm/releases/download/v11.9.0/pnpm-darwin-arm64.tar.gz|bfff255e35-pnpm-darwin-arm64.tar.gz"
            "https://github.com/cpisciotta/xcbeautify/releases/download/3.2.1/xcbeautify-3.2.1-arm64-apple-macosx.zip|c132514727-xcbeautify-3.2.1-arm64-apple-macosx.zip"
        )
        ;;
    Linux)
        cache_dir="${1:-$HOME/.cache/JetBrains/Kotlin/download.cache}"
        case "$(uname -m)" in
            x86_64)
                items=(
                    "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz|bd467c5b70-OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz"
                    "https://github.com/pnpm/pnpm/releases/download/v11.9.0/pnpm-linux-x64.tar.gz|d30df19002-pnpm-linux-x64.tar.gz"
                )
                ;;
            aarch64)
                items=(
                    "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_aarch64_linux_hotspot_25.0.5_8.tar.gz|e259c06f4c-OpenJDK25U-jdk_aarch64_linux_hotspot_25.0.5_8.tar.gz"
                )
                ;;
            *)
                items=()
                ;;
        esac
        ;;
    MINGW*|MSYS*|CYGWIN*)
        if [ -n "${LOCALAPPDATA:-}" ]; then
            win_cache="$(cygpath -u "$LOCALAPPDATA")/JetBrains/Kotlin/download.cache"
        else
            win_cache="$HOME/AppData/Local/JetBrains/Kotlin/download.cache"
        fi
        cache_dir="${1:-$win_cache}"
        items=(
            "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip|c93db5a50c-OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip"
        )
        ;;
    *)
        items=()
        ;;
esac

mkdir -p "$cache_dir"

for item in "${items[@]}"; do
    url="${item%%|*}"
    file="${item##*|}"
    dest="$cache_dir/$file"
    if [ ! -s "$dest" ]; then
        echo "prefetching $file..."
        tmp="$dest.tmp.$$"
        if curl -fSL --retry 3 --retry-delay 2 "$url" -o "$tmp"; then
            mv "$tmp" "$dest"
        else
            rm -f "$tmp"
            echo "warning: failed to prefetch $file" >&2
        fi
    fi
done

# Patch Ktor HTTP client's 8192-character header line limit in any extracted toolchain distributions.
cli_dir="$(dirname "$cache_dir")/cli"
if [ -d "$cli_dir" ] && command -v python3 >/dev/null 2>&1; then
    py_cli_dir="$cli_dir"
    command -v cygpath >/dev/null 2>&1 && py_cli_dir="$(cygpath -w "$cli_dir")"
    python3 -c "
import zipfile, os

cli_dir = r'$py_cli_dir'
for root, dirs, files in os.walk(cli_dir):
    for f in files:
        if f.startswith('ktor-http-cio') and f.endswith('.jar'):
            jar_path = os.path.join(root, f)
            try:
                with zipfile.ZipFile(jar_path, 'r') as zin:
                    entries = {item.filename: zin.read(item.filename) for item in zin.infolist()}
                cls_name = 'io/ktor/http/cio/HttpParserKt.class'
                if cls_name in entries:
                    b = bytearray(entries[cls_name])
                    old_long = b'\x00\x00\x00\x00\x00\x00\x20\x00'
                    new_long = b'\x00\x00\x00\x00\x00\x01\x00\x00'
                    old_sipush = b'\x11\x20\x00'
                    new_sipush = b'\x11\x7f\xff'
                    changed = False
                    if old_long in b:
                        b = bytearray(bytes(b).replace(old_long, new_long, 1))
                        changed = True
                    if old_sipush in b:
                        b = bytearray(bytes(b).replace(old_sipush, new_sipush, 1))
                        changed = True
                    if changed:
                        entries[cls_name] = bytes(b)
                        tmp_jar = jar_path + '.tmp'
                        with zipfile.ZipFile(tmp_jar, 'w', compression=zipfile.ZIP_DEFLATED) as zout:
                            for name, data in entries.items():
                                zout.writestr(name, data)
                        os.replace(tmp_jar, jar_path)
            except Exception:
                pass
" 2>/dev/null || true
fi


#!/usr/bin/env bash
# A compiler that native-image calls instead of cc, so the final link can be watched and
# then done twice.
#
# native-image can emit an executable, a shared library or a static executable. It cannot
# emit a static library, which is the one shape a Rust program wants: a crate can carry a
# `.a` and link it, and cannot reasonably carry a platform's `.dll` (oracle/graal#3053,
# open since 2020). The workaround people use is this one: intercept the compiler, let
# every compile through untouched, and when the link that produces the library comes past,
# make an archive out of the same inputs as well.
#
# `--native-compiler-path=<this>` installs it. Everything it does not recognise is
# forwarded, so a build with this in place produces what it produced before plus a record
# of the link and, where the inputs allow it, the archive.
#
# What this is for is finding out whether the inputs allow it at all. The answer is
# written to the report file rather than guessed at.
set -uo pipefail

real_cc="${DXC_REAL_CC:-/usr/bin/cc}"
report="${DXC_LINK_REPORT:-/tmp/dxc-native-link.txt}"

# The link that matters names a .dylib as its output. Everything else is a compile.
output=""
is_link=0
previous=""
for argument in "$@"; do
    if [[ "$previous" == "-o" ]]; then
        output="$argument"
        [[ "$argument" == *.dylib || "$argument" == *.so ]] && is_link=1
    fi
    previous="$argument"
done

if [[ "$is_link" -eq 1 ]]; then
    {
        echo "=== link seen $(date -u +%FT%TZ)"
        echo "output: $output"
        echo "argument count: $#"
        printf '%s\n' "$@"
    } >> "$report"
fi

# The real thing first, so the build finishes whatever happens next.
"$real_cc" "$@"
status=$?

if [[ "$is_link" -eq 1 && "$status" -eq 0 ]]; then
    # The same inputs, gathered into an archive. Only the object files and archives among
    # the arguments go in; a linker flag in an `ar` line is an error rather than a hint.
    inputs=()
    for argument in "$@"; do
        case "$argument" in
            *.o|*.a) [[ -f "$argument" ]] && inputs+=("$argument") ;;
        esac
    done
    archive="${output%.dylib}.a"
    archive="${archive%.so}.a"
    if [[ "${#inputs[@]}" -gt 0 ]]; then
        if libtool -static -o "$archive" "${inputs[@]}" 2>>"$report"; then
            echo "archive: $archive ($(wc -c < "$archive") bytes from ${#inputs[@]} inputs)" >> "$report"
        else
            echo "archive: libtool refused the inputs, see above" >> "$report"
        fi
    else
        echo "archive: no object or archive inputs on the link line" >> "$report"
    fi
fi

exit "$status"

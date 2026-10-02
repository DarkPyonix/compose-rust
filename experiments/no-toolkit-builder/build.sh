#!/usr/bin/env bash
# Compiles the replacement the image build patches into the builder, and prints the flag
# that uses it.
#
# Usage: ./build.sh [output-dir]
#
#   DXC_EXTRA_NI_FLAGS="$(experiments/no-toolkit-builder/build.sh)" ./desktop/scripts/build-native.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${1:-$here/out}"
source "$here/../../dioxus-compose-renderer/desktop/scripts/env.sh" 2>/dev/null || true
: "${GRAALVM_HOME:?set GRAALVM_HOME, or run desktop/scripts/env.sh first}"

rm -rf "$out"
mkdir -p "$out/src/com/oracle/svm/hosted/jdk" "$out/classes"
cp "$here/JNIRegistrationAwt.java" "$out/src/com/oracle/svm/hosted/jdk/"

"$GRAALVM_HOME/bin/javac" \
    --module-path "$GRAALVM_HOME/lib/svm/builder" \
    --patch-module org.graalvm.nativeimage.builder="$out/src" \
    --add-modules org.graalvm.nativeimage.builder \
    -d "$out/classes" \
    "$out/src/com/oracle/svm/hosted/jdk/JNIRegistrationAwt.java"

echo "-J--patch-module=org.graalvm.nativeimage.builder=$out/classes"

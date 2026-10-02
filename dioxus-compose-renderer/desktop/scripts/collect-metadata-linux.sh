#!/usr/bin/env bash
# Collects Linux-only agent evidence without merging it into the macOS-derived checked-in
# metadata. The agent records only exercised paths. Click, type Korean through ibus or fcitx,
# resize, and close the window. Review the result before any manual merge.
# UNTESTED ON LINUX as of 2026-09-20.
set -euo pipefail
source "$(dirname "$0")/env-linux.sh"

output="${LINUX_METADATA_OUTPUT:-$BUILD_DIR/linux-agent-metadata}"
mkdir -p "$output"
agent="$GRAALVM_HOME/lib/libnative-image-agent.so"
[[ -f "$agent" ]] || die "native-image agent not found at $agent"
[[ -n "${DISPLAY:-}" ]] || die "DISPLAY is not set; metadata collection needs a real X11/XWayland session" \
    "Do not use Xvfb for IME evidence because it has no ibus/fcitx desktop session."
DIOXUS_COMPOSE_AUTOEXIT_MS="${DIOXUS_COMPOSE_AUTOEXIT_MS:-60000}" run_on_jvm \
    "-agentpath:$agent=config-merge-dir=$output"
echo "UNTESTED Linux metadata: $output"
echo "Coverage is limited to the interactions performed during this run."

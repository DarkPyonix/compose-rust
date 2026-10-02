#!/usr/bin/env bash
# Runs the renderer on the NIK JVM under the tracing agent and merges what it records into
# the checked-in reachability metadata. The agent records only the code paths that run,
# so interact with the window (click, type Korean) before it closes.
set -euo pipefail
source "$(dirname "$0")/env.sh"

mkdir -p "$METADATA_DIR"
DIOXUS_COMPOSE_AUTOEXIT_MS="${DIOXUS_COMPOSE_AUTOEXIT_MS:-30000}" run_on_jvm \
    "-agentpath:$GRAALVM_HOME/lib/libnative-image-agent.dylib=config-merge-dir=$METADATA_DIR"
echo "metadata: $METADATA_DIR"

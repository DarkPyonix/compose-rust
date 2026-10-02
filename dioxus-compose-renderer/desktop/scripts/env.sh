# Shared settings for the native build scripts. Sourced, not executed.

NATIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="$(cd "$NATIVE_DIR/.." && pwd)"
BUILD_DIR="$PROJECT_DIR/build/native-image"
DIST_DIR="$BUILD_DIR/dist"
LIBRARY_NAME="libdioxus_compose_renderer"
METADATA_DIR="$NATIVE_DIR/resources/META-INF/native-image/dioxus.compose/dioxus-compose-renderer"

die() {
    echo "error: $1" >&2
    shift
    local line
    for line in "$@"; do echo "       $line" >&2; done
    exit 1
}

# Every script here builds or links a macOS shared library (.dylib, AppKit, Skiko JNI).
if [[ "$(uname -s)" != "Darwin" ]]; then
    die "only macOS is scripted so far (this is $(uname -s))" \
        "Linux and Windows native-image builds are not scripted yet." \
        "The Rust workspace and the JVM dev shell (./kotlin run -m desktop) work everywhere."
fi

HOST_ARCH="$(uname -m)"
case "$HOST_ARCH" in
    arm64)  SKIKO_ARCH=arm64 ;;
    x86_64) SKIKO_ARCH=x64 ;;
    *) die "unsupported macOS architecture '$HOST_ARCH'" \
           "Only arm64 and x86_64 are supported; Skiko publishes no other macOS build." ;;
esac

# The C shims and the native-image link step need cc, ld and the AppKit headers.
if ! xcode-select -p >/dev/null 2>&1 || ! command -v cc >/dev/null 2>&1; then
    die "Xcode command line tools not found" \
        "They provide cc, ld and the AppKit headers used by desktop/c/*." \
        "fix: xcode-select --install"
fi

# macOS needs Liberica NIK Full: upstream GraalVM skips AWT on Darwin (oracle/graal#13272).
NIK_INSTALL_HINT=(
    "Install Liberica NIK 25 Full (the 'Full' variant, not the standard one):"
    "  https://bell-sw.com/pages/downloads/native-image-kit/"
    "  or: brew install --cask liberica-nik-full"
    "Scripts use \$GRAALVM_HOME when set, otherwise the newest match of"
    "  ~/Library/Java/JavaVirtualMachines/bellsoft-liberica-vm-full-openjdk25*/Contents/Home"
    "Run ../../scripts/setup-check.sh from the repository root to verify the toolchain."
)

if [[ -z "${GRAALVM_HOME:-}" ]]; then
    for candidate in "$HOME"/Library/Java/JavaVirtualMachines/bellsoft-liberica-vm-full-openjdk25*/Contents/Home; do
        [[ -x "$candidate/bin/native-image" ]] && GRAALVM_HOME="$candidate"
    done
    [[ -n "${GRAALVM_HOME:-}" ]] || die \
        "no Liberica NIK 25 Full found, and GRAALVM_HOME is not set" "${NIK_INSTALL_HINT[@]}"
elif [[ ! -d "$GRAALVM_HOME" ]]; then
    die "GRAALVM_HOME=$GRAALVM_HOME does not exist" "${NIK_INSTALL_HINT[@]}"
fi

[[ -x "$GRAALVM_HOME/bin/native-image" ]] || die \
    "GRAALVM_HOME=$GRAALVM_HOME has no executable bin/native-image" "${NIK_INSTALL_HINT[@]}"

# The static AWT archive is the thing upstream GraalVM is missing on Darwin. Checking it
# here turns an unreadable link failure minutes into the build into an immediate message.
AWT_STATIC_ARCHIVE=""
for candidate in "$GRAALVM_HOME"/lib/static/darwin-*/libawt_lwawt.a; do
    [[ -f "$candidate" ]] && AWT_STATIC_ARCHIVE="$candidate"
done
[[ -n "$AWT_STATIC_ARCHIVE" ]] || die \
    "$GRAALVM_HOME has no lib/static/darwin-*/libawt_lwawt.a" \
    "This is upstream GraalVM or a non-Full NIK. On macOS it skips AWT entirely" \
    "(oracle/graal#13272), so Compose Desktop cannot be linked into a native image." \
    "${NIK_INSTALL_HINT[@]}"

KOTLIN_WRAPPER="$PROJECT_DIR/kotlin"
[[ -x "$KOTLIN_WRAPPER" ]] || die \
    "$KOTLIN_WRAPPER is missing or not executable" \
    "It is the self-bootstrapping Kotlin Toolchain wrapper; no separate install is needed." \
    "fix: chmod +x $KOTLIN_WRAPPER"

CLASSPATH_FILE="$BUILD_DIR/classpath.txt"

# Runs the native module on the JVM through the Kotlin Toolchain, which resolves the runtime
# classpath correctly. (Its executable jar does not: JetBrains and Google both publish a
# `lifecycle-common-jvm-2.11.0.jar`, and the second overwrites the first inside the jar.)
#
# The JVM prints its properties at startup; java.class.path is captured from that output
# into $CLASSPATH_FILE so native-image builds against exactly what ran.
run_on_jvm() {
    local extra_jvm_args="$1"
    local log="$BUILD_DIR/jvm-run.log"
    mkdir -p "$BUILD_DIR"
    # Metadata describes the JDK it was collected on, so the JVM run uses NIK itself.
    #
    # The exit status is deliberately not fatal here, and the reason is where the class
    # path comes from: `-XshowSettings:properties` is printed by the JVM at startup,
    # before main runs, so it is already in the log by the time anything opens a window.
    # This function is called by the native-image build for that one line, and opening a
    # Compose window to read it is a lot of machinery to stand on. A hosted macOS runner
    # killed one of these with SIGTRAP while nothing in the tree had changed for that
    # platform, and the build failed for want of a string it had already read.
    #
    # So a run that dies after printing is reported and accepted. A run that dies before
    # printing leaves the file empty and still fails below. Whether the renderer actually
    # works is not this function's question: the smoke test that runs the built image is,
    # and it runs either way.
    local jvm_status=0
    (cd "$PROJECT_DIR" && JAVA_HOME="$GRAALVM_HOME" ./kotlin run -m desktop --no-compose-hot-reload \
        --jvm-args="-XshowSettings:properties $extra_jvm_args") 2>&1 | tee "$log" >&2 || jvm_status=$?
    awk '
        /^ *java\.class\.path = / { sub(/^ *java\.class\.path = /, ""); print; collecting = 1; next }
        collecting && /^ {8,}[^ ]/ { sub(/^ +/, ""); print; next }
        collecting { exit }
    ' "$log" | paste -sd: - > "$CLASSPATH_FILE"
    if [[ ! -s "$CLASSPATH_FILE" ]]; then
        echo "error: could not read java.class.path from $log" >&2
        [[ "$jvm_status" -eq 0 ]] || echo "       the JVM run also exited with $jvm_status" >&2
        exit 1
    fi
    [[ "$jvm_status" -eq 0 ]] || echo \
        "warning: the JVM run exited with $jvm_status after printing its class path; continuing" >&2
}

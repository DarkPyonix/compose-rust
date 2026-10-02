# Shared settings for Linux native build scripts. Sourced, not executed.
#
# UNTESTED ON LINUX as of 2026-09-20. Verify first with:
#   GRAALVM_HOME=/path/to/graalvm-jdk-25 ./desktop/scripts/build-native-linux.sh

NATIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="$(cd "$NATIVE_DIR/.." && pwd)"
BUILD_DIR="$PROJECT_DIR/build/native-image-linux"
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

if [[ "$(uname -s)" != "Linux" ]]; then
    die "build-native-linux.sh requires Linux (this is $(uname -s))" \
        "It builds ELF shared objects and uses the Linux X11 AWT toolkit."
fi

HOST_ARCH="$(uname -m)"
case "$HOST_ARCH" in
    x86_64)         SKIKO_ARCH=x64 ;;
    aarch64|arm64)  SKIKO_ARCH=arm64 ;;
    *) die "unsupported Linux architecture '$HOST_ARCH'" \
           "Only x86_64 and aarch64 have matching Skiko runtime artifacts." ;;
esac

LINUX_PACKAGES=(build-essential zlib1g-dev unzip pkg-config fontconfig libx11-dev libxext-dev
                libxi-dev libxrender-dev libxtst-dev libxrandr-dev libfontconfig1-dev
                libfreetype6-dev libgl1-mesa-dev fonts-noto-cjk)

for tool in cc nm readelf unzip pkg-config fc-match; do
    command -v "$tool" >/dev/null 2>&1 || die "required tool '$tool' was not found" \
        "On Ubuntu 24.04: sudo apt-get install ${LINUX_PACKAGES[*]}"
done

# gl is the window's own: it draws through GLX, which is the headers in libgl1-mesa-dev
# and the library -lGL. The others are what the AWT toolkit and Skia need.
for package in x11 xext xi xrender xtst xrandr gl fontconfig freetype2; do
    pkg-config --exists "$package" || die "pkg-config cannot find '$package'" \
        "On Ubuntu 24.04: sudo apt-get install ${LINUX_PACKAGES[*]}"
done

[[ -n "${GRAALVM_HOME:-}" ]] || die "GRAALVM_HOME is not set" \
    "Install upstream Oracle GraalVM or GraalVM Community for JDK 25." \
    "Liberica NIK Full is not expected to be necessary on Linux." \
    "Then export GRAALVM_HOME=/path/to/graalvm-jdk-25."
[[ -d "$GRAALVM_HOME" ]] || die "GRAALVM_HOME=$GRAALVM_HOME does not exist"
[[ -x "$GRAALVM_HOME/bin/native-image" ]] || die \
    "GRAALVM_HOME=$GRAALVM_HOME has no executable bin/native-image" \
    "Use a GraalVM for JDK 25 distribution that includes Native Image."

native_image_version="$($GRAALVM_HOME/bin/native-image --version 2>&1)"
grep -Eq '(^|[^0-9])25([. +]|$)' <<< "$native_image_version" || die \
    "Native Image must use JDK 25; got: $native_image_version" \
    "The renderer compiles against org.graalvm.sdk:nativeimage:25.0.1."

KOTLIN_WRAPPER="$PROJECT_DIR/kotlin"
[[ -x "$KOTLIN_WRAPPER" ]] || die "$KOTLIN_WRAPPER is missing or not executable" \
    "fix: chmod +x $KOTLIN_WRAPPER"

CLASSPATH_FILE="$BUILD_DIR/classpath.txt"

# Resolving the platform classpath starts the Compose JVM application briefly. It needs an
# X11 display even though no human interaction is required. On a headless builder, wrap the
# calling script in xvfb-run. Xvfb does not validate Wayland or an input method.
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

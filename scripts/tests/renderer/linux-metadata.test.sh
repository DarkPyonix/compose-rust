#!/usr/bin/env bash
# Static contract for how the Linux image gets its AWT classes into the JNI tables. It runs
# on macOS, and the Linux job runs it before the build, because the failure it guards
# against costs a full native-image build to observe.
#
# The failure it is written against: the Linux smoke host started and died with
#   java.lang.NoClassDefFoundError: java/awt/GraphicsEnvironment
#       at ...JNIFunctions.FindClass
#       at ...JNILibraryInitializer.initialize
#       at java.awt.Toolkit.loadLibraries
# libawt's JNI_OnLoad resolves that class to choose between the headless and the X11
# toolkit. Staging libawt_xawt.so does not register anything; a class that is not in the
# image's JNI tables is not found however plainly the JDK declares it.
set -euo pipefail

# `! grep ...` cannot fail a script: the shell ignores `set -e` for any command preceded by
# the `!` reserved word, so every negative assertion written that way is inert and reports
# success no matter what the file contains. Say it the long way instead.
absent() {
    local pattern="$1" file="$2" why="$3"
    # Comments are stripped first. Half of what these scripts say about a flag is the
    # paragraph explaining why it is not used any more, and matching that would make the
    # assertion fire on its own explanation.
    if sed 's/[[:space:]]*#.*$//' "$file" | grep -q -- "$pattern"; then
        echo "FAIL: $(basename "$file") still uses '$pattern'." >&2
        echo "      $why" >&2
        exit 1
    fi
}

scripts_dir="$(cd "$(dirname "$0")/../../../renderer/desktop/scripts" && pwd)"
native_dir="$(cd "$scripts_dir/.." && pwd)"

linux_build="$scripts_dir/build-native-linux.sh"
windows_build="$scripts_dir/build-native-windows.ps1"
# Read on every platform because it sits on the classpath, so it is where registrations
# shared by macOS, Linux and Windows belong.
shared_metadata="$native_dir/resources/META-INF/native-image/dev.darkpyonix.composerust/renderer/reachability-metadata.json"

for file in "$linux_build" "$windows_build" "$shared_metadata"; do
    [[ -f "$file" ]] || { echo "error: missing $file" >&2; exit 1; }
done

bash -n "$linux_build"

# The Linux image has no Java toolkit, so libawt's JNI_OnLoad is never reached and neither the
# preserved java.desktop module nor the input method and accessibility features are wanted.
# The check that none of them comes back is scripts/tests/no-awt-on-linux-path.test.sh.
absent '-H:Preserve=module=java.desktop' "$linux_build" \
    "Preserving the module keeps the whole Java toolkit in the image"
absent 'ReachabilityFeature' "$linux_build" \
    "Those features register the toolkit's input method and accessibility classes"

# The Windows overlay is a list of sun.awt.windows and sun.java2d.windows classes. Pointing
# the Linux build at it would register nothing that exists on Linux.
absent 'windows-metadata' "$linux_build" \
    "That overlay is a list of sun.awt.windows classes, none of which exist on Linux"

# Windows keeps its two configuration directories. It used to preserve java.desktop as
# well, which is what got that image past Toolkit.getDefaultToolkit and into the next
# reflective lookup, where Swing asks UIManager for a ComponentUI by name and finds no look
# and feel class in the image.
#
# It does not preserve the module any more, because it builds with NIK now and links the
# JDK's desktop libraries in rather than shipping them beside it, which is what lets the
# same accessibility feature macOS uses register what is actually reached. Preserving a
# whole JDK module was the reason the Windows image was 101.6MB against macOS's 70.5MB.
#
# So what is required here is that ONE of the two answers is present, the way the Linux
# check above is written: either the module is preserved, or the feature that registers
# the reached classes is on. Neither would be an image that cannot start.
grep -q 'ConfigurationFileDirectories=$MetadataDir,$ResourceMetadataDir,$ToolkitMetadataDir' "$windows_build"
if ! grep -q -- '-H:Preserve=module=java.desktop' "$windows_build" &&
    ! grep -q 'AccessibilityReachabilityFeature' "$windows_build"; then
    echo "fail  the Windows build neither preserves java.desktop nor registers the" >&2
    echo "      classes AWT reaches. One of the two has to be true or the image cannot" >&2
    echo "      start: Swing looks a look and feel up by class name, and a class nobody" >&2
    echo "      references is not in the image." >&2
    exit 1
fi

python3 - "$shared_metadata" <<'PY'
import json
import sys

metadata = json.load(open(sys.argv[1], encoding="utf-8"))
entries = {entry["type"]: entry for entry in metadata["reflection"]}

# Skiko and Skia are not part of java.desktop, so preserving that module does not reach them.
# These come from the classpath metadata on every platform, and Skia's native code resolves
# them through JNI on the first frame.
required = {"org.jetbrains.skia.impl.Native", "org.jetbrains.skia.Rect"}
missing = sorted(required - entries.keys())
if missing:
    raise SystemExit("shared classpath metadata is missing: " + ", ".join(missing))
# Registering a toolkit type for reflection or JNI makes it reachable, and with it the native
# library its static initialiser loads. The registrations live in toolkit-metadata, which only
# the Windows build reads.
toolkit = sorted(n for n in entries if n.startswith(("java.awt", "sun.awt", "sun.java2d", "sun.lwawt", "javax.swing", "androidx.compose.ui.awt", "org.jetbrains.skiko.SkiaLayer", "org.jetbrains.skiko.HardwareLayer", "androidx.compose.ui.scene.ComposeSceneMediator$Invisible", "androidx.compose.ui.scene.skia.")))
if toolkit:
    raise SystemExit("the shared classpath metadata registers toolkit types: " + ", ".join(toolkit))
if not entries["org.jetbrains.skia.impl.Native"].get("jniAccessible"):
    raise SystemExit("org.jetbrains.skia.impl.Native must be registered as JNI accessible")

# The Linux redrawers declare nothing that Skiko's native code calls back into, unlike
# MetalRedrawer.onOcclusionStateChanged on macOS and Direct3DRedrawer.isAdapterSupported on
# Windows, both of which are registered. If a Linux redrawer ever appears here, the comment
# in build-native-linux.sh that explains why Linux needs no Skiko overlay is out of date.
for name in entries:
    if "redrawer.Linux" in name:
        raise SystemExit("unexpected Linux redrawer registration: " + name)
PY

echo "linux metadata contract: ok"

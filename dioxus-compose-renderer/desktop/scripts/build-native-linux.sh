#!/usr/bin/env bash
# Builds the renderer as a Linux native shared library and stages dist/lib.
#
# UNTESTED ON LINUX as of 2026-09-20. This script encodes the expected upstream GraalVM
# Linux AWT layout, but no Linux native-image build or target run was available here.
# Verify it with the exact commands in linux-build-evidence.md.
set -euo pipefail
source "$(dirname "$0")/env-linux.sh"

[[ -f "$NATIVE_DIR/c/renderer_entry.c" ]] || die "missing $NATIVE_DIR/c/renderer_entry.c"

# Unlike Darwin, upstream GraalVM supports Linux AWT. Its Native Image feature registers
# awt_xawt and writes the dynamic AWT libraries and libjava/libjvm shims beside the image.
# Do not force-load libawt_xawt.a and do not add the macOS placeholder or JNI_OnLoad_osxui.
#
# The JNI half of that support still has to be asked for. Staging the libraries only decides
# which ones load; it does not put any class in the image's JNI tables, and libawt's
# JNI_OnLoad starts by resolving java/awt/GraphicsEnvironment with FindClass to decide
# between the headless and the X11 toolkit. Without a registration that FindClass returns
# null and the process dies with NoClassDefFoundError on a class the JDK plainly has, inside
# Toolkit.loadLibraries, before any window exists. See the preserve flag below.
DIOXUS_COMPOSE_AUTOEXIT_MS=1 run_on_jvm ""
classpath="$(cat "$CLASSPATH_FILE")"
obj="$BUILD_DIR/obj"
lib="$DIST_DIR/lib"
rm -rf "$DIST_DIR" "$obj"
mkdir -p "$obj" "$lib"

cc -c -O2 -fPIC -o "$obj/renderer_entry.o" "$NATIVE_DIR/c/renderer_entry.c"
cc -c -O2 -fPIC -o "$obj/x11_window.o" "$NATIVE_DIR/c/x11_window.c"

# Why the C shim is not handed to native-image here, the way build-native.sh does on macOS.
#
# When Native Image links a shared library on Linux it always writes its own linker version
# script and passes it as -Wl,--version-script=<file>. That script lists the image's own
# @CEntryPoint symbols under "global:" and ends with "local: *;", so any symbol that Native
# Image did not generate itself becomes local no matter how it got into the link, and the
# -Wl,-x that follows drops local entries from the symbol table altogether. That is why the
# shim's two functions were missing from even the full symbol table, and why neither
# --export-dynamic-symbol nor -u brought them back: a version script's "local: *" cannot be
# overridden by another command-line flag, and GNU ld refuses a second --version-script when
# the first one is anonymous, which the generated one is. The version script is written in
# CCLinkerInvocation.java in the GraalVM sources (the shared-library branch that produces
# exported_symbols.list); the second-script refusal is binutils' "anonymous version tag
# cannot be combined with other version tags".
#
# So Native Image builds the image under its own name and this script performs the final
# link itself. Nothing below depends on how native-image forwards linker arguments: the shim
# object and the image library sit on a plain cc command whose output is the library the Host
# loads. macOS keeps using -H:NativeLinkerOption, where -exported_symbol does work.
image_name="${LIBRARY_NAME}_image"

# The Host's dioxus_compose_host_* functions remain unresolved until the application loads
# the renderer. $ORIGIN lets GraalVM's generated shims and AWT libraries find the
# renderer and one another in the staged lib directory. The soname keeps the wrapper's
# DT_NEEDED entry a bare file name, so the staged directory stays relocatable.
#
# -H:Preserve=module=java.desktop is how the AWT classes reach the image's reflection and JNI
# tables. It is what GraalVM itself passes to build its own non-headless java.desktop
# integration test, which is the only AWT image upstream runs on Linux, and it is documented
# as removing the need to write reachability metadata for what it covers. The alternative
# would be a hand-written list of the X11 toolkit classes that libawt_xawt calls back into,
# and nobody here can run Linux to find out where such a list stops. It costs build time and
# image size, which is what -Os above is for, and the Linux job prints the staged size.
#
# The Compose, Skiko and Skia registrations are not part of java.desktop. They come from
# desktop/resources/META-INF/native-image, which is on the classpath and is therefore read on
# every platform without a -H:ConfigurationFileDirectories argument.
#
# The window draws its own frame when it is resized, and it does that by calling a function
# in the image through a pointer. The pointer is a CEntryPointLiteral, which Native Image
# fills in while it builds the image and only for a literal that is already in the image
# heap: the class holding it has to be initialised here rather than when the library starts,
# or the pointer stays null and every resize silently draws nothing. Asked for by name so
# that a class which cannot be initialised at build time fails this build instead.
(cd "$lib" && "$GRAALVM_HOME/bin/native-image" \
    --initialize-at-build-time=dioxus.compose.ui.platform.X11FrameCallback \
    --shared \
    -cp "$classpath" \
    -o "$image_name" \
    --no-fallback \
    --features=dioxus.compose.ui.platform.ImeReachabilityFeature \
    -Djava.awt.headless=false \
    -H:IncludeLocales=en,ko \
    -Os \
    -H:+UnlockExperimentalVMOptions \
    -H:Preserve=module=java.desktop \
    "-H:NativeLinkerOption=$obj/x11_window.o" \
    '-H:NativeLinkerOption=-lX11' \
    '-H:NativeLinkerOption=-lGL' \
    '-H:NativeLinkerOption=-lXext' \
    "-H:NativeLinkerOption=-Wl,-soname,$image_name.so" \
    '-H:NativeLinkerOption=-Wl,-rpath,$ORIGIN')

# These files are emitted by upstream Native Image when AWT is reachable. Fail here instead
# of shipping an image that later resolves its toolkit against a developer JDK.
for runtime_file in "$image_name.so" libawt.so libawt_headless.so libawt_xawt.so \
                    libfontmanager.so libjava.so libjvm.so; do
    [[ -f "$lib/$runtime_file" ]] || die "Native Image did not emit $runtime_file" \
        "This upstream GraalVM Linux AWT layout is untested for the selected JDK build." \
        "Keep $BUILD_DIR and report: $GRAALVM_HOME/bin/native-image --version"
done

# The shim calls these five. Check them before linking, so a rename or a dropped export is
# reported as itself rather than as an undefined reference in the middle of a cc command.
for required_symbol in graal_create_isolate graal_attach_thread graal_get_current_thread \
                       dioxus_compose_renderer_run_impl \
                       dioxus_compose_renderer_request_frame_impl; do
    nm -D "$lib/$image_name.so" | grep -Eq " [TW] ${required_symbol}$" && continue
    echo "-- dynamic symbol table of $image_name.so (graal_*, dioxus_*)" >&2
    nm -D "$lib/$image_name.so" | grep -E "graal_|dioxus_" >&2 || echo "   (none)" >&2
    die "$image_name.so does not export $required_symbol" \
        "The C shim forwards to it, so the public library cannot be linked without it."
done

# The library the Host loads: the argument-free C ABI, linked against the image library.
cc -shared -fPIC -pthread -o "$lib/$LIBRARY_NAME.so" "$obj/renderer_entry.o" \
    -L"$lib" -Wl,--no-as-needed "-l${image_name#lib}" -Wl,-rpath,'$ORIGIN'

for exported_symbol in dioxus_compose_renderer_run dioxus_compose_renderer_request_frame; do
    nm -D "$lib/$LIBRARY_NAME.so" | grep -Eq " [TW] ${exported_symbol}$" && continue
    # Say which of the two failure modes this is. The symbol can be missing entirely, which
    # means the C shim was not linked in, or it can be present but local, which means the
    # shared-library link hid it. The remedies have nothing in common, so print the evidence
    # rather than leaving the next reader to rebuild for twenty minutes to see it.
    echo "-- dynamic symbol table (dioxus_*)" >&2
    nm -D "$lib/$LIBRARY_NAME.so" | grep dioxus_ >&2 || echo "   (none)" >&2
    echo "-- full symbol table (dioxus_compose_renderer_*)" >&2
    nm "$lib/$LIBRARY_NAME.so" 2>/dev/null | grep dioxus_compose_renderer_ >&2 || echo "   (none)" >&2
    die "$LIBRARY_NAME.so does not export $exported_symbol" \
        "Keep $BUILD_DIR and inspect the cc -shared command in this script."
done

# A DT_NEEDED entry carrying a build-host path would make the staged directory unusable
# anywhere else, and that failure would only surface when someone runs the shipped bundle.
needed="$(readelf -d "$lib/$LIBRARY_NAME.so" | grep NEEDED | grep "$image_name" || true)"
[[ "$needed" == *"[$image_name.so]"* ]] || die \
    "$LIBRARY_NAME.so does not depend on $image_name.so by bare file name" \
    "readelf -d reported: ${needed:-no matching NEEDED entry}" \
    "Check that -Wl,-soname reached the Native Image link."

# Skiko loads JAWT from <java.home>/lib. Native Image may emit it when it sees the load. If
# it does not, stage the matching library from the same GraalVM. This fallback is untested.
if [[ ! -f "$lib/libjawt.so" ]]; then
    [[ -f "$GRAALVM_HOME/lib/libjawt.so" ]] || die "libjawt.so was neither emitted nor found in GraalVM" \
        "Looked for $lib/libjawt.so and $GRAALVM_HOME/lib/libjawt.so."
    cp "$GRAALVM_HOME/lib/libjawt.so" "$lib/libjawt.so"
fi

skiko_jar="$(tr ':' '\n' <<< "$classpath" | grep "skiko-awt-runtime-linux-$SKIKO_ARCH" | head -1)"
[[ -n "$skiko_jar" ]] || die "no skiko-awt-runtime-linux-$SKIKO_ARCH jar on the runtime classpath" \
    "Check $CLASSPATH_FILE and the compose dependency in desktop/module.yaml."
skiko_library="libskiko-linux-$SKIKO_ARCH.so"
unzip -q -o -j "$skiko_jar" "$skiko_library" -d "$lib"
[[ -f "$lib/$skiko_library" ]] || die "$skiko_library was not present in $skiko_jar"

# Font lookup is delegated to the target system's fontconfig. Skia is bundled, fonts and
# fontconfig are not. Confirm that this build host has a Korean-capable fallback, then require
# the target to install equivalent packages such as fontconfig and fonts-noto-cjk.
cjk_font="$(fc-match -f '%{family}\n' 'sans:lang=ko' | head -1)"
[[ -n "$cjk_font" ]] || die "fontconfig found no Korean-capable sans font" \
    "On Ubuntu 24.04: sudo apt-get install fontconfig fonts-noto-cjk"
echo "fontconfig Korean fallback (build host only): $cjk_font"

mkdir -p "$DIST_DIR/include"
mv "$lib"/*.h "$DIST_DIR/include/" 2>/dev/null || true
rm -f "$lib"/*.md

echo "UNTESTED: build artifacts exist, but they have not been executed on Linux."
echo "Verify: DIOXUS_COMPOSE_AUTOEXIT_MS=5000 $NATIVE_DIR/scripts/smoke-test-linux.sh"
ls -la "$lib"

# The schema this renderer was generated from, written beside it, so a build script that
# pairs a program with this distribution can see the two disagree before the program runs.
# The handshake catches it as well, but by then the window is open and empty, which is what
# a white window on Windows turned out to be.
schema_hash_decimal="$(
    grep -o 'const val SCHEMA_HASH: Long = -\?[0-9]*' \
        "$NATIVE_DIR/src/protocol/Protocol.gen.kt" |
        grep -o -- '-\?[0-9]*$'
)"
# printf rather than awk. The hash fills all 64 bits and awk works in doubles, which
# rounded one off by 118 and produced a file that disagreed with itself.
printf '0x%016x\n' "$schema_hash_decimal" > "$DIST_DIR/schema-hash.txt"

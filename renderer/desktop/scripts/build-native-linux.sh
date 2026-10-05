#!/usr/bin/env bash
# Builds the renderer as a Linux native shared library and stages dist/lib.
#
# Verify it with the exact commands in linux-build-evidence.md.
set -euo pipefail
source "$(dirname "$0")/env-linux.sh"

[[ -f "$NATIVE_DIR/c/renderer_entry.c" ]] || die "missing $NATIVE_DIR/c/renderer_entry.c"

# The X11 window is the Compose fork's: its C is compiled from the checkout at the pinned
# commit, and its Kotlin is the published module the renderer's desktop module depends on.
"$PROJECT_DIR/scripts/publish-window.sh"
fork_window="$("$PROJECT_DIR/../scripts/fetch-fork-window.sh")/extended/window"
x11_source="$fork_window/graalvm/graalvm-linux/c/x11_window.c"
[[ -f "$x11_source" ]] || die "missing $x11_source"

# The window is the X11 one this renderer makes itself, so the image carries no Java toolkit:
# no libawt, libawt_xawt or libawt_headless beside it, no input method or accessibility
# registration, and no preserved java.desktop module. Text input comes from XIM in the
# window's own C. Nothing on this path may name java.awt, and
# scripts/tests/no-awt-on-unix-path.test.sh fails the build when something does.
COMPOSE_RUST_AUTOEXIT_MS=1 run_on_jvm ""
classpath="$(cat "$CLASSPATH_FILE")"
obj="$BUILD_DIR/obj"
# Swing's coroutine provider is left out of the image. It is a main dispatcher that wakes the
# Java toolkit, and the window here answers `Dispatchers.Main` itself (FrameMainDispatcher).
# With the provider on the class path it is still found, and everything it reaches comes with it.
classpath="$(tr ':' '\n' <<< "$classpath" | grep -v 'kotlinx-coroutines-swing' | paste -sd: -)"

# The fork's desktop modules must be what the classpath names, not upstream's. The desktop module
# reads the local Maven repository first and quietly falls back to upstream's jars when the fork's
# are not there, and upstream's Compose sends its main-thread work to Swing: an image built that
# way links, passes a build and dies when the first scene is made, with the toolkit's library
# missing. build-compose.sh --target desktop publishes them.
for fork_jar in 'repository/org/jetbrains/compose/ui/ui-desktop/' 'repository/org/jetbrains/skiko/skiko-awt/'; do
    grep -q "$fork_jar" <<< "$(tr ':' '\n' <<< "$classpath")" || die \
        "the class path holds no $fork_jar" \
        "These are the Compose fork's desktop modules and its skiko-awt, published to the local" \
        "Maven repository. Without them the image would use upstream Compose and need the Java toolkit." \
        "fix: renderer/scripts/build-compose.sh --target desktop, then scripts/fetch-fork-skiko.sh and its" \
        "extended/skiko/build-skiko-awt.sh <work-dir> (the compose-desktop job in test-graalvm-renderer.yml shows both)"
done
lib="$DIST_DIR/lib"
rm -rf "$DIST_DIR" "$obj"
mkdir -p "$obj" "$lib"

cc -c -O2 -fPIC -o "$obj/renderer_entry.o" "$NATIVE_DIR/c/renderer_entry.c"
cc -c -O2 -fPIC -o "$obj/x11_window.o" "$x11_source"
cc -c -O2 -fPIC -o "$obj/linux_host_references.o" "$NATIVE_DIR/c/linux_host_references.c"

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

# The Host's compose_rust_host_* functions remain unresolved until the application loads
# the renderer. $ORIGIN lets GraalVM's generated shims and AWT libraries find the
# renderer and one another in the staged lib directory. The soname keeps the wrapper's
# DT_NEEDED entry a bare file name, so the staged directory stays relocatable.
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
    --initialize-at-build-time=org.thisisthepy.compose.window.graalvm.linux.X11Upcalls \
    --shared \
    -cp "$classpath" \
    -o "$image_name" \
    --no-fallback \
    -Ddxc.toolkit.window=false \
    -Dcompose.awt=false \
    -Ddxc.awt.clipboard=false \
    -Djava.awt.headless=false \
    -H:IncludeLocales=en,ko \
    -Os \
    -H:+UnlockExperimentalVMOptions \
    -H:ReportAnalysisForbiddenType=java.awt.Toolkit \
    -H:ReportAnalysisForbiddenType=java.awt.Component \
    -H:+PrintAnalysisCallTree \
    -H:PrintAnalysisCallTreeType=TXT \
    "-H:NativeLinkerOption=$obj/x11_window.o" \
    '-H:NativeLinkerOption=-lX11' \
    '-H:NativeLinkerOption=-lGL' \
    '-H:NativeLinkerOption=-lXext' \
    "-H:NativeLinkerOption=-Wl,-soname,$image_name.so" \
    '-H:NativeLinkerOption=-Wl,-rpath,$ORIGIN')

# The image library is the one file the link below needs from Native Image.
[[ -f "$lib/$image_name.so" ]] || die "Native Image did not emit $image_name.so" \
    "Keep $BUILD_DIR and report: $GRAALVM_HOME/bin/native-image --version"

# Native Image writes the JDK's desktop libraries beside an image whenever a java.awt class is
# reachable, and Compose's and Skiko's desktop classes name a few that nothing runs here: no
# toolkit class is initialised, because the window is the X11 one of our own and Compose's
# main-thread work runs in its frame loop. They are removed, so that an image that does reach
# the toolkit at run time fails at once with a missing library instead of quietly loading it.
# The smoke test is what proves nothing does.
rm -f "$lib"/libawt.so "$lib"/libawt_xawt.so "$lib"/libawt_headless.so \
      "$lib"/libfontmanager.so "$lib"/liblcms.so "$lib"/libjavajpeg.so

# The shim calls these five. Check them before linking, so a rename or a dropped export is
# reported as itself rather than as an undefined reference in the middle of a cc command.
for required_symbol in graal_create_isolate graal_attach_thread graal_get_current_thread \
                       compose_rust_renderer_run_impl \
                       compose_rust_renderer_request_frame_impl; do
    nm -D "$lib/$image_name.so" | grep -Eq " [TW] ${required_symbol}$" && continue
    echo "-- dynamic symbol table of $image_name.so (graal_*, dioxus_*)" >&2
    nm -D "$lib/$image_name.so" | grep -E "graal_|dioxus_" >&2 || echo "   (none)" >&2
    die "$image_name.so does not export $required_symbol" \
        "The C shim forwards to it, so the public library cannot be linked without it."
done

# The library the Host loads: the argument-free C ABI, linked against the image library.
#
# linux_host_references.o leaves every compose_rust_host_* function undefined in this
# library. An executable that links it then exports those functions, which is how the image
# finds them in an application whose link nothing else configured. See that file.
cc -shared -fPIC -pthread -o "$lib/$LIBRARY_NAME.so" "$obj/renderer_entry.o" \
    "$obj/linux_host_references.o" \
    -L"$lib" -Wl,--no-as-needed "-l${image_name#lib}" -Wl,-rpath,'$ORIGIN'

# Every Host function the image calls has to be one this library leaves undefined, or an
# application links, starts, and dies the first time the renderer calls the missing one.
# Read from the image rather than from a list, so a Host function added to the renderer
# and not to linux_host_references.c fails here instead of on somebody's machine.
host_needed="$(nm -D --undefined-only "$lib/$image_name.so" |
    grep -oE 'compose_rust_host_[a-z_]+' | sort -u)"
[[ -n "$host_needed" ]] || die "$image_name.so leaves no compose_rust_host_* undefined" \
    "It calls the Host through those names, so either the image no longer does or nm could not read it."
host_referenced="$(nm -D --undefined-only "$lib/$LIBRARY_NAME.so" |
    grep -oE 'compose_rust_host_[a-z_]+' | sort -u)"
host_missing="$(comm -23 <(echo "$host_needed") <(echo "$host_referenced"))"
[[ -z "$host_missing" ]] || die "$LIBRARY_NAME.so does not reference $(echo $host_missing)" \
    "$image_name.so calls it in the executable, and only a reference from the library the" \
    "executable links makes the linker export it. Add it to c/linux_host_references.c."
echo "host functions an application will export: $(echo $host_referenced)"

for exported_symbol in compose_rust_renderer_run compose_rust_renderer_request_frame; do
    nm -D "$lib/$LIBRARY_NAME.so" | grep -Eq " [TW] ${exported_symbol}$" && continue
    # Say which of the two failure modes this is. The symbol can be missing entirely, which
    # means the C shim was not linked in, or it can be present but local, which means the
    # shared-library link hid it. The remedies have nothing in common, so print the evidence
    # rather than leaving the next reader to rebuild for twenty minutes to see it.
    echo "-- dynamic symbol table (dioxus_*)" >&2
    nm -D "$lib/$LIBRARY_NAME.so" | grep dioxus_ >&2 || echo "   (none)" >&2
    echo "-- full symbol table (compose_rust_renderer_*)" >&2
    nm "$lib/$LIBRARY_NAME.so" 2>/dev/null | grep compose_rust_renderer_ >&2 || echo "   (none)" >&2
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

# No libjawt is staged. The skiko library does not link it and its loader maps none when nothing
# asks for an AWT canvas, which nothing in this renderer does; the smoke test runs without one.

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
echo "Verify: COMPOSE_RUST_AUTOEXIT_MS=5000 $NATIVE_DIR/scripts/smoke-test-linux.sh"
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

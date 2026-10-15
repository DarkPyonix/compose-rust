#!/usr/bin/env bash
# Builds the renderer as a native shared library and stages a self-contained lib/ directory:
#
#   build/native-image/dist/lib/
#     libcompose_rust_renderer.dylib   the renderer, with Skia (no JAWT), Compose and our code in it
set -euo pipefail
source "$(dirname "$0")/env.sh"
SCRIPT_DIR_NATIVE="$(cd "$(dirname "$0")" && pwd)"

# env.sh validates the platform, the architecture, the Xcode tools and the NIK install.
arch="$HOST_ARCH"
skiko_arch="$SKIKO_ARCH"

for source_file in renderer_entry.c macos_main_thread.m macos_notifications.m; do
    [[ -f "$NATIVE_DIR/c/$source_file" ]] || die "missing $NATIVE_DIR/c/$source_file"
done

# The AppKit window is the Compose fork's: its C is compiled from the checkout at the pinned
# commit, and its Kotlin is the published module the renderer's desktop module depends on.
"$PROJECT_DIR/scripts/publish-window.sh"
fork_window="$("$PROJECT_DIR/../scripts/fetch-fork-window.sh")/extended/window"
appkit_source="$fork_window/graalvm/graalvm-macos/native/appkit_window.m"
[[ -f "$appkit_source" ]] || die "missing $appkit_source"

# The classpath comes from a short JVM run so that it matches what the metadata describes.
COMPOSE_RUST_AUTOEXIT_MS=1 run_on_jvm ""
classpath="$(cat "$CLASSPATH_FILE")"
# Swing's coroutine provider is left out: the window answers `Dispatchers.Main` itself
# (FrameMainDispatcher), and the provider would wake the Java toolkit.
classpath="$(tr ':' '\n' <<< "$classpath" | grep -v 'kotlinx-coroutines-swing' | paste -sd: -)"

# The fork's desktop modules must be what the classpath names, not upstream's. They are published
# under versions of their own (EXTENDED_AS and SKIKO_AWT_EXTENDED_AS in build-compose.sh) because
# at upstream's version a resolver that holds upstream's jars, Amper's cache does, takes those and
# never reads the local Maven repository. An image built that way links, passes a build and dies
# when the first scene is made: upstream's Compose sends its main-thread work to Swing, with the
# toolkit's library missing.
compose_script="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/scripts/build-compose.sh"
compose_as="$(sed -n 's/^EXTENDED_AS="\([^"]*\)"$/\1/p' "$compose_script" | head -1)"
skiko_as="$(sed -n 's/^SKIKO_AWT_EXTENDED_AS="\([^"]*\)"$/\1/p' "$compose_script")"
[[ -n "$compose_as" && -n "$skiko_as" ]] || die "$compose_script names no EXTENDED_AS or SKIKO_AWT_EXTENDED_AS"
for fork_jar in "repository/org/jetbrains/compose/ui/ui-desktop/$compose_as/" \
                "repository/org/jetbrains/skiko/skiko-awt/$skiko_as/"; do
    grep -q "$fork_jar" <<< "$(tr ':' '\n' <<< "$classpath")" || die \
        "the class path holds no $fork_jar" \
        "These are the Compose fork's desktop modules and its skiko-awt, published to the local" \
        "Maven repository under versions of their own. Without them the image would use upstream" \
        "Compose and need the Java toolkit. If the jars are in the local repository, the module that" \
        "asks for them (desktop/module.yaml) names a different version than $compose_script." \
        "fix: renderer/scripts/build-compose.sh --target desktop, then scripts/publish-skiko-awt.sh <work-dir>" \
        "(the compose-desktop job in test-graalvm-renderer.yml shows both)"
done
obj="$BUILD_DIR/obj"
lib="$DIST_DIR/lib"
rm -rf "$DIST_DIR" "$obj"
mkdir -p "$obj" "$lib"

cc -c -O2 -arch "$arch" -o "$obj/renderer_entry.o" "$NATIVE_DIR/c/renderer_entry.c"
cc -c -O2 -arch "$arch" -o "$obj/macos_main_thread.o" "$NATIVE_DIR/c/macos_main_thread.m"
# The window the renderer is learning to open for itself. Compiled with ARC because it
# holds AppKit and Metal objects, and reference counting them by hand is a class of bug
# this project has no reason to invite.
cc -c -O2 -fobjc-arc -arch "$arch" -o "$obj/appkit_window.o" "$appkit_source"
# Notifications through UNUserNotificationCenter. With ARC for the same reason as the window.
cc -c -O2 -fobjc-arc -arch "$arch" -o "$obj/macos_notifications.o" "$NATIVE_DIR/c/macos_notifications.m"

exported=(compose_rust_renderer_run compose_rust_renderer_request_frame)
# The renderer calls the Host's compose_rust_host_* functions, which live in the Rust
# executable that loads this library. They are resolved at load time, so the link must
# tolerate them being undefined here.
linker_args=("-H:NativeLinkerOption=-Wl,-undefined,dynamic_lookup"
             "-H:NativeLinkerOption=$obj/renderer_entry.o"
             "-H:NativeLinkerOption=$obj/macos_main_thread.o"
             "-H:NativeLinkerOption=$obj/appkit_window.o"
             "-H:NativeLinkerOption=$obj/macos_notifications.o"
             # Named rather than left to the loader. AppKit resolves today because the
             # toolkit has already opened it; a window that does not use the toolkit has
             # nobody to borrow it from.
             "-H:NativeLinkerOption=-framework" "-H:NativeLinkerOption=AppKit"
             "-H:NativeLinkerOption=-framework" "-H:NativeLinkerOption=Carbon"
             "-H:NativeLinkerOption=-framework" "-H:NativeLinkerOption=Metal"
             "-H:NativeLinkerOption=-framework" "-H:NativeLinkerOption=MetalKit"
             "-H:NativeLinkerOption=-framework" "-H:NativeLinkerOption=QuartzCore"
             "-H:NativeLinkerOption=-framework" "-H:NativeLinkerOption=UserNotifications"
             "-H:NativeLinkerOption=-Wl,-install_name,@rpath/$LIBRARY_NAME.dylib")
for symbol in "${exported[@]}"; do
    linker_args+=("-H:NativeLinkerOption=-Wl,-exported_symbol,_$symbol")
done

# Heap and GC settings, in service of the desktop memory target (an empty window under
# 56MB of physical footprint). `-R:` options are baked in as the image's runtime defaults. Measure with desktop/scripts/measure-memory.sh.
#
# Measured 2026-09-20 (M1, smoke test window): pinning the maximum does not move the
# footprint. At the default (80% of RAM), at 64MB and at 24MB the MALLOC_SMALL region is
# 14MB in all three runs, because the Serial GC's adaptive policy already sizes the heap to
# the live set rather than to the maximum. The Java heap is the 2.5MB untagged VM_ALLOCATE
# region, not the 14MB of MALLOC_SMALL, which is Skia's native allocation.
#
# The cap stays because it bounds the worst case rather than the steady state: without it a
# runaway allocation may grow to gigabytes before the collector reacts. 64MB is many times
# the live set, so collections stay in the young generation and do not lengthen frames
# (the budget is one 120Hz frame, 8.33ms). Do not lower it to buy footprint; it does not buy any.
memory_args=("-R:MaxHeapSize=64m"
             "-R:MaxHeapFree=4m"
             "-R:MaximumYoungGenerationSizePercent=25")

# Graphics memory is the largest block of the footprint and Skiko has the knob for it, so
# the image is built to let that knob be turned.
#
# Measured 2026-09-23 on an empty window: graphics and the window surface are 4.9MB at
# 400x300, 22.1MB at 800x600 and 53.8MB at 1600x1200, which is the surface scaling with the
# window and nothing else. At 800x600 on a 2x display one buffer is 7.7MB, so 22MB is three
# of them. `skiko.buffering=DOUBLE` takes the Metal drawable count from three to two.
#
# The property could not be set. Skiko reads it through System.getProperty, and
# `SkikoProperties` is a Kotlin object whose initialiser runs while the image is built, so
# it captures the build machine's properties and a value written at startup arrives too
# late. Passing `-D` to native-image does not help either: that sets the property for the
# build JVM, and a shared library has no command line to carry one into the image.
#
# Initialising that one class at run time is the fix. Its initialiser then runs in the
# process that is going to draw, and reads what RuntimeLayout.kt set moments earlier
# alongside skiko.library.path, which has always worked for exactly this reason.
#
# Not `--initialize-at-build-time` for it, which is what a previous note proposed: that
# freezes the whole System.getProperties() table into the artifact, including the build
# machine's java.home and user.home.
initialisation_args=("--initialize-at-run-time=org.jetbrains.skiko.SkikoProperties")

# Skia inside the image rather than beside it, and no AWT with it.
#
# skiko's JVM natives are built as a static archive from the Compose fork's script with JAWT
# left out (build-static-skiko.sh), so the image links neither libjawt nor libawt for Skia's
# sake and nothing of the toolkit ships beside the renderer. The interface the feature uses to
# link it lives under com.oracle.svm.core, is documented nowhere, and is not promised to
# survive a GraalVM release, so it fails loudly rather than falling back to a Skia loaded from
# somewhere else.
static_dir="$("$SCRIPT_DIR_NATIVE/build-static-skiko.sh")"
DXC_STATIC_SKIKO="$static_dir/libskiko-static.a"
skia_archives=("$static_dir"/skia/*.a)
[[ -f "$DXC_STATIC_SKIKO" && -f "${skia_archives[0]}" ]] || die "no static skiko under $static_dir"
foreign_stubs="$BUILD_DIR/foreign-stubs.o"
static_skiko_args=(
    "--features=dev.darkpyonix.composerust.ui.platform.StaticSkikoFeature"
    "-Ddev.darkpyonix.composerust.staticSkiko=true"
    "-H:CLibraryPath=$static_dir"
    # Every member, not only the ones something refers to. A JNI entry point is reached by
    # name at run time and nothing in the image refers to it by symbol, so ordinary archive
    # semantics drop the member that defines it and the library fails to load with the
    # first such name in it.
    "-H:NativeLinkerOption=-Wl,-force_load,$DXC_STATIC_SKIKO"
)
# Skia's own archives are ordinary ones: its module archives each carry a copy of Skia's core
# objects, and forcing them in would define those twice.
for archive in "${skia_archives[@]}"; do
    static_skiko_args+=("-H:NativeLinkerOption=$archive")
done
for framework in CoreText CoreGraphics CoreFoundation Foundation ApplicationServices IOSurface; do
    static_skiko_args+=("-H:NativeLinkerOption=-framework" "-H:NativeLinkerOption=$framework")
done
# The builder's own packages the feature reaches into, which the module system does not export.
static_skiko_args+=(
    "-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jdk=ALL-UNNAMED"
    "-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.hosted=ALL-UNNAMED"
    "-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.hosted.c=ALL-UNNAMED"
)
echo "==> linking Skia into the image from $DXC_STATIC_SKIKO"

# Locale data and reachable code are already as small as they can safely go.
# `-H:IncludeLocales=en,ko` is the minimum the product supports and ko is not removable:
# Korean input is a headline requirement of this project. Neither shows up in the footprint
# anyway. Locale data,
# image code and read-only image heap land in __TEXT and clean __DATA, which the physical
# footprint does not count; only the 7.6MB of dirty __DATA does. Shrinking reachable code
# mostly shrinks the 65MB on disk, not the resident cost.
# An experiment can put a compiler of its own in front of the real one, to watch the link
# that produces the library. Unset, nothing changes and native-image finds cc itself.
probe_args=()
if [[ -n "${DXC_NATIVE_COMPILER:-}" ]]; then
    probe_args+=("--native-compiler-path=$DXC_NATIVE_COMPILER")
fi

link_image() {
    (cd "$lib" && "$GRAALVM_HOME/bin/native-image" \
        ${probe_args[@]+"${probe_args[@]}"} \
        "${initialisation_args[@]}" \
        ${static_skiko_args[@]+"${static_skiko_args[@]}"} \
        ${stub_args[@]+"${stub_args[@]}"} \
        --shared \
        -cp "$classpath" \
        -o "$LIBRARY_NAME" \
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
        "${memory_args[@]}" \
        "${linker_args[@]}")
}

# Skiko declares every platform's native methods and compiles one platform's. Linked in, the
# image refers to all of them, and macOS binds every symbol at load, so the ones this platform
# does not have are defined as stubs that stop. Which ones is read off a first link, which is
# why the image is linked twice the first time and once when the stubs are already there.
stub_args=()
if [[ ! -f "$foreign_stubs" ]]; then
    link_image
    "$NATIVE_DIR/../../experiments/static-library/generate-foreign-stubs.sh" \
        "$lib/$LIBRARY_NAME.dylib" "$DXC_STATIC_SKIKO" "$BUILD_DIR/foreign-stubs.c"
    cc -c -O2 -arch "$arch" -o "$foreign_stubs" "$BUILD_DIR/foreign-stubs.c"
fi
stub_args=("-H:NativeLinkerOption=$foreign_stubs")
link_image

# native-image leaves headers and build reports next to the library; keep lib/ runtime-only.
mkdir -p "$DIST_DIR/include"
mv "$lib"/*.h "$DIST_DIR/include/" 2>/dev/null || true
rm -f "$lib"/*.md

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

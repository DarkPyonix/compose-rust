#!/usr/bin/env bash
# Builds the renderer as a native shared library and stages a self-contained lib/ directory:
#
#   build/native-image/dist/lib/
#     libcompose_rust_renderer.dylib   the renderer (AWT, Skiko JNI, Compose, our code)
#     libskiko-macos-<arch>.dylib        Skia, loaded by Skiko by path
#     libjawt.dylib                      forwards JAWT_GetAWT into the renderer
#     libawt_lwawt.dylib                 placeholder libawt loads by path
set -euo pipefail
source "$(dirname "$0")/env.sh"

# env.sh validates the platform, the architecture, the Xcode tools and the NIK install.
arch="$HOST_ARCH"
skiko_arch="$SKIKO_ARCH"

for source_file in renderer_entry.c macos_awt_compat.c macos_main_thread.m \
                   appkit_window.m macos_notifications.m jawt_forwarder.c lwawt_placeholder.c; do
    [[ -f "$NATIVE_DIR/c/$source_file" ]] || die "missing $NATIVE_DIR/c/$source_file"
done

# The classpath comes from a short JVM run so that it matches what the metadata describes.
COMPOSE_RUST_AUTOEXIT_MS=1 run_on_jvm ""
classpath="$(cat "$CLASSPATH_FILE")"
obj="$BUILD_DIR/obj"
lib="$DIST_DIR/lib"
rm -rf "$DIST_DIR" "$obj"
mkdir -p "$obj" "$lib"

cc -c -O2 -arch "$arch" -o "$obj/renderer_entry.o" "$NATIVE_DIR/c/renderer_entry.c"
cc -c -O2 -arch "$arch" -o "$obj/macos_awt_compat.o" "$NATIVE_DIR/c/macos_awt_compat.c"
cc -c -O2 -arch "$arch" -o "$obj/macos_main_thread.o" "$NATIVE_DIR/c/macos_main_thread.m"
# The window the renderer is learning to open for itself. Compiled with ARC because it
# holds AppKit and Metal objects, and reference counting them by hand is a class of bug
# this project has no reason to invite.
cc -c -O2 -fobjc-arc -arch "$arch" -o "$obj/appkit_window.o" "$NATIVE_DIR/c/appkit_window.m"
# Notifications through UNUserNotificationCenter. With ARC for the same reason as the window.
cc -c -O2 -fobjc-arc -arch "$arch" -o "$obj/macos_notifications.o" "$NATIVE_DIR/c/macos_notifications.m"

exported=(compose_rust_renderer_run compose_rust_renderer_request_frame compose_rust_renderer_measure
          compose_rust_jawt_get_awt JNI_OnLoad_osxui)
# The renderer calls the Host's compose_rust_host_* functions, which live in the Rust
# executable that loads this library. They are resolved at load time, so the link must
# tolerate them being undefined here.
# The IME entry points (Java_sun_lwawt_macosx_CInputMethod_*) live in objects of the AWT
# toolkit archive that nothing else references, so the linker drops them and the image
# aborts the first time an input method touches a text field. Forcing the whole archive in
# also brings the accessibility entry points (Java_sun_lwawt_macosx_CAccessib*) and the
# Objective-C side that AppKit drives, without which the native build publishes an empty
# accessibility tree and aborts when one is queried.
awt_archive="$GRAALVM_HOME/lib/static/darwin-$([[ "$arch" == "arm64" ]] && echo aarch64 || echo amd64)/libawt_lwawt.a"
[[ -f "$awt_archive" ]] || { echo "error: missing $awt_archive" >&2; exit 1; }

linker_args=("-H:NativeLinkerOption=-Wl,-undefined,dynamic_lookup"
             "-H:NativeLinkerOption=-Wl,-force_load,$awt_archive"
             "-H:NativeLinkerOption=$obj/renderer_entry.o" "-H:NativeLinkerOption=$obj/macos_awt_compat.o"
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

# Forcing the archive in is not enough for the accessibility classes.
# AppKit never names them: the Objective-C side maps a Java role to a class name and looks the
# class up with NSClassFromString, so nothing in the image refers to GroupAccessibility,
# ButtonAccessibility or the rest by symbol, and the link drops them as dead code. The lookup
# then returns nil, allocating from a nil class gives a nil child, and AppKit aborts the
# process with "object cannot be nil" the moment anything reads the window's children.
#
# Listing the classes by hand would rot, so the list is read back out of the archive that
# defines them and every one is made a root of the link. They are small, and keeping them is
# the whole of the accessibility tree below the window.
a11y_classes=()
while IFS= read -r class_symbol; do
    a11y_classes+=("-H:NativeLinkerOption=-Wl,-u,$class_symbol")
done < <(nm -g "$awt_archive" 2>/dev/null |
    awk '$2 == "S" && $3 ~ /^_OBJC_CLASS_\$_[A-Za-z]+Accessibility$/ { print $3 }' | sort -u)
[[ ${#a11y_classes[@]} -gt 0 ]] || die \
    "no Objective-C accessibility classes found in $awt_archive" \
    "AppKit looks these classes up by name at runtime, so nothing references them by symbol" \
    "and the linker is free to drop them. When it does, the build and the window are fine" \
    "and the process aborts the moment an assistive technology attaches."
linker_args+=("${a11y_classes[@]}")

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

# Skia inside the image rather than beside it, when an archive has been built for it.
#
# Off unless DXC_STATIC_SKIKO names one, because the interface the feature uses to do it
# lives under com.oracle.svm.core, is documented nowhere, and is not promised to survive a
# GraalVM release. When it is on, the archive is put on the linker's library path and the
# feature is added; the dylib beside the renderer is then unnecessary and the staging step
# below says so.
static_skiko_args=()
if [[ -n "${DXC_STATIC_SKIKO:-}" ]]; then
    [[ -f "$DXC_STATIC_SKIKO" ]] || die "no archive at $DXC_STATIC_SKIKO" \
        "experiments/static-library/build-static-skiko.sh builds one."
    static_skiko_dir="$(cd "$(dirname "$DXC_STATIC_SKIKO")" && pwd)"
    static_skiko_args=(
        "--features=dev.darkpyonix.composerust.ui.platform.StaticSkikoFeature"
        "-Ddioxus.compose.staticSkiko=true"
        "-H:CLibraryPath=$static_skiko_dir"
        # Every member, not only the ones something refers to. A JNI entry point is
        # reached by name at run time and nothing in the image refers to it by symbol, so
        # ordinary archive semantics drop the member that defines it and the library
        # fails to load with the first such name in it. The AWT archive above is forced
        # in for the same reason.
        "-H:NativeLinkerOption=-Wl,-force_load,$DXC_STATIC_SKIKO"
    )
    # The entry points belonging to other platforms. Skiko declares every platform's
    # native methods everywhere and compiles only this one's, which is invisible while the
    # library is loaded by name at run time and fatal once it is linked in: macOS binds
    # every symbol at load, so the first Direct3D declaration kills the process before
    # anything is drawn. experiments/static-library/generate-foreign-stubs.sh writes them.
    foreign_stubs="$static_skiko_dir/foreign-stubs.o"
    [[ -f "$foreign_stubs" ]] || die "no $foreign_stubs" \
        "experiments/static-library/generate-foreign-stubs.sh writes the source for it."
    static_skiko_args+=(
        "-H:NativeLinkerOption=$foreign_stubs"
        # The three packages the feature reaches into are not exported by the builder
        # module, which is the module system saying what the comment on the feature says:
        # this is not an API. `-J` passes a flag to the builder's own JVM.
        "-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jdk=ALL-UNNAMED"
        "-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.hosted=ALL-UNNAMED"
        "-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.hosted.c=ALL-UNNAMED"
    )
    echo "==> linking Skia into the image from $DXC_STATIC_SKIKO"
fi

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

(cd "$lib" && "$GRAALVM_HOME/bin/native-image" \
    ${probe_args[@]+"${probe_args[@]}"} \
    "${initialisation_args[@]}" \
    ${static_skiko_args[@]+"${static_skiko_args[@]}"} \
    --shared \
    -cp "$classpath" \
    -o "$LIBRARY_NAME" \
    --no-fallback \
    --features=dev.darkpyonix.composerust.ui.platform.ImeReachabilityFeature \
    --features=dev.darkpyonix.composerust.ui.platform.AccessibilityReachabilityFeature \
    -Djava.awt.headless=false \
    -H:IncludeLocales=en,ko \
    -Os \
    -H:+UnlockExperimentalVMOptions \
    "${memory_args[@]}" \
    "${linker_args[@]}")

# Skia goes beside the library, unless this build put it inside. Staging it anyway would
# leave the one file the whole exercise exists to remove, and would hide a substitution
# that had stopped working: the loader would find the file and the image would look fine.
if [[ -z "${DXC_STATIC_SKIKO:-}" ]]; then
    skiko_jar="$(tr ':' '\n' <<< "$classpath" | grep "skiko-awt-runtime-macos-$skiko_arch" | head -1)"
    [[ -n "$skiko_jar" ]] || die \
        "no skiko-awt-runtime-macos-$skiko_arch jar on the runtime classpath" \
        "Skia ships inside that jar and is staged next to the library." \
        "Check $CLASSPATH_FILE and the compose dependency in desktop/module.yaml."
    unzip -q -o -j "$skiko_jar" "libskiko-macos-$skiko_arch.dylib" -d "$lib"
else
    rm -f "$lib/libskiko-macos-$skiko_arch.dylib"
fi
cc -dynamiclib -O2 -arch "$arch" -install_name @rpath/libjawt.dylib \
    -o "$lib/libjawt.dylib" "$NATIVE_DIR/c/jawt_forwarder.c"
cc -dynamiclib -O2 -arch "$arch" -install_name @rpath/libawt_lwawt.dylib \
    -o "$lib/libawt_lwawt.dylib" "$NATIVE_DIR/c/lwawt_placeholder.c"

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

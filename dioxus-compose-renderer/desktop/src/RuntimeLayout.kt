package dioxus.compose.ui.platform

import java.io.File

/**
 * Points AWT and Skiko at the directory the renderer library was loaded from.
 *
 * `java.home` is unset in a native image, and AWT resolves its libraries against it:
 * `<java.home>/lib/libjawt.dylib` on macOS. The distribution keeps every runtime file in
 * one `lib` directory, so its parent is a valid `java.home`.
 *
 * Skiko normally unpacks Skia from its jar into `~/.skiko`; a native image has no jar, so
 * the library is staged next to the renderer and Skiko is told where it is.
 *
 * Must run before anything touches AWT.
 */
internal fun configureRuntimeLayout(libraryDir: String) {
    val lib = File(libraryDir)
    if (System.getProperty("java.home") == null) {
        System.setProperty("java.home", lib.parentFile.absolutePath)
    }
    System.setProperty("skiko.library.path", lib.absolutePath)
    System.setProperty("skiko.data.path", lib.absolutePath)

    // The window's surface is the largest single thing this process holds. Measured on an
    // empty window at 800x600 on a 2x display, the surface and the graphics allocations
    // behind it come to 22MB, and one buffer at that size is 7.7MB, so that is three
    // buffers. Two is enough to draw without tearing.
    //
    // Set here rather than passed to the build, because a shared library has no command
    // line and there is nothing to carry a `-D` into the image. It is read moments later,
    // when Skiko's property holder initialises, which is why the image is built with that
    // one class initialising at run time instead of while it is being built.
    //
    // Only where nothing said otherwise, so that an operator can still override either.
    if (System.getProperty("skiko.buffering") == null) {
        System.setProperty("skiko.buffering", "DOUBLE")
    }
}

package dev.darkpyonix.composerust.ui.platform

// Named outright rather than left to the default imports. The JVM imports java.lang on its
// own and Kotlin/Native does not, where this is the shim each native renderer carries. One
// file then reads the same on both.
import java.lang.System

/**
 * The lines a window of our own prints as it comes up, which the smoke runs read.
 *
 * A renderer that returns 0 has not shown that it drew anything. An image built from upstream
 * Compose opens its window and then dies making the scene, because upstream sends Compose's
 * main-thread work to Swing and Swing loads the toolkit's library, which this image does not
 * carry. These lines say how far a start got: the window opened, the scene exists, Compose's
 * main-thread work goes to the dispatcher named here, and a frame reached the window.
 *
 * One file for every desktop renderer: the native image's windows compile it, and the
 * Kotlin/Native renderers for macOS and Linux link it from here. Every renderer then says the
 * same thing in the same words, and a check written against one reads the others. What
 * differs is only how a runtime names a class, which [mainDispatcherName] answers for each.
 */
internal object WindowStartReport {
    private var frameReported = false

    /** Once the window is open, with the size of its content in pixels. */
    fun windowOpened(width: Int, height: Int, scale: Float) {
        System.err.println(windowOpenedLine(width, height, scale))
    }

    /**
     * Once the scene is made. The dispatcher is the one `Dispatchers.Main` resolves to. On the
     * native image that is where the Compose fork sends its main-thread work when
     * `compose.main.dispatcher` says `coroutines`, and it has to be [FrameMainDispatcher]. A
     * Kotlin/Native build has no such property and reads `unset`.
     */
    fun sceneCreated(width: Int, height: Int, scale: Float) {
        System.err.println(
            sceneCreatedLine(
                dispatcher = mainDispatcherName(),
                asked = System.getProperty("compose.main.dispatcher"),
                width = width,
                height = height,
                scale = scale,
            ),
        )
    }

    /** After the first frame the scene painted has been handed to the window. Once per process. */
    fun frameDrawn(width: Int, height: Int) {
        if (frameReported) return
        frameReported = true
        System.err.println(firstFrameLine(width, height))
    }

    fun windowOpenedLine(width: Int, height: Int, scale: Float): String =
        "compose-rust: a window of our own, ${width}x$height at ${scale}x, with no toolkit in it"

    fun sceneCreatedLine(dispatcher: String, asked: String?, width: Int, height: Int, scale: Float): String =
        "compose-rust: Compose scene created, main dispatcher $dispatcher " +
            "(compose.main.dispatcher=${asked ?: "unset"}), window ${width}x$height at ${scale}x"

    fun firstFrameLine(width: Int, height: Int): String = "compose-rust: first frame drawn, ${width}x$height"
}

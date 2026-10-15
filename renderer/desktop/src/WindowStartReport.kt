package dev.darkpyonix.composerust.ui.platform

/**
 * The lines a window of our own prints as it comes up, which the smoke runs read.
 *
 * A renderer that returns 0 has not shown that it drew anything. An image built from upstream
 * Compose opens its window and then dies making the scene, because upstream sends Compose's
 * main-thread work to Swing and Swing loads the toolkit's library, which this image does not
 * carry. These lines say how far a start got: the scene exists, Compose's main-thread work
 * goes to the dispatcher named here, and a frame reached the window.
 */
internal object WindowStartReport {
    private var frameReported = false

    /**
     * Once the scene is made. The dispatcher is the one `Dispatchers.Main` resolves to, which is
     * where the Compose fork sends its main-thread work when `compose.main.dispatcher` says
     * `coroutines`; on this path that has to be [FrameMainDispatcher].
     */
    fun sceneCreated(width: Int, height: Int, scale: Float) {
        val main = kotlinx.coroutines.Dispatchers.Main.javaClass.name
        val asked = System.getProperty("compose.main.dispatcher") ?: "unset"
        System.err.println(
            "compose-rust: Compose scene created, main dispatcher $main " +
                "(compose.main.dispatcher=$asked), window ${width}x$height at ${scale}x",
        )
    }

    /** After the first frame the scene painted has been handed to the window. Once per process. */
    fun frameDrawn(width: Int, height: Int) {
        if (frameReported) return
        frameReported = true
        System.err.println("compose-rust: first frame drawn, ${width}x$height")
    }
}

package dev.darkpyonix.composerust.ui.platform

/** Where a clipboard write goes. In an application it is Compose's clipboard. */
fun interface ClipboardSink {
    fun setText(text: String)
}

/**
 * Puts the text the Host asked for on the clipboard.
 *
 * The composition hands over [sink] once it can see a clipboard. Until then, and wherever
 * there is none at all (a headless run, a test), a write does nothing: nothing answers it
 * and it is not a protocol error, because no application needs to know.
 *
 * The text is whatever the application copied, and an invite link or a snippet can carry
 * something private, so it is never written to a log. A failure says how long the text was
 * and nothing else.
 */
class ClipboardWriter {
    var sink: ClipboardSink? = null

    /** Where a failed write is reported. Tests replace it to read what was said. */
    var log: (String) -> Unit = { line -> System.err.println(line) }

    fun write(text: String) {
        val target = sink ?: return
        try {
            target.setText(text)
        } catch (failure: Throwable) {
            // The exception's own message is left out: a platform may quote what it was given.
            log(
                "compose-rust: the clipboard refused ${text.length} characters " +
                    "(${failure::class.simpleName})",
            )
        }
    }
}

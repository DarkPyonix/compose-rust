package dioxus.compose.ui

import dioxus.compose.ui.node.platformReducedMotion
import java.util.concurrent.TimeUnit

// Desktop only, and each desktop keeps the setting somewhere else. None of the three can
// be read through a Java API, so each is asked with the query its own platform documents.
// Reading it is worth a few tens of milliseconds once, and it is worth nothing at all to a
// screen that declares no motion, so the answer is taken on the first question and kept.

private const val QUERY_TIMEOUT_SECONDS = 2L

/**
 * What the running desktop says about reducing motion.
 *
 * Unknown counts as no: a machine that cannot be asked is a machine whose user has not
 * asked for anything, and holding every animation still on that guess would be the larger
 * mistake.
 */
private fun askThePlatform(): Boolean {
    val name = System.getProperty("os.name").orEmpty().lowercase()
    val query = when {
        name.contains("mac") ->
            listOf("defaults", "read", "com.apple.Accessibility", "ReduceMotionEnabled")

        name.contains("win") -> listOf(
            "reg",
            "query",
            "HKCU\\Control Panel\\Desktop\\WindowMetrics",
            "/v",
            "MinAnimate",
        )

        // GNOME keeps it as animations being on, which is the same question inverted.
        else -> listOf("gsettings", "get", "org.gnome.desktop.interface", "enable-animations")
    }
    val answer = runCatching {
        val process = ProcessBuilder(query).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        text
    }.getOrNull() ?: return false
    return readAnswer(answer, inverted = !name.contains("mac") && !name.contains("win"))
}

/**
 * Whether [answer] means motion should be reduced.
 *
 * The three answers look nothing alike: "1", "0x0" at the end of a registry line, and
 * "false". [inverted] is for the setting that is phrased the other way round, where
 * animations being off is motion being reduced.
 */
internal fun readAnswer(answer: String, inverted: Boolean): Boolean {
    val text = answer.trim().substringAfterLast(' ').lowercase()
    val on = when (text) {
        "1", "true", "0x1", "yes" -> true
        "0", "false", "0x0", "no" -> false
        else -> return false
    }
    return if (inverted) !on else on
}

/**
 * Hands the desktop's answer to the shared tree walk. Called once, before the first frame.
 *
 * Lazily, so that a screen with no motion role never starts a process to ask.
 */
internal fun installReducedMotion() {
    val answer = lazy { askThePlatform() }
    platformReducedMotion = { answer.value }
}

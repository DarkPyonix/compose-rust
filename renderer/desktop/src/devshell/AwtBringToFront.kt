package dev.darkpyonix.composerust.ui.platform

/**
 * Brings the application's window up for a press on a notification's body: back from
 * being minimised, and in front of the others.
 *
 * On the toolkit's thread, because that is the only thread a toolkit window may be touched
 * from, and a press is reported from wherever the bus was read.
 */
internal fun bringAwtWindowToFront() {
    java.awt.EventQueue.invokeLater {
        val window = java.awt.Window.getWindows().firstOrNull { it.isVisible } ?: return@invokeLater
        if (window is java.awt.Frame) {
            window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
        }
        window.toFront()
        window.requestFocus()
    }
}

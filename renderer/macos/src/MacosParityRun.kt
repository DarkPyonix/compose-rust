@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import java.lang.System
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.macos.MacosWindow
import platform.AppKit.NSApplication
import platform.Foundation.NSTimer

/**
 * The made-up input of a parity run on the Kotlin/Native macOS window: a click in the first
 * text field, five letters, the sizes a drag would pass through, and then the end of the run.
 * Asked for with `DXC_SYNTH`, the same variable and the same timeline every other window
 * reads, so the numbers it leaves in [LatencyTrace] and the `dxc resize:` line are
 * comparable. Unset, nothing here runs.
 *
 * Driven from a timer on the main run loop, which is where AppKit delivers everything else,
 * so the made-up input arrives the way a real one would. The window owns the scene; it is
 * handed the input and asked for its counts through the hooks it has for this.
 */
internal fun startParityRun(window: MacosWindow) {
    val what = System.getenv("DXC_SYNTH") ?: return
    val input = SyntheticInput(what)
    // The macOS window describes itself in its own element type, the same fields and roles as
    // the shared one the made-up input reads.
    window.onElements = { elements ->
        input.noteElements(
            elements.map { AccessibleElement(it.role, it.x, it.y, it.width, it.height, it.label) },
        )
    }
    // Something has to be asking for the tree, or the field is never found: a reader
    // normally does, and nobody is reading this.
    window.listenForReader()
    window.onFrameRendered = { nanos -> LatencyTrace.frameDrawn(nanos) }
    NSTimer.scheduledTimerWithTimeInterval(PARITY_TURN_SECONDS, repeats = true) { _ ->
        val now = System.nanoTime()
        val measured = window.measure()
        for (event in input.due(now, androidx.compose.ui.unit.IntSize(measured.width, measured.height))) {
            if (event.kind == WindowEvent.TEXT_COMMIT) LatencyTrace.inputSent()
            LatencyTrace.mark("synthetic ${event.kind} sent")
            window.inject(event)
        }
        if (input.resizeDue(now)) {
            LatencyTrace.phase = "resize"
            window.scriptedResize(PARITY_RESIZE_WIDTH, PARITY_RESIZE_HEIGHT, PARITY_RESIZE_STEPS, PARITY_RESIZE_PAUSE_SECONDS)
            LatencyTrace.phase = "idle"
        }
        if (input.exitDue(now)) {
            LatencyTrace.summary()
            if (System.getenv("DXC_REPORT_RESIZE") != null) System.err.println(window.resizeStats.report())
            NSApplication.sharedApplication().terminate(null)
        }
    }
}

/** How often the made-up input looks at the clock. */
private const val PARITY_TURN_SECONDS = 0.016

// The scripted drag: the size every window is taken to, in the same steps.
private const val PARITY_RESIZE_WIDTH = 360.0
private const val PARITY_RESIZE_HEIGHT = 420.0
private const val PARITY_RESIZE_STEPS = 60
private const val PARITY_RESIZE_PAUSE_SECONDS = 0.008

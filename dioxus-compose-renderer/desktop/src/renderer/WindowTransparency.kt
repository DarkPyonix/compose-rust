package dioxus.compose.ui

import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import org.jetbrains.skiko.SkiaLayer

// Letting a window show what is behind it, from the toolkit's side.
//
// The platform half of this is elsewhere: the shell puts a view behind the window that
// draws what the desktop is showing there, and gives the window's own frame back. None of
// that reaches the eye while the surface drawn into clears itself opaque first, which is
// what a Skia layer does unless it is told otherwise.
//
// Skiko's `transparency` is that switch and is public API of the version this project
// pins. The walk is plain AWT.

/**
 * Stops this window's drawing surface from clearing itself opaque.
 *
 * Returns whether a surface was found and told. False is not a failure to report: a
 * development shell may have no such layer, and a window that keeps its opaque surface
 * looks exactly as it did before.
 */
internal fun letWindowShowItsBackdrop(root: Component): Boolean {
    val report = System.getenv("DXC_REPORT_MATERIAL") != null
    val layer = findSkiaLayer(root)
    if (layer == null) {
        if (report) System.err.println("dxc material: no drawing surface found in the window")
        return false
    }
    layer.transparency = true
    // The panels between the window and that surface paint their own background first,
    // and an opaque one there covers the material just as completely.
    var parent: Container? = layer.parent
    while (parent != null) {
        (parent as? JComponent)?.isOpaque = false
        parent = parent.parent
    }
    if (report) {
        System.err.println(
            "dxc material: surface made transparent, window opaque=" +
                "${(root as? java.awt.Window)?.isOpaque}",
        )
    }
    return true
}

private fun findSkiaLayer(component: Component): SkiaLayer? {
    if (component is SkiaLayer) return component
    if (component !is Container) return null
    for (child in component.components) {
        findSkiaLayer(child)?.let { return it }
    }
    return null
}

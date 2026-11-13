@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.foundation.NativeHole
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.NativeViewWindow
import dev.darkpyonix.composerust.foundation.NativeViewWindowEvents
import dev.darkpyonix.composerust.protocol.NativeViewWire
import org.thisisthepy.compose.window.PixelRect
import org.thisisthepy.compose.window.PixelRoundRect
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.macos.MacosWindow

/**
 * The platform view slot of one Kotlin/Native macOS window: the renderer's [NativeViewWindow]
 * over the fork's `MacosWindow.nativeViews`, which puts each view in a container beside the
 * scene's Metal view, cuts it to its region and reports its focus.
 *
 * Made before the window's content is set, so the content can be given it, and told to
 * [listen] after: setting the content is where the window's scene hooks its own event handler
 * and scale handler into the window, and this one is chained after them, so the scene still
 * hears every event first (it lets go of its own focus when a view takes it).
 */
internal class MacosNativeViewWindow(private val window: MacosWindow) : NativeViewWindow {
    private val views get() = window.nativeViews

    override var events: NativeViewWindowEvents? = null
        set(value) {
            field = value
            // Set from the content's first composition, which is after the content was set.
            if (value != null) listen()
        }

    private var listening = false

    /** Starts hearing the window's focus and scale reports. Called once its content is set. */
    fun listen() {
        if (listening) return
        listening = true
        val sceneEvents = window.onEvent
        window.onEvent = { event ->
            sceneEvents?.invoke(event)
            when (event.kind) {
                WindowEvent.NATIVE_VIEW_FOCUS_IN -> events?.focusChanged(event.nativeViewId, true)
                WindowEvent.NATIVE_VIEW_FOCUS_OUT -> events?.focusChanged(event.nativeViewId, false)
            }
        }
        val sceneScale = window.onScaleChanged
        window.onScaleChanged = { scale ->
            sceneScale?.invoke(scale)
            events?.scaleChanged(scale)
        }
    }

    override val parentHandle: Long get() = views.parentHandle

    override val parentKind: Int get() = NativeViewWire.PARENT_APPKIT

    override val scale: Float
        get() = window.window.backingScaleFactor.toFloat().takeIf { it > 0f } ?: 1f

    override fun attach(id: Int, handle: Long): Boolean = views.attach(id, handle)

    override fun setFrame(id: Int, x: Int, y: Int, width: Int, height: Int) = views.setFrame(id, x, y, width, height)

    override fun setVisibleRegion(id: Int, clip: NativeRect?, holes: List<NativeHole>) {
        views.setVisibleRegion(
            id,
            clip?.let { PixelRect(it.x, it.y, it.width, it.height) },
            holes.map { PixelRoundRect(it.rect.x, it.rect.y, it.rect.width, it.rect.height, it.cornerRadius) },
        )
    }

    override fun setVisible(id: Int, visible: Boolean) = views.setVisible(id, visible)

    override fun focus(id: Int) = views.focus(id)

    override fun focusScene() = views.focusScene()

    override fun detach(id: Int) = views.detach(id)
}

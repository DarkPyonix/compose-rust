package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.foundation.NativeHole
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.NativeViewWindow
import dev.darkpyonix.composerust.foundation.NativeViewWindowEvents
import dev.darkpyonix.composerust.protocol.NativeViewWire
import org.thisisthepy.compose.window.NativeViews
import org.thisisthepy.compose.window.PixelRect
import org.thisisthepy.compose.window.PixelRoundRect
import org.thisisthepy.compose.window.WindowEvent

/**
 * The platform view slot of one Kotlin/Native Linux X11 window: adapts the fork's
 * `X11Window.nativeViews` ([NativeViews]) to the renderer's [NativeViewWindow].
 */
internal class LinuxNativeViewWindow(
    private val window: LinuxWindow,
) : NativeViewWindow {
    private val views: NativeViews? get() = window.x11.nativeViews

    override var events: NativeViewWindowEvents? = null

    override val parentHandle: Long
        get() = views?.parentHandle ?: 0L

    override val parentKind: Int
        get() = NativeViewWire.PARENT_X11

    override val scale: Float
        get() = window.x11.density.takeIf { it > 0f } ?: 1f

    override fun attach(id: Int, handle: Long): Boolean = views?.attach(id, handle) ?: false

    override fun setFrame(id: Int, x: Int, y: Int, width: Int, height: Int) {
        views?.setFrame(id, x, y, width, height)
    }

    override fun setVisibleRegion(id: Int, clip: NativeRect?, holes: List<NativeHole>) {
        views?.setVisibleRegion(
            id,
            clip?.let { PixelRect(it.x, it.y, it.width, it.height) },
            pixelHoles(holes),
        )
    }

    override fun setFrameAndVisibleRegion(
        id: Int,
        frame: NativeRect,
        clip: NativeRect?,
        holes: List<NativeHole>,
    ) {
        views?.setFrameAndVisibleRegion(
            id,
            frame.x,
            frame.y,
            frame.width,
            frame.height,
            clip?.let { PixelRect(it.x, it.y, it.width, it.height) },
            pixelHoles(holes),
        )
    }

    override fun setVisible(id: Int, visible: Boolean) {
        views?.setVisible(id, visible)
    }

    override fun focus(id: Int) {
        views?.focus(id)
    }

    override fun focusScene() {
        views?.focusScene()
    }

    override fun detach(id: Int) {
        views?.detach(id)
    }

    internal fun onWindowEvent(event: WindowEvent) {
        when (event.kind) {
            WindowEvent.NATIVE_VIEW_FOCUS_IN -> events?.focusChanged(event.keyCode, true)
            WindowEvent.NATIVE_VIEW_FOCUS_OUT -> events?.focusChanged(event.keyCode, false)
        }
    }

    private fun pixelHoles(holes: List<NativeHole>): List<PixelRoundRect> =
        if (holes.isEmpty()) emptyList()
        else holes.map { PixelRoundRect(it.rect.x, it.rect.y, it.rect.width, it.rect.height, it.cornerRadius) }
}

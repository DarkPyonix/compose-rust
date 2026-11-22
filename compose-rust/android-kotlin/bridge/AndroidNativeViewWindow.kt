package dev.darkpyonix.composerust.ui.platform

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.darkpyonix.composerust.foundation.NativeHole
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.NativeViewWindow
import dev.darkpyonix.composerust.foundation.NativeViewWindowEvents
import dev.darkpyonix.composerust.protocol.NativeViewWire
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Registry mapping Android [View] instances to opaque 64-bit handles carried across the
 * `NativeView` wire protocol (`NativeViewParent` and `AttachNativeView`).
 */
object AndroidNativeViews {
    private val nextHandle = AtomicLong(1L)
    private val views = ConcurrentHashMap<Long, View>()

    fun register(view: View): Long {
        val handle = nextHandle.getAndIncrement()
        views[handle] = view
        return handle
    }

    fun lookup(handle: Long): View? = if (handle == 0L) null else views[handle]

    fun unregister(handle: Long) {
        if (handle != 0L) views.remove(handle)
    }
}

/**
 * The platform view slot of one Android [FrameLayout] root: places Host-owned Android [View]
 * instances above the `ComposeView`, clips them to ancestor bounds, cuts rounded holes for
 * Compose overlays, and lets touches inside holes pass through to Compose.
 */
class AndroidNativeViewWindow(private val root: FrameLayout) : NativeViewWindow {
    private class Slot(
        val id: Int,
        val handle: Long,
        val view: View,
        val container: NativeViewSlotFrame,
    ) {
        var frame: NativeRect = NativeRect.EMPTY
        var clip: NativeRect? = null
        var holes: List<NativeHole> = emptyList()
        var requestedVisible: Boolean = false
        var regionEmpty: Boolean = true
    }

    private val slots = LinkedHashMap<Int, Slot>()
    private var focusedSlotId: Int? = null
    private var handle: Long = AndroidNativeViews.register(root)

    override var events: NativeViewWindowEvents? = null

    override val parentHandle: Long
        get() = handle

    override val parentKind: Int
        get() = NativeViewWire.PARENT_ANDROID

    override val scale: Float
        get() = root.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f

    override fun attach(id: Int, handle: Long): Boolean {
        if (handle == 0L || this.handle == 0L) return false
        if (slots.values.any { it.handle == handle && it.id != id }) return false
        val view = AndroidNativeViews.lookup(handle) ?: return false

        detach(id)

        (view.parent as? ViewGroup)?.removeView(view)
        val container = NativeViewSlotFrame(root.context)
        container.visibility = View.GONE
        container.clipChildren = true
        container.clipToPadding = true
        container.addView(view, FrameLayout.LayoutParams(0, 0))
        root.addView(container, FrameLayout.LayoutParams(0, 0))

        slots[id] = Slot(id = id, handle = handle, view = view, container = container)
        return true
    }

    override fun setFrame(id: Int, x: Int, y: Int, width: Int, height: Int) {
        val slot = slots[id] ?: return
        slot.frame = NativeRect(x, y, width, height)
        applyLayout(slot)
    }

    override fun setVisibleRegion(id: Int, clip: NativeRect?, holes: List<NativeHole>) {
        val slot = slots[id] ?: return
        slot.clip = clip
        slot.holes = holes
        applyLayout(slot)
    }

    override fun setFrameAndVisibleRegion(
        id: Int,
        frame: NativeRect,
        clip: NativeRect?,
        holes: List<NativeHole>,
    ) {
        val slot = slots[id] ?: return
        slot.frame = frame
        slot.clip = clip
        slot.holes = holes
        applyLayout(slot)
    }

    override fun setVisible(id: Int, visible: Boolean) {
        val slot = slots[id] ?: return
        slot.requestedVisible = visible
        slot.container.visibility = if (slot.requestedVisible && !slot.regionEmpty) View.VISIBLE else View.GONE
        if (!visible && focusedSlotId == id) {
            slot.view.clearFocus()
            focusedSlotId = null
            events?.focusChanged(id, false)
        }
    }

    override fun focus(id: Int) {
        val slot = slots[id] ?: return
        val previous = focusedSlotId
        if (previous != null && previous != id) {
            slots[previous]?.view?.clearFocus()
            focusedSlotId = null
            events?.focusChanged(previous, false)
        }
        slot.view.requestFocus()
        focusedSlotId = id
        events?.focusChanged(id, true)
    }

    override fun focusScene() {
        val previous = focusedSlotId ?: return
        slots[previous]?.view?.clearFocus()
        focusedSlotId = null
        events?.focusChanged(previous, false)
    }

    override fun detach(id: Int) {
        val slot = slots.remove(id) ?: return
        if (focusedSlotId == id) {
            slot.view.clearFocus()
            focusedSlotId = null
            events?.focusChanged(id, false)
        }
        slot.container.removeView(slot.view)
        root.removeView(slot.container)
    }

    /** Detaches all views and releases the parent handle when the Activity is destroyed. */
    fun close() {
        for (id in slots.keys.toList()) {
            detach(id)
        }
        AndroidNativeViews.unregister(handle)
        handle = 0L
    }

    private fun applyLayout(slot: Slot) {
        val effective = slot.clip?.intersect(slot.frame) ?: slot.frame
        slot.regionEmpty = effective.isEmpty
        if (effective.isEmpty) {
            slot.container.visibility = View.GONE
            slot.container.setHoles(emptyList())
            return
        }

        val containerParams = FrameLayout.LayoutParams(effective.width, effective.height).apply {
            leftMargin = effective.x
            topMargin = effective.y
        }
        slot.container.layoutParams = containerParams

        val viewParams = FrameLayout.LayoutParams(slot.frame.width, slot.frame.height).apply {
            leftMargin = slot.frame.x - effective.x
            topMargin = slot.frame.y - effective.y
        }
        slot.view.layoutParams = viewParams

        val localHoles = slot.holes.mapNotNull { hole ->
            val clipped = hole.rect.intersect(effective)
            if (clipped.isEmpty) null
            else LocalAndroidHole(
                rect = RectF(
                    (clipped.x - effective.x).toFloat(),
                    (clipped.y - effective.y).toFloat(),
                    (clipped.right - effective.x).toFloat(),
                    (clipped.bottom - effective.y).toFloat(),
                ),
                radius = hole.cornerRadius.toFloat(),
            )
        }
        slot.container.setHoles(localHoles)
        slot.container.visibility = if (slot.requestedVisible) View.VISIBLE else View.GONE
    }
}

private data class LocalAndroidHole(val rect: RectF, val radius: Float)

private class NativeViewSlotFrame(context: Context) : FrameLayout(context) {
    private var holes: List<LocalAndroidHole> = emptyList()
    private val holePath = Path()

    fun setHoles(newHoles: List<LocalAndroidHole>) {
        holes = newHoles
        holePath.reset()
        for (h in holes) {
            holePath.addRoundRect(h.rect, h.radius, h.radius, Path.Direction.CW)
        }
        invalidate()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (holes.any { it.rect.contains(ev.x, ev.y) }) {
            return false
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (holes.isEmpty()) {
            super.dispatchDraw(canvas)
            return
        }
        val save = canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(holePath)
        } else {
            @Suppress("DEPRECATION")
            canvas.clipPath(holePath, android.graphics.Region.Op.DIFFERENCE)
        }
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}

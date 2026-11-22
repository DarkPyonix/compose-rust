package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.foundation.NativeHole
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.NativeViewWindow
import dev.darkpyonix.composerust.foundation.NativeViewWindowEvents
import dev.darkpyonix.composerust.protocol.NativeViewWire
import kotlinx.cinterop.CValue
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGPathAddPath
import platform.CoreGraphics.CGPathAddRect
import platform.CoreGraphics.CGPathCreateMutable
import platform.CoreGraphics.CGPathRelease
import platform.QuartzCore.CAShapeLayer
import platform.QuartzCore.kCAFillRuleEvenOdd
import platform.UIKit.UIBezierPath
import platform.UIKit.UIEvent
import platform.UIKit.UIView
import platform.UIKit.UIWindow

/**
 * The platform view slot of one iOS [UIWindow]: places Host-owned `UIView` instances in
 * clipped container views above the Compose view, cuts rounded holes for Compose overlays,
 * and forwards touch hit-testing inside holes back to Compose.
 */
internal class IosNativeViewWindow(private val window: UIWindow) : NativeViewWindow {
    private class Slot(
        val id: Int,
        val handle: Long,
        val view: UIView,
        val container: NativeViewSlotContainer,
    ) {
        var frame: NativeRect = NativeRect.EMPTY
        var clip: NativeRect? = null
        var holes: List<NativeHole> = emptyList()
        var requestedVisible: Boolean = false
        var regionEmpty: Boolean = true
    }

    private val slots = LinkedHashMap<Int, Slot>()
    private var focusedSlotId: Int? = null

    override var events: NativeViewWindowEvents? = null

    override val parentHandle: Long
        get() = window.objcPtr().toLong()

    override val parentKind: Int
        get() = NativeViewWire.PARENT_UIKIT

    override val scale: Float
        get() = window.screen.scale.toFloat().takeIf { it > 0f } ?: 1f

    override fun attach(id: Int, handle: Long): Boolean {
        if (handle == 0L) return false
        if (slots.values.any { it.handle == handle && it.id != id }) return false
        val rawPtr = handle.toCPointer<kotlinx.cinterop.CPointed>() ?: return false
        val obj = interpretObjCPointerOrNull<Any>(rawPtr.rawValue) ?: return false
        val view = obj as? UIView ?: return false

        detach(id)

        val container = NativeViewSlotContainer()
        container.clipsToBounds = true
        container.hidden = true
        container.userInteractionEnabled = true
        container.addSubview(view)
        window.addSubview(container)

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
        slot.container.hidden = !slot.requestedVisible || slot.regionEmpty
        if (!visible && focusedSlotId == id) {
            slot.view.resignFirstResponder()
            focusedSlotId = null
            events?.focusChanged(id, false)
        }
    }

    override fun focus(id: Int) {
        val slot = slots[id] ?: return
        val previous = focusedSlotId
        if (previous != null && previous != id) {
            slots[previous]?.view?.resignFirstResponder()
            focusedSlotId = null
            events?.focusChanged(previous, false)
        }
        slot.view.becomeFirstResponder()
        focusedSlotId = id
        events?.focusChanged(id, true)
    }

    override fun focusScene() {
        val previous = focusedSlotId ?: return
        slots[previous]?.view?.resignFirstResponder()
        focusedSlotId = null
        events?.focusChanged(previous, false)
    }

    override fun detach(id: Int) {
        val slot = slots.remove(id) ?: return
        if (focusedSlotId == id) {
            slot.view.resignFirstResponder()
            focusedSlotId = null
            events?.focusChanged(id, false)
        }
        slot.view.removeFromSuperview()
        slot.container.removeFromSuperview()
    }

    private fun applyLayout(slot: Slot) {
        val s = scale.toDouble()
        val effective = slot.clip?.intersect(slot.frame) ?: slot.frame
        slot.regionEmpty = effective.isEmpty
        if (effective.isEmpty) {
            slot.container.hidden = true
            slot.container.holeRects = emptyList()
            slot.container.layer.mask = null
            return
        }

        val cx = effective.x.toDouble() / s
        val cy = effective.y.toDouble() / s
        val cw = effective.width.toDouble() / s
        val ch = effective.height.toDouble() / s
        slot.container.setFrame(CGRectMake(cx, cy, cw, ch))

        val vx = (slot.frame.x - effective.x).toDouble() / s
        val vy = (slot.frame.y - effective.y).toDouble() / s
        val vw = slot.frame.width.toDouble() / s
        val vh = slot.frame.height.toDouble() / s
        slot.view.setFrame(CGRectMake(vx, vy, vw, vh))

        val localHoles = slot.holes.mapNotNull { hole ->
            val clipped = hole.rect.intersect(effective)
            if (clipped.isEmpty) null
            else LocalHole(
                x = (clipped.x - effective.x).toDouble() / s,
                y = (clipped.y - effective.y).toDouble() / s,
                w = clipped.width.toDouble() / s,
                h = clipped.height.toDouble() / s,
                radius = hole.cornerRadius.toDouble() / s,
            )
        }
        slot.container.holeRects = localHoles

        if (localHoles.isEmpty()) {
            slot.container.layer.mask = null
        } else {
            val path = CGPathCreateMutable()
            CGPathAddRect(path, null, CGRectMake(0.0, 0.0, cw, ch))
            for (h in localHoles) {
                val bezier = UIBezierPath.bezierPathWithRoundedRect(
                    CGRectMake(h.x, h.y, h.w, h.h),
                    cornerRadius = h.radius,
                )
                CGPathAddPath(path, null, bezier.CGPath)
            }
            val mask = CAShapeLayer()
            mask.fillRule = kCAFillRuleEvenOdd
            mask.path = path
            CGPathRelease(path)
            slot.container.layer.mask = mask
        }

        slot.container.hidden = !slot.requestedVisible
    }
}

private data class LocalHole(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val radius: Double,
)

private class NativeViewSlotContainer : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    var holeRects: List<LocalHole> = emptyList()

    override fun hitTest(point: CValue<CGPoint>, withEvent: UIEvent?): UIView? {
        val inHole = point.useContents {
            holeRects.any { h -> x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h }
        }
        if (inHole) return null
        return super.hitTest(point, withEvent)
    }
}

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.foundation.NativeHole
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.NativeViewWindow
import dev.darkpyonix.composerust.foundation.NativeViewWindowEvents

/**
 * The platform view slot of one browser viewport: positions Host-owned DOM elements in an
 * overlay container above the Compose canvas, clips them to ancestor bounds, and manages focus.
 *
 * DOM elements are registered by handle on `globalThis.__composeRustNativeViews` (a `Map` from
 * numeric handle to `HTMLElement`), with handle `1` reserved for the overlay container itself.
 */
internal class WebNativeViewWindow : NativeViewWindow {
    private class Slot(
        val id: Int,
        val handle: Long,
    ) {
        var frame: NativeRect = NativeRect.EMPTY
        var clip: NativeRect? = null
        var holes: List<NativeHole> = emptyList()
        var requestedVisible: Boolean = false
    }

    private val slots = LinkedHashMap<Int, Slot>()
    private var focusedSlotId: Int? = null
    private val rootHandle: Long = ensureDomOverlayHost().toLong()

    override var events: NativeViewWindowEvents? = null

    override val parentHandle: Long
        get() = rootHandle

    override val parentKind: Int
        get() = PARENT_DOM

    override val scale: Float
        get() = domDevicePixelRatio().toFloat().takeIf { it > 0f } ?: 1f

    override fun attach(id: Int, handle: Long): Boolean {
        if (handle <= 0L || handle == rootHandle) return false
        if (slots.values.any { it.handle == handle && it.id != id }) return false
        if (!domAttachSlot(id, handle.toDouble())) return false
        slots[id] = Slot(id = id, handle = handle)
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
        if (!visible && focusedSlotId == id) {
            domBlurSlot(id)
            focusedSlotId = null
            events?.focusChanged(id, false)
        }
        applyLayout(slot)
    }

    override fun focus(id: Int) {
        if (!slots.containsKey(id)) return
        val previous = focusedSlotId
        if (previous != null && previous != id) {
            domBlurSlot(previous)
            focusedSlotId = null
            events?.focusChanged(previous, false)
        }
        domFocusSlot(id)
        focusedSlotId = id
        events?.focusChanged(id, true)
    }

    override fun focusScene() {
        val previous = focusedSlotId ?: return
        domBlurSlot(previous)
        focusedSlotId = null
        events?.focusChanged(previous, false)
    }

    override fun detach(id: Int) {
        slots.remove(id) ?: return
        if (focusedSlotId == id) {
            domBlurSlot(id)
            focusedSlotId = null
            events?.focusChanged(id, false)
        }
        domDetachSlot(id)
    }

    private fun applyLayout(slot: Slot) {
        val s = scale.toDouble()
        val effective = slot.clip?.intersect(slot.frame) ?: slot.frame
        if (effective.isEmpty || !slot.requestedVisible) {
            domSetSlotLayout(slot.id, false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            return
        }
        domSetSlotLayout(
            slot.id,
            true,
            effective.x.toDouble() / s,
            effective.y.toDouble() / s,
            effective.width.toDouble() / s,
            effective.height.toDouble() / s,
            (slot.frame.x - effective.x).toDouble() / s,
            (slot.frame.y - effective.y).toDouble() / s,
            slot.frame.width.toDouble() / s,
            slot.frame.height.toDouble() / s,
        )
    }

    companion object {
        const val PARENT_DOM = 6
    }
}

private fun domDevicePixelRatio(): Double = js(
    """(typeof window !== 'undefined' && window.devicePixelRatio > 0) ? window.devicePixelRatio : 1"""
)

private fun ensureDomOverlayHost(): Int = js(
    """(() => {
        const g = globalThis;
        if (!g.__composeRustNativeViews) g.__composeRustNativeViews = new Map();
        if (!g.__composeRustNativeSlots) g.__composeRustNativeSlots = new Map();
        if (typeof document === 'undefined' || !document.body) return 1;
        let host = g.__composeRustNativeViews.get(1);
        if (!host) {
            host = document.createElement('div');
            host.style.position = 'fixed';
            host.style.left = '0';
            host.style.top = '0';
            host.style.width = '100%';
            host.style.height = '100%';
            host.style.pointerEvents = 'none';
            host.style.zIndex = '1000';
            document.body.appendChild(host);
            g.__composeRustNativeViews.set(1, host);
        }
        return 1;
    })()"""
)

private fun domAttachSlot(id: Int, handle: Double): Boolean = js(
    """(() => {
        const g = globalThis;
        const map = g.__composeRustNativeViews;
        if (!map) return false;
        const el = map.get(handle);
        if (!el) return false;
        const slots = g.__composeRustNativeSlots;
        if (slots.has(id)) {
            const old = slots.get(id);
            if (old && old.parentNode) old.parentNode.removeChild(old);
            slots.delete(id);
        }
        if (typeof document === 'undefined') {
            slots.set(id, el);
            return true;
        }
        const host = map.get(1);
        const container = document.createElement('div');
        container.style.position = 'absolute';
        container.style.overflow = 'hidden';
        container.style.display = 'none';
        container.style.pointerEvents = 'auto';
        el.style.position = 'absolute';
        container.appendChild(el);
        if (host) host.appendChild(container);
        slots.set(id, { container, el });
        return true;
    })()"""
)

private fun domSetSlotLayout(
    id: Int,
    visible: Boolean,
    cx: Double,
    cy: Double,
    cw: Double,
    ch: Double,
    vx: Double,
    vy: Double,
    vw: Double,
    vh: Double,
): Unit = js(
    """{
        const entry = globalThis.__composeRustNativeSlots && globalThis.__composeRustNativeSlots.get(id);
        if (entry && entry.container) {
            const c = entry.container;
            const e = entry.el;
            if (!visible) {
                c.style.display = 'none';
            } else {
                c.style.display = 'block';
                c.style.left = cx + 'px';
                c.style.top = cy + 'px';
                c.style.width = cw + 'px';
                c.style.height = ch + 'px';
                e.style.left = vx + 'px';
                e.style.top = vy + 'px';
                e.style.width = vw + 'px';
                e.style.height = vh + 'px';
            }
        }
    }"""
)

private fun domFocusSlot(id: Int): Unit = js(
    """{
        const entry = globalThis.__composeRustNativeSlots && globalThis.__composeRustNativeSlots.get(id);
        if (entry && entry.el && typeof entry.el.focus === 'function') entry.el.focus();
    }"""
)

private fun domBlurSlot(id: Int): Unit = js(
    """{
        const entry = globalThis.__composeRustNativeSlots && globalThis.__composeRustNativeSlots.get(id);
        if (entry && entry.el && typeof entry.el.blur === 'function') entry.el.blur();
    }"""
)

private fun domDetachSlot(id: Int): Unit = js(
    """{
        const slots = globalThis.__composeRustNativeSlots;
        if (!slots) return;
        const entry = slots.get(id);
        if (entry) {
            if (entry.el && entry.el.parentNode) entry.el.parentNode.removeChild(entry.el);
            if (entry.container && entry.container.parentNode) entry.container.parentNode.removeChild(entry.container);
            slots.delete(id);
        }
    }"""
)

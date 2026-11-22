@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SemanticsRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout

private const val ROOT = 1
private const val POPOVER = 2
private const val ANCHOR = 3
private const val MENU = 4
private val ITEMS = listOf(10, 11, 12)
private const val DISABLED = 13

private fun menuBatch(): List<Mutation> = buildList {
    add(Mutation.Create(ROOT, WidgetKind.Box))
    add(Mutation.SetModifier(ROOT, 0, ProtocolModifier.FillMaxWidth))
    add(Mutation.SetModifier(ROOT, 1, ProtocolModifier.FillMaxHeight))
    add(Mutation.Create(POPOVER, WidgetKind.Popover))
    add(Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(true)))
    add(Mutation.SetModifier(POPOVER, 0, ProtocolModifier.Semantics(SemanticsRole.Menu, 0)))
    add(Mutation.Create(ANCHOR, WidgetKind.Button))
    add(Mutation.SetProp(ANCHOR, PropertyKind.Text, PropertyValue.Text("Open menu")))
    add(Mutation.Insert(POPOVER, ANCHOR, 0))
    add(Mutation.Create(MENU, WidgetKind.Column))
    add(Mutation.Insert(POPOVER, MENU, 1))
    var position = 0
    for ((index, id) in ITEMS.withIndex()) {
        val selected = if (index == 1) 2 shl 2 else 1 shl 2
        add(Mutation.Create(id, WidgetKind.Box))
        add(Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(100L + id)))
        add(Mutation.SetModifier(id, 0, ProtocolModifier.Semantics(SemanticsRole.MenuItem, selected or (1 shl 11))))
        add(Mutation.Create(id + 20, WidgetKind.Text))
        add(Mutation.SetProp(id + 20, PropertyKind.Text, PropertyValue.Text("Item $id")))
        add(Mutation.Insert(id, id + 20, 0))
        add(Mutation.Insert(MENU, id, position++))
        if (index == 1) {
            add(Mutation.Create(DISABLED, WidgetKind.Box))
            add(Mutation.SetProp(DISABLED, PropertyKind.Enabled, PropertyValue.Bool(false)))
            add(Mutation.SetProp(DISABLED, PropertyKind.OnClick, PropertyValue.Integer(100L + DISABLED)))
            add(Mutation.SetModifier(DISABLED, 0, ProtocolModifier.Semantics(SemanticsRole.MenuItem, (1 shl 2) or (2 shl 6) or (1 shl 11))))
            add(Mutation.Create(DISABLED + 20, WidgetKind.Text))
            add(Mutation.SetProp(DISABLED + 20, PropertyKind.Text, PropertyValue.Text("Disabled")))
            add(Mutation.Insert(DISABLED, DISABLED + 20, 0))
            add(Mutation.Insert(MENU, DISABLED, position++))
        }
    }
    add(Mutation.Insert(ROOT, POPOVER, 0))
}

/** Exercise the browser's canvas and native keyboard events, not just the desktop scene. */
@OptIn(ExperimentalComposeUiApi::class)
class WebPopoverMenuTest {
    @Test
    fun fr77_web_popover_menu_keys_focus_and_activate_the_current_item(): Promise<JsAny?> = GlobalScope.promise<JsAny?> {
        createViewport()
        val connection = FakeHostConnection(menuBatch())
        try {
            ComposeViewport("web-popover-test") {
                ComposeRustContent(rememberComposeRustHost(remember { connection }), Modifier.fillMaxSize())
            }
            withTimeout(10_000) {
                while (!connection.initialized || !canvasReady()) delay(20)
            }
            // The initial selection is the second item. Activating it observes where focus went.
            delay(250)
            suspend fun press(key: String) {
                assertTrue(sendKey(key), "the Compose canvas is present")
                delay(80)
            }
            suspend fun activated(expected: Int, count: Int) {
                withTimeout(5_000) {
                    while (connection.events.filterIsInstance<HostEvent.Clicked>().size < count) delay(20)
                }
                assertEquals(expected, connection.events.filterIsInstance<HostEvent.Clicked>().last().nodeId)
            }
            press("Enter")
            activated(ITEMS[1], 1)
            press("ArrowDown")
            press("Enter")
            activated(ITEMS[2], 2)
            press("ArrowDown")
            press("Enter")
            activated(ITEMS[0], 3)
            press("ArrowUp")
            press("Enter")
            activated(ITEMS[2], 4)
            assertTrue(connection.events.none { it is HostEvent.KeyDown || it is HostEvent.KeyUp })
        } finally {
            removeViewport()
        }
        null
    }
}

private fun createViewport(): Unit = js("""{
    const old = document.getElementById('web-popover-test');
    if (old) old.remove();
    const container = document.createElement('div');
    container.id = 'web-popover-test';
    container.style.width = '800px';
    container.style.height = '600px';
    document.body.appendChild(container);
}""")

private fun canvasReady(): Boolean = js("""(() => {
    const root = document.getElementById('web-popover-test');
    return !!root?.firstElementChild?.firstElementChild?.shadowRoot?.querySelector('canvas');
})()""")

private fun sendKey(key: String): Boolean = js("""(() => {
    const canvas = document.getElementById('web-popover-test')?.firstElementChild?.firstElementChild?.shadowRoot?.querySelector('canvas');
    if (!canvas) return false;
    canvas.dispatchEvent(new KeyboardEvent('keydown', { key, code: key, bubbles: true, cancelable: true }));
    canvas.dispatchEvent(new KeyboardEvent('keyup', { key, code: key, bubbles: true, cancelable: true }));
    return true;
})()""")

private fun removeViewport(): Unit = js("document.getElementById('web-popover-test')?.remove()")

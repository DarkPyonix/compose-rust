package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.LastWindowClosed
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.Window
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.webZoomStorageKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebWindowBackendTest {
    @Test
    fun fr53_9_web_first_window_opens_and_second_emits_window_unavailable() {
        val titles = mutableListOf<String>()
        val backend = WebWindowBackend(applyTitle = { titles += it })
        val connection = FakeHostConnection()
        val host = ComposeRustHost(connection)
        host.start()
        val manager = WindowManager(host, backend, onExit = {})
        manager.attach()

        host.table.apply(
            Mutation.SetWindow(
                Window(
                    chrome = Chrome.Modern,
                    titleBar = TitleBar.Normal,
                    title = "",
                    icon = 0,
                    width = 0,
                    height = 0,
                    minWidth = 0,
                    minHeight = 0,
                    resizable = true,
                    mode = WindowMode.Windows,
                    lastWindowClosed = LastWindowClosed.KeepRunning,
                ),
            ),
        )
        host.table.apply(Mutation.Create(1, WidgetKind.Window))
        host.table.apply(Mutation.SetProp(1, PropertyKind.WindowTitle, PropertyValue.Text("First")))
        host.table.apply(Mutation.SetProp(1, PropertyKind.OnWindowUnavailable, PropertyValue.Integer(101)))
        host.table.apply(Mutation.InsertChild(0, 1, 0))

        host.table.apply(Mutation.Create(2, WidgetKind.Window))
        host.table.apply(Mutation.SetProp(2, PropertyKind.WindowTitle, PropertyValue.Text("Second")))
        host.table.apply(Mutation.SetProp(2, PropertyKind.OnWindowUnavailable, PropertyValue.Integer(202)))
        host.table.apply(Mutation.InsertChild(0, 2, 1))
        manager.sync()

        assertEquals(1, backend.activeNodeId)
        assertEquals(listOf(1), manager.openWindows)
        assertEquals(listOf("First"), titles)

        val unavailable = connection.events.filterIsInstance<HostEvent.WindowUnavailable>()
        assertEquals(1, unavailable.size)
        assertEquals(2, unavailable.single().nodeId)
        assertEquals(202L, unavailable.single().handlerId)

        // Syncing again does not re-emit WindowUnavailable for the same node.
        manager.sync()
        assertEquals(1, connection.events.filterIsInstance<HostEvent.WindowUnavailable>().size)

        manager.shutdown()
        assertEquals(0, backend.activeNodeId)
    }

    @Test
    fun fr43_web_zoom_key_is_scoped_by_app_identifier() {
        val todoKey = webZoomStorageKey("dev.darkpyonix.todo", "/samples/todo/")
        val notepadKey = webZoomStorageKey("dev.darkpyonix.notepad", "/samples/notepad/")
        assertEquals("compose-rust.zoom-level:dev.darkpyonix.todo", todoKey)
        assertEquals("compose-rust.zoom-level:dev.darkpyonix.notepad", notepadKey)
        assertTrue(todoKey != notepadKey)

        // Without an explicit identifier, different paths on the same origin still stay distinct.
        val pathA = webZoomStorageKey(null, "/samples/todo/")
        val pathB = webZoomStorageKey("", "/samples/notepad/")
        assertEquals("compose-rust.zoom-level:/samples/todo", pathA)
        assertEquals("compose-rust.zoom-level:/samples/notepad", pathB)
        assertTrue(pathA != pathB)
    }
}

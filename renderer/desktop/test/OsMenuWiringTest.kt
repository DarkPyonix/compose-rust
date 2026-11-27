@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.BlockingOsMenu
import dev.darkpyonix.composerust.foundation.BlockingOsMenuPresenter
import dev.darkpyonix.composerust.foundation.LocalOsMenuPresenter
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Item
import dev.darkpyonix.composerust.foundation.OsMenuPresenter
import dev.darkpyonix.composerust.foundation.OsMenuRequest
import dev.darkpyonix.composerust.foundation.OsMenuResult
import dev.darkpyonix.composerust.foundation.ProvideOsMenu
import dev.darkpyonix.composerust.foundation.runOsMenuAfterFrame
import dev.darkpyonix.composerust.protocol.ButtonVariant
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How the macOS and Windows hosts wire their entry menus, through the two pieces all four of
 * them share: [ProvideOsMenu] around each window's content, and [runOsMenuAfterFrame] in each
 * window's loop after the frame. The platform menu is a fake [BlockingOsMenu], so no window
 * is needed and this runs on any OS.
 */
class OsMenuWiringTest {

    /** A platform menu that answers [answer] and writes down what it was asked, and when. */
    private class Platform(var answer: (OsMenuRequest) -> List<Int>? = { null }) : BlockingOsMenu {
        val shown = mutableListOf<OsMenuRequest>()
        var closes = 0

        override fun show(request: OsMenuRequest): List<Int>? {
            shown += request
            return answer(request)
        }

        override fun close() {
            closes++
        }
    }

    private val anchor = NativeRect(10, 20, 120, 32)

    private fun request(label: String = "Open") = OsMenuRequest(listOf(Item(id = 1, label = label)), anchor)

    @Test
    fun fr33_2_a_window_content_wrapper_provides_that_windows_presenter() = runComposeUiTest {
        val first = BlockingOsMenuPresenter(Platform())
        val second = BlockingOsMenuPresenter(Platform())
        var inFirst: OsMenuPresenter? = null
        var inSecond: OsMenuPresenter? = null
        var outside: OsMenuPresenter? = first
        setContent {
            Column {
                outside = LocalOsMenuPresenter.current
                ProvideOsMenu(first) { inFirst = LocalOsMenuPresenter.current }
                ProvideOsMenu(second) { inSecond = LocalOsMenuPresenter.current }
            }
        }
        waitForIdle()
        assertSame(first, inFirst, "the first window's content does not see its own presenter")
        assertSame(second, inSecond, "the second window's content does not see its own presenter")
        assertNull(outside, "the presenter leaked out of the window it was given to")
    }

    @Test
    fun fr33_2_after_the_frame_the_loop_puts_the_waiting_menu_up_and_stays_awake() {
        val results = mutableListOf<OsMenuResult>()
        val platform = Platform { listOf(0) }
        val presenter = BlockingOsMenuPresenter(platform)
        presenter.open(request()) { results += it }
        assertTrue(platform.shown.isEmpty(), "the menu went up inside the frame that asked for it")
        assertTrue(presenter.waiting, "the loop is not told a menu waits, so it may sleep on its events")

        val awake = runOsMenuAfterFrame(presenter)

        assertEquals(1, platform.shown.size)
        assertEquals(listOf<OsMenuResult>(OsMenuResult.Chosen(listOf(0))), results)
        assertTrue(awake, "the choice needs a frame, and the loop was let sleep before drawing it")
        assertFalse(presenter.waiting)
    }

    @Test
    fun fr33_2_with_no_menu_waiting_the_loop_may_rest() {
        val platform = Platform()
        val presenter = BlockingOsMenuPresenter(platform)
        assertFalse(runOsMenuAfterFrame(presenter), "an idle window was kept spinning")
        assertTrue(platform.shown.isEmpty())
    }

    @Test
    fun fr33_2_a_menu_asked_for_while_one_is_up_keeps_the_loop_awake_for_the_next_turn() {
        val results = mutableListOf<OsMenuResult>()
        lateinit var presenter: BlockingOsMenuPresenter
        val platform = Platform { shown ->
            // A frame drawn while the system's menu loop runs asks for the next menu.
            if (shown.entries.first().let { it is Item && it.label == "Open" }) {
                presenter.open(request("Next")) { results += it }
            }
            null
        }
        presenter = BlockingOsMenuPresenter(platform)
        presenter.open(request()) { results += it }

        assertTrue(runOsMenuAfterFrame(presenter))
        assertEquals(1, platform.shown.size, "the second menu went up inside the first")
        assertTrue(presenter.waiting, "the loop would sleep with a menu waiting")

        assertTrue(runOsMenuAfterFrame(presenter))
        assertEquals(2, platform.shown.size)
        assertEquals(listOf<OsMenuResult>(OsMenuResult.Dismissed, OsMenuResult.Dismissed), results)
        assertFalse(runOsMenuAfterFrame(presenter))
    }

    // ----- the whole path, as a host runs it --------------------------------------------

    private fun textVariant() = PropertyValue.Integer(ButtonVariant.Text.ordinal + 1L)

    private fun menuBatch(): List<Mutation> = buildList {
        add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false)))
        add(Mutation.Create(1, WidgetKind.Box))
        add(Mutation.Insert(0, 1, 0))
        add(Mutation.Create(2, WidgetKind.Menu))
        add(Mutation.SetProp(2, PropertyKind.Open, PropertyValue.Bool(true)))
        add(Mutation.SetProp(2, PropertyKind.OnDismiss, PropertyValue.Integer(900L)))
        add(Mutation.Insert(1, 2, 0))
        add(Mutation.Create(3, WidgetKind.Button))
        add(Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("File")))
        add(Mutation.Insert(2, 3, 0))
        for ((index, label) in listOf("Open", "Save").withIndex()) {
            val id = 10 + index
            add(Mutation.Create(id, WidgetKind.Button))
            add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(label)))
            add(Mutation.SetProp(id, PropertyKind.Variant, textVariant()))
            add(Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(100L + id)))
            add(Mutation.SetProp(id, PropertyKind.MenuEntry, PropertyValue.Bool(true)))
            add(Mutation.Insert(2, id, index + 1))
        }
    }

    @Test
    fun fr33_2_an_open_menu_goes_up_from_the_loop_after_the_frame_and_its_choice_reaches_the_host() = runComposeUiTest {
        val platform = Platform { listOf(1) }
        val presenter = BlockingOsMenuPresenter(platform)
        val connection = FakeHostConnection(menuBatch())
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides FrameRequestSource(),
                LocalDensity provides Density(1f),
            ) {
                ProvideOsMenu(presenter) {
                    Box(Modifier.fillMaxSize()) {
                        ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
                    }
                }
            }
        }
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()

        assertTrue(platform.shown.isEmpty(), "the menu went up from inside composition")
        assertTrue(presenter.waiting, "the open menu never reached the window's presenter")

        // The window's loop, between frames.
        val awake = runOnIdle { runOsMenuAfterFrame(presenter) }
        waitForIdle()

        assertTrue(awake)
        assertEquals(1, platform.shown.size)
        assertEquals(listOf("Open", "Save"), platform.shown.single().entries.map { (it as Item).label })
        assertEquals(
            listOf(HostEvent.Clicked(11, 111L)),
            connection.events.filterIsInstance<HostEvent.Clicked>(),
        )
    }
}

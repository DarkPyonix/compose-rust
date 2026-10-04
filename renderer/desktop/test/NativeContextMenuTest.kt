@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.test.ExperimentalTestApi::class,
)

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.darkpyonix.composerust.ui.platform.NativeContextMenuRepresentation
import dev.darkpyonix.composerust.ui.platform.NativeMenuEntry
import dev.darkpyonix.composerust.ui.platform.WindowEvent
import dev.darkpyonix.composerust.ui.platform.receive
import org.jetbrains.skia.Surface
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FIELD = "field"

/**
 * The right-click menu: the system's by default, the application's when it gives one, and
 * one menu for one click either way.
 *
 * What the system draws cannot be seen from a test, so the menu is put up by a fake that
 * writes down what it was asked to show. A screenshot on the machine is what says that
 * the menu is an `NSMenu`.
 */
class NativeContextMenuTest {

    /** Every menu the native representation was asked for, as the entries it was given. */
    private val nativeRequests = mutableListOf<List<NativeMenuEntry>>()

    private val native = NativeContextMenuRepresentation { entries ->
        nativeRequests += entries
        -1
    }

    @Composable
    private fun Field(extra: List<ContextMenuItem> = emptyList()) {
        val state = rememberTextFieldState("hello world")
        ContextMenuDataProvider(items = { extra }) {
            BasicTextField(state, Modifier.testTag(FIELD))
        }
    }

    @Test
    fun fr33_1_one_right_click_on_a_field_asks_for_exactly_one_native_menu() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalContextMenuRepresentation provides native) { Field() }
        }
        onNodeWithTag(FIELD).performMouseInput { rightClick(center) }
        waitForIdle()

        assertEquals(1, nativeRequests.size, "one right click, one menu")
        val labels = nativeRequests.single().map { it.label }
        for (expected in listOf("Cut", "Copy", "Paste", "Select all")) {
            assertTrue(expected in labels, "the menu offers $expected: $labels")
        }
        assertEquals(
            listOf("Cut", "Copy", "Paste", "Select all"),
            labels.filter { it in setOf("Cut", "Copy", "Paste", "Select all") },
            "in the order every other menu on the platform has them",
        )
    }

    @Test
    fun fr33_1_the_native_menu_holds_the_items_the_drawn_menu_would() = runComposeUiTest {
        val custom = ContextMenuItem("Look Up in Notes") {}
        val drawnRequests = mutableListOf<List<NativeMenuEntry>>()
        val drawn = RecordingRepresentation(drawnRequests)
        var useNative by mutableStateOf(true)

        setContent {
            CompositionLocalProvider(
                LocalContextMenuRepresentation provides if (useNative) native else drawn,
            ) { Field(listOf(custom)) }
        }
        onNodeWithTag(FIELD).performMouseInput { rightClick(center) }
        waitForIdle()
        useNative = false
        waitForIdle()
        onNodeWithTag(FIELD).performMouseInput { rightClick(center) }
        waitForIdle()

        assertEquals(1, nativeRequests.size)
        assertEquals(1, drawnRequests.size)
        assertEquals(
            drawnRequests.single(),
            nativeRequests.single(),
            "the same items, in the same order and with the same enabled states, in both",
        )
        assertTrue(nativeRequests.single().any { it.label == custom.label })
    }

    @Test
    fun fr33_1_an_application_representation_replaces_the_native_menu() = runComposeUiTest {
        val appRequests = mutableListOf<List<NativeMenuEntry>>()
        setContent {
            CompositionLocalProvider(LocalContextMenuRepresentation provides native) {
                // What an application that draws its own menus provides, further in.
                CompositionLocalProvider(
                    LocalContextMenuRepresentation provides RecordingRepresentation(appRequests),
                ) { Field() }
            }
        }
        onNodeWithTag(FIELD).performMouseInput { rightClick(center) }
        waitForIdle()

        assertEquals(0, nativeRequests.size, "the system's menu does not come up as well")
        assertEquals(1, appRequests.size, "the application's menu comes up, once")
    }

    @Test
    fun fr33_1_an_application_text_context_menu_replaces_the_native_menu() = runComposeUiTest {
        var appOpened = 0
        val appMenu = object : TextContextMenu {
            @Composable
            override fun Area(
                textManager: TextContextMenu.TextManager,
                state: ContextMenuState,
                content: @Composable () -> Unit,
            ) {
                Box(
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press &&
                                    event.buttons.isSecondaryPressed
                                ) {
                                    appOpened++
                                }
                            }
                        }
                    },
                ) { content() }
            }
        }
        setContent {
            CompositionLocalProvider(LocalContextMenuRepresentation provides native) {
                CompositionLocalProvider(LocalTextContextMenu provides appMenu) { Field() }
            }
        }
        onNodeWithTag(FIELD).performMouseInput { rightClick(center) }
        waitForIdle()

        assertEquals(0, nativeRequests.size, "the system's menu does not come up as well")
        assertEquals(1, appOpened, "the application's menu is asked for, once")
    }

    /**
     * The native image's window records a right click with the secondary bit set, and the
     * scene must hear a secondary press. It used to hear a primary one: the click selected
     * text and no menu was asked for.
     */
    @Test
    fun fr33_1_a_right_click_from_the_window_reaches_the_scene_as_secondary() {
        val pressed = mutableListOf<PointerButton?>()
        val scene = CanvasLayersComposeScene(
            density = Density(1f),
            size = IntSize(100, 100),
            coroutineContext = Dispatchers.Unconfined,
        )
        try {
            scene.setContent {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press) pressed += event.button
                            }
                        }
                    },
                )
            }
            val surface = Surface.makeRasterN32Premul(100, 100)
            scene.render(surface.canvas.asComposeCanvas(), 0L)
            scene.receive(event(WindowEvent.POINTER_DOWN, 2 or WindowEvent.SECONDARY_BUTTON))
            scene.receive(event(WindowEvent.POINTER_UP, WindowEvent.SECONDARY_BUTTON))
            scene.receive(event(WindowEvent.POINTER_DOWN, 1))
            scene.receive(event(WindowEvent.POINTER_UP, 0))
            assertEquals<List<PointerButton?>>(listOf(PointerButton.Secondary, PointerButton.Primary), pressed)
        } finally {
            scene.close()
        }
    }

    private fun event(kind: Int, buttons: Int) =
        WindowEvent(kind, 10f, 10f, buttons, 0, 0, 0, "")
}

/** A menu an application draws itself, which writes down what it was asked to show. */
private class RecordingRepresentation(
    private val requests: MutableList<List<NativeMenuEntry>>,
) : ContextMenuRepresentation {
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        if (state.status is ContextMenuState.Status.Open) {
            androidx.compose.runtime.LaunchedEffect(state.status) {
                requests += items().map { NativeMenuEntry(it.label, it.enabled) }
                state.status = ContextMenuState.Status.Closed
            }
        }
    }
}

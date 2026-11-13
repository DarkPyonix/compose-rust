package dev.darkpyonix.composerust.test

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import dev.darkpyonix.composerust.ui.pressFeedbackTarget
import dev.darkpyonix.composerust.ui.pressReleaseDelay
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier

private const val CONTAINER = 1
private const val LABEL = 2
private const val HANDLER = 55L

private val CONTAINERS = listOf(
    WidgetKind.Row,
    WidgetKind.Column,
    WidgetKind.Box,
    WidgetKind.Card,
    WidgetKind.Surface,
)

private fun tree(kind: WidgetKind, clickable: Boolean) = buildList {
    add(Mutation.Create(CONTAINER, kind))
    if (clickable) add(Mutation.SetProp(CONTAINER, PropertyKind.OnClick, PropertyValue.Integer(HANDLER)))
    add(Mutation.Create(LABEL, WidgetKind.Text))
    add(Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("Open")))
    add(Mutation.Insert(CONTAINER, LABEL, 0))
}

private val isButton = SemanticsMatcher("has the Button role") {
    it.config.getOrNull(SemanticsProperties.Role) == Role.Button
}

/** A container with an `OnClick` is a control, not a pointer region. */
@OptIn(ExperimentalTestApi::class)
class ClickableContainerTest {
    @Test
    fun fr_55_a_click_on_each_container_reaches_the_host_once() {
        CONTAINERS.forEach { kind ->
            runComposeUiTest {
                val connection = FakeHostConnection(tree(kind, clickable = true))
                setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
                onNodeWithTag(nodeTestTag(CONTAINER)).performClick()
                waitForIdle()
                assertEquals(
                    listOf(HostEvent.Clicked(CONTAINER, HANDLER)),
                    connection.events.filterIsInstance<HostEvent.Clicked>(),
                    "$kind",
                )
            }
        }
    }

    @Test
    fun fr_55_enter_and_space_activate_a_focused_container() {
        CONTAINERS.forEach { kind ->
            listOf(Key.Enter, Key.Spacebar).forEach { key ->
                runComposeUiTest {
                    val connection = FakeHostConnection(tree(kind, clickable = true))
                    setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
                    onNodeWithTag(nodeTestTag(CONTAINER)).requestFocus()
                    onNodeWithTag(nodeTestTag(CONTAINER)).performKeyInput { pressKey(key) }
                    waitForIdle()
                    assertEquals(
                        listOf(HostEvent.Clicked(CONTAINER, HANDLER)),
                        connection.events.filterIsInstance<HostEvent.Clicked>(),
                        "$kind with $key",
                    )
                }
            }
        }
    }

    @Test
    fun fr_55_a_clickable_container_is_announced_as_a_button() {
        CONTAINERS.forEach { kind ->
            runComposeUiTest {
                val connection = FakeHostConnection(tree(kind, clickable = true))
                setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
                onNodeWithTag(nodeTestTag(CONTAINER)).assert(isButton).assertHasClickAction()
            }
        }
    }

    @Test
    fun fr_55_a_container_without_a_handler_is_not_a_control() {
        CONTAINERS.forEach { kind ->
            runComposeUiTest {
                val connection = FakeHostConnection(tree(kind, clickable = false))
                setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
                assertFalse(
                    onNodeWithTag(nodeTestTag(CONTAINER)).fetchSemanticsNode().config
                        .contains(androidx.compose.ui.semantics.SemanticsActions.OnClick),
                    "$kind",
                )
                onNodeWithTag(nodeTestTag(CONTAINER)).performMouseInput { press(); release() }
                waitForIdle()
                assertEquals(0, connection.events.filterIsInstance<HostEvent.Clicked>().size)
            }
        }
    }

    @Test
    fun fr_55_hovering_and_pressing_do_not_send_events() {
        runComposeUiTest {
            val connection = FakeHostConnection(tree(WidgetKind.Box, clickable = true))
            setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
            onNodeWithTag(nodeTestTag(CONTAINER)).performMouseInput { moveTo(center); press() }
            waitForIdle()
            assertEquals(0, connection.events.filterIsInstance<HostEvent.Clicked>().size)
        }
    }

    @Test
    fun fr_55_a_clickable_container_takes_keyboard_focus() {
        CONTAINERS.forEach { kind ->
            runComposeUiTest {
                val connection = FakeHostConnection(tree(kind, clickable = true))
                setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
                onNodeWithTag(nodeTestTag(CONTAINER)).requestFocus()
                onNodeWithTag(nodeTestTag(CONTAINER)).assertIsFocused()
            }
        }
    }

    @Test
    fun fr_55_hovering_and_pressing_change_what_is_drawn() {
        runComposeUiTest {
            val connection = FakeHostConnection(
                tree(WidgetKind.Box, clickable = true) +
                    Mutation.SetModifier(CONTAINER, 0, dev.darkpyonix.composerust.protocol.Modifier.Size(80f, 80f)),
            )
            setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
            waitForIdle()
            fun corner() = onNodeWithTag(nodeTestTag(CONTAINER)).captureToImage().toPixelMap()[70, 70].toArgb()
            val resting = corner()
            onNodeWithTag(nodeTestTag(CONTAINER)).performMouseInput { moveTo(center) }
            waitForIdle()
            val hovered = corner()
            onNodeWithTag(nodeTestTag(CONTAINER)).performMouseInput { press() }
            waitForIdle()
            val pressed = corner()
            kotlin.test.assertNotEquals(resting, hovered, "hover is drawn")
            kotlin.test.assertNotEquals(hovered, pressed, "press is drawn apart from hover")
        }
    }

    @Test
    fun fr51_press_tint_changes_the_surface_without_an_event() {
        runComposeUiTest {
            val connection = FakeHostConnection(
                tree(WidgetKind.Box, clickable = true) +
                    Mutation.SetModifier(CONTAINER, 0, dev.darkpyonix.composerust.protocol.Modifier.Size(80f, 80f)),
            )
            setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
            onNodeWithTag(nodeTestTag(CONTAINER)).performMouseInput { moveTo(center) }
            waitForIdle()
            fun pixel() = onNodeWithTag(nodeTestTag(CONTAINER)).captureToImage().toPixelMap()[70, 70].toArgb()
            val hover = pixel()
            onNodeWithTag(nodeTestTag(CONTAINER)).performMouseInput { press() }
            waitForIdle()
            kotlin.test.assertNotEquals(hover, pixel())
            assertEquals(0, connection.events.filterIsInstance<HostEvent.Clicked>().size)
        }
    }

    @Test
    fun fr51_press_feedback_can_be_turned_off() {
        runComposeUiTest {
            val connection = FakeHostConnection(
                tree(WidgetKind.Box, clickable = true) + listOf(
                    Mutation.SetModifier(CONTAINER, 0, dev.darkpyonix.composerust.protocol.Modifier.Size(80f, 80f)),
                    Mutation.SetModifier(CONTAINER, 1, dev.darkpyonix.composerust.protocol.Modifier.PressFeedback(1f, 0f)),
                ),
            )
            setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
            onNodeWithTag(nodeTestTag(CONTAINER)).performMouseInput { moveTo(center) }
            waitForIdle()
            fun pixel() = onNodeWithTag(nodeTestTag(CONTAINER)).captureToImage().toPixelMap()[70, 70].toArgb()
            val hover = pixel()
            onNodeWithTag(nodeTestTag(CONTAINER)).performMouseInput { press() }
            waitForIdle()
            assertEquals(hover, pixel())
        }
    }

    @Test
    fun fr51_press_scale_and_release_timing() {
        val pressed = pressFeedbackTarget(true, true, false, false, null)
        assertEquals(0.975f, pressed.scale, 0.0001f)
        assertEquals(0.07f, pressed.tint, 0.0001f)
        assertEquals(120L, pressReleaseDelay(0))
        assertEquals(20L, pressReleaseDelay(100))
        assertEquals(0L, pressReleaseDelay(200))
        val released = pressFeedbackTarget(false, true, false, false, null)
        assertEquals(1f, released.scale)
        assertEquals(0f, released.tint)
    }

    @Test
    fun fr51_reduced_motion_disabled_and_custom_feedback() {
        val reduced = pressFeedbackTarget(true, true, true, true, null)
        assertEquals(1f, reduced.scale)
        assertEquals(0.10f, reduced.tint, 0.0001f)
        val disabled = pressFeedbackTarget(true, false, false, false, null)
        assertEquals(1f, disabled.scale)
        assertEquals(0f, disabled.tint)
        val invisible = pressFeedbackTarget(true, true, false, false, ProtocolModifier.PressFeedback(1f, 0f))
        assertEquals(1f, invisible.scale)
        assertEquals(0f, invisible.tint)
    }

    @Test
    fun fr51_transparent_row_draws_tint_and_restores_hover_after_release() {
        runComposeUiTest {
            val connection = FakeHostConnection(
                tree(WidgetKind.Row, clickable = true) +
                    Mutation.SetModifier(CONTAINER, 0, ProtocolModifier.Size(80f, 80f)),
            )
            setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
            fun pixel() = onNodeWithTag(nodeTestTag(CONTAINER)).captureToImage().toPixelMap()[70, 70].toArgb()
            val resting = pixel()
            onNodeWithTag(nodeTestTag(CONTAINER)).performMouseInput { moveTo(center) }
            waitForIdle()
            val hover = pixel()
            assertNotEquals(resting, hover)
            onNodeWithTag(nodeTestTag(CONTAINER)).performMouseInput { press() }
            waitForIdle()
            assertNotEquals(hover, pixel())
            onNodeWithTag(nodeTestTag(CONTAINER)).performMouseInput { release() }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertEquals(hover, pixel())
        }
    }

    @Test
    fun fr51_button_draws_press_feedback_and_disabled_button_does_not() {
        listOf(true, false).forEach { enabled ->
            runComposeUiTest {
                val connection = FakeHostConnection(listOf(
                    Mutation.Create(CONTAINER, WidgetKind.Button),
                    Mutation.SetProp(CONTAINER, PropertyKind.OnClick, PropertyValue.Integer(HANDLER)),
                    Mutation.SetProp(CONTAINER, PropertyKind.Enabled, PropertyValue.Bool(enabled)),
                    Mutation.SetModifier(CONTAINER, 0, ProtocolModifier.Size(80f, 80f)),
                ))
                setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
                onNodeWithTag(nodeTestTag(CONTAINER)).performMouseInput { moveTo(center) }
                waitForIdle()
                fun pixel() = onNodeWithTag(nodeTestTag(CONTAINER)).captureToImage().toPixelMap()[40, 40].toArgb()
                val before = pixel()
                onNodeWithTag(nodeTestTag(CONTAINER)).performMouseInput { press() }
                waitForIdle()
                if (enabled) assertNotEquals(before, pixel()) else assertEquals(before, pixel())
                assertEquals(0, connection.events.filterIsInstance<HostEvent.Clicked>().size)
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.paddedContainer(): () -> Int {
        val connection = FakeHostConnection(
            tree(WidgetKind.Box, clickable = true) + listOf(
                Mutation.SetModifier(CONTAINER, 0, dev.darkpyonix.composerust.protocol.Modifier.Size(80f, 40f)),
                Mutation.SetModifier(CONTAINER, 1, dev.darkpyonix.composerust.protocol.Modifier.Padding(16f)),
            ),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        waitForIdle()
        return { onNodeWithTag(nodeTestTag(CONTAINER)).captureToImage().toPixelMap()[40, 1].toArgb() }
    }

    @Test
    fun fr_55_focus_ring_is_not_drawn_after_a_pointer_click() {
        runComposeUiTest {
            val edgePixel = paddedContainer()
            val resting = edgePixel()
            onNodeWithTag(nodeTestTag(CONTAINER)).performMouseInput {
                moveTo(center)
                press()
                release()
                moveTo(androidx.compose.ui.geometry.Offset(-20f, -20f))
            }
            waitForIdle()
            assertEquals(resting, edgePixel(), "pointer click must not leave a focus ring")
        }
    }

    @Test
    fun fr_55_focus_ring_is_drawn_on_keyboard_focus_at_outer_padded_bounds() {
        runComposeUiTest {
            val edgePixel = paddedContainer()
            val resting = edgePixel()
            onNodeWithTag(nodeTestTag(CONTAINER)).requestFocus()
            waitForIdle()
            kotlin.test.assertNotEquals(
                resting,
                edgePixel(),
                "keyboard focus ring is drawn at the outer edge even when Padding is present",
            )
        }
    }
}

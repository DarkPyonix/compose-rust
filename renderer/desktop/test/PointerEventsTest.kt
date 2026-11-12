package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.foundation.hostWheelDeltaMode
import dev.darkpyonix.composerust.foundation.pointerKindBit
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PointerKind
import dev.darkpyonix.composerust.protocol.PointerType
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WheelDeltaMode
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.LocalZoom
import dev.darkpyonix.composerust.runtime.Zoom
import dev.darkpyonix.composerust.runtime.hostPlatformOverride
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier

private const val OUTER = 1
private const val INNER = 2
private const val OTHER = 3
private const val OUTER_HANDLER = 71L
private const val INNER_HANDLER = 72L
private const val OTHER_HANDLER = 73L

/** Every scene is drawn at two pixels to the dp, so a position sent in pixels would show. */
private const val SCALE = 2f

private fun kinds(vararg kinds: PointerKind): Long =
    kinds.fold(0) { mask, kind -> mask or pointerKindBit(kind) }.toLong()

private val EVERY_KIND = kinds(*PointerKind.entries.toTypedArray())

private fun MutableList<Mutation>.box(
    id: Int,
    width: Float,
    height: Float,
    widget: WidgetKind = WidgetKind.Box,
    vararg more: ProtocolModifier,
) {
    add(Mutation.Create(id, widget))
    add(Mutation.SetModifier(id, 0, ProtocolModifier.RequiredSize(width, height)))
    more.forEachIndexed { index, modifier -> add(Mutation.SetModifier(id, index + 1, modifier)) }
}

private fun MutableList<Mutation>.subscribe(id: Int, handler: Long, mask: Long) {
    add(Mutation.SetProp(id, PropertyKind.OnPointer, PropertyValue.Integer(handler)))
    add(Mutation.SetProp(id, PropertyKind.PointerKinds, PropertyValue.Integer(mask)))
}

/** One box of 200 by 200 dp, subscribed to `mask`. */
private fun single(mask: Long) = buildList {
    box(OUTER, 200f, 200f)
    subscribe(OUTER, OUTER_HANDLER, mask)
}

/**
 * A box of 200 by 200 dp with 20 dp of padding, and a box of 100 by 100 dp inside it, so the
 * same pointer is 20 dp further along in the outer one than in the inner one.
 */
private fun nested(outerMask: Long, innerMask: Long) = buildList {
    box(OUTER, 200f, 200f, WidgetKind.Box, ProtocolModifier.Padding(20f))
    subscribe(OUTER, OUTER_HANDLER, outerMask)
    box(INNER, 100f, 100f)
    subscribe(INNER, INNER_HANDLER, innerMask)
    add(Mutation.Insert(OUTER, INNER, 0))
}

/** Pixels of the scene for a position in dp. */
private fun at(x: Float, y: Float) = Offset(x * SCALE, y * SCALE)

private fun FakeHostConnection.pointers(node: Int? = null): List<HostEvent.Pointer> =
    events.filterIsInstance<HostEvent.Pointer>().filter { node == null || it.nodeId == node }

private fun FakeHostConnection.kindsSeen(node: Int? = null): List<PointerKind> = pointers(node).map { it.kind }

private fun assertNear(expected: Float, actual: Float, what: String) {
    assertTrue(abs(expected - actual) <= 0.6f, "$what: expected about $expected, got $actual")
}

/** Node pointer events, from Compose's input to the records the Host is sent. */
@OptIn(ExperimentalTestApi::class)
class PointerEventsTest {
    @AfterTest
    fun restore() {
        hostPlatformOverride = null
        hostWheelDeltaMode = WheelDeltaMode.Line
    }

    /** Whether each press had been consumed by the time every node had seen it. */
    private val pressesConsumed = mutableListOf<Boolean>()

    private fun ComposeUiTest.show(connection: FakeHostConnection): ComposeRustHost {
        lateinit var shown: ComposeRustHost
        setContent {
            CompositionLocalProvider(
                LocalZoom provides remember { Zoom() },
                LocalDensity provides Density(SCALE),
            ) {
                val host = rememberComposeRustHost(connection)
                shown = host
                ComposeRustContent(
                    host,
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (event.type == PointerEventType.Press) {
                                    pressesConsumed += event.changes.all { it.isConsumed }
                                }
                            }
                        }
                    },
                )
            }
        }
        waitForIdle()
        return shown
    }

    @Test
    fun fr46_unsubscribed_node_gets_no_pointer_modifier() {
        val plain = buildList { box(OUTER, 200f, 200f) }
        val handlerOnly = buildList {
            box(OUTER, 200f, 200f)
            add(Mutation.SetProp(OUTER, PropertyKind.OnPointer, PropertyValue.Integer(OUTER_HANDLER)))
        }
        val emptyMask = single(0)
        listOf(plain, handlerOnly, emptyMask).forEachIndexed { index, tree ->
            runComposeUiTest {
                val connection = FakeHostConnection(tree)
                val host = show(connection)
                assertEquals(0, host.table.pointers.subscribedNodes, "tree $index")
                onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
                    moveTo(at(50f, 50f))
                    press()
                    release()
                }
                waitForIdle()
                assertEquals(emptyList(), connection.pointers(), "tree $index")
            }
        }
        runComposeUiTest {
            val host = show(FakeHostConnection(single(EVERY_KIND)))
            assertEquals(1, host.table.pointers.subscribedNodes)
        }
    }

    @Test
    fun fr46_only_masked_kinds_are_sent() = runComposeUiTest {
        val connection = FakeHostConnection(single(kinds(PointerKind.Down, PointerKind.Up)))
        val host = show(connection)
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
            moveTo(at(50f, 50f))
            moveTo(at(60f, 60f))
            moveTo(at(70f, 70f))
            press()
            release()
            scroll(1f)
            moveTo(at(300f, 300f))
        }
        waitForIdle()
        assertEquals(listOf(PointerKind.Down, PointerKind.Up), connection.kindsSeen())
        // Nothing was ever held for a frame: a node that did not ask for moves has none tracked.
        assertEquals(0, host.table.pointers.pendingGeneration.intValue)
    }

    @Test
    fun fr46_hover_enter_move_leave_in_order() = runComposeUiTest {
        val connection = FakeHostConnection(single(kinds(PointerKind.Enter, PointerKind.Move, PointerKind.Leave)))
        show(connection)
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
            moveTo(at(50f, 40f))
            moveTo(at(80f, 30f))
        }
        waitForIdle()
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput { moveTo(at(300f, 300f)) }
        waitForIdle()
        val seen = connection.pointers()
        assertEquals(listOf(PointerKind.Enter, PointerKind.Move, PointerKind.Leave), seen.map { it.kind })
        assertNear(50f, seen[0].x, "Enter x")
        assertNear(40f, seen[0].y, "Enter y")
        assertNear(80f, seen[1].x, "Move x")
        assertNear(30f, seen[1].y, "Move y")
        assertTrue(seen[2].x > 200f, "Leave is where the pointer went, outside the node: ${seen[2].x}")
        seen.forEach {
            assertEquals(OUTER, it.nodeId)
            assertEquals(OUTER_HANDLER, it.handlerId)
            assertEquals(1, it.pointerId)
            assertEquals(PointerType.Mouse, it.pointerType)
        }
    }

    @Test
    fun fr46_moves_coalesce_to_one_per_frame() = runComposeUiTest {
        val connection = FakeHostConnection(single(kinds(PointerKind.Move)))
        show(connection)
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
            moveTo(at(10f, 10f))
            for (step in 1..40) moveTo(at(10f + step, 10f + step * 2), delayMillis = 0)
        }
        waitForIdle()
        val moves = connection.pointers()
        assertEquals(1, moves.size, "forty moves inside one frame are one record: $moves")
        assertNear(50f, moves[0].x, "the last x")
        assertNear(90f, moves[0].y, "the last y")
    }

    @Test
    fun fr46_pending_move_flushes_before_down() = runComposeUiTest {
        val connection = FakeHostConnection(single(kinds(PointerKind.Move, PointerKind.Down)))
        show(connection)
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
            moveTo(at(10f, 10f))
            for (step in 1..10) moveTo(at(10f + step, 10f), delayMillis = 0)
            press()
        }
        waitForIdle()
        val seen = connection.pointers()
        assertEquals(listOf(PointerKind.Move, PointerKind.Down), seen.map { it.kind })
        assertNear(20f, seen[0].x, "the move carries where the pointer was last")
        assertNear(20f, seen[1].x, "the press is where the move ended")
    }

    @Test
    fun fr46_button_bits_match_compose_pointer_buttons() {
        // Compose numbers the buttons 0 to 4 in this order; the wire gives each its own bit.
        val buttons = listOf(0 to 1, 2 to 4, 3 to 8, 4 to 16)
        buttons.forEach { (index, bit) ->
            runComposeUiTest {
                val connection = FakeHostConnection(single(kinds(PointerKind.Down, PointerKind.Up)))
                show(connection)
                onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
                    moveTo(at(50f, 50f))
                    press(MouseButton(index))
                    release(MouseButton(index))
                }
                waitForIdle()
                val seen = connection.pointers()
                assertEquals(listOf(PointerKind.Down, PointerKind.Up), seen.map { it.kind }, "button $index")
                assertEquals(bit, seen[0].button, "Down.button of button $index")
                assertEquals(bit, seen[0].buttons, "Down.buttons of button $index")
                assertEquals(bit, seen[1].button, "Up.button of button $index")
                assertEquals(0, seen[1].buttons, "Up.buttons of button $index")
            }
        }
        // The secondary button asks for a menu when it goes down, and is a button when it comes up.
        runComposeUiTest {
            val connection = FakeHostConnection(single(kinds(PointerKind.Down, PointerKind.Up, PointerKind.ContextMenu)))
            show(connection)
            onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
                moveTo(at(50f, 50f))
                press(MouseButton.Secondary)
                release(MouseButton.Secondary)
            }
            waitForIdle()
            val seen = connection.pointers()
            assertEquals(listOf(PointerKind.ContextMenu, PointerKind.Up), seen.map { it.kind })
            assertEquals(2, seen[0].buttons)
            assertEquals(2, seen[1].button)
        }
    }

    @Test
    fun fr46_double_press_reports_click_count_two() = runComposeUiTest {
        val connection = FakeHostConnection(single(kinds(PointerKind.Down, PointerKind.Up)))
        show(connection)
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
            moveTo(at(50f, 50f))
            press()
            release()
            press()
            release()
            // Long after the second press, the next one starts a new run.
            advanceEventTime(5_000)
            press()
            release()
        }
        waitForIdle()
        val seen = connection.pointers()
        assertEquals(
            listOf(PointerKind.Down, PointerKind.Up, PointerKind.Down, PointerKind.Up, PointerKind.Down, PointerKind.Up),
            seen.map { it.kind },
        )
        assertEquals(listOf(1, 1, 2, 2, 1, 1), seen.map { it.clickCount })
    }

    @Test
    fun fr46_context_menu_kind_and_consume() {
        listOf(0L, 1L).forEach { answer ->
            pressesConsumed.clear()
            runComposeUiTest {
                val connection = FakeHostConnection(single(kinds(PointerKind.Down, PointerKind.ContextMenu)))
                connection.respondWith { HostResponse(result = answer) }
                show(connection)
                onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
                    moveTo(at(50f, 50f))
                    press(MouseButton.Secondary)
                    release(MouseButton.Secondary)
                }
                waitForIdle()
                val seen = connection.pointers()
                assertEquals(listOf(PointerKind.ContextMenu), seen.map { it.kind }, "a menu, and no Down beside it")
                assertEquals(0, seen[0].button)
                assertEquals(0, seen[0].clickCount)
                assertNear(50f, seen[0].x, "x")
                // Consumed by the handler means consumed for Compose, so nothing opens a menu on it.
                assertEquals(listOf(answer != 0L), pressesConsumed, "answer $answer")
            }
        }
    }

    @Test
    fun fr46_control_click_is_a_context_menu_on_macos() = runComposeUiTest {
        hostPlatformOverride = HostPlatform.MacOs
        val connection = FakeHostConnection(single(kinds(PointerKind.Down, PointerKind.ContextMenu)))
        show(connection)
        onNodeWithTag(nodeTestTag(OUTER)).performKeyInput { keyDown(Key.CtrlLeft) }
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
            moveTo(at(50f, 50f))
            press()
            release()
        }
        onNodeWithTag(nodeTestTag(OUTER)).performKeyInput { keyUp(Key.CtrlLeft) }
        waitForIdle()
        assertEquals(listOf(PointerKind.ContextMenu), connection.kindsSeen())
        assertEquals(2, connection.pointers()[0].modifiers, "Ctrl is 2")
    }

    @Test
    fun fr46_wheel_deltas_sum_per_frame_and_pass_through() = runComposeUiTest {
        val tree = buildList {
            box(OUTER, 100f, 100f, WidgetKind.ScrollColumn)
            box(INNER, 100f, 400f)
            subscribe(INNER, INNER_HANDLER, kinds(PointerKind.Wheel))
            add(Mutation.Insert(OUTER, INNER, 0))
        }
        val connection = FakeHostConnection(tree)
        show(connection)
        fun contentTop() = onNodeWithTag(nodeTestTag(INNER)).fetchSemanticsNode().positionInRoot.y
        val before = contentTop()
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
            moveTo(at(50f, 50f))
            scroll(1f)
            scroll(1f)
            scroll(1f)
        }
        waitForIdle()
        val wheels = connection.pointers()
        assertEquals(listOf(PointerKind.Wheel), wheels.map { it.kind }, "three wheels in a frame are one record")
        assertNear(3f, wheels[0].deltaY, "the travel added up")
        assertNear(0f, wheels[0].deltaX, "no sideways travel")
        assertEquals(WheelDeltaMode.Line, wheels[0].deltaMode)
        assertTrue(contentTop() < before, "a wheel nobody consumed scrolls the column around it")
    }

    @Test
    fun fr46_consumed_wheel_does_not_scroll() = runComposeUiTest {
        val tree = buildList {
            box(OUTER, 100f, 100f, WidgetKind.ScrollColumn)
            box(INNER, 100f, 400f)
            subscribe(INNER, INNER_HANDLER, kinds(PointerKind.Wheel))
            add(Mutation.Insert(OUTER, INNER, 0))
        }
        val connection = FakeHostConnection(tree)
        connection.respondWith { HostResponse(result = 1) }
        show(connection)
        fun contentTop() = onNodeWithTag(nodeTestTag(INNER)).fetchSemanticsNode().positionInRoot.y
        // The answer to a wheel arrives with the frame it is sent in, so it is the wheels
        // after the first answer that Compose is kept from.
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
            moveTo(at(50f, 50f))
            scroll(1f)
        }
        waitForIdle()
        val settled = contentTop()
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput { scroll(1f) }
        waitForIdle()
        assertEquals(2, connection.pointers().size)
        assertEquals(settled, contentTop(), "a wheel the handler takes does not scroll the column")
    }

    @Test
    fun fr46_capture_keeps_moves_after_leaving_bounds() = runComposeUiTest {
        val tree = buildList {
            add(Mutation.Create(OUTER, WidgetKind.Row))
            box(INNER, 100f, 100f)
            subscribe(
                INNER,
                INNER_HANDLER,
                kinds(PointerKind.Down, PointerKind.Move, PointerKind.Up, PointerKind.Enter, PointerKind.Leave),
            )
            add(Mutation.Insert(OUTER, INNER, 0))
            box(OTHER, 100f, 100f)
            subscribe(OTHER, OTHER_HANDLER, kinds(PointerKind.Enter, PointerKind.Move))
            add(Mutation.Insert(OUTER, OTHER, 1))
        }
        val connection = FakeHostConnection(tree)
        show(connection)
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
            moveTo(at(50f, 50f))
            press()
            moveTo(at(150f, 50f))
            moveTo(at(160f, 50f))
        }
        waitForIdle()
        assertEquals(emptyList(), connection.pointers(OTHER), "the node the pointer is over hears nothing during the press")
        val held = connection.pointers(INNER)
        assertEquals(listOf(PointerKind.Enter, PointerKind.Down), held.take(2).map { it.kind })
        val moves = held.filter { it.kind == PointerKind.Move }
        assertTrue(moves.isNotEmpty(), "the node that took the press keeps the moves")
        assertNear(160f, moves.last().x, "outside its own 100 dp, in its own space")
        assertEquals(1, moves.last().buttons)
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput { release() }
        waitForIdle()
        assertTrue(PointerKind.Up in connection.kindsSeen(INNER), "and the release")
        assertEquals(0, connection.pointers(OTHER).count { it.kind == PointerKind.Move })
    }

    @Test
    fun fr46_cancel_ends_capture() = runComposeUiTest {
        val connection = FakeHostConnection(single(EVERY_KIND))
        show(connection)
        onNodeWithTag(nodeTestTag(OUTER)).performTouchInput {
            down(at(50f, 50f))
            cancel()
        }
        waitForIdle()
        val seen = connection.pointers()
        assertEquals(
            listOf(PointerKind.Enter, PointerKind.Down, PointerKind.Cancel, PointerKind.Leave),
            seen.map { it.kind },
        )
        assertEquals(0, seen[2].buttons, "a cancel lets go of what was held")
        // A new touch starts from nothing: it is entered and pressed again, by itself.
        onNodeWithTag(nodeTestTag(OUTER)).performTouchInput {
            down(at(60f, 60f))
            up()
        }
        waitForIdle()
        assertEquals(
            listOf(PointerKind.Enter, PointerKind.Down, PointerKind.Up, PointerKind.Leave),
            connection.kindsSeen().drop(4),
        )
    }

    @Test
    fun fr53_torn_off_window_cancels_the_capture_of_the_pressed_node() = runComposeUiTest {
        val connection = FakeHostConnection(
            single(kinds(PointerKind.Down, PointerKind.Move, PointerKind.Up, PointerKind.Cancel)),
        )
        val host = show(connection)
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
            moveTo(at(50f, 50f))
            press()
        }
        waitForIdle()
        assertEquals(PointerKind.Down, connection.kindsSeen().last())
        // No node holds a pointer the Host never heard of, and nothing is sent for one.
        assertEquals(false, runOnIdle { host.table.pointers.cancelPointer(7) })
        assertEquals(PointerKind.Down, connection.kindsSeen().last())

        // The window manager takes the mouse over for the window it tore off.
        assertEquals(true, runOnIdle { host.table.pointers.cancelPointer(1) })
        val cancel = connection.pointers().last()
        assertEquals(PointerKind.Cancel, cancel.kind)
        assertEquals(OUTER, cancel.nodeId)
        assertEquals(1, cancel.pointerId)
        assertEquals(0, cancel.buttons, "a cancel lets go of what was held")
        assertEquals(1, connection.kindsSeen().count { it == PointerKind.Cancel })
        assertEquals(false, runOnIdle { host.table.pointers.cancelPointer(1) }, "the capture ended with the Cancel")

        // The window the press began in still hears the drag and the release. Neither is sent.
        val told = connection.pointers().size
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
            moveTo(at(70f, 60f))
            moveTo(at(90f, 70f))
            release()
        }
        waitForIdle()
        assertEquals(emptyList(), connection.kindsSeen().drop(told), "the rest of a cancelled press reached the Host")

        // The next press is a press like any other.
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
            press()
            release()
        }
        waitForIdle()
        assertEquals(listOf(PointerKind.Down, PointerKind.Up), connection.kindsSeen().drop(told))
    }

    @Test
    fun fr46_removed_capture_node_drops_silently() = runComposeUiTest {
        val connection = FakeHostConnection(nested(outerMask = 0, innerMask = kinds(PointerKind.Down, PointerKind.Move, PointerKind.Up)))
        // The Host answers the press by removing the node that took it.
        connection.respondWith { event ->
            if (event is HostEvent.Pointer && event.kind == PointerKind.Down) {
                HostResponse(listOf(Mutation.Remove(INNER)))
            } else {
                HostResponse()
            }
        }
        show(connection)
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
            moveTo(at(50f, 50f))
            press()
            moveTo(at(60f, 60f), delayMillis = 0)
            moveTo(at(70f, 70f), delayMillis = 0)
            release()
        }
        waitForIdle()
        assertEquals(PointerKind.Down, connection.kindsSeen().last(), "nothing follows the press: ${connection.kindsSeen()}")
        assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>())
    }

    @Test
    fun fr46_coordinates_are_mapped_back_through_transform() {
        // rotate(30deg) about the middle of a 100 dp box.
        val cos = 0.8660254f
        val sin = 0.5f
        runComposeUiTest {
            val tree = buildList {
                box(OUTER, 200f, 200f)
                box(INNER, 100f, 100f, WidgetKind.Box, ProtocolModifier.Transform(cos, sin, -sin, cos, 0f, 0f, 0.5f, 0.5f))
                subscribe(INNER, INNER_HANDLER, kinds(PointerKind.Down))
                add(Mutation.Insert(OUTER, INNER, 0))
            }
            val connection = FakeHostConnection(tree)
            show(connection)
            // 20 dp to the right of the middle on screen is 20 dp along the turned x axis
            // taken back by 30 degrees: (20 cos 30, -20 sin 30) from the middle.
            onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
                moveTo(at(70f, 50f))
                press()
                release()
            }
            waitForIdle()
            val down = connection.pointers().single()
            assertNear(50f + 20f * cos, down.x, "x in the box before it was turned")
            assertNear(50f - 20f * sin, down.y, "y in the box before it was turned")
        }
        // A box scaled to nothing has no inverse, and no position in it to report.
        runComposeUiTest {
            val tree = buildList {
                box(OUTER, 200f, 200f)
                box(INNER, 100f, 100f, WidgetKind.Box, ProtocolModifier.Transform(0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 0.5f))
                subscribe(INNER, INNER_HANDLER, EVERY_KIND)
                add(Mutation.Insert(OUTER, INNER, 0))
            }
            val connection = FakeHostConnection(tree)
            show(connection)
            onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
                moveTo(at(50f, 50f))
                press()
                release()
                moveTo(at(60f, 60f))
            }
            waitForIdle()
            assertEquals(emptyList(), connection.pointers())
        }
    }

    @Test
    fun fr46_island_pointer_coordinates_exclude_zoom() = runComposeUiTest {
        hostWheelDeltaMode = WheelDeltaMode.Pixel
        val tree = buildList {
            box(OUTER, 300f, 300f, WidgetKind.AbsoluteBox)
            add(Mutation.SetProp(OUTER, PropertyKind.IslandZoom, PropertyValue.Float(1.5f)))
            subscribe(OUTER, OUTER_HANDLER, kinds(PointerKind.Down, PointerKind.Wheel))
        }
        val connection = FakeHostConnection(tree)
        show(connection)
        onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
            moveTo(at(150f, 60f))
            press()
            release()
            scroll(30f)
        }
        waitForIdle()
        val seen = connection.pointers()
        assertEquals(listOf(PointerKind.Down, PointerKind.Wheel), seen.map { it.kind })
        // 150 dp of the window is 100 of the island's own pixels at a zoom of 1.5.
        assertNear(100f, seen[0].x, "x in the island's pixels")
        assertNear(40f, seen[0].y, "y in the island's pixels")
        assertEquals(WheelDeltaMode.Pixel, seen[1].deltaMode)
        assertNear(30f / (SCALE * 1.5f), seen[1].deltaY, "pixel travel in the island's pixels")
    }

    @Test
    fun fr46_unconsumed_bubbles_to_subscribed_ancestor() {
        val both = kinds(PointerKind.Down, PointerKind.Enter, PointerKind.Leave)
        // Nobody consumes: the inner node first, then the outer one, each in its own space.
        runComposeUiTest {
            val connection = FakeHostConnection(nested(both, both))
            show(connection)
            onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
                moveTo(at(50f, 60f))
                press()
                release()
            }
            waitForIdle()
            val downs = connection.pointers().filter { it.kind == PointerKind.Down }
            assertEquals(listOf(INNER, OUTER), downs.map { it.nodeId })
            assertEquals(listOf(INNER_HANDLER, OUTER_HANDLER), downs.map { it.handlerId })
            assertNear(30f, downs[0].x, "inner x, inside the outer padding")
            assertNear(40f, downs[0].y, "inner y")
            assertNear(50f, downs[1].x, "outer x")
            assertNear(60f, downs[1].y, "outer y")
            // Entering is not passed up: each node is entered once, for itself.
            val enters = connection.pointers().filter { it.kind == PointerKind.Enter }
            assertEquals(setOf(INNER, OUTER), enters.map { it.nodeId }.toSet())
            assertEquals(2, enters.size)
        }
        // The inner handler consumes: the outer one never hears the press.
        runComposeUiTest {
            pressesConsumed.clear()
            val connection = FakeHostConnection(nested(both, both))
            connection.respondWith { event ->
                val taken = event is HostEvent.Pointer && event.kind == PointerKind.Down && event.nodeId == INNER
                HostResponse(result = if (taken) 1 else 0)
            }
            show(connection)
            onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
                moveTo(at(50f, 60f))
                press()
                release()
            }
            waitForIdle()
            val downs = connection.pointers().filter { it.kind == PointerKind.Down }
            assertEquals(listOf(INNER), downs.map { it.nodeId })
            assertEquals(listOf(true), pressesConsumed)
        }
        // A node between that did not subscribe to the kind is passed over.
        runComposeUiTest {
            val connection = FakeHostConnection(nested(kinds(PointerKind.Up), kinds(PointerKind.Down)))
            show(connection)
            onNodeWithTag(nodeTestTag(OUTER)).performMouseInput {
                moveTo(at(50f, 60f))
                press()
                release()
            }
            waitForIdle()
            assertEquals(
                listOf(INNER to PointerKind.Down, OUTER to PointerKind.Up),
                connection.pointers().map { it.nodeId to it.kind },
            )
        }
    }

    @Test
    fun fr46_touch_enters_before_down_and_leaves_after_up() = runComposeUiTest {
        val connection = FakeHostConnection(single(EVERY_KIND))
        show(connection)
        onNodeWithTag(nodeTestTag(OUTER)).performTouchInput {
            down(at(50f, 50f))
            up()
        }
        waitForIdle()
        val seen = connection.pointers()
        assertEquals(
            listOf(PointerKind.Enter, PointerKind.Down, PointerKind.Up, PointerKind.Leave),
            seen.map { it.kind },
        )
        seen.forEach { assertEquals(PointerType.Touch, it.pointerType) }
        assertEquals(1, seen[1].button, "a finger is the primary button")
        assertEquals(1, seen[1].buttons)
        assertEquals(0, seen[2].buttons)
    }
}

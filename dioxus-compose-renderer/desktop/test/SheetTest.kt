package dioxus.compose.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dioxus.compose.foundation.sheetScrimTestTag
import dioxus.compose.foundation.sheetTestTag
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag
import dioxus.compose.ui.platform.FrameRequestSource
import dioxus.compose.ui.platform.LocalFrameRequests
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun assertNear(expected: Dp, actual: Dp, what: String) {
    assertTrue(
        abs(expected.value - actual.value) <= 1f,
        "$what should be about ${expected.value}dp but was ${actual.value}dp",
    )
}

private const val SHEET = 1
private const val CONTENT = 2
private const val DISMISS_HANDLER = 55L

private fun sheetBatch(open: Boolean) = listOf(
    Mutation.SetTheme(
        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
    ),
    Mutation.Create(SHEET, WidgetKind.Sheet),
    Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(open)),
    Mutation.SetProp(SHEET, PropertyKind.OnDismiss, PropertyValue.Integer(DISMISS_HANDLER)),
    Mutation.Create(CONTENT, WidgetKind.Text),
    Mutation.SetProp(CONTENT, PropertyKind.Text, PropertyValue.Text("filter by")),
    Mutation.Insert(SHEET, CONTENT, 0),
)

@OptIn(ExperimentalTestApi::class)
class SheetTest {
    private val frames = FrameRequestSource()

    /** A sheet that was never opened draws nothing; the Host property is what opens it. */
    @Test
    fun fr21_a_sheet_shows_only_while_the_host_property_says_it_is_open() = runComposeUiTest {
        val connection = FakeHostConnection(sheetBatch(open = false))
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                DioxusContent(rememberDioxusHost(connection))
            }
        }
        waitForIdle()
        onNodeWithTag(nodeTestTag(CONTENT)).assertDoesNotExist()

        connection.scheduleFrame(
            listOf(Mutation.SetProp(SHEET, PropertyKind.Open, PropertyValue.Bool(true))),
        )
        frames.request()
        waitForIdle()
        onNodeWithTag(nodeTestTag(CONTENT)).assertTextEquals("filter by")
    }

    /**
     * Pulling a sheet shut reports one dismissal, and reports nothing while it is being
     * pulled. How far it has travelled is this side's state: a Host that heard every frame
     * of a drag would be running the VirtualDom for an animation it cannot see.
     */
    @Test
    fun fr21_dragging_a_sheet_shut_reports_one_dismissal_and_nothing_during_the_drag() =
        runComposeUiTest {
            val connection = FakeHostConnection(sheetBatch(open = true))
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    DioxusContent(
                        rememberDioxusHost(connection),
                        Modifier.requiredSize(500.dp, 800.dp),
                    )
                }
            }
            waitForIdle()

            // A pull that stops short of the threshold changes nothing and says nothing.
            onNodeWithTag(sheetTestTag(SHEET)).performTouchInput {
                swipeDown(startY = top + 4f, endY = top + 40f)
            }
            waitForIdle()
            assertEquals(
                0,
                connection.nodeEvents.size,
                "a drag that did not close the sheet must not reach the Host: " +
                    "${connection.nodeEvents}",
            )
            onNodeWithTag(nodeTestTag(CONTENT)).assertTextEquals("filter by")

            // A pull that carries it past the threshold closes it, once.
            onNodeWithTag(sheetTestTag(SHEET)).performTouchInput { swipeDown() }
            waitForIdle()
            val dismissals = connection.events.filterIsInstance<HostEvent.Clicked>()
            assertEquals(1, dismissals.size, "one dismissal, not ${connection.nodeEvents}")
            assertEquals(SHEET, dismissals[0].nodeId)
            assertEquals(DISMISS_HANDLER, dismissals[0].handlerId)
            // And the Renderer closed it without waiting to be told to.
            onNodeWithTag(nodeTestTag(CONTENT)).assertDoesNotExist()
        }

    /**
     * The same records come in from the bottom of a narrow window and from the side of a
     * wide one. The Host has no property that could have asked for either.
     *
     * The measurements are taken against what the sheet covers rather than against the
     * content, because a sheet is its own window and covers all of it.
     */
    @Test
    fun fr21_a_sheet_comes_from_the_bottom_when_narrow_and_from_the_side_when_wide() =
        runComposeUiTest {
            var width by mutableStateOf(500.dp)
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    DioxusContent(
                        rememberDioxusHost(FakeHostConnection(sheetBatch(open = true))),
                        Modifier.requiredSize(width, 800.dp),
                    )
                }
            }
            waitForIdle()
            var covered = onNodeWithTag(sheetScrimTestTag(SHEET)).getBoundsInRoot()
            var sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
            assertNear(covered.right - covered.left, sheet.right - sheet.left, "a bottom sheet's width")
            assertNear((covered.bottom - covered.top) / 2f, sheet.bottom - sheet.top, "its height")
            assertNear(covered.bottom, sheet.bottom, "its bottom edge")

            // Wide enough for a side sheet: the same records, the other edge.
            width = 1100.dp
            waitForIdle()
            covered = onNodeWithTag(sheetScrimTestTag(SHEET)).getBoundsInRoot()
            sheet = onNodeWithTag(sheetTestTag(SHEET)).getBoundsInRoot()
            assertNear(covered.bottom - covered.top, sheet.bottom - sheet.top, "a side sheet's height")
            assertNear(
                (covered.right - covered.left) * 0.4f,
                sheet.right - sheet.left,
                "its width",
            )
            assertNear(covered.right, sheet.right, "its trailing edge")
        }
}

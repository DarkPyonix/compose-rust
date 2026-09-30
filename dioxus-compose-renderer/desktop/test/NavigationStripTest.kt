package dioxus.compose.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import kotlin.test.assertEquals
import dioxus.compose.ui.node.nodeTestTag
import dioxus.compose.protocol.IconRole
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dioxus.compose.design.HostPlatform
import dioxus.compose.ui.platform.FrameRequestSource
import dioxus.compose.ui.platform.LocalFrameRequests
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.SlotRole
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.hostPlatformOverride
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * What a strip carries besides its destinations.
 *
 * The references open with the application's mark and its name, break the destinations
 * into named groups, and close with an account row. Only the destinations were ever said
 * to be the strip's, so the sample was drawing a sidebar with two rows in it.
 */
@OptIn(ExperimentalTestApi::class)
class NavigationStripTest {
    private val frames = FrameRequestSource()

    @AfterTest
    fun clearPlatform() {
        hostPlatformOverride = null
    }

    @Test
    fun fr21_2_2_a_sidebar_draws_its_head_its_groups_and_its_foot() =
        runDesktopComposeUiTest(1200, 800) {
            open(1200.dp, 800.dp)
            onNodeWithText(HEAD_TEXT).assertIsDisplayed()
            onNodeWithText(GROUP).assertIsDisplayed()
            onNodeWithText(FOOT_TEXT).assertIsDisplayed()
        }

    /**
     * One heading per run, not one per destination.
     *
     * Two destinations carrying the same name are one group. Drawing the name over each of
     * them would turn a group of three conversations into three headings with a row under
     * each, which is a list of lists.
     */
    @Test
    fun fr21_2_2_a_group_is_headed_once() = runDesktopComposeUiTest(1200, 800) {
        open(1200.dp, 800.dp)
        // `onNodeWithText` fails when more than one node matches, which is the assertion.
        onNodeWithText(GROUP).assertIsDisplayed()
    }

    /**
     * A bar draws neither.
     *
     * Five destinations standing side by side along the bottom have nowhere to put a
     * wordmark or a heading, and what a narrow window loses is those rather than the
     * destinations.
     */
    @Test
    fun fr21_2_2_a_bar_draws_neither_the_head_nor_the_groups() =
        runDesktopComposeUiTest(420, 800) {
            // A phone, where this language hands its destinations to the platform's own
            // bar rather than putting them away.
            hostPlatformOverride = HostPlatform.Ios
            open(420.dp, 800.dp)
            onNodeWithText(HEAD_TEXT).assertDoesNotExist()
            onNodeWithText(GROUP).assertDoesNotExist()
            onNodeWithText(FOOT_TEXT).assertDoesNotExist()
        }

    /**
     * A search destination is a destination.
     *
     * It was drawn as a bar of its own, filled and cut into a capsule, which put a second
     * filled thing in a list whose one filled thing means "this is where you are". The
     * rule came from a memory of how this platform's sidebars hold their search, and the
     * platform does not hold it there at all.
     *
     * Asserted by drawing two destinations that are both not the one you are on, one of
     * them the search, and reading the same pixel inside each: whatever they mean, two
     * rows in the same state look the same.
     */
    @Test
    fun fr22_a_search_destination_is_drawn_like_any_other() =
        runDesktopComposeUiTest(1200, 800) {
            open(1200.dp, 800.dp)
            val search = onNodeWithTag(nodeTestTag(FIRST)).captureToImage().toPixelMap()
            val plain = onNodeWithTag(nodeTestTag(SECOND)).captureToImage().toPixelMap()
            // Inside the row and clear of its glyph and its word, which is where a fill of
            // its own would show and nothing else does.
            val corner = 2
            assertEquals(
                plain[plain.width - corner, corner],
                search[search.width - corner, corner],
                "the search destination is filled where the destination beside it is not",
            )
        }

    private fun androidx.compose.ui.test.ComposeUiTest.open(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(strip())),
                    Modifier.requiredSize(width, height),
                )
            }
        }
        waitForIdle()
    }

    /** A strip with a head, two destinations in one named group, and a foot. */
    private fun strip(): List<Mutation> = listOf(
        Mutation.SetTheme(
            Theme(DesignSystem.LiquidGlass, DesignSystem.LiquidGlass, ColorScheme.Light, false),
        ),
        Mutation.Create(NAVIGATION, WidgetKind.Navigation),
        Mutation.SetModifier(NAVIGATION, 0, ProtocolModifier.FillMaxWidth),
        Mutation.SetModifier(NAVIGATION, 1, ProtocolModifier.FillMaxHeight),
        // The page, so that neither destination is the one being looked at.
        Mutation.SetProp(NAVIGATION, PropertyKind.SelectedIndex, PropertyValue.Integer(2)),

        Mutation.Create(HEAD, WidgetKind.ScaffoldSlot),
        Mutation.SetProp(HEAD, PropertyKind.Slot, PropertyValue.Integer(SlotRole.TopBar.ordinal + 1L)),
        Mutation.Insert(NAVIGATION, HEAD, 0),
        Mutation.Create(HEAD_LABEL, WidgetKind.Text),
        Mutation.SetProp(HEAD_LABEL, PropertyKind.Text, PropertyValue.Text(HEAD_TEXT)),
        Mutation.Insert(HEAD, HEAD_LABEL, 0),

        Mutation.Create(FIRST, WidgetKind.NavigationItem),
        Mutation.SetProp(FIRST, PropertyKind.Text, PropertyValue.Text("first")),
        // The search, and not the one being looked at: what it used to be drawn as showed
        // only where it was neither.
        Mutation.SetProp(FIRST, PropertyKind.Icon, PropertyValue.Integer(IconRole.Search.ordinal + 1L)),
        Mutation.SetProp(FIRST, PropertyKind.Section, PropertyValue.Text(GROUP)),
        Mutation.Insert(NAVIGATION, FIRST, 1),
        Mutation.Create(SECOND, WidgetKind.NavigationItem),
        Mutation.SetProp(SECOND, PropertyKind.Text, PropertyValue.Text("second")),
        Mutation.SetProp(SECOND, PropertyKind.Section, PropertyValue.Text(GROUP)),
        Mutation.Insert(NAVIGATION, SECOND, 2),

        Mutation.Create(FOOT, WidgetKind.ScaffoldSlot),
        Mutation.SetProp(FOOT, PropertyKind.Slot, PropertyValue.Integer(SlotRole.BottomBar.ordinal + 1L)),
        Mutation.Insert(NAVIGATION, FOOT, 3),
        Mutation.Create(FOOT_LABEL, WidgetKind.Text),
        Mutation.SetProp(FOOT_LABEL, PropertyKind.Text, PropertyValue.Text(FOOT_TEXT)),
        Mutation.Insert(FOOT, FOOT_LABEL, 0),

        Mutation.Create(PAGE, WidgetKind.Text),
        Mutation.SetProp(PAGE, PropertyKind.Text, PropertyValue.Text("the page")),
        Mutation.Insert(NAVIGATION, PAGE, 4),
    )

    private companion object {
        const val NAVIGATION = 1
        const val HEAD = 2
        const val HEAD_LABEL = 3
        const val FIRST = 4
        const val SECOND = 5
        const val FOOT = 6
        const val FOOT_LABEL = 7
        const val PAGE = 8

        const val HEAD_TEXT = "the mark"
        const val FOOT_TEXT = "who is signed in"
        const val GROUP = "Chats"
    }
}

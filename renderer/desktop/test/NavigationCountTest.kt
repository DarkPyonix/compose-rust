package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.NavigationPresentation
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.foundation.BadgeLabel
import dev.darkpyonix.composerust.foundation.badgeMarkTestTag
import dev.darkpyonix.composerust.foundation.navigationCountTestTag
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WindowSizeClass
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.hostPlatformOverride
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.node.RenderNodeObserver
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val NAVIGATION = 1
private const val COUNTED = 2
private const val UNCOUNTED = 3
private const val LARGE = 4
private const val SCREEN = 5
private const val BADGE = 6
private const val BADGED = 7

/** A count every system writes out in full. */
private const val SMALL_COUNT = 4L

/** A count past every system's ceiling, so the shortening is exercised as well. */
private const val LARGE_COUNT = 120L

private fun destination(id: Int, label: String, icon: IconRole, index: Int, count: Long?) = buildList {
    add(Mutation.Create(id, WidgetKind.NavigationItem))
    add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(label)))
    add(Mutation.SetProp(id, PropertyKind.Icon, PropertyValue.Integer(icon.ordinal + 1L)))
    add(Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(id.toLong())))
    if (count != null) add(Mutation.SetProp(id, PropertyKind.Count, PropertyValue.Integer(count)))
    add(Mutation.Insert(NAVIGATION, id, index))
}

/**
 * A navigation whose first destination counts four, whose second names no count, and whose
 * third counts past every ceiling, beside a `Badge` carrying that same large count on the
 * screen they lead to.
 */
private fun counted(system: DesignSystem): List<Mutation> = buildList {
    add(Mutation.SetTheme(Theme(system, system, ColorScheme.Light, adaptive = false)))
    add(Mutation.Create(NAVIGATION, WidgetKind.Navigation))
    add(Mutation.SetProp(NAVIGATION, PropertyKind.SelectedIndex, PropertyValue.Integer(0)))
    addAll(destination(COUNTED, "My Day", IconRole.Home, 0, SMALL_COUNT))
    addAll(destination(UNCOUNTED, "Planned", IconRole.List, 1, null))
    addAll(destination(LARGE, "Inbox", IconRole.Inbox, 2, LARGE_COUNT))
    add(Mutation.Create(SCREEN, WidgetKind.Column))
    add(Mutation.Create(BADGE, WidgetKind.Badge))
    add(Mutation.SetProp(BADGE, PropertyKind.Count, PropertyValue.Integer(LARGE_COUNT)))
    add(Mutation.Create(BADGED, WidgetKind.Text))
    add(Mutation.SetProp(BADGED, PropertyKind.Text, PropertyValue.Text("Inbox")))
    add(Mutation.Insert(BADGE, BADGED, 0))
    add(Mutation.Insert(SCREEN, BADGE, 0))
    add(Mutation.Insert(NAVIGATION, SCREEN, 3))
}

/**
 * How many things a destination holds, drawn where the design system puts a count: at the
 * end of a drawer's row, or as its badge on the icon of a bar or a rail.
 */
@OptIn(ExperimentalTestApi::class)
class NavigationCountTest {
    private val frames = FrameRequestSource()

    @AfterTest
    fun reset() {
        hostPlatformOverride = null
        RenderNodeObserver.onCompose = null
    }

    /**
     * In a drawer a destination's count is the number at the end of its row, written out,
     * and a destination that named no count has nothing there. All seven systems.
     */
    @Test
    fun fr21_2_4_drawer_destination_draws_its_count() {
        DesignSystem.entries.forEach { system ->
            runDesktopComposeUiTest(1200, 800) {
                open(counted(system), 1200.dp, 800.dp)
                onNodeWithTag(navigationCountTestTag(COUNTED), useUnmergedTree = true)
                    .assertTextEquals(SMALL_COUNT.toString())
                onNodeWithTag(navigationCountTestTag(UNCOUNTED), useUnmergedTree = true)
                    .assertDoesNotExist()
                // A drawer has the room to write the large one out as well.
                onNodeWithTag(navigationCountTestTag(LARGE), useUnmergedTree = true)
                    .assertTextEquals(LARGE_COUNT.toString())
            }
        }
    }

    /**
     * In a bar and in a rail the count is a badge on the destination's icon, and what the
     * badge says is what the same system's `Badge` says for the same count, shortened at
     * the same ceiling. A destination that named no count has no badge.
     *
     * A system that draws no rail answers a medium window with a drawer, and a desktop
     * window running the Apple language puts a narrow one's destinations away; both are
     * checked where they draw a bar or a rail, which every system does at one width.
     */
    @Test
    fun fr21_2_4_bar_and_rail_destination_badges_its_count() {
        DesignSystem.entries.forEach { system ->
            var badged = 0
            for ((width, sizeClass) in listOf(420.dp to WindowSizeClass.Compact, 700.dp to WindowSizeClass.Medium)) {
                // A phone, so the Apple systems draw their bar rather than putting it away;
                // no platform bar is installed in a test, so the bar is drawn here.
                hostPlatformOverride = HostPlatform.Ios
                val theme = resolveTheme(
                    Theme(system, system, ColorScheme.Light, adaptive = false),
                    HostPlatform.Ios,
                    systemDark = false,
                    sizeClass = sizeClass,
                )
                val presentation = theme.rules.navigation(sizeClass, theme).presentation
                if (presentation != NavigationPresentation.Bar && presentation != NavigationPresentation.Rail) {
                    continue
                }
                badged++
                runDesktopComposeUiTest(width.value.toInt(), 800) {
                    open(counted(system), width, 800.dp)
                    val badge = markLabel(badgeMarkTestTag(BADGE))
                    assertNotNull(badge, "$system's Badge shows no label for $LARGE_COUNT")
                    assertEquals(
                        badge,
                        markLabel(navigationCountTestTag(LARGE)),
                        "$system's $presentation badges a destination unlike its own Badge",
                    )
                    assertEquals(
                        theme.rules.badge(theme).label(SMALL_COUNT),
                        markLabel(navigationCountTestTag(COUNTED)),
                        "$system's $presentation does not badge a count of $SMALL_COUNT",
                    )
                    onNodeWithTag(navigationCountTestTag(UNCOUNTED), useUnmergedTree = true)
                        .assertDoesNotExist()
                }
            }
            assertTrue(badged > 0, "$system draws neither a bar nor a rail at any width tried")
        }
    }

    /**
     * A new count draws that destination again and leaves the others alone: neither the
     * destinations beside it nor the screen they lead to are composed again.
     */
    @Test
    fun fr21_2_4_a_changed_count_recomposes_only_its_destination() {
        for ((width, label) in listOf(1200.dp to "drawer", 420.dp to "bar")) {
            runDesktopComposeUiTest(width.value.toInt(), 800) {
                val compositions = mutableMapOf<Int, Int>()
                RenderNodeObserver.onCompose = { id -> compositions[id] = (compositions[id] ?: 0) + 1 }
                val connection = FakeHostConnection(counted(DesignSystem.Material3))
                // Only the click answers. The Renderer reports the window's size and more when it
                // starts, and answering those would change the count before the test looks.
                connection.respondWith { event ->
                    if (event is HostEvent.Clicked) {
                        HostResponse(
                            listOf(
                                Mutation.SetProp(COUNTED, PropertyKind.Count, PropertyValue.Integer(SMALL_COUNT + 1)),
                            ),
                        )
                    } else {
                        HostResponse()
                    }
                }
                lateinit var host: ComposeRustHost
                setContent {
                    CompositionLocalProvider(
                        LocalFrameRequests provides frames,
                        LocalDensity provides Density(1f),
                    ) {
                        host = rememberComposeRustHost(connection)
                        ComposeRustContent(host, Modifier.requiredSize(width, 800.dp))
                    }
                }
                waitForIdle()
                val before = compositions.toMap()

                host.dispatch(HostEvent.Clicked(SCREEN, 0))
                waitForIdle()

                assertTrue(
                    (compositions[COUNTED] ?: 0) > (before[COUNTED] ?: 0),
                    "the $label destination whose count changed was not drawn again",
                )
                for (other in listOf(UNCOUNTED, LARGE)) {
                    assertEquals(
                        before[other],
                        compositions[other],
                        "the $label destination $other was composed again for its neighbour's count",
                    )
                }
                assertEquals(before[SCREEN], compositions[SCREEN], "the $label screen was composed again")
                assertEquals(before[NAVIGATION], compositions[NAVIGATION], "the $label navigation was composed again")
            }
        }
    }

    /** What the mark with this tag shows, as the badge writes it, or null for a dot. */
    private fun ComposeUiTest.markLabel(tag: String): String? =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(BadgeLabel)

    private fun ComposeUiTest.open(batch: List<Mutation>, width: Dp, height: Dp) {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(
                    rememberComposeRustHost(FakeHostConnection(batch)),
                    Modifier.requiredSize(width, height),
                )
            }
        }
        waitForIdle()
    }
}

package dioxus.compose.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dioxus.compose.foundation.PlatformNavigationShell
import dioxus.compose.foundation.ShellDestination
import dioxus.compose.foundation.navigationStripTestTag
import dioxus.compose.foundation.platformNavigationShell
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.IconRole
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dioxus.compose.runtime.hostPlatformOverride
import dioxus.compose.design.HostPlatform
import kotlin.test.BeforeTest

private const val NAVIGATION = 1
private const val FIRST = 2
private const val SECOND = 3
private const val SCREEN = 4
private const val SURROUND = 5
private const val FIRST_HANDLER = 91L
private const val SECOND_HANDLER = 92L

/** The same two destinations and one screen the drawn bar is tested with. */
private fun navigationBatch(system: DesignSystem = DesignSystem.Cupertino) = listOf(
    Mutation.SetTheme(Theme(system, system, ColorScheme.Light, adaptive = false)),
    Mutation.Create(NAVIGATION, WidgetKind.Navigation),
    Mutation.SetProp(NAVIGATION, PropertyKind.SelectedIndex, PropertyValue.Integer(0)),
    Mutation.Create(FIRST, WidgetKind.NavigationItem),
    Mutation.SetProp(FIRST, PropertyKind.Text, PropertyValue.Text("Tasks")),
    Mutation.SetProp(FIRST, PropertyKind.Icon, PropertyValue.Integer(IconRole.List.ordinal + 1L)),
    Mutation.SetProp(FIRST, PropertyKind.OnClick, PropertyValue.Integer(FIRST_HANDLER)),
    Mutation.Insert(NAVIGATION, FIRST, 0),
    Mutation.Create(SECOND, WidgetKind.NavigationItem),
    Mutation.SetProp(SECOND, PropertyKind.Text, PropertyValue.Text("Done")),
    Mutation.SetProp(SECOND, PropertyKind.Icon, PropertyValue.Integer(IconRole.Check.ordinal + 1L)),
    Mutation.SetProp(SECOND, PropertyKind.OnClick, PropertyValue.Integer(SECOND_HANDLER)),
    Mutation.Insert(NAVIGATION, SECOND, 1),
    Mutation.Create(SCREEN, WidgetKind.Text),
    Mutation.SetProp(SCREEN, PropertyKind.Text, PropertyValue.Text("the screen")),
    Mutation.Insert(NAVIGATION, SCREEN, 2),
)

/**
 * A shell that records what it was handed instead of putting it on a platform.
 *
 * A fake rather than a mock of the real thing: the interpreter's side of this seam is the
 * whole of what the tests below are about, and a real `UITabBarController` would only say
 * whether UIKit works.
 */
private class RecordingShell(override val drawsStrip: Boolean = true) : PlatformNavigationShell {
    override val stripHeight: Float = 49f
    override val titleHeight: Float = 0f

    var handed: List<ShellDestination> = emptyList()
        private set
    var selected: Int = -1
        private set
    var presentations: Int = 0
        private set
    var dismissals: Int = 0
        private set

    private var choose: ((Int) -> Unit)? = null

    override fun present(
        destinations: List<ShellDestination>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ) {
        handed = destinations
        this.selected = selected
        choose = onSelect
        presentations++
    }

    override fun dismiss() {
        dismissals++
    }

    /** Stands in for the user tapping the platform's own strip. */
    fun tap(index: Int) = choose!!(index)
}

@OptIn(ExperimentalTestApi::class)
class PlatformNavigationShellTest {
    private val frames = FrameRequestSource()

    /**
     * Every test here is about a phone, because the shell is the platform's own strip and
     * only a phone has one. Said out loud rather than inherited from whatever machine the
     * test runs on: the Apple systems answer a narrow window differently on a desktop,
     * where there is no tab bar to hand the destinations to.
     */
    @BeforeTest
    fun sayThisIsAPhone() {
        hostPlatformOverride = HostPlatform.Ios
    }

    @AfterTest
    fun clearShell() {
        platformNavigationShell = null
        hostPlatformOverride = null
    }

    /**
     * The seam is invisible until something fills it.
     *
     * This is the assertion that protects every platform that has no shell, which is all of
     * them but one: desktop, Android, the web and iOS below 26 must come out of this change
     * drawing exactly the bar they drew before it.
     */
    @Test
    fun fr14_9_nothing_changes_while_no_shell_is_installed() = runComposeUiTest {
        assertNull(platformNavigationShell, "no shell may be installed by default")
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(navigationBatch())),
                    Modifier.requiredSize(500.dp, 800.dp),
                )
            }
        }
        waitForIdle()

        onNodeWithTag(navigationStripTestTag(NAVIGATION)).assertExists()
        onNodeWithTag(nodeTestTag(SCREEN)).assertTextEquals("the screen")
    }

    /** With a shell installed the strip is gone from the composition and the screen is not. */
    @Test
    fun fr14_9_an_installed_shell_takes_the_strip_and_leaves_the_screen() = runComposeUiTest {
        val shell = RecordingShell()
        platformNavigationShell = shell
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(navigationBatch())),
                    Modifier.requiredSize(500.dp, 800.dp),
                )
            }
        }
        waitForIdle()

        onNodeWithTag(navigationStripTestTag(NAVIGATION)).assertDoesNotExist()
        onNodeWithTag(nodeTestTag(SCREEN)).assertTextEquals("the screen")

        assertEquals(2, shell.handed.size)
        assertEquals(listOf("Tasks", "Done"), shell.handed.map { it.label })
        assertEquals(listOf(FIRST, SECOND), shell.handed.map { it.nodeId })
        assertEquals(listOf(IconRole.List, IconRole.Check), shell.handed.map { it.icon })
        assertTrue(shell.handed.all { it.enabled })
        assertEquals(0, shell.selected)
    }

    /**
     * Choosing on the platform's strip is that destination's own click, exactly once.
     *
     * The same contract the drawn bar keeps, and the reason this needed no new event tag:
     * what reaches the Host is indistinguishable from a tap on the Compose bar.
     */
    @Test
    fun fr14_9_choosing_on_the_shell_clicks_that_destination_once() = runComposeUiTest {
        val shell = RecordingShell()
        platformNavigationShell = shell
        val connection = FakeHostConnection(navigationBatch())
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                DioxusContent(rememberDioxusHost(connection))
            }
        }
        waitForIdle()

        shell.tap(1)
        waitForIdle()

        val clicks = connection.events.filterIsInstance<HostEvent.Clicked>()
        assertEquals(1, clicks.size, "choosing a destination must cost exactly one event")
        assertEquals(SECOND, clicks[0].nodeId)
        assertEquals(SECOND_HANDLER, clicks[0].handlerId)
        // The Host answered with nothing, and the shell was told the selection moved anyway.
        assertEquals(1, shell.selected)
    }

    /** A selection the Host sends reaches the platform's strip. */
    @Test
    fun fr14_9_a_selection_from_the_host_reaches_the_shell() = runComposeUiTest {
        val shell = RecordingShell()
        platformNavigationShell = shell
        val connection = FakeHostConnection(navigationBatch())
        connection.scheduleFrame(
            listOf(
                Mutation.SetProp(NAVIGATION, PropertyKind.SelectedIndex, PropertyValue.Integer(1)),
            ),
        )
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                DioxusContent(rememberDioxusHost(connection))
            }
        }
        waitForIdle()
        assertEquals(0, shell.selected)

        frames.request()
        waitForIdle()

        assertEquals(1, shell.selected)
    }

    /**
     * A rail and a drawer are not offered.
     *
     * They take their width out of the screen, so a strip drawn outside the Compose surface
     * would sit on top of a screen that is still the whole window wide.
     *
     * The counts are compared rather than fixed, because the window's width is not known
     * until it has been measured: the first composition of a wide window is laid out as a
     * compact one and hands the destinations over, and the measurement takes them back a
     * frame later. What the assertion is about is where they end up.
     */
    @Test
    fun fr14_9_the_shell_is_offered_the_bar_and_not_the_rail_or_the_drawer() = runComposeUiTest {
        val shell = RecordingShell()
        platformNavigationShell = shell
        var width by mutableStateOf(1100.dp)
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(navigationBatch())),
                    Modifier.requiredSize(width, 800.dp),
                )
            }
        }
        waitForIdle()

        onNodeWithTag(navigationStripTestTag(NAVIGATION)).assertExists()
        assertEquals(
            shell.presentations,
            shell.dismissals,
            "a drawer must not be left standing on the shell",
        )

        width = 500.dp
        waitForIdle()

        onNodeWithTag(navigationStripTestTag(NAVIGATION)).assertDoesNotExist()
        assertTrue(shell.presentations > shell.dismissals, "a bar goes to the shell")
    }

    /** A shell that says it will not draw the strip is the same as none at all. */
    @Test
    fun fr14_9_a_shell_that_declines_leaves_the_drawn_bar_alone() = runComposeUiTest {
        val shell = RecordingShell(drawsStrip = false)
        platformNavigationShell = shell
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(navigationBatch())),
                    Modifier.requiredSize(500.dp, 800.dp),
                )
            }
        }
        waitForIdle()

        onNodeWithTag(navigationStripTestTag(NAVIGATION)).assertExists()
        assertEquals(0, shell.presentations)
    }

    /**
     * Tapping again does not take the strip down and put it back up.
     *
     * Handing the destinations over and taking them away are two effects for this reason.
     * One effect keyed on the selection would have torn the platform's strip down on every
     * tap, which is a rebuilt tab bar for what is meant to be a change of one index.
     */
    @Test
    fun fr14_9_moving_the_selection_does_not_take_the_strip_down() = runComposeUiTest {
        val shell = RecordingShell()
        platformNavigationShell = shell
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                DioxusContent(rememberDioxusHost(FakeHostConnection(navigationBatch())))
            }
        }
        waitForIdle()

        shell.tap(1)
        waitForIdle()
        shell.tap(0)
        waitForIdle()

        assertEquals(0, shell.dismissals, "the strip stays up while the selection moves")
    }
    /**
     * A navigation that is not a root of the tree keeps the bar drawn here.
     *
     * The platform's chrome belongs to the window and there is one of it. A navigation
     * nested inside part of the screen would take that one bar away from whatever owns it,
     * and two of them would take turns.
     */
    @Test
    fun fr14_9_a_nested_navigation_keeps_the_drawn_bar() = runComposeUiTest {
        val shell = RecordingShell()
        platformNavigationShell = shell
        val nested = navigationBatch() + listOf(
            Mutation.Create(SURROUND, WidgetKind.Column),
            Mutation.Insert(SURROUND, NAVIGATION, 0),
        )
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(nested)),
                    Modifier.requiredSize(500.dp, 800.dp),
                )
            }
        }
        waitForIdle()

        onNodeWithTag(navigationStripTestTag(NAVIGATION)).assertExists()
        assertEquals(0, shell.presentations, "the window's bar is not a nested widget's")
    }
    /**
     * An application that asked for Material 3 gets Material 3, on iOS as anywhere.
     *
     * The chrome the shell stands up is Apple's, drawn by Apple. Putting it under a screen
     * that asked for another design language would answer a question nobody asked, and it
     * would do it on the one platform where the answer is hardest to argue with.
     */
    @Test
    fun fr14_9_another_design_language_keeps_its_own_bar() = runComposeUiTest {
        val shell = RecordingShell()
        platformNavigationShell = shell
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(navigationBatch(DesignSystem.Material3))),
                    Modifier.requiredSize(500.dp, 800.dp),
                )
            }
        }
        waitForIdle()

        onNodeWithTag(navigationStripTestTag(NAVIGATION)).assertExists()
        assertEquals(0, shell.presentations, "Material 3 does not get Apple's tab bar")
    }

    /** The seventh design system is Apple's too, and it gets the same chrome. */
    @Test
    fun fr14_9_the_liquid_glass_system_is_offered_the_shell() = runComposeUiTest {
        val shell = RecordingShell()
        platformNavigationShell = shell
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                DioxusContent(
                    rememberDioxusHost(
                        FakeHostConnection(navigationBatch(DesignSystem.LiquidGlass)),
                    ),
                    Modifier.requiredSize(500.dp, 800.dp),
                )
            }
        }
        waitForIdle()

        onNodeWithTag(navigationStripTestTag(NAVIGATION)).assertDoesNotExist()
        assertTrue(shell.presentations > 0)
    }
}

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.SystemColorScheme
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.LocalSystemDarkObserver
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The system's light or dark appearance, told to the Host. The test stands in for the
 * system with the observer the desktop installs, holding a value it can flip.
 */
@OptIn(ExperimentalTestApi::class)
class ColorSchemeChangedTest {
    private fun page(scheme: ColorScheme) = FakeHostConnection(
        listOf(
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, scheme, false)),
            Mutation.Create(1, WidgetKind.Column),
            Mutation.Create(2, WidgetKind.Text),
            Mutation.Insert(1, 2, 0),
        ),
    )

    private fun FakeHostConnection.schemes(): List<HostEvent.ColorSchemeChanged> =
        events.filterIsInstance<HostEvent.ColorSchemeChanged>()

    /**
     * Turning the system dark while the application runs reaches the Host as one event,
     * addressed to the Host itself, and turning it light again is one more.
     */
    @Test
    fun fr44_system_scheme_change_reaches_the_host() = runComposeUiTest {
        val connection = page(ColorScheme.FollowSystem)
        var dark by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalSystemDarkObserver provides { dark }) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()
        assertEquals(listOf(SystemColorScheme.Light), connection.schemes().map { it.scheme })

        dark = true
        waitForIdle()
        assertEquals(
            listOf(SystemColorScheme.Light, SystemColorScheme.Dark),
            connection.schemes().map { it.scheme },
            "one event for the change, no restart",
        )
        assertTrue(connection.schemes().all { it.nodeId == 0 && it.handlerId == 0L })

        dark = false
        waitForIdle()
        assertEquals(
            listOf(SystemColorScheme.Light, SystemColorScheme.Dark, SystemColorScheme.Light),
            connection.schemes().map { it.scheme },
        )
    }

    /**
     * The current value goes to the Host before the first frame is drawn, and nothing makes
     * it go again while it stays the same: not a recomposition, not the content leaving
     * and coming back, and not a theme that names its own scheme, which is about the
     * application rather than the system.
     */
    @Test
    fun fr44_scheme_is_reported_at_start_and_never_repeated() = runComposeUiTest {
        val connection = page(ColorScheme.Light)
        var dark by mutableStateOf(true)
        var tick by mutableStateOf(0)
        var shown by mutableStateOf(true)
        var reportedAtFirstDraw: Int? = null
        setContent {
            val host = rememberComposeRustHost(connection)
            Box(
                Modifier.drawBehind {
                    if (reportedAtFirstDraw == null) reportedAtFirstDraw = connection.schemes().size
                },
            ) {
                // Reading the tick inside the observer recomposes the content whenever it moves.
                CompositionLocalProvider(LocalSystemDarkObserver provides { tick; dark }) {
                    if (shown) ComposeRustContent(host)
                }
            }
        }
        waitForIdle()
        assertEquals(1, reportedAtFirstDraw, "the scheme is told before the first frame")
        assertEquals(listOf(SystemColorScheme.Dark), connection.schemes().map { it.scheme })

        repeat(3) {
            tick++
            waitForIdle()
        }
        shown = false
        waitForIdle()
        shown = true
        waitForIdle()
        // The same value set again is not a change.
        dark = true
        waitForIdle()
        assertEquals(
            listOf(SystemColorScheme.Dark),
            connection.schemes().map { it.scheme },
            "the same value went to the Host twice",
        )

        dark = false
        waitForIdle()
        dark = false
        tick++
        waitForIdle()
        assertEquals(
            listOf(SystemColorScheme.Dark, SystemColorScheme.Light),
            connection.schemes().map { it.scheme },
        )
    }
}

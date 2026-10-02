package dioxus.compose.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.LocalSystemDarkObserver
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag
import kotlinx.coroutines.delay
import kotlin.test.Test

/**
 * The observer that follows the system appearance must not outlive the window it belongs to.
 *
 * The desktop one polls in a loop that never finishes. That is right in a window and fatal
 * anywhere a test clock is running: a composition holding a coroutine that is forever
 * waiting on a delay never reports itself idle, so waitForIdle advances frames until the
 * test times out a minute later. While the observer was a process-wide variable, the first
 * composition to install one did that to every composition created after it, in tests that
 * had nothing to do with appearance and no way to know why they hung.
 */
@OptIn(ExperimentalTestApi::class)
class SystemAppearanceTest {

    private val label = 2

    /** Stands in for the desktop observer: the shape that matters is that it never ends. */
    @Composable
    private fun pollingObserver(): Boolean {
        var dark by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000L)
                dark = !dark
            }
        }
        return dark
    }

    private fun batch() = listOf(
        Mutation.Create(1, WidgetKind.Column),
        Mutation.Create(label, WidgetKind.Text),
        Mutation.SetProp(label, PropertyKind.Text, PropertyValue.Text("drawn")),
        Mutation.Insert(label, 1, 0),
    )

    @Test
    fun nfr7_a_polling_appearance_observer_does_not_stop_the_composition_going_idle() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalSystemDarkObserver provides { pollingObserver() }) {
                    DioxusContent(rememberDioxusHost(FakeHostConnection(batch())))
                }
            }
            waitForIdle()
            onNodeWithTag(nodeTestTag(label)).assertTextEquals("drawn")
        }

    /**
     * And the composition next door, which provided nothing, must be unaffected by the one
     * above having run first.
     */
    @Test
    fun nfr7_an_observer_installed_by_one_composition_does_not_reach_another() =
        runComposeUiTest {
            setContent { DioxusContent(rememberDioxusHost(FakeHostConnection(batch()))) }
            waitForIdle()
            onNodeWithTag(nodeTestTag(label)).assertTextEquals("drawn")
        }
}

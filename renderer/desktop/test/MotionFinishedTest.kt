package dev.darkpyonix.composerust.test

import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.design.rulesFor
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.MotionRole
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.motionFinishedListener
import dev.darkpyonix.composerust.ui.node.platformReducedMotion
import dev.darkpyonix.composerust.ui.toComposeModifier
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val COLUMN = 1
private const val PANEL = 2
private const val HANDLER = 41L

/** The length of one frame of the test clock. */
private const val FRAME_MILLIS = 16L

/**
 * A column holding a 50 by 120 dp panel whose size moves under [role]: the motion first,
 * so it animates what the size after it measures.
 */
private fun panel(
    handler: Boolean = true,
    role: MotionRole = MotionRole.Standard,
    system: DesignSystem = DesignSystem.Material3,
): List<Mutation> = buildList {
    add(Mutation.SetTheme(Theme(system, system, ColorScheme.Light, adaptive = false)))
    add(Mutation.Create(COLUMN, WidgetKind.Column))
    add(Mutation.Create(PANEL, WidgetKind.Box))
    add(Mutation.SetModifier(PANEL, 0, ProtocolModifier.Motion(role)))
    add(Mutation.SetModifier(PANEL, 1, ProtocolModifier.Size(50f, 120f)))
    if (handler) add(Mutation.SetProp(PANEL, PropertyKind.OnMotionFinished, PropertyValue.Integer(HANDLER)))
    add(Mutation.Insert(COLUMN, PANEL, 0))
}

private fun height(dp: Float) = Mutation.SetModifier(PANEL, 1, ProtocolModifier.Size(50f, dp))

private class MotionScreen(val connection: FakeHostConnection, val host: ComposeRustHost) {
    val finished: List<HostEvent.MotionFinished>
        get() = connection.events.filterIsInstance<HostEvent.MotionFinished>()

    val errors: List<HostEvent.ProtocolError>
        get() = connection.events.filterIsInstance<HostEvent.ProtocolError>()
}

/** Draws [batch] at one pixel to the dp, with the clock moved only by the test. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.screen(batch: List<Mutation>): MotionScreen {
    mainClock.autoAdvance = false
    val connection = FakeHostConnection(batch)
    lateinit var host: ComposeRustHost
    setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            host = rememberComposeRustHost(connection)
            ComposeRustContent(host)
        }
    }
    waitForIdle()
    return MotionScreen(connection, host)
}

/** Sends one more batch the way a frame does. The clock does not move. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.send(screen: MotionScreen, vararg mutations: Mutation) {
    runOnIdle {
        screen.connection.scheduleFrame(mutations.toList())
        screen.host.renderFrame(0L)
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.frames(count: Int) = repeat(count) { mainClock.advanceTimeByFrame() }

/**
 * Steps the clock a frame at a time until a `MotionFinished` arrives, and says how many
 * frames that took, or null if none came within [limit].
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.framesUntilFinished(screen: MotionScreen, limit: Int): Int? {
    val before = screen.finished.size
    for (frame in 1..limit) {
        mainClock.advanceTimeByFrame()
        if (screen.finished.size > before) return frame
    }
    return null
}

/**
 * A node's size motion tells its handler when it settles: once, with the size it settled
 * at, never while it runs, never for a run a newer size interrupted, and never for the
 * first layout.
 */
@OptIn(ExperimentalTestApi::class)
class MotionFinishedTest {

    @AfterTest
    fun putTheSettingBack() {
        platformReducedMotion = { false }
    }

    /**
     * 120 dp to 0 under `Standard`, in every design system: nothing while it runs, then
     * exactly one report carrying 0, when the system's length for the role has passed.
     * The lengths differ between systems, so the reports do not all arrive together.
     */
    @Test
    fun fr24_motion_finished_reports_once_with_the_final_size() {
        val arrivals = mutableMapOf<DesignSystem, Long>()
        for (system in DesignSystem.entries) {
            runComposeUiTest {
                val screen = screen(panel(system = system))
                frames(5)
                assertEquals(emptyList<HostEvent>(), screen.finished, "$system: reported before anything moved")

                send(screen, height(0f))
                frames(3)
                assertEquals(emptyList<HostEvent>(), screen.finished, "$system: reported while it ran")

                val millis = rulesFor(system).motion.millis(MotionRole.Standard)
                val more = assertNotNull(
                    framesUntilFinished(screen, limit = 200),
                    "$system: the run never reported that it settled",
                )
                val elapsed = (3L + more) * FRAME_MILLIS
                assertTrue(elapsed >= millis, "$system: reported after $elapsed ms of a $millis ms run")
                // The run starts on the frame that lays the change out and is reported on
                // the frame it ends in, so a few frames past its length at most.
                assertTrue(
                    elapsed <= millis + 4 * FRAME_MILLIS,
                    "$system: reported $elapsed ms after a $millis ms run started",
                )
                assertEquals(
                    listOf(HostEvent.MotionFinished(PANEL, HANDLER, 50f, 0f)),
                    screen.finished,
                    system.name,
                )

                // Nothing more once it has settled.
                frames(30)
                assertEquals(1, screen.finished.size, "$system: ${screen.finished}")
                assertEquals(emptyList<HostEvent>(), screen.errors)
                arrivals[system] = elapsed
            }
        }
        assertTrue(arrivals.values.toSet().size > 1, "every system settled on the same frame: $arrivals")
    }

    /**
     * Turned back to 120 dp part way down: the run towards 0 is not reported, and the one
     * report is the size it settled at, 120.
     */
    @Test
    fun fr24_interrupted_motion_reports_only_where_it_settles() = runComposeUiTest {
        val screen = screen(panel())
        frames(5)
        send(screen, height(0f))
        // Four frames into a 300 ms run.
        frames(4)
        assertEquals(emptyList<HostEvent>(), screen.finished)
        send(screen, height(120f))
        frames(120)
        assertEquals(listOf(HostEvent.MotionFinished(PANEL, HANDLER, 50f, 120f)), screen.finished)
        assertEquals(emptyList<HostEvent>(), screen.errors)
    }

    /**
     * Asked to hold still, the platform turns every role into no run at all. The size
     * changes in one frame, and the report comes on that frame, once, so a Host waiting for
     * it to take a child out is not left waiting.
     *
     * The change is applied on the first frame after the batch, where the motion starts;
     * a run of no length ends on the frame after, which is the frame the new size is first
     * drawn at. So the report is in by the second frame.
     */
    @Test
    fun fr24_motion_finished_arrives_under_reduced_motion() = runComposeUiTest {
        platformReducedMotion = { true }
        val screen = screen(panel(role = MotionRole.Slow))
        frames(5)
        assertEquals(emptyList<HostEvent>(), screen.finished)
        send(screen, height(0f))
        assertNotNull(
            framesUntilFinished(screen, limit = 2),
            "no report within two frames of the change: ${screen.connection.nodeEvents}",
        )
        assertEquals(listOf(HostEvent.MotionFinished(PANEL, HANDLER, 50f, 0f)), screen.finished)
        frames(30)
        assertEquals(1, screen.finished.size, "${screen.finished}")
    }

    /**
     * The first layout has nothing to animate, and a batch that leaves the size as it was
     * starts no run, so neither reports.
     */
    @Test
    fun fr24_first_layout_sends_no_motion_finished() = runComposeUiTest {
        val screen = screen(panel())
        frames(60)
        assertEquals(emptyList<HostEvent>(), screen.finished, "the first layout reported")

        send(screen, height(120f))
        frames(60)
        assertEquals(emptyList<HostEvent>(), screen.finished, "a layout at the same size reported")
        assertEquals(emptyList<HostEvent>(), screen.errors)
    }

    /**
     * A node with no handler sends nothing when its size settles, and its chain is the one
     * it had before the report existed: the size motion with no listener.
     */
    @Test
    fun fr24_no_handler_means_no_listener() {
        runComposeUiTest {
            val screen = screen(panel(handler = false))
            frames(5)
            send(screen, height(0f))
            frames(120)
            assertEquals(emptyList<HostEvent>(), screen.finished)
            assertEquals(emptyList<HostEvent>(), screen.errors)
        }

        val theme = resolveTheme(
            Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
            HostPlatform.Unknown,
            systemDark = false,
            highContrast = { false },
        )
        val dispatcher = EventDispatcher { false }
        val motion = listOf<ProtocolModifier>(ProtocolModifier.Motion(MotionRole.Standard))
        val plain = motion.toComposeModifier(PANEL, dispatcher, theme)
        assertEquals(Modifier.animateContentSize(theme.motion<IntSize>(MotionRole.Standard)), plain)

        val listened = motion.toComposeModifier(
            PANEL,
            dispatcher,
            theme,
            motionFinished = motionFinishedListener(PANEL, HANDLER, Density(1f), dispatcher),
        )
        assertNotEquals(plain, listened)
    }

    /** A node removed while its size moves sends nothing, and nothing goes wrong. */
    @Test
    fun fr24_removed_node_sends_no_motion_finished() = runComposeUiTest {
        val screen = screen(panel())
        frames(5)
        send(screen, height(0f))
        frames(3)
        send(screen, Mutation.Remove(PANEL))
        frames(120)
        assertEquals(emptyList<HostEvent>(), screen.finished)
        assertEquals(emptyList<HostEvent>(), screen.errors)
    }
}

package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.AnimatedProperty
import dev.darkpyonix.composerust.protocol.Animation
import dev.darkpyonix.composerust.protocol.AnimationEventKind
import dev.darkpyonix.composerust.protocol.ColorInterpolation
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.FillMode
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Keyframe
import dev.darkpyonix.composerust.protocol.KeyframeValue
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PlayState
import dev.darkpyonix.composerust.protocol.PlaybackDirection
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.ReducedMotion
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.Timing
import dev.darkpyonix.composerust.protocol.TransformFunction
import dev.darkpyonix.composerust.protocol.TransformFunctionKind
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.HostConnection
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.RenderNodeObserver
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.node.platformReducedMotionSetting
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROOT = 1
private const val WHITE = 0xFFFFFFFF.toInt()
private const val RED = 0xFFFF0000.toInt()
private const val CLICK = 55L

/** Counts the Host's frames, which an animation playing on its own must never ask for. */
private class CountingConnection(val inner: FakeHostConnection) : HostConnection by inner {
    var frames = 0

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {
        frames += 1
        inner.renderFrame(frameTimeNanos, onMutation)
    }
}

private fun rotation(degrees: Float) =
    KeyframeValue.Transform(listOf(TransformFunction(TransformFunctionKind.Rotate, degrees, 0f, 0f, 0f, 0f, 0f)))

private fun played(
    node: Int,
    id: Int,
    property: AnimatedProperty,
    from: KeyframeValue,
    to: KeyframeValue,
    events: Int = 0,
    delayMs: Float = 0f,
    durationMs: Float = 1000f,
    playState: PlayState = PlayState.Running,
    iterations: Float = 1f,
) = Mutation.StartAnimation(
    Animation(
        nodeId = node,
        animationId = id,
        property = property,
        slot = 0,
        direction = PlaybackDirection.Normal,
        fill = FillMode.None,
        playState = playState,
        interpolation = if (property == AnimatedProperty.Color || property == AnimatedProperty.Background) {
            ColorInterpolation.SrgbPremultiplied
        } else {
            null
        },
        startTimeNanos = 0L,
        delayMs = delayMs,
        durationMs = durationMs,
        iterations = iterations,
        originX = 0.5f,
        originY = 0.5f,
        events = events,
        keyframes = listOf(Keyframe(0f, Timing.Linear, false, from), Keyframe(1f, Timing.Linear, false, to)),
    ),
)

/**
 * Animations played by the Renderer, drawn: what reaches the screen, what is recomposed
 * and measured while they play, what is hit, and what the Host is asked.
 */
@OptIn(ExperimentalTestApi::class)
class AnimationRendererTest {

    @AfterTest
    fun clear() {
        RenderNodeObserver.onCompose = null
        RenderNodeObserver.onMeasure = null
        platformReducedMotionSetting = { ReducedMotion.Unknown }
    }

    private fun page(build: MutableList<Mutation>.() -> Unit): List<Mutation> = buildList {
        add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false)))
        add(Mutation.Create(ROOT, WidgetKind.AbsoluteBox))
        add(Mutation.SetModifier(ROOT, 0, ProtocolModifier.RequiredSize(300f, 200f)))
        add(Mutation.SetModifier(ROOT, 1, ProtocolModifier.Background(Paint.Literal(WHITE))))
        build()
    }

    private fun MutableList<Mutation>.node(
        id: Int,
        vararg modifiers: ProtocolModifier,
        widget: WidgetKind = WidgetKind.AbsoluteBox,
        parent: Int = ROOT,
    ) {
        add(Mutation.Create(id, widget))
        modifiers.forEachIndexed { index, modifier -> add(Mutation.SetModifier(id, index, modifier)) }
        add(Mutation.Insert(parent, id, -1))
    }

    private fun ComposeUiTest.show(connection: HostConnection) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.frames(count: Int) = repeat(count) { mainClock.advanceTimeByFrame() }

    private fun FakeHostConnection.animationEvents() = events.filterIsInstance<HostEvent.AnimationEvent>()

    /**
     * A second of opacity and a second of rotation over sixty frames: with no events asked
     * for, the Host is neither asked for a frame nor told anything; with the start and the
     * end asked for on one of them, it is told exactly twice.
     */
    @Test
    fun fr41_playing_calls_the_host_only_for_what_was_asked() {
        for (events in listOf(0, 2 or 8)) {
            runComposeUiTest {
                mainClock.autoAdvance = false
                val fake = FakeHostConnection(
                    page {
                        node(2, ProtocolModifier.RequiredSize(40f, 40f), ProtocolModifier.Alpha(1f),
                            ProtocolModifier.Transform(1f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0.5f))
                        add(played(2, 1, AnimatedProperty.Alpha, KeyframeValue.Alpha(0f), KeyframeValue.Alpha(1f), events = events))
                        add(played(2, 2, AnimatedProperty.Transform, rotation(0f), rotation(90f)))
                    },
                )
                val connection = CountingConnection(fake)
                show(connection)
                val framesBefore = connection.frames
                frames(70)
                assertEquals(framesBefore, connection.frames, "events $events: the Host was asked for frames")
                assertEquals(
                    if (events == 0) 0 else 2,
                    fake.animationEvents().size,
                    "events $events: ${fake.animationEvents()}",
                )
                if (events != 0) {
                    assertEquals(
                        listOf(AnimationEventKind.Active, AnimationEventKind.End),
                        fake.animationEvents().map { it.kind },
                    )
                }
            }
        }
    }

    /** What is drawn: a red box at half its opacity over white is pink. */
    @Test
    fun fr41_an_animated_opacity_is_what_is_drawn() = runComposeUiTest {
        mainClock.autoAdvance = false
        show(
            FakeHostConnection(
                page {
                    node(2, ProtocolModifier.RequiredSize(40f, 40f), ProtocolModifier.Alpha(0.2f),
                        ProtocolModifier.Background(Paint.Literal(RED)))
                    // Paused half way through, so the value is exact whatever the clock does.
                    add(played(2, 1, AnimatedProperty.Alpha, KeyframeValue.Alpha(0f), KeyframeValue.Alpha(1f),
                        delayMs = -500f, playState = PlayState.Paused))
                },
            ),
        )
        frames(3)
        val pixel = onNodeWithTag(nodeTestTag(ROOT)).captureToImage().toPixelMap()[20, 20]
        assertTrue(abs(pixel.green - 0.5f) < 0.02f, "red at half opacity over white, found $pixel")
    }

    /**
     * Nothing animated is recomposed or measured while it plays: not an opacity, not a
     * background, not a transform and not a text colour.
     */
    @Test
    fun fr41_playing_recomposes_and_measures_nothing() = runComposeUiTest {
        mainClock.autoAdvance = false
        val compositions = mutableMapOf<Int, Int>()
        val measures = mutableMapOf<Int, Int>()
        RenderNodeObserver.onCompose = { id -> compositions[id] = (compositions[id] ?: 0) + 1 }
        RenderNodeObserver.onMeasure = { id -> measures[id] = (measures[id] ?: 0) + 1 }
        show(
            FakeHostConnection(
                page {
                    node(2, ProtocolModifier.RequiredSize(40f, 40f), ProtocolModifier.Alpha(1f))
                    node(3, ProtocolModifier.RequiredSize(40f, 40f), ProtocolModifier.Background(Paint.Literal(RED)))
                    node(4, ProtocolModifier.RequiredSize(40f, 40f), ProtocolModifier.Transform(1f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0.5f))
                    node(5, widget = WidgetKind.Text)
                    add(Mutation.SetProp(5, PropertyKind.Text, PropertyValue.Text("colour")))
                    add(played(2, 1, AnimatedProperty.Alpha, KeyframeValue.Alpha(0f), KeyframeValue.Alpha(1f)))
                    add(played(3, 2, AnimatedProperty.Background, KeyframeValue.PaintValue(Paint.Literal(RED)),
                        KeyframeValue.PaintValue(Paint.Literal(WHITE))))
                    add(played(4, 3, AnimatedProperty.Transform, rotation(0f), rotation(180f)))
                    add(played(5, 4, AnimatedProperty.Color, KeyframeValue.PaintValue(Paint.Literal(RED)),
                        KeyframeValue.PaintValue(Paint.Literal(0xFF0000FF.toInt()))))
                },
            ),
        )
        frames(2)
        val composed = compositions.toMap()
        val measured = measures.toMap()
        frames(30)
        for (id in 2..5) {
            assertEquals(composed[id], compositions[id], "node $id was recomposed while it played")
        }
        for (id in 2..4) {
            assertEquals(measured[id], measures[id], "node $id was measured while it played")
        }
    }

    /**
     * A pointer is tested against the shape a transform animation shows at that moment: a
     * box held at 45 degrees part way through a turn is hit where the turned box is.
     */
    @Test
    fun fr41_hit_testing_follows_an_animated_transform() = runComposeUiTest {
        mainClock.autoAdvance = false
        val connection = FakeHostConnection(
            page {
                node(
                    2,
                    ProtocolModifier.Offset(50f, 50f),
                    ProtocolModifier.RequiredSize(100f, 40f),
                    ProtocolModifier.Transform(1f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0.5f),
                    ProtocolModifier.Clickable(CLICK),
                )
                add(played(2, 1, AnimatedProperty.Transform, rotation(0f), rotation(90f),
                    delayMs = -500f, playState = PlayState.Paused))
            },
        )
        show(connection)
        frames(3)
        fun clickAt(x: Float, y: Float) {
            onNodeWithTag(nodeTestTag(ROOT)).performMouseInput {
                moveTo(Offset(x, y))
                press()
                release()
            }
            frames(1)
        }
        fun clicks() = connection.events.filterIsInstance<HostEvent.Clicked>().count { it.handlerId == CLICK }
        clickAt(53f, 53f)
        assertEquals(0, clicks(), "near the corner the turned box has left")
        clickAt(100f, 70f)
        assertEquals(1, clicks(), "the centre")
        clickAt(134f, 104f)
        assertEquals(2, clicks(), "outside the original rectangle, inside the turned one")
    }

    /**
     * The motion setting is told once after start, and an animation is still played as it
     * was described when it asks for less motion: what to reduce is the Host's decision.
     */
    @Test
    fun fr41_reduced_motion_is_reported_and_animations_still_play() = runComposeUiTest {
        mainClock.autoAdvance = false
        platformReducedMotionSetting = { ReducedMotion.On }
        val connection = FakeHostConnection(
            page {
                node(2, ProtocolModifier.RequiredSize(40f, 40f), ProtocolModifier.Alpha(0.2f),
                    ProtocolModifier.Background(Paint.Literal(RED)))
                add(played(2, 1, AnimatedProperty.Alpha, KeyframeValue.Alpha(0f), KeyframeValue.Alpha(1f),
                    delayMs = -500f, playState = PlayState.Paused))
            },
        )
        show(connection)
        frames(5)
        assertEquals(
            listOf(ReducedMotion.On),
            connection.events.filterIsInstance<HostEvent.ReducedMotionChanged>().map { it.state },
        )
        val pixel = onNodeWithTag(nodeTestTag(ROOT)).captureToImage().toPixelMap()[20, 20]
        assertTrue(abs(pixel.green - 0.5f) < 0.02f, "still played, half way, found $pixel")
    }
}

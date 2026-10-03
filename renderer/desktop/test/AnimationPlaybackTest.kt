package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.Color
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.protocol.AnimatedProperty
import dev.darkpyonix.composerust.protocol.Animation
import dev.darkpyonix.composerust.protocol.AnimationControl
import dev.darkpyonix.composerust.protocol.AnimationEventKind
import dev.darkpyonix.composerust.protocol.ColorInterpolation
import dev.darkpyonix.composerust.protocol.ColorRole
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
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.Timing
import dev.darkpyonix.composerust.protocol.TransformFunction
import dev.darkpyonix.composerust.protocol.TransformFunctionKind
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where every animation in these tests starts on the frame clock: one second in. */
private const val START = 1_000_000_000L
private const val NODE = 1

private fun ms(value: Double): Long = START + (value * 1_000_000).toLong()

private fun alpha(value: Float) = KeyframeValue.Alpha(value)
private fun paint(argb: Long) = KeyframeValue.PaintValue(Paint.Literal(argb.toInt()))
private fun rotate(degrees: Float) =
    KeyframeValue.Transform(listOf(TransformFunction(TransformFunctionKind.Rotate, degrees, 0f, 0f, 0f, 0f, 0f)))

private fun animation(
    property: AnimatedProperty,
    from: KeyframeValue,
    to: KeyframeValue,
    id: Int = 1,
    slot: Int = 0,
    delayMs: Float = 0f,
    durationMs: Float = 1000f,
    iterations: Float = 1f,
    direction: PlaybackDirection = PlaybackDirection.Normal,
    fill: FillMode = FillMode.None,
    events: Int = 0,
    start: Long = START,
    fromPresented: Boolean = false,
    playState: PlayState = PlayState.Running,
) = Animation(
    nodeId = NODE,
    animationId = id,
    property = property,
    slot = slot,
    direction = direction,
    fill = fill,
    playState = playState,
    interpolation = if (property == AnimatedProperty.Color || property == AnimatedProperty.Background) {
        ColorInterpolation.SrgbPremultiplied
    } else {
        null
    },
    startTimeNanos = start,
    delayMs = delayMs,
    durationMs = durationMs,
    iterations = iterations,
    originX = 0.5f,
    originY = 0.5f,
    events = events,
    keyframes = listOf(Keyframe(0f, Timing.Linear, fromPresented, from), Keyframe(1f, Timing.Linear, false, to)),
)

/**
 * The animation table on its own: what each animation writes for the node to draw at a
 * given frame time, and which events it hands the Host, without a window. The tests that
 * need a window, for what is actually drawn and for what is recomposed, are in
 * `AnimationRendererTest`.
 */
class AnimationPlaybackTest {

    private val light: ResolvedTheme = resolveTheme(
        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
        HostPlatform.Unknown,
        systemDark = false,
        highContrast = { false },
    )
    private val dark: ResolvedTheme = resolveTheme(
        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Dark, adaptive = false),
        HostPlatform.Unknown,
        systemDark = true,
        highContrast = { false },
    )

    private val table = NodeTable()
    private val events = mutableListOf<HostEvent>()

    private fun node(widget: WidgetKind = WidgetKind.Box, vararg modifiers: ProtocolModifier) {
        table.apply(Mutation.Create(NODE, widget))
        modifiers.forEachIndexed { index, modifier -> table.apply(Mutation.SetModifier(NODE, index, modifier)) }
        assertEquals(emptyList(), table.drainErrors())
    }

    private fun start(animation: Animation): List<TableError> {
        table.apply(Mutation.StartAnimation(animation))
        return table.drainErrors()
    }

    /** Made once, so handing it over each frame allocates nothing. */
    private val collect: (HostEvent) -> Unit = { events += it }

    private fun frame(atMs: Double, theme: ResolvedTheme = light) =
        table.animations.tick(ms(atMs), theme, collect)

    private val shown get() = table.animations.animated(NODE)

    private fun alphaAt(atMs: Double): Float {
        frame(atMs)
        return shown.alpha.floatValue
    }

    private fun assertClose(expected: Float, actual: Float, what: String, tolerance: Float = 1e-3f) {
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, was $actual")
    }

    /**
     * Opacity over 1000 ms after a 200 ms delay, from 0 to 1, over an underlying 0.2: the
     * underlying value shows before and after, and the fills hold the ends.
     */
    @Test
    fun fr41_alpha_follows_the_timing_model_and_its_fill() {
        node(WidgetKind.Box, ProtocolModifier.Alpha(0.2f))
        assertEquals(emptyList(), start(animation(AnimatedProperty.Alpha, alpha(0f), alpha(1f), delayMs = 200f)))
        assertTrue(alphaAt(100.0).isNaN(), "before the delay ends the underlying 0.2 shows")
        assertClose(0.5f, alphaAt(700.0), "half way")
        assertTrue(alphaAt(1300.0).isNaN(), "after the end the underlying 0.2 shows again")

        start(animation(AnimatedProperty.Alpha, alpha(0f), alpha(1f), delayMs = 200f, fill = FillMode.Backwards))
        assertClose(0f, alphaAt(100.0), "a backwards fill holds the first keyframe")
        start(animation(AnimatedProperty.Alpha, alpha(0f), alpha(1f), delayMs = 200f, fill = FillMode.Forwards))
        assertClose(1f, alphaAt(1300.0), "a forwards fill holds the last keyframe")
    }

    /** A background between opaque red and half-transparent blue, mixed premultiplied. */
    @Test
    fun fr41_background_mixes_premultiplied_srgb() {
        node(WidgetKind.Box, ProtocolModifier.Background(Paint.Literal(0)))
        start(animation(AnimatedProperty.Background, paint(0xFFFF0000), paint(0x800000FF)))
        frame(500.0)
        val mid = shown.backgroundOverride()!!
        // Alpha 0.75; red (1 x 1 x 0.5) / 0.75, blue (1 x 0.5 x 0.5) / 0.75.
        assertClose(0.75f, mid.alpha, "alpha", 0.01f)
        assertClose(0.667f, mid.red, "red", 0.01f)
        assertClose(0.333f, mid.blue, "blue", 0.01f)
    }

    /** A Text's colour, black to white, is grey half way. */
    @Test
    fun fr41_text_colour_is_mixed_and_drawn_through_its_producer() {
        node(WidgetKind.Text)
        start(animation(AnimatedProperty.Color, paint(0xFF000000), paint(0xFFFFFFFF)))
        frame(500.0)
        val mid = shown.textColorProducer()
        assertClose(0.5f, mid.red, "red", 0.01f)
        assertClose(0.5f, mid.green, "green", 0.01f)
    }

    /**
     * rotate(0) to rotate(720deg) over a second: a quarter of the way is half a turn, and
     * half way is one whole turn, back where it started, rather than nothing having moved.
     */
    @Test
    fun fr41_a_rotation_is_interpolated_as_a_function_not_a_matrix() {
        node(WidgetKind.Box, ProtocolModifier.Transform(1f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0.5f))
        start(animation(AnimatedProperty.Transform, rotate(0f), rotate(720f)))
        frame(250.0)
        assertClose(-1f, shown.matrix[0], "a quarter of the way is 180 degrees")
        frame(125.0 + 500.0)
        // 450 degrees: a quarter turn past one whole turn.
        assertClose(0f, shown.matrix[0], "450 degrees, cos")
        assertClose(1f, shown.matrix[1], "450 degrees, sin")
        frame(500.0)
        assertClose(1f, shown.matrix[0], "one whole turn")
    }

    /** Two matrices are interpolated by decomposition: half way from 0 to 90 degrees is 45. */
    @Test
    fun fr41_matrices_are_interpolated_by_decomposition() {
        node(WidgetKind.Box, ProtocolModifier.Transform(1f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0.5f))
        fun matrix(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) =
            KeyframeValue.Transform(listOf(TransformFunction(TransformFunctionKind.Matrix, a, b, c, d, e, f)))
        start(animation(AnimatedProperty.Transform, matrix(1f, 0f, 0f, 1f, 0f, 0f), matrix(0f, 1f, -1f, 0f, 20f, 0f)))
        frame(500.0)
        val half = (sqrt(2.0) / 2).toFloat()
        assertClose(half, shown.matrix[0], "a")
        assertClose(half, shown.matrix[1], "b")
        assertClose(-half, shown.matrix[2], "c")
        assertClose(10f, shown.matrix[4], "e", 0.01f)
    }

    /**
     * An infinite alternate animation reports each new iteration once, and once when a
     * long frame crosses several boundaries. Three iterations report one end, with the
     * whole active duration as elapsed time. Nothing it did not ask for is reported.
     */
    @Test
    fun fr41_iteration_and_end_events_come_once_each() {
        node(WidgetKind.Box, ProtocolModifier.Alpha(1f))
        start(
            animation(
                AnimatedProperty.Alpha, alpha(0f), alpha(1f),
                durationMs = 100f, iterations = Float.POSITIVE_INFINITY,
                direction = PlaybackDirection.Alternate, events = 4,
            ),
        )
        frame(0.0)
        frame(50.0)
        frame(150.0)
        frame(160.0)
        assertEquals(1, events.size, "one boundary crossed, one event: $events")
        frame(560.0)
        assertEquals(2, events.size, "four boundaries crossed in one frame, one event: $events")
        assertTrue(events.all { (it as HostEvent.AnimationEvent).kind == AnimationEventKind.Iteration })

        events.clear()
        start(animation(AnimatedProperty.Alpha, alpha(0f), alpha(1f), id = 2, durationMs = 100f, iterations = 3f, events = 8))
        for (time in 0..400 step 16) frame(time.toDouble())
        val ends = events.filterIsInstance<HostEvent.AnimationEvent>()
        assertEquals(1, ends.size, "one end: $events")
        assertEquals(AnimationEventKind.End, ends.single().kind)
        assertEquals(300f, ends.single().elapsedMs, "the elapsed time is the active duration")
    }

    /**
     * A new underlying value while an animation plays changes nothing on screen, and shows
     * once it ends. A forwards fill keeps showing its end over a new underlying value until
     * it is cancelled.
     */
    @Test
    fun fr41_the_underlying_value_shows_only_when_no_animation_does() {
        node(WidgetKind.Box, ProtocolModifier.Alpha(0.2f))
        start(animation(AnimatedProperty.Alpha, alpha(0f), alpha(1f)))
        frame(500.0)
        table.apply(Mutation.SetModifier(NODE, 0, ProtocolModifier.Alpha(0.9f)))
        assertEquals(emptyList(), table.drainErrors())
        assertClose(0.75f, alphaAt(750.0), "still the animation")
        assertTrue(alphaAt(1100.0).isNaN(), "after it the new underlying value shows")

        start(animation(AnimatedProperty.Alpha, alpha(0f), alpha(1f), id = 3, fill = FillMode.Forwards))
        frame(1100.0)
        table.apply(Mutation.SetModifier(NODE, 0, ProtocolModifier.Alpha(0.4f)))
        table.drainErrors()
        assertClose(1f, alphaAt(1200.0), "the fill holds over the new underlying value")
        table.apply(Mutation.ControlAnimation(NODE, 3, AnimatedProperty.Alpha, 0, AnimationControl.Cancel, 0L))
        assertTrue(alphaAt(1300.0).isNaN(), "cancelled, the underlying value shows")
    }

    /**
     * A transition that changes course midway starts from what is on screen: its first
     * frame is within one frame's change of the frame before.
     */
    @Test
    fun fr41_from_presented_does_not_jump() {
        node(WidgetKind.Box, ProtocolModifier.Alpha(0f))
        start(animation(AnimatedProperty.Alpha, alpha(0f), alpha(1f)))
        val before = alphaAt(500.0)
        start(animation(AnimatedProperty.Alpha, alpha(0f), alpha(0f), id = 2, start = 0L, fromPresented = true))
        val after = alphaAt(516.0)
        assertTrue(abs(after - before) <= 0.016f + 1e-3f, "jumped from $before to $after")
    }

    /**
     * A higher slot covers a lower one while it is in effect, and the lower one shows from
     * the frame after the higher one ends.
     */
    @Test
    fun fr41_the_highest_slot_in_effect_shows() {
        node(WidgetKind.Box, ProtocolModifier.Alpha(1f))
        start(animation(AnimatedProperty.Alpha, alpha(0f), alpha(1f), durationMs = 2000f))
        start(animation(AnimatedProperty.Alpha, alpha(0.9f), alpha(0.9f), id = 2, slot = 1, durationMs = 500f))
        assertClose(0.9f, alphaAt(250.0), "slot 1 covers slot 0")
        assertClose(0.3f, alphaAt(600.0), "slot 0 once slot 1 has ended")
    }

    /**
     * A paused animation holds still and ends that much later once resumed. A removed
     * node's animations go without an event, and a control for a key nothing plays under
     * does nothing.
     */
    @Test
    fun fr41_pause_resume_remove_and_unknown_controls() {
        node(WidgetKind.Box, ProtocolModifier.Alpha(1f))
        start(animation(AnimatedProperty.Alpha, alpha(0f), alpha(1f), events = 8))
        frame(300.0)
        table.apply(Mutation.ControlAnimation(NODE, 1, AnimatedProperty.Alpha, 0, AnimationControl.Pause, ms(300.0)))
        assertClose(0.3f, alphaAt(800.0), "paused at 300 ms")
        table.apply(Mutation.ControlAnimation(NODE, 1, AnimatedProperty.Alpha, 0, AnimationControl.Resume, ms(800.0)))
        assertClose(0.4f, alphaAt(900.0), "resumed 500 ms later")
        frame(1300.0)
        assertEquals(0, events.size, "not ended at its original end")
        frame(1500.0)
        assertEquals(1, events.size, "ended 500 ms late")

        events.clear()
        start(animation(AnimatedProperty.Alpha, alpha(0f), alpha(1f), id = 4, events = 15))
        table.apply(Mutation.ControlAnimation(NODE, 99, AnimatedProperty.Alpha, 0, AnimationControl.Cancel, 0L))
        table.apply(Mutation.ControlAnimation(NODE, 4, AnimatedProperty.Alpha, 7, AnimationControl.Cancel, 0L))
        table.apply(Mutation.Remove(NODE))
        table.drainErrors()
        frame(1600.0)
        assertEquals(0, events.size, "a removed node's animation reports nothing")
        assertTrue(!table.animations.needsFrames(), "and is no longer played")
    }

    /**
     * An animation with nowhere to be drawn is a protocol error once the batch is in, but
     * not before: the record that gives it somewhere may come later in the same batch.
     */
    @Test
    fun fr41_an_animation_needs_its_underlying_modifier_after_the_batch() {
        table.apply(Mutation.Create(NODE, WidgetKind.Box))
        table.apply(Mutation.StartAnimation(animation(AnimatedProperty.Alpha, alpha(0f), alpha(1f))))
        table.apply(Mutation.SetModifier(NODE, 0, ProtocolModifier.Alpha(1f)))
        assertEquals(emptyList(), table.drainErrors(), "the Alpha arrived in the same batch")

        table.apply(Mutation.StartAnimation(animation(AnimatedProperty.Background, paint(0xFF000000), paint(0xFFFFFFFF))))
        assertEquals(listOf(TableError.INVALID_ANIMATION), table.drainErrors().map { it.code }, "no Background")

        table.apply(Mutation.Create(2, WidgetKind.Box))
        table.apply(Mutation.StartAnimation(animation(AnimatedProperty.Color, paint(0xFF000000), paint(0xFFFFFFFF)).copy(nodeId = 2)))
        assertEquals(listOf(TableError.INVALID_ANIMATION), table.drainErrors().map { it.code }, "a Box has no colour")
    }

    /** A role in a keyframe is resolved against the theme every frame. */
    @Test
    fun fr41_a_role_follows_the_theme_while_playing() {
        node(WidgetKind.Box, ProtocolModifier.Background(Paint.Literal(0)))
        start(
            animation(
                AnimatedProperty.Background,
                KeyframeValue.PaintValue(Paint.Role(ColorRole.Primary)),
                KeyframeValue.PaintValue(Paint.Role(ColorRole.Primary)),
            ),
        )
        frame(400.0, light)
        val inLight = shown.backgroundOverride()
        frame(416.0, dark)
        val inDark = shown.backgroundOverride()
        assertEquals(light.color(ColorRole.Primary).toArgbInt(), inLight?.toArgbInt())
        assertEquals(dark.color(ColorRole.Primary).toArgbInt(), inDark?.toArgbInt())
    }

    /**
     * A hundred animations playing allocate nothing per frame, and once they have all
     * ended no more frames are asked for.
     */
    @Test
    fun fr41_a_hundred_animations_allocate_nothing_per_frame() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        for (id in 1..100) {
            table.apply(Mutation.Create(id, WidgetKind.Box))
            table.apply(Mutation.SetModifier(id, 0, ProtocolModifier.Alpha(1f)))
            table.apply(Mutation.SetModifier(id, 1, ProtocolModifier.Transform(1f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0.5f)))
            val property = if (id % 2 == 0) AnimatedProperty.Alpha else AnimatedProperty.Transform
            val (from, to) = if (property == AnimatedProperty.Alpha) alpha(0f) to alpha(1f) else rotate(0f) to rotate(360f)
            table.apply(Mutation.StartAnimation(animation(property, from, to, id = id, durationMs = 100_000f).copy(nodeId = id)))
        }
        assertEquals(emptyList(), table.drainErrors())
        var time = 0.0
        repeat(200) { frame(time); time += 16.0 }
        val before = threads.currentThreadAllocatedBytes
        repeat(1000) { frame(time); time += 16.0 }
        val perFrame = (threads.currentThreadAllocatedBytes - before) / 1000
        assertEquals(0L, perFrame, "bytes allocated per frame with a hundred animations playing")

        val ending = NodeTable()
        ending.apply(Mutation.Create(NODE, WidgetKind.Box))
        ending.apply(Mutation.SetModifier(NODE, 0, ProtocolModifier.Alpha(1f)))
        ending.apply(Mutation.StartAnimation(animation(AnimatedProperty.Alpha, alpha(0f), alpha(1f), durationMs = 100f)))
        ending.drainErrors()
        ending.animations.tick(ms(0.0), light, collect)
        assertTrue(ending.animations.needsFrames())
        ending.animations.tick(ms(200.0), light, collect)
        assertTrue(!ending.animations.needsFrames(), "an ended animation asks for no more frames")
    }

    /**
     * A thousand nodes turning at once: the Renderer's work for one frame of them, measured
     * and printed, and held well inside the 16.7 ms a 60 Hz frame has, so the drawing those
     * values feed still has most of the frame.
     */
    @Test
    fun fr41_a_thousand_transform_animations_fit_in_a_frame() {
        for (id in 1..1000) {
            table.apply(Mutation.Create(id, WidgetKind.Box))
            table.apply(Mutation.SetModifier(id, 0, ProtocolModifier.Transform(1f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0.5f)))
            table.apply(
                Mutation.StartAnimation(
                    animation(AnimatedProperty.Transform, rotate(0f), rotate(360f), id = id, durationMs = 100_000f)
                        .copy(nodeId = id),
                ),
            )
        }
        assertEquals(emptyList(), table.drainErrors())
        var time = 0.0
        repeat(100) { frame(time); time += 16.0 }
        val started = System.nanoTime()
        val frames = 200
        repeat(frames) { frame(time); time += 16.0 }
        val perFrameMs = (System.nanoTime() - started) / 1_000_000.0 / frames
        println("a thousand transform animations: ${"%.3f".format(perFrameMs)} ms of Renderer work per frame")
        assertTrue(perFrameMs < 8.0, "$perFrameMs ms per frame")
    }

    private fun Color.toArgbInt(): Int =
        ((alpha * 255 + 0.5f).toInt() shl 24) or ((red * 255 + 0.5f).toInt() shl 16) or
            ((green * 255 + 0.5f).toInt() shl 8) or (blue * 255 + 0.5f).toInt()
}

package dev.darkpyonix.composerust.ui.node

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.protocol.AnimatedProperty
import dev.darkpyonix.composerust.protocol.Animation
import dev.darkpyonix.composerust.protocol.AnimationControl
import dev.darkpyonix.composerust.protocol.AnimationEventKind
import dev.darkpyonix.composerust.protocol.ColorInterpolation
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
import dev.darkpyonix.composerust.protocol.StepPosition
import dev.darkpyonix.composerust.protocol.Timing
import dev.darkpyonix.composerust.protocol.TransformFunction
import dev.darkpyonix.composerust.protocol.TransformFunctionKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The phase an animation is in at one moment, as Web Animations Level 1 names them.
 */
internal enum class AnimationPhase { Before, Active, After }

/**
 * Where an animation is at one moment: the CSS timing model, run once.
 *
 * One mutable instance is filled per question, so playing a hundred animations allocates
 * nothing per frame.
 */
internal class TimeSample {
    var phase = AnimationPhase.Before

    /** Whether the animation has a value at all: it is active, or a fill holds it. */
    var inEffect = false

    /** The directed iteration progress, from 0 to 1, when [inEffect]. */
    var progress = 0f

    /** The current iteration, counting from zero. Infinite past the end of an infinite run. */
    var iteration = 0.0

    /** Active time in ms, or NaN where it is unresolved. */
    var activeTime = 0.0

    /** Set where `steps()` has to take the step before the boundary rather than after. */
    var beforeFlag = false
}

/**
 * The Web Animations Level 1 timing model for one animation effect with a playback rate of
 * one, no end delay and an iteration start of zero, which is everything a CSS transition
 * or animation is.
 *
 * A pure function of the record's timing and a local time, so the Host, which knows both,
 * can compute the same value at any moment without being told.
 */
internal object WebAnimationTiming {

    /** The active duration: `duration × iterations`, where zero times infinity is zero. */
    fun activeDuration(durationMs: Double, iterations: Double): Double =
        if (durationMs == 0.0 || iterations == 0.0) 0.0 else durationMs * iterations

    fun sample(
        localTimeMs: Double,
        delayMs: Double,
        durationMs: Double,
        iterations: Double,
        direction: PlaybackDirection,
        fill: FillMode,
        into: TimeSample,
    ) {
        val active = activeDuration(durationMs, iterations)
        val endTime = max(delayMs + active, 0.0)
        val beforeActive = max(min(delayMs, endTime), 0.0)
        val activeAfter = max(min(delayMs + active, endTime), 0.0)
        val phase = when {
            localTimeMs < beforeActive -> AnimationPhase.Before
            localTimeMs >= activeAfter -> AnimationPhase.After
            else -> AnimationPhase.Active
        }
        into.phase = phase
        val backwards = fill == FillMode.Backwards || fill == FillMode.Both
        val forwards = fill == FillMode.Forwards || fill == FillMode.Both
        val activeTime = when (phase) {
            AnimationPhase.Before -> if (backwards) max(localTimeMs - delayMs, 0.0) else Double.NaN
            AnimationPhase.Active -> localTimeMs - delayMs
            AnimationPhase.After -> if (forwards) max(min(localTimeMs - delayMs, active), 0.0) else Double.NaN
        }
        into.activeTime = activeTime
        if (activeTime.isNaN()) {
            into.inEffect = false
            return
        }
        into.inEffect = true
        val overall = if (durationMs == 0.0) {
            if (phase == AnimationPhase.Before) 0.0 else iterations
        } else {
            activeTime / durationMs
        }
        var simple = if (overall.isInfinite()) 0.0 else overall - floor(overall)
        if (simple == 0.0 && phase != AnimationPhase.Before && activeTime == active && iterations != 0.0) {
            simple = 1.0
        }
        val current = when {
            phase == AnimationPhase.After && iterations.isInfinite() -> Double.POSITIVE_INFINITY
            simple == 1.0 -> floor(overall) - 1.0
            else -> floor(overall)
        }
        into.iteration = current
        val forwardsDirection = when (direction) {
            PlaybackDirection.Normal -> true
            PlaybackDirection.Reverse -> false
            PlaybackDirection.Alternate -> current.isInfinite() || current % 2.0 == 0.0
            PlaybackDirection.AlternateReverse -> !(current.isInfinite() || current % 2.0 == 0.0)
        }
        into.progress = (if (forwardsDirection) simple else 1.0 - simple).toFloat()
        into.beforeFlag = (phase == AnimationPhase.Before && forwardsDirection) ||
            (phase == AnimationPhase.After && !forwardsDirection)
    }

    /** CSS Easing 1's step function, with the before flag. */
    fun steps(input: Float, count: Int, position: StepPosition, beforeFlag: Boolean): Float {
        var step = floor(input.toDouble() * count)
        if (position == StepPosition.JumpStart || position == StepPosition.JumpBoth) step += 1.0
        if (beforeFlag && (input.toDouble() * count) % 1.0 == 0.0) step -= 1.0
        if (input >= 0f && step < 0.0) step = 0.0
        val jumps = when (position) {
            StepPosition.JumpBoth -> count + 1
            StepPosition.JumpNone -> count - 1
            else -> count
        }
        if (input <= 1f && step > jumps) step = jumps.toDouble()
        return (step / jumps).toFloat()
    }

    /** One keyframe's timing function applied to the progress through its segment. */
    fun ease(timing: Timing, input: Float, beforeFlag: Boolean): Float = when (timing) {
        Timing.Linear -> input
        is Timing.CubicBezier -> when {
            // Outside 0..1 a cubic-bezier is extended along its end tangents; inside, it is
            // Compose's own curve.
            input in 0f..1f -> CubicBezierEasing(timing.x1, timing.y1, timing.x2, timing.y2).transform(input)
            input < 0f -> if (timing.x1 > 0f) input * timing.y1 / timing.x1 else 0f
            else -> if (timing.x2 < 1f) 1f + (input - 1f) * (1f - timing.y2) / (1f - timing.x2) else 1f
        }
        is Timing.Steps -> steps(input, timing.count, timing.position, beforeFlag)
    }
}

/**
 * The values a node draws while an animation plays on it, read in the draw phase only.
 *
 * Nothing here is read during composition except [twoLayers], so writing a value every
 * frame redraws the node and neither recomposes nor remeasures it.
 */
class AnimatedNode internal constructor() {
    /** The opacity an animation shows, or NaN where none does. */
    internal val alpha = mutableFloatStateOf(Float.NaN)

    /** The text colour an animation shows, as `Color.value`, or [NO_COLOR]. */
    internal val textColor = mutableLongStateOf(NO_COLOR)

    /** The background an animation shows, as `Color.value`, or [NO_COLOR]. */
    internal val background = mutableLongStateOf(NO_COLOR)

    /** The matrix an animation shows, `a, b, c, d, e, f`, valid while [transformShown]. */
    internal val matrix = FloatArray(6)
    internal var transformShown = false

    /** Bumped whenever [matrix] or [transformShown] changes, so a layer reading it redraws. */
    internal val transformVersion = mutableIntStateOf(0)

    /**
     * Whether the node's transform has to be drawn with both layers. Read in composition,
     * and set once, the first time a transform animation plays on the node.
     */
    internal val twoLayers = mutableStateOf(false)

    /** The colour a Text draws with: the animation's while one plays, its own otherwise. */
    val textColorProducer: ColorProducer = ColorProducer {
        val bits = textColor.longValue
        if (bits == NO_COLOR) Color.Unspecified else Color(bits.toULong())
    }

    internal fun backgroundOverride(): Color? {
        val bits = background.longValue
        return if (bits == NO_COLOR) null else Color(bits.toULong())
    }

    internal companion object {
        /** No colour a paint resolves to has this value: its low bits name a colour space. */
        const val NO_COLOR = 1L
    }
}

/**
 * The animations the Renderer is playing, and the frame-by-frame work of playing them.
 *
 * Keyed by node, property and slot. Each frame every entry is evaluated by the timing model
 * and the highest slot in effect on each property writes its value into the node's
 * [AnimatedNode], which only the draw phase reads. Requested events are collected and
 * handed to the Host once the values are written and before anything is drawn.
 */
class AnimationTable internal constructor(private val table: NodeTable) {

    private class Entry(val animation: Animation) {
        /** Resolved on the first frame where the record said zero. Nanoseconds. */
        var startNanos: Long = animation.startTimeNanos
        var pausedAtNanos: Long = if (animation.playState == PlayState.Paused) PAUSED_AT_START else NOT_PAUSED
        var pendingControl: AnimationControl? = null
        var started = false
        var lastPhase: AnimationPhase? = null
        var lastIteration = 0.0
        var fromPresented: Any? = null
        var newInBatch = true
        var finished = false
        var readySent = false
    }

    private val entries = ArrayList<Entry>()

    /** Bumped when an animation starts or resumes, which is what wakes the frame loop. */
    internal val generation = mutableIntStateOf(0)

    private val sample = TimeSample()
    private val scratch = FloatArray(6)
    private val scratchFrom = FloatArray(6)
    private val scratchTo = FloatArray(6)
    private val pendingEvents = ArrayList<HostEvent>()

    /** The node's draw-phase values, made the first time anything asks. */
    private val nodes = HashMap<Int, AnimatedNode>()

    fun animated(nodeId: Int): AnimatedNode = nodes.getOrPut(nodeId) { AnimatedNode() }

    internal fun start(animation: Animation) {
        entries.removeAll {
            it.animation.nodeId == animation.nodeId &&
                it.animation.property == animation.property &&
                it.animation.slot == animation.slot
        }
        entries += Entry(animation)
        if (animation.property == AnimatedProperty.Transform) {
            animated(animation.nodeId).twoLayers.value = true
        }
        generation.intValue += 1
    }

    internal fun control(mutation: Mutation.ControlAnimation) {
        val entry = entries.firstOrNull {
            it.animation.nodeId == mutation.nodeId &&
                it.animation.property == mutation.property &&
                it.animation.slot == mutation.slot
        } ?: return
        if (entry.animation.animationId != mutation.animationId) return
        when (mutation.op) {
            AnimationControl.Cancel -> {
                entries.remove(entry)
                clearValue(mutation.nodeId, mutation.property)
            }
            else -> {
                if (mutation.atTimeNanos == 0L) {
                    entry.pendingControl = mutation.op
                } else {
                    applyControl(entry, mutation.op, mutation.atTimeNanos)
                }
            }
        }
        generation.intValue += 1
    }

    private fun applyControl(entry: Entry, op: AnimationControl, at: Long) {
        when (op) {
            AnimationControl.Pause -> if (entry.pausedAtNanos == NOT_PAUSED) entry.pausedAtNanos = at
            AnimationControl.Resume -> {
                val pausedAt = entry.pausedAtNanos
                if (pausedAt != NOT_PAUSED) {
                    if (entry.started && pausedAt != PAUSED_AT_START) entry.startNanos += at - pausedAt
                    else if (pausedAt == PAUSED_AT_START) entry.startNanos = at
                    entry.pausedAtNanos = NOT_PAUSED
                }
            }
            AnimationControl.Cancel -> Unit
        }
    }

    /** A removed node takes its animations with it, without an event. */
    internal fun removeNode(nodeId: Int) {
        if (entries.removeAll { it.animation.nodeId == nodeId }) generation.intValue += 1
        nodes.remove(nodeId)
    }

    internal fun clear() {
        entries.clear()
        nodes.clear()
    }

    /**
     * Checked once the whole batch is applied, because a batch may start an animation
     * before the record that gives it its underlying value. An animation started in this
     * batch with nowhere to be drawn is a protocol error; an older one whose underlying
     * value has since been taken away is cancelled without a word, because the Host took
     * it away and knows.
     */
    internal fun afterBatch(fail: (Int, String) -> Unit) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val animation = entry.animation
            val problem = baseProblem(animation)
            if (problem != null) {
                iterator.remove()
                clearValue(animation.nodeId, animation.property)
                if (entry.newInBatch) fail(TableError.INVALID_ANIMATION, problem)
            }
            entry.newInBatch = false
        }
    }

    private fun baseProblem(animation: Animation): String? {
        val node = table.node(animation.nodeId)
            ?: return "animation ${animation.animationId} plays on node ${animation.nodeId}, which does not exist"
        return when (animation.property) {
            AnimatedProperty.Alpha -> if (node.modifiers.none { it is ProtocolModifier.Alpha }) "no Alpha modifier" else null
            AnimatedProperty.Background -> if (node.modifiers.none { it is ProtocolModifier.Background }) "no Background modifier" else null
            AnimatedProperty.Transform -> if (node.modifiers.none { it is ProtocolModifier.Transform }) "no Transform modifier" else null
            AnimatedProperty.Color ->
                if (!NodeTable.supportsProperty(node.widget, PropertyKind.Color)) "${node.widget} has no color" else null
        }?.let { "animation ${animation.animationId} on node ${animation.nodeId}: $it to play on" }
    }

    /** Whether any animation is waiting to start or still running, and so needs frames. */
    internal fun needsFrames(): Boolean = entries.any { entry ->
        !entry.finished && (entry.pausedAtNanos == NOT_PAUSED || entry.pendingControl != null || !entry.started)
    }

    /**
     * Plays one frame: every animation evaluated at [frameNanos], the winning value of each
     * property written where the node draws it, and the requested events handed to
     * [dispatch] once all values are written.
     */
    internal fun tick(frameNanos: Long, theme: ResolvedTheme, dispatch: (HostEvent) -> Unit) {
        pendingEvents.clear()
        // Settle starts and controls that were waiting for a frame time, and evaluate.
        for (entry in entries) {
            val animation = entry.animation
            if (!entry.started) {
                entry.started = true
                if (entry.startNanos == 0L) entry.startNanos = frameNanos
                if (entry.pausedAtNanos == PAUSED_AT_START) entry.pausedAtNanos = entry.startNanos
                entry.fromPresented = if (animation.keyframes.first().fromPresented) presented(animation, theme) else null
                if (animation.startTimeNanos == 0L && animation.events and EVENT_READY != 0 && !entry.readySent) {
                    entry.readySent = true
                    pendingEvents += event(animation, AnimationEventKind.Ready, 0, 0f, entry.startNanos)
                }
            }
            entry.pendingControl?.let { op ->
                entry.pendingControl = null
                applyControl(entry, op, frameNanos)
            }
        }
        // The highest slot in effect on each node and property wins; lower ones and finished
        // ones still produce their events.
        var index = 0
        while (index < entries.size) {
            val entry = entries[index]
            val animation = entry.animation
            val time = if (entry.pausedAtNanos != NOT_PAUSED) entry.pausedAtNanos else frameNanos
            val localMs = (time - entry.startNanos) / 1_000_000.0
            WebAnimationTiming.sample(
                localMs,
                animation.delayMs.toDouble(),
                animation.durationMs.toDouble(),
                animation.iterations.toDouble(),
                animation.direction,
                animation.fill,
                sample,
            )
            collectEvents(entry, frameNanos)
            entry.finished = sample.phase == AnimationPhase.After
            if (entry.finished && !sample.inEffect) {
                // Ended with no fill: it is gone, and the underlying value shows again
                // unless another slot still has one.
                entries.removeAt(index)
                clearValue(animation.nodeId, animation.property)
                continue
            }
            index++
        }
        // Write each property's winning value, once per node and property.
        for (position in entries.indices) {
            val animation = entries[position].animation
            var first = true
            for (earlier in 0 until position) {
                val other = entries[earlier].animation
                if (other.nodeId == animation.nodeId && other.property == animation.property) {
                    first = false
                    break
                }
            }
            if (first) writeWinner(animation.nodeId, animation.property, frameNanos, theme)
        }
        // Handed over once every value is written: a handler that starts the next animation
        // has it applied in this same frame, before anything is drawn.
        for (event in pendingEvents) dispatch(event)
        pendingEvents.clear()
    }

    private fun writeWinner(nodeId: Int, property: AnimatedProperty, frameNanos: Long, theme: ResolvedTheme) {
        var winner: Entry? = null
        for (entry in entries) {
            val animation = entry.animation
            if (animation.nodeId != nodeId || animation.property != property) continue
            if (winner == null || animation.slot > winner.animation.slot) {
                if (inEffect(entry, frameNanos)) winner = entry
            }
        }
        if (winner == null) {
            clearValue(nodeId, property)
            return
        }
        val time = if (winner.pausedAtNanos != NOT_PAUSED) winner.pausedAtNanos else frameNanos
        val animation = winner.animation
        WebAnimationTiming.sample(
            (time - winner.startNanos) / 1_000_000.0,
            animation.delayMs.toDouble(),
            animation.durationMs.toDouble(),
            animation.iterations.toDouble(),
            animation.direction,
            animation.fill,
            sample,
        )
        val keyframes = animation.keyframes
        val p = sample.progress
        var segment = 0
        while (segment < keyframes.size - 2 && p >= keyframes[segment + 1].offset) segment++
        val from = keyframes[segment]
        val to = keyframes[segment + 1]
        val span = to.offset - from.offset
        val local = if (span == 0f) 0f else (p - from.offset) / span
        val eased = WebAnimationTiming.ease(from.timing, local, sample.beforeFlag)
        val fromValue: Any = if (segment == 0 && winner.fromPresented != null) winner.fromPresented!! else from.value
        val node = animated(nodeId)
        when (property) {
            AnimatedProperty.Alpha -> {
                val start = (fromValue as? KeyframeValue.Alpha)?.value ?: (fromValue as Float)
                val end = (to.value as KeyframeValue.Alpha).value
                node.alpha.floatValue = (start + (end - start) * eased).coerceIn(0f, 1f)
            }
            AnimatedProperty.Color, AnimatedProperty.Background -> {
                val start = colorOf(fromValue, theme)
                val end = theme.color((to.value as KeyframeValue.PaintValue).paint)
                val mixed = mix(start, end, eased, animation.interpolation ?: ColorInterpolation.SrgbPremultiplied)
                val bits = mixed.value.toLong()
                if (property == AnimatedProperty.Color) node.textColor.longValue = bits else node.background.longValue = bits
            }
            AnimatedProperty.Transform -> {
                transformBetween(fromValue, to.value as KeyframeValue.Transform, eased, node.matrix)
                node.transformShown = true
                node.transformVersion.intValue += 1
            }
        }
    }

    private fun inEffect(entry: Entry, frameNanos: Long): Boolean {
        val animation = entry.animation
        val time = if (entry.pausedAtNanos != NOT_PAUSED) entry.pausedAtNanos else frameNanos
        WebAnimationTiming.sample(
            (time - entry.startNanos) / 1_000_000.0,
            animation.delayMs.toDouble(),
            animation.durationMs.toDouble(),
            animation.iterations.toDouble(),
            animation.direction,
            animation.fill,
            sample,
        )
        return sample.inEffect
    }

    private fun clearValue(nodeId: Int, property: AnimatedProperty) {
        val node = nodes[nodeId] ?: return
        when (property) {
            AnimatedProperty.Alpha -> if (!node.alpha.floatValue.isNaN()) node.alpha.floatValue = Float.NaN
            AnimatedProperty.Color -> if (node.textColor.longValue != AnimatedNode.NO_COLOR) node.textColor.longValue = AnimatedNode.NO_COLOR
            AnimatedProperty.Background -> if (node.background.longValue != AnimatedNode.NO_COLOR) node.background.longValue = AnimatedNode.NO_COLOR
            AnimatedProperty.Transform -> if (node.transformShown) {
                node.transformShown = false
                node.transformVersion.intValue += 1
            }
        }
    }

    /** The events this frame produced for one animation, in the order CSS fires them. */
    private fun collectEvents(entry: Entry, frameNanos: Long) {
        val animation = entry.animation
        val wanted = animation.events
        val previous = entry.lastPhase
        val phase = sample.phase
        val active = WebAnimationTiming.activeDuration(animation.durationMs.toDouble(), animation.iterations.toDouble())
        val entered = previous != AnimationPhase.Active && previous != AnimationPhase.After &&
            phase != AnimationPhase.Before
        if (entered && wanted and EVENT_ACTIVE != 0) {
            val elapsed = min(max(-animation.delayMs.toDouble(), 0.0), active)
            pendingEvents += event(animation, AnimationEventKind.Active, 0, elapsed.toFloat(), frameNanos)
        }
        if (phase == AnimationPhase.Active && previous == AnimationPhase.Active &&
            sample.iteration != entry.lastIteration && wanted and EVENT_ITERATION != 0
        ) {
            val iteration = sample.iteration
            pendingEvents += event(
                animation,
                AnimationEventKind.Iteration,
                iteration.toInt(),
                (iteration * animation.durationMs).toFloat(),
                frameNanos,
            )
        }
        if (phase == AnimationPhase.After && previous != AnimationPhase.After && wanted and EVENT_END != 0) {
            val iteration = if (sample.iteration.isFinite()) sample.iteration.toInt() else Int.MAX_VALUE
            pendingEvents += event(animation, AnimationEventKind.End, iteration, active.toFloat(), frameNanos)
        }
        entry.lastPhase = phase
        if (sample.iteration.isFinite()) entry.lastIteration = sample.iteration
    }

    private fun event(animation: Animation, kind: AnimationEventKind, iteration: Int, elapsedMs: Float, time: Long) =
        HostEvent.AnimationEvent(
            nodeId = animation.nodeId,
            handlerId = 0,
            animationId = animation.animationId,
            kind = kind,
            property = animation.property,
            slot = animation.slot,
            iteration = iteration,
            elapsedMs = elapsedMs,
            timeNanos = time,
        )

    /**
     * What the node shows on this property right now: the value an animation wrote last
     * frame, or the underlying value where none did.
     */
    private fun presented(animation: Animation, theme: ResolvedTheme): Any? {
        val node = table.node(animation.nodeId) ?: return null
        val shown = nodes[animation.nodeId]
        return when (animation.property) {
            AnimatedProperty.Alpha -> shown?.alpha?.floatValue?.takeUnless { it.isNaN() }
                ?: (node.modifiers.firstOrNull { it is ProtocolModifier.Alpha } as ProtocolModifier.Alpha?)?.value
                ?: 1f
            AnimatedProperty.Background -> shown?.backgroundOverride()
                ?: (node.modifiers.firstOrNull { it is ProtocolModifier.Background } as ProtocolModifier.Background?)
                    ?.let { theme.color(it.paint) }
                ?: Color.Transparent
            AnimatedProperty.Color -> shown?.textColor?.longValue?.takeUnless { it == AnimatedNode.NO_COLOR }
                ?.let { Color(it.toULong()) }
                ?: ((node.property(PropertyKind.Color) as? PropertyValue.Integer)?.value)
                    ?.let { bits -> paintOf(bits)?.let(theme::color) }
                ?: theme.color(dev.darkpyonix.composerust.protocol.ColorRole.OnSurface)
            AnimatedProperty.Transform -> FloatArray(6).also { matrix ->
                if (shown != null && shown.transformShown) {
                    shown.matrix.copyInto(matrix)
                } else {
                    val base = node.modifiers.firstOrNull { it is ProtocolModifier.Transform } as ProtocolModifier.Transform?
                    if (base == null) {
                        identity(matrix)
                    } else {
                        matrix[0] = base.a; matrix[1] = base.b; matrix[2] = base.c
                        matrix[3] = base.d; matrix[4] = base.e; matrix[5] = base.f
                    }
                }
            }
        }
    }

    private fun colorOf(value: Any, theme: ResolvedTheme): Color = when (value) {
        is Color -> value
        is KeyframeValue.PaintValue -> theme.color(value.paint)
        else -> Color.Transparent
    }

    /**
     * The matrix between two keyframes' transforms at [t].
     *
     * Function by function where the two lists line up, which is what the Host sends; when
     * one side is a matrix taken from what was presented, both are folded into matrices
     * and interpolated by decomposition, as CSS does for lists that do not line up.
     */
    private fun transformBetween(from: Any, to: KeyframeValue.Transform, t: Float, into: FloatArray) {
        if (from is KeyframeValue.Transform && from.functions.size == to.functions.size) {
            identity(into)
            for (index in from.functions.indices) {
                val a = from.functions[index]
                val b = to.functions[index]
                if (a.kind == TransformFunctionKind.Matrix) {
                    functionMatrix(a, 0f, null, scratchFrom)
                    functionMatrix(b, 0f, null, scratchTo)
                    interpolateMatrices(scratchFrom, scratchTo, t, scratch)
                } else {
                    functionMatrix(a, t, b, scratch)
                }
                multiply(into, scratch, into)
            }
            return
        }
        when (from) {
            is FloatArray -> from.copyInto(scratchFrom)
            is KeyframeValue.Transform -> fold(from.functions, scratchFrom)
            else -> identity(scratchFrom)
        }
        fold(to.functions, scratchTo)
        interpolateMatrices(scratchFrom, scratchTo, t, into)
    }

    private fun fold(functions: List<TransformFunction>, into: FloatArray) {
        identity(into)
        for (function in functions) {
            functionMatrix(function, 0f, null, scratch)
            multiply(into, scratch, into)
        }
    }

    internal companion object {
        const val EVENT_READY = 1
        const val EVENT_ACTIVE = 2
        const val EVENT_ITERATION = 4
        const val EVENT_END = 8
        const val NOT_PAUSED = Long.MIN_VALUE

        /** Paused from the start: the start time is not known yet, and the pause is there. */
        const val PAUSED_AT_START = Long.MIN_VALUE + 1

        fun identity(m: FloatArray) {
            m[0] = 1f; m[1] = 0f; m[2] = 0f; m[3] = 1f; m[4] = 0f; m[5] = 0f
        }

        /** `out = left × right` for CSS matrices `a, b, c, d, e, f`. `out` may be either. */
        fun multiply(left: FloatArray, right: FloatArray, out: FloatArray) {
            val a = left[0] * right[0] + left[2] * right[1]
            val b = left[1] * right[0] + left[3] * right[1]
            val c = left[0] * right[2] + left[2] * right[3]
            val d = left[1] * right[2] + left[3] * right[3]
            val e = left[0] * right[4] + left[2] * right[5] + left[4]
            val f = left[1] * right[4] + left[3] * right[5] + left[5]
            out[0] = a; out[1] = b; out[2] = c; out[3] = d; out[4] = e; out[5] = f
        }

        /**
         * The matrix of one function, or of the function between [function] and [other]
         * at [t] when [other] is given, every value interpolated on its own.
         */
        fun functionMatrix(function: TransformFunction, t: Float, other: TransformFunction?, out: FloatArray) {
            fun v(index: Int): Float {
                val a = when (index) { 0 -> function.v0; 1 -> function.v1; 2 -> function.v2; 3 -> function.v3; 4 -> function.v4; else -> function.v5 }
                if (other == null) return a
                val b = when (index) { 0 -> other.v0; 1 -> other.v1; 2 -> other.v2; 3 -> other.v3; 4 -> other.v4; else -> other.v5 }
                return a + (b - a) * t
            }
            when (function.kind) {
                TransformFunctionKind.Translate -> { identity(out); out[4] = v(0); out[5] = v(1) }
                TransformFunctionKind.Rotate -> {
                    val radians = v(0) * PI.toFloat() / 180f
                    out[0] = cos(radians); out[1] = sin(radians); out[2] = -sin(radians); out[3] = cos(radians)
                    out[4] = 0f; out[5] = 0f
                }
                TransformFunctionKind.Scale -> { identity(out); out[0] = v(0); out[3] = v(1) }
                TransformFunctionKind.Skew -> {
                    identity(out)
                    out[2] = tan(v(0) * PI.toFloat() / 180f)
                    out[1] = tan(v(1) * PI.toFloat() / 180f)
                }
                TransformFunctionKind.Matrix -> {
                    out[0] = v(0); out[1] = v(1); out[2] = v(2); out[3] = v(3); out[4] = v(4); out[5] = v(5)
                }
            }
        }

        /**
         * Interpolates two matrices the way CSS Transforms 1 does in 2D: each is decomposed
         * into a translation, a scale, an angle and what remains, the parts are interpolated,
         * and the result is put back together.
         */
        fun interpolateMatrices(from: FloatArray, to: FloatArray, t: Float, out: FloatArray) {
            val a = decomposedFrom.also { it.of(from) }
            val b = decomposedTo.also { it.of(to) }
            if ((a.scaleX < 0 && b.scaleY < 0) || (a.scaleY < 0 && b.scaleX < 0)) {
                a.scaleX = -a.scaleX
                a.scaleY = -a.scaleY
                a.angle += if (a.angle < 0) 180.0 else -180.0
            }
            if (a.angle == 0.0) a.angle = 360.0
            if (b.angle == 0.0) b.angle = 360.0
            if (abs(a.angle - b.angle) > 180.0) {
                if (a.angle > b.angle) a.angle -= 360.0 else b.angle -= 360.0
            }
            fun lerp(x: Double, y: Double) = x + (y - x) * t
            val result = decomposedResult
            result.translateX = lerp(a.translateX, b.translateX)
            result.translateY = lerp(a.translateY, b.translateY)
            result.scaleX = lerp(a.scaleX, b.scaleX)
            result.scaleY = lerp(a.scaleY, b.scaleY)
            result.angle = lerp(a.angle, b.angle)
            result.m11 = lerp(a.m11, b.m11)
            result.m12 = lerp(a.m12, b.m12)
            result.m21 = lerp(a.m21, b.m21)
            result.m22 = lerp(a.m22, b.m22)
            result.into(out)
        }

        // Reused for every matrix interpolation. Animations are played on the UI thread
        // and nowhere else, so one set is enough and a frame allocates none.
        private val decomposedFrom = Decomposed()
        private val decomposedTo = Decomposed()
        private val decomposedResult = Decomposed()

        /** The decoded paint a `Color` property holds, as a Host sends it. */
        fun paintOf(bits: Long): Paint? {
            val value = bits.toInt()
            return when ((bits ushr 32).toInt()) {
                1 -> dev.darkpyonix.composerust.protocol.ColorRole.entries.getOrNull(value - 1)?.let(Paint::Role)
                2 -> Paint.Literal(value)
                else -> null
            }
        }

        /** Two colours mixed at [t], in the space the animation named, clamped to the gamut. */
        fun mix(from: Color, to: Color, t: Float, space: ColorInterpolation): Color = when (space) {
            ColorInterpolation.SrgbPremultiplied -> {
                val alpha = from.alpha + (to.alpha - from.alpha) * t
                fun channel(a: Float, b: Float): Float {
                    if (alpha <= 0f) return 0f
                    val premultiplied = a * from.alpha + (b * to.alpha - a * from.alpha) * t
                    return (premultiplied / alpha).coerceIn(0f, 1f)
                }
                Color(channel(from.red, to.red), channel(from.green, to.green), channel(from.blue, to.blue), alpha.coerceIn(0f, 1f))
            }
            ColorInterpolation.Oklab -> {
                val a = oklab(from)
                val b = oklab(to)
                val alpha = from.alpha + (to.alpha - from.alpha) * t
                // Premultiplied, as CSS Color 4 interpolates with alpha.
                fun channel(index: Int): Float {
                    if (alpha <= 0f) return 0f
                    val premultiplied = a[index] * from.alpha + (b[index] * to.alpha - a[index] * from.alpha) * t
                    return premultiplied / alpha
                }
                fromOklab(channel(0), channel(1), channel(2), alpha.coerceIn(0f, 1f))
            }
        }

        private fun linear(c: Float): Float =
            if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

        private fun gamma(c: Float): Float =
            if (c <= 0.0031308f) 12.92f * c else (1.055 * c.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()

        private fun oklab(color: Color): FloatArray {
            val r = linear(color.red)
            val g = linear(color.green)
            val b = linear(color.blue)
            val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
            val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
            val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
            return floatArrayOf(
                0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
                1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
                0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
            )
        }

        private fun fromOklab(lightness: Float, a: Float, b: Float, alpha: Float): Color {
            val l = (lightness + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
            val m = (lightness - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
            val s = (lightness - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
            val r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
            val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
            val bl = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
            return Color(
                gamma(r).coerceIn(0f, 1f),
                gamma(g).coerceIn(0f, 1f),
                gamma(bl).coerceIn(0f, 1f),
                alpha,
            )
        }
    }

    /** A 2D matrix taken apart as CSS Transforms 1 takes one apart. Angles in degrees. */
    private class Decomposed {
        var translateX = 0.0
        var translateY = 0.0
        var scaleX = 1.0
        var scaleY = 1.0
        var angle = 0.0
        var m11 = 1.0
        var m12 = 0.0
        var m21 = 0.0
        var m22 = 1.0

        fun of(m: FloatArray) {
            var row0x = m[0].toDouble()
            var row0y = m[1].toDouble()
            var row1x = m[2].toDouble()
            var row1y = m[3].toDouble()
            translateX = m[4].toDouble()
            translateY = m[5].toDouble()
            scaleX = sqrt(row0x * row0x + row0y * row0y)
            scaleY = sqrt(row1x * row1x + row1y * row1y)
            val determinant = row0x * row1y - row0y * row1x
            if (determinant < 0) {
                if (row0x < row1y) scaleX = -scaleX else scaleY = -scaleY
            }
            if (scaleX != 0.0) {
                row0x /= scaleX
                row0y /= scaleX
            }
            if (scaleY != 0.0) {
                row1x /= scaleY
                row1y /= scaleY
            }
            val radians = atan2(row0y, row0x)
            angle = radians * 180.0 / PI
            if (radians != 0.0) {
                val sn = -row0y
                val cs = row0x
                val a11 = row0x
                val a12 = row0y
                val a21 = row1x
                val a22 = row1y
                row0x = cs * a11 + sn * a21
                row0y = cs * a12 + sn * a22
                row1x = -sn * a11 + cs * a21
                row1y = -sn * a12 + cs * a22
            }
            m11 = row0x
            m12 = row0y
            m21 = row1x
            m22 = row1y
        }

        fun into(out: FloatArray) {
            val radians = angle * PI / 180.0
            val cs = cos(radians)
            val sn = sin(radians)
            // The rotation applied to the remaining matrix's rows, then the scale.
            val row0x = (cs * m11 + sn * m21) * scaleX
            val row0y = (cs * m12 + sn * m22) * scaleX
            val row1x = (-sn * m11 + cs * m21) * scaleY
            val row1y = (-sn * m12 + cs * m22) * scaleY
            out[0] = row0x.toFloat()
            out[1] = row0y.toFloat()
            out[2] = row1x.toFloat()
            out[3] = row1y.toFloat()
            out[4] = translateX.toFloat()
            out[5] = translateY.toFloat()
        }
    }
}

package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.MessagePlacement
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.design.composeLetterSpacing
import dev.darkpyonix.composerust.design.composeLineHeight
import dev.darkpyonix.composerust.design.fontSize
import androidx.compose.ui.text.TextStyle
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.MessageDuration
import dev.darkpyonix.composerust.protocol.MessageHorizontal
import dev.darkpyonix.composerust.protocol.MessageVertical
import dev.darkpyonix.composerust.runtime.EventDispatcher
import kotlinx.coroutines.delay

/**
 * One thing the Host asked to say.
 *
 * `serial` is this side's own number rather than anything from the wire: two identical
 * messages are two messages, and the composable that shows one has to be able to tell that
 * the second is not the first still on screen.
 *
 * `vertical` and `horizontal` are where the Host asked for it to be drawn, and `offsetDp`
 * how far from that edge. Null leaves each to the design system.
 */
data class HostMessage(
    val serial: Long,
    val handlerId: Long,
    val text: String,
    val action: String,
    val duration: MessageDuration,
    val vertical: MessageVertical? = null,
    val horizontal: MessageHorizontal? = null,
    val offsetDp: Float? = null,
)

/**
 * The messages waiting to be said, oldest first. The first of them is the one on screen.
 *
 * **One at a time, in order.** A second message arriving while the first is up waits its
 * turn: replacing the first would take away the answer to something the user just did, and
 * stacking them would cover the screen they are about.
 *
 * The line is finite. Past [CAPACITY] the longest-waiting message that is not the one being
 * read is dropped, because by then it is the answer to something the user has stopped
 * thinking about, and because an unbounded line is a leak with a nicer name.
 */
class MessageQueue {
    private val waiting = mutableStateListOf<HostMessage>()
    private var nextSerial = 1L

    /** The message on screen, or null when there is nothing to say. */
    val current: HostMessage? get() = waiting.firstOrNull()

    /** How many messages are waiting, including the one on screen. */
    val size: Int get() = waiting.size

    fun post(
        handlerId: Long,
        text: String,
        action: String,
        duration: MessageDuration,
        vertical: MessageVertical? = null,
        horizontal: MessageHorizontal? = null,
        offsetDp: Float? = null,
    ) {
        if (waiting.size >= CAPACITY) {
            // Index 1 rather than 0: the one at the front is being read.
            waiting.removeAt(if (waiting.size > 1) 1 else 0)
        }
        waiting.add(HostMessage(nextSerial++, handlerId, text, action, duration, vertical, horizontal, offsetDp))
    }

    /** Takes the message off the front, if it is still the one at the front. */
    fun dismiss(message: HostMessage) {
        if (waiting.firstOrNull()?.serial == message.serial) {
            waiting.removeAt(0)
        }
    }

    fun clear() = waiting.clear()

    companion object {
        const val CAPACITY = 8
    }
}

/**
 * How much of the window's edges the screen's own chrome is using.
 *
 * A message is drawn over everything, because it is not in the tree, and a message drawn
 * over the destinations would hide the thing the user needs next. The navigation says how
 * tall its bar is while it is drawing one, and the message keeps clear of it. Each top bar
 * says how tall it is, so a message the application placed along the top can keep clear
 * of the one the page opens with.
 */
class ChromeInsets {
    var bottom: Dp by mutableStateOf(0.dp)
        internal set

    /** How tall each top bar on screen is, by node. */
    private val topBars = mutableStateMapOf<Int, Dp>()

    /** How tall the top bar `nodeId` is, or zero when there is no such bar on screen. */
    fun topBarHeight(nodeId: Int): Dp = topBars[nodeId] ?: 0.dp

    internal fun topBar(nodeId: Int, height: Dp?) {
        if (height == null) {
            topBars.remove(nodeId)
        } else if (topBars[nodeId] != height) {
            topBars[nodeId] = height
        }
    }
}

/**
 * The strips of the window nothing should be drawn under, and the bar the page opens with.
 *
 * `top` is the window's caption and a phone's status bar, `bottom` a gesture bar or the
 * keyboard. `pageTop` is where the page starts, and `topBar` the node id of the top bar it
 * opens with, or 0 when it opens with none: that bar runs from `pageTop` down by its own
 * height, and a message along the top starts below it.
 */
data class MessageSafeArea(
    val top: Dp = 0.dp,
    val bottom: Dp = 0.dp,
    val pageTop: Dp = 0.dp,
    val topBar: Int = 0,
)

/** Where a message sits once the Host's request and the design system's answer are put together. */
private data class MessageFrame(
    val alignment: Alignment,
    val start: Dp,
    val top: Dp,
    val end: Dp,
    val bottom: Dp,
)

/**
 * The padding and alignment of the box a message is drawn in.
 *
 * A message that asks for nothing is where it always was: the design system's place, its
 * inset from the edges, and clear of the bar along the bottom. One that asks for a place
 * or an offset is measured from inside the safe area and the application's bars instead,
 * so a message at the top is below the top bar and one at the bottom above the tab bar,
 * whichever of the two the application chose.
 */
private fun messageFrame(
    message: HostMessage,
    style: dev.darkpyonix.composerust.design.MessageStyle,
    insets: ChromeInsets,
    safeArea: MessageSafeArea,
): MessageFrame {
    if (message.vertical == null && message.offsetDp == null) {
        val alignment = when (style.placement) {
            MessagePlacement.BottomStart -> Alignment.BottomStart
            MessagePlacement.BottomCenter -> Alignment.BottomCenter
            MessagePlacement.TopCenter -> Alignment.TopCenter
            MessagePlacement.TopEnd -> Alignment.TopEnd
        }
        // A message along the bottom edge keeps clear of whatever chrome is already there;
        // one along the top has nothing to keep clear of.
        val bottom = if (style.placement.atTop) style.inset else style.inset + insets.bottom
        return MessageFrame(alignment, style.inset, style.inset, style.inset, bottom)
    }
    val vertical = message.vertical ?: if (style.placement.atTop) MessageVertical.Top else MessageVertical.Bottom
    val horizontal = message.horizontal ?: when (style.placement) {
        MessagePlacement.BottomStart -> MessageHorizontal.Start
        MessagePlacement.BottomCenter, MessagePlacement.TopCenter -> MessageHorizontal.Center
        MessagePlacement.TopEnd -> MessageHorizontal.End
    }
    val alignment = when (vertical) {
        MessageVertical.Top -> when (horizontal) {
            MessageHorizontal.Start -> Alignment.TopStart
            MessageHorizontal.Center -> Alignment.TopCenter
            MessageHorizontal.End -> Alignment.TopEnd
        }
        MessageVertical.Bottom -> when (horizontal) {
            MessageHorizontal.Start -> Alignment.BottomStart
            MessageHorizontal.Center -> Alignment.BottomCenter
            MessageHorizontal.End -> Alignment.BottomEnd
        }
    }
    // Never a negative padding: a padding below zero throws from inside layout.
    val edge = (message.offsetDp?.dp ?: style.inset).coerceAtLeast(0.dp)
    val barBottom = if (safeArea.topBar == 0) 0.dp else safeArea.pageTop + insets.topBarHeight(safeArea.topBar)
    val topClear = maxOf(safeArea.top, barBottom).coerceAtLeast(0.dp)
    val bottomClear = maxOf(safeArea.bottom, insets.bottom).coerceAtLeast(0.dp)
    return when (vertical) {
        MessageVertical.Top ->
            MessageFrame(alignment, style.inset, topClear + edge, style.inset, bottomClear + style.inset)
        MessageVertical.Bottom ->
            MessageFrame(alignment, style.inset, topClear + style.inset, style.inset, bottomClear + edge)
    }
}

/**
 * Draws the message at the front of the queue, for as long as this design system says.
 *
 * The timer lives here, which is the reason a message is a record rather than a node: the
 * Host said the sentence once and does not hold a deadline for it, so nothing in Rust runs
 * to make it go away.
 */
@Composable
internal fun HostMessages(
    queue: MessageQueue,
    insets: ChromeInsets,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
    safeArea: MessageSafeArea = MessageSafeArea(),
) {
    val message = queue.current ?: return
    val style = theme.rules.message(theme)
    val token = theme.type(style.typeRole)
    val millis = when (message.duration) {
        MessageDuration.Short -> style.shortMillis
        MessageDuration.Long -> style.longMillis
    }
    LaunchedEffect(message.serial) {
        delay(millis.toLong())
        queue.dismiss(message)
    }
    val frame = messageFrame(message, style, insets, safeArea)
    // The application's overlay tokens, where it set them, take the place of the design
    // system's corner and lift. A message is not a node, so this is the only way to give it
    // either. Zero, or no tokens at all, keeps the design system's message as it is, with
    // the design system's own lift under it.
    val overlay = theme.overlay
    val shape = overlay?.toastCornerRadius?.takeIf { it > 0 }?.let { RoundedCornerShape(it.dp) } ?: style.shape
    val elevation = overlay?.toastElevation?.takeIf { it > 0 }?.dp ?: style.elevation
    val lift = if (elevation.value > 0f) Modifier.shadow(elevation, shape, clip = false) else Modifier
    val outline = if (style.borderWidth.value > 0f) {
        Modifier.border(style.borderWidth, style.borderColor, shape)
    } else {
        Modifier
    }
    Box(
        Modifier.fillMaxSize().padding(
            start = frame.start,
            top = frame.top,
            end = frame.end,
            bottom = frame.bottom,
        ),
        contentAlignment = frame.alignment,
    ) {
        Row(
            Modifier
                .testTag(MESSAGE_CONTAINER_TEST_TAG)
                .nativeViewOverlay(shape)
                .then(lift)
                .clip(shape)
                .background(style.container)
                .then(outline)
                .padding(
                    horizontal = style.horizontalPadding,
                    vertical = style.verticalPadding,
                ),
            horizontalArrangement = Arrangement.spacedBy(style.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val base = TextStyle(
                fontSize = token.fontSize,
                fontFamily = theme.family(style.typeRole),
                lineHeight = token.composeLineHeight,
                letterSpacing = token.composeLetterSpacing,
            )
            ThemedText(
                text = message.text,
                modifier = Modifier.testTag(MESSAGE_TEST_TAG),
                style = base.copy(color = style.content),
            )
            if (message.action.isNotEmpty()) {
                ThemedText(
                    text = message.action,
                    modifier = Modifier
                        .testTag(MESSAGE_ACTION_TEST_TAG)
                        .clickable {
                            // The message owns no node, so the press reports the "no node"
                            // id with the handler the Host sent.
                            dispatcher.dispatch(HostEvent.Clicked(0, message.handlerId))
                            queue.dismiss(message)
                        },
                    style = base.copy(
                        color = style.actionContent,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

/** Test tag of the sentence on screen. There is at most one. */
const val MESSAGE_TEST_TAG: String = "dioxus-message"

/** Test tag of the surface the message is drawn on, which is what its place is measured by. */
const val MESSAGE_CONTAINER_TEST_TAG: String = "compose-message-container"

/** Test tag of the message's action label, where it has one. */
const val MESSAGE_ACTION_TEST_TAG: String = "compose-message-action"

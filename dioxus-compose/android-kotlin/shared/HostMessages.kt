package dioxus.compose.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dioxus.compose.design.MessagePlacement
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.design.composeLetterSpacing
import dioxus.compose.design.composeLineHeight
import dioxus.compose.design.fontSize
import androidx.compose.ui.text.TextStyle
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.MessageDuration
import dioxus.compose.runtime.EventDispatcher
import kotlinx.coroutines.delay

/**
 * One thing the Host asked to say.
 *
 * `serial` is this side's own number rather than anything from the wire: two identical
 * messages are two messages, and the composable that shows one has to be able to tell that
 * the second is not the first still on screen.
 */
data class HostMessage(
    val serial: Long,
    val handlerId: Long,
    val text: String,
    val action: String,
    val duration: MessageDuration,
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

    fun post(handlerId: Long, text: String, action: String, duration: MessageDuration) {
        if (waiting.size >= CAPACITY) {
            // Index 1 rather than 0: the one at the front is being read.
            waiting.removeAt(if (waiting.size > 1) 1 else 0)
        }
        waiting.add(HostMessage(nextSerial++, handlerId, text, action, duration))
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
 * How much of the window's bottom edge the screen's own chrome is using.
 *
 * A message is drawn over everything, because it is not in the tree, and a message drawn
 * over the destinations would hide the thing the user needs next. The navigation says how
 * tall its bar is while it is drawing one, and the message keeps clear of it.
 */
class ChromeInsets {
    var bottom: Dp by mutableStateOf(0.dp)
        internal set
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
    val alignment = when (style.placement) {
        MessagePlacement.BottomStart -> Alignment.BottomStart
        MessagePlacement.BottomCenter -> Alignment.BottomCenter
        MessagePlacement.TopCenter -> Alignment.TopCenter
        MessagePlacement.TopEnd -> Alignment.TopEnd
    }
    val outline = if (style.borderWidth.value > 0f) {
        Modifier.border(style.borderWidth, style.borderColor, style.shape)
    } else {
        Modifier
    }
    // A message along the bottom edge keeps clear of whatever chrome is already there;
    // one along the top has nothing to keep clear of.
    val bottomInset = if (style.placement.atTop) style.inset else style.inset + insets.bottom
    Box(
        Modifier.fillMaxSize().padding(
            start = style.inset,
            top = style.inset,
            end = style.inset,
            bottom = bottomInset,
        ),
        contentAlignment = alignment,
    ) {
        Row(
            Modifier
                .clip(style.shape)
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
            BasicText(
                text = message.text,
                modifier = Modifier.testTag(MESSAGE_TEST_TAG),
                style = base.copy(color = style.content),
            )
            if (message.action.isNotEmpty()) {
                BasicText(
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

/** Test tag of the message's action label, where it has one. */
const val MESSAGE_ACTION_TEST_TAG: String = "dioxus-message-action"

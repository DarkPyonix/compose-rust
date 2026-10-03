// Taking text from the platform's input session without a text field in between is not
// something Compose offers through a settled API: the request type and the session are
// marked as still moving. They are used in this file and nowhere else, and are pinned to
// Compose 1.11.1, so a version that changes them fails this file rather than quietly
// typing nothing into the drawn editor.
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dioxus.compose.foundation.code

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.TextEditingScope
import androidx.compose.ui.text.input.TextEditorState
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What the drawn editor offers the platform's input session, and what it takes back.
 *
 * The session sees the caret's line, with any composition in it, which is all an input
 * method needs to compose and to place its candidate window.
 */
internal interface DrawnInputTarget {
    /** The caret's line as the input method should see it, composition included. */
    fun text(): String

    /** The caret, in [text]. */
    fun caret(): Int

    /** The composition, in [text], or null where none is open. */
    fun composition(): TextRange?

    /** Text the reader has finished choosing. */
    fun commit(text: String)

    /** Text the reader is still composing. Empty ends the composition with nothing. */
    fun compose(text: String)

    /** Keeps what is being composed as it is. */
    fun finishComposing()

    /** Deletes around the caret, in UTF-16 units. */
    fun deleteAround(before: Int, after: Int)

    fun layout(): TextLayoutResult?

    fun caretRectInRoot(): Rect

    fun fieldRectInRoot(): Rect
}

/** Opens the platform's input session for [target] while the node after this one is focused. */
internal fun Modifier.drawnTextInput(target: DrawnInputTarget): Modifier = this then DrawnTextInputElement(target)

private class DrawnTextInputElement(val target: DrawnInputTarget) : ModifierNodeElement<DrawnTextInputNode>() {
    override fun create() = DrawnTextInputNode(target)

    override fun update(node: DrawnTextInputNode) {
        node.target = target
    }

    override fun equals(other: Any?) = other is DrawnTextInputElement && other.target === target

    override fun hashCode() = System.identityHashCode(target)
}

private class DrawnTextInputNode(var target: DrawnInputTarget) :
    Modifier.Node(),
    PlatformTextInputModifierNode,
    FocusEventModifierNode {
    private var session: Job? = null

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused) {
            if (session == null) {
                session = coroutineScope.launch {
                    establishTextInputSession { startInputMethod(DrawnRequest(target)) }
                }
            }
        } else {
            session?.cancel()
            session = null
        }
    }

    override fun onDetach() {
        session?.cancel()
        session = null
    }
}

private class DrawnRequest(private val target: DrawnInputTarget) : PlatformTextInputMethodRequest {
    override val value: () -> TextFieldValue = {
        TextFieldValue(target.text(), TextRange(target.caret()), target.composition())
    }

    override val state: TextEditorState = object : TextEditorState {
        override val length: Int get() = target.text().length

        override fun get(index: Int): Char = target.text()[index]

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            target.text().subSequence(startIndex, endIndex)

        override val selection: TextRange get() = TextRange(target.caret())

        override val composition: TextRange? get() = target.composition()

        override fun toString(): String = target.text()
    }

    override val imeOptions: ImeOptions = ImeOptions.Default

    override val onEditCommand: (List<EditCommand>) -> Unit = { commands ->
        for (command in commands) {
            when (command) {
                is CommitTextCommand -> target.commit(command.text)
                is SetComposingTextCommand -> target.compose(command.text)
                is FinishComposingTextCommand -> target.finishComposing()
                is DeleteSurroundingTextCommand ->
                    target.deleteAround(command.lengthBeforeCursor, command.lengthAfterCursor)
                is BackspaceCommand -> target.deleteAround(1, 0)
                else -> {}
            }
        }
    }

    override val onImeAction: ((ImeAction) -> Unit)? = null

    override val textLayoutResult: () -> TextLayoutResult = {
        target.layout() ?: error("the drawn editor has not laid out its caret line yet")
    }

    override val focusedRectInRoot: () -> Rect = { target.caretRectInRoot() }

    override val textFieldRectInRoot: () -> Rect = { target.fieldRectInRoot() }

    override val textClippingRectInRoot: () -> Rect = { target.fieldRectInRoot() }

    override val unclippedTextOffsetInRoot: () -> Offset = { target.fieldRectInRoot().topLeft }

    override val editText: ((TextEditingScope) -> Unit) -> Unit = { block ->
        block(
            object : TextEditingScope {
                override fun deleteSurroundingTextInCodePoints(lengthBeforeCursor: Int, lengthAfterCursor: Int) {
                    target.deleteAround(lengthBeforeCursor, lengthAfterCursor)
                }

                override fun commitText(text: CharSequence, newCursorPosition: Int) {
                    target.commit(text.toString())
                }

                override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
                    target.compose(text.toString())
                }

                override fun finishComposingText() {
                    target.finishComposing()
                }
            },
        )
    }
}

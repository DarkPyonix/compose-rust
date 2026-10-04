@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.input.key.Key

/**
 * The Compose key a platform key number means.
 *
 * A table because the two numberings have nothing to do with each other: the platform
 * numbers keys by where they sit on the board, and Compose names them by what they are.
 * Only the keys that have a meaning of their own are here. A key that types a character
 * carries that character in the event beside it, and a screen reading text wants the
 * character rather than the position.
 *
 * Unknown is a real answer for a key that only types something. What reaches the scene
 * carries no character on purpose, because the input method is putting the text in and
 * sending it here as well types every letter twice; so a key that is only a letter has
 * nothing to do here and Unknown is what it should be.
 *
 * A letter held with a modifier is not that. Then it is a shortcut, and a shortcut is
 * matched by which key it is: with the letters missing from this table, every one of them
 * arrived as Unknown and Compose's own editing shortcuts were dead. Copy, cut, paste,
 * select all, undo and redo are the ones anyone notices, and none of them worked.
 */
internal fun composeKey(platformKey: Int): Key = when (platformKey) {
    // The letters, by where they sit on the board. Needed for the shortcuts above.
    0x00 -> Key.A
    0x01 -> Key.S
    0x02 -> Key.D
    0x03 -> Key.F
    0x04 -> Key.H
    0x05 -> Key.G
    0x06 -> Key.Z
    0x07 -> Key.X
    0x08 -> Key.C
    0x09 -> Key.V
    0x0B -> Key.B
    0x0C -> Key.Q
    0x0D -> Key.W
    0x0E -> Key.E
    0x0F -> Key.R
    0x10 -> Key.Y
    0x11 -> Key.T
    0x1F -> Key.O
    0x20 -> Key.U
    0x22 -> Key.I
    0x23 -> Key.P
    0x25 -> Key.L
    0x26 -> Key.J
    0x28 -> Key.K
    0x2D -> Key.N
    0x2E -> Key.M
    // The digits, for the same reason: a shortcut may be a number.
    0x12 -> Key.One
    0x13 -> Key.Two
    0x14 -> Key.Three
    0x15 -> Key.Four
    0x17 -> Key.Five
    0x16 -> Key.Six
    0x1A -> Key.Seven
    0x1C -> Key.Eight
    0x19 -> Key.Nine
    0x1D -> Key.Zero
    0x24 -> Key.Enter
    0x30 -> Key.Tab
    0x31 -> Key.Spacebar
    0x33 -> Key.Backspace
    0x35 -> Key.Escape
    0x75 -> Key.Delete
    0x7B -> Key.DirectionLeft
    0x7C -> Key.DirectionRight
    0x7D -> Key.DirectionDown
    0x7E -> Key.DirectionUp
    0x73 -> Key.MoveHome
    0x77 -> Key.MoveEnd
    0x74 -> Key.PageUp
    0x79 -> Key.PageDown
    else -> Key.Unknown
}

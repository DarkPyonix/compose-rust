package dev.darkpyonix.composerust.test

import androidx.compose.ui.input.key.Key
import dev.darkpyonix.composerust.ui.platform.composeKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a platform key number means to Compose.
 *
 * The letters are here because of what they mean when a modifier is held. A letter on its
 * own types something and the input method puts that text in, so the scene wants nothing
 * from it; a letter held with Command is a shortcut, and a shortcut is matched by which
 * key it is. With the letters missing every one of them arrived as `Unknown`, so copy,
 * cut, paste, select all, undo and redo were all dead in every field this renderer draws.
 */
class AppKitKeysTest {
    @Test
    fun fr5_the_keys_the_editing_shortcuts_are_made_of_reach_compose() {
        assertEquals(Key.C, composeKey(0x08), "Command C is copy and it did not arrive")
        assertEquals(Key.V, composeKey(0x09), "Command V is paste and it did not arrive")
        assertEquals(Key.X, composeKey(0x07), "Command X is cut and it did not arrive")
        assertEquals(Key.A, composeKey(0x00), "Command A selects all and it did not arrive")
        assertEquals(Key.Z, composeKey(0x06), "Command Z undoes and it did not arrive")
    }

    @Test
    fun fr5_the_keys_that_are_not_text_still_reach_compose() {
        assertEquals(Key.Enter, composeKey(0x24))
        assertEquals(Key.Backspace, composeKey(0x33))
        assertEquals(Key.DirectionLeft, composeKey(0x7B))
        assertEquals(Key.Unknown, composeKey(0x7FF), "a key nobody maps is not a key")
    }
}

package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.ImageBitmap
import dev.darkpyonix.composerust.ui.platform.DockIcon
import dev.darkpyonix.composerust.ui.platform.contentMinimum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Minimum size and the Dock icon, which the other macOS window already had. */
class MacosWindowChromeTest {

    @Test
    fun fr19_a_window_that_named_a_minimum_gets_it_and_one_that_did_not_gets_none() {
        assertEquals(320.0 to 240.0, contentMinimum(320, 240))
        assertEquals(320.0 to 0.0, contentMinimum(320, 0), "one axis may be named alone")
        assertNull(contentMinimum(0, 0), "zero means the application did not ask")
        assertNull(contentMinimum(-1, -1))
    }

    @Test
    fun fr19_3_the_dock_icon_waits_for_the_asset_and_is_put_on_once() {
        val picture = ImageBitmap(1, 1)
        var arrived: ImageBitmap? = null
        val applied = ArrayList<ImageBitmap>()
        val icon = DockIcon(lookup = { arrived }, apply = { applied.add(it) })

        assertFalse(icon.tryApply(0), "no icon named")
        assertFalse(icon.tryApply(7), "the asset has not arrived")
        assertTrue(applied.isEmpty())

        arrived = picture
        assertTrue(icon.tryApply(7))
        assertTrue(icon.tryApply(7))
        assertEquals(listOf(picture), applied, "applied once however often it is asked")
    }
}

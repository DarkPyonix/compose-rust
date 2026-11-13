package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.LinuxTextSettings
import dev.darkpyonix.composerust.runtime.Zoom
import dev.darkpyonix.composerust.ui.platform.kdeConfigFontDpi
import dev.darkpyonix.composerust.ui.platform.kdeConfigGlobalScale
import dev.darkpyonix.composerust.ui.platform.linuxTextScale
import dev.darkpyonix.composerust.ui.platform.parseXSettingsIntegers
import dev.darkpyonix.composerust.ui.platform.resourceFontDpi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a Linux desktop says how large the reader wants text, and which answer wins.
 *
 * Driven through a fake of what the window's X connection and the file system would say,
 * because no machine this runs on has a GNOME or a KDE session to ask. The bytes are built
 * the way the XSETTINGS specification lays them out, in both byte orders, since the settings
 * manager writes in its own.
 */
class LinuxTextScaleTest {

    /** What the desktop says, as a test sets it. */
    private var serial = 1
    private var xsettings: ByteArray? = null
    private var resources: String? = null
    private val files = mutableMapOf<String, String>()
    private val environment = mutableMapOf("HOME" to "/home/reader")
    private var reads = 0

    private val settings = LinuxTextSettings(
        serial = { serial },
        xsettings = {
            reads++
            xsettings
        },
        resources = { resources },
        readFile = { files[it] },
        environment = { environment[it] },
    )

    @Test
    fun fr43_gnome_text_scaling_factor_is_read_from_its_unscaled_dpi() {
        // GNOME at a text scaling factor of 1.25 on a display it scales by two: Xft/DPI counts
        // both, the unscaled DPI only the text.
        xsettings = xsettingsBytes(
            littleEndian = true,
            "Xft/DPI" to 96 * 1024 * 5 / 4 * 2,
            "Gdk/UnscaledDPI" to 96 * 1024 * 5 / 4,
            "Net/ThemeName" to "Adwaita",
        )

        assertEquals(1.25f, linuxTextScale(settings)())
    }

    @Test
    fun fr43_a_settings_manager_with_only_xft_dpi_is_read_from_that() {
        xsettings = xsettingsBytes(littleEndian = false, "Xft/DPI" to 144 * 1024)

        assertEquals(1.5f, linuxTextScale(settings)())
    }

    @Test
    fun fr43_kde_forced_font_dpi_wins_in_a_kde_session() {
        environment["XDG_CURRENT_DESKTOP"] = "KDE"
        files["/home/reader/.config/kcmfonts"] = "[General]\nforceFontDPI=120\n"
        xsettings = xsettingsBytes(littleEndian = true, "Xft/DPI" to 96 * 1024)
        resources = "Xft.dpi:\t96\n"

        assertEquals(1.25f, linuxTextScale(settings)())
    }

    @Test
    fun fr43_kde_font_dpi_in_kdeglobals_is_read_where_kcmfonts_forces_nothing() {
        environment["KDE_FULL_SESSION"] = "true"
        environment["XDG_CONFIG_HOME"] = "/elsewhere"
        files["/elsewhere/kcmfonts"] = "[General]\nforceFontDPI=0\n"
        files["/elsewhere/kdeglobals"] = "[General]\nforceFontDPIWayland=200\nforceFontDPI=144\n"

        assertEquals(1.5f, linuxTextScale(settings)())
    }

    /**
     * Plasma's global scale on X11 is written into the font DPI too. It is a display scale,
     * not a text size, so it is taken back out: a scale of 2 forces 192, which is text at its
     * default size.
     */
    @Test
    fun fr43_kde_global_scale_is_not_counted_as_a_text_size() {
        environment["XDG_CURRENT_DESKTOP"] = "KDE"
        files["/home/reader/.config/kcmfonts"] = "[General]\nforceFontDPI=192\n"
        files["/home/reader/.config/kdeglobals"] = "[General]\nfont=Noto Sans\n\n[KScreen]\nScaleFactor=2\n"

        assertEquals(1f, linuxTextScale(settings)())

        // A larger font DPI on top of the scale is the reader's text size.
        files["/home/reader/.config/kcmfonts"] = "[General]\nforceFontDPI=240\n"
        assertEquals(1.25f, linuxTextScale(settings)())
    }

    /** The same factor comes off a DPI the resource database carries in a KDE session. */
    @Test
    fun fr43_kde_global_scale_comes_off_the_resource_database_too() {
        environment["KDE_FULL_SESSION"] = "true"
        files["/home/reader/.config/kdeglobals"] = "[KScreen]\nScaleFactor=1.5\n"
        resources = "Xft.dpi:\t144\n"

        assertEquals(1f, linuxTextScale(settings)())
    }

    @Test
    fun kde_global_scale_is_read_only_from_its_own_group() {
        assertNull(kdeConfigGlobalScale("[General]\nScaleFactor=2\n"))
        assertEquals(2f, kdeConfigGlobalScale("[General]\nx=1\n[KScreen]\nScaleFactor=2\n"))
        assertNull(kdeConfigGlobalScale("[KScreen]\nScaleFactor=0\n"))
    }

    @Test
    fun fr43_kde_files_outside_a_kde_session_are_not_read() {
        files["/home/reader/.config/kcmfonts"] = "[General]\nforceFontDPI=192\n"

        assertEquals(1f, linuxTextScale(settings)())
    }

    @Test
    fun fr43_the_resource_database_answers_where_no_settings_manager_runs() {
        resources = "Xcursor.size:\t24\n*Xft.dpi:\t120\nXft.antialias:\t1\n"

        assertEquals(1.25f, linuxTextScale(settings)())
    }

    @Test
    fun fr43_nothing_published_is_the_default_size() {
        assertEquals(1f, linuxTextScale(settings)())
    }

    /**
     * A change the desktop announces reaches the window without a restart, and nothing is
     * read again until it is announced.
     */
    @Test
    fun fr43_a_changed_setting_is_read_when_the_desktop_announces_it() {
        xsettings = xsettingsBytes(littleEndian = true, "Gdk/UnscaledDPI" to 96 * 1024)
        val scale = Zoom(linuxTextScale(settings))
        assertEquals(1f, scale.os)

        // The reader moves GNOME's slider. The property changes, and so does the serial.
        xsettings = xsettingsBytes(littleEndian = true, "Gdk/UnscaledDPI" to 96 * 1024 * 3 / 2)
        repeat(3) { scale.refresh() }
        assertEquals(1f, scale.os, "not read again until the desktop says so")
        val readsBefore = reads

        serial++
        assertTrue(scale.refresh())
        assertEquals(1.5f, scale.os)
        repeat(3) { scale.refresh() }
        assertEquals(readsBefore + 1, reads, "read once per announced change, not once a frame")
    }

    @Test
    fun xsettings_are_read_in_either_byte_order_past_strings_and_colours() {
        for (littleEndian in listOf(true, false)) {
            val parsed = parseXSettingsIntegers(
                xsettingsBytes(
                    littleEndian,
                    "Net/ThemeName" to "Yaru-dark",
                    "Gtk/CursorThemeSize" to 24,
                    "Gtk/Color" to Colour,
                    "Xft/DPI" to 98304,
                ),
            )
            assertEquals(mapOf("Gtk/CursorThemeSize" to 24, "Xft/DPI" to 98304), parsed)
        }
    }

    @Test
    fun xsettings_cut_short_answer_what_came_before_the_cut() {
        val whole = xsettingsBytes(true, "Xft/DPI" to 98304, "Gdk/UnscaledDPI" to 98304)

        val parsed = parseXSettingsIntegers(whole.copyOf(whole.size - 2))

        assertEquals(mapOf("Xft/DPI" to 98304), parsed)
        assertEquals(emptyMap(), parseXSettingsIntegers(ByteArray(3)))
    }

    @Test
    fun kde_and_resource_entries_are_read_by_their_exact_names() {
        assertNull(kdeConfigFontDpi("forceFontDPIWayland=150\n"))
        assertNull(kdeConfigFontDpi("forceFontDPI=0\n"))
        assertEquals(110f, kdeConfigFontDpi("[General]\n forceFontDPI = 110 \n"))
        assertNull(resourceFontDpi("Xft.dpiX:\t96\n"))
        assertEquals(96f, resourceFontDpi("Xft.dpi:\t96"))
    }

    /** A colour setting, which carries four 16-bit channels. */
    private object Colour

    private fun xsettingsBytes(littleEndian: Boolean, vararg entries: Pair<String, Any>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun card8(value: Int) = out.write(value and 0xFF)
        fun card16(value: Int) {
            if (littleEndian) {
                card8(value)
                card8(value shr 8)
            } else {
                card8(value shr 8)
                card8(value)
            }
        }
        fun card32(value: Int) {
            val bytes = (0 until 4).map { (value shr (8 * it)) and 0xFF }
            (if (littleEndian) bytes else bytes.reversed()).forEach(::card8)
        }
        fun string(bytes: ByteArray) {
            out.write(bytes)
            repeat((4 - bytes.size % 4) % 4) { card8(0) }
        }
        card8(if (littleEndian) 0 else 1)
        repeat(3) { card8(0) }
        card32(7)
        card32(entries.size)
        for ((name, value) in entries) {
            val nameBytes = name.encodeToByteArray()
            card8(
                when (value) {
                    is Int -> 0
                    is String -> 1
                    else -> 2
                },
            )
            card8(0)
            card16(nameBytes.size)
            string(nameBytes)
            card32(3)
            when (value) {
                is Int -> card32(value)
                is String -> {
                    val bytes = value.encodeToByteArray()
                    card32(bytes.size)
                    string(bytes)
                }
                else -> repeat(4) { card16(0x7FFF) }
            }
        }
        return out.toByteArray()
    }
}

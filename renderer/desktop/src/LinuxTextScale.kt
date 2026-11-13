package dev.darkpyonix.composerust.ui.platform

// Where a Linux desktop keeps the reader's text size, and how to read it.
//
// There is no one place. GNOME keeps a text scaling factor in GSettings, KDE keeps a font DPI
// in its configuration, and both, along with most other desktops, publish what they decided
// to X clients: GNOME's settings daemon announces it over XSETTINGS, and KDE writes it into
// the X resource database. This window is an X client (XWayland on a Wayland desktop), so
// that is the channel it reads, and it is the one that says when the value changes: the
// XSETTINGS owner's property and the root window's resources are both properties, and a
// changed property is an event.
//
// What is read where is the window's business and differs between the native image, which
// reaches Xlib through C, and the Kotlin/Native window, which reaches it directly. What the
// bytes mean, and which answer wins, is written once here and shared by both through a
// symlink.

/**
 * What a Linux window can find out about the reader's text size.
 *
 * Each is asked only after [serial] has changed, so none of them has to be cheap.
 *
 * @property serial A number that changes whenever any of the others may have. The window's
 *   own X connection counts the property changes that matter.
 * @property xsettings The XSETTINGS manager's `_XSETTINGS_SETTINGS` property, raw, or null
 *   where no settings manager is running.
 * @property resources The root window's `RESOURCE_MANAGER` property, or null where nothing
 *   has loaded a resource database.
 * @property readFile A whole text file, or null where it cannot be read.
 * @property environment An environment variable, or null where it is not set.
 */
class LinuxTextSettings(
    val serial: () -> Int,
    val xsettings: () -> ByteArray?,
    val resources: () -> String?,
    val readFile: (String) -> String?,
    val environment: (String) -> String?,
)

/**
 * The reader's text scale on Linux, read again whenever the desktop says it may have changed.
 *
 * The function every Linux window gets its font scale from. Cheap to call once a frame:
 * until the serial moves it answers what it answered last time.
 */
fun linuxTextScale(settings: LinuxTextSettings): () -> Float {
    var seen: Int? = null
    var scale = 1f
    return {
        val now = settings.serial()
        if (now != seen) {
            seen = now
            scale = resolveLinuxTextScale(settings)
        }
        scale
    }
}

/**
 * Which of the places a desktop may have put the text size wins, and what it says.
 *
 * In this order:
 * 1. KDE's forced font DPI, in a KDE session, relative to the 96 a desktop assumes. KDE's own
 *    applications read it from the same files.
 * 2. GNOME's text scaling factor, which its settings daemon announces as `Gdk/UnscaledDPI`:
 *    96 times the factor, in 1024ths. Unscaled, so the display's own scale is not counted a
 *    second time as a text size.
 * 3. `Xft/DPI` from whichever settings manager is running, the same way.
 * 4. `Xft.dpi` from the X resource database, which is where a desktop with no settings
 *    manager, and KDE's settings module on apply, put the font DPI.
 *
 * **KDE's global scale is not a text size, and is taken back out.** On X11, setting
 * Plasma's global scale writes it into the font DPI as well: a scale of 2 forces a font DPI
 * of 192 and records `ScaleFactor=2` under `[KScreen]` in `kdeglobals`. Read as it stands,
 * that DPI would draw text twice the size inside a layout drawn at the original size. So in
 * a KDE session every DPI is divided by that factor first, and what is left is the text
 * size the reader chose on top of the scale: 192 at a scale of 2 is 1, and 240 at a scale
 * of 2 is 1.25. Where the two cannot be told apart (a session that records no factor) the
 * factor is taken to be 1, which is the setting KDE writes when there is no global scale.
 *
 * Nothing found is the default size.
 */
internal fun resolveLinuxTextScale(settings: LinuxTextSettings): Float {
    val kde = isKdeSession(settings.environment)
    // One where this is not KDE, or KDE records no global scale.
    val globalScale = if (kde) kdeGlobalScale(settings) else 1f
    val dpi = run dpi@{
        if (kde) kdeForcedFontDpi(settings)?.let { return@dpi it }
        val announced = settings.xsettings()?.let(::parseXSettingsIntegers).orEmpty()
        announced["Gdk/UnscaledDPI"]?.takeIf { it > 0 }?.let { return@dpi it / XSETTINGS_DPI_UNIT }
        announced["Xft/DPI"]?.takeIf { it > 0 }?.let { return@dpi it / XSETTINGS_DPI_UNIT }
        settings.resources()?.let(::resourceFontDpi)
    } ?: return 1f
    return dpi / (BASE_DPI * globalScale)
}

private fun isKdeSession(environment: (String) -> String?): Boolean =
    environment("KDE_FULL_SESSION") != null ||
        environment("XDG_CURRENT_DESKTOP")?.split(':')?.any { it.equals("KDE", ignoreCase = true) } == true

private fun kdeConfigHome(settings: LinuxTextSettings): String? =
    settings.environment("XDG_CONFIG_HOME")?.takeIf { it.isNotEmpty() }
        ?: settings.environment("HOME")?.let { "$it/.config" }

/**
 * KDE's forced font DPI, from the files KDE's font settings write.
 *
 * `kcmfonts` is where Plasma 5 keeps it and `kdeglobals` is where later versions do; both
 * are read, in that order, because a machine upgraded from one to the other can have both.
 * Zero is KDE's own way of saying the DPI is not forced.
 */
private fun kdeForcedFontDpi(settings: LinuxTextSettings): Float? {
    val configHome = kdeConfigHome(settings) ?: return null
    for (name in KDE_FONT_FILES) {
        val text = settings.readFile("$configHome/$name") ?: continue
        kdeConfigFontDpi(text)?.let { return it }
    }
    return null
}

/** Plasma's global scale, `ScaleFactor` under `[KScreen]` in `kdeglobals`, or 1 where none. */
private fun kdeGlobalScale(settings: LinuxTextSettings): Float {
    val configHome = kdeConfigHome(settings) ?: return 1f
    val text = settings.readFile("$configHome/kdeglobals") ?: return 1f
    return kdeConfigGlobalScale(text) ?: 1f
}

private val KDE_FONT_FILES = listOf("kcmfonts", "kdeglobals")

/** The `ScaleFactor` entry of the `[KScreen]` group, where it is a scale at all. */
internal fun kdeConfigGlobalScale(text: String): Float? {
    var group = ""
    for (line in text.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            group = trimmed.substring(1, trimmed.length - 1)
            continue
        }
        if (group != "KScreen") continue
        val separator = trimmed.indexOf('=')
        if (separator < 0 || trimmed.substring(0, separator).trim() != "ScaleFactor") continue
        val value = trimmed.substring(separator + 1).trim().toFloatOrNull() ?: continue
        if (value > 0f) return value
    }
    return null
}

/** The `forceFontDPI` entry of a KDE configuration file, where it forces anything. */
internal fun kdeConfigFontDpi(text: String): Float? {
    for (line in text.lineSequence()) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("forceFontDPI")) continue
        val separator = trimmed.indexOf('=')
        if (separator < 0) continue
        // `forceFontDPI` and not `forceFontDPIWayland` or any other key it begins.
        if (trimmed.substring(0, separator).trim() != "forceFontDPI") continue
        val value = trimmed.substring(separator + 1).trim().toFloatOrNull() ?: continue
        if (value > 0f) return value
    }
    return null
}

/** The `Xft.dpi` entry of an X resource database, as `xrdb -query` would print it. */
internal fun resourceFontDpi(text: String): Float? {
    for (line in text.lineSequence()) {
        val separator = line.indexOf(':')
        if (separator < 0) continue
        val name = line.substring(0, separator).trim().removePrefix("*")
        if (name != "Xft.dpi") continue
        val value = line.substring(separator + 1).trim().toFloatOrNull() ?: continue
        if (value > 0f) return value
    }
    return null
}

/**
 * The integer settings in an `_XSETTINGS_SETTINGS` property, by name.
 *
 * The layout is the XSETTINGS specification's: a byte order, a serial and a count, then each
 * setting as a type, a name padded to four bytes, the serial it last changed at, and a value
 * whose size depends on the type. Strings and colours are stepped over, because nothing here
 * needs them. A property cut short ends the reading rather than the process: what was read
 * before the cut is still answered.
 */
internal fun parseXSettingsIntegers(bytes: ByteArray): Map<String, Int> {
    val found = HashMap<String, Int>()
    if (bytes.size < XSETTINGS_HEADER) return found
    val bigEndian = bytes[0].toInt() == XSETTINGS_MSB_FIRST
    fun card16(at: Int): Int {
        val first = bytes[at].toInt() and 0xFF
        val second = bytes[at + 1].toInt() and 0xFF
        return if (bigEndian) (first shl 8) or second else (second shl 8) or first
    }
    fun card32(at: Int): Int {
        var value = 0
        for (index in 0 until 4) {
            val byte = bytes[at + if (bigEndian) index else 3 - index].toInt() and 0xFF
            value = (value shl 8) or byte
        }
        return value
    }
    val count = card32(8)
    var at = XSETTINGS_HEADER
    for (setting in 0 until count) {
        if (at + 4 > bytes.size) break
        val type = bytes[at].toInt() and 0xFF
        val nameLength = card16(at + 2)
        val nameStart = at + 4
        val afterName = nameStart + padded(nameLength)
        // The name, then the serial it last changed at.
        if (afterName + 4 > bytes.size) break
        val name = bytes.decodeToString(nameStart, nameStart + nameLength)
        at = afterName + 4
        when (type) {
            XSETTINGS_INTEGER -> {
                if (at + 4 > bytes.size) break
                found[name] = card32(at)
                at += 4
            }
            XSETTINGS_STRING -> {
                if (at + 4 > bytes.size) break
                val length = card32(at)
                if (length < 0) break
                at += 4 + padded(length)
            }
            XSETTINGS_COLOUR -> at += 8
            else -> break
        }
    }
    return found
}

private fun padded(length: Int): Int = (length + 3) and 3.inv()

/** The DPI a desktop treats as text at its default size. */
private const val BASE_DPI = 96f

/** XSETTINGS carries a DPI in 1024ths. */
private const val XSETTINGS_DPI_UNIT = 1024f

private const val XSETTINGS_HEADER = 12
private const val XSETTINGS_MSB_FIRST = 1
private const val XSETTINGS_INTEGER = 0
private const val XSETTINGS_STRING = 1
private const val XSETTINGS_COLOUR = 2

package dioxus.compose.design

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle

/**
 * The typeface this machine writes its interfaces in.
 *
 * Not the design system's choice, and not the Host's. A window reads as belonging to the
 * machine it is on mostly because its letters look like every other window's, and that is
 * a fact about the machine rather than an axis a design language gets to move. A Material
 * 3 screen on a Mac is still on a Mac.
 *
 * Found by name among what is installed, never bundled: nothing is added to the
 * distribution and no licence follows the binary. Where none of the names is present the
 * toolkit's own default is used, which is what every screen used before this.
 *
 * It mattered more than it sounds. Until this existed every one of the seven design
 * systems drew in Helvetica, because that is what the toolkit's default resolves to on
 * macOS, and the screens read as pictures of an interface rather than as one. The letters
 * were never blurred: measured against the system's own text in the same screenshot, a
 * glyph edge crossed 1.10 pixels here against 1.27 there. They were simply somebody
 * else's letters.
 */
/**
 * Finds the face and hands it to the shared table. Called once, before the first frame.
 *
 * Desktop only, and that is the point of the file: it asks a font manager and reads
 * system properties, neither of which a browser or a Kotlin/Native target has.
 */
internal fun installPlatformUiFamily() {
    platformUiFamily = resolvePlatformUiFamily()
}

/** The name that was found, or null where none was and the toolkit's default is in use. */
internal var platformUiFamilyName: String? = null
    private set

/**
 * The names to try, most specific first.
 *
 * Each list is the platform's own UI face followed by what stands in for it on machines
 * that do not have it: an older release, or the face a distribution ships instead.
 */
internal fun platformUiFamilyNames(osName: String, desktop: String = ""): List<String> = when {
    osName.startsWith("Mac") -> listOf(".AppleSystemUIFont", "SF Pro Text", "SF Pro", "Helvetica Neue")
    osName.startsWith("Windows") -> listOf("Segoe UI Variable Text", "Segoe UI Variable", "Segoe UI")
    desktop.contains("KDE", ignoreCase = true) -> listOf("Noto Sans", "DejaVu Sans")
    else -> listOf("Adwaita Sans", "Cantarell", "Noto Sans", "DejaVu Sans")
}

private fun resolvePlatformUiFamily(): FontFamily {
    val names = platformUiFamilyNames(
        System.getProperty("os.name").orEmpty(),
        System.getenv("XDG_CURRENT_DESKTOP") ?: System.getenv("DESKTOP_SESSION") ?: "",
    )
    val manager = FontMgr.default
    for (name in names) {
        val face = manager.matchFamilyStyle(name, FontStyle.NORMAL) ?: continue
        // Asking for a name that is not installed can still answer with a substitute, so
        // the face is asked what it actually is rather than trusted to be what was asked
        // for. A substitute is the toolkit's default by another route.
        if (!face.familyName.equals(name, ignoreCase = true) &&
            !name.startsWith(".")
        ) {
            continue
        }
        platformUiFamilyName = face.familyName
        return FontFamily(Typeface(face))
    }
    return FontFamily.Default
}

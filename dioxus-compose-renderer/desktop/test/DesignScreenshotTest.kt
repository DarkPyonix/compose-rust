package dioxus.compose.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Theme
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.LocalWindowActions
import dioxus.compose.runtime.WindowActions
import dioxus.compose.runtime.WindowCaption
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.designShowcaseHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.tooling.overlayShowcaseHost
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.protocol.Modifier as ProtocolModifier

/**
 * The scene the showcase is rendered into, tall enough for the whole of it.
 *
 * The point of these images is comparing the systems by eye, and a window that cuts the
 * bottom off hides exactly the controls that were added last.
 */
private const val SHOWCASE_WIDTH = 520
private const val SHOWCASE_HEIGHT = 2100

/** A window wide enough that a caption strip has somewhere to put three buttons. */
private const val CAPTION_WIDTH = 520
private const val CAPTION_HEIGHT = 200

/** The strip a window with modern chrome leaves at its top, as macOS reports it. */
private val CAPTION = WindowCaption(height = 38.dp, buttonsWidth = 78.dp)

/**
 * `Column { TopAppBar { Text } Text }` under a named system: a window that opens with a
 * bar, which is the case where the bar takes the caption.
 */
private fun captionTree(system: DesignSystem, scheme: ColorScheme) = listOf(
    Mutation.SetTheme(Theme(system, system, scheme, false)),
    Mutation.Create(1, WidgetKind.Column),
    Mutation.SetModifier(1, 0, ProtocolModifier.FillMaxWidth),
    Mutation.Create(2, WidgetKind.TopAppBar),
    Mutation.SetModifier(2, 0, ProtocolModifier.FillMaxWidth),
    Mutation.Create(3, WidgetKind.Text),
    Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("Inbox")),
    Mutation.Insert(2, 3, 0),
    Mutation.Insert(1, 2, 0),
    Mutation.Create(4, WidgetKind.Text),
    Mutation.SetProp(4, PropertyKind.Text, PropertyValue.Text("The page under the bar.")),
    Mutation.Insert(1, 4, 1),
)

/** Room for a dialog to sit centred with the page showing around it. */
private const val OVERLAY_WIDTH = 520
private const val OVERLAY_HEIGHT = 420

/**
 * Renders the design showcase for each system and writes a PNG, so the systems can be
 * compared by eye. Whether they really look different is not something an assertion
 * settles. Enabled only when `DXC_SCREENSHOT_DIR` is set, because it writes files.
 */
@OptIn(ExperimentalTestApi::class)
class DesignScreenshotTest {
    @Test
    fun fr14_showcase_screenshots() {
        val directory = System.getenv("DXC_SCREENSHOT_DIR") ?: return
        File(directory).mkdirs()
        DesignSystem.entries.forEach { system ->
            listOf(ColorScheme.Light, ColorScheme.Dark).forEach { scheme ->
                // The scene's own size is what bounds the capture, so a Box cannot make
                // room: anything past the bottom of the window is simply not in the image.
                runDesktopComposeUiTest(SHOWCASE_WIDTH, SHOWCASE_HEIGHT) {
                    setContent {
                        DioxusContent(
                            rememberDioxusHost(
                                designShowcaseHost(Theme(system, system, scheme, false)),
                            ),
                        )
                    }
                    val bitmap = onRoot().captureToImage().asSkiaBitmap()
                    val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)
                        ?: error("PNG encoding failed")
                    File(directory, "${system.name}-${scheme.name}.png").writeBytes(data.bytes)
                }
            }
        }
    }

    /**
     * The window's own caption under each system, which is the one surface that never
     * appears in the showcase next door.
     *
     * The bar takes the caption here, so what these images show is the whole of that
     * arrangement at once: the bar's surface covering the strip rather than starting
     * below it, its title on the same line as the window buttons, and each system's own
     * three buttons at the end its language puts them.
     */
    @Test
    fun fr19_caption_screenshots() {
        val directory = System.getenv("DXC_SCREENSHOT_DIR") ?: return
        File(directory).mkdirs()
        DesignSystem.entries.forEach { system ->
            listOf(ColorScheme.Light, ColorScheme.Dark).forEach { scheme ->
                runDesktopComposeUiTest(CAPTION_WIDTH, CAPTION_HEIGHT) {
                    setContent {
                        CompositionLocalProvider(
                            LocalWindowActions provides WindowActions({}, {}, {}),
                        ) {
                            DioxusContent(
                                rememberDioxusHost(FakeHostConnection(captionTree(system, scheme))),
                                caption = CAPTION,
                            )
                        }
                    }
                    waitForIdle()
                    val bitmap = onRoot().captureToImage().asSkiaBitmap()
                    val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)
                        ?: error("PNG encoding failed")
                    File(directory, "caption-${system.name}-${scheme.name}.png").writeBytes(data.bytes)
                }
            }
        }
    }

    /**
     * The same for the two things that are drawn over a window rather than in it.
     *
     * A dialog and a menu were the last surfaces here that had never been on screen under
     * any system, because putting either one in the showcase next door covers the whole of
     * it. They get their own scene, one per overlay per system.
     */
    @Test
    fun fr14_overlay_screenshots() {
        val directory = System.getenv("DXC_SCREENSHOT_DIR") ?: return
        File(directory).mkdirs()
        DesignSystem.entries.forEach { system ->
            listOf("dialog" to true, "menu" to false).forEach { (name, dialog) ->
                runDesktopComposeUiTest(OVERLAY_WIDTH, OVERLAY_HEIGHT) {
                    setContent {
                        DioxusContent(
                            rememberDioxusHost(
                                overlayShowcaseHost(
                                    Theme(system, system, ColorScheme.Light, false),
                                    dialog,
                                ),
                            ),
                        )
                    }
                    waitForIdle()
                    // An overlay is its own root: the page is one and the popup laid over
                    // it is another, and the popup is the one with the thing in it.
                    val roots = onAllNodes(isRoot())
                    val bitmap = roots[roots.fetchSemanticsNodes().size - 1]
                        .captureToImage()
                        .asSkiaBitmap()
                    val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)
                        ?: error("PNG encoding failed")
                    File(directory, "overlay-${system.name}-$name.png").writeBytes(data.bytes)
                }
            }
        }
    }
}

package dioxus.compose.test

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Theme
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.navigationShowcaseHost
import dioxus.compose.ui.platform.FrameRequestSource
import dioxus.compose.ui.platform.LocalFrameRequests

/**
 * Writes the same navigation at the three widths, so the claim that one declaration looks
 * native at each of them can be looked at rather than only asserted on.
 *
 * Enabled only when `DXC_SCREENSHOT_DIR` is set, because it writes files.
 */
@OptIn(ExperimentalTestApi::class)
class NavigationScreenshotTest {
    private val frames = FrameRequestSource()

    @Test
    fun fr21_navigation_screenshots_at_every_width() {
        val directory = System.getenv("DXC_SCREENSHOT_DIR") ?: return
        File(directory).mkdirs()
        val widths = listOf("compact" to 500, "medium" to 700, "expanded" to 1100)
        // Every system, not the three that were here when this was written. A design
        // system left out of the one place its navigation can be looked at is a design
        // system nobody looks at, which is how three of them came to share one strip.
        DesignSystem.entries.forEach { system ->
            widths.forEach { (name, widthDp) ->
                runComposeUiTest {
                    setContent {
                        CompositionLocalProvider(
                            LocalFrameRequests provides frames,
                            LocalDensity provides Density(1f),
                        ) {
                            Box(Modifier.size(widthDp.dp, 760.dp)) {
                                DioxusContent(
                                    rememberDioxusHost(
                                        navigationShowcaseHost(
                                            Theme(system, system, ColorScheme.Light, false),
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                    waitForIdle()
                    val bitmap = onRoot().captureToImage().asSkiaBitmap()
                    val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)
                        ?: error("PNG encoding failed")
                    File(directory, "navigation-${system.name}-$name.png").writeBytes(data.bytes)
                }
            }
        }
    }
}

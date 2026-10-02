package dioxus.compose.test

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Protocol
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.platform.FrameRequestSource
import dioxus.compose.ui.platform.LocalFrameRequests
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Draws a real sample's screen, once per design system and once per window width, and
 * writes a PNG beside the recording it came from.
 *
 * The showcase next door is a script of primitives written to exercise the token tables.
 * A sample is an application laid out the way someone would really lay one out, and that
 * is where a token turns out to be wrong in a way no ladder of swatches shows: a readout
 * whose fill matches the page behind it is drawn full size, in the right colour, and
 * cannot be seen. Six systems that nobody has looked at under a real screen is six
 * chances at that, and three widths each is where a layout that is right on a phone and
 * broken on a desktop stops hiding.
 *
 * The frames are the bytes the Host encoded, written by the samples' own `DXC_FRAME_DIR`
 * runs, so what gets drawn here is what an application would send and not a Kotlin
 * restatement of it. A screen can take more than one batch to build, a list holds no rows
 * until something asks for a window, so a file is a sequence of batches, each behind four
 * little endian bytes of its length.
 *
 * The window is not chosen here. The Host laid the screen out for a particular size and
 * said so in the file's name, so drawing it at any other size would photograph a layout
 * that never existed. Density is pinned at one, so a dp in the name is a pixel in the
 * picture.
 *
 * Enabled only when `DXC_FRAME_DIR` names a directory holding those recordings, because it
 * reads and writes files.
 */
@OptIn(ExperimentalTestApi::class)
class SampleScreenshotTest {
    private val frameRequests = FrameRequestSource()

    /** The `<width>x<height>` the recorder put at the end of the name, in dp. */
    private fun windowOf(name: String): Pair<Int, Int> {
        val size = name.substringAfterLast('-')
        val (width, height) = size.split('x', limit = 2)
        return width.toInt() to height.toInt()
    }

    @Test
    fun fr14_sample_screenshots() {
        val directory = System.getenv("DXC_FRAME_DIR")?.let(::File) ?: return
        val only = System.getenv("DXC_FRAME_FILTER").orEmpty()
        val frames = directory.listFiles { file -> file.extension == "bin" }
            ?.filter { it.name.contains(only) }
            ?.sorted()
            .orEmpty()
        check(frames.isNotEmpty()) {
            "${directory.absolutePath} holds no recordings. Write them first with " +
                "`DXC_FRAME_DIR=${directory.absolutePath} cargo test --workspace is_recorded`."
        }
        frames.forEach { frame ->
            val mutations = mutableListOf<Mutation>()
            val file = ByteBuffer.wrap(frame.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            while (file.remaining() >= Int.SIZE_BYTES) {
                val length = file.int
                check(length in 0..file.remaining()) {
                    "${frame.name} claims a batch of $length bytes with ${file.remaining()} left"
                }
                val batch = file.slice().order(ByteOrder.LITTLE_ENDIAN).limit(length)
                Protocol.decode(batch, mutations::add)
                file.position(file.position() + length)
            }
            check(mutations.isNotEmpty()) { "${frame.name} decoded to no records" }
            val (widthDp, heightDp) = windowOf(frame.nameWithoutExtension)
            // The window is the recording's, not the test harness's default. A root left
            // at 1024 by 768 silently clips anything wider, and the picture then shows a
            // layout the Host never laid out.
            runDesktopComposeUiTest(widthDp, heightDp) {
                setContent {
                    CompositionLocalProvider(
                        LocalFrameRequests provides frameRequests,
                        LocalDensity provides Density(1f),
                    ) {
                        Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                            DioxusContent(rememberDioxusHost(FakeHostConnection(mutations)))
                        }
                    }
                }
                waitForIdle()
                // The whole scene rather than a root. A sheet is a Popup, which is a root
                // of its own, so a recording with one open has two and asking for "the"
                // root fails. The scene is what a person would have seen.
                val bitmap = captureToImage().asSkiaBitmap()
                check(bitmap.width == widthDp && bitmap.height == heightDp) {
                    "${frame.name} was drawn ${bitmap.width} by ${bitmap.height} instead of " +
                        "$widthDp by $heightDp, so the picture is not of the window the Host " +
                        "was told about"
                }
                val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)
                    ?: error("${frame.name} could not be encoded as a PNG")
                File(directory, "${frame.nameWithoutExtension}.png").writeBytes(data.bytes)
            }
        }
    }
}

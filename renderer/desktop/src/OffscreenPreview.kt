package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Protocol
import dev.darkpyonix.composerust.runtime.WindowContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo

/** Decoded RGBA 8888 frame rasterized offscreen on the CPU via Skia. */
data class RgbaFrame(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
)

/**
 * Renders a binary wire-format mutation batch through the real Compose Multiplatform
 * widget interpreter onto a headless Skia CPU raster surface (no window, GPU, or display).
 */
object OffscreenPreview {
    fun decodeMutations(batchBytes: ByteArray): List<Mutation> {
        val out = mutableListOf<Mutation>()
        if (batchBytes.isNotEmpty()) {
            val buf = ByteBuffer.wrap(batchBytes).order(ByteOrder.LITTLE_ENDIAN)
            Protocol.decode(buf) { out += it }
        }
        return out
    }

    fun renderMutationsToRgba(
        mutations: List<Mutation>,
        widthDp: Int,
        heightDp: Int,
        scale: Float = 1f,
        atMs: Long = 0L,
    ): RgbaFrame {
        System.setProperty("java.awt.headless", "true")
        val safeScale = if (scale.isFinite() && scale > 0f) scale else 1f
        val wPx = (widthDp * safeScale).roundToInt().coerceAtLeast(1)
        val hPx = (heightDp * safeScale).roundToInt().coerceAtLeast(1)
        val frames = FrameRequestSource()
        val scene = ImageComposeScene(
            width = wPx,
            height = hPx,
            density = Density(safeScale),
            content = {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    WindowContent(rememberComposeRustHost(FakeHostConnection(mutations)))
                }
            },
        )
        return try {
            // Compose initial frame, then render at the requested virtual clock time.
            val image = if (atMs > 0L) {
                scene.render(0L)
                scene.render(atMs * 1_000_000L)
            } else {
                scene.render(0L)
            }
            val bitmap = Bitmap()
            bitmap.allocPixels(ImageInfo(wPx, hPx, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
            check(image.readPixels(bitmap, 0, 0)) { "Skia readPixels failed" }
            val pixels = requireNotNull(bitmap.readPixels()) { "Skia bitmap pixels unavailable" }
            RgbaFrame(width = wPx, height = hPx, pixels = pixels)
        } finally {
            scene.close()
        }
    }

    fun renderBatchToRgba(
        batchBytes: ByteArray,
        widthDp: Int,
        heightDp: Int,
        scale: Float = 1f,
        atMs: Long = 0L,
    ): RgbaFrame = renderMutationsToRgba(decodeMutations(batchBytes), widthDp, heightDp, scale, atMs)

    fun renderBatchToPng(
        batchBytes: ByteArray,
        widthDp: Int,
        heightDp: Int,
        scale: Float = 1f,
        atMs: Long = 0L,
    ): ByteArray {
        System.setProperty("java.awt.headless", "true")
        val safeScale = if (scale.isFinite() && scale > 0f) scale else 1f
        val wPx = (widthDp * safeScale).roundToInt().coerceAtLeast(1)
        val hPx = (heightDp * safeScale).roundToInt().coerceAtLeast(1)
        val mutations = decodeMutations(batchBytes)
        val frames = FrameRequestSource()
        val scene = ImageComposeScene(
            width = wPx,
            height = hPx,
            density = Density(safeScale),
            content = {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    WindowContent(rememberComposeRustHost(FakeHostConnection(mutations)))
                }
            },
        )
        return try {
            val image = if (atMs > 0L) {
                scene.render(0L)
                scene.render(atMs * 1_000_000L)
            } else {
                scene.render(0L)
            }
            val data = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)) {
                "Skia PNG encoding failed"
            }
            data.bytes
        } finally {
            scene.close()
        }
    }
}

private fun renderOne(batchPath: String, widthDp: Int, heightDp: Int, scale: Float, atMs: Long, outPath: String) {
    val batchBytes = File(batchPath).readBytes()
    val outFile = File(outPath)
    outFile.parentFile?.mkdirs()
    if (outPath.endsWith(".png", ignoreCase = true)) {
        outFile.writeBytes(OffscreenPreview.renderBatchToPng(batchBytes, widthDp, heightDp, scale, atMs))
    } else {
        val frame = OffscreenPreview.renderBatchToRgba(batchBytes, widthDp, heightDp, scale, atMs)
        outFile.writeBytes(frame.pixels)
    }
}

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    if (args.contains("--daemon")) {
        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = reader.readLine() ?: break
            val parts = line.split('\t')
            if (parts.size < 6) {
                println("ERR expected 6 tab-separated fields")
                System.out.flush()
                continue
            }
            try {
                val t0 = System.nanoTime()
                renderOne(
                    batchPath = parts[0],
                    widthDp = parts[1].toInt(),
                    heightDp = parts[2].toInt(),
                    scale = parts[3].toFloat(),
                    atMs = parts[4].toLong(),
                    outPath = parts[5],
                )
                val ms = (System.nanoTime() - t0) / 1_000_000L
                println("OK $ms")
            } catch (t: Throwable) {
                println("ERR ${t.message ?: t.javaClass.simpleName}")
            }
            System.out.flush()
        }
        return
    }

    var batch: String? = null
    var width = 1440
    var height = 900
    var scale = 1f
    var atMs = 0L
    var out: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--batch" -> batch = args[++i]
            "--width" -> width = args[++i].toInt()
            "--height" -> height = args[++i].toInt()
            "--scale" -> scale = args[++i].toFloat()
            "--at-ms" -> atMs = args[++i].toLong()
            "--out" -> out = args[++i]
            else -> error("unknown flag: ${args[i]}")
        }
        i++
    }
    renderOne(
        batchPath = requireNotNull(batch) { "--batch is required" },
        widthDp = width,
        heightDp = height,
        scale = scale,
        atMs = atMs,
        outPath = requireNotNull(out) { "--out is required" },
    )
}

package dev.darkpyonix.composerust.test

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.ui.node.SvgDocument
import dev.darkpyonix.composerust.ui.node.decodeVectorAsset
import dev.darkpyonix.composerust.ui.node.drawSvgDocument
import dev.darkpyonix.composerust.ui.node.drawVectorDocument
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The SVG reader the renderer carries for a graphics stack that has none of its own.
 *
 * Android draws through its own graphics stack, which has no SVG parser, so the renderer
 * brings one. The desktop draws the same files through Skia's SVG document, and the two have
 * to agree: an application ships one picture and expects it to look the same on a phone as
 * on a laptop. Skia's drawing is the reference here because it is the one the desktop and iOS
 * already show, and every picture is drawn by both at the same sizes and compared pixel by
 * pixel.
 *
 * Tolerance: a pixel differs when any premultiplied channel is more than 64 out of 255 apart,
 * and at most 1.5% of a picture's pixels may differ. Edges are where the two disagree, because
 * Skia draws an arc as a conic and Compose's path reader as cubic Béziers, and its coverage
 * rounds differently, so a picture that agrees everywhere but its antialiased rims is a pass.
 * A missing shape, a wrong colour or a wrong placement each moves far more than that.
 */
class SvgDocumentTest {
    @Test
    fun fr16_svg_asset_decodes_the_same_on_every_platform() {
        val pictures = sampleSvgs() + emberStyleSvgs()
        assertTrue(pictures.size >= 26 + emberStyleSvgs().size, "found ${pictures.size} pictures")
        val failures = mutableListOf<String>()
        for ((name, bytes) in pictures) {
            val ours = SvgDocument.parse(bytes)
            if (ours == null) {
                failures += "$name: not read"
                continue
            }
            for ((width, height) in SIZES) {
                val reference = render(width, height) { drawVectorDocument(decodeVectorAsset(bytes)!!) }
                val drawn = render(width, height) { drawSvgDocument(ours) }
                val inked = inkedFraction(drawn)
                val referenceInked = inkedFraction(reference)
                val differing = differingFraction(reference, drawn)
                println(
                    "$name at ${width}x$height: ${percent(differing)} of pixels differ, " +
                        "ink ${percent(inked)} against ${percent(referenceInked)}",
                )
                if (referenceInked > 0.0 && inked == 0.0) {
                    failures += "$name at ${width}x$height drew nothing"
                } else if (differing > TOLERANCE) {
                    failures += "$name at ${width}x$height: ${percent(differing)} of pixels differ"
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /**
     * The one place this reader and Skia part ways on purpose. Skia gives an `ellipse` no
     * bounding box, so a gradient in bounding box units paints it solid black on the desktop;
     * every other shape takes the gradient. Copying that would put a black blob on a phone
     * as well, so here the ellipse takes its gradient like the rest, and the comparison above
     * keeps that one combination out.
     */
    @Test
    fun fr16_svg_a_bounding_box_gradient_on_an_ellipse_paints_the_gradient() {
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><defs>" +
            "<linearGradient id='g'><stop offset='0' stop-color='#ff0000'/>" +
            "<stop offset='1' stop-color='#0000ff'/></linearGradient></defs>" +
            "<ellipse cx='50' cy='40' rx='30' ry='20' fill='url(#g)'/></svg>"
        val document = assertNotNull(SvgDocument.parse(svg.toByteArray()))
        val drawn = render(100, 100) { drawSvgDocument(document) }
        val left = drawn[23, 40]
        val right = drawn[77, 40]
        assertTrue(left.red > 0.8f && left.blue < 0.2f, "the left end is red, not $left")
        assertTrue(right.blue > 0.8f && right.red < 0.2f, "the right end is blue, not $right")
    }

    @Test
    fun fr16_svg_every_sample_picture_draws_something() {
        for ((name, bytes) in sampleSvgs()) {
            val document = assertNotNull(SvgDocument.parse(bytes), "$name was not read")
            val drawn = render(120, 120) { drawSvgDocument(document) }
            assertTrue(inkedFraction(drawn) > 0.01, "$name drew an empty box")
        }
    }

    @Test
    fun fr16_svg_bytes_that_are_not_a_document_are_not_read() {
        assertNull(SvgDocument.parse(byteArrayOf(1, 2, 3, 4)), "four bytes are not a picture")
        assertNull(SvgDocument.parse("<html><body/></html>".toByteArray()), "no svg root")
        assertNull(SvgDocument.parse("<svg viewBox='0 0 4 4'><rect".toByteArray()), "cut off")
    }

    @Test
    fun fr16_svg_a_byte_order_mark_and_a_prologue_are_read() {
        val text = "﻿<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" " +
            "\"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">\n" +
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 8 8\">" +
            "<!-- a comment --><rect width=\"8\" height=\"8\" fill=\"#336699\"/></svg>"
        val document = assertNotNull(SvgDocument.parse(text.toByteArray()))
        val drawn = render(16, 16) { drawSvgDocument(document) }
        val corner = drawn[14, 14]
        assertTrue(abs(corner.red - 0x33 / 255f) < 0.02f && abs(corner.blue - 0x99 / 255f) < 0.02f, "$corner")
    }

    private fun render(width: Int, height: Int, draw: DrawScope.() -> Unit): PixelMap {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap),
            Size(width.toFloat(), height.toFloat()),
            draw,
        )
        return bitmap.toPixelMap()
    }

    private fun inkedFraction(pixels: PixelMap): Double {
        var inked = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].alpha > 0.1f) inked++
            }
        }
        return inked.toDouble() / (pixels.width * pixels.height)
    }

    private fun differingFraction(a: PixelMap, b: PixelMap): Double {
        var differing = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val p = a[x, y]
                val q = b[x, y]
                val delta = max(
                    max(abs(p.red * p.alpha - q.red * q.alpha), abs(p.green * p.alpha - q.green * q.alpha)),
                    max(abs(p.blue * p.alpha - q.blue * q.alpha), abs(p.alpha - q.alpha)),
                )
                if (delta > CHANNEL_TOLERANCE) differing++
            }
        }
        return differing.toDouble() / (a.width * a.height)
    }

    private fun percent(fraction: Double) = "${(fraction * 1000).toInt() / 10.0}%"

    /** Every picture the samples register, read from the files they embed. */
    private fun sampleSvgs(): List<Pair<String, ByteArray>> {
        val samples = repositoryRoot().resolve("samples")
        val files = samples.walkTopDown()
            .onEnter { it.name != "target" && it.name != "build" }
            .filter { it.isFile && it.extension == "svg" && it.parentFile.name == "assets" }
            .sortedBy { it.path }
            .toList()
        return files.map { "${it.parentFile.parentFile.name}/${it.name}" to it.readBytes() }
    }

    private fun repositoryRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (dir.resolve("samples").isDirectory && dir.resolve("renderer").isDirectory) return dir
            dir = dir.parentFile
        }
        error("no repository root above ${System.getProperty("user.dir")}")
    }

    /**
     * Pictures written the way an application generates them at run time: an icon set
     * stroked in one colour and sized in its root, a progress ring of arcs, a hatched swatch,
     * a mark clipped to an even-odd outline and filled with user-space gradients, and a
     * translucent highlight. None of them is a sample file, and each reaches a part of the
     * format the samples do not.
     */
    private fun emberStyleSvgs(): List<Pair<String, ByteArray>> = listOf(
        "icon-home" to (
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"120\" height=\"120\" " +
                "fill=\"none\" stroke=\"#56534d\" stroke-width=\"1.5\"><path stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\" d=\"m2.25 12 8.954-8.955c.44-.439 1.152-.439 1.591 0L21.75 12M4.5 " +
                "9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 " +
                "0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75M8.25 21h8.25\"/></svg>"
            ),
        // Sized in its root at fewer units than the box: both draw it at that size, in the
        // drawing area's own units, in the top left corner.
        "icon-sized-in-root" to (
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"48\" height=\"48\" " +
                "fill=\"none\" stroke=\"#151514\" stroke-width=\"1.5\"><path stroke-linecap=\"round\" " +
                "d=\"M12 4.5v15m7.5-7.5h-15\"/></svg>"
            ),
        "icon-gear" to (
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' " +
                "color='#c3bfb7' stroke-width='1.5'><g transform='rotate(90 12 12)'><path " +
                "stroke-linecap='round' stroke-linejoin='round' d='M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z" +
                "M10.343 3.94c.09-.542.56-.94 1.11-.94h1.093c.55 0 1.02.398 1.11.94l.149.894'/></g>" +
                "<path d='M6 18 18 6M6 6l12 12'/></svg>"
            ),
        "ring" to (
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100%\" height=\"100%\" viewBox=\"0 0 48 48\">" +
                "<circle cx=\"24\" cy=\"24\" r=\"20\" fill=\"none\" stroke=\"#e6e3dd\" stroke-width=\"8\"/>" +
                "<path d=\"M24.00 4.00A20 20 0 0 1 43.02 30.18\" fill=\"none\" stroke=\"#3b82f6\" stroke-width=\"8\"/>" +
                "<path d=\"M43.02 30.18A20 20 0 1 1 24.00 4.00\" fill=\"none\" stroke=\"#ef4444\" stroke-width=\"8\" " +
                "stroke-linecap=\"round\"/></svg>"
            ),
        "hatched-swatch" to (
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100%\" height=\"100%\" viewBox=\"0 0 18 10\" " +
                "fill=\"none\"><defs><pattern id=\"ws\" width=\"5\" height=\"5\" patternTransform=\"rotate(45 0 0)\" " +
                "patternUnits=\"userSpaceOnUse\"><line x1=\"0\" y1=\"0\" x2=\"0\" y2=\"5\" stroke=\"#2563eb\" " +
                "stroke-width=\"1.5\" stroke-opacity=\"0.6\"/></pattern></defs><rect x=\"0.5\" y=\"0.5\" " +
                "width=\"17\" height=\"9\" rx=\"2.5\" fill=\"url(#ws)\" stroke=\"#2563eb\" stroke-width=\"1\" " +
                "stroke-opacity=\"0.65\"/></svg>"
            ),
        "dashed-grid" to (
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100%\" height=\"100%\" viewBox=\"0 0 200 120\" " +
                "fill=\"none\"><line x1=\"40.5\" y1=\"0\" x2=\"40.5\" y2=\"120\" stroke=\"#9ca3af\" stroke-width=\"2\" " +
                "stroke-dasharray=\"6 4\"/><line x1=\"0\" y1=\"60\" x2=\"200\" y2=\"60\" stroke=\"#111827\" " +
                "stroke-width=\"3\"/><polyline points=\"10,100 60,40 110,90 190,20\" stroke=\"rgb(220, 38, 38)\" " +
                "stroke-width=\"4\" stroke-linejoin=\"bevel\"/><polygon points=\"150,110 190,110 170,80\" " +
                "fill=\"rgba(16,185,129,0.5)\"/></svg>"
            ),
        "clipped-mark" to (
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\"><defs>" +
                "<linearGradient id=\"gm\" gradientUnits=\"userSpaceOnUse\" x1=\"50\" y1=\"0\" x2=\"50\" y2=\"100\">" +
                "<stop offset=\"0\" stop-color=\"#FFD15A\"/><stop offset=\".5\" stop-color=\"#FF8A47\"/>" +
                "<stop offset=\"1\" stop-color=\"#FF6450\"/></linearGradient>" +
                "<radialGradient id=\"glow\" cx=\"50%\" cy=\"40%\" r=\"50%\"><stop offset=\"0\" " +
                "stop-color=\"#ffffff\" stop-opacity=\"0.9\"/><stop offset=\"1\" stop-color=\"#ffffff\" " +
                "stop-opacity=\"0\"/></radialGradient>" +
                "<clipPath id=\"cm\"><path clip-rule=\"evenodd\" d=\"M50 4 C80 30 96 60 50 96 C4 60 20 30 50 4Z" +
                "M50 40 C60 50 60 60 50 70 C40 60 40 50 50 40Z\"/></clipPath></defs>" +
                "<g clip-path=\"url(#cm)\"><rect x=\"-50\" y=\"-50\" width=\"200\" height=\"200\" fill=\"url(#gm)\"/>" +
                "<circle cx=\"50\" cy=\"40\" r=\"24\" fill=\"url(#glow)\"/>" +
                "<path fill=\"#FF9A4A\" fill-rule=\"evenodd\" d=\"M30 80 L70 80 L50 50 Z M45 75 L55 75 L50 65Z\"/></g>" +
                "<ellipse cx=\"30\" cy=\"20\" rx=\"10\" ry=\"6\" fill=\"#ffffff\" opacity=\"0.4\"/></svg>"
            ),
        "styled-and-used" to (
            "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
                "viewBox=\"0 0 60 40\" preserveAspectRatio=\"xMinYMin meet\"><defs><rect id=\"chip\" width=\"16\" " +
                "height=\"10\" rx=\"3\"/></defs><g style=\"fill: #7c3aed; stroke: navy; stroke-width: 2\" " +
                "opacity=\"0.8\"><use xlink:href=\"#chip\" x=\"4\" y=\"4\"/><use href=\"#chip\" x=\"24\" y=\"4\" " +
                "style=\"fill:orange\"/><circle cx=\"50\" cy=\"9\" r=\"5\"/></g><g transform=\"translate(4 20) " +
                "scale(2 1) skewX(10)\"><rect width=\"10\" height=\"12\" fill=\"#0ea5e9\"/></g>" +
                "<path d=\"M40 20 h16 v16 h-16 z\" fill=\"#f59e0b\" fill-opacity=\"0.5\" stroke=\"#000\" " +
                "stroke-opacity=\"0.5\" stroke-width=\"3\" stroke-linejoin=\"miter\"/></svg>"
            ),
    ).map { (name, text) -> name to text.toByteArray() }

    private companion object {
        /** A square box and a wide one, so the aspect ratio rules are exercised too. */
        val SIZES = listOf(120 to 120, 200 to 120)
        const val CHANNEL_TOLERANCE = 64f / 255f
        const val TOLERANCE = 0.015
    }
}

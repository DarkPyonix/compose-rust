package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.GenericFamily
import dev.darkpyonix.composerust.protocol.MeasureRecords
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import kotlin.test.Test

/** Throwaway probe for compose-rust #1109 (draft branch, never merged): reports raw widths by failing. */
@OptIn(ExperimentalTestApi::class)
class ZzMeasureProbeTest {
    private val strings = listOf("Button.test.tsx", "Button.tsx", "format.ts", "# Probe workspace", "The quick brown fox jumps over the lazy dog")

    @Test
    fun probe() {
        val lines = mutableListOf<String>()
        runComposeUiTest {
        startHost(FakeHostConnection(emptyList()), Density(1f))
        val system = listOf(FontRef.Generic(GenericFamily.SystemUi))
        for (size in listOf(13f, 20f, 32f, 48f)) {
            for (s in strings) {
                val req = MeasureRequests()
                    .text(s, role = 0, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT, fontSize = size, fonts = system, absoluteSize = true)
                    .text(s, role = 0, constraint = MeasureRecords.CONSTRAINT_AT_MOST, width = Float.POSITIVE_INFINITY, fontSize = size, fonts = system, absoluteSize = true)
                    .text(s, role = 0, constraint = MeasureRecords.CONSTRAINT_AT_MOST, width = 1000f, fontSize = size, fonts = system, absoluteSize = true)
                val (_, r) = measure(req)
                val raw = ParagraphIntrinsics(s, TextStyle(fontSize = size.sp, fontFamily = FontFamily.Default), density = Density(1f), fontFamilyResolver = createFontFamilyResolver()).maxIntrinsicWidth
                val awt = java.awt.Font(".AppleSystemUIFont", java.awt.Font.PLAIN, 1).deriveFont(size)
                val frc = java.awt.font.FontRenderContext(null, true, true)
                val awtW = awt.getStringBounds(s, frc).width
                lines += ("PROBE size=$size '$s' max=${r[0].width} atMostInf=${r[1].width}/${r[1].lastLineWidth} atMost1000=${r[2].width}/${r[2].lastLineWidth} composeDefault=$raw awtSF=$awtW awtName=${awt.fontName}")
            }
        }
        }
        kotlin.test.fail("measure probe, not a failure:\n" + lines.joinToString("\n"))
    }
}

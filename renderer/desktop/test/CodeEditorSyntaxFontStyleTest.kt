package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.FontLoadResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.foundation.code.CodeEditorLook
import dev.darkpyonix.composerust.foundation.code.CodeEditorModel
import dev.darkpyonix.composerust.foundation.code.UnderlineShape
import dev.darkpyonix.composerust.foundation.code.syntaxSpanStyle
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.SyntaxSpanRecord
import dev.darkpyonix.composerust.ui.node.decodeFontAsset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.jetbrains.skia.FontSlant

/**
 * Per-role font styles (italic comments, weight 500 types, underline) on CodeEditor syntax spans.
 */
class CodeEditorSyntaxFontStyleTest {

    private val resolver = createFontFamilyResolver()

    private fun testLook() = CodeEditorLook(
        textStyle = TextStyle(),
        container = Color.White,
        gutter = Color.White,
        lineNumber = Color.Gray,
        currentLineNumber = Color.Black,
        currentLine = Color.LightGray,
        currentLineBorder = Color.Transparent,
        gutterDivider = Color.Transparent,
        gutterDividerWidth = 1.dp,
        gutterPadding = 8.dp,
        textInset = 8.dp,
        selection = Color.Blue,
        cursor = Color.Black,
        underlineWidth = 1.dp,
        underline = { Color.Red to UnderlineShape.Straight },
        paint = { Color.DarkGray },
        ghostTextAlpha = 0.5f,
        lens = Color.Gray,
        tabWidth = 4,
        hoverDelayMillis = 300L,
        commandIsMeta = true,
    )

    @Test
    fun fr38_syntax_span_style_resolves_italic_comment_and_medium_type_typefaces() {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !java.io.File(dir, "renderer/web/resources/fonts").isDirectory) {
            dir = dir.parentFile
        }
        val fontsDir = assertNotNull(dir).resolve("renderer/web/resources/fonts")
        val regular = assertNotNull(decodeFontAsset(java.io.File(fontsDir, "Roboto-Regular.ttf").readBytes())?.face)
        val italic = assertNotNull(decodeFontAsset(java.io.File(fontsDir, "Roboto-Italic.ttf").readBytes())?.face)
        val medium = assertNotNull(decodeFontAsset(java.io.File(fontsDir, "Roboto-Medium.ttf").readBytes())?.face)
        val family = FontFamily(regular.font, italic.font, medium.font)

        val model = CodeEditorModel("// note\nstruct Foo")
        val italicBits = 1 shl 16
        val mediumBits = 500
        model.setSyntaxSpans(
            listOf(
                SyntaxSpanRecord(0, 0, 0, 0, 7, Paint.Role(ColorRole.SyntaxComment), italicBits),
                SyntaxSpanRecord(0, 1, 7, 1, 10, Paint.Role(ColorRole.SyntaxType), mediumBits),
                SyntaxSpanRecord(0, 1, 0, 1, 6, Paint.Role(ColorRole.SyntaxKeyword), 0),
            ),
        )

        val look = testLook()
        val commentStyle = syntaxSpanStyle(model.spans[0], look)
        assertEquals(FontStyle.Italic, commentStyle.fontStyle)
        assertNull(commentStyle.fontWeight)
        assertNull(commentStyle.textDecoration)

        val keywordStyle = syntaxSpanStyle(model.spans[1], look)
        assertNull(keywordStyle.fontStyle)
        assertNull(keywordStyle.fontWeight)

        val typeStyle = syntaxSpanStyle(model.spans[2], look)
        assertNull(typeStyle.fontStyle)
        assertEquals(FontWeight(500), typeStyle.fontWeight)

        // Resolve each span's style against the registered multi-face family and check the Skia Typeface.
        val commentFace = (resolver.resolve(
            family,
            commentStyle.fontWeight ?: FontWeight.Normal,
            commentStyle.fontStyle ?: FontStyle.Normal,
        ).value as FontLoadResult).typeface!!
        assertEquals(FontSlant.ITALIC, commentFace.fontStyle.slant, "comment span must resolve to the italic face")

        val typeFace = (resolver.resolve(
            family,
            typeStyle.fontWeight ?: FontWeight.Normal,
            typeStyle.fontStyle ?: FontStyle.Normal,
        ).value as FontLoadResult).typeface!!
        assertEquals(500, typeFace.fontStyle.weight, "type span must resolve to the weight-500 face")
        assertEquals(FontSlant.UPRIGHT, typeFace.fontStyle.slant, "type span stays upright")

        val keywordFace = (resolver.resolve(
            family,
            keywordStyle.fontWeight ?: FontWeight.Normal,
            keywordStyle.fontStyle ?: FontStyle.Normal,
        ).value as FontLoadResult).typeface!!
        assertEquals(400, keywordFace.fontStyle.weight)
        assertEquals(FontSlant.UPRIGHT, keywordFace.fontStyle.slant)
    }

    @Test
    fun fr38_syntax_span_underline_bit_sets_text_decoration() {
        val model = CodeEditorModel("deprecated")
        model.setSyntaxSpans(
            listOf(
                SyntaxSpanRecord(0, 0, 0, 0, 10, Paint.Role(ColorRole.SyntaxFunction), 1 shl 17),
            ),
        )
        val style = syntaxSpanStyle(model.spans.single(), testLook())
        assertEquals(TextDecoration.Underline, style.textDecoration)
    }
}

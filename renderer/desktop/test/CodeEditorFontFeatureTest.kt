package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.foundation.codeEditorLook
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.ui.node.NodeTable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * OpenType features on a `CodeEditor`: the editor's text is set with them, so a coding font's
 * ligatures can be turned off and `->` drawn as two glyphs, while the line numbers keep the
 * design system's features and an editor that asks for none is set as before.
 */
class CodeEditorFontFeatureTest {
    private val theme = resolveTheme(
        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
        HostPlatform.Unknown,
        systemDark = false,
        highContrast = { false },
    )

    private fun records(vararg features: Pair<String, Int>): ByteArray {
        val buffer = ByteBuffer.allocate(8 * features.size).order(ByteOrder.LITTLE_ENDIAN)
        for ((tag, value) in features) buffer.put(tag.encodeToByteArray()).putInt(value)
        return buffer.array()
    }

    @Test
    fun fr83_code_editor_sets_its_text_with_the_features_it_is_given() {
        val table = NodeTable()
        table.apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        table.apply(
            Mutation.SetProp(
                EDITOR,
                PropertyKind.FontFeatureSettings,
                PropertyValue.Bytes(records("liga" to 0, "calt" to 0)),
            ),
        )
        assertEquals(emptyList(), table.drainErrors(), "a code editor takes font features")
        val node = table.node(EDITOR)!!
        assertEquals("liga 0, calt 0", node.fontFeatures)

        val look = codeEditorLook(node, theme, tabWidth = null)
        assertEquals("liga 0, calt 0", look.textStyle.fontFeatureSettings)
        // The gutter's digits keep what the design system sets them with.
        val plainType = codeEditorLook(plainEditor(), theme, tabWidth = null).textStyle
        assertEquals(plainType.fontFeatureSettings, look.lineNumberFeatures)
    }

    @Test
    fun fr83_code_editor_without_features_is_set_as_before() {
        val look = codeEditorLook(plainEditor(), theme, tabWidth = null)
        assertNull(look.textStyle.fontFeatureSettings)
        assertNull(look.lineNumberFeatures)
    }

    private fun plainEditor() = NodeTable().run {
        apply(Mutation.Create(EDITOR, WidgetKind.CodeEditor))
        drainErrors()
        node(EDITOR)!!
    }

    private companion object {
        const val EDITOR = 7
    }
}

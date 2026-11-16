package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.design.ContainerRole
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.foundation.topAppBarSeparator
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.ui.node.Node
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TopAppBarSeparatorTest {
    private val cupertinoTheme = resolveTheme(
        Theme(DesignSystem.Cupertino, DesignSystem.Cupertino, ColorScheme.Light, adaptive = false),
        HostPlatform.MacOs,
        systemDark = false,
    )
    private val cupertinoStyle = cupertinoTheme.rules.container(ContainerRole.TopAppBar, cupertinoTheme)

    @Test
    fun fr15_2_topappbar_hairline_false_omits_separator() {
        // Cupertino defaults to having a separator
        val defaultNode = Node(1, WidgetKind.TopAppBar)
        val defaultSeparator = topAppBarSeparator(defaultNode, cupertinoStyle, cupertinoTheme)
        assertNotNull(defaultSeparator, "Cupertino should have a separator by default")

        // Explicitly turning off separator yields null
        val noHairlineNode = Node(2, WidgetKind.TopAppBar)
        noHairlineNode.props[PropertyKind.Separator] = PropertyValue.Bool(false)
        val noSeparator = topAppBarSeparator(noHairlineNode, cupertinoStyle, cupertinoTheme)
        assertNull(noSeparator, "Separator property false must omit separator")
    }

    @Test
    fun fr15_2_topappbar_own_border_omits_separator() {
        val borderedNode = Node(3, WidgetKind.TopAppBar)
        borderedNode.modifiers.add(
            ProtocolModifier.Border(
                width = 1f,
                paint = Paint.Literal(0xFF000000.toInt()),
            ),
        )
        val separator = topAppBarSeparator(borderedNode, cupertinoStyle, cupertinoTheme)
        assertNull(separator, "Bar with own Border modifier must omit default separator")

        // But an explicit separator=true overrides own border
        borderedNode.props[PropertyKind.Separator] = PropertyValue.Bool(true)
        val explicitSeparator = topAppBarSeparator(borderedNode, cupertinoStyle, cupertinoTheme)
        assertNotNull(explicitSeparator, "Explicit separator=true must override own border")
    }
}

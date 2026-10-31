package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CHIP = 1
private const val LABEL = "안 읽은 메시지 모아보기 필터 목록 보기"

/** A chip label is one line unless the Host asks for more. */
@OptIn(ExperimentalTestApi::class)
class ChipLinesTest {
    private fun heightOf(vararg extra: Mutation): Float {
        var height = 0f
        runComposeUiTest {
            val batch = listOf(
                Mutation.Create(CHIP, WidgetKind.Chip),
                Mutation.SetProp(CHIP, PropertyKind.Text, PropertyValue.Text(LABEL)),
                Mutation.SetModifier(CHIP, 0, ProtocolModifier.Width(90f)),
            ) + extra
            setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
            waitForIdle()
            height = onNodeWithTag(nodeTestTag(CHIP)).getUnclippedBoundsInRoot().height.value
        }
        return height
    }

    @Test
    fun fr_15_a_chip_label_is_one_line_unless_the_host_asks_for_more() {
        val one = heightOf()
        val three = heightOf(Mutation.SetProp(CHIP, PropertyKind.MaxLines, PropertyValue.Integer(3)))
        assertTrue(three > one, "three lines are taller than one: $three against $one")
        assertEquals(one, heightOf(Mutation.SetProp(CHIP, PropertyKind.MaxLines, PropertyValue.Integer(1))))
    }
}

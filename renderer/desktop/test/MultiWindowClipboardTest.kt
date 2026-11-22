package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.LocalServesFrames
import dev.darkpyonix.composerust.runtime.LocalWindowNodeId
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * An application of several windows reads and writes the clipboard and runs
 * file operations like one of a single window. Each of its windows draws its own `Window`
 * node (`LocalWindowNodeId` is never 0), so whatever wires the clipboard has to be one of
 * them: the window opened last serves, and when it closes the one before it takes over.
 */
@OptIn(ExperimentalTestApi::class)
class MultiWindowClipboardTest {

    private fun windows() = FakeHostConnection(
        listOf(
            Mutation.Create(1, WidgetKind.Window),
            Mutation.Insert(0, 1, -1),
            Mutation.Create(2, WidgetKind.Window),
            Mutation.Insert(0, 2, -1),
            Mutation.Create(10, WidgetKind.Text),
            Mutation.Insert(1, 10, -1),
            Mutation.Create(20, WidgetKind.Text),
            Mutation.Insert(2, 20, -1),
        ),
    )

    private fun ComposeRustHost.wired(): Boolean =
        table.clipboard.sink != null && table.clipboardReads.launcher != null && table.fileCalls.launcher != null

    @Test
    fun fr78_clipboard_and_file_calls_work_in_a_multi_window_app() = runComposeUiTest {
        val connection = windows()
        var host: ComposeRustHost? = null
        var first by mutableStateOf(true)
        var second by mutableStateOf(true)
        setContent {
            val shared = rememberComposeRustHost(connection)
            host = shared
            Column {
                for ((node, open) in listOf(1 to first, 2 to second)) {
                    if (open) {
                        CompositionLocalProvider(LocalWindowNodeId provides node, LocalServesFrames provides false) {
                            ComposeRustContent(shared)
                        }
                    }
                }
            }
        }
        waitForIdle()
        val h = assertNotNull(host)
        assertNotNull(h.table.clipboard.sink, "no window of several installed the clipboard the Host's copies go to")
        assertNotNull(h.table.clipboardReads.launcher, "no window of several runs the Host's clipboard reads")
        assertNotNull(h.table.fileCalls.launcher, "no window of several runs the Host's file operations")
        val servedBySecond = h.table.clipboardReads.launcher

        // The window that served closes: the other one takes over, with a scope that is alive.
        second = false
        waitForIdle()
        assertNotNull(h.table.clipboardReads.launcher, "the clipboard stopped when one window of two closed")
        assertNotNull(h.table.fileCalls.launcher, "file operations stopped when one window of two closed")
        check(h.table.clipboardReads.launcher !== servedBySecond) { "the closed window's scope still runs the reads" }

        // A window that opens again serves from then on; the last one to close leaves nothing behind.
        second = true
        waitForIdle()
        check(h.wired())
        first = false
        second = false
        waitForIdle()
        assertNull(h.table.clipboardReads.launcher, "a read was left to a scope that no window holds")
        assertNull(h.table.fileCalls.launcher, "a file operation was left to a scope that no window holds")
    }

    @Test
    fun fr78_single_window_still_serves_the_clipboard() = runComposeUiTest {
        val connection = FakeHostConnection(listOf(Mutation.Create(1, WidgetKind.Text)))
        var host: ComposeRustHost? = null
        setContent {
            val shared = rememberComposeRustHost(connection)
            host = shared
            ComposeRustContent(shared)
        }
        waitForIdle()
        val h = assertNotNull(host)
        check(h.wired()) { "the single window no longer wires the clipboard" }
    }
}

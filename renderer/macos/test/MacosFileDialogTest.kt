package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.FileDialogKind
import dev.darkpyonix.composerust.ui.platform.FileDialogFilter
import dev.darkpyonix.composerust.ui.platform.FileDialogOutcome
import dev.darkpyonix.composerust.ui.platform.FileDialogPath
import dev.darkpyonix.composerust.ui.platform.FileDialogSpec
import dev.darkpyonix.composerust.ui.platform.SheetFileDialogPlatform
import dev.darkpyonix.composerust.ui.platform.WindowSheets
import dev.darkpyonix.composerust.ui.platform.outcomeOf
import dev.darkpyonix.composerust.ui.platform.sheetRequestOf
import org.thisisthepy.compose.window.FileDialogKind as SheetKind
import org.thisisthepy.compose.window.FileDialogOutcome as SheetOutcome
import org.thisisthepy.compose.window.FileDialogRequest as SheetRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** One window's sheets that remember what they were asked and answer when the test says so. */
private class FakeSheets(val window: String, val gone: () -> Unit) : WindowSheets {
    class Shown(val request: SheetRequest, val onResult: (SheetOutcome) -> Unit)

    val shown = mutableListOf<Shown>()
    var cancelled = 0

    override fun show(request: SheetRequest, onResult: (SheetOutcome) -> Unit) {
        shown += Shown(request, onResult)
    }

    override fun cancelAll() {
        cancelled++
        shown.forEach { it.onResult(SheetOutcome.Cancelled) }
        shown.clear()
    }
}

private fun spec(
    request: Int = 1,
    window: Int = 0,
    kind: FileDialogKind = FileDialogKind.OpenFiles,
    filters: List<FileDialogFilter> = emptyList(),
    defaultFilter: Int = 0,
    name: String = "",
) = FileDialogSpec(
    request = request,
    windowNode = window,
    kind = kind,
    title = "파일 고르기",
    directory = "/Users/한글",
    name = name,
    filters = filters,
    defaultFilter = defaultFilter,
)

/**
 * The macOS renderer's glue between the interpreter's file dialog centre and the fork's
 * sheets. The sheet itself is AppKit's and is checked by hand on a macOS build; what is
 * checked here is which window a request goes to, what reaches the sheet, and what comes back.
 */
class MacosFileDialogTest {
    /** Windows by node, the way the window manager's handles resolve; node 0 is "root". */
    private val windows = mutableMapOf(0 to "root")
    private val made = mutableListOf<FakeSheets>()
    private val queued = ArrayDeque<() -> Unit>()

    private fun platform(attempts: Int = 3) = SheetFileDialogPlatform<String>(
        windowFor = { handle, node -> (handle as? String) ?: windows[node] },
        sheetsFor = { window, gone -> FakeSheets(window, gone).also { made += it } },
        later = { queued.addLast(it) },
        attempts = attempts,
    )

    private fun runLater() {
        while (queued.isNotEmpty()) queued.removeFirst()()
    }

    private fun sheetsOf(window: String) = made.last { it.window == window }

    @Test
    fun fr72_macos_request_reaches_the_sheet_unchanged() {
        for ((kind, sheetKind) in listOf(
            FileDialogKind.OpenFile to SheetKind.OpenFile,
            FileDialogKind.OpenFiles to SheetKind.OpenFiles,
            FileDialogKind.OpenFolder to SheetKind.OpenFolder,
            FileDialogKind.OpenFolders to SheetKind.OpenFolders,
            FileDialogKind.Save to SheetKind.Save,
        )) {
            val request = sheetRequestOf(
                spec(
                    kind = kind,
                    filters = listOf(FileDialogFilter("이미지", listOf("png", "jpg")), FileDialogFilter("모두", emptyList())),
                    defaultFilter = 1,
                    name = "보고서.txt",
                ),
            )
            assertEquals(sheetKind, request.kind)
            assertEquals("파일 고르기", request.title)
            assertEquals("/Users/한글", request.directory)
            assertEquals("보고서.txt", request.name)
            assertEquals(listOf("이미지", "모두"), request.filters.map { it.label })
            assertEquals(listOf(listOf("png", "jpg"), emptyList()), request.filters.map { it.extensions })
            assertEquals(1, request.defaultFilter)
        }
    }

    @Test
    fun fr72_macos_sheet_answers_become_the_centres_outcomes() {
        val chosen = outcomeOf(SheetOutcome.Chosen(listOf("/Users/한글/a b.png", "/tmp/😀.jpg"), 1))
        assertIs<FileDialogOutcome.Chosen>(chosen)
        assertEquals(
            listOf("/Users/한글/a b.png", "/tmp/😀.jpg"),
            chosen.paths.map { (it as FileDialogPath.Text).value },
        )
        assertEquals(1, chosen.filter)

        // A folder pick, or a dialog without filters, reports no filter.
        val noFilter = outcomeOf(SheetOutcome.Chosen(listOf("/Users"), -1))
        assertIs<FileDialogOutcome.Chosen>(noFilter)
        assertNull(noFilter.filter)

        assertSame(FileDialogOutcome.Cancelled, outcomeOf(SheetOutcome.Cancelled))
        assertSame(FileDialogOutcome.Unavailable, outcomeOf(SheetOutcome.Unavailable))
    }

    @Test
    fun fr72_macos_every_request_gets_exactly_one_result() {
        val platform = platform()
        val answers = mutableListOf<FileDialogOutcome>()
        platform.show(null, spec(request = 1)) { answers += it }
        sheetsOf("root").shown.single().onResult(SheetOutcome.Chosen(listOf("/a"), -1))
        assertEquals(1, answers.size)
        assertIs<FileDialogOutcome.Chosen>(answers.single())
    }

    @Test
    fun fr72_macos_dialog_waits_for_its_window_to_open() {
        // The batch that asked for the dialog is applied before the window is on screen.
        windows.clear()
        val platform = platform()
        val answers = mutableListOf<FileDialogOutcome>()
        platform.show(null, spec(request = 1, window = 7)) { answers += it }
        assertTrue(made.isEmpty())
        assertEquals(1, queued.size)

        windows[7] = "seven"
        runLater()
        assertEquals(1, sheetsOf("seven").shown.size)
        assertTrue(answers.isEmpty())
    }

    @Test
    fun fr72_macos_window_that_never_opens_answers_unavailable() {
        windows.clear()
        val platform = platform(attempts = 3)
        val answers = mutableListOf<FileDialogOutcome>()
        platform.show(null, spec(request = 1, window = 7)) { answers += it }
        runLater()
        assertEquals(listOf<FileDialogOutcome>(FileDialogOutcome.Unavailable), answers)
        assertTrue(made.isEmpty())
    }

    @Test
    fun fr72_macos_each_window_has_its_own_sheets() {
        windows[3] = "three"
        val platform = platform()
        platform.show(null, spec(request = 1, window = 3)) {}
        platform.show(null, spec(request = 2, window = 3)) {}
        platform.show(null, spec(request = 3, window = 0)) {}
        // The handle the centre resolved wins over the node.
        platform.show("handle", spec(request = 4, window = 3)) {}

        assertEquals(listOf("three", "root", "handle"), made.map { it.window })
        assertEquals(2, sheetsOf("three").shown.size)
        assertEquals(3, platform.windowCount)
    }

    @Test
    fun fr72_macos_closing_the_owner_window_cancels_the_dialog() {
        windows[3] = "three"
        val platform = platform()
        val answers = mutableListOf<FileDialogOutcome>()
        platform.show(null, spec(request = 1, window = 3)) { answers += it }

        // The node was removed: the centre names the handle it showed the dialog on.
        platform.cancelWindow("three")
        assertEquals(1, sheetsOf("three").cancelled)
        assertEquals(listOf<FileDialogOutcome>(FileDialogOutcome.Cancelled), answers)
        assertEquals(0, platform.windowCount)

        // The window closed by itself: its sheets say so, and the window is forgotten.
        platform.show(null, spec(request = 2, window = 3)) {}
        assertEquals(1, platform.windowCount)
        sheetsOf("three").gone()
        assertEquals(0, platform.windowCount)

        // The implicit window is never removed, so a missing handle cancels nothing.
        platform.show(null, spec(request = 3, window = 0)) {}
        platform.cancelWindow(null)
        assertEquals(0, sheetsOf("root").cancelled)
    }
}

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.FileDelivery
import dev.darkpyonix.composerust.protocol.FileDialogKind
import dev.darkpyonix.composerust.protocol.IoStatus
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

/**
 * The web's file dialogs and file handles, in the browser the test runs in, against the
 * browser's own objects.
 *
 * The pickers cannot be clicked through in a test, so each test puts a stand-in where the
 * page looks for them: with the File System Access API, pickers that answer with handles from
 * the origin private file system, which are real `FileSystemFileHandle`s; without it, a page
 * whose pickers are gone and whose file input "chooses" files built here. Everything after
 * the picker (reading, writing, permissions, downloads) is the browser's.
 */
class WebFilesTest {
    private fun spec(kind: FileDialogKind, name: String = "", filters: List<FileDialogFilter> = emptyList()) =
        FileDialogSpec(
            request = 1,
            windowNode = 0,
            kind = kind,
            title = "",
            directory = "",
            name = name,
            filters = filters,
            defaultFilter = 0,
        )

    private fun handles(outcome: FileDialogOutcome): FileDialogOutcome.Handles =
        outcome as? FileDialogOutcome.Handles ?: fail("expected a choice, got $outcome")

    private fun text(outcome: FileCallOutcome): String =
        (outcome as? FileCallOutcome.Bytes ?: fail("expected bytes, got $outcome")).data.decodeToString()

    private fun status(outcome: FileCallOutcome): IoStatus =
        (outcome as? FileCallOutcome.Failed)?.status ?: fail("expected a failure, got $outcome")

    @Test
    fun fr72_web_a_resave_writes_the_same_file(): Promise<JsAny?> = GlobalScope.promise<JsAny?> {
        if (!hasPrivateFileSystem()) {
            println("WebFilesTest: this browser has no origin private file system; the resave test needs Chromium")
            return@promise null
        }
        installAccessPickers("fr72-resave.txt").await<JsAny?>()
        try {
            val files = WebFiles()
            val saved = handles(files.choose(spec(FileDialogKind.Save, name = "fr72-resave.txt")))
            assertEquals(FileDelivery.Handle, saved.delivery)
            assertEquals(listOf(HeldFile("fr72-resave.txt", false)), saved.files)
            val handle = saved.firstHandle

            assertEquals(FileCallOutcome.Written, files.write(handle, "첫 번째".encodeToByteArray()))
            assertEquals(FileCallOutcome.Written, files.write(handle, "두 번째".encodeToByteArray()))
            assertEquals("두 번째", text(files.read(handle, 1 shl 20)))
            assertEquals("두 번째", privateFileText("fr72-resave.txt").await<JsString>().toString())

            // Opened rather than saved, the file is the same one, and a write changes it again.
            val opened = handles(files.choose(spec(FileDialogKind.OpenFile)))
            assertEquals(FileDelivery.Handle, opened.delivery)
            assertEquals(FileCallOutcome.Written, files.write(opened.firstHandle, "세 번째".encodeToByteArray()))
            assertEquals("세 번째", privateFileText("fr72-resave.txt").await<JsString>().toString())

            // A read longer than the limit is refused before anything is read.
            assertEquals(IoStatus.TooLarge, status(files.read(handle, 3)))
        } finally {
            restorePage()
        }
        null
    }

    @Test
    fun fr72_web_fallback_reports_upload_and_download(): Promise<JsAny?> = GlobalScope.promise<JsAny?> {
        removePickers()
        chooseWithInput("a.txt|하나\nsub/b.txt|둘")
        try {
            val files = WebFiles()
            val opened = handles(
                files.choose(spec(FileDialogKind.OpenFiles, filters = listOf(FileDialogFilter("Text", listOf("txt"))))),
            )
            assertEquals(FileDelivery.Upload, opened.delivery)
            assertEquals(listOf(HeldFile("a.txt", false), HeldFile("b.txt", false)), opened.files)
            assertEquals(".txt", lastInputAccept())
            assertEquals("하나", text(files.read(opened.firstHandle, 1 shl 20)))
            assertEquals(IoStatus.ReadOnly, status(files.write(opened.firstHandle, byteArrayOf(1))))

            // A folder is rebuilt from the paths the input gave, and can only be read.
            val folder = handles(files.choose(spec(FileDialogKind.OpenFolders)))
            assertEquals(FileDelivery.Upload, folder.delivery)
            assertEquals(listOf(HeldFile("root", true)), folder.files)
            val top = files.entries(folder.firstHandle) as? FileCallOutcome.Files ?: fail("the folder lists")
            assertEquals(FileDelivery.Upload, top.delivery)
            assertEquals(listOf(HeldFile("a.txt", false), HeldFile("sub", true)), top.files)
            val sub = files.entries(top.firstHandle + 1) as? FileCallOutcome.Files ?: fail("the sub folder lists")
            assertEquals(listOf(HeldFile("b.txt", false)), sub.files)
            assertEquals("둘", text(files.read(sub.firstHandle, 1 shl 20)))
            assertEquals(IoStatus.ReadOnly, status(files.child(folder.firstHandle, "new.txt", true)))

            // Saving has no picker: it is a download, one for every write, under the name asked for.
            val saved = handles(files.choose(spec(FileDialogKind.Save, name = "보고서.txt")))
            assertEquals(FileDelivery.Download, saved.delivery)
            assertEquals(listOf(HeldFile("보고서.txt", false)), saved.files)
            assertEquals(FileCallOutcome.Written, files.write(saved.firstHandle, "첫".encodeToByteArray()))
            assertEquals(FileCallOutcome.Written, files.write(saved.firstHandle, "둘째".encodeToByteArray()))
            assertEquals("보고서.txt|보고서.txt", downloads().toString())
        } finally {
            restorePage()
        }
        null
    }

    @Test
    fun fr72_web_cancel_and_refusal_map_to_their_status(): Promise<JsAny?> = GlobalScope.promise<JsAny?> {
        val files = WebFiles()
        try {
            // Without the API: closing the file input is a cancel.
            removePickers()
            cancelInput()
            assertEquals(FileDialogOutcome.Cancelled, files.choose(spec(FileDialogKind.OpenFile)))
            restorePage()

            // With it: the user closing the picker, and the browser refusing to open one.
            for ((error, expected) in listOf(
                "AbortError" to FileDialogOutcome.Cancelled,
                "SecurityError" to FileDialogOutcome.Unavailable,
                "NotAllowedError" to FileDialogOutcome.Unavailable,
            )) {
                rejectingPickers(error)
                for (kind in listOf(FileDialogKind.OpenFile, FileDialogKind.OpenFolder, FileDialogKind.Save)) {
                    assertEquals(expected, files.choose(spec(kind)), "$error from the $kind picker")
                }
                restorePage()
            }
            assertEquals(0, files.heldCount, "nothing is held for a dialog that chose nothing")
        } finally {
            restorePage()
        }
        null
    }

    @Test
    fun fr72_web_an_unknown_handle_is_not_found(): Promise<JsAny?> = GlobalScope.promise<JsAny?> {
        val files = WebFiles()
        assertEquals(IoStatus.NotFound, status(files.read(77, 100)))
        assertEquals(IoStatus.NotFound, status(files.write(77, byteArrayOf(1))))
        assertEquals(IoStatus.NotFound, status(files.entries(77)))
        assertEquals(IoStatus.NotFound, status(files.child(77, "a", false)))

        removePickers()
        try {
            val saved = handles(files.choose(spec(FileDialogKind.Save, name = "x.txt")))
            files.release(saved.firstHandle)
            assertEquals(IoStatus.NotFound, status(files.write(saved.firstHandle, byteArrayOf(1))))
            // Released twice, or never held: nothing happens.
            files.release(saved.firstHandle)
            files.release(4242)
            assertEquals(0, files.heldCount)
        } finally {
            restorePage()
        }
        null
    }

    @Test
    fun fr72_web_filters_become_picker_types_with_the_default_first() {
        val types = WebFiles.pickerTypes(
            FileDialogSpec(
                request = 1,
                windowNode = 0,
                kind = FileDialogKind.OpenFile,
                title = "",
                directory = "",
                name = "",
                filters = listOf(
                    FileDialogFilter("이미지", listOf("png", "jpg")),
                    FileDialogFilter("\"문서\"", listOf("pdf", "한글", "averyveryverylongext")),
                    FileDialogFilter("All", emptyList()),
                ),
                defaultFilter = 1,
            ),
        )
        assertEquals(
            "[{\"description\":\"\\\"문서\\\"\",\"accept\":{\"application/octet-stream\":[\".pdf\"]}}," +
                "{\"description\":\"이미지\",\"accept\":{\"application/octet-stream\":[\".png\",\".jpg\"]}}]",
            types,
        )
        assertTrue(parsesAsJson(types))
    }
}

// ----- the page under test ------------------------------------------------------------------

private fun hasPrivateFileSystem(): Boolean =
    js("(typeof navigator !== 'undefined' && navigator.storage && typeof navigator.storage.getDirectory === 'function')")

/** Pickers that answer with the private file system's `name`, made empty first. */
@Suppress("UNUSED_PARAMETER")
private fun installAccessPickers(name: String): Promise<JsAny?> = js(
    """(async () => {
        const root = await navigator.storage.getDirectory();
        try { await root.removeEntry(name); } catch (e) {}
        const file = await root.getFileHandle(name, { create: true });
        window.__fr72Saved = window.__fr72Saved || {
            open: window.showOpenFilePicker,
            save: window.showSaveFilePicker,
            folder: window.showDirectoryPicker,
            click: HTMLInputElement.prototype.click,
            link: HTMLAnchorElement.prototype.click,
        };
        window.showOpenFilePicker = async () => [file];
        window.showSaveFilePicker = async () => file;
        window.showDirectoryPicker = async () => root;
        return null;
    })()""",
)

@Suppress("UNUSED_PARAMETER")
private fun privateFileText(name: String): Promise<JsAny?> = js(
    """(async () => {
        const root = await navigator.storage.getDirectory();
        const file = await (await root.getFileHandle(name)).getFile();
        return await file.text();
    })()""",
)

private fun saveOriginals(): Unit = js(
    """{
        window.__fr72Saved = window.__fr72Saved || {
            open: window.showOpenFilePicker,
            save: window.showSaveFilePicker,
            folder: window.showDirectoryPicker,
            click: HTMLInputElement.prototype.click,
            link: HTMLAnchorElement.prototype.click,
        };
    }""",
)

private fun removePickers() {
    saveOriginals()
    removePickersNow()
}

private fun removePickersNow(): Unit = js(
    """{
        window.showOpenFilePicker = undefined;
        window.showSaveFilePicker = undefined;
        window.showDirectoryPicker = undefined;
        window.__fr72Downloads = [];
        HTMLAnchorElement.prototype.click = function () { window.__fr72Downloads.push(this.download); };
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun rejectingPickersNow(error: String): Unit = js(
    """{
        const reject = async () => { throw new DOMException('refused in a test', error); };
        window.showOpenFilePicker = reject;
        window.showSaveFilePicker = reject;
        window.showDirectoryPicker = reject;
    }""",
)

private fun rejectingPickers(error: String) {
    saveOriginals()
    rejectingPickersNow(error)
}

/**
 * Makes the file input "choose" the files in [listing]: one per line, a relative path and its
 * text separated by `|`. A folder input sees them under `root/`.
 */
@Suppress("UNUSED_PARAMETER")
private fun chooseWithInput(listing: String): Unit = js(
    """{
        const entries = listing.split('\n').map((line) => line.split('|'));
        HTMLInputElement.prototype.click = function () {
            window.__fr72Accept = this.accept;
            const transfer = new DataTransfer();
            for (const [path, text] of entries) {
                const parts = path.split('/');
                transfer.items.add(new File([text], parts[parts.length - 1]));
            }
            const files = this.webkitdirectory
                ? Array.from(transfer.files).map((f, i) => {
                    const path = 'root/' + entries[i][0];
                    Object.defineProperty(f, 'webkitRelativePath', { value: path });
                    return f;
                })
                : null;
            if (files) {
                Object.defineProperty(this, 'files', { value: files, configurable: true });
            } else {
                this.files = transfer.files;
            }
            this.dispatchEvent(new Event('change'));
        };
    }""",
)

private fun cancelInput(): Unit = js(
    """{
        HTMLInputElement.prototype.click = function () { this.dispatchEvent(new Event('cancel')); };
    }""",
)

private fun lastInputAccept(): String = js("(window.__fr72Accept || '')")

private fun downloads(): JsString = js("(window.__fr72Downloads || []).join('|')")

private fun restorePage(): Unit = js(
    """{
        const saved = window.__fr72Saved;
        if (saved) {
            window.showOpenFilePicker = saved.open;
            window.showSaveFilePicker = saved.save;
            window.showDirectoryPicker = saved.folder;
            HTMLInputElement.prototype.click = saved.click;
            HTMLAnchorElement.prototype.click = saved.link;
            window.__fr72Saved = undefined;
        }
    }""",
)

@Suppress("UNUSED_PARAMETER")
private fun parsesAsJson(text: String): Boolean = js("(() => { try { JSON.parse(text); return true; } catch (e) { return false; } })()")

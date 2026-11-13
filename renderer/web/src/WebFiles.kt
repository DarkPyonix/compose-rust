@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.FileDelivery
import dev.darkpyonix.composerust.protocol.FileDialogKind
import dev.darkpyonix.composerust.protocol.IoStatus
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch

/**
 * The user's files in a browser: the dialogs that choose them and the handles that read and
 * write them.
 *
 * A page gets no paths, so every file the user chooses is kept here as the browser's own
 * object and named to the Host by a number. Where the File System Access API is there
 * (Chromium), a choice is a `FileSystemHandle`: [FileDelivery.Handle], written again in
 * place. Where it is not (Firefox, Safari), opening goes through an `<input type=file>` and
 * hands over a copy to read ([FileDelivery.Upload]), and saving becomes a download every
 * time it is written ([FileDelivery.Download]). Which one a run has is looked up on every
 * request, not once, so a page that gains or loses the API answers by what it has now.
 *
 * Every browser call here answers: the JavaScript catches what the browser throws and turns
 * it into a status, so a refusal is an answer and never an exception in the frame loop.
 */
class WebFiles : FileDialogPlatform, FileCallPlatform {
    /** The browser's objects, by the number the Host knows them by. */
    private val held = HashMap<Int, JsAny>()
    private var next = 1

    /**
     * What the pickers are told this application is, so the browser remembers its last folder
     * for it apart from other pages on the same origin. Null gives the pickers no `id`.
     */
    var pickerId: String? = null

    private val scope = MainScope()

    /** How many handles are held, for tests. */
    internal val heldCount: Int get() = held.size

    override fun show(window: Any?, request: FileDialogSpec, onResult: (FileDialogOutcome) -> Unit) {
        // Started at once, inside this call, so the picker opens while the click that asked
        // for it still counts as the user's.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            onResult(
                try {
                    choose(request)
                } catch (failure: Throwable) {
                    FileDialogOutcome.Unavailable
                },
            )
        }
    }

    /** Asks the browser for the files [request] describes and keeps what it gives. */
    internal suspend fun choose(request: FileDialogSpec): FileDialogOutcome {
        val result = pickFiles(
            kindCode(request.kind),
            pickerTypes(request),
            request.name,
            pickerId ?: "",
        ).await<JsAny>()
        return when (resultStatus(result)) {
            STATUS_CANCELLED -> FileDialogOutcome.Cancelled
            STATUS_CHOSEN -> {
                val delivery = deliveryOf(resultDelivery(result))
                val (first, files) = keep(result)
                FileDialogOutcome.Handles(delivery, first, files)
            }
            else -> FileDialogOutcome.Unavailable
        }
    }

    override fun discardHandles(firstHandle: Int, count: Int) {
        for (index in 0 until count) release(firstHandle + index)
    }

    override suspend fun read(handle: Int, limit: Long): FileCallOutcome {
        val entry = held[handle] ?: return FileCallOutcome.Failed(IoStatus.NotFound)
        val result = readEntry(entry, limit.toDouble()).await<JsAny>()
        val status = resultStatus(result)
        if (status != IO_OK) return FileCallOutcome.Failed(ioStatusOf(status))
        return FileCallOutcome.Bytes(bytesOf(resultBytes(result)))
    }

    override suspend fun write(handle: Int, data: ByteArray): FileCallOutcome {
        val entry = held[handle] ?: return FileCallOutcome.Failed(IoStatus.NotFound)
        // The one copy there has to be: the browser's stream takes its own buffer, and keeps
        // it past this call.
        val status = resultStatus(writeEntry(entry, jsBytesOf(data)).await<JsAny>())
        return if (status == IO_OK) FileCallOutcome.Written else FileCallOutcome.Failed(ioStatusOf(status))
    }

    override suspend fun entries(handle: Int): FileCallOutcome {
        val entry = held[handle] ?: return FileCallOutcome.Failed(IoStatus.NotFound)
        val result = listEntry(entry).await<JsAny>()
        val status = resultStatus(result)
        if (status != IO_OK) return FileCallOutcome.Failed(ioStatusOf(status))
        val (first, files) = keep(result)
        return FileCallOutcome.Files(deliveryOf(entryDelivery(entry)), first, files)
    }

    override suspend fun child(handle: Int, name: String, create: Boolean): FileCallOutcome {
        val entry = held[handle] ?: return FileCallOutcome.Failed(IoStatus.NotFound)
        val result = childEntry(entry, name, create).await<JsAny>()
        val status = resultStatus(result)
        if (status != IO_OK) return FileCallOutcome.Failed(ioStatusOf(status))
        val (first, files) = keep(result)
        return FileCallOutcome.Files(deliveryOf(entryDelivery(entry)), first, files)
    }

    override fun release(handle: Int) {
        held.remove(handle)
    }

    /** Numbers every entry of [result] in order and keeps it. Returns the first number and what each is. */
    private fun keep(result: JsAny): Pair<Int, List<HeldFile>> {
        val first = next
        val files = ArrayList<HeldFile>()
        for (index in 0 until resultCount(result)) {
            val entry = resultEntry(result, index)
            held[next++] = entry
            files += HeldFile(entryName(entry), entryFolder(entry))
        }
        return first to files
    }

    private fun kindCode(kind: FileDialogKind): Int = when (kind) {
        FileDialogKind.OpenFile -> 1
        FileDialogKind.OpenFiles -> 2
        FileDialogKind.OpenFolder -> 3
        // A browser has no way to choose more than one folder, and one is "one or more".
        FileDialogKind.OpenFolders -> 3
        FileDialogKind.Save -> 5
    }

    private fun deliveryOf(code: Int): FileDelivery = when (code) {
        2 -> FileDelivery.Upload
        3 -> FileDelivery.Download
        else -> FileDelivery.Handle
    }

    private fun ioStatusOf(code: Int): IoStatus =
        IoStatus.entries.getOrNull(code - 1)?.takeIf { it != IoStatus.Ok } ?: IoStatus.Failed

    private fun bytesOf(array: JsAny?): ByteArray {
        if (array == null) return ByteArray(0)
        val length = byteLength(array)
        return ByteArray(length) { index -> byteAt(array, index).toByte() }
    }

    private fun jsBytesOf(data: ByteArray): JsAny {
        val array = newBytes(data.size)
        for (index in data.indices) setByte(array, index, data[index].toInt() and 0xff)
        return array
    }

    companion object {
        private const val STATUS_CHOSEN = 1
        private const val STATUS_CANCELLED = 2
        private const val IO_OK = 1

        /**
         * The filters as the pickers take them, as JSON: one `{description, accept}` per
         * filter, the default first, because a picker has no "selected first" of its own. An
         * extension a picker would refuse (longer than 16 characters with its dot, or with a
         * character outside letters, digits, `+`, `-`, `_` and `.`) is left out, and a filter
         * left with none lets every file through, like one that named none.
         */
        internal fun pickerTypes(spec: FileDialogSpec): String {
            if (spec.filters.isEmpty()) return "[]"
            val ordered = spec.filters.withIndex().sortedBy { if (it.index == spec.defaultFilter) 0 else 1 }
            val parts = ordered.mapNotNull { (_, filter) ->
                val extensions = filter.extensions
                    .map { ".$it" }
                    .filter { it.length <= 16 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "+-_." } }
                if (extensions.isEmpty()) return@mapNotNull null
                "{\"description\":${jsonString(filter.label)},\"accept\":{\"application/octet-stream\":[" +
                    extensions.joinToString(",") { jsonString(it) } + "]}}"
            }
            return "[" + parts.joinToString(",") + "]"
        }

        private fun jsonString(value: String): String {
            val out = StringBuilder("\"")
            for (c in value) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> out.append(c)
                }
            }
            return out.append('"').toString()
        }
    }
}

// ----- the browser --------------------------------------------------------------------------
//
// Each call resolves with a result object and never rejects: `s` is the status (for a dialog
// 1 chosen, 2 cancelled, 3 unavailable; for a file the `IoAnswer` status), `e` the entries
// kept, `d` how they were delivered and `b` the bytes read. An entry is one of
//   { k: "h", h: FileSystemHandle }                 a file or folder in the file system;
//   { k: "u", f: File }                              a copy from a file input;
//   { k: "ud", name, children: Map<name, entry> }    a folder from a file input;
//   { k: "d", name, last: Uint8Array | null }        a save that downloads on every write.

/**
 * Opens the picker for [kind] (1 one file, 2 files, 3 a folder, 5 save) and resolves with
 * what was chosen. Uses the File System Access API where the page has it and the file input
 * and downloads where it does not.
 */
@Suppress("UNUSED_PARAMETER")
private fun pickFiles(kind: Int, types: String, name: String, id: String): Promise<JsAny?> = js(
    """(async () => {
        const parsed = JSON.parse(types);
        const options = {};
        if (parsed.length > 0) options.types = parsed;
        if (id) options.id = id;
        const refused = (e) => (e && e.name === 'AbortError') ? { s: 2 } : { s: 3 };
        const access = typeof window.showOpenFilePicker === 'function'
            && typeof window.showSaveFilePicker === 'function'
            && typeof window.showDirectoryPicker === 'function';
        if (access) {
            try {
                if (kind === 5) {
                    if (name) options.suggestedName = name;
                    const handle = await window.showSaveFilePicker(options);
                    return { s: 1, d: 1, e: [{ k: 'h', h: handle }] };
                }
                if (kind === 3) {
                    const folder = { mode: 'readwrite' };
                    if (id) folder.id = id;
                    const handle = await window.showDirectoryPicker(folder);
                    return { s: 1, d: 1, e: [{ k: 'h', h: handle }] };
                }
                options.multiple = kind === 2;
                const handles = await window.showOpenFilePicker(options);
                return { s: 1, d: 1, e: handles.map((h) => ({ k: 'h', h: h })) };
            } catch (e) {
                return refused(e);
            }
        }
        if (kind === 5) {
            return { s: 1, d: 3, e: [{ k: 'd', name: name || 'download', last: null }] };
        }
        return await new Promise((resolve) => {
            const input = document.createElement('input');
            input.type = 'file';
            if (kind === 3) {
                input.webkitdirectory = true;
            } else {
                input.multiple = kind === 2;
                const accept = [];
                for (const type of parsed) {
                    for (const list of Object.values(type.accept)) accept.push(...list);
                }
                if (accept.length > 0) input.accept = accept.join(',');
            }
            input.style.position = 'fixed';
            input.style.left = '-10000px';
            let answered = false;
            const answer = (value) => {
                if (answered) return;
                answered = true;
                input.remove();
                resolve(value);
            };
            input.addEventListener('cancel', () => answer({ s: 2 }));
            input.addEventListener('change', () => {
                const files = Array.from(input.files || []);
                if (files.length === 0) {
                    answer({ s: 2 });
                } else if (kind === 3) {
                    const root = { k: 'ud', name: '', children: new Map() };
                    for (const file of files) {
                        const parts = (file.webkitRelativePath || file.name).split('/');
                        root.name = parts[0];
                        let folder = root;
                        for (let i = 1; i < parts.length - 1; i++) {
                            let next = folder.children.get(parts[i]);
                            if (!next) {
                                next = { k: 'ud', name: parts[i], children: new Map() };
                                folder.children.set(parts[i], next);
                            }
                            folder = next;
                        }
                        folder.children.set(file.name, { k: 'u', f: file });
                    }
                    answer(root.name ? { s: 1, d: 2, e: [root] } : { s: 2 });
                } else {
                    answer({ s: 1, d: 2, e: files.map((f) => ({ k: 'u', f: f })) });
                }
            });
            document.body.appendChild(input);
            try {
                input.click();
            } catch (e) {
                answer({ s: 3 });
            }
        });
    })()""",
)

/** Reads a file whole, unless it is longer than [limit] bytes. */
@Suppress("UNUSED_PARAMETER")
private fun readEntry(entry: JsAny, limit: Double): Promise<JsAny?> = js(
    """(async () => {
        const status = (e) => {
            const name = e && e.name;
            if (name === 'NotFoundError') return 2;
            if (name === 'NotAllowedError' || name === 'SecurityError') return 5;
            return 6;
        };
        try {
            let file;
            if (entry.k === 'h') {
                if (entry.h.kind !== 'file') return { s: 6 };
                file = await entry.h.getFile();
            } else if (entry.k === 'u') {
                file = entry.f;
            } else if (entry.k === 'd') {
                if (!entry.last) return { s: 2 };
                if (entry.last.length > limit) return { s: 8 };
                return { s: 1, b: entry.last };
            } else {
                return { s: 6 };
            }
            if (file.size > limit) return { s: 8 };
            return { s: 1, b: new Uint8Array(await file.arrayBuffer()) };
        } catch (e) {
            return { s: status(e) };
        }
    })()""",
)

/** Replaces a file's contents, asking for write permission the first time, or downloads them. */
@Suppress("UNUSED_PARAMETER")
private fun writeEntry(entry: JsAny, bytes: JsAny): Promise<JsAny?> = js(
    """(async () => {
        const status = (e) => {
            const name = e && e.name;
            if (name === 'NotFoundError') return 2;
            if (name === 'QuotaExceededError') return 3;
            if (name === 'NotAllowedError' || name === 'SecurityError') return 5;
            if (name === 'NoModificationAllowedError' || name === 'InvalidModificationError') return 7;
            return 6;
        };
        try {
            if (entry.k === 'u' || entry.k === 'ud') return { s: 7 };
            if (entry.k === 'd') {
                const url = URL.createObjectURL(new Blob([bytes]));
                const link = document.createElement('a');
                link.href = url;
                link.download = entry.name;
                link.style.display = 'none';
                document.body.appendChild(link);
                link.click();
                link.remove();
                setTimeout(() => URL.revokeObjectURL(url), 60000);
                entry.last = bytes;
                return { s: 1 };
            }
            if (entry.h.kind !== 'file') return { s: 6 };
            if (typeof entry.h.queryPermission === 'function') {
                let permission = await entry.h.queryPermission({ mode: 'readwrite' });
                if (permission !== 'granted' && typeof entry.h.requestPermission === 'function') {
                    permission = await entry.h.requestPermission({ mode: 'readwrite' });
                }
                if (permission !== 'granted') return { s: 5 };
            }
            const writable = await entry.h.createWritable();
            try {
                await writable.write(bytes);
                await writable.close();
            } catch (e) {
                try { await writable.abort(); } catch (ignored) {}
                throw e;
            }
            return { s: 1 };
        } catch (e) {
            return { s: status(e) };
        }
    })()""",
)

/** What a folder holds directly. */
@Suppress("UNUSED_PARAMETER")
private fun listEntry(entry: JsAny): Promise<JsAny?> = js(
    """(async () => {
        try {
            if (entry.k === 'ud') {
                return { s: 1, e: Array.from(entry.children.values()) };
            }
            if (entry.k !== 'h' || entry.h.kind !== 'directory') return { s: 6 };
            const found = [];
            for await (const child of entry.h.values()) found.push({ k: 'h', h: child });
            return { s: 1, e: found };
        } catch (e) {
            const name = e && e.name;
            if (name === 'NotFoundError') return { s: 2 };
            if (name === 'NotAllowedError' || name === 'SecurityError') return { s: 5 };
            return { s: 6 };
        }
    })()""",
)

/** The child of a folder by name, a file of that name made when [create] and there is none. */
@Suppress("UNUSED_PARAMETER")
private fun childEntry(entry: JsAny, name: String, create: Boolean): Promise<JsAny?> = js(
    """(async () => {
        const status = (e) => {
            const kind = e && e.name;
            if (kind === 'NotFoundError') return 2;
            if (kind === 'QuotaExceededError') return 3;
            if (kind === 'NotAllowedError' || kind === 'SecurityError') return 5;
            if (kind === 'NoModificationAllowedError') return 7;
            return 6;
        };
        try {
            if (entry.k === 'ud') {
                const child = entry.children.get(name);
                if (child) return { s: 1, e: [child] };
                return { s: create ? 7 : 2 };
            }
            if (entry.k !== 'h' || entry.h.kind !== 'directory') return { s: 6 };
            try {
                return { s: 1, e: [{ k: 'h', h: await entry.h.getFileHandle(name) }] };
            } catch (e) {
                if (e && e.name === 'TypeMismatchError') {
                    return { s: 1, e: [{ k: 'h', h: await entry.h.getDirectoryHandle(name) }] };
                }
                if (!(e && e.name === 'NotFoundError') || !create) throw e;
            }
            return { s: 1, e: [{ k: 'h', h: await entry.h.getFileHandle(name, { create: true }) }] };
        } catch (e) {
            return { s: status(e) };
        }
    })()""",
)

@Suppress("UNUSED_PARAMETER")
private fun resultStatus(result: JsAny): Int = js("result.s")

@Suppress("UNUSED_PARAMETER")
private fun resultDelivery(result: JsAny): Int = js("result.d || 1")

@Suppress("UNUSED_PARAMETER")
private fun resultCount(result: JsAny): Int = js("(result.e ? result.e.length : 0)")

@Suppress("UNUSED_PARAMETER")
private fun resultEntry(result: JsAny, index: Int): JsAny = js("result.e[index]")

@Suppress("UNUSED_PARAMETER")
private fun resultBytes(result: JsAny): JsAny? = js("(result.b || null)")

@Suppress("UNUSED_PARAMETER")
private fun entryName(entry: JsAny): String = js("(entry.k === 'h' ? entry.h.name : entry.k === 'u' ? entry.f.name : entry.name)")

@Suppress("UNUSED_PARAMETER")
private fun entryFolder(entry: JsAny): Boolean = js("(entry.k === 'ud' || (entry.k === 'h' && entry.h.kind === 'directory'))")

/** How an entry's children reach the page: as it did. */
@Suppress("UNUSED_PARAMETER")
private fun entryDelivery(entry: JsAny): Int = js("(entry.k === 'h' ? 1 : entry.k === 'd' ? 3 : 2)")

@Suppress("UNUSED_PARAMETER")
private fun byteLength(array: JsAny): Int = js("array.length")

@Suppress("UNUSED_PARAMETER")
private fun byteAt(array: JsAny, index: Int): Int = js("array[index]")

@Suppress("UNUSED_PARAMETER")
private fun newBytes(length: Int): JsAny = js("new Uint8Array(length)")

@Suppress("UNUSED_PARAMETER")
private fun setByte(array: JsAny, index: Int, value: Int): Unit = js("{ array[index] = value; }")

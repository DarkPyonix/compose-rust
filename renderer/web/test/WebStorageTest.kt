@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.BatchBytes
import dev.darkpyonix.composerust.protocol.IoStatus
import dev.darkpyonix.composerust.protocol.StorageOp
import kotlin.js.Promise
import kotlin.test.Test

/**
 * The application's storage in a real browser's IndexedDB, through the same platform the
 * web renderer installs. Each test uses databases of its own, named for the run, so a page
 * that ran it before leaves nothing it could read.
 *
 * Run with the rest of the web module's tests:
 * ```sh
 * cd renderer && ./kotlin test -p wasmJs --include-module web
 * ```
 */
class WebStorageTest {
    private val storage = WebStorage()

    private fun op(store: String, op: StorageOp, key: String = "", value: ByteArray = ByteArray(0), done: (StorageAnswer) -> Unit) {
        storage.start(StorageRequest(requests++, op, store, key, BatchBytes.of(value)), done)
    }

    /** Runs [steps] one after the other, each handed the way on to the next. */
    private fun sequence(vararg steps: (next: () -> Unit) -> Unit): Promise<JsAny?> =
        Promise { resolve, reject ->
            failWith = { message -> reject(message.toJsString()) }
            fun run(index: Int) {
                if (index == steps.size) {
                    resolve(null)
                    return
                }
                try {
                    steps[index] { run(index + 1) }
                } catch (failure: Throwable) {
                    reject(failure.toString().toJsString())
                }
            }
            run(0)
        }

    /**
     * Fails the running test. The answers arrive in the browser's own callbacks, where a
     * thrown error reaches no test, so the failure rejects the test's promise first.
     */
    private fun expect(condition: Boolean, message: () -> String) {
        if (condition) return
        val text = message()
        failWith?.invoke(text)
        throw AssertionError(text)
    }

    @Test
    fun fr79_web_identifiers_do_not_share_keys(): Promise<JsAny?> {
        val run = runName()
        val first = "test.fr79.first.$run"
        val second = "test.fr79.second.$run"
        return sequence(
            { next ->
                op(first, StorageOp.Write, "shared", "from the first".encodeToByteArray()) { answer ->
                    expect(answer.status == IoStatus.Ok) { "the write answered ${answer.status}" }
                    next()
                }
            },
            { next ->
                op(second, StorageOp.Read, "shared") { answer ->
                    expect(answer.status == IoStatus.NotFound) { "the second application read ${answer.status}" }
                    next()
                }
            },
            { next ->
                op(second, StorageOp.Keys) { answer ->
                    expect(answer.status == IoStatus.Ok && answer.data.isEmpty()) {
                        "the second application listed ${answer.data.decodeToString()}"
                    }
                    next()
                }
            },
            { next ->
                op(first, StorageOp.Read, "shared") { answer ->
                    expect(answer.status == IoStatus.Ok && answer.data.decodeToString() == "from the first") {
                        "the first application read ${answer.status} ${answer.data.size} bytes"
                    }
                    next()
                }
            },
            { next ->
                op(first, StorageOp.Keys) { answer ->
                    expect(answer.data.decodeToString() == "shared") { "the first listed ${answer.data.decodeToString()}" }
                    next()
                }
            },
        )
    }

    /** One operation on one key after another sees the one before, without waiting for it. */
    @Test
    fun fr79_web_operations_on_one_key_apply_in_call_order(): Promise<JsAny?> {
        val store = "test.fr79.order.${runName()}"
        return sequence({ next ->
            op(store, StorageOp.Write, "order", "a".encodeToByteArray()) { }
            op(store, StorageOp.Write, "order", "b".encodeToByteArray()) { }
            op(store, StorageOp.Read, "order") { answer ->
                expect(answer.data.decodeToString() == "b") { "the read saw ${answer.data.decodeToString()}" }
                next()
            }
        })
    }

    @Test
    fun fr79_web_quota_is_reported_not_thrown(): Promise<JsAny?> {
        val store = "test.fr79.quota.${runName()}"
        return sequence(
            { next ->
                op(store, StorageOp.Write, "kept", "old".encodeToByteArray()) { next() }
            },
            { next ->
                failPutsWithQuota()
                op(store, StorageOp.Write, "kept", "new".encodeToByteArray()) { answer ->
                    restorePuts()
                    expect(answer.status == IoStatus.QuotaExceeded) { "a full quota answered ${answer.status}" }
                    next()
                }
            },
            { next ->
                op(store, StorageOp.Read, "kept") { answer ->
                    expect(answer.data.decodeToString() == "old") { "the old value is ${answer.data.decodeToString()}" }
                    next()
                }
            },
        )
    }

    /**
     * The web renderer lets an answer be as long as the largest event buffer its Host lends,
     * so a value far over the first 4096 bytes reads back whole.
     */
    @Test
    fun fr79_web_reads_a_value_larger_than_the_event_buffer(): Promise<JsAny?> {
        val store = "test.fr79.larger.${runName()}"
        val previous = Storage.maxAnswerBytes
        Storage.maxAnswerBytes = EVENT_BUFFER_MAX_BYTES - 32
        val length = 1024 * 1024
        val value = ByteArray(length) { (it % 251).toByte() }
        return sequence(
            { next ->
                op(store, StorageOp.Write, "large", value) { answer ->
                    expect(answer.status == IoStatus.Ok) { "the write answered ${answer.status}" }
                    next()
                }
            },
            { next ->
                op(store, StorageOp.Read, "large") { answer ->
                    Storage.maxAnswerBytes = previous
                    expect(answer.status == IoStatus.Ok) { "the read answered ${answer.status}" }
                    expect(answer.data.contentEquals(value)) {
                        "the read gave back ${answer.data.size} bytes, not the $length written"
                    }
                    next()
                }
            },
        )
    }

    /**
     * An answer longer than the limit comes back as one too-large answer carrying its
     * length. The limit here is the first event buffer, which is what the web carried before
     * its buffer could grow, so the case stays small.
     */
    @Test
    fun fr79_web_an_answer_over_the_event_buffer_is_too_large(): Promise<JsAny?> {
        val store = "test.fr79.large.${runName()}"
        val previous = Storage.maxAnswerBytes
        Storage.maxAnswerBytes = EVENT_BUFFER_BYTES - 32
        val length = 1024 * 1024
        return sequence(
            { next ->
                op(store, StorageOp.Write, "large", ByteArray(length) { (it % 251).toByte() }) { answer ->
                    expect(answer.status == IoStatus.Ok) { "the write answered ${answer.status}" }
                    next()
                }
            },
            { next ->
                op(store, StorageOp.Read, "large") { answer ->
                    Storage.maxAnswerBytes = previous
                    expect(answer.status == IoStatus.TooLarge) { "the read answered ${answer.status}" }
                    val carried = (answer.data[0].toInt() and 0xff) or
                        ((answer.data[1].toInt() and 0xff) shl 8) or
                        ((answer.data[2].toInt() and 0xff) shl 16) or
                        ((answer.data[3].toInt() and 0xff) shl 24)
                    expect(carried == length) { "the answer said $carried bytes" }
                    next()
                }
            },
        )
    }

    /** Where a failed expectation goes: the promise the running test returned. */
    private var failWith: ((String) -> Unit)? = null

    private companion object {
        var requests = 1
    }
}

/** A name for this run, so databases from an earlier run are not the ones read. */
@JsFun("() => String(Date.now()) + '.' + String(Math.floor(Math.random() * 1e9))")
private external fun runName(): String

/** Makes every `put` throw what a full quota throws, until [restorePuts]. */
@JsFun(
    """() => {
  const prototype = IDBObjectStore.prototype;
  if (!prototype.__composeRustPut) prototype.__composeRustPut = prototype.put;
  prototype.put = function () { throw new DOMException('the quota is full', 'QuotaExceededError'); };
}""",
)
private external fun failPutsWithQuota()

@JsFun(
    """() => {
  const prototype = IDBObjectStore.prototype;
  if (prototype.__composeRustPut) {
    prototype.put = prototype.__composeRustPut;
    delete prototype.__composeRustPut;
  }
}""",
)
private external fun restorePuts()

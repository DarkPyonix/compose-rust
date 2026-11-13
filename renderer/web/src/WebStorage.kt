@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.BatchBytes
import dev.darkpyonix.composerust.protocol.IoStatus
import dev.darkpyonix.composerust.protocol.StorageOp

/**
 * The application's storage in a browser: one IndexedDB database per application
 * identifier, `compose-rust/<identifier>`, with one object store `values` whose keys are the
 * application's keys as they are.
 *
 * One database per identifier because every sample on the guide site shares one origin, and
 * an origin has one IndexedDB namespace: without it two applications would see each other's
 * keys.
 *
 * Every operation is its own transaction, created in the order the Host asked, so the
 * browser applies the operations on one key in that order: IndexedDB runs transactions whose
 * scopes overlap in the order they were created. While the database is still opening, the
 * operations wait in that order in a list kept beside it on the JavaScript side, and run as
 * soon as it is open.
 *
 * The value to write is copied out of the shared memory with `slice()` while the batch that
 * carries it is applied, because the Host may write over that memory as soon as the call
 * returns. A view cannot be handed to `put` instead: cloning a view clones the whole memory
 * it looks into. The browser's clone of that slice is the write itself.
 *
 * Nothing here throws to the Host. A browser without IndexedDB, a page that may not use it
 * (a private window in some browsers), a full quota and a failed transaction each become the
 * operation's one answer.
 */
class WebStorage : StoragePlatform {
    override fun start(request: StorageRequest, answer: (StorageAnswer) -> Unit) {
        val value = if (request.op == StorageOp.Write) jsBytes(request.data) else null
        storageRun(
            request.store,
            opCode(request.op),
            request.key,
            value,
            Storage.maxAnswerBytes,
        ) { status, bytes, length, reason ->
            answer(answerFrom(status, bytes, length, reason))
        }
    }

    private fun answerFrom(status: Int, bytes: JsAny?, length: Int, reason: String): StorageAnswer =
        when (status) {
            STATUS_OK -> StorageAnswer(IoStatus.Ok, if (bytes == null) ByteArray(0) else kotlinBytes(bytes, length))
            STATUS_NOT_FOUND -> StorageAnswer(IoStatus.NotFound)
            STATUS_QUOTA -> StorageAnswer(IoStatus.QuotaExceeded)
            STATUS_UNAVAILABLE -> StorageAnswer.saying(IoStatus.Unavailable, reason)
            STATUS_TOO_LARGE -> StorageAnswer.tooLarge(length)
            else -> StorageAnswer.saying(IoStatus.Failed, reason)
        }

    companion object {
        // The words the JavaScript half answers with, the same numbers as `IoStatus` on the wire.
        private const val STATUS_OK = 1
        private const val STATUS_NOT_FOUND = 2
        private const val STATUS_QUOTA = 3
        private const val STATUS_UNAVAILABLE = 4
        private const val STATUS_TOO_LARGE = 8

        private fun opCode(op: StorageOp): Int = when (op) {
            StorageOp.Read -> 1
            StorageOp.Write -> 2
            StorageOp.Remove -> 3
            StorageOp.Keys -> 4
        }

        /**
         * The bytes as a browser array of their own. Read straight out of the shared memory
         * when they are in the Host's batch; a buffer over a Kotlin array (a test) is copied
         * byte by byte.
         */
        internal fun jsBytes(data: BatchBytes): JsAny {
            val address = data.buffer.linearAddress(data.index)
            if (address >= 0) return copyFromMemory(address, data.size)
            val array = newBytes(data.size)
            val copy = data.copy()
            for (index in copy.indices) setByte(array, index, copy[index].toInt())
            return array
        }

        /** A browser array's first [length] bytes, as a Kotlin array. */
        internal fun kotlinBytes(array: JsAny, length: Int): ByteArray =
            ByteArray(length) { index -> byteAt(array, index).toByte() }
    }
}

/** `new Uint8Array(memory.buffer, address, length).slice()`: one copy, out of the shared memory. */
@JsFun("(address, length) => new Uint8Array(wasmExports.memory.buffer, address, length).slice()")
private external fun copyFromMemory(address: Int, length: Int): JsAny

@JsFun("(length) => new Uint8Array(length)")
private external fun newBytes(length: Int): JsAny

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setByte(array: JsAny, index: Int, value: Int)

@JsFun("(array, index) => array[index]")
private external fun byteAt(array: JsAny, index: Int): Int

/**
 * Carries out one operation on the database for [store] and calls [done] once with a status
 * word, the bytes (a `Uint8Array`, or null), their length, and a sentence for a failure.
 *
 * For a read whose value is longer than [maxAnswer] the answer is too large, carrying only the
 * length, so a value that the event cannot carry is never copied over to be dropped.
 */
@JsFun(
    """(store, op, key, value, maxAnswer, done) => {
  const OK = 1, NOT_FOUND = 2, QUOTA = 3, UNAVAILABLE = 4, FAILED = 6, TOO_LARGE = 8;
  let answered = false;
  const answer = (status, bytes, length, reason) => {
    if (answered) return;
    answered = true;
    done(status, bytes, length, reason || '');
  };
  const failure = (error, what) => {
    const name = error && error.name ? error.name : '';
    if (name === 'QuotaExceededError') return answer(QUOTA, null, 0, '');
    if (name === 'SecurityError' || name === 'InvalidStateError') {
      return answer(UNAVAILABLE, null, 0, 'the browser does not let this page use IndexedDB (' + name + ')');
    }
    answer(FAILED, null, 0, what + (name ? ' (' + name + ')' : ''));
  };
  const all = globalThis.__composeRustStorage || (globalThis.__composeRustStorage = new Map());
  let database = all.get(store);
  if (!database) {
    database = { handle: null, failed: null, waiting: [] };
    all.set(store, database);
    const opened = () => {
      const waiting = database.waiting;
      database.waiting = null;
      for (const run of waiting) run();
    };
    try {
      if (typeof indexedDB === 'undefined' || indexedDB === null) {
        database.failed = 'this browser has no IndexedDB';
        opened();
      } else {
        const request = indexedDB.open('compose-rust/' + store, 1);
        request.onupgradeneeded = () => {
          if (!request.result.objectStoreNames.contains('values')) {
            request.result.createObjectStore('values');
          }
        };
        request.onsuccess = () => { database.handle = request.result; opened(); };
        request.onerror = () => {
          const name = request.error && request.error.name ? request.error.name : 'Error';
          database.failed = 'the browser could not open the database (' + name + ')';
          opened();
        };
      }
    } catch (error) {
      database.failed = 'the browser refused to open the database (' + (error && error.name) + ')';
      opened();
    }
  }
  const run = () => {
    if (database.failed !== null) return answer(UNAVAILABLE, null, 0, database.failed);
    let transaction, values;
    try {
      transaction = database.handle.transaction('values', op === 1 || op === 4 ? 'readonly' : 'readwrite');
      values = transaction.objectStore('values');
    } catch (error) {
      return failure(error, 'the browser could not start a transaction');
    }
    transaction.onabort = () => failure(transaction.error, 'the browser abandoned the transaction');
    try {
      if (op === 1) {
        const request = values.get(key);
        request.onsuccess = () => {
          const result = request.result;
          if (result === undefined) return answer(NOT_FOUND, null, 0, '');
          const bytes = new Uint8Array(result);
          if (bytes.length > maxAnswer) return answer(TOO_LARGE, null, bytes.length, '');
          answer(OK, bytes, bytes.length, '');
        };
      } else if (op === 2) {
        values.put(value.buffer, key);
        transaction.oncomplete = () => answer(OK, null, 0, '');
      } else if (op === 3) {
        values.delete(key);
        transaction.oncomplete = () => answer(OK, null, 0, '');
      } else if (op === 4) {
        const request = values.getAllKeys();
        request.onsuccess = () => {
          const keys = request.result.map(String).sort();
          const bytes = new TextEncoder().encode(keys.join('\u0000'));
          if (bytes.length > maxAnswer) return answer(TOO_LARGE, null, bytes.length, '');
          answer(OK, bytes, bytes.length, '');
        };
      } else {
        answer(FAILED, null, 0, 'unknown storage operation ' + op);
      }
    } catch (error) {
      try { transaction.abort(); } catch (ignored) {}
      failure(error, 'the browser refused the operation');
    }
  };
  if (database.waiting !== null) database.waiting.push(run); else run();
}""",
)
private external fun storageRun(
    store: String,
    op: Int,
    key: String,
    value: JsAny?,
    maxAnswer: Int,
    done: (Int, JsAny?, Int, String) -> Unit,
)

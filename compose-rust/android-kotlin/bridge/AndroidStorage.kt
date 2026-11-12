package dev.darkpyonix.composerust.ui.platform

import androidx.activity.ComponentActivity

/**
 * Tells the Host where the application's files directory is, which it needs for its storage
 * and cannot find on its own: only a `Context` knows it. The Host keeps the values under
 * `compose-rust/storage` inside it.
 *
 * Called by the generated Activity on every creation, before the Host is asked for. The Host
 * hears it once, as its first event after the first batch and before the first frame; a
 * storage operation the application starts before then waits for it.
 */
fun installStorageLocation(activity: ComponentActivity) {
    ComposeRustRuntime.storageLocation = activity.applicationContext.filesDir.absolutePath
}

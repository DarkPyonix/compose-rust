package dev.darkpyonix.composerust.ui.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher

/**
 * Makes [dispatcher] the answer to `Dispatchers.Main` for the rest of the process.
 *
 * The one place this renderer reaches into kotlinx.coroutines' internals, kept in a file of its
 * own so that it is found and replaced in one place. On Linux, kotlinx.coroutines has no main
 * dispatcher and no public way to name one: `Dispatchers.Main` answers with a stand-in that
 * throws at the first dispatch. What it does have is `Dispatchers.injectMain`, an internal
 * function marked `@PublishedApi` that its own test library calls to implement `setMain`. This
 * calls it the same way the test library does, without making the test library a dependency of
 * a shipped renderer. `injectMain` is the same in kotlinx.coroutines 1.9.0, 1.10.2 and 1.11.0,
 * and the version this module builds with is pinned in `renderer/libs.versions.toml`, so an
 * upgrade that renames it fails to compile here, and `MainDispatcherNameTest` fails a build that
 * compiled and installed nothing.
 *
 * Called before anything reads `Dispatchers.Main`: Compose asks once, the first time its native
 * dispatch code is used, and keeps what it was told. Asked before this ran, it was told there is
 * none and keeps running its own work in place.
 */
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
internal fun installMainDispatcher(dispatcher: MainCoroutineDispatcher) {
    Dispatchers.injectMain(dispatcher)
}

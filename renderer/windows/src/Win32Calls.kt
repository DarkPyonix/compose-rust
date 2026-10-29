@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar

// The calls of the Compose fork's `extended/window/windows-shared/c/win32_window.c` that
// several windows in one process need and its Kotlin layer does not hand out: the selection
// of the window the calls that name none are about, the per-window state that is read once
// (activation, a move, the end of a follow, a request to close), and the placement calls.
// Reached by symbol name, as the toasts are (`Win32Notifications.kt`): the C is linked into
// the same executable, and naming it here asks nothing of how the fork's klib is resolved.
// The header is `cinterop/include/win32_window.h` in the fork, section "Several windows".

@SymbolName("dxc_native_window_select")
private external fun nativeWindowSelect(window: COpaquePointer?)

@SymbolName("dxc_native_current_window")
private external fun nativeCurrentWindow(): Long

@SymbolName("dxc_native_window_count")
private external fun nativeWindowCount(): Int

@SymbolName("dxc_native_window_configure_position")
private external fun nativeConfigurePosition(hasPosition: Int, x: Int, y: Int)

@SymbolName("dxc_native_window_set_close_deferred")
private external fun nativeSetCloseDeferred(deferred: Int)

@SymbolName("dxc_native_take_close_request")
private external fun nativeTakeCloseRequest(): Int

@SymbolName("dxc_native_window_destroy")
private external fun nativeWindowDestroy(window: COpaquePointer?)

@SymbolName("dxc_native_window_release")
private external fun nativeWindowRelease(window: COpaquePointer?)

@SymbolName("dxc_native_window_set_position")
private external fun nativeSetPosition(x: Int, y: Int)

@SymbolName("dxc_native_window_set_content_size")
private external fun nativeSetContentSize(width: Int, height: Int)

@SymbolName("dxc_native_window_bounds")
private external fun nativeBounds(
    x: CPointer<IntVar>?,
    y: CPointer<IntVar>?,
    width: CPointer<IntVar>?,
    height: CPointer<IntVar>?,
): Int

@SymbolName("dxc_native_window_set_title")
private external fun nativeSetTitle(title: CPointer<ByteVar>?)

@SymbolName("dxc_native_window_set_min_size")
private external fun nativeSetMinSize(width: Int, height: Int)

@SymbolName("dxc_native_window_focus")
private external fun nativeFocus(): Int

@SymbolName("dxc_native_window_is_active")
private external fun nativeIsActive(): Int

@SymbolName("dxc_native_take_activation")
private external fun nativeTakeActivation(active: CPointer<IntVar>?): Int

@SymbolName("dxc_native_take_moved")
private external fun nativeTakeMoved(
    x: CPointer<IntVar>?,
    y: CPointer<IntVar>?,
    width: CPointer<IntVar>?,
    height: CPointer<IntVar>?,
): Int

@SymbolName("dxc_native_pointer_on_screen")
private external fun nativePointerOnScreen(
    x: CPointer<FloatVar>?,
    y: CPointer<FloatVar>?,
    primaryDown: CPointer<IntVar>?,
): Int

@SymbolName("dxc_native_follow_start")
private external fun nativeFollowStart(anchorX: Float, anchorY: Float): Int

@SymbolName("dxc_native_take_follow_end")
private external fun nativeTakeFollowEnd(cancelled: CPointer<IntVar>?): Int

/** Chooses the window the calls that name none run for. */
internal fun selectWindow(window: COpaquePointer?) = nativeWindowSelect(window)

/** The handle of the window whose message is being handled, so a draw callback finds its window. */
internal fun currentWindow(): Long = nativeCurrentWindow()

/** How many windows the C side holds open. */
internal fun nativeOpenWindows(): Int = nativeWindowCount()

/** Where the next window opens, in points; [hasPosition] 0 lets the system choose. */
internal fun configurePosition(hasPosition: Int, x: Int, y: Int) = nativeConfigurePosition(hasPosition, x, y)

/** Holds the selected window's request to close for the application instead of closing it. */
internal fun setCloseDeferred(deferred: Int) = nativeSetCloseDeferred(deferred)

/** True once for each request to close the selected window that the system made. */
internal fun takeCloseRequest(): Boolean = nativeTakeCloseRequest() != 0

internal fun destroyHandle(window: COpaquePointer?) = nativeWindowDestroy(window)

internal fun releaseHandle(window: COpaquePointer?) = nativeWindowRelease(window)

internal fun setWindowPosition(x: Int, y: Int) = nativeSetPosition(x, y)

internal fun setWindowContentSize(width: Int, height: Int) = nativeSetContentSize(width, height)

internal fun setWindowTitle(title: CPointer<ByteVar>?) = nativeSetTitle(title)

internal fun setWindowMinSize(width: Int, height: Int) = nativeSetMinSize(width, height)

/** False where the foreground lock refused: the window's taskbar button flashes instead. */
internal fun focusWindow(): Boolean = nativeFocus() != 0

/** Whether the selected window is the active one, as WM_ACTIVATE last said. */
internal fun windowIsActive(): Boolean = nativeIsActive() != 0

internal fun windowBounds(
    x: CPointer<IntVar>?,
    y: CPointer<IntVar>?,
    width: CPointer<IntVar>?,
    height: CPointer<IntVar>?,
): Boolean = nativeBounds(x, y, width, height) != 0

internal fun takeActivation(active: CPointer<IntVar>?): Boolean = nativeTakeActivation(active) != 0

internal fun takeMoved(
    x: CPointer<IntVar>?,
    y: CPointer<IntVar>?,
    width: CPointer<IntVar>?,
    height: CPointer<IntVar>?,
): Boolean = nativeTakeMoved(x, y, width, height) != 0

internal fun readPointerOnScreen(
    x: CPointer<FloatVar>?,
    y: CPointer<FloatVar>?,
    primaryDown: CPointer<IntVar>?,
): Boolean = nativePointerOnScreen(x, y, primaryDown) != 0

internal fun startFollow(anchorX: Float, anchorY: Float): Boolean = nativeFollowStart(anchorX, anchorY) != 0

internal fun takeFollowEnd(cancelled: CPointer<IntVar>?): Boolean = nativeTakeFollowEnd(cancelled) != 0

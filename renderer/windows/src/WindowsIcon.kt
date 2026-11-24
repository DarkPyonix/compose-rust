@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.ui.node.Asset
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.coroutines.delay
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.thisisthepy.compose.window.DockIcon
import org.thisisthepy.compose.window.windows.c.dxc_native_set_icon

// The window's icon on Windows: the caption, the task bar and Alt+Tab. The asset the
// application named (SetWindow.icon, or a Window node's WindowIcon) arrives a little after the
// batch that names it, so it is looked for until it is there, through the window module's
// DockIcon as on macOS.

/** [picture] as unpadded RGBA rows, premultiplied as the window module takes them, or null. */
private fun iconRgba(picture: ImageBitmap): Triple<ByteArray, Int, Int>? {
    val bitmap = picture.asSkiaBitmap()
    val info = ImageInfo(bitmap.width, bitmap.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    val rgba = bitmap.readPixels(info, info.minRowBytes) ?: return null
    return Triple(rgba, bitmap.width, bitmap.height)
}

/** Puts [picture] on [window] (dxc_native_set_icon on the selected window). Answers whether it could. */
internal fun applyWindowsIcon(window: Win32Window, picture: ImageBitmap): Boolean {
    val (rgba, width, height) = iconRgba(picture) ?: return false
    window.select()
    memScoped {
        val bytes = allocArray<UByteVar>(rgba.size)
        for (index in rgba.indices) bytes[index] = rgba[index].toUByte()
        dxc_native_set_icon(bytes, width, height)
    }
    return true
}

/**
 * Looks for asset [assetId] until it is there and puts it on [window] once. Nothing for 0, the
 * application naming no icon.
 */
@Composable
internal fun WindowsIconEffect(host: ComposeRustHost, window: Win32Window, assetId: Int) {
    if (assetId == 0) return
    LaunchedEffect(window, assetId) {
        val icon = DockIcon(
            lookup = { id -> (host.table.assets.asset(id) as? Asset.Raster)?.bitmap },
            apply = { picture -> applyWindowsIcon(window, picture) },
        )
        while (!icon.tryApply(assetId)) delay(100)
    }
}

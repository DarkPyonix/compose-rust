@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.windows.SystemParametersInfoW

/** SPI_GETCLIENTAREAANIMATION: whether Windows shows animations, the switch the reduced-motion setting reads. */
private const val SPI_GETCLIENTAREAANIMATION = 0x1042u

/** Windows' "Show animations in Windows", or null when the system could not be asked. */
internal fun win32ClientAreaAnimation(): Boolean? = memScoped {
    val on = alloc<IntVar>()
    if (SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0u, on.ptr, 0u) == 0) null else on.value != 0
}

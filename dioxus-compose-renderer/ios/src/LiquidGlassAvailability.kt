package dioxus.compose.ui.platform

import kotlinx.cinterop.convert
import kotlinx.cinterop.cValue
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo

/**
 * The first iOS that draws its own chrome as Liquid Glass.
 *
 * Below it the system has no glass to give, so there is nothing to take and the renderer
 * draws the bar itself, as it does on every other platform.
 */
internal const val LIQUID_GLASS_IOS_MAJOR = 26

/**
 * Whether the system running this process draws Liquid Glass.
 *
 * The comparison is Apple's rather than ours. `UIDevice.systemVersion` is a string that can
 * arrive as "26", "26.0" or a beta spelling, and turning that into a number is code with a
 * bug in it; `NSProcessInfo` already holds the three fields and already compares them.
 *
 * Asking about the version rather than probing a selector is deliberate. What this decides
 * is not whether one method exists but which shell gets built, and the thing that matters
 * most about iOS 26, that the system puts glass on a `UITabBar` without being asked, is a
 * drawing behaviour and not a selector anything can be asked about. The selector probes are
 * still there, one per property that exists only on 26, at the point where each is set.
 */
internal fun systemDrawsLiquidGlass(): Boolean = systemIsAtLeast(LIQUID_GLASS_IOS_MAJOR)

/** Whether the running system is at least this version. */
internal fun systemIsAtLeast(major: Int, minor: Int = 0, patch: Int = 0): Boolean =
    NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(
        cValue<NSOperatingSystemVersion> {
            majorVersion = major.convert()
            minorVersion = minor.convert()
            patchVersion = patch.convert()
        },
    )

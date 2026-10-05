package dev.darkpyonix.composerust.ui.platform

/**
 * Whether this build opens the Java toolkit's window anywhere.
 *
 * Which window a platform opens is decided at run time from the operating system, so every
 * window's code is reachable from the one entry point of the image, and the toolkit's window
 * pulls the whole Java toolkit in with it: `java.awt`, Swing, the native libraries they load,
 * on a platform that never opens that window.
 *
 * A native image build that has no use for the toolkit's window says so with
 * `-Ddxc.toolkit.window=false`. This class is initialised while the image is built (see
 * native-image.properties), so [available] is a constant by then, the branch that would open
 * the toolkit's window is dead, and the analysis never walks it. A development run on a JVM
 * sets nothing and keeps the toolkit's window.
 */
internal object ToolkitWindow {
    @JvmField
    val available: Boolean = System.getProperty("dxc.toolkit.window") != "false"
}

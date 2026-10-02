package dioxus.compose.ui.platform

import org.graalvm.nativeimage.hosted.Feature

/**
 * Registers the AWT text input path for the image.
 *
 * The platform calls into Java through JNI to drive an input method: it asks which character
 * a point maps to, where the caret is, what the composed text is. Those Java methods are
 * private and reached only from Objective-C, so the closed-world analysis cannot see them.
 * Missing one does not fail the build; it aborts the process as an Objective-C exception the
 * first time an IME touches a text field, which is how this was found.
 *
 * Listing methods by hand is the wrong shape of solution: the list is long, it is invisible
 * to the compiler, and it silently rots when the JDK changes. This registers whole packages
 * instead, letting the image carry a little more than it strictly needs. See
 * [ReachabilityRegistration] for how members are registered and why native methods are skipped.
 */
class ImeReachabilityFeature : Feature {

    override fun getDescription(): String = "Registers the AWT input method path for JNI"

    override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
        val classes = ReachabilityRegistration.classesIn("java.desktop", PACKAGES) + EXTRA_CLASSES
        ReachabilityRegistration.registerAll(classes)
    }

    private companion object {
        /** Everything that participates in input method handling on the AWT side. */
        val PACKAGES = setOf("sun.lwawt.macosx", "sun.awt.im", "java.awt.im", "java.awt.im.spi")

        /** Types the input method exchanges with the platform, from packages too broad to take whole. */
        val EXTRA_CLASSES = listOf(
            "java.awt.event.InputMethodEvent",
            "java.awt.font.TextAttribute",
            "java.awt.font.TextHitInfo",
            "java.text.AttributedString",
            "java.text.AttributedCharacterIterator",
            "java.text.AttributedCharacterIterator\$Attribute",
        )
    }
}

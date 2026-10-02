package dioxus.compose.ui.platform

import org.graalvm.nativeimage.hosted.Feature

/**
 * Links Skia into the image instead of loading it from a file beside it.
 *
 * Skia reaches this process through JNI, and a JNI library is normally a file the runtime
 * opens by name. That is one more file every application has to carry: 21MB beside the
 * renderer on macOS, and on Windows a 14MB DLL plus a 10MB data file that a single
 * executable cannot absorb. The archive is built by
 * experiments/static-library/build-static-skiko.sh from the same object files and Skia
 * archives the shared library is linked from, so nothing here is a different Skia.
 *
 * The three calls below are what tells the image that a named library is already inside
 * it: stop trying to open a file for it, treat calls in these packages as belonging to it,
 * and link this archive. Nothing else changes, and the Java side goes on calling
 * System.loadLibrary as it always has.
 *
 * NONE OF THIS IS A PUBLIC API. It lives under com.oracle.svm.core, it is documented
 * nowhere, and the people who wrote about it say plainly that it may not survive a GraalVM
 * release. The build that uses it says which version it was checked against, and this fails
 * loudly rather than quietly falling back, because a silent fallback here is a renderer
 * that loads a Skia from somewhere else and appears to work.
 */
class StaticSkikoFeature : Feature {

    override fun getDescription(): String = "Links Skia into the image rather than beside it"

    override fun isInConfiguration(access: Feature.IsInConfigurationAccess): Boolean =
        System.getProperty(ENABLED_PROPERTY) == "true"

    override fun afterRegistration(access: Feature.AfterRegistrationAccess) {
        val support = lookup("com.oracle.svm.core.jdk.NativeLibrarySupport")
        support.javaClass.getMethod("preregisterUninitializedBuiltinLibrary", String::class.java)
            .invoke(support, LIBRARY)

        val platform = lookup("com.oracle.svm.core.jdk.PlatformNativeLibrarySupport")
        val addPrefix = platform.javaClass.getMethod("addBuiltinPkgNativePrefix", String::class.java)
        // Every JNI entry point in the archive is under one of these two, so a call that
        // starts with either is a call into the library that is now part of this image.
        for (prefix in PREFIXES) {
            addPrefix.invoke(platform, prefix)
        }
    }

    override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
        // The linker has to be told to pull the archive in. This reaches the build's own
        // NativeLibraries through the access object, which is the only route to it.
        val libraries = access.javaClass.getMethod("getNativeLibraries").invoke(access)
        libraries.javaClass.getMethod("addStaticJniLibrary", String::class.java, Array<String>::class.java)
            .invoke(libraries, LIBRARY, emptyArray<String>())
    }

    private fun lookup(name: String): Any {
        val type = Class.forName(name)
        return type.getMethod("singleton").invoke(null)
            ?: error(
                "$name has no singleton. This build links Skia into the image through an " +
                    "internal interface that is not promised to keep working, and it has " +
                    "stopped working. Either restore the interface or build with " +
                    "$ENABLED_PROPERTY unset, which goes back to loading Skia from a file."
            )
    }

    private companion object {
        /** The name the Java side passes to System.loadLibrary. */
        const val LIBRARY = "skiko"

        /** The packages whose native methods belong to that library. */
        val PREFIXES = listOf("org_jetbrains_skia", "org_jetbrains_skiko")

        /** Off unless the build asks for it, so an ordinary build is unaffected. */
        const val ENABLED_PROPERTY = "dioxus.compose.staticSkiko"
    }
}

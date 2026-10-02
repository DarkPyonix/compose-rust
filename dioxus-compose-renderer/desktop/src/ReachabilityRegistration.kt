package dioxus.compose.ui.platform

import org.graalvm.nativeimage.hosted.RuntimeJNIAccess
import org.graalvm.nativeimage.hosted.RuntimeReflection
import java.lang.reflect.Modifier

/**
 * Shared registration helpers for the Features that keep JDK internal JNI paths reachable
 * for text input and accessibility.
 *
 * Several parts of `java.desktop` are entered only from Objective-C. The closed-world
 * analysis cannot see those entries, so each one has to be registered by hand. The failure
 * mode is not a build error: a JNI lookup that misses simply returns null, and the platform
 * code carries that null forward until AppKit raises an Objective-C exception and the
 * process aborts with no Java stack trace.
 *
 * Registering whole packages rather than hand-listed members is deliberate. The lists are
 * long, invisible to the compiler, and rot silently when the JDK changes, so the image
 * carries a little more than it strictly needs in exchange for not breaking quietly.
 */
internal object ReachabilityRegistration {

    /**
     * Registers [type] and its declared members for both reflection and JNI.
     *
     * Native methods are deliberately skipped. Registering one makes the image link against
     * its symbol, and the JDK declares some native methods it does not ship, so the library
     * would then fail to load. The JDK's own registration covers the native side.
     */
    fun register(type: Class<*>) {
        RuntimeReflection.register(type)
        RuntimeJNIAccess.register(type)
        runCatching {
            val members = type.declaredMethods.filterNot { Modifier.isNative(it.modifiers) }
            RuntimeReflection.register(*members.toTypedArray())
            RuntimeJNIAccess.register(*members.toTypedArray())
            RuntimeReflection.register(*type.declaredConstructors)
            RuntimeJNIAccess.register(*type.declaredConstructors)
            RuntimeReflection.register(*type.declaredFields)
            RuntimeJNIAccess.register(*type.declaredFields)
        }
    }

    /** Loads and registers every class named in [classNames] that resolves. */
    fun registerAll(classNames: Iterable<String>) {
        for (name in classNames) {
            val type = runCatching { Class.forName(name, false, ClassLoader.getSystemClassLoader()) }
                .getOrNull() ?: continue
            register(type)
        }
    }

    /** Class names in the boot module [moduleName] whose package is one of [packages]. */
    fun classesIn(moduleName: String, packages: Set<String>): List<String> {
        val module = ModuleLayer.boot().findModule(moduleName).orElse(null) ?: return emptyList()
        return module.packages
            .filter { it in packages }
            .flatMap { pkg ->
                runCatching {
                    ModuleLayer.boot().configuration().findModule(module.name).get()
                        .reference().open().use { reader ->
                            reader.list().use { entries ->
                                entries.filter { it.endsWith(".class") }
                                    .map { it.removeSuffix(".class").replace('/', '.') }
                                    .filter { it.substringBeforeLast('.') == pkg }
                                    .toList()
                            }
                        }
                }.getOrDefault(emptyList())
            }
    }
}

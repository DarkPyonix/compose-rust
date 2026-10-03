@file:Suppress("PackageDirectoryMismatch")

package java.nio.charset

/**
 * The slice of `java.nio.charset` the generated protocol codec uses. See `JavaNioShim.kt`,
 * of which each JDK-less module has its own, over the memory that module can address.
 *
 * Compiled by the iOS module and, through a symlink, by the web module.
 *
 * Only the charset itself, because the codec no longer decodes through a `CharsetDecoder`.
 * It checks that a range of the arena is UTF-8 where it lies and then builds one `String`
 * from it, so the charset is here to name what the bytes are: `StandardCharsets.UTF_8` is
 * the argument the codec passes to the string shims in `ProtocolCompatShims.kt`, which
 * check it rather than interpret it.
 */
class Charset internal constructor(val name: String)

object StandardCharsets {
    val UTF_8 = Charset("UTF-8")
}

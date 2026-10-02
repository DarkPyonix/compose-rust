package dioxus.compose.ui.node

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Brush as ComposeBrush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import dioxus.compose.protocol.AssetKind
import dioxus.compose.protocol.IconRole
import java.lang.InterruptedException

/**
 * What one registered asset became once the Renderer read it.
 *
 * Nothing here points back at the batch buffer. The buffer is only valid for the call that
 * carried it, and an asset has to outlive the frame that draws it, so registration is where
 * the bytes stop being borrowed: a raster is decoded into a bitmap, a vector is parsed into
 * a document, and a symbol keeps nothing but the meaning it named.
 */
sealed interface Asset {
    /** A decoded PNG or JPEG. */
    class Raster(val bitmap: ImageBitmap) : Asset

    /** A parsed vector document, drawn at whatever size the modifier chain gives it. */
    class Vector(val document: VectorDocument) : Asset

    /**
     * A face the application shipped, which a type role can be resolved to.
     *
     * The family and not the bytes: reading a font file is done once at registration, and
     * every piece of text in that role after it is a lookup.
     */
    class Font(val family: FontFamily) : Asset

    /**
     * A gradient or a picture laid out as a fill, ready for anywhere a colour can go.
     *
     * Read once at registration. A frame painted with it is a lookup and a draw.
     */
    class Brush(val brush: ComposeBrush) : Asset

    /**
     * One meaning out of the closed set, with no artwork attached.
     *
     * The artwork belongs to the design system: this is what lets the same registration
     * come out as an SF Symbols shape under Cupertino and a Material Symbols shape under
     * Material 3, which a picture or a system icon name could never do.
     */
    data class Symbol(val role: IconRole) : Asset
}

/**
 * The assets the Host registered, by id.
 *
 * The Host owns the lifetime: `RegisterAsset` puts one in, `ReleaseAsset` takes it out, and
 * an id that names neither is a reported protocol error rather than a guess or a crash.
 *
 * Reading is a map lookup and allocates nothing, so a frame that draws the same picture
 * again costs a lookup and the draw.
 */
class AssetCache {
    private val entries = mutableStateMapOf<Int, Asset>()

    fun asset(assetId: Int): Asset? = entries[assetId]

    /** Registered ids, for tests that check what the cache is holding. */
    internal val size: Int get() = entries.size

    /**
     * Reads the bytes and keeps the result. Returns the error to report, or null.
     *
     * `kindTag` is the raw wire tag: a kind this Renderer cannot read has to be reported
     * without stopping the rest of the batch.
     */
    internal fun register(assetId: Int, kindTag: Int, bytes: ByteArray): TableError? {
        val kind = AssetKind.entries.getOrNull(kindTag - 1)
            ?: return TableError(
                TableError.UNSUPPORTED_ASSET,
                "asset $assetId has kind tag $kindTag, which this renderer cannot read",
            )
        val asset = try {
            decode(kind, bytes)
        } catch (error: Throwable) {
            // A corrupt or truncated file is the Host's mistake and gets reported as one.
            // Letting the decoder's exception out would take the composition down with it.
            if (error is InterruptedException) throw error
            return TableError(
                TableError.UNREADABLE_ASSET,
                "asset $assetId could not be read as $kind: ${error.message ?: "unreadable"}",
            )
        }
        if (asset == null) {
            val reason = if (kind == AssetKind.VectorIcon) {
                "does not name one of the icons this renderer draws"
            } else {
                "could not be read as $kind by this platform's graphics"
            }
            return TableError(TableError.UNREADABLE_ASSET, "asset $assetId $reason")
        }
        entries[assetId] = asset
        return null
    }

    /**
     * Forgets every registration, for a Renderer that is throwing its tree away and asking
     * the Host to send the whole of it again. The ids in the new batch are the Host's to
     * assign from nothing, so keeping the old entries would leave pictures nobody names.
     */
    internal fun clear() = entries.clear()

    internal fun release(assetId: Int): TableError? {
        if (entries.remove(assetId) == null) {
            return TableError(
                TableError.UNKNOWN_ASSET,
                "ReleaseAsset for asset $assetId, which is not registered",
            )
        }
        return null
    }

    private fun decode(kind: AssetKind, bytes: ByteArray): Asset? = when (kind) {
        AssetKind.Png, AssetKind.Jpeg -> decodeRasterAsset(bytes)?.let(Asset::Raster)

        AssetKind.Svg -> decodeVectorAsset(bytes)?.let(Asset::Vector)

        AssetKind.Font -> decodeFontAsset(bytes)?.let(Asset::Font)

        // An image brush names a picture registered in its own right, so the lookup is
        // into what this cache is already holding. A brush registered before the picture
        // it names is unreadable and says so, rather than drawing nothing quietly.
        AssetKind.Brush -> decodeBrushAsset(bytes, entries::get)?.let(Asset::Brush)

        // A vector icon registers a meaning, so its bytes are the role tag and nothing
        // else. There is no artwork on the wire and no icon name to look up at run time.
        AssetKind.VectorIcon -> iconRole(bytes)?.let(Asset::Symbol)
    }

    private fun iconRole(bytes: ByteArray): IconRole? {
        if (bytes.size < 2) return null
        val tag = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        return IconRole.entries.getOrNull(tag - 1)
    }
}

package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.protocol.AssetKind
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PaletteEntry
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag

private const val IMAGE = 1
private const val ASSET = 7

/** A one pixel PNG, so the decoder has a real file to read rather than a stub. */
private val ONE_PIXEL_PNG = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
    0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
    0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
    0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
    0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
    0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
    0x42, 0x60, 0x82.toByte(),
)

/** A minimal SVG, so the parser has a real document to read rather than a stub. */
private val SQUARE_SVG = (
    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 8 8\">" +
        "<rect width=\"8\" height=\"8\" fill=\"#336699\"/></svg>"
    ).toByteArray(Charsets.UTF_8)

/**
 * The same square, written the way a drawing meant to be scaled is written: single quoted
 * attributes and a root that says its size is the whole of whatever it is given.
 *
 * Both spellings are here because they do not behave the same, and the one an author
 * reaches for first is the one that was wrong.
 */
private val SIZED_SQUARE_SVG = (
    "<svg xmlns='http://www.w3.org/2000/svg' width='100%' height='100%' " +
        "viewBox='0 0 8 8' fill='none'>" +
        "<rect width='8' height='8' fill='#336699'/></svg>"
    ).toByteArray(Charsets.UTF_8)

/**
 * Two black strokes across a 24 unit box, the way many icon sets write their icons: a
 * `viewBox` and a fixed size of 24 on the root. The upper one is opaque and the lower one is
 * at half opacity.
 */
private val STROKES_SVG = (
    "<svg xmlns='http://www.w3.org/2000/svg' width='24' height='24' viewBox='0 0 24 24' fill='none'>" +
        "<path d='M2 7H22' stroke='black' stroke-width='4'/>" +
        "<path d='M2 18H22' stroke='#000000' stroke-opacity='0.5' stroke-width='4'/></svg>"
    ).toByteArray(Charsets.UTF_8)

private const val BRAND_LIGHT = 0xFFE8590C.toInt()
private const val BRAND_DARK = 0xFF2E7DFF.toInt()

private fun iconBytes(role: IconRole): ByteArray {
    val tag = role.ordinal + 1
    return byteArrayOf((tag and 0xFF).toByte(), ((tag shr 8) and 0xFF).toByte())
}

private fun imageTree(assetId: Int) = listOf(
    Mutation.Create(IMAGE, WidgetKind.Image),
    // The modifier chain sizes a picture, the same way it sizes everything else, so the
    // node keeps its place whether or not there is anything to draw in it.
    Mutation.SetModifier(IMAGE, 0, ProtocolModifier.Size(32f, 32f)),
    Mutation.SetProp(IMAGE, PropertyKind.Asset, PropertyValue.Integer(assetId.toLong())),
)

/** A sized box, so a test that only cares that the rest of the batch applied can see it. */
private fun sizedBox(nodeId: Int) = listOf(
    Mutation.Create(nodeId, WidgetKind.Box),
    Mutation.SetModifier(nodeId, 0, ProtocolModifier.Size(16f, 16f)),
)

/**
 * Assets reach the Renderer once, by id, and the id is all a frame carries afterwards.
 */
@OptIn(ExperimentalTestApi::class)
class AssetDeliveryTest {
    @Test
    fun fr16_a_registered_png_is_drawn_from_the_cache() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(Mutation.RegisterAsset(ASSET, AssetKind.Png.ordinal + 1, ONE_PIXEL_PNG)) +
                imageTree(ASSET),
        )
        lateinit var host: ComposeRustHost
        setContent {
            host = rememberComposeRustHost(connection)
            ComposeRustContent(host)
        }
        waitForIdle()

        onNodeWithTag(nodeTestTag(IMAGE)).assertIsDisplayed()
        assertEquals(
            emptyList(),
            connection.events.filterIsInstance<HostEvent.ProtocolError>(),
            "a registered asset must not be reported as a problem",
        )
    }

    /**
     * Vectors take a different path from rasters all the way through: a different decoder,
     * a different thing held in the cache, and a different draw. Only one of the two was
     * covered, and a renderer whose graphics stack cannot parse one reports it rather than
     * drawing it, so which of the two happened has to be visible.
     */
    @Test
    fun fr16_a_registered_svg_is_drawn_from_the_cache() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(Mutation.RegisterAsset(ASSET, AssetKind.Svg.ordinal + 1, SQUARE_SVG)) +
                imageTree(ASSET),
        )
        lateinit var host: ComposeRustHost
        setContent {
            host = rememberComposeRustHost(connection)
            ComposeRustContent(host)
        }
        waitForIdle()

        onNodeWithTag(nodeTestTag(IMAGE)).assertIsDisplayed()
        assertEquals(
            emptyList(),
            connection.events.filterIsInstance<HostEvent.ProtocolError>(),
            "this renderer parses vectors, so nothing is reported",
        )
    }

    /**
     * A drawing fills the box it was given rather than the box its file was written in.
     *
     * Named for what it defends: a vector whose size is a `viewBox` and nothing else, which
     * is how a drawing that means to be scaled is written, has an intrinsic size of exactly
     * that box. Drawn at one user unit to the pixel it lands in the top left corner of
     * whatever it was given and is clipped there, which is what a 120 unit garment did in a
     * card 388 wide. The picture is right, the node is the right size, and the assertion
     * that the picture is displayed passes the whole time.
     */
    @Test
    fun fr16_a_vector_fills_the_box_it_was_given() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(Mutation.RegisterAsset(ASSET, AssetKind.Svg.ordinal + 1, SIZED_SQUARE_SVG)) +
                listOf(
                    Mutation.Create(IMAGE, WidgetKind.Image),
                    Mutation.SetModifier(IMAGE, 0, ProtocolModifier.Size(80f, 80f)),
                    Mutation.SetProp(
                        IMAGE,
                        PropertyKind.Asset,
                        PropertyValue.Integer(ASSET.toLong()),
                    ),
                ),
        )
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()

        // The document is eight units square and the node is eighty pixels square, so a
        // drawing that scaled reaches the far corner and one that did not stops at an
        // eighth of the way across.
        val pixels = onNodeWithTag(nodeTestTag(IMAGE)).captureToImage().toPixelMap()
        assertEquals(
            Color(0xFF336699),
            pixels[pixels.width - 2, pixels.height - 2],
            "the far corner of the picture is empty, so the drawing was not scaled",
        )
    }

    @Test
    fun fr16_a_released_asset_id_is_a_protocol_error() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(Mutation.RegisterAsset(ASSET, AssetKind.Png.ordinal + 1, ONE_PIXEL_PNG)) +
                imageTree(ASSET),
        )
        lateinit var host: ComposeRustHost
        setContent {
            host = rememberComposeRustHost(connection)
            ComposeRustContent(host)
        }
        waitForIdle()
        assertTrue(
            connection.events.filterIsInstance<HostEvent.ProtocolError>().isEmpty(),
            "the asset is registered, so nothing is wrong yet",
        )

        connection.scheduleFrame(listOf(Mutation.ReleaseAsset(ASSET)))
        host.renderFrame(0L)
        waitForIdle()

        val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
        assertEquals(1, errors.size, "using a released id is reported exactly once")
        assertTrue(
            errors.single().message.contains("$ASSET"),
            "the report has to name the id that is missing, got: ${errors.single().message}",
        )
        // The point of reporting rather than aborting: the tree is still there.
        onNodeWithTag(nodeTestTag(IMAGE)).assertIsDisplayed()
    }

    @Test
    fun fr16_an_unregistered_asset_id_is_a_protocol_error() = runComposeUiTest {
        val connection = FakeHostConnection(imageTree(ASSET))
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        waitForIdle()

        assertEquals(
            1,
            connection.events.filterIsInstance<HostEvent.ProtocolError>().size,
            "an id that was never registered must be reported",
        )
    }

    @Test
    fun fr16_a_kind_the_renderer_cannot_read_is_reported_not_fatal() = runComposeUiTest {
        val unknownKind = 99
        val connection = FakeHostConnection(
            listOf(Mutation.RegisterAsset(ASSET, unknownKind, ONE_PIXEL_PNG)) +
                sizedBox(2),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        waitForIdle()

        val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
        assertEquals(1, errors.size, "an unreadable kind is one report")
        // The rest of the batch still applied, which is why the error is a report.
        onNodeWithTag(nodeTestTag(2)).assertIsDisplayed()
    }

    @Test
    fun fr16_a_corrupt_file_is_reported_rather_than_thrown() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.RegisterAsset(
                    ASSET,
                    AssetKind.Png.ordinal + 1,
                    byteArrayOf(1, 2, 3, 4),
                ),
            ) + sizedBox(2),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        waitForIdle()

        assertEquals(
            1,
            connection.events.filterIsInstance<HostEvent.ProtocolError>().size,
            "four bytes are not a picture, and saying so must not end the process",
        )
        onNodeWithTag(nodeTestTag(2)).assertIsDisplayed()
    }

    @Test
    fun fr16_an_icon_registers_a_role_and_draws_from_it() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.RegisterAsset(
                    ASSET,
                    AssetKind.VectorIcon.ordinal + 1,
                    iconBytes(IconRole.Search),
                ),
                Mutation.Create(IMAGE, WidgetKind.Icon),
                Mutation.SetProp(IMAGE, PropertyKind.Asset, PropertyValue.Integer(ASSET.toLong())),
            ),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        waitForIdle()

        onNodeWithTag(nodeTestTag(IMAGE)).assertIsDisplayed()
        assertTrue(connection.events.filterIsInstance<HostEvent.ProtocolError>().isEmpty())
    }

    @Test
    fun fr16_a_vector_icon_naming_no_role_is_reported() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.RegisterAsset(
                    ASSET,
                    AssetKind.VectorIcon.ordinal + 1,
                    byteArrayOf(0x7F, 0x00),
                ),
            ) + sizedBox(2),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        waitForIdle()

        assertEquals(1, connection.events.filterIsInstance<HostEvent.ProtocolError>().size)
    }

    /**
     * The same registration comes out as each system's own icon. If the systems agreed on
     * everything, an icon would be one drawing wearing several names.
     */
    @Test
    fun fr16_one_icon_role_is_drawn_to_each_systems_own_metrics() {
        val styles = DesignSystem.entries.map { system ->
            val theme = resolveTheme(
                Theme(system, DesignSystem.Material3, ColorScheme.Light, false),
                HostPlatform.MacOs,
                false,
            )
            theme.rules.icon(IconRole.Back, theme)
        }

        assertEquals(styles.size, styles.distinct().size, "each system draws its own icons")
        // Not one cap per system: Compose has three stroke ends and there are more systems
        // than that, so the most that can be asked is that the sets do not all end alike.
        assertTrue(
            styles.map { it.cap }.distinct().size > 1,
            "the shape of a stroke's end is the most recognisable difference between the sets",
        )
        assertTrue(
            styles.map { it.size }.distinct().size > 1,
            "the optical sizes differ too",
        )
    }
    /**
     * A picture the application shipped, drawn as an icon, is drawn in the icon's colour:
     * the black strokes come out Primary, in the light scheme and again after the scheme
     * turns dark, and a stroke at half opacity keeps its half. The picture fills the icon's
     * slot, so the strokes are where the file puts them in it.
     */
    @Test
    fun fr16_icon_asset_takes_the_icon_tint() = runComposeUiTest {
        val page = 1
        val icon = 2
        val white = 0xFFFFFFFF.toInt()
        val palette = listOf(
            PaletteEntry(ColorRole.Primary, dark = false, argb = BRAND_LIGHT),
            PaletteEntry(ColorRole.Primary, dark = true, argb = BRAND_DARK),
        )
        fun theme(scheme: ColorScheme) =
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, scheme, false, palette = palette))
        val primary = (1L shl 32) or (ColorRole.Primary.ordinal + 1).toLong()
        val connection = FakeHostConnection(
            listOf(
                theme(ColorScheme.Light),
                Mutation.RegisterAsset(ASSET, AssetKind.Svg.ordinal + 1, STROKES_SVG),
                Mutation.Create(page, WidgetKind.Box),
                Mutation.SetModifier(page, 0, ProtocolModifier.Size(80f, 80f)),
                Mutation.SetModifier(page, 1, ProtocolModifier.Background(Paint.Literal(white))),
                Mutation.Create(icon, WidgetKind.Icon),
                Mutation.SetProp(icon, PropertyKind.IconAsset, PropertyValue.Integer(ASSET.toLong())),
                Mutation.SetProp(icon, PropertyKind.Color, PropertyValue.Integer(primary)),
                Mutation.Insert(page, icon, 0),
            ),
        )
        lateinit var host: ComposeRustHost
        setContent {
            // Two pixels to the dp, so the icon's 24 unit picture has to be scaled to fill
            // its slot rather than happening to match it one to one.
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                host = rememberComposeRustHost(connection)
                ComposeRustContent(host)
            }
        }
        waitForIdle()
        assertTrue(connection.events.filterIsInstance<HostEvent.ProtocolError>().isEmpty())

        fun check(expected: Int, scheme: String) {
            val side = onNodeWithTag(nodeTestTag(icon)).fetchSemanticsNode().size.width
            assertTrue(side >= 32, "$scheme: the icon is drawn at an icon's size, got $side px")
            val picture = onNodeWithTag(nodeTestTag(page)).captureToImage().toAwtImage()
            fun at(unitY: Float) = picture.getRGB(side / 2, (side * unitY / 24f).toInt())
            assertEquals(
                Integer.toHexString(expected),
                Integer.toHexString(at(7f)),
                "$scheme: the opaque stroke is the icon's colour, not the picture's black",
            )
            // Half of the tint over the white page, channel by channel.
            val half = at(18f)
            for (shift in listOf(16, 8, 0)) {
                val want = (((expected shr shift) and 0xFF) + 0xFF) / 2
                val got = (half shr shift) and 0xFF
                assertTrue(
                    kotlin.math.abs(want - got) <= 4,
                    "$scheme: the half opacity stroke keeps its alpha, got ${Integer.toHexString(half)}",
                )
            }
            assertEquals(white, picture.getRGB(side / 2, (side * 12.5f / 24f).toInt()), "$scheme: between the strokes")
        }
        check(BRAND_LIGHT, "light")

        connection.scheduleFrame(listOf(theme(ColorScheme.Dark)))
        host.renderFrame(0L)
        waitForIdle()
        check(BRAND_DARK, "dark")
    }
}

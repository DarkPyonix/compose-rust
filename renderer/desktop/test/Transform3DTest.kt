package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.Compose3DBinding
import dev.darkpyonix.composerust.ui.Transform3DPlan
import dev.darkpyonix.composerust.ui.node.RenderNodeObserver
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WHITE = 0xFFFFFFFF.toInt()
private const val RED = 0xFFFF0000.toInt()
private const val ROOT = 1
private const val BOX = 2
private const val CLICK = 78L

/** A CSS 4x4 matrix in the column-major order of `matrix3d`, as doubles. */
private class Css3D(val m: DoubleArray) {
    operator fun times(other: Css3D): Css3D {
        val out = DoubleArray(16)
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) sum += m[4 * k + row] * other.m[4 * column + k]
                out[4 * column + row] = sum
            }
        }
        return Css3D(out)
    }

    /** Where the point (x, y, 0) lands on the screen, after the division by w. */
    fun apply(x: Double, y: Double): Offset {
        val w = m[3] * x + m[7] * y + m[15]
        return Offset(((m[0] * x + m[4] * y + m[12]) / w).toFloat(), ((m[1] * x + m[5] * y + m[13]) / w).toFloat())
    }

    /** The same matrix turning about the point (x, y), the way `transform-origin` does. */
    fun about(x: Double, y: Double): Css3D = translate(x, y) * this * translate(-x, -y)

    fun modifier(originX: Float, originY: Float, backfaceHidden: Boolean = false) = ProtocolModifier.Transform3D(
        m[0].toFloat(), m[1].toFloat(), m[2].toFloat(), m[3].toFloat(),
        m[4].toFloat(), m[5].toFloat(), m[6].toFloat(), m[7].toFloat(),
        m[8].toFloat(), m[9].toFloat(), m[10].toFloat(), m[11].toFloat(),
        m[12].toFloat(), m[13].toFloat(), m[14].toFloat(), m[15].toFloat(),
        originX, originY, backfaceHidden,
    )

    fun floats(): FloatArray = FloatArray(16) { m[it].toFloat() }

    companion object {
        private fun identity() = DoubleArray(16).also { it[0] = 1.0; it[5] = 1.0; it[10] = 1.0; it[15] = 1.0 }
        private fun radians(degrees: Double) = degrees * PI / 180.0

        fun perspective(d: Double) = Css3D(identity().also { it[11] = -1.0 / d })
        fun rotateX(degrees: Double) = Css3D(identity().also {
            val c = cos(radians(degrees)); val s = sin(radians(degrees))
            it[5] = c; it[6] = s; it[9] = -s; it[10] = c
        })
        fun rotateY(degrees: Double) = Css3D(identity().also {
            val c = cos(radians(degrees)); val s = sin(radians(degrees))
            it[0] = c; it[2] = -s; it[8] = s; it[10] = c
        })
        fun rotateZ(degrees: Double) = Css3D(identity().also {
            val c = cos(radians(degrees)); val s = sin(radians(degrees))
            it[0] = c; it[1] = s; it[4] = -s; it[5] = c
        })
        fun skewX(degrees: Double) = Css3D(identity().also { it[4] = tan(radians(degrees)) })
        fun translate(x: Double, y: Double, z: Double = 0.0) = Css3D(identity().also { it[12] = x; it[13] = y; it[14] = z })
        fun scale(x: Double, y: Double) = Css3D(identity().also { it[0] = x; it[5] = y })
    }
}

/**
 * CSS 3D `transform` on a node: drawn through the layers that reproduce the matrix, with the
 * layout untouched and pointer input following the drawn shape.
 *
 * Every test runs at a density of one, so a dp is a pixel.
 */
@OptIn(ExperimentalTestApi::class)
class Transform3DTest {

    private fun page(build: MutableList<Mutation>.() -> Unit): List<Mutation> = buildList {
        add(
            Mutation.SetTheme(
                Theme(
                    designSystem = DesignSystem.Material3,
                    fallback = DesignSystem.Material3,
                    colorScheme = ColorScheme.Light,
                    adaptive = false,
                ),
            ),
        )
        add(Mutation.Create(ROOT, WidgetKind.AbsoluteBox))
        add(Mutation.SetModifier(ROOT, 0, ProtocolModifier.RequiredSize(300f, 200f)))
        add(Mutation.SetModifier(ROOT, 1, ProtocolModifier.Background(Paint.Literal(WHITE))))
        build()
    }

    private fun MutableList<Mutation>.node(
        id: Int,
        parent: Int,
        index: Int,
        vararg modifiers: ProtocolModifier,
        widget: WidgetKind = WidgetKind.Box,
    ) {
        add(Mutation.Create(id, widget))
        modifiers.forEachIndexed { position, modifier -> add(Mutation.SetModifier(id, position, modifier)) }
        add(Mutation.Insert(parent, id, index))
    }

    private fun ComposeUiTest.show(connection: FakeHostConnection): ComposeRustHost {
        lateinit var host: ComposeRustHost
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                host = rememberComposeRustHost(connection)
                ComposeRustContent(host)
            }
        }
        waitForIdle()
        return host
    }

    private fun ComposeUiTest.picture(): PixelMap =
        onNodeWithTag(nodeTestTag(ROOT)).captureToImage().toPixelMap()

    private fun ComposeUiTest.toRootBox(id: Int, x: Float, y: Float): Offset {
        val root = onNodeWithTag(nodeTestTag(ROOT)).fetchSemanticsNode().layoutInfo.coordinates
        val node = onNodeWithTag(nodeTestTag(id)).fetchSemanticsNode().layoutInfo.coordinates
        return node.localToRoot(Offset(x, y)) - root.localToRoot(Offset.Zero)
    }

    private fun ComposeUiTest.clickAt(x: Float, y: Float) {
        onNodeWithTag(nodeTestTag(ROOT)).performMouseInput {
            moveTo(Offset(x, y))
            press()
            release()
        }
        waitForIdle()
    }

    private fun assertColor(expected: Int, actual: Color, what: String) {
        val wanted = Color(expected)
        val close = abs(wanted.red - actual.red) <= 2f / 255f &&
            abs(wanted.green - actual.green) <= 2f / 255f &&
            abs(wanted.blue - actual.blue) <= 2f / 255f
        assertTrue(close, "$what: expected $wanted, found $actual")
    }

    private val corners = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 40.0, 0.0 to 40.0, 50.0 to 20.0)

    /** A 100 by 40 box at (50, 50) with [matrix] about the fractions (originX, originY) of it. */
    private fun ComposeUiTest.showBox(
        matrix: Css3D,
        originX: Float,
        originY: Float,
        backfaceHidden: Boolean = false,
        painted: Boolean = false,
        clickable: Boolean = false,
    ): FakeHostConnection {
        val connection = FakeHostConnection(
            page {
                val modifiers = buildList {
                    add(ProtocolModifier.Offset(50f, 50f))
                    add(ProtocolModifier.RequiredSize(100f, 40f))
                    add(matrix.modifier(originX, originY, backfaceHidden))
                    if (painted) add(ProtocolModifier.Background(Paint.Literal(RED)))
                    if (clickable) add(ProtocolModifier.Clickable(CLICK))
                }
                node(BOX, ROOT, 0, *modifiers.toTypedArray())
            },
        )
        show(connection)
        return connection
    }

    /** The corners and centre of the box, where CSS puts them, in the root box. */
    private fun expectedRoot(matrix: Css3D, originX: Float, originY: Float, x: Double, y: Double): Offset {
        val about = matrix.about(originX * 100.0, originY * 40.0)
        return about.apply(x, y) + Offset(50f, 50f)
    }

    private fun assertCorners(name: String, matrix: Css3D, originX: Float, originY: Float) = runComposeUiTest {
        showBox(matrix, originX, originY)
        for ((x, y) in corners) {
            val expected = expectedRoot(matrix, originX, originY, x, y)
            val actual = toRootBox(BOX, x.toFloat(), y.toFloat())
            assertTrue(
                hypot(expected.x - actual.x, expected.y - actual.y) < 1f,
                "$name about ($originX, $originY): ($x, $y) should be at $expected, is at $actual",
            )
        }
    }

    /**
     * The corners of a 100 by 40 box land within a pixel of the CSS computation for
     * perspective(400px) with rotateY(45deg), rotateX(60deg), the two together, and a turn
     * about the corner instead of the centre.
     */
    @Test
    fun fr41_3_rotate_perspective_corners_match_css() {
        val cases = mapOf(
            "perspective(400px) rotateY(45deg)" to Css3D.perspective(400.0) * Css3D.rotateY(45.0),
            "perspective(400px) rotateX(60deg)" to Css3D.perspective(400.0) * Css3D.rotateX(60.0),
            "perspective(400px) rotateX(60deg) rotateY(45deg)" to
                Css3D.perspective(400.0) * Css3D.rotateX(60.0) * Css3D.rotateY(45.0),
            "perspective(300px) rotateX(20deg) rotateY(-35deg) rotateZ(15deg)" to
                Css3D.perspective(300.0) * Css3D.rotateX(20.0) * Css3D.rotateY(-35.0) * Css3D.rotateZ(15.0),
            "rotateY(45deg) with no perspective" to Css3D.rotateY(45.0),
        )
        for ((name, matrix) in cases) {
            for ((originX, originY) in listOf(0.5f to 0.5f, 0f to 0f)) {
                assertCorners(name, matrix, originX, originY)
            }
        }
    }

    /**
     * A skew together with perspective, and a translation inside the perspective, are no
     * single layer's matrix. They are drawn by the stacked layers, and the corners match the
     * CSS computation just as closely. The ones a single layer can draw are not stacked.
     * The file that does this names the Compose release it was read against, and the module
     * depends on that release.
     */
    @Test
    fun fr41_3_undecomposable_matrix_uses_the_adapter_path() {
        fun kindOf(matrix: Css3D, backfaceHidden: Boolean = false): Transform3DPlan.Kind =
            Transform3DPlan().also { it.solve(matrix.floats(), backfaceHidden) }.kind

        val single = Css3D.perspective(400.0) * Css3D.rotateY(45.0)
        val skewed = Css3D.perspective(400.0) * Css3D.rotateY(40.0) * Css3D.skewX(15.0)
        val moved = Css3D.perspective(400.0) * Css3D.rotateY(30.0) * Css3D.translate(10.0, 5.0)
        assertEquals(Transform3DPlan.Kind.Single, kindOf(single), "rotateY under perspective is one layer")
        assertEquals(Transform3DPlan.Kind.Stacked, kindOf(skewed), "a skew under perspective")
        assertEquals(Transform3DPlan.Kind.Stacked, kindOf(moved), "a translation inside the perspective")
        assertEquals(Transform3DPlan.Kind.Affine, kindOf(Css3D.rotateY(45.0)), "no perspective is the affine layers")

        val plan = Transform3DPlan().also { it.solve(single.floats(), false) }
        assertTrue(abs(plan.rotationY - 45f) < 0.01f, "rotationY ${plan.rotationY}")
        assertTrue(abs(plan.depth - 400f) < 0.1f, "the camera distance ${plan.depth}")

        for ((name, matrix) in mapOf("skewed" to skewed, "moved" to moved)) {
            for ((originX, originY) in listOf(0.5f to 0.5f, 0f to 0f)) {
                assertCorners(name, matrix, originX, originY)
            }
        }

        val declared = File(moduleFile("renderer/desktop/module.yaml")).readText()
        assertTrue(
            declared.contains("org.thisisthepy.compose.ui:ui:${Compose3DBinding.COMPOSE_VERSION}"),
            "Transform3DLayers.kt was read against Compose ${Compose3DBinding.COMPOSE_VERSION}, " +
                "but the module depends on another release: read the file against it first",
        )
    }

    /**
     * A pointer is tested against the shape that is drawn. A box turned 60 degrees about the
     * vertical axis under perspective is not hit near its original left corner, is hit at
     * its centre, and is hit above its original top edge where the near side has grown.
     */
    @Test
    fun fr41_3_hit_test_follows_the_shape() = runComposeUiTest {
        val matrix = Css3D.perspective(400.0) * Css3D.rotateY(60.0)
        val connection = showBox(matrix, 0.5f, 0.5f, clickable = true)
        fun clicks() = connection.events.filterIsInstance<HostEvent.Clicked>().count { it.handlerId == CLICK }
        fun root(x: Double, y: Double) = expectedRoot(matrix, 0.5f, 0.5f, x, y)

        // The original rectangle covers (50..150, 50..90). The near corner, the left one for
        // this turn, has grown above it.
        val grownCorner = root(0.0, 0.0)
        val centre = root(50.0, 20.0)
        val above = Offset(
            grownCorner.x + (centre.x - grownCorner.x) * 0.05f,
            grownCorner.y + (centre.y - grownCorner.y) * 0.05f,
        )
        assertTrue(above.y < 50f, "the point chosen is above the original rectangle: $above")

        clickAt(53f, 53f)
        assertEquals(0, clicks(), "near the original corner, which the turned box has left")
        clickAt(centre.x, centre.y)
        assertEquals(1, clicks(), "the centre")
        clickAt(above.x, above.y)
        assertEquals(2, clicks(), "outside the original rectangle, inside the turned shape")
    }

    /**
     * backface-visibility: hidden. Turned half way round the box shows its back and is not
     * drawn, and is not hit; without the flag the same box is drawn mirrored. Turned less
     * than a quarter it shows its front and is drawn with the flag too.
     */
    @Test
    fun fr41_3_backface_hidden() {
        val half = Css3D.perspective(400.0) * Css3D.rotateY(180.0)
        val slight = Css3D.perspective(400.0) * Css3D.rotateY(30.0)
        fun centreIsRed(matrix: Css3D, hidden: Boolean): Pair<Boolean, Int> {
            var red = false
            var clicks = 0
            runComposeUiTest {
                val connection = showBox(matrix, 0.5f, 0.5f, backfaceHidden = hidden, painted = true, clickable = true)
                val pixel = picture()[100, 70]
                red = abs(pixel.red - 1f) < 0.02f && pixel.green < 0.02f && pixel.blue < 0.02f
                clickAt(100f, 70f)
                clicks = connection.events.filterIsInstance<HostEvent.Clicked>().count { it.handlerId == CLICK }
            }
            return red to clicks
        }
        assertEquals(false to 0, centreIsRed(half, hidden = true), "the back, hidden")
        assertEquals(true to 1, centreIsRed(half, hidden = false), "the back, not hidden")
        assertEquals(true to 1, centreIsRed(slight, hidden = true), "the front, with the flag set")
    }

    /**
     * A change to the matrix alone is one `SetModifier` and measures nothing again: not the
     * node, and not what is inside it.
     */
    @Test
    fun fr41_3_update_sends_one_record_and_does_not_remeasure() = runComposeUiTest {
        val compositions = mutableMapOf<Int, Int>()
        val measures = mutableMapOf<Int, Int>()
        RenderNodeObserver.onCompose = { id -> compositions[id] = (compositions[id] ?: 0) + 1 }
        RenderNodeObserver.onMeasure = { id -> measures[id] = (measures[id] ?: 0) + 1 }
        val before = Css3D.perspective(400.0) * Css3D.rotateY(10.0)
        val after = Css3D.perspective(400.0) * Css3D.rotateY(50.0)
        val connection = FakeHostConnection(
            page {
                node(
                    BOX, ROOT, 0,
                    ProtocolModifier.Offset(50f, 50f),
                    ProtocolModifier.RequiredSize(100f, 40f),
                    before.modifier(0.5f, 0.5f),
                    widget = WidgetKind.AbsoluteBox,
                )
                node(3, BOX, 0, ProtocolModifier.RequiredSize(20f, 20f), widget = WidgetKind.AbsoluteBox)
            },
        )
        connection.respondWith { HostResponse(listOf(Mutation.SetModifier(BOX, 2, after.modifier(0.5f, 0.5f)))) }
        val host = show(connection)
        val measuredBefore = measures.toMap()
        val composedBefore = compositions.toMap()

        host.dispatch(HostEvent.Clicked(ROOT, 0))
        waitForIdle()

        val corner = toRootBox(BOX, 0f, 0f)
        val expected = expectedRoot(after, 0.5f, 0.5f, 0.0, 0.0)
        assertTrue(hypot(corner.x - expected.x, corner.y - expected.y) < 1f, "the new matrix is drawn: $corner, not $expected")
        assertEquals(measuredBefore[BOX], measures[BOX], "the transformed node is not measured again")
        assertEquals(measuredBefore[3], measures[3], "its child is not measured again")
        assertEquals(composedBefore[3], compositions[3], "its child is not recomposed")
        assertEquals(composedBefore[ROOT], compositions[ROOT], "its parent is not recomposed")
    }

    @AfterTest
    fun clearObservers() {
        RenderNodeObserver.onCompose = null
        RenderNodeObserver.onMeasure = null
    }

    private fun moduleFile(relative: String): String {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relative)
            if (candidate.isFile) return candidate.path
            directory = directory.parentFile
        }
        error("$relative not found above ${System.getProperty("user.dir")}")
    }
}

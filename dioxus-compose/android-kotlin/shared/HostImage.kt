package dioxus.compose.foundation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dioxus.compose.design.IconStyle
import dioxus.compose.design.ResolvedTheme
import androidx.compose.ui.graphics.Path
import dioxus.compose.design.iconGeometry
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.IconRole
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.intProp
import dioxus.compose.ui.node.Asset
import dioxus.compose.ui.node.AssetCache
import dioxus.compose.ui.node.drawVectorDocument
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.node.TableError
import dioxus.compose.ui.paintProp

/**
 * A registered asset drawn as a picture.
 *
 * The node carries an id and nothing else: the bytes were read once, when the Host
 * registered them, so a frame that draws the same picture again costs a lookup in the
 * cache. An id the cache does not know, because it was never registered or because the Host
 * has since released it, is reported to the Host and draws nothing. It is not a crash and it
 * is not a placeholder standing in for a picture the Renderer does not have.
 */
@Composable
internal fun HostImage(
    node: Node,
    modifier: Modifier,
    assets: AssetCache,
    dispatcher: EventDispatcher,
) {
    val assetId = node.intProp(PropertyKind.Asset)?.toInt()
    val asset = assetId?.let(assets::asset)
    ReportMissingAsset(node.id, assetId, asset, dispatcher)
    Canvas(modifier) {
        when (asset) {
            is Asset.Raster -> drawRaster(asset)
            is Asset.Vector -> drawVector(asset)
            // A symbol carries a meaning rather than a picture, so drawn as a picture it
            // takes the surface's own colour.
            is Asset.Symbol -> Unit
            // Neither a face nor a fill is a picture. An `Image` that names one is a
            // mistake, and the report above is what says so.
            is Asset.Font, is Asset.Brush -> Unit
            null -> Unit
        }
    }
}

/**
 * A registered icon, drawn in this design system's icon set.
 *
 * What the Host registered is a meaning, `Back` or `Search`, never a picture and never a
 * system icon name. The artwork and its metrics come from the design system, so the same
 * declaration is a rounded thin stroke under Cupertino and a heavier flat-ended one under
 * Material 3 without the Host being told which platform it is on.
 */
@Composable
internal fun HostIcon(
    node: Node,
    modifier: Modifier,
    assets: AssetCache,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val assetId = node.intProp(PropertyKind.Asset)?.toInt()
    val asset = assetId?.let(assets::asset)
    ReportMissingAsset(node.id, assetId, asset, dispatcher)
    val paint = node.paintProp(PropertyKind.Color)
    val tint = if (paint != null) theme.color(paint) else theme.color(ColorRole.OnSurface)
    when (asset) {
        is Asset.Symbol -> {
            val style = theme.rules.icon(asset.role, theme)
            Canvas(modifier.size(style.size)) { drawSymbol(asset, style, tint) }
        }

        // An icon slot can also hold a picture. It still takes the icon's optical size, so
        // a row of icons lines up whatever each one was registered as.
        is Asset.Raster -> Canvas(modifier.size(theme.rules.icon(fallbackRole, theme).size)) {
            drawRaster(asset)
        }

        is Asset.Vector -> Canvas(modifier.size(theme.rules.icon(fallbackRole, theme).size)) {
            drawVector(asset)
        }

        // Neither a face nor a fill has a shape to draw. An `Icon` that names one is a
        // mistake, and the report above is what says so.
        is Asset.Font, is Asset.Brush -> Box(modifier)

        null -> Box(modifier)
    }
}

/**
 * One icon, drawn from its meaning in this design system's set.
 *
 * Used where the meaning is a property rather than a registered asset, which is how a
 * navigation destination carries its icon: there is no picture to register, only a role.
 */
@Composable
internal fun RoleIcon(
    role: IconRole,
    tint: Color,
    theme: ResolvedTheme,
    modifier: Modifier = Modifier,
) {
    val style = theme.rules.icon(role, theme)
    Canvas(modifier.size(style.size)) { drawRole(role, style, tint) }
}

/** Only one role is needed to ask the design system what an icon's optical size is. */
private val fallbackRole = IconRole.Back

/**
 * Tells the Host once that the id names nothing.
 *
 * Once, not every frame: the report is keyed on the id, so a picture that stays missing
 * does not turn into a stream of events, and it goes out after composition rather than
 * during it.
 */
@Composable
private fun ReportMissingAsset(
    nodeId: Int,
    assetId: Int?,
    asset: Asset?,
    dispatcher: EventDispatcher,
) {
    if (assetId == null || asset != null) return
    LaunchedEffect(nodeId, assetId) {
        dispatcher.dispatch(
            HostEvent.ProtocolError(
                nodeId = nodeId,
                handlerId = 0,
                code = TableError.UNKNOWN_ASSET,
                message = "asset $assetId is not registered, so node $nodeId drew nothing",
            ),
        )
    }
}

/** Scales the bitmap to fit the box it was given, keeping its proportions. */
private fun DrawScope.drawRaster(asset: Asset.Raster) {
    val bitmap = asset.bitmap
    if (bitmap.width <= 0 || bitmap.height <= 0) return
    val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    drawImage(
        image = bitmap,
        dstOffset = IntOffset(
            ((size.width - width) / 2f).toInt(),
            ((size.height - height) / 2f).toInt(),
        ),
        dstSize = IntSize(width.toInt(), height.toInt()),
    )
}

private fun DrawScope.drawVector(asset: Asset.Vector) {
    if (size.width <= 0f || size.height <= 0f) return
    drawVectorDocument(asset.document)
}

/**
 * Draws one icon's geometry at this design system's weight.
 *
 * The unit box the geometry is written in is scaled to the whole drawing area, and the
 * stroke is inset by half its width so a shape that touches the edge is not clipped in half.
 */
private fun DrawScope.drawSymbol(asset: Asset.Symbol, style: IconStyle, tint: Color) =
    drawRole(asset.role, style, tint)

/**
 * A run of points as a path, with each corner turned through an arc rather than met.
 *
 * A run whose last point repeats its first is a closed shape, and the path starts halfway
 * along one of its edges so that the corner where it began and ended is turned like the
 * others. Left as it came, that one corner stayed square while the other three were round,
 * which is more obviously wrong than four square ones.
 *
 * The turn is shortened where an edge is too short to give it room, so a small glyph does
 * not round itself away.
 */
internal fun turnedPath(points: List<Offset>, corner: Float): Path {
    val closed = points.size >= 4 && points.first() == points.last()
    val run = if (!closed) {
        points
    } else {
        val ring = points.dropLast(1)
        val seam = (ring.last() + ring.first()) / 2f
        listOf(seam) + ring + listOf(seam)
    }
    val path = Path()
    path.moveTo(run.first().x, run.first().y)
    if (corner <= 0f || run.size < 3) {
        run.drop(1).forEach { path.lineTo(it.x, it.y) }
        return path
    }
    for (index in 1 until run.size - 1) {
        val before = run[index - 1]
        val here = run[index]
        val after = run[index + 1]
        val back = (before - here).getDistance()
        val on = (after - here).getDistance()
        if (back == 0f || on == 0f) {
            path.lineTo(here.x, here.y)
            continue
        }
        val entry = minOf(corner, back / 2f)
        val exit = minOf(corner, on / 2f)
        val from = here + (before - here) * (entry / back)
        val to = here + (after - here) * (exit / on)
        path.lineTo(from.x, from.y)
        path.quadraticTo(here.x, here.y, to.x, to.y)
    }
    path.lineTo(run.last().x, run.last().y)
    return path
}

private fun DrawScope.drawRole(role: IconRole, style: IconStyle, tint: Color) {
    val stroke = style.strokeWidth.toPx()
    val inset = stroke / 2f
    val box = Size(size.width - stroke, size.height - stroke)
    if (box.width <= 0f || box.height <= 0f) return
    fun at(point: Offset) =
        Offset(inset + point.x * box.width, inset + point.y * box.height)

    val geometry = iconGeometry(role)
    val outline = Stroke(width = stroke, cap = style.cap, join = style.join)
    // As a path per run, not a line per pair. Drawn pair by pair, every corner in every
    // glyph was two ends laid over each other and the design system's join never applied
    // to anything: a frame came out with four blunt corners whatever the system asked for.
    geometry.strokes.forEach { points ->
        if (points.size < 2) return@forEach
        drawPath(turnedPath(points.map(::at), style.corner.toPx()), tint, style = outline)
    }
    geometry.dots.forEach { dot ->
        drawCircle(
            color = tint,
            radius = dot.radius * minOf(box.width, box.height),
            center = at(dot.center),
            style = if (dot.filled) Fill else outline,
        )
    }
}

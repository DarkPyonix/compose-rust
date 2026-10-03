package org.thisisthepy.compose.liquidglass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.thisisthepy.compose.designsystem.SurfaceMaterial

/**
 * How much the material asserts itself over what is behind it.
 *
 * Apple's two glass recipes differ in exactly this: one is for surfaces that carry
 * controls and text and therefore has to win against a busy backdrop, the other is for
 * surfaces floating over media where the content underneath is the point.
 */
enum class GlassProminence {
    /** Carries text and controls. More tint, more blur, legible over anything. */
    Regular,

    /** Floats over content that should stay visible. Barely tinted. */
    Clear,
}

/**
 * The scope of what this module can and cannot draw, stated once so that no caller has to
 * guess.
 *
 * Compose Multiplatform cannot produce the real Liquid Glass material. On iOS 26 the
 * system draws it, through native SwiftUI navigation containers, and a Compose app only
 * gets it by hosting its content inside a SwiftUI shell; the glass then belongs to that
 * shell's chrome, not to anything drawn here. On the desktop, sampling the wallpaper
 * behind the window needs a platform compositing view outside the Compose surface.
 *
 * What this module draws is the part whose backdrop is content this application drew
 * itself: continuous and concentric corners, a translucent tinted surface over app
 * content, an edge lit along the top and shaded along the bottom, depth from layers
 * overlapping, and Compose's own blur applied to the content underneath. Those are
 * really drawn, not approximated, and they are most of what makes a screen read as iOS
 * 26.
 *
 * The wallpaper is the host's to put there. Where it has put the platform's own material
 * behind the window, [windowMaterial] is the recipe for chrome that sits straight on it,
 * and that chrome then really does show the desktop. Where it has not, nothing here
 * claims to.
 */
object LiquidGlass {

    /**
     * A glass material with a guaranteed readable fallback.
     *
     * [backdrop] is the colour the material expects to sit over most of the time, and is
     * used only to work out what the translucent surface will actually look like once it
     * is composited. [content] is the colour that will be drawn on top of the material,
     * and is what the fallback has to stay legible against: the fallback is computed by
     * compositing the tint over the backdrop and then, if that does not reach
     * [minContrast], moving it away from [content] until it does.
     *
     * The translucent path carries no such guarantee and cannot, because the thing behind
     * it changes every frame. That asymmetry is the reason the fallback exists as a
     * stored colour rather than as a runtime alpha adjustment.
     */
    fun material(
        dark: Boolean,
        prominence: GlassProminence = GlassProminence.Regular,
        backdrop: Color,
        content: Color,
        minContrast: Float = MIN_CONTRAST_BODY,
    ): SurfaceMaterial.Glass {
        val tint = if (dark) DARK_TINT else LIGHT_TINT
        // Low enough that what is behind the surface colours it. That is the one property
        // a glass surface has that a grey one does not: the composer over a blue page is
        // bluish, the sidebar over a pink wallpaper is pinkish. At the 0.85 this used to
        // be, a pale surface over a blue page composited to a flat grey whatever was
        // behind it, and every screen drawn in this system read as the flat language
        // beside it.
        //
        // A surface this translucent over a page of its own colour would vanish, which is
        // what happened the first time the page went white. What keeps it on screen there
        // is not more tint but the two things glass really has: the lit rim, and the soft
        // shadow of [LIFT] that a caller puts under it with `glassLift`.
        val alpha = when (prominence) {
            GlassProminence.Regular -> if (dark) 0.66f else 0.70f
            GlassProminence.Clear -> if (dark) 0.32f else 0.34f
        }
        val blur = when (prominence) {
            GlassProminence.Regular -> 30.dp
            GlassProminence.Clear -> 18.dp
        }
        return glass(dark, tint, alpha, blur, backdrop, content, minContrast)
    }

    /**
     * Glass for chrome that sits straight on a window the platform has backed with its own
     * material.
     *
     * The platform's view behind the window already blurs the desktop and lays its own
     * frost over it, so a surface drawn on top of that has very little left to do. Drawn
     * with the ordinary recipe it covers seventy percent of what the platform went to the
     * trouble of showing, and the sidebar that should carry the wallpaper's colour comes
     * out the grey of its own tint. So this one is mostly rim: enough tint to lift the
     * surface a step off the window around it, and the rest is the desktop.
     *
     * The fallback is computed against [backdrop] exactly as [material]'s is, so a reader
     * who asked for reduced transparency still gets an opaque surface with its contrast
     * guaranteed.
     */
    fun windowMaterial(
        dark: Boolean,
        backdrop: Color,
        content: Color,
        minContrast: Float = MIN_CONTRAST_BODY,
    ): SurfaceMaterial.Glass = glass(
        dark = dark,
        tint = if (dark) DARK_TINT else LIGHT_TINT,
        alpha = if (dark) WINDOW_TINT_ALPHA_DARK else WINDOW_TINT_ALPHA_LIGHT,
        blur = 0.dp,
        backdrop = backdrop,
        content = content,
        minContrast = minContrast,
    )

    private fun glass(
        dark: Boolean,
        tint: Color,
        alpha: Float,
        blur: Dp,
        backdrop: Color,
        content: Color,
        minContrast: Float,
    ): SurfaceMaterial.Glass {
        // Light arrives from above, as it does in every Apple surface: the top edge
        // catches it and the bottom edge falls into shadow. A single flat stroke all the
        // way round reads as a drawn border; this reads as thickness.
        val highlight = if (dark) Color.White.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.90f)
        val shade = if (dark) Color.Black.copy(alpha = 0.46f) else Color.Black.copy(alpha = 0.10f)

        val composited = compositeOver(tint.copy(alpha = alpha), backdrop)
        return SurfaceMaterial.Glass(
            tint = tint,
            tintAlpha = alpha,
            blurRadius = blur,
            highlight = highlight,
            shade = shade,
            fallback = ensureContrast(composited, content, minContrast),
        )
    }

    /**
     * The tint of the glass itself, before anything shows through it.
     *
     * Light is white. Glass in light mode brightens what is behind it, which is why a
     * glass capsule over a blue page reads as a paler blue and not as a grey; a grey tint
     * pulls every backdrop towards the same grey, and that is the flat look this system
     * exists not to have. The cost is that white glass over a white page is a step of
     * nothing, and the rim and the lift are what carry it there, as they do in Apple's own
     * screens.
     *
     * Dark is a grey lighter than any page the surface can land on and than any fill that
     * can land on the surface. It was once two levels from the secondary fill grey, and a
     * tinted button on a bar disappeared into the bar.
     */
    val LIGHT_TINT: Color = Color(0xFFFFFFFF)
    val DARK_TINT: Color = Color(0xFF3A3A3C)

    /**
     * How much of [windowMaterial]'s tint is laid over the platform's own material.
     *
     * About a fifth. The reference sidebar sits over a pink and blue wallpaper and its top
     * is pink and its foot is blue, so most of what reaches the eye is the desktop and
     * what the surface adds is a lighter wash and its rim. It is laid twice where a
     * sidebar made of chrome sits on a window made of chrome, and the two together still
     * leave two thirds of the desktop showing.
     */
    const val WINDOW_TINT_ALPHA_LIGHT: Float = 0.18f
    const val WINDOW_TINT_ALPHA_DARK: Float = 0.16f

    /**
     * How far the soft shadow `glassLift` draws around a floating glass surface reaches.
     *
     * Wide and faint. A capsule floating over a page of its own colour is visible because
     * of this and the rim, not because of its tint.
     */
    val LIFT: Dp = 12.dp

    /** How dark that shadow is at the surface's edge, where it is darkest. */
    const val LIFT_ALPHA: Float = 0.10f

    /**
     * The ratio the opaque fallback is held to: WCAG 2.2 AA for body text.
     *
     * Body text is the hardest case a surface has to carry, so meeting it means every
     * lighter demand is met too.
     */
    const val MIN_CONTRAST_BODY: Float = 4.5f

    /**
     * How far a glass layer is pushed by each layer already beneath it.
     *
     * Stacked glass in iOS 26 does not gain a heavier shadow; it gains tint. Each layer
     * sees slightly less of the original backdrop through it, which is what separates a
     * sheet over a panel over the background without drawing three shadows.
     */
    const val DEPTH_TINT_STEP: Float = 0.08f

    /** The shadow a glass layer casts at [depth], wide and soft rather than tight. */
    fun depthShadow(depth: Int): Dp = when (depth.coerceAtLeast(0)) {
        0 -> 0.dp
        1 -> 8.dp
        2 -> 20.dp
        else -> 36.dp
    }
}

/**
 * [this] with its translucency increased for a layer sitting at [depth] in a stack.
 *
 * Depth 0 is unchanged. The alpha is capped below 1 so that a deeply stacked surface is
 * still glass rather than quietly becoming a flat fill.
 */
fun SurfaceMaterial.Glass.atDepth(depth: Int): SurfaceMaterial.Glass {
    if (depth <= 0) return this
    val raised = (tintAlpha + LiquidGlass.DEPTH_TINT_STEP * depth).coerceAtMost(0.94f)
    return copy(tintAlpha = raised)
}

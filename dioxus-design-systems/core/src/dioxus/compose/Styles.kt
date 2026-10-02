package dioxus.compose

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * What a raised surface looks like, as the three systems each understand raising.
 *
 * A system uses the parts it believes in and leaves the rest at their defaults. Material
 * sets [tint] and [shadow]; Cupertino sets [shadow] alone, wide and soft; Fluent sets
 * [shadow] and [strokeTop].
 */
@Immutable
data class ElevationStyle(
    /** The surface colour after the system has applied whatever tint it uses. */
    val surface: Color,
    val shadowElevation: Dp,
    val shadowColor: Color,
    /** A lighter edge along the top, which Fluent uses to suggest a lit surface. */
    val strokeTop: Color? = null,
)

/** How a button is painted in each state a pointer can put it in. */
@Immutable
data class ButtonStyle(
    val container: Color,
    val content: Color,
    val border: Color?,
    val borderWidth: Dp,
    val shape: ShapeRole,
    val pressedContainer: Color,
    val pressedContent: Color,
    val pressedBorder: Color?,
    /**
     * Whether pressing should spread a ripple from the touch point.
     *
     * A Material button does; a Cupertino button dims instead, and adding a ripple to it
     * would be a visible mistake rather than a detail.
     */
    val ripple: Boolean,
)

/** Durations and easing for state transitions. */
@Immutable
data class Motion(
    val pressMillis: Int,
    val releaseMillis: Int,
    val easing: Easing,
)

/**
 * What a surface is made of.
 *
 * Most surfaces are a flat colour. Cupertino's are not: since iOS 26 they sample and blur
 * what is behind them and catch light along their edges.
 */
@Immutable
sealed interface SurfaceMaterial {

    /** A flat fill. What Material 3, Fluent and the Linux systems use. */
    @Immutable
    data class Opaque(val color: Color) : SurfaceMaterial

    /**
     * A translucent, blurred, edge-lit surface: Liquid Glass.
     *
     * The blur applies to content this application drew. Sampling the system background
     * behind the window is not something a Compose surface can do, so this describes a
     * real effect over app content rather than a claim about the desktop behind it.
     */
    @Immutable
    data class Glass(
        val tint: Color,
        val tintAlpha: Float,
        val blurRadius: Dp,
        /** The bright edge where light would catch the top of the surface. */
        val highlight: Color,
        /** The darker edge along the bottom, which gives the surface thickness. */
        val shade: Color,
        /**
         * The opaque colour to use instead when blur is unavailable or the reader asked
         * for reduced transparency. It has to meet contrast on its own.
         */
        val fallback: Color,
    ) : SurfaceMaterial
}

/**
 * What a small interactive control looks like and how it answers a press.
 *
 * One shape for the four of them because they share the same parts: something that holds
 * a state, something that indicates it, and a reaction to being touched. A Slider's track
 * is its container and its thumb is its indicator, which is the same relationship a
 * Switch has.
 */
@Immutable
data class ControlStyle(
    /** The control's own footprint, before any label beside it. */
    val size: Dp,
    /** The track, box or circle that holds the state. */
    val container: Color,
    val containerSelected: Color,
    /** The tick, dot or thumb that shows it. */
    val indicator: Color,
    val indicatorSelected: Color,
    val border: Color?,
    val borderWidth: Dp,
    val shape: ShapeRole,
    /** Thumb size for a Switch or a Slider; unused by the others. */
    val thumbSize: Dp,
    val trackHeight: Dp,
    val disabledAlpha: Float,
    /** A Material control ripples. A Cupertino one dims, and rippling would look wrong. */
    val ripple: Boolean,
)

/** How progress is drawn, which is a bar in some systems and a spinner in others. */
@Immutable
data class ProgressStyle(
    val thickness: Dp,
    val track: Color,
    val indicator: Color,
    /** Diameter when circular. */
    val size: Dp,
    val rounded: Boolean,
    /** One full sweep of the indeterminate animation. */
    val indeterminatePeriodMillis: Int,
)

/** A rule between things. Thin and full width in some systems, inset in others. */
@Immutable
data class DividerStyle(
    val thickness: Dp,
    val color: Color,
    /** How far it is held back from the leading edge. */
    val inset: Dp,
)

/**
 * How a text field reads, which is one of the clearest differences between the systems.
 *
 * Material fills a box and underlines it, Cupertino draws a rounded rectangle, Fluent
 * draws a box with an accent line along the bottom that thickens on focus.
 */
@Immutable
data class FieldStyle(
    val container: Color,
    val containerFocused: Color,
    val content: Color,
    val placeholder: Color,
    val border: Color?,
    val borderFocused: Color?,
    val borderWidth: Dp,
    val borderWidthFocused: Dp,
    /** Drawn along the bottom edge only, which is how Material and Fluent mark focus. */
    val underline: Boolean,
    val shape: ShapeRole,
    val contentPadding: SpaceRole,
    val cursor: Color,
)

/**
 * How a value gets picked.
 *
 * The presentation is the decision; the rest is what the renderer needs to draw whichever
 * one was chosen. A widget carries a value and a range and never sees this.
 */
@Immutable
data class PickerStyle(
    val presentation: PickerPresentation,
    val surface: Color,
    val shape: ShapeRole,
    val elevation: Dp,
    /** Row height on a wheel, cell size on a grid. */
    val itemExtent: Dp,
    /** How many rows a wheel shows at once. Ignored by the other presentations. */
    val visibleItems: Int,
)

/** How something that sits over the screen arrives, and what it does to what is behind. */
@Immutable
data class OverlayStyle(
    val presentation: OverlayPresentation,
    val surface: Color,
    val shape: ShapeRole,
    val elevation: Dp,
    /** Null where a system dims nothing, which is usual for a menu and a tooltip. */
    val scrim: Color?,
    val padding: SpaceRole,
    val enterMillis: Int,
    val exitMillis: Int,
)

/** How a navigation surface is laid out. */
@Immutable
data class NavigationStyle(
    val height: Dp,
    val surface: Color,
    val content: Color,
    val contentSelected: Color,
    val indicator: TabIndicator,
    val indicatorThickness: Dp,
    val titleAlignment: TitleAlignment,
    /** Lifted only once the content beneath has scrolled, in the systems that do it. */
    val elevationOnScroll: Dp,
)

/** How a scrolling surface behaves at its edges and whether it shows a scrollbar. */
@Immutable
data class ScrollStyle(
    val overscroll: OverscrollBehaviour,
    /** A scrollbar that is always there, rather than one that fades in while scrolling. */
    val persistentScrollbar: Boolean,
    val scrollbarThickness: Dp,
    val scrollbarColor: Color,
)

/**
 * The icon family a system draws with.
 *
 * A role names a meaning, never a glyph, which is what lets the same declaration come out
 * as SF Symbols under Cupertino and Material Symbols under Material 3.
 */
@Immutable
data class IconStyle(
    val size: Dp,
    val strokeWidth: Dp,
    /** Systems differ on whether an icon is drawn as an outline or filled in. */
    val filled: Boolean,
)

package dioxus.compose

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The contract every design system in this project implements.
 *
 * A caller names a role. The design system answers with pixels. That direction is the
 * whole point of the split: a widget never says "blue" or "8dp corner", it says
 * `ColorRole.Primary` and `ShapeRole.Medium`, so the same widget code draws a Material
 * button, a Cupertino one and a Fluent one without knowing that any of them exist.
 *
 * Adding a seventh design system therefore means writing one more implementation of this
 * interface. It does not mean touching a widget.
 *
 * Everything here is resolved for one colour scheme. Light and dark are two instances,
 * not a flag threaded through every call, because a design system is free to change more
 * than colours between them.
 */
@Immutable
interface DesignSystem {

    val id: DesignSystemId

    /** True when this instance resolves its roles for a dark scheme. */
    val isDark: Boolean

    fun color(role: ColorRole): Color

    fun type(role: TypeRole): TextStyle

    fun shape(role: ShapeRole): Shape

    fun space(role: SpaceRole): Dp

    /**
     * How a raised surface reads at [elevation].
     *
     * Elevation crosses the boundary as a single dp value and nothing else, because the
     * three systems disagree about what raising something looks like: Material tints the
     * surface and casts a shadow, Cupertino prefers a wide soft shadow with no tint,
     * Fluent draws a layered shadow with a light stroke along the top edge. A caller that
     * could specify the shadow colour would be making that decision for them.
     */
    fun elevation(elevation: Dp, base: Color): ElevationStyle

    /** How a button of [variant] is painted, in each of its interaction states. */
    fun button(variant: ButtonVariant): ButtonStyle

    /** Durations and easing for state transitions. */
    val motion: Motion

    /**
     * The material a surface is made of, if this system has one.
     *
     * Cupertino answers with Liquid Glass. The others answer [SurfaceMaterial.Opaque],
     * which is not a fallback but their actual design language: a Material 3 card is a
     * solid tinted surface, and drawing it as glass would be wrong rather than plainer.
     */
    fun material(role: ColorRole): SurfaceMaterial = SurfaceMaterial.Opaque(color(role))

    // The component answers below exist because these widgets differ between systems by
    // more than a colour, and a widget that cannot ask has to guess. They are grouped by
    // family rather than one method per widget: adding a widget to a family that already
    // exists costs nothing here, which is what keeps a seventh design system from having
    // to be rewritten every time the vocabulary grows.
    //
    // Each has a default, so a system implements what it has an opinion about and
    // inherits a reasonable answer for the rest. The defaults lean on the roles above, so
    // an unimplemented member still comes out in that system's own colours rather than in
    // someone else's.

    /** Checkbox, RadioButton, Switch and Slider. */
    fun control(kind: ControlKind): ControlStyle = ControlStyle(
        size = if (kind == ControlKind.Slider) 24.dp else 20.dp,
        container = color(ColorRole.SurfaceVariant),
        containerSelected = color(ColorRole.Primary),
        indicator = color(ColorRole.OnSurfaceVariant),
        indicatorSelected = color(ColorRole.OnPrimary),
        border = color(ColorRole.Outline),
        borderWidth = 1.dp,
        shape = if (kind == ControlKind.RadioButton) ShapeRole.Full else ShapeRole.ExtraSmall,
        thumbSize = 20.dp,
        trackHeight = 4.dp,
        disabledAlpha = 0.38f,
        ripple = false,
    )

    /** The bar or the spinner. */
    fun progress(circular: Boolean): ProgressStyle = ProgressStyle(
        thickness = 4.dp,
        track = color(ColorRole.SurfaceVariant),
        indicator = color(ColorRole.Primary),
        size = 40.dp,
        rounded = true,
        indeterminatePeriodMillis = 1_200,
    )

    fun divider(): DividerStyle = DividerStyle(
        thickness = 1.dp,
        color = color(ColorRole.OutlineVariant),
        inset = 0.dp,
    )

    /** How a text field is framed, which is one of the clearest differences on screen. */
    fun field(): FieldStyle = FieldStyle(
        container = color(ColorRole.SurfaceVariant),
        containerFocused = color(ColorRole.SurfaceVariant),
        content = color(ColorRole.OnSurface),
        placeholder = color(ColorRole.OnSurfaceVariant),
        border = color(ColorRole.Outline),
        borderFocused = color(ColorRole.Primary),
        borderWidth = 1.dp,
        borderWidthFocused = 2.dp,
        underline = false,
        shape = ShapeRole.Small,
        contentPadding = SpaceRole.Sm,
        cursor = color(ColorRole.Primary),
    )

    /**
     * How a date, a time or a choice is picked.
     *
     * The presentation is the answer that matters. These are not one control styled three
     * ways: a calendar grid, a wheel and a flyout are operated differently, and only the
     * design system knows which one it means. A widget carries a value and a range and
     * has no property that could ask for a wheel.
     */
    fun picker(kind: PickerKind): PickerStyle = PickerStyle(
        presentation = when (kind) {
            PickerKind.Date -> PickerPresentation.CalendarGrid
            PickerKind.Time -> PickerPresentation.Dial
            PickerKind.Choice -> PickerPresentation.Menu
        },
        surface = color(ColorRole.Surface),
        shape = ShapeRole.Medium,
        elevation = 3.dp,
        itemExtent = 40.dp,
        visibleItems = 5,
    )

    /** Dialog, Menu and Tooltip: what appears over the screen rather than in it. */
    fun overlay(kind: OverlayKind): OverlayStyle = OverlayStyle(
        presentation = when (kind) {
            OverlayKind.Dialog -> OverlayPresentation.CenteredModal
            OverlayKind.Menu -> OverlayPresentation.Dropdown
            OverlayKind.Tooltip -> OverlayPresentation.Popover
        },
        surface = color(ColorRole.Surface),
        shape = if (kind == OverlayKind.Dialog) ShapeRole.Large else ShapeRole.Small,
        elevation = if (kind == OverlayKind.Dialog) 6.dp else 3.dp,
        scrim = if (kind == OverlayKind.Dialog) color(ColorRole.Background).copy(alpha = 0.32f) else null,
        padding = SpaceRole.Md,
        enterMillis = motion.pressMillis,
        exitMillis = motion.releaseMillis,
    )

    /** Tabs and TopAppBar, which disagree about height and about where a title sits. */
    fun navigation(kind: NavigationKind): NavigationStyle = NavigationStyle(
        height = if (kind == NavigationKind.TopAppBar) 56.dp else 48.dp,
        surface = color(ColorRole.Surface),
        content = color(ColorRole.OnSurfaceVariant),
        contentSelected = color(ColorRole.Primary),
        indicator = TabIndicator.Underline,
        indicatorThickness = 2.dp,
        titleAlignment = TitleAlignment.Start,
        elevationOnScroll = 3.dp,
    )

    /** What a list does at its edges, which is a recognisable platform signature. */
    fun scroll(): ScrollStyle = ScrollStyle(
        overscroll = OverscrollBehaviour.Stretch,
        persistentScrollbar = false,
        scrollbarThickness = 4.dp,
        scrollbarColor = color(ColorRole.OutlineVariant),
    )

    /** The metrics of the icon family this system draws with. */
    fun icons(): IconStyle = IconStyle(size = 24.dp, strokeWidth = 2.dp, filled = false)
}

/**
 * The design system in effect for this part of the tree.
 *
 * Static because it changes rarely and reading it must not cost a recomposition scope on
 * every widget that asks.
 */
val LocalDesignSystem = staticCompositionLocalOf<DesignSystem> {
    error(
        "No design system is in scope. Wrap this content in a DesignSystemProvider, or in " +
            "the renderer's host composable, which installs the one the application asked " +
            "for.",
    )
}

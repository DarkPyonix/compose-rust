package dioxus.compose.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dioxus.compose.protocol.ButtonVariant
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.IconRole
import dioxus.compose.protocol.MaterialRole
import dioxus.compose.protocol.ShapeRole
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.protocol.TypeRole
import dioxus.compose.protocol.WindowSizeClass

/**
 * Material 3 Expressive rules: elevation, button variants and motion.
 *
 * Reference: m3.material.io, "Elevation", "Buttons", "Shape" and "Motion easing and
 * duration", the 2025 expressive revision, which is the revision the generated token
 * table cites and the one the screens in `docs/references/design-systems/material3/` are
 * drawn in.
 *
 * Elevation is tonal plus a shadow, buttons are capsules at every size with a state layer
 * that grows on press, motion uses the emphasised easing, and corners are cut on the
 * expressive ladder, which is the part of this that a reader sees first.
 */
internal object Material3Rules : ComponentRules {
    override fun elevation(modifier: Modifier, elevation: Dp, shape: Shape, theme: ResolvedTheme): Modifier {
        if (elevation.value <= 0f) return modifier
        // Material 3 raises the surface tone with height as well as casting a shadow. The
        // tint is drawn first so a node that also sets its own Background still wins.
        val tint = lerp(
            theme.color(ColorRole.Surface),
            theme.color(ColorRole.Primary),
            (elevation.value / TONE_FULL_DP).coerceIn(0f, MAX_TONE),
        )
        return modifier.shadow(elevation, shape).background(tint, shape)
    }

    override fun button(variant: ButtonVariant, theme: ResolvedTheme): ButtonStyle {
        val outline = theme.color(ColorRole.Outline)
        val base = ButtonStyle(
            container = Color.Transparent,
            pressedContainer = Color.Transparent,
            content = theme.color(ColorRole.Primary),
            // The state layer, not opacity, is Material's press feedback, so the label
            // keeps its full opacity.
            pressedContentAlpha = 1f,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
            pressedBorderColor = Color.Transparent,
            topHighlight = null,
            // Material 3 buttons are pills.
            shape = theme.shape(ShapeRole.Full),
            horizontalPadding = theme.space(SpaceRole.Lg),
            verticalPadding = theme.space(SpaceRole.Sm) + 2.dp,
            minHeight = 40.dp,
            typeRole = TypeRole.Label,
            restElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledAlpha = DISABLED_ALPHA,
        )
        return when (variant) {
            ButtonVariant.Filled -> base.copy(
                container = theme.color(ColorRole.Primary),
                pressedContainer = stateLayer(theme.color(ColorRole.Primary), theme.color(ColorRole.OnPrimary)),
                content = theme.color(ColorRole.OnPrimary),
                // A pressed filled button rises: Material's feedback is tonal and spatial.
                pressedElevation = 2.dp,
            )

            ButtonVariant.Tonal -> base.copy(
                container = theme.color(ColorRole.SurfaceVariant),
                pressedContainer = stateLayer(
                    theme.color(ColorRole.SurfaceVariant),
                    theme.color(ColorRole.OnSurfaceVariant),
                ),
                content = theme.color(ColorRole.OnSurfaceVariant),
                pressedElevation = 1.dp,
            )

            ButtonVariant.Operator -> base.copy(
                container = theme.color(ColorRole.SurfaceVariant),
                pressedContainer = stateLayer(
                    theme.color(ColorRole.SurfaceVariant),
                    theme.color(ColorRole.Primary),
                ),
                content = theme.color(ColorRole.Primary),
                pressedElevation = 1.dp,
            )

            ButtonVariant.Outlined -> base.copy(
                pressedContainer = stateLayer(Color.Transparent, theme.color(ColorRole.Primary)),
                borderWidth = 1.dp,
                borderColor = outline,
                pressedBorderColor = outline,
            )

            ButtonVariant.Text -> base.copy(
                pressedContainer = stateLayer(Color.Transparent, theme.color(ColorRole.Primary)),
            )
        }
    }

    /** Material's pressed state layer: the content colour at 12% over the container. */
    private fun stateLayer(container: Color, content: Color): Color =
        lerp(container, content.copy(alpha = 1f), STATE_LAYER_ALPHA)
            .copy(alpha = if (container.alpha == 0f) STATE_LAYER_ALPHA else container.alpha)

    /**
     * Material's containers: the card is a tonal surface with a large corner, the dialog
     * and the menu rise above the screen, and the tooltip inverts the surface so it reads
     * as an overlay rather than part of the page.
     */
    override fun container(role: ContainerRole, theme: ResolvedTheme): ContainerStyle {
        val base = ContainerStyle(
            container = theme.color(ColorRole.Surface),
            content = theme.color(ColorRole.OnSurface),
            shape = theme.shape(ShapeRole.Medium),
            elevation = 0.dp,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
            horizontalPadding = theme.space(SpaceRole.Md),
            verticalPadding = theme.space(SpaceRole.Md),
            separator = null,
            scrim = Color.Transparent,
            typeRole = TypeRole.Body,
        )
        return when (role) {
            ContainerRole.Card -> base.copy(
                container = theme.color(ColorRole.SurfaceVariant),
                content = theme.color(ColorRole.OnSurfaceVariant),
                shape = theme.shape(ShapeRole.Large),
                elevation = 1.dp,
            )

            // A `Surface` is a panel: a layer raised off the page, holding the page's own
            // reading ink. That is `SurfaceContainer`, not `Surface`. `Surface` is the base
            // reading colour, and Material 3 gives it the same value as `Background` on
            // purpose, so a panel painted with it was drawn full size, in the right colour,
            // and could not be seen against the page it sat on.
            ContainerRole.Surface -> base.copy(container = theme.color(ColorRole.SurfaceContainer))

            ContainerRole.TopAppBar -> base.copy(
                shape = theme.shape(ShapeRole.None),
                verticalPadding = theme.space(SpaceRole.Sm),
                typeRole = TypeRole.Title,
            )

            ContainerRole.Dialog -> base.copy(
                shape = theme.shape(ShapeRole.Large),
                elevation = 6.dp,
                horizontalPadding = theme.space(SpaceRole.Lg),
                verticalPadding = theme.space(SpaceRole.Lg),
                scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
            )

            ContainerRole.Menu -> base.copy(
                shape = theme.shape(ShapeRole.Small),
                elevation = 3.dp,
                horizontalPadding = 0.dp,
                verticalPadding = theme.space(SpaceRole.Xs),
            )

            // The inverse surface: light text on a dark chip, and the reverse in dark mode.
            ContainerRole.Tooltip -> base.copy(
                container = theme.color(ColorRole.OnSurface),
                content = theme.color(ColorRole.Surface),
                shape = theme.shape(ShapeRole.ExtraSmall),
                horizontalPadding = theme.space(SpaceRole.Sm),
                verticalPadding = theme.space(SpaceRole.Xs),
                typeRole = TypeRole.Caption,
            )
        }
    }

    /**
     * A Material tab row: the selected tab is underlined across its full width.
     *
     * The rule under it is a thick rounded bar rather than a hairline. That is the
     * expressive indicator, and it is what the reference screens draw under a selected
     * destination: a stub of capsule, not a line.
     */
    override fun tabs(theme: ResolvedTheme): TabsStyle = TabsStyle(
        container = theme.color(ColorRole.Surface),
        shape = theme.shape(ShapeRole.None),
        selectedContent = theme.color(ColorRole.Primary),
        unselectedContent = theme.color(ColorRole.OnSurfaceVariant),
        selectedContainer = Color.Transparent,
        selectedShape = theme.shape(ShapeRole.None),
        indicator = theme.color(ColorRole.Primary),
        indicatorHeight = 4.dp,
        indicatorShape = theme.shape(ShapeRole.Full),
        indicatorFillsTab = true,
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Sm),
        typeRole = TypeRole.Label,
    )

    /**
     * Material draws its own controls: `androidx.compose.material3` is already here, and a
     * hand-written copy of a filled box with a tick would only be a worse one.
     */
    override val controlWidgets: ControlWidgets get() = Material3ControlWidgets

    /**
     * Material's controls: a filled box with a tick, a ring with a dot, and a wide track
     * whose thumb travels across it. The unchecked states are outlined rather than
     * filled, which is what makes a checked one read as a deliberate choice.
     *
     * The colours here are what [Material3ControlWidgets] hands the library, and the
     * divider is drawn from them directly. The dimensions are the library's own.
     */
    override fun controls(theme: ResolvedTheme): ControlsStyle {
        val outline = theme.color(ColorRole.Outline)
        val primary = theme.color(ColorRole.Primary)
        return ControlsStyle(
            checkbox = ToggleStyle(
                size = 18.dp,
                container = Color.Transparent,
                containerChecked = primary,
                mark = theme.color(ColorRole.OnPrimary),
                markUnchecked = Color.Transparent,
                border = outline,
                borderWidth = 2.dp,
                shape = theme.shape(ShapeRole.ExtraSmall),
                thumbSize = 0.dp,
                trackWidth = 0.dp,
                trackHeight = 0.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            radioButton = ToggleStyle(
                size = 20.dp,
                container = Color.Transparent,
                containerChecked = Color.Transparent,
                mark = primary,
                markUnchecked = Color.Transparent,
                border = outline,
                borderWidth = 2.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 10.dp,
                trackWidth = 0.dp,
                trackHeight = 0.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            // Material's switch is the widest of the three: a 52 by 32 track carrying a
            // thumb that nearly fills its height.
            switch = ToggleStyle(
                size = 32.dp,
                container = theme.color(ColorRole.SurfaceVariant),
                containerChecked = primary,
                mark = theme.color(ColorRole.OnPrimary),
                markUnchecked = outline,
                border = outline,
                borderWidth = 2.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 24.dp,
                trackWidth = 52.dp,
                trackHeight = 32.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            slider = SliderStyle(
                trackHeight = 4.dp,
                track = theme.color(ColorRole.SurfaceVariant),
                activeTrack = primary,
                thumbSize = 20.dp,
                thumb = primary,
                thumbBorder = Color.Transparent,
                thumbBorderWidth = 0.dp,
                // Material marks the stops of a stepped slider on the track itself.
                tick = theme.color(ColorRole.OnPrimary),
            ),
            progress = ProgressStyle(
                thickness = 4.dp,
                track = theme.color(ColorRole.SurfaceVariant),
                indicator = primary,
                diameter = 40.dp,
                rounded = true,
                periodMillis = 1_200,
            ),
            divider = DividerStyle(
                thickness = 1.dp,
                color = theme.color(ColorRole.OutlineVariant),
                inset = 0.dp,
            ),
        )
    }

    /**
     * A filled field with a rule under it, and nothing down the sides.
     *
     * The rule is the whole of Material's frame: it darkens and doubles in width when the
     * caret goes in, and the box it sits under never gets a line at all. Tall, because a
     * filled Material field leaves room above the text for a label whether or not one is
     * there.
     */
    override fun field(theme: ResolvedTheme): FieldStyle = FieldStyle(
        container = theme.color(ColorRole.SurfaceVariant),
        containerFocused = theme.color(ColorRole.SurfaceVariant),
        border = Color.Transparent,
        borderFocused = Color.Transparent,
        borderWidth = 0.dp,
        borderWidthFocused = 0.dp,
        underline = FieldUnderline(
            color = theme.color(ColorRole.OnSurfaceVariant),
            focusedColor = theme.color(ColorRole.Primary),
            width = 1.dp,
            focusedWidth = 2.dp,
        ),
        shape = theme.shape(ShapeRole.ExtraSmall),
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Sm),
        cursor = theme.color(ColorRole.Primary),
        minHeight = 56.dp,
    )

    /**
     * Material Symbols metrics: a 24 dp grid, a 2 dp stem and flat ends.
     *
     * Every role is drawn to the same weight, which is what makes a row of them line up.
     */
    override fun icon(role: IconRole, theme: ResolvedTheme): IconStyle = IconStyle(
        size = 24.dp,
        strokeWidth = 2.dp,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )

    /** A calendar grid for the date, a dial for the time, a menu under the field. */
    override val pickers: PickerRules = PickerRules(
        date = DatePresentation.CalendarGrid,
        time = TimePresentation.Dial,
        choice = ChoicePresentation.ExposedMenu,
    )

    /**
     * Material 3 does not blur. It raises the surface and lays a tone of the primary
     * colour over it, and thickness is how much of that tone there is. Drawing Apple's
     * blur here would be the mistake the shared vocabulary exists to prevent: the
     * declaration is the same, the answer is this system's own.
     */
    override fun material(role: MaterialRole, theme: ResolvedTheme): SurfaceMaterial {
        val tone = when (role) {
            MaterialRole.Thin -> 0.05f
            MaterialRole.Regular -> 0.08f
            MaterialRole.Thick -> 0.11f
            MaterialRole.Chrome -> 0.14f
        }
        val tint = theme.color(ColorRole.Primary).copy(alpha = tone)
        return SurfaceMaterial.Opaque(compositeOver(tint, theme.color(ColorRole.Surface)))
    }

    override val motion: Motion = Motion(
        pressMillis = 100,
        releaseMillis = 200,
        easing = FastOutSlowInEasing,
        tooltipDelayMillis = 500,
        // Material 3's own durations: short4, medium2, long2, and the emphasized
        // transition that the system asks for whenever a change is worth noticing.
        quickMillis = 150,
        standardMillis = 300,
        slowMillis = 450,
        emphasizedMillis = 500,
    )


    /**
     * Material's navigation set: a bar of destinations at the bottom of a phone-shaped
     * window, a rail beside a tablet-shaped one, a drawer standing open on a desktop.
     *
     * The mark is the pill Material 3 puts behind the selected icon, which is the single
     * thing that reads most as Material in a row of destinations.
     */
    override fun navigation(sizeClass: WindowSizeClass, theme: ResolvedTheme): NavigationStyle =
        NavigationStyle(
            presentation = when (sizeClass) {
                WindowSizeClass.Compact -> NavigationPresentation.Bar
                WindowSizeClass.Medium -> NavigationPresentation.Rail
                WindowSizeClass.Expanded -> NavigationPresentation.Drawer
            },
            container = theme.color(ColorRole.SurfaceContainer),
            content = theme.color(ColorRole.OnSurfaceVariant),
            selectedContent = theme.color(ColorRole.OnSurface),
            indicator = theme.color(ColorRole.SurfaceVariant),
            indicatorShape = theme.shape(ShapeRole.Full),
            indicatorKind = NavigationIndicator.Pill,
            // Material's bars sit flush against the content and are separated by tone.
            separator = null,
            barHeight = 80.dp,
            railWidth = 80.dp,
            drawerWidth = 280.dp,
            itemSpacing = theme.space(SpaceRole.Xs),
            itemPadding = theme.space(SpaceRole.Sm),
            labelInRail = true,
            typeRole = TypeRole.Label,
        )

    /**
     * Material has no desktop caption of its own, so this is Material's vocabulary applied
     * to one: a capsule hover behind each glyph, in the tonal grey a Material icon button
     * uses, and the error colour under close. The title sits at the start, where a
     * Material top app bar puts it.
     */
    override fun caption(theme: ResolvedTheme): CaptionStyle = CaptionStyle(
        side = CaptionSide.End,
        buttonWidth = 40.dp,
        buttonHeight = 40.dp,
        shape = theme.shape(ShapeRole.Full),
        minimiseContainer = Color.Transparent,
        maximiseContainer = Color.Transparent,
        closeContainer = Color.Transparent,
        hover = theme.color(ColorRole.SurfaceVariant),
        closeHover = theme.color(ColorRole.Error),
        glyph = theme.color(ColorRole.OnSurfaceVariant),
        closeHoverGlyph = theme.color(ColorRole.OnError),
        glyphStroke = 1.5.dp,
        glyphAtRest = true,
        spacing = theme.space(SpaceRole.Xs),
        edgePadding = theme.space(SpaceRole.Sm),
        titleAlignment = CaptionTitleAlignment.Start,
    )

    /** A bottom sheet with a drag handle, or a side sheet once there is room for one. */
    override fun sheet(sizeClass: WindowSizeClass, theme: ResolvedTheme): SheetStyle = SheetStyle(
        edge = if (sizeClass == WindowSizeClass.Compact) SheetEdge.Bottom else SheetEdge.End,
        container = theme.color(ColorRole.SurfaceContainer),
        content = theme.color(ColorRole.OnSurface),
        shape = theme.shape(ShapeRole.Large),
        elevation = 1.dp,
        scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
        handle = theme.color(ColorRole.OutlineVariant),
        widthFraction = 0.4f,
        heightFraction = 0.5f,
        padding = theme.space(SpaceRole.Lg),
        borderWidth = 0.dp,
        borderColor = Color.Transparent,
    )

    /** A snackbar: the inverse surface, low and to the leading side, with four seconds. */
    override fun message(theme: ResolvedTheme): MessageStyle = MessageStyle(
        container = theme.color(ColorRole.OnSurface),
        content = theme.color(ColorRole.Surface),
        actionContent = theme.color(ColorRole.Primary),
        shape = theme.shape(ShapeRole.ExtraSmall),
        elevation = 6.dp,
        placement = MessagePlacement.BottomStart,
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Sm),
        inset = theme.space(SpaceRole.Md),
        // The two durations Material documents for a snackbar.
        shortMillis = 4_000,
        longMillis = 10_000,
        borderWidth = 0.dp,
        borderColor = Color.Transparent,
        typeRole = TypeRole.Body,
    )

    private const val SCRIM_ALPHA = 0.32f
    private const val DISABLED_ALPHA = 0.38f
    private const val STATE_LAYER_ALPHA = 0.12f
    private const val TONE_FULL_DP = 24f
    private const val MAX_TONE = 0.14f
}

/**
 * Cupertino rules: elevation, button variants and motion.
 *
 * Reference: Apple Human Interface Guidelines, "Materials", "Buttons" and "Motion", 2024.
 *
 * Shadows are wide and faint rather than layered, the emphasised button is flat with no
 * shadow at all, and the press feedback is a dim, not a ripple.
 */
internal object CupertinoRules : ComponentRules {
    override fun elevation(modifier: Modifier, elevation: Dp, shape: Shape, theme: ResolvedTheme): Modifier {
        if (elevation.value <= 0f) return modifier
        // One soft shadow spread over roughly twice the requested height, at a low alpha.
        return modifier.shadow(
            elevation = elevation * SPREAD,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = AMBIENT_ALPHA),
            spotColor = Color.Black.copy(alpha = SPOT_ALPHA),
        )
    }

    override fun button(variant: ButtonVariant, theme: ResolvedTheme): ButtonStyle {
        val base = ButtonStyle(
            container = Color.Transparent,
            pressedContainer = Color.Transparent,
            content = theme.color(ColorRole.Primary),
            // HIG presses dim the whole control instead of layering a colour on it.
            pressedContentAlpha = PRESSED_ALPHA,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
            pressedBorderColor = Color.Transparent,
            topHighlight = null,
            // The continuous curvature of a HIG capsule is approximated by the Medium
            // radius; see the note in the Renderer README about squircle support.
            shape = theme.shape(ShapeRole.Medium),
            horizontalPadding = theme.space(SpaceRole.Md),
            verticalPadding = theme.space(SpaceRole.Sm),
            minHeight = 34.dp,
            typeRole = TypeRole.BodyStrong,
            // Apple's buttons never cast a shadow, pressed or not.
            restElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledAlpha = DISABLED_ALPHA,
        )
        return when (variant) {
            ButtonVariant.Filled, ButtonVariant.Operator -> base.copy(
                container = theme.color(ColorRole.Primary),
                pressedContainer = theme.color(ColorRole.Primary).copy(alpha = PRESSED_ALPHA),
                content = theme.color(ColorRole.OnPrimary),
            )

            ButtonVariant.Tonal -> base.copy(
                container = theme.color(ColorRole.SurfaceVariant),
                pressedContainer = theme.color(ColorRole.OutlineVariant),
                content = theme.color(ColorRole.Primary),
            )

            ButtonVariant.Outlined -> base.copy(
                pressedContainer = theme.color(ColorRole.SurfaceVariant),
                borderWidth = 1.dp,
                borderColor = theme.color(ColorRole.Outline),
                pressedBorderColor = theme.color(ColorRole.Outline),
            )

            // A plain button: content colour only, no container even when pressed.
            ButtonVariant.Text -> base
        }
    }

    /**
     * HIG containers: grouped content sits on a slightly different surface rather than
     * casting a shadow, bars are separated by a hairline, and only what floats over the
     * screen is raised at all.
     */
    override fun container(role: ContainerRole, theme: ResolvedTheme): ContainerStyle {
        val base = ContainerStyle(
            container = theme.color(ColorRole.Surface),
            content = theme.color(ColorRole.OnSurface),
            shape = theme.shape(ShapeRole.Medium),
            elevation = 0.dp,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
            horizontalPadding = theme.space(SpaceRole.Md),
            verticalPadding = theme.space(SpaceRole.Md),
            separator = null,
            scrim = Color.Transparent,
            typeRole = TypeRole.Body,
        )
        return when (role) {
            // A grouped box: no shadow, just a different surface and a generous corner.
            ContainerRole.Card -> base.copy(
                container = theme.color(ColorRole.SurfaceVariant),
                shape = theme.shape(ShapeRole.Large),
            )

            // A `Surface` is a panel: a layer raised off the page, holding the page's own
            // reading ink. That is `SurfaceContainer`, not `Surface`. `Surface` is the base
            // reading colour, and Material 3 gives it the same value as `Background` on
            // purpose, so a panel painted with it was drawn full size, in the right colour,
            // and could not be seen against the page it sat on.
            ContainerRole.Surface -> base.copy(container = theme.color(ColorRole.SurfaceContainer))

            // A navigation bar is flush with the content and divided by a hairline.
            ContainerRole.TopAppBar -> base.copy(
                shape = theme.shape(ShapeRole.None),
                verticalPadding = theme.space(SpaceRole.Sm),
                separator = theme.color(ColorRole.OutlineVariant),
                typeRole = TypeRole.BodyStrong,
            )

            // An alert: centred, heavily rounded, over a dimmed screen.
            ContainerRole.Dialog -> base.copy(
                shape = theme.shape(ShapeRole.Large),
                horizontalPadding = theme.space(SpaceRole.Lg),
                verticalPadding = theme.space(SpaceRole.Lg),
                scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
            )

            ContainerRole.Menu -> base.copy(
                elevation = 2.dp,
                horizontalPadding = 0.dp,
                verticalPadding = theme.space(SpaceRole.Xs),
                borderWidth = 1.dp,
                borderColor = theme.color(ColorRole.OutlineVariant),
            )

            // A help tag: a light chip with a hairline, not an inverted one.
            ContainerRole.Tooltip -> base.copy(
                shape = theme.shape(ShapeRole.Small),
                borderWidth = 1.dp,
                borderColor = theme.color(ColorRole.OutlineVariant),
                horizontalPadding = theme.space(SpaceRole.Sm),
                verticalPadding = theme.space(SpaceRole.Xs),
                typeRole = TypeRole.Caption,
            )
        }
    }

    /** A segmented control: the selection is a filled segment inside a track. */
    override fun tabs(theme: ResolvedTheme): TabsStyle = TabsStyle(
        container = theme.color(ColorRole.SurfaceVariant),
        shape = theme.shape(ShapeRole.Medium),
        selectedContent = theme.color(ColorRole.OnSurface),
        unselectedContent = theme.color(ColorRole.OnSurfaceVariant),
        selectedContainer = theme.color(ColorRole.Surface),
        selectedShape = theme.shape(ShapeRole.Small),
        indicator = Color.Transparent,
        indicatorHeight = 0.dp,
        indicatorShape = theme.shape(ShapeRole.None),
        indicatorFillsTab = true,
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Xs),
        typeRole = TypeRole.Body,
    )

    /**
     * HIG controls: the checkmark sits in a filled circle rather than a square, the
     * switch is a tall capsule with a pale thumb that nearly fills it, and a slider shows
     * no tick marks, because a stepped iOS slider still reads as continuous.
     */
    override fun controls(theme: ResolvedTheme): ControlsStyle {
        val primary = theme.color(ColorRole.Primary)
        val surface = theme.color(ColorRole.Surface)
        return ControlsStyle(
            // A circle, not a box, which is the clearest difference from Material here.
            checkbox = ToggleStyle(
                size = 22.dp,
                container = Color.Transparent,
                containerChecked = primary,
                mark = theme.color(ColorRole.OnPrimary),
                markUnchecked = Color.Transparent,
                border = theme.color(ColorRole.Outline),
                borderWidth = 1.5.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 0.dp,
                trackWidth = 0.dp,
                trackHeight = 0.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            radioButton = ToggleStyle(
                size = 22.dp,
                container = Color.Transparent,
                containerChecked = primary,
                mark = theme.color(ColorRole.OnPrimary),
                markUnchecked = Color.Transparent,
                border = theme.color(ColorRole.Outline),
                borderWidth = 1.5.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 8.dp,
                trackWidth = 0.dp,
                trackHeight = 0.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            // 51 by 31, the proportions Apple's switch has always had.
            switch = ToggleStyle(
                size = 31.dp,
                container = theme.color(ColorRole.SurfaceVariant),
                containerChecked = primary,
                mark = surface,
                markUnchecked = surface,
                border = Color.Transparent,
                borderWidth = 0.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 27.dp,
                trackWidth = 51.dp,
                trackHeight = 31.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            slider = SliderStyle(
                trackHeight = 4.dp,
                track = theme.color(ColorRole.OutlineVariant),
                activeTrack = primary,
                // A large pale thumb that sits over the track rather than in it.
                thumbSize = 28.dp,
                thumb = surface,
                thumbBorder = theme.color(ColorRole.OutlineVariant),
                thumbBorderWidth = 0.5.dp,
                tick = null,
            ),
            progress = ProgressStyle(
                thickness = 3.dp,
                track = theme.color(ColorRole.OutlineVariant),
                indicator = primary,
                diameter = 20.dp,
                rounded = true,
                periodMillis = 1_000,
            ),
            // A hairline held back from the leading edge, the way a grouped list rules
            // between its rows.
            divider = DividerStyle(
                thickness = 0.5.dp,
                color = theme.color(ColorRole.OutlineVariant),
                inset = 16.dp,
            ),
        )
    }

    /**
     * A rounded box with a hairline, and the focus ring macOS puts around whatever the
     * caret is in.
     *
     * The ring is the accent at three times the resting width rather than a recoloured
     * hairline, because on Apple's desktop it reads as something laid around the field
     * rather than as the field's own edge changing colour. Short: a HIG field is the
     * least tall of the six.
     */
    override fun field(theme: ResolvedTheme): FieldStyle = FieldStyle(
        container = theme.color(ColorRole.Surface),
        containerFocused = theme.color(ColorRole.Surface),
        border = theme.color(ColorRole.Outline),
        borderFocused = theme.color(ColorRole.Primary),
        borderWidth = 1.dp,
        borderWidthFocused = 3.dp,
        underline = null,
        shape = theme.shape(ShapeRole.Small),
        horizontalPadding = theme.space(SpaceRole.Sm),
        verticalPadding = theme.space(SpaceRole.Xs),
        cursor = theme.color(ColorRole.Primary),
        minHeight = 30.dp,
    )

    /**
     * SF Symbols metrics: a lighter stroke on a 22 dp grid, with rounded ends and joins.
     * The rounded terminal is the single thing that reads most as Apple's icon set.
     */
    override fun icon(role: IconRole, theme: ResolvedTheme): IconStyle = IconStyle(
        size = 22.dp,
        strokeWidth = 1.75.dp,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )

    /** Wheels throughout: a date, a time and a list are all spun to the value. */
    override val pickers: PickerRules = PickerRules(
        date = DatePresentation.Wheel,
        time = TimePresentation.Wheel,
        choice = ChoicePresentation.Wheel,
    )

    /**
     * The blur materials iOS had before glass. Thin and regular let a good deal of the
     * backdrop through; a chrome bar has to win against whatever is scrolling under it.
     */
    override fun material(role: MaterialRole, theme: ResolvedTheme): SurfaceMaterial {
        val prominence = when (role) {
            MaterialRole.Thin, MaterialRole.Regular -> GlassProminence.Clear
            MaterialRole.Thick, MaterialRole.Chrome -> GlassProminence.Regular
        }
        val glass = LiquidGlass.material(
            dark = theme.dark,
            prominence = prominence,
            backdrop = theme.color(ColorRole.Surface),
            content = theme.color(ColorRole.OnSurface),
        )
        // Shorter radii than the glass system uses. A pre-26 blur is a frosted pane, not
        // a lens, and it does not bend what is behind it.
        return glass.copy(
            blurRadius = when (role) {
                MaterialRole.Thin -> 8.dp
                MaterialRole.Regular -> 14.dp
                MaterialRole.Thick -> 20.dp
                MaterialRole.Chrome -> 24.dp
            },
        )
    }

    override val motion: Motion = Motion(
        pressMillis = 80,
        releaseMillis = 180,
        easing = LinearOutSlowInEasing,
        // A help tag waits until the pointer has clearly stopped.
        tooltipDelayMillis = 1000,
        // Shorter than Material throughout. An iOS transition settles rather than
        // arrives, and a long one reads as sluggish next to the system's own.
        quickMillis = 120,
        standardMillis = 250,
        slowMillis = 400,
        emphasizedMillis = 350,
    )


    /**
     * A tab bar at the bottom of a phone, a sidebar once the window is wide enough.
     *
     * The tab bar marks its selection with colour alone, which is what Apple does; the
     * sidebar marks it with a rounded fill, which is also what Apple does. One style
     * expresses both because the size class is what it is answering about.
     */
    override fun navigation(sizeClass: WindowSizeClass, theme: ResolvedTheme): NavigationStyle {
        val presentation = when (sizeClass) {
            // Two platforms draw this language and they answer a narrow window
            // differently. A phone has its own tab bar and the destinations are handed to
            // it (FR-14.9), so the presentation there stays the bar the shell is offered.
            // A narrow window on a desktop has no tab bar to hand them to, and that
            // platform's answer for a list with no room is to take it off the screen and
            // leave the button that brings it back.
            WindowSizeClass.Compact -> if (theme.platform == HostPlatform.Ios ||
                theme.platform == HostPlatform.Android
            ) {
                NavigationPresentation.Bar
            } else {
                NavigationPresentation.PutAway
            }
            // No rail. A column of icons with the words taken off is Material's answer to
            // a medium window and it is not this language's: Notes, Mail and Finder keep a
            // sidebar with its labels at every width a window can be dragged to, and the
            // one thing they do when there is truly no room is put the whole thing away.
            // None of them shows icons without their words.
            WindowSizeClass.Medium -> NavigationPresentation.Drawer
            WindowSizeClass.Expanded -> NavigationPresentation.Drawer
        }
        val bar = presentation == NavigationPresentation.Bar
        return NavigationStyle(
            presentation = presentation,
            container = theme.color(ColorRole.SurfaceContainer),
            content = theme.color(ColorRole.OnSurfaceVariant),
            // The same split as the glass system: the bar tints, the sidebar fills.
            selectedContent = if (bar) {
                theme.color(ColorRole.Primary)
            } else {
                theme.color(ColorRole.OnSurfaceVariant)
            },
            indicator = if (bar) Color.Transparent else theme.color(ColorRole.SurfaceVariant),
            indicatorShape = theme.shape(ShapeRole.Medium),
            indicatorKind = if (bar) NavigationIndicator.None else NavigationIndicator.Pill,
            // An Apple sidebar fills the whole row it marks, label included. A tab bar
            // marks nothing, so this says nothing about the bar.
            indicatorExtent = NavigationExtent.Destination,
            // A tab bar and a sidebar are both divided from the content by a hairline.
            separator = theme.color(ColorRole.OutlineVariant),
            barHeight = 50.dp,
            railWidth = 76.dp,
            drawerWidth = 260.dp,
            itemSpacing = theme.space(SpaceRole.Xs),
            itemPadding = theme.space(SpaceRole.Xs),
            labelInRail = true,
            typeRole = TypeRole.Caption,
        )
    }

    /**
     * Three coloured discs at the leading edge, close then minimise then zoom.
     *
     * This is only ever drawn where the platform does not draw it. On macOS the system
     * owns these buttons and imitating them would be the most visible way to fail at
     * looking native; on a Linux or Windows session running the Apple language there is
     * nothing to keep, and a caption with no buttons is a window nobody can close.
     *
     * The discs carry no glyphs until the pointer is over the set, which is the behaviour
     * that makes them read as Apple's rather than as three coloured dots.
     */
    override fun caption(theme: ResolvedTheme): CaptionStyle = CaptionStyle(
        side = CaptionSide.Start,
        buttonWidth = 12.dp,
        buttonHeight = 12.dp,
        shape = theme.shape(ShapeRole.Full),
        minimiseContainer = TRAFFIC_AMBER,
        maximiseContainer = TRAFFIC_GREEN,
        closeContainer = TRAFFIC_RED,
        hover = Color.Transparent,
        closeHover = Color.Transparent,
        glyph = Color.Black.copy(alpha = 0.55f),
        closeHoverGlyph = Color.Black.copy(alpha = 0.55f),
        glyphStroke = 1.dp,
        // The colour says which is which; the marks appear under the pointer.
        glyphAtRest = false,
        spacing = 8.dp,
        edgePadding = 20.dp,
        titleAlignment = CaptionTitleAlignment.Center,
    )

    /** A card sheet pulled up over a dimmed screen, with the grabber along its top edge. */
    override fun sheet(sizeClass: WindowSizeClass, theme: ResolvedTheme): SheetStyle = SheetStyle(
        edge = if (sizeClass == WindowSizeClass.Compact) SheetEdge.Bottom else SheetEdge.End,
        container = theme.color(ColorRole.SurfaceContainer),
        content = theme.color(ColorRole.OnSurface),
        shape = theme.shape(ShapeRole.Large),
        // Apple's sheets are not raised by a shadow, they cover.
        elevation = 0.dp,
        scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
        handle = theme.color(ColorRole.Outline),
        widthFraction = 0.38f,
        heightFraction = 0.55f,
        padding = theme.space(SpaceRole.Lg),
        borderWidth = 0.dp,
        borderColor = Color.Transparent,
    )

    /**
     * A banner rather than a snackbar: a light capsule at the top, with a hairline and no
     * inverted surface. Apple has no snackbar, and drawing one here would be the Material
     * answer wearing Apple's colours.
     */
    override fun message(theme: ResolvedTheme): MessageStyle = MessageStyle(
        container = theme.color(ColorRole.SurfaceContainer),
        content = theme.color(ColorRole.OnSurface),
        actionContent = theme.color(ColorRole.Primary),
        shape = theme.shape(ShapeRole.Large),
        elevation = 2.dp,
        placement = MessagePlacement.TopEnd,
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Sm),
        inset = theme.space(SpaceRole.Md),
        shortMillis = 3_000,
        longMillis = 8_000,
        borderWidth = 1.dp,
        borderColor = theme.color(ColorRole.OutlineVariant),
        typeRole = TypeRole.Body,
    )

    private const val SCRIM_ALPHA = 0.4f
    private const val DISABLED_ALPHA = 0.38f
    private const val PRESSED_ALPHA = 0.6f
    private const val AMBIENT_ALPHA = 0.08f
    private const val SPOT_ALPHA = 0.12f
    private const val SPREAD = 2f
}

/**
 * The three window button colours the Apple systems use, shared because they are the
 * same three buttons in both languages: the glass window in the reference screens carries
 * exactly the discs the flat one does.
 */
internal val TRAFFIC_RED = Color(0xFFFF5F57)
internal val TRAFFIC_AMBER = Color(0xFFFEBC2E)
internal val TRAFFIC_GREEN = Color(0xFF28C840)

/**
 * WinUI / Fluent 2 rules: elevation, button variants and motion.
 *
 * Reference: fluent2.microsoft.design, "Elevation", "Button" and "Motion duration", 2024.
 *
 * Elevation is a layer shadow with a hairline stroke, buttons are 4 dp rounded with a
 * lighter top edge, and the press is an immediate tone change with no travel.
 */
internal object FluentRules : ComponentRules {
    override fun elevation(modifier: Modifier, elevation: Dp, shape: Shape, theme: ResolvedTheme): Modifier {
        if (elevation.value <= 0f) return modifier
        // Fluent pairs every layer shadow with a thin stroke so the layer edge stays crisp.
        return modifier
            .shadow(elevation, shape, clip = false)
            .border(1.dp, theme.color(ColorRole.OutlineVariant), shape)
    }

    override fun button(variant: ButtonVariant, theme: ResolvedTheme): ButtonStyle {
        val outline = theme.color(ColorRole.Outline)
        val base = ButtonStyle(
            container = theme.color(ColorRole.Surface),
            pressedContainer = theme.color(ColorRole.SurfaceVariant),
            content = theme.color(ColorRole.OnSurface),
            pressedContentAlpha = 1f,
            borderWidth = 1.dp,
            borderColor = outline,
            pressedBorderColor = theme.color(ColorRole.OutlineVariant),
            // The lighter top edge every Fluent control carries.
            topHighlight = Color.White.copy(alpha = if (theme.dark) 0.08f else 0.6f),
            // Fluent corners are 4 dp, which is ShapeRole.Medium in the Fluent table.
            shape = theme.shape(ShapeRole.Medium),
            horizontalPadding = theme.space(SpaceRole.Lg),
            verticalPadding = theme.space(SpaceRole.Sm),
            minHeight = 32.dp,
            typeRole = TypeRole.BodyStrong,
            restElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledAlpha = DISABLED_ALPHA,
        )
        return when (variant) {
            // Accent button.
            ButtonVariant.Filled -> base.copy(
                container = theme.color(ColorRole.Primary),
                pressedContainer = lerp(theme.color(ColorRole.Primary), Color.Black, PRESS_SHADE),
                content = theme.color(ColorRole.OnPrimary),
                borderColor = theme.color(ColorRole.Primary),
                pressedBorderColor = Color.Transparent,
                topHighlight = Color.White.copy(alpha = 0.18f),
            )

            // Standard button.
            ButtonVariant.Tonal, ButtonVariant.Operator -> base

            // Standard button with a stronger stroke.
            ButtonVariant.Outlined -> base.copy(
                container = Color.Transparent,
                borderColor = outline,
                topHighlight = null,
            )

            // Subtle button: no container until it is pressed.
            ButtonVariant.Text -> base.copy(
                container = Color.Transparent,
                borderWidth = 0.dp,
                borderColor = Color.Transparent,
                pressedBorderColor = Color.Transparent,
                topHighlight = null,
            )
        }
    }

    /**
     * Fluent containers: a layer is a surface with a hairline stroke, and anything that
     * floats over the page carries both the stroke and a layer shadow.
     */
    override fun container(role: ContainerRole, theme: ResolvedTheme): ContainerStyle {
        val stroke = theme.color(ColorRole.OutlineVariant)
        val base = ContainerStyle(
            container = theme.color(ColorRole.Surface),
            content = theme.color(ColorRole.OnSurface),
            shape = theme.shape(ShapeRole.Medium),
            elevation = 0.dp,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
            horizontalPadding = theme.space(SpaceRole.Md),
            verticalPadding = theme.space(SpaceRole.Md),
            separator = null,
            scrim = Color.Transparent,
            typeRole = TypeRole.Body,
        )
        return when (role) {
            ContainerRole.Card -> base.copy(
                elevation = 2.dp,
                borderWidth = 1.dp,
                borderColor = stroke,
            )

            // A `Surface` is a panel: a layer raised off the page, holding the page's own
            // reading ink. That is `SurfaceContainer`, not `Surface`. `Surface` is the base
            // reading colour, and Material 3 gives it the same value as `Background` on
            // purpose, so a panel painted with it was drawn full size, in the right colour,
            // and could not be seen against the page it sat on.
            ContainerRole.Surface -> base.copy(container = theme.color(ColorRole.SurfaceContainer))

            // A command bar: flat, tight, and ruled off from the content below it.
            ContainerRole.TopAppBar -> base.copy(
                shape = theme.shape(ShapeRole.None),
                verticalPadding = theme.space(SpaceRole.Xs),
                separator = stroke,
                typeRole = TypeRole.BodyStrong,
            )

            ContainerRole.Dialog -> base.copy(
                elevation = 8.dp,
                borderWidth = 1.dp,
                borderColor = stroke,
                horizontalPadding = theme.space(SpaceRole.Lg),
                verticalPadding = theme.space(SpaceRole.Lg),
                scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
            )

            // A flyout.
            ContainerRole.Menu -> base.copy(
                elevation = 8.dp,
                borderWidth = 1.dp,
                borderColor = stroke,
                horizontalPadding = 0.dp,
                verticalPadding = theme.space(SpaceRole.Xs),
            )

            ContainerRole.Tooltip -> base.copy(
                container = theme.color(ColorRole.SurfaceVariant),
                content = theme.color(ColorRole.OnSurfaceVariant),
                shape = theme.shape(ShapeRole.Small),
                borderWidth = 1.dp,
                borderColor = stroke,
                horizontalPadding = theme.space(SpaceRole.Sm),
                verticalPadding = theme.space(SpaceRole.Xs),
                elevation = 4.dp,
                typeRole = TypeRole.Caption,
            )
        }
    }

    /** A pivot: a short rounded bar centred under the selected header. */
    override fun tabs(theme: ResolvedTheme): TabsStyle = TabsStyle(
        container = theme.color(ColorRole.Surface),
        shape = theme.shape(ShapeRole.None),
        selectedContent = theme.color(ColorRole.OnSurface),
        unselectedContent = theme.color(ColorRole.OnSurfaceVariant),
        selectedContainer = Color.Transparent,
        selectedShape = theme.shape(ShapeRole.None),
        indicator = theme.color(ColorRole.Primary),
        indicatorHeight = 3.dp,
        indicatorShape = theme.shape(ShapeRole.Full),
        indicatorFillsTab = false,
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Sm),
        typeRole = TypeRole.Subtitle,
    )

    /**
     * Fluent controls: everything is stroked. A checkbox is a 20 dp box with a hairline,
     * a radio button is a ring the dot sits inside, the toggle is short and narrow, and
     * the slider's thumb is a ring rather than a disc.
     */
    override fun controls(theme: ResolvedTheme): ControlsStyle {
        val outline = theme.color(ColorRole.Outline)
        val primary = theme.color(ColorRole.Primary)
        val onPrimary = theme.color(ColorRole.OnPrimary)
        return ControlsStyle(
            checkbox = ToggleStyle(
                size = 20.dp,
                container = Color.Transparent,
                containerChecked = primary,
                mark = onPrimary,
                markUnchecked = Color.Transparent,
                border = outline,
                borderWidth = 1.dp,
                shape = theme.shape(ShapeRole.Small),
                thumbSize = 0.dp,
                trackWidth = 0.dp,
                trackHeight = 0.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            radioButton = ToggleStyle(
                size = 20.dp,
                container = Color.Transparent,
                containerChecked = Color.Transparent,
                mark = primary,
                markUnchecked = Color.Transparent,
                border = outline,
                borderWidth = 1.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 10.dp,
                trackWidth = 0.dp,
                trackHeight = 0.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            // 40 by 20 with a small thumb: the shortest toggle of the three.
            switch = ToggleStyle(
                size = 20.dp,
                container = Color.Transparent,
                containerChecked = primary,
                mark = onPrimary,
                markUnchecked = theme.color(ColorRole.OnSurfaceVariant),
                border = outline,
                borderWidth = 1.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 12.dp,
                trackWidth = 40.dp,
                trackHeight = 20.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            slider = SliderStyle(
                trackHeight = 4.dp,
                track = theme.color(ColorRole.OutlineVariant),
                activeTrack = primary,
                // The ring: a surface-filled thumb with a thick accent stroke.
                thumbSize = 20.dp,
                thumb = theme.color(ColorRole.Surface),
                thumbBorder = primary,
                thumbBorderWidth = 4.dp,
                tick = null,
            ),
            progress = ProgressStyle(
                thickness = 3.dp,
                track = theme.color(ColorRole.OutlineVariant),
                indicator = primary,
                diameter = 32.dp,
                // Fluent's bars end square, which is part of why they read as crisper.
                rounded = false,
                periodMillis = 800,
            ),
            divider = DividerStyle(
                thickness = 1.dp,
                color = theme.color(ColorRole.OutlineVariant),
                inset = 0.dp,
            ),
        )
    }

    /**
     * A grey rectangle that stays grey, with a bottom line that goes accent.
     *
     * Both, which is the thing that makes a WinUI field a WinUI field: the box marks where
     * you may type and the line underneath marks that you are typing. A system with one
     * field colour for the two states would have to pick one of them.
     */
    override fun field(theme: ResolvedTheme): FieldStyle = FieldStyle(
        container = theme.color(ColorRole.Surface),
        containerFocused = theme.color(ColorRole.Surface),
        border = theme.color(ColorRole.Outline),
        borderFocused = theme.color(ColorRole.Outline),
        borderWidth = 1.dp,
        borderWidthFocused = 1.dp,
        underline = FieldUnderline(
            color = theme.color(ColorRole.Outline),
            focusedColor = theme.color(ColorRole.Primary),
            width = 1.dp,
            focusedWidth = 2.dp,
        ),
        shape = theme.shape(ShapeRole.Medium),
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Sm),
        cursor = theme.color(ColorRole.Primary),
        minHeight = 32.dp,
    )

    /**
     * Fluent icon metrics: a 20 dp grid, a 1.5 dp stroke and square ends, which is what
     * keeps a command bar's icons reading as one set with its text.
     */
    override fun icon(role: IconRole, theme: ResolvedTheme): IconStyle = IconStyle(
        size = 20.dp,
        strokeWidth = 1.5.dp,
        cap = StrokeCap.Square,
        join = StrokeJoin.Bevel,
    )

    /** A calendar flyout, a stepped field for the time, and a combo box for a list. */
    override val pickers: PickerRules = PickerRules(
        date = DatePresentation.CalendarFlyout,
        time = TimePresentation.Stepper,
        choice = ChoicePresentation.ComboBox,
    )

    /**
     * Acrylic, which is Fluent's own translucency and not a borrowed one.
     *
     * Windows has had this since Fluent 1 and it is nothing like Apple's: a lighter tint,
     * a shorter blur, and a single hairline along the top rather than a lit rim all the
     * way round. Answering with a flat fill here would have been the easy reading and the
     * wrong one, and it showed: it put Fluent on exactly the pixels GNOME paints.
     */
    override fun material(role: MaterialRole, theme: ResolvedTheme): SurfaceMaterial {
        val surface = theme.color(ColorRole.Surface)
        val alpha = when (role) {
            MaterialRole.Thin -> 0.60f
            MaterialRole.Regular -> 0.72f
            MaterialRole.Thick -> 0.84f
            MaterialRole.Chrome -> 0.92f
        }
        return SurfaceMaterial.Glass(
            tint = surface,
            tintAlpha = alpha,
            blurRadius = when (role) {
                MaterialRole.Thin -> 12.dp
                MaterialRole.Regular -> 20.dp
                MaterialRole.Thick -> 28.dp
                MaterialRole.Chrome -> 34.dp
            },
            // One hairline along the top edge. Acrylic is a sheet laid on the window, and
            // a sheet has a thickness you see at its edge and nowhere else.
            highlight = if (theme.dark) {
                Color.White.copy(alpha = 0.10f)
            } else {
                Color.White.copy(alpha = 0.55f)
            },
            shade = Color.Black.copy(alpha = if (theme.dark) 0.24f else 0.06f),
            fallback = opaqueMaterial(role, theme),
        )
    }

    override val motion: Motion = Motion(
        // Fluent's "ultra fast" duration: the press reads as instant.
        pressMillis = 50,
        releaseMillis = 100,
        easing = LinearEasing,
        tooltipDelayMillis = 300,
        // The shortest set here. Fluent's guidance is that a transition should be over
        // before it is noticed.
        quickMillis = 100,
        standardMillis = 200,
        slowMillis = 300,
        emphasizedMillis = 250,
    )


    /**
     * One `NavigationView`, in its three display modes: minimal at the bottom, compact as a
     * narrow rail, expanded as a pane of labelled rows.
     *
     * Fluent marks the selection with a short bar along the leading edge of the row rather
     * than a fill, and the pane is ruled off from the content.
     */
    override fun navigation(sizeClass: WindowSizeClass, theme: ResolvedTheme): NavigationStyle =
        NavigationStyle(
            presentation = when (sizeClass) {
                WindowSizeClass.Compact -> NavigationPresentation.Bar
                WindowSizeClass.Medium -> NavigationPresentation.Rail
                WindowSizeClass.Expanded -> NavigationPresentation.Drawer
            },
            container = theme.color(ColorRole.SurfaceContainer),
            content = theme.color(ColorRole.OnSurfaceVariant),
            selectedContent = theme.color(ColorRole.OnSurface),
            indicator = theme.color(ColorRole.Primary),
            indicatorShape = theme.shape(ShapeRole.Full),
            indicatorKind = NavigationIndicator.LeadingEdgeBar,
            separator = theme.color(ColorRole.OutlineVariant),
            barHeight = 56.dp,
            railWidth = 48.dp,
            drawerWidth = 320.dp,
            itemSpacing = theme.space(SpaceRole.Xs),
            itemPadding = theme.space(SpaceRole.Sm),
            // A compact rail is icons only: Fluent puts the label in the flyout instead.
            labelInRail = false,
            typeRole = TypeRole.Body,
        )

    /** A layer, so the sheet carries the stroke every Fluent layer carries. */
    override fun sheet(sizeClass: WindowSizeClass, theme: ResolvedTheme): SheetStyle = SheetStyle(
        edge = if (sizeClass == WindowSizeClass.Compact) SheetEdge.Bottom else SheetEdge.End,
        container = theme.color(ColorRole.SurfaceContainer),
        content = theme.color(ColorRole.OnSurface),
        shape = theme.shape(ShapeRole.Medium),
        elevation = 8.dp,
        scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
        // Fluent's sheets are not dragged about, so there is no grabber to draw.
        handle = null,
        widthFraction = 0.36f,
        heightFraction = 0.5f,
        padding = theme.space(SpaceRole.Lg),
        borderWidth = 1.dp,
        borderColor = theme.color(ColorRole.OutlineVariant),
    )

    /** A teaching tip: a stroked layer in the corner the notifications come from. */
    override fun message(theme: ResolvedTheme): MessageStyle = MessageStyle(
        container = theme.color(ColorRole.SurfaceContainer),
        content = theme.color(ColorRole.OnSurface),
        actionContent = theme.color(ColorRole.Primary),
        shape = theme.shape(ShapeRole.Medium),
        elevation = 8.dp,
        placement = MessagePlacement.TopEnd,
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Sm),
        inset = theme.space(SpaceRole.Md),
        shortMillis = 4_000,
        longMillis = 9_000,
        borderWidth = 1.dp,
        borderColor = theme.color(ColorRole.OutlineVariant),
        typeRole = TypeRole.Body,
    )

    /**
     * Windows 11's caption: three wide rectangles at the trailing edge, with the close
     * zone turning the platform's own red.
     *
     * 46 by 32 is the Windows caption button, and its width is the point: the hover zone
     * is half as wide again as it is tall, which is why a Windows caption reads as three
     * bands rather than three buttons. The red under close is the system value rather
     * than this palette's error colour, because it is the same red on every Windows
     * window whatever an application's accent is.
     */
    override fun caption(theme: ResolvedTheme): CaptionStyle = CaptionStyle(
        side = CaptionSide.End,
        buttonWidth = 46.dp,
        buttonHeight = 32.dp,
        shape = theme.shape(ShapeRole.None),
        minimiseContainer = Color.Transparent,
        maximiseContainer = Color.Transparent,
        closeContainer = Color.Transparent,
        hover = theme.color(ColorRole.SurfaceVariant),
        closeHover = WINDOWS_CLOSE_RED,
        glyph = theme.color(ColorRole.OnSurface),
        closeHoverGlyph = Color.White,
        glyphStroke = 1.dp,
        glyphAtRest = true,
        spacing = 0.dp,
        edgePadding = 0.dp,
        titleAlignment = CaptionTitleAlignment.Start,
    )

    /** The red Windows puts under a close button, on every window and every accent. */
    private val WINDOWS_CLOSE_RED = Color(0xFFC42B1C)

    private const val SCRIM_ALPHA = 0.3f
    private const val DISABLED_ALPHA = 0.38f
    private const val PRESS_SHADE = 0.12f
}

/**
 * GNOME 50 rules, in the Adwaita design language.
 *
 * Reference: the GNOME Human Interface Guidelines ("Ui Styling") and the libadwaita
 * stylesheet, GNOME 50, the same revision the generated token table cites.
 *
 * Adwaita keeps surfaces flat and separates layers with a hairline rather than a tint, it
 * spends its accent on one suggested action per view, and a pressed control changes fill
 * and stops: nothing ripples and nothing rises.
 */
internal object GnomeRules : ComponentRules {
    // The same value Material, Cupertino and Fluent use for a control nobody can press.
    private const val DISABLED_ALPHA = 0.38f

    /**
     * The knob on a switch and on a slider, which is white in both schemes.
     *
     * GTK does not recolour a knob when the switch is thrown or when the session turns
     * dark: what changes is the track behind it. Taking the knob from the reading surface
     * instead loses it entirely in a dark session, where the surface is the same near
     * black the knob would be sitting on.
     */
    private val HANDLE = Color(0xFFFFFFFF)

    override fun controls(theme: ResolvedTheme): ControlsStyle {
        val primary = theme.color(ColorRole.Primary)
        val onPrimary = theme.color(ColorRole.OnPrimary)
        val outline = theme.color(ColorRole.Outline)
        // Adwaita: a softly rounded box, a radio button that fills in when it is chosen,
        // and a wide pill switch carrying a white knob. The switch is the widest of the
        // six, which is what makes GNOME read as GNOME at a glance.
        return ControlsStyle(
            checkbox = ToggleStyle(
                size = 18.dp,
                container = Color.Transparent,
                containerChecked = primary,
                mark = onPrimary,
                markUnchecked = Color.Transparent,
                border = outline,
                borderWidth = 1.dp,
                shape = theme.shape(ShapeRole.ExtraSmall),
                thumbSize = 0.dp,
                trackWidth = 0.dp,
                trackHeight = 0.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            // A chosen radio button fills with the accent and carries a white dot. It does
            // not keep a pale centre with a coloured dot inside it, which is the Material
            // answer and reads as the wrong desktop.
            radioButton = ToggleStyle(
                size = 18.dp,
                container = Color.Transparent,
                containerChecked = primary,
                mark = onPrimary,
                markUnchecked = Color.Transparent,
                border = outline,
                borderWidth = 1.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 8.dp,
                trackWidth = 0.dp,
                trackHeight = 0.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            // 48 by 26 carrying a 22 knob: the GTK switch, and the roomiest of the six.
            switch = ToggleStyle(
                size = 20.dp,
                container = theme.color(ColorRole.SurfaceVariant),
                containerChecked = primary,
                mark = HANDLE,
                markUnchecked = HANDLE,
                border = outline,
                borderWidth = 1.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 22.dp,
                trackWidth = 48.dp,
                trackHeight = 26.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            slider = SliderStyle(
                trackHeight = 4.dp,
                track = theme.color(ColorRole.OutlineVariant),
                activeTrack = primary,
                thumbSize = 18.dp,
                thumb = HANDLE,
                thumbBorder = outline,
                thumbBorderWidth = 1.dp,
                tick = null,
            ),
            progress = ProgressStyle(
                thickness = 4.dp,
                track = theme.color(ColorRole.OutlineVariant),
                indicator = primary,
                diameter = 32.dp,
                rounded = true,
                periodMillis = 1000,
            ),
            // The border grey rather than the fainter one. A boxed list is the place
            // Adwaita rules between rows, and the layer it sits on is a step off white
            // already: the faint rule is within five parts of that layer, so the rows run
            // together with nothing between them.
            divider = DividerStyle(
                thickness = 1.dp,
                color = outline,
                inset = 0.dp,
            ),
        )
    }

    override fun elevation(modifier: Modifier, elevation: Dp, shape: Shape, theme: ResolvedTheme): Modifier {
        if (elevation.value <= 0f) return modifier
        // Half the requested height, because a Material shadow at the same number reads as
        // a floating card rather than a GTK popover. The surface colour is left alone: a
        // raised Adwaita layer is marked by its border, not by a tone shift.
        return modifier
            .shadow(elevation * SHADOW_SCALE, shape, clip = false)
            .border(1.dp, theme.color(ColorRole.OutlineVariant), shape)
    }

    override fun button(variant: ButtonVariant, theme: ResolvedTheme): ButtonStyle {
        val neutral = theme.color(ColorRole.SurfaceVariant)
        val neutralPressed = lerp(neutral, theme.color(ColorRole.OnSurface), PRESS_MIX)
        val onNeutral = theme.color(ColorRole.OnSurface)
        val base = ButtonStyle(
            container = neutral,
            pressedContainer = neutralPressed,
            content = onNeutral,
            pressedContentAlpha = 1f,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
            pressedBorderColor = Color.Transparent,
            topHighlight = null,
            // GTK buttons are 6 dp rounded, which is ShapeRole.Small in the Adwaita table.
            shape = theme.shape(ShapeRole.Small),
            horizontalPadding = theme.space(SpaceRole.Md),
            verticalPadding = theme.space(SpaceRole.Sm),
            minHeight = 34.dp,
            typeRole = TypeRole.BodyStrong,
            restElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledAlpha = DISABLED_ALPHA,
        )
        return when (variant) {
            // The suggested action, and the only place the accent appears.
            ButtonVariant.Filled -> base.copy(
                container = theme.color(ColorRole.Primary),
                pressedContainer = lerp(theme.color(ColorRole.Primary), Color.Black, PRESS_SHADE),
                content = theme.color(ColorRole.OnPrimary),
                shape = theme.shape(ShapeRole.Full),
            )

            // The standard button: grey, not a tinted accent, which is the visible
            // difference from a Material screen where every variant carries the hue.
            ButtonVariant.Tonal -> base

            ButtonVariant.Operator -> base.copy(content = theme.color(ColorRole.Primary))

            ButtonVariant.Outlined -> base.copy(
                container = Color.Transparent,
                borderWidth = 1.dp,
                borderColor = theme.color(ColorRole.Outline),
                pressedBorderColor = theme.color(ColorRole.Outline),
            )

            // A flat button, which takes the neutral fill only while it is held.
            ButtonVariant.Text -> base.copy(
                container = Color.Transparent,
                pressedContainer = neutral,
            )
        }
    }

    /**
     * Adwaita containers: a card is a flat view with a hairline, a header bar is the
     * chrome grey ruled off from the view below it, and a dialog is a rounded sheet.
     */
    override fun container(role: ContainerRole, theme: ResolvedTheme): ContainerStyle {
        val hairline = theme.color(ColorRole.OutlineVariant)
        val base = ContainerStyle(
            container = theme.color(ColorRole.Surface),
            content = theme.color(ColorRole.OnSurface),
            shape = theme.shape(ShapeRole.Large),
            elevation = 0.dp,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
            horizontalPadding = theme.space(SpaceRole.Md),
            verticalPadding = theme.space(SpaceRole.Md),
            separator = null,
            scrim = Color.Transparent,
            typeRole = TypeRole.Body,
        )
        return when (role) {
            // A boxed list: the view white, a 12 dp corner and a single line around it.
            ContainerRole.Card -> base.copy(
                shape = theme.shape(ShapeRole.Medium),
                borderWidth = 1.dp,
                borderColor = hairline,
            )

            // A panel is a layer raised off the page holding the page's own reading ink,
            // which is `SurfaceContainer`. `Surface` is the base reading colour and is too
            // close to the page in some systems to be seen against it.
            ContainerRole.Surface -> base.copy(
                container = theme.color(ColorRole.SurfaceContainer),
                shape = theme.shape(ShapeRole.None),
            )

            // A header bar: the chrome grey, flat, with a rule under it.
            ContainerRole.TopAppBar -> base.copy(
                container = theme.color(ColorRole.SurfaceVariant),
                shape = theme.shape(ShapeRole.None),
                verticalPadding = theme.space(SpaceRole.Sm),
                separator = hairline,
                typeRole = TypeRole.BodyStrong,
            )

            ContainerRole.Dialog -> base.copy(
                elevation = 4.dp,
                borderWidth = 1.dp,
                borderColor = hairline,
                horizontalPadding = theme.space(SpaceRole.Xl),
                verticalPadding = theme.space(SpaceRole.Lg),
                scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
            )

            // A popover: rounded, bordered, and only shallowly raised.
            ContainerRole.Menu -> base.copy(
                shape = theme.shape(ShapeRole.Medium),
                elevation = 3.dp,
                borderWidth = 1.dp,
                borderColor = hairline,
                horizontalPadding = 0.dp,
                verticalPadding = theme.space(SpaceRole.Xs),
            )

            // A GTK tooltip is a dark chip with light text in both schemes.
            ContainerRole.Tooltip -> base.copy(
                container = theme.color(ColorRole.OnSurface),
                content = theme.color(ColorRole.Surface),
                shape = theme.shape(ShapeRole.Small),
                horizontalPadding = theme.space(SpaceRole.Sm),
                verticalPadding = theme.space(SpaceRole.Xs),
                typeRole = TypeRole.Caption,
            )
        }
    }

    /**
     * A view switcher: the selected view is a filled pill in the header bar, which is
     * what GNOME uses where Material would underline and Fluent would draw a pivot bar.
     */
    override fun tabs(theme: ResolvedTheme): TabsStyle = TabsStyle(
        container = theme.color(ColorRole.SurfaceVariant),
        shape = theme.shape(ShapeRole.None),
        selectedContent = theme.color(ColorRole.OnSurface),
        unselectedContent = theme.color(ColorRole.OnSurfaceVariant),
        selectedContainer = theme.color(ColorRole.Surface),
        selectedShape = theme.shape(ShapeRole.Full),
        indicator = Color.Transparent,
        indicatorHeight = 0.dp,
        indicatorShape = theme.shape(ShapeRole.None),
        indicatorFillsTab = true,
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Xs),
        typeRole = TypeRole.BodyStrong,
    )

    /**
     * A GTK entry: the chrome grey filled in, rounded at six, with the accent taking over
     * the line when the caret goes in.
     *
     * No rule underneath. Adwaita marks the focused entry all the way round, which is the
     * clearest difference from the Material field beside it.
     */
    override fun field(theme: ResolvedTheme): FieldStyle = FieldStyle(
        container = theme.color(ColorRole.SurfaceVariant),
        containerFocused = theme.color(ColorRole.SurfaceVariant),
        border = theme.color(ColorRole.Outline),
        borderFocused = theme.color(ColorRole.Primary),
        borderWidth = 1.dp,
        borderWidthFocused = 2.dp,
        underline = null,
        shape = theme.shape(ShapeRole.Small),
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Sm),
        cursor = theme.color(ColorRole.Primary),
        minHeight = 34.dp,
    )

    /**
     * Adwaita icon metrics: a 16 dp symbolic grid drawn with a heavier stem and rounded
     * ends, which is what makes a GNOME icon read as solid rather than outlined.
     */
    override fun icon(role: IconRole, theme: ResolvedTheme): IconStyle = IconStyle(
        size = 16.dp,
        strokeWidth = 2.dp,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )

    /** A calendar grid, a stepped time entry, and a popover list for a choice. */
    override val pickers: PickerRules = PickerRules(
        date = DatePresentation.CalendarGrid,
        time = TimePresentation.Stepper,
        choice = ChoicePresentation.ComboBox,
    )

    override val motion: Motion = Motion(
        // GTK state changes are a short symmetric ease, and nothing ripples.
        pressMillis = 200,
        releaseMillis = 200,
        easing = FastOutSlowInEasing,
        tooltipDelayMillis = 500,
        // Adwaita's transitions are even and unhurried, and its emphasis is a longer
        // version of the same curve rather than a different one.
        quickMillis = 180,
        standardMillis = 350,
        slowMillis = 500,
        emphasizedMillis = 420,
    )

    /**
     * A view switcher across the bottom of a narrow window, a sidebar down the side of a
     * wide one.
     *
     * The mark is a tint of the accent behind the selected view, rounded at the corner GTK
     * rounds a button. It is not a capsule and it is not opaque: a solid pill behind a
     * sidebar row is the Material answer, and Adwaita does not draw one anywhere.
     *
     * The strip is the header bar grey with a rule between it and the view, which is how
     * GNOME separates chrome from content everywhere else.
     */
    override fun navigation(sizeClass: WindowSizeClass, theme: ResolvedTheme): NavigationStyle =
        NavigationStyle(
            presentation = when (sizeClass) {
                WindowSizeClass.Compact -> NavigationPresentation.Bar
                WindowSizeClass.Medium -> NavigationPresentation.Rail
                WindowSizeClass.Expanded -> NavigationPresentation.Drawer
            },
            container = theme.color(ColorRole.SurfaceVariant),
            content = theme.color(ColorRole.OnSurfaceVariant),
            selectedContent = theme.color(ColorRole.Primary),
            indicator = theme.color(ColorRole.Primary).copy(alpha = SELECTED_TINT),
            indicatorShape = theme.shape(ShapeRole.Medium),
            indicatorKind = NavigationIndicator.Pill,
            indicatorExtent = NavigationExtent.Destination,
            separator = theme.color(ColorRole.OutlineVariant),
            barHeight = 60.dp,
            railWidth = 68.dp,
            drawerWidth = 240.dp,
            itemSpacing = theme.space(SpaceRole.Xs),
            itemPadding = theme.space(SpaceRole.Sm),
            labelInRail = true,
            typeRole = TypeRole.Caption,
        )

    /**
     * A bottom sheet with a grab handle, rounded at the corner Adwaita rounds a window.
     *
     * Barely raised and lined instead: everywhere else here a layer is separated from what
     * it covers by a hairline rather than by a shadow, and a sheet is no exception.
     */
    override fun sheet(sizeClass: WindowSizeClass, theme: ResolvedTheme): SheetStyle = SheetStyle(
        edge = if (sizeClass == WindowSizeClass.Compact) SheetEdge.Bottom else SheetEdge.End,
        container = theme.color(ColorRole.Surface),
        content = theme.color(ColorRole.OnSurface),
        shape = theme.shape(ShapeRole.Large),
        elevation = 2.dp,
        scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
        handle = theme.color(ColorRole.Outline),
        widthFraction = 0.34f,
        heightFraction = 0.52f,
        padding = theme.space(SpaceRole.Lg),
        borderWidth = 1.dp,
        borderColor = theme.color(ColorRole.OutlineVariant),
    )

    /**
     * A toast: a dark capsule centred along the bottom edge.
     *
     * Dark in a light session and dark again in a dark one, because GTK draws a toast in
     * the overlay colours it uses for anything laid over content, and those do not follow
     * the scheme. Fully rounded, which is the shape nothing else in Adwaita has, and five
     * seconds, which is what a toast is given when nobody names a time.
     */
    override fun message(theme: ResolvedTheme): MessageStyle = MessageStyle(
        container = OVERLAY,
        content = ON_OVERLAY,
        actionContent = ON_OVERLAY,
        shape = theme.shape(ShapeRole.Full),
        elevation = 3.dp,
        placement = MessagePlacement.BottomCenter,
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Sm),
        inset = theme.space(SpaceRole.Md),
        shortMillis = 5_000,
        longMillis = 10_000,
        borderWidth = 0.dp,
        borderColor = Color.Transparent,
        typeRole = TypeRole.Body,
    )

    /**
     * Adwaita's caption: three grey circles at the trailing edge, and the title centred.
     *
     * A GNOME window button is a filled disc rather than a bare glyph or a hover zone, and
     * the fill is there at rest rather than appearing under the pointer. That, and the
     * centred title, are what the GNOME 50 screen in the reference shows on every window
     * in it, and they are the two things that make a header bar read as GNOME's before
     * anything inside it is read at all.
     */
    override fun caption(theme: ResolvedTheme): CaptionStyle = CaptionStyle(
        side = CaptionSide.End,
        buttonWidth = 26.dp,
        buttonHeight = 26.dp,
        shape = theme.shape(ShapeRole.Full),
        // A tint rather than a stored grey. The header bar's own colour is the grey a
        // stored one would have been, so the discs came out the colour of the bar and
        // the set read as three bare glyphs, which is the GTK button this is not.
        // libadwaita defines its own button fill the same way, and for the same reason.
        minimiseContainer = captionFill(theme.dark, BUTTON_TINT),
        maximiseContainer = captionFill(theme.dark, BUTTON_TINT),
        closeContainer = captionFill(theme.dark, BUTTON_TINT),
        hover = captionFill(theme.dark, BUTTON_TINT_HOVER),
        closeHover = theme.color(ColorRole.Error),
        glyph = theme.color(ColorRole.OnSurface),
        closeHoverGlyph = Color.White,
        glyphStroke = 1.5.dp,
        glyphAtRest = true,
        spacing = theme.space(SpaceRole.Sm),
        edgePadding = theme.space(SpaceRole.Sm),
        titleAlignment = CaptionTitleAlignment.Center,
    )

    /** A fill that darkens a light bar and lightens a dark one, whatever colour it is. */
    private fun captionFill(dark: Boolean, alpha: Float): Color =
        if (dark) Color.White.copy(alpha = alpha) else Color.Black.copy(alpha = alpha)

    /** How far a window button's fill moves the header bar under it. */
    private const val BUTTON_TINT = 0.10f
    private const val BUTTON_TINT_HOVER = 0.20f

    private const val SCRIM_ALPHA = 0.45f
    private const val PRESS_MIX = 0.1f
    private const val PRESS_SHADE = 0.16f
    private const val SHADOW_SCALE = 0.5f

    /** How much of the accent stands behind the selected destination. */
    private const val SELECTED_TINT = 0.25f

    /** The overlay grey GTK lays over content, and the white it writes on it. */
    private val OVERLAY = Color(0xFF383838)
    private val ON_OVERLAY = Color(0xFFFFFFFF)
}

/**
 * KDE Breeze rules, the Plasma design language.
 *
 * Reference: the Breeze colour schemes shipped with Plasma, the KDE Human Interface
 * Guidelines and Kirigami's unit scale, Plasma 6, the revision the token table cites.
 *
 * Breeze draws with thin, precise strokes: every frame is a hairline, a raised layer is
 * marked by a line before it is marked by a shadow, and an outlined control recolours its
 * line on press rather than filling in.
 */
internal object BreezeRules : ComponentRules {
    // The same value Material, Cupertino and Fluent use for a control nobody can press.
    private const val DISABLED_ALPHA = 0.38f

    /**
     * The knob on a switch and on a slider.
     *
     * Plasma's view white, held here rather than read from the table because the knob does
     * not follow the scheme: a Breeze switch carries a light knob in a dark session too,
     * and the ink the table puts on the highlight is for text rather than for a knob.
     */
    private val HANDLE = Color(0xFFFCFCFC)

    override fun controls(theme: ResolvedTheme): ControlsStyle {
        val primary = theme.color(ColorRole.Primary)
        val onPrimary = theme.color(ColorRole.OnPrimary)
        val outline = theme.color(ColorRole.Outline)
        // Breeze draws a barely rounded box, a switch a little over two grid units wide,
        // and indicators with square ends, which is the KDE house style against GNOME next
        // door.
        return ControlsStyle(
            // Plasma 6 takes the hard corner off a checkbox without rounding it: two
            // pixels, which still reads as the square one beside Adwaita's four and
            // Deepin's six.
            checkbox = ToggleStyle(
                size = 18.dp,
                container = Color.Transparent,
                containerChecked = primary,
                mark = onPrimary,
                markUnchecked = Color.Transparent,
                border = outline,
                borderWidth = 1.dp,
                shape = theme.shape(ShapeRole.ExtraSmall),
                thumbSize = 0.dp,
                trackWidth = 0.dp,
                trackHeight = 0.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            radioButton = ToggleStyle(
                size = 18.dp,
                container = Color.Transparent,
                containerChecked = Color.Transparent,
                mark = primary,
                markUnchecked = Color.Transparent,
                border = outline,
                borderWidth = 1.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 8.dp,
                trackWidth = 0.dp,
                trackHeight = 0.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            // Kirigami measures in grid units, and a Plasma switch is a little over two of
            // them wide by a little over one tall. That leaves it shorter than the GTK
            // switch beside it and longer than the Windows toggle, which is where it
            // really sits; the earlier 36 dp track was written from a description and made
            // Breeze the most compact of the six, a place WinUI holds.
            switch = ToggleStyle(
                size = 22.dp,
                container = theme.color(ColorRole.SurfaceVariant),
                containerChecked = primary,
                mark = HANDLE,
                markUnchecked = HANDLE,
                border = outline,
                borderWidth = 1.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 18.dp,
                trackWidth = 42.dp,
                trackHeight = 22.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            slider = SliderStyle(
                trackHeight = 3.dp,
                track = theme.color(ColorRole.OutlineVariant),
                activeTrack = primary,
                thumbSize = 16.dp,
                thumb = HANDLE,
                thumbBorder = outline,
                thumbBorderWidth = 1.dp,
                tick = null,
            ),
            progress = ProgressStyle(
                thickness = 3.dp,
                track = theme.color(ColorRole.OutlineVariant),
                indicator = primary,
                diameter = 32.dp,
                rounded = false,
                periodMillis = 900,
            ),
            divider = DividerStyle(
                thickness = 1.dp,
                color = theme.color(ColorRole.OutlineVariant),
                inset = 0.dp,
            ),
        )
    }

    override fun elevation(modifier: Modifier, elevation: Dp, shape: Shape, theme: ResolvedTheme): Modifier {
        if (elevation.value <= 0f) return modifier
        return modifier
            .shadow(elevation * SHADOW_SCALE, shape, clip = false)
            .border(1.dp, theme.color(ColorRole.Outline), shape)
    }

    override fun button(variant: ButtonVariant, theme: ResolvedTheme): ButtonStyle {
        val accent = theme.color(ColorRole.Primary)
        val line = theme.color(ColorRole.Outline)
        val standard = theme.color(ColorRole.Surface)
        val onStandard = theme.color(ColorRole.OnSurface)
        val base = ButtonStyle(
            container = standard,
            pressedContainer = lerp(standard, onStandard, PRESS_MIX),
            content = onStandard,
            pressedContentAlpha = 1f,
            borderWidth = 1.dp,
            borderColor = line,
            pressedBorderColor = accent,
            topHighlight = null,
            // Breeze rounds a control at 4 dp, which is ShapeRole.Medium in its table.
            shape = theme.shape(ShapeRole.Medium),
            horizontalPadding = theme.space(SpaceRole.Lg),
            verticalPadding = theme.space(SpaceRole.Sm),
            minHeight = 30.dp,
            typeRole = TypeRole.Body,
            restElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledAlpha = DISABLED_ALPHA,
        )
        return when (variant) {
            // Even the accent button is a filled rectangle inside a darker line.
            ButtonVariant.Filled -> base.copy(
                container = accent,
                pressedContainer = lerp(accent, Color.Black, PRESS_SHADE),
                content = theme.color(ColorRole.OnPrimary),
                borderColor = lerp(accent, Color.Black, BORDER_SHADE),
                pressedBorderColor = lerp(accent, Color.Black, BORDER_SHADE),
            )

            // The standard button: a light fill inside a hairline.
            ButtonVariant.Tonal -> base

            ButtonVariant.Operator -> base.copy(content = accent)

            // Hovering or pressing an outlined Breeze button recolours its line rather
            // than filling it in.
            ButtonVariant.Outlined -> base.copy(
                container = Color.Transparent,
                pressedContainer = Color.Transparent,
            )

            ButtonVariant.Text -> base.copy(
                container = Color.Transparent,
                pressedContainer = Color.Transparent,
                borderWidth = 0.dp,
                borderColor = Color.Transparent,
                pressedBorderColor = Color.Transparent,
            )
        }
    }

    /**
     * Breeze containers: everything is framed. A card is a view inside a single pixel
     * line, a toolbar is ruled off from the content, and a menu is a framed list.
     */
    override fun container(role: ContainerRole, theme: ResolvedTheme): ContainerStyle {
        val line = theme.color(ColorRole.Outline)
        val base = ContainerStyle(
            container = theme.color(ColorRole.Surface),
            content = theme.color(ColorRole.OnSurface),
            shape = theme.shape(ShapeRole.Medium),
            elevation = 0.dp,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
            horizontalPadding = theme.space(SpaceRole.Md),
            verticalPadding = theme.space(SpaceRole.Md),
            separator = null,
            scrim = Color.Transparent,
            typeRole = TypeRole.Body,
        )
        return when (role) {
            ContainerRole.Card -> base.copy(
                shape = theme.shape(ShapeRole.Large),
                borderWidth = 1.dp,
                borderColor = line,
            )

            // A panel is a layer raised off the page holding the page's own reading ink,
            // which is `SurfaceContainer`. `Surface` is the base reading colour and is too
            // close to the page in some systems to be seen against it.
            ContainerRole.Surface -> base.copy(
                container = theme.color(ColorRole.SurfaceContainer),
                shape = theme.shape(ShapeRole.None),
            )

            // A Plasma toolbar: the window grey, tight, ruled off below.
            ContainerRole.TopAppBar -> base.copy(
                container = theme.color(ColorRole.Background),
                shape = theme.shape(ShapeRole.None),
                verticalPadding = theme.space(SpaceRole.Sm),
                separator = line,
                typeRole = TypeRole.Subtitle,
            )

            ContainerRole.Dialog -> base.copy(
                shape = theme.shape(ShapeRole.Large),
                elevation = 6.dp,
                borderWidth = 1.dp,
                borderColor = line,
                horizontalPadding = theme.space(SpaceRole.Xl),
                verticalPadding = theme.space(SpaceRole.Lg),
                scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
            )

            ContainerRole.Menu -> base.copy(
                shape = theme.shape(ShapeRole.Small),
                elevation = 4.dp,
                borderWidth = 1.dp,
                borderColor = line,
                horizontalPadding = 0.dp,
                verticalPadding = theme.space(SpaceRole.Xs),
            )

            ContainerRole.Tooltip -> base.copy(
                shape = theme.shape(ShapeRole.ExtraSmall),
                borderWidth = 1.dp,
                borderColor = line,
                horizontalPadding = theme.space(SpaceRole.Sm),
                verticalPadding = theme.space(SpaceRole.Xs),
                typeRole = TypeRole.Caption,
            )
        }
    }

    /**
     * A Plasma tab bar: the selected tab is a framed page tab sitting on the toolbar,
     * with a thin highlight line along its top edge.
     */
    override fun tabs(theme: ResolvedTheme): TabsStyle = TabsStyle(
        container = theme.color(ColorRole.Background),
        shape = theme.shape(ShapeRole.None),
        selectedContent = theme.color(ColorRole.OnSurface),
        unselectedContent = theme.color(ColorRole.OnSurfaceVariant),
        selectedContainer = theme.color(ColorRole.Surface),
        selectedShape = theme.shape(ShapeRole.ExtraSmall),
        indicator = theme.color(ColorRole.Primary),
        indicatorHeight = 2.dp,
        indicatorShape = theme.shape(ShapeRole.None),
        indicatorFillsTab = true,
        horizontalPadding = theme.space(SpaceRole.Lg),
        verticalPadding = theme.space(SpaceRole.Xs),
        typeRole = TypeRole.Body,
    )

    /**
     * A framed view: the white of a list inside a hairline that is recoloured rather than
     * thickened when the caret goes in.
     *
     * The same thing a Breeze outlined button does when it is pressed, and the same reason:
     * a line that grows moves everything beside it by a pixel, and Plasma does not.
     * Shortest of the six after the HIG field, because Kirigami packs tightly.
     */
    override fun field(theme: ResolvedTheme): FieldStyle = FieldStyle(
        container = theme.color(ColorRole.Surface),
        containerFocused = theme.color(ColorRole.Surface),
        border = theme.color(ColorRole.Outline),
        borderFocused = theme.color(ColorRole.Primary),
        borderWidth = 1.dp,
        borderWidthFocused = 1.dp,
        underline = null,
        shape = theme.shape(ShapeRole.Medium),
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Sm),
        cursor = theme.color(ColorRole.Primary),
        minHeight = 28.dp,
    )

    /**
     * Breeze icon metrics: a 22 dp grid drawn with a thin, even stroke and mitred joins,
     * which is what gives the set its drafted look next to Adwaita's solid symbolics.
     */
    override fun icon(role: IconRole, theme: ResolvedTheme): IconStyle = IconStyle(
        size = 22.dp,
        strokeWidth = 1.25.dp,
        cap = StrokeCap.Butt,
        join = StrokeJoin.Miter,
    )

    /** A calendar grid, a stepped time field, and a drop down list for a choice. */
    override val pickers: PickerRules = PickerRules(
        date = DatePresentation.CalendarGrid,
        time = TimePresentation.Stepper,
        choice = ChoicePresentation.ExposedMenu,
    )

    override val motion: Motion = Motion(
        // Plasma's transitions are quick and nearly linear: a Breeze control is meant to
        // feel mechanical, so the release is shorter than the press.
        pressMillis = 100,
        releaseMillis = 80,
        easing = LinearEasing,
        tooltipDelayMillis = 700,
        // Mechanical: the quickest standard change of the seven.
        quickMillis = 80,
        standardMillis = 150,
        slowMillis = 250,
        emphasizedMillis = 200,
    )

    /**
     * A Kirigami page stack: a dense strip of destinations, and the selected one filled
     * solid with the highlight.
     *
     * That solid fill is the one place Plasma spends its accent on a large area, and it is
     * what a selected row in a KDE sidebar, a file manager list and a settings tree all
     * look like. The strip is the window grey rather than the view white, and it is ruled
     * off, because Breeze marks every edge.
     *
     * Everything here is tighter than the other five: Kirigami's grid unit is smaller than
     * GNOME's six pixel step, and a Plasma sidebar fits more rows in the same height.
     */
    override fun navigation(sizeClass: WindowSizeClass, theme: ResolvedTheme): NavigationStyle =
        NavigationStyle(
            presentation = when (sizeClass) {
                WindowSizeClass.Compact -> NavigationPresentation.Bar
                WindowSizeClass.Medium -> NavigationPresentation.Rail
                WindowSizeClass.Expanded -> NavigationPresentation.Drawer
            },
            container = theme.color(ColorRole.Background),
            content = theme.color(ColorRole.OnSurfaceVariant),
            selectedContent = theme.color(ColorRole.OnPrimary),
            indicator = theme.color(ColorRole.Primary),
            indicatorShape = theme.shape(ShapeRole.ExtraSmall),
            indicatorKind = NavigationIndicator.Pill,
            indicatorExtent = NavigationExtent.Destination,
            separator = theme.color(ColorRole.Outline),
            barHeight = 48.dp,
            railWidth = 56.dp,
            drawerWidth = 220.dp,
            itemSpacing = theme.space(SpaceRole.Xs),
            itemPadding = theme.space(SpaceRole.Sm),
            labelInRail = true,
            typeRole = TypeRole.Caption,
        )

    /**
     * An overlay drawer: a framed panel, barely rounded, that covers less of the window
     * than the others do.
     *
     * No grab handle inside it. Plasma puts the thing you pull on outside the drawer, on
     * the edge it comes from, so a bar drawn along the sheet's own edge would be a second
     * handle for a gesture this one does not offer.
     */
    override fun sheet(sizeClass: WindowSizeClass, theme: ResolvedTheme): SheetStyle = SheetStyle(
        edge = if (sizeClass == WindowSizeClass.Compact) SheetEdge.Bottom else SheetEdge.End,
        container = theme.color(ColorRole.Surface),
        content = theme.color(ColorRole.OnSurface),
        shape = theme.shape(ShapeRole.Large),
        elevation = 4.dp,
        scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
        handle = null,
        widthFraction = 0.32f,
        heightFraction = 0.45f,
        padding = theme.space(SpaceRole.Lg),
        borderWidth = 1.dp,
        borderColor = theme.color(ColorRole.Outline),
    )

    /**
     * A framed chip along the bottom edge, centred.
     *
     * Breeze marks a layer with a line before it marks it with anything else, and a
     * message is a layer: this is the same frame a menu, a tooltip and a card get, rather
     * than the inverted surface Material uses or the dark overlay GNOME lays over content.
     * The two timings are Kirigami's own short and long.
     */
    override fun message(theme: ResolvedTheme): MessageStyle = MessageStyle(
        container = theme.color(ColorRole.SurfaceContainer),
        content = theme.color(ColorRole.OnSurface),
        actionContent = theme.color(ColorRole.Primary),
        shape = theme.shape(ShapeRole.Small),
        elevation = 4.dp,
        placement = MessagePlacement.BottomCenter,
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Sm),
        inset = theme.space(SpaceRole.Md),
        shortMillis = 4_000,
        longMillis = 7_000,
        borderWidth = 1.dp,
        borderColor = theme.color(ColorRole.Outline),
        typeRole = TypeRole.Body,
    )

    /**
     * Breeze's caption: small square buttons at the trailing edge, barely rounded, with a
     * light hover and the negative colour under close.
     *
     * The smallest set of the seven, which is the house style: Plasma spends less room on
     * chrome than GNOME next door, and the Dolphin window in the reference screen puts
     * three small glyphs where GNOME puts three circles. The title is centred, which both
     * Breeze windows in the reference do.
     */
    override fun caption(theme: ResolvedTheme): CaptionStyle = CaptionStyle(
        side = CaptionSide.End,
        buttonWidth = 24.dp,
        buttonHeight = 24.dp,
        shape = theme.shape(ShapeRole.ExtraSmall),
        minimiseContainer = Color.Transparent,
        maximiseContainer = Color.Transparent,
        closeContainer = Color.Transparent,
        hover = theme.color(ColorRole.SurfaceVariant),
        closeHover = theme.color(ColorRole.Error),
        glyph = theme.color(ColorRole.OnSurfaceVariant),
        closeHoverGlyph = Color.White,
        glyphStroke = 1.dp,
        glyphAtRest = true,
        spacing = theme.space(SpaceRole.Xs),
        edgePadding = theme.space(SpaceRole.Sm),
        titleAlignment = CaptionTitleAlignment.Center,
    )

    private const val SCRIM_ALPHA = 0.5f
    private const val PRESS_MIX = 0.12f
    private const val PRESS_SHADE = 0.1f
    private const val BORDER_SHADE = 0.22f
    private const val SHADOW_SCALE = 0.75f
}

/**
 * Deepin rules, and the look a Linux session that is neither GNOME nor KDE gets.
 *
 * Reference: the Deepin Design specification and the DTK control defaults, deepin 23, the
 * revision the token table cites. Only token values and style rules are taken: Deepin's
 * icon set and its bundled typeface carry their own licences.
 *
 * Depth here is a wide, soft, warm shadow plus a small lightening of the surface itself,
 * never a border: a hairline would fight the large radii that make the language what it
 * is.
 */
internal object DeepinRules : ComponentRules {
    // The same value Material, Cupertino and Fluent use for a control nobody can press.
    private const val DISABLED_ALPHA = 0.38f

    /** The knob on a switch: white, and white again in a dark session. */
    private val HANDLE = Color(0xFFFFFFFF)

    override fun controls(theme: ResolvedTheme): ControlsStyle {
        val primary = theme.color(ColorRole.Primary)
        val onPrimary = theme.color(ColorRole.OnPrimary)
        val outline = theme.color(ColorRole.Outline)
        // Deepin rounds everything and leans on fill rather than outline, so its controls
        // read as softer and heavier than the other two. A checkbox is still a box: a
        // 20 dp square rounded by 6, which is soft next to Adwaita's 4 and Breeze's 2
        // without turning into the radio button underneath it.
        return ControlsStyle(
            checkbox = ToggleStyle(
                size = 20.dp,
                container = Color.Transparent,
                containerChecked = primary,
                mark = onPrimary,
                markUnchecked = Color.Transparent,
                border = outline,
                borderWidth = 1.dp,
                shape = theme.shape(ShapeRole.ExtraSmall),
                thumbSize = 0.dp,
                trackWidth = 0.dp,
                trackHeight = 0.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            radioButton = ToggleStyle(
                size = 20.dp,
                container = Color.Transparent,
                containerChecked = Color.Transparent,
                mark = primary,
                markUnchecked = Color.Transparent,
                border = outline,
                borderWidth = 1.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 9.dp,
                trackWidth = 0.dp,
                trackHeight = 0.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            // The one control here with no line around it at all. A zero width border is
            // a hairline rather than nothing, so the colour has to go as well.
            switch = ToggleStyle(
                size = 24.dp,
                container = theme.color(ColorRole.SurfaceVariant),
                containerChecked = primary,
                mark = HANDLE,
                markUnchecked = HANDLE,
                border = Color.Transparent,
                borderWidth = 0.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 20.dp,
                trackWidth = 44.dp,
                trackHeight = 24.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            // The handle is filled with the accent rather than with the page. Deepin draws
            // it that way, and it is also the only thing that keeps it on screen: a white
            // handle with no ring on Deepin's near white page is drawn at the right size
            // in the right place and cannot be seen.
            slider = SliderStyle(
                trackHeight = 6.dp,
                track = theme.color(ColorRole.OutlineVariant),
                activeTrack = primary,
                thumbSize = 20.dp,
                thumb = primary,
                thumbBorder = Color.Transparent,
                thumbBorderWidth = 0.dp,
                tick = null,
            ),
            progress = ProgressStyle(
                thickness = 6.dp,
                track = theme.color(ColorRole.OutlineVariant),
                indicator = primary,
                diameter = 32.dp,
                rounded = true,
                periodMillis = 1100,
            ),
            // The stronger outline rather than the fainter one, for the same reason GNOME
            // takes the stronger of its two: Deepin's panel in a dark session is a step
            // lighter than its reading surface, and the faint line is within eight parts
            // of it, so a list of rows comes out as one unbroken block.
            divider = DividerStyle(
                thickness = 1.dp,
                color = outline,
                inset = 0.dp,
            ),
        )
    }

    override fun elevation(modifier: Modifier, elevation: Dp, shape: Shape, theme: ResolvedTheme): Modifier {
        if (elevation.value <= 0f) return modifier
        // Spread further than the dp asked for, and lightened, so a card at 8 dp sits
        // visibly above one at 2 dp even where the shadow itself is hard to see.
        val lifted = lerp(
            theme.color(ColorRole.Surface),
            if (theme.dark) LIFT_DARK else Color.White,
            (elevation.value / LIFT_FULL_DP).coerceIn(0f, 1f) * MAX_LIFT,
        )
        return modifier
            .shadow(
                elevation = elevation * SPREAD,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = AMBIENT_ALPHA),
                spotColor = Color.Black.copy(alpha = SPOT_ALPHA),
            )
            .background(lifted, shape)
    }

    override fun button(variant: ButtonVariant, theme: ResolvedTheme): ButtonStyle {
        val accent = theme.color(ColorRole.Primary)
        val tonal = theme.color(ColorRole.SurfaceVariant)
        val onTonal = theme.color(ColorRole.OnSurface)
        val base = ButtonStyle(
            container = tonal,
            pressedContainer = lerp(tonal, theme.color(ColorRole.Outline), PRESS_MIX),
            content = onTonal,
            pressedContentAlpha = 1f,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
            pressedBorderColor = Color.Transparent,
            topHighlight = null,
            // A lozenge: 10 dp, which is ShapeRole.Medium in the Deepin table.
            shape = theme.shape(ShapeRole.Medium),
            horizontalPadding = theme.space(SpaceRole.Lg),
            verticalPadding = theme.space(SpaceRole.Sm),
            minHeight = 36.dp,
            typeRole = TypeRole.Body,
            restElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledAlpha = DISABLED_ALPHA,
        )
        return when (variant) {
            // A rounded slab of brand blue with no border at all.
            ButtonVariant.Filled -> base.copy(
                container = accent,
                pressedContainer = lerp(accent, Color.Black, PRESS_SHADE),
                content = theme.color(ColorRole.OnPrimary),
            )

            // A plain grey fill, a step off whatever it sits on.
            ButtonVariant.Tonal -> base

            ButtonVariant.Operator -> base.copy(content = accent)

            ButtonVariant.Outlined -> base.copy(
                container = Color.Transparent,
                pressedContainer = tonal,
                borderWidth = 1.dp,
                borderColor = theme.color(ColorRole.Outline),
                pressedBorderColor = accent,
            )

            // A text button keeps the accent for its label, which is the one place the
            // brand colour appears without a fill behind it.
            ButtonVariant.Text -> base.copy(
                container = Color.Transparent,
                pressedContainer = tonal,
                content = accent,
            )
        }
    }

    /**
     * Deepin containers: large radii throughout, depth by shadow, and a title bar that is
     * part of the rounded window rather than a bar ruled off from it.
     */
    override fun container(role: ContainerRole, theme: ResolvedTheme): ContainerStyle {
        val base = ContainerStyle(
            container = theme.color(ColorRole.Surface),
            content = theme.color(ColorRole.OnSurface),
            shape = theme.shape(ShapeRole.Medium),
            elevation = 0.dp,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
            horizontalPadding = theme.space(SpaceRole.Md),
            verticalPadding = theme.space(SpaceRole.Md),
            separator = null,
            scrim = Color.Transparent,
            typeRole = TypeRole.Body,
        )
        return when (role) {
            ContainerRole.Card -> base.copy(
                shape = theme.shape(ShapeRole.Large),
                elevation = 3.dp,
            )

            // A panel is a layer raised off the page holding the page's own reading ink,
            // which is `SurfaceContainer`. `Surface` is the base reading colour and is too
            // close to the page in some systems to be seen against it.
            ContainerRole.Surface -> base.copy(
                container = theme.color(ColorRole.SurfaceContainer),
                shape = theme.shape(ShapeRole.None),
            )

            // Part of the window, not a bar over it: no rule underneath, and the title
            // sits on the same warm surface as the content.
            ContainerRole.TopAppBar -> base.copy(
                shape = theme.shape(ShapeRole.None),
                verticalPadding = theme.space(SpaceRole.Sm),
                typeRole = TypeRole.Subtitle,
            )

            ContainerRole.Dialog -> base.copy(
                shape = theme.shape(ShapeRole.Large),
                elevation = 12.dp,
                horizontalPadding = theme.space(SpaceRole.Xl),
                verticalPadding = theme.space(SpaceRole.Xl),
                scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
            )

            ContainerRole.Menu -> base.copy(
                shape = theme.shape(ShapeRole.Small),
                elevation = 8.dp,
                horizontalPadding = theme.space(SpaceRole.Xs),
                verticalPadding = theme.space(SpaceRole.Xs),
            )

            ContainerRole.Tooltip -> base.copy(
                container = theme.color(ColorRole.SurfaceVariant),
                content = theme.color(ColorRole.OnSurfaceVariant),
                shape = theme.shape(ShapeRole.ExtraSmall),
                elevation = 6.dp,
                horizontalPadding = theme.space(SpaceRole.Sm),
                verticalPadding = theme.space(SpaceRole.Xs),
                typeRole = TypeRole.Caption,
            )
        }
    }

    /**
     * A pill switcher: the selected view is a fully rounded slab of the accent, which is
     * the mark Deepin uses where Material underlines and Breeze frames a page tab.
     */
    override fun tabs(theme: ResolvedTheme): TabsStyle = TabsStyle(
        container = theme.color(ColorRole.SurfaceVariant),
        shape = theme.shape(ShapeRole.Full),
        selectedContent = theme.color(ColorRole.OnPrimary),
        unselectedContent = theme.color(ColorRole.OnSurfaceVariant),
        selectedContainer = theme.color(ColorRole.Primary),
        selectedShape = theme.shape(ShapeRole.Full),
        indicator = Color.Transparent,
        indicatorHeight = 0.dp,
        indicatorShape = theme.shape(ShapeRole.None),
        indicatorFillsTab = true,
        horizontalPadding = theme.space(SpaceRole.Lg),
        verticalPadding = theme.space(SpaceRole.Xs),
        typeRole = TypeRole.Body,
    )

    /**
     * A warm filled lozenge with no line on it at all until the caret goes in.
     *
     * Depth and fill rather than outline, the same way everything else here works: at rest
     * the field is a tinted shape on the page, and focusing it lightens the fill to the
     * reading surface and draws the accent round it. The largest corner and the most inner
     * room of the six.
     */
    override fun field(theme: ResolvedTheme): FieldStyle = FieldStyle(
        container = theme.color(ColorRole.SurfaceVariant),
        containerFocused = theme.color(ColorRole.Surface),
        border = Color.Transparent,
        borderFocused = theme.color(ColorRole.Primary),
        borderWidth = 0.dp,
        borderWidthFocused = 2.dp,
        underline = null,
        shape = theme.shape(ShapeRole.Medium),
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Sm),
        cursor = theme.color(ColorRole.Primary),
        minHeight = 36.dp,
    )

    /**
     * A large, soft icon grid: 24 dp drawn with a heavy rounded stroke and bevelled
     * joins, to match the rounded shapes rather than fight them.
     */
    override fun icon(role: IconRole, theme: ResolvedTheme): IconStyle = IconStyle(
        size = 24.dp,
        strokeWidth = 2.25.dp,
        cap = StrokeCap.Round,
        join = StrokeJoin.Bevel,
    )

    /** A calendar flyout, a dial for the time, and a drop down list for a choice. */
    override val pickers: PickerRules = PickerRules(
        date = DatePresentation.CalendarFlyout,
        time = TimePresentation.Dial,
        choice = ChoicePresentation.ExposedMenu,
    )

    override val motion: Motion = Motion(
        // The slowest and softest here, to match the rounded shapes.
        pressMillis = 250,
        releaseMillis = 200,
        easing = LinearOutSlowInEasing,
        tooltipDelayMillis = 600,
        // The longest set here, to match the rounded shapes and the soft elevation.
        quickMillis = 200,
        standardMillis = 400,
        slowMillis = 600,
        emphasizedMillis = 450,
    )

    /**
     * A sidebar of large rounded rows, with the selected one filled with the brand blue.
     *
     * Deepin's mark is the only fully rounded one of the six: the same lozenge its buttons
     * and its view switcher are, carried behind a destination. The strip is the panel
     * white and it is not ruled off from the screen, which is the same decision the title
     * bar makes: a Deepin window is one rounded surface rather than bars stacked on
     * content.
     *
     * The rows are the tallest and the sidebar is the narrowest, because the labels sit
     * beside icons drawn on a 24 dp grid and the whole language is roomy vertically.
     */
    override fun navigation(sizeClass: WindowSizeClass, theme: ResolvedTheme): NavigationStyle =
        NavigationStyle(
            presentation = when (sizeClass) {
                WindowSizeClass.Compact -> NavigationPresentation.Bar
                WindowSizeClass.Medium -> NavigationPresentation.Rail
                WindowSizeClass.Expanded -> NavigationPresentation.Drawer
            },
            container = theme.color(ColorRole.SurfaceContainer),
            content = theme.color(ColorRole.OnSurfaceVariant),
            selectedContent = theme.color(ColorRole.OnPrimary),
            indicator = theme.color(ColorRole.Primary),
            indicatorShape = theme.shape(ShapeRole.Full),
            indicatorKind = NavigationIndicator.Pill,
            indicatorExtent = NavigationExtent.Destination,
            separator = null,
            barHeight = 68.dp,
            railWidth = 72.dp,
            drawerWidth = 200.dp,
            itemSpacing = theme.space(SpaceRole.Xs),
            itemPadding = theme.space(SpaceRole.Sm),
            labelInRail = true,
            typeRole = TypeRole.Body,
        )

    /**
     * A sheet with the largest corner and the deepest shadow here, and no line anywhere.
     *
     * Depth is how this language separates layers, so the sheet is lifted rather than
     * framed, and it covers more of the window than the others because its padding is
     * already the roomiest of the six.
     */
    override fun sheet(sizeClass: WindowSizeClass, theme: ResolvedTheme): SheetStyle = SheetStyle(
        edge = if (sizeClass == WindowSizeClass.Compact) SheetEdge.Bottom else SheetEdge.End,
        container = theme.color(ColorRole.SurfaceContainer),
        content = theme.color(ColorRole.OnSurface),
        shape = theme.shape(ShapeRole.Large),
        elevation = 12.dp,
        scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
        handle = theme.color(ColorRole.Outline),
        widthFraction = 0.44f,
        heightFraction = 0.58f,
        padding = theme.space(SpaceRole.Xl),
        borderWidth = 0.dp,
        borderColor = Color.Transparent,
    )

    /**
     * A floating message: a lozenge that drops in at the top of the window, centred.
     *
     * The only one of the six that comes from the top middle. Deepin puts a desktop
     * notification in the corner and an application's own "done that" at the top of its
     * window, over the title bar, so this is the second of those and not a corner toast.
     */
    override fun message(theme: ResolvedTheme): MessageStyle = MessageStyle(
        container = theme.color(ColorRole.SurfaceContainer),
        content = theme.color(ColorRole.OnSurface),
        actionContent = theme.color(ColorRole.Primary),
        shape = theme.shape(ShapeRole.Full),
        elevation = 6.dp,
        placement = MessagePlacement.TopCenter,
        horizontalPadding = theme.space(SpaceRole.Lg),
        verticalPadding = theme.space(SpaceRole.Sm),
        inset = theme.space(SpaceRole.Md),
        shortMillis = 4_000,
        longMillis = 8_000,
        borderWidth = 0.dp,
        borderColor = Color.Transparent,
        typeRole = TypeRole.Body,
    )

    /**
     * Deepin's caption: bare glyphs at the trailing edge in a square hover zone, with the
     * close zone turning red.
     *
     * The rounding on the hover zone is what makes it Deepin's rather than Windows': the
     * whole language rounds, and a square hover in a window cut at eighteen looks
     * borrowed. Both reference screens put the glyphs on the same line as the rest of the
     * bar's content, which the caption layout already does.
     */
    override fun caption(theme: ResolvedTheme): CaptionStyle = CaptionStyle(
        side = CaptionSide.End,
        buttonWidth = 40.dp,
        buttonHeight = 40.dp,
        shape = theme.shape(ShapeRole.Small),
        minimiseContainer = Color.Transparent,
        maximiseContainer = Color.Transparent,
        closeContainer = Color.Transparent,
        hover = theme.color(ColorRole.SurfaceVariant),
        closeHover = theme.color(ColorRole.Error),
        glyph = theme.color(ColorRole.OnSurfaceVariant),
        closeHoverGlyph = Color.White,
        glyphStroke = 1.5.dp,
        glyphAtRest = true,
        spacing = 0.dp,
        edgePadding = theme.space(SpaceRole.Xs),
        titleAlignment = CaptionTitleAlignment.Center,
    )

    private const val SCRIM_ALPHA = 0.35f
    private const val PRESS_MIX = 0.35f
    private const val PRESS_SHADE = 0.2f
    private const val AMBIENT_ALPHA = 0.1f
    private const val SPOT_ALPHA = 0.18f
    private const val SPREAD = 1.6f
    private const val LIFT_FULL_DP = 24f
    private const val MAX_LIFT = 0.08f
    private val LIFT_DARK = Color(0xFFFFFFFF)
}

/**
 * Liquid Glass rules: the language macOS 26 and iOS 26 draw.
 *
 * Reference: the screens in `docs/references/design-systems/liquidglass/`, read against
 * Apple's "Liquid Glass" announcement and the Human Interface Guidelines materials and
 * buttons chapters, 2026 revision, which is the revision the generated token table cites.
 *
 * This sits beside the flat Apple language rather than replacing it. Four things separate
 * it, and all four are drawn here rather than described:
 *
 *  - Surfaces are a translucent tint over whatever the application drew behind them.
 *  - Their edges are lit along the top and shaded along the bottom, so they have depth.
 *  - Corners are continuous rather than circular, and an inner corner is cut concentric
 *    with the container it sits in.
 *  - Depth comes from layers overlapping instead of from a stack of shadows.
 *
 * What is not drawn, and is not claimed: the material does not sample the desktop behind
 * the window. That needs a platform compositing view outside the Compose surface.
 *
 * Where the glass goes depends on the window. The desktop screens put it on chrome, the
 * sidebar and the toolbar and the title bar area, over document content that stays
 * opaque; the phone screens carry it onto floating control surfaces too. A window that is
 * glass from edge to edge is wrong at both sizes, so the size class decides, and [isGlass]
 * is where that decision lives.
 */
internal object LiquidGlassRules : ComponentRules {

    /**
     * Which container roles are glass in a window of this class.
     *
     * Compact is a phone: the floating control surfaces are glass as well as the chrome.
     * Medium and Expanded are a desktop window, where a card or a plain surface is the
     * document being read and has to stay opaque behind its text.
     */
    private fun isGlass(role: ContainerRole, sizeClass: WindowSizeClass): Boolean = when (role) {
        // Chrome, at every size.
        ContainerRole.TopAppBar, ContainerRole.Menu, ContainerRole.Dialog, ContainerRole.Tooltip -> true
        // Content, glass only where the screen is small enough that there is no separate
        // chrome to speak of.
        ContainerRole.Card, ContainerRole.Surface -> sizeClass == WindowSizeClass.Compact
    }

    /**
     * The material for one container role: glass where this window puts glass, and a flat
     * fill everywhere else.
     *
     * [container] is what the surface expects to sit over, which is what decides how the
     * translucent tint will actually read once composited. [content] is the colour that
     * will be drawn on top, and is what the opaque fallback has to stay legible against.
     */
    private fun material(
        role: ContainerRole,
        container: Color,
        content: Color,
        theme: ResolvedTheme,
    ): SurfaceMaterial = if (isGlass(role, theme.sizeClass)) {
        LiquidGlass.material(
            dark = theme.dark,
            // Regular throughout. Every container role here carries text or controls,
            // and that is exactly what Regular is for: the clear recipe is meant for a
            // surface floating over media where the content underneath is the point, and
            // none of these are that. Using it for bars and cards made them invisible.
            prominence = GlassProminence.Regular,
            backdrop = container,
            content = content,
        )
    } else {
        SurfaceMaterial.Opaque(container)
    }

    /**
     * The page, which Apple specifies twice.
     *
     * In dark mode the grouped page is pure black, and on a phone that is right: the
     * screen is almost all content and the black is what the panels sit on. A desktop
     * window has never been black. Painting one black leaves a 0x1c1c1e panel two levels
     * away from the page it sits on, so the panels stop reading as panels and the window
     * reads as a video player rather than as a document. The desktop page is the darkest
     * of the system greys instead, which is what the panels are meant to be a well in.
     *
     * Light needs no such choice: the page is already the reading surface at either size.
     */
    override fun color(role: ColorRole, dark: Boolean, sizeClass: WindowSizeClass): Color? = when {
        role != ColorRole.Background || !dark -> null
        sizeClass == WindowSizeClass.Compact -> null
        else -> DESKTOP_PAGE_DARK
    }

    override fun elevation(modifier: Modifier, elevation: Dp, shape: Shape, theme: ResolvedTheme): Modifier {
        if (elevation.value <= 0f) return modifier
        // Glass gets its depth from layers tinting each other rather than from a stack of
        // shadows, so what remains is one wide, faint shadow whose job is to lift the
        // layer off the page and nothing more.
        return modifier.shadow(
            elevation = elevation * SPREAD,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = AMBIENT_ALPHA),
            spotColor = Color.Black.copy(alpha = SPOT_ALPHA),
        )
    }

    /**
     * Every button is a capsule, at every size.
     *
     * That is the single loudest difference from the flat language beside it, where a
     * button is a rounded rectangle that only becomes a capsule when it happens to be
     * short. Every button in the reference screens is a capsule: the alert pair, the
     * playback buttons, the formatting chips, the composer's send key.
     */
    override fun button(variant: ButtonVariant, theme: ResolvedTheme): ButtonStyle {
        val base = ButtonStyle(
            container = Color.Transparent,
            pressedContainer = Color.Transparent,
            content = theme.color(ColorRole.Primary),
            // A press dims the whole control rather than layering a colour on it.
            pressedContentAlpha = PRESSED_ALPHA,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
            pressedBorderColor = Color.Transparent,
            topHighlight = null,
            shape = theme.shape(ShapeRole.Full),
            horizontalPadding = theme.space(SpaceRole.Md),
            verticalPadding = theme.space(SpaceRole.Sm),
            // Taller than the flat button, because a capsule that is not tall enough for
            // its own end caps reads as a lozenge rather than as a pill.
            minHeight = 40.dp,
            typeRole = TypeRole.BodyStrong,
            // These buttons never cast a shadow, pressed or not.
            restElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledAlpha = DISABLED_ALPHA,
        )
        return when (variant) {
            ButtonVariant.Filled, ButtonVariant.Operator -> base.copy(
                container = theme.color(ColorRole.Primary),
                pressedContainer = theme.color(ColorRole.Primary).copy(alpha = PRESSED_ALPHA),
                content = theme.color(ColorRole.OnPrimary),
            )

            // A glass button: a translucent fill rather than a solid grey, so it is
            // always a step away from whatever it sits on. A stored grey cannot be: the
            // secondary fill and the tint of a bar are neighbours, so a tinted button on
            // a toolbar came out the colour of the toolbar and vanished. Apple's own fill
            // colours are defined this way too, dark in light mode and light in dark,
            // which is why the direction flips with the scheme.
            ButtonVariant.Tonal -> base.copy(
                container = tintedFill(theme.dark, TONAL_ALPHA),
                pressedContainer = tintedFill(theme.dark, TONAL_PRESSED_ALPHA),
                content = theme.color(ColorRole.Primary),
            )

            ButtonVariant.Outlined -> base.copy(
                pressedContainer = tintedFill(theme.dark, TONAL_ALPHA),
                borderWidth = 1.dp,
                borderColor = theme.color(ColorRole.Outline),
                pressedBorderColor = theme.color(ColorRole.Outline),
            )

            // A plain button: content colour only, no container even when pressed.
            ButtonVariant.Text -> base
        }
    }

    /**
     * The containers: chrome is glass, content is a surface with a continuous corner, and
     * nothing is divided by a hairline that a layer edge already divides.
     */
    override fun container(role: ContainerRole, theme: ResolvedTheme): ContainerStyle {
        val base = ContainerStyle(
            container = theme.color(ColorRole.Surface),
            content = theme.color(ColorRole.OnSurface),
            shape = theme.shape(ShapeRole.Medium),
            elevation = 0.dp,
            borderWidth = 0.dp,
            borderColor = Color.Transparent,
            horizontalPadding = theme.space(SpaceRole.Md),
            verticalPadding = theme.space(SpaceRole.Md),
            separator = null,
            scrim = Color.Transparent,
            typeRole = TypeRole.Body,
        )
        val styled = when (role) {
            // A floating panel, with the deepest corner in the ladder.
            ContainerRole.Card -> base.copy(
                container = theme.color(ColorRole.SurfaceContainer),
                shape = theme.shape(ShapeRole.Large),
            )

            // A `Surface` is a panel: a layer raised off the page, holding the page's own
            // reading ink. That is `SurfaceContainer`, not `Surface`. Here the page is
            // white in light mode, so a panel painted with `Surface` would be drawn full
            // size, in the right colour, and could not be seen at all.
            ContainerRole.Surface -> base.copy(container = theme.color(ColorRole.SurfaceContainer))

            // A toolbar. Glass at every size, because this is the piece the desktop
            // screens make glass over opaque content. No hairline under it: the lit edge
            // of the glass is the division, and a rule under a lit edge is one line too
            // many.
            //
            // And not a strip. The toolbars in the reference screens paint nothing across
            // the window: the title sits on the page and the actions float in capsules of
            // glass, one capsule to a group, at every size.
            ContainerRole.TopAppBar -> base.copy(
                shape = theme.shape(ShapeRole.Full),
                verticalPadding = theme.space(SpaceRole.Sm),
                typeRole = TypeRole.BodyStrong,
                floats = true,
            )

            // An alert: centred, capsule buttons inside, over a dimmed screen.
            ContainerRole.Dialog -> base.copy(
                shape = theme.shape(ShapeRole.Large),
                horizontalPadding = theme.space(SpaceRole.Lg),
                verticalPadding = theme.space(SpaceRole.Lg),
                scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
            )

            ContainerRole.Menu -> base.copy(
                shape = theme.shape(ShapeRole.Medium),
                elevation = 2.dp,
                horizontalPadding = 0.dp,
                verticalPadding = theme.space(SpaceRole.Xs),
            )

            // A help tag: a light chip, not an inverted one.
            ContainerRole.Tooltip -> base.copy(
                shape = theme.shape(ShapeRole.Small),
                horizontalPadding = theme.space(SpaceRole.Sm),
                verticalPadding = theme.space(SpaceRole.Xs),
                typeRole = TypeRole.Caption,
            )
        }
        return styled.copy(material = material(role, styled.container, styled.content, theme))
    }

    /**
     * A segmented control: a capsule track with the selected segment floating inside it.
     *
     * The selected segment's corner is cut concentric with the track's, so the gap
     * between the two outlines is the same all the way round instead of pinching at the
     * corners. This is the smallest place the concentric rule shows, and the easiest to
     * see once you know to look. Both are capsules in the reference screens, which is
     * what the concentric rule reduces to when the outer radius is larger than the
     * height.
     */
    override fun tabs(theme: ResolvedTheme): TabsStyle {
        val inset = theme.space(SpaceRole.Xs)
        return TabsStyle(
            container = theme.color(ColorRole.SurfaceVariant),
            shape = theme.shape(ShapeRole.Full),
            selectedContent = theme.color(ColorRole.OnSurface),
            unselectedContent = theme.color(ColorRole.OnSurfaceVariant),
            selectedContainer = theme.color(ColorRole.Surface),
            selectedShape = theme.shape(ShapeRole.Full),
            indicator = Color.Transparent,
            indicatorHeight = 0.dp,
            indicatorShape = theme.shape(ShapeRole.None),
            indicatorFillsTab = true,
            horizontalPadding = theme.space(SpaceRole.Md),
            verticalPadding = inset,
            typeRole = TypeRole.Label,
        )
    }

    /**
     * The controls: a circular checkmark, a capsule switch that is green rather than
     * accent coloured, and a slider whose thumb sits over the track rather than in it.
     *
     * The green is not a substitution for the accent. Every switch in the reference
     * screens is green whatever else on that screen is tinted, because on this platform a
     * switch means on rather than means selected, and colouring it with the accent would
     * make a screen full of switches look like a screen full of selections.
     */
    override fun controls(theme: ResolvedTheme): ControlsStyle {
        val primary = theme.color(ColorRole.Primary)
        val surface = theme.color(ColorRole.Surface)
        return ControlsStyle(
            // A circle, not a box, which is the clearest difference from Material here.
            checkbox = ToggleStyle(
                size = 24.dp,
                container = Color.Transparent,
                containerChecked = primary,
                mark = theme.color(ColorRole.OnPrimary),
                markUnchecked = Color.Transparent,
                border = theme.color(ColorRole.Outline),
                borderWidth = 1.5.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 0.dp,
                trackWidth = 0.dp,
                trackHeight = 0.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            radioButton = ToggleStyle(
                size = 24.dp,
                container = Color.Transparent,
                containerChecked = primary,
                mark = theme.color(ColorRole.OnPrimary),
                markUnchecked = Color.Transparent,
                border = theme.color(ColorRole.Outline),
                borderWidth = 1.5.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 9.dp,
                trackWidth = 0.dp,
                trackHeight = 0.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            // 54 by 32, a shade larger than the flat switch beside it, with a thumb that
            // all but fills the track. The reference switches read as one solid capsule
            // with a disc pushed to one end rather than as a dot in a groove, and that
            // only happens when the thumb is within a few dp of the track's height.
            switch = ToggleStyle(
                size = 32.dp,
                container = theme.color(ColorRole.SurfaceVariant),
                containerChecked = SWITCH_ON,
                mark = Color.White,
                markUnchecked = Color.White,
                border = Color.Transparent,
                borderWidth = 0.dp,
                shape = theme.shape(ShapeRole.Full),
                thumbSize = 28.dp,
                trackWidth = 54.dp,
                trackHeight = 32.dp,
                disabledAlpha = DISABLED_ALPHA,
            ),
            slider = SliderStyle(
                trackHeight = 5.dp,
                track = theme.color(ColorRole.OutlineVariant),
                activeTrack = primary,
                // A large pale thumb that sits over the track rather than in it.
                thumbSize = 28.dp,
                thumb = surface,
                thumbBorder = theme.color(ColorRole.OutlineVariant),
                thumbBorderWidth = 0.5.dp,
                tick = null,
            ),
            progress = ProgressStyle(
                thickness = 4.dp,
                track = theme.color(ColorRole.OutlineVariant),
                indicator = primary,
                diameter = 22.dp,
                rounded = true,
                periodMillis = 1_000,
            ),
            // A hairline held back from the leading edge, the way a grouped list rules
            // between its rows.
            divider = DividerStyle(
                thickness = 0.5.dp,
                color = theme.color(ColorRole.OutlineVariant),
                inset = 16.dp,
            ),
        )
    }

    /**
     * A capsule with no box around it.
     *
     * Every field in the reference screens is a filled capsule: the sidebar search, the
     * composer at the foot of the window, the search bar over a list. The frame is the
     * fill, so the resting state has no line at all and focus is marked by the accent
     * being laid around it.
     */
    override fun field(theme: ResolvedTheme): FieldStyle = FieldStyle(
        container = theme.color(ColorRole.SurfaceVariant),
        containerFocused = theme.color(ColorRole.SurfaceVariant),
        border = Color.Transparent,
        borderFocused = theme.color(ColorRole.Primary),
        borderWidth = 0.dp,
        borderWidthFocused = 2.dp,
        underline = null,
        shape = theme.shape(ShapeRole.Full),
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Sm),
        cursor = theme.color(ColorRole.Primary),
        minHeight = 40.dp,
        // On glass the field is the glass. The composer in the reference is one capsule
        // with the text in it, not a capsule holding a second, greyer one.
        containerOnGlass = Color.Transparent,
    )

    /**
     * SF Symbols metrics on a 22 dp grid, with rounded ends and joins, and a heavier
     * stroke than the flat language uses.
     *
     * The weight is the difference that matters. A glyph sitting on a translucent surface
     * competes with whatever shows through it, and every toolbar glyph in the reference
     * screens is drawn at a semibold weight for that reason. The rounded terminal is what
     * reads most as Apple's icon set, and it is shared with the flat language because it
     * is the same icon set.
     */
    override fun icon(role: IconRole, theme: ResolvedTheme): IconStyle = IconStyle(
        size = 22.dp,
        strokeWidth = 2.dp,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )

    /**
     * Wheels throughout: a date, a time and a list are all spun to the value.
     *
     * The rows are taller than the flat language's, which is the same decision as the
     * roomier spacing ladder: a value on a wheel is a control, and the controls here have
     * more room around them.
     */
    override val pickers: PickerRules = PickerRules(
        date = DatePresentation.Wheel,
        time = TimePresentation.Wheel,
        choice = ChoicePresentation.Wheel,
        wheelRowHeight = 36.dp,
    )

    /**
     * Glass at every thickness, which is what this system is for. Thickness is how much
     * of the backdrop survives, and the chrome role is the one that has to carry controls
     * over anything at all.
     */
    override fun material(role: MaterialRole, theme: ResolvedTheme): SurfaceMaterial {
        // Chrome over a window the platform has backed is the window's own material with
        // a rim on it. Drawn with the ordinary recipe it would cover most of the desktop
        // the platform was asked to show, which is exactly how every window drawn in this
        // system came out grey.
        if (role == MaterialRole.Chrome && theme.windowBackdrop) {
            return LiquidGlass.windowMaterial(
                dark = theme.dark,
                backdrop = theme.color(ColorRole.Background),
                content = theme.color(ColorRole.OnSurface),
            )
        }
        val glass = LiquidGlass.material(
            dark = theme.dark,
            prominence = if (role == MaterialRole.Thin) {
                GlassProminence.Clear
            } else {
                GlassProminence.Regular
            },
            backdrop = theme.color(ColorRole.Surface),
            content = theme.color(ColorRole.OnSurface),
        )
        return glass.copy(
            blurRadius = when (role) {
                MaterialRole.Thin -> 18.dp
                MaterialRole.Regular -> 30.dp
                MaterialRole.Thick -> 44.dp
                MaterialRole.Chrome -> 52.dp
            },
        )
    }

    override val motion: Motion = Motion(
        pressMillis = 80,
        releaseMillis = 220,
        easing = LinearOutSlowInEasing,
        // A help tag waits until the pointer has clearly stopped.
        tooltipDelayMillis = 1000,
        // Glass moves a little longer than Cupertino does, because the material itself
        // is what is being seen to move and it is given time to be read.
        quickMillis = 140,
        standardMillis = 280,
        slowMillis = 460,
        emphasizedMillis = 430,
    )

    /**
     * A floating tab bar on a phone, a floating rail on a tablet, a floating sidebar on a
     * desktop.
     *
     * Floating is the whole of it. None of the three is a strip attached to the window's
     * edge: the bar is a capsule held off the bottom and the sides, and the rail and the
     * sidebar are panels held off the leading edge, the top and the bottom, with a corner
     * concentric with the window's. The sidebar runs to the top of the window and the
     * window buttons sit on it, which is what macOS 26 does and why there is no bar
     * across the top of such a window.
     *
     * The bar marks its selection with a filled capsule behind the destination rather
     * than with colour alone, and it is separated from the content by its own lit edge
     * rather than by a hairline.
     *
     * Where the platform has put its own material behind the window, the rail and the
     * sidebar sit straight on it, drawn mostly as rim so the desktop shows through them,
     * and the page is painted only beside them. Everywhere else they are clear glass over
     * the page, so they take its colour.
     */
    override fun navigation(sizeClass: WindowSizeClass, theme: ResolvedTheme): NavigationStyle {
        val presentation = when (sizeClass) {
            // Two platforms draw this language and they answer a narrow window
            // differently. A phone has its own tab bar and the destinations are handed to
            // it (FR-14.9), so the presentation there stays the bar the shell is offered.
            // A narrow window on a desktop has no tab bar to hand them to, and that
            // platform's answer for a list with no room is to take it off the screen and
            // leave the button that brings it back.
            WindowSizeClass.Compact -> if (theme.platform == HostPlatform.Ios ||
                theme.platform == HostPlatform.Android
            ) {
                NavigationPresentation.Bar
            } else {
                NavigationPresentation.PutAway
            }
            // No rail. A column of icons with the words taken off is Material's answer to
            // a medium window and it is not this language's: Notes, Mail and Finder keep a
            // sidebar with its labels at every width a window can be dragged to, and the
            // one thing they do when there is truly no room is put the whole thing away.
            // None of them shows icons without their words.
            WindowSizeClass.Medium -> NavigationPresentation.Drawer
            WindowSizeClass.Expanded -> NavigationPresentation.Drawer
        }
        val bar = presentation == NavigationPresentation.Bar
        val onWindow = theme.windowBackdrop && !bar
        val content = theme.color(ColorRole.OnSurface)
        val strip = when {
            onWindow -> LiquidGlass.windowMaterial(
                dark = theme.dark,
                backdrop = theme.color(ColorRole.Background),
                content = content,
            )
            bar -> LiquidGlass.material(
                dark = theme.dark,
                prominence = GlassProminence.Regular,
                backdrop = theme.color(ColorRole.Background),
                content = content,
            )
            else -> LiquidGlass.material(
                dark = theme.dark,
                prominence = GlassProminence.Clear,
                backdrop = theme.color(ColorRole.PrimaryContainer),
                content = content,
            )
        }
        val inset = if (bar) theme.space(SpaceRole.Md) else SIDEBAR_INSET
        // Over the window's own material the page gives some of its opacity up, so the
        // desktop reaches the page as well as the sidebar, only less of it.
        val start = theme.color(ColorRole.Background)
        val end = theme.color(ColorRole.PrimaryContainer)
        return NavigationStyle(
            presentation = presentation,
            container = theme.color(ColorRole.SurfaceContainer).copy(alpha = NAVIGATION_ALPHA),
            content = theme.color(ColorRole.OnSurfaceVariant),
            // A tab bar tints the tab you are on and draws no fill; a strip down the side
            // fills the row and leaves its words alone. Blue words with no fill under them
            // is what a link looks like, and a sidebar of links reads as a list of places
            // to go rather than as the one you are in.
            selectedContent = if (bar) {
                theme.color(ColorRole.Primary)
            } else {
                theme.color(ColorRole.OnSurfaceVariant)
            },
            indicator = if (bar) Color.Transparent else tintedFill(theme.dark, TONAL_ALPHA),
            indicatorShape = theme.shape(ShapeRole.Full),
            indicatorKind = NavigationIndicator.Pill,
            indicatorExtent = NavigationExtent.Destination,
            separator = null,
            barHeight = 56.dp,
            railWidth = 76.dp,
            drawerWidth = 260.dp,
            itemSpacing = if (presentation == NavigationPresentation.Drawer) {
                theme.space(SpaceRole.Sm)
            } else {
                theme.space(SpaceRole.Xs)
            },
            itemPadding = if (presentation == NavigationPresentation.Drawer) {
                DRAWER_ROW_PADDING
            } else {
                theme.space(SpaceRole.Xs)
            },
            labelInRail = true,
            // A sidebar row is a line of text beside its icon, set at the size of the rest
            // of the window's text. The small label is for a bar and a rail, where it sits
            // under the icon in a column a finger wide.
            typeRole = if (presentation == NavigationPresentation.Drawer) {
                TypeRole.Body
            } else {
                TypeRole.Caption
            },
            pageGradientStart = if (theme.windowBackdrop) start.copy(alpha = PAGE_OVER_WINDOW_TOP) else start,
            pageGradientEnd = if (theme.windowBackdrop) end.copy(alpha = PAGE_OVER_WINDOW_FOOT) else end,
            searchContainer = tintedFill(theme.dark, TONAL_ALPHA),
            stripMaterial = strip,
            stripShape = if (bar) {
                theme.shape(ShapeRole.Full)
            } else {
                ContinuousCornerShape(concentricRadius(WINDOW_CORNER, inset))
            },
            floatingInset = inset,
            carriesCaption = !bar,
            pageBehindStrip = !onWindow,
            pageGradientHold = PAGE_GRADIENT_HOLD,
            destinationGap = if (presentation == NavigationPresentation.Drawer) 2.dp else null,
        )
    }

    /**
     * A sheet pulled up over a dimmed screen, with the deepest corner in the ladder.
     *
     * It takes less of the window than the flat language's sheet does. The formatting
     * sheet in the reference sits low over a note that stays visible above it, which is
     * the point of a glass sheet: what it covers still shows through and still reads, so
     * covering more of the screen would be covering it for no reason.
     */
    override fun sheet(sizeClass: WindowSizeClass, theme: ResolvedTheme): SheetStyle = SheetStyle(
        edge = if (sizeClass == WindowSizeClass.Compact) SheetEdge.Bottom else SheetEdge.End,
        container = theme.color(ColorRole.SurfaceContainer),
        content = theme.color(ColorRole.OnSurface),
        shape = theme.shape(ShapeRole.Large),
        // Sheets here are not raised by a shadow, they cover.
        elevation = 0.dp,
        scrim = Color.Black.copy(alpha = SCRIM_ALPHA),
        handle = theme.color(ColorRole.Outline),
        widthFraction = 0.34f,
        heightFraction = 0.45f,
        padding = theme.space(SpaceRole.Lg),
        borderWidth = 0.dp,
        borderColor = Color.Transparent,
    )

    /**
     * A capsule that drops in at the top of the window, centred.
     *
     * Not a snackbar and not a bordered banner: the cards that arrive at the top of these
     * screens are capsules with a lit edge and no line, and they are centred over the
     * window rather than tucked into a corner.
     */
    override fun message(theme: ResolvedTheme): MessageStyle = MessageStyle(
        container = theme.color(ColorRole.SurfaceContainer),
        content = theme.color(ColorRole.OnSurface),
        actionContent = theme.color(ColorRole.Primary),
        shape = theme.shape(ShapeRole.Full),
        elevation = 4.dp,
        placement = MessagePlacement.TopCenter,
        horizontalPadding = theme.space(SpaceRole.Lg),
        verticalPadding = theme.space(SpaceRole.Sm),
        inset = theme.space(SpaceRole.Md),
        shortMillis = 3_000,
        longMillis = 8_000,
        borderWidth = 0.dp,
        borderColor = Color.Transparent,
        typeRole = TypeRole.Body,
    )

    /**
     * A fill that lightens or darkens whatever it lands on, rather than replacing it.
     *
     * Light mode fills are translucent black and dark mode fills are translucent white,
     * which is how Apple defines them and the reason a control keeps its separation over
     * a page, over a panel and over a bar without any of the three being named here.
     */
    private fun tintedFill(dark: Boolean, alpha: Float): Color =
        if (dark) Color.White.copy(alpha = alpha) else Color.Black.copy(alpha = alpha)

    /**
     * systemGray6 in dark: the darkest of Apple's greys that is not black, and the tone a
     * desktop window is. Far enough from the 0x1c1c1e of a panel that the panel has an
     * edge.
     */
    private val DESKTOP_PAGE_DARK = Color(0xFF2C2C2E)

    /** systemGreen. A switch means on, so it is green and not the accent. */
    private val SWITCH_ON = Color(0xFF34C759)

    /** How far a tinted button moves what is under it, resting and pressed. */
    private const val TONAL_ALPHA = 0.08f
    private const val TONAL_PRESSED_ALPHA = 0.16f
    private const val NAVIGATION_ALPHA = 0.72f

    /**
     * The corner of a macOS 26 window, which a sidebar held inside it is cut concentric
     * with.
     */
    private val WINDOW_CORNER = 26.dp

    /**
     * How far a rail or a sidebar stands off the window's leading edge, top and bottom.
     * Measured off the reference, where the sidebar's edge is eight points in from the
     * window's all the way round.
     */
    private val SIDEBAR_INSET = 8.dp

    /** The room around a sidebar row's icon and label, which is what sets its height. */
    private val DRAWER_ROW_PADDING = 8.dp

    /**
     * How opaque the page is at its top and at its foot over a window that shows the
     * desktop. Enough that text on it reads as text on a page, little enough that the
     * wallpaper's colour carries across the whole window and not only the sidebar.
     */
    private const val PAGE_OVER_WINDOW_TOP = 0.62f
    private const val PAGE_OVER_WINDOW_FOOT = 0.82f

    /**
     * The page stays its own colour down a little under half its height before it turns,
     * which is where the reference's wash begins.
     */
    private const val PAGE_GRADIENT_HOLD = 0.45f

    /**
     * The glass caption. Three coloured discs at the leading edge, exactly as the flat
     * Apple system draws them, because they are the same three buttons: the glass window
     * in the reference screens carries the same traffic lights, sitting straight on the
     * translucent chrome with no strip of their own.
     */
    override fun caption(theme: ResolvedTheme): CaptionStyle = CaptionStyle(
        side = CaptionSide.Start,
        buttonWidth = 12.dp,
        buttonHeight = 12.dp,
        shape = theme.shape(ShapeRole.Full),
        minimiseContainer = TRAFFIC_AMBER,
        maximiseContainer = TRAFFIC_GREEN,
        closeContainer = TRAFFIC_RED,
        hover = Color.Transparent,
        closeHover = Color.Transparent,
        glyph = Color.Black.copy(alpha = 0.55f),
        closeHoverGlyph = Color.Black.copy(alpha = 0.55f),
        glyphStroke = 1.dp,
        glyphAtRest = false,
        spacing = 8.dp,
        // Further in than the flat language's, because the glass chrome the discs sit on
        // is inset from the window edge rather than flush with it.
        edgePadding = 22.dp,
        titleAlignment = CaptionTitleAlignment.Center,
    )

    private const val SCRIM_ALPHA = 0.4f
    private const val DISABLED_ALPHA = 0.38f
    private const val PRESSED_ALPHA = 0.6f
    private const val AMBIENT_ALPHA = 0.08f
    private const val SPOT_ALPHA = 0.12f
    private const val SPREAD = 2f
}

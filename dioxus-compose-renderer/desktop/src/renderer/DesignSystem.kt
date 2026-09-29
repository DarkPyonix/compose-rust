package dioxus.compose.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush as ComposeBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.DesignTokenTable
import dioxus.compose.protocol.DesignTokens
import androidx.compose.animation.core.FiniteAnimationSpec
import dioxus.compose.protocol.MaterialRole
import dioxus.compose.protocol.MotionRole
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.ShapeRole
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.TypeRole
import dioxus.compose.protocol.TypeToken
import dioxus.compose.protocol.WindowSizeClass
import java.lang.System
import dioxus.compose.protocol.TitleBar

/**
 * The platforms `Theme::adaptive` distinguishes.
 *
 * `LinuxGnome`, `LinuxKde` and `LinuxOther` exist because each maps to a design system of
 * its own (GNOME 50, KDE Breeze, Deepin); until those are implemented they resolve to the
 * Host's mandatory fallback.
 */
enum class HostPlatform { Android, MacOs, Ios, Windows, LinuxGnome, LinuxKde, LinuxOther, Web, Unknown }

/**
 * Reads the platform once, at startup: the OS first, then `XDG_CURRENT_DESKTOP` and, if
 * that is empty, `DESKTOP_SESSION` for the Linux desktop environment.
 */
internal fun detectHostPlatform(
    osName: String = System.getProperty("os.name").orEmpty(),
    androidRuntime: String? = System.getProperty("java.vm.vendor"),
    xdgCurrentDesktop: String? = System.getenv("XDG_CURRENT_DESKTOP"),
    desktopSession: String? = System.getenv("DESKTOP_SESSION"),
): HostPlatform {
    val os = osName.lowercase()
    return when {
        androidRuntime?.contains("Android", ignoreCase = true) == true -> HostPlatform.Android
        // The browser has no operating system to report. Its shim for this property answers
        // `web`, which is the only way a tab can say what it is: `navigator.platform` names
        // the machine underneath, so a browser on a Mac would come out as macOS and take
        // Cupertino, and the same page on Windows would take Fluent.
        os == "web" -> HostPlatform.Web
        os.contains("mac") || os.contains("darwin") -> HostPlatform.MacOs
        os.contains("ios") -> HostPlatform.Ios
        os.contains("win") -> HostPlatform.Windows
        os.contains("linux") || os.contains("nix") || os.contains("bsd") ->
            linuxDesktop(xdgCurrentDesktop, desktopSession)
        else -> HostPlatform.Unknown
    }
}

private fun linuxDesktop(xdgCurrentDesktop: String?, desktopSession: String?): HostPlatform {
    val value = xdgCurrentDesktop?.takeIf { it.isNotBlank() } ?: desktopSession.orEmpty()
    return when {
        value.contains("GNOME", ignoreCase = true) -> HostPlatform.LinuxGnome
        value.contains("KDE", ignoreCase = true) -> HostPlatform.LinuxKde
        else -> HostPlatform.LinuxOther
    }
}

/**
 * The system `adaptive` picks for a platform.
 *
 * A GNOME session takes Adwaita, a KDE session takes Breeze, and any other Linux session
 * takes Deepin. A platform with no design language of its own takes the Host's fallback. That is why `Theme::adaptive`
 * makes the fallback a required argument: there is no platform for which adaptive has
 * nothing to choose.
 */
internal fun adaptiveSystem(platform: HostPlatform, fallback: DesignSystem): DesignSystem =
    when (platform) {
        HostPlatform.Android -> DesignSystem.Material3
        // Apple's current language, the one macOS 26 and iOS 26 draw themselves in.
        // Cupertino is the previous one and is still current vocabulary elsewhere, so it
        // stays reachable, but an app that did not choose gets what the machine uses.
        HostPlatform.MacOs, HostPlatform.Ios -> DesignSystem.LiquidGlass
        HostPlatform.Windows -> DesignSystem.Fluent
        // A browser has no design language of its own, so the choice is arbitrary; Fluent 2
        // is the documented default and an app can say `unified` to be explicit.
        HostPlatform.Web -> DesignSystem.Fluent
        HostPlatform.LinuxGnome -> DesignSystem.Gnome
        HostPlatform.LinuxKde -> DesignSystem.Breeze
        // Any other Linux session, and one that cannot be identified at all, takes
        // Deepin. That is the job it was written for.
        HostPlatform.LinuxOther -> DesignSystem.Deepin
        HostPlatform.Unknown -> fallback
    }

/**
 * A design system bound to one colour scheme: the generated token table (colours, type,
 * shapes, spacing) plus the component rules the Renderer owns (elevation, button variants,
 * motion).
 */
class ResolvedTheme(
    val system: DesignSystem,
    val tokens: DesignTokenTable,
    val rules: ComponentRules,
    val dark: Boolean,
    /**
     * The class of window this theme is being resolved for.
     *
     * A design system is allowed to answer differently at different widths, and Apple's
     * does: macOS 26 puts glass on chrome over opaque document content, while a phone
     * sized window carries it much further. Nothing on the wire says this; the Renderer
     * already measures the window for `WindowSizeChanged`, and this is the same reading.
     */
    val sizeClass: WindowSizeClass = WindowSizeClass.Compact,
    /**
     * The faces the application registered, by the role each was registered for.
     *
     * Empty is the ordinary case and means every role is written in the machine's own UI
     * face. A role that is in here is written in the face the application shipped, and a
     * role that is not stays where it was: naming a font for titles must not quietly
     * change body text as well.
     */
    val fonts: Map<TypeRole, FontFamily> = emptyMap(),
    /**
     * What a registered brush id was registered as, or null for an id naming nothing.
     *
     * A lookup and not a map, because a brush is named per node rather than per theme and
     * copying every registration into the theme each time one changes would cost the
     * whole table for the one node that asked.
     */
    val brushes: (Int) -> ComposeBrush? = { null },
    /**
     * Whether the window this theme draws into has the platform's own material behind
     * it, so that what the renderer leaves unpainted shows the desktop.
     *
     * A design system that draws chrome as glass has to know, because the right recipe
     * differs. Over app content the glass carries its own tint; straight over the
     * platform's material that tint would cover the thing the platform was asked to show,
     * and the chrome should be mostly the desktop. False everywhere the window is opaque,
     * which is every platform but one and every window that did not ask.
     */
    val windowBackdrop: Boolean = false,

    /**
     * Which platform this is running on.
     *
     * A design system is not a platform and mostly does not need to know, which is why
     * this arrived late. One rule does: the Apple language is drawn on two platforms whose
     * answer for a set of destinations with no room differs, because one of them has its
     * own tab bar to hand the destinations to (FR-14.9) and the other has a sidebar that
     * is put away behind a button.
     */
    val platform: HostPlatform = HostPlatform.Unknown,
) {
    fun color(role: ColorRole): Color =
        rules.color(role, dark, sizeClass) ?: Color(tokens.color(role, dark))

    /**
     * A literal paints itself, a role goes through the table, a brush answers with the
     * colour it starts from.
     *
     * A gradient asked for as a colour has to become one somewhere, and the alternative
     * is a caller that has to know which kind of paint it was handed before it can use
     * it. Everywhere a brush can actually be drawn asks [brush] instead.
     */
    fun color(paint: Paint): Color = when (paint) {
        is Paint.Literal -> Color(paint.argb)
        is Paint.Role -> color(paint.role)
        is Paint.Asset -> brushes(paint.assetId)?.let(::startingColor)
            ?: color(ColorRole.Surface)
    }

    /**
     * What to fill with: a brush where the paint named one, a flat colour otherwise.
     *
     * Null where the paint named a brush that is not registered, so that the caller can
     * report it. Drawing a guess would leave the application with a screen that is only
     * subtly wrong and nothing to read about why.
     */
    fun brush(paint: Paint): ComposeBrush? = when (paint) {
        is Paint.Asset -> brushes(paint.assetId)
        else -> SolidColor(color(paint))
    }

    fun space(role: SpaceRole): Dp = tokens.space(role).dp

    /**
     * The spec a change of this importance runs on in this system.
     *
     * Reduced motion is answered here rather than by the Host: every role becomes
     * `Instant`, so each one finishes inside the frame it starts in, and the screen that
     * declared it is not involved and does not need to be recomposed for it.
     */
    fun <T> motion(role: MotionRole): FiniteAnimationSpec<T> = rules.motion.spec(
        if (dioxus.compose.ui.node.platformReducedMotion()) MotionRole.Instant else role,
    )

    fun radius(role: ShapeRole): Dp = tokens.radius(role).dp

    /** `Full` is stored as a very large radius, which is a pill at any height. */
    fun shape(role: ShapeRole): Shape = shapeOfRadius(tokens.radius(role))

    /**
     * True where this system's corners are continuous rather than circular.
     *
     * A circular corner joins the straight edge at a point where curvature jumps from
     * zero to 1/r, and the eye reads that jump as a pinch. Apple's corners do not have
     * it; every other system here draws a plain arc, and giving them a superellipse would
     * be a mistake rather than a refinement.
     */
    val continuousCorners: Boolean
        get() = system == DesignSystem.LiquidGlass || system == DesignSystem.Cupertino

    /**
     * One radius in dp, cut the way this system cuts corners.
     *
     * `Full` is a true pill in every system, including this one. A continuous corner
     * taken to its largest radius is not a capsule: the superellipse keeps its flattened
     * flanks and the end reads as a squircle, which is what an Apple button is not.
     * Apple's capsules are semicircular at the ends.
     */
    fun shapeOfRadius(radius: Float): Shape = when {
        radius >= FULL_RADIUS -> roundedShape(radius)
        continuousCorners -> ContinuousCornerShape(radius.dp)
        else -> roundedShape(radius)
    }

    /** Four radii in dp, cut the way this system cuts corners. */
    fun shapeOfRadii(topStart: Float, topEnd: Float, bottomEnd: Float, bottomStart: Float): Shape =
        if (continuousCorners) {
            ContinuousCornerShape(topStart.dp, topEnd.dp, bottomEnd.dp, bottomStart.dp)
        } else {
            RoundedCornerShape(topStart.dp, topEnd.dp, bottomEnd.dp, bottomStart.dp)
        }

    fun type(role: TypeRole): TypeToken = tokens.type(role)

    /**
     * The face this role is written in.
     *
     * A registered font wins, then code, then the machine's UI face. Code is second
     * because a monospace role that resolved to a proportional face is not a styling
     * difference, it is columns that no longer line up.
     */
    fun family(role: TypeRole): FontFamily =
        fonts[role] ?: if (tokens.type(role).monospace) FontFamily.Monospace else platformUiFamily

    companion object {
        internal fun roundedShape(radius: Float): Shape =
            if (radius >= FULL_RADIUS) RoundedCornerShape(percent = 50) else RoundedCornerShape(radius.dp)

        internal const val FULL_RADIUS = 1000.0f
    }
}

val TypeToken.fontSize: TextUnit get() = size.sp
val TypeToken.composeWeight: FontWeight get() = FontWeight(weight)
val TypeToken.composeLineHeight: TextUnit get() = lineHeight.sp
val TypeToken.composeLetterSpacing: TextUnit get() = letterSpacing.sp

/**
 * The face this machine writes its interfaces in.
 *
 * Held here and filled in by the platform, because finding it is platform work: the
 * desktop asks the font manager for the system's UI face by name, and a browser or a
 * phone has its own answer or none. Whoever knows assigns it before the first frame;
 * whoever does not leaves the toolkit's default, which is what every screen had before.
 *
 * Not the design system's choice. A window belongs to the machine it is on mostly because
 * its letters look like every other window's, and a Material 3 screen on a Mac is still
 * on a Mac.
 */
var platformUiFamily: FontFamily = FontFamily.Default

/**
 * The hand-written half of a design system: elevation rendering, `ButtonVariant` styling
 * and motion.
 *
 * A fourth design system is one `DesignTokenTable` in the generated protocol plus one
 * implementation of this interface, and nothing else. No widget, property, modifier or wire
 * format changes.
 */
interface ComponentRules {
    /**
     * A colour this system answers differently in a window of this class, or null to take
     * the generated table's value.
     *
     * Null for almost everything, because a design system has one palette. Apple is the
     * exception and only in one place: its page is a different colour on a phone and in a
     * desktop window, and both values are part of the same design language. The table can
     * carry one value per role, so the choice between two of Apple's own has to be made
     * here.
     *
     * This is not a way for a system to redecorate at will. A role answered here is one
     * the platform vendor specifies twice; anything else belongs in the table, where the
     * Host's own colour resolution can see it.
     */
    fun color(role: ColorRole, dark: Boolean, sizeClass: WindowSizeClass): Color? = null

    /** How `Modifier::Elevation(dp)` is drawn. The Host sends a dp value and nothing else. */
    fun elevation(
        modifier: androidx.compose.ui.Modifier,
        elevation: Dp,
        shape: Shape,
        theme: ResolvedTheme,
    ): androidx.compose.ui.Modifier

    /** How a `ButtonVariant` looks, resting and pressed. */
    fun button(
        variant: dioxus.compose.protocol.ButtonVariant,
        theme: ResolvedTheme,
    ): ButtonStyle

    /**
     * How a container or overlay looks. The widget says which kind of container it is and
     * nothing else: the background, the corner, the resting height, the scrim behind a
     * modal and the hairline under a bar are all decided here.
     */
    fun container(role: ContainerRole, theme: ResolvedTheme): ContainerStyle

    /** How a tab strip and its selection indicator look. */
    fun tabs(theme: ResolvedTheme): TabsStyle

    /**
     * How the selection controls and the two indicators are drawn.
     *
     * One call for the six of them because they are one family: something that holds a
     * state, something that shows it, and a reaction to being touched. A widget sends
     * whether it is on, where it sits between two ends, or which way it runs. The size of
     * the box, the shape of the tick, the dimensions of a track and a thumb, the speed of
     * an indeterminate sweep and the weight of a rule are all decided here, which is why
     * the same declaration comes out as a Material checkbox and as a Cupertino one.
     */
    fun controls(theme: ResolvedTheme): ControlsStyle

    /**
     * Who draws those six.
     *
     * The default draws them from [controls], so a new design system implements `controls()`
     * and nothing else. Material 3 overrides this because `androidx.compose.material3`
     * already draws Material's controls, and a copy of them would only drift from the
     * specification it claims to follow.
     */
    val controlWidgets: ControlWidgets get() = DrawnControlWidgets

    /**
     * The metrics this system's icon set is drawn to.
     *
     * The Host registers a meaning, never a picture or a system icon name, so the artwork
     * is chosen here. That is what makes one declaration come out as the shape Cupertino
     * draws and as the shape Material 3 draws, without the Host knowing either.
     */
    fun icon(role: dioxus.compose.protocol.IconRole, theme: ResolvedTheme): IconStyle

    /**
     * How this system asks for a date, a time and a choice.
     *
     * The three systems do not merely style their pickers differently, they are operated
     * differently: a calendar grid, a wheel, a flyout. The widget carries a value, a range
     * and a change event, so the way of picking is decided here and nowhere else.
     */
    val pickers: PickerRules

    /**
     * The frame around a text field.
     *
     * A field with nothing around it is the same field in all six systems, and it was:
     * no fill, no line, no inner room, no focus mark. Which of those a system draws, and
     * what changes when the caret goes in, is decided here.
     */
    fun field(theme: ResolvedTheme): FieldStyle

    /** State transition timing. Motion is a design system rule, not a Host parameter. */
    val motion: Motion

    /**
     * What a surface of this role is made of here.
     *
     * Four roles and no blur radius, because the systems disagree about what a material
     * even is: Apple's blur what is behind them, Material 3 lifts the surface and lays a
     * tone over it, and the GNOME and KDE systems draw an opaque panel. A radius from the
     * screen would be a blur instruction handed to systems that never blur.
     *
     * The default is the opaque reading, and it is the reading four of the seven want. It
     * is built from this system's own surface and outline tokens, so a screen asking for
     * the same material still comes out in this system's colours rather than in someone
     * else's.
     */
    fun material(role: MaterialRole, theme: ResolvedTheme): SurfaceMaterial =
        SurfaceMaterial.Opaque(opaqueMaterial(role, theme))

    // The three answers below have defaults, and that is the point of them. A design
    // system implements what it has an opinion about; what it says nothing about is
    // derived from its own token table, so it still comes out in its own colours rather
    // than in someone else's. A seventh design system is one more implementation of this
    // interface, and it does not stop compiling when the vocabulary grows again.

    /**
     * How a set of destinations is presented at this width, and what it is drawn with.
     *
     * The width decides between a bar across the bottom, a rail down the side and a
     * drawer standing open, because that is the correspondence every one of these design
     * systems already makes. The Host declared one navigation and said nothing about
     * which of the three it wanted.
     */
    fun navigation(sizeClass: WindowSizeClass, theme: ResolvedTheme): NavigationStyle =
        NavigationStyle(
            presentation = when (sizeClass) {
                WindowSizeClass.Compact -> NavigationPresentation.Bar
                WindowSizeClass.Medium -> NavigationPresentation.Rail
                WindowSizeClass.Expanded -> NavigationPresentation.Drawer
            },
            container = theme.color(ColorRole.SurfaceContainer),
            content = theme.color(ColorRole.OnSurfaceVariant),
            selectedContent = theme.color(ColorRole.Primary),
            indicator = Color.Transparent,
            indicatorShape = theme.shape(ShapeRole.Full),
            indicatorKind = NavigationIndicator.None,
            separator = theme.color(ColorRole.OutlineVariant),
            barHeight = 64.dp,
            railWidth = 80.dp,
            drawerWidth = 240.dp,
            itemSpacing = theme.space(SpaceRole.Xs),
            itemPadding = theme.space(SpaceRole.Sm),
            labelInRail = true,
            typeRole = TypeRole.Label,
        )

    /**
     * Which edge a sheet enters from at this width, and what it is drawn with.
     *
     * Up from the bottom where the window is narrow, in from the side where it is wide:
     * the same convention in all of these systems, and the same reason as the navigation
     * presentation. The Host has no property that could ask for one of them.
     */
    fun sheet(sizeClass: WindowSizeClass, theme: ResolvedTheme): SheetStyle = SheetStyle(
        edge = if (sizeClass == WindowSizeClass.Compact) SheetEdge.Bottom else SheetEdge.End,
        container = theme.color(ColorRole.SurfaceContainer),
        content = theme.color(ColorRole.OnSurface),
        shape = theme.shape(ShapeRole.Large),
        elevation = 6.dp,
        scrim = Color.Black.copy(alpha = 0.32f),
        handle = theme.color(ColorRole.OutlineVariant),
        widthFraction = 0.4f,
        heightFraction = 0.5f,
        padding = theme.space(SpaceRole.Lg),
        borderWidth = 0.dp,
        borderColor = Color.Transparent,
    )

    /**
     * How this system draws the window's own caption: the three buttons, and where the
     * title sits.
     *
     * Only the platforms where the renderer draws the caption itself use this. macOS
     * keeps its own buttons, and imitating them would be the most visible way to fail at
     * looking native. Everywhere else the window is undecorated, so these three are the
     * only way to minimise, maximise or close it: they are not decoration.
     *
     * The Host has no say here, deliberately. The colour, the position and the shape of a
     * window button belong to the design system and the platform.
     *
     * The default is a plain set at the trailing edge with a tinted hover, which is what
     * most of these systems do. A system overrides what it actually differs about.
     */
    fun caption(theme: ResolvedTheme, titleBar: TitleBar = TitleBar.Normal): CaptionStyle = CaptionStyle(
        side = CaptionSide.End,
        buttonWidth = 32.dp,
        buttonHeight = 32.dp,
        shape = theme.shape(ShapeRole.Small),
        minimiseContainer = Color.Transparent,
        maximiseContainer = Color.Transparent,
        closeContainer = Color.Transparent,
        hover = theme.color(ColorRole.SurfaceVariant),
        closeHover = theme.color(ColorRole.Error),
        glyph = theme.color(ColorRole.OnSurfaceVariant),
        closeHoverGlyph = theme.color(ColorRole.OnError),
        glyphStroke = 1.dp,
        glyphAtRest = true,
        spacing = theme.space(SpaceRole.Xs),
        edgePadding = theme.space(SpaceRole.Sm),
        titleAlignment = CaptionTitleAlignment.Start,
    )

    /**
     * How a transient message is drawn, and how long each of the two durations lasts.
     *
     * The milliseconds are here rather than on the wire because they are a rule of the
     * design language: Material's four and ten seconds are not Apple's, and a Host that
     * sent a number would be overruling whichever system it ended up under.
     */
    fun message(theme: ResolvedTheme): MessageStyle = MessageStyle(
        container = theme.color(ColorRole.OnSurface),
        content = theme.color(ColorRole.Surface),
        actionContent = theme.color(ColorRole.Primary),
        shape = theme.shape(ShapeRole.Small),
        elevation = 3.dp,
        placement = MessagePlacement.BottomCenter,
        horizontalPadding = theme.space(SpaceRole.Md),
        verticalPadding = theme.space(SpaceRole.Sm),
        inset = theme.space(SpaceRole.Md),
        shortMillis = 4_000,
        longMillis = 10_000,
        borderWidth = 0.dp,
        borderColor = Color.Transparent,
        typeRole = TypeRole.Body,
    )
}

/** Which end of the caption the window buttons sit at. */
enum class CaptionSide { Start, End }

/** Where the window's title sits along the caption. */
enum class CaptionTitleAlignment { Start, Center }

/**
 * How the window's own caption is drawn: the three buttons, and where the title sits.
 *
 * All seven systems are described by one shape, because the differences between them are
 * differences of value rather than of kind: a disc at the leading edge that shows its
 * glyph only under the pointer, a wide rectangle at the trailing edge that turns red when
 * the pointer is on close, a grey circle, a bare glyph. What none of them disagree about
 * is that there are three buttons and what each one does.
 *
 * Nothing here crosses the boundary. A window button is the design system's and the
 * platform's, and an application that could recolour one would be deciding for whichever
 * platform it ended up on.
 */
data class CaptionStyle(
    val side: CaptionSide,
    /** Wider than it is tall on the systems that give a button a hover zone. */
    val buttonWidth: Dp,
    val buttonHeight: Dp,
    val shape: Shape,
    /** What each button is filled with when the pointer is elsewhere. */
    val minimiseContainer: Color,
    val maximiseContainer: Color,
    val closeContainer: Color,
    val hover: Color,
    /** Close is the button most of these systems colour differently under the pointer. */
    val closeHover: Color,
    val glyph: Color,
    val closeHoverGlyph: Color,
    val glyphStroke: Dp,
    /**
     * Whether the glyph is drawn when the pointer is elsewhere.
     *
     * False on the one system whose buttons are coloured discs: their colour is what says
     * which is which, and the marks inside appear only when the pointer is over the set.
     */
    val glyphAtRest: Boolean,
    val spacing: Dp,
    val edgePadding: Dp,
    val titleAlignment: CaptionTitleAlignment,
    /**
     * How far in from the window's corner the platform's own buttons sit.
     *
     * macOS only, and only where the window's buttons are the system's. Everywhere else
     * the caption's buttons are ours and drawn where [side] says, so there is no corner to
     * come in from. Zero leaves them where the system put them.
     */
    val platformButtonInset: Dp = 0.dp,
    /**
     * How round the window's own corners are, or zero to leave the system's.
     *
     * macOS only, for the same reason. On Windows and Linux the window's outline belongs
     * to the compositor rather than to the application.
     */
    val windowCornerRadius: Dp = 0.dp,
    /**
     * How tall the caption is where the application draws it.
     *
     * Windows and Linux. Zero means the design system has nothing to say and the caption
     * is whatever it was, which is what every system answered before the two title bar
     * modes existed.
     */
    val height: Dp = 0.dp,
)

/**
 * What marks the selected destination.
 *
 * The three systems disagree, visibly: Material puts a filled pill behind the icon, Fluent
 * draws a short bar along the leading edge of the row, and a Cupertino tab bar marks the
 * selection with colour alone. A widget that only knew "selected" could not produce any of
 * the three on purpose.
 */
enum class NavigationIndicator { Pill, LeadingEdgeBar, None }

/**
 * How much of a destination the mark covers.
 *
 * Material's pill sits behind the icon and the label hangs below it, outside the fill.
 * A Plasma sidebar row, a Deepin lozenge and an Adwaita view switcher button all cover
 * the whole destination and write the label on the fill. The two are not interchangeable:
 * a label coloured to read on the mark, drawn beside a mark that does not reach it, comes
 * out white on white.
 */
enum class NavigationExtent { Icon, Destination }

/** Which of its three shapes a set of destinations has taken. */
enum class NavigationPresentation {
    /** A bar across the bottom of the window, destinations side by side. */
    Bar,

    /** A narrow column down the leading edge. */
    Rail,

    /** A wide column down the leading edge, labels beside their icons. */
    Drawer,

    /**
     * Not on the screen, with a button that brings it back over the page.
     *
     * What every macOS sidebar does when there is no room for it, and the only one of
     * these four where the page has the whole window. The button belongs with the window's
     * own buttons where the platform hands its caption over.
     */
    PutAway,
}

/**
 * How one presentation of a set of destinations is drawn.
 *
 * All three presentations are described by one style, because they are one concept: what
 * changes between them is where the strip sits and how much room a destination gets, not
 * what a destination is.
 */
data class NavigationStyle(
    val presentation: NavigationPresentation,
    val container: Color,
    val content: Color,
    val selectedContent: Color,
    /** What is drawn behind or beside the selected destination. */
    val indicator: Color,
    val indicatorShape: Shape,
    val indicatorKind: NavigationIndicator,
    /** Whether that mark covers the icon alone or the whole destination. */
    val indicatorExtent: NavigationExtent = NavigationExtent.Icon,
    /** The hairline between the destinations and the screen, null where there is none. */
    val separator: Color?,
    val barHeight: Dp,
    val railWidth: Dp,
    val drawerWidth: Dp,
    val itemSpacing: Dp,
    val itemPadding: Dp,
    /** Whether a rail, which is narrow, still writes the label under the icon. */
    val labelInRail: Boolean,
    val typeRole: TypeRole,
    /** Optional page gradient behind both the destinations and their content. */
    val pageGradientStart: Color? = null,
    val pageGradientEnd: Color? = null,
    /** A search destination becomes a field-shaped action when this is non-null. */
    val searchContainer: Color? = null,
    /**
     * The hairline round that field, or null where the fill alone says what it is.
     *
     * A drawer marks the destination you are on with a fill as well, so a field drawn as a
     * fill and nothing else is a second thing in the list that looks selected. The line is
     * what tells a place you can go from a place you can type.
     */
    val searchOutline: Color? = null,
    /**
     * What the strip is made of when it floats, or null for a strip painted [container]
     * straight onto the window's edge.
     */
    val stripMaterial: SurfaceMaterial? = null,
    /** The outline of a floating strip. A strip on the window's edge has none. */
    val stripShape: Shape = RectangleShape,
    /**
     * How far a floating strip stands off the edges of the window it is laid along.
     *
     * Zero is a strip attached to the edge, which is what every system but one draws. A
     * bar keeps this much room at its sides and under it; a rail or a drawer at its
     * leading edge, top and bottom.
     */
    val floatingInset: Dp = 0.dp,
    /**
     * Whether a rail or a drawer runs to the top of the window and carries the window
     * buttons on its own surface.
     *
     * Where it does, the strip is the top of the window on its side, the caption is laid
     * out inside it, and the page beside it starts its own content clear of the caption
     * without a band of page colour across the top of the whole window.
     */
    val carriesCaption: Boolean = false,
    /**
     * Whether the page gradient runs behind a rail or a drawer as well as behind the page.
     *
     * True where the strip is translucent over the page, so it takes the page's colour.
     * False where the strip sits straight on the window's own backdrop instead, and the
     * page is painted only beside it.
     */
    val pageBehindStrip: Boolean = true,
    /**
     * How far down the page the gradient's first colour holds before it starts to turn,
     * as a fraction of the page's height. Zero is an even gradient from top to bottom.
     */
    val pageGradientHold: Float = 0f,
    /**
     * The gap between one destination and the next down a rail or a drawer, or null to
     * use [itemSpacing]. Separate because [itemSpacing] is also the gap between an icon
     * and its label, and a drawer wants the rows closer together than that.
     */
    val destinationGap: Dp? = null,
    /**
     * The room the strip keeps around its destinations, or null to use [itemPadding].
     *
     * Separate because the two are measured against different things. The strip's own
     * inset is what sets how far the selected row's mark stops short of the panel's edge,
     * and the room inside a row is what sets where the icon column falls; a sidebar that
     * uses one number for both puts its icons wherever its mark happens to want to stop.
     */
    val stripPadding: Dp? = null,
    /**
     * The colour that rises from the window's two bottom corners, or null for a page whose
     * wash is level all the way across.
     *
     * A wash that turns at the same height at every horizontal position is a band, and a
     * band reads as a fill rather than as light. Measured across the reference, the height
     * its wash begins at runs from six hundred and forty two under the middle of the window
     * to four hundred and ninety six at the trailing edge: two glows anchored in the bottom
     * corners, arcing up over a level ramp.
     */
    val pageCornerGlow: Color? = null,
    /**
     * The room inside one drawer row, from the row's edge to its icon, or null to use
     * [itemPadding].
     */
    val destinationInset: Dp? = null,
    /**
     * How tall one drawer row is, or null to let what it holds decide.
     *
     * A row with an icon and a row with only a name are not the same height when nothing
     * says they are, and a list whose rows are two heights has no rhythm.
     */
    val destinationHeight: Dp? = null,
)

/** The edge a sheet comes in from. */
enum class SheetEdge { Bottom, End }

/**
 * How a sheet is drawn, and where it comes from.
 *
 * Nothing here is sent by the Host, including the edge: the Renderer measured the window,
 * so the Renderer decides.
 */
data class SheetStyle(
    val edge: SheetEdge,
    val container: Color,
    val content: Color,
    val shape: Shape,
    val elevation: Dp,
    val scrim: Color,
    /** The grab handle a sheet draws along its dragging edge, null where none is drawn. */
    val handle: Color?,
    /** How much of the window a sheet entering from the side takes. */
    val widthFraction: Float,
    /** How much of the window a sheet entering from the bottom takes. */
    val heightFraction: Float,
    val padding: Dp,
    val borderWidth: Dp,
    val borderColor: Color,
)

/**
 * Where a transient message appears.
 *
 * Four rather than one, because this is a place the systems genuinely disagree: Material
 * puts a snackbar low and to the leading side, GNOME centres a toast along the bottom
 * edge, Windows and Apple slide a banner in from the top corner, and Deepin drops a
 * lozenge in at the top middle. A widget that could only be told "show a message" would
 * have to pick one of the four for everybody.
 */
enum class MessagePlacement { BottomStart, BottomCenter, TopCenter, TopEnd;

    /** True where the message sits along the window's top edge. */
    val atTop: Boolean get() = this == TopCenter || this == TopEnd
}

/**
 * How a transient message is drawn and how long it stays.
 *
 * The durations are part of this because they are part of the design language, and because
 * the alternative is the Host holding a timer for an animation it cannot see.
 */
data class MessageStyle(
    val container: Color,
    val content: Color,
    val actionContent: Color,
    val shape: Shape,
    val elevation: Dp,
    val placement: MessagePlacement,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    /** How far the message sits from the window's edges. */
    val inset: Dp,
    val shortMillis: Int,
    val longMillis: Int,
    val borderWidth: Dp,
    val borderColor: Color,
    val typeRole: TypeRole,
)

/**
 * The design system's state transition timing.
 *
 * The four named lengths are what a node's declared importance resolves to. A screen says
 * how much a change matters and never how many milliseconds it takes, because Material's
 * emphasized curve and Cupertino's softer, shorter one are different answers to the same
 * question and the screen is not the place to settle it.
 */
data class Motion(
    val pressMillis: Int,
    val releaseMillis: Int,
    val easing: androidx.compose.animation.core.Easing,
    /** How long a pointer rests on something before its explanation appears. */
    val tooltipDelayMillis: Int = 500,
    val quickMillis: Int = pressMillis,
    val standardMillis: Int = releaseMillis,
    val slowMillis: Int = releaseMillis * 2,
    val emphasizedMillis: Int = releaseMillis * 3 / 2,
    /** The curve for the one role that is allowed its own, and the same curve otherwise. */
    val emphasizedEasing: androidx.compose.animation.core.Easing = easing,
) {

    /** How long a change of this importance runs for. Instant is a change with no run. */
    fun millis(role: MotionRole): Int = when (role) {
        MotionRole.Instant -> 0
        MotionRole.Quick -> quickMillis
        MotionRole.Standard -> standardMillis
        MotionRole.Slow -> slowMillis
        MotionRole.Emphasized -> emphasizedMillis
    }

    fun easing(role: MotionRole): androidx.compose.animation.core.Easing =
        if (role == MotionRole.Emphasized) emphasizedEasing else easing

    /**
     * The spec a change of this importance runs on.
     *
     * A zero length tween finishes inside the frame it starts in, which is what a system
     * asked to reduce motion gets for every role, and what `Instant` means anyway.
     */
    fun <T> spec(role: MotionRole): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        androidx.compose.animation.core.tween(millis(role), easing = easing(role))
}

/**
 * A line along a field's bottom edge, which is what Material and Fluent thicken when the
 * caret goes in.
 *
 * Null on the systems that mark focus with the box instead. Separate from the box because
 * Fluent draws both: a grey rectangle that stays grey, and a bottom line that goes accent.
 */
data class FieldUnderline(
    val color: Color,
    val focusedColor: Color,
    val width: Dp,
    val focusedWidth: Dp,
)

/**
 * The frame around a text field, resting and with the caret in it.
 *
 * Nothing here is sent by the Host. A field is one of the places these six languages
 * diverge most visibly, and a screen that painted its own fill and line would be deciding
 * for whichever system it ended up under.
 *
 * Focus is Renderer state, so the second half of every pair is reached without a boundary
 * call and the Host never learns that the caret moved.
 */
/**
 * The shape a field of this height should actually wear.
 *
 * A capsule is a shape for something one line tall. Liquid Glass asks for one, which is
 * right for a search field and wrong for a page: a full corner on an editor that fills
 * the window puts a radius of half the window on it, and the notepad drew its document
 * as an enormous lozenge with the first line of text cut off by the curve.
 *
 * Only the fully round corner is capped, and only where the field takes more than a line.
 * Everything else a design system asked for is left exactly as it asked.
 */
internal fun FieldStyle.forHeight(multiline: Boolean, theme: ResolvedTheme): FieldStyle =
    if (multiline && shape == theme.shape(ShapeRole.Full)) {
        copy(shape = theme.shape(ShapeRole.Large))
    } else {
        this
    }

data class FieldStyle(
    val container: Color,
    val containerFocused: Color,
    /** The box around the field. A zero width is no box, not a hairline. */
    val border: Color,
    val borderFocused: Color,
    val borderWidth: Dp,
    val borderWidthFocused: Dp,
    val underline: FieldUnderline?,
    val shape: Shape,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val cursor: Color,
    /** How tall an empty single line field is before anything is typed into it. */
    val minHeight: Dp,
    /**
     * The fill a field takes when it sits on a glass surface, or null to keep [container].
     *
     * A field inside a glass bar is part of the bar in the systems that draw glass: the
     * composer at the foot of a chat is one capsule with the text in it, not a capsule
     * holding a second, greyer capsule.
     */
    val containerOnGlass: Color? = null,
)

/**
 * The kinds of container the core vocabulary has. A widget emits the role; which colour,
 * corner and height that role means belongs to the design system.
 */
enum class ContainerRole { Card, Surface, TopAppBar, Dialog, Menu, Tooltip }

/**
 * How one container role is drawn.
 *
 * `scrim` is what covers what is behind a modal, and is transparent for the roles that
 * cover nothing. `separator` is the hairline a bar draws under itself, null where the
 * system draws none.
 */
data class ContainerStyle(
    val container: Color,
    val content: Color,
    val shape: Shape,
    val elevation: Dp,
    val borderWidth: Dp,
    val borderColor: Color,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val separator: Color?,
    val scrim: Color,
    val typeRole: TypeRole,
    /**
     * What the container is made of, when the design system has an opinion beyond a flat
     * colour.
     *
     * Null means [container] is painted straight on, which is what Material 3, Fluent and
     * the Linux systems mean by a surface. Liquid Glass answers with glass for the roles
     * that are glass in the window it is drawing into, and with [SurfaceMaterial.Opaque]
     * for the rest; a role that is glass here draws the translucent tint and the lit edge
     * instead of [container], and falls back to the stored opaque colour when the reader
     * has asked for reduced transparency.
     */
    val material: SurfaceMaterial? = null,
    /**
     * Whether this container is drawn as floating pieces rather than as a strip.
     *
     * Only a top app bar asks the question. Where it is true the bar paints nothing
     * across the window: its title sits on the page as it is, and each run of plain
     * actions in it is gathered into a capsule of [material] floating over the page,
     * which is how a toolbar looks in the systems that draw glass.
     */
    val floats: Boolean = false,
)

/**
 * How a tab strip is drawn.
 *
 * The three systems disagree about what marks the selection: Material underlines the tab,
 * Cupertino fills the selected segment, Fluent draws a short bar under the label. All three
 * are expressed here, so the widget only has to know which tab is selected.
 */
data class TabsStyle(
    val container: Color,
    val shape: Shape,
    val selectedContent: Color,
    val unselectedContent: Color,
    val selectedContainer: Color,
    val selectedShape: Shape,
    val indicator: Color,
    val indicatorHeight: Dp,
    val indicatorShape: Shape,
    /** True where the mark spans the whole tab, false where it is a short centred bar. */
    val indicatorFillsTab: Boolean,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val typeRole: TypeRole,
)

/**
 * The metrics one icon is drawn to.
 *
 * The geometry of each role is shared, because `Back` means the same thing everywhere. What
 * differs is how it is drawn: Material's heavier stem with flat ends, Cupertino's thin
 * stroke with rounded ends, Fluent's lighter stroke with square ends, each at its own
 * optical size.
 */
data class IconStyle(
    val size: Dp,
    val strokeWidth: Dp,
    val cap: androidx.compose.ui.graphics.StrokeCap,
    val join: androidx.compose.ui.graphics.StrokeJoin,
)

/** Which of the three toggles is being drawn. */
enum class ToggleRole { Checkbox, RadioButton, Switch }

/**
 * How one toggle is drawn, in both of the states it holds.
 *
 * The unchecked and checked colours are separate fields rather than a second call so the
 * transition between them can be interpolated.
 */
data class ToggleStyle(
    /** The control's own footprint, before anything placed beside it. */
    val size: Dp,
    /** The box, circle or track that holds the state. */
    val container: Color,
    val containerChecked: Color,
    /** The tick, the dot or the thumb that shows it. */
    val mark: Color,
    val markUnchecked: Color,
    val border: Color,
    val borderWidth: Dp,
    val shape: Shape,
    /** A switch's travelling thumb. The other two roles leave it at zero. */
    val thumbSize: Dp,
    val trackWidth: Dp,
    val trackHeight: Dp,
    val disabledAlpha: Float,
)

/**
 * How a slider's track, its filled part and its thumb are drawn.
 *
 * `tick` is null where the system marks no discrete stops, which is the difference
 * between a Material slider and a Cupertino one at a glance.
 */
data class SliderStyle(
    val trackHeight: Dp,
    val track: Color,
    val activeTrack: Color,
    val thumbSize: Dp,
    val thumb: Color,
    val thumbBorder: Color,
    val thumbBorderWidth: Dp,
    val tick: Color?,
)

/** How progress is shown, as a bar or as a ring. */
data class ProgressStyle(
    val thickness: Dp,
    val track: Color,
    val indicator: Color,
    /** Diameter when circular. */
    val diameter: Dp,
    val rounded: Boolean,
    /** One full sweep of the indeterminate animation. */
    val periodMillis: Int,
)

/** A rule between two things: thin and full width in some systems, inset in others. */
data class DividerStyle(
    val thickness: Dp,
    val color: Color,
    /** How far it is held back from the leading edge. */
    val inset: Dp,
)

/** Everything the selection controls and the indicators need, answered in one call. */
data class ControlsStyle(
    val checkbox: ToggleStyle,
    val radioButton: ToggleStyle,
    val switch: ToggleStyle,
    val slider: SliderStyle,
    val progress: ProgressStyle,
    val divider: DividerStyle,
) {
    fun toggle(role: ToggleRole): ToggleStyle = when (role) {
        ToggleRole.Checkbox -> checkbox
        ToggleRole.RadioButton -> radioButton
        ToggleRole.Switch -> switch
    }
}

/** The way a date is picked. */
enum class DatePresentation {
    /** A month laid out as a grid of days. */
    CalendarGrid,

    /** Scrolling wheels, one per field. */
    Wheel,

    /** A field that opens a calendar over the page. */
    CalendarFlyout,
}

/** The way a time of day is picked. */
enum class TimePresentation {
    /** A clock face the hand is dragged around. */
    Dial,

    /** Scrolling wheels for the hour and the minute. */
    Wheel,

    /** A field with the hour and the minute stepped up and down. */
    Stepper,
}

/** The way one item out of a list is picked. */
enum class ChoicePresentation {
    /** A field that drops a menu below itself. */
    ExposedMenu,

    /** A wheel of the options, with the chosen one in the middle. */
    Wheel,

    /** A field that opens a flyout list over the page. */
    ComboBox,
}

/**
 * Which of the ways of picking this design system uses.
 *
 * There is no Host property that can override any of these. A widget that could ask for a
 * wheel would stop being a date and start being a piece of interface design, and the
 * system's own conventions would be the thing that loses.
 */
data class PickerRules(
    val date: DatePresentation,
    val time: TimePresentation,
    val choice: ChoicePresentation,
    /**
     * How tall one row of a wheel is.
     *
     * A wheel shows the chosen value between its neighbours, so this is what decides how
     * much of a window the control takes and how far a finger has to travel for one step.
     * It belongs to the design system for the same reason a button's minimum height does:
     * a roomy language spins a roomy wheel, and one number shared by every system means
     * two systems that both spin a wheel spin an identical one.
     */
    val wheelRowHeight: Dp = DEFAULT_WHEEL_ROW_HEIGHT,
)

/** The row height a system takes when it has no opinion about how roomy a wheel is. */
val DEFAULT_WHEEL_ROW_HEIGHT: Dp = 32.dp

/**
 * How one button variant is drawn. The pressed values are separate fields rather than a
 * second call, so the press animation can interpolate between the two.
 */
data class ButtonStyle(
    val container: Color,
    val pressedContainer: Color,
    val content: Color,
    val pressedContentAlpha: Float,
    val borderWidth: Dp,
    val borderColor: Color,
    val pressedBorderColor: Color,
    /** Fluent's lighter top edge; null where the system has no such stroke. */
    val topHighlight: Color?,
    val shape: Shape,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val minHeight: Dp,
    val typeRole: TypeRole,
    val restElevation: Dp,
    val pressedElevation: Dp,
    /**
     * How much of the button is left when it is disabled.
     *
     * One number for the whole control rather than a disabled colour per variant: four
     * more colours is four more answers every design system owes, and a seventh system
     * would owe them too. Fading the drawn button says the same thing in every system's
     * own palette, because it is that system's palette being faded.
     */
    val disabledAlpha: Float,
)

/**
 * Resolves the Host's `SetTheme` into the table and rules used for this frame.
 *
 * The Host's choice is read, never second-guessed: `adaptive` follows the platform only
 * because the Host said `adaptive = true`.
 *
 * With no `SetTheme` at all the default follows the platform, with Material 3 as the
 * fallback. A Host that has sent a theme is a different case from one that has
 * not said anything yet, and only the second is this default.
 */
fun resolveTheme(
    theme: Theme?,
    platform: HostPlatform,
    systemDark: Boolean,
    sizeClass: WindowSizeClass = WindowSizeClass.Compact,
    /**
     * What a registered font asset became, or null where nothing was registered.
     *
     * A lookup rather than the cache itself, so that the design system stays a thing that
     * can be resolved in a test with nothing else standing up around it.
     */
    fontOf: (Int) -> FontFamily? = { null },
    /** What a registered brush became, or null for an id naming nothing. */
    brushOf: (Int) -> ComposeBrush? = { null },
    /** Whether the window has the platform's own material behind it. */
    windowBackdrop: Boolean = false,
): ResolvedTheme {
    val system = when {
        theme == null -> adaptiveSystem(platform, DesignSystem.Material3)
        theme.adaptive -> adaptiveSystem(platform, theme.fallback)
        else -> theme.designSystem
    }
    val dark = when (theme?.colorScheme ?: ColorScheme.FollowSystem) {
        ColorScheme.Light -> false
        ColorScheme.Dark -> true
        ColorScheme.FollowSystem -> systemDark
    }
    // Only the roles the application actually named. A role whose asset is missing or is
    // not a font is left out, so it stays on the machine's own face rather than on
    // nothing, and the report of the missing asset is what says it happened.
    val fonts = buildMap {
        theme?.let { asked ->
            for (role in TypeRole.entries) {
                val family = asked.font(role)?.let(fontOf) ?: continue
                put(role, family)
            }
        }
    }
    return ResolvedTheme(
        system,
        DesignTokens.of(system),
        rulesFor(system),
        dark,
        sizeClass,
        fonts,
        brushOf,
        windowBackdrop,
        platform,
    )
}

/**
 * The opaque reading of a material: the system's surface, moved towards its own outline.
 *
 * Thickness is how far it has moved. A thin material is barely separated from the page, a
 * chrome panel is the furthest, and every step is taken between two colours this system
 * already chose, so nothing here invents a colour.
 */
/**
 * The colour a brush starts from.
 *
 * Only for the places that can hold a colour and nothing else. A gradient's first stop is
 * the honest answer there: it is a colour the application actually chose.
 */
private fun startingColor(brush: ComposeBrush): Color = when (brush) {
    is SolidColor -> brush.value
    else -> Color.Unspecified
}

internal fun opaqueMaterial(role: MaterialRole, theme: ResolvedTheme): Color {
    val surface = theme.color(ColorRole.Surface)
    val toward = theme.color(ColorRole.SurfaceVariant)
    val distance = when (role) {
        MaterialRole.Thin -> 0.18f
        MaterialRole.Regular -> 0.38f
        MaterialRole.Thick -> 0.62f
        MaterialRole.Chrome -> 0.85f
    }
    return Color(
        red = surface.red + (toward.red - surface.red) * distance,
        green = surface.green + (toward.green - surface.green) * distance,
        blue = surface.blue + (toward.blue - surface.blue) * distance,
        alpha = 1f,
    )
}

internal fun rulesFor(system: DesignSystem): ComponentRules = when (system) {
    DesignSystem.Material3 -> Material3Rules
    DesignSystem.Cupertino -> CupertinoRules
    DesignSystem.Fluent -> FluentRules
    DesignSystem.Gnome -> GnomeRules
    DesignSystem.Breeze -> BreezeRules
    DesignSystem.Deepin -> DeepinRules
    DesignSystem.LiquidGlass -> LiquidGlassRules
}

/**
 * The theme every interpreted node reads. `SetTheme` changes it once and Compose invalidates
 * the readers, so a theme change is one record on the wire rather than a `SetProp` for
 * every node in the tree.
 */
val LocalDesignTheme = staticCompositionLocalOf {
    resolveTheme(theme = null, platform = HostPlatform.Unknown, systemDark = false)
}

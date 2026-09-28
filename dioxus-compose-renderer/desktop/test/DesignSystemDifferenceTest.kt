package dioxus.compose.test

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue
import dioxus.compose.design.ContainerRole
import dioxus.compose.design.HostPlatform
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.design.resolveTheme
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.protocol.WindowSizeClass
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag

private fun resolved(system: DesignSystem, dark: Boolean): ResolvedTheme = resolveTheme(
    Theme(system, system, if (dark) ColorScheme.Dark else ColorScheme.Light, false),
    HostPlatform.MacOs,
    systemDark = false,
)

/** Both schemes of one system, which is how a colour answer has to be checked. */
private fun bothSchemes(system: DesignSystem): List<ResolvedTheme> =
    listOf(resolved(system, dark = false), resolved(system, dark = true))

/** WCAG relative luminance, 0 for black and 1 for white. */
private fun luminance(color: Color): Float {
    fun channel(value: Float): Float =
        if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
}

/**
 * How far apart two colours are on their widest channel, in the 0 to 255 the values were
 * written as.
 *
 * A ratio would say two dark greys are alike when a reader can see the edge between them
 * perfectly well; the widest channel is closer to what the eye picks up on small shapes
 * like a thumb against a page.
 */
private fun apart(first: Color, second: Color): Float = max(
    max(abs(first.red - second.red), abs(first.green - second.green)),
    abs(first.blue - second.blue),
) * 255f

/** The outermost ring of a picture, which is where a field's frame is drawn. */
private fun edgeOf(pixels: PixelMap): List<Int> {
    val edge = mutableListOf<Int>()
    for (x in 0 until pixels.width) {
        edge += pixels[x, 0].toArgb()
        edge += pixels[x, pixels.height - 1].toArgb()
    }
    for (y in 0 until pixels.height) {
        edge += pixels[0, y].toArgb()
        edge += pixels[pixels.width - 1, y].toArgb()
    }
    return edge
}

/** The corner this shape would round a square of [size] to, in dp. */
private fun cornerRadius(shape: Shape, size: Dp): Float {
    val rounded = shape as? RoundedCornerShape ?: return 0f
    return rounded.topStart.toPx(Size(size.value, size.value), Density(1f))
}

/**
 * The places where one design system's answer has to stay its own, and where an answer
 * that is merely plausible turns out to be invisible on screen.
 *
 * Three of these systems were written from a description rather than from a desktop, and
 * a wrong colour here does not fail anything else in the build: a white thumb on a white
 * page is drawn full size, in the right place, and cannot be seen.
 */
class DesignSystemDifferenceTest {

    /**
     * A checkbox is drawn as a box, except in the one system that draws a circle.
     *
     * The two Apple systems are named rather than skipped. A round checkbox is Apple's own
     * answer in both of its languages, and the difference from its radio button is the
     * mark inside, not the outline. Everywhere else a corner that grows past a third of
     * the control stops reading as a box at all, and a reader is left with two circles
     * that differ only in what is drawn inside them.
     */
    /**
     * Material cuts its corners on the expressive ladder, not the baseline one.
     *
     * The reference screens are Material 3 Expressive, and the corner is the thing a
     * reader sees first: a card there is cut at twenty eight, and the same layout drawn
     * on the baseline ladder at sixteen reads as the previous version of Material. This
     * is the only system here that rounds a card that far, so the number is the
     * signature.
     */
    @Test
    fun fr14_7_material_cuts_a_card_on_the_expressive_ladder() {
        val theme = resolved(DesignSystem.Material3, dark = false)
        val card = theme.rules.container(ContainerRole.Card, theme)
        val radius = cornerRadius(card.shape, 200.dp)
        assertTrue(
            radius >= 24f,
            "a Material card is cut at $radius, which is the baseline ladder rather than " +
                "the expressive one the reference screens are drawn in",
        )
    }

    @Test
    fun fr14_1_a_checkbox_is_a_box_except_where_the_system_draws_a_circle() {
        val round = setOf(DesignSystem.Cupertino, DesignSystem.LiquidGlass)
        DesignSystem.entries.forEach { system ->
            val theme = resolved(system, dark = false)
            val checkbox = theme.rules.controls(theme).checkbox
            val radius = cornerRadius(checkbox.shape, checkbox.size)
            if (system in round) {
                assertTrue(
                    radius >= checkbox.size.value / 2f - 0.5f,
                    "$system's checkbox is a circle: ${checkbox.size} rounded at $radius",
                )
            } else {
                assertTrue(
                    radius <= checkbox.size.value / 3f,
                    "$system rounds a ${checkbox.size} checkbox by $radius, which is round " +
                        "enough to be mistaken for its own radio button",
                )
            }
        }
    }

    /**
     * GNOME, KDE and Deepin all draw a light switch handle, whichever way the switch is
     * thrown and whichever scheme is in effect.
     *
     * None of the three recolours the handle when the switch turns on: what changes is the
     * track behind it. A handle taken from the ink colour of whatever is under it comes out
     * dark grey when the switch is off and near black on KDE's blue when it is on, which is
     * a control nobody on those desktops would recognise.
     */
    @Test
    fun fr14_1_the_linux_switches_keep_a_light_handle() {
        listOf(DesignSystem.Gnome, DesignSystem.Breeze, DesignSystem.Deepin).forEach { system ->
            bothSchemes(system).forEach { theme ->
                val switch = theme.rules.controls(theme).switch
                listOf("on" to switch.mark, "off" to switch.markUnchecked).forEach { (state, handle) ->
                    assertTrue(
                        luminance(handle) >= LIGHT_HANDLE_LUMINANCE,
                        "$system draws a dark handle on a switch that is $state " +
                            "(dark scheme: ${theme.dark})",
                    )
                }
            }
        }
    }

    /**
     * A slider's thumb can be seen against the page it sits on, in every system and both
     * schemes.
     *
     * A thumb filled with the reading surface and given no ring is the failure this whole
     * set of tables exists to catch: it is drawn, at the right size, in the right place,
     * and there is nothing on screen. A system may earn the contrast with the fill or with
     * a ring around it, and which of the two is its own decision.
     */
    @Test
    fun fr14_1_a_slider_thumb_can_be_seen_against_the_page() {
        DesignSystem.entries.forEach { system ->
            bothSchemes(system).forEach { theme ->
                val slider = theme.rules.controls(theme).slider
                val page = theme.color(ColorRole.Surface)
                val byFill = apart(slider.thumb, page)
                val byRing = if (slider.thumbBorderWidth.value > 0f) {
                    apart(slider.thumbBorder, page)
                } else {
                    0f
                }
                assertTrue(
                    max(byFill, byRing) >= VISIBLE_APART,
                    "$system (dark scheme: ${theme.dark}) draws a slider thumb $byFill from " +
                        "the page it sits on, with a ring $byRing from it",
                )
            }
        }
    }

    /**
     * A rule between two rows can be seen on the panel a list of rows sits on.
     *
     * A divider is the one thing on a screen that is nothing but contrast, so one taken
     * from a colour a hair away from the layer under it is not a faint rule, it is no rule
     * at all: three rows run together with no gap and nothing to see. The panel rather than
     * the page, because that is where a list is.
     */
    @Test
    fun fr15_2_4_a_rule_can_be_seen_on_the_panel_a_list_sits_on() {
        DesignSystem.entries.forEach { system ->
            bothSchemes(system).forEach { theme ->
                val divider = theme.rules.controls(theme).divider
                val panel = theme.color(ColorRole.SurfaceContainer)
                assertTrue(
                    apart(divider.color, panel) >= VISIBLE_RULE,
                    "$system (dark scheme: ${theme.dark}) rules its rows in a colour " +
                        "${apart(divider.color, panel)} from the panel they sit on",
                )
            }
        }
    }

    /**
     * A control that asks for no line around it is drawn without one.
     *
     * Compose reads a zero width border as a hairline and draws a one pixel line anyway,
     * so the two systems that say a switch has no outline were both getting a dark ring
     * around the track. Cupertino is the case here because its switch is the plainest of
     * the six: a pale track, a white knob and nothing else, so any line in the picture is
     * a line nobody asked for.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fr14_1_a_control_that_asks_for_no_line_is_drawn_without_one() {
        val system = DesignSystem.Cupertino
        val theme = resolved(system, dark = false)
        assertTrue(
            theme.rules.controls(theme).switch.borderWidth.value == 0f,
            "this test only means anything while $system asks for no line",
        )
        runComposeUiTest {
            setContent {
                DioxusContent(
                    rememberDioxusHost(
                        FakeHostConnection(
                            listOf(
                                Mutation.SetTheme(
                                    Theme(system, system, ColorScheme.Light, false),
                                ),
                                Mutation.Create(SWITCH, WidgetKind.Switch),
                                Mutation.SetProp(
                                    SWITCH,
                                    PropertyKind.Checked,
                                    PropertyValue.Bool(false),
                                ),
                            ),
                        ),
                    ),
                )
            }
            waitForIdle()
            val pixels = onNodeWithTag(nodeTestTag(SWITCH)).captureToImage().toPixelMap()
            var darkest = 1f
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    darkest = minOf(darkest, luminance(pixels[x, y]))
                }
            }
            assertTrue(
                darkest >= UNLINED_LUMINANCE,
                "$system's switch has a line around it: the darkest pixel in it is " +
                    "$darkest, and its palest colour is the track",
            )
        }
    }

    /**
     * No two systems lay a set of destinations out alike.
     *
     * The colours are left out of this on purpose. Every system has its own palette, so
     * comparing whole styles would pass while all six drew the identical strip in six
     * tints, which is exactly what three of them were doing: they answered nothing at all
     * and took the shared default, so the rail was the same width and the selection was
     * marked the same way on GNOME, on KDE and on Deepin.
     */
    @Test
    fun fr21_no_two_systems_lay_a_set_of_destinations_out_alike() {
        val shapes = DesignSystem.entries.associateWith { system ->
            val theme = resolved(system, dark = false)
            // The rail, because it is the presentation where the three measurements and
            // the mark are all in play at once.
            val style = theme.rules.navigation(WindowSizeClass.Medium, theme)
            listOf(
                style.indicatorKind,
                style.barHeight,
                style.railWidth,
                style.drawerWidth,
                style.labelInRail,
                style.typeRole,
                style.separator != null,
            )
        }
        assertDistinct(shapes, "a set of destinations")
    }

    /** No two systems draw a sheet alike, on the same reasoning as the strip above. */
    @Test
    fun fr21_no_two_systems_draw_a_sheet_alike() {
        val shapes = DesignSystem.entries.associateWith { system ->
            val theme = resolved(system, dark = false)
            val style = theme.rules.sheet(WindowSizeClass.Compact, theme)
            listOf(
                style.edge,
                style.elevation,
                style.handle != null,
                style.widthFraction,
                style.heightFraction,
                style.borderWidth,
            )
        }
        assertDistinct(shapes, "a sheet")
    }

    /**
     * No two systems say something transient the same way.
     *
     * Where it appears and how long it stays are the two parts of this that a reader
     * actually experiences, and they are the parts a system that answered nothing was
     * taking from somebody else.
     */
    @Test
    fun fr21_no_two_systems_say_something_transient_alike() {
        val shapes = DesignSystem.entries.associateWith { system ->
            val theme = resolved(system, dark = false)
            val style = theme.rules.message(theme)
            listOf(
                style.placement,
                style.elevation,
                style.shortMillis,
                style.longMillis,
                style.borderWidth,
            )
        }
        assertDistinct(shapes, "a transient message")
    }

    /**
     * Every system frames a text field, and no two frame it the same way.
     *
     * A field drawn with no fill, no line, no inner room and no focus mark is not a plain
     * field, it is an editing area with nothing around it, and it looked identical under
     * all six. The frame is one of the places these languages diverge most: a filled box
     * with a rule under it, a rounded fill, a box whose bottom line goes accent, a macOS
     * focus ring.
     */
    @Test
    fun fr14_7_every_system_frames_a_field_and_no_two_frame_it_alike() {
        val shapes = DesignSystem.entries.associateWith { system ->
            val theme = resolved(system, dark = false)
            val field = theme.rules.field(theme)
            assertTrue(
                field.container != Color.Transparent ||
                    field.borderWidth.value > 0f ||
                    field.underline != null,
                "$system draws a field with no frame at all",
            )
            assertTrue(
                field.container != field.containerFocused ||
                    field.border != field.borderFocused ||
                    field.borderWidth != field.borderWidthFocused ||
                    field.underline?.let { it.color != it.focusedColor || it.width != it.focusedWidth } == true,
                "$system does not change a field's frame when the caret goes into it",
            )
            listOf(
                field.borderWidth,
                field.borderWidthFocused,
                field.underline != null,
                field.shape,
                field.horizontalPadding,
                field.verticalPadding,
                field.minHeight,
            )
        }
        assertDistinct(shapes, "a text field")
    }

    /**
     * A field holding a page of text is not drawn as an ellipse.
     *
     * The Liquid Glass notepad drew its whole editing area as a grey egg with the writing
     * clipped off both sides, and no assertion noticed: the rule asked for that system's
     * widest corner, its widest corner is a stadium, and a stadium on something six
     * hundred dp tall is an ellipse.
     *
     * What is looked at is a point a tenth of the way in from the top left corner. Inside
     * any rounded rectangle whose corner is a corner, that point is on the field. Inside
     * an ellipse it is outside it, and what is drawn there is the page. Every system is
     * checked, because the next one to round a field that far fails the same way.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fr14_7_a_field_holding_a_page_is_not_drawn_as_an_ellipse() {
        DesignSystem.entries.forEach { system ->
            runComposeUiTest {
                setContent {
                    Box(Modifier.size(FIELD_PAGE).background(Color.White)) {
                        DioxusContent(
                            rememberDioxusHost(
                                FakeHostConnection(
                                    listOf(
                                        Mutation.SetTheme(
                                            Theme(system, system, ColorScheme.Light, false),
                                        ),
                                        Mutation.Create(FIELD, WidgetKind.TextField),
                                        Mutation.SetProp(
                                            FIELD,
                                            PropertyKind.Multiline,
                                            PropertyValue.Bool(true),
                                        ),
                                        Mutation.SetModifier(
                                            FIELD,
                                            FILL_WIDTH,
                                            ProtocolModifier.FillMaxWidth,
                                        ),
                                        Mutation.SetModifier(
                                            FIELD,
                                            FILL_HEIGHT,
                                            ProtocolModifier.FillMaxHeight,
                                        ),
                                    ),
                                ),
                            ),
                            Modifier.fillMaxSize(),
                        )
                    }
                }
                waitForIdle()
                val pixels = onRoot().captureToImage().toPixelMap()
                val x = (pixels.width * FIELD_CORNER_FRACTION).toInt()
                val y = (pixels.height * FIELD_CORNER_FRACTION).toInt()
                val middle = pixels[pixels.width / 2, pixels.height / 2].toArgb()
                assertTrue(
                    pixels[x, y].toArgb() == middle,
                    "$system draws a page-tall field with its corner cut away: the point " +
                        "a tenth in from the top left is not on the field, which is what " +
                        "an ellipse looks like",
                )
            }
        }
    }

    /**
     * A field with no Modifier on it at all comes out framed, and differently framed under
     * each system.
     *
     * The rule next door can say whatever it likes; what settles this is whether anything
     * reads it. A page with a field on it that is indistinguishable from the page is what
     * was there before, and it looked the same under all six.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fr14_7_a_bare_field_is_drawn_with_the_frame_its_system_gives_it() {
        val drawn = mutableMapOf<DesignSystem, List<Int>>()
        DesignSystem.entries.forEach { system ->
            runComposeUiTest {
                setContent {
                    Box(Modifier.size(FIELD_SCENE).background(Color.White), Alignment.Center) {
                        DioxusContent(
                            rememberDioxusHost(
                                FakeHostConnection(
                                    listOf(
                                        Mutation.SetTheme(
                                            Theme(system, system, ColorScheme.Light, false),
                                        ),
                                        Mutation.Create(FIELD, WidgetKind.TextField),
                                    ),
                                ),
                            ),
                        )
                    }
                }
                waitForIdle()
                val pixels = onRoot().captureToImage().toPixelMap()
                val colours = mutableListOf<Int>()
                for (y in 0 until pixels.height) {
                    for (x in 0 until pixels.width) {
                        colours += pixels[x, y].toArgb()
                    }
                }
                assertTrue(
                    colours.distinct().size >= 2,
                    "$system draws a field nobody can see: the whole scene is one colour",
                )
                drawn[system] = colours
            }
        }
        val systems = DesignSystem.entries
        for (i in systems.indices) {
            for (j in i + 1 until systems.size) {
                assertTrue(
                    drawn[systems[i]] != drawn[systems[j]],
                    "${systems[i]} and ${systems[j]} draw the same field",
                )
            }
        }
    }

    /**
     * Putting the caret in a field changes the frame around it, in every system.
     *
     * Only the outermost ring of the picture is compared. The caret itself lands in the
     * middle, so a test that looked at the whole field would go green on the caret alone
     * and say nothing about whether the frame moved.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fr14_7_the_frame_changes_when_the_caret_goes_in() {
        DesignSystem.entries.forEach { system ->
            runComposeUiTest {
                setContent {
                    Box(Modifier.size(FIELD_SCENE).background(Color.White), Alignment.Center) {
                        DioxusContent(
                            rememberDioxusHost(
                                FakeHostConnection(
                                    listOf(
                                        Mutation.SetTheme(
                                            Theme(system, system, ColorScheme.Light, false),
                                        ),
                                        Mutation.Create(FIELD, WidgetKind.TextField),
                                    ),
                                ),
                            ),
                        )
                    }
                }
                waitForIdle()
                val resting = edgeOf(onNodeWithTag(nodeTestTag(FIELD)).captureToImage().toPixelMap())
                onNodeWithTag(nodeTestTag(FIELD)).performClick()
                waitForIdle()
                val focused = edgeOf(onNodeWithTag(nodeTestTag(FIELD)).captureToImage().toPixelMap())
                assertTrue(
                    resting != focused,
                    "$system draws the same edge whether or not the caret is in the field",
                )
            }
        }
    }

    private fun assertDistinct(shapes: Map<DesignSystem, List<Any>>, what: String) {
        val systems = shapes.keys.toList()
        for (i in systems.indices) {
            for (j in i + 1 until systems.size) {
                assertTrue(
                    shapes[systems[i]] != shapes[systems[j]],
                    "${systems[i]} and ${systems[j]} give $what the same shape: " +
                        "${shapes[systems[i]]}",
                )
            }
        }
    }
}

/** The node the border test draws. */
private const val SWITCH = 1

/** The node the field test draws. */
private const val FIELD = 1

/** A scene big enough to hold a field and show the page around it. */
private val FIELD_SCENE = DpSize(220.dp, 90.dp)

/** A field the size of a note taking up a phone screen. The height is what matters. */
private val FIELD_PAGE = DpSize(390.dp, 620.dp)

/** How far in from the corner the ellipse test looks. */
private const val FIELD_CORNER_FRACTION = 0.1f

/** The two modifier slots the page-tall field fills itself with. */
private const val FILL_WIDTH = 1
private const val FILL_HEIGHT = 2

/**
 * How light a switch handle has to be to read as the white knob those desktops draw.
 *
 * Far enough above the middle that a mid grey fails, low enough that a warm off white
 * passes.
 */
private const val LIGHT_HANDLE_LUMINANCE = 0.55f

/** How far apart two colours have to be before the edge between them is worth drawing. */
private const val VISIBLE_APART = 24f

/**
 * How far a rule has to be from what it is drawn on.
 *
 * Lower than [VISIBLE_APART], because a hairline separating two rows is meant to be quiet:
 * it only has to be there.
 */
private const val VISIBLE_RULE = 12f

/**
 * How dark a pixel of an unlined control is allowed to get.
 *
 * Above the darkest colour such a control is made of, and far below the near black a
 * one pixel line comes out as.
 */
private const val UNLINED_LUMINANCE = 0.5f

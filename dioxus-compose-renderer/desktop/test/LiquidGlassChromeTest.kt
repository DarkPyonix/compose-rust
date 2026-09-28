package dioxus.compose.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dioxus.compose.design.ContainerRole
import dioxus.compose.design.HostPlatform
import dioxus.compose.design.LiquidGlass
import dioxus.compose.design.NavigationPresentation
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.design.SurfaceMaterial
import dioxus.compose.design.compositeOver
import dioxus.compose.design.resolveTheme
import dioxus.compose.foundation.floatingGroups
import dioxus.compose.foundation.navigationStripTestTag
import dioxus.compose.protocol.ButtonVariant
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.IconRole
import dioxus.compose.protocol.MaterialRole
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.ShapeRole
import dioxus.compose.protocol.SlotRole
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.protocol.WindowSizeClass
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.WindowCaption
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.NodeTable
import dioxus.compose.ui.node.nodeTestTag
import dioxus.compose.ui.platform.FrameRequestSource
import dioxus.compose.ui.platform.LocalFrameRequests
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What makes a Liquid Glass window look like one rather than like the flat language beside
 * it: surfaces that take the colour of what is behind them, chrome that floats rather than
 * running in strips along the window's edges, and a window whose own material is the
 * desktop wherever the platform can put it there.
 *
 * The values are asserted directly where they can be. The geometry is measured in a
 * composition at a pinned density, so a dp in the assertion is a pixel on the screen.
 */
@OptIn(ExperimentalTestApi::class)
class LiquidGlassChromeTest {
    private val frames = FrameRequestSource()

    private fun glass(
        sizeClass: WindowSizeClass,
        dark: Boolean = false,
        windowBackdrop: Boolean = false,
    ): ResolvedTheme = resolveTheme(
        theme = Theme(
            DesignSystem.LiquidGlass,
            DesignSystem.LiquidGlass,
            if (dark) ColorScheme.Dark else ColorScheme.Light,
            adaptive = false,
        ),
        platform = HostPlatform.MacOs,
        systemDark = dark,
        sizeClass = sizeClass,
        windowBackdrop = windowBackdrop,
    )

    private fun assertNear(expected: Dp, actual: Dp, what: String) {
        assertTrue(
            abs(expected.value - actual.value) <= 1f,
            "$what should be about ${expected.value}dp but was ${actual.value}dp",
        )
    }

    /** How far apart two colours are on their furthest channel, in levels out of 255. */
    private fun levels(a: Color, b: Color): Int = maxOf(
        abs(a.red - b.red),
        abs(a.green - b.green),
        abs(a.blue - b.blue),
    ).let { (it * 255f).toInt() }

    // The values.

    /**
     * A glass surface is coloured by what is behind it.
     *
     * That is the property the flat language does not have, and the one that was missing:
     * at 0.85 of a grey tint, a composer over a blue page and a composer over a white one
     * came out six levels apart, which is the same grey twice. At least a quarter of what
     * is behind a surface that carries text reaches the eye, and more of it through a
     * clear one.
     */
    @Test
    fun fr14_1_2_glass_takes_the_colour_of_what_is_behind_it() {
        for (dark in listOf(false, true)) {
            val theme = glass(WindowSizeClass.Compact, dark = dark)
            val regular = theme.rules.material(MaterialRole.Regular, theme) as SurfaceMaterial.Glass
            val thin = theme.rules.material(MaterialRole.Thin, theme) as SurfaceMaterial.Glass
            assertTrue(regular.tintAlpha <= 0.75f, "regular glass is ${regular.tintAlpha} opaque (dark=$dark)")
            assertTrue(thin.tintAlpha <= 0.4f, "thin glass is ${thin.tintAlpha} opaque (dark=$dark)")
        }

        val theme = glass(WindowSizeClass.Compact)
        val regular = theme.rules.material(MaterialRole.Regular, theme) as SurfaceMaterial.Glass
        val fill = regular.tint.copy(alpha = regular.tintAlpha)
        val overPage = compositeOver(fill, theme.color(ColorRole.Background))
        val overBlue = compositeOver(fill, theme.color(ColorRole.PrimaryContainer))
        assertTrue(
            levels(overPage, overBlue) >= 10,
            "the same glass over the page and over the blue wash is only " +
                "${levels(overPage, overBlue)} levels apart, so it does not take the colour " +
                "of what is behind it",
        )
    }

    /**
     * Over a window the platform has backed with its own material, chrome is mostly that
     * material. The ordinary recipe laid seventy percent of a tint over the desktop the
     * platform had been asked to show.
     */
    @Test
    fun fr29_chrome_on_a_window_that_shows_the_desktop_is_mostly_the_desktop() {
        for (dark in listOf(false, true)) {
            val backed = glass(WindowSizeClass.Expanded, dark = dark, windowBackdrop = true)
            val chrome = backed.rules.material(MaterialRole.Chrome, backed) as SurfaceMaterial.Glass
            assertTrue(
                chrome.tintAlpha <= 0.35f,
                "chrome over the desktop covers ${chrome.tintAlpha} of it (dark=$dark)",
            )
            assertEquals(1f, chrome.fallback.alpha, "the reduced transparency path is still opaque")

            val opaque = glass(WindowSizeClass.Expanded, dark = dark, windowBackdrop = false)
            val ordinary = opaque.rules.material(MaterialRole.Chrome, opaque) as SurfaceMaterial.Glass
            assertTrue(
                ordinary.tintAlpha > chrome.tintAlpha,
                "chrome over the application's own page has to carry more of its own tint",
            )
        }
    }

    /**
     * The sidebar sits straight on the window's material and the page is painted beside
     * it, translucent, so the wallpaper is strongest under the sidebar and still reaches
     * the page. Where the window is opaque the sidebar is glass over the page instead.
     */
    @Test
    fun fr29_the_sidebar_sits_on_the_desktop_and_the_page_lets_some_of_it_through() {
        val backed = glass(WindowSizeClass.Expanded, windowBackdrop = true)
        val onWindow = backed.rules.navigation(WindowSizeClass.Expanded, backed)
        val strip = onWindow.stripMaterial as SurfaceMaterial.Glass
        assertTrue(strip.tintAlpha <= LiquidGlass.WINDOW_TINT_ALPHA_LIGHT + 0.001f)
        assertFalse(onWindow.pageBehindStrip, "the page is painted under a sidebar that should show the desktop")
        assertTrue(onWindow.pageGradientStart!!.alpha < 1f, "the top of the page hides the desktop")
        assertTrue(onWindow.pageGradientEnd!!.alpha < 1f, "the foot of the page hides the desktop")

        val opaque = glass(WindowSizeClass.Expanded, windowBackdrop = false)
        val overPage = opaque.rules.navigation(WindowSizeClass.Expanded, opaque)
        assertTrue(overPage.pageBehindStrip, "a sidebar over an opaque window has only the page to be glass over")
        assertEquals(1f, overPage.pageGradientStart!!.alpha)
        assertEquals(1f, overPage.pageGradientEnd!!.alpha)
    }

    /**
     * A narrow window puts the sidebar away and leaves the button that brings it back.
     *
     * The state FR-21.2 did not have. Notes, Mail, Finder and the application the chat
     * sample is drawn from all do this, and none of them shows a column of icons with the
     * words taken off instead. A sidebar that cannot be put away is not this platform's
     * sidebar.
     *
     * Compact is where it happens, because that is the width at which a sidebar and a page
     * cannot both be read. Above it the sidebar stays, with its labels, at every width.
     */
    @Test
    fun fr21_2_1_a_narrow_window_puts_the_destinations_away() {
        val phone = glass(WindowSizeClass.Compact)
        val put = phone.rules.navigation(WindowSizeClass.Compact, phone)
        assertEquals(
            NavigationPresentation.PutAway,
            put.presentation,
            "a phone width still draws a strip of destinations, so the page does not have " +
                "the window",
        )
        for (sizeClass in listOf(WindowSizeClass.Medium, WindowSizeClass.Expanded)) {
            val theme = glass(sizeClass)
            assertEquals(
                NavigationPresentation.Drawer,
                theme.rules.navigation(sizeClass, theme).presentation,
                "$sizeClass does not keep its sidebar",
            )
        }

        // The other platform this language is drawn on answers the same width differently,
        // and has to: a phone has its own tab bar and the destinations are handed to it
        // rather than put away behind a button nobody on a phone would look for.
        val onIos = resolveTheme(
            theme = Theme(
                DesignSystem.LiquidGlass,
                DesignSystem.LiquidGlass,
                ColorScheme.Light,
                adaptive = false,
            ),
            platform = HostPlatform.Ios,
            systemDark = false,
            sizeClass = WindowSizeClass.Compact,
        )
        assertEquals(
            NavigationPresentation.Bar,
            onIos.rules.navigation(WindowSizeClass.Compact, onIos).presentation,
            "a phone lost its tab bar, which is the one the platform itself draws",
        )
    }

    /**
     * In a strip down the side, a selected destination is marked by its fill and keeps the
     * colour of the words around it.
     *
     * The Apple references put a rounded fill behind the selected row and leave its words
     * the colour every other row's words are. Blue words with no fill is what a link looks
     * like, and a sidebar of links reads as a list of things to go and fetch rather than as
     * a place you already are.
     *
     * A tab bar is the other way round and stays that way: iOS tints the selected tab and
     * draws no fill at all, because a fill behind one tab of five is a button in a row of
     * labels.
     */
    @Test
    fun fr21_a_selected_destination_in_a_strip_keeps_the_colour_of_the_others() {
        for (sizeClass in WindowSizeClass.entries) {
            val theme = glass(sizeClass)
            val style = theme.rules.navigation(sizeClass, theme)
            if (style.presentation == NavigationPresentation.Bar) {
                assertEquals(
                    0f,
                    style.indicator.alpha,
                    "a tab bar fills its selected tab, which makes one tab a button",
                )
                continue
            }
            assertEquals(
                style.content,
                style.selectedContent,
                "the $sizeClass navigation colours its selected words differently, so the " +
                    "fill is not what says which one you are on",
            )
            assertTrue(
                style.indicator.alpha > 0f,
                "nothing fills the selected destination, so with the words left alone " +
                    "there is no mark on it at all",
            )
        }
    }

    /**
     * A bar on a phone and a sidebar from a tablet up, and neither is a strip on the
     * window's edge.
     *
     * No rail. A column of icons without their labels is Material's answer to a medium
     * window and it is not Apple's: Notes, Mail and Finder keep a sidebar with its labels
     * at every width a Mac window can be dragged to, and the one thing they do when there
     * is no room is take it away entirely behind a button. None of the three shows icons
     * with the words removed. A rail at 780dp was this project's own invention in an
     * Apple language.
     */
    @Test
    fun fr21_liquid_glass_floats_its_navigation_at_every_width() {
        val expected = mapOf(
            WindowSizeClass.Compact to NavigationPresentation.PutAway,
            WindowSizeClass.Medium to NavigationPresentation.Drawer,
            WindowSizeClass.Expanded to NavigationPresentation.Drawer,
        )
        for ((sizeClass, presentation) in expected) {
            val theme = glass(sizeClass)
            val style = theme.rules.navigation(sizeClass, theme)
            assertEquals(presentation, style.presentation)
            assertTrue(style.floatingInset > 0.dp, "the $presentation is attached to the window's edge")
            assertTrue(style.stripMaterial is SurfaceMaterial.Glass, "the $presentation is not glass")
            assertTrue(
                style.carriesCaption,
                "a strip down the side runs to the top of the window and carries its buttons",
            )
        }

        // And the flat systems keep their strips, exactly as they were.
        for (system in listOf(DesignSystem.Material3, DesignSystem.Fluent, DesignSystem.Cupertino)) {
            val theme = resolveTheme(
                theme = Theme(system, system, ColorScheme.Light, adaptive = false),
                platform = HostPlatform.Unknown,
                systemDark = false,
                sizeClass = WindowSizeClass.Expanded,
            )
            val style = theme.rules.navigation(WindowSizeClass.Expanded, theme)
            assertEquals(0.dp, style.floatingInset, "$system")
            assertEquals(null, style.stripMaterial, "$system")
            assertFalse(style.carriesCaption, "$system")
        }
    }

    /** A toolbar here is floating capsules and no strip, at every width. */
    @Test
    fun fr14_1_4_the_top_bar_floats() {
        for (sizeClass in WindowSizeClass.entries) {
            val theme = glass(sizeClass)
            val bar = theme.rules.container(ContainerRole.TopAppBar, theme)
            assertTrue(bar.floats, "the top bar is a strip at $sizeClass")
            assertTrue(bar.material is SurfaceMaterial.Glass, "its capsules are glass at $sizeClass")
        }
        for (system in listOf(DesignSystem.Material3, DesignSystem.Fluent, DesignSystem.Cupertino)) {
            val theme = resolveTheme(
                theme = Theme(system, system, ColorScheme.Light, adaptive = false),
                platform = HostPlatform.Unknown,
                systemDark = false,
            )
            assertFalse(theme.rules.container(ContainerRole.TopAppBar, theme).floats, "$system")
        }
    }

    /**
     * The actions next to each other share one capsule, and a filled button stands alone
     * because it is a capsule already.
     */
    @Test
    fun fr14_1_4_neighbouring_actions_share_a_capsule() {
        val table = NodeTable()
        listOf(
            Mutation.Create(1, WidgetKind.TopAppBar),
            Mutation.Create(2, WidgetKind.Spacer),
            Mutation.Insert(1, 2, 0),
        ).forEach(table::apply)
        textButton(table, 3, parent = 1, index = 1, IconRole.Add)
        textButton(table, 4, parent = 1, index = 2, IconRole.More)
        listOf(
            Mutation.Create(5, WidgetKind.Button),
            Mutation.SetProp(5, PropertyKind.Text, PropertyValue.Text("Done")),
            Mutation.SetProp(
                5,
                PropertyKind.Variant,
                PropertyValue.Integer(ButtonVariant.Filled.ordinal + 1L),
            ),
            Mutation.Insert(1, 5, 3),
        ).forEach(table::apply)

        assertEquals(
            listOf(listOf(2), listOf(3, 4), listOf(5)),
            floatingGroups(table.node(1)!!.children, table),
        )
    }

    // The geometry.

    /**
     * The sidebar is a panel held eight points in from the window's leading edge, top and
     * bottom, not a column attached to the edge.
     */
    @Test
    fun fr21_the_sidebar_floats_inside_the_window() = runDesktopComposeUiTest(1200, 800) {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(navigation())),
                    Modifier.requiredSize(1200.dp, 800.dp),
                )
            }
        }
        waitForIdle()
        val strip = onNodeWithTag(navigationStripTestTag(NAVIGATION)).getBoundsInRoot()
        assertNear(8.dp, strip.left, "the sidebar's leading edge")
        assertNear(8.dp, strip.top, "the sidebar's top")
        assertNear(792.dp, strip.bottom, "the sidebar's foot")
        // Measured off the application the chat sample is drawn from. It was 260 here, and
        // beside the reference the extra thirty read as a generic application menu rather
        // than that product's dense one.
        assertNear(230.dp, strip.right - strip.left, "the sidebar's width")
    }

    /**
     * On a phone the destinations are not on the screen until they are asked for.
     *
     * This used to draw a capsule along the bottom, which is a tab bar, which is for a set
     * of places an application switches between. A list of conversations or of folders is
     * not that, and the platform's answer for a list with no room is to take it off the
     * screen. So the strip is absent until the button brings it out, and the page has the
     * whole window in the meantime.
     */
    @Test
    fun fr21_2_1_a_phone_has_no_strip_until_it_is_asked_for() = runDesktopComposeUiTest(400, 800) {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(navigation())),
                    Modifier.requiredSize(400.dp, 800.dp),
                )
            }
        }
        waitForIdle()
        onNodeWithTag(navigationStripTestTag(NAVIGATION)).assertDoesNotExist()
    }

    /**
     * On a desktop the sidebar runs to the top of the window and the window buttons sit
     * on it, and the top bar's actions float beside it on the same line as the buttons.
     * There is no band across the top of the whole window.
     */
    @Test
    fun fr19_2_the_sidebar_carries_the_window_buttons_and_the_actions_share_their_line() =
        runDesktopComposeUiTest(1200, 800) {
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    DioxusContent(
                        rememberDioxusHost(FakeHostConnection(frame())),
                        Modifier.requiredSize(1200.dp, 800.dp),
                        caption = WindowCaption(height = 28.dp, buttonsWidth = 78.dp),
                    )
                }
            }
            waitForIdle()
            val strip = onNodeWithTag(navigationStripTestTag(NAVIGATION)).getBoundsInRoot()
            assertNear(8.dp, strip.top, "the sidebar should run to the top of the window")
            val first = onNodeWithTag(nodeTestTag(FIRST)).getBoundsInRoot()
            assertTrue(first.top >= 28.dp, "the first destination is under the window buttons at ${first.top}")

            val add = onNodeWithTag(nodeTestTag(ADD)).getBoundsInRoot()
            val more = onNodeWithTag(nodeTestTag(MORE)).getBoundsInRoot()
            assertTrue(add.left >= strip.right, "the actions run over the sidebar")
            assertTrue(more.left > add.left, "the actions are out of order")
            assertTrue(
                add.top < 28.dp,
                "the actions start below the window buttons at ${add.top} rather than sharing their line",
            )
            val page = onNodeWithTag(nodeTestTag(PAGE)).getBoundsInRoot()
            assertTrue(page.left >= strip.right, "the page is under the sidebar")
        }

    /**
     * A bar that opens the tree takes the caption, and a sidebar under it does not take it
     * a second time. Stepping the destinations down by the caption there left a blank band
     * the height of the window buttons at the top of the sidebar, under a bar that had
     * already made room for them.
     */
    @Test
    fun fr19_2_a_sidebar_under_a_bar_does_not_take_the_caption_again() =
        runDesktopComposeUiTest(1200, 800) {
            setContent {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    DioxusContent(
                        rememberDioxusHost(FakeHostConnection(barAboveNavigation())),
                        Modifier.requiredSize(1200.dp, 800.dp),
                        caption = WindowCaption(height = 28.dp, buttonsWidth = 78.dp),
                    )
                }
            }
            waitForIdle()
            val strip = onNodeWithTag(navigationStripTestTag(NAVIGATION)).getBoundsInRoot()
            val first = onNodeWithTag(nodeTestTag(FIRST)).getBoundsInRoot()
            assertTrue(
                first.top - strip.top < 28.dp,
                "the first destination sits ${first.top - strip.top} below the top of a " +
                    "sidebar the bar above already cleared the window buttons for",
            )
        }

    /**
     * A floating bar paints nothing between its capsules: the page there is the page.
     *
     * Dark, because the light page and the light glass are both white and the question
     * could not be told apart.
     */
    @Test
    fun fr14_1_4_a_floating_top_bar_paints_nothing_across_the_window() = runDesktopComposeUiTest(1200, 600) {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(barOverPage(ColorScheme.Dark))),
                    Modifier.requiredSize(1200.dp, 600.dp),
                )
            }
        }
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        assertEquals(
            image.getRGB(100, 400),
            image.getRGB(100, 12),
            "the bar painted a strip across the top of the window",
        )
    }

    /**
     * A field inside a glass bar is part of the bar. The composer is one capsule with the
     * text in it, not a capsule holding a second, greyer one.
     */
    @Test
    fun fr14_1_2_a_field_on_glass_takes_the_glass_as_its_fill() = runDesktopComposeUiTest(400, 300) {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(composer())),
                    Modifier.requiredSize(400.dp, 300.dp),
                )
            }
        }
        waitForIdle()
        val image = onNodeWithTag(nodeTestTag(FIELD)).captureToImage().toAwtImage()
        val pixel = Color(image.getRGB(image.width - 8, image.height / 2))
        val field = Color(0xFFE9E9EB)
        assertTrue(
            levels(pixel, field) >= 10,
            "the field inside the glass composer is still painted its own grey",
        )
    }

    // The batches.

    private fun theme(scheme: ColorScheme = ColorScheme.Light) = Mutation.SetTheme(
        Theme(DesignSystem.LiquidGlass, DesignSystem.LiquidGlass, scheme, adaptive = false),
    )

    private fun destinations(parent: Int): List<Mutation> = listOf(
        Mutation.Create(FIRST, WidgetKind.NavigationItem),
        Mutation.SetProp(FIRST, PropertyKind.Text, PropertyValue.Text("Search")),
        Mutation.SetProp(FIRST, PropertyKind.Icon, PropertyValue.Integer(IconRole.Search.ordinal + 1L)),
        Mutation.Insert(parent, FIRST, 0),
        Mutation.Create(SECOND, WidgetKind.NavigationItem),
        Mutation.SetProp(SECOND, PropertyKind.Text, PropertyValue.Text("New chat")),
        Mutation.SetProp(SECOND, PropertyKind.Icon, PropertyValue.Integer(IconRole.Inbox.ordinal + 1L)),
        Mutation.Insert(parent, SECOND, 1),
    )

    /** A navigation holding its own page. */
    private fun navigation(): List<Mutation> = listOf(
        theme(),
        Mutation.Create(NAVIGATION, WidgetKind.Navigation),
        Mutation.SetModifier(NAVIGATION, 0, ProtocolModifier.FillMaxWidth),
        Mutation.SetModifier(NAVIGATION, 1, ProtocolModifier.FillMaxHeight),
    ) + destinations(NAVIGATION) + listOf(
        Mutation.Create(PAGE, WidgetKind.Text),
        Mutation.SetProp(PAGE, PropertyKind.Text, PropertyValue.Text("the page")),
        Mutation.Insert(NAVIGATION, PAGE, 2),
    )

    /** The chat's frame: two actions in the top slot, the destinations in the bottom one. */
    private fun frame(): List<Mutation> = listOf(
        theme(),
        Mutation.Create(SCAFFOLD, WidgetKind.Scaffold),
        Mutation.SetModifier(SCAFFOLD, 0, ProtocolModifier.Material(MaterialRole.Chrome)),
    ) + slot(TOP_SLOT, SlotRole.TopBar, 0) + listOf(
        Mutation.Create(BAR, WidgetKind.TopAppBar),
        Mutation.SetModifier(BAR, 0, ProtocolModifier.FillMaxWidth),
        Mutation.Insert(TOP_SLOT, BAR, 0),
        Mutation.Create(SPACER, WidgetKind.Spacer),
        Mutation.SetModifier(SPACER, 0, ProtocolModifier.Weight(1f)),
        Mutation.Insert(BAR, SPACER, 0),
    ) + textButtonMutations(ADD, BAR, 1, IconRole.Add) +
        textButtonMutations(MORE, BAR, 2, IconRole.More) +
        slot(BOTTOM_SLOT, SlotRole.BottomBar, 1) + listOf(
            Mutation.Create(NAVIGATION, WidgetKind.Navigation),
            Mutation.Insert(BOTTOM_SLOT, NAVIGATION, 0),
        ) + destinations(NAVIGATION) + slot(CONTENT_SLOT, SlotRole.Content, 2) + listOf(
            Mutation.Create(PAGE, WidgetKind.Text),
            Mutation.SetProp(PAGE, PropertyKind.Text, PropertyValue.Text("the page")),
            Mutation.Insert(CONTENT_SLOT, PAGE, 0),
        )

    /** `Column { TopAppBar; Navigation { destinations, page } }`. */
    private fun barAboveNavigation(): List<Mutation> = listOf(
        theme(),
        Mutation.Create(SCAFFOLD, WidgetKind.Column),
        Mutation.SetModifier(SCAFFOLD, 0, ProtocolModifier.FillMaxWidth),
        Mutation.SetModifier(SCAFFOLD, 1, ProtocolModifier.FillMaxHeight),
        Mutation.Create(BAR, WidgetKind.TopAppBar),
        Mutation.SetModifier(BAR, 0, ProtocolModifier.FillMaxWidth),
        Mutation.Insert(SCAFFOLD, BAR, 0),
        Mutation.Create(NAVIGATION, WidgetKind.Navigation),
        Mutation.SetModifier(NAVIGATION, 0, ProtocolModifier.FillMaxWidth),
        Mutation.SetModifier(NAVIGATION, 1, ProtocolModifier.Weight(1f)),
        Mutation.Insert(SCAFFOLD, NAVIGATION, 1),
    ) + destinations(NAVIGATION) + listOf(
        Mutation.Create(PAGE, WidgetKind.Text),
        Mutation.SetProp(PAGE, PropertyKind.Text, PropertyValue.Text("the page")),
        Mutation.Insert(NAVIGATION, PAGE, 2),
    )

    /** A frame with a bar and a page and no destinations. */
    private fun barOverPage(scheme: ColorScheme): List<Mutation> = listOf(
        theme(scheme),
        Mutation.Create(SCAFFOLD, WidgetKind.Scaffold),
    ) + slot(TOP_SLOT, SlotRole.TopBar, 0) + listOf(
        Mutation.Create(BAR, WidgetKind.TopAppBar),
        Mutation.SetModifier(BAR, 0, ProtocolModifier.FillMaxWidth),
        Mutation.Insert(TOP_SLOT, BAR, 0),
        Mutation.Create(SPACER, WidgetKind.Spacer),
        Mutation.SetModifier(SPACER, 0, ProtocolModifier.Weight(1f)),
        Mutation.Insert(BAR, SPACER, 0),
    ) + textButtonMutations(ADD, BAR, 1, IconRole.Add) +
        textButtonMutations(MORE, BAR, 2, IconRole.More) +
        slot(CONTENT_SLOT, SlotRole.Content, 1) + listOf(
            Mutation.Create(PAGE, WidgetKind.Box),
            Mutation.SetModifier(PAGE, 0, ProtocolModifier.FillMaxWidth),
            Mutation.SetModifier(PAGE, 1, ProtocolModifier.FillMaxHeight),
            Mutation.Insert(CONTENT_SLOT, PAGE, 0),
        )

    /** A glass composer with a field in it. */
    private fun composer(): List<Mutation> = listOf(
        theme(),
        Mutation.Create(COMPOSER, WidgetKind.Row),
        Mutation.SetModifier(COMPOSER, 0, ProtocolModifier.FillMaxWidth),
        Mutation.SetModifier(COMPOSER, 1, ProtocolModifier.ShapeRole(ShapeRole.Full)),
        Mutation.SetModifier(COMPOSER, 2, ProtocolModifier.Material(MaterialRole.Regular)),
        Mutation.SetModifier(COMPOSER, 3, ProtocolModifier.PaddingRole(dioxus.compose.protocol.SpaceRole.Sm)),
        Mutation.Create(FIELD, WidgetKind.TextField),
        Mutation.SetModifier(FIELD, 0, ProtocolModifier.Weight(1f)),
        Mutation.Insert(COMPOSER, FIELD, 0),
    )

    private fun slot(id: Int, role: SlotRole, index: Int): List<Mutation> = listOf(
        Mutation.Create(id, WidgetKind.ScaffoldSlot),
        Mutation.SetProp(id, PropertyKind.Slot, PropertyValue.Integer(role.ordinal + 1L)),
        Mutation.Insert(SCAFFOLD, id, index),
    )

    private fun textButtonMutations(id: Int, parent: Int, index: Int, icon: IconRole) = listOf(
        Mutation.Create(id, WidgetKind.Button),
        Mutation.SetProp(id, PropertyKind.Icon, PropertyValue.Integer(icon.ordinal + 1L)),
        Mutation.SetProp(id, PropertyKind.Variant, PropertyValue.Integer(ButtonVariant.Text.ordinal + 1L)),
        Mutation.Insert(parent, id, index),
    )

    private fun textButton(table: NodeTable, id: Int, parent: Int, index: Int, icon: IconRole) =
        textButtonMutations(id, parent, index, icon).forEach(table::apply)

    private companion object {
        const val NAVIGATION = 1
        const val FIRST = 2
        const val SECOND = 3
        const val PAGE = 4
        const val SCAFFOLD = 10
        const val TOP_SLOT = 11
        const val BOTTOM_SLOT = 12
        const val CONTENT_SLOT = 13
        const val BAR = 14
        const val SPACER = 15
        const val ADD = 16
        const val MORE = 17
        const val COMPOSER = 20
        const val FIELD = 21
    }
}

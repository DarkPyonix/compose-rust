package dioxus.compose.tooling

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dioxus.compose.protocol.ButtonVariant
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.ShapeRole
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.TypeRole
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import java.lang.System

/**
 * A scripted Host that exercises the design primitives under one design system, so the
 * result can be looked at rather than only asserted on.
 *
 * Nothing here is design system aware: the same records produce Material 3, Cupertino or
 * Fluent pixels depending only on the `SetTheme` at the front.
 */
fun designShowcaseHost(theme: Theme): FakeHostConnection {
    val connection = FakeHostConnection(designShowcaseRecords(theme))
    connection.respondWith { HostResponse(result = 1) }
    return connection
}

/**
 * The records the showcase is built from.
 *
 * Separate from [designShowcaseHost] so a test can ask what the showcase contains without
 * rendering it, which is how "every widget appears here" stays a checkable claim rather
 * than something someone has to notice going missing.
 */
fun designShowcaseRecords(theme: Theme): List<Mutation> {
    val records = mutableListOf<Mutation>(Mutation.SetTheme(theme))
    var nextId = 1
    fun id(): Int = nextId++

    fun text(
        parent: Int,
        index: Int,
        value: String,
        role: TypeRole,
        color: ColorRole = ColorRole.OnSurface,
    ) {
        val node = id()
        records += Mutation.Create(node, WidgetKind.Text)
        records += Mutation.SetProp(node, PropertyKind.Text, PropertyValue.Text(value))
        records += Mutation.SetProp(node, PropertyKind.TypeRole, PropertyValue.Integer(role.ordinal + 1L))
        records += Mutation.SetProp(node, PropertyKind.Color, PropertyValue.Integer(roleBits(color)))
        records += Mutation.Insert(parent, node, index)
    }

    val root = id()
    records += Mutation.Create(root, WidgetKind.ScrollColumn)
    records += Mutation.SetModifier(root, 0, ProtocolModifier.FillMaxWidth)
    records += Mutation.SetModifier(root, 1, ProtocolModifier.FillMaxHeight)
    // The window colour rather than the reading surface. A panel is the thing that has to
    // lift off the page, and painting the page with the same role a panel uses hides
    // whichever systems set the two alike.
    records += Mutation.SetModifier(root, 2, ProtocolModifier.Background(Paint.Role(ColorRole.Background)))
    records += Mutation.SetModifier(root, 3, ProtocolModifier.PaddingRole(SpaceRole.Lg))
    records += Mutation.SetProp(root, PropertyKind.SpaceRole, PropertyValue.Integer(SpaceRole.Md.ordinal + 1L))

    var slot = 0
    text(root, slot++, systemName(theme), TypeRole.Display)
    text(root, slot++, "the nine rung type ladder", TypeRole.Headline, ColorRole.OnSurfaceVariant)
    listOf(
        TypeRole.Title to "Title sets a section",
        TypeRole.Subtitle to "Subtitle carries it",
        TypeRole.Body to "Body is the reading size for long copy.",
        TypeRole.BodyStrong to "BodyStrong is the same size with more weight.",
        TypeRole.Label to "LABEL",
        TypeRole.Caption to "Caption is the smallest rung",
        TypeRole.Mono to "mono 0O1lI",
    ).forEach { (role, sample) -> text(root, slot++, sample, role) }

    // The four button variants, side by side.
    val buttons = id()
    records += Mutation.Create(buttons, WidgetKind.Row)
    records += Mutation.SetProp(buttons, PropertyKind.SpaceRole, PropertyValue.Integer(SpaceRole.Sm.ordinal + 1L))
    records += Mutation.Insert(root, buttons, slot++)
    ButtonVariant.entries.forEachIndexed { index, variant ->
        val node = id()
        records += Mutation.Create(node, WidgetKind.Button)
        records += Mutation.SetProp(node, PropertyKind.Text, PropertyValue.Text(variant.name))
        records += Mutation.SetProp(node, PropertyKind.Variant, PropertyValue.Integer(variant.ordinal + 1L))
        records += Mutation.SetProp(node, PropertyKind.OnClick, PropertyValue.Integer(1L))
        records += Mutation.Insert(buttons, node, index)
    }

    // An elevated card: the same dp is a tonal rise, a soft shadow or a layer stroke.
    val card = id()
    records += Mutation.Create(card, WidgetKind.Box)
    records += Mutation.SetModifier(card, 0, ProtocolModifier.FillMaxWidth)
    records += Mutation.SetModifier(card, 1, ProtocolModifier.ShapeRole(ShapeRole.Large))
    records += Mutation.SetModifier(card, 2, ProtocolModifier.Elevation(6f))
    records += Mutation.SetModifier(card, 3, ProtocolModifier.Background(Paint.Role(ColorRole.SurfaceVariant)))
    records += Mutation.SetModifier(card, 4, ProtocolModifier.PaddingRole(SpaceRole.Lg))
    records += Mutation.Insert(root, card, slot++)
    text(card, 0, "Elevation 6 dp, ShapeRole.Large", TypeRole.BodyStrong, ColorRole.OnSurfaceVariant)

    // Weight: 1 to 3 across the row, which only the parent scope can express.
    val weights = id()
    records += Mutation.Create(weights, WidgetKind.Row)
    records += Mutation.SetModifier(weights, 0, ProtocolModifier.FillMaxWidth)
    records += Mutation.SetModifier(weights, 1, ProtocolModifier.Height(48f))
    records += Mutation.SetProp(weights, PropertyKind.SpaceRole, PropertyValue.Integer(SpaceRole.Sm.ordinal + 1L))
    records += Mutation.Insert(root, weights, slot++)
    listOf(1f to ColorRole.Primary, 3f to ColorRole.Secondary).forEachIndexed { index, (weight, color) ->
        val node = id()
        records += Mutation.Create(node, WidgetKind.Box)
        records += Mutation.SetModifier(node, 0, ProtocolModifier.Weight(weight))
        records += Mutation.SetModifier(node, 1, ProtocolModifier.FillMaxHeight)
        records += Mutation.SetModifier(node, 2, ProtocolModifier.ShapeRole(ShapeRole.Small))
        records += Mutation.SetModifier(node, 3, ProtocolModifier.Background(Paint.Role(color)))
        records += Mutation.Insert(weights, node, index)
    }

    // The selection controls, both states side by side, so a design system's own answer to
    // a tick, a dot and a track can be compared against the other two by looking.
    text(root, slot++, "selection controls", TypeRole.Headline, ColorRole.OnSurfaceVariant)
    listOf(
        WidgetKind.Checkbox,
        WidgetKind.RadioButton,
        WidgetKind.Switch,
    ).forEach { widget ->
        val row = id()
        records += Mutation.Create(row, WidgetKind.Row)
        records += Mutation.SetProp(row, PropertyKind.SpaceRole, PropertyValue.Integer(SpaceRole.Md.ordinal + 1L))
        records += Mutation.Insert(root, row, slot++)
        listOf(false, true).forEachIndexed { index, checked ->
            val node = id()
            records += Mutation.Create(node, widget)
            records += Mutation.SetProp(node, PropertyKind.Checked, PropertyValue.Bool(checked))
            // A handler makes it operable, so the showcase can be clicked rather than only
            // looked at. The Host holds the value, so nothing here moves on its own.
            records += Mutation.SetProp(node, PropertyKind.OnValueChange, PropertyValue.Integer(1L))
            records += Mutation.Insert(row, node, index)
        }
    }

    // A slider with stops and one without, which is where a system that marks its stops
    // parts company with one that does not.
    listOf(0L, 4L).forEach { steps ->
        val node = id()
        records += Mutation.Create(node, WidgetKind.Slider)
        records += Mutation.SetProp(node, PropertyKind.Min, PropertyValue.Float(0f))
        records += Mutation.SetProp(node, PropertyKind.Max, PropertyValue.Float(1f))
        records += Mutation.SetProp(node, PropertyKind.Value, PropertyValue.Float(0.4f))
        records += Mutation.SetProp(node, PropertyKind.Steps, PropertyValue.Integer(steps))
        records += Mutation.SetProp(node, PropertyKind.OnValueChange, PropertyValue.Integer(1L))
        records += Mutation.SetModifier(node, 0, ProtocolModifier.FillMaxWidth)
        records += Mutation.Insert(root, node, slot++)
    }

    // The four indicators: bar and ring, each determinate and indeterminate. The two
    // indeterminate ones are the only things in this window that move on their own, and how
    // fast they move is the design system's motion rule.
    fun indicator(parent: Int, index: Int, circular: Boolean, determinate: Boolean) {
        val node = id()
        records += Mutation.Create(node, WidgetKind.ProgressIndicator)
        records += Mutation.SetProp(node, PropertyKind.Circular, PropertyValue.Bool(circular))
        records += Mutation.SetProp(node, PropertyKind.Determinate, PropertyValue.Bool(determinate))
        records += Mutation.SetProp(node, PropertyKind.Value, PropertyValue.Float(0.6f))
        records += Mutation.Insert(parent, node, index)
    }

    // The bars take the width they are given, so they get a row each rather than sharing
    // one and coming out too short to read.
    listOf(true, false).forEach { determinate ->
        indicator(root, slot++, circular = false, determinate = determinate)
    }

    // The rings are sized by the design system, so they sit side by side.
    val rings = id()
    records += Mutation.Create(rings, WidgetKind.Row)
    records += Mutation.SetProp(rings, PropertyKind.SpaceRole, PropertyValue.Integer(SpaceRole.Lg.ordinal + 1L))
    records += Mutation.Insert(root, rings, slot++)
    listOf(true, false).forEachIndexed { index, determinate ->
        indicator(rings, index, circular = true, determinate = determinate)
    }

    // A horizontal rule, then a vertical one standing between two blocks, since a divider
    // only shows its axis next to something.
    val rule = id()
    records += Mutation.Create(rule, WidgetKind.Divider)
    records += Mutation.Insert(root, rule, slot++)

    val split = id()
    records += Mutation.Create(split, WidgetKind.Row)
    records += Mutation.SetModifier(split, 0, ProtocolModifier.FillMaxWidth)
    records += Mutation.SetModifier(split, 1, ProtocolModifier.Height(32f))
    records += Mutation.SetProp(split, PropertyKind.SpaceRole, PropertyValue.Integer(SpaceRole.Md.ordinal + 1L))
    records += Mutation.Insert(root, split, slot++)
    text(split, 0, "left", TypeRole.Body)
    val standing = id()
    records += Mutation.Create(standing, WidgetKind.Divider)
    records += Mutation.SetProp(standing, PropertyKind.Vertical, PropertyValue.Bool(true))
    records += Mutation.Insert(split, standing, 1)
    text(split, 2, "right", TypeRole.Body)

    // A field, with nothing on it but the width. The frame around it is the design
    // system's: the corner, the fill, the line and the room inside all come from the same
    // place the button's container does, so a hand drawn border here would be a second
    // frame around the first.
    val field = id()
    records += Mutation.Create(field, WidgetKind.TextField)
    records += Mutation.SetProp(field, PropertyKind.Placeholder, PropertyValue.Text("type here"))
    records += Mutation.SetModifier(field, 0, ProtocolModifier.FillMaxWidth)
    records += Mutation.Insert(root, field, slot++)

    // The containers. A bar, a card, a panel of rows and a tab strip are where the six
    // systems disagree about the things a screen is actually made of: whether a layer is
    // marked by a line, by a tone or by a shadow, and what a selected tab looks like. None
    // of it was in this window before, so none of it had been looked at.
    text(root, slot++, "containers", TypeRole.Headline, ColorRole.OnSurfaceVariant)

    val bar = id()
    records += Mutation.Create(bar, WidgetKind.TopAppBar)
    records += Mutation.SetModifier(bar, 0, ProtocolModifier.FillMaxWidth)
    records += Mutation.Insert(root, bar, slot++)
    text(bar, 0, "Inbox", TypeRole.Title)

    val grouped = id()
    records += Mutation.Create(grouped, WidgetKind.Card)
    records += Mutation.SetModifier(grouped, 0, ProtocolModifier.FillMaxWidth)
    records += Mutation.Insert(root, grouped, slot++)
    text(grouped, 0, "Card", TypeRole.BodyStrong)
    text(grouped, 1, "A grouped box, raised however this system raises one.", TypeRole.Body)

    // A panel of rows, ruled between: the shape almost every list on a real screen has,
    // and where a divider's thickness and inset finally mean something.
    val panel = id()
    records += Mutation.Create(panel, WidgetKind.Surface)
    records += Mutation.SetModifier(panel, 0, ProtocolModifier.FillMaxWidth)
    records += Mutation.Insert(root, panel, slot++)
    var row = 0
    listOf("First row", "Second row", "Third row").forEachIndexed { index, line ->
        if (index > 0) {
            val rule = id()
            records += Mutation.Create(rule, WidgetKind.Divider)
            records += Mutation.Insert(panel, rule, row++)
        }
        text(panel, row++, line, TypeRole.Body)
    }

    val tabs = id()
    records += Mutation.Create(tabs, WidgetKind.Tabs)
    records += Mutation.SetModifier(tabs, 0, ProtocolModifier.FillMaxWidth)
    records += Mutation.SetProp(tabs, PropertyKind.SelectedIndex, PropertyValue.Integer(1))
    records += Mutation.Insert(root, tabs, slot++)
    listOf("All", "Unread", "Flagged").forEachIndexed { index, label ->
        val tab = id()
        records += Mutation.Create(tab, WidgetKind.Text)
        records += Mutation.SetProp(tab, PropertyKind.Text, PropertyValue.Text(label))
        records += Mutation.SetProp(tab, PropertyKind.OnClick, PropertyValue.Integer(1L))
        records += Mutation.Insert(tabs, tab, index)
    }

    return records
}

private fun roleBits(role: ColorRole): Long = (1L shl 32) or (role.ordinal + 1L)

private fun systemName(theme: Theme): String = when (theme.designSystem) {
    DesignSystem.Material3 -> "Material 3 Expressive"
    DesignSystem.Cupertino -> "Cupertino"
    DesignSystem.Fluent -> "WinUI Fluent 2"
    DesignSystem.Gnome -> "GNOME 50 Adwaita"
    DesignSystem.Breeze -> "KDE Breeze"
    DesignSystem.Deepin -> "Deepin"
    DesignSystem.LiquidGlass -> "Liquid Glass"
}

/**
 * Opens the showcase for one design system.
 *
 * `DXC_DESIGN_SYSTEM` is `material3`, `cupertino`, `liquidglass`, `fluent`, `gnome`,
 * `breeze` or `deepin`, `DXC_COLOR_SCHEME` is `light`, `dark` or `system`, and
 * `DXC_ADAPTIVE=1` makes the Host send an adaptive theme with the chosen system as the
 * mandatory fallback.
 */
fun main() = application {
    val system = when (System.getenv("DXC_DESIGN_SYSTEM")?.lowercase()) {
        "hig", "apple", "applehig", "cupertino" -> DesignSystem.Cupertino
        "fluent", "winui" -> DesignSystem.Fluent
        "gnome", "adwaita" -> DesignSystem.Gnome
        "breeze", "kde", "plasma" -> DesignSystem.Breeze
        "deepin", "dtk" -> DesignSystem.Deepin
        "liquidglass", "liquid-glass", "glass" -> DesignSystem.LiquidGlass
        else -> DesignSystem.Material3
    }
    val scheme = when (System.getenv("DXC_COLOR_SCHEME")?.lowercase()) {
        "dark" -> ColorScheme.Dark
        "system", "follow" -> ColorScheme.FollowSystem
        else -> ColorScheme.Light
    }
    val adaptive = System.getenv("DXC_ADAPTIVE") == "1"
    val theme = Theme(system, system, scheme, adaptive)
    Window(onCloseRequest = ::exitApplication, title = "dioxus-compose design showcase") {
        val connection = remember { designShowcaseHost(theme) }
        DioxusContent(rememberDioxusHost(connection))
    }
}

/**
 * A screen with one overlay standing open on it.
 *
 * Separate from the showcase next door because a dialog and a menu are drawn over the
 * window rather than in it: put either one in that scroll and it covers everything under
 * it. They need their own picture, and until they had one they were the part of these six
 * systems nobody had ever looked at.
 */
fun overlayShowcaseHost(theme: Theme, dialog: Boolean): FakeHostConnection {
    val connection = FakeHostConnection(overlayShowcaseRecords(theme, dialog))
    connection.respondWith { HostResponse(result = 1) }
    return connection
}

/** The records [overlayShowcaseHost] is built from. */
fun overlayShowcaseRecords(theme: Theme, dialog: Boolean): List<Mutation> {
    val records = mutableListOf<Mutation>(Mutation.SetTheme(theme))
    var nextId = 1
    fun id(): Int = nextId++

    fun text(parent: Int, index: Int, value: String, role: TypeRole) {
        val node = id()
        records += Mutation.Create(node, WidgetKind.Text)
        records += Mutation.SetProp(node, PropertyKind.Text, PropertyValue.Text(value))
        records += Mutation.SetProp(node, PropertyKind.TypeRole, PropertyValue.Integer(role.ordinal + 1L))
        records += Mutation.SetProp(
            node,
            PropertyKind.Color,
            PropertyValue.Integer(roleBits(ColorRole.OnSurface)),
        )
        records += Mutation.Insert(parent, node, index)
    }

    // The page underneath, so the scrim has something to dim and the overlay has something
    // to be raised off.
    val root = id()
    records += Mutation.Create(root, WidgetKind.Column)
    records += Mutation.SetModifier(root, 0, ProtocolModifier.FillMaxWidth)
    records += Mutation.SetModifier(root, 1, ProtocolModifier.FillMaxHeight)
    records += Mutation.SetModifier(root, 2, ProtocolModifier.Background(Paint.Role(ColorRole.Background)))
    records += Mutation.SetModifier(root, 3, ProtocolModifier.PaddingRole(SpaceRole.Lg))
    records += Mutation.SetProp(root, PropertyKind.SpaceRole, PropertyValue.Integer(SpaceRole.Md.ordinal + 1L))
    text(root, 0, systemName(theme), TypeRole.Title)
    text(root, 1, "The page the overlay is drawn over.", TypeRole.Body)

    if (dialog) {
        val modal = id()
        records += Mutation.Create(modal, WidgetKind.Dialog)
        records += Mutation.SetProp(modal, PropertyKind.Open, PropertyValue.Bool(true))
        records += Mutation.Insert(root, modal, 2)
        text(modal, 0, "Discard changes?", TypeRole.Subtitle)
        text(modal, 1, "The note has not been saved.", TypeRole.Body)
        val actions = id()
        records += Mutation.Create(actions, WidgetKind.Row)
        records += Mutation.SetProp(actions, PropertyKind.SpaceRole, PropertyValue.Integer(SpaceRole.Sm.ordinal + 1L))
        records += Mutation.Insert(modal, actions, 2)
        listOf("Cancel" to ButtonVariant.Text, "Discard" to ButtonVariant.Filled)
            .forEachIndexed { index, (label, variant) ->
                val button = id()
                records += Mutation.Create(button, WidgetKind.Button)
                records += Mutation.SetProp(button, PropertyKind.Text, PropertyValue.Text(label))
                records += Mutation.SetProp(button, PropertyKind.Variant, PropertyValue.Integer(variant.ordinal + 1L))
                records += Mutation.SetProp(button, PropertyKind.OnClick, PropertyValue.Integer(1L))
                records += Mutation.Insert(actions, button, index)
            }
        return records
    }

    val menu = id()
    records += Mutation.Create(menu, WidgetKind.Menu)
    records += Mutation.SetProp(menu, PropertyKind.Open, PropertyValue.Bool(true))
    records += Mutation.Insert(root, menu, 2)
    // Child 0 is the anchor the popup hangs off; the rest are the entries.
    val anchor = id()
    records += Mutation.Create(anchor, WidgetKind.Button)
    records += Mutation.SetProp(anchor, PropertyKind.Text, PropertyValue.Text("Options"))
    records += Mutation.SetProp(anchor, PropertyKind.Variant, PropertyValue.Integer(ButtonVariant.Tonal.ordinal + 1L))
    records += Mutation.SetProp(anchor, PropertyKind.OnClick, PropertyValue.Integer(1L))
    records += Mutation.Insert(menu, anchor, 0)
    // Plain text rather than flat buttons. What this picture is for is the popup itself:
    // its corner, its line, how far it is raised and how much room it gives a row. Text
    // buttons would paint every entry in whatever colour each system gives a flat button,
    // and on one of them that is the accent, so the panel would be the hardest thing in
    // the picture to see.
    listOf("Rename", "Duplicate", "Delete").forEachIndexed { index, label ->
        val entry = id()
        records += Mutation.Create(entry, WidgetKind.Text)
        records += Mutation.SetProp(entry, PropertyKind.Text, PropertyValue.Text(label))
        records += Mutation.SetProp(
            entry,
            PropertyKind.TypeRole,
            PropertyValue.Integer(TypeRole.Body.ordinal + 1L),
        )
        records += Mutation.SetProp(
            entry,
            PropertyKind.Color,
            PropertyValue.Integer(roleBits(ColorRole.OnSurface)),
        )
        records += Mutation.SetModifier(entry, 0, ProtocolModifier.PaddingRole(SpaceRole.Sm))
        records += Mutation.Insert(menu, entry, index + 1)
    }
    return records
}

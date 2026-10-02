package dioxus.compose.tooling

import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.IconRole
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.MessageDuration
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.TypeRole
import dioxus.compose.protocol.WidgetKind

/**
 * A scripted Host with one navigation, three destinations and a screen behind them, plus
 * a message already said.
 *
 * It is the todo sample's shape, written as records, so the three presentations can be
 * screenshotted at three widths without building the Rust side. Nothing here is width
 * aware: the same batch produces the bar, the rail and the drawer.
 */
fun navigationShowcaseHost(theme: Theme, message: Boolean = true): FakeHostConnection {
    val records = mutableListOf<Mutation>(Mutation.SetTheme(theme))
    var nextId = 1
    fun id(): Int = nextId++

    fun text(
        parent: Int,
        index: Int,
        value: String,
        role: TypeRole,
        color: ColorRole = ColorRole.OnSurface,
        weight: Float? = null,
    ): Int {
        val node = id()
        records += Mutation.Create(node, WidgetKind.Text)
        records += Mutation.SetProp(node, PropertyKind.Text, PropertyValue.Text(value))
        records += Mutation.SetProp(
            node,
            PropertyKind.TypeRole,
            PropertyValue.Integer(role.ordinal + 1L),
        )
        records += Mutation.SetProp(node, PropertyKind.Color, PropertyValue.Integer(colorRoleBits(color)))
        if (weight != null) {
            records += Mutation.SetModifier(node, 0, ProtocolModifier.Weight(weight))
        }
        records += Mutation.Insert(parent, node, index)
        return node
    }

    val navigation = id()
    records += Mutation.Create(navigation, WidgetKind.Navigation)
    records += Mutation.SetModifier(navigation, 1, ProtocolModifier.FillMaxWidth)
    records += Mutation.SetModifier(navigation, 2, ProtocolModifier.FillMaxHeight)
    records += Mutation.SetProp(navigation, PropertyKind.SelectedIndex, PropertyValue.Integer(0))

    listOf(
        "All" to IconRole.List,
        "Active" to IconRole.Inbox,
        "Done" to IconRole.Check,
    ).forEachIndexed { index, (label, icon) ->
        val destination = id()
        records += Mutation.Create(destination, WidgetKind.NavigationItem)
        records += Mutation.SetProp(destination, PropertyKind.Text, PropertyValue.Text(label))
        records += Mutation.SetProp(
            destination,
            PropertyKind.Icon,
            PropertyValue.Integer(icon.ordinal + 1L),
        )
        records += Mutation.SetProp(destination, PropertyKind.OnClick, PropertyValue.Integer(1L))
        records += Mutation.Insert(navigation, destination, index)
    }

    // The screen the destinations lead to: a bar, then a list of tasks on a panel.
    val screen = id()
    records += Mutation.Create(screen, WidgetKind.Column)
    records += Mutation.SetModifier(screen, 1, ProtocolModifier.FillMaxWidth)
    records += Mutation.SetModifier(screen, 2, ProtocolModifier.FillMaxHeight)
    records += Mutation.Insert(navigation, screen, 3)

    val bar = id()
    records += Mutation.Create(bar, WidgetKind.TopAppBar)
    records += Mutation.SetModifier(bar, 1, ProtocolModifier.FillMaxWidth)
    records += Mutation.Insert(screen, bar, 0)
    text(bar, 0, "Tasks", TypeRole.Title, weight = 1f)
    text(bar, 1, "2 of 4 remaining", TypeRole.Label, ColorRole.OnSurfaceVariant)

    val page = id()
    records += Mutation.Create(page, WidgetKind.Column)
    records += Mutation.SetModifier(page, 1, ProtocolModifier.FillMaxWidth)
    records += Mutation.SetModifier(page, 10, ProtocolModifier.PaddingRole(SpaceRole.Lg))
    records += Mutation.SetProp(page, PropertyKind.SpaceRole, PropertyValue.Integer(SpaceRole.Md.ordinal + 1L))
    records += Mutation.Insert(screen, page, 1)

    val panel = id()
    records += Mutation.Create(panel, WidgetKind.Surface)
    records += Mutation.SetModifier(panel, 1, ProtocolModifier.FillMaxWidth)
    records += Mutation.Insert(page, panel, 0)
    listOf(
        "☑  Buy milk",
        "☐  Write the release notes",
        "☐  Book the flights",
        "☑  Renew the domain",
    ).forEachIndexed { index, line ->
        text(panel, index, line, TypeRole.Body)
    }

    text(
        page,
        1,
        "The same declaration, at every width.",
        TypeRole.Caption,
        ColorRole.OnSurfaceVariant,
    )

    if (message) {
        records += Mutation.ShowMessage(
            handlerId = 9L,
            text = "Deleted “Buy milk”",
            action = "Undo",
            duration = MessageDuration.Long,
        )
    }
    return FakeHostConnection(records)
}

/** A `ColorRole` as the `Paint` bits every colour slot on the wire carries. */
private fun colorRoleBits(role: ColorRole): Long = (1L shl 32) or (role.ordinal + 1L)

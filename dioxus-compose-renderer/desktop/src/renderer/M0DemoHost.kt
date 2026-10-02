package dioxus.compose.tooling

import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.WidgetKind

// Node ids of the M0 slice, mirroring what the Rust renderer emits for
// `Column { Text, TextField, Button }`.
private const val COLUMN = 1
private const val LABEL = 2
private const val FIELD = 3
private const val BUTTON = 4
private const val CLICK_HANDLER = 11L
private const val VALUE_CHANGE_HANDLER = 12L
private const val SUBMIT_HANDLER = 13L

/**
 * The demo screen driven by a scripted Host.
 *
 * Hot reload and `@Preview` work against this without building the Rust side.
 */
fun m0DemoHost(): FakeHostConnection {
    val initial = listOf(
        Mutation.Create(COLUMN, WidgetKind.Column),
        Mutation.SetModifier(COLUMN, 0, ProtocolModifier.Padding(16f)),
        Mutation.SetModifier(COLUMN, 1, ProtocolModifier.FillMaxWidth),
        Mutation.Create(LABEL, WidgetKind.Text),
        Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("clicks: 0")),
        Mutation.Insert(COLUMN, LABEL, 0),
        Mutation.Create(FIELD, WidgetKind.TextField),
        Mutation.SetProp(FIELD, PropertyKind.Placeholder, PropertyValue.Text("type here")),
        Mutation.SetProp(
            FIELD,
            PropertyKind.OnValueChange,
            PropertyValue.Integer(VALUE_CHANGE_HANDLER),
        ),
        Mutation.SetProp(FIELD, PropertyKind.OnSubmit, PropertyValue.Integer(SUBMIT_HANDLER)),
        Mutation.Insert(COLUMN, FIELD, 1),
        Mutation.Create(BUTTON, WidgetKind.Button),
        Mutation.SetProp(BUTTON, PropertyKind.Text, PropertyValue.Text("increment")),
        Mutation.SetProp(BUTTON, PropertyKind.OnClick, PropertyValue.Integer(CLICK_HANDLER)),
        Mutation.Insert(COLUMN, BUTTON, 2),
    )
    val connection = FakeHostConnection(initial)
    var clicks = 0
    connection.respondWith { event ->
        when (event) {
            is HostEvent.Clicked -> {
                clicks += 1
                HostResponse(
                    listOf(
                        Mutation.SetProp(
                            LABEL,
                            PropertyKind.Text,
                            PropertyValue.Text("clicks: $clicks"),
                        ),
                    ),
                    result = 1,
                )
            }

            is HostEvent.TextSubmitted -> {
                HostResponse(
                    listOf(
                        Mutation.SetProp(
                            LABEL,
                            PropertyKind.Text,
                            PropertyValue.Text("submitted: ${event.text}"),
                        ),
                    ),
                    result = 1,
                )
            }

            else -> HostResponse()
        }
    }
    return connection
}

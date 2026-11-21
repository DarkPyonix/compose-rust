// What code outside compose-rust writes instead: a `_` arm, `HostEvent::new` and `..`.
use compose_rust::EventPayload;
use compose_rust::protocol::HostEvent;

fn describe(payload: &EventPayload<'_>) -> &'static str {
    match payload {
        EventPayload::Clicked => "clicked",
        EventPayload::TextChanged(_) => "text",
        _ => "something else",
    }
}

fn main() {
    let event = HostEvent::new(1, 2, EventPayload::Clicked);
    let HostEvent {
        node_id,
        handler_id,
        ..
    } = &event;
    assert_eq!((*node_id, *handler_id), (1, 2));
    assert_eq!(describe(&event.payload), "clicked");
    assert_eq!(describe(&EventPayload::FocusLost), "something else");
}

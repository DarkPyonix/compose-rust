// A pattern that names every field of a non-exhaustive struct still needs `..`, so a field
// added later does not break it.
use compose_rust::EventPayload;
use compose_rust::protocol::HostEvent;

fn node(event: &HostEvent<'_>) -> u32 {
    let HostEvent {
        node_id,
        handler_id: _,
        payload: _,
    } = event;
    *node_id
}

fn main() {
    node(&HostEvent::new(1, 2, EventPayload::Clicked));
}

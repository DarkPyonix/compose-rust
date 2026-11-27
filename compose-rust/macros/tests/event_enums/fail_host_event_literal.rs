// A struct literal cannot build a non-exhaustive struct outside its crate.
// `HostEvent::new` is the way to build one.
use compose_rust::EventPayload;
use compose_rust::protocol::HostEvent;

fn main() {
    let _event = HostEvent {
        node_id: 1,
        handler_id: 2,
        payload: EventPayload::Clicked,
    };
}

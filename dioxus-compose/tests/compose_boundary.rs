//! A composable application through the same five boundary calls an `rsx!` one uses.

mod support;

use compose_rust::LaunchBuilder;
use compose_rust::boundary::{
    MutationBatch, STATUS_OK, dioxus_compose_host_dispatch_event, dioxus_compose_host_init,
    dioxus_compose_host_render_frame, dioxus_compose_host_shutdown,
};
use compose_rust::protocol::{HostEvent, Mutation, decode_batch, encode_event};
use compose_rust::runtime::*;
use compose_rust::schema::{EventPayload, LoopMode, PROTOCOL_VERSION, PropertyKind, SCHEMA_HASH};
use compose_rust::ui::*;
use support::Tree;

#[composable]
fn Counter() {
    let count = remember(|| mutable_state_of(0));
    Column().content(|| {
        Text(format!("{}", count.get()));
        let more = count.clone();
        Button("More").on_click(move || more.update(|count| *count += 1));
    });
}

fn batch_of(output: &MutationBatch) -> Vec<u8> {
    // SAFETY: a successful call returned a readable batch owned by the Host.
    unsafe { std::slice::from_raw_parts(output.ptr, output.len as usize) }.to_vec()
}

#[test]
fn fr39_a_composable_application_runs_through_the_unchanged_boundary() {
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .application(Counter);
    let mut handshake = Vec::with_capacity(12);
    handshake.extend_from_slice(&SCHEMA_HASH.to_le_bytes());
    handshake.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
    handshake.extend_from_slice(&[LoopMode::Platform as u8, 0]);

    // On a thread of its own, as the Renderer's UI thread is not the one that launched.
    std::thread::spawn(move || {
        let mut output = MutationBatch::default();
        // SAFETY: both buffers are live for the call.
        let status = unsafe {
            dioxus_compose_host_init(handshake.as_ptr(), handshake.len() as u32, &mut output)
        };
        assert_eq!(status, STATUS_OK);
        let initial = batch_of(&output);
        assert!(
            matches!(
                decode_batch(&initial).unwrap().first(),
                Some(Mutation::SetTheme(_))
            ),
            "the first record is the theme, as it is for an rsx application"
        );
        let mut tree = Tree::default();
        tree.apply(&initial);
        assert_eq!(tree.texts(), vec!["0", "More"]);

        let (node_id, handler_id) = tree.find("Button", Some("More"), 0, PropertyKind::OnClick);
        let mut event = Vec::new();
        encode_event(
            &HostEvent {
                node_id,
                handler_id,
                payload: EventPayload::Clicked,
            },
            &mut event,
        )
        .unwrap();
        // SAFETY: the event and the output are live for the call.
        let status = unsafe {
            dioxus_compose_host_dispatch_event(event.as_ptr(), event.len() as u32, &mut output)
        };
        assert_eq!(status, STATUS_OK);
        tree.apply(&batch_of(&output));
        assert_eq!(tree.texts(), vec!["1", "More"]);

        // SAFETY: the output is live for the call.
        let status = unsafe { dioxus_compose_host_render_frame(0, &mut output) };
        assert_eq!(status, STATUS_OK);
        dioxus_compose_host_shutdown();
    })
    .join()
    .unwrap();
}

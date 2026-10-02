//! Streaming text: the Host sends only the appended tail, coalesced per frame.

use dioxus_compose::Host;
use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{Mutation, decode_batch};
use dioxus_compose::schema::WidgetKind;

const TOKENS_PER_SECOND: usize = 100;

fn app() -> Element {
    rsx! {
        Column {
            Text { text: "" }
        }
    }
}

fn host_and_text_node() -> (Host, u32) {
    let mut host = Host::new(app);
    let initial = decode_batch(host.rebuild().unwrap()).unwrap();
    let node_id = initial
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Text,
            } => Some(*node_id),
            _ => None,
        })
        .unwrap();
    drop(initial);
    (host, node_id)
}

#[test]
fn fr9_streaming_sends_only_the_appended_tail() {
    let (mut host, node_id) = host_and_text_node();
    let answer = "a long already streamed answer line\n".repeat(1_000);
    let baseline = host.set_text(node_id, &answer, None).unwrap().len();
    assert!(baseline > answer.len());

    host.append_text(node_id, " token");
    let batch = host.render_frame(0).unwrap();
    let batch_len = batch.len();
    let decoded = decode_batch(batch).unwrap();

    assert_eq!(
        decoded,
        vec![Mutation::AppendText {
            node_id,
            text: " token",
        }]
    );
    assert!(
        batch_len < 64,
        "append batch was {batch_len} bytes; the whole answer must not be re-sent"
    );
}

#[test]
fn fr9_tokens_coalesce_into_one_batch_per_frame() {
    let (mut host, node_id) = host_and_text_node();
    for _ in 0..TOKENS_PER_SECOND {
        host.append_text(node_id, "tok ");
    }

    let expected = "tok ".repeat(TOKENS_PER_SECOND);
    let decoded = decode_batch(host.render_frame(0).unwrap()).unwrap();
    assert_eq!(
        decoded,
        vec![Mutation::AppendText {
            node_id,
            text: &expected,
        }]
    );

    // A frame with no new tokens carries no append record.
    let decoded = decode_batch(host.render_frame(0).unwrap()).unwrap();
    assert!(
        decoded.is_empty(),
        "flushed tokens were resent: {decoded:?}"
    );
}

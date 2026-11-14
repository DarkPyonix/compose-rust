//! What the Host does with a protocol error the Renderer reports.
//!
//! The Renderer reports every protocol error it finds as a `ProtocolError` event, and the
//! Host has no handler for one, so it refuses the call with a protocol-error status. The
//! Renderer prints a refused report as itself. This pins the Host's half of that: the
//! report is refused, it reaches no handler, and the Host goes on answering the next event.
//!
//! On Android a picture that could not be decoded was reported this way at the first frame,
//! and what reached the log was "compose_rust_host_dispatch_event returned -1" rather than
//! the missing picture. The refusal is this one; the fix is the Renderer printing the report.

use compose_rust::boundary::Host;
use compose_rust::protocol::{HostEvent, Mutation, ProtocolError, encode_event};
use compose_rust::schema::{EventPayload, WidgetKind};
use compose_rust::{Batch, Runtime};
use std::sync::atomic::{AtomicUsize, Ordering};

const IMAGE: u32 = 5;
const BUTTON: u32 = 6;
const ON_CLICK: u64 = 1;

static HANDLED: AtomicUsize = AtomicUsize::new(0);

/// A picture and a button, kept by hand.
struct Screen {
    batch: Batch,
}

impl Runtime for Screen {
    fn batch(&self) -> &Batch {
        &self.batch
    }

    fn batch_mut(&mut self) -> &mut Batch {
        &mut self.batch
    }

    fn rebuild(&mut self) {
        self.batch.write(Mutation::Create {
            node_id: IMAGE,
            widget: WidgetKind::Image,
        });
        self.batch.write(Mutation::Create {
            node_id: BUTTON,
            widget: WidgetKind::Button,
        });
    }

    fn render(&mut self, _frame_time_nanos: Option<u64>) {}

    fn handle_event(&mut self, event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        HANDLED.fetch_add(1, Ordering::SeqCst);
        if event.node_id == BUTTON && event.handler_id == ON_CLICK {
            Ok(0)
        } else {
            Err(ProtocolError::InvalidValueKind(0))
        }
    }

    fn run_in_context(&mut self, action: &mut dyn FnMut()) {
        action();
    }
}

fn encoded(event: &HostEvent<'_>) -> Vec<u8> {
    let mut bytes = Vec::new();
    encode_event(event, &mut bytes).expect("the event encodes");
    bytes
}

#[test]
fn nfr7_a_renderer_report_is_refused_reaches_no_handler_and_leaves_the_host_working() {
    let mut host = Host::new(|| {
        Box::new(Screen {
            batch: Batch::new(),
        }) as Box<dyn Runtime>
    });
    host.rebuild().expect("the first batch encodes");

    // The report a picture with no decodable asset sends at its first frame.
    let report = encoded(&HostEvent {
        node_id: IMAGE,
        handler_id: 0,
        payload: EventPayload::ProtocolError {
            code: 5,
            message: "asset 7 is not registered, so node 5 drew nothing",
        },
    });
    let handled_before = HANDLED.load(Ordering::SeqCst);
    assert_eq!(
        host.dispatch_event(&report).map(|(_, result)| result),
        Err(ProtocolError::InvalidValueKind(0)),
        "a report has no handler, so the Host refuses it and the Renderer prints it",
    );
    assert_eq!(
        HANDLED.load(Ordering::SeqCst),
        handled_before,
        "a report never reaches an application handler",
    );

    // The refusal changes nothing: the next event is answered as it would have been.
    let click = encoded(&HostEvent {
        node_id: BUTTON,
        handler_id: ON_CLICK,
        payload: EventPayload::Clicked,
    });
    assert_eq!(
        host.dispatch_event(&click).map(|(_, result)| result),
        Ok(0),
        "the Host still answers the click after refusing the report",
    );
    assert_eq!(HANDLED.load(Ordering::SeqCst), handled_before + 1);
}

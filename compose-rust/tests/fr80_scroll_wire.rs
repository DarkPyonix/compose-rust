//! The scroll requests and the scroll report on the wire: what `Batch::scroll_to` and
//! `Batch::scroll_into_view` write, what the decoder reads back, and how a report reaches
//! the runtime that owns the handler.

use compose_rust::boundary::Host;
use compose_rust::protocol::{
    HostEvent, Mutation, PropertyValue, ProtocolError, decode_batch, decode_event, encode_event,
};
use compose_rust::schema::{
    EventPayload, PropertyKind, Rect, ScrollAlign, ScrollIntoView, ScrollTarget, ScrollTo,
    WidgetKind, scroll_into_view_flags, scroll_target_kind, scroll_to_flags,
};
use compose_rust::{Batch, Runtime};
use std::sync::Mutex;

const MUTATIONS: &[u8] = include_bytes!("vectors/mutations.bin");
const EVENTS: &[u8] = include_bytes!("vectors/events.bin");

/// Where the report sits in the event vector, and how long it is.
const REPORT_AT: usize = 1319;
const REPORT_LEN: usize = 72;

fn vector_report() -> HostEvent<'static> {
    HostEvent {
        node_id: 10,
        handler_id: 21,
        payload: EventPayload::ScrollChanged {
            item: 3,
            settled: true,
            can_scroll_backward: true,
            can_scroll_forward: false,
            x_px: 0.0,
            y_px: 700.5,
            max_x_px: 0.0,
            max_y_px: -1.0,
            viewport_width_px: 320.0,
            viewport_height_px: 300.0,
        },
    }
}

fn scroll_records(batch: &[u8]) -> Vec<Mutation<'_>> {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .filter(|mutation| {
            matches!(
                mutation,
                Mutation::ScrollTo { .. } | Mutation::ScrollIntoView { .. }
            )
        })
        .collect()
}

#[test]
fn fr80_scroll_records_round_trip_through_the_protocol_vectors() {
    let expected = vec![
        Mutation::ScrollTo {
            node_id: 10,
            item: 3,
            flags: scroll_to_flags::ANIMATE | scroll_to_flags::Y,
            x_px: 0.0,
            y_px: 120.5,
        },
        Mutation::ScrollIntoView {
            node_id: 10,
            target: 12,
            target_kind: scroll_target_kind::NODE,
            align_x: ScrollAlign::Keep as u16,
            align_y: ScrollAlign::Nearest as u16,
            flags: scroll_into_view_flags::ANIMATE,
            rect: Rect::new(0.0, 8.0, 40.0, 24.5),
        },
    ];
    assert_eq!(scroll_records(MUTATIONS), expected);

    // The Batch methods write the same two records.
    let mut batch = Batch::new();
    batch.begin();
    batch.scroll_to(
        10,
        ScrollTo {
            x_px: None,
            y_px: Some(120.5),
            item: 3,
            animate: true,
        },
    );
    batch.scroll_into_view(
        10,
        ScrollIntoView {
            target: ScrollTarget::Node(12, Rect::new(0.0, 8.0, 40.0, 24.5)),
            align_x: ScrollAlign::Keep,
            align_y: ScrollAlign::Nearest,
            animate: true,
        },
    );
    let written = batch.finish().expect("the batch encodes").to_vec();
    assert_eq!(scroll_records(&written), expected);

    // The report sits at its place in the event vector, byte for byte. Records added after
    // it leave it where it is.
    assert!(EVENTS.len() >= REPORT_AT + REPORT_LEN);
    let bytes = &EVENTS[REPORT_AT..REPORT_AT + REPORT_LEN];
    assert_eq!(
        decode_event(bytes).expect("the report decodes"),
        vector_report()
    );
    let mut encoded = Vec::new();
    encode_event(&vector_report(), &mut encoded).expect("the report encodes");
    assert_eq!(encoded, bytes);
}

#[test]
fn fr80_scroll_changed_decodes_every_field() {
    let event = HostEvent {
        node_id: 7,
        handler_id: 0x1_0000_0002,
        payload: EventPayload::ScrollChanged {
            item: 500,
            settled: false,
            can_scroll_backward: false,
            can_scroll_forward: true,
            x_px: 1.5,
            y_px: 2.5,
            max_x_px: 3.5,
            max_y_px: 4.5,
            viewport_width_px: 5.5,
            viewport_height_px: 6.5,
        },
    };
    let mut bytes = Vec::new();
    encode_event(&event, &mut bytes).expect("the report encodes");
    assert_eq!(bytes.len(), 72);
    // The eight fields sit where the record says they do.
    assert_eq!(u32::from_le_bytes(bytes[16..20].try_into().unwrap()), 500);
    assert_eq!(u32::from_le_bytes(bytes[20..24].try_into().unwrap()), 0b100);
    for (index, expected) in [1.5, 2.5, 3.5, 4.5, 5.5, 6.5].into_iter().enumerate() {
        let at = 24 + index * 8;
        let value = f64::from_le_bytes(bytes[at..at + 8].try_into().unwrap());
        assert_eq!(value, expected, "the double at byte {at}");
    }
    assert_eq!(decode_event(&bytes).expect("the report decodes"), event);

    // A flag bit with no meaning is refused, not guessed at.
    let mut unknown_bit = bytes.clone();
    unknown_bit[20] |= 0b1000;
    assert_eq!(
        decode_event(&unknown_bit),
        Err(ProtocolError::InvalidValueKind(3))
    );

    // So is a record of any other length.
    let mut short = bytes[..64].to_vec();
    short[2..4].copy_from_slice(&64_u16.to_le_bytes());
    assert_eq!(
        decode_event(&short),
        Err(ProtocolError::InvalidRecordLength)
    );
    let mut long = bytes.clone();
    long.extend_from_slice(&[0; 8]);
    long[2..4].copy_from_slice(&80_u16.to_le_bytes());
    assert_eq!(decode_event(&long), Err(ProtocolError::InvalidRecordLength));
}

#[test]
fn fr80_scroll_to_writes_only_the_axes_it_names() {
    let mut batch = Batch::new();
    batch.begin();
    batch.scroll_to(
        4,
        ScrollTo {
            x_px: Some(30.0),
            ..ScrollTo::default()
        },
    );
    batch.scroll_into_view(
        4,
        ScrollIntoView {
            target: ScrollTarget::Item(500, Rect::default()),
            align_x: ScrollAlign::Keep,
            align_y: ScrollAlign::Start,
            animate: false,
        },
    );
    batch.scroll_into_view(
        4,
        ScrollIntoView {
            target: ScrollTarget::Content(Rect::new(0.0, 600.0, 10.0, 40.0)),
            align_x: ScrollAlign::Center,
            align_y: ScrollAlign::End,
            animate: false,
        },
    );
    let written = batch.finish().expect("the batch encodes").to_vec();
    assert_eq!(
        scroll_records(&written),
        vec![
            Mutation::ScrollTo {
                node_id: 4,
                item: 0,
                flags: scroll_to_flags::X,
                x_px: 30.0,
                y_px: 0.0,
            },
            Mutation::ScrollIntoView {
                node_id: 4,
                target: 500,
                target_kind: scroll_target_kind::ITEM,
                align_x: 0,
                align_y: 1,
                flags: 0,
                rect: Rect::default(),
            },
            Mutation::ScrollIntoView {
                node_id: 4,
                target: 0,
                target_kind: scroll_target_kind::CONTENT,
                align_x: 2,
                align_y: 3,
                flags: 0,
                rect: Rect::new(0.0, 600.0, 10.0, 40.0),
            },
        ]
    );
}

#[test]
fn fr80_a_request_the_renderer_would_refuse_fails_the_batch() {
    let refused = |write: &dyn Fn(&mut Batch)| {
        let mut batch = Batch::new();
        batch.begin();
        write(&mut batch);
        batch.finish().map(<[u8]>::to_vec)
    };
    // Neither axis named.
    assert!(refused(&|batch| batch.scroll_to(1, ScrollTo::default())).is_err());
    // A coordinate that is not a number.
    assert!(
        refused(&|batch| batch.scroll_to(
            1,
            ScrollTo {
                y_px: Some(f64::NAN),
                ..ScrollTo::default()
            }
        ))
        .is_err()
    );
    // A rectangle with a negative size.
    assert!(
        refused(&|batch| batch.scroll_into_view(
            1,
            ScrollIntoView {
                target: ScrollTarget::Content(Rect::new(0.0, 0.0, -1.0, 4.0)),
                align_x: ScrollAlign::Keep,
                align_y: ScrollAlign::Start,
                animate: false,
            }
        ))
        .is_err()
    );
    // Below zero or past the end is not a mistake: the Renderer keeps it in range.
    assert!(
        refused(&|batch| batch.scroll_to(
            1,
            ScrollTo {
                y_px: Some(-10.0),
                ..ScrollTo::default()
            }
        ))
        .is_ok()
    );
}

const LIST: u32 = 1;
const ON_SCROLL: u64 = 9;

/// A scroll report as `(item, y, max_y, viewport_height, settled)`.
type Heard = (u32, f64, f64, f64, bool);

/// What the runtime below was last told.
static HEARD: Mutex<Option<Heard>> = Mutex::new(None);

/// One scroll container with a scroll handler, kept by hand.
struct Listener {
    batch: Batch,
}

impl Runtime for Listener {
    fn batch(&self) -> &Batch {
        &self.batch
    }

    fn batch_mut(&mut self) -> &mut Batch {
        &mut self.batch
    }

    fn rebuild(&mut self) {
        self.batch.write(Mutation::Create {
            node_id: LIST,
            widget: WidgetKind::ScrollColumn,
        });
        self.batch.write(Mutation::SetProp {
            node_id: LIST,
            property: PropertyKind::OnScrollChanged,
            value: PropertyValue::Integer(ON_SCROLL as i64),
        });
    }

    fn render(&mut self, _frame_time_nanos: Option<u64>) {}

    fn handle_event(&mut self, event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        if event.node_id != LIST || event.handler_id != ON_SCROLL {
            return Err(ProtocolError::InvalidValueKind(0));
        }
        let EventPayload::ScrollChanged {
            item,
            settled,
            y_px,
            max_y_px,
            viewport_height_px,
            ..
        } = event.payload
        else {
            return Err(ProtocolError::InvalidValueKind(0));
        };
        *HEARD.lock().unwrap() = Some((item, y_px, max_y_px, viewport_height_px, settled));
        Ok(0)
    }

    fn run_in_context(&mut self, action: &mut dyn FnMut()) {
        action();
    }
}

#[test]
fn fr80_a_scroll_report_reaches_the_runtime_that_owns_the_handler() {
    let mut host = Host::new(|| {
        Box::new(Listener {
            batch: Batch::new(),
        }) as Box<dyn Runtime>
    });
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    assert!(
        decode_batch(&first)
            .unwrap()
            .iter()
            .any(|mutation| matches!(
                mutation,
                Mutation::SetProp {
                    node_id: LIST,
                    property: PropertyKind::OnScrollChanged,
                    ..
                }
            )),
        "the handler travels as the OnScrollChanged property"
    );

    let mut bytes = Vec::new();
    encode_event(
        &HostEvent {
            node_id: LIST,
            handler_id: ON_SCROLL,
            payload: EventPayload::ScrollChanged {
                item: 0,
                settled: true,
                can_scroll_backward: true,
                can_scroll_forward: false,
                x_px: 0.0,
                y_px: 700.0,
                max_x_px: 0.0,
                max_y_px: 700.0,
                viewport_width_px: 200.0,
                viewport_height_px: 300.0,
            },
        },
        &mut bytes,
    )
    .unwrap();
    host.dispatch_event(&bytes)
        .expect("the report is delivered");
    assert_eq!(*HEARD.lock().unwrap(), Some((0, 700.0, 700.0, 300.0, true)));
}

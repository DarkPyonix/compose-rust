//! The measure call, from the Host's side, against the mock renderer's fixed rules.
//!
//! A Host lays its page out in the middle of a call the Renderer made into it, and asks the
//! Renderer how big things are from inside that call. These tests drive the Host through a
//! runtime that measures while it renders, with the mock renderer answering: every
//! character half the font size wide, a line one and a quarter font sizes tall, and no node
//! known, because nothing applies a tree.

use compose_rust::boundary::{RendererApi, install_renderer_api};
use compose_rust::measure::{
    FAKE_FONT_SIZE, MEASURE_OFF_UI_THREAD, MEASURE_RECORD_LEN, MeasureError, MeasureRequests,
    MeasureResult, MeasureStatus, NODE_ZOOM_AT, NodeConstraints, TEXT_TEXT_LENGTH_AT,
    TEXT_TEXT_OFFSET_AT, TEXT_ZOOM_AT, TextConstraint, TextStyle, fake_measure, measurer,
};
use compose_rust::protocol::{HostEvent, Mutation, ProtocolError};
use compose_rust::spans::TextSpans;
use compose_rust::{Batch, Host, PropertyKind, Runtime, TypeRole, WidgetKind};
use std::cell::RefCell;
use std::ffi::c_int;
use std::sync::{Mutex, MutexGuard};

extern "C" fn never_run() -> c_int {
    0
}

extern "C" fn no_frames() {}

/// The mock renderer, installed once for the whole binary. Every test in this file uses
/// the same one, so it does not matter which installs it.
fn mock_renderer() {
    let _ = install_renderer_api(RendererApi {
        run: never_run,
        request_frame: no_frames,
        measure: fake_measure,
    });
}

/// What a test asks the runtime to do while it renders, and what came of it.
type Work = Box<dyn FnMut()>;

thread_local! {
    static WORK: RefCell<Option<Work>> = RefCell::new(None);
}

/// Tests that build a Host take turns, because a Host resets process-wide state when it
/// is made.
static HOSTS: Mutex<()> = Mutex::new(());

fn host_guard() -> MutexGuard<'static, ()> {
    HOSTS
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// One Text node, and whatever the test asked for on every render.
struct Measuring {
    batch: Batch,
}

impl Runtime for Measuring {
    fn batch(&self) -> &Batch {
        &self.batch
    }

    fn batch_mut(&mut self) -> &mut Batch {
        &mut self.batch
    }

    fn rebuild(&mut self) {
        self.batch.write(Mutation::Create {
            node_id: 1,
            widget: WidgetKind::Text,
        });
        self.batch.write(Mutation::SetProp {
            node_id: 1,
            property: PropertyKind::Text,
            value: compose_rust::protocol::PropertyValue::String("measured"),
        });
    }

    fn render(&mut self) {
        WORK.with(|work| {
            if let Some(work) = work.borrow_mut().as_mut() {
                work();
            }
        });
    }

    fn handle_event(&mut self, _event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        Ok(0)
    }
}

fn host() -> Host {
    mock_renderer();
    let mut host = Host::new(|| {
        Box::new(Measuring {
            batch: Batch::new(),
        }) as Box<dyn Runtime>
    });
    host.rebuild().expect("the first frame failed to encode");
    host
}

/// Runs `work` inside the next frame the Host renders, on this thread.
fn during_next_frame(host: &mut Host, work: impl FnMut() + 'static) {
    WORK.with(|slot| *slot.borrow_mut() = Some(Box::new(work)));
    let rendered = host.render_frame(0).map(|bytes| bytes.len());
    WORK.with(|slot| *slot.borrow_mut() = None);
    rendered.expect("the frame failed to encode");
}

const ADVANCE: f32 = FAKE_FONT_SIZE * 0.5;
const LINE: f32 = FAKE_FONT_SIZE * 1.25;

/// The Host asks from inside the frame it is rendering, and the answer is there before
/// the frame returns, on the same thread, with no queue in between.
#[test]
fn pr1_measure_is_answered_inside_the_host_call() {
    let _hosts = host_guard();
    let mut host = host();
    let caller = std::thread::current().id();
    let answered = std::rc::Rc::new(RefCell::new(None));
    let seen = answered.clone();
    during_next_frame(&mut host, move || {
        let mut measurer = measurer().expect("a frame is a Host call, so a measurer is handed out");
        let mut requests = MeasureRequests::new();
        let style = TextStyle::default();
        requests.text(
            "hello world",
            &TextSpans::default(),
            &style,
            TextConstraint::MaxContent,
        );
        requests.text(
            "hello world",
            &TextSpans::default(),
            &style,
            TextConstraint::MinContent,
        );
        requests.text(
            "hello world",
            &TextSpans::default(),
            &style,
            TextConstraint::AtMost(40.0),
        );
        let mut results = Vec::new();
        measurer
            .measure(&mut requests, &mut results)
            .expect("the mock renderer answers a call made inside a Host call");
        *seen.borrow_mut() = Some((std::thread::current().id(), results));
    });
    let (thread, results) = answered
        .borrow_mut()
        .take()
        .expect("the answer was not there when the frame returned");
    assert_eq!(thread, caller, "the answer came on another thread");
    assert_eq!(results.len(), 3);
    for result in &results {
        assert_eq!(result.status(), MeasureStatus::Ok);
    }
    assert_eq!(
        results[0].width,
        11.0 * ADVANCE,
        "max content is the whole line"
    );
    assert_eq!(results[0].line_count, 1);
    assert_eq!(
        results[1].width,
        5.0 * ADVANCE,
        "min content is the longest word"
    );
    assert_eq!(results[1].line_count, 2);
    assert_eq!(
        results[2].line_count, 2,
        "eleven characters do not fit forty dp"
    );
    assert_eq!(results[2].height, 2.0 * LINE);
    assert_eq!(results[2].last_line_width, 5.0 * ADVANCE);

    // Outside a Host call there is no Renderer standing still to answer, and the Host
    // does not ask.
    assert!(matches!(measurer(), Err(MeasureError::OutsideHostCall)));
}

/// A thread other than the UI thread is refused by both halves: the Host hands out no
/// measurer there, and the Renderer measures nothing and says so. The process goes on.
#[test]
fn pr3_measure_off_the_ui_thread_is_refused() {
    let _hosts = host_guard();
    let mut host = host();
    let refused = std::thread::spawn(|| {
        let host_side = matches!(measurer(), Err(MeasureError::OutsideHostCall));
        let mut requests = vec![0u8; MEASURE_RECORD_LEN];
        requests[0] = 1;
        let mut results = [MeasureResult::default()];
        // SAFETY: both buffers are live for the call.
        let renderer_side = unsafe {
            fake_measure(
                requests.as_ptr(),
                requests.len() as u32,
                1,
                results.as_mut_ptr(),
            )
        };
        (host_side, renderer_side, results[0])
    })
    .join()
    .expect("a refused call must not take the thread down");
    assert!(refused.0, "a measurer was handed out off the UI thread");
    assert_eq!(refused.1, MEASURE_OFF_UI_THREAD);
    assert_eq!(
        refused.2,
        MeasureResult::default(),
        "a refused call wrote a result"
    );

    // The Host is still there and still answers on its own thread.
    let ok = std::rc::Rc::new(RefCell::new(false));
    let seen = ok.clone();
    during_next_frame(&mut host, move || {
        *seen.borrow_mut() = measurer().is_ok();
    });
    assert!(
        *ok.borrow(),
        "the UI thread lost its measurer after a refusal elsewhere"
    );
}

/// A buffer the Renderer cannot read is refused as a whole: every answer is zero size, the
/// call comes back as a protocol error, and the frame it was asked in still renders.
#[test]
fn nfr7_malformed_measure_buffer_is_a_protocol_error() {
    let _hosts = host_guard();
    let mut host = host();
    let outcome = std::rc::Rc::new(RefCell::new(None));
    let seen = outcome.clone();
    during_next_frame(&mut host, move || {
        let mut measurer = measurer().expect("inside a frame");
        // Three records announced, ten bytes given.
        let mut results = [MeasureResult::default(); 3];
        let whole = measurer.measure_encoded(&[0u8; 10], 3, &mut results);

        // One record pointing outside the buffer among good ones is that record's
        // problem alone.
        let mut requests = MeasureRequests::new();
        let style = TextStyle::default();
        requests.text(
            "ok",
            &TextSpans::default(),
            &style,
            TextConstraint::MaxContent,
        );
        requests.text(
            "bad",
            &TextSpans::default(),
            &style,
            TextConstraint::MaxContent,
        );
        requests.text(
            "ok",
            &TextSpans::default(),
            &style,
            TextConstraint::MaxContent,
        );
        // The buffer as it would be sent, with the middle record broken.
        let mut encoded = requests.encoded().to_vec();
        let mut per_record = vec![MeasureResult::default(); 3];
        let at = MEASURE_RECORD_LEN + TEXT_TEXT_OFFSET_AT;
        encoded[at..at + 4].copy_from_slice(&u32::MAX.to_le_bytes());
        let one = measurer.measure_encoded(&encoded, 3, &mut per_record);
        *seen.borrow_mut() = Some((whole, results, one, per_record));
    });
    let (whole, results, one, per_record) = outcome.borrow_mut().take().expect("the frame ran");
    assert_eq!(
        whole,
        Err(MeasureError::Refused(ProtocolError::MeasureRefused(-1)))
    );
    for result in results {
        assert_eq!((result.width, result.height), (0.0, 0.0));
        assert_eq!(result.status(), MeasureStatus::Malformed);
    }
    assert_eq!(one, Ok(()), "one bad record does not refuse the call");
    assert_eq!(per_record[0].status(), MeasureStatus::Ok);
    assert_eq!(per_record[1].status(), MeasureStatus::Malformed);
    assert_eq!(per_record[2].status(), MeasureStatus::Ok);
}

/// A node the Renderer has not applied is that request's problem, and the others in the
/// same call are measured.
#[test]
fn pr2_unknown_node_is_reported_per_record() {
    let _hosts = host_guard();
    let mut host = host();
    let outcome = std::rc::Rc::new(RefCell::new(Vec::new()));
    let seen = outcome.clone();
    during_next_frame(&mut host, move || {
        let mut measurer = measurer().expect("inside a frame");
        let mut requests = MeasureRequests::new();
        let style = TextStyle::role(TypeRole::Body);
        requests.text(
            "before",
            &TextSpans::default(),
            &style,
            TextConstraint::MaxContent,
        );
        requests.node(4_000_000, NodeConstraints::UNBOUNDED);
        requests.text(
            "after",
            &TextSpans::default(),
            &style,
            TextConstraint::MaxContent,
        );
        let mut results = Vec::new();
        measurer
            .measure(&mut requests, &mut results)
            .expect("measured");
        *seen.borrow_mut() = results;
    });
    let results = outcome.borrow();
    assert_eq!(results.len(), 3);
    assert_eq!(results[0].status(), MeasureStatus::Ok);
    assert_eq!(results[1].status(), MeasureStatus::UnknownNode);
    assert!(results[1].width == 0.0 && results[1].first_baseline.is_nan());
    assert_eq!(results[2].status(), MeasureStatus::Ok);
    assert_eq!(results[2].width, 5.0 * ADVANCE);
}

/// The request buffer is laid out as records and then the payload they point at, and every
/// offset lands on what it names.
#[test]
fn pr2_a_request_buffer_points_at_its_own_payload() {
    let mut requests = MeasureRequests::new();
    let style = TextStyle::default();
    requests.text(
        "first",
        &TextSpans::default(),
        &style,
        TextConstraint::MaxContent,
    );
    requests.node(7, NodeConstraints::width(100.0));
    requests.text(
        "second",
        &TextSpans::default(),
        &style,
        TextConstraint::AtMost(10.0),
    );
    let buffer = requests.encoded().to_vec();
    let word = |at: usize| u32::from_le_bytes(buffer[at..at + 4].try_into().unwrap()) as usize;
    for (index, expected) in [(0usize, "first"), (2, "second")] {
        let record = index * MEASURE_RECORD_LEN;
        let at = word(record + TEXT_TEXT_OFFSET_AT);
        let length = word(record + TEXT_TEXT_LENGTH_AT);
        assert!(at >= 3 * MEASURE_RECORD_LEN, "text inside the record area");
        assert_eq!(&buffer[at..at + length], expected.as_bytes());
    }
}

/// The zoom of the region a request belongs to travels on the request itself: zero where
/// it was never set, which the Renderer reads as one, and the value set from then on.
#[test]
fn fr43_every_request_carries_its_zoom() {
    let mut requests = MeasureRequests::new();
    let style = TextStyle::default();
    requests.text(
        "before",
        &TextSpans::default(),
        &style,
        TextConstraint::MaxContent,
    );
    requests.set_zoom(1.5);
    requests.text(
        "after",
        &TextSpans::default(),
        &style,
        TextConstraint::MaxContent,
    );
    requests.node(3, NodeConstraints::UNBOUNDED);
    let buffer = requests.encoded().to_vec();
    let real = |at: usize| f32::from_le_bytes(buffer[at..at + 4].try_into().unwrap());
    assert_eq!(real(TEXT_ZOOM_AT), 0.0);
    assert_eq!(real(MEASURE_RECORD_LEN + TEXT_ZOOM_AT), 1.5);
    assert_eq!(real(2 * MEASURE_RECORD_LEN + NODE_ZOOM_AT), 1.5);
}

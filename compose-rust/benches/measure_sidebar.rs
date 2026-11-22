//! The Host's half of measuring one sidebar: two hundred text labels, each asked for its
//! narrowest width, its widest width and its height at a given width, six hundred
//! measurements in one measure call, with nothing cached.
//!
//! The mock renderer answers by fixed rules, so what is timed here is everything the Host
//! adds on its side of the call: writing the requests and crossing into the Renderer and
//! back. The Renderer's half, the text layout itself against Compose's `TextMeasurer`
//! called directly, is `MeasureSidebarBenchmarkTest` in the renderer's tests; the platform
//! smoke tests time the whole call through the real boundary on each platform.

use compose_rust::boundary::{Host, RendererApi, install_renderer_api};
use compose_rust::measure::{
    MeasureRequests, MeasureResult, MeasureStatus, TextConstraint, TextStyle, fake_measure,
    measurer,
};
use compose_rust::protocol::{HostEvent, ProtocolError};
use compose_rust::spans::TextSpans;
use compose_rust::{Batch, FontRef, FontRefs, GenericFamily, Runtime, TypeRole};
use std::cell::Cell;
use std::ffi::c_int;
use std::hint::black_box;
use std::time::{Duration, Instant};

const LABELS: usize = 200;
const REQUESTS: usize = LABELS * 3;
const WIDTH: f32 = 180.0;
const WARMUP: usize = 3;
const QUICK_ROUNDS: usize = 15;
const FULL_ROUNDS: usize = 100;

const WORDS: [&str; 6] = [
    "Inbox",
    "Drafts and unsent messages",
    "Archive",
    "A considerably longer label that wraps at the sidebar's width",
    "설정",
    "Projects / compose-rust / renderer",
];

extern "C" fn never_run() -> c_int {
    0
}

extern "C" fn no_frames() {}

thread_local! {
    /// How many answers the last frame got, for the check that the frame measured at all.
    static ANSWERED: Cell<usize> = const { Cell::new(0) };
    /// Checksum of measured widths and heights so the compiler keeps the whole loop live.
    static SINK: Cell<f32> = const { Cell::new(0.0) };
}

/// Measures the whole sidebar every time it renders, reusing the buffers it keeps across
/// frames while leaving every measurement uncached.
struct Sidebar {
    batch: Batch,
    labels: Vec<String>,
    style: TextStyle,
    requests: MeasureRequests,
    results: Vec<MeasureResult>,
}

impl Sidebar {
    fn new() -> Self {
        Self {
            batch: Batch::new(),
            labels: (0..LABELS)
                .map(|index| {
                    format!(
                        "Sidebar entry {}: {}",
                        index + 1,
                        WORDS[index % WORDS.len()]
                    )
                })
                .collect(),
            style: TextStyle {
                type_role: TypeRole::None,
                font: FontRefs::new([FontRef::Generic(GenericFamily::SansSerif)]),
                font_size: Some(13.0),
                ..TextStyle::default()
            },
            requests: MeasureRequests::new(),
            results: Vec::with_capacity(REQUESTS),
        }
    }

    fn fill_requests(&mut self) {
        self.requests.clear();
        let spans = TextSpans::default();
        for label in &self.labels {
            self.requests
                .text(label, &spans, &self.style, TextConstraint::MinContent);
            self.requests
                .text(label, &spans, &self.style, TextConstraint::MaxContent);
            self.requests
                .text(label, &spans, &self.style, TextConstraint::AtMost(WIDTH));
        }
    }
}

impl Runtime for Sidebar {
    fn batch(&self) -> &Batch {
        &self.batch
    }

    fn batch_mut(&mut self) -> &mut Batch {
        &mut self.batch
    }

    fn rebuild(&mut self) {}

    fn render(&mut self, _frame_time_nanos: Option<u64>) {
        let mut measurer = measurer().expect("a frame is a Host call");
        self.fill_requests();
        measurer
            .measure(&mut self.requests, &mut self.results)
            .expect("the mock renderer answers");
        let mut checksum = 0.0f32;
        for result in &self.results {
            assert_eq!(result.status(), MeasureStatus::Ok);
            checksum += result.width + result.height;
        }
        ANSWERED.with(|answered| answered.set(self.results.len()));
        SINK.with(|sink| sink.set(checksum));
    }

    fn handle_event(&mut self, _event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        Ok(0)
    }
}

fn is_quick_mode() -> bool {
    if std::env::var_os("DXC_BENCH_QUICK").is_some() {
        return true;
    }
    std::env::args().any(|arg| arg == "--quick")
}

fn ms(duration: Duration) -> f64 {
    duration.as_secs_f64() * 1_000.0
}

fn percentile(sorted: &[Duration], num: usize, den: usize) -> Duration {
    let index = (sorted.len().saturating_sub(1) * num) / den;
    sorted[index]
}

fn main() {
    let quick = is_quick_mode();
    let rounds = if quick { QUICK_ROUNDS } else { FULL_ROUNDS };

    let _ = install_renderer_api(RendererApi {
        run: never_run,
        request_frame: no_frames,
        measure: fake_measure,
    });

    // Everything a Host holds on to between frames is made once, so the timed part is one
    // frame's worth of asking rather than setup.
    let mut host = Host::new(|| Box::new(Sidebar::new()) as Box<dyn Runtime>);
    host.rebuild().expect("rebuild succeeds");

    let cold_started = Instant::now();
    let cold_batch = host.render_frame(0).expect("cold frame succeeds");
    let cold_elapsed = cold_started.elapsed();
    black_box(cold_batch.len());
    assert_eq!(ANSWERED.with(Cell::get), REQUESTS);
    assert!(SINK.with(Cell::get) > 0.0);

    for _ in 0..WARMUP {
        let batch = host.render_frame(0).expect("warmup frame succeeds");
        black_box(batch.len());
    }

    let mut frame_samples = Vec::with_capacity(rounds);
    for _ in 0..rounds {
        let started = Instant::now();
        let batch = host.render_frame(0).expect("frame succeeds");
        frame_samples.push(started.elapsed());
        black_box(batch.len());
    }
    frame_samples.sort_unstable();
    let frame_median = percentile(&frame_samples, 1, 2);
    let frame_p95 = percentile(&frame_samples, 95, 100);

    // Isolate the Host's binary encoding cost for the same 600 requests without running
    // the mock renderer's character loop.
    let mut encoder = Sidebar::new();
    encoder.fill_requests();
    let encoded_bytes = encoder.requests.encoded().len();
    for _ in 0..WARMUP {
        encoder.fill_requests();
        black_box(encoder.requests.encoded().len());
    }
    let mut encode_samples = Vec::with_capacity(rounds);
    for _ in 0..rounds {
        let started = Instant::now();
        encoder.fill_requests();
        let bytes = encoder.requests.encoded();
        encode_samples.push(started.elapsed());
        black_box(bytes.len());
    }
    encode_samples.sort_unstable();
    let encode_median = percentile(&encode_samples, 1, 2);
    let encode_p95 = percentile(&encode_samples, 95, 100);

    println!(
        "measure_sidebar (rust host + mock renderer): {} measurements, {:.3} ms cold, \
         {:.3} ms median, {:.3} ms p95 ({} rounds, {} bytes, quick={})",
        REQUESTS,
        ms(cold_elapsed),
        ms(frame_median),
        ms(frame_p95),
        rounds,
        encoded_bytes,
        quick,
    );
    println!(
        "measure_sidebar (rust encode only): {} requests, {:.3} ms median, \
         {:.3} ms p95 ({} rounds, {} bytes)",
        REQUESTS,
        ms(encode_median),
        ms(encode_p95),
        rounds,
        encoded_bytes,
    );
}

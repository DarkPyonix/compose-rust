//! The Host's half of measuring one sidebar: two hundred text labels, each asked for its
//! narrowest width, its widest width and its height at a given width, six hundred
//! measurements in one measure call, with nothing cached.
//!
//! The mock renderer answers, by fixed rules, so what is timed here is everything the Host
//! adds on its side of the call: writing the requests and crossing into the Renderer and
//! back. The Renderer's half, the text layout itself against Compose's `TextMeasurer`
//! called directly, is `MeasureSidebarBenchmarkTest` in the renderer's tests; the platform
//! smoke tests time the whole call through the real boundary on each platform.

use criterion::{Criterion, Throughput, criterion_group, criterion_main};
use dioxus_compose_adapter::boundary::{Host, RendererApi, install_renderer_api};
use dioxus_compose_adapter::measure::{
    MeasureRequests, MeasureResult, TextConstraint, TextStyle, fake_measure, measurer,
};
use dioxus_compose_adapter::protocol::{HostEvent, Mutation, ProtocolError};
use dioxus_compose_adapter::spans::TextSpans;
use dioxus_compose_adapter::{Batch, FontRef, FontRefs, GenericFamily, Runtime, TypeRole};
use std::cell::Cell;
use std::ffi::c_int;

const LABELS: usize = 200;
const WIDTH: f32 = 180.0;

extern "C" fn never_run() -> c_int {
    0
}

extern "C" fn no_frames() {}

/// Measures the whole sidebar every time it renders, with the buffers it keeps.
struct Sidebar {
    batch: Batch,
    labels: Vec<String>,
    style: TextStyle,
    requests: MeasureRequests,
    results: Vec<MeasureResult>,
}

thread_local! {
    /// How many answers the last frame got, for the check that the frame measured at all.
    static ANSWERED: Cell<usize> = const { Cell::new(0) };
}

impl Sidebar {
    fn new() -> Self {
        Self {
            batch: Batch::new(),
            labels: (0..LABELS)
                .map(|index| format!("Sidebar entry {}: a label of a few words", index + 1))
                .collect(),
            style: TextStyle {
                type_role: TypeRole::None,
                font: FontRefs::new([FontRef::Generic(GenericFamily::SansSerif)]),
                font_size: Some(13.0),
                ..TextStyle::default()
            },
            requests: MeasureRequests::new(),
            results: Vec::with_capacity(3 * LABELS),
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

    fn render(&mut self) {
        let mut measurer = measurer().expect("a frame is a Host call");
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
        measurer
            .measure(&mut self.requests, &mut self.results)
            .expect("the mock renderer answers");
        ANSWERED.with(|answered| answered.set(self.results.len()));
    }

    fn handle_event(&mut self, _event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        Ok(0)
    }
}

fn benchmarks(criterion: &mut Criterion) {
    let _ = install_renderer_api(RendererApi {
        run: never_run,
        request_frame: no_frames,
        measure: fake_measure,
    });
    // Everything a Host would hold on to between frames is made once, so the timed part
    // is one frame's worth of asking, not the setting up.
    let mut host = Host::new(|| Box::new(Sidebar::new()) as Box<dyn Runtime>);
    host.rebuild().unwrap();
    host.render_frame(0).unwrap();
    assert_eq!(ANSWERED.with(Cell::get), 3 * LABELS);

    let mut group = criterion.benchmark_group("measure_sidebar");
    group.throughput(Throughput::Elements((3 * LABELS) as u64));
    group.bench_function("host_side_600_measurements", |bencher| {
        bencher.iter(|| {
            let batch = host.render_frame(0).unwrap();
            std::hint::black_box(batch.len());
        });
    });
    group.finish();
}

criterion_group!(benches, benchmarks);
criterion_main!(benches);

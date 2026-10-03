//! The Dioxus path's runs of every scenario: the baseline crate's rsx! screens through the
//! adapter's Host, at the pinned commit.

use dioxus_baseline::adapter::Host;
use dioxus_baseline::adapter::prelude::*;
use dioxus_baseline::adapter::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
use dioxus_baseline::adapter::schema::{EventPayload, WindowHeightClass, WindowSizeClass};
use dioxus_baseline::adapter::{RendererApi, install_renderer_api};
use fr39_scenarios::{App, Batch, Event, Path, Payload, Record, SLOT_SWEEP, Value};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::{Duration, Instant};

static FRAME_REQUESTED: AtomicBool = AtomicBool::new(false);
static SLOTS: AtomicUsize = AtomicUsize::new(1);

extern "C" fn run() -> i32 {
    0
}

extern "C" fn request_frame() {
    FRAME_REQUESTED.store(true, Ordering::Release);
}

/// The sweep screen the recomposition experiment measured: a column of texts that all
/// read the same counter, and the button that moves it. Its width is set before each
/// scenario starts, because a root component is a plain function.
fn sweep() -> Element {
    let mut count = use_signal(|| 0_u64);
    let slots = SLOTS.load(Ordering::Relaxed);
    rsx! {
        Column {
            for slot in 0..slots {
                Text { key: "{slot}", text: format!("{}-{slot}", count()) }
            }
            Button { text: "Increment", on_click: move |_| *count.write() += 1 }
        }
    }
}

/// The root component a scenario's application starts from.
fn root(app: App) -> fn() -> Element {
    let name = match app {
        App::Sweep(slots) => {
            SLOTS.store(slots, Ordering::Relaxed);
            return sweep;
        }
        App::Calculator => "calculator",
        App::Todo => "todo",
        App::Chat => "chat",
        App::Minimal => "minimal",
    };
    dioxus_baseline::screen(name)
        .unwrap_or_else(|| panic!("the baseline has no {name} screen"))
        .app
}

/// The Dioxus Host for whichever application the scenario runs.
struct DioxusPath {
    host: Option<Host>,
}

impl DioxusPath {
    fn host(&mut self) -> &mut Host {
        self.host.as_mut().expect("start was called first")
    }
}

impl Path for DioxusPath {
    fn start(&mut self, app: App) -> Batch {
        FRAME_REQUESTED.store(false, Ordering::Release);
        self.host = None;
        let mut host = Host::new(root(app));
        let batch = convert(host.rebuild().expect("the first batch encodes"));
        self.host = Some(host);
        batch
    }

    fn dispatch(&mut self, event: &Event) -> (Batch, Duration) {
        let payload = match &event.payload {
            Payload::Clicked => EventPayload::Clicked,
            Payload::TextChanged(text) => EventPayload::TextChanged(text),
            Payload::TextSubmitted(text) => EventPayload::TextSubmitted(text),
            Payload::ValueChanged(value) => EventPayload::ValueChanged(*value),
            Payload::RangeRequested { start, count } => EventPayload::RangeRequested {
                start: *start,
                count: *count,
            },
            Payload::WindowSize { width, height } => EventPayload::WindowSizeChanged {
                width_dp: *width,
                height_dp: *height,
                class: WindowSizeClass::from_width_dp(*width),
                height_class: WindowHeightClass::from_height_dp(*height),
            },
        };
        let event = HostEvent {
            node_id: event.node,
            handler_id: event.handler,
            payload,
        };
        let host = self.host();
        let started = Instant::now();
        let (bytes, _) = host.dispatch(event).expect("the event is accepted");
        let elapsed = started.elapsed();
        (convert(bytes), elapsed)
    }

    fn frame(&mut self, frame_time_nanos: u64) -> (Batch, Duration) {
        FRAME_REQUESTED.store(false, Ordering::Release);
        let host = self.host();
        let started = Instant::now();
        let bytes = host
            .render_frame(frame_time_nanos)
            .expect("the frame encodes");
        let elapsed = started.elapsed();
        (convert(bytes), elapsed)
    }

    fn frame_requested(&self) -> bool {
        FRAME_REQUESTED.load(Ordering::Acquire)
    }
}

/// Reduces a batch to records after the clock has stopped.
fn convert(bytes: &[u8]) -> Batch {
    let records = decode_batch(bytes)
        .expect("the batch decodes")
        .into_iter()
        .map(|mutation| match mutation {
            Mutation::Create { node_id, widget } => Record::Create {
                node: node_id,
                widget: format!("{widget:?}"),
            },
            Mutation::SetProp {
                node_id,
                property,
                value,
            } => Record::SetProp {
                node: node_id,
                property: format!("{property:?}"),
                value: match value {
                    PropertyValue::None => Value::None,
                    PropertyValue::String(text) => Value::Text(text.to_owned()),
                    PropertyValue::Bool(value) => Value::Bool(value),
                    PropertyValue::Integer(value) => Value::Int(value),
                    PropertyValue::Float(value) => Value::Float(value.to_bits()),
                    PropertyValue::Bytes(bytes) => Value::Bytes(bytes.to_vec()),
                },
            },
            Mutation::SetModifier {
                node_id,
                index,
                modifier,
            } => Record::SetModifier {
                node: node_id,
                index,
                modifier: format!("{modifier:?}"),
            },
            Mutation::Insert {
                parent_id,
                node_id,
                index,
            } => Record::Insert {
                parent: parent_id,
                node: node_id,
                index,
            },
            Mutation::Move {
                parent_id,
                node_id,
                index,
            } => Record::Move {
                parent: parent_id,
                node: node_id,
                index,
            },
            Mutation::Remove { node_id } => Record::Remove { node: node_id },
            Mutation::SetText { node_id, text, .. } => Record::SetText {
                node: node_id,
                text: text.to_owned(),
            },
            Mutation::AppendText { node_id, text } => Record::AppendText {
                node: node_id,
                text: text.to_owned(),
            },
            other => Record::Other(format!("{other:?}")),
        })
        .collect();
    Batch {
        records,
        bytes: bytes.len(),
    }
}

fn main() {
    // The todo sample loads its list from a file and would otherwise start from whatever
    // the last person to run it left there. The path is in the run's own folder and is
    // never written: the sample saves through a thread nobody starts here.
    // SAFETY: first thing in `main`, before any thread exists.
    unsafe { std::env::set_var("SAMPLE_TODO_FILE", "fr39-todo-never-written.tsv") };
    let _ = install_renderer_api(RendererApi { run, request_frame });
    let mut path = DioxusPath { host: None };
    let mut apps: Vec<App> = SLOT_SWEEP.iter().map(|slots| App::Sweep(*slots)).collect();
    apps.extend([App::Calculator, App::Todo, App::Chat, App::Minimal]);
    fr39_scenarios::main_for(&mut path, "dioxus", &apps);
}

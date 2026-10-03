//! The slot table path, driven through its public Host API at this checkout.

use compose_rust::ComposeHost;
use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
use compose_rust::schema::{EventPayload, WindowHeightClass, WindowSizeClass};
use compose_rust::{RendererApi, install_renderer_api};
use fr39_scenarios::{App, Batch, Event, Path, Payload, Record, Value};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

static FRAME_REQUESTED: AtomicBool = AtomicBool::new(false);

extern "C" fn run() -> i32 {
    0
}

extern "C" fn request_frame() {
    FRAME_REQUESTED.store(true, Ordering::Release);
}

/// The composable Host for one application.
pub struct ComposePath {
    app: fn(),
    host: Option<ComposeHost>,
}

impl ComposePath {
    /// `app` is the root composable every scenario of this binary starts.
    pub fn new(app: fn()) -> Self {
        let _ = install_renderer_api(RendererApi { run, request_frame });
        Self { app, host: None }
    }

    fn host(&mut self) -> &mut ComposeHost {
        self.host.as_mut().expect("start was called first")
    }
}

impl Path for ComposePath {
    fn start(&mut self, _app: App) -> Batch {
        FRAME_REQUESTED.store(false, Ordering::Release);
        self.host = None;
        let mut host = ComposeHost::new(self.app);
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
pub fn convert(bytes: &[u8]) -> Batch {
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

/// Points the samples at state that starts empty on every run.
///
/// The todo sample loads its list from a file and would otherwise start from whatever the
/// last person to run it left there. The path is inside the working directory, which the
/// runner sets to this checkout's scratch area, and it is never written: the sample saves
/// through a thread nobody starts here.
pub fn prepare_environment() {
    // SAFETY: called first thing in `main`, before any thread exists that could read the
    // environment at the same time.
    unsafe {
        std::env::set_var("SAMPLE_TODO_FILE", "fr39-todo-never-written.tsv");
    }
}

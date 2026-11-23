//! The slot table path's runs of every scenario: the sample screens the scenarios were
//! frozen against, written in today's call form (`../screens`), through the composable Host,
//! at this checkout.

use compose_rust::ComposeHost as Host;
use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{EventPayload, WindowHeightClass, WindowSizeClass};
use compose_rust::ui::*;
use compose_rust::{RendererApi, install_renderer_api};
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

/// The same sweep screen as the Dioxus path's, written with composables: a column of keyed
/// texts that all read the same counter, and the button that moves it.
#[Composable]
fn sweep() {
    let count = remember(|| mutable_state_of(0_u64));
    let slots = SLOTS.load(Ordering::Relaxed);
    Column!({
        for slot in 0..slots {
            key(slot, || {
                Text!(format!("{}-{slot}", count.get()));
            });
        }
        let more = count.clone();
        Button!("Increment", move || more.update(|count| *count += 1));
    });
}

/// The root composable a scenario's application starts from.
fn root(app: App) -> fn() {
    match app {
        App::Sweep(slots) => {
            SLOTS.store(slots, Ordering::Relaxed);
            sweep
        }
        App::Calculator => fr39_screen_calculator::app,
        App::Todo => sample_todo::app,
        App::Chat => sample_chat::app,
        App::Minimal => fr39_screen_minimal::app,
    }
}

/// The composable Host for whichever application the scenario runs.
struct ComposePath {
    host: Option<Host>,
}

impl ComposePath {
    fn host(&mut self) -> &mut Host {
        self.host.as_mut().expect("start was called first")
    }
}

impl Path for ComposePath {
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
                safe_area_top: 0.0,
                safe_area_leading: 0.0,
                safe_area_trailing: 0.0,
                safe_area_bottom: 0.0,
            },
        };
        let event = HostEvent::new(event.node, event.handler, payload);
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
    // The todo sample keeps its list in the application's storage only once its launch
    // turns that on, which nothing here does, so every run starts from an empty list and
    // writes nothing.
    let _ = install_renderer_api(RendererApi {
        run,
        request_frame,
        measure: compose_rust::measure::fake_measure,
    });
    let mut path = ComposePath { host: None };
    let mut apps: Vec<App> = SLOT_SWEEP.iter().map(|slots| App::Sweep(*slots)).collect();
    apps.extend([App::Calculator, App::Todo, App::Chat, App::Minimal]);
    fr39_scenarios::main_for(&mut path, "compose", &apps);
}

#[cfg(test)]
mod tests {
    //! The screens the candidate draws answer every step of the frozen scenarios that use
    //! them. Each test runs its scenario once, on the composable Host with the mock
    //! renderer, through the same model of the Renderer's node table the measurement uses:
    //! a step whose control is not on screen when the step comes fails the run, with the
    //! step it failed at. Whether the trees equal the Dioxus path's is `parity.sh`'s
    //! question, which needs the pinned baseline built beside this.

    use super::*;
    use fr39_scenarios::{ScenarioRun, scenarios};
    use std::sync::Mutex;

    /// The frame flag and the installed renderer table are process-wide, so the scenarios
    /// run one at a time even when the test harness runs the tests on several threads.
    static ONE_AT_A_TIME: Mutex<()> = Mutex::new(());

    /// Runs the scenario called `name` once, start to finish, and returns what it recorded.
    fn run_scenario(name: &str) -> ScenarioRun {
        let _turn = ONE_AT_A_TIME
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let _ = install_renderer_api(RendererApi {
            run,
            request_frame,
            measure: compose_rust::measure::fake_measure,
        });
        let scenario = scenarios()
            .into_iter()
            .find(|scenario| scenario.name == name)
            .unwrap_or_else(|| panic!("the scenarios have none called {name}"));
        let mut path = ComposePath { host: None };
        let recorded = fr39_scenarios::run(&mut path, "compose", &scenario, 1, 0)
            .unwrap_or_else(|error| panic!("{name} stopped on the candidate: {error}"));
        assert_eq!(
            recorded.step_trees.len(),
            scenario.steps.len(),
            "{name} recorded a tree for some of its steps only"
        );
        recorded
    }

    #[test]
    fn fr39_screen_calculator_answers_every_step_of_calculator_input() {
        let recorded = run_scenario("calculator_input");
        // The pad with `C` is the one the scenario keys on, and in an expanded window the
        // tape stands beside it.
        assert!(
            recorded.final_tree.contains("Text=Text(\"Standard\")"),
            "the calculator is not showing the pad the scenario keys on:\n{}",
            recorded.final_tree
        );
        assert!(
            recorded.final_tree.contains("Text=Text(\"History\")"),
            "the tape is not beside the keys in an expanded window:\n{}",
            recorded.final_tree
        );
    }

    #[test]
    fn fr39_screen_minimal_answers_every_step_of_tab_switching() {
        let recorded = run_scenario("tab_switching");
        // The last click is Parts, so the sheet ends on the controls group.
        assert!(
            recorded
                .final_tree
                .contains("Text=Text(\"Everything a person can operate.\")"),
            "the sheet did not end on the Parts group:\n{}",
            recorded.final_tree
        );
        for tab in ["Panels", "Colour", "Parts"] {
            assert!(
                recorded
                    .final_tree
                    .contains(&format!("Text=Text(\"{tab}\")")),
                "the strip has no {tab} segment:\n{}",
                recorded.final_tree
            );
        }
    }
}

//! The performance scenarios for the slot table runtime, fixed before the runtime they
//! measure was written, and the harness both authoring paths are driven through.
//!
//! **Frozen.** The scenarios below were committed before the runtime they measure, and
//! changing any of them (a step, a label, a size, the sweep widths, the order) needs the
//! owner's approval. A scenario that moves after the numbers are in is a scenario chosen
//! for its numbers.
//!
//! Two groups:
//!
//! - **The slot sweep.** One click changes N dynamic text slots, for N in [`SLOT_SWEEP`].
//!   The screen is the one the recomposition experiment measured the Dioxus path with: a
//!   column of N texts reading `"{count}-{slot}"` and one button labelled `Increment`.
//! - **Recorded interactions with the real samples.** Calculator input, adding and
//!   deleting tasks, a streamed chat reply, scrolling a five thousand row list through its
//!   window, and switching tabs. Each one is a list of [`Step`]s against the sample's own
//!   screen, written as what a person does (press the key labelled 7, type into the field
//!   whose placeholder is "Message") so the same trace drives either path without knowing
//!   its node or handler ids. No screen here was written for the benchmark.
//!
//! What a run records, per scenario and per path: the Host time of every interaction
//! (handler, recomposition or diff, batch encoding), the time to apply the batch to a model
//! of the Renderer's node table, and their sum; the mutation count and byte count; and a
//! hash of the Renderer-side tree after every step. Two paths whose trees differ after any
//! step did not do the same work, and [`compare`] refuses the comparison.
//!
//! The model applier is a stand-in for the Kotlin interpreter's apply: it does what the
//! interpreter does to its node table for each record (insert into a map, splice a child
//! list, replace a property) and nothing about drawing. It is here so that a path cannot
//! look faster by sending more records for the Renderer to apply. The Renderer's own apply
//! time is measured on the renderer build, which this harness cannot run.

use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashMap};
use std::time::{Duration, Instant};

/// How many dynamic slots one click changes, in the sweep. The widths the recomposition
/// experiment fitted its per-slot cost over.
pub const SLOT_SWEEP: [usize; 6] = [1, 5, 17, 33, 65, 129];

/// How many clicks one pass of a sweep scenario makes.
pub const SWEEP_CLICKS: usize = 20;

/// How long a stream may go without asking for a frame before it counts as finished.
const QUIET: Duration = Duration::from_secs(1);

/// The window every scenario is measured at: expanded, so every sample lays out its widest
/// form and the same form on both paths.
pub const WINDOW: (f32, f32) = (1200.0, 800.0);

/// Which application a scenario runs.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum App {
    /// The sweep screen with this many slots.
    Sweep(usize),
    Calculator,
    Todo,
    Chat,
    Minimal,
}

/// One thing a person does, named by what they can see.
#[derive(Clone, Debug, PartialEq)]
pub enum Step {
    /// The Renderer reports the window's size.
    Resize { width: f32, height: f32 },
    /// Presses the `nth` widget of this kind, in tree order, whose text is `label`.
    Click {
        widget: &'static str,
        label: &'static str,
        nth: usize,
    },
    /// The text field whose placeholder is this now holds `text`.
    Type {
        placeholder: &'static str,
        text: &'static str,
    },
    /// The text field whose placeholder is this submits `text`.
    Submit {
        placeholder: &'static str,
        text: &'static str,
    },
    /// The `nth` checkbox, in tree order, is pressed: it reports the opposite of what it
    /// shows.
    Toggle { nth: usize },
    /// The `nth` lazy list, in tree order, asks for the items in this window.
    Range { list: usize, start: u32, count: u32 },
    /// Frames the Host asked for are served until it stops asking or `timeout_ms` passes.
    /// Each frame is one sample. This is how work that arrives from a worker is measured.
    FramesUntilIdle { timeout_ms: u64 },
}

/// A scenario: an application and what is done to it.
#[derive(Clone, Debug)]
pub struct Scenario {
    pub name: &'static str,
    pub app: App,
    pub steps: Vec<Step>,
}

const fn click(widget: &'static str, label: &'static str) -> Step {
    Step::Click {
        widget,
        label,
        nth: 0,
    }
}

/// Every scenario, in the order they are reported.
pub fn scenarios() -> Vec<Scenario> {
    let resize = Step::Resize {
        width: WINDOW.0,
        height: WINDOW.1,
    };
    let mut all = Vec::new();

    for slots in SLOT_SWEEP {
        let mut steps = vec![resize.clone()];
        steps.extend((0..SWEEP_CLICKS).map(|_| click("Button", "Increment")));
        all.push(Scenario {
            name: sweep_name(slots),
            app: App::Sweep(slots),
            steps,
        });
    }

    // Two sums, a product, a clear and a division with a decimal, keyed the way a person
    // keys them. The labels are the calculator's own: the operators are the Unicode signs
    // its keys carry, not the ASCII ones.
    let keys = [
        "1", "2", "3", "+", "4", "5", "6", "=", "\u{00d7}", "2", "=", "C", "7", ".", "5",
        "\u{00f7}", "3", "=", "\u{2212}", "1", "=", "%", "\u{00b1}", "=",
    ];
    let mut steps = vec![resize.clone()];
    steps.extend(keys.iter().map(|key| click("Button", key)));
    all.push(Scenario {
        name: "calculator_input",
        app: App::Calculator,
        steps,
    });

    // Three tasks added through the composer at the head of the list, the list window
    // opened, the first ticked, then the second and the first deleted through their row
    // menus.
    let field = "Add a task, then press Enter";
    let mut steps = vec![resize.clone()];
    for title in ["Buy milk", "Call the bank", "Water the plants"] {
        steps.push(Step::Type {
            placeholder: field,
            text: title,
        });
        steps.push(Step::Submit {
            placeholder: field,
            text: title,
        });
    }
    steps.push(Step::Range {
        list: 0,
        start: 0,
        count: 20,
    });
    steps.push(Step::Toggle { nth: 0 });
    steps.push(Step::Click {
        widget: "Button",
        label: "\u{22ef}",
        nth: 1,
    });
    steps.push(click("Button", "Delete"));
    steps.push(Step::Click {
        widget: "Button",
        label: "\u{22ef}",
        nth: 0,
    });
    steps.push(click("Button", "Delete"));
    steps.push(Step::Type {
        placeholder: field,
        text: "Return the library books",
    });
    steps.push(Step::Submit {
        placeholder: field,
        text: "Return the library books",
    });
    all.push(Scenario {
        name: "todo_add_delete",
        app: App::Todo,
        steps,
    });

    // Two prompts, each answered by the sample's assistant thread one chunk at a time.
    // The frames that carry the chunks are the measurement.
    let mut steps = vec![resize.clone()];
    for prompt in ["Hello", "How does streaming work?"] {
        steps.push(Step::Type {
            placeholder: "Message",
            text: prompt,
        });
        steps.push(Step::Submit {
            placeholder: "Message",
            text: prompt,
        });
        steps.push(Step::FramesUntilIdle { timeout_ms: 15_000 });
    }
    all.push(Scenario {
        name: "chat_streaming",
        app: App::Chat,
        steps,
    });

    // Five thousand rows from the sample's own About sheet, then the window walked down
    // the list ten rows at a time, a jump far down it, and a jump back to the top.
    let mut steps = vec![resize.clone(), click("Button", "About")];
    steps.push(click("Button", "Add 5000 tasks"));
    for start in (0..=500).step_by(10) {
        steps.push(Step::Range {
            list: 0,
            start,
            count: 30,
        });
    }
    steps.push(Step::Range {
        list: 0,
        start: 4_000,
        count: 30,
    });
    steps.push(Step::Range {
        list: 0,
        start: 0,
        count: 30,
    });
    all.push(Scenario {
        name: "long_list_scroll",
        app: App::Todo,
        steps,
    });

    // The component sheet's three tabs, round three times.
    let mut steps = vec![resize];
    for _ in 0..3 {
        for tab in ["Panels", "Colour", "Parts"] {
            steps.push(click("Button", tab));
        }
    }
    all.push(Scenario {
        name: "tab_switching",
        app: App::Minimal,
        steps,
    });

    all
}

fn sweep_name(slots: usize) -> &'static str {
    match slots {
        1 => "sweep_1",
        5 => "sweep_5",
        17 => "sweep_17",
        33 => "sweep_33",
        65 => "sweep_65",
        129 => "sweep_129",
        _ => "sweep_other",
    }
}

// ----- the records both paths are reduced to ---------------------------------------------

/// A property value, as the Renderer would hold it.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum Value {
    None,
    Text(String),
    Bool(bool),
    Int(i64),
    /// The bits of an `f32`, so equality is exact and the value survives a round trip.
    Float(u32),
    Bytes(Vec<u8>),
}

/// One wire record, by name rather than by tag, so either path's types convert into it.
#[derive(Clone, Debug, PartialEq)]
pub enum Record {
    Create {
        node: u32,
        widget: String,
    },
    SetProp {
        node: u32,
        property: String,
        value: Value,
    },
    SetModifier {
        node: u32,
        index: u16,
        modifier: String,
    },
    Insert {
        parent: u32,
        node: u32,
        index: u32,
    },
    Move {
        parent: u32,
        node: u32,
        index: u32,
    },
    Remove {
        node: u32,
    },
    SetText {
        node: u32,
        text: String,
    },
    AppendText {
        node: u32,
        text: String,
    },
    /// A record about no node: a theme, a window, an asset, a message, a notification.
    Other(String),
}

/// What one Host call produced.
#[derive(Clone, Debug, Default)]
pub struct Batch {
    pub records: Vec<Record>,
    pub bytes: usize,
}

/// An event, addressed the way the Renderer addresses one.
#[derive(Clone, Debug, PartialEq)]
pub struct Event {
    pub node: u32,
    pub handler: u64,
    pub payload: Payload,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Payload {
    Clicked,
    TextChanged(String),
    TextSubmitted(String),
    ValueChanged(f64),
    RangeRequested { start: u32, count: u32 },
    WindowSize { width: f32, height: f32 },
}

/// One authoring path under measurement.
///
/// Implementations call the Host through its public API, time only the Host call, and
/// convert the batch it returned into [`Record`]s after the clock has stopped.
pub trait Path {
    /// Builds the application and returns its first batch.
    fn start(&mut self, app: App) -> Batch;
    /// Delivers one event and returns the batch and the time the Host call took.
    fn dispatch(&mut self, event: &Event) -> (Batch, Duration);
    /// Serves one frame and returns the batch and the time the Host call took.
    fn frame(&mut self, frame_time_nanos: u64) -> (Batch, Duration);
    /// Whether the Host has asked for a frame since the last one was served.
    fn frame_requested(&self) -> bool;
}

// ----- the model of the Renderer's node table -------------------------------------------

#[derive(Clone, Debug, Default)]
struct Node {
    widget: String,
    props: BTreeMap<String, Value>,
    modifiers: BTreeMap<u16, String>,
    children: Vec<u32>,
    parent: Option<u32>,
}

/// The Renderer's node table, as far as the tree it describes goes.
#[derive(Clone, Debug, Default)]
pub struct Model {
    nodes: HashMap<u32, Node>,
}

impl Model {
    pub fn new() -> Self {
        let mut nodes = HashMap::new();
        nodes.insert(
            0,
            Node {
                widget: "Root".to_owned(),
                ..Node::default()
            },
        );
        Self { nodes }
    }

    /// Applies one batch, the way the interpreter applies it to its own table.
    pub fn apply(&mut self, batch: &Batch) {
        for record in &batch.records {
            match record {
                Record::Create { node, widget } => {
                    self.nodes.insert(
                        *node,
                        Node {
                            widget: widget.clone(),
                            ..Node::default()
                        },
                    );
                }
                Record::SetProp {
                    node,
                    property,
                    value,
                } => {
                    if let Some(entry) = self.nodes.get_mut(node) {
                        if *value == Value::None {
                            entry.props.remove(property);
                        } else {
                            entry.props.insert(property.clone(), value.clone());
                        }
                    }
                }
                Record::SetModifier {
                    node,
                    index,
                    modifier,
                } => {
                    if let Some(entry) = self.nodes.get_mut(node) {
                        if modifier == "Empty" {
                            entry.modifiers.remove(index);
                        } else {
                            entry.modifiers.insert(*index, modifier.clone());
                        }
                    }
                }
                Record::Insert {
                    parent,
                    node,
                    index,
                }
                | Record::Move {
                    parent,
                    node,
                    index,
                } => {
                    self.detach(*node);
                    if let Some(entry) = self.nodes.get_mut(parent) {
                        let at = (*index as usize).min(entry.children.len());
                        entry.children.insert(at, *node);
                    }
                    if let Some(entry) = self.nodes.get_mut(node) {
                        entry.parent = Some(*parent);
                    }
                }
                Record::Remove { node } => {
                    self.detach(*node);
                    self.forget(*node);
                }
                Record::SetText { node, text } => {
                    if let Some(entry) = self.nodes.get_mut(node) {
                        entry
                            .props
                            .insert("Text".to_owned(), Value::Text(text.clone()));
                    }
                }
                Record::AppendText { node, text } => {
                    if let Some(entry) = self.nodes.get_mut(node) {
                        match entry.props.get_mut("Text") {
                            Some(Value::Text(existing)) => existing.push_str(text),
                            _ => {
                                entry
                                    .props
                                    .insert("Text".to_owned(), Value::Text(text.clone()));
                            }
                        }
                    }
                }
                Record::Other(_) => {}
            }
        }
    }

    fn detach(&mut self, node: u32) {
        let parent = self.nodes.get(&node).and_then(|entry| entry.parent);
        if let Some(parent) = parent {
            if let Some(entry) = self.nodes.get_mut(&parent) {
                entry.children.retain(|child| *child != node);
            }
        }
        if let Some(entry) = self.nodes.get_mut(&node) {
            entry.parent = None;
        }
    }

    fn forget(&mut self, node: u32) {
        if let Some(entry) = self.nodes.remove(&node) {
            for child in entry.children {
                self.forget(child);
            }
        }
    }

    /// The tree under the root, in a form that does not depend on node or handler ids.
    ///
    /// Event properties carry handler ids, which each path allocates in its own order, so
    /// they are written as present or absent. Everything else is written as it is.
    pub fn dump(&self) -> String {
        let mut out = String::new();
        self.dump_node(0, 0, &mut out);
        out
    }

    fn dump_node(&self, node: u32, depth: usize, out: &mut String) {
        let Some(entry) = self.nodes.get(&node) else {
            return;
        };
        for _ in 0..depth {
            out.push_str("  ");
        }
        out.push_str(&entry.widget);
        for (name, value) in &entry.props {
            if name.starts_with("On") {
                out.push_str(&format!(" {name}=handler"));
            } else {
                out.push_str(&format!(" {name}={value:?}"));
            }
        }
        for (index, modifier) in &entry.modifiers {
            out.push_str(&format!(" m{index}={modifier}"));
        }
        out.push('\n');
        for child in &entry.children {
            self.dump_node(*child, depth + 1, out);
        }
    }

    /// The nodes in tree order.
    fn preorder(&self) -> Vec<u32> {
        let mut order = Vec::new();
        let mut stack = vec![0_u32];
        while let Some(node) = stack.pop() {
            order.push(node);
            if let Some(entry) = self.nodes.get(&node) {
                for child in entry.children.iter().rev() {
                    stack.push(*child);
                }
            }
        }
        order
    }

    fn handler(&self, node: u32, property: &str) -> Option<u64> {
        match self.nodes.get(&node)?.props.get(property)? {
            Value::Int(handler) => Some(*handler as u64),
            _ => None,
        }
    }

    fn text_of(&self, node: u32, property: &str) -> Option<&str> {
        match self.nodes.get(&node)?.props.get(property)? {
            Value::Text(text) => Some(text),
            _ => None,
        }
    }

    fn nth(&self, widget: &str, nth: usize, matches: impl Fn(u32) -> bool) -> Option<u32> {
        self.preorder()
            .into_iter()
            .filter(|node| {
                self.nodes
                    .get(node)
                    .is_some_and(|entry| entry.widget == widget)
            })
            .filter(|node| matches(*node))
            .nth(nth)
    }

    /// The event a step delivers, found in the tree as it stands.
    pub fn event_for(&self, step: &Step) -> Result<Event, String> {
        let missing = || format!("nothing on screen answers {step:?}");
        match step {
            Step::Resize { width, height } => Ok(Event {
                node: 0,
                handler: 0,
                payload: Payload::WindowSize {
                    width: *width,
                    height: *height,
                },
            }),
            Step::Click { widget, label, nth } => {
                let node = self
                    .nth(widget, *nth, |node| {
                        self.text_of(node, "Text") == Some(label)
                    })
                    .ok_or_else(missing)?;
                let handler = self.handler(node, "OnClick").ok_or_else(missing)?;
                Ok(Event {
                    node,
                    handler,
                    payload: Payload::Clicked,
                })
            }
            Step::Type { placeholder, text } | Step::Submit { placeholder, text } => {
                let node = self
                    .nth("TextField", 0, |node| {
                        self.text_of(node, "Placeholder") == Some(placeholder)
                    })
                    .ok_or_else(missing)?;
                let (property, payload) = match step {
                    Step::Type { .. } => ("OnValueChange", Payload::TextChanged((*text).into())),
                    _ => ("OnSubmit", Payload::TextSubmitted((*text).into())),
                };
                let handler = self.handler(node, property).ok_or_else(missing)?;
                Ok(Event {
                    node,
                    handler,
                    payload,
                })
            }
            Step::Toggle { nth } => {
                let node = self.nth("Checkbox", *nth, |_| true).ok_or_else(missing)?;
                let checked = matches!(
                    self.nodes
                        .get(&node)
                        .and_then(|entry| entry.props.get("Checked")),
                    Some(Value::Bool(true))
                );
                let handler = self.handler(node, "OnValueChange").ok_or_else(missing)?;
                Ok(Event {
                    node,
                    handler,
                    payload: Payload::ValueChanged(if checked { 0.0 } else { 1.0 }),
                })
            }
            Step::Range { list, start, count } => {
                let node = self
                    .nth("LazyColumn", *list, |_| true)
                    .ok_or_else(missing)?;
                let handler = self.handler(node, "OnRangeRequested").ok_or_else(missing)?;
                Ok(Event {
                    node,
                    handler,
                    payload: Payload::RangeRequested {
                        start: *start,
                        count: *count,
                    },
                })
            }
            Step::FramesUntilIdle { .. } => Err("frames are not an event".to_owned()),
        }
    }
}

/// A stable hash of a dump, for comparing trees without keeping every dump.
pub fn hash(text: &str) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325_u64;
    for byte in text.bytes() {
        hash ^= u64::from(byte);
        hash = hash.wrapping_mul(0x100_0000_01b3);
    }
    hash
}

// ----- running -----------------------------------------------------------------------------

/// What one path did in one scenario.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct ScenarioRun {
    pub name: String,
    pub path: String,
    pub iterations: usize,
    pub warmup: usize,
    /// Per interaction: the Host call, in nanoseconds.
    pub host_ns: Vec<u64>,
    /// Per interaction: applying its batch to the model node table.
    pub apply_ns: Vec<u64>,
    /// Per interaction: the two together.
    pub frame_ns: Vec<u64>,
    /// Records and bytes over one pass, initial batch excluded.
    pub mutations: usize,
    pub bytes: usize,
    /// The tree after every step of the first measured pass.
    pub step_trees: Vec<u64>,
    /// The tree at the end of the first measured pass, in full.
    pub final_tree: String,
}

/// Runs a scenario `warmup + iterations` times, each from a fresh application, and keeps
/// the measurements of the last `iterations`.
pub fn run(
    path: &mut dyn Path,
    path_name: &str,
    scenario: &Scenario,
    iterations: usize,
    warmup: usize,
) -> Result<ScenarioRun, String> {
    let mut result = ScenarioRun {
        name: scenario.name.to_owned(),
        path: path_name.to_owned(),
        iterations,
        warmup,
        ..ScenarioRun::default()
    };
    for pass in 0..warmup + iterations {
        let measured = pass >= warmup;
        let first = pass == warmup;
        let mut model = Model::new();
        let initial = path.start(scenario.app);
        model.apply(&initial);
        let mut frame_clock: u64 = 0;
        for step in &scenario.steps {
            let mut samples: Vec<(Duration, Duration, Batch)> = Vec::new();
            match step {
                Step::FramesUntilIdle { timeout_ms } => {
                    // Waits up to the timeout for the first frame, then stops once no
                    // request has arrived for a second: the worker has finished.
                    let deadline = Instant::now() + Duration::from_millis(*timeout_ms);
                    let mut quiet_since = Instant::now();
                    let mut started = false;
                    loop {
                        if !path.frame_requested() {
                            let limit_passed = if started {
                                quiet_since.elapsed() > QUIET
                            } else {
                                Instant::now() > deadline
                            };
                            if limit_passed {
                                break;
                            }
                            std::thread::sleep(Duration::from_micros(200));
                            continue;
                        }
                        started = true;
                        quiet_since = Instant::now();
                        frame_clock += 16_666_667;
                        let (batch, host) = path.frame(frame_clock);
                        let started = Instant::now();
                        model.apply(&batch);
                        samples.push((host, started.elapsed(), batch));
                    }
                }
                _ => {
                    let event = model.event_for(step)?;
                    let (batch, host) = path.dispatch(&event);
                    let started = Instant::now();
                    model.apply(&batch);
                    let mut host = host;
                    let mut apply = started.elapsed();
                    let mut combined = batch;
                    // Frames the interaction asked for are part of it: work a path
                    // defers to a frame is still work the interaction caused.
                    let mut served = 0;
                    while path.frame_requested() && served < 8 {
                        frame_clock += 16_666_667;
                        let (batch, frame_host) = path.frame(frame_clock);
                        let started = Instant::now();
                        model.apply(&batch);
                        apply += started.elapsed();
                        host += frame_host;
                        combined.bytes += batch.bytes;
                        combined.records.extend(batch.records);
                        served += 1;
                    }
                    samples.push((host, apply, combined));
                }
            }
            if measured {
                for (host, apply, batch) in &samples {
                    let host = host.as_nanos() as u64;
                    let apply = apply.as_nanos() as u64;
                    result.host_ns.push(host);
                    result.apply_ns.push(apply);
                    result.frame_ns.push(host + apply);
                    if first {
                        result.mutations += batch.records.len();
                        result.bytes += batch.bytes;
                    }
                }
                if first {
                    result.step_trees.push(hash(&model.dump()));
                }
            }
        }
        if first {
            result.final_tree = model.dump();
        }
    }
    Ok(result)
}

/// The value at quantile `q` of `samples`, by nearest rank.
pub fn quantile(samples: &[u64], q: f64) -> u64 {
    if samples.is_empty() {
        return 0;
    }
    let mut sorted = samples.to_vec();
    sorted.sort_unstable();
    let rank = ((q * sorted.len() as f64).ceil() as usize).clamp(1, sorted.len());
    sorted[rank - 1]
}

/// One scenario's verdict.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Comparison {
    pub name: String,
    pub valid: bool,
    pub reason: String,
    pub baseline_p50_ns: u64,
    pub baseline_p99_ns: u64,
    pub baseline_max_ns: u64,
    pub candidate_p50_ns: u64,
    pub candidate_p99_ns: u64,
    pub candidate_max_ns: u64,
    pub p50_ratio: f64,
    pub p99_ratio: f64,
    pub baseline_mutations: usize,
    pub candidate_mutations: usize,
    pub baseline_bytes: usize,
    pub candidate_bytes: usize,
}

/// Compares one scenario's two runs by whole-interaction time.
///
/// Invalid when the trees differ after any step, or when the candidate sent more records
/// or more bytes than the baseline. An invalid comparison has no ratio worth reading, and
/// the summary does not count it as passing.
pub fn compare(baseline: &ScenarioRun, candidate: &ScenarioRun) -> Comparison {
    let mut reasons = Vec::new();
    if baseline.step_trees != candidate.step_trees {
        let step = baseline
            .step_trees
            .iter()
            .zip(&candidate.step_trees)
            .position(|(left, right)| left != right)
            .unwrap_or(baseline.step_trees.len().min(candidate.step_trees.len()));
        reasons.push(format!("the Renderer trees differ after step {step}"));
    }
    if candidate.mutations > baseline.mutations {
        reasons.push(format!(
            "the candidate sent {} records against the baseline's {}",
            candidate.mutations, baseline.mutations
        ));
    }
    if candidate.bytes > baseline.bytes {
        reasons.push(format!(
            "the candidate sent {} bytes against the baseline's {}",
            candidate.bytes, baseline.bytes
        ));
    }
    let b50 = quantile(&baseline.frame_ns, 0.50);
    let b99 = quantile(&baseline.frame_ns, 0.99);
    let c50 = quantile(&candidate.frame_ns, 0.50);
    let c99 = quantile(&candidate.frame_ns, 0.99);
    Comparison {
        name: baseline.name.clone(),
        valid: reasons.is_empty(),
        reason: reasons.join("; "),
        baseline_p50_ns: b50,
        baseline_p99_ns: b99,
        baseline_max_ns: baseline.frame_ns.iter().copied().max().unwrap_or(0),
        candidate_p50_ns: c50,
        candidate_p99_ns: c99,
        candidate_max_ns: candidate.frame_ns.iter().copied().max().unwrap_or(0),
        p50_ratio: b50 as f64 / c50.max(1) as f64,
        p99_ratio: b99 as f64 / c99.max(1) as f64,
        baseline_mutations: baseline.mutations,
        candidate_mutations: candidate.mutations,
        baseline_bytes: baseline.bytes,
        candidate_bytes: candidate.bytes,
    }
}

/// The geometric mean of a set of ratios.
pub fn geometric_mean(ratios: &[f64]) -> f64 {
    if ratios.is_empty() {
        return 0.0;
    }
    let sum: f64 = ratios
        .iter()
        .map(|ratio| ratio.max(f64::MIN_POSITIVE).ln())
        .sum();
    (sum / ratios.len() as f64).exp()
}

/// The target: p50 ratios' geometric mean at least 10, p99 ratios' at least 5, every
/// comparison valid, and no scenario slower than the baseline at p50 or p99.
pub fn verdict(comparisons: &[Comparison]) -> (bool, f64, f64) {
    let p50: Vec<f64> = comparisons.iter().map(|c| c.p50_ratio).collect();
    let p99: Vec<f64> = comparisons.iter().map(|c| c.p99_ratio).collect();
    let g50 = geometric_mean(&p50);
    let g99 = geometric_mean(&p99);
    let all_valid = comparisons.iter().all(|c| c.valid);
    let none_slower = comparisons
        .iter()
        .all(|c| c.p50_ratio >= 1.0 && c.p99_ratio >= 1.0);
    (
        all_valid && none_slower && g50 >= 10.0 && g99 >= 5.0,
        g50,
        g99,
    )
}

/// Command line shared by both paths' binaries: `--scenario NAME` (repeatable, default
/// every scenario this binary's application runs), `--iterations N`, `--warmup N`,
/// `--out FILE`.
pub struct Args {
    pub scenarios: Vec<String>,
    pub iterations: usize,
    pub warmup: usize,
    pub out: std::path::PathBuf,
}

impl Args {
    pub fn parse() -> Self {
        let mut args = Self {
            scenarios: Vec::new(),
            iterations: 50,
            warmup: 5,
            out: std::path::PathBuf::from("fr39-run.json"),
        };
        let mut given = std::env::args().skip(1);
        while let Some(flag) = given.next() {
            let value = given.next().unwrap_or_default();
            match flag.as_str() {
                "--scenario" => args.scenarios.push(value),
                "--iterations" => args.iterations = value.parse().unwrap_or(args.iterations),
                "--warmup" => args.warmup = value.parse().unwrap_or(args.warmup),
                "--out" => args.out = value.into(),
                other => panic!("unknown argument {other}"),
            }
        }
        args
    }
}

/// Runs every scenario of the given applications on one path and writes the runs out.
pub fn main_for(path: &mut dyn Path, path_name: &str, apps: &[App]) {
    let args = Args::parse();
    let mut runs = Vec::new();
    for scenario in scenarios() {
        let ours = match scenario.app {
            App::Sweep(_) => apps.iter().any(|app| matches!(app, App::Sweep(_))),
            app => apps.contains(&app),
        };
        if !ours {
            continue;
        }
        if !args.scenarios.is_empty() && !args.scenarios.iter().any(|name| name == scenario.name) {
            continue;
        }
        eprintln!("{path_name}: {}", scenario.name);
        match run(path, path_name, &scenario, args.iterations, args.warmup) {
            Ok(run) => runs.push(run),
            Err(error) => {
                eprintln!("{path_name}: {} could not run: {error}", scenario.name);
                std::process::exit(1);
            }
        }
    }
    let text = serde_json::to_string_pretty(&runs).expect("runs serialize");
    std::fs::write(&args.out, text).expect("the run file could not be written");
}

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
///
/// Node 0 is the virtual root. Its children are the table's roots: every node created and
/// not yet placed under a parent, in creation order, and every node inserted under node 0.
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
                    // A node the Host creates and never inserts is a root, drawn under the
                    // virtual root in the order it was created, until an Insert or a Move
                    // places it under a parent. The Renderer's node table does the same:
                    // no record attaches the application's top node, so the Dioxus path
                    // only ever creates it, while the slot table path also inserts it under
                    // node 0. Either way it lands in the same place, and this adds no
                    // record of its own.
                    let new = !self.nodes.contains_key(node);
                    self.nodes.insert(
                        *node,
                        Node {
                            widget: widget.clone(),
                            parent: new.then_some(0),
                            ..Node::default()
                        },
                    );
                    if new && let Some(root) = self.nodes.get_mut(&0) {
                        root.children.push(*node);
                    }
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
        if let Some(parent) = parent
            && let Some(entry) = self.nodes.get_mut(&parent)
        {
            entry.children.retain(|child| *child != node);
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
    /// they are written as present or absent. A node's Modifiers are written in slot order
    /// without their slot numbers, which belong to the protocol version that wrote them:
    /// the Renderer applies the slots that are set in order, so the order is what the tree
    /// holds. Everything else is written as it is.
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
        // In slot order, without the slot's number: the number is the wire's, and the
        // protocol renumbered the slots between the two revisions compared here.
        for modifier in entry.modifiers.values() {
            out.push_str(&format!(" modifier={modifier}"));
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

/// The first line at which two dumps differ: its number from 1, and the line on each side.
///
/// A side that has run out of lines gives an empty line. `None` when the dumps are equal.
pub fn first_difference(baseline: &str, candidate: &str) -> Option<(usize, String, String)> {
    let mut left = baseline.lines();
    let mut right = candidate.lines();
    let mut number = 1;
    loop {
        match (left.next(), right.next()) {
            (None, None) => return None,
            (a, b) if a == b => number += 1,
            (a, b) => {
                return Some((
                    number,
                    a.unwrap_or("").trim_start().to_owned(),
                    b.unwrap_or("").trim_start().to_owned(),
                ));
            }
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
    /// Set when the trees are equal but the candidate sent more records or more bytes.
    #[serde(default)]
    pub record_violation: Option<RecordViolation>,
}

/// Equal trees, but more records or bytes than the baseline: the timings still compare the
/// same work, so the run goes on, and the verdict cannot be met.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct RecordViolation {
    pub scenario: String,
    pub candidate_records: usize,
    pub baseline_records: usize,
    pub excess_records: usize,
    pub candidate_bytes: usize,
    pub baseline_bytes: usize,
    pub excess_bytes: usize,
}

impl std::fmt::Display for RecordViolation {
    /// One line that starts with `violation <scenario>:`, which the scripts read back.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "violation {}: breaks the rule that the candidate sends no more records or bytes \
             than the baseline: {} records against the baseline's {} (+{}), {} bytes against \
             the baseline's {} (+{})",
            self.scenario,
            self.candidate_records,
            self.baseline_records,
            self.excess_records,
            self.candidate_bytes,
            self.baseline_bytes,
            self.excess_bytes
        )
    }
}

/// Compares one scenario's two runs by whole-interaction time.
///
/// Invalid when the trees differ after any step: the two paths did not do the same work,
/// so no ratio is worth reading. A candidate that built the same trees but sent more
/// records or bytes is valid and carries a [`RecordViolation`] instead. Neither counts as
/// passing in the verdict.
pub fn compare(baseline: &ScenarioRun, candidate: &ScenarioRun) -> Comparison {
    let mut reasons = Vec::new();
    let trees_differ = baseline.step_trees != candidate.step_trees;
    if trees_differ {
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
    // With equal trees the only reasons left are the counts, which make a violation and
    // not an invalid run. With differing trees the message stays whole.
    let excess = !trees_differ && !reasons.is_empty();
    if excess {
        reasons.clear();
    }
    Comparison {
        name: baseline.name.clone(),
        valid: !trees_differ,
        reason: reasons.join("; "),
        record_violation: excess.then(|| RecordViolation {
            scenario: baseline.name.clone(),
            candidate_records: candidate.mutations,
            baseline_records: baseline.mutations,
            excess_records: candidate.mutations.saturating_sub(baseline.mutations),
            candidate_bytes: candidate.bytes,
            baseline_bytes: baseline.bytes,
            excess_bytes: candidate.bytes.saturating_sub(baseline.bytes),
        }),
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
/// comparison valid and without a record violation, and no scenario slower than the
/// baseline at p50 or p99.
pub fn verdict(comparisons: &[Comparison]) -> (bool, f64, f64) {
    let p50: Vec<f64> = comparisons.iter().map(|c| c.p50_ratio).collect();
    let p99: Vec<f64> = comparisons.iter().map(|c| c.p99_ratio).collect();
    let g50 = geometric_mean(&p50);
    let g99 = geometric_mean(&p99);
    let all_valid = comparisons
        .iter()
        .all(|c| c.valid && c.record_violation.is_none());
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

#[cfg(test)]
mod tests {
    //! The model's roots, against what the Renderer's node table
    //! (`renderer/desktop/src/renderer/NodeTable.kt`) does with the same records. Each test
    //! names the NodeTable behaviour it copies.

    use super::*;

    fn create(node: u32, widget: &str) -> Record {
        Record::Create {
            node,
            widget: widget.to_owned(),
        }
    }

    fn text(node: u32, value: &str) -> Record {
        Record::SetProp {
            node,
            property: "Text".to_owned(),
            value: Value::Text(value.to_owned()),
        }
    }

    fn on_click(node: u32, handler: i64) -> Record {
        Record::SetProp {
            node,
            property: "OnClick".to_owned(),
            value: Value::Int(handler),
        }
    }

    fn insert(parent: u32, node: u32, index: u32) -> Record {
        Record::Insert {
            parent,
            node,
            index,
        }
    }

    fn batch(records: Vec<Record>) -> Batch {
        Batch {
            bytes: records.len() * 16,
            records,
        }
    }

    fn model(batches: &[Vec<Record>]) -> Model {
        let mut model = Model::new();
        for records in batches {
            model.apply(&batch(records.clone()));
        }
        model
    }

    /// The sweep screen's shape the way the Dioxus path sends it at the pinned revision: the
    /// column is created and filled, and never inserted anywhere.
    fn only_created(column: u32, label: u32, button: u32, handler: i64) -> Vec<Record> {
        vec![
            create(column, "Column"),
            create(label, "Text"),
            text(label, "0-0"),
            insert(column, label, 0),
            create(button, "Button"),
            text(button, "Increment"),
            on_click(button, handler),
            insert(column, button, 1),
        ]
    }

    /// The same screen the way the slot table path sends it: the column is also inserted
    /// under node 0.
    fn inserted_under_the_root(column: u32, label: u32, button: u32, handler: i64) -> Vec<Record> {
        let mut records = only_created(column, label, button, handler);
        records.push(insert(0, column, 0));
        records
    }

    // A parity failure has to say what differs, so the first differing line is found
    // without its indentation and with its line number.
    #[test]
    fn fr39_first_difference_names_the_line_and_both_sides() {
        let baseline = "Root\n  Column\n    Button Text=Text(\"a\")\n";
        let candidate = "Root\n  Column\n    Button Kind=Int(0) Text=Text(\"a\")\n";
        assert_eq!(
            first_difference(baseline, candidate),
            Some((
                3,
                "Button Text=Text(\"a\")".to_owned(),
                "Button Kind=Int(0) Text=Text(\"a\")".to_owned()
            ))
        );
        assert_eq!(first_difference(baseline, baseline), None);
        assert_eq!(
            first_difference("Root\n", "Root\n  Column\n"),
            Some((2, String::new(), "Column".to_owned()))
        );
    }

    fn modifier(node: u32, index: u16, modifier: &str) -> Record {
        Record::SetModifier {
            node,
            index,
            modifier: modifier.to_owned(),
        }
    }

    // A Modifier's slot number belongs to the protocol version that wrote it. The pinned
    // Dioxus path writes a shape in slot 5 and a padding in slot 10, and this checkout
    // writes them in 12 and 22. NodeTable.setModifier keeps a node's Modifiers in a list
    // indexed by slot and applies the ones that are set in that order, so what a tree
    // holds is the Modifiers and their order.
    #[test]
    fn fr39_model_shows_the_same_tree_when_only_the_slot_numbers_differ() {
        let pinned = model(&[vec![
            create(1, "Surface"),
            modifier(1, 10, "PaddingRole(Md)"),
            modifier(1, 5, "ShapeRole(Large)"),
        ]]);
        let today = model(&[vec![
            create(1, "Surface"),
            modifier(1, 12, "ShapeRole(Large)"),
            modifier(1, 22, "PaddingRole(Md)"),
        ]]);
        assert_eq!(pinned.dump(), today.dump());
    }

    // The order is still part of the tree: the same two Modifiers the other way round are
    // applied the other way round.
    #[test]
    fn fr39_model_tells_apart_modifiers_in_another_order() {
        let shape_first = model(&[vec![
            create(1, "Surface"),
            modifier(1, 5, "ShapeRole(Large)"),
            modifier(1, 10, "PaddingRole(Md)"),
        ]]);
        let padding_first = model(&[vec![
            create(1, "Surface"),
            modifier(1, 5, "PaddingRole(Md)"),
            modifier(1, 10, "ShapeRole(Large)"),
        ]]);
        assert_ne!(shape_first.dump(), padding_first.dump());
    }

    // Clearing a slot takes its Modifier out of the tree, whatever the slot's number.
    #[test]
    fn fr39_model_drops_a_cleared_modifier() {
        let cleared = model(&[vec![
            create(1, "Surface"),
            modifier(1, 5, "ShapeRole(Large)"),
            modifier(1, 10, "PaddingRole(Md)"),
            modifier(1, 5, "Empty"),
        ]]);
        let never_set = model(&[vec![
            create(1, "Surface"),
            modifier(1, 22, "PaddingRole(Md)"),
        ]]);
        assert_eq!(cleared.dump(), never_set.dump());
    }

    const SWEEP_SCREEN: &str = "Root\n  Column\n    Text Text=Text(\"0-0\")\n    Button OnClick=handler Text=Text(\"Increment\")\n";

    // NodeTable.create adds every created node to the root list; ResyncTest pins that a
    // Column created and never inserted is the only root, and the Texts inserted under it
    // in the same batch are not roots.
    #[test]
    fn fr39_model_keeps_a_created_never_inserted_node_as_a_root() {
        let model = model(&[only_created(1, 2, 3, 7)]);
        assert_eq!(model.dump(), SWEEP_SCREEN);
    }

    // NodeTable.roots is "top-level nodes, in creation order until the Host parents them":
    // create appends to the root list, so the order is creation order, not id order.
    #[test]
    fn fr39_model_orders_inferred_roots_by_creation_not_by_id() {
        let model = model(&[vec![
            create(5, "Text"),
            text(5, "first"),
            create(3, "Text"),
            text(3, "second"),
        ]]);
        assert_eq!(
            model.dump(),
            "Root\n  Text Text=Text(\"first\")\n  Text Text=Text(\"second\")\n"
        );
    }

    // NodeTable.insert under the root id splices into the root list itself (childrenOf(0)
    // is the root list), at the index the record gives, after taking the node out of it.
    #[test]
    fn fr39_model_places_an_insert_under_the_root_among_the_inferred_roots() {
        let model = model(&[vec![
            create(1, "Text"),
            text(1, "a"),
            create(2, "Text"),
            text(2, "b"),
            insert(0, 2, 0),
        ]]);
        assert_eq!(
            model.dump(),
            "Root\n  Text Text=Text(\"b\")\n  Text Text=Text(\"a\")\n"
        );
    }

    // NodeTable.detach takes the node out of the root list whatever its parent, so a root
    // that a later batch inserts under a parent is there once, under that parent.
    #[test]
    fn fr39_model_moves_an_inferred_root_under_the_parent_it_is_inserted_into() {
        let mut model = model(&[vec![
            create(1, "Column"),
            create(2, "Text"),
            text(2, "child"),
        ]]);
        assert_eq!(
            model.dump(),
            "Root\n  Column\n  Text Text=Text(\"child\")\n"
        );
        model.apply(&batch(vec![insert(1, 2, 0)]));
        assert_eq!(
            model.dump(),
            "Root\n  Column\n    Text Text=Text(\"child\")\n"
        );
        assert_eq!(
            model.preorder().iter().filter(|node| **node == 2).count(),
            1
        );
    }

    // NodeTable.remove detaches the node, which takes it out of the root list, and drops its
    // subtree from the table.
    #[test]
    fn fr39_model_drops_an_inferred_root_on_remove() {
        let mut model = model(&[
            only_created(1, 2, 3, 7),
            vec![create(9, "Text"), text(9, "other")],
        ]);
        model.apply(&batch(vec![Record::Remove { node: 9 }]));
        assert_eq!(model.dump(), SWEEP_SCREEN);
        model.apply(&batch(vec![Record::Remove { node: 1 }]));
        assert_eq!(model.dump(), "Root\n");
        assert!(
            !model.nodes.contains_key(&3),
            "the removed root's subtree is gone"
        );
    }

    // NodeTable.create puts a node on the root list and NodeTable.insert takes it off again
    // in the same batch, so a node created and inserted under a parent is never a root.
    #[test]
    fn fr39_model_does_not_make_roots_of_nodes_created_and_inserted_elsewhere() {
        let model = model(&[vec![
            create(1, "Column"),
            create(2, "Text"),
            text(2, "a"),
            insert(1, 2, 0),
            create(3, "Text"),
            text(3, "b"),
            insert(1, 3, u32::MAX),
        ]]);
        assert_eq!(model.nodes[&0].children, vec![1]);
        assert_eq!(
            model.dump(),
            "Root\n  Column\n    Text Text=Text(\"a\")\n    Text Text=Text(\"b\")\n"
        );
    }

    // NodeTable draws its root list whether a node got there by being created or by being
    // inserted under the root id, so the two paths' trees are the same tree, and so are
    // their hashes. Different ids and handler ids on each side do not matter.
    #[test]
    fn fr39_model_shows_the_same_tree_whether_the_top_node_is_inserted_or_only_created() {
        let baseline = model(&[only_created(1, 2, 3, 7)]);
        let candidate = model(&[inserted_under_the_root(40, 41, 42, 99)]);
        assert_eq!(baseline.dump(), candidate.dump());
        assert_eq!(hash(&baseline.dump()), hash(&candidate.dump()));

        // An append index, and an Insert under the root sent before the children, land in
        // the same place.
        let mut early = vec![create(40, "Column"), insert(0, 40, u32::MAX)];
        early.extend(only_created(40, 41, 42, 99).into_iter().skip(1));
        assert_eq!(model(&[early]).dump(), baseline.dump());

        let click = click("Button", "Increment");
        let found = baseline
            .event_for(&click)
            .expect("the baseline's button is on screen");
        assert_eq!((found.node, found.handler), (3, 7));
        let found = candidate
            .event_for(&click)
            .expect("the candidate's button is on screen");
        assert_eq!((found.node, found.handler), (42, 99));
    }

    /// A path that answers with fixed batches, so what the run counts can be checked against
    /// what was sent.
    struct Fixed {
        start: Vec<Record>,
        reply: Vec<Record>,
        clicked: Vec<Event>,
    }

    impl Path for Fixed {
        fn start(&mut self, _app: App) -> Batch {
            batch(self.start.clone())
        }
        fn dispatch(&mut self, event: &Event) -> (Batch, Duration) {
            self.clicked.push(event.clone());
            (batch(self.reply.clone()), Duration::from_nanos(1))
        }
        fn frame(&mut self, _frame_time_nanos: u64) -> (Batch, Duration) {
            (Batch::default(), Duration::ZERO)
        }
        fn frame_requested(&self) -> bool {
            false
        }
    }

    // The root rule changes where a node is drawn and nothing about what was sent: the
    // driver finds the Dioxus path's button, and the records and bytes counted are exactly
    // those of the batches the path returned.
    #[test]
    fn fr39_an_inferred_root_is_clickable_and_adds_no_record() {
        let mut path = Fixed {
            start: only_created(1, 2, 3, 7),
            reply: vec![text(2, "1-0")],
            clicked: Vec::new(),
        };
        let scenario = Scenario {
            name: "sweep_1",
            app: App::Sweep(1),
            steps: vec![click("Button", "Increment"), click("Button", "Increment")],
        };
        let run = run(&mut path, "fixed", &scenario, 1, 0).expect("the button is found");
        assert_eq!(path.clicked.len(), 2);
        assert!(
            path.clicked
                .iter()
                .all(|event| event.node == 3 && event.handler == 7)
        );
        assert_eq!(run.mutations, 2);
        assert_eq!(run.bytes, 2 * 16);
        assert_eq!(run.step_trees.len(), 2);
    }

    /// A run of `tab_switching` fast enough to meet the timing target on its own.
    fn timed(frame_ns: u64, mutations: usize, bytes: usize, trees: &[u64]) -> ScenarioRun {
        ScenarioRun {
            name: "tab_switching".to_owned(),
            frame_ns: vec![frame_ns],
            mutations,
            bytes,
            step_trees: trees.to_vec(),
            ..ScenarioRun::default()
        }
    }

    // More records or bytes over the same trees is recorded with both counts and lets the
    // run go on, and the verdict is not met even though every timing would meet it.
    #[test]
    fn fr39_compare_reports_a_count_excess_as_a_violation_not_a_void_run() {
        let baseline = timed(1000, 3386, 79727, &[1, 2]);
        let candidate = timed(10, 3401, 80339, &[1, 2]);
        let c = compare(&baseline, &candidate);
        assert!(c.valid);
        assert_eq!(c.reason, "");
        let violation = c.record_violation.clone().expect("a violation is recorded");
        assert_eq!(
            violation.to_string(),
            "violation tab_switching: breaks the rule that the candidate sends no more \
             records or bytes than the baseline: 3401 records against the baseline's 3386 \
             (+15), 80339 bytes against the baseline's 79727 (+612)"
        );
        assert!(!verdict(&[c]).0);
        let clean = compare(&baseline, &timed(10, 3386, 79727, &[1, 2]));
        assert!(clean.valid && clean.record_violation.is_none());
        assert!(verdict(&[clean]).0);
    }

    // Different trees mean different work: the run is void, with the message it always had,
    // and a count excess in the same scenario does not turn it into a mere violation.
    #[test]
    fn fr39_compare_still_voids_a_run_whose_trees_differ() {
        let baseline = timed(1000, 3386, 79727, &[1, 2]);
        let c = compare(&baseline, &timed(10, 3386, 79727, &[1, 3]));
        assert!(!c.valid && c.record_violation.is_none());
        assert_eq!(c.reason, "the Renderer trees differ after step 1");
        let both = compare(&baseline, &timed(10, 3401, 79727, &[1, 3]));
        assert!(!both.valid && both.record_violation.is_none());
        assert_eq!(
            both.reason,
            "the Renderer trees differ after step 1; the candidate sent 3401 records against \
             the baseline's 3386"
        );
        assert!(!verdict(&[both]).0);
    }
}

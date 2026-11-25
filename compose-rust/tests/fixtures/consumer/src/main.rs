//! An application that depends on compose-rust and nothing else.
//!
//! The tree is built by a runtime written by hand, a column with one text in it, so no
//! authoring layer stands between this program and the boundary.
//!
//! Run with `--launch` to open the window. Without it the program returns as soon as it
//! starts, which is what `scripts/tests/consumer-crate.test.sh` wants: by the time `main`
//! runs, the loader has already had to find the renderer, resolve it, and bind the
//! `compose_rust_host_*` symbols it calls back into. Those are the three things that
//! used to fail, and all three happen before the first line of this function.
//!
//! `--self-check` opens the window as well and exits 0 only if the renderer drew. Starting
//! is not enough to prove that: on Linux the renderer looks the Host's functions up in the
//! executable only once it runs, so an application that started cleanly could still die
//! with "undefined symbol" the moment the window came up. With `COMPOSE_RUST_AUTOEXIT_MS`
//! set the renderer closes its window and the check waits for that, so a clean shutdown is
//! part of what passes. Without it, for a renderer that cannot close its own window (the
//! Kotlin/Native one on Linux), the check ends the process itself once the frames are in.
//!
//! `--deep-tree=<depth>`, with `--self-check`, puts a tree nested that many levels deep under
//! the screen: columns, rows and boxes in turn, each with modifiers, inside a scrolling
//! column, with texts along the way. The renderer composes, measures and draws a tree by
//! recursion, so the depth it can hold is the stack of the thread it runs on. Released
//! applications overflowed a 1 MB Windows main thread on their first frames, and nothing
//! had ever drawn a tree deeper than three levels to notice. `scripts/check-windows-consumer.sh`
//! finds the first depth that overflows with the UI thread's stack held to the megabyte it
//! used to have (`COMPOSE_RUST_UI_STACK_BYTES`), and that depth then has to draw as the
//! application is.
//!
//! `--input-check` opens a window with a text field and a button, for a person typing Korean
//! into it and for `.github/scripts/check-linux-input-access.sh`, which drives it with a real
//! input method and a real accessibility registry. It stays open until it is killed and says
//! on standard output what its handlers heard: the field's text each time it changes, and
//! each press of the button. That is the only way the check can tell that an event arrived
//! all the way at the Host.
//!
//! `--parity` is that window with a title and a smallest size of its own, for
//! `scripts/parity/linux.sh`, which reads both back from the display server.
//!
//! The call to launch stays in the binary because the branch is decided at run time.
//! That is what makes the renderer a load-time dependency of this executable rather than
//! a library the linker drops for being unused.

use compose_rust::boundary::STATUS_OK;
use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, ProtocolError};
use compose_rust::runtime::{Composable, MutableState, Recomposer, mutable_state_of};
use compose_rust::schema::{
    ColorRole, EventPayload, Modifier, Paint, PropertyKind, WidgetKind, Window,
};
use compose_rust::ui::*;
use compose_rust::{Batch, LaunchBuilder, Runtime};
use std::ffi::c_int;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::task::{Context, Poll};
use std::time::{Duration, Instant};

const COLUMN: u32 = 1;
const TEXT: u32 = 2;
const KOREAN: u32 = 3;

/// The highest step the self-check reached. Read after the renderer loop has ended.
static STEP: AtomicU32 = AtomicU32::new(0);

/// How many frames the renderer has to have driven before the check passes.
///
/// Each step is rendered inside `compose_rust_host_render_frame`, which
/// the renderer calls only from its window's frame clock, and the next step is asked for
/// while that frame is being produced. So reaching step 2 means the renderer applied step
/// 1's changes, drew that frame, and came back for another one: a whole frame went
/// through, not only the start of one.
const FRAMES: u32 = 2;

/// The scrolling column the deep tree hangs under, and the first id of the tree itself.
const DEEP_SCROLL: u32 = 100;
const DEEP_FIRST: u32 = 1000;

/// What a level of the deep tree is: a column, a row or a box, in turn.
fn deep_widget(level: u32) -> WidgetKind {
    match level % 3 {
        0 => WidgetKind::Column,
        1 => WidgetKind::Row,
        _ => WidgetKind::Box,
    }
}

/// How often a level of the deep tree carries a text beside the next level. A multiple of
/// three, so the level is a column and the text sits above what is nested in it.
const DEEP_TEXT_EVERY: u32 = 30;

/// One column holding one text. With `counting` set, every frame moves the text one step
/// on and asks for the next frame, until `FRAMES` steps have been drawn.
struct Screen {
    batch: Batch,
    counting: bool,
    step: u32,
    /// How many levels of containers to nest under the screen, 0 for none.
    deep_tree: u32,
}

impl Screen {
    fn new(counting: bool) -> Self {
        Self {
            batch: Batch::new(),
            counting,
            step: 0,
            deep_tree: 0,
        }
    }

    fn with_deep_tree(mut self, depth: u32) -> Self {
        self.deep_tree = depth;
        self
    }

    /// Nests `deep_tree` containers under the screen, each inside the one before.
    ///
    /// What an application's screens are made of, only deeper: columns, rows and boxes in
    /// turn, a padding on every fourth, a background on every fifth and a border on every
    /// seventh, so the levels carry modifier nodes as real ones do and each costs the
    /// renderer what a real one costs. All of it is inside one scrolling column, which
    /// gives the tree all the height it asks for. The paddings are small enough that the
    /// innermost level still has width at any depth this is run at.
    fn deep_tree(&mut self) {
        if self.deep_tree == 0 {
            return;
        }
        self.batch.write(Mutation::Create {
            node_id: DEEP_SCROLL,
            widget: WidgetKind::ScrollColumn,
        });
        // Nothing here fills its parent's width. A child that does makes the column above
        // it ask for its narrowest width first, and the renderer answers that by asking
        // every level three questions, each of which asks the level below three more: a
        // tree 400 levels deep did not finish its first frame in two minutes that way.
        let mut parent = DEEP_SCROLL;
        // Where in its parent the next level goes: after the parent's text, if it has one.
        let mut slot = 0;
        // Two ids a level: the container, and the text some levels carry.
        for level in 0..self.deep_tree {
            let node_id = DEEP_FIRST + level * 2;
            self.batch.write(Mutation::Create {
                node_id,
                widget: deep_widget(level),
            });
            let modifiers = [
                (level % 4 == 0).then_some(Modifier::Padding(0.25)),
                (level % 5 == 0)
                    .then_some(Modifier::Background(Paint::Role(ColorRole::SurfaceVariant))),
                (level % 7 == 0).then_some(Modifier::Border {
                    width: 0.25,
                    paint: Paint::Role(ColorRole::Primary),
                }),
            ];
            for (index, modifier) in modifiers.into_iter().flatten().enumerate() {
                self.batch.write(Mutation::SetModifier {
                    node_id,
                    index: index as u16,
                    modifier,
                });
            }
            self.batch.write(Mutation::Insert {
                parent_id: parent,
                node_id,
                index: slot,
            });
            slot = 0;
            if level % DEEP_TEXT_EVERY == 0 {
                self.deep_text(node_id, node_id + 1, &format!("level {level}"));
                slot = 1;
            }
            parent = node_id;
        }
        // The innermost level holds a text of its own, so the recursion ends in something
        // that is measured and drawn and not in an empty box.
        let innermost = DEEP_FIRST + self.deep_tree * 2;
        self.batch.write(Mutation::Create {
            node_id: innermost,
            widget: WidgetKind::Text,
        });
        self.batch.write(Mutation::SetProp {
            node_id: innermost,
            property: PropertyKind::Text,
            value: PropertyValue::String("the innermost level, 가장 안쪽"),
        });
        self.batch.write(Mutation::Insert {
            parent_id: parent,
            node_id: innermost,
            index: slot,
        });
        self.batch.write(Mutation::Insert {
            parent_id: COLUMN,
            node_id: DEEP_SCROLL,
            index: 2,
        });
    }

    /// A text as the first child of one level of the deep tree.
    fn deep_text(&mut self, parent_id: u32, node_id: u32, text: &str) {
        self.batch.write(Mutation::Create {
            node_id,
            widget: WidgetKind::Text,
        });
        self.batch.write(Mutation::SetProp {
            node_id,
            property: PropertyKind::Text,
            value: PropertyValue::String(text),
        });
        self.batch.write(Mutation::Insert {
            parent_id,
            node_id,
            index: 0,
        });
    }

    fn label(&mut self) {
        let text = if self.counting {
            format!("self-check frame {} of {FRAMES}", self.step)
        } else {
            "A consumer of compose-rust.".to_owned()
        };
        self.batch.write(Mutation::SetProp {
            node_id: TEXT,
            property: PropertyKind::Text,
            value: PropertyValue::String(&text),
        });
    }
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
            node_id: COLUMN,
            widget: WidgetKind::Column,
        });
        self.batch.write(Mutation::Create {
            node_id: TEXT,
            widget: WidgetKind::Text,
        });
        self.label();
        self.batch.write(Mutation::Insert {
            parent_id: COLUMN,
            node_id: TEXT,
            index: 0,
        });
        // Korean, so the frames the check waits for draw it too. Whether it is shaped and
        // wrapped correctly is the renderer's to say when COMPOSE_RUST_TEXT_SELF_CHECK is
        // set, and .github/scripts/check-single-executable.sh sets it.
        self.batch.write(Mutation::Create {
            node_id: KOREAN,
            widget: WidgetKind::Text,
        });
        self.batch.write(Mutation::SetProp {
            node_id: KOREAN,
            property: PropertyKind::Text,
            value: PropertyValue::String("안녕하세요 반갑습니다. 한국어 줄바꿈과 단어 경계를 확인합니다."),
        });
        self.batch.write(Mutation::Insert {
            parent_id: COLUMN,
            node_id: KOREAN,
            index: 1,
        });
        self.deep_tree();
    }

    /// Moves the text one step on, but only inside a frame.
    ///
    /// The Host also renders when an event arrives, with no frame time. The window's size is
    /// one such event, and the renderer reports it from the layout of the first frame, before
    /// that frame is on screen. Counting it as a step reached `FRAMES` within the first
    /// frame, and the check then ended the process before the window had shown anything: the
    /// macOS window had not presented its first frame yet, so it never said it had.
    fn render(&mut self, frame_time_nanos: Option<u64>) {
        if self.counting && frame_time_nanos.is_some() && self.step < FRAMES {
            self.step += 1;
            STEP.fetch_max(self.step, Ordering::SeqCst);
            self.label();
        }
    }

    fn handle_event(&mut self, _event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        // Nothing on this screen has a handler, so any event names one that does not exist.
        Err(ProtocolError::InvalidValueKind(0))
    }

    /// Asks for the next frame while steps are left. The Host turns a ready poll into a
    /// frame request, the way it does for any runtime with work waiting.
    fn poll_work(&mut self, _context: &mut Context<'_>) -> Poll<()> {
        if self.counting && self.step < FRAMES {
            Poll::Ready(())
        } else {
            Poll::Pending
        }
    }
}

/// The smallest size `--parity` asks the window to allow, which the parity check reads back.
const PARITY_MIN_WIDTH: u16 = 300;
const PARITY_MIN_HEIGHT: u16 = 260;

const FORM: u32 = 10;
const FIELD: u32 = 11;
const SAVE: u32 = 12;
const ON_VALUE_CHANGE: u64 = 1;
const ON_CLICK: u64 = 2;
const DROP: u32 = 13;
const LABEL: u32 = 14;
const ON_DROP: u64 = 3;

/// A column holding a text field, a button and a place to drop files, and nothing else.
struct Form {
    batch: Batch,
    /// Asks for a window of its own: a title and a smallest size, which the parity check reads
    /// back from the display server on every path.
    parity: bool,
}

impl Form {
    fn new() -> Self {
        Self {
            batch: Batch::new(),
            parity: false,
        }
    }

    fn for_parity() -> Self {
        Self {
            batch: Batch::new(),
            parity: true,
        }
    }

    fn text(&mut self, node_id: u32, property: PropertyKind, value: &str) {
        self.batch.write(Mutation::SetProp {
            node_id,
            property,
            value: PropertyValue::String(value),
        });
    }

    fn handler(&mut self, node_id: u32, property: PropertyKind, handler_id: u64) {
        self.batch.write(Mutation::SetProp {
            node_id,
            property,
            value: PropertyValue::Integer(handler_id as i64),
        });
    }
}

impl Runtime for Form {
    fn batch(&self) -> &Batch {
        &self.batch
    }

    fn batch_mut(&mut self) -> &mut Batch {
        &mut self.batch
    }

    fn rebuild(&mut self) {
        if self.parity {
            self.batch.write(Mutation::SetWindow(Window {
                title: "compose-rust parity",
                min_width: PARITY_MIN_WIDTH,
                min_height: PARITY_MIN_HEIGHT,
                ..Window::new()
            }));
        }
        for (node_id, widget) in [
            (FORM, WidgetKind::Column),
            (FIELD, WidgetKind::TextField),
            (SAVE, WidgetKind::Button),
            (DROP, WidgetKind::FileDropTarget),
            (LABEL, WidgetKind::Text),
        ] {
            self.batch.write(Mutation::Create { node_id, widget });
        }
        self.text(FIELD, PropertyKind::Placeholder, "Name");
        self.handler(FIELD, PropertyKind::OnValueChange, ON_VALUE_CHANGE);
        self.text(SAVE, PropertyKind::Text, "Save");
        self.handler(SAVE, PropertyKind::OnClick, ON_CLICK);
        self.text(LABEL, PropertyKind::Text, "DROP FILES HERE ................................");
        self.handler(DROP, PropertyKind::OnFilesDropped, ON_DROP);
        self.batch.write(Mutation::Insert { parent_id: DROP, node_id: LABEL, index: 0 });
        for (index, node_id) in [FIELD, SAVE, DROP].into_iter().enumerate() {
            self.batch.write(Mutation::Insert {
                parent_id: FORM,
                node_id,
                index: index as u32,
            });
        }
    }

    fn render(&mut self, _frame_time_nanos: Option<u64>) {}

    fn handle_event(&mut self, event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        match (event.node_id, event.handler_id, &event.payload) {
            (FIELD, ON_VALUE_CHANGE, EventPayload::TextChanged(value)) => {
                println!("input-check: field = {value}");
                Ok(0)
            }
            (DROP, ON_DROP, EventPayload::FilesDropped(paths)) => {
                println!("input-check: dropped = {paths}");
                Ok(0)
            }
            (SAVE, ON_CLICK, EventPayload::Clicked) => {
                println!("input-check: clicked");
                Ok(0)
            }
            _ => Err(ProtocolError::InvalidValueKind(0)),
        }
    }

    fn poll_work(&mut self, _context: &mut Context<'_>) -> Poll<()> {
        Poll::Pending
    }
}

/// How long the check waits for the frames when it is the one ending the process.
const SELF_CHECK_TIMEOUT: Duration = Duration::from_secs(120);

unsafe extern "C" {
    /// Ends the process without running exit handlers. The renderer's runtime is still
    /// running on the main thread, and handlers that tear it down from this one could hang
    /// rather than end anything.
    fn _exit(status: c_int) -> !;
}

/// Ends the process once the renderer has driven `FRAMES` frames, or fails after a timeout.
fn end_when_drawn() {
    std::thread::spawn(|| {
        let started = Instant::now();
        loop {
            let reached = STEP.load(Ordering::SeqCst);
            let status = if reached >= FRAMES {
                println!("self-check: the renderer drew {reached} frames");
                0
            } else if started.elapsed() > SELF_CHECK_TIMEOUT {
                eprintln!(
                    "self-check: the renderer drove {reached} of {FRAMES} frames in {}s. It \
                     started and did not draw; its own output above says why.",
                    SELF_CHECK_TIMEOUT.as_secs()
                );
                1
            } else {
                std::thread::sleep(Duration::from_millis(50));
                continue;
            };
            let _ = std::io::Write::flush(&mut std::io::stdout());
            let _ = std::io::Write::flush(&mut std::io::stderr());
            // SAFETY: ends the process; nothing after it runs.
            unsafe { _exit(status) }
        }
    });
}

/// The depth `--deep-tree=<depth>` asks for, 0 when it is not given. A value that is not a
/// number ends the program: a check that silently drew the shallow screen would pass for
/// the wrong reason.
fn deep_tree_argument() -> u32 {
    let Some(value) = std::env::args().find_map(|argument| {
        argument.strip_prefix("--deep-tree=").map(str::to_owned)
    }) else {
        return 0;
    };
    match value.parse::<u32>() {
        // Two ids a level above `DEEP_FIRST`, which a million levels still fits in.
        Ok(depth) if (1..=1_000_000).contains(&depth) => depth,
        _ => {
            eprintln!("self-check: --deep-tree={value} is not a depth between 1 and 1000000");
            std::process::exit(2);
        }
    }
}

/// Default frame count for `--frame-soak` when no count is given.
const DEFAULT_FRAME_SOAK_FRAMES: u32 = 2500;
/// Warmup frames drawn before recording the baseline footprint.
const DEFAULT_FRAME_SOAK_WARMUP: u32 = 100;
/// Milliseconds to pause after logging `phase start` so the harness samples at warmup.
const DEFAULT_FRAME_SOAK_PAUSE_MS: u64 = 1500;
/// Maximum seconds the frame soak watchdog waits before failing.
const FRAME_SOAK_TIMEOUT: Duration = Duration::from_secs(300);

static SOAK_FRAMES: AtomicU32 = AtomicU32::new(0);
static SOAK_DONE: AtomicBool = AtomicBool::new(false);

/// A busy screen whose text properties change on every frame, exercising the slot-table
/// runtime and property writer over thousands of frames.
#[Composable]
fn SoakScreen(tick: MutableState<u32>) {
    let current = tick.get();
    let suffix = current % 64;
    Column!({
        for row in 0..20u32 {
            Row!({
                Text!(format!("row {row} frame {current} input {suffix}"));
                Button!("Send", || {});
            });
        }
    });
}

/// Drives [`SoakScreen`] through `target` frames, mutating state on every frame and logging
/// `phase start` (after warmup) and `phase end` (at target) with the process PID.
struct FrameSoak {
    inner: Recomposer,
    tick: MutableState<u32>,
    warmup: u32,
    target: u32,
    pause_ms: u64,
    drawn: u32,
    start_logged: bool,
    end_logged: bool,
}

impl FrameSoak {
    fn new(target: u32, warmup: u32, pause_ms: u64) -> Self {
        let tick = mutable_state_of(0u32);
        let state = tick;
        let inner = Recomposer::new(move || SoakScreen(state));
        Self {
            inner,
            tick,
            warmup: warmup.min(target.saturating_sub(1).max(1)),
            target,
            pause_ms,
            drawn: 0,
            start_logged: false,
            end_logged: false,
        }
    }
}

impl Runtime for FrameSoak {
    fn batch(&self) -> &Batch {
        self.inner.batch()
    }

    fn batch_mut(&mut self) -> &mut Batch {
        self.inner.batch_mut()
    }

    fn rebuild(&mut self) {
        self.inner.rebuild();
    }

    fn render(&mut self, frame_time_nanos: Option<u64>) {
        if frame_time_nanos.is_some() && self.drawn < self.target {
            self.drawn += 1;
            SOAK_FRAMES.store(self.drawn, Ordering::SeqCst);
            self.tick.set(self.drawn);
        }
        self.inner.render(frame_time_nanos);
        if self.drawn == self.warmup && !self.start_logged {
            self.start_logged = true;
            println!(
                "frame-soak: phase start pid={} frames={}",
                std::process::id(),
                self.drawn
            );
            let _ = std::io::Write::flush(&mut std::io::stdout());
            if self.pause_ms > 0 {
                std::thread::sleep(Duration::from_millis(self.pause_ms));
            }
        }
        if self.drawn >= self.target && !self.end_logged {
            self.end_logged = true;
            println!(
                "frame-soak: phase end pid={} frames={}",
                std::process::id(),
                self.drawn
            );
            let _ = std::io::Write::flush(&mut std::io::stdout());
            SOAK_DONE.store(true, Ordering::SeqCst);
        }
    }

    fn handle_event(&mut self, event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        self.inner.handle_event(event)
    }

    fn poll_work(&mut self, context: &mut Context<'_>) -> Poll<()> {
        if self.drawn < self.target {
            Poll::Ready(())
        } else {
            self.inner.poll_work(context)
        }
    }
}

/// Ends the soak process shortly after `phase end` (giving an external sampler time to read
/// memory) or fails if the frame target is not reached within `FRAME_SOAK_TIMEOUT`.
fn end_soak_when_drawn(target: u32) {
    std::thread::spawn(move || {
        let started = Instant::now();
        loop {
            if SOAK_DONE.load(Ordering::SeqCst) {
                std::thread::sleep(Duration::from_secs(5));
                let _ = std::io::Write::flush(&mut std::io::stdout());
                let _ = std::io::Write::flush(&mut std::io::stderr());
                // SAFETY: ends the process after the soak finishes; nothing after it runs.
                unsafe { _exit(0) }
            }
            if started.elapsed() > FRAME_SOAK_TIMEOUT {
                let reached = SOAK_FRAMES.load(Ordering::SeqCst);
                eprintln!(
                    "frame-soak: drew {reached} of {target} frames in {}s",
                    FRAME_SOAK_TIMEOUT.as_secs()
                );
                let _ = std::io::Write::flush(&mut std::io::stderr());
                // SAFETY: ends the process on timeout; nothing after it runs.
                unsafe { _exit(1) }
            }
            std::thread::sleep(Duration::from_millis(50));
        }
    });
}

/// Parses `--frame-soak` or `--frame-soak=<frames>`, returning `None` when not requested.
fn frame_soak_argument() -> Option<u32> {
    for argument in std::env::args() {
        if argument == "--frame-soak" {
            return Some(DEFAULT_FRAME_SOAK_FRAMES);
        }
        if let Some(value) = argument.strip_prefix("--frame-soak=") {
            match value.parse::<u32>() {
                Ok(frames) if frames >= 2 => return Some(frames),
                _ => {
                    eprintln!("frame-soak: --frame-soak={value} must be an integer >= 2");
                    std::process::exit(2);
                }
            }
        }
    }
    None
}

fn main() {
    if let Some(target_frames) = frame_soak_argument() {
        let warmup = std::env::var("DXC_FRAME_SOAK_WARMUP")
            .ok()
            .and_then(|v| v.parse::<u32>().ok())
            .filter(|&v| v >= 1)
            .unwrap_or(DEFAULT_FRAME_SOAK_WARMUP);
        let pause_ms = std::env::var("DXC_FRAME_SOAK_PAUSE_MS")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
            .unwrap_or(DEFAULT_FRAME_SOAK_PAUSE_MS);
        end_soak_when_drawn(target_frames);
        let status = LaunchBuilder::new().try_launch_runtime(move || {
            Box::new(FrameSoak::new(target_frames, warmup, pause_ms)) as Box<dyn Runtime>
        });
        if status != STATUS_OK {
            eprintln!("frame-soak: the renderer loop ended with status {status}");
            std::process::exit(1);
        }
        return;
    }
    if std::env::args().any(|argument| argument == "--self-check") {
        let deep_tree = deep_tree_argument();
        if deep_tree > 0 {
            println!("self-check: a tree {deep_tree} levels deep is under the screen");
            let _ = std::io::Write::flush(&mut std::io::stdout());
        }
        if std::env::var_os("COMPOSE_RUST_AUTOEXIT_MS").is_none() {
            end_when_drawn();
        }
        let status = LaunchBuilder::new().try_launch_runtime(move || {
            Box::new(Screen::new(true).with_deep_tree(deep_tree)) as Box<dyn Runtime>
        });
        let reached = STEP.load(Ordering::SeqCst);
        if status != STATUS_OK {
            eprintln!("self-check: the renderer loop ended with status {status}");
            std::process::exit(1);
        }
        if reached < FRAMES {
            eprintln!(
                "self-check: the renderer drove {reached} of {FRAMES} frames before the \
                 window closed. It started and did not draw; its own output above says why."
            );
            std::process::exit(1);
        }
        println!("self-check: the renderer drew {reached} frames");
        return;
    }
    if std::env::args().any(|argument| argument == "--input-check") {
        LaunchBuilder::new().launch_runtime(|| Box::new(Form::new()) as Box<dyn Runtime>);
        return;
    }
    if std::env::args().any(|argument| argument == "--parity") {
        LaunchBuilder::new().launch_runtime(|| Box::new(Form::for_parity()) as Box<dyn Runtime>);
        return;
    }
    if std::env::args().any(|argument| argument == "--launch") {
        LaunchBuilder::new().launch_runtime(|| Box::new(Screen::new(false)) as Box<dyn Runtime>);
        return;
    }
    println!("the renderer was loaded and this program started");
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A render with no frame time is an event's, not a frame's, and must not count. The
    /// window's first size report renders that way from inside the first frame, so counting
    /// it ended the self-check before the first frame was presented.
    #[test]
    fn nfr15_self_check_counts_only_frames_the_renderer_drove() {
        let mut screen = Screen::new(true);
        screen.render(None);
        assert_eq!(screen.step, 0, "a render outside a frame advanced the self-check");
        screen.render(Some(1));
        assert_eq!(screen.step, 1);
        screen.render(None);
        assert_eq!(screen.step, 1, "a render outside a frame advanced the self-check");
        assert!(screen.poll_work(&mut Context::from_waker(std::task::Waker::noop())).is_ready());
        screen.render(Some(2));
        assert_eq!(screen.step, FRAMES);
        assert!(screen.poll_work(&mut Context::from_waker(std::task::Waker::noop())).is_pending());
    }

    /// The deep tree is as deep as it was asked to be: every level is the only container
    /// in the one before it, down from the scrolling column, and the last holds a text. A
    /// tree that came out flat would draw at any stack size and the check would pass for
    /// nothing.
    #[test]
    fn pr3_the_deep_tree_nests_every_level_inside_the_one_before() {
        use compose_rust::protocol::decode_batch;
        use std::collections::HashMap;

        const DEPTH: u32 = 95;
        let mut screen = Screen::new(true).with_deep_tree(DEPTH);
        screen.rebuild();
        let bytes = screen.batch.finish().expect("the batch encodes").to_vec();
        let mutations = decode_batch(&bytes).expect("the batch decodes");
        let mut parent_of = HashMap::new();
        let mut widget_of = HashMap::new();
        let mut modifiers = 0;
        for mutation in &mutations {
            match mutation {
                Mutation::Create { node_id, widget } => {
                    widget_of.insert(*node_id, *widget);
                }
                Mutation::Insert { parent_id, node_id, .. } => {
                    assert!(parent_of.insert(*node_id, *parent_id).is_none(), "{node_id} twice");
                }
                Mutation::SetModifier { .. } => modifiers += 1,
                _ => {}
            }
        }
        let innermost = DEEP_FIRST + DEPTH * 2;
        assert_eq!(widget_of[&innermost], WidgetKind::Text);
        let mut node = innermost;
        let mut levels = 0;
        let mut kinds = std::collections::HashSet::new();
        while parent_of[&node] != DEEP_SCROLL {
            node = parent_of[&node];
            kinds.insert(widget_of[&node] as u32);
            levels += 1;
        }
        assert_eq!(levels, DEPTH, "the innermost text is not {DEPTH} containers down");
        assert_eq!(kinds.len(), 3, "columns, rows and boxes are not all there");
        assert_eq!(widget_of[&DEEP_SCROLL], WidgetKind::ScrollColumn);
        assert_eq!(parent_of[&DEEP_SCROLL], COLUMN);
        assert!(modifiers > DEPTH / 3, "the levels carry no modifiers to speak of");

        let mut shallow = Screen::new(true);
        shallow.rebuild();
        let bytes = shallow.batch.finish().expect("the batch encodes").to_vec();
        assert!(
            !decode_batch(&bytes).expect("the batch decodes").iter().any(
                |mutation| matches!(mutation, Mutation::Create { node_id, .. } if *node_id >= DEEP_SCROLL)
            ),
            "the screen without --deep-tree grew a tree"
        );
    }

    #[test]
    fn nfr17_frame_soak_advances_on_frames_only() {
        let mut soak = FrameSoak::new(4, 2, 0);
        soak.rebuild();
        soak.render(None);
        assert_eq!(soak.drawn, 0, "event render without frame time must not count");
        assert!(!soak.start_logged);
        assert!(!soak.end_logged);

        for frame in 1..=4u64 {
            assert!(
                soak.poll_work(&mut Context::from_waker(std::task::Waker::noop())).is_ready(),
                "frame soak must keep requesting frames before target"
            );
            soak.render(Some(frame));
            assert_eq!(soak.drawn, frame as u32);
        }
        assert!(soak.start_logged, "start phase must be logged at warmup");
        assert!(soak.end_logged, "end phase must be logged at target");
        assert!(
            soak.poll_work(&mut Context::from_waker(std::task::Waker::noop())).is_pending(),
            "frame soak must stop requesting frames after target"
        );
    }
}

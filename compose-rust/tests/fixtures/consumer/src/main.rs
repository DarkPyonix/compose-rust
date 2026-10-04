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
//! The call to launch stays in the binary because the branch is decided at run time.
//! That is what makes the renderer a load-time dependency of this executable rather than
//! a library the linker drops for being unused.

use compose_rust::boundary::STATUS_OK;
use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, ProtocolError};
use compose_rust::schema::{PropertyKind, WidgetKind};
use compose_rust::{Batch, LaunchBuilder, Runtime};
use std::ffi::c_int;
use std::sync::atomic::{AtomicU32, Ordering};
use std::task::{Context, Poll};
use std::time::{Duration, Instant};

const COLUMN: u32 = 1;
const TEXT: u32 = 2;
const KOREAN: u32 = 3;

/// The highest step the self-check reached. Read after the renderer loop has ended.
static STEP: AtomicU32 = AtomicU32::new(0);

/// How many frames the renderer has to have driven before the check passes.
///
/// Each step after the first is rendered inside `compose_rust_host_render_frame`, which
/// the renderer calls only from its window's frame clock, and the next step is asked for
/// while that frame is being produced. So reaching step 2 means the renderer applied step
/// 1's changes, drew that frame, and came back for another one: a whole frame went
/// through, not only the start of one.
const FRAMES: u32 = 2;

/// One column holding one text. With `counting` set, every frame moves the text one step
/// on and asks for the next frame, until `FRAMES` steps have been drawn.
struct Screen {
    batch: Batch,
    counting: bool,
    step: u32,
}

impl Screen {
    fn new(counting: bool) -> Self {
        Self {
            batch: Batch::new(),
            counting,
            step: 0,
        }
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
    }

    fn render(&mut self) {
        if self.counting && self.step < FRAMES {
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

fn main() {
    if std::env::args().any(|argument| argument == "--self-check") {
        if std::env::var_os("COMPOSE_RUST_AUTOEXIT_MS").is_none() {
            end_when_drawn();
        }
        let status = LaunchBuilder::new()
            .try_launch_runtime(|| Box::new(Screen::new(true)) as Box<dyn Runtime>);
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
    if std::env::args().any(|argument| argument == "--launch") {
        LaunchBuilder::new().launch_runtime(|| Box::new(Screen::new(false)) as Box<dyn Runtime>);
        return;
    }
    println!("the renderer was loaded and this program started");
}

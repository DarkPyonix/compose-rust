//! The program the HiDPI check runs in.
//!
//! `--check` starts the renderer with a tree of one empty column. With
//! `COMPOSE_RUST_FR82_CHECK=1` set the renderer opens its own window instead, draws its marker
//! scene (white, 1 dp black bars, a circle, a rounded clip and "Hg"), checks it against the
//! screen, prints one `compose-rust: fr82_* scale=<s>: ok|FAILED` line per check and returns.
//! The exit status is the renderer's verdict: 0 when every check passed.
//!
//! With `COMPOSE_RUST_FR82_SCALE_CHANGES=<n>` it waits, after the first round, for the display
//! scale to change n times while it runs, printing `compose-rust: fr82 waiting for a scale
//! change` before each, and checks again at every new scale.
//!
//! Without `--check` it returns at once, so a build can be started to see that it loads.

use compose_rust::boundary::STATUS_OK;
use compose_rust::protocol::{HostEvent, Mutation, ProtocolError};
use compose_rust::schema::WidgetKind;
use compose_rust::{Batch, LaunchBuilder, Runtime};
use std::task::{Context, Poll};

/// One empty column: the renderer needs a tree to start with, and the check draws none of it.
struct Empty {
    batch: Batch,
}

impl Runtime for Empty {
    fn batch(&self) -> &Batch {
        &self.batch
    }

    fn batch_mut(&mut self) -> &mut Batch {
        &mut self.batch
    }

    fn rebuild(&mut self) {
        self.batch.write(Mutation::Create {
            node_id: 1,
            widget: WidgetKind::Column,
        });
    }

    fn render(&mut self, _frame_time_nanos: Option<u64>) {}

    fn handle_event(&mut self, _event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        Err(ProtocolError::InvalidValueKind(0))
    }

    fn poll_work(&mut self, _context: &mut Context<'_>) -> Poll<()> {
        Poll::Pending
    }
}

fn main() {
    if !std::env::args().any(|argument| argument == "--check") {
        println!("the renderer was loaded and this program started");
        return;
    }
    if std::env::var_os("COMPOSE_RUST_FR82_CHECK").is_none() {
        eprintln!("hidpi: --check needs COMPOSE_RUST_FR82_CHECK=1, or the renderer runs the empty tree");
        std::process::exit(2);
    }
    let status = LaunchBuilder::new().try_launch_runtime(|| {
        Box::new(Empty {
            batch: Batch::new(),
        }) as Box<dyn Runtime>
    });
    if status != STATUS_OK {
        eprintln!("hidpi: the renderer ended with status {status}; the lines above say which check failed");
        std::process::exit(1);
    }
}

//! One window, 800 by 600 dp, holding one empty box that fills it, and nothing else.
//!
//! It exists to be measured. A process's memory is what the renderer, its graphics surface
//! and the window cost plus what the content costs, and every other fixture has content: a
//! counter, text fields, a list. With nothing drawn but the window's background, a number
//! read from this process is the floor every application on the same renderer starts from.
//!
//! It takes no input and changes nothing after the first frame, so readings taken at rest
//! a few seconds apart differ only by what the renderer does on its own. It prints one
//! `blank-window:` line when the renderer reports the window's size, so a log says how big
//! the measured window was; otherwise it prints nothing.
//!
//! The renderer comes from `COMPOSE_RUST_RENDERER_DIR`, or from the static build named by
//! `DXC_LINUX_NATIVE_LIB` or `DXC_WINDOWS_NATIVE_LIB`, as it does for any application.

use compose_rust::protocol::{HostEvent, Mutation, ProtocolError};
use compose_rust::schema::{EventPayload, Modifier, WidgetKind, Window};
use compose_rust::{Batch, LaunchBuilder, Runtime};

/// The only node. The first node created is the one the renderer draws as the root.
const ROOT: u32 = 1;

/// The window's size in dp. At 100% display scale this is also its size in pixels.
const WIDTH: u16 = 800;
const HEIGHT: u16 = 600;

struct App {
    batch: Batch,
    reported: bool,
}

impl Runtime for App {
    fn batch(&self) -> &Batch {
        &self.batch
    }

    fn batch_mut(&mut self) -> &mut Batch {
        &mut self.batch
    }

    fn rebuild(&mut self) {
        let mut window = Window::new();
        window.title = "compose-rust blank window";
        window.width = WIDTH;
        window.height = HEIGHT;
        self.batch.write(Mutation::SetWindow(window));
        self.batch.write(Mutation::Create {
            node_id: ROOT,
            widget: WidgetKind::Box,
        });
        for (index, modifier) in [Modifier::FillMaxWidth, Modifier::FillMaxHeight]
            .into_iter()
            .enumerate()
        {
            self.batch.write(Mutation::SetModifier {
                node_id: ROOT,
                index: index as u16,
                modifier,
            });
        }
    }

    fn render(&mut self, _frame_time_nanos: Option<u64>) {}

    fn handle_event(&mut self, event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        match &event.payload {
            EventPayload::WindowSizeChanged {
                width_dp,
                height_dp,
                ..
            } => {
                if !self.reported {
                    self.reported = true;
                    println!("blank-window: window {width_dp:.0}x{height_dp:.0} dp");
                }
                Ok(0)
            }
            _ => Err(ProtocolError::InvalidValueKind(0)),
        }
    }
}

fn main() {
    LaunchBuilder::new().launch_runtime(|| {
        Box::new(App {
            batch: Batch::new(),
            reported: false,
        }) as Box<dyn Runtime>
    });
}

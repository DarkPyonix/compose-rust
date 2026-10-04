//! An application with a C++ library built for the C runtime DLL beside compose-rust.
//!
//! Runs the library's function and checks what it wrote. `--launch` opens the window, and
//! is there so the renderer stays a part of the executable the linker has to put together.

use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, ProtocolError};
use compose_rust::schema::{PropertyKind, WidgetKind};
use compose_rust::{Batch, LaunchBuilder, Runtime};
use std::task::{Context, Poll};

unsafe extern "C" {
    fn mixed_runtime_greeting(out: *mut u8, capacity: usize) -> usize;
}

/// One column holding one text, written by hand like the plain consumer's.
struct Screen {
    batch: Batch,
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
            node_id: 1,
            widget: WidgetKind::Column,
        });
        self.batch.write(Mutation::Create {
            node_id: 2,
            widget: WidgetKind::Text,
        });
        self.batch.write(Mutation::SetProp {
            node_id: 2,
            property: PropertyKind::Text,
            value: PropertyValue::String("A consumer with a library built for the runtime DLL."),
        });
        self.batch.write(Mutation::Insert {
            parent_id: 1,
            node_id: 2,
            index: 0,
        });
    }

    fn render(&mut self) {}

    fn handle_event(&mut self, _event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        Err(ProtocolError::InvalidValueKind(0))
    }

    fn poll_work(&mut self, _context: &mut Context<'_>) -> Poll<()> {
        Poll::Pending
    }
}

fn main() {
    if std::env::args().any(|argument| argument == "--launch") {
        LaunchBuilder::new().launch_runtime(|| {
            Box::new(Screen {
                batch: Batch::new(),
            }) as Box<dyn Runtime>
        });
        return;
    }
    let mut buffer = [0_u8; 128];
    // SAFETY: the buffer is as long as the capacity passed, and the function writes at most
    // capacity - 1 bytes and a terminator.
    let length = unsafe { mixed_runtime_greeting(buffer.as_mut_ptr(), buffer.len()) };
    let text = String::from_utf8_lossy(&buffer[..length]);
    println!("mixed runtime: {text}");
    if text != "built for the C runtime DLL, linked into one executable" {
        eprintln!("mixed runtime: the C++ library answered something else");
        std::process::exit(1);
    }
}

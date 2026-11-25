//! The five Host exports, called in any order with any bytes and with null pointers, must
//! each return a status: a panic, an unwind across the C boundary, an abort or an out of
//! bounds read is a finding. The decoder targets fuzz the bytes alone; this one fuzzes the
//! calls the Renderer makes with them, against a running composable application.
#![no_main]

use arbitrary::Arbitrary;
use compose_rust::LaunchBuilder;
use compose_rust::boundary::{
    MutationBatch, compose_rust_host_dispatch_event, compose_rust_host_init,
    compose_rust_host_release_batch, compose_rust_host_render_frame, compose_rust_host_shutdown,
};
use compose_rust::runtime::*;
use compose_rust::schema::{LoopMode, PROTOCOL_VERSION, SCHEMA_HASH};
use compose_rust::ui::*;
use libfuzzer_sys::fuzz_target;

#[Composable]
fn Counter() {
    let count = remember(|| mutable_state_of(0));
    Column!({
        Text!(format!("{}", count.get()));
        let more = count;
        Button!("More", move || more.update(|count| *count += 1));
    });
}

#[derive(Arbitrary, Debug)]
enum Call {
    /// The handshake the Renderer sends, so the calls after it reach a running Host
    /// rather than stopping at "not initialized".
    Handshake {
        null_out: bool,
    },
    Init {
        bytes: Vec<u8>,
        null_input: bool,
        null_out: bool,
    },
    DispatchEvent {
        bytes: Vec<u8>,
        null_input: bool,
        null_out: bool,
    },
    RenderFrame {
        frame_time_nanos: u64,
        null_out: bool,
    },
    ReleaseBatch {
        null: bool,
    },
    Shutdown,
}

fn input(bytes: &[u8], null: bool) -> *const u8 {
    if null {
        std::ptr::null()
    } else {
        bytes.as_ptr()
    }
}

fn output(out: &mut MutationBatch, null: bool) -> *mut MutationBatch {
    if null {
        std::ptr::null_mut()
    } else {
        out
    }
}

fn handshake() -> Vec<u8> {
    let mut bytes = Vec::with_capacity(12);
    bytes.extend_from_slice(&SCHEMA_HASH.to_le_bytes());
    bytes.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
    bytes.extend_from_slice(&[LoopMode::Platform as u8, 0]);
    bytes
}

fuzz_target!(|calls: Vec<Call>| {
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .application(Counter);
    let handshake = handshake();
    let mut out = MutationBatch::default();
    for call in &calls {
        // SAFETY: every pointer is either null, on purpose, or addresses this iteration's
        // own storage with the length passed beside it.
        unsafe {
            match call {
                Call::Handshake { null_out } => {
                    compose_rust_host_init(
                        handshake.as_ptr(),
                        handshake.len() as u32,
                        output(&mut out, *null_out),
                    );
                }
                Call::Init {
                    bytes,
                    null_input,
                    null_out,
                } => {
                    compose_rust_host_init(
                        input(bytes, *null_input),
                        bytes.len() as u32,
                        output(&mut out, *null_out),
                    );
                }
                Call::DispatchEvent {
                    bytes,
                    null_input,
                    null_out,
                } => {
                    compose_rust_host_dispatch_event(
                        input(bytes, *null_input),
                        bytes.len() as u32,
                        output(&mut out, *null_out),
                    );
                }
                Call::RenderFrame {
                    frame_time_nanos,
                    null_out,
                } => {
                    compose_rust_host_render_frame(
                        *frame_time_nanos,
                        output(&mut out, *null_out),
                    );
                }
                Call::ReleaseBatch { null } => {
                    compose_rust_host_release_batch(output(&mut out, *null));
                }
                Call::Shutdown => compose_rust_host_shutdown(),
            }
        }
    }
    compose_rust_host_shutdown();
});

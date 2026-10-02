//! Every `dioxus_compose_host_*` export must return a status for any
//! call a hostile or buggy Renderer can make - null pointers, zero lengths, and calls
//! in the wrong order - and must never unwind across the C ABI or abort the process.
//!
//! Each test runs on its own thread, and the Host lives in a thread-local, so the
//! lifecycle state of one test cannot leak into another.

use dioxus_compose::boundary::{
    MutationBatch, STATUS_ALREADY_INITIALIZED, STATUS_NOT_INITIALIZED, STATUS_OK, STATUS_PANIC,
    STATUS_PROTOCOL_ERROR, dioxus_compose_host_dispatch_event, dioxus_compose_host_init,
    dioxus_compose_host_release_batch, dioxus_compose_host_render_frame,
    dioxus_compose_host_shutdown,
};
use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{HostEvent, encode_event};
use dioxus_compose::schema::{EventPayload, PROTOCOL_VERSION, SCHEMA_HASH};

fn app() -> Element {
    rsx! {
        Column {
            Text { text: "hardening" }
        }
    }
}

fn handshake() -> Vec<u8> {
    let mut bytes = Vec::with_capacity(12);
    bytes.extend_from_slice(&SCHEMA_HASH.to_le_bytes());
    bytes.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
    bytes.extend_from_slice(&[LoopMode::Platform as u8, 0]);
    bytes
}

fn clicked_event() -> Vec<u8> {
    let mut bytes = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 1,
            handler_id: 1,
            payload: EventPayload::Clicked,
        },
        &mut bytes,
    )
    .unwrap();
    bytes
}

/// Brings the thread-local Host up, so call-order tests can start from a live Host.
fn init_host() -> MutationBatch {
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .launch(app);
    let bytes = handshake();
    let mut out = MutationBatch::default();
    // SAFETY: The handshake buffer and `out` are live test-owned storage.
    let status = unsafe { dioxus_compose_host_init(bytes.as_ptr(), bytes.len() as u32, &mut out) };
    assert_eq!(status, STATUS_OK);
    out
}

// --- Null pointers and zero lengths ---------------------------------------------------

#[test]
fn nfr7_init_with_null_handshake_returns_a_status() {
    let mut out = MutationBatch::default();
    // SAFETY: A null input pointer is exactly the contract violation under test.
    let status = unsafe { dioxus_compose_host_init(std::ptr::null(), 12, &mut out) };
    assert_eq!(status, STATUS_PROTOCOL_ERROR);
}

#[test]
fn nfr7_init_with_zero_length_returns_a_status() {
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .launch(app);
    let mut out = MutationBatch::default();
    // SAFETY: A zero length makes the pointer unused; both forms are valid calls.
    unsafe {
        assert_eq!(
            dioxus_compose_host_init(std::ptr::null(), 0, &mut out),
            STATUS_PROTOCOL_ERROR
        );
        let bytes = handshake();
        assert_eq!(
            dioxus_compose_host_init(bytes.as_ptr(), 0, &mut out),
            STATUS_PROTOCOL_ERROR
        );
    }
    dioxus_compose_host_shutdown();
}

#[test]
fn nfr7_init_with_a_null_out_pointer_returns_a_status() {
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .launch(app);
    let bytes = handshake();
    // SAFETY: A null `out` is the contract violation under test.
    let status = unsafe {
        dioxus_compose_host_init(bytes.as_ptr(), bytes.len() as u32, std::ptr::null_mut())
    };
    assert_eq!(status, STATUS_PROTOCOL_ERROR);
    dioxus_compose_host_shutdown();
}

#[test]
fn nfr7_init_with_a_truncated_handshake_returns_a_status() {
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .launch(app);
    let bytes = handshake();
    let mut out = MutationBatch::default();
    for len in 0..bytes.len() {
        // SAFETY: The buffer is live; only the declared length is short.
        let status = unsafe { dioxus_compose_host_init(bytes.as_ptr(), len as u32, &mut out) };
        assert_eq!(
            status, STATUS_PROTOCOL_ERROR,
            "a {len}-byte handshake must be rejected"
        );
    }
    dioxus_compose_host_shutdown();
}

#[test]
fn nfr7_init_with_a_wrong_schema_hash_returns_a_status() {
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .launch(app);
    let mut bytes = handshake();
    bytes[0] ^= 0xFF;
    let mut out = MutationBatch::default();
    // SAFETY: The buffer and `out` are live test-owned storage.
    let status = unsafe { dioxus_compose_host_init(bytes.as_ptr(), bytes.len() as u32, &mut out) };
    assert_eq!(status, STATUS_PROTOCOL_ERROR);
    dioxus_compose_host_shutdown();
}

#[test]
fn nfr7_dispatch_with_null_or_empty_input_returns_a_status() {
    let _batch = init_host();
    let mut out = MutationBatch::default();
    // SAFETY: Null and zero-length inputs are the contract violations under test.
    unsafe {
        assert_eq!(
            dioxus_compose_host_dispatch_event(std::ptr::null(), 16, &mut out),
            STATUS_PROTOCOL_ERROR
        );
        assert_eq!(
            dioxus_compose_host_dispatch_event(std::ptr::null(), 0, &mut out),
            STATUS_PROTOCOL_ERROR
        );
        let event = clicked_event();
        assert_eq!(
            dioxus_compose_host_dispatch_event(event.as_ptr(), 0, &mut out),
            STATUS_PROTOCOL_ERROR
        );
    }
    dioxus_compose_host_shutdown();
}

#[test]
fn nfr7_dispatch_with_a_null_out_pointer_returns_a_status() {
    let _batch = init_host();
    let event = clicked_event();
    // SAFETY: A null `out` is the contract violation under test.
    let status = unsafe {
        dioxus_compose_host_dispatch_event(event.as_ptr(), event.len() as u32, std::ptr::null_mut())
    };
    // The event names a handler that does not exist, so either rejection is a status,
    // never an unwind.
    assert_eq!(status, STATUS_PROTOCOL_ERROR);
    dioxus_compose_host_shutdown();
}

#[test]
fn nfr7_render_frame_with_a_null_out_pointer_returns_a_status() {
    let _batch = init_host();
    // SAFETY: A null `out` is the contract violation under test.
    let status = unsafe { dioxus_compose_host_render_frame(0, std::ptr::null_mut()) };
    assert_eq!(status, STATUS_PROTOCOL_ERROR);
    dioxus_compose_host_shutdown();
}

// --- Call order -----------------------------------------------------------------------

/// The boundary declares `STATUS_NOT_INITIALIZED`; a Renderer that dispatches before the
/// handshake must be able to tell that apart from a malformed event.
#[test]
fn nfr7_dispatch_before_init_reports_not_initialized() {
    let event = clicked_event();
    let mut out = MutationBatch::default();
    // SAFETY: All pointers refer to live test-owned buffers.
    let status =
        unsafe { dioxus_compose_host_dispatch_event(event.as_ptr(), event.len() as u32, &mut out) };
    assert_eq!(status, STATUS_NOT_INITIALIZED);
}

#[test]
fn nfr7_render_frame_before_init_reports_not_initialized() {
    let mut out = MutationBatch::default();
    // SAFETY: `out` is live test-owned storage.
    let status = unsafe { dioxus_compose_host_render_frame(0, &mut out) };
    assert_eq!(status, STATUS_NOT_INITIALIZED);
}

/// The boundary declares `STATUS_ALREADY_INITIALIZED`; a second handshake is a call-order
/// error, not a malformed message.
#[test]
fn nfr7_double_init_reports_already_initialized() {
    let _batch = init_host();
    let bytes = handshake();
    let mut out = MutationBatch::default();
    // SAFETY: The handshake buffer and `out` are live test-owned storage.
    let status = unsafe { dioxus_compose_host_init(bytes.as_ptr(), bytes.len() as u32, &mut out) };
    assert_eq!(status, STATUS_ALREADY_INITIALIZED);
    dioxus_compose_host_shutdown();
}

#[test]
fn nfr7_use_after_shutdown_reports_not_initialized() {
    let _batch = init_host();
    dioxus_compose_host_shutdown();

    let event = clicked_event();
    let mut out = MutationBatch::default();
    // SAFETY: All pointers refer to live test-owned buffers.
    unsafe {
        assert_eq!(
            dioxus_compose_host_dispatch_event(event.as_ptr(), event.len() as u32, &mut out),
            STATUS_NOT_INITIALIZED
        );
        assert_eq!(
            dioxus_compose_host_render_frame(0, &mut out),
            STATUS_NOT_INITIALIZED
        );
    }
}

#[test]
fn nfr7_repeated_shutdown_is_harmless() {
    let _batch = init_host();
    for _ in 0..4 {
        dioxus_compose_host_shutdown();
    }
}

#[test]
fn nfr7_release_batch_tolerates_null_double_release_and_stale_batches() {
    let mut batch = init_host();
    // SAFETY: Null, then a real batch, then the same batch a second time.
    unsafe {
        dioxus_compose_host_release_batch(std::ptr::null_mut());
        dioxus_compose_host_release_batch(&mut batch);
        dioxus_compose_host_release_batch(&mut batch);
    }
    assert!(batch.ptr.is_null(), "release must clear the batch");
    assert_eq!(batch.len, 0);

    dioxus_compose_host_shutdown();
    // Releasing after shutdown is still just a write to caller storage.
    // SAFETY: `batch` is live test-owned storage.
    unsafe { dioxus_compose_host_release_batch(&mut batch) };
    assert!(batch.ptr.is_null());
}

/// A Renderer that tears its UI thread down without calling `shutdown` must not
/// take the process with it. Dropping the `VirtualDom` from a thread-local destructor
/// used to panic with "cannot access a TLS value during or after destruction", and a
/// panic in a destructor is non-unwinding - it aborts. The abort killed the whole test
/// binary, so this test runs the sequence on its own thread and joins it.
#[test]
fn nfr7_thread_exit_without_shutdown_does_not_abort() {
    let handle = std::thread::spawn(|| {
        let batch = init_host();
        assert!(!batch.ptr.is_null());
        // Deliberately no `dioxus_compose_host_shutdown()`.
    });
    handle.join().expect("the UI thread must exit cleanly");
}

// --- catch_unwind coverage ------------------------------------------------------------

/// Re-enters the boundary from inside a component render, which is what a Renderer that
/// calls back into the Host mid-batch would do.
fn reentrant_app() -> Element {
    let mut out = MutationBatch::default();
    // SAFETY: `out` is live stack storage; the re-entrancy is the point of the test.
    let status = unsafe { dioxus_compose_host_render_frame(0, &mut out) };
    rsx! {
        Column {
            Text { text: "{status}" }
        }
    }
}

/// The Host slot is a `RefCell`, so a Renderer that calls back into an export
/// while one is still running hits a double borrow, and `RefCell::borrow_mut` panics.
/// Unwinding out of an `extern "C"` function aborts, so that panic has to be contained
/// and reported. This is what proves the `catch_unwind` in `ffi_status` is real rather
/// than assumed: every export is wrapped, and this is the path that exercises it.
#[test]
fn nfr7_reentrant_export_call_is_contained_not_aborted() {
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .launch(reentrant_app);
    let bytes = handshake();
    let mut out = MutationBatch::default();

    // The inner panic is expected, so keep its backtrace out of the test output.
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(|_| {}));
    // SAFETY: The handshake buffer and `out` are live test-owned storage.
    let status = unsafe { dioxus_compose_host_init(bytes.as_ptr(), bytes.len() as u32, &mut out) };
    std::panic::set_hook(previous);

    // Whatever the outcome, it is a status and the process is still here.
    assert!(
        status == STATUS_OK || status == STATUS_PANIC || status == STATUS_PROTOCOL_ERROR,
        "re-entrancy returned {status}, which is not a declared status"
    );
    dioxus_compose_host_shutdown();
}

/// The whole point of crash isolation: a long run of hostile calls in arbitrary order returns
/// statuses and leaves the process alive.
#[test]
fn nfr7_arbitrary_call_order_never_aborts() {
    let mut out = MutationBatch::default();
    let handshake = handshake();
    let event = clicked_event();
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .launch(app);

    for step in 0..64_u32 {
        // SAFETY: Every pointer is either null by design or live test-owned storage.
        unsafe {
            match step % 5 {
                0 => {
                    dioxus_compose_host_init(handshake.as_ptr(), handshake.len() as u32, &mut out);
                }
                1 => {
                    dioxus_compose_host_dispatch_event(
                        event.as_ptr(),
                        event.len() as u32,
                        &mut out,
                    );
                }
                2 => {
                    dioxus_compose_host_render_frame(u64::from(step), &mut out);
                }
                3 => dioxus_compose_host_release_batch(&mut out),
                _ => dioxus_compose_host_shutdown(),
            }
        }
    }
    dioxus_compose_host_shutdown();
}

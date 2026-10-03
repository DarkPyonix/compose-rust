//! The Android boundary: the generated shims, and the frame request a Renderer that is not
//! yet listening would otherwise swallow. The lifecycle events the Host answers for itself
//! are driven through an application, so they are in the Dioxus adapter's tests.
//!
//! Each `tests/*.rs` file is its own binary, so the process-global frame state these tests
//! read belongs to this file alone. They still take `FRAME_STATE` in turn, because that
//! state is one set of flags and the test harness runs them on several threads.

use compose_rust::boundary::STATUS_OK;
use compose_rust::codegen::{
    generate_android_bridge_kotlin, generate_fast_native_kotlin, generate_jni_rust,
};
use compose_rust::schema::{BOUNDARY_SCHEMA, BoundaryParam};
use compose_rust::{RendererApi, install_renderer_api, request_frame_from_worker};
use std::ffi::c_int;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Mutex, MutexGuard};

static FRAME_REQUESTS: AtomicUsize = AtomicUsize::new(0);
static FRAME_STATE: Mutex<()> = Mutex::new(());

extern "C" fn count_request() {
    FRAME_REQUESTS.fetch_add(1, Ordering::AcqRel);
}

extern "C" fn never_run() -> c_int {
    STATUS_OK as c_int
}

/// Installs the counting renderer and takes the frame state, counting from zero.
fn frame_state() -> MutexGuard<'static, ()> {
    let guard = FRAME_STATE
        .lock()
        .unwrap_or_else(|poison| poison.into_inner());
    let _ = install_renderer_api(RendererApi {
        run: never_run,
        request_frame: count_request,
    });
    FRAME_REQUESTS.store(0, Ordering::Release);
    guard
}

fn requests() -> usize {
    FRAME_REQUESTS.load(Ordering::Acquire)
}

/// The Renderer folds requests into its frame clock, so the Host has to keep asking.
/// Holding the flag until a frame came back lost every request that followed one the
/// Renderer was not yet listening for, and on a cold start that is the first one.
#[test]
fn pr3_a_missed_frame_request_does_not_silence_the_next() {
    let _state = frame_state();
    request_frame_from_worker();
    request_frame_from_worker();
    request_frame_from_worker();
    assert_eq!(requests(), 3);
}

/// Both halves of the boundary are generated from one table, so they cannot drift, and
/// what is checked in has to be what the generator produces today.
#[test]
fn pr5_generated_shims_match_the_boundary_schema() {
    let rust = generate_jni_rust();
    let kotlin = generate_android_bridge_kotlin();
    for op in BOUNDARY_SCHEMA {
        let symbol = format!(
            "Java_dev_darkpyonix_composerust_ui_platform_HostBridge_native{}",
            op.name
        );
        assert!(rust.contains(&symbol), "missing shim for {}", op.name);
        assert!(rust.contains(op.symbol), "shim does not call {}", op.symbol);
        assert!(
            kotlin.contains(&format!("external fun native{}(", op.name)),
            "missing declaration for {}",
            op.name
        );
        assert_eq!(
            op.fast,
            kotlin.contains(&format!("@FastNative\nexternal fun native{}(", op.name)),
            "the fast annotation on {} does not match the schema",
            op.name
        );
    }
    // The batch is read where it lies, so the shims map the arena and copy nothing.
    assert!(rust.contains("new_direct_byte_buffer"));
    assert!(!rust.contains("copy_from_slice"));
    // A worker thread attaches to the JavaVM once and stays attached.
    assert!(rust.contains("attach_current_thread_permanently"));

    assert_eq!(
        include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/src/boundary_jni.gen.rs"
        )),
        rust,
        "generated JNI shims are stale; run `cargo run -p compose-rust --bin codegen`",
    );
    assert_eq!(
        include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../renderer/android/src/bridge/HostBridge.gen.kt"
        )),
        kotlin,
        "generated Kotlin bridge is stale; run `cargo run -p compose-rust --bin codegen`",
    );
    assert_eq!(
        include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../renderer/android/src/bridge/FastNative.gen.kt"
        )),
        generate_fast_native_kotlin(),
        "the generated annotation is stale; run `cargo run -p compose-rust --bin codegen`",
    );
}

/// The two halves have to agree on how many arguments each call takes and on how many
/// slots the reply has. A mismatch is not a compile error on either side: it is an
/// unsatisfied link at the first call, on a device, after everything else looked fine.
#[test]
fn pr5_both_halves_agree_on_the_argument_and_slot_counts() {
    let rust = generate_jni_rust();
    let kotlin = generate_android_bridge_kotlin();

    for op in BOUNDARY_SCHEMA {
        // Bytes arrive as a buffer and a length, everything else as one argument, and a
        // call that answers with a batch takes the reply array as well.
        let expected: usize = op
            .params
            .iter()
            .map(|param| match param {
                BoundaryParam::Bytes { .. } => 2,
                BoundaryParam::Nanos { .. } => 1,
            })
            .sum::<usize>()
            + usize::from(op.returns_batch);

        let declaration = format!("external fun native{}(", op.name);
        let kotlin_arguments = signature_after(&kotlin, &declaration, ')');
        assert_eq!(
            arguments_in(&kotlin_arguments),
            expected,
            "{} takes {expected} arguments in Kotlin",
            op.name
        );

        let shim = format!(
            "pub extern \"system\" fn Java_dev_darkpyonix_composerust_ui_platform_HostBridge_native{}(",
            op.name
        );
        let rust_arguments = signature_after(&rust, &shim, ')');
        // The environment and the class are the JNI calling convention, not arguments of
        // the operation, so they do not appear on the Kotlin side.
        assert_eq!(
            arguments_in(&rust_arguments),
            expected + 2,
            "{} takes {expected} arguments plus the JNI pair in the shim",
            op.name
        );
    }

    let slots = format!("pub const OUT_SLOTS: usize = {};", OUT_SLOT_COUNT);
    assert!(
        rust.contains(&slots),
        "the shims write {OUT_SLOT_COUNT} slots"
    );
    assert!(
        kotlin.contains(&format!("const val OUT_SLOTS: Int = {OUT_SLOT_COUNT}")),
        "the caller sizes its reply array for {OUT_SLOT_COUNT} slots"
    );
}

/// The batch offset, its length, the handler result, and the arena's address and capacity.
const OUT_SLOT_COUNT: usize = 5;

/// The text between `opening` and the first `closing` after it.
fn signature_after(source: &str, opening: &str, closing: char) -> String {
    let start = source
        .find(opening)
        .unwrap_or_else(|| panic!("no `{opening}` in the generated source"))
        + opening.len();
    let rest = &source[start..];
    let end = rest.find(closing).expect("unterminated argument list");
    rest[..end].to_owned()
}

fn arguments_in(signature: &str) -> usize {
    signature
        .split(',')
        .filter(|argument| !argument.trim().is_empty())
        .count()
}

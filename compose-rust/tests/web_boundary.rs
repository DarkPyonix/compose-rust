//! The web boundary: three generated files, one schema, and nothing on the call path that
//! serialises, copies or queues.
//!
//! Every assertion here is about what the generator writes, because that is the only part
//! of this boundary a test on this machine can reach. What the browser does with it is
//! checked by running the page.

use compose_rust::codegen::{
    WEB_HOST_WASM_NAME, WEB_MEMORY_MIN_PAGES, generate_wasm_rust, generate_web_bridge_kotlin,
    generate_web_loader_js,
};
use compose_rust::protocol::{HostEvent, decode_event, encode_event};
use compose_rust::schema::{
    BOUNDARY_SCHEMA, BoundaryOp, BoundaryParam, EventPayload, IoStatus, WEB_BATCH_BYTES,
    WEB_BATCH_FIELDS, WEB_EVENT_BUFFER_BYTES, WEB_EVENT_BUFFER_MAX_BYTES, WEB_EVENT_BUFFER_OFFSET,
    WEB_LEND_EVENT_BUFFER_SYMBOL, WEB_RUST_REGION_BASE, WEB_START_SYMBOL,
};
use compose_rust::web_event_buffer::{LentEventBuffer, region_address};

/// The wasm symbol for one operation, spelled out here rather than taken from the
/// generator, so that a renamed symbol fails a test instead of renaming its own assertion.
fn symbol(op: &BoundaryOp) -> String {
    let mut snake = String::new();
    for (index, character) in op.name.char_indices() {
        if character.is_ascii_uppercase() && index != 0 {
            snake.push('_');
        }
        snake.push(character.to_ascii_lowercase());
    }
    format!("compose_rust_host_web_{snake}")
}

/// How many arguments an operation takes in the browser.
///
/// A byte range is an address and a length. A frame timestamp is two halves, because a
/// 64-bit argument reaches a JavaScript forwarder as a `BigInt` and that is an allocation
/// on the one call that happens every frame. A call that answers with a batch, and the one
/// that releases it, also name the record to write it into.
fn arguments(op: &BoundaryOp) -> usize {
    op.params
        .iter()
        .map(|param| match param {
            BoundaryParam::Bytes { .. } => 2,
            BoundaryParam::Nanos { .. } => 2,
        })
        .sum::<usize>()
        + usize::from(op.returns_batch || op.name == "ReleaseBatch")
}

/// The three halves are renderings of one table, and what is checked in has to be what the
/// generator writes today.
#[test]
fn pr6_generated_web_bindings_match_the_boundary_schema() {
    let rust = generate_wasm_rust();
    let kotlin = generate_web_bridge_kotlin();
    let loader = generate_web_loader_js();

    for op in BOUNDARY_SCHEMA {
        let symbol = symbol(op);
        assert!(
            rust.contains(&format!("pub extern \"C\" fn {symbol}(")),
            "missing wasm shim for {}",
            op.name
        );
        assert!(
            rust.contains(&format!("crate::boundary::{}(", op.symbol)),
            "the shim for {} does not call {}",
            op.name,
            op.symbol
        );
        assert!(
            kotlin.contains(&format!("external fun host{}(", op.name)),
            "missing forwarder declaration for {}",
            op.name
        );
        assert!(
            kotlin.contains(&symbol),
            "the forwarder for {} does not name {symbol}",
            op.name
        );
    }
    // The work is generated; the export is in the authoring layer's `web_main!`, because a
    // wasm module cannot be linked with an undefined symbol the way an ELF shared library
    // can, so this crate's own module must not name a function only an application can
    // define. What it takes is a runtime, so any authoring layer can start one with it.
    assert!(
        rust.contains("pub fn web_start(")
            && rust.contains("runtime: impl Fn() -> Box<dyn Runtime> + Send + Sync + 'static,"),
        "the extra entry point a page needs in place of a library loader is missing"
    );
    assert!(
        kotlin.contains(&format!("host.{WEB_START_SYMBOL}()")),
        "the Renderer has to start the Host it just instantiated"
    );
    assert!(
        loader.contains("WebAssembly.compileStreaming("),
        "compiling before the Renderer's module is evaluated is the page's whole job"
    );

    assert_eq!(
        include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/src/boundary_wasm.gen.rs"
        )),
        rust,
        "generated wasm shims are stale; run `cargo run -p compose-rust --bin codegen`",
    );
    assert_eq!(
        include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../renderer/web/src/bridge/HostBridge.gen.kt"
        )),
        kotlin,
        "generated Kotlin bridge is stale; run `cargo run -p compose-rust --bin codegen`",
    );
    assert_eq!(
        include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../renderer/web/resources/compose-rust-host.gen.mjs"
        )),
        loader,
        "the generated loader is stale; run `cargo run -p compose-rust --bin codegen`",
    );
}

/// A mismatch in the argument list is not a compile error on either side. It is a call that
/// traps in the browser, at the first frame, after everything else looked fine.
#[test]
fn pr6_both_halves_agree_on_the_argument_counts() {
    let rust = generate_wasm_rust();
    let kotlin = generate_web_bridge_kotlin();

    for op in BOUNDARY_SCHEMA {
        let expected = arguments(op);
        let declaration = format!("external fun host{}(", op.name);
        assert_eq!(
            count(&signature_after(&kotlin, &declaration, ')')),
            expected,
            "{} takes {expected} arguments in Kotlin",
            op.name
        );
        let shim = format!("pub extern \"C\" fn {}(", symbol(op));
        assert_eq!(
            count(&signature_after(&rust, &shim, ')')),
            expected,
            "{} takes {expected} arguments in the shim",
            op.name
        );
    }
}

/// Only primitives cross, so every argument is a 32-bit integer and so is every answer.
/// A wider one would be a `BigInt`, which is a heap allocation, and anything else would be
/// a value the forwarder had to build.
#[test]
fn pr6_only_primitives_cross_the_forwarder() {
    let kotlin = generate_web_bridge_kotlin();
    for op in BOUNDARY_SCHEMA {
        let declaration = format!("external fun host{}(", op.name);
        let signature = signature_after(&kotlin, &declaration, ')');
        for argument in signature.split(',').filter(|part| !part.trim().is_empty()) {
            assert!(
                argument.trim().ends_with(": Int"),
                "{} takes {argument}, which is not a 32-bit integer",
                op.name
            );
        }
        assert!(
            kotlin.contains(&format!("{declaration}{signature}): Int")),
            "{} has to answer with a status",
            op.name
        );
    }
}

/// The forwarder passes its arguments on and does nothing else. That is the whole of what
/// JavaScript is allowed to do here: no encoding, no copy, no promise, no queue.
#[test]
fn pr6_the_forwarder_only_passes_its_arguments_on() {
    let kotlin = generate_web_bridge_kotlin();
    for op in BOUNDARY_SCHEMA {
        let declaration = format!("external fun host{}(", op.name);
        let parameters: Vec<String> = signature_after(&kotlin, &declaration, ')')
            .split(',')
            .filter(|part| !part.trim().is_empty())
            .map(|part| {
                part.trim()
                    .split(':')
                    .next()
                    .expect("a declared parameter has a name")
                    .to_owned()
            })
            .collect();
        let names = parameters.join(", ");
        let expected = format!(
            "@JsFun(\"({names}) => globalThis.__composeRustHost.{}({names})\")",
            symbol(op)
        );
        assert!(
            kotlin.contains(&expected),
            "the forwarder for {} is not the fixed shape; expected\n{expected}",
            op.name
        );
    }
    for forbidden in ["await", "Promise", "new Uint8Array", "JSON", "BigInt"] {
        assert!(
            !kotlin.contains(forbidden),
            "the Kotlin bridge mentions {forbidden}, which no forwarder may do"
        );
    }
}

/// The other direction has no JavaScript on it. The page hands over the exported function
/// object itself; wrapping it in a closure would cost a frame per frame request for nothing.
#[test]
fn pr6_the_frame_request_binds_to_the_wasm_export() {
    let kotlin = generate_web_bridge_kotlin();
    let binding = signature_after(&kotlin, "compose_rust_renderer_request_frame:", ',');
    assert!(
        binding.contains("wasmExports.compose_rust_renderer_request_frame"),
        "the frame request has to be bound to the Renderer's export, got: {binding}"
    );
    assert!(
        !binding.contains("=>"),
        "the frame request is wrapped in a closure, which puts a JavaScript frame on it: {binding}"
    );
    assert!(
        kotlin.contains("@WasmExport(\"compose_rust_renderer_request_frame\")"),
        "the Renderer has to export the function that import binds to"
    );
}

/// The arena is read where it lies, so neither generated half may make a copy of it, and
/// the loader may not reach into the memory at all.
#[test]
fn pr6_the_arena_is_never_copied() {
    let rust = generate_wasm_rust();
    for forbidden in ["copy_from_slice", "to_vec", "copy_nonoverlapping"] {
        assert!(
            !rust.contains(forbidden),
            "the wasm shims call {forbidden}, and a batch is read in place"
        );
    }
    let kotlin = generate_web_bridge_kotlin();
    let loader = generate_web_loader_js();
    for forbidden in ["Uint8Array", "DataView", "memory.buffer.slice"] {
        assert!(
            !kotlin.contains(forbidden) && !loader.contains(forbidden),
            "the wiring reaches into the shared memory through {forbidden}; only the two \
             modules read it"
        );
    }
    assert!(
        kotlin.contains("env: { memory }"),
        "the Host has to import the memory the Renderer defined, not make one of its own"
    );
}

/// One memory, two allocators, and an overlap that draws a wrong screen rather than
/// crashing. Both sides are written against the same line, and both check it.
#[test]
fn pr6_the_two_regions_are_stated_the_same_on_both_sides() {
    let rust = generate_wasm_rust();
    let kotlin = generate_web_bridge_kotlin();
    let _loader = generate_web_loader_js();

    assert!(
        kotlin.contains(&format!(
            "const val RUST_REGION_BASE: Int = {WEB_RUST_REGION_BASE}"
        )),
        "the Renderer has to know where the Host's region starts"
    );
    assert!(
        kotlin.contains(&format!("if (block < {WEB_RUST_REGION_BASE}) {{")),
        "and refuse a Host whose block landed below it"
    );
    assert!(
        rust.contains("fn lent(address: u32) -> bool {"),
        "the shims have to refuse an address outside the Host's region"
    );
    assert!(
        rust.contains("address >= WEB_RUST_REGION_BASE"),
        "and refuse it against the same constant"
    );

    for field in WEB_BATCH_FIELDS {
        let mut screaming = String::new();
        for (index, character) in field.name.char_indices() {
            if character.is_ascii_uppercase() && index != 0 {
                screaming.push('_');
            }
            screaming.push(character.to_ascii_uppercase());
        }
        assert!(
            kotlin.contains(&format!(
                "const val BATCH_{screaming}_OFFSET: Int = {}",
                field.offset
            )),
            "the Renderer reads {} at {}",
            field.name,
            field.offset
        );
    }
    assert!(kotlin.contains(&format!("const val BATCH_BYTES: Int = {WEB_BATCH_BYTES}")));
    assert!(kotlin.contains(&format!(
        "const val EVENT_BUFFER_OFFSET: Int = {WEB_EVENT_BUFFER_OFFSET}"
    )));
    assert!(kotlin.contains(&format!(
        "const val EVENT_BUFFER_BYTES: Int = {WEB_EVENT_BUFFER_BYTES}"
    )));
    // The offsets the Kotlin reads by are checked against the layout the Host's compiler
    // chose, which is the one thing a constant written down by hand cannot promise.
    assert!(rust.contains("assert!(size_of::<MutationBatch>() == WEB_BATCH_BYTES as usize);"));
    assert!(rust.contains("offset_of!(BoundaryBlock, event) == WEB_EVENT_BUFFER_OFFSET as usize"));
}

/// The Renderer's memory starts at zero pages, so the page grows it to what the Host's
/// import declares before the two types can match. Both numbers come from here.
#[test]
fn pr6_the_page_grows_the_memory_to_what_the_host_declares() {
    let kotlin = generate_web_bridge_kotlin();
    let loader = generate_web_loader_js();
    assert!(
        kotlin.contains(&format!("memory.grow({WEB_MEMORY_MIN_PAGES} - pages)")),
        "the memory is grown to the minimum the Host's link declared, by the difference"
    );
    assert!(
        loader.contains(&format!("const HOST_WASM = './{WEB_HOST_WASM_NAME}';")),
        "the loader fetches the Host from beside itself"
    );
    // The Host's region has to start above the memory the page guarantees, or the first
    // address the Host reports would be past the end of the memory.
    assert!(
        u64::from(WEB_MEMORY_MIN_PAGES) * 65536 > u64::from(WEB_RUST_REGION_BASE),
        "{WEB_MEMORY_MIN_PAGES} pages do not reach the Host's region at {WEB_RUST_REGION_BASE}"
    );
}

/// A browser will not instantiate a module with an import nobody supplied, used or not,
/// and `dioxus-core` brings wasm-bindgen's placeholders in through `subsecond`. So they are
/// answered, and answered by something that cannot go stale: the names carry a per-version
/// hash.
#[test]
fn pr6_the_page_answers_the_imports_the_host_carries() {
    let kotlin = generate_web_bridge_kotlin();
    for namespace in ["__wbindgen_placeholder__", "__wbindgen_externref_xform__"] {
        assert!(
            kotlin.contains(&format!("{namespace}: unbound('{namespace}')")),
            "the instantiation has to answer {namespace} or the Host will not start"
        );
    }
    assert!(
        kotlin.contains("throw new Error("),
        "and reaching one of them has to be reported rather than ignored"
    );
}

/// A page that serves the renderer with no Host beside it still has to come up, because
/// the renderer is worked on without the other side being built.
#[test]
fn nfr5_a_page_without_a_host_says_so_and_carries_on() {
    let kotlin = generate_web_bridge_kotlin();
    let loader = generate_web_loader_js();
    assert!(
        kotlin.contains("if (!compiled) return 0;"),
        "a missing Host has to answer zero rather than throw"
    );
    assert!(
        loader.contains("console.info("),
        "and say on the console why the screen is the development host"
    );
}

/// Writes `bytes` where the Renderer would, into a buffer the Host lent, and decodes what the
/// Host then reads there.
fn through_lent_buffer<'a>(
    lender: &mut LentEventBuffer,
    bytes: &[u8],
    read: &'a mut Vec<u8>,
) -> HostEvent<'a> {
    let length = u32::try_from(bytes.len()).expect("the event fits in 32 bits");
    let pointer = lender
        .lend(length)
        .expect("the Host refused a buffer under the ceiling");
    // SAFETY: the buffer just lent holds at least `length` bytes and nothing else refers to
    // it while this writes, which is the promise the Renderer relies on in a browser.
    let lent = unsafe { std::slice::from_raw_parts_mut(pointer, bytes.len()) };
    lent.clone_from_slice(bytes);
    read.clear();
    read.extend_from_slice(lent);
    decode_event(read).expect("the Host could not decode the event it was lent a buffer for")
}

/// A paste, a large edit and a long storage answer are each larger than the 4 KiB buffer in
/// the startup block. The Host lends a buffer that holds each of them, and what it reads
/// there is the whole event.
#[test]
fn pr6_an_event_larger_than_the_lent_buffer_arrives_whole() {
    let clipboard = "가나다라마바사아".repeat(64 * 1024 / 24) + &"x".repeat(64 * 1024 % 24);
    assert_eq!(clipboard.len(), 64 * 1024);
    let edit = "line of a pasted document\n".repeat(32 * 1024 / 26 + 1)[..32 * 1024].to_owned();
    let value: Vec<u8> = (0..1024 * 1024).map(|index| (index % 251) as u8).collect();
    let events = [
        HostEvent {
            node_id: 3,
            handler_id: 9,
            payload: EventPayload::ClipboardText {
                request: 1,
                status: 0,
                len: clipboard.chars().count() as u32,
                text: &clipboard,
            },
        },
        HostEvent {
            node_id: 5,
            handler_id: 2,
            payload: EventPayload::TextChanged(&edit),
        },
        HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: EventPayload::IoAnswer {
                request: 7,
                status: IoStatus::Ok,
                data: &value,
            },
        },
    ];

    let mut lender = LentEventBuffer::new();
    let mut wire = Vec::new();
    let mut read = Vec::new();
    for event in &events {
        encode_event(event, &mut wire).expect("the event did not encode");
        assert!(wire.len() > WEB_EVENT_BUFFER_BYTES as usize);
        let arrived = through_lent_buffer(&mut lender, &wire, &mut read);
        assert_eq!(
            &arrived, event,
            "the Host read something other than what was sent"
        );
    }
    assert!(lender.len() >= wire.len());
}

/// The lent buffer is kept: a request it already satisfies answers with the same address and
/// allocates nothing, so a second event like the largest one costs nothing.
#[test]
fn pr6_a_lent_buffer_is_kept_until_a_larger_one_is_asked_for() {
    let mut lender = LentEventBuffer::new();
    assert!(lender.is_empty());
    let grown = lender.lend(16 * 1024).expect("a buffer under the ceiling");
    assert_eq!(lender.len(), 16 * 1024);
    for smaller in [1, 8 * 1024, 16 * 1024] {
        assert_eq!(
            lender.lend(smaller),
            Some(grown),
            "a request for {smaller} bytes moved the buffer"
        );
    }
    assert_eq!(
        lender.len(),
        16 * 1024,
        "a smaller request does not shrink it"
    );
    lender.lend(32 * 1024).expect("a buffer under the ceiling");
    assert_eq!(lender.len(), 32 * 1024);
}

/// Past 64 MiB the Host refuses, and a refusal leaves the buffer it lent before in place,
/// because the Renderer goes on encoding into that one.
#[test]
fn pr6_an_event_past_the_ceiling_is_refused() {
    assert_eq!(WEB_EVENT_BUFFER_MAX_BYTES, 64 * 1024 * 1024);
    let mut lender = LentEventBuffer::new();
    let kept = lender.lend(8 * 1024).expect("a buffer under the ceiling");
    assert_eq!(lender.lend(WEB_EVENT_BUFFER_MAX_BYTES + 1), None);
    assert_eq!(lender.lend(u32::MAX), None);
    assert_eq!(lender.lend(0), None, "a request for nothing lends nothing");
    assert_eq!(
        lender.len(),
        8 * 1024,
        "a refusal frees nothing and lends nothing"
    );
    assert_eq!(lender.lend(8 * 1024), Some(kept));

    let largest = lender
        .lend(WEB_EVENT_BUFFER_MAX_BYTES)
        .expect("the ceiling itself is lent");
    assert!(!largest.is_null());
    assert_eq!(lender.len(), WEB_EVENT_BUFFER_MAX_BYTES as usize);
}

/// Only an address in the Host's region is ever handed to the Renderer. Below it is the
/// Renderer's own allocator, and past 32 bits is not an address a browser memory has.
#[test]
fn pr6_a_lent_address_is_in_the_host_region() {
    let below = (WEB_RUST_REGION_BASE as usize - 1) as *const u8;
    let base = WEB_RUST_REGION_BASE as usize as *const u8;
    let above = (WEB_RUST_REGION_BASE as usize + 4096) as *const u8;
    assert_eq!(region_address(std::ptr::null()), 0);
    assert_eq!(region_address(below), 0);
    assert_eq!(region_address(base), WEB_RUST_REGION_BASE);
    assert_eq!(region_address(above), WEB_RUST_REGION_BASE + 4096);
    #[cfg(target_pointer_width = "64")]
    assert_eq!(region_address((u32::MAX as usize + 1) as *const u8), 0);
}

/// The export the Renderer borrows from is generated beside the boundary shims, takes one
/// 32-bit length, answers with one 32-bit address, and only ever answers with an address
/// the region check passed. The forwarder is the same fixed shape as every other one.
#[test]
fn pr6_the_lend_export_and_its_forwarder_come_from_the_schema() {
    let rust = generate_wasm_rust();
    let kotlin = generate_web_bridge_kotlin();
    assert!(
        rust.contains(&format!(
            "#[unsafe(no_mangle)]\npub extern \"C\" fn {WEB_LEND_EVENT_BUFFER_SYMBOL}(min_len: u32) -> u32 {{"
        )),
        "the wasm module has to export {WEB_LEND_EVENT_BUFFER_SYMBOL}"
    );
    assert!(
        rust.contains(".map_or(0, |pointer| region_address(pointer.cast_const()))"),
        "the export has to answer zero for anything the region check refuses"
    );
    assert!(
        kotlin.contains(&format!(
            "@JsFun(\"(minLength) => globalThis.__composeRustHost.{WEB_LEND_EVENT_BUFFER_SYMBOL}(minLength)\")\n\
             external fun hostLendEventBuffer(minLength: Int): Int"
        )),
        "the Renderer's forwarder for {WEB_LEND_EVENT_BUFFER_SYMBOL} is not the fixed shape"
    );
    assert!(kotlin.contains(&format!(
        "const val EVENT_BUFFER_MAX_BYTES: Int = {WEB_EVENT_BUFFER_MAX_BYTES}"
    )));
    // Setup, not a boundary operation: the list of boundary functions does not grow.
    assert!(
        BOUNDARY_SCHEMA
            .iter()
            .all(|op| !op.symbol.contains("lend_event_buffer")),
        "lending is setup and has no place in the boundary schema"
    );
}

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

fn count(signature: &str) -> usize {
    signature
        .split(',')
        .filter(|argument| !argument.trim().is_empty())
        .count()
}

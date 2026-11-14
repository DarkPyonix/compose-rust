//! The web boundary: four generated files, one schema, and nothing on the call path that
//! serialises, copies, queues or is JavaScript.
//!
//! Every assertion here is about what the generator writes, because that is the only part
//! of this boundary a test on this machine can reach. What the browser does with it is
//! checked by running the page.

use compose_rust::codegen::{
    WEB_HOST_WASM_NAME, WEB_LOADER_FILE_NAME, WEB_LOADER_RELATIVE_PATH, WEB_MEMORY_MIN_PAGES,
    WEB_TEST_LOADER_RELATIVE_PATH, generate_wasm_rust, generate_web_bridge_kotlin,
    generate_web_loader_js, generate_web_trampoline_wasm,
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

/// The Kotlin types an operation takes in the browser, in order.
///
/// A byte range is an address and a length. A frame timestamp is one `Long`: every call is
/// wasm to wasm, so a 64-bit value crosses as an `i64` and nothing turns it into a
/// `BigInt`. A call that answers with a batch, and the one that releases it, also name the
/// record to write it into.
fn kotlin_types(op: &BoundaryOp) -> Vec<&'static str> {
    let mut types = Vec::new();
    for param in op.params {
        match param {
            BoundaryParam::Bytes { .. } => types.extend(["Int", "Int"]),
            BoundaryParam::Nanos { .. } => types.push("Long"),
        }
    }
    if op.returns_batch || op.name == "ReleaseBatch" {
        types.push("Int");
    }
    types
}

/// The same, as the wasm value types the trampoline declares.
fn wasm_types(op: &BoundaryOp) -> Vec<u8> {
    kotlin_types(op)
        .iter()
        .map(|kind| if *kind == "Long" { 0x7E } else { 0x7F })
        .collect()
}

fn arguments(op: &BoundaryOp) -> usize {
    kotlin_types(op).len()
}

/// Every call the trampoline carries, in slot order, as (symbol, wasm parameter types): the
/// boundary operations in schema order, then the event buffer lend, which takes one length.
fn trampoline_calls() -> Vec<(String, Vec<u8>)> {
    let mut calls: Vec<(String, Vec<u8>)> = BOUNDARY_SCHEMA
        .iter()
        .map(|op| (symbol(op), wasm_types(op)))
        .collect();
    calls.push((WEB_LEND_EVENT_BUFFER_SYMBOL.to_owned(), vec![0x7F]));
    calls
}

/// A non-negative slot number as the signed LEB128 an `i32.const` carries it in.
fn sleb(value: u32) -> Vec<u8> {
    let mut value = value as i64;
    let mut output = Vec::new();
    loop {
        let byte = (value & 0x7F) as u8;
        value >>= 7;
        if value == 0 && byte & 0x40 == 0 {
            output.push(byte);
            return output;
        }
        output.push(byte | 0x80);
    }
}

/// The four parts are renderings of one table, and what is checked in has to be what the
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
            "missing import declaration for {}",
            op.name
        );
        assert!(
            kotlin.contains(&symbol),
            "the import for {} does not name {symbol}",
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
    // The test page needs the same loader, because the Renderer's module imports it by name
    // and does not load without it.
    assert_eq!(
        include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../renderer/web/testResources/compose-rust-host.gen.mjs"
        )),
        loader,
        "the test page's loader is stale; run `cargo run -p compose-rust --bin codegen`",
    );
    assert!(
        WEB_LOADER_RELATIVE_PATH.ends_with(&format!("/resources/{WEB_LOADER_FILE_NAME}"))
            && WEB_TEST_LOADER_RELATIVE_PATH
                .ends_with(&format!("/testResources/{WEB_LOADER_FILE_NAME}")),
        "the loader has to sit under the name the Kotlin imports name"
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

/// Only primitives cross: every argument is an address, a length or a timestamp, each a
/// wasm number, and every answer is a 32-bit status. Anything else would be a value
/// something had to build.
#[test]
fn pr6_only_primitives_cross_the_boundary() {
    let kotlin = generate_web_bridge_kotlin();
    for op in BOUNDARY_SCHEMA {
        let declaration = format!("external fun host{}(", op.name);
        let signature = signature_after(&kotlin, &declaration, ')');
        let declared: Vec<&str> = signature
            .split(',')
            .filter(|part| !part.trim().is_empty())
            .map(|part| part.rsplit(':').next().expect("a declared type").trim())
            .collect();
        assert_eq!(
            declared,
            kotlin_types(op),
            "{} takes the wrong types in Kotlin",
            op.name
        );
        assert!(
            kotlin.contains(&format!("{declaration}{signature}): Int")),
            "{} has to answer with a status",
            op.name
        );
    }
}

/// Every Renderer to Host call is a wasm import bound to the trampoline, so no call has
/// JavaScript on it. The only JavaScript the Kotlin bridge carries is the one-time
/// instantiation, which is not a boundary call.
#[test]
fn pr6_renderer_to_host_calls_are_wasm_imports_with_no_javascript() {
    let kotlin = generate_web_bridge_kotlin();
    for op in BOUNDARY_SCHEMA {
        let expected = format!(
            "@WasmImport(\"./{WEB_LOADER_FILE_NAME}\", \"{}\")\nexternal fun host{}(",
            symbol(op),
            op.name
        );
        assert!(
            kotlin.contains(&expected),
            "{} is not a wasm import bound to the trampoline; expected\n{expected}",
            op.name
        );
    }
    assert!(
        kotlin.contains(&format!(
            "@WasmImport(\"./{WEB_LOADER_FILE_NAME}\", \"{WEB_LEND_EVENT_BUFFER_SYMBOL}\")\n\
             external fun hostLendEventBuffer(minLength: Int): Int"
        )),
        "the event buffer lend is not a wasm import bound to the trampoline"
    );
    assert_eq!(
        kotlin.matches("@JsFun(").count(),
        1,
        "the only JavaScript in the Kotlin bridge is installHost; a second @JsFun is a \
         JavaScript forwarder on a call into the Host"
    );
    assert!(kotlin.contains("external fun installHost(): kotlin.js.Promise<JsNumber>"));
    // Start-up is the one asynchronous step, and it is all inside installHost. Everything
    // else in the bridge is on the call path, where nothing waits, encodes or copies.
    let install = kotlin
        .find("@JsFun(")
        .expect("installHost's @JsFun is missing");
    let install_end = kotlin[install..]
        .find("external fun installHost()")
        .map(|at| install + at)
        .expect("installHost's declaration is missing");
    let calls = format!("{}{}", &kotlin[..install], &kotlin[install_end..]);
    for forbidden in [
        "await",
        "Promise.",
        ".then(",
        "new Uint8Array",
        "JSON",
        "BigInt",
    ] {
        assert!(
            !calls.contains(forbidden),
            "the Kotlin bridge mentions {forbidden} outside the start-up, which nothing on \
             the boundary may do"
        );
    }
    for forbidden in ["new Uint8Array", "JSON", "BigInt"] {
        assert!(
            !kotlin[install..install_end].contains(forbidden),
            "installHost mentions {forbidden}, which the start-up has no use for"
        );
    }
}

/// A browser refuses to instantiate a module over 8MB synchronously on its main thread, and
/// a Host that carries an HTML engine is larger than that. So the Host is instantiated with
/// the asynchronous form and no synchronous compile or instantiation is generated anywhere.
#[test]
fn pr6_the_host_is_instantiated_asynchronously() {
    let kotlin = generate_web_bridge_kotlin();
    let loader = generate_web_loader_js();
    assert!(
        kotlin.contains("return WebAssembly.instantiate(compiled, {"),
        "the Host has to be instantiated with the asynchronous WebAssembly.instantiate"
    );
    assert!(
        kotlin.contains("}).then((instance) => {"),
        "and the table filled once that has settled"
    );
    assert!(
        kotlin.contains("if (!compiled) return Promise.resolve(0);"),
        "a page without a Host still answers a promise, so main has one shape to wait for"
    );
    for generated in [&kotlin, &loader] {
        for synchronous in ["new WebAssembly.Instance", "new WebAssembly.Module"] {
            assert!(
                !generated.contains(synchronous),
                "{synchronous} is generated, which a browser refuses for a module over 8MB"
            );
        }
    }
}

/// The loader hands the Renderer the trampoline's own functions, not closures around them,
/// so the engine binds each Kotlin import as a wasm call.
#[test]
fn pr6_the_loader_exports_the_trampoline_functions_themselves() {
    let loader = generate_web_loader_js();
    for (symbol, _) in trampoline_calls() {
        assert!(
            loader.contains(&format!("export const {symbol} = trampoline.{symbol};")),
            "the loader does not export the trampoline's {symbol} as itself"
        );
    }
    assert_eq!(
        loader.matches("=>").count(),
        0,
        "the loader defines a JavaScript function, which would be a frame on a call"
    );
    assert!(
        loader.contains(&format!(
            "new WebAssembly.Table({{ element: 'anyfunc', initial: {} }})",
            BOUNDARY_SCHEMA.len() + 1
        )),
        "the table needs one slot per operation and one for the event buffer lend"
    );
    assert!(
        loader.contains("{ env: { table } }"),
        "the trampoline imports the table the Host's exports go into"
    );
}

/// The trampoline itself: one function per call into the Host, of the call's own type,
/// that passes its arguments on through its slot and does nothing else. Parsed here byte by
/// byte, because the browser would report a mistake only as a trap at the first frame.
#[test]
fn pr6_the_trampoline_calls_each_slot_through_the_table() {
    let module = Wasm::parse(&generate_web_trampoline_wasm());
    let calls = trampoline_calls();
    let count = calls.len() as u32;

    assert_eq!(
        module.table_import,
        Some(("env".to_owned(), "table".to_owned(), count)),
        "the trampoline imports env.table with one slot per call into the Host"
    );
    assert!(
        module.memories == 0,
        "the trampoline has no memory of its own"
    );
    assert_eq!(module.functions.len(), calls.len());
    for (slot, (symbol, wasm_params)) in calls.iter().enumerate() {
        assert_eq!(
            module.exports.get(slot),
            Some(&(symbol.clone(), slot as u32)),
            "export {slot} is not {symbol}"
        );
        let type_index = module.functions[slot];
        let (params, results) = &module.types[type_index as usize];
        assert_eq!(params, wasm_params, "{symbol} has the wrong parameters");
        assert_eq!(results, &vec![0x7Fu8], "{symbol} has to answer with an i32");

        let mut expected = vec![0x00];
        for parameter in 0..params.len() {
            expected.push(0x20);
            expected.push(parameter as u8);
        }
        expected.push(0x41);
        expected.extend(sleb(slot as u32));
        expected.extend([0x11, type_index as u8, 0x00, 0x0B]);
        assert_eq!(
            module.bodies[slot], expected,
            "{symbol} does not just pass its arguments to slot {slot}"
        );
    }
}

/// The loader carries exactly the trampoline the generator assembles.
#[test]
fn pr6_the_loader_embeds_the_generated_trampoline() {
    let loader = generate_web_loader_js();
    let start = loader
        .find("new Uint8Array([")
        .expect("the loader has no trampoline bytes")
        + "new Uint8Array([".len();
    let end = start
        + loader[start..]
            .find("])")
            .expect("unterminated trampoline bytes");
    let embedded: Vec<u8> = loader[start..end]
        .split(',')
        .map(str::trim)
        .filter(|byte| !byte.is_empty())
        .map(|byte| {
            u8::from_str_radix(byte.trim_start_matches("0x"), 16).expect("a hexadecimal byte")
        })
        .collect();
    assert_eq!(embedded, generate_web_trampoline_wasm());
}

/// The Host's exports go into the table only after it has reported a block inside its own
/// region. Filled earlier, the first call could reach a Host whose data overlaps the
/// Renderer's allocator.
#[test]
fn pr6_the_table_is_filled_only_after_the_host_checks_out() {
    let kotlin = generate_web_bridge_kotlin();
    let check = kotlin
        .find(&format!("if (block < {WEB_RUST_REGION_BASE}) {{"))
        .expect("the block check is missing");
    for (slot, (symbol, _)) in trampoline_calls().iter().enumerate() {
        let fill = format!("table.set({slot}, host.{symbol});");
        let at = kotlin
            .find(&fill)
            .unwrap_or_else(|| panic!("slot {slot} is never filled with {symbol}"));
        assert!(
            at > check,
            "slot {slot} is filled before the block is checked"
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
            !kotlin.contains(forbidden),
            "the wiring reaches into the shared memory through {forbidden}; only the two \
             modules read it"
        );
    }
    // The loader never sees the memory at all. Its one byte array is the trampoline's code.
    assert!(
        !loader.contains(".buffer") && !loader.contains("DataView"),
        "the loader reaches into the shared memory; only the two modules read it"
    );
    assert_eq!(loader.matches("Uint8Array").count(), 1);
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
        kotlin.contains("if (!compiled) return Promise.resolve(0);"),
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
/// the region check passed. The Renderer reaches it the way it reaches every boundary
/// call: a wasm import bound to the trampoline, in the slot after the boundary operations.
#[test]
fn pr6_the_lend_export_and_its_import_come_from_the_schema() {
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
            "@WasmImport(\"./{WEB_LOADER_FILE_NAME}\", \"{WEB_LEND_EVENT_BUFFER_SYMBOL}\")\n\
             external fun hostLendEventBuffer(minLength: Int): Int"
        )),
        "the Renderer's import of {WEB_LEND_EVENT_BUFFER_SYMBOL} is not bound to the trampoline"
    );
    assert!(
        kotlin.contains(&format!(
            "table.set({}, host.{WEB_LEND_EVENT_BUFFER_SYMBOL});",
            BOUNDARY_SCHEMA.len()
        )),
        "the lend's slot comes right after the boundary operations'"
    );
    assert!(
        !kotlin.contains("__composeRustHost.") && !kotlin.contains("__composeRustHost ="),
        "nothing reaches the Host through a global any more"
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

/// Just enough of a wasm module reader to check the trampoline the generator assembles.
struct Wasm {
    types: Vec<(Vec<u8>, Vec<u8>)>,
    table_import: Option<(String, String, u32)>,
    functions: Vec<u32>,
    memories: u32,
    exports: Vec<(String, u32)>,
    bodies: Vec<Vec<u8>>,
}

impl Wasm {
    fn parse(bytes: &[u8]) -> Self {
        assert_eq!(
            &bytes[..8],
            b"\0asm\x01\0\0\0",
            "not a version 1 wasm module"
        );
        let mut reader = Reader { bytes, at: 8 };
        let mut module = Wasm {
            types: Vec::new(),
            table_import: None,
            functions: Vec::new(),
            memories: 0,
            exports: Vec::new(),
            bodies: Vec::new(),
        };
        while reader.at < bytes.len() {
            let id = reader.byte();
            let size = reader.uleb() as usize;
            let end = reader.at + size;
            match id {
                1 => {
                    for _ in 0..reader.uleb() {
                        assert_eq!(reader.byte(), 0x60, "a type that is not a function type");
                        let params = reader.vector();
                        let results = reader.vector();
                        module.types.push((params, results));
                    }
                }
                2 => {
                    for _ in 0..reader.uleb() {
                        let namespace = reader.name();
                        let name = reader.name();
                        assert_eq!(reader.byte(), 0x01, "the trampoline imports only a table");
                        assert_eq!(reader.byte(), 0x70, "the table holds functions");
                        let flags = reader.byte();
                        let minimum = reader.uleb();
                        if flags & 1 != 0 {
                            reader.uleb();
                        }
                        module.table_import = Some((namespace, name, minimum));
                    }
                }
                3 => {
                    for _ in 0..reader.uleb() {
                        let index = reader.uleb();
                        module.functions.push(index);
                    }
                }
                5 => module.memories += reader.uleb(),
                7 => {
                    for _ in 0..reader.uleb() {
                        let name = reader.name();
                        assert_eq!(reader.byte(), 0x00, "the trampoline exports only functions");
                        let index = reader.uleb();
                        module.exports.push((name, index));
                    }
                }
                10 => {
                    for _ in 0..reader.uleb() {
                        let length = reader.uleb() as usize;
                        module
                            .bodies
                            .push(bytes[reader.at..reader.at + length].to_vec());
                        reader.at += length;
                    }
                }
                other => panic!("the trampoline has an unexpected section {other}"),
            }
            assert_eq!(reader.at, end, "section {id} is not the length it declares");
        }
        module
    }
}

struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl Reader<'_> {
    fn byte(&mut self) -> u8 {
        let byte = self.bytes[self.at];
        self.at += 1;
        byte
    }

    fn uleb(&mut self) -> u32 {
        let mut value = 0u32;
        let mut shift = 0;
        loop {
            let byte = self.byte();
            value |= u32::from(byte & 0x7F) << shift;
            if byte & 0x80 == 0 {
                return value;
            }
            shift += 7;
        }
    }

    fn vector(&mut self) -> Vec<u8> {
        let length = self.uleb() as usize;
        let items = self.bytes[self.at..self.at + length].to_vec();
        self.at += length;
        items
    }

    fn name(&mut self) -> String {
        String::from_utf8(self.vector()).expect("a UTF-8 name")
    }
}

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
use compose_rust::schema::{
    BOUNDARY_SCHEMA, BoundaryOp, BoundaryParam, WEB_BATCH_BYTES, WEB_BATCH_FIELDS,
    WEB_EVENT_BUFFER_BYTES, WEB_EVENT_BUFFER_OFFSET, WEB_RUST_REGION_BASE, WEB_START_SYMBOL,
};

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
    assert_eq!(
        kotlin.matches("@JsFun(").count(),
        1,
        "the only JavaScript in the Kotlin bridge is installHost; a second @JsFun is a \
         JavaScript forwarder on a boundary call"
    );
    assert!(kotlin.contains("external fun installHost(): Int"));
    for forbidden in ["await", "Promise", "new Uint8Array", "JSON", "BigInt"] {
        assert!(
            !kotlin.contains(forbidden),
            "the Kotlin bridge mentions {forbidden}, which nothing on the boundary may do"
        );
    }
}

/// The loader hands the Renderer the trampoline's own functions, not closures around them,
/// so the engine binds each Kotlin import as a wasm call.
#[test]
fn pr6_the_loader_exports_the_trampoline_functions_themselves() {
    let loader = generate_web_loader_js();
    for op in BOUNDARY_SCHEMA {
        let symbol = symbol(op);
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
            BOUNDARY_SCHEMA.len()
        )),
        "the table needs one slot per operation"
    );
    assert!(
        loader.contains("{ env: { table } }"),
        "the trampoline imports the table the Host's exports go into"
    );
}

/// The trampoline itself: one function per operation, of the operation's own type, that
/// passes its arguments on through its slot and does nothing else. Parsed here byte by byte,
/// because the browser would report a mistake only as a trap at the first frame.
#[test]
fn pr6_the_trampoline_calls_each_slot_through_the_table() {
    let module = Wasm::parse(&generate_web_trampoline_wasm());
    let count = BOUNDARY_SCHEMA.len() as u32;

    assert_eq!(
        module.table_import,
        Some(("env".to_owned(), "table".to_owned(), count)),
        "the trampoline imports env.table with one slot per operation"
    );
    assert!(
        module.memories == 0,
        "the trampoline has no memory of its own"
    );
    assert_eq!(module.functions.len(), BOUNDARY_SCHEMA.len());
    for (slot, op) in BOUNDARY_SCHEMA.iter().enumerate() {
        assert_eq!(
            module.exports.get(slot),
            Some(&(symbol(op), slot as u32)),
            "export {slot} is not {}",
            symbol(op)
        );
        let type_index = module.functions[slot];
        let (params, results) = &module.types[type_index as usize];
        assert_eq!(
            params,
            &wasm_types(op),
            "{} has the wrong parameters",
            op.name
        );
        assert_eq!(
            results,
            &vec![0x7Fu8],
            "{} has to answer with an i32",
            op.name
        );

        let mut expected = vec![0x00];
        for parameter in 0..params.len() {
            expected.push(0x20);
            expected.push(parameter as u8);
        }
        expected.extend([0x41, slot as u8, 0x11, type_index as u8, 0x00, 0x0B]);
        assert_eq!(
            module.bodies[slot], expected,
            "{} does not just pass its arguments to slot {slot}",
            op.name
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
    for (slot, op) in BOUNDARY_SCHEMA.iter().enumerate() {
        let fill = format!("table.set({slot}, host.{});", symbol(op));
        let at = kotlin
            .find(&fill)
            .unwrap_or_else(|| panic!("slot {slot} is never filled with {}", symbol(op)));
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
        kotlin.contains("if (!compiled) return 0;"),
        "a missing Host has to answer zero rather than throw"
    );
    assert!(
        loader.contains("console.info("),
        "and say on the console why the screen is the development host"
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

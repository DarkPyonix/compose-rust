//! The web boundary: three generated files, one schema, and nothing on the call path that
//! serialises, copies or queues.
//!
//! Every assertion here is about what the generator writes, because that is the only part
//! of this boundary a test on this machine can reach. What the browser does with it is
//! checked by running the page.

use dioxus_compose::codegen::{
    WEB_HOST_WASM_NAME, WEB_MEMORY_MIN_PAGES, generate_wasm_rust, generate_web_bridge_kotlin,
    generate_web_loader_js,
};
use dioxus_compose::schema::{
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
    format!("dioxus_compose_host_web_{snake}")
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
    // The work is generated; the export is in `web_main!`, because a wasm module cannot
    // be linked with an undefined symbol the way an ELF shared library can, so this
    // crate's own module must not name a function only an application can define.
    assert!(
        rust.contains("pub fn web_start(builder: LaunchBuilder, app: fn() -> Element) -> u32 {"),
        "the extra entry point a page needs in place of a library loader is missing"
    );
    assert!(
        include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/lib.rs")).contains(&format!(
            "pub extern \"C\" fn {WEB_START_SYMBOL}() -> u32 {{"
        )),
        "`web_main!` has to export it under the name the page calls"
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
        "generated wasm shims are stale; run `cargo run -p dioxus-compose --bin codegen`",
    );
    assert_eq!(
        include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../dioxus-compose-renderer/web/src/bridge/HostBridge.gen.kt"
        )),
        kotlin,
        "generated Kotlin bridge is stale; run `cargo run -p dioxus-compose --bin codegen`",
    );
    assert_eq!(
        include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../dioxus-compose-renderer/web/resources/dioxus-compose-host.gen.mjs"
        )),
        loader,
        "the generated loader is stale; run `cargo run -p dioxus-compose --bin codegen`",
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
            "@JsFun(\"({names}) => globalThis.__dioxusComposeHost.{}({names})\")",
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
    let binding = signature_after(&kotlin, "dioxus_compose_renderer_request_frame:", ',');
    assert!(
        binding.contains("wasmExports.dioxus_compose_renderer_request_frame"),
        "the frame request has to be bound to the Renderer's export, got: {binding}"
    );
    assert!(
        !binding.contains("=>"),
        "the frame request is wrapped in a closure, which puts a JavaScript frame on it: {binding}"
    );
    assert!(
        kotlin.contains("@WasmExport(\"dioxus_compose_renderer_request_frame\")"),
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
    let loader = generate_web_loader_js();

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

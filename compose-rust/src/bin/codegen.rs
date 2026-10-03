use compose_rust::codegen::{
    ANDROID_BRIDGE_RELATIVE_PATH, ANDROID_FAST_NATIVE_RELATIVE_PATH, EVENT_VECTOR_RELATIVE_PATH,
    GENERATED_RELATIVE_PATH, JNI_RUST_RELATIVE_PATH, MUTATION_VECTOR_RELATIVE_PATH,
    SCHEMA_HASH_RELATIVE_PATH, VECTOR_DESCRIPTION_RELATIVE_PATH, WASM_RUST_RELATIVE_PATH,
    WEB_BRIDGE_RELATIVE_PATH, WEB_LOADER_RELATIVE_PATH, WEB_TEST_LOADER_RELATIVE_PATH,
    generate_android_bridge_kotlin, generate_event_vector, generate_fast_native_kotlin,
    generate_jni_rust, generate_kotlin, generate_mutation_vector, generate_schema_hash,
    generate_vector_description, generate_wasm_rust, generate_web_bridge_kotlin,
    generate_web_loader_js,
};
use std::path::{Path, PathBuf};

fn main() {
    if let Err(message) = run() {
        eprintln!("codegen: {message}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let manifest_dir = output_tree(
        Path::new(env!("CARGO_MANIFEST_DIR")),
        std::env::var_os("CARGO_MANIFEST_DIR").map(PathBuf::from),
    )?;
    write(
        manifest_dir.join(GENERATED_RELATIVE_PATH),
        generate_kotlin().as_bytes(),
    )?;
    write(
        manifest_dir.join(SCHEMA_HASH_RELATIVE_PATH),
        generate_schema_hash().as_bytes(),
    )?;
    write(
        manifest_dir.join(MUTATION_VECTOR_RELATIVE_PATH),
        &generate_mutation_vector()?,
    )?;
    write(
        manifest_dir.join(EVENT_VECTOR_RELATIVE_PATH),
        &generate_event_vector()?,
    )?;
    write(
        manifest_dir.join(VECTOR_DESCRIPTION_RELATIVE_PATH),
        generate_vector_description().as_bytes(),
    )?;
    // Both halves of the Android boundary come from the same schema.
    write(
        manifest_dir.join(JNI_RUST_RELATIVE_PATH),
        generate_jni_rust().as_bytes(),
    )?;
    write(
        manifest_dir.join(ANDROID_BRIDGE_RELATIVE_PATH),
        generate_android_bridge_kotlin().as_bytes(),
    )?;
    write(
        manifest_dir.join(ANDROID_FAST_NATIVE_RELATIVE_PATH),
        generate_fast_native_kotlin().as_bytes(),
    )?;
    // Every part of the web boundary comes from the same schema: the shims, the imports
    // that call them, and the loader that carries the trampoline between the two and wires
    // the modules together.
    write(
        manifest_dir.join(WASM_RUST_RELATIVE_PATH),
        generate_wasm_rust().as_bytes(),
    )?;
    write(
        manifest_dir.join(WEB_BRIDGE_RELATIVE_PATH),
        generate_web_bridge_kotlin().as_bytes(),
    )?;
    write(
        manifest_dir.join(WEB_LOADER_RELATIVE_PATH),
        generate_web_loader_js().as_bytes(),
    )?;
    // The same loader beside the test page, which the Renderer's module also imports.
    write(
        manifest_dir.join(WEB_TEST_LOADER_RELATIVE_PATH),
        generate_web_loader_js().as_bytes(),
    )?;
    Ok(())
}

/// The crate directory this run may write generated files into.
///
/// `compiled_in` is `env!("CARGO_MANIFEST_DIR")`, fixed when the binary was compiled.
/// `invoked_in` is the `CARGO_MANIFEST_DIR` cargo puts into the environment of a process
/// it launches, which names the checkout the command was actually typed in.
///
/// The two disagree when more than one checkout of this repository builds into one
/// directory. Cargo leaves the package path out of the unit hash for a path package, so
/// two checkouts holding identical sources are a single cache entry, and the binary that
/// runs is whichever one compiled first. Its generated files would land in that checkout
/// rather than in the one the command was run from, which has already happened here and
/// overwrote another worktree's files while someone was working in it.
///
/// With no `invoked_in` the binary was started outside cargo, and the directory it was
/// compiled in is the only checkout it knows anything about.
fn output_tree(compiled_in: &Path, invoked_in: Option<PathBuf>) -> Result<PathBuf, String> {
    let Some(invoked_in) = invoked_in else {
        return Ok(compiled_in.to_path_buf());
    };
    if same_directory(compiled_in, &invoked_in) {
        return Ok(invoked_in);
    }
    Err(format!(
        "refusing to write into a checkout this binary was not compiled in.\n  \
         compiled in: {}\n  \
         running in:  {}\n\
         Both checkouts build into one directory, so cargo reused the binary compiled in \
         the first one and the paths compiled into it point there. Give this checkout its \
         own build directory: run scripts/setup-worktrees.sh, which removes a target-dir \
         from .cargo/config.toml, and unset CARGO_TARGET_DIR. Then run this again.",
        compiled_in.display(),
        invoked_in.display(),
    ))
}

/// Whether two paths name the same directory, following symlinks when both exist.
///
/// `/tmp` and `/private/tmp` on macOS are the same directory under two names, and a
/// checkout reached through a symlink is the same checkout.
fn same_directory(left: &Path, right: &Path) -> bool {
    if left == right {
        return true;
    }
    match (std::fs::canonicalize(left), std::fs::canonicalize(right)) {
        (Ok(left), Ok(right)) => left == right,
        _ => false,
    }
}

fn write(path: PathBuf, contents: &[u8]) -> Result<(), Box<dyn std::error::Error>> {
    let parent = path.parent().ok_or("generated output has no parent")?;
    std::fs::create_dir_all(parent)?;
    std::fs::write(&path, contents)?;
    println!("generated {}", path.display());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn invoked(path: &str) -> Option<PathBuf> {
        Some(PathBuf::from(path))
    }

    #[test]
    fn fr7_writes_the_checkout_it_was_compiled_in() {
        let tree = output_tree(Path::new("/one/compose-rust"), invoked("/one/compose-rust"))
            .expect("the checkout that compiled it is the checkout it writes");
        assert_eq!(tree, PathBuf::from("/one/compose-rust"));
    }

    #[test]
    fn fr7_refuses_a_checkout_it_was_not_compiled_in() {
        let message = output_tree(Path::new("/one/compose-rust"), invoked("/two/compose-rust"))
            .expect_err("a binary compiled elsewhere must not write here");
        assert!(
            message.contains("/one/compose-rust") && message.contains("/two/compose-rust"),
            "the message has to name both checkouts, got: {message}"
        );
        assert!(
            message.contains("setup-worktrees.sh"),
            "the message has to say how to fix it, got: {message}"
        );
    }

    #[test]
    fn fr7_falls_back_to_the_compiled_checkout_outside_cargo() {
        let tree = output_tree(Path::new("/one/compose-rust"), None)
            .expect("run outside cargo there is no other checkout to mean");
        assert_eq!(tree, PathBuf::from("/one/compose-rust"));
    }

    #[test]
    fn nfr12_two_names_for_one_directory_are_one_checkout() {
        let temporary = std::env::temp_dir();
        let canonical = std::fs::canonicalize(&temporary).expect("the temporary directory exists");
        assert!(
            same_directory(&temporary, &canonical),
            "{} and {} are the same directory",
            temporary.display(),
            canonical.display()
        );
    }
}

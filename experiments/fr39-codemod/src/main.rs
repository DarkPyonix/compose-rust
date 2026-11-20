//! `fr39-codemod [--check] [--crate-path PATH] <file or directory>...`
//!
//! Rewrites every `.rs` file under the given paths in place, then prints what it changed
//! and every chain it left alone with the reason. With `--check` it writes nothing and
//! exits 1 if anything would change. Run `cargo fmt` afterwards.

use std::path::{Path, PathBuf};
use std::process::ExitCode;

fn main() -> ExitCode {
    let mut check = false;
    let mut options = fr39_codemod::Options::default();
    let mut paths = Vec::new();
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--check" => check = true,
            "--crate-path" => match args.next() {
                Some(path) => options.crate_path = path,
                None => {
                    eprintln!("--crate-path needs a value, such as ::compose_rust or crate");
                    return ExitCode::from(2);
                }
            },
            _ => paths.push(PathBuf::from(arg)),
        }
    }
    if paths.is_empty() {
        eprintln!("usage: fr39-codemod [--check] [--crate-path PATH] <file or directory>...");
        return ExitCode::from(2);
    }

    let mut files = Vec::new();
    for path in &paths {
        collect(path, &mut files);
    }
    let (mut changed, mut chains, mut imports, mut flagged, mut failed) = (0, 0, 0, 0, 0);
    for file in &files {
        let Ok(source) = std::fs::read_to_string(file) else {
            eprintln!("{}: cannot read", file.display());
            failed += 1;
            continue;
        };
        let outcome = match fr39_codemod::rewrite(&source, &options) {
            Ok(outcome) => outcome,
            Err(error) => {
                let at = error.span().start();
                eprintln!(
                    "{}:{}:{}: does not parse: {error}",
                    file.display(),
                    at.line,
                    at.column + 1
                );
                failed += 1;
                continue;
            }
        };
        for flag in &outcome.flags {
            println!(
                "{}:{}:{}: left alone: {}",
                file.display(),
                flag.line,
                flag.column,
                flag.reason
            );
        }
        flagged += outcome.flags.len();
        if outcome.source != source {
            changed += 1;
            chains += outcome.rewritten;
            imports += outcome.imports;
            if !check && std::fs::write(file, &outcome.source).is_err() {
                eprintln!("{}: cannot write", file.display());
                failed += 1;
            }
        }
    }
    println!(
        "{} files, {changed} {}, {chains} chains and {imports} imports rewritten, {flagged} left alone, {failed} failed",
        files.len(),
        if check { "would change" } else { "changed" },
    );
    if failed > 0 || (check && changed > 0) {
        ExitCode::from(1)
    } else {
        ExitCode::SUCCESS
    }
}

fn collect(path: &Path, files: &mut Vec<PathBuf>) {
    if path.is_file() {
        if path.extension().is_some_and(|ext| ext == "rs") {
            files.push(path.to_owned());
        }
        return;
    }
    let Ok(entries) = std::fs::read_dir(path) else {
        return;
    };
    let mut entries: Vec<PathBuf> = entries
        .filter_map(|entry| entry.ok().map(|e| e.path()))
        .collect();
    entries.sort();
    for entry in entries {
        let name = entry
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("");
        if entry.is_dir() && (name == "target" || name.starts_with('.')) {
            continue;
        }
        collect(&entry, files);
    }
}

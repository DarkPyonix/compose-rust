//! `compose-rust-preview` CLI: `list`, `export`, and `watch`.

use compose_rust::preview::{
    PreviewSize, format_scale, platform_default_system, scheme_slug, system_slug,
};
use compose_rust::{ColorScheme, DesignSystem};
use compose_rust_preview::discover::{
    DiscoveredPreview, discover_in_crate, matches_glob, parse_system_name,
};
use compose_rust_preview::image_cmp::compare_pngs;
use compose_rust_preview::render::{ExportRequest, export_batch_via_renderer, export_preview_png};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode};
use std::time::{Duration, Instant, SystemTime};
use walkdir::WalkDir;

fn main() -> ExitCode {
    let args: Vec<String> = env::args().skip(1).collect();
    match run(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(msg) => {
            eprintln!("compose-rust-preview: {msg}");
            ExitCode::FAILURE
        }
    }
}

fn run(args: &[String]) -> Result<(), String> {
    let Some((cmd, rest)) = args.split_first() else {
        return Err("usage: compose-rust-preview <list|export|watch> [options]".to_string());
    };
    match cmd.as_str() {
        "list" => cmd_list(rest),
        "export" => cmd_export(rest),
        "watch" => cmd_watch(rest),
        other => Err(format!(
            "unknown subcommand `{other}`; expected list, export, or watch"
        )),
    }
}

fn resolve_crate(package: Option<&str>) -> Result<(PathBuf, String), String> {
    let cwd = env::current_dir().map_err(|e| format!("current_dir failed: {e}"))?;
    if let Some(pkg) = package {
        let direct = cwd.join(pkg);
        if direct.join("Cargo.toml").is_file() {
            return Ok((direct, read_crate_name(&cwd.join(pkg))?));
        }
        for sub in ["samples", "compose-rust", "ember"] {
            let cand = cwd.join(sub).join(pkg);
            if cand.join("Cargo.toml").is_file() {
                return Ok((cand.clone(), read_crate_name(&cand)?));
            }
        }
        // Scan shallowly for matching package name in Cargo.toml.
        for entry in WalkDir::new(&cwd)
            .max_depth(3)
            .into_iter()
            .filter_map(Result::ok)
        {
            if entry.file_name() == "Cargo.toml"
                && entry.file_type().is_file()
                && let Some(dir) = entry.path().parent()
                && let Ok(name) = read_crate_name(dir)
                && name == pkg
            {
                return Ok((dir.to_path_buf(), name));
            }
        }
        return Err(format!("package `{pkg}` not found under {}", cwd.display()));
    }
    let name = read_crate_name(&cwd)?;
    Ok((cwd, name))
}

fn read_crate_name(crate_dir: &Path) -> Result<String, String> {
    let manifest = fs::read_to_string(crate_dir.join("Cargo.toml"))
        .map_err(|e| format!("failed to read {}/Cargo.toml: {e}", crate_dir.display()))?;
    let mut in_package = false;
    for line in manifest.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with('[') {
            in_package = trimmed == "[package]";
            continue;
        }
        if in_package
            && let Some(rest) = trimmed.strip_prefix("name")
            && let Some(val) = rest.trim().strip_prefix('=')
        {
            let v = val.trim().trim_matches('"').trim_matches('\'');
            if !v.is_empty() {
                return Ok(v.to_string());
            }
        }
    }
    Err(format!(
        "no [package] name in {}/Cargo.toml",
        crate_dir.display()
    ))
}

fn cmd_list(args: &[String]) -> Result<(), String> {
    let mut package: Option<String> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "-p" | "--package" => {
                i += 1;
                package = Some(args.get(i).ok_or("-p requires a crate name")?.clone());
            }
            other => return Err(format!("unknown flag `{other}` for list")),
        }
        i += 1;
    }
    let (dir, name) = resolve_crate(package.as_deref())?;
    let previews = discover_in_crate(&dir, &name)?;
    for p in previews {
        let sizes: Vec<String> = p
            .effective_sizes()
            .into_iter()
            .map(|s| format!("{}x{}", s.width_dp(), s.height_dp()))
            .collect();
        let schemes: Vec<&str> = p.effective_schemes().into_iter().map(scheme_slug).collect();
        let systems: Vec<&str> = p.effective_systems().into_iter().map(system_slug).collect();
        let scales: Vec<String> = p.effective_scales().into_iter().map(format_scale).collect();
        println!(
            "{}\t{}\tsizes=[{}]\tschemes=[{}]\tsystems=[{}]\tscales=[{}]",
            p.id,
            p.name,
            sizes.join(","),
            schemes.join(","),
            systems.join(","),
            scales.join(",")
        );
    }
    Ok(())
}

fn cmd_export(args: &[String]) -> Result<(), String> {
    let mut package: Option<String> = None;
    let mut preview_glob = String::from("*");
    let mut size_override: Option<PreviewSize> = None;
    let mut scheme_override: Option<Vec<ColorScheme>> = None;
    let mut system_override: Option<DesignSystem> = None;
    let mut scale_override: Option<f32> = None;
    let mut at_ms: Option<u64> = None;
    let mut batch_path: Option<PathBuf> = None;
    let mut out_dir: Option<PathBuf> = None;
    let mut compare_path: Option<PathBuf> = None;
    let mut check_dir: Option<PathBuf> = None;
    let mut max_diff: f64 = 0.0;
    let mut update = false;

    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "-p" | "--package" => {
                i += 1;
                package = Some(args.get(i).ok_or("-p requires a crate name")?.clone());
            }
            "--preview" => {
                i += 1;
                preview_glob = args
                    .get(i)
                    .ok_or("--preview requires an id or glob")?
                    .clone();
            }
            "--size" => {
                i += 1;
                let s = args.get(i).ok_or("--size requires a value")?;
                size_override = Some(PreviewSize::parse(s)?);
            }
            "--scheme" => {
                i += 1;
                let s = args.get(i).ok_or("--scheme requires light|dark|both")?;
                scheme_override = Some(match s.to_ascii_lowercase().as_str() {
                    "light" => vec![ColorScheme::Light],
                    "dark" => vec![ColorScheme::Dark],
                    "both" => vec![ColorScheme::Light, ColorScheme::Dark],
                    other => return Err(format!("invalid --scheme `{other}`")),
                });
            }
            "--system" => {
                i += 1;
                let s = args.get(i).ok_or("--system requires a value")?;
                system_override = Some(parse_system_name(s)?);
            }
            "--scale" => {
                i += 1;
                let s = args.get(i).ok_or("--scale requires a number")?;
                let v: f32 = s.parse().map_err(|_| format!("invalid --scale `{s}`"))?;
                if !v.is_finite() || v <= 0.0 {
                    return Err(format!("--scale must be positive, got `{s}`"));
                }
                scale_override = Some(v);
            }
            "--at-ms" => {
                i += 1;
                let s = args.get(i).ok_or("--at-ms requires milliseconds")?;
                at_ms = Some(s.parse().map_err(|_| format!("invalid --at-ms `{s}`"))?);
            }
            "--batch" => {
                i += 1;
                batch_path = Some(PathBuf::from(
                    args.get(i).ok_or("--batch requires a file path")?,
                ));
            }
            "--out" => {
                i += 1;
                out_dir = Some(PathBuf::from(
                    args.get(i).ok_or("--out requires a directory")?,
                ));
            }
            "--compare" => {
                i += 1;
                compare_path = Some(PathBuf::from(
                    args.get(i).ok_or("--compare requires a PNG path")?,
                ));
            }
            "--check" => {
                i += 1;
                check_dir = Some(PathBuf::from(
                    args.get(i).ok_or("--check requires a directory")?,
                ));
            }
            "--max-diff" => {
                i += 1;
                let s = args.get(i).ok_or("--max-diff requires a ratio")?;
                max_diff = s.parse().map_err(|_| format!("invalid --max-diff `{s}`"))?;
            }
            "--update" => {
                update = true;
            }
            other => return Err(format!("unknown flag `{other}` for export")),
        }
        i += 1;
    }

    let out_dir = out_dir.ok_or("export requires --out <dir>")?;
    let (dir, name) = resolve_crate(package.as_deref())?;
    let previews: Vec<DiscoveredPreview> = discover_in_crate(&dir, &name)?
        .into_iter()
        .filter(|p| matches_glob(&preview_glob, &p.id))
        .collect();
    if previews.is_empty() {
        return Err(format!(
            "no #[Preview] matched `{preview_glob}` in crate `{name}`"
        ));
    }

    let mut written = Vec::new();
    for p in previews {
        let sizes = size_override
            .map(|s| vec![s])
            .unwrap_or_else(|| p.effective_sizes());
        let schemes = scheme_override
            .clone()
            .unwrap_or_else(|| p.effective_schemes());
        let systems = system_override
            .map(|s| vec![s])
            .unwrap_or_else(|| p.effective_systems());
        let scales = scale_override
            .map(|s| vec![s])
            .unwrap_or_else(|| p.effective_scales());

        for size in sizes {
            for &scale in &scales {
                for &scheme in &schemes {
                    for &system in &systems {
                        let req = ExportRequest {
                            id: p.id.clone(),
                            size,
                            scale,
                            scheme,
                            system,
                            at_ms,
                            animation_duration_ms: None,
                        };
                        let (png_path, clock) = if let Some(bp) = batch_path.as_deref() {
                            export_batch_via_renderer(&req, bp, &out_dir)?
                        } else {
                            export_preview_png(&req, &out_dir)?
                        };
                        if clock.capped_at_limit {
                            eprintln!(
                                "warning: {} did not settle within 10s; captured at 10000ms",
                                p.id
                            );
                        }
                        println!("{}", png_path.display());
                        written.push(png_path);
                    }
                }
            }
        }
    }

    if let Some(cmp) = compare_path {
        for png_path in &written {
            let side = with_suffix(png_path, ".side.png");
            let diff = with_suffix(png_path, ".diff.png");
            let res = compare_pngs(png_path, &cmp, Some(&side), Some(&diff))?;
            println!(
                "compare {}: {:.6} ({}/{} pixels differ)",
                png_path.display(),
                res.diff_ratio(),
                res.diff_pixels,
                res.total_pixels
            );
        }
    }

    if let Some(ref_dir) = check_dir {
        fs::create_dir_all(&ref_dir)
            .map_err(|e| format!("failed to create {}: {e}", ref_dir.display()))?;
        let mut failed = false;
        for png_path in &written {
            let fname = png_path.file_name().ok_or("invalid output filename")?;
            let ref_path = ref_dir.join(fname);
            if update {
                fs::copy(png_path, &ref_path)
                    .map_err(|e| format!("failed to update {}: {e}", ref_path.display()))?;
                continue;
            }
            if !ref_path.is_file() {
                return Err(format!("missing reference image {}", ref_path.display()));
            }
            let diff = with_suffix(png_path, ".diff.png");
            let res = compare_pngs(png_path, &ref_path, None, Some(&diff))?;
            if res.diff_ratio() > max_diff {
                eprintln!(
                    "diff {} exceeded max_diff {:.6}: {:.6} ({}/{} pixels)",
                    png_path.display(),
                    max_diff,
                    res.diff_ratio(),
                    res.diff_pixels,
                    res.total_pixels
                );
                failed = true;
            } else {
                let _ = fs::remove_file(&diff);
            }
        }
        if failed {
            return Err("one or more previews differed from reference images".to_string());
        }
    }

    Ok(())
}

fn cmd_watch(args: &[String]) -> Result<(), String> {
    let mut package: Option<String> = None;
    let mut preview_glob = String::from("*");
    let mut size = PreviewSize::Desktop;
    let mut scheme = ColorScheme::Light;
    let mut system = platform_default_system();
    let mut scale = 1.0_f32;
    let mut once = false;

    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "-p" | "--package" => {
                i += 1;
                package = Some(args.get(i).ok_or("-p requires a crate name")?.clone());
            }
            "--preview" => {
                i += 1;
                preview_glob = args.get(i).ok_or("--preview requires an id")?.clone();
            }
            "--size" => {
                i += 1;
                size = PreviewSize::parse(args.get(i).ok_or("--size requires a value")?)?;
            }
            "--scheme" => {
                i += 1;
                scheme = match args
                    .get(i)
                    .ok_or("--scheme requires light|dark")?
                    .to_ascii_lowercase()
                    .as_str()
                {
                    "light" => ColorScheme::Light,
                    "dark" => ColorScheme::Dark,
                    other => return Err(format!("invalid --scheme `{other}`")),
                };
            }
            "--system" => {
                i += 1;
                system = parse_system_name(args.get(i).ok_or("--system requires a value")?)?;
            }
            "--scale" => {
                i += 1;
                scale = args
                    .get(i)
                    .ok_or("--scale requires a number")?
                    .parse()
                    .map_err(|_| "invalid --scale".to_string())?;
            }
            "--once" => {
                once = true;
            }
            other => return Err(format!("unknown flag `{other}` for watch")),
        }
        i += 1;
    }

    let (crate_dir, crate_name) = resolve_crate(package.as_deref())?;
    let target_dir = crate_dir.join("target").join("compose-rust-preview");
    let out_dir = target_dir.join("frames");

    let run_cycle = || -> Result<(), String> {
        let t0 = Instant::now();
        let build = Command::new("cargo")
            .args(["check", "-p", &crate_name])
            .env("CARGO_TARGET_DIR", &target_dir)
            .env("RUSTFLAGS", "--cfg compose_rust_preview")
            .status()
            .map_err(|e| format!("failed to run cargo check: {e}"))?;
        let build_ms = t0.elapsed().as_millis();
        if !build.success() {
            eprintln!("watch build failed ({build_ms} ms); keeping last good frame");
            return Ok(());
        }
        let t_render = Instant::now();
        let previews = discover_in_crate(&crate_dir, &crate_name)?;
        let selected = previews
            .into_iter()
            .find(|p| matches_glob(&preview_glob, &p.id))
            .ok_or_else(|| format!("no preview matched `{preview_glob}`"))?;
        let req = ExportRequest {
            id: selected.id,
            size,
            scale,
            scheme,
            system,
            at_ms: None,
            animation_duration_ms: None,
        };
        let (path, _) = export_preview_png(&req, &out_dir)?;
        let render_ms = t_render.elapsed().as_millis();
        let total_ms = t0.elapsed().as_millis();
        println!(
            "frame {} (build={}ms render={}ms total={}ms)",
            path.display(),
            build_ms,
            render_ms,
            total_ms
        );
        Ok(())
    };

    run_cycle()?;
    if once {
        return Ok(());
    }

    let mut last_mtime = newest_rs_mtime(&crate_dir.join("src"));
    loop {
        std::thread::sleep(Duration::from_millis(100));
        let cur = newest_rs_mtime(&crate_dir.join("src"));
        if cur > last_mtime {
            last_mtime = cur;
            run_cycle()?;
        }
    }
}

fn newest_rs_mtime(dir: &Path) -> SystemTime {
    WalkDir::new(dir)
        .into_iter()
        .filter_map(Result::ok)
        .filter(|e| e.file_type().is_file() && e.path().extension().is_some_and(|x| x == "rs"))
        .filter_map(|e| e.metadata().ok().and_then(|m| m.modified().ok()))
        .max()
        .unwrap_or(SystemTime::UNIX_EPOCH)
}

fn with_suffix(path: &Path, suffix: &str) -> PathBuf {
    let s = path.to_string_lossy();
    let stem = s.strip_suffix(".png").unwrap_or(&s);
    PathBuf::from(format!("{stem}{suffix}"))
}

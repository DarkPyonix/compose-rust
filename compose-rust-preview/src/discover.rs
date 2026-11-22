//! Source-level discovery of `#[Preview]` functions in a crate.
//!
//! Scans `.rs` files under `<crate>/src/` with `syn` so discovery needs no `build.rs`,
//! no pre-`main` hooks, and yields the exact same sorted list on macOS, Linux, and Windows.

use compose_rust::preview::{PreviewSize, platform_default_system};
use compose_rust::{ColorScheme, DesignSystem};
use std::fs;
use std::path::{Path, PathBuf};
use syn::parse::Parser;
use syn::punctuated::Punctuated;
use syn::{Attribute, Expr, ExprArray, Item, ItemFn, Lit, Meta, Token};
use walkdir::WalkDir;

/// One `#[Preview]` declaration found in a crate's source tree.
#[derive(Debug, Clone, PartialEq)]
pub struct DiscoveredPreview {
    pub id: String,
    pub name: String,
    pub source_file: PathBuf,
    pub sizes: Vec<PreviewSize>,
    pub schemes: Vec<ColorScheme>,
    pub systems: Vec<DesignSystem>,
    pub scales: Vec<f32>,
    pub fixture: Option<String>,
}

impl DiscoveredPreview {
    /// Effective sizes (defaults to `[Desktop]`).
    pub fn effective_sizes(&self) -> Vec<PreviewSize> {
        if self.sizes.is_empty() {
            vec![PreviewSize::Desktop]
        } else {
            self.sizes.clone()
        }
    }

    /// Effective colour schemes (defaults to `[Light]`).
    pub fn effective_schemes(&self) -> Vec<ColorScheme> {
        if self.schemes.is_empty() {
            vec![ColorScheme::Light]
        } else {
            self.schemes.clone()
        }
    }

    /// Effective design systems (defaults to `[platform_default_system()]`).
    pub fn effective_systems(&self) -> Vec<DesignSystem> {
        if self.systems.is_empty() {
            vec![platform_default_system()]
        } else {
            self.systems.clone()
        }
    }

    /// Effective scales (defaults to `[1.0]`).
    pub fn effective_scales(&self) -> Vec<f32> {
        if self.scales.is_empty() {
            vec![1.0]
        } else {
            self.scales.clone()
        }
    }
}

/// Discover all `#[Preview]` declarations under `crate_dir/src` for `crate_name`.
pub fn discover_in_crate(
    crate_dir: &Path,
    crate_name: &str,
) -> Result<Vec<DiscoveredPreview>, String> {
    let src_dir = crate_dir.join("src");
    if !src_dir.is_dir() {
        return Ok(Vec::new());
    }
    let normalized_crate = crate_name.replace('-', "_");
    let mut files: Vec<PathBuf> = WalkDir::new(&src_dir)
        .into_iter()
        .filter_map(Result::ok)
        .filter(|e| e.file_type().is_file() && e.path().extension().is_some_and(|x| x == "rs"))
        .map(|e| e.into_path())
        .collect();
    files.sort();

    let mut out = Vec::new();
    for file in files {
        let rel = file
            .strip_prefix(&src_dir)
            .map_err(|e| format!("strip_prefix failed: {e}"))?;
        let mod_prefix = module_prefix_for_file(&normalized_crate, rel);
        let source = fs::read_to_string(&file)
            .map_err(|e| format!("failed to read {}: {e}", file.display()))?;
        let parsed = syn::parse_file(&source)
            .map_err(|e| format!("failed to parse {}: {e}", file.display()))?;
        collect_items(&parsed.items, &mod_prefix, &file, &mut out)?;
    }
    out.sort_by(|a, b| a.id.cmp(&b.id));
    Ok(out)
}

/// Match a preview identifier against an exact id or `*` wildcard glob.
pub fn matches_glob(pattern: &str, text: &str) -> bool {
    let p: Vec<char> = pattern.chars().collect();
    let t: Vec<char> = text.chars().collect();
    let (mut pi, mut ti) = (0usize, 0usize);
    let (mut star_p, mut star_t) = (None, 0usize);
    while ti < t.len() {
        if pi < p.len() && (p[pi] == '?' || p[pi] == t[ti]) {
            pi += 1;
            ti += 1;
        } else if pi < p.len() && p[pi] == '*' {
            star_p = Some(pi);
            pi += 1;
            star_t = ti;
        } else if let Some(sp) = star_p {
            pi = sp + 1;
            star_t += 1;
            ti = star_t;
        } else {
            return false;
        }
    }
    while pi < p.len() && p[pi] == '*' {
        pi += 1;
    }
    pi == p.len()
}

fn module_prefix_for_file(crate_ident: &str, rel: &Path) -> Vec<String> {
    let mut segs = vec![crate_ident.to_string()];
    let parts: Vec<String> = rel
        .components()
        .filter_map(|c| c.as_os_str().to_str().map(String::from))
        .collect();
    for (idx, part) in parts.iter().enumerate() {
        let is_last = idx + 1 == parts.len();
        if is_last {
            let stem = part.strip_suffix(".rs").unwrap_or(part);
            if !matches!(stem, "lib" | "main" | "mod") {
                segs.push(stem.to_string());
            }
        } else {
            segs.push(part.clone());
        }
    }
    segs
}

fn collect_items(
    items: &[Item],
    mod_path: &[String],
    file: &Path,
    out: &mut Vec<DiscoveredPreview>,
) -> Result<(), String> {
    for item in items {
        match item {
            Item::Fn(func) => {
                if let Some(attr) = find_preview_attr(&func.attrs) {
                    out.push(parse_preview_fn(func, attr, mod_path, file)?);
                }
            }
            Item::Mod(m) => {
                if let Some((_, nested)) = &m.content {
                    let mut child_path = mod_path.to_vec();
                    child_path.push(m.ident.to_string());
                    collect_items(nested, &child_path, file, out)?;
                }
            }
            _ => {}
        }
    }
    Ok(())
}

fn find_preview_attr(attrs: &[Attribute]) -> Option<&Attribute> {
    attrs.iter().find(|a| {
        a.path()
            .segments
            .last()
            .is_some_and(|s| s.ident == "Preview")
    })
}

fn parse_preview_fn(
    func: &ItemFn,
    attr: &Attribute,
    mod_path: &[String],
    file: &Path,
) -> Result<DiscoveredPreview, String> {
    let fn_name = func.sig.ident.to_string();
    let id = format!("{}::{}", mod_path.join("::"), fn_name);
    let mut name = fn_name;
    let mut sizes = Vec::new();
    let mut schemes = Vec::new();
    let mut systems = Vec::new();
    let mut scales = Vec::new();
    let mut fixture = None;

    if let Meta::List(list) = &attr.meta {
        let parser = Punctuated::<syn::MetaNameValue, Token![,]>::parse_terminated;
        let pairs = parser
            .parse2(list.tokens.clone())
            .map_err(|e| format!("invalid #[Preview] args on {id}: {e}"))?;
        for nv in pairs {
            let key = nv
                .path
                .get_ident()
                .map(|i| i.to_string())
                .unwrap_or_default();
            match key.as_str() {
                "name" => {
                    if let Expr::Lit(l) = &nv.value
                        && let Lit::Str(s) = &l.lit
                    {
                        name = s.value();
                    }
                }
                "fixture" => {
                    if let Expr::Lit(l) = &nv.value
                        && let Lit::Str(s) = &l.lit
                    {
                        fixture = Some(s.value());
                    }
                }
                "sizes" => {
                    if let Expr::Array(arr) = &nv.value {
                        sizes = parse_sizes_array(arr)?;
                    }
                }
                "schemes" => {
                    if let Expr::Array(arr) = &nv.value {
                        schemes = parse_schemes_array(arr)?;
                    }
                }
                "systems" => {
                    if let Expr::Array(arr) = &nv.value {
                        systems = parse_systems_array(arr)?;
                    }
                }
                "scales" => {
                    if let Expr::Array(arr) = &nv.value {
                        scales = parse_scales_array(arr)?;
                    }
                }
                _ => {}
            }
        }
    }

    Ok(DiscoveredPreview {
        id,
        name,
        source_file: file.to_path_buf(),
        sizes,
        schemes,
        systems,
        scales,
        fixture,
    })
}

fn parse_sizes_array(arr: &ExprArray) -> Result<Vec<PreviewSize>, String> {
    let mut out = Vec::new();
    for expr in &arr.elems {
        match expr {
            Expr::Path(p) => {
                let s = p
                    .path
                    .get_ident()
                    .map(|i| i.to_string())
                    .ok_or_else(|| "invalid size ident".to_string())?;
                out.push(PreviewSize::parse(&s)?);
            }
            Expr::Lit(l) => {
                if let Lit::Str(s) = &l.lit {
                    out.push(PreviewSize::parse(&s.value())?);
                }
            }
            _ => return Err("unsupported size expression in #[Preview]".to_string()),
        }
    }
    Ok(out)
}

fn parse_schemes_array(arr: &ExprArray) -> Result<Vec<ColorScheme>, String> {
    let mut out = Vec::new();
    for expr in &arr.elems {
        if let Expr::Path(p) = expr
            && let Some(ident) = p.path.get_ident()
        {
            match ident.to_string().as_str() {
                "Light" => out.push(ColorScheme::Light),
                "Dark" => out.push(ColorScheme::Dark),
                other => return Err(format!("unknown scheme `{other}`")),
            }
        }
    }
    Ok(out)
}

fn parse_systems_array(arr: &ExprArray) -> Result<Vec<DesignSystem>, String> {
    let mut out = Vec::new();
    for expr in &arr.elems {
        if let Expr::Path(p) = expr
            && let Some(ident) = p.path.get_ident()
        {
            out.push(parse_system_name(&ident.to_string())?);
        }
    }
    Ok(out)
}

fn parse_scales_array(arr: &ExprArray) -> Result<Vec<f32>, String> {
    let mut out = Vec::new();
    for expr in &arr.elems {
        if let Expr::Lit(l) = expr {
            match &l.lit {
                Lit::Float(f) => out.push(f.base10_parse::<f32>().map_err(|e| e.to_string())?),
                Lit::Int(i) => out.push(i.base10_parse::<u32>().map_err(|e| e.to_string())? as f32),
                _ => return Err("invalid scale literal".to_string()),
            }
        }
    }
    Ok(out)
}

/// Parse a design system name (case-insensitive).
pub fn parse_system_name(input: &str) -> Result<DesignSystem, String> {
    match input.trim().to_ascii_lowercase().as_str() {
        "material3" | "material" | "m3" => Ok(DesignSystem::Material3),
        "cupertino" | "apple" | "hig" => Ok(DesignSystem::Cupertino),
        "fluent" | "windows" => Ok(DesignSystem::Fluent),
        "gnome" | "adwaita" => Ok(DesignSystem::Gnome),
        "breeze" | "kde" => Ok(DesignSystem::Breeze),
        "deepin" | "dde" => Ok(DesignSystem::Deepin),
        "liquidglass" | "liquid_glass" | "liquid-glass" => Ok(DesignSystem::LiquidGlass),
        other => Err(format!("unknown design system `{other}`")),
    }
}

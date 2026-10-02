//! `dioxus-compose-msix`: package a desktop build as MSIX.
//!
//! ```text
//! dioxus-compose-msix --project <dir> --exe <file> --out <dir> [options]
//! ```
//!
//! Run with `--help` for the options.

use std::io::Write;
use std::path::PathBuf;
use std::process::ExitCode;

use dioxus_compose_msix::{
    AppMetadata, Arch, Channel, Error, PackageVersion, Payload, Plan, pack, stage,
};

const HELP: &str = "\
dioxus-compose-msix: package a dioxus-compose desktop build as MSIX

USAGE:
    dioxus-compose-msix --project <dir> --exe <path> --out <dir> [options]

REQUIRED:
    --project <dir>      The application's directory, holding Dioxus.toml and Cargo.toml
    --exe <path>         The executable. With --payload-dir, a path inside that directory
    --out <dir>          Where the layout, .msix, .msixbundle and feed are written

OPTIONS:
    --payload-dir <dir>  Package this whole directory (renderer beside the program)
    --channel <name>     store (default) or sideload
    --revision <n>       Fourth version part; sideload only (a build number that grows)
    --version <semver>   Use this instead of the version in Cargo.toml
    --arch <arch>        x64 (default) or arm64
    --icon <png>         Use this icon instead of the one Dioxus.toml names
    --feed-base <url>    Write an App Installer feed for a bundle served from this URL
    --test-identity      Allow the Store channel with a derived identity (for certification runs)
    --layout-only        Stage the package directory and stop; runs on any host
    --summary <file>     Append key=value lines describing the outputs (for CI)
    -h, --help           Show this text
";

struct Args {
    project: PathBuf,
    exe: PathBuf,
    out: PathBuf,
    payload_dir: Option<PathBuf>,
    channel: Channel,
    revision: Option<u32>,
    version: Option<String>,
    arch: Arch,
    icon: Option<PathBuf>,
    feed_base: Option<String>,
    test_identity: bool,
    layout_only: bool,
    summary: Option<PathBuf>,
}

fn parse_args() -> Result<Option<Args>, Error> {
    let mut project = None;
    let mut exe = None;
    let mut out = None;
    let mut payload_dir = None;
    let mut channel = Channel::Store;
    let mut revision = None;
    let mut version = None;
    let mut arch = Arch::X64;
    let mut icon = None;
    let mut feed_base = None;
    let mut test_identity = false;
    let mut layout_only = false;
    let mut summary = None;

    let mut it = std::env::args().skip(1);
    while let Some(flag) = it.next() {
        let mut value = || {
            it.next()
                .ok_or_else(|| Error::new(format!("{flag} needs a value")))
        };
        match flag.as_str() {
            "-h" | "--help" => return Ok(None),
            "--project" => project = Some(PathBuf::from(value()?)),
            "--exe" => exe = Some(PathBuf::from(value()?)),
            "--out" => out = Some(PathBuf::from(value()?)),
            "--payload-dir" => payload_dir = Some(PathBuf::from(value()?)),
            "--channel" => channel = Channel::parse(&value()?)?,
            "--revision" => {
                let v = value()?;
                revision =
                    Some(v.parse().map_err(|_| {
                        Error::new(format!("--revision `{v}` is not a whole number"))
                    })?);
            }
            "--version" => version = Some(value()?),
            "--arch" => {
                let v = value()?;
                arch = Arch::parse(&v)
                    .ok_or_else(|| Error::new(format!("--arch `{v}`: use x64 or arm64")))?;
            }
            "--icon" => icon = Some(PathBuf::from(value()?)),
            "--feed-base" => feed_base = Some(value()?),
            "--test-identity" => test_identity = true,
            "--layout-only" => layout_only = true,
            "--summary" => summary = Some(PathBuf::from(value()?)),
            other => return Err(Error::new(format!("unknown option `{other}`; see --help"))),
        }
    }
    let need = |v: Option<PathBuf>, name: &str| {
        v.ok_or_else(|| Error::new(format!("{name} is required; see --help")))
    };
    Ok(Some(Args {
        project: need(project, "--project")?,
        exe: need(exe, "--exe")?,
        out: need(out, "--out")?,
        payload_dir,
        channel,
        revision,
        version,
        arch,
        icon,
        feed_base,
        test_identity,
        layout_only,
        summary,
    }))
}

fn run() -> Result<(), Error> {
    let Some(args) = parse_args()? else {
        print!("{HELP}");
        return Ok(());
    };
    let meta = AppMetadata::load(&args.project)?;
    if args.channel == Channel::Store
        && !meta.store_identity
        && !args.test_identity
        && !args.layout_only
    {
        return Err(Error::new(
            "the Store channel needs the identity Partner Center assigned: set identity_name and publisher \
             in [windows.msix] of Dioxus.toml, or pass --test-identity for a certification run",
        ));
    }
    let semver = args.version.clone().unwrap_or_else(|| meta.version.clone());
    let version = PackageVersion::from_semver(&semver, args.channel, args.revision)?;
    let payload = match &args.payload_dir {
        Some(root) => Payload::Directory {
            root: root.clone(),
            executable: args.exe.clone(),
        },
        None => Payload::Executable(args.exe.clone()),
    };
    let plan = Plan {
        meta,
        version,
        channel: args.channel,
        arch: args.arch,
        payload,
        icon: args.icon.clone(),
    };

    let layout = args.out.join("layout");
    for warning in stage(&plan, &layout)? {
        eprintln!("warning: {warning}");
    }
    println!(
        "staged {} {} at {}",
        plan.meta.identity_name,
        plan.version,
        layout.display()
    );

    let mut lines = vec![
        format!("identity={}", plan.meta.identity_name),
        format!("publisher={}", plan.meta.publisher),
        format!("version={}", plan.version),
        format!("layout={}", layout.display()),
    ];
    if !args.layout_only {
        let outputs = pack(&plan, &layout, &args.out, args.feed_base.as_deref())?;
        println!("package {}", outputs.package.display());
        println!("bundle  {}", outputs.bundle.display());
        lines.push(format!("package={}", outputs.package.display()));
        lines.push(format!("bundle={}", outputs.bundle.display()));
        if let Some(feed) = &outputs.feed {
            println!("feed    {}", feed.display());
            lines.push(format!("feed={}", feed.display()));
        }
    }
    if let Some(path) = &args.summary {
        let mut file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(path)
            .map_err(|e| Error::new(format!("cannot open {}: {e}", path.display())))?;
        for line in lines {
            writeln!(file, "{line}")
                .map_err(|e| Error::new(format!("cannot write {}: {e}", path.display())))?;
        }
    }
    Ok(())
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}

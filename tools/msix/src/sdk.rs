//! Finding and running the Windows SDK's packaging tools.

use std::ffi::OsStr;
use std::path::{Path, PathBuf};
use std::process::Command;

use crate::Error;

/// Parses `10.0.22621.0` into something that orders numerically.
fn sdk_version(name: &str) -> Option<Vec<u32>> {
    let parts: Option<Vec<u32>> = name.split('.').map(|p| p.parse().ok()).collect();
    parts.filter(|p| p.len() == 4)
}

/// The newest `<kits>\bin\<version>\<host>\<tool>` under `kits`.
pub fn newest_in_kits(kits: &Path, host: &str, tool: &str) -> Option<PathBuf> {
    let mut found: Vec<(Vec<u32>, PathBuf)> = std::fs::read_dir(kits.join("bin"))
        .ok()?
        .filter_map(|e| e.ok())
        .filter_map(|e| {
            let version = sdk_version(&e.file_name().to_string_lossy())?;
            let candidate = e.path().join(host).join(tool);
            candidate.is_file().then_some((version, candidate))
        })
        .collect();
    found.sort();
    found.pop().map(|(_, p)| p)
}

/// Finds an SDK tool: `$DXC_WINDOWS_SDK_BIN\<tool>` if set, else the newest installed
/// Windows 10/11 SDK, else whatever is on `PATH`.
pub fn find_tool(tool: &str) -> Result<PathBuf, Error> {
    if let Some(dir) = std::env::var_os("DXC_WINDOWS_SDK_BIN") {
        let candidate = Path::new(&dir).join(tool);
        if candidate.is_file() {
            return Ok(candidate);
        }
        return Err(Error::new(format!(
            "DXC_WINDOWS_SDK_BIN is set, and {} is not there",
            candidate.display()
        )));
    }
    let host = if cfg!(target_arch = "aarch64") {
        "arm64"
    } else {
        "x64"
    };
    for root in ["ProgramFiles(x86)", "ProgramFiles"] {
        if let Some(pf) = std::env::var_os(root) {
            let kits = Path::new(&pf).join("Windows Kits").join("10");
            if let Some(found) = newest_in_kits(&kits, host, tool) {
                return Ok(found);
            }
        }
    }
    if let Some(path) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&path) {
            let candidate = dir.join(tool);
            if candidate.is_file() {
                return Ok(candidate);
            }
        }
    }
    Err(Error::new(format!(
        "{tool} was not found: install the Windows SDK, or set DXC_WINDOWS_SDK_BIN to the directory that holds it. \
         Packing needs Windows; `--layout-only` stages the package directory on any host"
    )))
}

pub fn run(tool: &Path, args: &[&OsStr]) -> Result<(), Error> {
    let status = Command::new(tool)
        .args(args)
        .status()
        .map_err(|e| Error::new(format!("cannot start {}: {e}", tool.display())))?;
    if status.success() {
        Ok(())
    } else {
        Err(Error::new(format!(
            "{} {} failed with {status}",
            tool.display(),
            args.iter()
                .map(|a| a.to_string_lossy())
                .collect::<Vec<_>>()
                .join(" ")
        )))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fr34_sdk_versions_order_numerically() {
        let mut v = vec![
            sdk_version("10.0.9999.0").unwrap(),
            sdk_version("10.0.22621.0").unwrap(),
            sdk_version("10.0.19041.0").unwrap(),
        ];
        v.sort();
        assert_eq!(v.last().unwrap(), &vec![10, 0, 22621, 0]);
        assert!(sdk_version("wdf").is_none());
        assert!(sdk_version("10.0.1").is_none());
    }
}

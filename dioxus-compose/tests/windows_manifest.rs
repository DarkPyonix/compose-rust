//! The manifest every Windows executable built on this crate carries, and the resource
//! file the build script writes it into.
//!
//! The build script is never compiled for test, so the module it includes is included
//! here as well, and the bytes it would hand the linker are checked field by field.

mod windows_manifest {
    include!("../build/windows_manifest.rs");
}

use windows_manifest::{APPLICATION_MANIFEST, manifest_resource};

fn u16_at(bytes: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([bytes[at], bytes[at + 1]])
}

fn u32_at(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
}

#[test]
fn fr35_the_manifest_declares_per_monitor_v2_awareness() {
    // Both elements: `dpiAwareness` is what Windows 10 1607 and later read, `dpiAware`
    // with `/pm` is the fallback for anything older, and the certification kit accepts
    // either.
    assert!(APPLICATION_MANIFEST.contains(
        "<dpiAwareness xmlns=\"http://schemas.microsoft.com/SMI/2016/WindowsSettings\">PerMonitorV2</dpiAwareness>"
    ));
    assert!(APPLICATION_MANIFEST.contains(
        "<dpiAware xmlns=\"http://schemas.microsoft.com/SMI/2005/WindowsSettings\">true/pm</dpiAware>"
    ));
    assert!(APPLICATION_MANIFEST.contains("level=\"asInvoker\""));
    assert!(APPLICATION_MANIFEST.starts_with("<?xml"));
    assert!(APPLICATION_MANIFEST.trim_end().ends_with("</assembly>"));
}

#[test]
fn fr35_the_resource_file_starts_with_the_empty_entry() {
    let res = manifest_resource(b"<x/>");
    // DataSize 0, HeaderSize 32, type ordinal 0, name ordinal 0, and zeros after. The
    // linker recognises a resource file by exactly these 32 bytes.
    let mut expected = vec![0u8; 32];
    expected[4] = 0x20;
    expected[8..12].copy_from_slice(&[0xFF, 0xFF, 0, 0]);
    expected[12..16].copy_from_slice(&[0xFF, 0xFF, 0, 0]);
    assert_eq!(&res[..32], &expected[..]);
}

#[test]
fn fr35_the_resource_file_holds_the_manifest_as_resource_one() {
    let manifest = APPLICATION_MANIFEST.as_bytes();
    let res = manifest_resource(manifest);
    let e = 32;
    assert_eq!(u32_at(&res, e) as usize, manifest.len(), "DataSize");
    assert_eq!(u32_at(&res, e + 4), 32, "HeaderSize");
    assert_eq!(u16_at(&res, e + 8), 0xFFFF);
    assert_eq!(u16_at(&res, e + 10), 24, "RT_MANIFEST");
    assert_eq!(u16_at(&res, e + 12), 0xFFFF);
    assert_eq!(u16_at(&res, e + 14), 1, "the executable's manifest id");
    assert_eq!(u16_at(&res, e + 22), 0x0409, "language");
    assert_eq!(&res[e + 32..e + 32 + manifest.len()], manifest);
    assert_eq!(res.len() % 4, 0, "entries are padded to four bytes");
    assert!(res.len() - (e + 32 + manifest.len()) < 4);
}

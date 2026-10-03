//! The C runtime a Windows application links with the Kotlin/Native renderer, and the
//! rewriting of prebuilt libraries that lets the build choose it.
//!
//! `build/windows_crt.rs` is included from `build.rs`, which is never compiled for test, so
//! this is where it is exercised. Nothing here needs Windows: the libraries are byte strings
//! holding the directives the MSVC compiler writes.

#![allow(dead_code)]

mod windows_crt {
    include!("../build/windows_crt.rs");
}

use windows_crt::{
    Blanked, LINKED_IN_DIRECTIVES, WindowsCrt, blank_runtime_directives, find_msvc_library,
    unreconcilable_message, windows_crt,
};

/// A `.drectve` section the way MSVC writes one for a /MT C++ object, surrounded by bytes
/// that are not directives.
fn static_runtime_object() -> Vec<u8> {
    let mut bytes = vec![0x4c, 0x01, 0x00, 0xff];
    bytes.extend_from_slice(
        b"   /FAILIFMISMATCH:\"_MSC_VER=1900\" /FAILIFMISMATCH:\"_ITERATOR_DEBUG_LEVEL=0\" \
          /FAILIFMISMATCH:\"RuntimeLibrary=MT_StaticRelease\" /DEFAULTLIB:\"libcpmt\" \
          /DEFAULTLIB:\"LIBCMT\" /DEFAULTLIB:\"OLDNAMES\" ",
    );
    bytes.extend_from_slice(&[0x00, 0x90, 0x2f, 0x2d]);
    bytes
}

#[test]
fn nfr15_blanking_removes_the_runtime_guard_and_the_static_defaults() {
    let mut bytes = static_runtime_object();
    let found = blank_runtime_directives(&mut bytes);
    let text = String::from_utf8_lossy(&bytes);
    assert_eq!(found.directives, 3, "{text}");
    assert!(found.unreconcilable.is_empty());
    assert!(!text.contains("RuntimeLibrary"), "{text}");
    assert!(!text.contains("libcpmt"), "{text}");
    assert!(!text.contains("LIBCMT"), "{text}");
}

#[test]
fn nfr15_blanking_keeps_what_has_nothing_to_do_with_the_runtime() {
    let mut bytes = static_runtime_object();
    blank_runtime_directives(&mut bytes);
    let text = String::from_utf8_lossy(&bytes);
    // The compiler version and the iterator layout still have to agree; only where the
    // runtime comes from is decided by the build.
    assert!(text.contains("/FAILIFMISMATCH:\"_MSC_VER=1900\""), "{text}");
    assert!(
        text.contains("/FAILIFMISMATCH:\"_ITERATOR_DEBUG_LEVEL=0\""),
        "{text}"
    );
    assert!(text.contains("/DEFAULTLIB:\"OLDNAMES\""), "{text}");
}

#[test]
fn nfr15_blanking_moves_no_offset() {
    let original = static_runtime_object();
    let mut bytes = original.clone();
    blank_runtime_directives(&mut bytes);
    assert_eq!(bytes.len(), original.len());
    for (index, (before, after)) in original.iter().zip(&bytes).enumerate() {
        assert!(
            before == after || *after == b' ',
            "byte {index} changed to something other than a space"
        );
    }
    // The bytes around the section are not touched, including a slash and a dash that
    // start no directive.
    assert_eq!(&bytes[..4], &original[..4]);
    assert_eq!(&bytes[bytes.len() - 4..], &original[original.len() - 4..]);
}

#[test]
fn nfr15_blanking_reads_the_spellings_clang_writes_too() {
    // clang-cl writes the default library bare, and a GNU-style driver with a dash.
    let mut bytes =
        b" /DEFAULTLIB:libcmt.lib -defaultlib:libcpmt.lib /failifmismatch:RuntimeLibrary=MD_DynamicRelease "
            .to_vec();
    let found = blank_runtime_directives(&mut bytes);
    assert_eq!(found.directives, 3);
    assert!(bytes.iter().all(|byte| *byte == b' '));
}

#[test]
fn nfr15_a_debug_runtime_is_named_and_left_for_the_linker() {
    let mut bytes = b" /FAILIFMISMATCH:\"RuntimeLibrary=MDd_DynamicDebug\" ".to_vec();
    let original = bytes.clone();
    let found = blank_runtime_directives(&mut bytes);
    assert_eq!(
        found,
        Blanked {
            directives: 0,
            unreconcilable: vec!["MDd_DynamicDebug".to_string()]
        }
    );
    assert_eq!(bytes, original, "a debug guard is not blanked");

    let message = unreconcilable_message(
        std::path::Path::new("C:/kit/skiko/mixed.lib"),
        &found.unreconcilable,
    );
    // Which library, what it was built with, what the linker would have said, and what to do.
    assert!(message.contains("C:/kit/skiko/mixed.lib"), "{message}");
    assert!(message.contains("MDd_DynamicDebug"), "{message}");
    assert!(message.contains("LNK2038"), "{message}");
    assert!(message.contains("RuntimeLibrary"), "{message}");
    assert!(message.contains("/MT or /MD"), "{message}");
}

#[test]
fn nfr15_an_application_with_no_settings_links_the_runtime_in() {
    assert_eq!(windows_crt("sse,sse2", None), Ok(WindowsCrt::LinkedIn));
    assert_eq!(windows_crt("", Some("static")), Ok(WindowsCrt::LinkedIn));
    assert_eq!(
        windows_crt("sse2,crt-static", None),
        Ok(WindowsCrt::FullyStatic)
    );
    assert_eq!(
        windows_crt("sse2", Some("dynamic")),
        Ok(WindowsCrt::Dynamic)
    );
    let refused = windows_crt("", Some("shared")).unwrap_err();
    assert!(
        refused.contains("'static'") && refused.contains("'dynamic'"),
        "{refused}"
    );
}

#[test]
fn nfr15_the_host_object_carries_the_linked_in_directives() {
    // The directives reach an application only from inside an object it links, which is the
    // Host's boundary module. A directive listed here and missing there is a runtime chosen
    // by whichever library the linker happens to read first.
    let boundary = include_str!("../src/boundary.rs");
    for directive in LINKED_IN_DIRECTIVES {
        assert!(
            boundary.contains(&format!(".ascii \\\" {directive}\\\"")),
            "src/boundary.rs does not carry {directive}"
        );
    }
}

#[test]
fn nfr15_the_static_cxx_library_is_found_where_the_linker_looks() {
    let root = std::env::temp_dir().join(format!("compose-rust-crt-{}", std::process::id()));
    let lib = root.join("lib");
    let tools = root.join("tools");
    let xwin = root.join("xwin");
    std::fs::create_dir_all(&lib).unwrap();
    std::fs::create_dir_all(tools.join("lib/x64")).unwrap();
    std::fs::create_dir_all(xwin.join("crt/lib/x86_64")).unwrap();

    assert_eq!(find_msvc_library("libcpmt.lib", None, &[], &[]), None);

    std::fs::write(xwin.join("crt/lib/x86_64/libcpmt.lib"), b"!<arch>\n").unwrap();
    assert_eq!(
        find_msvc_library("libcpmt.lib", None, &[tools.clone()], &[xwin.clone()]),
        Some(xwin.join("crt/lib/x86_64/libcpmt.lib"))
    );

    std::fs::write(tools.join("lib/x64/libcpmt.lib"), b"!<arch>\n").unwrap();
    assert_eq!(
        find_msvc_library("libcpmt.lib", None, &[tools.clone()], &[xwin.clone()]),
        Some(tools.join("lib/x64/libcpmt.lib"))
    );

    std::fs::write(lib.join("libcpmt.lib"), b"!<arch>\n").unwrap();
    let lib_env = format!("C:\\nowhere;{};", lib.display());
    assert_eq!(
        find_msvc_library("libcpmt.lib", Some(&lib_env), &[tools], &[xwin]),
        Some(lib.join("libcpmt.lib"))
    );
    std::fs::remove_dir_all(&root).ok();
}

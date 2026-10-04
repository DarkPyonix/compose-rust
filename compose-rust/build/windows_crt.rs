// Which C runtime a Windows application links with the Kotlin/Native renderer, and the
// rewriting of prebuilt libraries that lets it choose.
//
// Free of Cargo directives, like renderer_dir.rs, so tests/windows_crt.rs can exercise it.
//
// The problem. JetBrains builds Skia for Windows against the static C runtime (/MT), and a
// Rust application links the DLL one (/MD) unless it asks otherwise with `+crt-static`, which
// an application is not to be asked for: adding compose-rust to Cargo.toml is meant to be the
// whole of it. Every C++ object the MSVC compiler writes carries two things that make the
// linker hold it to its choice: a guard, `/FAILIFMISMATCH:"RuntimeLibrary=MT_StaticRelease"`,
// which fails the link (LNK2038) beside an object that says anything else, and default library
// names (`/DEFAULTLIB:"LIBCMT"`, `/DEFAULTLIB:"libcpmt"`) that pull the static runtime in beside
// the DLL one, where the two define the same functions twice.
//
// The answer, which compose-multiplatform-extended's Windows single executable proved first:
// link copies of the prebuilt libraries with those directives blanked out, so the runtime is
// decided once, here, rather than by whichever object the linker reads first. The guard is
// there for code that hands runtime objects across a library boundary (a FILE*, memory one
// side allocates and the other frees), and Skia, skiko's C++ half and the renderer share none:
// they meet in C calls that pass numbers and pointers to their own objects.
//
// Blanked rather than removed: each directive becomes spaces of the same length, so no offset
// in the archive or the object moves.

/// How the application's C runtime is put together.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WindowsCrt {
    /// The default, and what an application gets with nothing in its build settings. The
    /// Universal C Runtime from Windows itself (it is part of Windows 10 and later, so it is
    /// not a DLL anybody ships), and vcruntime and the C++ standard library linked in, so the
    /// executable needs neither VCRUNTIME140.dll nor MSVCP140.dll.
    LinkedIn,
    /// An application that asked for `+crt-static` for reasons of its own: all of it linked
    /// in, the UCRT too.
    FullyStatic,
    /// vcruntime from its DLL, which the application then has to ship or have installed. The
    /// C++ standard library stays linked in: Skia's objects are built for the static runtime
    /// and name its data symbols that way, which MSVCP140.dll's import library does not
    /// define. Not a default and not recommended; it is here so the size it saves can be
    /// measured against what it costs (`DXC_WINDOWS_CRT=dynamic`).
    Dynamic,
}

/// The environment variable that chooses [`WindowsCrt::Dynamic`].
pub const WINDOWS_CRT_ENV: &str = "DXC_WINDOWS_CRT";

/// Decides the runtime from the target features Cargo passes and the one variable.
pub fn windows_crt(target_features: &str, requested: Option<&str>) -> Result<WindowsCrt, String> {
    if target_features
        .split(',')
        .any(|feature| feature == "crt-static")
    {
        return Ok(WindowsCrt::FullyStatic);
    }
    match requested.map(str::trim) {
        None | Some("") | Some("static") => Ok(WindowsCrt::LinkedIn),
        Some("dynamic") => Ok(WindowsCrt::Dynamic),
        Some(other) => Err(format!(
            "{WINDOWS_CRT_ENV} is '{other}'. It takes 'static' (the default: vcruntime and the C++ \
             library linked into the executable) or 'dynamic' (vcruntime from its DLL, which the \
             application then has to ship)."
        )),
    }
}

/// The linker directives the Host's own object carries for [`WindowsCrt::LinkedIn`].
///
/// Directives rather than link arguments, for the reason the export directives give in
/// src/boundary.rs: a build script's link arguments stop at this package, and a directive in
/// an object reaches every application that links the object. Kept here so the test can hold
/// boundary.rs to the same list.
pub const LINKED_IN_DIRECTIVES: [&str; 6] = [
    // vcruntime from the static library rather than the import library msvcrt.lib names.
    "/NODEFAULTLIB:vcruntime.lib",
    "/DEFAULTLIB:libvcruntime.lib",
    // The C++ standard library: neither the import library nor the stock static one. The
    // build script links its own copy with the guard blanked.
    "/NODEFAULTLIB:msvcprt.lib",
    "/NODEFAULTLIB:libcpmt.lib",
    // The static C runtime, which the UCRT DLL replaces. A default library some prebuilt
    // object still names would otherwise define the C library a second time.
    "/NODEFAULTLIB:libcmt.lib",
    // winpthread, in the renderer's GCC runtime, calls longjmp through an import pointer.
    // With vcruntime linked in there is no import library to define one; the linker makes
    // it from the static definition, but only once something has brought that definition
    // in. Skia's libpng happens to, which is not a thing to rest on.
    "/INCLUDE:longjmp",
];

/// What blanking found in one library.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Blanked {
    /// Directives turned to spaces.
    pub directives: usize,
    /// `RuntimeLibrary` values this cannot reconcile, which are the debug runtimes: their
    /// objects are compiled against a different layout of the C++ library, not merely a
    /// different place to find it, so blanking the guard would trade a link error for memory
    /// corruption.
    pub unreconcilable: Vec<String>,
}

const RUNTIME_LIBRARY: &str = "runtimelibrary";
const RECONCILABLE: [&str; 2] = ["mt_staticrelease", "md_dynamicrelease"];
const STATIC_DEFAULTS: [&str; 4] = ["libcmt", "libcmt.lib", "libcpmt", "libcpmt.lib"];

/// Blanks every runtime guard and every static-runtime default library in `bytes`.
///
/// Recognises both spellings compilers write, `/DIRECTIVE:` and `-directive:`, in any case,
/// with or without quotes around the value.
pub fn blank_runtime_directives(bytes: &mut [u8]) -> Blanked {
    let mut found = Blanked::default();
    let mut index = 0;
    while index < bytes.len() {
        let byte = bytes[index];
        if byte != b'/' && byte != b'-' {
            index += 1;
            continue;
        }
        if let Some(end) = match_directive(bytes, index, &mut found) {
            for at in index..end {
                bytes[at] = b' ';
            }
            found.directives += 1;
            index = end;
        } else {
            index += 1;
        }
    }
    found
}

/// The end of a directive at `start` that is to be blanked, if there is one.
fn match_directive(bytes: &[u8], start: usize, found: &mut Blanked) -> Option<usize> {
    let after = start + 1;
    if let Some(value_at) = keyword(bytes, after, "failifmismatch:") {
        let (value, end) = token(bytes, value_at)?;
        let (key, setting) = value.split_once('=')?;
        if !key.eq_ignore_ascii_case(RUNTIME_LIBRARY) {
            return None;
        }
        if RECONCILABLE.contains(&setting.to_ascii_lowercase().as_str()) {
            return Some(end);
        }
        if !found.unreconcilable.iter().any(|seen| seen == setting) {
            found.unreconcilable.push(setting.to_string());
        }
        return None;
    }
    if let Some(value_at) = keyword(bytes, after, "defaultlib:") {
        let (value, end) = token(bytes, value_at)?;
        if STATIC_DEFAULTS.contains(&value.to_ascii_lowercase().as_str()) {
            return Some(end);
        }
    }
    None
}

/// Where the value starts, if `word` (lower case) is at `at` in any case.
fn keyword(bytes: &[u8], at: usize, word: &str) -> Option<usize> {
    let end = at + word.len();
    if end > bytes.len() {
        return None;
    }
    bytes[at..end]
        .iter()
        .zip(word.bytes())
        .all(|(have, want)| have.to_ascii_lowercase() == want)
        .then_some(end)
}

/// A directive's value, quoted or bare, and where the directive ends.
fn token(bytes: &[u8], at: usize) -> Option<(String, usize)> {
    if at >= bytes.len() {
        return None;
    }
    let (from, quoted) = if bytes[at] == b'"' {
        (at + 1, true)
    } else {
        (at, false)
    };
    let mut end = from;
    while end < bytes.len() {
        let byte = bytes[end];
        let stop = if quoted {
            byte == b'"'
        } else {
            byte == b' ' || byte == 0 || byte == b'\t' || byte == b'\n' || byte == b'\r'
        };
        if stop {
            break;
        }
        // A directive is printable ASCII; anything else means this was not one.
        if !(0x20..0x7f).contains(&byte) {
            return None;
        }
        end += 1;
    }
    if end == from || (quoted && end >= bytes.len()) {
        return None;
    }
    let value = String::from_utf8(bytes[from..end].to_vec()).ok()?;
    Some((value, if quoted { end + 1 } else { end }))
}

/// What the build says about a library built against a debug runtime.
pub fn unreconcilable_message(library: &std::path::Path, settings: &[String]) -> String {
    format!(
        "compose-rust: {} was built against the debug C runtime ({}).\n\n\
         The Windows renderer links one C runtime for the whole executable, the release one, \
         and the debug runtime lays out the C++ library differently, so the two cannot be \
         put in one program: the linker refuses it with LNK2038, a RuntimeLibrary mismatch.\n\n\
         Rebuild that library with /MT or /MD (release, not /MTd or /MDd), or put the release \
         build of it where this one is.",
        library.display(),
        settings.join(", ")
    )
}

/// Where the MSVC installation keeps the static C++ library, `libcpmt.lib`.
///
/// Looked for on LIB first, which is what the linker itself searches and what a Visual Studio
/// developer prompt sets; then under the installation `vc_tools_dirs` names (found with
/// vswhere by the caller, or VCToolsInstallDir); then in the layout cargo-xwin writes, for an
/// application cross-compiled from macOS or Linux.
pub fn find_msvc_library(
    name: &str,
    lib_env: Option<&str>,
    vc_tools_dirs: &[std::path::PathBuf],
    xwin_dirs: &[std::path::PathBuf],
) -> Option<std::path::PathBuf> {
    let from_lib = lib_env
        .into_iter()
        .flat_map(|value| value.split(';'))
        .filter(|entry| !entry.trim().is_empty())
        .map(|entry| std::path::PathBuf::from(entry.trim()).join(name));
    let from_tools = vc_tools_dirs
        .iter()
        .map(|dir| dir.join("lib").join("x64").join(name));
    let from_xwin = xwin_dirs
        .iter()
        .map(|dir| dir.join("crt").join("lib").join("x86_64").join(name));
    from_lib
        .chain(from_tools)
        .chain(from_xwin)
        .find(|candidate| candidate.is_file())
}

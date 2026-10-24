// The application manifest every Windows program built on this crate carries, and the
// compiled resource file that carries it.
//
// Windows reads a program's DPI awareness from the manifest embedded in the executable,
// before any of its code runs. The renderer also asks for per-monitor v2 awareness at
// startup, and that works for drawing, but it is a request made at run time and so
// invisible to anything that inspects the file: the Windows App Certification Kit
// reports such a program as not DPI aware, which is what a Store submission is checked
// against. Declaring it in the manifest says the same thing earlier. Once a process has
// its awareness from the manifest, the renderer's own calls fail harmlessly (awareness
// cannot be changed after it is set), so the two agree rather than compete.
//
// The resource is written here rather than compiled with rc.exe. A `.res` file is a
// short fixed format, and writing it needs no Windows SDK on the build machine, so it
// works the same for a cross build through lld-link.
//
// Included from build.rs and from tests/windows_manifest.rs, which is how it is tested.

/// The manifest: per-monitor v2 DPI awareness, the same awareness the renderer asks for,
/// and `asInvoker`, so Windows never guesses from a file name that the program is an
/// installer wanting elevation.
pub const APPLICATION_MANIFEST: &str = include_str!("application.manifest");

/// `RT_MANIFEST`.
const RT_MANIFEST: u16 = 24;
/// `CREATEPROCESS_MANIFEST_RESOURCE_ID`, the manifest the loader reads for an executable.
const MANIFEST_ID: u16 = 1;
/// MOVEABLE | PURE | DISCARDABLE, what rc.exe writes for a manifest.
const MEMORY_FLAGS: u16 = 0x1030;
/// English (United States), what rc.exe writes when the script names no language.
const LANGUAGE: u16 = 0x0409;

fn entry(out: &mut Vec<u8>, kind: u16, name: u16, flags: u16, language: u16, data: &[u8]) {
    let header_size: u32 = 32;
    out.extend_from_slice(&(data.len() as u32).to_le_bytes());
    out.extend_from_slice(&header_size.to_le_bytes());
    // Type and name as ordinals: 0xFFFF followed by the number.
    out.extend_from_slice(&0xFFFFu16.to_le_bytes());
    out.extend_from_slice(&kind.to_le_bytes());
    out.extend_from_slice(&0xFFFFu16.to_le_bytes());
    out.extend_from_slice(&name.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes()); // DataVersion
    out.extend_from_slice(&flags.to_le_bytes());
    out.extend_from_slice(&language.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes()); // Version
    out.extend_from_slice(&0u32.to_le_bytes()); // Characteristics
    out.extend_from_slice(data);
    while out.len() % 4 != 0 {
        out.push(0);
    }
}

/// A `.res` file holding `manifest` as the executable's manifest resource.
///
/// It opens with the empty entry every `.res` file starts with, which is also how the
/// MSVC linker recognises the file as a resource file whatever it is named.
pub fn manifest_resource(manifest: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(64 + manifest.len() + 3);
    entry(&mut out, 0, 0, 0, 0, &[]);
    entry(&mut out, RT_MANIFEST, MANIFEST_ID, MEMORY_FLAGS, LANGUAGE, manifest);
    out
}

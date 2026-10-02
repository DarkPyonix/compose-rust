// Reading and removing an ELF shared object's SONAME.
//
// On Linux a library's SONAME is what dependents record as their `DT_NEEDED` entry. A
// shared object that has one is looked up by that bare name at run time, against the
// loader's search path and the runpath of whatever loaded it, which an application that
// merely depends on this crate does not have. A shared object with no SONAME is recorded
// by the path the linker opened it at, which is absolute here, and the loader finds it
// with no search at all. That is the same lookup an absolute install name buys on macOS.
//
// The renderer is built without a SONAME, so nothing below normally fires. It is here so
// that an artifact which does carry one is fixed rather than turned into an application
// that links and then cannot start. Removing a `DT_SONAME` entry means dropping one
// sixteen byte slot out of the `.dynamic` array and moving the rest up, which is a local
// edit that needs no string table surgery and therefore no patchelf: requiring a tool
// that most distributions do not install by default would put a manual step back into
// `cargo build`.
//
// It is `include!`d from `renderer_dir.rs`, so the build script and the tests read the
// same code.

use std::fs::OpenOptions;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;

const MAGIC: [u8; 4] = [0x7f, b'E', b'L', b'F'];
const CLASS_64: u8 = 2;
const DATA_LITTLE_ENDIAN: u8 = 1;

const PT_LOAD: u32 = 1;
const PT_DYNAMIC: u32 = 2;

const DT_NULL: i64 = 0;
const DT_STRTAB: i64 = 5;
const DT_SONAME: i64 = 14;

/// A 64-bit little-endian program header, which is all the renderer is ever built as.
#[derive(Clone, Copy)]
struct Segment {
    kind: u32,
    offset: u64,
    address: u64,
    file_size: u64,
}

/// One `.dynamic` entry.
#[derive(Clone, Copy)]
struct Dynamic {
    tag: i64,
    value: u64,
}

/// What the `.dynamic` array is and where it lives in the file, read once so that reading
/// the SONAME and removing it do not walk the headers twice.
struct DynamicSection {
    /// Where the array starts in the file.
    offset: u64,
    /// Every entry up to and including the first `DT_NULL`.
    entries: Vec<Dynamic>,
    /// The `PT_LOAD` segments, which are what turns a virtual address into a file offset.
    loads: Vec<Segment>,
}

/// The SONAME this shared object carries, or `None` if it carries none.
///
/// Anything that is not a 64-bit little-endian ELF file is an error rather than `None`:
/// "there is no SONAME" and "this was not read" have to be told apart, because the first
/// means the library is ready to link and the second means nobody knows.
pub fn soname(library: &Path) -> Result<Option<String>, String> {
    let mut file = open(library, false)?;
    let Some(section) = dynamic_section(&mut file, library)? else {
        return Ok(None);
    };

    let Some(name_offset) = section
        .entries
        .iter()
        .find(|entry| entry.tag == DT_SONAME)
        .map(|entry| entry.value)
    else {
        return Ok(None);
    };
    let Some(string_table) = section
        .entries
        .iter()
        .find(|entry| entry.tag == DT_STRTAB)
        .map(|entry| entry.value)
    else {
        return Err(format!(
            "{} records a SONAME but no string table to read it out of",
            library.display()
        ));
    };

    let address = string_table.saturating_add(name_offset);
    let Some(at) = file_offset(&section.loads, address) else {
        return Err(format!(
            "{} places its SONAME at an address no loadable segment covers",
            library.display()
        ));
    };
    Ok(Some(read_c_string(&mut file, at, library)?))
}

/// Remove the SONAME, and report whether there was one to remove.
///
/// The entry is lifted out of the array and the ones after it move up one slot, so the
/// array keeps its length and its terminator. Nothing outside those sixteen bytes per
/// moved entry changes: no section moves, no string is rewritten, and the string the
/// SONAME pointed at simply stops being referenced.
pub fn remove_soname(library: &Path) -> Result<bool, String> {
    let mut file = open(library, true)?;
    let Some(section) = dynamic_section(&mut file, library)? else {
        return Ok(false);
    };
    let Some(index) = section.entries.iter().position(|entry| entry.tag == DT_SONAME) else {
        return Ok(false);
    };

    let mut entries = section.entries;
    entries.remove(index);
    entries.push(Dynamic {
        tag: DT_NULL,
        value: 0,
    });

    let mut bytes = Vec::with_capacity(entries.len() * 16);
    for entry in &entries {
        bytes.extend_from_slice(&entry.tag.to_le_bytes());
        bytes.extend_from_slice(&entry.value.to_le_bytes());
    }
    file.seek(SeekFrom::Start(section.offset))
        .and_then(|_| file.write_all(&bytes))
        .and_then(|()| file.flush())
        .map_err(|error| {
            format!(
                "could not rewrite the dynamic section of {}: {error}",
                library.display()
            )
        })?;
    Ok(true)
}

fn open(library: &Path, writable: bool) -> Result<std::fs::File, String> {
    OpenOptions::new()
        .read(true)
        .write(writable)
        .open(library)
        .map_err(|error| format!("could not open {}: {error}", library.display()))
}

/// Walk the program headers and read the whole `.dynamic` array. `None` means there is no
/// `PT_DYNAMIC` segment, which a statically linked object or a plain relocatable file
/// would not have and which is not an error to see.
fn dynamic_section(
    file: &mut std::fs::File,
    library: &Path,
) -> Result<Option<DynamicSection>, String> {
    let mut header = [0u8; 64];
    read_exact_at(file, 0, &mut header, library)?;
    if header[..4] != MAGIC {
        return Err(format!("{} is not an ELF file", library.display()));
    }
    if header[4] != CLASS_64 || header[5] != DATA_LITTLE_ENDIAN {
        return Err(format!(
            "{} is not a 64-bit little-endian ELF file, which is the only shape the \
             renderer is published in",
            library.display()
        ));
    }

    let table_offset = u64::from_le_bytes(header[32..40].try_into().expect("eight bytes"));
    let entry_size = u16::from_le_bytes(header[54..56].try_into().expect("two bytes")) as u64;
    let count = u16::from_le_bytes(header[56..58].try_into().expect("two bytes")) as u64;
    if entry_size < 56 {
        return Err(format!(
            "{} declares {entry_size} byte program headers, which cannot hold one",
            library.display()
        ));
    }

    let mut loads = Vec::new();
    let mut dynamic = None;
    for index in 0..count {
        let mut entry = [0u8; 56];
        read_exact_at(file, table_offset + index * entry_size, &mut entry, library)?;
        let segment = Segment {
            kind: u32::from_le_bytes(entry[0..4].try_into().expect("four bytes")),
            offset: u64::from_le_bytes(entry[8..16].try_into().expect("eight bytes")),
            address: u64::from_le_bytes(entry[16..24].try_into().expect("eight bytes")),
            file_size: u64::from_le_bytes(entry[32..40].try_into().expect("eight bytes")),
        };
        match segment.kind {
            PT_LOAD => loads.push(segment),
            PT_DYNAMIC => dynamic = Some(segment),
            _ => {}
        }
    }

    let Some(dynamic) = dynamic else {
        return Ok(None);
    };

    let mut entries = Vec::new();
    for index in 0..dynamic.file_size / 16 {
        let mut entry = [0u8; 16];
        read_exact_at(file, dynamic.offset + index * 16, &mut entry, library)?;
        let entry = Dynamic {
            tag: i64::from_le_bytes(entry[0..8].try_into().expect("eight bytes")),
            value: u64::from_le_bytes(entry[8..16].try_into().expect("eight bytes")),
        };
        let terminator = entry.tag == DT_NULL;
        entries.push(entry);
        if terminator {
            break;
        }
    }

    Ok(Some(DynamicSection {
        offset: dynamic.offset,
        entries,
        loads,
    }))
}

/// Where a virtual address lands in the file, according to the loadable segments.
fn file_offset(loads: &[Segment], address: u64) -> Option<u64> {
    loads.iter().find_map(|segment| {
        let end = segment.address.checked_add(segment.file_size)?;
        (segment.address <= address && address < end)
            .then(|| segment.offset + (address - segment.address))
    })
}

fn read_c_string(file: &mut std::fs::File, at: u64, library: &Path) -> Result<String, String> {
    // A SONAME is a file name, so a kilobyte is a generous bound and a missing terminator
    // stops here rather than reading the rest of a sixty megabyte library into memory.
    let mut window = [0u8; 1024];
    file.seek(SeekFrom::Start(at))
        .map_err(|error| format!("could not read {}: {error}", library.display()))?;
    let read = file
        .read(&mut window)
        .map_err(|error| format!("could not read {}: {error}", library.display()))?;
    let end = window[..read]
        .iter()
        .position(|byte| *byte == 0)
        .ok_or_else(|| {
            format!(
                "the SONAME in {} is not terminated within a kilobyte",
                library.display()
            )
        })?;
    String::from_utf8(window[..end].to_vec())
        .map_err(|_| format!("the SONAME in {} is not UTF-8", library.display()))
}

fn read_exact_at(
    file: &mut std::fs::File,
    at: u64,
    buffer: &mut [u8],
    library: &Path,
) -> Result<(), String> {
    file.seek(SeekFrom::Start(at))
        .and_then(|_| file.read_exact(buffer))
        .map_err(|error| {
            format!(
                "could not read {} bytes at {at} of {}: {error}",
                buffer.len(),
                library.display()
            )
        })
}

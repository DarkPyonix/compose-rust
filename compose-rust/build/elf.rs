// Reading and setting an ELF shared object's SONAME.
//
// On Linux a library's SONAME is what dependents record as their `DT_NEEDED` entry: every
// ELF linker (GNU ld, gold, lld, mold) copies it verbatim. When there is no SONAME, GNU ld
// records the name it found the library under instead, and for a library reached through
// `-l` and a search directory that is the bare file name, not the directory it was found
// in. ld's own source says so where it opens a dynamic library it found by searching
// (`ldelf_open_dynamic_archive` in ld/ldelf.c): a DT_SONAME is used if the file has one,
// and otherwise the entry is the file name stripped of the directory the search found it
// in. Cargo links the renderer exactly that way, `-lcompose_rust_renderer` plus `-L`, so
// a renderer with no SONAME is recorded as `libcompose_rust_renderer.so` and the loader
// then has to search for it on a path an application that merely depends on this crate
// does not have. CI saw exactly that: "error while loading shared libraries:
// libcompose_rust_renderer.so: cannot open shared object file".
//
// A SONAME that is the library's absolute path is recorded as that path, and the dynamic
// loader treats a `DT_NEEDED` entry containing a slash as a pathname and opens it directly,
// with no search at all (the ld.so(8) manual page describes that rule). That is the same
// lookup an absolute install name buys on macOS, which is what `set_soname` is for.
//
// Setting one needs the new string in the dynamic string table, and that table is packed
// between other sections with no room to grow where it is. So the table is copied, with
// the new name appended, into the padding at the end of a loadable segment: the linker
// starts each segment on a fresh page, and on the published renderer the read-only segment
// that holds the string table ends about two kilobytes short of the next one. The segment is extended over the copy, `DT_STRTAB`,
// `DT_STRSZ` and the string table's section header are pointed at it, and `DT_SONAME` is
// either updated or written into one of the spare `DT_NULL` slots GNU ld leaves at the end
// of the dynamic array. Every offset into the old table means the same thing in the new
// one, because the new one starts with a byte-for-byte copy of the old. Nothing else in
// the file moves.
//
// That is what patchelf would do, done here because requiring a tool that most
// distributions do not install by default would put a manual step back into `cargo build`.
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

const PF_X: u32 = 1;
const PF_W: u32 = 2;

const SHT_NULL: u32 = 0;
const SHT_STRTAB: u32 = 3;
const SHT_DYNAMIC: u32 = 6;
const SHT_NOBITS: u32 = 8;

const DT_NULL: i64 = 0;
const DT_STRTAB: i64 = 5;
const DT_STRSZ: i64 = 10;
const DT_SONAME: i64 = 14;

const ELF_HEADER_SIZE: u64 = 64;
const PROGRAM_HEADER_SIZE: u64 = 56;
const SECTION_HEADER_SIZE: u64 = 64;
const DYNAMIC_ENTRY_SIZE: u64 = 16;

/// The smallest page the loader maps with. A segment's own alignment is used when it is
/// larger, which is what a library linked for 64K pages declares.
const MIN_PAGE: u64 = 0x1000;

/// A 64-bit little-endian program header, which is all the renderer is ever built as.
#[derive(Clone, Copy, Debug)]
struct Segment {
    /// Which entry of the program header table this is, so that it can be written back.
    index: u64,
    kind: u32,
    flags: u32,
    offset: u64,
    address: u64,
    file_size: u64,
    memory_size: u64,
    align: u64,
}

/// A 64-bit section header, reduced to the fields this file reads or writes.
#[derive(Clone, Copy, Debug)]
struct Section {
    index: u64,
    kind: u32,
    address: u64,
    offset: u64,
    size: u64,
    link: u32,
}

/// One `.dynamic` entry.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct Dynamic {
    tag: i64,
    value: u64,
}

/// Everything about the file's layout that reading or setting the SONAME needs, read once.
struct Layout {
    program_header_offset: u64,
    program_header_entry: u64,
    program_header_count: u64,
    section_header_offset: u64,
    section_header_entry: u64,
    section_header_count: u64,
    loads: Vec<Segment>,
    sections: Vec<Section>,
    dynamic: Option<DynamicSection>,
}

/// What the `.dynamic` array is and where it lives in the file.
struct DynamicSection {
    /// Where the array starts in the file.
    offset: u64,
    /// Every entry up to and including the first `DT_NULL`.
    entries: Vec<Dynamic>,
    /// How many entries the array has room for, counting the padding after the
    /// terminator.
    capacity: u64,
}

impl DynamicSection {
    fn value(&self, tag: i64) -> Option<u64> {
        self.entries
            .iter()
            .find(|entry| entry.tag == tag)
            .map(|entry| entry.value)
    }
}

/// The SONAME this shared object carries, or `None` if it carries none.
///
/// Anything that is not a 64-bit little-endian ELF file is an error rather than `None`:
/// "there is no SONAME" and "this was not read" have to be told apart, because the first
/// means the library has not been named yet and the second means nobody knows.
pub fn soname(library: &Path) -> Result<Option<String>, String> {
    let mut file = open(library, false)?;
    let layout = read_layout(&mut file, library)?;
    let Some(dynamic) = layout.dynamic.as_ref() else {
        return Ok(None);
    };
    let Some(name_offset) = dynamic.value(DT_SONAME) else {
        return Ok(None);
    };
    let Some(string_table) = dynamic.value(DT_STRTAB) else {
        return Err(format!(
            "{} records a SONAME but no string table to read it out of",
            library.display()
        ));
    };

    let address = string_table.saturating_add(name_offset);
    let Some(at) = file_offset(&layout.loads, address) else {
        return Err(format!(
            "{} places its SONAME at an address no loadable segment covers",
            library.display()
        ));
    };
    Ok(Some(read_c_string(&mut file, at, library)?))
}

/// Give the library the SONAME `name`, and report whether anything had to be written.
///
/// A library that already carries exactly that name is not opened for writing at all, so
/// a renderer that was named on an earlier build can sit in a read-only directory.
///
/// Every failure leaves the file as it was: the checks that decide whether the edit fits
/// all run before the first byte is written.
pub fn set_soname(library: &Path, name: &str) -> Result<bool, String> {
    if name.is_empty() || name.as_bytes().contains(&0) {
        return Err(format!(
            "{name:?} cannot be a SONAME: it has to be a non-empty string with no NUL in it"
        ));
    }
    if soname(library)?.as_deref() == Some(name) {
        return Ok(false);
    }

    let mut file = open(library, true)?;
    let layout = read_layout(&mut file, library)?;
    let plan = plan_soname(&mut file, &layout, name, library)?;
    apply(&mut file, &layout, &plan, library)?;
    Ok(true)
}

/// Every write `set_soname` will make, decided before any of them is made.
struct Plan {
    /// The string table as it will be: the old one, then the name.
    table: Vec<u8>,
    /// Where the copy goes in the file.
    table_offset: u64,
    /// The segment that is extended to cover the copy, with its new sizes.
    segment: Segment,
    /// The dynamic array as it will be, terminator included.
    entries: Vec<Dynamic>,
    /// The string table's section header, if the file has section headers.
    section: Option<Section>,
}

fn plan_soname(
    file: &mut std::fs::File,
    layout: &Layout,
    name: &str,
    library: &Path,
) -> Result<Plan, String> {
    let Some(dynamic) = layout.dynamic.as_ref() else {
        return Err(format!(
            "{} has no dynamic section, so it is not a shared library anything can link",
            library.display()
        ));
    };
    let (Some(old_address), Some(old_size)) = (dynamic.value(DT_STRTAB), dynamic.value(DT_STRSZ))
    else {
        return Err(format!(
            "{} has no dynamic string table (DT_STRTAB and DT_STRSZ)",
            library.display()
        ));
    };
    let Some(old_offset) = file_offset(&layout.loads, old_address) else {
        return Err(format!(
            "{} places its dynamic string table at an address no loadable segment covers",
            library.display()
        ));
    };

    // The section the linker reads the strings through. GNU ld, gold and lld all go by the
    // section headers rather than DT_STRTAB, so if there are any, this one has to move with
    // the table or the linker would read the SONAME out of the old copy, past its end.
    let section = string_table_section(layout, old_address, old_size);
    if !layout.sections.is_empty() && section.is_none() {
        return Err(format!(
            "{} has section headers but none of them describes the dynamic string table",
            library.display()
        ));
    }

    // The renderer's is a few hundred bytes. Anything near this bound is a corrupt header,
    // and believing it would mean allocating whatever it says.
    if old_size > 16 << 20 {
        return Err(format!(
            "{} declares a {old_size} byte dynamic string table, which is not plausible",
            library.display()
        ));
    }
    let mut old_table = vec![0u8; old_size as usize];
    read_exact_at(file, old_offset, &mut old_table, library)?;

    // A table this function wrote on an earlier build is reused rather than copied again,
    // so that a renderer which moves, and is renamed on each move, does not use up the
    // space behind its segment one copy at a time. It is recognisable: its SONAME is an
    // absolute path, it is the last string in the table, and the table is the last thing
    // in its segment. A table the linker wrote is followed by the version sections, and a
    // linker given `-soname` with a path is the only other way to get one that starts
    // with a slash.
    let previous = dynamic.value(DT_SONAME).and_then(|at| {
        let at = at as usize;
        let ours = old_table.get(at) == Some(&b'/')
            && old_table.last() == Some(&0)
            && !old_table[at..old_table.len() - 1].contains(&0);
        let segment = containing_load(&layout.loads, old_address)?;
        (ours
            && segment.file_size == segment.memory_size
            && old_address + old_size == segment.address + segment.file_size)
            .then_some((at, segment))
    });

    let (strings_kept, candidates) = match previous {
        Some((at, segment)) => (at, vec![(segment, old_offset, old_address)]),
        None => (
            old_table.len(),
            extendable_segments(&layout.loads)
                .into_iter()
                .map(|segment| {
                    (
                        segment,
                        segment.offset + segment.file_size,
                        segment.address + segment.file_size,
                    )
                })
                .collect(),
        ),
    };

    let mut table = old_table[..strings_kept].to_vec();
    let name_offset = table.len() as u64;
    table.extend_from_slice(name.as_bytes());
    table.push(0);
    let length = table.len() as u64;

    let reused_section = previous.and(section.map(|section| section.index));
    let Some((segment, table_offset, table_address)) =
        candidates.into_iter().find(|(segment, offset, address)| {
            fits(layout, segment, *offset, *address, length, reused_section)
        })
    else {
        return Err(format!(
            "{} has no room for a {length} byte string table at the end of any loadable \
             segment",
            library.display()
        ));
    };

    let mut entries: Vec<Dynamic> = dynamic
        .entries
        .iter()
        .copied()
        .filter(|entry| entry.tag != DT_NULL)
        .collect();
    for entry in &mut entries {
        match entry.tag {
            DT_STRTAB => entry.value = table_address,
            DT_STRSZ => entry.value = length,
            DT_SONAME => entry.value = name_offset,
            _ => {}
        }
    }
    if !entries.iter().any(|entry| entry.tag == DT_SONAME) {
        entries.push(Dynamic {
            tag: DT_SONAME,
            value: name_offset,
        });
    }
    entries.push(Dynamic {
        tag: DT_NULL,
        value: 0,
    });
    if entries.len() as u64 > dynamic.capacity {
        return Err(format!(
            "{} has no spare slot in its dynamic section for a SONAME entry",
            library.display()
        ));
    }

    // Exactly to the end of the table. When a table written last time is rewritten with
    // a shorter name this shrinks the segment, which is what keeps the table the last
    // thing in it and so recognisable the next time.
    let end = table_address + length;
    let segment = Segment {
        file_size: end - segment.address,
        memory_size: end - segment.address,
        ..segment
    };
    let section = section.map(|section| Section {
        address: table_address,
        offset: table_offset,
        size: length,
        ..section
    });

    Ok(Plan {
        table,
        table_offset,
        segment,
        entries,
        section,
    })
}

fn apply(
    file: &mut std::fs::File,
    layout: &Layout,
    plan: &Plan,
    library: &Path,
) -> Result<(), String> {
    let dynamic = layout
        .dynamic
        .as_ref()
        .expect("planned against a dynamic section");

    // The table first and the pointers to it last, so that an interrupted build leaves at
    // worst some unreferenced bytes in the padding rather than a pointer into nothing.
    write_at(file, plan.table_offset, &plan.table, library)?;

    let at = layout.program_header_offset + plan.segment.index * layout.program_header_entry;
    let mut sizes = Vec::with_capacity(16);
    sizes.extend_from_slice(&plan.segment.file_size.to_le_bytes());
    sizes.extend_from_slice(&plan.segment.memory_size.to_le_bytes());
    write_at(file, at + 32, &sizes, library)?;

    if let Some(section) = plan.section {
        let at = layout.section_header_offset + section.index * layout.section_header_entry;
        let mut placement = Vec::with_capacity(24);
        placement.extend_from_slice(&section.address.to_le_bytes());
        placement.extend_from_slice(&section.offset.to_le_bytes());
        placement.extend_from_slice(&section.size.to_le_bytes());
        write_at(file, at + 16, &placement, library)?;
    }

    let mut bytes = Vec::with_capacity(plan.entries.len() * DYNAMIC_ENTRY_SIZE as usize);
    for entry in &plan.entries {
        bytes.extend_from_slice(&entry.tag.to_le_bytes());
        bytes.extend_from_slice(&entry.value.to_le_bytes());
    }
    write_at(file, dynamic.offset, &bytes, library)?;

    file.flush()
        .map_err(|error| format!("could not write {}: {error}", library.display()))
}

/// The loadable segments that can grow at their end: no zero-filled tail, because bytes
/// placed after the file-backed part would land in memory the loader clears. Read-only,
/// non-executable segments are tried first, since a string table is neither.
fn extendable_segments(loads: &[Segment]) -> Vec<Segment> {
    let mut candidates: Vec<Segment> = loads
        .iter()
        .copied()
        .filter(|segment| segment.file_size == segment.memory_size)
        .collect();
    candidates.sort_by_key(|segment| (segment.flags & (PF_W | PF_X) != 0, segment.index));
    candidates
}

/// Whether `length` bytes at `offset` in the file and `address` in memory can join
/// `segment`.
///
/// In the file they must not overlap anything that is there already: the headers, another
/// segment, or the contents of any section. In memory they must not reach a page that
/// another segment maps, because the loader maps whole pages and the later mapping would
/// replace these bytes with whatever that segment has at the same place.
fn fits(
    layout: &Layout,
    segment: &Segment,
    offset: u64,
    address: u64,
    length: u64,
    replacing_section: Option<u64>,
) -> bool {
    let file_end = offset + length;
    let overlaps = |start: u64, size: u64| size > 0 && start < file_end && offset < start + size;

    if overlaps(0, ELF_HEADER_SIZE)
        || overlaps(
            layout.program_header_offset,
            layout.program_header_count * layout.program_header_entry,
        )
        || overlaps(
            layout.section_header_offset,
            layout.section_header_count * layout.section_header_entry,
        )
    {
        return false;
    }
    for other in &layout.loads {
        if other.index != segment.index && overlaps(other.offset, other.file_size) {
            return false;
        }
    }
    for section in &layout.sections {
        if Some(section.index) == replacing_section
            || section.kind == SHT_NULL
            || section.kind == SHT_NOBITS
        {
            continue;
        }
        if overlaps(section.offset, section.size) {
            return false;
        }
    }

    let page = layout
        .loads
        .iter()
        .map(|load| load.align)
        .max()
        .unwrap_or(0)
        .max(MIN_PAGE);
    let first_page = address / page * page;
    let last_page = (address + length).div_ceil(page) * page;
    for other in &layout.loads {
        if other.index == segment.index {
            continue;
        }
        let start = other.address / page * page;
        let end = (other.address + other.memory_size).div_ceil(page) * page;
        if start < last_page && first_page < end {
            return false;
        }
    }

    // The new end has to stay above the segment's start in both spaces, which the
    // arithmetic above already assumes, and the copy has to keep the segment's offset to
    // address relationship, which it does because both were derived from the same end.
    address >= segment.address
        && offset >= segment.offset
        && address - segment.address == offset - segment.offset
}

/// The section header that describes the dynamic string table: the one the `.dynamic`
/// section links to, or failing that, the string table at the address `DT_STRTAB` names.
fn string_table_section(layout: &Layout, address: u64, size: u64) -> Option<Section> {
    let linked = layout
        .sections
        .iter()
        .find(|section| section.kind == SHT_DYNAMIC)
        .and_then(|dynamic| {
            layout
                .sections
                .iter()
                .find(|section| section.index == dynamic.link as u64)
        })
        .filter(|section| section.kind == SHT_STRTAB);
    linked.copied().or_else(|| {
        layout
            .sections
            .iter()
            .find(|section| {
                section.kind == SHT_STRTAB && section.address == address && section.size == size
            })
            .copied()
    })
}

fn open(library: &Path, writable: bool) -> Result<std::fs::File, String> {
    OpenOptions::new()
        .read(true)
        .write(writable)
        .open(library)
        .map_err(|error| format!("could not open {}: {error}", library.display()))
}

/// Read the headers, the loadable segments, the section headers and the `.dynamic` array.
/// A file with no `PT_DYNAMIC` segment, which a statically linked object or a plain
/// relocatable file would be, has `dynamic: None`, which is not an error to see.
fn read_layout(file: &mut std::fs::File, library: &Path) -> Result<Layout, String> {
    let mut header = [0u8; ELF_HEADER_SIZE as usize];
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

    let program_header_offset = u64_at(&header, 32);
    let section_header_offset = u64_at(&header, 40);
    let program_header_entry = u16_at(&header, 54);
    let program_header_count = u16_at(&header, 56);
    let section_header_entry = u16_at(&header, 58);
    let section_header_count = u16_at(&header, 60);
    if program_header_entry < PROGRAM_HEADER_SIZE {
        return Err(format!(
            "{} declares {program_header_entry} byte program headers, which cannot hold one",
            library.display()
        ));
    }
    if section_header_count > 0 && section_header_entry < SECTION_HEADER_SIZE {
        return Err(format!(
            "{} declares {section_header_entry} byte section headers, which cannot hold one",
            library.display()
        ));
    }

    let mut loads = Vec::new();
    let mut dynamic_segment = None;
    for index in 0..program_header_count {
        let mut entry = [0u8; PROGRAM_HEADER_SIZE as usize];
        read_exact_at(
            file,
            program_header_offset + index * program_header_entry,
            &mut entry,
            library,
        )?;
        let segment = Segment {
            index,
            kind: u32_at(&entry, 0),
            flags: u32_at(&entry, 4),
            offset: u64_at(&entry, 8),
            address: u64_at(&entry, 16),
            file_size: u64_at(&entry, 32),
            memory_size: u64_at(&entry, 40),
            align: u64_at(&entry, 48),
        };
        match segment.kind {
            PT_LOAD => loads.push(segment),
            PT_DYNAMIC => dynamic_segment = Some(segment),
            _ => {}
        }
    }

    let mut sections = Vec::new();
    for index in 0..section_header_count {
        let mut entry = [0u8; SECTION_HEADER_SIZE as usize];
        read_exact_at(
            file,
            section_header_offset + index * section_header_entry,
            &mut entry,
            library,
        )?;
        sections.push(Section {
            index,
            kind: u32_at(&entry, 4),
            address: u64_at(&entry, 16),
            offset: u64_at(&entry, 24),
            size: u64_at(&entry, 32),
            link: u32_at(&entry, 40),
        });
    }

    let dynamic = match dynamic_segment {
        None => None,
        Some(segment) => {
            let capacity = segment.file_size / DYNAMIC_ENTRY_SIZE;
            let mut entries = Vec::new();
            for index in 0..capacity {
                let mut entry = [0u8; DYNAMIC_ENTRY_SIZE as usize];
                read_exact_at(
                    file,
                    segment.offset + index * DYNAMIC_ENTRY_SIZE,
                    &mut entry,
                    library,
                )?;
                let entry = Dynamic {
                    tag: u64_at(&entry, 0) as i64,
                    value: u64_at(&entry, 8),
                };
                entries.push(entry);
                if entry.tag == DT_NULL {
                    break;
                }
            }
            Some(DynamicSection {
                offset: segment.offset,
                entries,
                capacity,
            })
        }
    };

    Ok(Layout {
        program_header_offset,
        program_header_entry,
        program_header_count,
        section_header_offset,
        section_header_entry,
        section_header_count,
        loads,
        sections,
        dynamic,
    })
}

fn u16_at(bytes: &[u8], at: usize) -> u64 {
    u16::from_le_bytes(bytes[at..at + 2].try_into().expect("two bytes")) as u64
}

fn u32_at(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(bytes[at..at + 4].try_into().expect("four bytes"))
}

fn u64_at(bytes: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(bytes[at..at + 8].try_into().expect("eight bytes"))
}

/// The loadable segment whose file-backed part holds `address`.
fn containing_load(loads: &[Segment], address: u64) -> Option<Segment> {
    loads.iter().copied().find(|segment| {
        segment
            .address
            .checked_add(segment.file_size)
            .is_some_and(|end| segment.address <= address && address < end)
    })
}

/// Where a virtual address lands in the file, according to the loadable segments.
fn file_offset(loads: &[Segment], address: u64) -> Option<u64> {
    containing_load(loads, address).map(|segment| segment.offset + (address - segment.address))
}

fn read_c_string(file: &mut std::fs::File, at: u64, library: &Path) -> Result<String, String> {
    // A SONAME is a file name or a path, so four kilobytes is a generous bound, and a
    // missing terminator stops here rather than reading the rest of a sixty megabyte
    // library into memory.
    let mut window = [0u8; 4096];
    file.seek(SeekFrom::Start(at))
        .map_err(|error| format!("could not read {}: {error}", library.display()))?;
    let mut read = 0;
    while read < window.len() {
        let got = file
            .read(&mut window[read..])
            .map_err(|error| format!("could not read {}: {error}", library.display()))?;
        if got == 0 {
            break;
        }
        read += got;
    }
    let end = window[..read]
        .iter()
        .position(|byte| *byte == 0)
        .ok_or_else(|| {
            format!(
                "the SONAME in {} is not terminated within four kilobytes",
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

fn write_at(file: &mut std::fs::File, at: u64, bytes: &[u8], library: &Path) -> Result<(), String> {
    file.seek(SeekFrom::Start(at))
        .and_then(|_| file.write_all(bytes))
        .map_err(|error| {
            format!(
                "could not write {} bytes at {at} of {}: {error}",
                bytes.len(),
                library.display()
            )
        })
}

/// A hand-assembled shared object, shaped the way GNU ld lays one out, for the tests here
/// and in `tests/renderer_resolution.rs`.
///
/// Written out byte by byte rather than compiled, because the tests have to produce a
/// Linux library on whichever platform they run on, and because a fixture laid out here is
/// one whose every byte can be asserted on afterwards.
#[cfg(test)]
pub mod fixture {
    /// Where the pieces sit. Virtual addresses equal file offsets, as in the first segment
    /// of a real library.
    pub const STRING_TABLE: u64 = 0x100;
    /// A stand-in for the version and relocation sections that follow `.dynstr` in a real
    /// library, filled with a pattern so that a write over it shows.
    pub const FOLLOWER: u64 = 0x200;
    pub const FOLLOWER_SIZE: u64 = 0x100;
    pub const FOLLOWER_BYTE: u8 = 0xa5;
    /// The end of the first, read-only segment. The rest of its page is padding.
    pub const FIRST_SEGMENT_END: u64 = FOLLOWER + FOLLOWER_SIZE;
    /// The writable segment, on the next page, holding only `.dynamic`.
    pub const DYNAMIC: u64 = 0x1000;
    pub const PAGE: u64 = 0x1000;

    pub const NEEDED: &str = "libc.so.6";

    /// The shape of the fixture.
    #[derive(Clone, Copy)]
    pub struct Shape<'a> {
        /// The SONAME it is linked with, if any.
        pub soname: Option<&'a str>,
        /// `DT_NULL` slots after the terminator. GNU ld leaves some; lld leaves none.
        pub spare_slots: usize,
        /// Whether it has section headers. A stripped-to-the-bone library may not.
        pub section_headers: bool,
    }

    impl Default for Shape<'_> {
        fn default() -> Self {
            Shape {
                soname: None,
                spare_slots: 5,
                section_headers: true,
            }
        }
    }

    /// Section header table indices.
    pub const DYNSTR_SECTION: u64 = 1;
    pub const DYNAMIC_SECTION: u64 = 3;

    pub fn shared_object(shape: Shape) -> Vec<u8> {
        // A leading NUL, because index 0 of a string table is the empty string.
        let mut strings = vec![0u8];
        let needed = strings.len() as u64;
        strings.extend_from_slice(NEEDED.as_bytes());
        strings.push(0);
        let soname = shape.soname.map(|soname| {
            let at = strings.len() as u64;
            strings.extend_from_slice(soname.as_bytes());
            strings.push(0);
            at
        });
        assert!((strings.len() as u64) <= FOLLOWER - STRING_TABLE);

        // One entry on either side of where a SONAME goes, so an edit that took a
        // neighbour with it is visible. 1 is DT_NEEDED and 12 is DT_INIT.
        let mut dynamic: Vec<(i64, u64)> = vec![(1, needed)];
        if let Some(at) = soname {
            dynamic.push((14, at));
        }
        dynamic.push((5, STRING_TABLE));
        dynamic.push((10, strings.len() as u64));
        dynamic.push((12, 0x3000));
        dynamic.push((0, 0));
        for _ in 0..shape.spare_slots {
            dynamic.push((0, 0));
        }
        let dynamic_size = dynamic.len() as u64 * 16;
        let dynamic_end = DYNAMIC + dynamic_size;

        let section_headers_at = dynamic_end.next_multiple_of(8);
        let section_count: u64 = if shape.section_headers { 4 } else { 0 };
        let size = section_headers_at + section_count * 64;
        let mut file = vec![0u8; size as usize];

        file[..4].copy_from_slice(&[0x7f, b'E', b'L', b'F']);
        file[4] = 2; // 64-bit
        file[5] = 1; // little-endian
        file[6] = 1; // ELF version
        file[16..18].copy_from_slice(&3u16.to_le_bytes()); // a shared object
        file[18..20].copy_from_slice(&0x3eu16.to_le_bytes()); // x86-64
        file[20..24].copy_from_slice(&1u32.to_le_bytes());
        file[32..40].copy_from_slice(&64u64.to_le_bytes()); // program headers
        if shape.section_headers {
            file[40..48].copy_from_slice(&section_headers_at.to_le_bytes());
        }
        file[52..54].copy_from_slice(&64u16.to_le_bytes()); // ELF header size
        file[54..56].copy_from_slice(&56u16.to_le_bytes()); // program header size
        file[56..58].copy_from_slice(&3u16.to_le_bytes()); // three of them
        file[58..60].copy_from_slice(&64u16.to_le_bytes()); // section header size
        file[60..62].copy_from_slice(&(section_count as u16).to_le_bytes());

        let mut segment = |index: usize, kind: u32, flags: u32, offset: u64, length: u64| {
            let at = 64 + index * 56;
            file[at..at + 4].copy_from_slice(&kind.to_le_bytes());
            file[at + 4..at + 8].copy_from_slice(&flags.to_le_bytes());
            file[at + 8..at + 16].copy_from_slice(&offset.to_le_bytes());
            file[at + 16..at + 24].copy_from_slice(&offset.to_le_bytes());
            file[at + 24..at + 32].copy_from_slice(&offset.to_le_bytes());
            file[at + 32..at + 40].copy_from_slice(&length.to_le_bytes());
            file[at + 40..at + 48].copy_from_slice(&length.to_le_bytes());
            file[at + 48..at + 56].copy_from_slice(&PAGE.to_le_bytes());
        };
        segment(0, 1, 4, 0, FIRST_SEGMENT_END); // PT_LOAD, read-only
        segment(1, 1, 6, DYNAMIC, dynamic_size); // PT_LOAD, read-write
        segment(2, 2, 6, DYNAMIC, dynamic_size); // PT_DYNAMIC

        let at = STRING_TABLE as usize;
        file[at..at + strings.len()].copy_from_slice(&strings);
        let at = FOLLOWER as usize;
        file[at..at + FOLLOWER_SIZE as usize].fill(FOLLOWER_BYTE);
        for (index, (tag, value)) in dynamic.iter().enumerate() {
            let at = DYNAMIC as usize + index * 16;
            file[at..at + 8].copy_from_slice(&tag.to_le_bytes());
            file[at + 8..at + 16].copy_from_slice(&value.to_le_bytes());
        }

        if shape.section_headers {
            let mut section = |index: u64, kind: u32, address: u64, length: u64, link: u32| {
                let at = (section_headers_at + index * 64) as usize;
                file[at + 4..at + 8].copy_from_slice(&kind.to_le_bytes());
                file[at + 16..at + 24].copy_from_slice(&address.to_le_bytes());
                file[at + 24..at + 32].copy_from_slice(&address.to_le_bytes());
                file[at + 32..at + 40].copy_from_slice(&length.to_le_bytes());
                file[at + 40..at + 44].copy_from_slice(&link.to_le_bytes());
            };
            // Index 0 is the null section every ELF file starts its table with.
            section(DYNSTR_SECTION, 3, STRING_TABLE, strings.len() as u64, 0);
            section(2, 1, FOLLOWER, FOLLOWER_SIZE, 0); // PROGBITS
            section(
                DYNAMIC_SECTION,
                6,
                DYNAMIC,
                dynamic_size,
                DYNSTR_SECTION as u32,
            );
        }
        file
    }

    /// Every `(tag, value)` in the dynamic section, up to its terminator.
    pub fn dynamic_entries(bytes: &[u8]) -> Vec<(i64, u64)> {
        let mut entries = Vec::new();
        let mut at = DYNAMIC as usize;
        loop {
            let tag = i64::from_le_bytes(bytes[at..at + 8].try_into().unwrap());
            let value = u64::from_le_bytes(bytes[at + 8..at + 16].try_into().unwrap());
            entries.push((tag, value));
            if tag == 0 {
                return entries;
            }
            at += 16;
        }
    }

    /// The file size and memory size of a program header.
    pub fn segment_sizes(bytes: &[u8], index: usize) -> (u64, u64) {
        let at = 64 + index * 56;
        (
            u64::from_le_bytes(bytes[at + 32..at + 40].try_into().unwrap()),
            u64::from_le_bytes(bytes[at + 40..at + 48].try_into().unwrap()),
        )
    }

    /// A section header's address, offset and size.
    pub fn section_placement(bytes: &[u8], index: u64) -> (u64, u64, u64) {
        let table = u64::from_le_bytes(bytes[40..48].try_into().unwrap());
        let at = (table + index * 64) as usize;
        (
            u64::from_le_bytes(bytes[at + 16..at + 24].try_into().unwrap()),
            u64::from_le_bytes(bytes[at + 24..at + 32].try_into().unwrap()),
            u64::from_le_bytes(bytes[at + 32..at + 40].try_into().unwrap()),
        )
    }

    /// The NUL-terminated string at `at`.
    pub fn string_at(bytes: &[u8], at: u64) -> String {
        let at = at as usize;
        let end = at + bytes[at..].iter().position(|byte| *byte == 0).unwrap();
        String::from_utf8(bytes[at..end].to_vec()).unwrap()
    }
}

#[cfg(test)]
mod tests {
    use super::fixture::{self, Shape};
    use super::*;
    use std::path::PathBuf;

    const ABSOLUTE: &str = "/home/someone/.cache/compose-rust/renderer/v9.9.9/linux-x64/lib/\
                            libcompose_rust_renderer.so";

    /// A file in a directory of its own, removed when the test ends.
    struct Scratch(PathBuf);

    impl Scratch {
        fn with(label: &str, bytes: &[u8]) -> Self {
            use std::sync::atomic::{AtomicU32, Ordering};
            static COUNTER: AtomicU32 = AtomicU32::new(0);
            let dir = std::env::temp_dir().join(format!(
                "compose-rust-elf-{label}-{}-{}",
                std::process::id(),
                COUNTER.fetch_add(1, Ordering::Relaxed)
            ));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).unwrap();
            std::fs::write(dir.join("renderer.so"), bytes).unwrap();
            Scratch(dir)
        }

        fn path(&self) -> PathBuf {
            self.0.join("renderer.so")
        }

        fn bytes(&self) -> Vec<u8> {
            std::fs::read(self.path()).unwrap()
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn value(entries: &[(i64, u64)], tag: i64) -> Option<u64> {
        entries.iter().find(|(t, _)| *t == tag).map(|(_, v)| *v)
    }

    /// The point of the whole module: a library with no SONAME comes out answering to its
    /// absolute path, and everything that pointed into the string table still finds the
    /// same string.
    #[test]
    fn nfr11_set_soname_round_trips_on_a_library_without_one() {
        let original = fixture::shared_object(Shape::default());
        let scratch = Scratch::with("set", &original);

        assert_eq!(soname(&scratch.path()).unwrap(), None);
        assert!(set_soname(&scratch.path(), ABSOLUTE).unwrap());
        assert_eq!(soname(&scratch.path()).unwrap().as_deref(), Some(ABSOLUTE));

        let after = scratch.bytes();
        let before_entries = fixture::dynamic_entries(&original);
        let after_entries = fixture::dynamic_entries(&after);

        // Every entry that is not about the string table is exactly what it was, in the
        // same order, and the SONAME is the one addition.
        let untouched = |entries: &[(i64, u64)]| -> Vec<(i64, u64)> {
            entries
                .iter()
                .copied()
                .filter(|(tag, _)| ![DT_STRTAB, DT_STRSZ, DT_SONAME, DT_NULL].contains(tag))
                .collect()
        };
        assert_eq!(untouched(&after_entries), untouched(&before_entries));
        assert_eq!(after_entries.len(), before_entries.len() + 1);
        assert_eq!(after_entries.last(), Some(&(DT_NULL, 0)));

        // The table moved into the padding after the first segment, and the segment grew
        // over it.
        let table = value(&after_entries, DT_STRTAB).unwrap();
        let length = value(&after_entries, DT_STRSZ).unwrap();
        assert_eq!(table, fixture::FIRST_SEGMENT_END);
        assert_eq!(
            fixture::segment_sizes(&after, 0),
            (table + length, table + length)
        );
        assert!(table + length <= fixture::DYNAMIC);

        // An offset into the old table means the same string in the new one.
        let needed = value(&after_entries, 1).unwrap();
        assert_eq!(fixture::string_at(&after, table + needed), fixture::NEEDED);
        let name = value(&after_entries, DT_SONAME).unwrap();
        assert_eq!(fixture::string_at(&after, table + name), ABSOLUTE);
        assert_eq!(name + ABSOLUTE.len() as u64 + 1, length);

        // The linker reads through the section header, so that moved too.
        assert_eq!(
            fixture::section_placement(&after, fixture::DYNSTR_SECTION),
            (table, table, length)
        );

        // And nothing else in the file changed: the old table is still where it was, the
        // section after it is untouched, and the other segment kept its size.
        let old_table_end =
            (fixture::STRING_TABLE + value(&before_entries, DT_STRSZ).unwrap()) as usize;
        assert_eq!(
            after[fixture::STRING_TABLE as usize..old_table_end],
            original[fixture::STRING_TABLE as usize..old_table_end]
        );
        assert!(
            after
                [fixture::FOLLOWER as usize..(fixture::FOLLOWER + fixture::FOLLOWER_SIZE) as usize]
                .iter()
                .all(|byte| *byte == fixture::FOLLOWER_BYTE)
        );
        assert_eq!(
            fixture::segment_sizes(&after, 1),
            fixture::segment_sizes(&original, 1)
        );
        assert_eq!(after.len(), original.len());
    }

    /// A library linked with a bare SONAME gets the absolute one instead, through the same
    /// entry rather than a second one.
    #[test]
    fn nfr11_set_soname_replaces_a_bare_soname() {
        let original = fixture::shared_object(Shape {
            soname: Some("libcompose_rust_renderer.so"),
            ..Shape::default()
        });
        let scratch = Scratch::with("replace", &original);

        assert!(set_soname(&scratch.path(), ABSOLUTE).unwrap());
        assert_eq!(soname(&scratch.path()).unwrap().as_deref(), Some(ABSOLUTE));

        let entries = fixture::dynamic_entries(&scratch.bytes());
        assert_eq!(
            entries.iter().filter(|(tag, _)| *tag == DT_SONAME).count(),
            1
        );
        assert_eq!(entries.len(), fixture::dynamic_entries(&original).len());
    }

    /// Every build runs through this. A library that already answers to the name is not
    /// written to, which is what lets a named renderer sit in a read-only directory.
    #[test]
    fn nfr11_set_soname_is_a_no_op_when_the_name_is_already_right() {
        let scratch = Scratch::with("idempotent", &fixture::shared_object(Shape::default()));
        assert!(set_soname(&scratch.path(), ABSOLUTE).unwrap());
        let named = scratch.bytes();

        assert!(!set_soname(&scratch.path(), ABSOLUTE).unwrap());
        assert_eq!(scratch.bytes(), named);
    }

    /// A renderer that moves is renamed on the next build, and the copy made last time is
    /// rewritten in place rather than followed by another one. Otherwise each move would
    /// use up more of the padding until a move failed.
    #[test]
    fn nfr11_renaming_reuses_the_table_written_last_time() {
        let scratch = Scratch::with("rename", &fixture::shared_object(Shape::default()));
        assert!(set_soname(&scratch.path(), ABSOLUTE).unwrap());
        let first = fixture::dynamic_entries(&scratch.bytes());

        let moved = "/elsewhere/lib/libcompose_rust_renderer.so";
        assert!(set_soname(&scratch.path(), moved).unwrap());
        assert_eq!(soname(&scratch.path()).unwrap().as_deref(), Some(moved));

        let bytes = scratch.bytes();
        let second = fixture::dynamic_entries(&bytes);
        assert_eq!(value(&second, DT_STRTAB), value(&first, DT_STRTAB));
        assert_eq!(
            value(&second, DT_SONAME),
            value(&first, DT_SONAME),
            "the old name was not replaced in place"
        );
        let table = value(&second, DT_STRTAB).unwrap();
        let needed = value(&second, 1).unwrap();
        assert_eq!(fixture::string_at(&bytes, table + needed), fixture::NEEDED);

        // And it still works the other way round, back to the longer name.
        assert!(set_soname(&scratch.path(), ABSOLUTE).unwrap());
        assert_eq!(soname(&scratch.path()).unwrap().as_deref(), Some(ABSOLUTE));
        assert_eq!(
            value(&fixture::dynamic_entries(&scratch.bytes()), DT_STRTAB),
            value(&first, DT_STRTAB)
        );
    }

    /// With no section headers there is only the dynamic section to update, and that is
    /// still enough for the loader and for a linker that goes by the dynamic segment.
    #[test]
    fn nfr11_set_soname_works_without_section_headers() {
        let scratch = Scratch::with(
            "no-sections",
            &fixture::shared_object(Shape {
                section_headers: false,
                ..Shape::default()
            }),
        );
        assert!(set_soname(&scratch.path(), ABSOLUTE).unwrap());
        assert_eq!(soname(&scratch.path()).unwrap().as_deref(), Some(ABSOLUTE));
    }

    /// No spare slot in the dynamic array means no SONAME can be added, and the file is
    /// left exactly as it was rather than half edited.
    #[test]
    fn nfr11_set_soname_refuses_a_dynamic_section_with_no_spare_slot() {
        let original = fixture::shared_object(Shape {
            spare_slots: 0,
            ..Shape::default()
        });
        let scratch = Scratch::with("no-slot", &original);

        let failure = set_soname(&scratch.path(), ABSOLUTE).expect_err("there is no slot");
        assert!(failure.contains("spare slot"), "{failure}");
        assert!(failure.contains("renderer.so"), "{failure}");
        assert_eq!(scratch.bytes(), original);
    }

    /// A name that does not fit in the padding before the next page is refused, rather
    /// than written over the next segment, and the file is left as it was.
    #[test]
    fn nfr11_set_soname_refuses_a_name_with_no_room_for_it() {
        let original = fixture::shared_object(Shape::default());
        let scratch = Scratch::with("no-room", &original);

        let long = format!("/{}/libcompose_rust_renderer.so", "d".repeat(0x2000));
        let failure = set_soname(&scratch.path(), &long).expect_err("it cannot fit");
        assert!(failure.contains("no room"), "{failure}");
        assert_eq!(scratch.bytes(), original);
    }

    /// "There is no SONAME" and "this file was not read" are different answers.
    #[test]
    fn nfr11_a_file_that_is_not_elf_is_an_error_not_a_missing_soname() {
        let scratch = Scratch::with("not-elf", b"not a shared object");
        let failure = soname(&scratch.path()).expect_err("this is not an ELF file");
        assert!(failure.contains("renderer.so"), "{failure}");
        assert!(set_soname(&scratch.path(), ABSOLUTE).is_err());
        assert_eq!(scratch.bytes(), b"not a shared object");
    }
}

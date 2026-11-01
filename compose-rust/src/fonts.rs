//! The font a piece of text with no role is set in.
//!
//! Text with a role takes its font from the theme, and nothing on this side can change
//! that for one node. Text with no role, which is what a page laid out by CSS draws, names
//! its own: a list of candidates tried in order, the way a CSS `font-family` list is. The
//! text is set in the first one the Renderer can resolve, and a letter it has no glyph for
//! comes from the next candidate that has one.
//!
//! The list travels as fixed-length records followed by the bytes of the family names they
//! point at. The same records sit in a `Text`'s `Font` property, in a run of a `Text`'s
//! spans, and in a measure request, so the Renderer resolves all three with one function
//! and the text it measures is set in exactly the font it draws.

use crate::schema::GenericFamily;
use std::rc::Rc;

/// Bytes of one candidate: what kind it is, then two words whose meaning the kind gives.
pub const FONT_REF_LEN: usize = 12;
/// Where each word of a candidate starts.
pub const FONT_REF_KIND_AT: usize = 0;
pub const FONT_REF_VALUE_AT: usize = 4;
pub const FONT_REF_LENGTH_AT: usize = 8;

/// A registered font asset. `value` is its id.
pub const FONT_REF_ASSET: u32 = 1;
/// An installed family, by name. `value` and `length` are where the UTF-8 name sits,
/// counted from the start of the buffer the record is in.
pub const FONT_REF_SYSTEM: u32 = 2;
/// A generic family. `value` is its `GenericFamily` tag.
pub const FONT_REF_GENERIC: u32 = 3;

/// The most candidates one list may hold. A CSS list longer than this is a page that
/// names fonts it does not expect to find, and the ninth would never be reached.
pub const MAX_FONT_REFS: usize = 8;

/// One candidate.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FontRef {
    /// A font the application registered as an asset, by id: CSS `@font-face`.
    Asset(u32),
    /// A family installed on the machine, by name.
    System(String),
    /// A family CSS names without naming a font.
    Generic(GenericFamily),
}

/// A list of candidates, ready to travel.
///
/// Shared rather than copied, like a span list: the same list on every frame compares
/// equal and costs no record.
#[derive(Clone, Default, PartialEq)]
pub struct FontRefs {
    bytes: Option<Rc<[u8]>>,
}

impl std::fmt::Debug for FontRefs {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.debug_list().entries(self.refs()).finish()
    }
}

impl FontRefs {
    /// The list, in the order it is tried. Anything past [`MAX_FONT_REFS`] is left out.
    pub fn new(refs: impl IntoIterator<Item = FontRef>) -> Self {
        let refs: Vec<FontRef> = refs.into_iter().take(MAX_FONT_REFS).collect();
        if refs.is_empty() {
            return Self::default();
        }
        let mut bytes = Vec::with_capacity(4 + refs.len() * FONT_REF_LEN);
        bytes.extend_from_slice(&(refs.len() as u32).to_le_bytes());
        let names_at = 4 + refs.len() * FONT_REF_LEN;
        write_records(&refs, &mut bytes, names_at as u32);
        Self {
            bytes: Some(bytes.into()),
        }
    }

    /// Adopts an already encoded list.
    pub fn from_bytes(bytes: impl Into<Rc<[u8]>>) -> Self {
        let bytes = bytes.into();
        Self {
            bytes: (!bytes.is_empty()).then_some(bytes),
        }
    }

    /// The property blob: a count, the records, then the names, each record's name
    /// counted from the start of the blob.
    pub fn as_bytes(&self) -> &[u8] {
        self.bytes.as_deref().unwrap_or(&[])
    }

    pub fn is_empty(&self) -> bool {
        self.bytes.is_none()
    }

    /// How many candidates the list holds.
    pub fn len(&self) -> usize {
        let bytes = self.as_bytes();
        if bytes.len() < 4 {
            return 0;
        }
        u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]) as usize
    }

    /// The candidates, decoded again. A record that does not decode is left out.
    pub fn refs(&self) -> Vec<FontRef> {
        let bytes = self.as_bytes();
        (0..self.len())
            .filter_map(|index| decode_record(bytes, 4 + index * FONT_REF_LEN))
            .collect()
    }

    /// The records without the count, with every name counted from `base` instead of from
    /// the start of the blob: what a span or a measure request carries.
    pub(crate) fn write_relocated(&self, out: &mut Vec<u8>, base: u32) {
        write_records(&self.refs(), out, base);
    }
}

/// Writes `refs` as records followed by their names, with each name's position counted
/// as `names_base` plus its distance from the end of the records.
fn write_records(refs: &[FontRef], out: &mut Vec<u8>, names_base: u32) {
    let mut names: Vec<u8> = Vec::new();
    for font in refs {
        let (kind, value, length) = match font {
            FontRef::Asset(id) => (FONT_REF_ASSET, *id, 0),
            FontRef::System(name) => {
                let at = names_base + names.len() as u32;
                names.extend_from_slice(name.as_bytes());
                (FONT_REF_SYSTEM, at, name.len() as u32)
            }
            FontRef::Generic(family) => (FONT_REF_GENERIC, u32::from(u16::from(*family)), 0),
        };
        out.extend_from_slice(&kind.to_le_bytes());
        out.extend_from_slice(&value.to_le_bytes());
        out.extend_from_slice(&length.to_le_bytes());
    }
    out.extend_from_slice(&names);
}

/// One record at `at`, with its name read from the same buffer.
pub(crate) fn decode_record(bytes: &[u8], at: usize) -> Option<FontRef> {
    let word = |offset: usize| -> Option<u32> {
        let slice = bytes.get(at + offset..at + offset + 4)?;
        Some(u32::from_le_bytes([slice[0], slice[1], slice[2], slice[3]]))
    };
    let value = word(FONT_REF_VALUE_AT)?;
    match word(FONT_REF_KIND_AT)? {
        FONT_REF_ASSET => Some(FontRef::Asset(value)),
        FONT_REF_SYSTEM => {
            let length = word(FONT_REF_LENGTH_AT)? as usize;
            let name = bytes.get(value as usize..value as usize + length)?;
            std::str::from_utf8(name)
                .ok()
                .map(|name| FontRef::System(name.to_owned()))
        }
        FONT_REF_GENERIC => u16::try_from(value)
            .ok()
            .and_then(|tag| GenericFamily::try_from(tag).ok())
            .map(FontRef::Generic),
        _ => None,
    }
}

/// What the bytes of a font stack start with.
///
/// A stack is registered as a font asset, because to everything that names a font it is
/// one: a theme slot or a font list names it by id the same way it names a face. These four
/// bytes are what tell the Renderer it is not a font file. No font format starts with them
/// (TrueType starts with `00 01 00 00` or `true`, OpenType with `OTTO`, a collection with
/// `ttcf`), so a face can never be read as a stack or a stack as a face.
pub const FONT_STACK_MAGIC: [u8; 4] = *b"CRFS";

/// The layout of a stack after the magic. Version 1 is a family count, then for each family
/// a face count and that many face ids, every number a little endian `u32`.
pub const FONT_STACK_VERSION: u32 = 1;

/// The most families one stack may hold, for the same reason a font list stops at eight.
pub const MAX_FONT_STACK_FAMILIES: usize = 8;

/// The most faces one family of a stack may hold: one for each of the nine standard weights,
/// upright and italic.
pub const MAX_FONT_STACK_FACES: usize = 18;

/// Fonts the application shipped, tried a glyph at a time in the order given.
///
/// A family is the faces of one typeface, a file for each weight or style. Text in a stack
/// is set in the first family, and each letter that family has no glyph for comes from the
/// next family that has one, the way CSS reads `font-family: Inter, Pretendard`. Letters no
/// family has come from the machine's own fonts. Within a family the face is picked by the
/// weight and style the text asks for, which each face's file says for itself.
///
/// A stack is registered once and named by id, so a theme can make it the font of every
/// role and stay `Copy`:
///
/// ```ignore
/// static INTER_REGULAR: &[u8] = include_bytes!("../assets/fonts/Inter-Regular.otf");
/// static INTER_BOLD: &[u8] = include_bytes!("../assets/fonts/Inter-Bold.otf");
/// static PRETENDARD_REGULAR: &[u8] = include_bytes!("../assets/fonts/Pretendard-Regular.otf");
///
/// let latin = [asset(AssetKind::Font, INTER_REGULAR), asset(AssetKind::Font, INTER_BOLD)];
/// let korean = [asset(AssetKind::Font, PRETENDARD_REGULAR)];
/// let stack = FontStack::new().family(latin).family(korean).register();
/// let theme = Theme::adaptive(DesignSystem::Material3).with_default_font(stack);
/// ```
///
/// The faces have to be registered before the stack that names them, which the order above
/// gives for free. A stack naming an id that is not a registered face is reported by the
/// Renderer, and that face is left out.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FontStack {
    families: Vec<Vec<u32>>,
}

impl FontStack {
    pub fn new() -> Self {
        Self::default()
    }

    /// Adds one family, tried after every family added before it. An empty family is left
    /// out, and so is anything past [`MAX_FONT_STACK_FAMILIES`] families or
    /// [`MAX_FONT_STACK_FACES`] faces.
    pub fn family(mut self, faces: impl IntoIterator<Item = u32>) -> Self {
        let faces: Vec<u32> = faces.into_iter().take(MAX_FONT_STACK_FACES).collect();
        if !faces.is_empty() && self.families.len() < MAX_FONT_STACK_FAMILIES {
            self.families.push(faces);
        }
        self
    }

    /// The families, in the order they are tried.
    pub fn families(&self) -> &[Vec<u32>] {
        &self.families
    }

    /// The asset bytes: the magic, the version, then the families.
    pub fn to_bytes(&self) -> Vec<u8> {
        let faces: usize = self.families.iter().map(Vec::len).sum();
        let mut out = Vec::with_capacity(12 + 4 * (self.families.len() + faces));
        out.extend_from_slice(&FONT_STACK_MAGIC);
        out.extend_from_slice(&FONT_STACK_VERSION.to_le_bytes());
        out.extend_from_slice(&(self.families.len() as u32).to_le_bytes());
        for family in &self.families {
            out.extend_from_slice(&(family.len() as u32).to_le_bytes());
            for face in family {
                out.extend_from_slice(&face.to_le_bytes());
            }
        }
        out
    }

    /// Reads bytes [`FontStack::to_bytes`] wrote, or `None` for anything else.
    pub fn from_bytes(bytes: &[u8]) -> Option<Self> {
        let word = |at: usize| -> Option<u32> {
            let slice = bytes.get(at..at + 4)?;
            Some(u32::from_le_bytes([slice[0], slice[1], slice[2], slice[3]]))
        };
        if bytes.get(..4)? != FONT_STACK_MAGIC || word(4)? != FONT_STACK_VERSION {
            return None;
        }
        let count = word(8)? as usize;
        if count > MAX_FONT_STACK_FAMILIES {
            return None;
        }
        let mut at = 12;
        let mut families = Vec::with_capacity(count);
        for _ in 0..count {
            let faces = word(at)? as usize;
            if faces == 0 || faces > MAX_FONT_STACK_FACES {
                return None;
            }
            at += 4;
            let mut family = Vec::with_capacity(faces);
            for _ in 0..faces {
                family.push(word(at)?);
                at += 4;
            }
            families.push(family);
        }
        (at == bytes.len()).then_some(Self { families })
    }

    /// Registers the stack and returns the id a theme or a font list names it by.
    ///
    /// The same stack registered twice is one registration, as with any asset, so this can
    /// run in a component body.
    pub fn register(&self) -> u32 {
        crate::asset::register_owned(crate::schema::AssetKind::Font, self.to_bytes())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fr40_a_font_list_round_trips_in_order() {
        let list = FontRefs::new([
            FontRef::Asset(7),
            FontRef::System("JetBrains Mono".to_owned()),
            FontRef::Generic(GenericFamily::Monospace),
        ]);
        assert_eq!(
            list.refs(),
            vec![
                FontRef::Asset(7),
                FontRef::System("JetBrains Mono".to_owned()),
                FontRef::Generic(GenericFamily::Monospace),
            ]
        );
        assert_eq!(list.len(), 3);
    }

    #[test]
    fn fr40_a_font_list_holds_at_most_eight() {
        let list = FontRefs::new((0..12).map(FontRef::Asset));
        assert_eq!(list.len(), MAX_FONT_REFS);
        assert_eq!(list.refs().last(), Some(&FontRef::Asset(7)));
    }

    #[test]
    fn fr40_an_empty_font_list_travels_as_nothing() {
        assert!(FontRefs::new([]).is_empty());
        assert!(FontRefs::new([]).as_bytes().is_empty());
    }

    #[test]
    fn fr52_a_font_stack_round_trips_in_order() {
        let stack = FontStack::new().family([1, 2, 3]).family([4]).family([]);
        assert_eq!(stack.families(), &[vec![1, 2, 3], vec![4]]);
        let bytes = stack.to_bytes();
        assert_eq!(&bytes[..4], b"CRFS");
        assert_eq!(FontStack::from_bytes(&bytes), Some(stack));
    }

    #[test]
    fn fr52_font_stack_bytes_are_never_a_font_file() {
        let fonts: [&[u8]; 6] = [&[0, 1, 0, 0], b"OTTO", b"true", b"ttcf", b"wOFF", b"wOF2"];
        for font in fonts {
            assert_ne!(font, &FONT_STACK_MAGIC[..]);
            assert_eq!(FontStack::from_bytes(font), None);
        }
        let mut bytes = FontStack::new().family([1]).to_bytes();
        bytes.push(0);
        assert_eq!(
            FontStack::from_bytes(&bytes),
            None,
            "trailing bytes are not a stack"
        );
    }

    #[test]
    fn fr52_a_font_stack_holds_at_most_eight_families() {
        let stack = (0..12).fold(FontStack::new(), |stack, id| stack.family([id]));
        assert_eq!(stack.families().len(), MAX_FONT_STACK_FAMILIES);
        let wide = FontStack::new().family(0..40);
        assert_eq!(wide.families()[0].len(), MAX_FONT_STACK_FACES);
    }

    #[test]
    fn fr52_the_same_font_stack_registers_once() {
        crate::asset::reset_assets();
        let stack = FontStack::new().family([1, 2]).family([3]);
        let first = stack.register();
        assert_eq!(stack.register(), first);
        assert_ne!(FontStack::new().family([3]).register(), first);
        crate::asset::reset_assets();
    }
}

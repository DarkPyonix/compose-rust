//! The font a piece of text with no role is set in.
//!
//! Text with a role takes its font from the theme, and nothing on this side can change
//! that for one node. Text with no role, which is what a page laid out by CSS draws, names
//! its own: a list of candidates tried in order, the way a CSS `font-family` list is, the
//! first one the Renderer can resolve winning.
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
}

//! OpenType features a piece of text asks its font for, such as tabular figures.
//!
//! A feature does not change which font the text is set in. It picks one of the forms the
//! font already has: digits of one width, a ligature left apart, a stylistic set. That is
//! why text with a type role may take features too, where it may not name a font.
//!
//! The list travels in the `FontFeatureSettings` property as fixed 8-byte records, the
//! four tag bytes and then the value as a little-endian `u32`. The Renderer turns it into
//! Compose's `fontFeatureSettings` string once, when the property changes.

use crate::runtime::composer::OwnedAttr;
use std::sync::RwLock;

/// Bytes of one feature record: the tag, then the value.
pub const FONT_FEATURE_LEN: usize = 8;
/// Where each part of a record starts.
pub const FONT_FEATURE_TAG_AT: usize = 0;
pub const FONT_FEATURE_VALUE_AT: usize = 4;
/// The most features one node may carry. The Renderer reports a longer list as a protocol
/// error and draws the text without any of them.
pub const MAX_FONT_FEATURES: usize = 16;

/// One OpenType feature and its value. Compose's `TextStyle.fontFeatureSettings` entry,
/// CSS's `font-feature-settings` entry.
///
/// The tag is four bytes rather than a string so that a tag of the wrong length does not
/// compile. Each byte has to be printable ASCII (0x20 to 0x7E); anything else is a
/// protocol error on the Renderer's side, and the text is drawn without features.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct FontFeature {
    pub tag: [u8; 4],
    pub value: u32,
}

impl FontFeature {
    /// Digits of one width, so a number that changes does not move the ones beside it.
    pub const TABULAR_NUMS: Self = Self::new(b"tnum", 1);
    /// Digits of their own widths, as a text face usually sets them.
    pub const PROPORTIONAL_NUMS: Self = Self::new(b"pnum", 1);
    /// Standard ligatures turned off, so `fi` stays two letters.
    pub const NO_LIGATURES: Self = Self::new(b"liga", 0);
    /// A zero with a slash or a dot through it, where the font has one.
    pub const SLASHED_ZERO: Self = Self::new(b"zero", 1);

    /// The feature `tag` with `value`: 1 turns most features on and 0 off; an alternate
    /// feature takes the number of the alternate.
    pub const fn new(tag: &[u8; 4], value: u32) -> Self {
        Self { tag: *tag, value }
    }

    /// The record as it travels.
    pub const fn to_bytes(self) -> [u8; FONT_FEATURE_LEN] {
        let value = self.value.to_le_bytes();
        [
            self.tag[0],
            self.tag[1],
            self.tag[2],
            self.tag[3],
            value[0],
            value[1],
            value[2],
            value[3],
        ]
    }
}

/// The most distinct feature lists a program keeps. An application names a handful (a
/// timer, a table), so the cap only matters to one that builds lists from data, which then
/// pays an allocation per write instead of growing this table without end.
const MAX_KEPT_LISTS: usize = 1024;

static KEPT: RwLock<Vec<&'static [u8]>> = RwLock::new(Vec::new());

/// The property value for `features`: nothing for an empty list, which removes the
/// property, and otherwise the records in the order given.
///
/// A list already seen is found under a shared lock and written without touching the
/// allocator, so a `Text` whose value changes every frame pays nothing for its features.
pub(crate) fn font_feature_attr(features: &[FontFeature]) -> OwnedAttr {
    if features.is_empty() {
        return OwnedAttr::None;
    }
    let mut stack = [0_u8; MAX_FONT_FEATURES * FONT_FEATURE_LEN];
    let heap;
    // A list past the limit is still sent, so the Renderer can say what is wrong with it.
    let bytes: &[u8] = if features.len() <= MAX_FONT_FEATURES {
        for (index, feature) in features.iter().enumerate() {
            let at = index * FONT_FEATURE_LEN;
            stack[at..at + FONT_FEATURE_LEN].copy_from_slice(&feature.to_bytes());
        }
        &stack[..features.len() * FONT_FEATURE_LEN]
    } else {
        heap = font_feature_bytes(features);
        &heap
    };
    {
        let kept = KEPT.read().unwrap_or_else(|p| p.into_inner());
        if let Some(found) = kept.iter().find(|list| **list == *bytes) {
            return OwnedAttr::Blob(*found);
        }
    }
    let mut kept = KEPT.write().unwrap_or_else(|p| p.into_inner());
    if let Some(found) = kept.iter().find(|list| **list == *bytes) {
        return OwnedAttr::Blob(*found);
    }
    if kept.len() >= MAX_KEPT_LISTS {
        return OwnedAttr::Bytes(bytes.to_vec());
    }
    let leaked: &'static [u8] = Box::leak(bytes.to_vec().into_boxed_slice());
    kept.push(leaked);
    OwnedAttr::Blob(leaked)
}

/// The records of `features`, in order, as the `FontFeatureSettings` property carries them.
pub fn font_feature_bytes(features: &[FontFeature]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(features.len() * FONT_FEATURE_LEN);
    for feature in features {
        bytes.extend_from_slice(&feature.to_bytes());
    }
    bytes
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fr83_a_feature_record_is_its_tag_then_its_value() {
        assert_eq!(FontFeature::TABULAR_NUMS.to_bytes(), *b"tnum\x01\0\0\0");
        assert_eq!(
            FontFeature::new(b"ss02", 0x0102_0304).to_bytes(),
            [b's', b's', b'0', b'2', 4, 3, 2, 1]
        );
    }

    #[test]
    fn fr83_the_same_list_is_kept_once() {
        let list = [FontFeature::new(b"cv11", 7), FontFeature::NO_LIGATURES];
        let (OwnedAttr::Blob(first), OwnedAttr::Blob(second)) =
            (font_feature_attr(&list), font_feature_attr(&list))
        else {
            panic!("a short list is kept");
        };
        assert!(
            std::ptr::eq(first, second),
            "the second write found the first"
        );
        assert_eq!(first, font_feature_bytes(&list).as_slice());
        assert_eq!(font_feature_attr(&[]), OwnedAttr::None);
    }
}

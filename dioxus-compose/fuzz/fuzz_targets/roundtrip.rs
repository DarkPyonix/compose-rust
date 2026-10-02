//! Encoder output always decodes back identically, and truncating
//! that output at *any* byte offset yields a clean `ProtocolError` rather than a
//! panic. Mutations are generated structurally with `arbitrary` so the encoder is
//! driven over its whole input domain, not just the shapes a fuzzer stumbles onto.
#![no_main]

use arbitrary::Arbitrary;
use dioxus_compose::protocol::{BatchEncoder, Mutation, PropertyValue, decode_batch};
use dioxus_compose::schema::{Modifier, PropertyKind, Selection, WidgetKind};
use libfuzzer_sys::fuzz_target;

/// An owned mirror of `Mutation`, so `arbitrary` can build one without lifetimes.
#[derive(Arbitrary, Debug)]
enum OwnedMutation {
    Create {
        node_id: u32,
        widget: u16,
    },
    SetProp {
        node_id: u32,
        property: u16,
        value: OwnedValue,
    },
    SetModifier {
        node_id: u32,
        index: u16,
        modifier: OwnedModifier,
    },
    Insert {
        parent_id: u32,
        node_id: u32,
        index: u32,
    },
    Move {
        parent_id: u32,
        node_id: u32,
        index: u32,
    },
    Remove {
        node_id: u32,
    },
    SetText {
        node_id: u32,
        text: String,
        selection: Option<(u32, u32)>,
    },
    AppendText {
        node_id: u32,
        text: String,
    },
}

#[derive(Arbitrary, Debug)]
enum OwnedValue {
    None,
    String(String),
    Bool(bool),
    Integer(i64),
    /// Carried as bits and canonicalised below; a NaN payload would make the
    /// round-trip comparison meaningless rather than finding a real defect.
    Float(u32),
}

#[derive(Arbitrary, Debug)]
enum OwnedModifier {
    Empty,
    Padding(u32),
    FillMaxWidth,
    FillMaxHeight,
    Width(u32),
    Height(u32),
    Size { width: u32, height: u32 },
    Background(u32),
    Clickable { handler_id: u64 },
}

fn finite(bits: u32) -> f32 {
    let value = f32::from_bits(bits);
    if value.is_finite() { value } else { 0.0 }
}

impl OwnedModifier {
    fn borrow(&self) -> Modifier {
        match *self {
            Self::Empty => Modifier::Empty,
            Self::Padding(bits) => Modifier::Padding(finite(bits)),
            Self::FillMaxWidth => Modifier::FillMaxWidth,
            Self::FillMaxHeight => Modifier::FillMaxHeight,
            Self::Width(bits) => Modifier::Width(finite(bits)),
            Self::Height(bits) => Modifier::Height(finite(bits)),
            Self::Size { width, height } => Modifier::Size {
                width: finite(width),
                height: finite(height),
            },
            Self::Background(argb) => Modifier::Background(argb),
            Self::Clickable { handler_id } => Modifier::Clickable { handler_id },
        }
    }
}

impl OwnedMutation {
    /// `None` when the generated tag is not a schema value; those are the
    /// decoder's job, not the encoder's.
    fn borrow(&self) -> Option<Mutation<'_>> {
        Some(match self {
            Self::Create { node_id, widget } => Mutation::Create {
                node_id: *node_id,
                widget: WidgetKind::try_from(*widget).ok()?,
            },
            Self::SetProp {
                node_id,
                property,
                value,
            } => Mutation::SetProp {
                node_id: *node_id,
                property: PropertyKind::try_from(*property).ok()?,
                value: match value {
                    OwnedValue::None => PropertyValue::None,
                    OwnedValue::String(text) => PropertyValue::String(text),
                    OwnedValue::Bool(flag) => PropertyValue::Bool(*flag),
                    OwnedValue::Integer(number) => PropertyValue::Integer(*number),
                    OwnedValue::Float(bits) => PropertyValue::Float(finite(*bits)),
                },
            },
            Self::SetModifier {
                node_id,
                index,
                modifier,
            } => Mutation::SetModifier {
                node_id: *node_id,
                index: *index,
                modifier: modifier.borrow(),
            },
            Self::Insert {
                parent_id,
                node_id,
                index,
            } => Mutation::Insert {
                parent_id: *parent_id,
                node_id: *node_id,
                index: *index,
            },
            Self::Move {
                parent_id,
                node_id,
                index,
            } => Mutation::Move {
                parent_id: *parent_id,
                node_id: *node_id,
                index: *index,
            },
            Self::Remove { node_id } => Mutation::Remove {
                node_id: *node_id,
            },
            Self::SetText {
                node_id,
                text,
                selection,
            } => Mutation::SetText {
                node_id: *node_id,
                text,
                // `(u32::MAX, u32::MAX)` is the wire encoding of "no selection",
                // so it cannot also denote a selection.
                selection: selection
                    .filter(|(start, end)| *start != u32::MAX || *end != u32::MAX)
                    .map(|(start, end)| Selection { start, end }),
            },
            Self::AppendText { node_id, text } => Mutation::AppendText {
                node_id: *node_id,
                text,
            },
        })
    }
}

fuzz_target!(|owned: Vec<OwnedMutation>| {
    let mutations: Vec<Mutation<'_>> = owned.iter().filter_map(OwnedMutation::borrow).collect();
    let mut encoder = BatchEncoder::default();
    for mutation in &mutations {
        if encoder.encode(mutation).is_err() {
            return;
        }
    }
    let Ok(bytes) = encoder.finish() else {
        return;
    };
    let bytes = bytes.to_vec();

    // Encoder output always decodes back identically.
    let decoded = decode_batch(&bytes).expect("encoder output must decode");
    assert_eq!(decoded, mutations, "encode/decode round trip diverged");
    drop(decoded);

    // Truncation at every byte offset is a clean ProtocolError, never a panic.
    for end in 0..bytes.len() {
        let _ = decode_batch(&bytes[..end]);
    }
});

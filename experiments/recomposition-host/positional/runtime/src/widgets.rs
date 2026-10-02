//! The widget layer, done the way a slot table would do it, writing the real wire format.
//!
//! The first version of this runtime emitted strings into a `Vec` and had no props, no
//! schema and no encoder, so its numbers were a floor. This is the part that was missing:
//! `text` takes the same twenty-odd props `dioxus_compose::Text` takes, compares them the
//! same way, and writes the same records through the same `BatchEncoder`. What it does
//! differently is only what a slot table does differently: the previous props sit in a
//! slot, a composition whose props compare equal is skipped in place, and a changed one
//! writes the properties that moved and nothing else.

use crate::{compose, composable, own_node, remember, skip_to_group_end, take_removed};
use dioxus_compose::protocol::{BatchEncoder, Mutation, PropertyValue};
use dioxus_compose::schema::{PropertyKind, WidgetKind};
use dioxus_compose::{Modifier, Paint, ShapeRole, SpaceRole, TextAlign, TextOverflow, TypeRole};
use std::cell::RefCell;

struct Frame {
    encoder: BatchEncoder,
    next_node: u32,
    /// The node children are being inserted under, and how many have been placed so far.
    parents: Vec<(u32, u32)>,
}

thread_local! {
    static FRAME: RefCell<Frame> = RefCell::new(Frame {
        encoder: BatchEncoder::default(),
        next_node: 1,
        parents: vec![(0, 0)],
    });
}

fn write(mutation: Mutation<'_>) {
    FRAME.with(|frame| frame.borrow_mut().encoder.encode(&mutation).unwrap());
}

/// Runs one composition and returns the size of the batch it produced.
///
/// The batch is the same envelope the boundary carries: cleared at the start, closed by
/// `finish`, with the removals of anything the composition stopped producing at the end.
pub fn compose_frame(content: impl FnOnce()) -> usize {
    FRAME.with(|frame| {
        let mut frame = frame.borrow_mut();
        frame.encoder.clear();
        frame.parents.clear();
        frame.parents.push((0, 0));
    });
    compose(content);
    for node_id in take_removed() {
        write(Mutation::Remove { node_id });
    }
    FRAME.with(|frame| frame.borrow_mut().encoder.finish().unwrap().len())
}

/// Hands the batch the last frame produced to `read`, without copying it.
pub fn with_last_batch(read: impl FnOnce(&[u8])) {
    FRAME.with(|frame| read(frame.borrow_mut().encoder.finish().unwrap()));
}

/// Starts over: a fresh node counter and an empty encoder, for a test or a new sweep.
pub fn reset_frame() {
    FRAME.with(|frame| {
        let mut frame = frame.borrow_mut();
        frame.next_node = 1;
        frame.parents.clear();
        frame.parents.push((0, 0));
        frame.encoder.clear();
    });
}

/// This call site's node, created and inserted the first time it is composed.
///
/// A node already created is not re-inserted. Its position only changes when something
/// before it appears or goes away, and a real runtime would send `Move` for that; this
/// does not, which is fine for everything measured here and wrong for reordering.
fn node(widget: WidgetKind) -> (u32, bool) {
    let slot = remember(|| Option::<u32>::None);
    let (id, created) = match *slot.borrow() {
        Some(id) => (id, false),
        None => {
            let (id, parent, index) = FRAME.with(|frame| {
                let mut frame = frame.borrow_mut();
                let id = frame.next_node;
                frame.next_node += 1;
                let (parent, index) = *frame.parents.last().unwrap();
                (id, parent, index)
            });
            write(Mutation::Create { node_id: id, widget });
            write(Mutation::Insert {
                parent_id: parent,
                node_id: id,
                index,
            });
            (id, true)
        }
    };
    *slot.borrow_mut() = Some(id);
    own_node(id);
    FRAME.with(|frame| frame.borrow_mut().parents.last_mut().unwrap().1 += 1);
    (id, created)
}

/// A container. Its children are placed under it in the order they are composed.
#[composable]
pub fn column(content: impl FnOnce()) {
    let (id, _) = node(WidgetKind::Column);
    FRAME.with(|frame| frame.borrow_mut().parents.push((id, 0)));
    content();
    FRAME.with(|frame| frame.borrow_mut().parents.pop());
}

/// The props `dioxus_compose::Text` takes, less the runs of styled spans.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct TextProps {
    pub weight: Option<f32>,
    pub width: Option<f32>,
    pub height: Option<f32>,
    pub padding: Option<f32>,
    pub padding_role: Option<SpaceRole>,
    pub background: Option<Paint>,
    pub shape_role: Option<ShapeRole>,
    pub corner_radius: Option<f32>,
    pub border_width: Option<f32>,
    pub border_color: Option<Paint>,
    pub elevation: Option<f32>,
    pub fill_max_width: bool,
    pub fill_max_height: bool,
    pub text: String,
    pub type_role: Option<TypeRole>,
    pub font_size: Option<f32>,
    pub font_weight: Option<u16>,
    pub line_height: Option<f32>,
    pub letter_spacing: Option<f32>,
    pub color: Option<Paint>,
    pub text_align: Option<TextAlign>,
    pub max_lines: Option<u32>,
    pub overflow: Option<TextOverflow>,
}

/// A text node.
///
/// Its previous props live in a slot. Equal props skip the rest of the group, which is the
/// skip Compose makes when a composable's parameters are all unchanged. Unequal props are
/// compared field by field and each field that moved is one record, which is what the
/// Dioxus path sends for the same change.
#[composable]
pub fn text(props: TextProps) {
    let (id, created) = node(WidgetKind::Text);
    let last = remember(|| Option::<TextProps>::None);
    if !created && last.borrow().as_ref() == Some(&props) {
        skip_to_group_end();
        return;
    }
    let previous = last.borrow_mut().take().unwrap_or_default();
    write_changes(id, &previous, &props);
    *last.borrow_mut() = Some(props);
}

fn write_changes(node_id: u32, before: &TextProps, after: &TextProps) {
    // Modifier slots, numbered the way the Renderer numbers them.
    let modifiers: [(u16, bool, Option<Modifier>); 11] = [
        (0, before.weight != after.weight, after.weight.map(Modifier::Weight)),
        (1, before.fill_max_width != after.fill_max_width, after.fill_max_width.then_some(Modifier::FillMaxWidth)),
        (2, before.fill_max_height != after.fill_max_height, after.fill_max_height.then_some(Modifier::FillMaxHeight)),
        (3, before.width != after.width, after.width.map(Modifier::Width)),
        (4, before.height != after.height, after.height.map(Modifier::Height)),
        (5, before.shape_role != after.shape_role, after.shape_role.map(Modifier::ShapeRole)),
        (6, before.background != after.background, after.background.map(Modifier::Background)),
        (7, before.border_width != after.border_width || before.border_color != after.border_color, None),
        (8, before.elevation != after.elevation, None),
        (10, before.padding != after.padding || before.padding_role != after.padding_role,
            after.padding.map(Modifier::Padding).or(after.padding_role.map(Modifier::PaddingRole))),
        (5, before.corner_radius != after.corner_radius, None),
    ];
    for (index, moved, modifier) in modifiers {
        if moved {
            write(Mutation::SetModifier {
                node_id,
                index,
                modifier: modifier.unwrap_or(Modifier::Empty),
            });
        }
    }

    let role = |value: Option<u16>| i64::from(value.unwrap_or(0));
    let dp = |value: Option<f32>| value.unwrap_or(0.0);
    if before.text != after.text {
        write(Mutation::SetProp {
            node_id,
            property: PropertyKind::Text,
            value: PropertyValue::String(&after.text),
        });
    }
    let integers = [
        (PropertyKind::TypeRole, role(before.type_role.map(u16::from)), role(after.type_role.map(u16::from))),
        (PropertyKind::FontWeight, role(before.font_weight), role(after.font_weight)),
        (PropertyKind::Color, before.color.map_or(0, |paint| paint.to_bits() as i64), after.color.map_or(0, |paint| paint.to_bits() as i64)),
        (PropertyKind::TextAlign, role(before.text_align.map(u16::from)), role(after.text_align.map(u16::from))),
        (PropertyKind::MaxLines, i64::from(before.max_lines.unwrap_or(0)), i64::from(after.max_lines.unwrap_or(0))),
        (PropertyKind::Overflow, role(before.overflow.map(u16::from)), role(after.overflow.map(u16::from))),
    ];
    for (property, old, new) in integers {
        if old != new {
            write(Mutation::SetProp {
                node_id,
                property,
                value: PropertyValue::Integer(new),
            });
        }
    }
    let floats = [
        (PropertyKind::FontSize, dp(before.font_size), dp(after.font_size)),
        (PropertyKind::LineHeight, dp(before.line_height), dp(after.line_height)),
        (PropertyKind::LetterSpacing, dp(before.letter_spacing), dp(after.letter_spacing)),
    ];
    for (property, old, new) in floats {
        if old != new {
            write(Mutation::SetProp {
                node_id,
                property,
                value: PropertyValue::Float(new),
            });
        }
    }
}

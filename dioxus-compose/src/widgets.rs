#![allow(non_snake_case)]

use dioxus_core::{Callback, Element, EventHandler};
use dioxus_core_macro::{Props, component, rsx};
use dioxus_hooks::use_signal;
use dioxus_signals::WritableExt as _;

use crate as dioxus_elements;
use crate::Key;
use crate::drawing::DrawList;
use crate::schema::{
    Alignment, Arrangement, ButtonVariant, ColorRole, IconRole, MaterialRole, MotionRole, Paint,
    ShapeRole, SlotRole, SpaceRole, TextAlign, TextOverflow, TypeRole,
};
use std::cell::Cell;
use std::rc::Rc;

/// A key-down event whose consumption state is shared with the Host boundary.
#[derive(Clone, Debug)]
pub struct KeyEvent {
    key: Key,
    shift_key: bool,
    ctrl_key: bool,
    alt_key: bool,
    meta_key: bool,
    consumed: Rc<Cell<bool>>,
}

impl KeyEvent {
    pub(crate) fn new(
        key: Key,
        shift_key: bool,
        ctrl_key: bool,
        alt_key: bool,
        meta_key: bool,
    ) -> Self {
        Self {
            key,
            shift_key,
            ctrl_key,
            alt_key,
            meta_key,
            consumed: Rc::new(Cell::new(false)),
        }
    }

    pub fn key(&self) -> Key {
        self.key
    }

    pub fn shift_key(&self) -> bool {
        self.shift_key
    }

    pub fn ctrl_key(&self) -> bool {
        self.ctrl_key
    }

    pub fn alt_key(&self) -> bool {
        self.alt_key
    }

    pub fn meta_key(&self) -> bool {
        self.meta_key
    }

    pub fn consume(&self) {
        self.consumed.set(true);
    }

    pub fn consumed(&self) -> bool {
        self.consumed.get()
    }
}

/// A role that was not set is tag 0, which means "not sent". The Renderer
/// never sees a zero role, so it never has to guess what an unset role meant.
fn role(value: Option<impl Into<u16>>) -> i64 {
    value.map_or(0, |value| i64::from(value.into()))
}

/// A Modifier value that was not set sends nothing at all, rather than a zero. No padding
/// and a padding of zero look the same on screen, but only one of them should cost a
/// mutation on every frame the widget appears in.
fn opt_dp(value: Option<f32>) -> Option<f64> {
    value.map(f64::from)
}

fn opt_role(value: Option<impl Into<u16>>) -> Option<i64> {
    value.map(|value| i64::from(value.into()))
}

fn opt_paint(value: Option<Paint>) -> Option<i64> {
    value.map(|paint| paint.to_bits() as i64)
}

fn dp(value: Option<f32>) -> f64 {
    f64::from(value.unwrap_or(0.0))
}

#[component]
pub fn Column(
    /// Asks the Renderer to report this node's measured size, under the name a
    /// screen gave it with `use_node_size`. Nothing is measured without it.
    #[props(default)]
    observe_size: Option<i64>,
    /// How important this node's changes are. The curve and the length are the design
    /// system's answer to that, and are never sent from here.
    #[props(default)]
    motion: Option<MotionRole>,
    /// What this node's surface is made of. Blur on the systems that blur, a lifted tone
    /// on the ones that lift, a flat fill on the rest.
    #[props(default)]
    material: Option<MaterialRole>,
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] arrangement: Option<Arrangement>,
    #[props(default)] spacing: Option<f32>,
    #[props(default)] space_role: Option<SpaceRole>,
    #[props(default)] alignment: Option<Alignment>,
    children: Element,
) -> Element {
    rsx! {
        column {
            observe_size,
            motion: opt_role(motion),
            material: opt_role(material),
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            arrangement: role(arrangement),
            spacing: dp(spacing),
            space_role: role(space_role),
            alignment: role(alignment),
            {children}
        }
    }
}

#[component]
pub fn Row(
    /// Asks the Renderer to report this node's measured size, under the name a
    /// screen gave it with `use_node_size`. Nothing is measured without it.
    #[props(default)]
    observe_size: Option<i64>,
    /// How important this node's changes are. The curve and the length are the design
    /// system's answer to that, and are never sent from here.
    #[props(default)]
    motion: Option<MotionRole>,
    /// What this node's surface is made of. Blur on the systems that blur, a lifted tone
    /// on the ones that lift, a flat fill on the rest.
    #[props(default)]
    material: Option<MaterialRole>,
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] arrangement: Option<Arrangement>,
    #[props(default)] spacing: Option<f32>,
    #[props(default)] space_role: Option<SpaceRole>,
    #[props(default)] alignment: Option<Alignment>,
    children: Element,
) -> Element {
    rsx! {
        row {
            observe_size,
            motion: opt_role(motion),
            material: opt_role(material),
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            arrangement: role(arrangement),
            spacing: dp(spacing),
            space_role: role(space_role),
            alignment: role(alignment),
            {children}
        }
    }
}

#[component]
pub fn ComposeBox(
    /// Asks the Renderer to report this node's measured size, under the name a
    /// screen gave it with `use_node_size`. Nothing is measured without it.
    #[props(default)]
    observe_size: Option<i64>,
    /// How important this node's changes are. The curve and the length are the design
    /// system's answer to that, and are never sent from here.
    #[props(default)]
    motion: Option<MotionRole>,
    /// What this node's surface is made of. Blur on the systems that blur, a lifted tone
    /// on the ones that lift, a flat fill on the rest.
    #[props(default)]
    material: Option<MaterialRole>,
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] alignment: Option<Alignment>,
    children: Element,
) -> Element {
    rsx! {
        composebox {
            observe_size,
            motion: opt_role(motion),
            material: opt_role(material),
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            alignment: role(alignment),
            {children}
        }
    }
}

/// The whole content with a vertical scroll attached. The scroll position is the
/// Renderer's, like focus and animation state, so scrolling never reaches the Host.
#[component]
pub fn ScrollColumn(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    children: Element,
) -> Element {
    rsx! {
        scrollcolumn {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            {children}
        }
    }
}

/// `type_role` alone takes the design system's size, weight, line height and letter
/// spacing. Each override replaces one axis and costs one `SetProp`, so changing the font
/// size does not resend the rest of the text's styling.
#[component]
pub fn Text(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    /// Runs of different treatment inside [text]: a bold phrase, a link, a piece of
    /// code. Left out, the widget travels exactly as it did before runs existed.
    #[props(default)]
    spans: crate::spans::TextSpans,
    #[props(into)] text: String,
    #[props(default)] type_role: Option<TypeRole>,
    #[props(default)] font_size: Option<f32>,
    #[props(default)] font_weight: Option<u16>,
    #[props(default)] line_height: Option<f32>,
    #[props(default)] letter_spacing: Option<f32>,
    #[props(default)] color: Option<Paint>,
    #[props(default)] text_align: Option<TextAlign>,
    #[props(default)] max_lines: Option<u32>,
    #[props(default)] overflow: Option<TextOverflow>,
) -> Element {
    rsx! {
        text {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            // Left out entirely when there are none, so a Text that says nothing about
            // runs travels exactly as it did before runs existed.
            spans: (!spans.is_empty()).then_some(spans),
            text,
            type_role: role(type_role),
            font_size: dp(font_size),
            font_weight: i64::from(font_weight.unwrap_or(0)),
            line_height: dp(line_height),
            letter_spacing: dp(letter_spacing),
            color: color.map_or(0, |paint| paint.to_bits() as i64),
            text_align: role(text_align),
            max_lines: i64::from(max_lines.unwrap_or(0)),
            overflow: role(overflow),
        }
    }
}

#[component]
pub fn TextField(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(into, default)] placeholder: String,
    #[props(default = true)] enabled: bool,
    #[props(default)] multiline: bool,
    /// The rung of the type ladder the field's own text is set in. A field holding a file
    /// path or a snippet of code wants the monospace rung; prose does not.
    #[props(default)]
    type_role: Option<TypeRole>,
    #[props(default)] on_value_change: EventHandler<String>,
    #[props(default)] on_submit: EventHandler<String>,
    #[props(default)] on_focus_lost: EventHandler<()>,
    #[props(default)] on_key_down: EventHandler<KeyEvent>,
) -> Element {
    rsx! {
        textfield {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            placeholder,
            enabled,
            multiline,
            type_role: role(type_role),
            onvaluechange: move |event| on_value_change.call((*event.data()).clone()),
            onsubmit: move |event| on_submit.call((*event.data()).clone()),
            onfocuslost: move |_| on_focus_lost.call(()),
            onkeydown: move |event| on_key_down.call((*event.data()).clone()),
        }
    }
}

/// The variant is the seam the design system's component rule attaches to.
/// The same rsx draws differently per system, and that is correct behaviour.
#[component]
pub fn Button(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(into)] text: String,
    /// The meaning of the glyph on it, never a picture: `IconRole::Search` comes out as
    /// this design system's search icon.
    ///
    /// With a `text` beside it the button shows both. With `text` empty it is the glyph
    /// alone, which is what every one of the reference toolbars is made of, and the role
    /// is what names it for assistive technology.
    #[props(default)]
    icon: Option<IconRole>,
    #[props(default = true)] enabled: bool,
    #[props(default)] variant: Option<ButtonVariant>,
    /// The label's colour, for the rare button whose meaning is not the variant's.
    /// A destructive action is the case that needs it: it is a plain button in every
    /// design system, and what marks it is that its label is the error colour.
    #[props(default)]
    color: Option<Paint>,
    #[props(default)] on_click: EventHandler<()>,
) -> Element {
    rsx! {
        button {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            text,
            icon: opt_role(icon),
            enabled,
            variant: role(variant),
            color: opt_paint(color),
            onclick: move |_| on_click.call(()),
        }
    }
}

/// A hairline between two rows of a grouped list.
///
/// One [`Divider`] under the name application code already uses. Nothing about the weight,
/// the colour or the inset is decided here: a Material rule, a Fluent layer stroke and an
/// Apple grouped-list separator are three different lines, and which one gets drawn is the
/// active design system's answer rather than a constant this library holds.
///
/// `color` overrides that answer for the rare case where a list rules itself in something
/// other than the quiet edge. Leaving it unset is the usual thing to do.
#[component]
pub fn Separator(#[props(default)] color: Option<Paint>) -> Element {
    rsx! {
        Divider { background: color }
    }
}

#[component]
pub fn Spacer(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
) -> Element {
    rsx! {
        spacer {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
        }
    }
}

/// The paths of the files a reader let go over a node.
///
/// Separated on the wire by a NUL, which is the one byte no path on any of the three
/// desktops may contain. A newline would have been shorter to read and wrong: a file
/// called "notes\nfor tuesday" is legal on two of them, and splitting on newlines would
/// have turned one file into two.
#[derive(Clone, Debug, PartialEq)]
pub struct FileDrop {
    paths: Vec<String>,
}

impl FileDrop {
    /// Takes apart the one string the paths travelled in.
    ///
    /// Public because the separation is part of what this type promises, and a test that
    /// could not build one could only check it through a window.
    pub fn new(joined: &str) -> Self {
        Self {
            paths: joined
                .split('\0')
                .filter(|path| !path.is_empty())
                .map(str::to_owned)
                .collect(),
        }
    }

    /// Every path that arrived, in the order the platform gave them.
    pub fn paths(&self) -> &[String] {
        &self.paths
    }
}

/// The visible item range the Renderer asks the Host to materialise.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RangeRequest {
    start: u32,
    count: u32,
}

impl RangeRequest {
    pub(crate) fn new(start: u32, count: u32) -> Self {
        Self { start, count }
    }

    pub fn start(&self) -> usize {
        self.start as usize
    }

    pub fn count(&self) -> usize {
        self.count as usize
    }
}

/// A windowed list. The Host declares `item_count` and a stable key per item, and
/// materialises **exactly** the range the Renderer last requested.
///
/// The read-ahead buffer belongs to the Renderer, which owns the scroll position and so
/// knows how far ahead to ask. Widening the range here would break the Renderer's placement:
/// `start` is the global index of the first child it receives, and that is what lets it draw
/// a real Compose `LazyColumn` of `item_count` items. The data stays in the Host, so
/// scrolling back re-materialises an identical subtree.
#[component]
pub fn LazyColumn(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    item_count: usize,
    #[props(default)] key_of: Option<Callback<usize, String>>,
    item: Callback<usize, Element>,
) -> Element {
    let mut range = use_signal(|| (0_usize, 0_usize));
    let (start, count) = range();
    let first = start.min(item_count);
    let last = first.saturating_add(count).min(item_count);
    rsx! {
        lazycolumn {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            item_count: item_count as i64,
            onrangerequest: move |event: dioxus_core::Event<RangeRequest>| {
                let requested = event.data();
                range.set((requested.start(), requested.count()));
            },
            for index in first..last {
                {
                    let item_key = key_of
                        .map_or_else(|| index.to_string(), |key_of| key_of.call(index));
                    rsx! {
                        composebox { key: "{item_key}", item_key, {item.call(index)} }
                    }
                }
            }
        }
    }
}

/// A grouped container. What a card looks like, its background, corner and resting
/// elevation, is the design system's decision, so the widget carries no appearance of its
/// own. `Modifier::Elevation` overrides the resting height when the Host has a reason to.
#[component]
pub fn Card(
    /// Asks the Renderer to report this node's measured size, under the name a
    /// screen gave it with `use_node_size`. Nothing is measured without it.
    #[props(default)]
    observe_size: Option<i64>,
    /// How important this node's changes are. The curve and the length are the design
    /// system's answer to that, and are never sent from here.
    #[props(default)]
    motion: Option<MotionRole>,
    /// What this node's surface is made of. Blur on the systems that blur, a lifted tone
    /// on the ones that lift, a flat fill on the rest.
    #[props(default)]
    material: Option<MaterialRole>,
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    children: Element,
) -> Element {
    rsx! {
        card {
            observe_size,
            motion: opt_role(motion),
            material: opt_role(material),
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            {children}
        }
    }
}

/// A plain background-and-elevation container. Use it where a `Card`'s grouping meaning
/// would be wrong and only the surface is wanted.
#[component]
pub fn Surface(
    /// Asks the Renderer to report this node's measured size, under the name a
    /// screen gave it with `use_node_size`. Nothing is measured without it.
    #[props(default)]
    observe_size: Option<i64>,
    /// How important this node's changes are. The curve and the length are the design
    /// system's answer to that, and are never sent from here.
    #[props(default)]
    motion: Option<MotionRole>,
    /// What this node's surface is made of. Blur on the systems that blur, a lifted tone
    /// on the ones that lift, a flat fill on the rest.
    #[props(default)]
    material: Option<MaterialRole>,
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    children: Element,
) -> Element {
    rsx! {
        surface {
            observe_size,
            motion: opt_role(motion),
            material: opt_role(material),
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            {children}
        }
    }
}

/// A modal. `open` seeds the Renderer's own open state and carries changes that came from
/// somewhere other than the Renderer; the Renderer runs the enter and exit itself so the
/// animation never round trips through the Host. `on_dismiss` fires when the user asks to
/// close it, and the Host decides whether to honour that by setting `open` to false.
#[component]
pub fn Dialog(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] open: bool,
    #[props(default)] on_dismiss: EventHandler<()>,
    children: Element,
) -> Element {
    rsx! {
        dialog {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            open,
            ondismiss: move |_| on_dismiss.call(()),
            {children}
        }
    }
}

/// A popup anchored to `anchor`, which is the widget the menu hangs off. The anchor is the
/// first child on the wire and the entries follow it, so the Renderer can place the popup
/// without the Host knowing any screen coordinates.
///
/// Each entry supplies its own `on_click`, which is what tells the Host which one was
/// chosen. The Renderer closes the popup itself.
#[component]
pub fn Menu(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] expanded: bool,
    #[props(default)] on_dismiss: EventHandler<()>,
    anchor: Element,
    children: Element,
) -> Element {
    rsx! {
        menu {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            open: expanded,
            ondismiss: move |_| on_dismiss.call(()),
            {anchor}
            {children}
        }
    }
}

/// A row of tabs. Each child is one tab.
///
/// `selected_index` seeds the Renderer's selection and moves it when the Host changes it.
/// Tapping a tab changes the selection in the Renderer and reports it by firing that tab's
/// own `on_click`, so switching tabs costs one event and no re-render of the tab strip.
#[component]
pub fn Tabs(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    /// The colour of the mark that shows which tab is selected.
    ///
    /// The design system decides where this is unset, which is what an adaptive
    /// application wants. A unified one names it, because its reference may have no
    /// accent at all and a system asked the question answers with its own.
    #[props(default)]
    color: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] selected_index: usize,
    children: Element,
) -> Element {
    rsx! {
        tabs {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            color: opt_paint(color),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            selected_index: selected_index as i64,
            {children}
        }
    }
}

/// The bar across the top of a screen. Its children are its content, left to right. How the
/// bar is sized, spaced and separated from what is below it is the design system's rule.
#[component]
pub fn TopAppBar(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    /// The window's title, where this bar is the window's own caption.
    ///
    /// A property rather than a child, because the Renderer has to be able to find it.
    /// Three design systems centre the window title and put everything else at the
    /// leading edge, and a bar whose children are an arbitrary tree gives the Renderer no
    /// way to tell which of them is the title. Where the bar is not the caption, or the
    /// design system puts its title at the start, this draws in the same place a first
    /// child would.
    #[props(default)]
    title: String,
    children: Element,
) -> Element {
    rsx! {
        topappbar {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            text: title,
            {children}
        }
    }
}

/// The horizontal axis of the same windowing protocol `LazyColumn` uses, with the same
/// contract: the Host materialises exactly the range the Renderer last asked for, and the
/// read-ahead buffer belongs to the Renderer because the scroll position does.
#[component]
pub fn LazyRow(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    item_count: usize,
    #[props(default)] key_of: Option<Callback<usize, String>>,
    item: Callback<usize, Element>,
) -> Element {
    let mut range = use_signal(|| (0_usize, 0_usize));
    let (start, count) = range();
    let first = start.min(item_count);
    let last = first.saturating_add(count).min(item_count);
    rsx! {
        lazyrow {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            item_count: item_count as i64,
            onrangerequest: move |event: dioxus_core::Event<RangeRequest>| {
                let requested = event.data();
                range.set((requested.start(), requested.count()));
            },
            for index in first..last {
                {
                    let item_key = key_of
                        .map_or_else(|| index.to_string(), |key_of| key_of.call(index));
                    rsx! {
                        composebox { key: "{item_key}", item_key, {item.call(index)} }
                    }
                }
            }
        }
    }
}

/// An explanation attached to its child. It is a description as much as a hover popup: the
/// Renderer also exposes the text to the accessibility tree, so a pointer is not the only
/// way to reach it.
#[component]
pub fn Tooltip(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(into)] text: String,
    children: Element,
) -> Element {
    rsx! {
        tooltip {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            text,
            {children}
        }
    }
}

/// Pixels the widget vocabulary cannot produce: charts, sparklines, signature pads, rings.
///
/// The list is a value, not a callback, because drawing code cannot cross the boundary into
/// an ahead-of-time compiled Renderer. Two equal lists are one attribute comparison, so a
/// frame that redraws the same chart sends nothing. Size comes from the Modifier chain, and
/// coordinates are dp from the widget's top-left corner.
///
/// Colour goes through `Paint`, so `Paint::Role(ColorRole::Primary)` draws in whatever the
/// active design system calls primary.
#[component]
pub fn Canvas(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    commands: DrawList,
) -> Element {
    rsx! {
        canvas {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            commands,
        }
    }
}

/// A registered asset drawn as a picture. The bytes reached the Renderer once, through
/// `Host::register_asset`, and what crosses per frame is the id.
#[component]
pub fn Image(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    asset_id: u32,
) -> Element {
    rsx! {
        image {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            asset: i64::from(asset_id),
        }
    }
}

/// A registered icon, tinted by a role. `Host::register_icon` registers the meaning rather
/// than a picture, so the same declaration comes out as the icon each design system draws.
#[component]
pub fn Icon(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    asset_id: u32,
    #[props(default)] color: Option<Paint>,
) -> Element {
    rsx! {
        icon {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            asset: i64::from(asset_id),
            color: color.map_or(0, |paint| paint.to_bits() as i64),
        }
    }
}

/// A date, as whole days since 1970-01-01.
///
/// The widget says what the value is and what range is allowed. How it is picked, a
/// calendar grid, a wheel or a flyout, belongs to the design system, and there is no
/// property that lets the Host ask for one of them.
///
/// Time zone, locale, the first day of the week and the display format stay in the
/// Renderer, which is the only side that can read the platform's settings. A format string
/// crossing here would turn "follows the platform" into a claim the Renderer cannot keep.
#[component]
pub fn DatePicker(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    /// Days since 1970-01-01.
    value: i64,
    #[props(default)] min: Option<i64>,
    #[props(default)] max: Option<i64>,
    #[props(default = true)] enabled: bool,
    #[props(default)] on_change: EventHandler<i64>,
) -> Element {
    rsx! {
        datepicker {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            value,
            min: min.unwrap_or(i64::MIN),
            max: max.unwrap_or(i64::MAX),
            enabled,
            onchange: move |event: dioxus_core::Event<f64>| on_change.call(*event.data() as i64),
        }
    }
}

/// A time of day, as minutes since midnight.
///
/// Whether it is picked on a dial, a wheel or a list is the design system's decision, and
/// whether it reads as 12 or 24 hour is the platform's.
#[component]
pub fn TimePicker(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    /// Minutes since midnight.
    value: u32,
    #[props(default)] min: Option<u32>,
    #[props(default)] max: Option<u32>,
    #[props(default = true)] enabled: bool,
    #[props(default)] on_change: EventHandler<u32>,
) -> Element {
    rsx! {
        timepicker {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            value: i64::from(value),
            min: i64::from(min.unwrap_or(0)),
            max: i64::from(max.unwrap_or(MINUTES_IN_A_DAY - 1)),
            enabled,
            onchange: move |event: dioxus_core::Event<f64>| {
                on_change.call((*event.data() as i64).clamp(0, i64::from(MINUTES_IN_A_DAY - 1)) as u32)
            },
        }
    }
}

const MINUTES_IN_A_DAY: u32 = 24 * 60;

/// One choice out of a list. Each child is one option, and the Renderer reports the
/// position the user landed on.
#[component]
pub fn Dropdown(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] selected_index: usize,
    #[props(default = true)] enabled: bool,
    #[props(default)] on_change: EventHandler<usize>,
    children: Element,
) -> Element {
    rsx! {
        dropdown {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            selected_index: selected_index as i64,
            enabled,
            onchange: move |event: dioxus_core::Event<f64>| {
                on_change.call((*event.data() as i64).max(0) as usize)
            },
            {children}
        }
    }
}

/// A two-state box.
///
/// The widget is controlled: it draws exactly what `checked` says and reports what the
/// user asked for, so the value the Host holds and the box on screen cannot disagree.
/// What the mark looks like, and whether pressing it ripples or dims, is the design
/// system's rule.
#[component]
pub fn Checkbox(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] checked: bool,
    #[props(default = true)] enabled: bool,
    #[props(default)] on_change: EventHandler<bool>,
) -> Element {
    rsx! {
        checkbox {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            checked,
            enabled,
            onchange: move |event: dioxus_core::Event<f64>| on_change.call(is_on(*event.data())),
        }
    }
}

/// One choice out of several. Which siblings it excludes is the Host's business, so the
/// widget carries only whether this one is the chosen one.
///
/// `selected` is the Compose name for that state and is what the rsx attribute is called.
/// On the wire it is the same "is this on" boolean the other two toggles send, because a
/// selected radio button and a switch that is on are one fact.
#[component]
pub fn RadioButton(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] selected: bool,
    #[props(default = true)] enabled: bool,
    #[props(default)] on_change: EventHandler<bool>,
) -> Element {
    rsx! {
        radiobutton {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            checked: selected,
            enabled,
            onchange: move |event: dioxus_core::Event<f64>| on_change.call(is_on(*event.data())),
        }
    }
}

/// An on/off control. The same contract as `Checkbox`: the Host owns the value, and the
/// track, the thumb and the way the change is animated belong to the design system.
#[component]
pub fn Switch(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] checked: bool,
    #[props(default = true)] enabled: bool,
    #[props(default)] on_change: EventHandler<bool>,
) -> Element {
    rsx! {
        switch {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            checked,
            enabled,
            onchange: move |event: dioxus_core::Event<f64>| on_change.call(is_on(*event.data())),
        }
    }
}

/// A value picked from a range.
///
/// The position a drag is passing through stays in the Renderer, so following a finger
/// costs no boundary call, and `value` seeds that position and moves it when the change
/// came from elsewhere. `steps` is the number of stops between the two ends; zero leaves
/// the slider continuous.
#[component]
pub fn Slider(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    /// The colour of the part of the track that has been filled in, and of the thumb.
    ///
    /// The design system decides where this is unset, which is what an adaptive
    /// application wants. A unified one names it, because its reference may have no
    /// accent at all and a system asked the question answers with its own.
    #[props(default)]
    color: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] value: f32,
    #[props(default = 0.0)] min: f32,
    #[props(default = 1.0)] max: f32,
    #[props(default)] steps: u32,
    #[props(default = true)] enabled: bool,
    #[props(default)] on_change: EventHandler<f32>,
) -> Element {
    rsx! {
        slider {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            color: opt_paint(color),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            value: f64::from(value),
            min: f64::from(min),
            max: f64::from(max),
            steps: i64::from(steps),
            enabled,
            onchange: move |event: dioxus_core::Event<f64>| on_change.call(*event.data() as f32),
        }
    }
}

/// Work in progress. `determinate` says whether `value` means anything, and `circular`
/// picks between the two forms every supported design system has.
///
/// There is no speed, no easing and no track colour here: how an indeterminate indicator
/// travels is motion, and motion is the design system's rule.
#[component]
pub fn ProgressIndicator(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] value: f32,
    #[props(default = true)] determinate: bool,
    #[props(default)] circular: bool,
) -> Element {
    rsx! {
        progressindicator {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            value: f64::from(value),
            determinate,
            circular,
        }
    }
}

/// A line between two things. The axis is the only decision the Host makes: the
/// thickness, the colour and the inset all come from the design system.
#[component]
pub fn Divider(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] vertical: bool,
) -> Element {
    rsx! {
        divider {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            vertical,
        }
    }
}

/// A toggle's new state, as it arrives on the wire: off is 0.0 and on is 1.0.
///
/// The comparison is against zero rather than against 1.0 so a Renderer that reports a
/// half-finished transition still reads as on.
fn is_on(value: f64) -> bool {
    value != 0.0
}

/// A set of destinations with one of them selected, plus the screen they lead to.
///
/// The children that are `NavigationItem` are the destinations; every other child is the
/// content of the selected screen. Splitting by kind rather than by index means a
/// destination that only exists under some condition does not force the Host to keep two
/// lists in step by hand.
///
/// **Nothing here says bar, rail or drawer.** The Renderer has already measured the window,
/// so it chooses: a bar across the bottom of a phone-shaped window, a rail down the side of
/// a tablet-shaped one, a drawer standing open on a desktop. Asking the Host to choose
/// would mean the width travelling up, a whole VirtualDom pass, and a tree of destinations
/// being destroyed and rebuilt every time the window crossed a boundary, to arrive at the
/// same three layouts the Renderer can reach by moving the nodes it already has.
///
/// `selected_index` counts destinations, not children. It seeds the Renderer's selection
/// and moves it when the change came from somewhere other than a tap; a tap moves the
/// selection in the Renderer and reports it by firing that destination's own `on_click`.
#[component]
pub fn Navigation(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] selected_index: usize,
    /// What sits above the destinations in a strip: a mark, a name, a control.
    ///
    /// Drawn only where the destinations are a strip down the side. A bar along the bottom
    /// is five destinations standing side by side and has nowhere to put this.
    #[props(default)]
    head: Option<Element>,
    /// What sits below them. An account row, usually.
    #[props(default)]
    foot: Option<Element>,
    children: Element,
) -> Element {
    rsx! {
        navigation {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            selected_index: selected_index as i64,
            // The same slot widget a Scaffold's bars arrive in, because it says the same
            // thing: where inside my parent this content goes.
            if let Some(head) = head {
                scaffoldslot { slot: i64::from(u16::from(SlotRole::TopBar)), {head} }
            }
            if let Some(foot) = foot {
                scaffoldslot { slot: i64::from(u16::from(SlotRole::BottomBar)), {foot} }
            }
            {children}
        }
    }
}

/// One destination inside a [`Navigation`].
///
/// The label and the icon are properties rather than a child tree, because the three
/// presentations disagree about how they go together: a bottom bar stacks a small label
/// under the icon, a rail may drop the label entirely, a drawer sets the label beside the
/// icon and left-aligns the row. A `Column { Icon, Text }` handed over as children would
/// have settled that question in the Host, where the window's width is not known.
///
/// The icon is a meaning, never a picture: `IconRole::Inbox` comes out as this design
/// system's inbox.
#[component]
pub fn NavigationItem(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] text: String,
    #[props(default)] icon: Option<IconRole>,
    /// The colour of this destination's icon and label, in both states.
    ///
    /// The design system decides what selected looks like where this is not set, which is
    /// what an adaptive application wants. A unified one names it, because its reference
    /// may have no accent in the bar at all and a system asked the question answers with
    /// its own.
    #[props(default)]
    color: Option<Paint>,
    #[props(default = true)] enabled: bool,
    /// The named group of the strip this destination belongs to.
    ///
    /// Neighbouring destinations carrying the same name are one group and the name is its
    /// heading. A destination that names none belongs to no group and is drawn where it
    /// always was. What a heading looks like is the design system's.
    #[props(default)]
    section: Option<String>,
    #[props(default)] on_click: EventHandler<()>,
) -> Element {
    rsx! {
        navigationitem {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            text,
            icon: opt_role(icon),
            color: opt_paint(color),
            enabled,
            section,
            onclick: move |_| on_click.call(()),
        }
    }
}

/// A temporary surface that slides in from an edge, holding whatever its children are.
///
/// It is a `Dialog` in every way that crosses the boundary: `open` seeds the Renderer's own
/// open state and carries a change that came from elsewhere, and `on_dismiss` says once
/// that the user asked to close it. The drag itself does not cross: while the sheet is
/// being pulled about, Rust hears nothing, and only a drag that ends in a dismissal is
/// reported, once.
///
/// Which edge it comes from is the Renderer's decision, made from the width it measured:
/// up from the bottom in a narrow window, in from the side in a wide one. There is no
/// property that could ask for one of them, for the same reason there is none that can ask
/// a date picker for a wheel.
#[component]
pub fn Sheet(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] open: bool,
    #[props(default)] on_dismiss: EventHandler<()>,
    children: Element,
) -> Element {
    rsx! {
        sheet {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            open,
            ondismiss: move |_| on_dismiss.call(()),
            {children}
        }
    }
}

/// The screen's frame: a top bar, a bottom bar, a floating action and the page.
///
/// Shaped after Material's `Scaffold`, because that is the vocabulary people already
/// write, and for the same reason: a screen's frame is the same few parts every time, and
/// assembling it by hand in every application is how every application ends up with a
/// different one.
///
/// What it buys here is more than the layout. **The application says which slot it filled
/// and nothing about what the slot becomes.** A top bar is the window's caption on a
/// desktop that hands its caption to the application and an ordinary bar where it does
/// not; a bottom bar is a bar on a phone, a rail on a tablet and a permanent drawer on a
/// desktop; a floating action floats, or sits in the toolbar, or folds into a menu. Those
/// are decisions about the running platform and the measured window, and both of those
/// are known in the Renderer and not here.
///
/// The page is the children, and it is laid out clear of whatever the other slots took.
/// Nothing about that arithmetic reaches the application, which is the point: a screen
/// that computed its own inset would be wrong the moment the Renderer chose differently.
///
/// ```ignore
/// rsx! {
///     Scaffold {
///         top_bar: rsx! { TopAppBar { title: "Standard" } },
///         bottom_bar: rsx! { Navigation { /* destinations */ } },
///         Column { /* the page */ }
///     }
/// }
/// ```
#[component]
pub fn Scaffold(
    /// The window's own colour, where this screen holds its own palette.
    ///
    /// Read off the root, so a frame that is the root is where it has to be said. A
    /// screen that says nothing gets the theme's page colour.
    #[props(default)]
    background: Option<Paint>,
    /// What the window itself is made of, where the screen wants it to be a material.
    ///
    /// Read off the root like the colour above, and asking for it is what stands the
    /// window up with something behind it to show. `MaterialRole::Chrome` is the window's
    /// own: on a platform that can put the system's material behind a window, the desktop
    /// shows through wherever the design system draws chrome; everywhere else the design
    /// system answers the role the way it answers it on any other node.
    #[props(default)]
    material: Option<MaterialRole>,
    /// The bar across the top, where this screen wants one.
    #[props(default)]
    top_bar: Option<Element>,
    /// The destinations, or the actions that belong at the bottom of a phone screen.
    #[props(default)]
    bottom_bar: Option<Element>,
    /// The one action a screen is mostly about, where it has one.
    #[props(default)]
    floating_action: Option<Element>,
    /// The page.
    children: Element,
) -> Element {
    rsx! {
        scaffold {
            background: opt_paint(background),
            material: opt_role(material),
            if let Some(bar) = top_bar {
                scaffoldslot { slot: i64::from(u16::from(SlotRole::TopBar)), {bar} }
            }
            if let Some(bar) = bottom_bar {
                scaffoldslot { slot: i64::from(u16::from(SlotRole::BottomBar)), {bar} }
            }
            if let Some(action) = floating_action {
                scaffoldslot {
                    slot: i64::from(u16::from(SlotRole::FloatingAction)),
                    {action}
                }
            }
            scaffoldslot { slot: i64::from(u16::from(SlotRole::Content)), {children} }
        }
    }
}

/// A grid that only builds the rows it can show.
///
/// The windowing protocol is `LazyColumn`'s, unchanged: the Renderer asks for a range of
/// items and this materialises exactly that range. What a grid adds is that the range
/// arrives rounded to whole rows, because a row is what the Renderer lays out.
///
/// How wide a column is can be said two ways, and they are different questions. `columns`
/// is a count the screen insists on. `min_column_width` is a width below which a column
/// may not fall, and the Renderer divides its own width by it: that is the same judgement
/// as a size class, made where the width is known, so the Host does not make it.
///
/// Saying both is saying the count, because a count leaves nothing for the width to
/// decide.
#[component]
pub fn LazyGrid(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    /// A fixed number of columns.
    #[props(default)]
    columns: Option<u32>,
    /// The narrowest a column may be, where the count is the Renderer's to work out.
    #[props(default)]
    min_column_width: Option<f32>,
    item_count: usize,
    #[props(default)] key_of: Option<Callback<usize, String>>,
    item: Callback<usize, Element>,
) -> Element {
    let mut range = use_signal(|| (0_usize, 0_usize));
    let (start, count) = range();
    let first = start.min(item_count);
    let last = first.saturating_add(count).min(item_count);
    rsx! {
        lazygrid {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            item_count: item_count as i64,
            columns: columns.map(i64::from),
            min_column_width: min_column_width.map(f64::from),
            onrangerequest: move |event: dioxus_core::Event<RangeRequest>| {
                let requested = event.data();
                range.set((requested.start(), requested.count()));
            },
            for index in first..last {
                {
                    let item_key = key_of
                        .map_or_else(|| index.to_string(), |key_of| key_of.call(index));
                    rsx! {
                        composebox { key: "{item_key}", item_key, {item.call(index)} }
                    }
                }
            }
        }
    }
}

/// A place files may be dropped.
///
/// A widget rather than a property on every container, because willingness has to be
/// absent by default and a handler cannot be: a listener is attached whether or not a
/// screen supplied one, so four containers each grew three records for saying nothing.
/// Being this widget is the willingness, and nothing else in the tree carries any of it.
///
/// Desktop only in effect. A phone has no notion of letting a file go over a window, so
/// the events never arrive there and the same screen compiles and runs unchanged.
#[component]
pub fn FileDropTarget(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] alignment: Option<Alignment>,
    /// Files have come over this node. Nothing about what they are: the platforms
    /// disagree about what is knowable before a drop, and a screen that only lights up
    /// does not need to know.
    #[props(default)]
    on_files_entered: EventHandler<()>,
    /// Files were let go here.
    #[props(default)]
    on_files_dropped: EventHandler<FileDrop>,
    children: Element,
) -> Element {
    rsx! {
        filedroptarget {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            alignment: opt_role(alignment),
            onfilesentered: move |_: dioxus_core::Event<()>| on_files_entered.call(()),
            onfilesdropped: move |event: dioxus_core::Event<FileDrop>| {
                on_files_dropped.call(event.data().as_ref().clone());
            },
            {children}
        }
    }
}

/// A small mark on something else that says how many, that there is something new, or one
/// short word.
///
/// With one child it is attached to that child: an icon, an avatar, a destination. With no
/// child it stands on its own, the count at the end of a list row. Compose calls the first
/// a `BadgedBox` and the second a `Badge`; here they are one widget, because whether there
/// is something to attach to is already said by the children.
///
/// `value` is a count and `text` is a short word. With neither it is a dot. Where it sits
/// on its child, what shape it is, and whether 120 is written out or shortened to `99+`
/// are the design system's, so there is no property for a corner or a ceiling.
///
/// The colour is a role and never a literal, and it defaults to the error role the way
/// every one of these systems draws an unread count. The content is read out as part of
/// the child it is attached to, and never on its own.
#[component]
pub fn Badge(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    /// How many. Leave it out for a dot or a word: no count is not the same as a count of
    /// nothing, and only the screen knows which one it means.
    #[props(default)]
    value: Option<u32>,
    /// One short word, shown where a count would be. A count wins where both are given.
    #[props(default)]
    text: Option<String>,
    /// The role the mark is filled with. The error role where it is not given.
    #[props(default)]
    color: Option<ColorRole>,
    #[props(default)] children: Element,
) -> Element {
    rsx! {
        badge {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            count: value.map(i64::from),
            text,
            color: opt_paint(color.map(Paint::Role)),
            {children}
        }
    }
}

/// A region whose text can be dragged over and copied as one selection.
///
/// Every `Text` inside it, however deep and however many, is part of the same selection,
/// so a whole message of paragraphs, list items and code can be picked up in one drag and
/// copied with a line break between each block. Text outside one cannot be selected at all,
/// which is what keeps button labels and destinations from being dragged over as though
/// the screen were a document.
///
/// Buttons, checkboxes and the other controls inside it are left out of the selection and
/// keep working as they always do.
///
/// Nothing about selecting or copying reaches this side. What is selected, the copy
/// shortcut, the context menu and the clipboard all belong to the Renderer, so there is no
/// event to handle and no way to read or set the selection from here.
#[component]
pub fn SelectionContainer(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    children: Element,
) -> Element {
    rsx! {
        selectioncontainer {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            {children}
        }
    }
}

/// A side pane and a body, with a divider between them the user drags to change the side
/// pane's width.
///
/// Exactly two children: the side pane first, the body second. The side pane sits at the
/// start of the reading direction, so it is on the right in a right to left locale.
///
/// **The drag is not reported while it happens.** The divider follows the pointer on the
/// Renderer's side, like a scroll position, and `on_change` is called once, when it is let
/// go, with the side pane's width in dp. The same happens once when the width was changed
/// from the keyboard, when the keys are released. Narrowing the window shrinks the side
/// pane without telling anyone, and widening it again brings back the width the user chose.
///
/// `value` is the width the side pane opens at and the way to change it from here. Leave it
/// out to open at the design system's own sidebar width. Write what `on_change` reports back
/// into the signal that feeds `value`, the way a slider's value is kept: otherwise sending
/// the same width again later is not a change and nothing moves.
///
/// `min` and `max` bound the drag, in dp; left out, the design system's bounds apply.
/// With `collapsible`, dragging past `min` folds the side pane away and `on_change` reports
/// `0.0`; `value: Some(0.0)` opens it folded; a non-zero value, a drag back out or the
/// keyboard unfolds it to the width it had before, which is reported once. A sidebar toggle
/// is a button that sends `0.0` or the last width, with `IconRole::Sidebar` on it.
///
/// In a narrow place, measured by the split pane's own width rather than the window's, the
/// design system may show one pane at a time. `selected_index` then says which: `0` for the
/// side pane, `1` for the body. Choosing something in the side pane is the application's
/// event, so it is the application that sends `1`. Going back (the platform's back gesture
/// or the back button the design system draws) shows the side pane at once and calls
/// `on_dismiss` once, and the application answers by sending `0` and clearing its selection.
///
/// `label` names the divider for a screen reader, which reads it with the current width as
/// an adjustable control. Left out, the Renderer uses the platform's own word for a sidebar.
#[component]
pub fn SplitPane(
    #[props(default)] weight: Option<f32>,
    #[props(default)] width: Option<f32>,
    #[props(default)] height: Option<f32>,
    #[props(default)] padding: Option<f32>,
    #[props(default)] padding_role: Option<SpaceRole>,
    #[props(default)] background: Option<Paint>,
    #[props(default)] shape_role: Option<ShapeRole>,
    #[props(default)] corner_radius: Option<f32>,
    #[props(default)] border_width: Option<f32>,
    #[props(default)] border_color: Option<Paint>,
    #[props(default)] elevation: Option<f32>,
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    /// The side pane's width in dp, or `0.0` to open it folded. Left out, the design
    /// system's sidebar width.
    #[props(default)]
    value: Option<f32>,
    /// The narrowest the side pane may be dragged, in dp.
    #[props(default)]
    min: Option<f32>,
    /// The widest the side pane may be dragged, in dp.
    #[props(default)]
    max: Option<f32>,
    /// Whether the side pane may be folded away.
    #[props(default)]
    collapsible: bool,
    /// Which pane shows when only one fits: `0` the side pane, `1` the body.
    #[props(default)]
    selected_index: usize,
    /// What a screen reader calls the divider.
    #[props(default)]
    label: Option<String>,
    /// The side pane's width once a drag or a key press has finished, or `0.0` once folded.
    #[props(default)]
    on_change: EventHandler<f32>,
    /// The user went back from the body to the side pane while one pane shows at a time.
    #[props(default)]
    on_dismiss: EventHandler<()>,
    children: Element,
) -> Element {
    rsx! {
        splitpane {
            weight: opt_dp(weight),
            width: opt_dp(width),
            height: opt_dp(height),
            padding: opt_dp(padding),
            padding_role: opt_role(padding_role),
            background: opt_paint(background),
            shape_role: opt_role(shape_role),
            corner_radius: opt_dp(corner_radius),
            border_width: opt_dp(border_width),
            border_color: opt_paint(border_color),
            elevation: opt_dp(elevation),
            fill_max_width,
            fill_max_height,
            value: value.map(f64::from),
            min: min.map(f64::from),
            max: max.map(f64::from),
            collapsible,
            selected_index: selected_index as i64,
            text: label,
            onchange: move |event: dioxus_core::Event<f64>| on_change.call(*event.data() as f32),
            ondismiss: move |_| on_dismiss.call(()),
            {children}
        }
    }
}

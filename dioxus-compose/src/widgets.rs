#![allow(non_snake_case)]

use dioxus_core::{Callback, Element, EventHandler};
use dioxus_core_macro::{Props, component, rsx};
use dioxus_hooks::use_signal;
use dioxus_signals::WritableExt as _;

use crate as dioxus_elements;
use crate::Key;
use crate::schema::{
    Alignment, Arrangement, ButtonVariant, Paint, SpaceRole, TextAlign, TextOverflow, TypeRole,
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

/// FR-13.8: a role that was not set is tag 0, which means "not sent". The Renderer
/// never sees a zero role, so it never has to guess what an unset role meant.
fn role(value: Option<impl Into<u16>>) -> i64 {
    value.map_or(0, |value| i64::from(value.into()))
}

fn dp(value: Option<f32>) -> f64 {
    f64::from(value.unwrap_or(0.0))
}

#[component]
pub fn Column(
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
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    #[props(default)] alignment: Option<Alignment>,
    children: Element,
) -> Element {
    rsx! {
        composebox {
            fill_max_width,
            fill_max_height,
            alignment: role(alignment),
            {children}
        }
    }
}

/// FR-13.6: the whole content with a vertical scroll attached. The scroll position is
/// the Renderer's (D5), so scrolling never reaches the Host.
#[component]
pub fn ScrollColumn(
    #[props(default)] fill_max_width: bool,
    #[props(default)] fill_max_height: bool,
    children: Element,
) -> Element {
    rsx! {
        scrollcolumn { fill_max_width, fill_max_height, {children} }
    }
}

/// FR-13.2: `type_role` alone takes the design system's size, weight, line height and
/// letter spacing. Each override replaces one axis and costs one `SetProp` (FR-4).
#[component]
pub fn Text(
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
    #[props(into, default)] placeholder: String,
    #[props(default = true)] enabled: bool,
    #[props(default)] multiline: bool,
    #[props(default)] on_value_change: EventHandler<String>,
    #[props(default)] on_submit: EventHandler<String>,
    #[props(default)] on_focus_lost: EventHandler<()>,
    #[props(default)] on_key_down: EventHandler<KeyEvent>,
) -> Element {
    rsx! {
        textfield {
            placeholder,
            enabled,
            multiline,
            onvaluechange: move |event| on_value_change.call((*event.data()).clone()),
            onsubmit: move |event| on_submit.call((*event.data()).clone()),
            onfocuslost: move |_| on_focus_lost.call(()),
            onkeydown: move |event| on_key_down.call((*event.data()).clone()),
        }
    }
}

/// FR-14.2: the variant is the seam the design system's component rule attaches to.
/// The same rsx draws differently per system, and that is correct behaviour.
#[component]
pub fn Button(
    #[props(into)] text: String,
    #[props(default = true)] enabled: bool,
    #[props(default)] variant: Option<ButtonVariant>,
    #[props(default)] on_click: EventHandler<()>,
) -> Element {
    rsx! {
        button {
            text,
            enabled,
            variant: role(variant),
            onclick: move |_| on_click.call(()),
        }
    }
}

#[component]
pub fn Spacer(#[props(default)] width: f32, #[props(default)] height: f32) -> Element {
    rsx! { spacer { width, height } }
}

/// FR-8: the visible item range the Renderer asks the Host to materialise.
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

/// A windowed list (FR-8). The Host declares `item_count` and a stable key per item, and
/// materialises **exactly** the range the Renderer last requested.
///
/// The read-ahead buffer belongs to the Renderer, which owns the scroll position (D5) and so
/// knows how far ahead to ask. Widening the range here would break the Renderer's placement:
/// `start` is the global index of the first child it receives, and that is what lets it draw
/// a real Compose `LazyColumn` of `item_count` items. The data stays in the Host, so
/// scrolling back re-materialises an identical subtree.
#[component]
pub fn LazyColumn(
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

/// An image drawn from an asset the Host registered with `Host::register_asset`.
///
/// Only the handle is a property. The bytes reached the Renderer once, through their own
/// command, and the Renderer owns the decoded result, so a frame that redraws this image
/// moves four bytes.
#[component]
pub fn Image(asset_id: u32) -> Element {
    rsx! { image { asset_id: i64::from(asset_id) } }
}

/// An icon drawn from an asset registered with `AssetKind::VectorIcon`, which names a role
/// rather than a picture.
///
/// The tint is a `Paint`, so `Paint::Role(..)` lets the design system choose the colour.
#[component]
pub fn Icon(asset_id: u32, #[props(default)] color: Option<Paint>) -> Element {
    rsx! {
        icon {
            asset_id: i64::from(asset_id),
            color: color.map_or(0, |paint| paint.to_bits() as i64),
        }
    }
}

/// A date chosen by the user, as whole days since 1970-01-01.
///
/// The value is an integer and never a formatted string, because the calendar, the time
/// zone, the first day of the week and the way the date is written belong to the Renderer.
/// The moment a format string crossed the boundary, "follows the platform" would stop being
/// true: the Host would be deciding what the user sees.
///
/// There is no property for how the date is picked either. Material shows a calendar grid,
/// Cupertino shows a wheel and Fluent shows a calendar flyout, and those are three ways of
/// operating the same widget, not three skins.
#[component]
pub fn DatePicker(
    /// Whole days since 1970-01-01.
    value: i64,
    #[props(default)] min: Option<i64>,
    #[props(default)] max: Option<i64>,
    #[props(default = true)] enabled: bool,
    #[props(default)] on_change: EventHandler<i64>,
) -> Element {
    rsx! {
        datepicker {
            value,
            min_value: min.unwrap_or(i64::MIN),
            max_value: max.unwrap_or(i64::MAX),
            enabled,
            onpick: move |event: dioxus_core::Event<i64>| on_change.call(*event.data()),
        }
    }
}

/// A time of day chosen by the user, as whole minutes since midnight.
///
/// Whether the user sees a 12 hour or a 24 hour clock is the platform's setting, read by
/// the Renderer. The Host neither sends nor receives that choice.
#[component]
pub fn TimePicker(
    /// Whole minutes since midnight, 0 to 1439.
    value: u32,
    #[props(default)] min: Option<u32>,
    #[props(default)] max: Option<u32>,
    #[props(default = true)] enabled: bool,
    #[props(default)] on_change: EventHandler<u32>,
) -> Element {
    rsx! {
        timepicker {
            value: i64::from(value),
            min_value: i64::from(min.unwrap_or(0)),
            max_value: i64::from(max.unwrap_or(MINUTES_PER_DAY - 1)),
            enabled,
            onpick: move |event: dioxus_core::Event<i64>| {
                on_change.call(u32::try_from(*event.data()).unwrap_or(0))
            },
        }
    }
}

const MINUTES_PER_DAY: u32 = 24 * 60;

/// One choice out of a list. The children are the options, in order, and the value is the
/// index of the chosen one, or `None` for nothing chosen yet.
///
/// Material opens a menu under the field, Cupertino turns a wheel, Fluent opens a combo box
/// flyout. The widget says none of that.
#[component]
pub fn Dropdown(
    #[props(default)] selected: Option<usize>,
    #[props(default = true)] enabled: bool,
    #[props(default)] on_change: EventHandler<usize>,
    children: Element,
) -> Element {
    rsx! {
        dropdown {
            value: selected.map_or(NOTHING_SELECTED, |index| index as i64),
            enabled,
            onpick: move |event: dioxus_core::Event<i64>| {
                if let Ok(index) = usize::try_from(*event.data()) {
                    on_change.call(index);
                }
            },
            {children}
        }
    }
}

/// A dropdown with no choice made yet. Index 0 is a real choice, so "none" needs a value
/// that cannot be an index.
const NOTHING_SELECTED: i64 = -1;

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

/// A role that was not set is tag 0, which means "not sent". The Renderer
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

/// The whole content with a vertical scroll attached. The scroll position is the
/// Renderer's, like focus and animation state, so scrolling never reaches the Host.
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

/// `type_role` alone takes the design system's size, weight, line height and letter
/// spacing. Each override replaces one axis and costs one `SetProp`, so changing the font
/// size does not resend the rest of the text's styling.
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

/// The variant is the seam the design system's component rule attaches to.
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

/// A grouped container. What a card looks like, its background, corner and resting
/// elevation, is the design system's decision, so the widget carries no appearance of its
/// own. `Modifier::Elevation` overrides the resting height when the Host has a reason to.
#[component]
pub fn Card(children: Element) -> Element {
    rsx! {
        card { {children} }
    }
}

/// A plain background-and-elevation container. Use it where a `Card`'s grouping meaning
/// would be wrong and only the surface is wanted.
#[component]
pub fn Surface(children: Element) -> Element {
    rsx! {
        surface { {children} }
    }
}

/// A modal. `open` seeds the Renderer's own open state and carries changes that came from
/// somewhere other than the Renderer; the Renderer runs the enter and exit itself so the
/// animation never round trips through the Host. `on_dismiss` fires when the user asks to
/// close it, and the Host decides whether to honour that by setting `open` to false.
#[component]
pub fn Dialog(
    #[props(default)] open: bool,
    #[props(default)] on_dismiss: EventHandler<()>,
    children: Element,
) -> Element {
    rsx! {
        dialog {
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
    #[props(default)] expanded: bool,
    #[props(default)] on_dismiss: EventHandler<()>,
    anchor: Element,
    children: Element,
) -> Element {
    rsx! {
        menu {
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
pub fn Tabs(#[props(default)] selected_index: usize, children: Element) -> Element {
    rsx! {
        tabs { selected_index: selected_index as i64, {children} }
    }
}

/// The bar across the top of a screen. Its children are its content, left to right. How the
/// bar is sized, spaced and separated from what is below it is the design system's rule.
#[component]
pub fn TopAppBar(children: Element) -> Element {
    rsx! {
        topappbar { {children} }
    }
}

/// The horizontal axis of the same windowing protocol `LazyColumn` uses, with the same
/// contract: the Host materialises exactly the range the Renderer last asked for, and the
/// read-ahead buffer belongs to the Renderer because the scroll position does.
#[component]
pub fn LazyRow(
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
pub fn Tooltip(#[props(into)] text: String, children: Element) -> Element {
    rsx! {
        tooltip { text, {children} }
    }
}

/// A two-state box. The widget is controlled: it draws exactly what `checked` says and
/// reports a press, so the value the Host holds and the box on screen can never disagree.
/// What the mark looks like, and whether pressing it ripples or dims, is the design
/// system's rule.
#[component]
pub fn Checkbox(
    #[props(default)] checked: bool,
    #[props(default = true)] enabled: bool,
    #[props(default)] on_click: EventHandler<()>,
) -> Element {
    rsx! {
        checkbox {
            checked,
            enabled,
            onclick: move |_| on_click.call(()),
        }
    }
}

/// One choice out of several. Which siblings it excludes is the Host's business, so the
/// widget carries only whether this one is the chosen one.
///
/// `selected` is the Compose name for that state, and it is what the rsx attribute is
/// called. On the wire it is the same "is this on" boolean the other two toggles send.
#[component]
pub fn RadioButton(
    #[props(default)] selected: bool,
    #[props(default = true)] enabled: bool,
    #[props(default)] on_click: EventHandler<()>,
) -> Element {
    rsx! {
        radiobutton {
            checked: selected,
            enabled,
            onclick: move |_| on_click.call(()),
        }
    }
}

/// An on/off control. Same contract as `Checkbox`: the Host owns the value and the design
/// system owns the track, the thumb and the way the change is animated.
#[component]
pub fn Switch(
    #[props(default)] checked: bool,
    #[props(default = true)] enabled: bool,
    #[props(default)] on_click: EventHandler<()>,
) -> Element {
    rsx! {
        switch {
            checked,
            enabled,
            onclick: move |_| on_click.call(()),
        }
    }
}

/// A value picked from a range.
///
/// Unlike the toggles this one needs a payload, because the position a drag lands on is a
/// continuous value the Host cannot work out from what it already sent. The in-flight
/// position stays in the Renderer, so following a finger costs no boundary calls, and
/// `value` seeds that position and moves it when the change came from elsewhere.
///
/// `steps` is the number of stops between the two ends; zero leaves the slider continuous.
#[component]
pub fn Slider(
    #[props(default)] value: f32,
    #[props(default = 0.0)] min_value: f32,
    #[props(default = 1.0)] max_value: f32,
    #[props(default)] steps: u32,
    #[props(default = true)] enabled: bool,
    #[props(default)] on_value_change: EventHandler<f32>,
) -> Element {
    rsx! {
        slider {
            value: f64::from(value),
            min_value: f64::from(min_value),
            max_value: f64::from(max_value),
            steps: i64::from(steps),
            enabled,
            onvaluechanged: move |event| on_value_change.call(*event.data()),
        }
    }
}

/// Work in progress. `determinate` says whether `value` means anything, and `circular`
/// picks between the two forms every one of the design systems has.
///
/// There is no speed, no easing and no track colour here: how an indeterminate indicator
/// travels is motion, and motion is the design system's rule.
#[component]
pub fn ProgressIndicator(
    #[props(default)] value: f32,
    #[props(default = true)] determinate: bool,
    #[props(default)] circular: bool,
) -> Element {
    rsx! {
        progressindicator {
            value: f64::from(value),
            determinate,
            circular,
        }
    }
}

/// A line between two things. The axis is the only decision the Host makes: the thickness,
/// the colour and the inset all come from the design system.
#[component]
pub fn Divider(#[props(default)] vertical: bool) -> Element {
    rsx! {
        divider { vertical }
    }
}

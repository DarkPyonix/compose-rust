//! One composable per widget in the schema.
//!
//! Each is a function with the widget's Compose name that takes what the widget cannot do
//! without, and returns a description whose methods set the rest. The description is
//! composed when the statement it is written in ends, or at once by `content` for a
//! widget that has children.
//!
//! Every attribute list below is the `rsx!` widget's, in its order, with its defaults. A
//! difference would be a widget that draws differently depending on which API wrote it.

#![allow(non_snake_case)]

use super::{
    Attrs, Modifier, Mods, dp_or_zero, emit, optional_text, paint_or_zero, push_modifiers,
    role_or_zero, widget_key,
};
use crate::drawing::DrawList;
use crate::runtime::composer::{EventCallback, OwnedAttr};
use crate::schema::{
    Alignment, Arrangement, ButtonVariant, ColorRole, IconRole, Paint, SlotRole, SpaceRole,
    TextAlign, TextOverflow, TypeRole, WidgetKind,
};
use crate::spans::TextSpans;
use crate::{FileDrop, KeyEvent};
use std::cell::RefCell;
use std::rc::Rc;

/// The empty handler every listener has until one is given. The `rsx!` widgets write
/// their listeners whether or not a handler was supplied, so these do too.
fn unit(handler: Option<Box<dyn FnMut()>>) -> EventCallback {
    match handler {
        Some(handler) => EventCallback::Unit(Rc::new(RefCell::new(handler))),
        None => EventCallback::Unit(Rc::new(RefCell::new(|| {}))),
    }
}

fn text_handler(handler: Option<Box<dyn FnMut(String)>>) -> EventCallback {
    match handler {
        Some(handler) => EventCallback::Text(Rc::new(RefCell::new(handler))),
        None => EventCallback::Text(Rc::new(RefCell::new(|_: String| {}))),
    }
}

fn value_handler(handler: Option<Box<dyn FnMut(f64)>>) -> EventCallback {
    match handler {
        Some(handler) => EventCallback::Value(Rc::new(RefCell::new(handler))),
        None => EventCallback::Value(Rc::new(RefCell::new(|_: f64| {}))),
    }
}

/// Composes the description when the statement it was written in ends.
macro_rules! composed_on_drop {
    ($name:ident $(<$lifetime:lifetime>)?) => {
        impl$(<$lifetime>)? Drop for $name$(<$lifetime>)? {
            fn drop(&mut self) {
                // A panic is already unwinding the composition, and composing on the way
                // out would only risk a second one.
                if !self.done && !std::thread::panicking() {
                    self.done = true;
                    self.compose(|| {});
                }
            }
        }

        impl$(<$lifetime>)? $name$(<$lifetime>)? {
            /// What sits around it. Compose's `modifier` parameter.
            pub fn modifier(mut self, modifier: Modifier) -> Self {
                self.modifier = modifier;
                self
            }
        }
    };
}

/// A container's `content`: composes it now, with these children.
macro_rules! container {
    ($name:ident $(<$lifetime:lifetime>)?) => {
        composed_on_drop!($name $(<$lifetime>)?);

        impl$(<$lifetime>)? $name$(<$lifetime>)? {
            /// Composes this widget with `content` as its children.
            pub fn content(mut self, content: impl FnOnce()) {
                self.done = true;
                self.compose(content);
            }
        }
    };
}

// ----- layout ---------------------------------------------------------------------------

/// The four properties a linear layout takes.
#[derive(Default)]
struct Linear {
    arrangement: Option<Arrangement>,
    spacing: Option<f32>,
    space_role: Option<SpaceRole>,
    alignment: Option<Alignment>,
}

impl Linear {
    fn push(&self, attrs: &mut Attrs) {
        attrs.push(("arrangement", role_or_zero(self.arrangement)));
        attrs.push(("spacing", dp_or_zero(self.spacing)));
        attrs.push(("space_role", role_or_zero(self.space_role)));
        attrs.push(("alignment", role_or_zero(self.alignment)));
    }
}

macro_rules! linear {
    ($name:ident, $widget:ident, $key:literal) => {
        #[doc = concat!("A `", stringify!($widget), "` and its builder.")]
        pub struct $name {
            modifier: Modifier,
            linear: Linear,
            done: bool,
        }

        #[doc = concat!("Lays its children out one after another. Compose's `", stringify!($widget), "`.")]
        pub fn $widget() -> $name {
            $name {
                modifier: Modifier::new(),
                linear: Linear::default(),
                done: false,
            }
        }

        impl $name {
            /// How the children share the main axis.
            pub fn arrangement(mut self, arrangement: Arrangement) -> Self {
                self.linear.arrangement = Some(arrangement);
                self
            }

            /// A literal gap between children, in dp.
            pub fn spacing(mut self, dp: f32) -> Self {
                self.linear.spacing = Some(dp);
                self
            }

            /// A gap from the design system's spacing ladder.
            pub fn space_role(mut self, role: SpaceRole) -> Self {
                self.linear.space_role = Some(role);
                self
            }

            /// Where the children sit on the cross axis.
            pub fn alignment(mut self, alignment: Alignment) -> Self {
                self.linear.alignment = Some(alignment);
                self
            }

            fn compose(&mut self, content: impl FnOnce()) {
                let modifier = self.modifier;
                let linear = std::mem::take(&mut self.linear);
                emit(
                    widget_key($key),
                    WidgetKind::$widget,
                    |attrs| {
                        push_modifiers(&modifier, Mods::Container, attrs);
                        linear.push(attrs);
                    },
                    Vec::new(),
                    content,
                );
            }
        }

        container!($name);
    };
}

linear!(ColumnScope, Column, "compose_rust::ui::Column");
linear!(RowScope, Row, "compose_rust::ui::Row");

/// A `Box` and its builder. Named apart from `std::boxed::Box`, which a glob import of
/// this module would otherwise hide.
pub struct ComposeBox {
    modifier: Modifier,
    alignment: Option<Alignment>,
    done: bool,
}

/// Stacks its children on top of each other. Compose's `Box`.
pub fn Box() -> ComposeBox {
    ComposeBox {
        modifier: Modifier::new(),
        alignment: None,
        done: false,
    }
}

impl ComposeBox {
    /// Where the children sit inside it.
    pub fn alignment(mut self, alignment: Alignment) -> Self {
        self.alignment = Some(alignment);
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let (modifier, alignment) = (self.modifier, self.alignment);
        emit(
            widget_key("compose_rust::ui::Box"),
            WidgetKind::Box,
            |attrs| {
                push_modifiers(&modifier, Mods::Container, attrs);
                attrs.push(("alignment", role_or_zero(alignment)));
            },
            Vec::new(),
            content,
        );
    }
}

container!(ComposeBox);

/// A plain container with the standard Modifiers and nothing of its own.
macro_rules! plain_container {
    ($name:ident, $widget:ident, $mods:expr, $key:literal, $doc:literal) => {
        #[doc = concat!("A `", stringify!($widget), "` and its builder.")]
        pub struct $name {
            modifier: Modifier,
            done: bool,
        }

        #[doc = $doc]
        pub fn $widget() -> $name {
            $name {
                modifier: Modifier::new(),
                done: false,
            }
        }

        impl $name {
            fn compose(&mut self, content: impl FnOnce()) {
                let modifier = self.modifier;
                emit(
                    widget_key($key),
                    WidgetKind::$widget,
                    |attrs| push_modifiers(&modifier, $mods, attrs),
                    Vec::new(),
                    content,
                );
            }
        }

        container!($name);
    };
}

plain_container!(
    ScrollColumnScope,
    ScrollColumn,
    Mods::Standard,
    "compose_rust::ui::ScrollColumn",
    "All of its content, with a vertical scroll the Renderer owns."
);
plain_container!(
    ScrollRowScope,
    ScrollRow,
    Mods::Standard,
    "compose_rust::ui::ScrollRow",
    "All of its content, with a horizontal scroll the Renderer owns."
);
plain_container!(
    CardScope,
    Card,
    Mods::Container,
    "compose_rust::ui::Card",
    "A grouped container. What it looks like is the design system's."
);
plain_container!(
    SurfaceScope,
    Surface,
    Mods::Container,
    "compose_rust::ui::Surface",
    "A plain background and elevation, without a card's meaning."
);
plain_container!(
    SelectionContainerScope,
    SelectionContainer,
    Mods::Standard,
    "compose_rust::ui::SelectionContainer",
    "Every `Text` inside can be selected and copied as one selection."
);
plain_container!(
    SpacerScope,
    Spacer,
    Mods::Standard,
    "compose_rust::ui::Spacer",
    "Empty space, sized by its Modifier."
);

// ----- text -----------------------------------------------------------------------------

/// A `Text` and its builder.
pub struct TextScope {
    modifier: Modifier,
    text: String,
    spans: TextSpans,
    type_role: Option<TypeRole>,
    font_size: Option<f32>,
    font_weight: Option<u16>,
    line_height: Option<f32>,
    letter_spacing: Option<f32>,
    color: Option<Paint>,
    text_align: Option<TextAlign>,
    max_lines: Option<u32>,
    overflow: Option<TextOverflow>,
    done: bool,
}

/// Text. Compose's `Text`.
///
/// `type_role` alone takes the design system's size, weight, line height and letter
/// spacing; each other style method overrides one axis of it.
pub fn Text(text: impl Into<String>) -> TextScope {
    TextScope {
        modifier: Modifier::new(),
        text: text.into(),
        spans: TextSpans::default(),
        type_role: None,
        font_size: None,
        font_weight: None,
        line_height: None,
        letter_spacing: None,
        color: None,
        text_align: None,
        max_lines: None,
        overflow: None,
        done: false,
    }
}

impl TextScope {
    /// Runs of different treatment inside the text: a bold phrase, a link, some code.
    pub fn spans(mut self, spans: TextSpans) -> Self {
        self.spans = spans;
        self
    }

    pub fn type_role(mut self, role: TypeRole) -> Self {
        self.type_role = Some(role);
        self
    }

    pub fn font_size(mut self, sp: f32) -> Self {
        self.font_size = Some(sp);
        self
    }

    pub fn font_weight(mut self, weight: u16) -> Self {
        self.font_weight = Some(weight);
        self
    }

    pub fn line_height(mut self, sp: f32) -> Self {
        self.line_height = Some(sp);
        self
    }

    pub fn letter_spacing(mut self, sp: f32) -> Self {
        self.letter_spacing = Some(sp);
        self
    }

    pub fn color(mut self, paint: Paint) -> Self {
        self.color = Some(paint);
        self
    }

    pub fn text_align(mut self, align: TextAlign) -> Self {
        self.text_align = Some(align);
        self
    }

    pub fn max_lines(mut self, lines: u32) -> Self {
        self.max_lines = Some(lines);
        self
    }

    pub fn overflow(mut self, overflow: TextOverflow) -> Self {
        self.overflow = Some(overflow);
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let text = std::mem::take(&mut self.text);
        let spans = std::mem::take(&mut self.spans);
        let (type_role, font_size, font_weight, line_height, letter_spacing) = (
            self.type_role,
            self.font_size,
            self.font_weight,
            self.line_height,
            self.letter_spacing,
        );
        let (color, text_align, max_lines, overflow) =
            (self.color, self.text_align, self.max_lines, self.overflow);
        emit(
            widget_key("compose_rust::ui::Text"),
            WidgetKind::Text,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                // Left out entirely when there are none, as the rsx widget leaves it out.
                attrs.push((
                    "spans",
                    if spans.is_empty() {
                        OwnedAttr::None
                    } else {
                        OwnedAttr::Spans(spans)
                    },
                ));
                attrs.push(("text", OwnedAttr::Text(text)));
                attrs.push(("type_role", role_or_zero(type_role)));
                attrs.push(("font_size", dp_or_zero(font_size)));
                attrs.push((
                    "font_weight",
                    OwnedAttr::Int(i64::from(font_weight.unwrap_or(0))),
                ));
                attrs.push(("line_height", dp_or_zero(line_height)));
                attrs.push(("letter_spacing", dp_or_zero(letter_spacing)));
                attrs.push(("color", paint_or_zero(color)));
                attrs.push(("text_align", role_or_zero(text_align)));
                attrs.push((
                    "max_lines",
                    OwnedAttr::Int(i64::from(max_lines.unwrap_or(0))),
                ));
                attrs.push(("overflow", role_or_zero(overflow)));
            },
            Vec::new(),
            content,
        );
    }
}

composed_on_drop!(TextScope);

/// A `TextField` and its builder.
pub struct TextFieldScope {
    modifier: Modifier,
    placeholder: String,
    enabled: bool,
    multiline: bool,
    type_role: Option<TypeRole>,
    on_value_change: Option<Box<dyn FnMut(String)>>,
    on_submit: Option<Box<dyn FnMut(String)>>,
    on_focus_lost: Option<Box<dyn FnMut()>>,
    on_key_down: Option<Box<dyn FnMut(KeyEvent)>>,
    done: bool,
}

/// An uncontrolled text field. Compose's `TextField`, with the value kept where the
/// platform's text input keeps it: the Renderer reports each change and the Host never
/// writes the value back while the user types.
pub fn TextField() -> TextFieldScope {
    TextFieldScope {
        modifier: Modifier::new(),
        placeholder: String::new(),
        enabled: true,
        multiline: false,
        type_role: None,
        on_value_change: None,
        on_submit: None,
        on_focus_lost: None,
        on_key_down: None,
        done: false,
    }
}

impl TextFieldScope {
    pub fn placeholder(mut self, placeholder: impl Into<String>) -> Self {
        self.placeholder = placeholder.into();
        self
    }

    pub fn enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    pub fn multiline(mut self, multiline: bool) -> Self {
        self.multiline = multiline;
        self
    }

    /// The rung of the type ladder the field's own text is set in.
    pub fn type_role(mut self, role: TypeRole) -> Self {
        self.type_role = Some(role);
        self
    }

    pub fn on_value_change(mut self, handler: impl FnMut(String) + 'static) -> Self {
        self.on_value_change = Some(std::boxed::Box::new(handler));
        self
    }

    pub fn on_submit(mut self, handler: impl FnMut(String) + 'static) -> Self {
        self.on_submit = Some(std::boxed::Box::new(handler));
        self
    }

    pub fn on_focus_lost(mut self, handler: impl FnMut() + 'static) -> Self {
        self.on_focus_lost = Some(std::boxed::Box::new(handler));
        self
    }

    /// Sees each key the field is pressed with; calling `consume` on the event keeps the
    /// field from acting on it.
    pub fn on_key_down(mut self, handler: impl FnMut(KeyEvent) + 'static) -> Self {
        self.on_key_down = Some(std::boxed::Box::new(handler));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let placeholder = std::mem::take(&mut self.placeholder);
        let (enabled, multiline, type_role) = (self.enabled, self.multiline, self.type_role);
        let key_down: EventCallback = match self.on_key_down.take() {
            Some(handler) => EventCallback::Key(Rc::new(RefCell::new(handler))),
            None => EventCallback::Key(Rc::new(RefCell::new(|_: KeyEvent| {}))),
        };
        let listeners = vec![
            ("onvaluechange", text_handler(self.on_value_change.take())),
            ("onsubmit", text_handler(self.on_submit.take())),
            ("onfocuslost", unit(self.on_focus_lost.take())),
            ("onkeydown", key_down),
        ];
        emit(
            widget_key("compose_rust::ui::TextField"),
            WidgetKind::TextField,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("placeholder", OwnedAttr::Text(placeholder)));
                attrs.push(("enabled", OwnedAttr::Bool(enabled)));
                attrs.push(("multiline", OwnedAttr::Bool(multiline)));
                attrs.push(("type_role", role_or_zero(type_role)));
            },
            listeners,
            content,
        );
    }
}

composed_on_drop!(TextFieldScope);

// ----- buttons and controls ---------------------------------------------------------------

/// A `Button` and its builder.
pub struct ButtonScope {
    modifier: Modifier,
    text: String,
    icon: Option<IconRole>,
    enabled: bool,
    variant: Option<ButtonVariant>,
    color: Option<Paint>,
    on_click: Option<Box<dyn FnMut()>>,
    done: bool,
}

/// A button with a label. Compose's `Button`; the variant is the design system's seam,
/// so `ButtonVariant::Text` is Compose's `TextButton` and `Outlined` its `OutlinedButton`.
///
/// An empty label with an icon is an icon button, named for assistive technology by its
/// icon's meaning.
pub fn Button(text: impl Into<String>) -> ButtonScope {
    ButtonScope {
        modifier: Modifier::new(),
        text: text.into(),
        icon: None,
        enabled: true,
        variant: None,
        color: None,
        on_click: None,
        done: false,
    }
}

impl ButtonScope {
    /// The meaning of the glyph on it, never a picture.
    pub fn icon(mut self, icon: IconRole) -> Self {
        self.icon = Some(icon);
        self
    }

    pub fn enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    pub fn variant(mut self, variant: ButtonVariant) -> Self {
        self.variant = Some(variant);
        self
    }

    /// The label's colour, for the rare button whose meaning is not its variant's.
    pub fn color(mut self, paint: Paint) -> Self {
        self.color = Some(paint);
        self
    }

    /// The same, when there may be no colour of its own.
    pub fn color_if(mut self, paint: Option<Paint>) -> Self {
        self.color = paint;
        self
    }

    pub fn on_click(mut self, handler: impl FnMut() + 'static) -> Self {
        self.on_click = Some(std::boxed::Box::new(handler));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let text = std::mem::take(&mut self.text);
        let (icon, enabled, variant, color) = (self.icon, self.enabled, self.variant, self.color);
        let listeners = vec![("onclick", unit(self.on_click.take()))];
        emit(
            widget_key("compose_rust::ui::Button"),
            WidgetKind::Button,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("text", OwnedAttr::Text(text)));
                attrs.push(("icon", super::role(icon)));
                attrs.push(("enabled", OwnedAttr::Bool(enabled)));
                attrs.push(("variant", role_or_zero(variant)));
                attrs.push(("color", super::paint(color)));
            },
            listeners,
            content,
        );
    }
}

composed_on_drop!(ButtonScope);

/// A two-state control, for Checkbox, RadioButton and Switch alike: the Host owns the
/// value, the control draws exactly what it is told and reports what the user asked for.
macro_rules! toggle {
    ($name:ident, $widget:ident, $setter:ident, $key:literal, $doc:literal) => {
        #[doc = concat!("A `", stringify!($widget), "` and its builder.")]
        pub struct $name {
            modifier: Modifier,
            checked: bool,
            enabled: bool,
            on_change: Option<Box<dyn FnMut(bool)>>,
            done: bool,
        }

        #[doc = $doc]
        pub fn $widget($setter: bool) -> $name {
            $name {
                modifier: Modifier::new(),
                checked: $setter,
                enabled: true,
                on_change: None,
                done: false,
            }
        }

        impl $name {
            pub fn enabled(mut self, enabled: bool) -> Self {
                self.enabled = enabled;
                self
            }

            /// Called with the state the user asked for. The control changes only when the
            /// value passed in does.
            pub fn on_change(mut self, handler: impl FnMut(bool) + 'static) -> Self {
                self.on_change = Some(std::boxed::Box::new(handler));
                self
            }

            fn compose(&mut self, content: impl FnOnce()) {
                let modifier = self.modifier;
                let (checked, enabled) = (self.checked, self.enabled);
                let handler: EventCallback = match self.on_change.take() {
                    Some(mut handler) => EventCallback::Value(Rc::new(RefCell::new(
                        // Off is 0.0 and on is anything else, so a Renderer that reports a
                        // half-finished transition still reads as on.
                        move |value: f64| handler(value != 0.0),
                    ))),
                    None => value_handler(None),
                };
                emit(
                    widget_key($key),
                    WidgetKind::$widget,
                    |attrs| {
                        push_modifiers(&modifier, Mods::Standard, attrs);
                        attrs.push(("checked", OwnedAttr::Bool(checked)));
                        attrs.push(("enabled", OwnedAttr::Bool(enabled)));
                    },
                    vec![("onchange", handler)],
                    content,
                );
            }
        }

        composed_on_drop!($name);
    };
}

toggle!(
    CheckboxScope,
    Checkbox,
    checked,
    "compose_rust::ui::Checkbox",
    "A box that is ticked or not. Compose's `Checkbox(checked, onCheckedChange)`."
);
toggle!(
    RadioButtonScope,
    RadioButton,
    selected,
    "compose_rust::ui::RadioButton",
    "One choice out of several. Compose's `RadioButton(selected, onClick)`."
);
toggle!(
    SwitchScope,
    Switch,
    checked,
    "compose_rust::ui::Switch",
    "An on and off control. Compose's `Switch(checked, onCheckedChange)`."
);

/// A `Slider` and its builder.
pub struct SliderScope {
    modifier: Modifier,
    color: Option<Paint>,
    value: f32,
    min: f32,
    max: f32,
    steps: u32,
    enabled: bool,
    on_change: Option<Box<dyn FnMut(f32)>>,
    done: bool,
}

/// A value picked from a range. Compose's `Slider`. The drag is the Renderer's; `value`
/// seeds it and moves it when the change came from elsewhere.
pub fn Slider(value: f32) -> SliderScope {
    SliderScope {
        modifier: Modifier::new(),
        color: None,
        value,
        min: 0.0,
        max: 1.0,
        steps: 0,
        enabled: true,
        on_change: None,
        done: false,
    }
}

impl SliderScope {
    /// The two ends. Compose's `valueRange`.
    pub fn range(mut self, min: f32, max: f32) -> Self {
        self.min = min;
        self.max = max;
        self
    }

    /// Stops between the ends; zero is continuous.
    pub fn steps(mut self, steps: u32) -> Self {
        self.steps = steps;
        self
    }

    pub fn enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    /// The filled part of the track and the thumb.
    pub fn color(mut self, paint: Paint) -> Self {
        self.color = Some(paint);
        self
    }

    pub fn on_change(mut self, handler: impl FnMut(f32) + 'static) -> Self {
        self.on_change = Some(std::boxed::Box::new(handler));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let (color, value, min, max, steps, enabled) = (
            self.color,
            self.value,
            self.min,
            self.max,
            self.steps,
            self.enabled,
        );
        let handler = match self.on_change.take() {
            Some(mut handler) => EventCallback::Value(Rc::new(RefCell::new(move |value: f64| {
                handler(value as f32)
            }))),
            None => value_handler(None),
        };
        emit(
            widget_key("compose_rust::ui::Slider"),
            WidgetKind::Slider,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("color", super::paint(color)));
                attrs.push(("value", OwnedAttr::Float(f64::from(value))));
                attrs.push(("min", OwnedAttr::Float(f64::from(min))));
                attrs.push(("max", OwnedAttr::Float(f64::from(max))));
                attrs.push(("steps", OwnedAttr::Int(i64::from(steps))));
                attrs.push(("enabled", OwnedAttr::Bool(enabled)));
            },
            vec![("onchange", handler)],
            content,
        );
    }
}

composed_on_drop!(SliderScope);

/// A `ProgressIndicator` and its builder.
pub struct ProgressIndicatorScope {
    modifier: Modifier,
    value: f32,
    determinate: bool,
    circular: bool,
    done: bool,
}

/// How far along something is, as a fraction. Compose's `LinearProgressIndicator(progress)`.
pub fn ProgressIndicator(value: f32) -> ProgressIndicatorScope {
    ProgressIndicatorScope {
        modifier: Modifier::new(),
        value,
        determinate: true,
        circular: false,
        done: false,
    }
}

/// Work in progress of no known length.
pub fn IndeterminateProgressIndicator() -> ProgressIndicatorScope {
    ProgressIndicator(0.0).determinate(false)
}

impl ProgressIndicatorScope {
    pub fn determinate(mut self, determinate: bool) -> Self {
        self.determinate = determinate;
        self
    }

    /// The circular form rather than the linear one.
    pub fn circular(mut self, circular: bool) -> Self {
        self.circular = circular;
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let (value, determinate, circular) = (self.value, self.determinate, self.circular);
        emit(
            widget_key("compose_rust::ui::ProgressIndicator"),
            WidgetKind::ProgressIndicator,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("value", OwnedAttr::Float(f64::from(value))));
                attrs.push(("determinate", OwnedAttr::Bool(determinate)));
                attrs.push(("circular", OwnedAttr::Bool(circular)));
            },
            Vec::new(),
            content,
        );
    }
}

composed_on_drop!(ProgressIndicatorScope);

/// A `Divider` and its builder.
pub struct DividerScope {
    modifier: Modifier,
    vertical: bool,
    done: bool,
}

/// A line between two things. Compose's `HorizontalDivider`; `vertical` makes it the other.
pub fn Divider() -> DividerScope {
    DividerScope {
        modifier: Modifier::new(),
        vertical: false,
        done: false,
    }
}

/// A hairline between two rows of a grouped list, in the design system's quiet edge or
/// the given colour. The same node as `Divider().modifier(Modifier.background(color))`.
pub fn Separator(color: Option<Paint>) -> DividerScope {
    Divider().modifier(Modifier::new().background_if(color))
}

impl DividerScope {
    pub fn vertical(mut self, vertical: bool) -> Self {
        self.vertical = vertical;
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let vertical = self.vertical;
        emit(
            widget_key("compose_rust::ui::Divider"),
            WidgetKind::Divider,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("vertical", OwnedAttr::Bool(vertical)));
            },
            Vec::new(),
            content,
        );
    }
}

composed_on_drop!(DividerScope);

// ----- pictures -------------------------------------------------------------------------

/// An `Image` or an `Icon` and its builder.
pub struct AssetScope {
    modifier: Modifier,
    widget: WidgetKind,
    asset_id: u32,
    color: Option<Paint>,
    done: bool,
}

/// A registered asset drawn as a picture. Compose's `Image`.
pub fn Image(asset_id: u32) -> AssetScope {
    AssetScope {
        modifier: Modifier::new(),
        widget: WidgetKind::Image,
        asset_id,
        color: None,
        done: false,
    }
}

/// A registered icon, tinted. Compose's `Icon`.
pub fn Icon(asset_id: u32) -> AssetScope {
    AssetScope {
        modifier: Modifier::new(),
        widget: WidgetKind::Icon,
        asset_id,
        color: None,
        done: false,
    }
}

impl AssetScope {
    /// The tint. An icon only; an image ignores it, as the rsx widget has no such property.
    pub fn color(mut self, paint: Paint) -> Self {
        self.color = Some(paint);
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let (widget, asset_id, color) = (self.widget, self.asset_id, self.color);
        let key = if widget == WidgetKind::Icon {
            widget_key("compose_rust::ui::Icon")
        } else {
            widget_key("compose_rust::ui::Image")
        };
        emit(
            key,
            widget,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("asset", OwnedAttr::Int(i64::from(asset_id))));
                if widget == WidgetKind::Icon {
                    attrs.push(("color", paint_or_zero(color)));
                }
            },
            Vec::new(),
            content,
        );
    }
}

composed_on_drop!(AssetScope);

/// A `Canvas` and its builder.
pub struct CanvasScope {
    modifier: Modifier,
    commands: DrawList,
    done: bool,
}

/// Pixels the widget vocabulary cannot produce. Compose's `Canvas`, with the drawing as a
/// value rather than a callback: drawing code cannot cross into the Renderer, and an equal
/// list costs no record.
pub fn Canvas(commands: DrawList) -> CanvasScope {
    CanvasScope {
        modifier: Modifier::new(),
        commands,
        done: false,
    }
}

impl CanvasScope {
    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let commands = std::mem::take(&mut self.commands);
        emit(
            widget_key("compose_rust::ui::Canvas"),
            WidgetKind::Canvas,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("commands", OwnedAttr::Draw(commands)));
            },
            Vec::new(),
            content,
        );
    }
}

composed_on_drop!(CanvasScope);

// ----- pickers ----------------------------------------------------------------------------

/// A `DatePicker` and its builder.
pub struct DatePickerScope {
    modifier: Modifier,
    value: i64,
    min: Option<i64>,
    max: Option<i64>,
    enabled: bool,
    on_change: Option<Box<dyn FnMut(i64)>>,
    done: bool,
}

/// A date, as whole days since 1970-01-01. How it is picked is the design system's.
pub fn DatePicker(value: i64) -> DatePickerScope {
    DatePickerScope {
        modifier: Modifier::new(),
        value,
        min: None,
        max: None,
        enabled: true,
        on_change: None,
        done: false,
    }
}

impl DatePickerScope {
    pub fn min(mut self, days: i64) -> Self {
        self.min = Some(days);
        self
    }

    pub fn max(mut self, days: i64) -> Self {
        self.max = Some(days);
        self
    }

    pub fn enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    pub fn on_change(mut self, handler: impl FnMut(i64) + 'static) -> Self {
        self.on_change = Some(std::boxed::Box::new(handler));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let (value, min, max, enabled) = (self.value, self.min, self.max, self.enabled);
        let handler = match self.on_change.take() {
            Some(mut handler) => EventCallback::Value(Rc::new(RefCell::new(move |value: f64| {
                handler(value as i64)
            }))),
            None => value_handler(None),
        };
        emit(
            widget_key("compose_rust::ui::DatePicker"),
            WidgetKind::DatePicker,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("value", OwnedAttr::Int(value)));
                attrs.push(("min", OwnedAttr::Int(min.unwrap_or(i64::MIN))));
                attrs.push(("max", OwnedAttr::Int(max.unwrap_or(i64::MAX))));
                attrs.push(("enabled", OwnedAttr::Bool(enabled)));
            },
            vec![("onchange", handler)],
            content,
        );
    }
}

composed_on_drop!(DatePickerScope);

const MINUTES_IN_A_DAY: u32 = 24 * 60;

/// A `TimePicker` and its builder.
pub struct TimePickerScope {
    modifier: Modifier,
    value: u32,
    min: Option<u32>,
    max: Option<u32>,
    enabled: bool,
    on_change: Option<Box<dyn FnMut(u32)>>,
    done: bool,
}

/// A time of day, as minutes since midnight.
pub fn TimePicker(value: u32) -> TimePickerScope {
    TimePickerScope {
        modifier: Modifier::new(),
        value,
        min: None,
        max: None,
        enabled: true,
        on_change: None,
        done: false,
    }
}

impl TimePickerScope {
    pub fn min(mut self, minutes: u32) -> Self {
        self.min = Some(minutes);
        self
    }

    pub fn max(mut self, minutes: u32) -> Self {
        self.max = Some(minutes);
        self
    }

    pub fn enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    pub fn on_change(mut self, handler: impl FnMut(u32) + 'static) -> Self {
        self.on_change = Some(std::boxed::Box::new(handler));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let (value, min, max, enabled) = (self.value, self.min, self.max, self.enabled);
        let handler = match self.on_change.take() {
            Some(mut handler) => EventCallback::Value(Rc::new(RefCell::new(move |value: f64| {
                handler((value as i64).clamp(0, i64::from(MINUTES_IN_A_DAY - 1)) as u32)
            }))),
            None => value_handler(None),
        };
        emit(
            widget_key("compose_rust::ui::TimePicker"),
            WidgetKind::TimePicker,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("value", OwnedAttr::Int(i64::from(value))));
                attrs.push(("min", OwnedAttr::Int(i64::from(min.unwrap_or(0)))));
                attrs.push((
                    "max",
                    OwnedAttr::Int(i64::from(max.unwrap_or(MINUTES_IN_A_DAY - 1))),
                ));
                attrs.push(("enabled", OwnedAttr::Bool(enabled)));
            },
            vec![("onchange", handler)],
            content,
        );
    }
}

composed_on_drop!(TimePickerScope);

/// A `Dropdown` and its builder.
pub struct DropdownScope {
    modifier: Modifier,
    selected_index: usize,
    enabled: bool,
    on_change: Option<Box<dyn FnMut(usize)>>,
    done: bool,
}

/// One choice out of a list; each child is one option.
pub fn Dropdown(selected_index: usize) -> DropdownScope {
    DropdownScope {
        modifier: Modifier::new(),
        selected_index,
        enabled: true,
        on_change: None,
        done: false,
    }
}

impl DropdownScope {
    pub fn enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    pub fn on_change(mut self, handler: impl FnMut(usize) + 'static) -> Self {
        self.on_change = Some(std::boxed::Box::new(handler));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let (selected_index, enabled) = (self.selected_index, self.enabled);
        let handler = match self.on_change.take() {
            Some(mut handler) => EventCallback::Value(Rc::new(RefCell::new(move |value: f64| {
                handler((value as i64).max(0) as usize)
            }))),
            None => value_handler(None),
        };
        emit(
            widget_key("compose_rust::ui::Dropdown"),
            WidgetKind::Dropdown,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("selected_index", OwnedAttr::Int(selected_index as i64)));
                attrs.push(("enabled", OwnedAttr::Bool(enabled)));
            },
            vec![("onchange", handler)],
            content,
        );
    }
}

container!(DropdownScope);

// ----- surfaces that come and go ----------------------------------------------------------

/// A `Dialog` or a `Sheet` and its builder: `open` seeds the Renderer's own open state,
/// and `on_dismiss` says once that the user asked to close it.
pub struct ModalScope {
    modifier: Modifier,
    widget: WidgetKind,
    open: bool,
    on_dismiss: Option<Box<dyn FnMut()>>,
    done: bool,
}

/// A modal. Compose's `Dialog`, kept in the tree and opened by `open`, so its enter and
/// exit belong to the Renderer.
pub fn Dialog(open: bool) -> ModalScope {
    ModalScope {
        modifier: Modifier::new(),
        widget: WidgetKind::Dialog,
        open,
        on_dismiss: None,
        done: false,
    }
}

/// A surface that slides in from the edge the Renderer chooses. Compose's
/// `ModalBottomSheet`, without a promise about which edge.
pub fn Sheet(open: bool) -> ModalScope {
    ModalScope {
        modifier: Modifier::new(),
        widget: WidgetKind::Sheet,
        open,
        on_dismiss: None,
        done: false,
    }
}

impl ModalScope {
    pub fn on_dismiss(mut self, handler: impl FnMut() + 'static) -> Self {
        self.on_dismiss = Some(std::boxed::Box::new(handler));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let (widget, open) = (self.widget, self.open);
        let key = if widget == WidgetKind::Dialog {
            widget_key("compose_rust::ui::Dialog")
        } else {
            widget_key("compose_rust::ui::Sheet")
        };
        emit(
            key,
            widget,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("open", OwnedAttr::Bool(open)));
            },
            vec![("ondismiss", unit(self.on_dismiss.take()))],
            content,
        );
    }
}

container!(ModalScope);

/// A `Menu` and its builder.
pub struct MenuScope<'a> {
    modifier: Modifier,
    expanded: bool,
    on_dismiss: Option<Box<dyn FnMut()>>,
    anchor: Option<Box<dyn FnOnce() + 'a>>,
    done: bool,
}

/// A popup hanging off its anchor. Compose's `DropdownMenu`, with the anchor said rather
/// than positioned: the Renderer places the popup.
pub fn Menu<'a>(expanded: bool) -> MenuScope<'a> {
    MenuScope {
        modifier: Modifier::new(),
        expanded,
        on_dismiss: None,
        anchor: None,
        done: false,
    }
}

impl<'a> MenuScope<'a> {
    pub fn on_dismiss(mut self, handler: impl FnMut() + 'static) -> Self {
        self.on_dismiss = Some(std::boxed::Box::new(handler));
        self
    }

    /// The widget the menu hangs off. It is the first child on the wire.
    pub fn anchor(mut self, anchor: impl FnOnce() + 'a) -> Self {
        self.anchor = Some(std::boxed::Box::new(anchor));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let expanded = self.expanded;
        let anchor = self.anchor.take();
        emit(
            widget_key("compose_rust::ui::Menu"),
            WidgetKind::Menu,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("open", OwnedAttr::Bool(expanded)));
            },
            vec![("ondismiss", unit(self.on_dismiss.take()))],
            || {
                if let Some(anchor) = anchor {
                    let _anchor =
                        crate::runtime::group_guard(widget_key("compose_rust::ui::Menu::anchor"));
                    anchor();
                }
                let _entries =
                    crate::runtime::group_guard(widget_key("compose_rust::ui::Menu::entries"));
                content();
            },
        );
    }
}

container!(MenuScope<'a>);

/// A `Tabs` and its builder.
pub struct TabsScope {
    modifier: Modifier,
    color: Option<Paint>,
    selected_index: usize,
    done: bool,
}

/// A row of tabs; each child is one tab, and a tap is that child's own click. Compose's
/// `TabRow`.
pub fn Tabs(selected_index: usize) -> TabsScope {
    TabsScope {
        modifier: Modifier::new(),
        color: None,
        selected_index,
        done: false,
    }
}

impl TabsScope {
    /// The mark that shows which tab is selected.
    pub fn color(mut self, paint: Paint) -> Self {
        self.color = Some(paint);
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let (color, selected_index) = (self.color, self.selected_index);
        emit(
            widget_key("compose_rust::ui::Tabs"),
            WidgetKind::Tabs,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("color", super::paint(color)));
                attrs.push(("selected_index", OwnedAttr::Int(selected_index as i64)));
            },
            Vec::new(),
            content,
        );
    }
}

container!(TabsScope);

/// A `TopAppBar` and its builder.
pub struct TopAppBarScope {
    modifier: Modifier,
    title: String,
    done: bool,
}

/// The bar across the top of a screen; its children are its content. Compose's
/// `TopAppBar`, with the title a property so the Renderer can find it.
pub fn TopAppBar() -> TopAppBarScope {
    TopAppBarScope {
        modifier: Modifier::new(),
        title: String::new(),
        done: false,
    }
}

impl TopAppBarScope {
    pub fn title(mut self, title: impl Into<String>) -> Self {
        self.title = title.into();
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let title = std::mem::take(&mut self.title);
        emit(
            widget_key("compose_rust::ui::TopAppBar"),
            WidgetKind::TopAppBar,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("text", OwnedAttr::Text(title)));
            },
            Vec::new(),
            content,
        );
    }
}

container!(TopAppBarScope);

/// A `Tooltip` and its builder.
pub struct TooltipScope {
    modifier: Modifier,
    text: String,
    done: bool,
}

/// An explanation attached to its child, which assistive technology reads too. Compose's
/// `TooltipBox`.
pub fn Tooltip(text: impl Into<String>) -> TooltipScope {
    TooltipScope {
        modifier: Modifier::new(),
        text: text.into(),
        done: false,
    }
}

impl TooltipScope {
    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let text = std::mem::take(&mut self.text);
        emit(
            widget_key("compose_rust::ui::Tooltip"),
            WidgetKind::Tooltip,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("text", OwnedAttr::Text(text)));
            },
            Vec::new(),
            content,
        );
    }
}

container!(TooltipScope);

// ----- frames and navigation --------------------------------------------------------------

/// Composes one slot of a frame: a ScaffoldSlot node holding `content`, in a group of its
/// own so that a slot coming and going leaves the others where they were.
fn slot(role: SlotRole, key: u64, content: impl FnOnce()) {
    emit(
        key,
        WidgetKind::ScaffoldSlot,
        |attrs| attrs.push(("slot", OwnedAttr::Int(i64::from(u16::from(role))))),
        Vec::new(),
        content,
    );
}

type Slot<'a> = Option<Box<dyn FnOnce() + 'a>>;

/// A `Scaffold` and its builder.
pub struct ScaffoldScope<'a> {
    modifier: Modifier,
    top_bar: Slot<'a>,
    bottom_bar: Slot<'a>,
    floating_action: Slot<'a>,
    done: bool,
}

/// The screen's frame: which slots it has filled, and the page. Compose's `Scaffold`.
/// What each slot becomes on this platform at this width is the Renderer's decision.
///
/// The Modifier carries the two things a frame takes: `background` and `material`.
pub fn Scaffold<'a>() -> ScaffoldScope<'a> {
    ScaffoldScope {
        modifier: Modifier::new(),
        top_bar: None,
        bottom_bar: None,
        floating_action: None,
        done: false,
    }
}

impl<'a> ScaffoldScope<'a> {
    pub fn top_bar(mut self, bar: impl FnOnce() + 'a) -> Self {
        self.top_bar = Some(std::boxed::Box::new(bar));
        self
    }

    pub fn bottom_bar(mut self, bar: impl FnOnce() + 'a) -> Self {
        self.bottom_bar = Some(std::boxed::Box::new(bar));
        self
    }

    pub fn floating_action(mut self, action: impl FnOnce() + 'a) -> Self {
        self.floating_action = Some(std::boxed::Box::new(action));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let (top_bar, bottom_bar, floating_action) = (
            self.top_bar.take(),
            self.bottom_bar.take(),
            self.floating_action.take(),
        );
        emit(
            widget_key("compose_rust::ui::Scaffold"),
            WidgetKind::Scaffold,
            |attrs| push_modifiers(&modifier, Mods::Scaffold, attrs),
            Vec::new(),
            || {
                if let Some(bar) = top_bar {
                    slot(
                        SlotRole::TopBar,
                        widget_key("compose_rust::ui::Scaffold::top_bar"),
                        bar,
                    );
                }
                if let Some(bar) = bottom_bar {
                    slot(
                        SlotRole::BottomBar,
                        widget_key("compose_rust::ui::Scaffold::bottom_bar"),
                        bar,
                    );
                }
                if let Some(action) = floating_action {
                    slot(
                        SlotRole::FloatingAction,
                        widget_key("compose_rust::ui::Scaffold::floating_action"),
                        action,
                    );
                }
                slot(
                    SlotRole::Content,
                    widget_key("compose_rust::ui::Scaffold::content"),
                    content,
                );
            },
        );
    }
}

container!(ScaffoldScope<'a>);

/// A `Navigation` and its builder.
pub struct NavigationScope<'a> {
    modifier: Modifier,
    selected_index: usize,
    head: Slot<'a>,
    foot: Slot<'a>,
    done: bool,
}

/// A set of destinations and the screen they lead to. The children that are
/// `NavigationItem`s are the destinations; whether they are a bar, a rail or a drawer is
/// the Renderer's choice. Compose's `NavigationSuiteScaffold`.
pub fn Navigation<'a>(selected_index: usize) -> NavigationScope<'a> {
    NavigationScope {
        modifier: Modifier::new(),
        selected_index,
        head: None,
        foot: None,
        done: false,
    }
}

impl<'a> NavigationScope<'a> {
    /// What sits above the destinations in a strip.
    pub fn head(mut self, head: impl FnOnce() + 'a) -> Self {
        self.head = Some(std::boxed::Box::new(head));
        self
    }

    /// What sits below them.
    pub fn foot(mut self, foot: impl FnOnce() + 'a) -> Self {
        self.foot = Some(std::boxed::Box::new(foot));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let selected_index = self.selected_index;
        let (head, foot) = (self.head.take(), self.foot.take());
        emit(
            widget_key("compose_rust::ui::Navigation"),
            WidgetKind::Navigation,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("selected_index", OwnedAttr::Int(selected_index as i64)));
            },
            Vec::new(),
            || {
                if let Some(head) = head {
                    slot(
                        SlotRole::TopBar,
                        widget_key("compose_rust::ui::Navigation::head"),
                        head,
                    );
                }
                if let Some(foot) = foot {
                    slot(
                        SlotRole::BottomBar,
                        widget_key("compose_rust::ui::Navigation::foot"),
                        foot,
                    );
                }
                let _destinations = crate::runtime::group_guard(widget_key(
                    "compose_rust::ui::Navigation::content",
                ));
                content();
            },
        );
    }
}

container!(NavigationScope<'a>);

/// A `NavigationItem` and its builder.
pub struct NavigationItemScope {
    modifier: Modifier,
    text: String,
    icon: Option<IconRole>,
    color: Option<Paint>,
    enabled: bool,
    section: Option<String>,
    on_click: Option<Box<dyn FnMut()>>,
    done: bool,
}

/// One destination inside a `Navigation`. Compose's `NavigationSuiteItem`.
pub fn NavigationItem(text: impl Into<String>) -> NavigationItemScope {
    NavigationItemScope {
        modifier: Modifier::new(),
        text: text.into(),
        icon: None,
        color: None,
        enabled: true,
        section: None,
        on_click: None,
        done: false,
    }
}

impl NavigationItemScope {
    pub fn icon(mut self, icon: IconRole) -> Self {
        self.icon = Some(icon);
        self
    }

    pub fn color(mut self, paint: Paint) -> Self {
        self.color = Some(paint);
        self
    }

    pub fn enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    /// The named group of the strip this destination belongs to.
    pub fn section(mut self, section: impl Into<String>) -> Self {
        self.section = Some(section.into());
        self
    }

    pub fn on_click(mut self, handler: impl FnMut() + 'static) -> Self {
        self.on_click = Some(std::boxed::Box::new(handler));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let text = std::mem::take(&mut self.text);
        let section = self.section.take();
        let (icon, color, enabled) = (self.icon, self.color, self.enabled);
        emit(
            widget_key("compose_rust::ui::NavigationItem"),
            WidgetKind::NavigationItem,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("text", OwnedAttr::Text(text)));
                attrs.push(("icon", super::role(icon)));
                attrs.push(("color", super::paint(color)));
                attrs.push(("enabled", OwnedAttr::Bool(enabled)));
                attrs.push(("section", optional_text(section)));
            },
            vec![("onclick", unit(self.on_click.take()))],
            content,
        );
    }
}

composed_on_drop!(NavigationItemScope);

// ----- small things ---------------------------------------------------------------------

/// A `FileDropTarget` and its builder.
pub struct FileDropTargetScope {
    modifier: Modifier,
    alignment: Option<Alignment>,
    on_files_entered: Option<Box<dyn FnMut()>>,
    on_files_dropped: Option<Box<dyn FnMut(FileDrop)>>,
    done: bool,
}

/// A place files may be dropped. Desktop only in effect.
pub fn FileDropTarget() -> FileDropTargetScope {
    FileDropTargetScope {
        modifier: Modifier::new(),
        alignment: None,
        on_files_entered: None,
        on_files_dropped: None,
        done: false,
    }
}

impl FileDropTargetScope {
    pub fn alignment(mut self, alignment: Alignment) -> Self {
        self.alignment = Some(alignment);
        self
    }

    pub fn on_files_entered(mut self, handler: impl FnMut() + 'static) -> Self {
        self.on_files_entered = Some(std::boxed::Box::new(handler));
        self
    }

    pub fn on_files_dropped(mut self, handler: impl FnMut(FileDrop) + 'static) -> Self {
        self.on_files_dropped = Some(std::boxed::Box::new(handler));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let alignment = self.alignment;
        let dropped: EventCallback = match self.on_files_dropped.take() {
            Some(handler) => EventCallback::Files(Rc::new(RefCell::new(handler))),
            None => EventCallback::Files(Rc::new(RefCell::new(|_: FileDrop| {}))),
        };
        emit(
            widget_key("compose_rust::ui::FileDropTarget"),
            WidgetKind::FileDropTarget,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("alignment", super::role(alignment)));
            },
            vec![
                ("onfilesentered", unit(self.on_files_entered.take())),
                ("onfilesdropped", dropped),
            ],
            content,
        );
    }
}

container!(FileDropTargetScope);

/// A `Chip` and its builder.
pub struct ChipScope {
    modifier: Modifier,
    text: String,
    icon: Option<IconRole>,
    selected: bool,
    enabled: bool,
    on_click: Option<Box<dyn FnMut()>>,
    done: bool,
}

/// A small token that is chosen or filters. Compose's `FilterChip`.
pub fn Chip(text: impl Into<String>) -> ChipScope {
    ChipScope {
        modifier: Modifier::new(),
        text: text.into(),
        icon: None,
        selected: false,
        enabled: true,
        on_click: None,
        done: false,
    }
}

impl ChipScope {
    pub fn icon(mut self, icon: IconRole) -> Self {
        self.icon = Some(icon);
        self
    }

    pub fn selected(mut self, selected: bool) -> Self {
        self.selected = selected;
        self
    }

    pub fn enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    pub fn on_click(mut self, handler: impl FnMut() + 'static) -> Self {
        self.on_click = Some(std::boxed::Box::new(handler));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let text = std::mem::take(&mut self.text);
        let (icon, selected, enabled) = (self.icon, self.selected, self.enabled);
        emit(
            widget_key("compose_rust::ui::Chip"),
            WidgetKind::Chip,
            |attrs| {
                push_modifiers(&modifier, Mods::Chip, attrs);
                attrs.push(("text", OwnedAttr::Text(text)));
                attrs.push(("icon", super::role(icon)));
                attrs.push(("checked", OwnedAttr::Bool(selected)));
                attrs.push(("enabled", OwnedAttr::Bool(enabled)));
            },
            vec![("onclick", unit(self.on_click.take()))],
            content,
        );
    }
}

composed_on_drop!(ChipScope);

/// A `FloatingAction` and its builder.
pub struct FloatingActionScope {
    modifier: Modifier,
    text: String,
    icon: Option<IconRole>,
    on_click: Option<Box<dyn FnMut()>>,
    done: bool,
}

/// The one action a screen is about. Compose's `ExtendedFloatingActionButton`; where it
/// goes is the design system's answer.
pub fn FloatingAction(text: impl Into<String>) -> FloatingActionScope {
    FloatingActionScope {
        modifier: Modifier::new(),
        text: text.into(),
        icon: None,
        on_click: None,
        done: false,
    }
}

impl FloatingActionScope {
    pub fn icon(mut self, icon: IconRole) -> Self {
        self.icon = Some(icon);
        self
    }

    pub fn on_click(mut self, handler: impl FnMut() + 'static) -> Self {
        self.on_click = Some(std::boxed::Box::new(handler));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let text = std::mem::take(&mut self.text);
        let icon = self.icon;
        emit(
            widget_key("compose_rust::ui::FloatingAction"),
            WidgetKind::FloatingAction,
            |attrs| {
                push_modifiers(&modifier, Mods::None, attrs);
                attrs.push(("text", OwnedAttr::Text(text)));
                attrs.push(("icon", super::role(icon)));
            },
            vec![("onclick", unit(self.on_click.take()))],
            content,
        );
    }
}

composed_on_drop!(FloatingActionScope);

/// A `Badge` and its builder.
pub struct BadgeScope {
    modifier: Modifier,
    value: Option<u32>,
    text: Option<String>,
    color: Option<ColorRole>,
    done: bool,
}

/// A count, a word or a dot, on its child or on its own. Compose's `BadgedBox` and `Badge`.
pub fn Badge() -> BadgeScope {
    BadgeScope {
        modifier: Modifier::new(),
        value: None,
        text: None,
        color: None,
        done: false,
    }
}

impl BadgeScope {
    /// How many.
    pub fn value(mut self, value: u32) -> Self {
        self.value = Some(value);
        self
    }

    /// One short word, shown where a count would be.
    pub fn text(mut self, text: impl Into<String>) -> Self {
        self.text = Some(text.into());
        self
    }

    /// The role the mark is filled with; the error role where it is not given.
    pub fn color(mut self, role: ColorRole) -> Self {
        self.color = Some(role);
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let text = self.text.take();
        let (value, color) = (self.value, self.color);
        emit(
            widget_key("compose_rust::ui::Badge"),
            WidgetKind::Badge,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push((
                    "count",
                    value.map_or(OwnedAttr::None, |value| OwnedAttr::Int(i64::from(value))),
                ));
                attrs.push(("text", optional_text(text)));
                attrs.push(("color", super::paint(color.map(Paint::Role))));
            },
            Vec::new(),
            content,
        );
    }
}

container!(BadgeScope);

/// A `SplitPane` and its builder.
pub struct SplitPaneScope {
    modifier: Modifier,
    value: Option<f32>,
    min: Option<f32>,
    max: Option<f32>,
    collapsible: bool,
    selected_index: usize,
    label: Option<String>,
    on_change: Option<Box<dyn FnMut(f32)>>,
    on_dismiss: Option<Box<dyn FnMut()>>,
    done: bool,
}

/// A side pane and a body with a divider the user drags. Exactly two children, side pane
/// first. The drag is reported once, when it ends.
pub fn SplitPane() -> SplitPaneScope {
    SplitPaneScope {
        modifier: Modifier::new(),
        value: None,
        min: None,
        max: None,
        collapsible: false,
        selected_index: 0,
        label: None,
        on_change: None,
        on_dismiss: None,
        done: false,
    }
}

impl SplitPaneScope {
    /// The side pane's width in dp, or 0 to open it folded.
    pub fn value(mut self, dp: f32) -> Self {
        self.value = Some(dp);
        self
    }

    pub fn min(mut self, dp: f32) -> Self {
        self.min = Some(dp);
        self
    }

    pub fn max(mut self, dp: f32) -> Self {
        self.max = Some(dp);
        self
    }

    pub fn collapsible(mut self, collapsible: bool) -> Self {
        self.collapsible = collapsible;
        self
    }

    /// Which pane shows when only one fits: 0 the side pane, 1 the body.
    pub fn selected_index(mut self, index: usize) -> Self {
        self.selected_index = index;
        self
    }

    /// What a screen reader calls the divider.
    pub fn label(mut self, label: impl Into<String>) -> Self {
        self.label = Some(label.into());
        self
    }

    pub fn on_change(mut self, handler: impl FnMut(f32) + 'static) -> Self {
        self.on_change = Some(std::boxed::Box::new(handler));
        self
    }

    pub fn on_dismiss(mut self, handler: impl FnMut() + 'static) -> Self {
        self.on_dismiss = Some(std::boxed::Box::new(handler));
        self
    }

    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let label = self.label.take();
        let (value, min, max, collapsible, selected_index) = (
            self.value,
            self.min,
            self.max,
            self.collapsible,
            self.selected_index,
        );
        let change = match self.on_change.take() {
            Some(mut handler) => EventCallback::Value(Rc::new(RefCell::new(move |value: f64| {
                handler(value as f32)
            }))),
            None => value_handler(None),
        };
        let as_float = |value: Option<f32>| {
            value.map_or(OwnedAttr::None, |value| OwnedAttr::Float(f64::from(value)))
        };
        emit(
            widget_key("compose_rust::ui::SplitPane"),
            WidgetKind::SplitPane,
            |attrs| {
                push_modifiers(&modifier, Mods::Standard, attrs);
                attrs.push(("value", as_float(value)));
                attrs.push(("min", as_float(min)));
                attrs.push(("max", as_float(max)));
                attrs.push(("collapsible", OwnedAttr::Bool(collapsible)));
                attrs.push(("selected_index", OwnedAttr::Int(selected_index as i64)));
                attrs.push(("text", optional_text(label)));
            },
            vec![
                ("onchange", change),
                ("ondismiss", unit(self.on_dismiss.take())),
            ],
            content,
        );
    }
}

container!(SplitPaneScope);

/// The worked extension widget: Material 3's determinate linear progress indicator.
pub struct LinearProgressIndicatorScope {
    modifier: Modifier,
    progress: f32,
    done: bool,
}

/// The extension widget the schema carries as its example.
pub fn LinearProgressIndicator(progress: f32) -> LinearProgressIndicatorScope {
    LinearProgressIndicatorScope {
        modifier: Modifier::new(),
        progress,
        done: false,
    }
}

impl LinearProgressIndicatorScope {
    fn compose(&mut self, content: impl FnOnce()) {
        let modifier = self.modifier;
        let progress = self.progress;
        emit(
            widget_key("compose_rust::ui::LinearProgressIndicator"),
            WidgetKind::LinearProgressIndicator,
            |attrs| {
                push_modifiers(&modifier, Mods::None, attrs);
                attrs.push(("progress", OwnedAttr::Float(f64::from(progress))));
            },
            Vec::new(),
            content,
        );
    }
}

composed_on_drop!(LinearProgressIndicatorScope);

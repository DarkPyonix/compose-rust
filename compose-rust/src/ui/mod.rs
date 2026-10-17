//! The widget vocabulary as composables: the same widgets, the same properties and the
//! same Modifier slots the `rsx!` layer writes, with Compose's names and Compose's shape.
//!
//! ```ignore
//! use compose_rust::runtime::*;
//! use compose_rust::ui::*;
//!
//! #[composable]
//! fn Greeting(name: String) {
//!     Column().modifier(Modifier.padding(16.0).fill_max_width()).content(|| {
//!         Text(format!("Hello, {name}")).type_role(TypeRole::Title);
//!         Button("Wave").on_click(|| show_message("Hello back"));
//!     });
//! }
//! ```
//!
//! **A widget is written as a statement.** `Text("Hello")` creates a description and the
//! statement's end hands it to the composition; the methods in between set what Compose
//! would take as named arguments. A container's children are the closure given to
//! `content`, which runs at once. Binding a widget to a variable delays it to the end of
//! the variable's scope, so a widget is not bound.
//!
//! **Nothing here defines a widget.** Each composable writes exactly the attributes its
//! `rsx!` counterpart writes, through the same translation into wire records, so a screen
//! written either way is the same tree of the same nodes for the Renderer. A Modifier the
//! `rsx!` counterpart does not accept is still written when it is set, because the wire
//! carries every Modifier slot on every node.

mod lazy;
mod widgets;

pub use crate::boundary::LaunchBuilder;
pub use crate::brush::{Brush, Stop, brush};
pub use crate::drawing::{DrawCommand, DrawList, DrawListBuilder};
pub use crate::message::{Message, show_message};
pub use crate::runtime::{application, launch};
pub use crate::schema::{
    Alignment, Arrangement, ButtonVariant, Color, ColorRole, ColorScheme, DesignSystem, IconRole,
    Key, MaterialRole, MessageDuration, MotionRole, Paint, ShapeRole, SpaceRole, TextAlign,
    TextOverflow, Theme, TypeRole, WindowHeightClass, WindowSizeClass,
};
pub use crate::spans::TextSpans;
pub use crate::theme::{ThemeHandle, current_theme};

/// The application's theme, to read and to change while it runs. A change is one
/// `SetTheme` record in the batch the call that made it produces.
pub fn theme() -> ThemeHandle {
    ThemeHandle::default()
}
pub use crate::window::{NodeSize, WindowSize};
pub use crate::{FileDrop, KeyEvent, RangeRequest, asset};
pub use lazy::{LazyColumn, LazyGrid, LazyList, LazyListScope, LazyRow};
pub use widgets::*;

use crate::runtime::composer::{EventCallback, OwnedAttr};
use crate::runtime::{group_guard, with_composer, with_composer_if_idle};
use crate::schema::WidgetKind;

/// What sits around a widget: its size, its surface, its outline, how far its content
/// is inset, and what the Renderer reports about it. Compose's `Modifier`.
///
/// Written the way Compose writes it, from the empty value `Modifier`:
///
/// ```ignore
/// Card().modifier(Modifier.fill_max_width().padding(16.0).background(Paint::Role(ColorRole::Surface)))
/// ```
///
/// The order of the calls does not matter. Each kind of Modifier has a fixed slot in the
/// node's chain, outside in: size, shape, fill, outline, lift, press, inset. That is what
/// the `rsx!` attributes do too, so the same Modifier draws the same either way.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Modifier {
    observe_size: Option<i64>,
    motion: Option<MotionRole>,
    material: Option<MaterialRole>,
    weight: Option<f32>,
    width: Option<f32>,
    height: Option<f32>,
    padding: Option<f32>,
    padding_role: Option<SpaceRole>,
    background: Option<Paint>,
    shape_role: Option<ShapeRole>,
    corner_radius: Option<f32>,
    border_width: Option<f32>,
    border_color: Option<Paint>,
    elevation: Option<f32>,
    fill_max_width: bool,
    fill_max_height: bool,
}

/// The empty Modifier, to start a chain from: `Modifier.padding(8.0)`.
#[allow(non_upper_case_globals)]
pub const Modifier: Modifier = Modifier::new();

impl Modifier {
    /// The empty Modifier. The same as the value `Modifier`.
    pub const fn new() -> Self {
        Self {
            observe_size: None,
            motion: None,
            material: None,
            weight: None,
            width: None,
            height: None,
            padding: None,
            padding_role: None,
            background: None,
            shape_role: None,
            corner_radius: None,
            border_width: None,
            border_color: None,
            elevation: None,
            fill_max_width: false,
            fill_max_height: false,
        }
    }

    /// A share of the space left in a `Row` or `Column`.
    pub const fn weight(mut self, weight: f32) -> Self {
        self.weight = Some(weight);
        self
    }

    pub const fn width(mut self, dp: f32) -> Self {
        self.width = Some(dp);
        self
    }

    pub const fn height(mut self, dp: f32) -> Self {
        self.height = Some(dp);
        self
    }

    /// Both at once.
    pub const fn size(self, width: f32, height: f32) -> Self {
        self.width(width).height(height)
    }

    /// A width that may be unset, for a screen whose measure depends on the window.
    pub const fn width_if(mut self, dp: Option<f32>) -> Self {
        self.width = dp;
        self
    }

    pub const fn fill_max_width(mut self) -> Self {
        self.fill_max_width = true;
        self
    }

    pub const fn fill_max_height(mut self) -> Self {
        self.fill_max_height = true;
        self
    }

    pub const fn fill_max_size(self) -> Self {
        self.fill_max_width().fill_max_height()
    }

    /// Fills the width only when `fill` is true.
    pub const fn fill_max_width_if(mut self, fill: bool) -> Self {
        self.fill_max_width = fill;
        self
    }

    /// The same inset on every side, in dp.
    pub const fn padding(mut self, dp: f32) -> Self {
        self.padding = Some(dp);
        self
    }

    /// An inset from the design system's spacing ladder. Wins over `padding`.
    pub const fn padding_role(mut self, role: SpaceRole) -> Self {
        self.padding_role = Some(role);
        self
    }

    /// The same, when the inset may be unset.
    pub const fn padding_role_if(mut self, role: Option<SpaceRole>) -> Self {
        self.padding_role = role;
        self
    }

    pub const fn background(mut self, paint: Paint) -> Self {
        self.background = Some(paint);
        self
    }

    /// The same, when there may be no background.
    pub const fn background_if(mut self, paint: Option<Paint>) -> Self {
        self.background = paint;
        self
    }

    /// A shape from the design system's ladder.
    pub const fn shape_role(mut self, role: ShapeRole) -> Self {
        self.shape_role = Some(role);
        self
    }

    /// A literal corner, in dp. Wins over `shape_role`.
    pub const fn corner_radius(mut self, dp: f32) -> Self {
        self.corner_radius = Some(dp);
        self
    }

    pub const fn border(mut self, width: f32, paint: Paint) -> Self {
        self.border_width = Some(width);
        self.border_color = Some(paint);
        self
    }

    /// A resting height above the default, in dp. How it is drawn is the design system's.
    pub const fn elevation(mut self, dp: f32) -> Self {
        self.elevation = Some(dp);
        self
    }

    /// Asks the Renderer to report this node's size under the token from
    /// [`crate::runtime::remember_node_size`].
    pub const fn observe_size(mut self, token: i64) -> Self {
        self.observe_size = Some(token);
        self
    }

    /// How important this node's changes are. The curve is the design system's.
    pub const fn motion(mut self, role: MotionRole) -> Self {
        self.motion = Some(role);
        self
    }

    /// What this node's surface is made of. The effect is the design system's.
    pub const fn material(mut self, role: MaterialRole) -> Self {
        self.material = Some(role);
        self
    }
}

/// Which Modifier attributes a widget's `rsx!` counterpart always writes. The others are
/// written only when they are set.
#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum Mods {
    /// The containers that take every Modifier: Column, Row, Box, Card, Surface.
    Container,
    /// The thirteen every other widget takes.
    Standard,
    /// A chip's six.
    Chip,
    /// A Scaffold's background and material.
    Scaffold,
    /// None.
    None,
}

pub(crate) type Attrs = Vec<(&'static str, OwnedAttr)>;

fn float(value: Option<f32>) -> OwnedAttr {
    value.map_or(OwnedAttr::None, |value| OwnedAttr::Float(f64::from(value)))
}

fn role(value: Option<impl Into<u16>>) -> OwnedAttr {
    value.map_or(OwnedAttr::None, |value| {
        OwnedAttr::Int(i64::from(value.into()))
    })
}

fn paint(value: Option<Paint>) -> OwnedAttr {
    value.map_or(OwnedAttr::None, |paint| {
        OwnedAttr::Int(paint.to_bits() as i64)
    })
}

/// A role that was not set is tag 0, which means "not sent".
pub(crate) fn role_or_zero(value: Option<impl Into<u16>>) -> OwnedAttr {
    OwnedAttr::Int(value.map_or(0, |value| i64::from(value.into())))
}

/// A dp value that was not set is 0.
pub(crate) fn dp_or_zero(value: Option<f32>) -> OwnedAttr {
    OwnedAttr::Float(f64::from(value.unwrap_or(0.0)))
}

/// A paint that was not set is 0.
pub(crate) fn paint_or_zero(value: Option<Paint>) -> OwnedAttr {
    OwnedAttr::Int(value.map_or(0, |paint| paint.to_bits() as i64))
}

pub(crate) fn optional_text(value: Option<String>) -> OwnedAttr {
    value.map_or(OwnedAttr::None, OwnedAttr::Text)
}

/// Writes the Modifier attributes, in the order the `rsx!` widgets write them.
pub(crate) fn push_modifiers(modifier: &Modifier, mods: Mods, out: &mut Attrs) {
    let container = mods == Mods::Container;
    let standard = container || mods == Mods::Standard;
    let chip = standard || mods == Mods::Chip;
    let scaffold = mods == Mods::Scaffold;
    let mut push = |always: bool, name: &'static str, value: OwnedAttr| {
        let set = !matches!(value, OwnedAttr::None | OwnedAttr::Bool(false));
        if always || set {
            out.push((name, value));
        }
    };
    push(
        container,
        "observe_size",
        modifier
            .observe_size
            .map_or(OwnedAttr::None, OwnedAttr::Int),
    );
    push(container, "motion", role(modifier.motion));
    push(container || scaffold, "material", role(modifier.material));
    push(chip, "weight", float(modifier.weight));
    push(chip, "width", float(modifier.width));
    push(chip, "height", float(modifier.height));
    push(chip, "padding", float(modifier.padding));
    push(chip, "padding_role", role(modifier.padding_role));
    push(
        standard || scaffold,
        "background",
        paint(modifier.background),
    );
    push(standard, "shape_role", role(modifier.shape_role));
    push(standard, "corner_radius", float(modifier.corner_radius));
    push(standard, "border_width", float(modifier.border_width));
    push(standard, "border_color", paint(modifier.border_color));
    push(standard, "elevation", float(modifier.elevation));
    push(
        chip,
        "fill_max_width",
        OwnedAttr::Bool(modifier.fill_max_width),
    );
    push(
        standard,
        "fill_max_height",
        OwnedAttr::Bool(modifier.fill_max_height),
    );
}

/// Composes one widget node: its group, its attributes and listeners, and its children.
///
/// `fill` writes the attributes and runs while the composer is held, so it only moves
/// values out of the builder. `content` runs afterwards, with the node as the parent of
/// whatever it composes.
pub(crate) fn emit(
    key: u64,
    widget: WidgetKind,
    fill: impl FnOnce(&mut Attrs),
    listeners: Vec<(&'static str, EventCallback)>,
    content: impl FnOnce(),
) {
    let _group = group_guard(key);
    emit_node(widget, fill, listeners, content);
}

/// The same, inside a group the caller has already opened, for a widget that remembers
/// something of its own before its node.
pub(crate) fn emit_node(
    widget: WidgetKind,
    fill: impl FnOnce(&mut Attrs),
    listeners: Vec<(&'static str, EventCallback)>,
    content: impl FnOnce(),
) {
    let node = with_composer_if_idle(|composer| {
        if !composer.composing() {
            return None;
        }
        let mut attrs = composer.take_attrs();
        fill(&mut attrs);
        Some(composer.update_node(widget, attrs, listeners))
    })
    .flatten();
    let Some(node) = node else {
        return;
    };
    with_composer(|composer| composer.push_node_context(node));
    let _context = NodeContextGuard;
    content();
}

/// Closes a widget's node context on every way out of its content, an unwind included.
struct NodeContextGuard;

impl Drop for NodeContextGuard {
    fn drop(&mut self) {
        with_composer_if_idle(|composer| composer.pop_node_context());
    }
}

/// A call site key for a widget, from its name.
pub(crate) const fn widget_key(name: &str) -> u64 {
    crate::runtime::composer::call_site(name, 0)
}

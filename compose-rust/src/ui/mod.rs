//! The widget vocabulary as composables: the same widgets, the same properties and the
//! same Modifier slots the `rsx!` layer writes, with Compose's names and Compose's shape.
//!
//! ```ignore
//! use compose_rust::runtime::*;
//! use compose_rust::ui::*;
//!
//! #[Composable]
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
pub use crate::schema::SemanticsRole as Role;
pub use crate::schema::{
    Alignment, Arrangement, ButtonKind, ButtonVariant, Color, ColorRole, ColorScheme, DesignSystem,
    IconRole, Key, MaterialRole, MessageDuration, MotionRole, Paint, PopoverAlign,
    PopoverPlacement, SemanticsRole, ShapeRole, SpaceRole, TextAlign, TextOverflow, Theme,
    TypeRole, WindowHeightClass, WindowSizeClass,
};
pub use crate::spans::TextSpans;
pub use crate::theme::{ThemeHandle, current_theme};

/// The application's theme, to read and to change while it runs. A change is one
/// `SetTheme` record in the batch the call that made it produces.
pub fn theme() -> ThemeHandle {
    ThemeHandle::default()
}
pub use crate::window::{NodeSize, WindowSafeArea, WindowSize};
pub use crate::{
    Cursor, FileDrop, KeyEvent, PointerEvent, PointerKind, PointerKinds, RangeRequest, UserSelect,
    asset, pointer_button,
};
pub use lazy::{LazyColumn, LazyGrid, LazyList, LazyListScope, LazyRow};
pub use widgets::*;

use crate::runtime::composer::{EventCallback, OwnedAttr};
use crate::runtime::{group_guard, with_composer, with_composer_if_idle};
use crate::schema::WidgetKind;

/// The most shadow layers one node can carry through this API.
pub const MAX_SHADOWS: usize = 4;

/// One layer of a drop shadow, as CSS `box-shadow` writes it: an offset, a blur, a spread
/// and a colour with its alpha. It follows the corners of the node that casts it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Shadow {
    pub x: f32,
    pub y: f32,
    pub blur: f32,
    pub spread: f32,
    pub paint: Paint,
}

impl Shadow {
    pub const fn new(x: f32, y: f32, blur: f32, spread: f32, paint: Paint) -> Self {
        Self {
            x,
            y,
            blur,
            spread,
            paint,
        }
    }
}

/// How a node presents itself to assistive technology. Each tri-state field is unset until
/// it is given a value, and an unset one sends nothing. The flags are off until set.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct SemanticsState {
    pub checked: Option<bool>,
    pub selected: Option<bool>,
    pub expanded: Option<bool>,
    pub disabled: Option<bool>,
    pub focused: Option<bool>,
    pub hidden: bool,
    pub click_action: bool,
    pub focus_action: bool,
}

impl SemanticsState {
    const NONE: Self = Self {
        checked: None,
        selected: None,
        expanded: None,
        disabled: None,
        focused: None,
        hidden: false,
        click_action: false,
        focus_action: false,
    };

    pub const fn checked(checked: bool) -> Self {
        Self {
            checked: Some(checked),
            ..Self::NONE
        }
    }

    pub const fn selected(selected: bool) -> Self {
        Self {
            selected: Some(selected),
            ..Self::NONE
        }
    }

    pub const fn expanded(expanded: bool) -> Self {
        Self {
            expanded: Some(expanded),
            ..Self::NONE
        }
    }

    pub const fn disabled(disabled: bool) -> Self {
        Self {
            disabled: Some(disabled),
            ..Self::NONE
        }
    }

    pub const fn focused(focused: bool) -> Self {
        Self {
            focused: Some(focused),
            ..Self::NONE
        }
    }

    pub const fn hidden(hidden: bool) -> Self {
        Self {
            hidden,
            ..Self::NONE
        }
    }

    pub const fn click_action(exposed: bool) -> Self {
        Self {
            click_action: exposed,
            ..Self::NONE
        }
    }

    pub const fn focus_action(exposed: bool) -> Self {
        Self {
            focus_action: exposed,
            ..Self::NONE
        }
    }

    /// The state word the `Semantics` modifier carries.
    pub(crate) fn bits(self) -> u32 {
        use crate::schema::*;
        let tri = |value: Option<bool>| value.map_or(0_u32, |on| if on { 2 } else { 1 });
        (tri(self.checked) << SEMANTICS_CHECKED_SHIFT)
            | (tri(self.selected) << SEMANTICS_SELECTED_SHIFT)
            | (tri(self.expanded) << SEMANTICS_EXPANDED_SHIFT)
            | (tri(self.disabled) << SEMANTICS_DISABLED_SHIFT)
            | (tri(self.focused) << SEMANTICS_FOCUSED_SHIFT)
            | if self.hidden { SEMANTICS_HIDDEN_BIT } else { 0 }
            | if self.click_action {
                SEMANTICS_CLICK_ACTION_BIT
            } else {
                0
            }
            | if self.focus_action {
                SEMANTICS_FOCUS_ACTION_BIT
            } else {
                0
            }
    }
}

/// The most distinct accessible names a program can keep. Names are kept for the life of
/// the program so a `Modifier` can stay `Copy`; the cap keeps a name built from data
/// (an id, a counter) from growing that memory without end.
pub(crate) const MAX_LABELS: usize = 4096;

/// The distinct accessible names seen so far, each leaked once.
struct LabelTable {
    kept: std::sync::RwLock<std::collections::HashSet<&'static str>>,
    warned: std::sync::atomic::AtomicBool,
}

impl LabelTable {
    fn new() -> Self {
        Self {
            kept: Default::default(),
            warned: Default::default(),
        }
    }

    /// The kept copy of `label`. A name already kept is found under a shared lock with no
    /// allocation. A new name is kept until the table holds `cap` names; past that it is
    /// dropped (and reported in debug builds) so memory stays bounded.
    fn intern(&self, label: &str, cap: usize) -> Option<&'static str> {
        use std::sync::atomic::Ordering;

        if label.is_empty() {
            return None;
        }
        {
            let kept = self.kept.read().unwrap_or_else(|p| p.into_inner());
            if let Some(found) = kept.get(label) {
                return Some(*found);
            }
        }
        let mut kept = self.kept.write().unwrap_or_else(|p| p.into_inner());
        if let Some(found) = kept.get(label) {
            return Some(*found);
        }
        if kept.len() >= cap {
            if cfg!(debug_assertions) && !self.warned.swap(true, Ordering::Relaxed) {
                eprintln!(
                    "compose-rust: more than {cap} distinct accessible names; \
                     names past the limit are left off. Use a fixed set of names."
                );
            }
            return None;
        }
        let leaked: &'static str = Box::leak(label.to_owned().into_boxed_str());
        kept.insert(leaked);
        Some(leaked)
    }

    #[cfg(test)]
    fn len(&self) -> usize {
        self.kept.read().unwrap_or_else(|p| p.into_inner()).len()
    }
}

fn keep_label(label: &str) -> Option<&'static str> {
    use std::cell::Cell;
    use std::sync::OnceLock;

    static LABELS: OnceLock<LabelTable> = OnceLock::new();
    thread_local! {
        // The name this thread kept last. A name written on every frame is found here
        // without taking any lock.
        static LAST: Cell<Option<&'static str>> = const { Cell::new(None) };
    }
    if let Some(last) = LAST.with(Cell::get) {
        if last == label {
            return Some(last);
        }
    }
    let kept = LABELS
        .get_or_init(LabelTable::new)
        .intern(label, MAX_LABELS);
    if kept.is_some() {
        LAST.with(|last| last.set(kept));
    }
    kept
}

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
    corner_radii: Option<[f32; 4]>,
    border_width: Option<f32>,
    border_color: Option<Paint>,
    elevation: Option<f32>,
    alpha: Option<f32>,
    press_feedback: Option<(f32, f32)>,
    semantics_role: Option<SemanticsRole>,
    semantics_label: Option<&'static str>,
    semantics_state: SemanticsState,
    transform: Option<[f32; 8]>,
    animated_alpha: Option<crate::Animated<f32>>,
    animated_background: Option<crate::Animated<Paint>>,
    animated_offset: Option<crate::Animated<(f32, f32)>>,
    cursor: Option<crate::Cursor>,
    user_select: Option<crate::UserSelect>,
    clip: bool,
    window_drag: Option<bool>,
    offset: Option<(f32, f32)>,
    shadows: [Option<Shadow>; MAX_SHADOWS],
    fill_max_width: bool,
    match_parent_size: bool,
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
            corner_radii: None,
            border_width: None,
            border_color: None,
            elevation: None,
            alpha: None,
            press_feedback: None,
            semantics_role: None,
            semantics_label: None,
            semantics_state: SemanticsState::NONE,
            transform: None,
            animated_alpha: None,
            animated_background: None,
            animated_offset: None,
            cursor: None,
            user_select: None,
            clip: false,
            window_drag: None,
            offset: None,
            shadows: [None; MAX_SHADOWS],
            fill_max_width: false,
            match_parent_size: false,
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

    /// Matches the bounds of a Box parent without affecting its size.
    pub const fn match_parent_size(mut self) -> Self {
        self.match_parent_size = true;
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
        self.corner_radii = None;
        self
    }

    /// Individual corner radii `[top_start, top_end, bottom_end, bottom_start]`, in dp.
    /// Wins over `shape_role` and `corner_radius`.
    pub const fn corner_radii(mut self, radii: [f32; 4]) -> Self {
        self.corner_radii = Some(radii);
        self.corner_radius = None;
        self
    }

    /// Clips the node and its children to its shape, or to its rectangle when it has none.
    pub const fn clip(mut self, clip: bool) -> Self {
        self.clip = clip;
        self
    }

    /// Includes or excludes this node from window dragging.
    pub const fn window_drag(mut self, enabled: bool) -> Self {
        self.window_drag = Some(enabled);
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

    /// Moves the node from where it was laid out, in dp, without moving anything else.
    pub const fn offset(mut self, x: f32, y: f32) -> Self {
        self.offset = Some((x, y));
        self
    }

    /// Adds a shadow layer. Layers are drawn in the order they are added, the first at
    /// the bottom. A node takes at most [`MAX_SHADOWS`]; a layer past that is ignored.
    pub const fn shadow(mut self, shadow: Shadow) -> Self {
        let mut index = 0;
        while index < MAX_SHADOWS {
            if self.shadows[index].is_none() {
                self.shadows[index] = Some(shadow);
                break;
            }
            index += 1;
        }
        self
    }

    /// Adds several shadow layers, in order.
    pub fn shadows(mut self, shadows: impl IntoIterator<Item = Shadow>) -> Self {
        for shadow in shadows {
            self = self.shadow(shadow);
        }
        self
    }

    /// An opacity for the node and everything in it, faded as one group.
    pub const fn alpha(mut self, alpha: f32) -> Self {
        self.alpha = Some(alpha);
        self
    }

    /// Sets the pressed scale and tint opacity.
    pub const fn press_feedback(mut self, scale: f32, tint_alpha: f32) -> Self {
        self.press_feedback = Some((scale, tint_alpha));
        self
    }

    /// The name assistive technology reads for this node. On a widget with a visible label
    /// it replaces that label; an empty name is the same as none.
    ///
    /// Names are kept for the life of the program, and at most 4096 distinct names are
    /// kept. A name past that limit is left off (debug builds print a warning once), so
    /// use a fixed set of names rather than one built from changing data.
    pub fn label(mut self, label: &str) -> Self {
        self.semantics_label = keep_label(label);
        self
    }

    /// What this node is to assistive technology, for a node that is not a widget with
    /// a role of its own: a `Box` that is pressed is a button.
    pub const fn role(mut self, role: Role) -> Self {
        self.semantics_role = Some(role);
        self
    }

    /// Whether the node is checked, as a toggle announces it.
    pub const fn checked(mut self, checked: bool) -> Self {
        self.semantics_state.checked = Some(checked);
        self
    }

    /// Whether the node is the selected one of its group.
    pub const fn selected(mut self, selected: bool) -> Self {
        self.semantics_state.selected = Some(selected);
        self
    }

    /// Whether the node is expanded, as a disclosure or a tree item announces it.
    pub const fn expanded(mut self, expanded: bool) -> Self {
        self.semantics_state.expanded = Some(expanded);
        self
    }

    /// Whether the node is disabled for assistive technology.
    pub const fn disabled(mut self, disabled: bool) -> Self {
        self.semantics_state.disabled = Some(disabled);
        self
    }

    /// Whether the node holds the focus, as assistive technology reads it.
    pub const fn focused(mut self, focused: bool) -> Self {
        self.semantics_state.focused = Some(focused);
        self
    }

    /// Leaves the node out of the accessibility tree when true.
    pub const fn hidden(mut self, hidden: bool) -> Self {
        self.semantics_state.hidden = hidden;
        self
    }

    /// Exposes a click action on the node, which runs the node's click handler.
    pub const fn click_action(mut self, exposed: bool) -> Self {
        self.semantics_state.click_action = exposed;
        self
    }

    /// Exposes a focus action on the node.
    pub const fn focus_action(mut self, exposed: bool) -> Self {
        self.semantics_state.focus_action = exposed;
        self
    }

    /// The role, the name and the state in one call.
    pub fn semantics(self, role: Role, label: &str, state: SemanticsState) -> Self {
        let mut named = self.role(role).label(label);
        named.semantics_state = state;
        named
    }

    /// Compose's `graphicsLayer`: a rotation, a scale and a translation about an origin,
    /// drawn without moving the layout, and an opacity for the whole group. A pointer is
    /// tested against the shape that is drawn.
    pub fn graphics_layer(mut self, layer: crate::GraphicsLayer) -> Self {
        if let crate::schema::Modifier::Transform {
            a,
            b,
            c,
            d,
            e,
            f,
            origin_x,
            origin_y,
        } = layer.transform()
        {
            self.transform = Some([a, b, c, d, e, f, origin_x, origin_y]);
        }
        self.alpha = Some(layer.alpha);
        self
    }

    /// An opacity the Renderer animates to, from [`crate::animate_float_as_state`]. It
    /// takes the place of `alpha`.
    pub const fn animated_alpha(mut self, value: crate::Animated<f32>) -> Self {
        self.animated_alpha = Some(value);
        self
    }

    /// A background the Renderer animates to, from [`crate::animate_color_as_state`]. It
    /// takes the place of `background`.
    pub const fn animated_background(mut self, value: crate::Animated<Paint>) -> Self {
        self.animated_background = Some(value);
        self
    }

    /// A translation the Renderer animates to, from [`crate::animate_offset_as_state`].
    /// It takes the place of the transform a `graphics_layer` would draw.
    pub const fn animated_offset(mut self, value: crate::Animated<(f32, f32)>) -> Self {
        self.animated_offset = Some(value);
        self
    }

    /// The cursor the platform shows over the node. CSS `cursor`: the deepest node under
    /// the pointer that names one other than `Cursor::Auto` wins, and while a node holds a
    /// pointer capture its cursor stays.
    pub const fn cursor(mut self, cursor: crate::Cursor) -> Self {
        self.cursor = Some(cursor);
        self
    }

    /// Whether the text under the node can be selected. CSS `user-select`; `Auto` follows
    /// the parent.
    pub const fn user_select(mut self, user_select: crate::UserSelect) -> Self {
        self.user_select = Some(user_select);
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

/// The role and state of a Modifier as the one integer the `semantics` attribute carries,
/// or none when neither was given.
fn semantics_bits(modifier: &Modifier) -> Option<i64> {
    let state = modifier.semantics_state;
    if modifier.semantics_role.is_none() && state == SemanticsState::NONE {
        return None;
    }
    let role = i64::from(u16::from(
        modifier.semantics_role.unwrap_or(SemanticsRole::None),
    ));
    Some(role | (i64::from(state.bits()) << 16))
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
    // An animated background owns the background's slot while it is set.
    push(
        standard || scaffold,
        "background",
        paint(if modifier.animated_background.is_some() {
            None
        } else {
            modifier.background
        }),
    );
    push(standard, "shape_role", role(modifier.shape_role));
    push(standard, "corner_radius", float(modifier.corner_radius));
    push(
        false,
        "corner_radii",
        modifier.corner_radii.map_or(OwnedAttr::None, |values| {
            OwnedAttr::Bytes(
                values
                    .iter()
                    .flat_map(|value| value.to_le_bytes())
                    .collect(),
            )
        }),
    );
    push(false, "clip", OwnedAttr::Bool(modifier.clip));
    push(
        true,
        "window_drag",
        modifier
            .window_drag
            .map_or(OwnedAttr::None, OwnedAttr::Bool),
    );
    push(standard, "border_width", float(modifier.border_width));
    push(standard, "border_color", paint(modifier.border_color));
    push(standard, "elevation", float(modifier.elevation));
    push(
        false,
        "match_parent_size",
        OwnedAttr::Bool(modifier.match_parent_size),
    );
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
    // The layer and the values the Renderer animates, written only when they are set. An
    // animated value owns its slot while it is set, as an animated background does above.
    push(
        false,
        "press_feedback",
        modifier
            .press_feedback
            .map_or(OwnedAttr::None, |(scale, alpha)| {
                OwnedAttr::Bytes([scale.to_le_bytes(), alpha.to_le_bytes()].concat())
            }),
    );
    // The role and state travel as one integer, the role in the low 16 bits; the name is a
    // property of the node because a string does not fit in a modifier's words.
    push(
        false,
        "semantics",
        semantics_bits(modifier).map_or(OwnedAttr::None, OwnedAttr::Int),
    );
    push(
        false,
        "accessible_name",
        modifier
            .semantics_label
            .map_or(OwnedAttr::None, OwnedAttr::Label),
    );
    push(
        false,
        "alpha",
        float(if modifier.animated_alpha.is_some() {
            None
        } else {
            modifier.alpha
        }),
    );
    push(
        false,
        "transform",
        match (modifier.animated_offset, modifier.transform) {
            (None, Some(values)) => OwnedAttr::Bytes(
                values
                    .iter()
                    .flat_map(|value| value.to_le_bytes())
                    .collect(),
            ),
            _ => OwnedAttr::None,
        },
    );
    // Written only when set, and `Auto` is the same as not set.
    push(
        false,
        "cursor",
        match modifier.cursor {
            Some(crate::Cursor::Auto) | None => OwnedAttr::None,
            Some(cursor) => OwnedAttr::Int(i64::from(u16::from(cursor))),
        },
    );
    push(
        false,
        "user_select",
        match modifier.user_select {
            Some(crate::UserSelect::Auto) | None => OwnedAttr::None,
            Some(select) => OwnedAttr::Int(i64::from(u16::from(select))),
        },
    );
    // The layers and the displacement, written only when they are set.
    push(
        false,
        "offset",
        modifier.offset.map_or(OwnedAttr::None, |(x, y)| {
            let mut bytes = Vec::with_capacity(8);
            bytes.extend_from_slice(&x.to_le_bytes());
            bytes.extend_from_slice(&y.to_le_bytes());
            OwnedAttr::Bytes(bytes)
        }),
    );
    push(
        false,
        "shadows",
        if modifier.shadows[0].is_none() {
            OwnedAttr::None
        } else {
            let mut bytes = Vec::new();
            for shadow in modifier.shadows.iter().flatten() {
                for value in [shadow.x, shadow.y, shadow.blur, shadow.spread] {
                    bytes.extend_from_slice(&value.to_le_bytes());
                }
                bytes.extend_from_slice(&shadow.paint.to_bits().to_le_bytes());
            }
            OwnedAttr::Bytes(bytes)
        },
    );
    push(
        false,
        "animated_alpha",
        modifier.animated_alpha.map_or(OwnedAttr::None, |value| {
            OwnedAttr::Bytes(crate::animation::animated_bytes(
                &[value.target.to_bits()],
                &value.spec,
            ))
        }),
    );
    push(
        false,
        "animated_background",
        modifier
            .animated_background
            .map_or(OwnedAttr::None, |value| {
                let bits = value.target.to_bits();
                OwnedAttr::Bytes(crate::animation::animated_bytes(
                    &[bits as u32, (bits >> 32) as u32],
                    &value.spec,
                ))
            }),
    );
    push(
        false,
        "animated_offset",
        modifier.animated_offset.map_or(OwnedAttr::None, |value| {
            OwnedAttr::Bytes(crate::animation::animated_bytes(
                &[value.target.0.to_bits(), value.target.1.to_bits()],
                &value.spec,
            ))
        }),
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

#[cfg(test)]
mod fr19_9_tests {
    use super::*;

    #[test]
    fn fr19_9_window_drag_builder_preserves_false() {
        for enabled in [false, true] {
            let mut attrs = Vec::new();
            push_modifiers(
                &Modifier::new().window_drag(enabled),
                Mods::Standard,
                &mut attrs,
            );
            assert!(attrs.iter().any(|(name, value)| *name == "window_drag"
                && matches!(value, OwnedAttr::Bool(value) if *value == enabled)));
        }
    }
}

#[cfg(test)]
mod fr68_label_tests {
    use super::*;

    #[test]
    fn fr68_repeated_labels_do_not_grow_the_table() {
        let table = LabelTable::new();
        let first = table.intern("Refresh", MAX_LABELS).unwrap();
        for _ in 0..10_000 {
            let again = table.intern("Refresh", MAX_LABELS).unwrap();
            assert!(std::ptr::eq(first, again));
        }
        assert_eq!(table.len(), 1);
    }

    #[test]
    fn fr68_labels_past_the_cap_are_dropped() {
        let table = LabelTable::new();
        for n in 0..MAX_LABELS {
            assert!(table.intern(&format!("label {n}"), MAX_LABELS).is_some());
        }
        assert_eq!(table.len(), MAX_LABELS);
        assert_eq!(table.intern("one too many", MAX_LABELS), None);
        assert_eq!(table.len(), MAX_LABELS);
        // A name already kept is still found.
        assert!(table.intern("label 0", MAX_LABELS).is_some());
    }
}

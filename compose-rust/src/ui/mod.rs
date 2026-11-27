//! The widgets as functions: every widget is one function that takes all of its
//! parameters, and the call macro of the same name that takes them by name with defaults.
//!
//! ```ignore
//! use compose_rust::runtime::*;
//! use compose_rust::ui::*;
//!
//! #[Composable]
//! fn Greeting(name: String) {
//!     Column!(modifier = Modifier.padding(16.0).fill_max_width(), {
//!         Text!(format!("Hello, {name}"), type_role = TypeRole::Title);
//!         Button!("Wave", || show_message("Hello back"));
//!     });
//! }
//! ```
//!
//! **Parameters come in one order**: what the widget cannot do without, its handler, the
//! `modifier`, the optional parameters, and the content last. The function form takes all
//! of them. The macro form takes the leading ones by position and the rest as
//! `name = value` in any order, fills in the defaults, and takes the content as a trailing
//! `{ .. }` block.
//!
//! **An optional value is written bare.** A parameter that can be left unset takes
//! `impl Into<Option<T>>`, so `font_size = 14.0` and `font_size = maybe_size` both work.
//!
//! **An optional handler is an `Option`.** A node without a handler is a different node
//! from one with an empty handler: a `Column` without `on_click` is not a control. So the
//! handler is `Some(closure)` or absent, and `cond.then_some(closure)` makes it depend on
//! something. Inside `Some(..)` the compiler does not infer a closure's parameter type, so
//! a handler that takes an event names it: `on_key_down = Some(|event: KeyEvent| ..)`.
//!
//! **Nothing here defines a widget.** Each function writes exactly the attributes its
//! `rsx!` counterpart writes, through the same translation into wire records, so a screen
//! written either way is the same tree of the same nodes for the Renderer. A Modifier the
//! `rsx!` counterpart does not accept is still written when it is set, because the wire
//! carries every Modifier slot on every node.

pub(crate) mod focus;
mod functions;
mod lazy;
pub(crate) mod rich;
pub(crate) mod text_handle;
pub(crate) mod widgets;

pub use crate::boundary::LaunchBuilder;
pub use crate::brush::{Brush, Stop, brush};
pub use crate::clipboard::{Clipboard, use_clipboard};
pub use crate::drawing::{DrawCommand, DrawList, DrawListBuilder};
pub use crate::font_features::FontFeature;
pub use crate::message::{Message, show_message};
pub use crate::rich_text::{
    CharacterRun, Format, ParagraphFormat, RichDocument, TextFormat, Toggle, Tri,
};
pub use crate::runtime::{application, launch};
pub use crate::schema::SemanticsRole as Role;
pub use crate::schema::{
    Alignment, Arrangement, BackgroundRepeat, ButtonKind, ButtonVariant, Color, ColorRole,
    ColorScheme, CompactNavigation, ContentScale, DesignSystem, HorizontalAlignment, IconRole, Key,
    MaterialRole, MessageDuration, MessageHorizontal, MessagePlacement, MessageVertical,
    MotionRole, Paint, PopoverAlign, PopoverPlacement, SemanticsRole, ShapeRole, SheetPresentation,
    Snap, SpaceRole, TextAlign, TextOverflow, Theme, TypeRole, VerticalAlignment,
    WindowHeightClass, WindowSizeClass,
};
pub use crate::schema::{ListKind, ParagraphRole};
pub use crate::spans::TextSpans;
pub use crate::theme::{ThemeHandle, current_theme};

/// The application's theme, to read and to change while it runs. A change is one
/// `SetTheme` record in the batch the call that made it produces.
pub fn theme() -> ThemeHandle {
    ThemeHandle::default()
}
pub use crate::asset::AssetId;
pub use crate::window::{NodeSize, WindowSafeArea, WindowSize};
pub use crate::windows::{WindowDragEvent, WindowDragOver, WindowFrame};
pub use crate::{
    Cursor, FileDrop, KeyEvent, PointerEvent, PointerKind, PointerKinds, RangeRequest, UserSelect,
    asset, pointer_button,
};
pub use crate::{BackRequested, BackSource, MotionFinished, OverflowWrap, WordBreak};
pub use focus::{FocusHandle, use_focus_handle};
pub use rich::{RichTextState, remember_rich_text_state};
pub use text_handle::{TextFieldHandle, use_text_field_handle};
// Each name is a function and a call macro. The macro is hidden where it is defined, and
// rustdoc leaves out a re-export of a hidden item unless the re-export is inlined, so
// without this `ui` would list the functions and none of the macros.
#[doc(inline)]
pub use functions::{
    Badge, Box, Button, Canvas, Card, Checkbox, Chip, Column, DatePicker, Dialog, Divider,
    Dropdown, FileDropTarget, FloatingAction, FlowRow, Icon, IconAsset, Image,
    IndeterminateProgressIndicator, LazyColumn, LazyGrid, LazyRow, LinearProgressIndicator, Menu,
    MenuItem, NativeView, Navigation, NavigationItem, Popover, ProgressIndicator, RadioButton, Row,
    Scaffold, ScrollColumn, ScrollRow, Select, SelectionContainer, Separator, Sheet, Slider,
    Spacer, SplitPane, Surface, Switch, Tabs, Text, TextField, TimePicker, Tooltip, TopAppBar,
    Window,
};
pub use lazy::LazyListScope;
pub use widgets::{ScrollState, remember_scroll_state};

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

/// The most background brush layers one node can carry through this API.
pub const MAX_BACKGROUND_BRUSHES: usize = 4;

/// One background layer, as CSS draws a `background-image` with its position, size and
/// repeat: a brush laid into a rectangle of the node, in dp from its top left corner, and
/// repeated from there. A gradient is measured against the rectangle, not the node. The
/// layer is cut to the node's outline and painted over its background colour.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BackgroundBrush {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
    pub paint: Paint,
    pub repeat: BackgroundRepeat,
    /// How far apart the copies are, in dp. The rectangle's own size is CSS `repeat`.
    pub tile_width: f32,
    pub tile_height: f32,
}

impl BackgroundBrush {
    /// One copy of `paint` in the rectangle, not repeated.
    pub const fn new(x: f32, y: f32, width: f32, height: f32, paint: Paint) -> Self {
        Self {
            x,
            y,
            width,
            height,
            paint,
            repeat: BackgroundRepeat::NoRepeat,
            tile_width: width,
            tile_height: height,
        }
    }

    /// Repeats the rectangle the given ways, a rectangle's size apart unless
    /// [`BackgroundBrush::step`] says otherwise.
    pub const fn repeat(mut self, repeat: BackgroundRepeat) -> Self {
        self.repeat = repeat;
        self
    }

    /// How far apart the copies are, in dp, for CSS `space` and `round`. A step of zero or
    /// less is not a step, and a layer with one is not sent.
    pub const fn step(mut self, width: f32, height: f32) -> Self {
        self.tile_width = width;
        self.tile_height = height;
        self
    }
}

/// How a node presents itself to assistive technology. Each tri-state field is unset until
/// it is given a value, and an unset one sends nothing. The flags are off until set.
///
/// The type is non-exhaustive, so that a later field does not break code that uses it.
/// Code outside this crate therefore cannot write `SemanticsState { .. }`, with or without
/// `..Default::default()`. It starts from `SemanticsState::default()`, or from the
/// constructor named after the first field it sets, and chains a `with_` method for each
/// further field:
///
/// ```
/// use compose_rust::ui::SemanticsState;
///
/// let nothing = SemanticsState::default();
/// let row = SemanticsState::selected(true).with_disabled(false);
/// let partly = SemanticsState::mixed().with_has_popup(true);
/// assert_eq!(nothing.with_selected(true).with_disabled(false), row);
/// assert!(partly.mixed && partly.has_popup);
/// ```
///
/// The fields stay public, so a state can be read, and a `let mut` one assigned to.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
#[non_exhaustive]
pub struct SemanticsState {
    pub checked: Option<bool>,
    /// Whether the node is mixed: a box standing for a set of which only some are checked.
    /// It wins over `checked`, and is sent as the checked field's fourth value.
    pub mixed: bool,
    pub selected: Option<bool>,
    pub expanded: Option<bool>,
    pub disabled: Option<bool>,
    pub focused: Option<bool>,
    pub hidden: bool,
    pub click_action: bool,
    pub focus_action: bool,
    pub has_popup: bool,
}

impl SemanticsState {
    const NONE: Self = Self {
        checked: None,
        mixed: false,
        selected: None,
        expanded: None,
        disabled: None,
        focused: None,
        hidden: false,
        click_action: false,
        focus_action: false,
        has_popup: false,
    };

    pub const fn checked(checked: bool) -> Self {
        Self {
            checked: Some(checked),
            ..Self::NONE
        }
    }

    /// A node that is neither checked nor unchecked, as a box that stands for a partly
    /// checked set announces itself.
    pub const fn mixed() -> Self {
        Self {
            mixed: true,
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

    pub const fn has_popup(has_popup: bool) -> Self {
        Self {
            has_popup,
            ..Self::NONE
        }
    }

    /// Says whether the node is checked, keeping every other field.
    pub const fn with_checked(mut self, checked: bool) -> Self {
        self.checked = Some(checked);
        self
    }

    /// Says whether the node is mixed, keeping every other field. Mixed wins over `checked`.
    pub const fn with_mixed(mut self, mixed: bool) -> Self {
        self.mixed = mixed;
        self
    }

    /// Says whether the node is selected, keeping every other field.
    pub const fn with_selected(mut self, selected: bool) -> Self {
        self.selected = Some(selected);
        self
    }

    /// Says whether the node is expanded, keeping every other field.
    pub const fn with_expanded(mut self, expanded: bool) -> Self {
        self.expanded = Some(expanded);
        self
    }

    /// Says whether the node is disabled, keeping every other field.
    pub const fn with_disabled(mut self, disabled: bool) -> Self {
        self.disabled = Some(disabled);
        self
    }

    /// Says whether the node is focused, keeping every other field.
    pub const fn with_focused(mut self, focused: bool) -> Self {
        self.focused = Some(focused);
        self
    }

    /// Hides the node from assistive technology or shows it, keeping every other field.
    pub const fn with_hidden(mut self, hidden: bool) -> Self {
        self.hidden = hidden;
        self
    }

    /// Exposes a click action or takes it away, keeping every other field.
    pub const fn with_click_action(mut self, exposed: bool) -> Self {
        self.click_action = exposed;
        self
    }

    /// Exposes a focus action or takes it away, keeping every other field.
    pub const fn with_focus_action(mut self, exposed: bool) -> Self {
        self.focus_action = exposed;
        self
    }

    /// Says whether the node opens a popup, keeping every other field.
    pub const fn with_has_popup(mut self, has_popup: bool) -> Self {
        self.has_popup = has_popup;
        self
    }

    /// The state word the `Semantics` modifier carries.
    pub(crate) fn bits(self) -> u32 {
        use crate::schema::*;
        let tri = |value: Option<bool>| value.map_or(0_u32, |on| if on { 2 } else { 1 });
        let checked = if self.mixed {
            SEMANTICS_CHECKED_MIXED
        } else {
            tri(self.checked)
        };
        (checked << SEMANTICS_CHECKED_SHIFT)
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
            | if self.has_popup {
                SEMANTICS_HAS_POPUP_BIT
            } else {
                0
            }
    }
}

use std::sync::Arc;

/// A bounded cache of recent accessible names.
pub(crate) struct LabelCache {
    entries: Vec<Arc<str>>,
    capacity: usize,
}

pub(crate) const DEFAULT_LABEL_CACHE_CAPACITY: usize = 128;

impl LabelCache {
    pub(crate) fn new() -> Self {
        Self::with_capacity(DEFAULT_LABEL_CACHE_CAPACITY)
    }

    pub(crate) fn with_capacity(capacity: usize) -> Self {
        Self {
            entries: Vec::with_capacity(capacity),
            capacity,
        }
    }

    /// The kept copy of `label`. If present in the cache, it is moved to the most recently
    /// used position and returned with no allocation. Otherwise a new `Arc<str>` is created,
    /// the oldest entry is evicted if full, and the new entry is returned.
    pub(crate) fn intern(&mut self, label: &str) -> Arc<str> {
        if let Some(pos) = self
            .entries
            .iter()
            .position(|entry| entry.as_ref() == label)
        {
            let entry = self.entries.remove(pos);
            self.entries.push(Arc::clone(&entry));
            return entry;
        }
        let entry: Arc<str> = Arc::from(label);
        if self.entries.len() >= self.capacity && self.capacity > 0 {
            self.entries.remove(0);
        }
        if self.capacity > 0 {
            self.entries.push(Arc::clone(&entry));
        }
        entry
    }

    #[cfg(test)]
    pub(crate) fn len(&self) -> usize {
        self.entries.len()
    }
}

fn keep_label(label: &str) -> Option<Arc<str>> {
    use std::cell::RefCell;

    if label.is_empty() {
        return None;
    }

    thread_local! {
        static LAST: RefCell<Option<Arc<str>>> = const { RefCell::new(None) };
        static CACHE: RefCell<LabelCache> = RefCell::new(LabelCache::new());
    }

    if let Some(last) = LAST.with(|cell| {
        let borrowed = cell.borrow();
        if let Some(last) = borrowed.as_ref() {
            if last.as_ref() == label {
                return Some(Arc::clone(last));
            }
        }
        None
    }) {
        return Some(last);
    }

    let kept = CACHE.with(|cache| cache.borrow_mut().intern(label));
    LAST.with(|cell| {
        *cell.borrow_mut() = Some(Arc::clone(&kept));
    });
    Some(kept)
}

/// One side of a node, for [`Modifier::border_side`].
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub enum Side {
    Top,
    /// The side a line of text ends on: the right side.
    End,
    Bottom,
    /// The side a line of text starts on: the left side.
    Start,
}

/// How a pressable node looks while a mouse is over it, for [`Modifier::hover_style`].
///
/// Built as `HoverStyle::fill(paint, alpha).border(paint).content_alpha(a)`, or from
/// `HoverStyle::default()`, which changes nothing: the default hover layer, the node's own
/// border colour and its full opacity.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct HoverStyle {
    /// The hover layer's paint. None keeps the default `OnSurface` layer.
    pub fill: Option<Paint>,
    /// The opacity the hover layer is laid over the node at, from 0 to 1.
    pub fill_alpha: f32,
    /// The colour the node's border takes while the mouse is over it. None keeps it.
    pub border: Option<Paint>,
    /// What the whole node's opacity is multiplied by while the mouse is over it.
    pub content_alpha: f32,
}

impl Default for HoverStyle {
    fn default() -> Self {
        Self {
            fill: None,
            fill_alpha: 0.0,
            border: None,
            content_alpha: 1.0,
        }
    }
}

impl HoverStyle {
    /// A hover layer of `paint` laid over the node at `alpha`.
    pub fn fill(paint: impl Into<Paint>, alpha: f32) -> Self {
        Self {
            fill: Some(paint.into()),
            fill_alpha: alpha,
            ..Self::default()
        }
    }

    /// The border colour while the mouse is over the node.
    pub fn border(mut self, paint: impl Into<Paint>) -> Self {
        self.border = Some(paint.into());
        self
    }

    /// The factor the node's opacity is multiplied by while the mouse is over it.
    pub fn content_alpha(mut self, alpha: f32) -> Self {
        self.content_alpha = alpha;
        self
    }
}

/// The colour and the press-in curve of a pressed surface, for [`Modifier::press_style`].
///
/// `PressStyle::default()` changes nothing.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct PressStyle {
    /// The colour laid over the surface while it is pressed, at the press tint's alpha.
    /// None keeps the node's content colour.
    pub tint: Option<Paint>,
    /// The `cubic-bezier(x1, y1, x2, y2)` the press runs along. None keeps the default
    /// ease-out. The release keeps its own curve either way.
    pub press_easing: Option<[f32; 4]>,
}

impl PressStyle {
    /// A press tint of `paint`.
    pub fn tint(paint: impl Into<Paint>) -> Self {
        Self {
            tint: Some(paint.into()),
            press_easing: None,
        }
    }

    /// The press-in curve, as CSS `cubic-bezier(x1, y1, x2, y2)`.
    pub const fn press_easing(mut self, x1: f32, y1: f32, x2: f32, y2: f32) -> Self {
        self.press_easing = Some([x1, y1, x2, y2]);
        self
    }
}

/// What sits around a widget: its size, its surface, its outline, how far its content
/// is inset, and what the Renderer reports about it. Compose's `Modifier`.
///
/// Written the way Compose writes it, from the empty value `Modifier`:
///
/// ```ignore
/// Card!(
///     modifier = Modifier.fill_max_width().padding(16.0).background(Paint::Role(ColorRole::Surface)),
///     {
///         Text!("Inside");
///     }
/// );
/// ```
///
/// The order of the calls does not matter. Each kind of Modifier has a fixed slot in the
/// node's chain, outside in: size, shape, fill, outline, lift, press, inset. That is what
/// the `rsx!` attributes do too, so the same Modifier draws the same either way.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Modifier {
    observe_size: Option<i64>,
    observe_size_every_dp: bool,
    motion: Option<MotionRole>,
    material: Option<MaterialRole>,
    weight: Option<(f32, bool)>,
    width: Option<f32>,
    height: Option<f32>,
    padding: Option<f32>,
    padding_role: Option<SpaceRole>,
    padding_each: Option<[f32; 4]>,
    background: Option<Paint>,
    shape_role: Option<ShapeRole>,
    corner_radius: Option<f32>,
    corner_radii: Option<[f32; 4]>,
    corner_curve: Option<f32>,
    min_touch_target: Option<f32>,
    overlay: Option<Paint>,
    border_width: Option<f32>,
    border_color: Option<Paint>,
    border_dash: Option<(f32, f32)>,
    border_each: Option<[(f32, Paint); 4]>,
    elevation: Option<f32>,
    alpha: Option<f32>,
    press_feedback: Option<(f32, f32)>,
    hover_style: Option<HoverStyle>,
    focus_ring: Option<(Paint, f32, f32)>,
    press_style: Option<PressStyle>,
    semantics_role: Option<SemanticsRole>,
    semantics_label: Option<Arc<str>>,
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
    background_brushes: [Option<BackgroundBrush>; MAX_BACKGROUND_BRUSHES],
    fill_max_width: bool,
    match_parent_size: bool,
    fill_max_height: bool,
    sticky: bool,
    unbounded_width: bool,
    size_in: Option<[f32; 4]>,
    aspect_ratio: Option<(f32, bool)>,
}

/// The empty Modifier, to start a chain from: `Modifier.padding(8.0)`.
#[allow(non_upper_case_globals)]
pub const Modifier: Modifier = Modifier::new();

impl Modifier {
    /// The empty Modifier. The same as the value `Modifier`.
    pub const fn new() -> Self {
        Self {
            observe_size: None,
            observe_size_every_dp: false,
            motion: None,
            material: None,
            weight: None,
            width: None,
            height: None,
            padding: None,
            padding_role: None,
            padding_each: None,
            background: None,
            shape_role: None,
            corner_radius: None,
            corner_radii: None,
            corner_curve: None,
            min_touch_target: None,
            overlay: None,
            border_width: None,
            border_color: None,
            border_dash: None,
            border_each: None,
            elevation: None,
            alpha: None,
            press_feedback: None,
            hover_style: None,
            focus_ring: None,
            press_style: None,
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
            background_brushes: [None; MAX_BACKGROUND_BRUSHES],
            fill_max_width: false,
            match_parent_size: false,
            fill_max_height: false,
            sticky: false,
            unbounded_width: false,
            size_in: None,
            aspect_ratio: None,
        }
    }

    /// A share of the space left in a `Row` or `Column`.
    pub const fn weight(mut self, weight: f32) -> Self {
        self.weight = Some((weight, true));
        self
    }

    /// A share of the space left in a `Row` or `Column`, without forcing a shorter child
    /// to expand to fill its share (Compose `Modifier.weight(w, fill = false)`).
    pub const fn weight_unfilled(mut self, weight: f32) -> Self {
        self.weight = Some((weight, false));
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

    /// Pins a child to the start edge of a scrolling container (such as ScrollRow)
    /// while the rest of the content scrolls.
    pub const fn sticky(mut self) -> Self {
        self.sticky = true;
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

    /// Measures the node with no maximum width, so [`Modifier::observe_size`] or
    /// [`Modifier::observe_size_every_dp`] reports the width its content asks for instead
    /// of the width its parent offered.
    ///
    /// Only the measurement changes. The node still takes the width its parent offered,
    /// its content starts at the start edge, and whatever is wider than that is clipped.
    /// A table column sized to its widest cell uses it: a cell wider than the column
    /// reports its own width, and the column grows on the next frame.
    pub const fn unbounded_width(mut self) -> Self {
        self.unbounded_width = true;
        self
    }

    /// [`Modifier::unbounded_width`] when `unbounded` is `true`, and nothing when it is
    /// `false`: the node is measured inside its parent's width as usual. The last call of
    /// the two decides.
    pub const fn wrap_content_width(mut self, unbounded: bool) -> Self {
        self.unbounded_width = unbounded;
        self
    }

    /// Constrains the size of the content within minimum and maximum bounds in dp.
    ///
    /// Pass `f32::NAN` for any bound that should remain unconstrained.
    pub const fn size_in(
        mut self,
        min_width: f32,
        max_width: f32,
        min_height: f32,
        max_height: f32,
    ) -> Self {
        self.size_in = Some([min_width, max_width, min_height, max_height]);
        self
    }

    /// Constrains width within minimum and maximum bounds in dp, preserving any height constraints.
    pub const fn width_in(mut self, min: f32, max: f32) -> Self {
        let (min_h, max_h) = match self.size_in {
            Some([_, _, min_h, max_h]) => (min_h, max_h),
            None => (f32::NAN, f32::NAN),
        };
        self.size_in = Some([min, max, min_h, max_h]);
        self
    }

    /// Constrains height within minimum and maximum bounds in dp, preserving any width constraints.
    pub const fn height_in(mut self, min: f32, max: f32) -> Self {
        let (min_w, max_w) = match self.size_in {
            Some([min_w, max_w, _, _]) => (min_w, max_w),
            None => (f32::NAN, f32::NAN),
        };
        self.size_in = Some([min_w, max_w, min, max]);
        self
    }

    /// Constrains maximum width in dp, preserving any height constraints.
    pub const fn max_width(self, max: f32) -> Self {
        self.width_in(f32::NAN, max)
    }

    /// Constrains maximum height in dp, preserving any width constraints.
    pub const fn max_height(self, max: f32) -> Self {
        self.height_in(f32::NAN, max)
    }

    /// Constrains minimum width in dp, preserving any height constraints.
    pub const fn min_width(self, min: f32) -> Self {
        self.width_in(min, f32::NAN)
    }

    /// Constrains minimum height in dp, preserving any width constraints.
    pub const fn min_height(self, min: f32) -> Self {
        self.height_in(min, f32::NAN)
    }

    /// Attempts to size the content to match a specified aspect ratio.
    pub const fn aspect_ratio(mut self, ratio: f32) -> Self {
        self.aspect_ratio = Some((ratio, false));
        self
    }

    /// Attempts to size the content to match a specified aspect ratio, optionally matching height constraints first.
    pub const fn aspect_ratio_match_height(
        mut self,
        ratio: f32,
        match_height_constraints_first: bool,
    ) -> Self {
        self.aspect_ratio = Some((ratio, match_height_constraints_first));
        self
    }

    /// Fills the width only when `fill` is true.
    pub const fn fill_max_width_if(mut self, fill: bool) -> Self {
        self.fill_max_width = fill;
        self
    }

    /// The same inset on every side, in dp.
    pub const fn padding(mut self, dp: f32) -> Self {
        self.padding = Some(dp);
        self.padding_role = None;
        self.padding_each = None;
        self
    }

    /// An inset from the design system's spacing ladder. Wins over `padding`.
    pub const fn padding_role(mut self, role: SpaceRole) -> Self {
        self.padding_role = Some(role);
        self.padding = None;
        self.padding_each = None;
        self
    }

    /// Insets each side of the node's content in dp (start, top, end, bottom).
    pub const fn padding_each(mut self, start: f32, top: f32, end: f32, bottom: f32) -> Self {
        self.padding_each = Some([start, top, end, bottom]);
        self.padding = None;
        self.padding_role = None;
        self
    }

    /// Insets the horizontal and vertical sides of the node's content in dp.
    pub const fn padding_xy(self, horizontal: f32, vertical: f32) -> Self {
        self.padding_each(horizontal, vertical, horizontal, vertical)
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

    /// A paint laid over the node: over its content and its background, in the box its
    /// background fills and cut to the same corners (`corner_radius`, `corner_radii`,
    /// `shape_role`, curved as `corner_curve` says). It takes no room, so a node sized by
    /// its content keeps its size, and nothing has to restate the node's size or radius.
    /// A press tint or a selection wash is one of these; give it a colour with alpha.
    pub const fn overlay(mut self, paint: Paint) -> Self {
        self.overlay = Some(paint);
        self
    }

    /// The same, when there may be no overlay.
    pub const fn overlay_if(mut self, paint: Option<Paint>) -> Self {
        self.overlay = paint;
        self
    }

    /// A shape from the design system's ladder. `ShapeRole::Full` is a pill at whatever
    /// height the node has, so round ends need no radius worked out from the height.
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

    /// The same border on every side. It takes the place of `border_each`,
    /// `border_side` and `border_dashed`.
    pub const fn border(mut self, width: f32, paint: Paint) -> Self {
        self.border_width = Some(width);
        self.border_color = Some(paint);
        self.border_dash = None;
        self.border_each = None;
        self
    }

    /// A dashed border on every side, drawn with dashes of `dash_dp` and gaps of `gap_dp`
    /// along the node's outline. A non-positive dash or gap draws a solid border. It takes
    /// the place of `border`, `border_each` and `border_side`.
    pub fn border_dashed(
        mut self,
        width_dp: f32,
        paint: impl Into<Paint>,
        dash_dp: f32,
        gap_dp: f32,
    ) -> Self {
        self.border_width = Some(width_dp);
        self.border_color = Some(paint.into());
        self.border_dash = Some((dash_dp, gap_dp));
        self.border_each = None;
        self
    }

    /// A border whose sides each have their own width and paint, in CSS order: top,
    /// right, bottom, left. A side of width 0 draws nothing. It takes the place of
    /// `border` and `border_dashed`.
    ///
    /// The sides are the screen's, not the reading direction's: `right` is the right side
    /// in a right-to-left window too.
    pub const fn border_each(
        mut self,
        top: (f32, Paint),
        right: (f32, Paint),
        bottom: (f32, Paint),
        left: (f32, Paint),
    ) -> Self {
        self.border_each = Some([top, right, bottom, left]);
        self.border_width = None;
        self.border_color = None;
        self.border_dash = None;
        self
    }

    /// A border on one side only, such as the guide line beside an indented row. The
    /// other sides have width 0, unless an earlier `border_side` or `border_each` in the
    /// same chain gave them one, so `border_side(Side::Top, ..).border_side(Side::Start,
    /// ..)` draws two sides. It takes the place of `border` and `border_dashed`.
    ///
    /// `Side::Start` is the left side and `Side::End` the right one. The Host lays every
    /// screen out left to right and the Renderer draws a border on the screen's sides, so
    /// a right-to-left window does not move a start border to the right.
    pub const fn border_side(mut self, side: Side, width: f32, paint: Paint) -> Self {
        let mut sides = match self.border_each {
            Some(sides) => sides,
            None => [(0.0, paint); 4],
        };
        let index = match side {
            Side::Top => 0,
            Side::End => 1,
            Side::Bottom => 2,
            Side::Start => 3,
        };
        sides[index] = (width, paint);
        self.border_each = Some(sides);
        self.border_width = None;
        self.border_color = None;
        self.border_dash = None;
        self
    }

    /// A resting height above the default, in dp. How it is drawn is the design system's.
    pub const fn elevation(mut self, dp: f32) -> Self {
        self.elevation = Some(dp);
        self
    }

    /// Asks the Renderer to report this node's size under the token from
    /// [`crate::runtime::remember_node_size`].
    ///
    /// A report goes out on the first measurement and then whenever the width or height
    /// class changes. A resize inside one class says nothing; use
    /// [`Modifier::observe_size_every_dp`] for that.
    pub const fn observe_size(mut self, token: i64) -> Self {
        self.observe_size = Some(token);
        self.observe_size_every_dp = false;
        self
    }

    /// Like [`Modifier::observe_size`], and also reports whenever the width or height,
    /// rounded to the nearest whole dp, changes inside one class. Several changes in one
    /// frame are one report carrying the last size.
    pub const fn observe_size_every_dp(mut self, token: i64) -> Self {
        self.observe_size = Some(token);
        self.observe_size_every_dp = true;
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

    /// Adds a background brush layer. Layers are drawn in the order they are added, the
    /// first at the bottom, all of them over the background colour. A node takes at most
    /// [`MAX_BACKGROUND_BRUSHES`]; a layer past that is ignored.
    pub const fn background_brush(mut self, layer: BackgroundBrush) -> Self {
        let mut index = 0;
        while index < MAX_BACKGROUND_BRUSHES {
            if self.background_brushes[index].is_none() {
                self.background_brushes[index] = Some(layer);
                break;
            }
            index += 1;
        }
        self
    }

    /// Adds several background brush layers, in order. CSS writes its list top first, so
    /// a list taken from CSS is added last to first.
    pub fn background_brushes(mut self, layers: impl IntoIterator<Item = BackgroundBrush>) -> Self {
        for layer in layers {
            self = self.background_brush(layer);
        }
        self
    }

    /// An opacity for the node and everything in it, faded as one group.
    pub const fn alpha(mut self, alpha: f32) -> Self {
        self.alpha = Some(alpha);
        self
    }

    /// How the node's rounded corners curve, from 0 (a circular arc, the default) to 1.
    /// Above 0 the curve eases in from each side with continuously changing curvature, as
    /// Figma's corner smoothing does; 0.6 is the look of iOS. The radius stays whatever
    /// `corner_radius`, `corner_radii` or `shape_role` made it, and the background, border,
    /// shadow and clip all follow the same curve. A value outside 0..=1 is taken as the
    /// nearer end, and NaN as 0.
    pub fn corner_curve(mut self, smoothing: f32) -> Self {
        self.corner_curve = Some(crate::schema::corner_smoothing(smoothing));
        self
    }

    /// The least size, in dp, of the area that takes this node's presses. A node drawn
    /// smaller, such as a 26 by 32 dp icon button, takes presses over a box grown to this
    /// size around it. Its size, its place and how it is drawn stay as they are, so nothing
    /// laid out beside it moves. Compose's `minimumInteractiveComponentSize` does the same
    /// for Material's controls; 44 dp is the usual target for a finger. Off unless set.
    pub const fn min_touch_target(mut self, dp: f32) -> Self {
        self.min_touch_target = Some(dp);
        self
    }

    /// Sets the pressed scale and tint opacity.
    pub const fn press_feedback(mut self, scale: f32, tint_alpha: f32) -> Self {
        self.press_feedback = Some((scale, tint_alpha));
        self
    }

    /// How this pressable node looks while a mouse is over it: its hover layer, its border
    /// colour and its opacity. A node without it keeps the default hover layer. Hovering
    /// sends nothing to the Host.
    pub const fn hover_style(mut self, style: HoverStyle) -> Self {
        self.hover_style = Some(style);
        self
    }

    /// The ring this pressable node draws while it has keyboard focus: its paint, its
    /// width in dp, and how far outside the node's outline it sits in dp. At 0 the ring is
    /// drawn just inside the outline; above 0 it is drawn that far outside it, with its
    /// corners grown by the same amount, as CSS `outline-offset` does. The ring takes no
    /// room, so nothing around the node moves. A node without it draws a 2 dp `Primary`
    /// ring inside its outline.
    pub fn focus_ring(mut self, paint: impl Into<Paint>, width_dp: f32, offset_dp: f32) -> Self {
        self.focus_ring = Some((paint.into(), width_dp, offset_dp));
        self
    }

    /// The colour laid over this node while it is pressed, and the curve the press runs
    /// along. The tint's alpha is still the one `press_feedback` gives, or the default.
    pub const fn press_style(mut self, style: PressStyle) -> Self {
        self.press_style = Some(style);
        self
    }

    /// The name assistive technology reads for this node. On a widget with a visible label
    /// it replaces that label; an empty name is the same as none.
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

    /// Whether the node opens a popup, as a menu button announces it.
    pub const fn has_popup(mut self, has_popup: bool) -> Self {
        self.semantics_state.has_popup = has_popup;
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
    // One slot, two names: whichever is not in use goes out unset, which clears nothing
    // because only the name that wrote a slot may clear it.
    let (plain, every_dp) = match modifier.observe_size {
        Some(token) if modifier.observe_size_every_dp => (OwnedAttr::None, OwnedAttr::Int(token)),
        Some(token) => (OwnedAttr::Int(token), OwnedAttr::None),
        None => (OwnedAttr::None, OwnedAttr::None),
    };
    push(container, "observe_size", plain);
    push(container, "observe_size_every_dp", every_dp);
    push(container, "motion", role(modifier.motion));
    push(container || scaffold, "material", role(modifier.material));
    // One slot, two names: whichever is not in use goes out unset, which clears nothing
    // because only the name that wrote a slot may clear it.
    let (weight_filled, weight_unfilled) = match modifier.weight {
        Some((value, true)) => (float(Some(value)), OwnedAttr::None),
        Some((value, false)) => (OwnedAttr::None, float(Some(value))),
        None => (OwnedAttr::None, OwnedAttr::None),
    };
    push(chip, "weight", weight_filled);
    push(chip, "weight_unfilled", weight_unfilled);
    push(chip, "width", float(modifier.width));
    push(chip, "height", float(modifier.height));
    push(chip, "padding", float(modifier.padding));
    push(chip, "padding_role", role(modifier.padding_role));
    push(
        false,
        "padding_each",
        modifier.padding_each.map_or(OwnedAttr::None, |values| {
            OwnedAttr::Bytes(
                values
                    .iter()
                    .flat_map(|value| value.to_le_bytes())
                    .collect(),
            )
        }),
    );
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
    push(false, "corner_curve", float(modifier.corner_curve));
    push(false, "min_touch_target", float(modifier.min_touch_target));
    push(false, "overlay", paint(modifier.overlay));
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
    push(
        false,
        "border_dash",
        modifier.border_dash.map_or(OwnedAttr::None, |(dash, gap)| {
            OwnedAttr::Bytes([dash.to_le_bytes(), gap.to_le_bytes()].concat())
        }),
    );
    // Four widths and then four paints, written only when set. It shares the border's
    // slot, after the plain border, so whichever of the two the chain holds is the one
    // the slot ends up with.
    push(
        false,
        "border_each",
        modifier.border_each.map_or(OwnedAttr::None, |sides| {
            let mut bytes = Vec::with_capacity(48);
            for (width, _) in sides {
                bytes.extend_from_slice(&width.to_le_bytes());
            }
            for (_, paint) in sides {
                bytes.extend_from_slice(&paint.to_bits().to_le_bytes());
            }
            OwnedAttr::Bytes(bytes)
        }),
    );
    push(standard, "elevation", float(modifier.elevation));
    push(
        false,
        "match_parent_size",
        OwnedAttr::Bool(modifier.match_parent_size),
    );
    push(false, "sticky", OwnedAttr::Bool(modifier.sticky));
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
    push(
        false,
        "unbounded_width",
        OwnedAttr::Bool(modifier.unbounded_width),
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
    // Two paints, a zero word for none, and then the two alphas.
    push(
        false,
        "hover_style",
        modifier.hover_style.map_or(OwnedAttr::None, |style| {
            let mut bytes = Vec::with_capacity(24);
            bytes.extend_from_slice(&style.fill.map_or(0, Paint::to_bits).to_le_bytes());
            bytes.extend_from_slice(&style.border.map_or(0, Paint::to_bits).to_le_bytes());
            bytes.extend_from_slice(&style.fill_alpha.to_le_bytes());
            bytes.extend_from_slice(&style.content_alpha.to_le_bytes());
            OwnedAttr::Bytes(bytes)
        }),
    );
    push(
        false,
        "focus_ring",
        modifier
            .focus_ring
            .map_or(OwnedAttr::None, |(paint, width, offset)| {
                let mut bytes = Vec::with_capacity(16);
                bytes.extend_from_slice(&paint.to_bits().to_le_bytes());
                bytes.extend_from_slice(&width.to_le_bytes());
                bytes.extend_from_slice(&offset.to_le_bytes());
                OwnedAttr::Bytes(bytes)
            }),
    );
    // The tint, a zero word for none, and then the four curve points, the first NaN for
    // no curve.
    push(
        false,
        "press_style",
        modifier.press_style.map_or(OwnedAttr::None, |style| {
            let mut bytes = Vec::with_capacity(24);
            bytes.extend_from_slice(&style.tint.map_or(0, Paint::to_bits).to_le_bytes());
            for point in style.press_easing.unwrap_or([f32::NAN, 0.0, 0.0, 0.0]) {
                bytes.extend_from_slice(&point.to_le_bytes());
            }
            OwnedAttr::Bytes(bytes)
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
            .as_ref()
            .map_or(OwnedAttr::None, |label| OwnedAttr::Label(Arc::clone(label))),
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
        "background_brushes",
        if modifier.background_brushes[0].is_none() {
            OwnedAttr::None
        } else {
            let mut bytes = Vec::new();
            for layer in modifier.background_brushes.iter().flatten() {
                for value in [layer.x, layer.y, layer.width, layer.height] {
                    bytes.extend_from_slice(&value.to_le_bytes());
                }
                bytes.extend_from_slice(&layer.paint.to_bits().to_le_bytes());
                bytes.extend_from_slice(&u32::from(u16::from(layer.repeat)).to_le_bytes());
                bytes.extend_from_slice(&layer.tile_width.to_le_bytes());
                bytes.extend_from_slice(&layer.tile_height.to_le_bytes());
                // Padding to the layer's 40 bytes, so a layer starts on an eight byte
                // boundary as the writer reads it.
                bytes.extend_from_slice(&[0; 4]);
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
    push(
        false,
        "size_in",
        modifier
            .size_in
            .map_or(OwnedAttr::None, |[min_w, max_w, min_h, max_h]| {
                let mut bytes = Vec::with_capacity(16);
                bytes.extend_from_slice(&min_w.to_le_bytes());
                bytes.extend_from_slice(&max_w.to_le_bytes());
                bytes.extend_from_slice(&min_h.to_le_bytes());
                bytes.extend_from_slice(&max_h.to_le_bytes());
                OwnedAttr::Bytes(bytes)
            }),
    );
    push(
        false,
        "aspect_ratio",
        modifier
            .aspect_ratio
            .map_or(OwnedAttr::None, |(ratio, match_height)| {
                let mut bytes = Vec::with_capacity(5);
                bytes.extend_from_slice(&ratio.to_le_bytes());
                bytes.push(u8::from(match_height));
                OwnedAttr::Bytes(bytes)
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
    fn fr68_repeated_labels_do_not_grow_the_cache() {
        let mut cache = LabelCache::new();
        let first = cache.intern("Refresh");
        for _ in 0..10_000 {
            let again = cache.intern("Refresh");
            assert!(Arc::ptr_eq(&first, &again));
        }
        assert_eq!(cache.len(), 1);
    }

    #[test]
    fn fr68_cache_evicts_old_entries_without_dropping_new_labels() {
        let mut cache = LabelCache::with_capacity(32);
        for n in 0..100 {
            let label = cache.intern(&format!("label {n}"));
            assert_eq!(label.as_ref(), format!("label {n}"));
        }
        assert_eq!(cache.len(), 32);
        // An old entry evicted earlier can still be interned again as new.
        let re_interned = cache.intern("label 0");
        assert_eq!(re_interned.as_ref(), "label 0");
        assert_eq!(cache.len(), 32);
    }
}

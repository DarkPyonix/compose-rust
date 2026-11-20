//! The widgets as functions: every widget is one function that takes all of its
//! parameters, and the call macro of the same name that takes them by name with defaults.
//!
//! ```ignore
//! use compose_rust::runtime::*;
//! use compose_rust::ui::next::*;
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
//! Everything else in [`crate::ui`] is re-exported here unchanged.

#![allow(non_snake_case, clippy::too_many_arguments)]

pub use super::*;
// The types an optional handler's closure has to name, and the two break rules `ui` does
// not re-export.
pub use crate::windows::{WindowDragEvent, WindowDragOver, WindowFrame};
pub use crate::{MotionFinished, OverflowWrap, WordBreak};

mod controls;
mod layout;
mod lazy;
mod navigation;
mod overlays;
mod text;
mod window;

pub use controls::{
    Badge, Button, Canvas, Checkbox, Chip, DatePicker, Divider, Dropdown, FloatingAction, Icon,
    IconAsset, Image, IndeterminateProgressIndicator, LinearProgressIndicator, MenuItem,
    ProgressIndicator, RadioButton, Select, Separator, Slider, Switch, TimePicker,
};
pub use layout::{
    Box, Card, Column, FlowRow, Row, ScrollColumn, ScrollRow, SelectionContainer, Spacer, Surface,
};
pub use lazy::{LazyColumn, LazyGrid, LazyRow};
pub use navigation::{Navigation, NavigationItem, Scaffold, SplitPane, Tabs, TopAppBar};
pub use overlays::{Dialog, FileDropTarget, Menu, NativeView, Popover, Sheet, Tooltip};
pub use text::{Text, TextField};
pub use window::Window;

/// Applies each named `impl Into<Option<T>>` parameter to the builder when it is set.
macro_rules! set {
    ($scope:ident; $($name:ident),* $(,)?) => {
        $(
            if let Some(value) = $name.into() {
                $scope = $scope.$name(value);
            }
        )*
    };
}
pub(crate) use set;

/// Applies each named `Option` parameter (a handler or a slot) to the builder when it is
/// there.
macro_rules! set_some {
    ($scope:ident; $($name:ident),* $(,)?) => {
        $(
            if let Some(value) = $name {
                $scope = $scope.$name(value);
            }
        )*
    };
}
pub(crate) use set_some;

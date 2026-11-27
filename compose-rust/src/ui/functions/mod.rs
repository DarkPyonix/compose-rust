//! The widgets as functions and call macros. `compose_rust::ui` re-exports every one; the
//! rules they follow are in that module's documentation.

#![allow(non_snake_case, clippy::too_many_arguments)]

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
    ProgressIndicator, RadioButton, Select, Separator, Slider, Submenu, Switch, TimePicker,
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

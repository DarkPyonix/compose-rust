//! Layout containers.

use super::{set, set_some};
use crate::schema::{
    Alignment, Arrangement, HorizontalAlignment, Snap, SpaceRole, VerticalAlignment,
};
use crate::ui::{FocusHandle, Modifier, ScrollState};
use crate::windows::WindowDragOver;
use crate::{BackRequested, KeyEvent, MotionFinished, PointerEvent, PointerKinds};
use compose_rust_macros::Composable;

/// A container that can be a control: the parameters every one of them takes after its
/// own, and the content.
///
/// `on_back_requested` hears a request to go back that no open overlay and no focused
/// control took: Android's back, Escape, a mouse's back button or an iOS edge swipe. The
/// innermost container with a handler hears it first. Calling `consume` on the event ends
/// it there; leaving it passes it to the next handler out and then to the platform.
///
/// `tear_off` adds the two parameters of a node the user can pull out into a window.
macro_rules! container {
    (
        $(#[$doc:meta])*
        $name:ident, tear_off: $tear_off:tt,
        params: { $($(#[$attr:meta])* $param:ident: $ty:ty,)* },
        apply: |$scope:ident| $apply:block
    ) => {
        container!(@function
            $(#[$doc])*
            $name,
            params: { $($(#[$attr])* $param: $ty,)* },
            tear_off: $tear_off,
            apply: |$scope| $apply
        );
    };
    (@function
        $(#[$doc:meta])*
        $name:ident,
        params: { $($(#[$attr:meta])* $param:ident: $ty:ty,)* },
        tear_off: true,
        apply: |$scope:ident| $apply:block
    ) => {
        $(#[$doc])*
        #[Composable(widget)]
        pub fn $name<'a>(
            modifier: Modifier,
            $($(#[$attr])* $param: $ty,)*
            #[default(None::<fn()>)] on_click: Option<impl FnMut() + 'static>,
            #[default(None::<(::compose_rust::PointerKinds, fn(::compose_rust::PointerEvent))>)]
            on_pointer: Option<(PointerKinds, impl FnMut(PointerEvent) + 'static)>,
            #[default(None::<fn()>)] on_focus_gained: Option<impl FnMut() + 'static>,
            #[default(None::<fn()>)] on_focus_lost: Option<impl FnMut() + 'static>,
            #[default(None::<fn(::compose_rust::KeyEvent)>)]
            on_key_down: Option<impl FnMut(KeyEvent) + 'static>,
            #[default(None::<fn(::compose_rust::KeyEvent)>)]
            on_key_up: Option<impl FnMut(KeyEvent) + 'static>,
            #[default(None::<&::compose_rust::ui::FocusHandle>)]
            focus_handle: impl Into<Option<&'a FocusHandle>>,
            #[default(None::<fn(::compose_rust::MotionFinished)>)]
            on_motion_finished: Option<impl FnMut(MotionFinished) + 'static>,
            #[default(None::<fn(::compose_rust::BackRequested)>)]
            on_back_requested: Option<impl FnMut(BackRequested) + 'static>,
            #[default(None::<&str>)] tear_off_key: impl Into<Option<&'a str>>,
            #[default(None::<fn(::compose_rust::windows::WindowDragOver)>)]
            on_window_drag_over: Option<impl FnMut(WindowDragOver) + 'static>,
            #[default(|| {})] content: impl FnOnce(),
        ) {
            let mut $scope = crate::ui::widgets::$name().modifier(modifier);
            $apply;
            if let Some((kinds, handler)) = on_pointer {
                $scope = $scope.on_pointer(kinds, handler);
            }
            set!($scope; focus_handle, tear_off_key);
            set_some!(
                $scope;
                on_click,
                on_focus_gained,
                on_focus_lost,
                on_key_down,
                on_key_up,
                on_motion_finished,
                on_back_requested,
                on_window_drag_over,
            );
            $scope.content(content);
        }
    };
    (@function
        $(#[$doc:meta])*
        $name:ident,
        params: { $($(#[$attr:meta])* $param:ident: $ty:ty,)* },
        tear_off: false,
        apply: |$scope:ident| $apply:block
    ) => {
        $(#[$doc])*
        #[Composable(widget)]
        pub fn $name<'a>(
            modifier: Modifier,
            $($(#[$attr])* $param: $ty,)*
            #[default(None::<fn()>)] on_click: Option<impl FnMut() + 'static>,
            #[default(None::<(::compose_rust::PointerKinds, fn(::compose_rust::PointerEvent))>)]
            on_pointer: Option<(PointerKinds, impl FnMut(PointerEvent) + 'static)>,
            #[default(None::<fn()>)] on_focus_gained: Option<impl FnMut() + 'static>,
            #[default(None::<fn()>)] on_focus_lost: Option<impl FnMut() + 'static>,
            #[default(None::<fn(::compose_rust::KeyEvent)>)]
            on_key_down: Option<impl FnMut(KeyEvent) + 'static>,
            #[default(None::<fn(::compose_rust::KeyEvent)>)]
            on_key_up: Option<impl FnMut(KeyEvent) + 'static>,
            #[default(None::<&::compose_rust::ui::FocusHandle>)]
            focus_handle: impl Into<Option<&'a FocusHandle>>,
            #[default(None::<fn(::compose_rust::MotionFinished)>)]
            on_motion_finished: Option<impl FnMut(MotionFinished) + 'static>,
            #[default(None::<fn(::compose_rust::BackRequested)>)]
            on_back_requested: Option<impl FnMut(BackRequested) + 'static>,
            #[default(|| {})] content: impl FnOnce(),
        ) {
            let mut $scope = crate::ui::widgets::$name().modifier(modifier);
            $apply;
            if let Some((kinds, handler)) = on_pointer {
                $scope = $scope.on_pointer(kinds, handler);
            }
            set!($scope; focus_handle);
            set_some!(
                $scope;
                on_click,
                on_focus_gained,
                on_focus_lost,
                on_key_down,
                on_key_up,
                on_motion_finished,
                on_back_requested,
            );
            $scope.content(content);
        }
    };
}

/// The parameters `Column` and `Row` share.
macro_rules! linear {
    ($(#[$doc:meta])* $name:ident) => {
        container! {
            $(#[$doc])*
            $name, tear_off: true,
            params: {
                #[default(None::<::compose_rust::ui::Arrangement>)]
                arrangement: impl Into<Option<Arrangement>>,
                #[default(None::<f32>)] spacing: impl Into<Option<f32>>,
                #[default(None::<::compose_rust::ui::SpaceRole>)]
                space_role: impl Into<Option<SpaceRole>>,
                #[default(None::<::compose_rust::ui::Alignment>)]
                alignment: impl Into<Option<Alignment>>,
                #[default(None::<bool>)] intrinsic_size: impl Into<Option<bool>>,
                #[default(false)] radio_group: bool,
            },
            apply: |scope| {
                set!(scope; arrangement, spacing, space_role, alignment, intrinsic_size);
                scope = scope.radio_group(radio_group);
            }
        }
    };
}

linear! {
    /// Lays its children out one below another. Compose's `Column`.
    ///
    /// `arrangement` is how the children share the main axis, `spacing` a literal gap in
    /// dp and `space_role` a gap from the design system's ladder, `alignment` where they
    /// sit on the cross axis. `intrinsic_size` sizes the column to its widest child, so
    /// children with `fill_max_width` match it. `radio_group` makes it a group of the
    /// `RadioButton`s inside: one stop for Tab, with the arrow keys moving the selection.
    ///
    /// With `on_click` the column is a button: it takes focus, runs the handler on a click
    /// and on Enter or Space, and is announced as a button. Without it the column is not a
    /// control.
    Column
}

linear! {
    /// Lays its children out one after another. Compose's `Row`.
    ///
    /// The parameters are `Column`'s, with the axes exchanged: `intrinsic_size` sizes the
    /// row to its tallest child, so children with `fill_max_height` match it.
    Row
}

container! {
    /// Stacks its children on top of each other. Compose's `Box`. `alignment` is where
    /// the children sit inside it.
    Box, tear_off: true,
    params: {
        #[default(None::<::compose_rust::ui::Alignment>)]
        alignment: impl Into<Option<Alignment>>,
    },
    apply: |scope| {
        set!(scope; alignment);
    }
}

/// The parameters `ScrollColumn` and `ScrollRow` share.
macro_rules! scroll {
    ($(#[$doc:meta])* $name:ident) => {
        container! {
            $(#[$doc])*
            $name, tear_off: false,
            params: {
                #[default(None::<&::compose_rust::ui::ScrollState>)]
                scroll_state: impl Into<Option<&'a ScrollState>>,
                #[default(::compose_rust::ui::Snap::None)] snap: Snap,
            },
            apply: |scope| {
                set!(scope; scroll_state);
                scope = scope.snap(snap);
            }
        }
    };
}

scroll! {
    /// All of its content, with a vertical scroll the Renderer owns.
    ///
    /// `scroll_state` binds a [`ScrollState`] to read the offset, the furthest offset and
    /// the size of the viewport, and to move it. `snap` is
    /// where the content comes to rest once a drag or a fling ends.
    ScrollColumn
}

scroll! {
    /// All of its content, with a horizontal scroll the Renderer owns. The parameters are
    /// `ScrollColumn`'s.
    ScrollRow
}

container! {
    /// A grouped container. What it looks like is the design system's.
    Card, tear_off: false,
    params: {},
    apply: |scope| {}
}

container! {
    /// A plain background and elevation, without a card's meaning.
    Surface, tear_off: false,
    params: {},
    apply: |scope| {}
}

container! {
    /// Every `Text` inside can be selected and copied as one selection.
    SelectionContainer, tear_off: false,
    params: {},
    apply: |scope| {}
}

container! {
    /// Empty space, sized by its Modifier.
    Spacer, tear_off: false,
    params: {},
    apply: |scope| {}
}

/// Lays its children out in rows and starts a new row when one is full. Compose's
/// `FlowRow`.
///
/// The two spacings are gaps in dp between the children of a row and between the rows.
/// The two alignments place the children of a row and the rows. `max_items_in_each_row`
/// starts a new row after that many children even when more would fit.
#[Composable(widget)]
pub fn FlowRow(
    modifier: Modifier,
    #[default(None::<f32>)] horizontal_spacing: impl Into<Option<f32>>,
    #[default(None::<f32>)] vertical_spacing: impl Into<Option<f32>>,
    #[default(None::<::compose_rust::ui::HorizontalAlignment>)] horizontal_alignment: impl Into<
        Option<HorizontalAlignment>,
    >,
    #[default(None::<::compose_rust::ui::VerticalAlignment>)] vertical_alignment: impl Into<
        Option<VerticalAlignment>,
    >,
    #[default(None::<u32>)] max_items_in_each_row: impl Into<Option<u32>>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(|| {})] content: impl FnOnce(),
) {
    let mut scope = crate::ui::widgets::FlowRow().modifier(modifier);
    set!(
        scope;
        horizontal_spacing,
        vertical_spacing,
        horizontal_alignment,
        vertical_alignment,
        max_items_in_each_row,
    );
    set_some!(scope; on_motion_finished);
    scope.content(content);
}

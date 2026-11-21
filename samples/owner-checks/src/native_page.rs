//! The native view screen: a platform text field placed inside the window, beside a Compose
//! one, so an input method can be watched going to the right one.
//!
//! In 1.0 a platform view can be placed only by the macOS Kotlin/Native renderer. Elsewhere
//! the screen says so instead of placing anything.

use crate::header;
#[cfg(target_os = "macos")]
use crate::{reading, steps};
use compose_rust::runtime::*;
use compose_rust::ui::*;

/// What the screen says where no platform view can be placed.
#[cfg(not(target_os = "macos"))]
pub(crate) const NOT_ON_THIS_PLATFORM: &str = "Not on this platform: in 1.0 a platform view \
     can be placed in the window only on macOS. Run the macOS download for this check.";

#[cfg(not(target_os = "macos"))]
pub(crate) fn native_view_page(_focused: MutableState<bool>, _compose_field: MutableState<String>) {
    header(
        "Native view",
        "A platform text field placed inside the window, beside a Compose one.",
    );
    Text!(NOT_ON_THIS_PLATFORM, type_role = TypeRole::BodyStrong);
}

#[cfg(target_os = "macos")]
pub(crate) fn native_view_page(focused: MutableState<bool>, compose_field: MutableState<String>) {
    use compose_rust::native_view::{native_parent, subscribe_native_parent};
    use compose_rust::{NativeHandle, NativeParentKind};
    use std::sync::Arc;

    // The window reports the view its platform views are made against once a NativeView
    // is in it, so the node goes in first with no view, and the view is made when the
    // report arrives.
    let parent = remember(|| mutable_state_of(native_parent(0)));
    disposable_effect((), move || {
        let subscription = subscribe_native_parent(Arc::new(move || parent.set(native_parent(0))));
        on_dispose(move || drop(subscription))
    });
    let reported = parent.get();
    let appkit = reported
        .filter(|report| report.kind == NativeParentKind::AppKit && !report.handle.is_none())
        .map(|report| report.handle);

    // The text field is this screen's: made when the window has reported, and given up
    // when the screen goes. The Renderer only places it and never destroys it, and the
    // window keeps its own hold on it until the batch that removes the node takes it out.
    let view = remember(|| mutable_state_of(NativeHandle::NONE));
    disposable_effect(appkit, move || {
        let made = if appkit.is_some() {
            crate::appkit::text_field(c"AppKit field: type Korean here")
        } else {
            NativeHandle::NONE
        };
        view.set(made);
        on_dispose(move || crate::appkit::release(made))
    });
    let handle = view.get();

    header(
        "Native view",
        "A platform text field (AppKit's NSTextField, not Compose) placed inside the \
         window, beside a Compose field.",
    );
    steps(
        "Do this, and see this",
        &[
            "Wait until the line below says Attached. A grey field appears under it.",
            "Click the grey field. Native field has keyboard focus says yes.",
            "Switch to a Korean input source and type 한국어. The composition and the \
             candidate window appear in the grey field.",
            "Compose field text, further down, stays empty while you do.",
            "Click Compose field and type 한국어 again. It composes there as usual, and \
             Native field has keyboard focus says no.",
        ],
    );
    reading(match (reported, handle.is_none()) {
        (None, _) => String::from(
            "Waiting for the window to report its view. If this line stays, the renderer \
             in this build has no slot for platform views.",
        ),
        (Some(report), false) => format!(
            "Attached: an AppKit text field, window scale {}",
            report.scale
        ),
        (Some(report), true) => format!(
            "The window reported a {:?} view; none is attached",
            report.kind
        ),
    });
    reading(format!(
        "Native field has keyboard focus: {}",
        if focused.get() { "yes" } else { "no" }
    ));
    NativeView!(
        handle,
        modifier = Modifier
            .fill_max_width()
            .height(32.0)
            .background(Paint::Role(ColorRole::SurfaceVariant)),
        on_focus_change = Some(move |now: bool| focused.set(now))
    );
    TextField!(
        "",
        move |text| compose_field.set(text),
        placeholder = "Compose field",
        modifier = Modifier.fill_max_width().label("Compose field")
    );
    reading(format!("Compose field text: {}", compose_field.get()));
}

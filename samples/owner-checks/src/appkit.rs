//! The one platform view this sample makes: an AppKit text field for the native view screen.
//!
//! An application makes its platform views itself and hands the Renderer a handle; the
//! Renderer places them and never makes or destroys one. A real application would use a
//! binding crate for this. The sample needs four messages, so it sends them through the
//! Objective-C runtime the system already has rather than taking a dependency.
//!
//! Every call here runs on the main thread: the Host runs on the Renderer's UI thread,
//! which on macOS is the main thread, and AppKit views may only be made there.

// `objc_msgSend` is one symbol called with a different signature per message, which is
// how the Objective-C runtime is called from C as well. Each signature below is the
// message's own.
#![allow(clashing_extern_declarations)]

use compose_rust::NativeHandle;
use std::ffi::{CStr, c_char, c_void};

type Id = *mut c_void;
type Sel = *mut c_void;

/// `CGRect`: an origin and a size, four doubles in that order.
#[repr(C)]
#[derive(Clone, Copy)]
struct Rect {
    x: f64,
    y: f64,
    width: f64,
    height: f64,
}

#[link(name = "AppKit", kind = "framework")]
unsafe extern "C" {}

#[link(name = "objc")]
unsafe extern "C" {
    fn objc_getClass(name: *const c_char) -> Id;
    fn sel_registerName(name: *const c_char) -> Sel;
    #[link_name = "objc_msgSend"]
    fn send(receiver: Id, selector: Sel) -> Id;
    #[link_name = "objc_msgSend"]
    fn send_rect(receiver: Id, selector: Sel, rect: Rect) -> Id;
    #[link_name = "objc_msgSend"]
    fn send_text(receiver: Id, selector: Sel, text: *const c_char) -> Id;
    #[link_name = "objc_msgSend"]
    fn send_object(receiver: Id, selector: Sel, object: Id);
    #[link_name = "objc_msgSend"]
    fn send_void(receiver: Id, selector: Sel);
}

fn selector(name: &CStr) -> Sel {
    // SAFETY: a NUL-terminated selector name; registering one has no other effect.
    unsafe { sel_registerName(name.as_ptr()) }
}

/// An editable `NSTextField` showing `placeholder` until something is typed, owned by the
/// caller (one retain), or no handle if AppKit would not make one. The Renderer sets its
/// frame, so it starts with an empty one.
pub(crate) fn text_field(placeholder: &CStr) -> NativeHandle {
    // SAFETY: each message is sent with the signature AppKit declares for it, to an object
    // checked to exist, on the main thread.
    unsafe {
        let class = objc_getClass(c"NSTextField".as_ptr());
        if class.is_null() {
            return NativeHandle::NONE;
        }
        let empty = Rect {
            x: 0.0,
            y: 0.0,
            width: 0.0,
            height: 0.0,
        };
        let field = send_rect(
            send(class, selector(c"alloc")),
            selector(c"initWithFrame:"),
            empty,
        );
        if field.is_null() {
            return NativeHandle::NONE;
        }
        let strings = objc_getClass(c"NSString".as_ptr());
        if !strings.is_null() {
            let text = send_text(
                send(strings, selector(c"alloc")),
                selector(c"initWithUTF8String:"),
                placeholder.as_ptr(),
            );
            if !text.is_null() {
                send_object(field, selector(c"setPlaceholderString:"), text);
                send_void(text, selector(c"release"));
            }
        }
        NativeHandle(field as u64)
    }
}

/// Gives up the caller's hold on a view `text_field` made. A window that still shows it
/// keeps its own hold until the view is taken out of it.
pub(crate) fn release(view: NativeHandle) {
    if view.is_none() {
        return;
    }
    // SAFETY: `view` is a live object this module made and the caller has not released.
    unsafe { send_void(view.0 as Id, selector(c"release")) }
}

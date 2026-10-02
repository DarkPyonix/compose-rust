//! Sparkle 2, loaded from the bundle at run time and driven through the Objective-C
//! runtime.
//!
//! Why the Host and not the Renderer: Sparkle is an Objective-C framework, and the Rust
//! side can call it directly through objc2. The Renderer is a GraalVM native image, from
//! which the same calls would need hand-written JNI or C glue, which this project does not
//! write. Nothing crosses the Host/Renderer boundary for this either: Sparkle draws its
//! own AppKit windows, and the only thing the application's UI does is ask for a check,
//! which is a plain Rust function call from an event handler.
//!
//! Threads: everything Sparkle is told happens on the main thread, where the renderer's
//! C entry runs `[NSApp run]`. Work is put on the main dispatch queue, which that run loop
//! drains, so it does not matter which thread asked or whether AppKit is running yet.

use std::ffi::c_void;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicPtr, Ordering};

use block2::{Block, RcBlock};
use objc2::rc::{Allocated, Retained};
use objc2::runtime::{AnyClass, AnyObject, Bool, NSObject};
use objc2::{AnyThread, define_class, msg_send};
use objc2_foundation::NSString;

use crate::{Install, UpdaterError, UpdaterOptions};

/// The SPUStandardUpdaterController, retained for the life of the process.
static CONTROLLER: AtomicPtr<AnyObject> = AtomicPtr::new(std::ptr::null_mut());
static STARTED: AtomicBool = AtomicBool::new(false);

#[repr(C)]
struct DispatchQueue {
    _opaque: [u8; 0],
}

// libdispatch, which every macOS process links through libSystem.
// `dispatch_get_main_queue()` is a macro for the address of `_dispatch_main_q`.
unsafe extern "C" {
    static _dispatch_main_q: DispatchQueue;
    fn dispatch_async_f(
        queue: *const DispatchQueue,
        context: *mut c_void,
        work: extern "C" fn(*mut c_void),
    );
}

/// Run `work` on the main thread, after whatever is already queued there.
fn on_main_thread<F: FnOnce() + Send + 'static>(work: F) {
    extern "C" fn trampoline<F: FnOnce()>(context: *mut c_void) {
        // SAFETY: `context` is the box leaked below, and the queue calls this once.
        let work = unsafe { Box::from_raw(context.cast::<F>()) };
        // An unwind may not cross into libdispatch.
        if catch_unwind(AssertUnwindSafe(work)).is_err() {
            eprintln!("dioxus-compose-update: an update request panicked on the main thread");
        }
    }
    let context = Box::into_raw(Box::new(work)).cast::<c_void>();
    // SAFETY: the main queue lives for the whole process and the trampoline matches the
    // context's type.
    unsafe { dispatch_async_f(&raw const _dispatch_main_q, context, trampoline::<F>) };
}

/// A value that is only touched on the main thread, carried there from the main thread.
struct OnMain<T>(T);

// SAFETY: only constructed on the main thread and only consumed by work queued to the
// main thread, so the value never runs anywhere else.
unsafe impl<T> Send for OnMain<T> {}

impl OnMain<RcBlock<dyn Fn()>> {
    fn call(self) {
        self.0.call(());
    }
}

define_class!(
    // SAFETY: NSObject has no subclassing requirements, and this class has no Drop.
    #[unsafe(super(NSObject))]
    struct InstallNowDelegate;

    impl InstallNowDelegate {
        // SPUUpdaterDelegate. Sparkle asks this when an update it downloaded on its own is
        // ready and would otherwise wait for the application to quit. Answering YES and
        // calling the block installs it now and relaunches.
        #[unsafe(method(updater:willInstallUpdateOnQuit:immediateInstallationBlock:))]
        fn will_install_update_on_quit(
            &self,
            _updater: *mut AnyObject,
            _item: *mut AnyObject,
            install: &Block<dyn Fn()>,
        ) -> Bool {
            eprintln!("dioxus-compose-update: update ready, installing and relaunching");
            // Not from inside the delegate call: Sparkle is still in the middle of the
            // step that asked.
            let install = OnMain(install.copy());
            on_main_thread(move || install.call());
            Bool::YES
        }
    }
);

impl InstallNowDelegate {
    fn new() -> Retained<Self> {
        let this = Self::alloc().set_ivars(());
        // SAFETY: NSObject's init on a freshly allocated object.
        unsafe { msg_send![super(this), init] }
    }
}

pub(crate) fn start(framework: PathBuf, options: UpdaterOptions) -> Result<(), UpdaterError> {
    if STARTED.swap(true, Ordering::AcqRel) {
        return Err(UpdaterError::AlreadyStarted);
    }
    on_main_thread(move || {
        // SAFETY: on the main thread, which is where AppKit and Sparkle must be used.
        match unsafe { start_on_main(&framework, options.install) } {
            Ok(()) => eprintln!("dioxus-compose-update: Sparkle started"),
            Err(message) => eprintln!("dioxus-compose-update: {message}"),
        }
    });
    if options.check_on_start {
        check_in_background();
    }
    Ok(())
}

unsafe fn start_on_main(framework: &Path, install: Install) -> Result<(), String> {
    let path = NSString::from_str(&framework.to_string_lossy());
    let bundle_class =
        AnyClass::get(c"NSBundle").ok_or("the Objective-C runtime has no NSBundle")?;
    // SAFETY: +[NSBundle bundleWithPath:] takes a string and returns a bundle or nil.
    let bundle: Option<Retained<AnyObject>> = unsafe { msg_send![bundle_class, bundleWithPath: &*path] };
    let bundle = bundle.ok_or_else(|| format!("{} is not a bundle", framework.display()))?;
    // SAFETY: -[NSBundle load] takes nothing and returns whether the code is loaded.
    let loaded: Bool = unsafe { msg_send![&*bundle, load] };
    if !loaded.as_bool() {
        return Err(format!(
            "{} did not load. A framework signed by a different team than the application \
             is refused by library validation; sign the whole bundle with one identity.",
            framework.display()
        ));
    }
    let controller_class = AnyClass::get(c"SPUStandardUpdaterController").ok_or_else(|| {
        format!(
            "{} loaded but has no SPUStandardUpdaterController; it is not Sparkle 2",
            framework.display()
        )
    })?;

    let delegate = match install {
        Install::Immediately => Some(InstallNowDelegate::new()),
        Install::OnQuit => None,
    };
    // SAFETY: +alloc on a class that exists.
    let allocated: Allocated<AnyObject> = unsafe { msg_send![controller_class, alloc] };
    // SAFETY: SPUStandardUpdaterController's designated initializer: whether to start the
    // updater now, an updater delegate or nil, and a user driver delegate or nil.
    let controller: Option<Retained<AnyObject>> = unsafe {
        msg_send![
            allocated,
            initWithStartingUpdater: Bool::YES,
            updaterDelegate: delegate.as_deref(),
            userDriverDelegate: None::<&AnyObject>
        ]
    };
    let controller = controller
        .ok_or_else(|| "SPUStandardUpdaterController refused to initialise".to_owned())?;

    // Sparkle keeps its delegate weakly, so the delegate is kept here, for the life of the
    // process, as is the controller.
    if let Some(delegate) = delegate {
        let _ = Retained::into_raw(delegate);
    }
    CONTROLLER.store(Retained::into_raw(controller), Ordering::Release);
    Ok(())
}

pub(crate) fn check_for_updates() {
    on_main_thread(|| {
        let controller = CONTROLLER.load(Ordering::Acquire);
        if controller.is_null() {
            eprintln!("dioxus-compose-update: no updater is running, so there is nothing to check with");
            return;
        }
        // SAFETY: the controller is retained for the life of the process; checkForUpdates:
        // is its menu action and takes the sender, which may be nil.
        unsafe {
            let () = msg_send![controller, checkForUpdates: None::<&AnyObject>];
        }
    });
}

pub(crate) fn check_in_background() {
    on_main_thread(|| {
        let controller = CONTROLLER.load(Ordering::Acquire);
        if controller.is_null() {
            eprintln!("dioxus-compose-update: no updater is running, so there is nothing to check with");
            return;
        }
        // SAFETY: as above; -updater returns the controller's SPUUpdater.
        unsafe {
            let updater: Option<Retained<AnyObject>> = msg_send![controller, updater];
            if let Some(updater) = updater {
                let () = msg_send![&*updater, checkForUpdatesInBackground];
            }
        }
    });
}

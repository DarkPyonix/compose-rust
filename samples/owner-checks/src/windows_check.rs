//! The windows check: several windows of one application, a tab pulled out into a window of
//! its own, and a second window that keeps drawing.
//!
//! It is a launch of its own, because it needs the application to run several windows while
//! the sample's other screens are checked in the one window they have always had. Build with
//! the `windows-check` feature, or start a desktop build with `--windows-check`.
//!
//! Every window counts seconds on its own. The wait is on a timer thread and the count moves
//! only when the window is drawn, so a window that the platform never asks to draw shows a
//! count that stands still. Every window also shows the last window event the application
//! heard, so the check needs nothing beside the windows.

use compose_rust::runtime::*;
use compose_rust::ui::*;
use compose_rust::windows::{WindowDragOver, WindowRequest};
use std::time::Duration;

/// The keys of the two tabs. Each is also the restore key of the window its tab becomes.
pub const TAB_KEYS: [&str; 2] = ["tab:a", "tab:b"];

/// What the two tabs say.
pub const TAB_NAMES: [&str; 2] = ["Tab A", "Tab B"];

/// The restore key of the first window.
pub const MAIN_KEY: &str = "main";

/// The restore key of the window the button opens.
pub const SECOND_KEY: &str = "second";

/// What the button that opens the second window says.
pub const OPEN_SECOND: &str = "Open a second window";

/// The line every window shows before any window event has arrived.
pub const NO_EVENT: &str = "Last window event: none yet";

/// The height of the strip that listens for a dragged tab or window, in dp.
const DROP_HEIGHT: f32 = 72.0;

/// What the windows share. Held once at the root, so a change made in one window is in the
/// batch that redraws every window.
#[derive(Clone, Copy)]
struct Windows {
    main_open: MutableState<bool>,
    /// The restore key of the first window: [`MAIN_KEY`], or empty once the first window was
    /// opened again in answer to a request with no key.
    main_key: MutableState<&'static str>,
    second_open: MutableState<bool>,
    /// Which tabs are in a window of their own rather than in the first window.
    torn: MutableState<[bool; 2]>,
    /// The last window event the application heard, as it is shown.
    last_event: MutableState<String>,
}

impl Windows {
    fn heard(self, event: String) {
        self.last_event.set(format!("Last window event: {event}"));
    }
}

/// The application of the windows check: one `Window` node per open window.
#[Composable]
pub fn windows_check_app() {
    let windows = remember(|| Windows {
        main_open: mutable_state_of(true),
        main_key: mutable_state_of(MAIN_KEY),
        second_open: mutable_state_of(false),
        torn: mutable_state_of([false; 2]),
        last_event: mutable_state_of(String::from(NO_EVENT)),
    });
    // A tab dragged out of its window, a window revived after the process was killed, or a
    // new window from the system's own menu. The answer is a `Window` node under the same
    // key; a key this check does not know gets none, and the platform closes that window.
    // A request with no key while the first window is closed is the application started
    // again after the user closed every window (Android's launcher, for one): it gets the
    // first window back, under that empty key, so the window that asked draws it.
    on_window_requested(move |request: WindowRequest| {
        windows.heard(format!("WindowRequested {:?}", request.key));
        if let Some(index) = TAB_KEYS.iter().position(|key| *key == request.key) {
            windows.torn.update(|torn| torn[index] = true);
        } else if request.key == SECOND_KEY {
            windows.second_open.set(true);
        } else if request.key == MAIN_KEY {
            windows.main_key.set(MAIN_KEY);
            windows.main_open.set(true);
        } else if request.key.is_empty() && !windows.main_open.get_untracked() {
            windows.main_key.set("");
            windows.main_open.set(true);
        }
    });
    if windows.main_open.get() {
        key(0_usize, || main_window(windows));
    }
    if windows.second_open.get() {
        key(1_usize, || second_window(windows));
    }
    let torn = windows.torn.get();
    for (index, out) in torn.iter().enumerate() {
        if *out {
            key(2 + index, || tab_window(index, windows));
        }
    }
}

/// The first window: the tabs, the button that opens the second window, and what to do.
fn main_window(windows: Windows) {
    Window!(
        move || {
            windows.heard(format!("WindowCloseRequested {MAIN_KEY}"));
            windows.main_open.set(false);
        },
        title = "Owner checks: windows",
        size = (720.0, 640.0),
        restore_key = windows.main_key.get(),
        on_closed = Some(move || {
            windows.heard(String::from("WindowClosed main"));
            windows.main_open.set(false);
        }),
        content = move || {
            ScrollColumn!(
                modifier = Modifier
                    .fill_max_size()
                    .background(Paint::Role(ColorRole::Background)),
                {
                    Column!(
                        modifier = Modifier.fill_max_width().padding_role(SpaceRole::Lg),
                        space_role = SpaceRole::Md,
                        {
                            // The controls come before the steps, so that a phone shows
                            // them without scrolling.
                            crate::header("Windows", "The steps are below the controls.");
                            tab_strip(windows);
                            Button!(
                                OPEN_SECOND,
                                move || windows.second_open.set(true),
                                accessible_name = OPEN_SECOND
                            );
                            shared(windows);
                            crate::steps(
                                "What to do and what must be seen",
                                &[
                                    "Press \"Open a second window\". Its seconds climb too.",
                                    "Desktop: drag Tab A out. A window follows the pointer.",
                                    "Android 15 or later: hold Tab A, drag it to a screen edge \
                                     or onto the desktop. It opens as a window of its own.",
                                    "iPad: hold Tab A, drag it to a screen edge. A window opens.",
                                    "Each time the line says WindowRequested with the tab's key, \
                                     and the tab leaves this window.",
                                    "Drag a tab or window over another window's strip. The line \
                                     says WindowDragOver: Enter, Move, then Leave or Drop.",
                                    "Press Back in a torn-off window (Android) or its close \
                                     button (desktop). The line says WindowCloseRequested with \
                                     the tab's key, the window closes and the tab comes back.",
                                    "Close a torn-off window from Recents or the app switcher. \
                                     The line says WindowClosed and the tab comes back.",
                                    "Android: with no other window open, press Back in this \
                                     window, then start the app again from the launcher. This \
                                     window comes back and the line says WindowRequested \"\".",
                                ],
                            );
                        }
                    );
                }
            );
        }
    );
}

/// The second window, which shows the same shared lines as the first.
fn second_window(windows: Windows) {
    Window!(
        move || {
            windows.heard(format!("WindowCloseRequested {SECOND_KEY}"));
            windows.second_open.set(false);
        },
        title = "Owner checks: second window",
        size = (520.0, 440.0),
        restore_key = SECOND_KEY,
        on_closed = Some(move || {
            windows.heard(String::from("WindowClosed second"));
            windows.second_open.set(false);
        }),
        content = move || {
            Column!(
                modifier = Modifier
                    .fill_max_size()
                    .background(Paint::Role(ColorRole::Background))
                    .padding_role(SpaceRole::Lg),
                space_role = SpaceRole::Md,
                {
                    crate::header(
                        "Second window",
                        "Its count of seconds must climb once a second on its own, whichever \
                         window is in front.",
                    );
                    shared(windows);
                }
            );
        }
    );
}

/// A tab in a window of its own. Closing it puts the tab back in the first window.
fn tab_window(index: usize, windows: Windows) {
    // A close request (Back on Android, the close button on the desktop) is the application's
    // to answer, and it answers by putting the tab back, which closes the window. The line
    // says so, so the check can tell a close request from a window the system closed.
    let put_back = move || {
        windows.heard(format!("WindowCloseRequested {}", TAB_KEYS[index]));
        windows.torn.update(|torn| torn[index] = false);
    };
    Window!(
        put_back,
        title = TAB_NAMES[index],
        size = (480.0, 400.0),
        restore_key = TAB_KEYS[index],
        on_closed = Some(move || {
            windows.heard(format!("WindowClosed {}", TAB_KEYS[index]));
            windows.torn.update(|torn| torn[index] = false);
        }),
        content = move || {
            Column!(
                modifier = Modifier
                    .fill_max_size()
                    .background(Paint::Role(ColorRole::Background))
                    .padding_role(SpaceRole::Lg),
                space_role = SpaceRole::Md,
                {
                    crate::header(
                        TAB_NAMES[index],
                        "This tab was pulled out of the first window. Closing this window puts \
                         it back there.",
                    );
                    shared(windows);
                }
            );
        }
    );
}

/// The tabs still in the first window, side by side.
fn tab_strip(windows: Windows) {
    let torn = windows.torn.get();
    Row!(
        modifier = Modifier.fill_max_width(),
        space_role = SpaceRole::Sm,
        {
            for (index, out) in torn.iter().enumerate() {
                if !*out {
                    key(index, || tab(index));
                }
            }
        }
    );
}

/// One tab, which the user can pull out into a window under its key.
fn tab(index: usize) {
    Box!(
        modifier = Modifier
            .background(Paint::Role(ColorRole::SecondaryContainer))
            .shape_role(ShapeRole::Medium)
            .padding_role(SpaceRole::Md),
        tear_off_key = TAB_KEYS[index],
        {
            Text!(TAB_NAMES[index], type_role = TypeRole::BodyStrong);
        }
    );
}

/// What every window shows: its own count of seconds, the strip that hears a drag pass over
/// it, and the last window event.
fn shared(windows: Windows) {
    seconds();
    drop_strip(windows);
    crate::reading(windows.last_event.get());
}

/// A count of seconds that only climbs while this window is drawn.
fn seconds() {
    let counted = remember(|| mutable_state_of(0_u32));
    launched_effect((), move || async move {
        loop {
            delay(Duration::from_secs(1)).await;
            counted.update(|counted| *counted += 1);
        }
    });
    crate::reading(format!("Seconds counted in this window: {}", counted.get()));
}

/// The strip a dragged tab or window is reported over.
fn drop_strip(windows: Windows) {
    Box!(
        modifier = Modifier
            .fill_max_width()
            .height(DROP_HEIGHT)
            .background(Paint::Role(ColorRole::SurfaceVariant))
            .padding_role(SpaceRole::Md),
        on_window_drag_over = Some(move |over: WindowDragOver| {
            windows.heard(format!(
                "WindowDragOver {:?}, window {}, at {:.0}, {:.0}",
                over.kind, over.window, over.x, over.y
            ));
        }),
        {
            Text!(
                "Drag a tab or a torn-off window over this strip.",
                type_role = TypeRole::Body
            );
        }
    );
}

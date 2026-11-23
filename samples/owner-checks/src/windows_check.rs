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
        second_open: mutable_state_of(false),
        torn: mutable_state_of([false; 2]),
        last_event: mutable_state_of(String::from(NO_EVENT)),
    });
    // A tab dragged out of its window, a window revived after the process was killed, or a
    // new window from the system's own menu. The answer is a `Window` node under the same
    // key; a key this check does not know gets none, and the platform closes that window.
    on_window_requested(move |request: WindowRequest| {
        windows.heard(format!("WindowRequested {:?}", request.key));
        if let Some(index) = TAB_KEYS.iter().position(|key| *key == request.key) {
            windows.torn.update(|torn| torn[index] = true);
        } else if request.key == SECOND_KEY {
            windows.second_open.set(true);
        } else if request.key == MAIN_KEY {
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
        move || windows.main_open.set(false),
        title = "Owner checks: windows",
        size = (720.0, 640.0),
        restore_key = MAIN_KEY,
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
                            crate::header(
                                "Windows",
                                "Several windows of one application: a second window that \
                                 keeps drawing, and tabs that tear off into windows of their own.",
                            );
                            crate::steps(
                                "What to do and what must be seen",
                                &[
                                    "Press \"Open a second window\". A second window opens, and its \
                                     count of seconds climbs once a second, as this one does.",
                                    "On a desktop, press Tab A and drag it out of this window. A \
                                     window opens under the pointer and follows it until you let go.",
                                    "On Android 15 or later, hold Tab A, then drag it to the side of \
                                     the screen. The system opens it as a window beside this one. \
                                     Below Android 15 nothing is dragged.",
                                    "On an iPad, hold Tab A, then drag it to the edge of the screen. \
                                     A new window opens with it. On an iPhone nothing is dragged.",
                                    "Each time, the line at the bottom says WindowRequested with the \
                                     tab's key, and the tab is gone from this window.",
                                    "Drag a tab or a torn-off window over the strip of another \
                                     window. The line says WindowDragOver: Enter, Move, then Leave \
                                     or Drop.",
                                    "Close a torn-off window through the system (swipe it away in \
                                     Recents, or close it in the iPad's app switcher). The line says \
                                     WindowClosed and the tab is back here.",
                                ],
                            );
                            tab_strip(windows);
                            Button!(
                                OPEN_SECOND,
                                move || windows.second_open.set(true),
                                accessible_name = OPEN_SECOND
                            );
                            shared(windows);
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
        move || windows.second_open.set(false),
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
    let put_back = move || windows.torn.update(|torn| torn[index] = false);
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

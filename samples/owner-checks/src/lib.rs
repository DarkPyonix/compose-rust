//! The hand checks a person makes before a release, one screen each.
//!
//! Some of what a release promises cannot be checked by a machine: that a notification
//! appears in the platform's notification centre, that a Korean input method composes in a
//! platform text field placed inside the window, that a screen reader says the names the
//! controls were given, that the window buttons still work over a caption strip the
//! application draws itself. Somebody has to look. This is what they look at.
//!
//! Each screen says in plain words what to do and what must be seen, so the person making
//! the check needs nothing beside the window. The list on the left reaches every screen.
//! The window draws its own caption strip (`Chrome::FullSize`), so every screen sits under
//! the coloured bar that runs beneath the window buttons and shows the safe area as
//! numbers.
//!
//! The sample is adaptive: it takes the design system the platform picks, because these
//! checks are about the platform, not about a design.
//!
//! The windows check is a launch of its own, because it runs several windows and the
//! screens above are checked in one: build with the `windows-check` feature, or start a
//! desktop build with `--windows-check` (`windows_check`).

#[cfg(target_os = "macos")]
mod appkit;
mod native_page;
mod windows_check;

use compose_rust::runtime::*;
use compose_rust::ui::*;
use compose_rust::{
    Chrome, Notification, NotificationActivation, NotificationPermission, NotificationPresentation,
    request_notification_permission, withdraw_notification,
};

/// The title every notification of this sample carries, and the key it is posted under.
/// One key, so posting again replaces the one already showing rather than adding another.
const NOTIFICATION_TITLE: &str = "Owner checks";
const NOTIFICATION_KEY: &str = "owner-checks/test";

/// How long the worker waits before it posts: long enough to minimise the window first.
#[cfg(not(target_family = "wasm"))]
const WORKER_WAIT: std::time::Duration = std::time::Duration::from_secs(5);

/// The caption strip is at least this tall, in dp, where the platform reports no caption
/// (a phone, a browser, or a desktop window before it has measured its own).
const CAPTION_MIN: f32 = 36.0;

/// The space between the strip's content and the window buttons, in dp.
const CAPTION_EDGE: f32 = 12.0;

/// The width of the list of screens, in dp.
const LIST_WIDTH: f32 = 200.0;

/// The height of the box that moves the window when it is dragged, in dp.
const HANDLE_HEIGHT: f32 = 64.0;

/// The names the five controls of the screen reader screen carry, in the order they stand.
/// The person making the check ticks these off as the screen reader says them.
pub const SCREEN_READER_NAMES: [&str; 5] = [
    "Test button",
    "Test switch",
    "Test checkbox",
    "Test slider",
    "Test text field",
];

/// The window: its own caption strip, a size that fits the list beside a screen, and the
/// sample's icon.
fn window() -> compose_rust::schema::Window {
    compose_rust::schema::Window::new()
        .with_title("Owner checks")
        .with_chrome(Chrome::FullSize)
        .with_size(960, 700)
        .with_min_size(640, 480)
        .with_icon(compose_rust::asset::asset(
            compose_rust::schema::AssetKind::Png,
            include_bytes!("../assets/icon.png"),
        ))
}

/// One screen of the sample.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
enum Page {
    Notifications,
    NativeView,
    Caption,
    ScreenReader,
    InputMethod,
    About,
}

impl Page {
    const ALL: [Page; 6] = [
        Page::Notifications,
        Page::NativeView,
        Page::Caption,
        Page::ScreenReader,
        Page::InputMethod,
        Page::About,
    ];

    /// What the list calls the screen, and what a person is told to press to reach it.
    const fn name(self) -> &'static str {
        match self {
            Page::Notifications => "Notifications",
            Page::NativeView => "Native view",
            Page::Caption => "Caption strip",
            Page::ScreenReader => "Screen reader",
            Page::InputMethod => "Input method",
            Page::About => "About this build",
        }
    }
}

/// Everything a person can change on any screen. Kept at the root rather than in each
/// screen, so going to another screen and back does not forget what a check reported.
#[derive(Clone, Copy)]
struct Checks {
    page: MutableState<Page>,
    posted: MutableState<u32>,
    pressed: MutableState<String>,
    worker: MutableState<String>,
    switch_on: MutableState<bool>,
    ticked: MutableState<bool>,
    level: MutableState<f32>,
    typed: MutableState<String>,
    single_line: MutableState<String>,
    multiline: MutableState<String>,
    composing: MutableState<bool>,
    native_focused: MutableState<bool>,
    compose_field: MutableState<String>,
}

/// The sample.
#[Composable]
pub fn app() {
    let checks = remember(|| Checks {
        page: mutable_state_of(Page::Notifications),
        posted: mutable_state_of(0_u32),
        pressed: mutable_state_of(String::from("nothing yet")),
        worker: mutable_state_of(String::new()),
        switch_on: mutable_state_of(false),
        ticked: mutable_state_of(false),
        level: mutable_state_of(0.5_f32),
        typed: mutable_state_of(String::new()),
        single_line: mutable_state_of(String::new()),
        multiline: mutable_state_of(String::new()),
        composing: mutable_state_of(false),
        native_focused: mutable_state_of(false),
        compose_field: mutable_state_of(String::new()),
    });
    // At the root, so a press on a notification is heard whichever screen is showing.
    on_notification_activated(move |activation| checks.pressed.set(describe_press(&activation)));

    let chosen = checks.page.get();
    Column!(
        modifier = Modifier
            .fill_max_size()
            .background(Paint::Role(ColorRole::Background)),
        {
            caption_strip();
            Row!(modifier = Modifier.fill_max_width().weight(1.0), {
                page_list(checks.page, chosen);
                ScrollColumn!(modifier = Modifier.weight(1.0).fill_max_height(), {
                    Column!(
                        modifier = Modifier.fill_max_width().padding_role(SpaceRole::Lg),
                        space_role = SpaceRole::Md,
                        {
                            key(chosen, || page(chosen, checks));
                        }
                    );
                });
            });
        }
    );
}

/// The bar the application draws where a title bar would be. It starts at the window's
/// top edge and runs under the window buttons, keeping its own content clear of them by
/// the safe area the platform reported, which it shows as numbers.
///
/// Nothing on it moves the window by being marked: an empty part of it moves the window
/// because the platform's caption is there. The chip at its end is marked as not moving
/// it, so the check can tell the two apart.
fn caption_strip() {
    let safe = current_window_size().safe_area;
    let ink = Paint::Role(ColorRole::OnPrimaryContainer);
    Row!(
        modifier = Modifier
            .fill_max_width()
            .height(safe.top.max(CAPTION_MIN))
            .background(Paint::Role(ColorRole::PrimaryContainer))
            .padding_each(
                safe.leading + CAPTION_EDGE,
                0.0,
                safe.trailing + CAPTION_EDGE,
                0.0
            ),
        alignment = Alignment::CenterStart,
        space_role = SpaceRole::Md,
        {
            Text!(
                "Owner checks",
                type_role = TypeRole::BodyStrong,
                color = ink
            );
            Text!(
                safe_area_line(safe),
                type_role = TypeRole::Caption,
                color = ink,
                modifier = Modifier.weight(1.0)
            );
            Box!(
                modifier = Modifier
                    .window_drag(false)
                    .background(Paint::Role(ColorRole::Surface))
                    .corner_radius(8.0)
                    .padding_xy(8.0, 2.0),
                {
                    Text!(
                        "No drag here",
                        type_role = TypeRole::Label,
                        color = Paint::Role(ColorRole::OnSurface)
                    );
                }
            );
        }
    );
}

/// The safe area as the strip and the caption screen print it, in whole dp.
fn safe_area_line(safe: WindowSafeArea) -> String {
    format!(
        "Safe area: top {:.0}, leading {:.0}, trailing {:.0}",
        safe.top, safe.leading, safe.trailing
    )
}

/// The list of screens down the left side.
fn page_list(page: MutableState<Page>, chosen: Page) {
    Column!(
        modifier = Modifier
            .width(LIST_WIDTH)
            .fill_max_height()
            .background(Paint::Role(ColorRole::SurfaceContainer))
            .padding_role(SpaceRole::Sm),
        space_role = SpaceRole::Xs,
        {
            for each in Page::ALL {
                let picked = each == chosen;
                key(each, || {
                    Button!(
                        each.name(),
                        move || page.set(each),
                        accessible_name = each.name(),
                        variant = if picked {
                            ButtonVariant::Tonal
                        } else {
                            ButtonVariant::Text
                        },
                        modifier = Modifier.fill_max_width().selected(picked)
                    );
                });
            }
        }
    );
}

/// The screen that is chosen.
fn page(chosen: Page, checks: Checks) {
    match chosen {
        Page::Notifications => notifications_page(checks),
        Page::NativeView => {
            native_page::native_view_page(checks.native_focused, checks.compose_field);
        }
        Page::Caption => caption_page(),
        Page::ScreenReader => screen_reader_page(checks),
        Page::InputMethod => input_method_page(checks),
        Page::About => about_page(),
    }
}

/// A screen's name and the one sentence that says what it checks.
pub(crate) fn header(title: &str, purpose: &str) {
    Text!(title, type_role = TypeRole::Headline);
    Text!(
        purpose,
        type_role = TypeRole::Body,
        color = Paint::Role(ColorRole::OnSurfaceVariant)
    );
}

/// A numbered list under a heading: the steps of a check, each with what must be seen.
pub(crate) fn steps(heading: &str, lines: &[&str]) {
    Column!(
        modifier = Modifier.fill_max_width(),
        space_role = SpaceRole::Xs,
        {
            Text!(heading, type_role = TypeRole::BodyStrong);
            for (index, line) in lines.iter().enumerate() {
                key(index, || {
                    Text!(format!("{}. {line}", index + 1), type_role = TypeRole::Body);
                });
            }
        }
    );
}

/// A line of what the check reported, set apart from the instructions.
pub(crate) fn reading(text: impl Into<String>) {
    Text!(text, type_role = TypeRole::Mono);
}

/// The notification this sample posts, with its two buttons.
fn test_notification(body: impl Into<String>) -> Notification {
    Notification::new(NOTIFICATION_TITLE)
        .body(body)
        .key(NOTIFICATION_KEY)
        .action_1("First button")
        .action_2("Second button")
}

/// What a press on a notification reports, in the words the steps use.
fn describe_press(activation: &NotificationActivation) -> String {
    match activation.action_button() {
        None => format!("the notification itself (key {})", activation.key),
        Some(button) => format!("button {button} (key {})", activation.key),
    }
}

/// What the platform says about notifications, as the screen prints it.
fn permission_name(permission: NotificationPermission) -> &'static str {
    match permission {
        NotificationPermission::NotDetermined => "not asked yet",
        NotificationPermission::Granted => "Granted",
        NotificationPermission::Denied => "Denied",
        NotificationPermission::Unsupported => "Unsupported",
    }
}

fn notifications_page(checks: Checks) {
    let permission = current_notification_permission();
    let posted = checks.posted.get();
    header(
        "Notifications",
        "Posts a notification through the platform's notification centre and shows what \
         came back.",
    );
    steps(
        "Do this, and see this",
        &[
            "Press Ask for permission. Allow it if the system asks. Permission below says \
             Granted.",
            "Press Post a notification. A notification titled Owner checks says \
             Notification number 1.",
            "Press it again. There is still one notification, and it now says number 2.",
            "Click the notification. This window comes to the front once, and Last press \
             says the notification itself.",
            "Post again and press First button on it. Last press says button 1, and the \
             window does not come to the front.",
            "With this window in front, press Post when inactive. Nothing appears.",
            "Press Post from a worker in 5 seconds and minimise the window at once. The \
             notification appears within a second of the 5 seconds.",
            "Press Withdraw it. The notification leaves the notification centre.",
            "Post once more, then quit the app. The notification leaves the notification \
             centre.",
        ],
    );
    reading(format!("Permission: {}", permission_name(permission)));
    if permission == NotificationPermission::Unsupported {
        Text!(
            format!(
                "Unsupported is this platform's answer, not a failure: {}",
                compose_rust::notification::unsupported_reason()
            ),
            type_role = TypeRole::Body,
            color = Paint::Role(ColorRole::Error)
        );
    }
    reading(format!("Posted: {posted}"));
    reading(format!("Last press: {}", checks.pressed.get()));
    Column!(space_role = SpaceRole::Sm, {
        Button!(
            "Ask for permission",
            request_notification_permission,
            accessible_name = "Ask for permission"
        );
        Button!(
            "Post a notification",
            move || {
                let number = checks.posted.get_untracked() + 1;
                checks.posted.set(number);
                test_notification(format!("Notification number {number}")).post();
            },
            accessible_name = "Post a notification"
        );
        Button!(
            "Post when inactive",
            || {
                test_notification("Posted to show only while the window is not in front")
                    .presentation(NotificationPresentation::WhenInactive)
                    .post();
            },
            accessible_name = "Post when inactive"
        );
        worker_button(checks.worker);
        Button!(
            "Withdraw it",
            || withdraw_notification(NOTIFICATION_KEY),
            accessible_name = "Withdraw it"
        );
    });
}

/// Posts from a worker thread after a wait, the way an application's news arrives while
/// nobody is looking at the window.
///
/// The wait is on the worker, not on a timer the UI thread runs, because what is being
/// checked is a post that arrives from another thread while the window is minimised.
#[cfg(not(target_family = "wasm"))]
fn worker_button(worker: MutableState<String>) {
    let scope = remember_coroutine_scope();
    Button!(
        "Post from a worker in 5 seconds",
        move || {
            worker.set(format!(
                "A worker thread is waiting {} seconds. Minimise the window now.",
                WORKER_WAIT.as_secs()
            ));
            scope.launch(async move {
                with_worker(|| {
                    // A receive that nothing will ever send to, so it returns when the
                    // wait is over.
                    let (keep, wait) = std::sync::mpsc::channel::<()>();
                    let _ = wait.recv_timeout(WORKER_WAIT);
                    drop(keep);
                    compose_rust::NotificationSender::new()
                        .post(test_notification("Posted from a worker thread"));
                })
                .await;
                worker.set(String::from("The worker thread posted its notification."));
            });
        },
        accessible_name = "Post from a worker in 5 seconds"
    );
    let said = worker.get();
    if !said.is_empty() {
        reading(said);
    }
}

/// A browser page has no threads here, so there is no worker to post from.
#[cfg(target_family = "wasm")]
fn worker_button(_worker: MutableState<String>) {
    Text!(
        "Post from a worker is a desktop check: a browser page has no worker threads.",
        type_role = TypeRole::Body
    );
}

fn caption_page() {
    let safe = current_window_size().safe_area;
    header(
        "Caption strip",
        "This window draws its own caption strip: the coloured bar at the top, under the \
         window buttons.",
    );
    steps(
        "Do this, and see this",
        &[
            "Look at the coloured bar. It starts at the top edge of the window, under the \
             window buttons, with no empty strip above it.",
            "Without resizing the window, read the numbers on the bar. macOS: top and \
             leading above 0, trailing 0. Windows: top above 0, leading 0, trailing 138. \
             Linux: top and trailing above 0, leading 0.",
            "Nothing on the bar is hidden under the window buttons.",
            "Drag an empty part of the bar. The window moves. Drag the No drag here chip. \
             The window does not move.",
            "Drag the Drag handle box below. The window moves, although the box is not in \
             the bar.",
            "Maximise the window and restore it. The numbers follow if the platform \
             changes them.",
            "Press minimise and maximise. Each works. Press close last: it quits the app.",
        ],
    );
    reading(safe_area_line(safe));
    Box!(
        modifier = Modifier
            .fill_max_width()
            .height(HANDLE_HEIGHT)
            .background(Paint::Role(ColorRole::TertiaryContainer))
            .corner_radius(12.0)
            .window_drag(true),
        alignment = Alignment::Center,
        {
            Text!(
                "Drag handle: drag here to move the window",
                type_role = TypeRole::BodyStrong,
                color = Paint::Role(ColorRole::OnTertiaryContainer)
            );
        }
    );
}

fn screen_reader_page(checks: Checks) {
    let [button, switch, checkbox, slider, field] = SCREEN_READER_NAMES;
    header(
        "Screen reader",
        "Five controls with fixed names. A screen reader has to say each name.",
    );
    steps(
        "Do this",
        &[
            "Turn on the screen reader. Windows: Narrator, Ctrl+Windows+Enter. macOS: \
             VoiceOver, Command+F5. Linux: Orca, Super+Alt+S.",
            "Click Test button, then press Tab to move through the controls below.",
            "Tick each name below as the screen reader says it. It says the name, then \
             what kind of control it is.",
        ],
    );
    steps(
        "You must hear, in this order",
        &[
            "Test button, a button",
            "Test switch, a switch or toggle, off",
            "Test checkbox, a check box, not checked",
            "Test slider, a slider, at 50 percent",
            "Test text field, an edit or text field",
        ],
    );
    Column!(
        modifier = Modifier.fill_max_width(),
        space_role = SpaceRole::Md,
        {
            Button!(button, || {}, accessible_name = button);
            Switch!(
                checks.switch_on.get(),
                move |on| checks.switch_on.set(on),
                accessible_name = switch
            );
            Checkbox!(
                checks.ticked.get(),
                move |ticked| checks.ticked.set(ticked),
                accessible_name = checkbox
            );
            Slider!(
                checks.level.get(),
                move |level| checks.level.set(level),
                accessible_name = slider,
                modifier = Modifier.fill_max_width()
            );
            TextField!(
                "",
                move |text| checks.typed.set(text),
                placeholder = "Type anything",
                modifier = Modifier.fill_max_width().label(field)
            );
        }
    );
}

fn input_method_page(checks: Checks) {
    header(
        "Input method",
        "Two fields for checking an input method. Below them, what each field reported and \
         whether a composition is in progress.",
    );
    steps(
        "The five core checks, with a Korean input method",
        &[
            "Click One-line field and type 안녕하세요. Each syllable shows while it is \
             being composed (underlined or highlighted) before it is committed.",
            "Type 한 and, while it is composing, press Backspace. One jamo goes at a time: \
             한, then 하, then ㅎ.",
            "Type 가 and, while it is composing, press the left arrow. The syllable is \
             committed first, then the caret moves.",
            "Type 가나다, click between 가 and 나, and type 라. It goes in where you \
             clicked: 가라나다.",
            "No character anywhere is an empty box.",
        ],
    );
    steps(
        "The other four",
        &[
            "In Multiline field, press Enter while a syllable is composing. The syllable \
             is committed, then a new line starts.",
            "Paste a long text with Korean in it. All of it arrives.",
            "With a Japanese or Chinese input method, the candidate window opens next to \
             the caret.",
            "The reported lines below change while you type, and the composition is never \
             cut off by them.",
        ],
    );
    TextField!(
        "",
        move |text| checks.single_line.set(text),
        placeholder = "One-line field",
        modifier = Modifier.fill_max_width().label("One-line field"),
        on_composition_change = Some(move |composing: bool| checks.composing.set(composing))
    );
    TextField!(
        "",
        move |text| checks.multiline.set(text),
        placeholder = "Multiline field",
        multiline = true,
        modifier = Modifier
            .fill_max_width()
            .height(120.0)
            .label("Multiline field"),
        on_composition_change = Some(move |composing: bool| checks.composing.set(composing))
    );
    reading(format!(
        "Composing now: {}",
        if checks.composing.get() { "yes" } else { "no" }
    ));
    reading(format!(
        "One-line field reported: {}",
        checks.single_line.get()
    ));
    reading(format!(
        "Multiline field reported: {}",
        checks.multiline.get()
    ));
}

/// The compose-rust crate's version, read from the crate's own manifest.
///
/// The sample's version is its own and says nothing about the crate it was built with, and
/// the crate does not export its version, so the manifest beside it is read instead.
fn compose_rust_version() -> &'static str {
    include_str!("../../../compose-rust/Cargo.toml")
        .lines()
        .find_map(|line| line.strip_prefix("version = \"")?.strip_suffix('"'))
        .unwrap_or("unknown")
}

/// The commit the build was made from, where the build recorded one. The samples workflow
/// sets it; a build on someone's own machine has none, and then the line is left out.
fn build_commit() -> Option<&'static str> {
    option_env!("COMPOSE_RUST_SAMPLE_COMMIT").filter(|commit| !commit.is_empty())
}

fn about_page() {
    header(
        "About this build",
        "Which build this is. Check it is the one you were asked to run.",
    );
    reading(format!("compose-rust {}", compose_rust_version()));
    reading(format!("Schema hash {:#018x}", compose_rust::SCHEMA_HASH));
    if let Some(commit) = build_commit() {
        reading(format!("Commit {commit}"));
    }
    reading(format!(
        "Built for {} {}",
        std::env::consts::OS,
        std::env::consts::ARCH
    ));
    reading(format!("Design system {:?}", current_design_system()));
}

/// What starts the windows check on a desktop instead of the sample's screens.
pub const WINDOWS_CHECK_ARGUMENT: &str = "--windows-check";

/// Whether this run is the windows check: built with the `windows-check` feature, or a
/// desktop build started with [`WINDOWS_CHECK_ARGUMENT`]. A phone has no command line, so
/// there the feature is the only way.
fn runs_windows_check() -> bool {
    static ASKED: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *ASKED.get_or_init(|| cfg!(feature = "windows-check") || asked_on_the_command_line())
}

#[cfg(not(any(target_os = "android", target_os = "ios", target_family = "wasm")))]
fn asked_on_the_command_line() -> bool {
    std::env::args()
        .skip(1)
        .any(|argument| argument == WINDOWS_CHECK_ARGUMENT)
}

#[cfg(any(target_os = "android", target_os = "ios", target_family = "wasm"))]
fn asked_on_the_command_line() -> bool {
    false
}

/// What the launch composes: the sample's screens, or the windows check.
#[Composable]
fn root() {
    if runs_windows_check() {
        windows_check::app();
    } else {
        app();
    }
}

/// Runs the sample as a program of its own.
pub fn launch() {
    launch_builder().application(root);
}

/// How this sample is configured, in one place because three entry points need it.
fn launch_builder() -> LaunchBuilder {
    let builder = LaunchBuilder::new()
        // The same as [bundle] identifier in Dioxus.toml. It names the storage, and on
        // macOS it is what the notification centre knows the application by.
        .identifier("dev.darkpyonix.composerust.samples.ownerchecks")
        .with_theme(compose_rust::demo_theme())
        .with_window(window());
    // The windows check runs several windows; the screens run in one.
    if runs_windows_check() {
        builder.windows()
    } else {
        builder
    }
}

compose_rust::android_application!({ launch_builder() }, root);
compose_rust::web_application!({ launch_builder() }, root);
compose_rust::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;

    use compose_rust::ComposeHost;
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
    use compose_rust::schema::{EventPayload, Modifier as Wire, PropertyKind, WidgetKind};

    /// The notification queue and the window size belong to the process, not to one Host,
    /// so a Host in one test can write another test's post into its own batch. Every test
    /// that drives a Host holds this for as long as it does.
    static ONE_HOST_AT_A_TIME: std::sync::Mutex<()> = std::sync::Mutex::new(());

    fn one_host_at_a_time() -> std::sync::MutexGuard<'static, ()> {
        ONE_HOST_AT_A_TIME
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// The screen, driven the way a Renderer drives it.
    ///
    /// Every batch is kept, because a batch is the change since the frame before it rather
    /// than what is on screen.
    struct Screen {
        host: ComposeHost,
        frames: Vec<Vec<u8>>,
        _alone: std::sync::MutexGuard<'static, ()>,
    }

    impl Screen {
        fn new() -> Self {
            let alone = one_host_at_a_time();
            compose_rust::window::reset_window_size();
            let mut host = ComposeHost::new(app);
            let first = host.rebuild().expect("the first frame failed").to_vec();
            Self {
                host,
                frames: vec![first],
                _alone: alone,
            }
        }

        /// The windows check, as its launch runs it: an application of several windows.
        fn windows_check() -> Self {
            let alone = one_host_at_a_time();
            compose_rust::window::reset_window_size();
            let mut host =
                ComposeHost::new(windows_check::app).with_window(window().with_windows());
            let first = host.rebuild().expect("the first frame failed").to_vec();
            Self {
                host,
                frames: vec![first],
                _alone: alone,
            }
        }

        /// How many `Window` nodes are live.
        fn window_nodes(&self) -> usize {
            self.live()
                .iter()
                .filter(|mutation| {
                    matches!(
                        mutation,
                        Mutation::Create {
                            widget: WidgetKind::Window,
                            ..
                        }
                    )
                })
                .count()
        }

        /// Every string a live node was given under `property`, sorted.
        fn strings_of(&self, property: PropertyKind) -> Vec<String> {
            let mut found: Vec<String> = self
                .live()
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetProp {
                        property: given,
                        value: PropertyValue::String(text),
                        ..
                    } if *given == property => Some((*text).to_owned()),
                    _ => None,
                })
                .collect();
            found.sort();
            found
        }

        /// The live node that was given `text` under `property`.
        fn node_with(&self, property: PropertyKind, text: &str) -> Option<u32> {
            self.live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: given,
                        value: PropertyValue::String(found),
                    } if *given == property && *found == text => Some(*node_id),
                    _ => None,
                })
        }

        /// The live node that carries a handler under `property`.
        fn node_handling(&self, property: PropertyKind) -> Option<(u32, u64)> {
            self.live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: given,
                        value: PropertyValue::Integer(handler),
                    } if *given == property => Some((*node_id, *handler as u64)),
                    _ => None,
                })
        }

        /// The system made a window under `key`, which the application has no node for.
        fn request_window(&mut self, key: &str) {
            self.dispatch(0, 0, EventPayload::WindowRequested { key, request: 1 });
        }

        /// The sample with `page` chosen from the list.
        fn on(page: Page) -> Self {
            let mut screen = Self::new();
            assert!(screen.press(page.name()), "the list has no {}", page.name());
            screen
        }

        fn mutations(&self) -> Vec<Mutation<'_>> {
            self.frames
                .iter()
                .flat_map(|frame| decode_batch(frame).expect("a batch did not decode"))
                .collect()
        }

        fn live(&self) -> Vec<Mutation<'_>> {
            sample_frames::live(self.mutations())
        }

        /// The text every live node shows now: the last one each was given, with any tail
        /// appended to it since. A text that only grows at its end arrives as that tail.
        fn texts(&self) -> Vec<String> {
            let mut shown: Vec<(u32, String)> = Vec::new();
            for mutation in self.live() {
                match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Text,
                        value: PropertyValue::String(text),
                    } => {
                        shown.retain(|(node, _)| *node != node_id);
                        shown.push((node_id, text.to_owned()));
                    }
                    Mutation::AppendText { node_id, text } => {
                        if let Some((_, whole)) =
                            shown.iter_mut().find(|(node, _)| *node == node_id)
                        {
                            whole.push_str(text);
                        }
                    }
                    _ => {}
                }
            }
            shown.into_iter().map(|(_, text)| text).collect()
        }

        fn shows(&self, text: &str) -> bool {
            self.texts().iter().any(|shown| shown == text)
        }

        /// The live node whose accessible name is `name`.
        fn named(&self, name: &str) -> Option<u32> {
            self.live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::AccessibleName,
                        value: PropertyValue::String(text),
                    } if *text == name => Some(*node_id),
                    _ => None,
                })
        }

        /// What kind of widget a live node is.
        fn widget_of(&self, node: u32) -> Option<WidgetKind> {
            self.live().iter().find_map(|mutation| match mutation {
                Mutation::Create { node_id, widget } if *node_id == node => Some(*widget),
                _ => None,
            })
        }

        /// The handler a live node carries under `property`.
        fn handler_of(&self, node: u32, property: PropertyKind) -> Option<u64> {
            self.live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: found,
                        value: PropertyValue::Integer(handler),
                    } if *node_id == node && *found == property => Some(*handler as u64),
                    _ => None,
                })
        }

        /// Where a live node was last placed: its parent and its place among the
        /// parent's children.
        fn placement(&self, node: u32) -> Option<(u32, u32)> {
            self.live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::Insert {
                        parent_id,
                        node_id,
                        index,
                    }
                    | Mutation::Move {
                        parent_id,
                        node_id,
                        index,
                    } if *node_id == node => Some((*parent_id, *index)),
                    _ => None,
                })
        }

        /// Hands the Host an event, the way the Renderer does, and keeps the batch.
        fn dispatch(&mut self, node_id: u32, handler_id: u64, payload: EventPayload<'_>) {
            let mut bytes = Vec::new();
            encode_event(&HostEvent::new(node_id, handler_id, payload), &mut bytes)
                .expect("the event did not encode");
            let (batch, _) = self.host.dispatch_event(&bytes).expect("the event failed");
            if !batch.is_empty() {
                self.frames.push(batch.to_vec());
            }
        }

        /// Presses the live control named `name`.
        fn press(&mut self, name: &str) -> bool {
            let Some(node_id) = self.named(name) else {
                return false;
            };
            let Some(handler_id) = self.handler_of(node_id, PropertyKind::OnClick) else {
                return false;
            };
            self.dispatch(node_id, handler_id, EventPayload::Clicked);
            true
        }

        /// Whether any live node carries the window-drag modifier set to `enabled`.
        fn drags(&self, enabled: bool) -> bool {
            self.live().iter().any(|mutation| {
                matches!(
                    mutation,
                    Mutation::SetModifier {
                        modifier: Wire::WindowDrag(found),
                        ..
                    } if *found == enabled
                )
            })
        }
    }

    /// The windows check opens one window holding the two tabs, each under its key.
    #[test]
    fn fr53_the_windows_check_opens_one_window_with_two_tear_off_tabs() {
        let screen = Screen::windows_check();
        assert_eq!(screen.window_nodes(), 1);
        assert_eq!(
            screen.strings_of(PropertyKind::TearOffKey),
            vec!["tab:a".to_owned(), "tab:b".to_owned()]
        );
        assert_eq!(
            screen.strings_of(PropertyKind::WindowRestoreKey),
            vec![windows_check::MAIN_KEY.to_owned()]
        );
        assert!(screen.shows(windows_check::NO_EVENT));
        assert!(
            screen
                .node_handling(PropertyKind::OnWindowDragOver)
                .is_some()
        );
        compose_rust::window::reset_window_size();
    }

    /// A window the system made for a dragged tab gets a `Window` node under the tab's key in
    /// the same batch, the tab leaves the first window, and the line says what came.
    #[test]
    fn fr53_a_window_request_for_a_tab_tears_it_off() {
        let mut screen = Screen::windows_check();
        screen.request_window("tab:a");
        assert_eq!(screen.window_nodes(), 2);
        assert_eq!(
            screen.strings_of(PropertyKind::TearOffKey),
            vec!["tab:b".to_owned()]
        );
        assert!(
            screen
                .strings_of(PropertyKind::WindowRestoreKey)
                .contains(&"tab:a".to_owned())
        );
        assert!(screen.shows("Last window event: WindowRequested \"tab:a\""));
        compose_rust::window::reset_window_size();
    }

    /// A key the check does not know opens nothing, so the platform closes that window.
    #[test]
    fn fr53_an_unknown_window_request_opens_nothing() {
        let mut screen = Screen::windows_check();
        screen.request_window("");
        assert_eq!(screen.window_nodes(), 1);
        assert!(screen.shows("Last window event: WindowRequested \"\""));
        compose_rust::window::reset_window_size();
    }

    /// The button opens the second window, whose count of seconds proves it is drawn.
    #[test]
    fn fr53_the_button_opens_a_second_window() {
        let mut screen = Screen::windows_check();
        assert!(screen.press(windows_check::OPEN_SECOND));
        assert_eq!(screen.window_nodes(), 2);
        assert!(
            screen
                .strings_of(PropertyKind::WindowRestoreKey)
                .contains(&windows_check::SECOND_KEY.to_owned())
        );
        compose_rust::window::reset_window_size();
    }

    /// A torn-off window the system closed puts its tab back in the first window.
    #[test]
    fn fr53_a_closed_tab_window_puts_the_tab_back() {
        let mut screen = Screen::windows_check();
        screen.request_window("tab:a");
        let window = screen
            .node_with(PropertyKind::WindowRestoreKey, "tab:a")
            .expect("the tab has a window");
        let handler = screen
            .handler_of(window, PropertyKind::OnWindowClosed)
            .expect("the tab's window hears that the system closed it");
        screen.dispatch(window, handler, EventPayload::WindowClosed);
        assert_eq!(screen.window_nodes(), 1);
        assert_eq!(
            screen.strings_of(PropertyKind::TearOffKey),
            vec!["tab:a".to_owned(), "tab:b".to_owned()]
        );
        assert!(screen.shows("Last window event: WindowClosed tab:a"));
        compose_rust::window::reset_window_size();
    }

    /// A tab or a window dragged over a window's strip is shown on the line.
    #[test]
    fn fr53_a_drag_over_the_strip_is_shown() {
        let mut screen = Screen::windows_check();
        let (strip, handler) = screen
            .node_handling(PropertyKind::OnWindowDragOver)
            .expect("the window has a strip that hears a drag");
        screen.dispatch(
            strip,
            handler,
            EventPayload::WindowDragOver {
                kind: compose_rust::protocol::WINDOW_DRAG_OVER_MOVE,
                window: 7,
                x: 12.0,
                y: 3.0,
            },
        );
        assert!(screen.shows("Last window event: WindowDragOver Move, window 7, at 12, 3"));
        compose_rust::window::reset_window_size();
    }

    /// A property the schema does not have fails the whole batch rather than just itself,
    /// so a screen that builds in Rust can still be blank on screen. Every screen is
    /// reached and drawn once.
    #[test]
    fn fr22_every_screen_is_reachable_from_the_list_and_encodes() {
        let mut screen = Screen::new();
        for page in Page::ALL {
            assert!(
                screen.press(page.name()),
                "the list has no {} to press",
                page.name()
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// The window asks for its own caption strip. The record the Renderer stands the window
    /// up from is what decides it, so it is read off the wire.
    #[test]
    fn fr19_9_the_window_record_asks_for_full_size_chrome() {
        let _alone = one_host_at_a_time();
        compose_rust::window::reset_window_size();
        let mut host = ComposeHost::new(app).with_window(window());
        let batch = host.rebuild().expect("the first frame failed").to_vec();
        let found = decode_batch(&batch)
            .expect("the first batch did not decode")
            .into_iter()
            .find_map(|mutation| match mutation {
                Mutation::SetWindow(window) => Some(window),
                _ => None,
            })
            .expect("the first batch carries no window record");
        assert_eq!(found.chrome, Chrome::FullSize);
        compose_rust::window::reset_window_size();
    }

    /// The strip shows the safe area the Renderer reported, as whole numbers, and the
    /// caption screen shows the same line.
    #[test]
    fn fr19_9_the_strip_shows_the_safe_area_it_was_given() {
        let mut screen = Screen::on(Page::Caption);
        screen.dispatch(
            0,
            0,
            EventPayload::WindowSizeChanged {
                width_dp: 960.0,
                height_dp: 700.0,
                class: WindowSizeClass::from_width_dp(960.0),
                height_class: WindowHeightClass::from_height_dp(700.0),
                safe_area_top: 28.0,
                safe_area_leading: 78.0,
                safe_area_trailing: 0.0,
                safe_area_bottom: 0.0,
            },
        );
        let line = "Safe area: top 28, leading 78, trailing 0";
        let count = screen.texts().iter().filter(|text| *text == line).count();
        assert_eq!(
            count,
            2,
            "the strip and the caption screen do not both show {line:?}: {:?}",
            screen.texts()
        );
        compose_rust::window::reset_window_size();
    }

    /// The caption screen has a box that moves the window and the strip a chip that does
    /// not, so a drag over each tells the two cases apart.
    #[test]
    fn fr19_9_a_marked_box_drags_the_window_and_a_marked_chip_does_not() {
        let screen = Screen::on(Page::Caption);
        assert!(screen.drags(true), "nothing is marked to move the window");
        assert!(
            screen.drags(false),
            "nothing is marked not to move the window"
        );
        compose_rust::window::reset_window_size();
    }

    /// Pressing the post button hands the Renderer the notification with the fixed title,
    /// key and body, in the batch the press produced.
    #[test]
    fn fr36_the_post_button_posts_the_fixed_notification() {
        compose_rust::notification::reset_notifications();
        let mut screen = Screen::on(Page::Notifications);
        assert!(
            screen.press("Post a notification"),
            "the screen has no Post a notification"
        );
        let posted = screen.mutations().iter().any(|mutation| {
            matches!(
                mutation,
                Mutation::PostNotification {
                    key: NOTIFICATION_KEY,
                    title: NOTIFICATION_TITLE,
                    body: "Notification number 1",
                    action_1: "First button",
                    action_2: "Second button",
                    ..
                }
            )
        });
        assert!(posted, "the press posted no notification");
        assert!(screen.shows("Posted: 1"));
        compose_rust::window::reset_window_size();
    }

    /// A platform that cannot show notifications says so on the screen, in that word.
    #[test]
    fn fr36_unsupported_is_shown_plainly() {
        let mut screen = Screen::on(Page::Notifications);
        screen.dispatch(
            0,
            0,
            EventPayload::NotificationPermissionChanged(NotificationPermission::Unsupported),
        );
        assert!(
            screen.shows("Permission: Unsupported"),
            "the screen does not say Unsupported: {:?}",
            screen.texts()
        );
        compose_rust::window::reset_window_size();
    }

    /// A press on a notification is reported on the screen with its button.
    #[test]
    fn fr36_a_press_on_the_notification_is_reported() {
        let mut screen = Screen::on(Page::Notifications);
        screen.dispatch(
            0,
            0,
            EventPayload::NotificationActivated {
                action: 1,
                key: NOTIFICATION_KEY,
            },
        );
        let expected = format!("Last press: button 1 (key {NOTIFICATION_KEY})");
        assert!(
            screen.shows(&expected),
            "the screen does not report the press: {:?}",
            screen.texts()
        );
        compose_rust::window::reset_window_size();
    }

    /// The five controls stand in one column in the fixed order, each the kind of control
    /// its name says, so the list the person ticks matches what the screen reader meets.
    #[test]
    fn nfr13_the_five_controls_carry_their_names_in_order() {
        let screen = Screen::on(Page::ScreenReader);
        let kinds = [
            WidgetKind::Button,
            WidgetKind::Switch,
            WidgetKind::Checkbox,
            WidgetKind::Slider,
            WidgetKind::TextField,
        ];
        let mut parent = None;
        let mut last = None;
        for (name, kind) in SCREEN_READER_NAMES.into_iter().zip(kinds) {
            let node = screen
                .named(name)
                .unwrap_or_else(|| panic!("no control is named {name:?}"));
            assert_eq!(
                screen.widget_of(node),
                Some(kind),
                "{name:?} is not a {kind:?}"
            );
            let (holder, index) = screen
                .placement(node)
                .unwrap_or_else(|| panic!("{name:?} is not placed"));
            assert_eq!(
                *parent.get_or_insert(holder),
                holder,
                "{name:?} is not in the same column as the others"
            );
            if let Some(before) = last {
                assert!(
                    index > before,
                    "{name:?} stands before the control above it"
                );
            }
            last = Some(index);
        }
        compose_rust::window::reset_window_size();
    }

    /// What the one-line field reports and whether a composition is in progress are both
    /// on the screen, so the person typing can see them change.
    #[test]
    fn nfr13_the_input_method_screen_shows_the_text_and_the_composition() {
        let mut screen = Screen::on(Page::InputMethod);
        let field = screen
            .named("One-line field")
            .expect("there is no One-line field");
        let changed = screen
            .handler_of(field, PropertyKind::OnValueChange)
            .expect("the field reports no text");
        let composing = screen
            .handler_of(field, PropertyKind::OnCompositionChange)
            .expect("the field reports no composition");
        assert!(screen.shows("Composing now: no"));
        screen.dispatch(
            field,
            composing,
            EventPayload::CompositionChanged { composing: true },
        );
        assert!(
            screen.shows("Composing now: yes"),
            "the composition is not shown: {:?}",
            screen.texts()
        );
        screen.dispatch(field, changed, EventPayload::TextChanged("안녕하세요"));
        assert!(
            screen.shows("One-line field reported: 안녕하세요"),
            "the text is not shown: {:?}",
            screen.texts()
        );
        assert!(
            screen.named("Multiline field").is_some(),
            "there is no Multiline field"
        );
        compose_rust::window::reset_window_size();
    }

    /// On macOS the native view screen holds the node a platform view is placed in.
    #[cfg(target_os = "macos")]
    #[test]
    fn fr81_the_native_view_screen_holds_a_native_view_on_macos() {
        compose_rust::native_view::reset_native_parents();
        let screen = Screen::on(Page::NativeView);
        let holds = screen.live().iter().any(|mutation| {
            matches!(
                mutation,
                Mutation::Create {
                    widget: WidgetKind::NativeView,
                    ..
                }
            )
        });
        assert!(holds, "the native view screen has no NativeView");
        compose_rust::window::reset_window_size();
    }

    /// Elsewhere the screen says the check is not on this platform, and places nothing.
    #[cfg(not(target_os = "macos"))]
    #[test]
    fn fr81_the_native_view_screen_says_it_is_not_on_this_platform() {
        let screen = Screen::on(Page::NativeView);
        assert!(screen.shows(native_page::NOT_ON_THIS_PLATFORM));
        let holds = screen.live().iter().any(|mutation| {
            matches!(
                mutation,
                Mutation::Create {
                    widget: WidgetKind::NativeView,
                    ..
                }
            )
        });
        assert!(
            !holds,
            "a NativeView was placed where there is no slot for one"
        );
        compose_rust::window::reset_window_size();
    }

    /// The about screen names the crate version and the schema the build was made with.
    #[test]
    fn nfr11_the_about_screen_names_the_crate_and_the_schema() {
        let screen = Screen::on(Page::About);
        assert_ne!(compose_rust_version(), "unknown");
        assert!(screen.shows(&format!("compose-rust {}", compose_rust_version())));
        assert!(screen.shows(&format!("Schema hash {:#018x}", compose_rust::SCHEMA_HASH)));
        compose_rust::window::reset_window_size();
    }
}

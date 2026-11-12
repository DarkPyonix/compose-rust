//! The multi-window fixture (`tests/fixtures/multi-window`), driven through a Host.
//!
//! The Windows consumer check runs that program against the real renderer and real Win32
//! windows. This runs the same composable here, on every platform, so that what the check
//! leans on is proved before a renderer exists to run it: two `Window` nodes that share their
//! state, a close that removes one window and leaves the other, and the scripted run's steps in
//! the order the check reads them.

mod support;

#[path = "fixtures/multi-window/src/app.rs"]
mod app;

use compose_rust::ComposeHost;
use compose_rust::protocol::{HostEvent, Mutation, decode_batch};
use compose_rust::schema::{
    DesignSystem, EventPayload, LastWindowClosed, PropertyKind, Theme, WidgetKind,
    Window as WindowSettings, WindowHeightClass, WindowSizeClass,
};
use std::sync::Mutex;
use std::sync::atomic::Ordering;
use support::Tree;

/// The fixture keeps its progress in statics, so its two runs here take turns.
static ONE_AT_A_TIME: Mutex<()> = Mutex::new(());

fn start() -> (ComposeHost, Tree, Vec<u32>) {
    let mut host = ComposeHost::with_theme(app::app, Theme::unified(DesignSystem::Material3))
        .with_window(
            WindowSettings::new()
                .with_windows()
                .with_last_window_closed(LastWindowClosed::Exit),
        );
    let mut tree = Tree::default();
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    let windows: Vec<u32> = decode_batch(&first)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Window,
            } => Some(node_id),
            _ => None,
        })
        .collect();
    tree.apply(&first);
    (host, tree, windows)
}

fn removed(batch: &[u8]) -> Vec<u32> {
    decode_batch(batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::Remove { node_id } => Some(node_id),
            _ => None,
        })
        .collect()
}

fn window_count(tree: &Tree) -> usize {
    tree.widgets()
        .iter()
        .filter(|widget| widget.as_str() == "Window")
        .count()
}

fn sized(window: u32) -> HostEvent<'static> {
    HostEvent {
        node_id: window,
        handler_id: 0,
        payload: EventPayload::WindowSizeChanged {
            width_dp: app::WINDOW_SIZE.0,
            height_dp: app::WINDOW_SIZE.1,
            class: WindowSizeClass::Medium,
            height_class: WindowHeightClass::Medium,
            safe_area_top: 0.0,
            safe_area_leading: 0.0,
            safe_area_trailing: 0.0,
        },
    }
}

/// What a person does with the fixture: both windows open with the shared state, typing in
/// the second shows in both, closing the second from its title bar leaves the first, and
/// closing the first leaves no window, which is when the renderer ends an `Exit` application.
#[test]
fn fr53_windows_app_two_windows_share_state_and_close_one_at_a_time() {
    let _turn = ONE_AT_A_TIME
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    app::reset();
    app::CHECK.store(false, Ordering::SeqCst);
    let (mut host, mut tree, windows) = start();
    assert_eq!(windows.len(), 2, "two Window nodes at the root");
    assert_eq!(window_count(&tree), 2);
    assert!(tree.texts().contains(&app::TITLES[0].to_owned()));
    assert!(tree.texts().contains(&app::TITLES[1].to_owned()));

    // Typed into the second window's field, shown under both fields.
    let (field, on_change) = tree.find("TextField", None, 1, PropertyKind::OnValueChange);
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id: field,
            handler_id: on_change,
            payload: EventPayload::TextChanged("안녕"),
        })
        .expect("the text change dispatches");
    tree.apply(batch);
    let echoes: Vec<String> = tree
        .texts()
        .into_iter()
        .filter(|text| text.starts_with("Typed in either window"))
        .collect();
    assert_eq!(
        echoes,
        vec![
            "Typed in either window: 안녕",
            "Typed in either window: 안녕"
        ]
    );

    // The click count is shared the same way.
    let (button, on_click) = tree.find("Button", Some("Add one"), 1, PropertyKind::OnClick);
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id: button,
            handler_id: on_click,
            payload: EventPayload::Clicked,
        })
        .expect("the click dispatches");
    tree.apply(batch);
    let counts: Vec<String> = tree
        .texts()
        .into_iter()
        .filter(|text| text.starts_with("Clicks"))
        .collect();
    assert_eq!(counts.len(), 2);
    assert!(
        counts.iter().all(|text| text.ends_with(": 1")),
        "{counts:?}"
    );

    // The second window's title bar asks to close it, and the application removes its node.
    let closes = tree.handled("Window", PropertyKind::OnWindowCloseRequested);
    assert_eq!(closes.len(), 2);
    let (second, close_second) = closes[1];
    assert_eq!(second, windows[1]);
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id: second,
            handler_id: close_second,
            payload: EventPayload::WindowCloseRequested,
        })
        .expect("the close request dispatches");
    let gone = removed(batch);
    assert!(gone.contains(&windows[1]), "{gone:?}");
    assert!(!gone.contains(&windows[0]), "{gone:?}");
    tree.apply(batch);
    assert_eq!(window_count(&tree), 1);
    assert!(
        tree.texts().contains(&"Open the second window".to_owned()),
        "the first window offers to open the second again"
    );

    // Closing the first from its own button leaves none.
    let (button, on_click) = tree.find(
        "Button",
        Some("Close this window"),
        0,
        PropertyKind::OnClick,
    );
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id: button,
            handler_id: on_click,
            payload: EventPayload::Clicked,
        })
        .expect("the click dispatches");
    let gone = removed(batch);
    assert!(gone.contains(&windows[0]), "{gone:?}");
    tree.apply(batch);
    assert_eq!(window_count(&tree), 0);
}

/// The scripted run the Windows check starts with `--check`: nothing happens before both
/// windows have reported a size; then the shared count reaches both, the second window is
/// removed while frames keep coming for the first, and only then is the first removed.
#[test]
fn fr53_windows_app_check_closes_the_second_window_then_the_last() {
    let _turn = ONE_AT_A_TIME
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    app::reset();
    app::CHECK.store(true, Ordering::SeqCst);
    let (mut host, mut tree, windows) = start();
    assert_eq!(windows.len(), 2);
    let mut now = 0_u64;
    let mut frame = |host: &mut ComposeHost, tree: &mut Tree| {
        now += 16_000_000;
        let batch = host.render_frame(now).expect("the frame encodes").to_vec();
        tree.apply(&batch);
        batch
    };

    // Neither window has been laid out, so the run waits however many frames go by.
    for _ in 0..5 {
        frame(&mut host, &mut tree);
    }
    assert_eq!(app::STEP.load(Ordering::SeqCst), 0);
    assert_eq!(window_count(&tree), 2);

    // One window laid out is not both.
    let (batch, _) = host
        .dispatch(sized(windows[0]))
        .expect("the size dispatches");
    tree.apply(batch);
    for _ in 0..3 {
        frame(&mut host, &mut tree);
    }
    assert_eq!(app::STEP.load(Ordering::SeqCst), 0);

    let (batch, _) = host
        .dispatch(sized(windows[1]))
        .expect("the size dispatches");
    tree.apply(batch);

    let mut removals = Vec::new();
    for _ in 0..50 {
        let batch = frame(&mut host, &mut tree);
        for node in removed(&batch) {
            if windows.contains(&node) {
                removals.push((node, app::STEP.load(Ordering::SeqCst)));
            }
        }
        if window_count(&tree) == 0 {
            break;
        }
    }
    assert_eq!(app::STEP.load(Ordering::SeqCst), app::LAST_CLOSED);
    assert_eq!(
        removals,
        vec![(windows[1], app::SHARED), (windows[0], app::LAST_CLOSED)],
        "the second window goes once the state is shared, and the first only after the \
         second's removal was followed by frames"
    );
    let counts: Vec<String> = tree
        .texts()
        .into_iter()
        .filter(|text| text.starts_with("Clicks"))
        .collect();
    assert!(counts.is_empty(), "no window is left: {counts:?}");
    app::CHECK.store(false, Ordering::SeqCst);
}

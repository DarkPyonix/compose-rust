//! Several windows in one process, from the Host's side (FR-53): what the records and
//! events look like on the wire, and what the composition does with them.
//!
//! The Renderer's half, opening and closing platform windows, is not here. What is here is
//! everything the Host promises it: a window is a node, windows share one batch, an event
//! names its window, and a single-window application sends what it always sent.

mod support;

use compose_rust::LaunchBuilder;
use compose_rust::protocol::{
    BatchEncoder, HostEvent, Mutation, PropertyValue, ProtocolError, decode_batch, decode_event,
    encode_event,
};
use compose_rust::runtime::*;
use compose_rust::schema::{
    Chrome, ColorScheme, DesignSystem, EventPayload, LastWindowClosed, PropertyKind, Theme,
    TitleBar, WidgetKind, Window as WindowSettings, WindowHeightClass, WindowMode, WindowSizeClass,
};
use compose_rust::ui::*;
use compose_rust::{ComposeHost, zoom};
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, Ordering};
use support::Tree;

fn several_windows() -> WindowSettings {
    WindowSettings::new()
        .with_windows()
        .with_last_window_closed(LastWindowClosed::Exit)
}

/// The ids of the windows a batch creates, in order.
fn window_ids(batch: &[u8]) -> Vec<u32> {
    decode_batch(batch)
        .expect("the batch decodes")
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Window,
            } => Some(node_id),
            _ => None,
        })
        .collect()
}

fn event(node_id: u32, handler_id: u64, payload: EventPayload<'static>) -> HostEvent<'static> {
    HostEvent {
        node_id,
        handler_id,
        payload,
    }
}

// ----- a single window is what it was -------------------------------------------------

#[composable]
fn Nothing() {}

/// An application launched the way it always was sends the window record it always sent,
/// byte for byte: nothing above the resizable flag is set, and nothing else is in the batch.
///
/// The expected record is written out by hand from the layout before windows existed, so the
/// test does not take the encoder's word for it.
#[test]
fn fr53_single_window_app_batches_are_unchanged() {
    let mut host = ComposeHost::with_theme(Nothing, Theme::unified(DesignSystem::Material3))
        .with_window(WindowSettings::new());
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    // The envelope, the 56 byte theme and the 32 byte window: no record is added by the
    // existence of windows.
    assert_eq!(batch.len(), 12 + 56 + 32);
    assert_eq!(decode_batch(&batch).unwrap().len(), 2);
    let window_record = &batch[12 + 56..];
    let expected: [u8; 32] = [
        13, 0, 32, 0, // SetWindow, 32 bytes
        1, 0, // chrome: Modern
        1, 0, // title bar: Normal
        0, 0, 0, 0, // width, height
        0, 0, 0, 0, // minimum width, minimum height
        1, 0, // resizable, and nothing above it
        0, 0, // no zoom level
        100, 0, 0, 0, 0, 0, 0, 0, // the empty title, at the end of the records
        0, 0, 0, 0, // no icon
    ];
    assert_eq!(window_record, &expected[..]);
    let Mutation::SetWindow(window) = &decode_batch(&batch).unwrap()[1] else {
        panic!("the second record is the window");
    };
    assert_eq!(window.mode, WindowMode::Single);
}

/// The same record with the mode and the policy set differs in the flags word and nowhere
/// else.
#[test]
fn fr53_window_mode_rides_in_the_spare_bits_of_the_resizable_word() {
    let single = {
        let mut host = ComposeHost::with_theme(Nothing, Theme::unified(DesignSystem::Material3))
            .with_window(WindowSettings::new());
        host.rebuild().unwrap().to_vec()
    };
    let windows = {
        let mut host = ComposeHost::with_theme(Nothing, Theme::unified(DesignSystem::Material3))
            .with_window(
                WindowSettings::new()
                    .with_windows()
                    .with_last_window_closed(LastWindowClosed::KeepRunning),
            );
        host.rebuild().unwrap().to_vec()
    };
    assert_eq!(single.len(), windows.len());
    let differing: Vec<usize> = (0..single.len())
        .filter(|at| single[*at] != windows[*at])
        .collect();
    // The flags word is 16 bytes into the window record: 0x1101 against 0x0001.
    let flags_at = 12 + 56 + 16;
    assert_eq!(differing, vec![flags_at + 1]);
    assert_eq!(windows[flags_at], 1);
    assert_eq!(windows[flags_at + 1], 0x11);
}

// ----- windows share one composition ---------------------------------------------------

#[composable]
fn Shared() {
    let count = remember(|| mutable_state_of(0));
    for name in ["a", "b"] {
        key(name, || {
            let more = count.clone();
            Window(|| {}).title(name).content(|| {
                Text(format!("{name}: {}", count.get()));
                Button("add").on_click(move || more.update(|count| *count += 1));
            });
        });
    }
}

/// Two windows read one state. A handler in the first changes it, and the batch that comes
/// back carries the change to both windows, in one snapshot.
#[test]
fn fr53_windows_share_state_in_one_batch() {
    let mut host = ComposeHost::with_theme(Shared, Theme::unified(DesignSystem::Material3))
        .with_window(several_windows());
    let mut tree = Tree::default();
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    assert_eq!(window_ids(&first).len(), 2, "two Window nodes, two windows");
    tree.apply(&first);
    assert_eq!(tree.texts(), vec!["a: 0", "add", "b: 0", "add"]);

    let (node_id, handler_id) = tree.find("Button", Some("add"), 0, PropertyKind::OnClick);
    let (batch, _) = host
        .dispatch(event(node_id, handler_id, EventPayload::Clicked))
        .expect("the click dispatches");
    let changes: Vec<_> = decode_batch(batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                property: PropertyKind::Text,
                value: PropertyValue::String(text),
                ..
            } => Some(text.to_owned()),
            _ => None,
        })
        .collect();
    assert_eq!(
        changes,
        vec!["a: 1", "b: 1"],
        "one handler, one batch, both windows"
    );
    tree.apply(batch);
    assert_eq!(tree.texts(), vec!["a: 1", "add", "b: 1", "add"]);
}

// ----- events name their window --------------------------------------------------------

static PANE_RUNS: [AtomicUsize; 2] = [AtomicUsize::new(0), AtomicUsize::new(0)];

#[composable]
fn Pane(index: usize) {
    PANE_RUNS[index].fetch_add(1, Ordering::SeqCst);
    let size = current_window_size();
    Text(format!("{index}:{:?}", size.class));
}

fn two_panes() {
    for index in 0..2_usize {
        key(index, || {
            Window(|| {}).content(|| Pane(index));
        });
    }
}

/// A size class, a zoom and a density report name the window they are about. Only the
/// composables in that window run again, and the other window keeps what it had.
#[test]
fn fr53_window_events_name_their_window() {
    let mut host = ComposeHost::with_theme(two_panes, Theme::unified(DesignSystem::Material3))
        .with_window(several_windows());
    let mut tree = Tree::default();
    let first = host.rebuild().unwrap().to_vec();
    let windows = window_ids(&first);
    assert_eq!(windows.len(), 2);
    tree.apply(&first);
    assert_eq!(tree.texts(), vec!["0:Compact", "1:Compact"]);
    let before = [
        PANE_RUNS[0].load(Ordering::SeqCst),
        PANE_RUNS[1].load(Ordering::SeqCst),
    ];

    let (batch, _) = host
        .dispatch(event(
            windows[1],
            0,
            EventPayload::WindowSizeChanged {
                width_dp: 900.0,
                height_dp: 700.0,
                class: WindowSizeClass::Expanded,
                height_class: WindowHeightClass::Medium,
            },
        ))
        .expect("the size report dispatches");
    tree.apply(batch);
    assert_eq!(tree.texts(), vec!["0:Compact", "1:Expanded"]);
    assert_eq!(PANE_RUNS[0].load(Ordering::SeqCst), before[0]);
    assert_eq!(PANE_RUNS[1].load(Ordering::SeqCst), before[1] + 1);

    host.dispatch(event(
        windows[0],
        0,
        EventPayload::ZoomChanged {
            k: 1.44,
            os: 1.0,
            level: 2,
        },
    ))
    .expect("the zoom report dispatches");
    assert_eq!(compose_rust::zoom::zoom_of(windows[0]).level, 2);
    assert_eq!(compose_rust::zoom::zoom_of(windows[1]).level, 0);

    // A report that names a node that is not a window is not a window's.
    assert!(
        host.dispatch(event(
            windows[0],
            7,
            EventPayload::ZoomChanged {
                k: 1.0,
                os: 1.0,
                level: 0,
            },
        ))
        .is_err()
    );
    // Outside every window, `zoom()` is the single window's, which is the default.
    assert_eq!(zoom().level, 0);
}

// ----- closing is the application's ----------------------------------------------------

static CLOSE_REQUESTS: AtomicUsize = AtomicUsize::new(0);
static FOCUS_CHANGES: AtomicUsize = AtomicUsize::new(0);

#[composable]
fn Closable() {
    let open = remember(|| mutable_state_of(true));
    if open.get() {
        let close = open.clone();
        Window(|| {
            CLOSE_REQUESTS.fetch_add(1, Ordering::SeqCst);
        })
        .on_focus_changed(|focused| {
            if focused {
                FOCUS_CHANGES.fetch_add(1, Ordering::SeqCst);
            }
        })
        .content(|| {
            Button("close it").on_click(move || close.set(false));
        });
    }
}

/// The close button asks. Nothing closes until the application stops composing the window.
#[test]
fn fr53_close_request_leaves_the_window_to_the_app() {
    CLOSE_REQUESTS.store(0, Ordering::SeqCst);
    FOCUS_CHANGES.store(0, Ordering::SeqCst);
    let mut host = ComposeHost::with_theme(Closable, Theme::unified(DesignSystem::Material3))
        .with_window(several_windows());
    let mut tree = Tree::default();
    let first = host.rebuild().unwrap().to_vec();
    let windows = window_ids(&first);
    tree.apply(&first);
    // The close handler is required and the others are only sent when given.
    let closes = tree.handled("Window", PropertyKind::OnWindowCloseRequested);
    assert_eq!(closes.len(), 1);
    assert!(
        tree.handled("Window", PropertyKind::OnWindowMoved)
            .is_empty()
    );
    let (window, handler) = closes[0];
    assert_eq!(window, windows[0]);

    let (batch, _) = host
        .dispatch(event(window, handler, EventPayload::WindowCloseRequested))
        .expect("the close request dispatches");
    assert_eq!(CLOSE_REQUESTS.load(Ordering::SeqCst), 1);
    assert!(
        !decode_batch(batch)
            .unwrap()
            .iter()
            .any(|mutation| matches!(mutation, Mutation::Remove { .. })),
        "asking to close removes nothing"
    );
    tree.apply(batch);
    assert_eq!(tree.widgets().first().map(String::as_str), Some("Window"));

    let (focus_node, focus_handler) = tree.handled("Window", PropertyKind::OnWindowFocusChanged)[0];
    host.dispatch(event(
        focus_node,
        focus_handler,
        EventPayload::WindowFocusChanged { focused: true },
    ))
    .expect("the focus report dispatches");
    assert_eq!(FOCUS_CHANGES.load(Ordering::SeqCst), 1);

    // The application decides: its own button removes the window.
    let (button, click) = tree.find("Button", Some("close it"), 0, PropertyKind::OnClick);
    let (batch, _) = host
        .dispatch(event(button, click, EventPayload::Clicked))
        .expect("the click dispatches");
    let removed: Vec<_> = decode_batch(batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::Remove { node_id } => Some(node_id),
            _ => None,
        })
        .collect();
    assert!(removed.contains(&window), "{removed:?}");
}

// ----- the last window ------------------------------------------------------------------

/// The builder's choices reach the window record, and a platform that is not asked gets its
/// convention.
#[test]
fn fr53_last_window_closed_policy() {
    let flags_of = |batch: &[u8]| {
        let decoded = decode_batch(batch).unwrap();
        let Some(Mutation::SetWindow(window)) = decoded
            .into_iter()
            .find(|mutation| matches!(mutation, Mutation::SetWindow(_)))
        else {
            panic!("no window record");
        };
        (window.mode, window.last_window_closed)
    };

    // Launched through the builder, in the mode where the platform owns the loop so that
    // nothing runs: the Host the boundary builds reads what the builder stored.
    LaunchBuilder::new()
        .with_mode(compose_rust::LoopMode::Platform)
        .windows()
        .on_last_window_closed(LastWindowClosed::KeepRunning)
        .try_launch(Nothing);
    let mut host = ComposeHost::new(Nothing);
    let batch = host.rebuild().unwrap().to_vec();
    assert_eq!(
        flags_of(&batch),
        (WindowMode::Windows, LastWindowClosed::KeepRunning)
    );
    assert_eq!(batch[12 + 56 + 16..12 + 56 + 18], [0x01, 0x11]);

    LaunchBuilder::new()
        .with_mode(compose_rust::LoopMode::Platform)
        .windows()
        .on_last_window_closed(LastWindowClosed::Exit)
        .try_launch(Nothing);
    let mut host = ComposeHost::new(Nothing);
    let batch = host.rebuild().unwrap().to_vec();
    assert_eq!(
        flags_of(&batch),
        (WindowMode::Windows, LastWindowClosed::Exit)
    );
    assert_eq!(batch[12 + 56 + 16..12 + 56 + 18], [0x01, 0x01]);

    // Not asked: macOS keeps the application, the others end it.
    LaunchBuilder::new()
        .with_mode(compose_rust::LoopMode::Platform)
        .windows()
        .try_launch(Nothing);
    let mut host = ComposeHost::new(Nothing);
    let (mode, policy) = flags_of(&host.rebuild().unwrap().to_vec());
    assert_eq!(mode, WindowMode::Windows);
    assert_eq!(
        policy,
        if cfg!(target_os = "macos") {
            LastWindowClosed::KeepRunning
        } else {
            LastWindowClosed::Exit
        }
    );

    // Back to one window, for whatever runs after this in the process.
    LaunchBuilder::new()
        .with_mode(compose_rust::LoopMode::Platform)
        .try_launch(Nothing);
    let mut host = ComposeHost::new(Nothing);
    assert_eq!(
        flags_of(&host.rebuild().unwrap().to_vec()),
        (WindowMode::Single, LastWindowClosed::Exit)
    );
}

// ----- the app's own events --------------------------------------------------------------

static REQUESTS: Mutex<Vec<(String, u32)>> = Mutex::new(Vec::new());
static REOPENS: AtomicUsize = AtomicUsize::new(0);

#[composable]
fn Listening() {
    on_window_requested(|request| {
        REQUESTS
            .lock()
            .unwrap()
            .push((request.key.clone(), request.request));
    });
    on_application_reopen(|| {
        REOPENS.fetch_add(1, Ordering::SeqCst);
    });
}

/// A window the system made, and the dock icon, reach the registrations as root events:
/// no node and no handler.
#[test]
fn fr53_root_events_reach_their_registrations() {
    REQUESTS.lock().unwrap().clear();
    REOPENS.store(0, Ordering::SeqCst);
    let mut host = ComposeHost::with_theme(Listening, Theme::unified(DesignSystem::Material3))
        .with_window(several_windows());
    host.rebuild().unwrap();
    host.dispatch(event(
        0,
        0,
        EventPayload::WindowRequested {
            key: "ws/1:2",
            request: 7,
        },
    ))
    .expect("the request dispatches");
    host.dispatch(event(0, 0, EventPayload::ApplicationReopen))
        .expect("the reopen dispatches");
    assert_eq!(*REQUESTS.lock().unwrap(), vec![("ws/1:2".to_owned(), 7)]);
    assert_eq!(REOPENS.load(Ordering::SeqCst), 1);
    // They belong to the application, so naming a node or a handler is an error.
    for payload in [
        EventPayload::WindowRequested {
            key: "",
            request: 1,
        },
        EventPayload::ApplicationReopen,
    ] {
        assert!(host.dispatch(event(3, 0, payload.clone())).is_err());
        assert!(host.dispatch(event(0, 3, payload)).is_err());
    }
}

// ----- wire --------------------------------------------------------------------------------

fn encoded(mutations: &[Mutation<'_>]) -> Vec<u8> {
    let mut encoder = BatchEncoder::default();
    for mutation in mutations {
        encoder.encode(mutation).expect("the record encodes");
    }
    encoder.finish().expect("the batch closes").to_vec()
}

fn floats(values: &[f32]) -> Vec<u8> {
    values
        .iter()
        .flat_map(|value| value.to_bits().to_le_bytes())
        .collect()
}

/// Every record and event the windows added encodes and decodes back to itself, the one with
/// a string included.
#[test]
fn fr53_window_records_round_trip() {
    let size = floats(&[800.0, 600.0]);
    let mut drag = 3_u32.to_le_bytes().to_vec();
    drag.extend(floats(&[12.5, 8.0]));
    let theme = Theme::adaptive(DesignSystem::Cupertino).with_color_scheme(ColorScheme::Dark);
    let mutations = vec![
        Mutation::SetWindow(
            WindowSettings::new()
                .with_windows()
                .with_last_window_closed(LastWindowClosed::KeepRunning)
                .with_title("titled")
                .with_size(800, 600),
        ),
        Mutation::Create {
            node_id: 5,
            widget: WidgetKind::Window,
        },
        Mutation::SetProp {
            node_id: 5,
            property: PropertyKind::WindowTitle,
            value: PropertyValue::String("보조 창"),
        },
        Mutation::SetProp {
            node_id: 5,
            property: PropertyKind::WindowSize,
            value: PropertyValue::Bytes(&size),
        },
        Mutation::SetProp {
            node_id: 5,
            property: PropertyKind::WindowChrome,
            value: PropertyValue::Integer(i64::from(Chrome::System as u16)),
        },
        Mutation::SetProp {
            node_id: 5,
            property: PropertyKind::WindowTitleBar,
            value: PropertyValue::Integer(i64::from(TitleBar::Simple as u16)),
        },
        Mutation::SetProp {
            node_id: 5,
            property: PropertyKind::DragFromPointer,
            value: PropertyValue::Bytes(&drag),
        },
        Mutation::SetProp {
            node_id: 5,
            property: PropertyKind::OnWindowCloseRequested,
            value: PropertyValue::Integer(9),
        },
        Mutation::SetProp {
            node_id: 6,
            property: PropertyKind::TearOffKey,
            value: PropertyValue::String("tab/3"),
        },
        Mutation::SetProp {
            node_id: 6,
            property: PropertyKind::OnWindowDragOver,
            value: PropertyValue::Integer(10),
        },
        Mutation::Insert {
            parent_id: 0,
            node_id: 5,
            index: 0,
        },
        Mutation::SetWindowTheme { node_id: 5, theme },
        Mutation::FocusWindow { node_id: 5 },
    ];
    let bytes = encoded(&mutations);
    assert_eq!(decode_batch(&bytes), Ok(mutations));

    let events = [
        (5, 9, EventPayload::WindowCloseRequested),
        (5, 10, EventPayload::WindowFocusChanged { focused: true }),
        (
            5,
            11,
            EventPayload::WindowMoved {
                x: 100.5,
                y: -20.0,
                width: 800.0,
                height: 600.0,
            },
        ),
        (
            5,
            12,
            EventPayload::WindowDrag {
                phase: 2,
                screen_x: 640.25,
                screen_y: 360.5,
                over_window: 8,
            },
        ),
        (
            6,
            13,
            EventPayload::WindowDragOver {
                kind: 4,
                window: 5,
                x: 12.5,
                y: 3.0,
            },
        ),
        (5, 14, EventPayload::WindowUnavailable),
        (5, 15, EventPayload::WindowClosed),
        (
            0,
            0,
            EventPayload::WindowRequested {
                key: "ws/1:2",
                request: 3,
            },
        ),
        (
            0,
            0,
            EventPayload::WindowRequested {
                key: "",
                request: 4,
            },
        ),
        (0, 0, EventPayload::ApplicationReopen),
    ];
    for (node_id, handler_id, payload) in events {
        let sent = HostEvent {
            node_id,
            handler_id,
            payload,
        };
        let mut wire = Vec::new();
        encode_event(&sent, &mut wire).expect("the event encodes");
        assert_eq!(decode_event(&wire), Ok(sent.clone()), "{sent:?}");
        // The tag is the one the table gives it, and the length is the record's.
        assert_eq!(
            usize::from(u16::from_le_bytes([wire[2], wire[3]])) % 4,
            0,
            "{sent:?}"
        );
    }
}

/// What the Renderer must refuse, the Host refuses: a phase or a kind that does not exist,
/// a record of the wrong length, stray bits in the window flags.
#[test]
fn fr53_malformed_window_wire_is_a_protocol_error() {
    let mut wire = Vec::new();
    encode_event(
        &event(
            1,
            2,
            EventPayload::WindowDrag {
                phase: 2,
                screen_x: 0.0,
                screen_y: 0.0,
                over_window: 0,
            },
        ),
        &mut wire,
    )
    .unwrap();
    let mut bad_phase = wire.clone();
    bad_phase[16..20].copy_from_slice(&9_u32.to_le_bytes());
    assert!(matches!(
        decode_event(&bad_phase),
        Err(ProtocolError::InvalidValueKind(9))
    ));
    let mut short = wire.clone();
    short.truncate(28);
    short[2..4].copy_from_slice(&28_u16.to_le_bytes());
    assert_eq!(
        decode_event(&short),
        Err(ProtocolError::InvalidRecordLength)
    );

    let mut batch = encoded(&[Mutation::SetWindow(WindowSettings::new())]);
    // The flags word of the window record: 12 for the envelope, 16 into the record.
    let flags_at = 12 + 16;
    for stray in [0x0002_u16, 0x0004, 0x0080, 0x0200, 0x2000] {
        let mut bent = batch.clone();
        bent[flags_at..flags_at + 2].copy_from_slice(&(1 | stray).to_le_bytes());
        assert!(
            decode_batch(&bent).is_err(),
            "flags {:#06x} are not a window's",
            1 | stray
        );
    }
    batch[flags_at..flags_at + 2].copy_from_slice(&(1_u16 | 0x0100).to_le_bytes());
    assert!(decode_batch(&batch).is_ok(), "mode Windows is a window's");
}

// ----- misplaced windows -------------------------------------------------------------------

#[composable]
fn WindowInAColumn() {
    Column().content(|| {
        Window(|| {}).content(|| {
            Text("lost");
        });
    });
}

/// A window anywhere but directly under the root fails the batch it is in, and the process
/// goes on: the Host reports it, and the next call is answered.
#[test]
fn nfr7_misplaced_window_is_a_protocol_error() {
    let mut host =
        ComposeHost::with_theme(WindowInAColumn, Theme::unified(DesignSystem::Material3))
            .with_window(several_windows());
    assert_eq!(host.rebuild().err(), Some(ProtocolError::MisplacedWindow));
    // The process goes on.
    let mut fine = ComposeHost::with_theme(Shared, Theme::unified(DesignSystem::Material3))
        .with_window(several_windows());
    assert!(fine.rebuild().is_ok());
}

// ----- the numbers ---------------------------------------------------------------------------

/// The wire numbers FR-53.10 lists are the ones the tables give.
#[test]
fn fr53_wire_numbers_are_the_ones_the_spec_lists() {
    use compose_rust::schema::EVENT_SCHEMA;
    assert_eq!(WidgetKind::Window as u16, 45);
    let properties = [
        (PropertyKind::WindowTitle, 150),
        (PropertyKind::WindowSize, 151),
        (PropertyKind::WindowMinSize, 152),
        (PropertyKind::WindowResizable, 153),
        (PropertyKind::WindowChrome, 154),
        (PropertyKind::WindowTitleBar, 155),
        (PropertyKind::WindowIcon, 156),
        (PropertyKind::WindowPosition, 157),
        (PropertyKind::WindowRestoreKey, 158),
        (PropertyKind::WindowZoomLevel, 159),
        (PropertyKind::DragFromPointer, 160),
        (PropertyKind::OnWindowCloseRequested, 161),
        (PropertyKind::OnWindowFocusChanged, 162),
        (PropertyKind::OnWindowMoved, 163),
        (PropertyKind::OnWindowDrag, 164),
        (PropertyKind::OnWindowUnavailable, 165),
        (PropertyKind::OnWindowClosed, 166),
        (PropertyKind::OnWindowDragOver, 167),
        (PropertyKind::TearOffKey, 168),
    ];
    for (property, tag) in properties {
        assert_eq!(property as u16, tag, "{property:?}");
    }
    let events = [
        ("WindowCloseRequested", 39),
        ("WindowFocusChanged", 40),
        ("WindowMoved", 41),
        ("WindowDrag", 42),
        ("WindowDragOver", 43),
        ("WindowUnavailable", 44),
        ("WindowClosed", 45),
        ("WindowRequested", 46),
        ("ApplicationReopen", 47),
    ];
    for (name, tag) in events {
        let found = EVENT_SCHEMA
            .iter()
            .find(|event| event.name == name)
            .unwrap_or_else(|| panic!("{name} is not in the event schema"));
        assert_eq!(found.tag, tag, "{name}");
    }
    // The commands are records 23 and 24: the first byte pair of each record is its tag.
    let theme = Theme::unified(DesignSystem::Material3);
    let bytes = encoded(&[
        Mutation::SetWindowTheme { node_id: 1, theme },
        Mutation::FocusWindow { node_id: 1 },
    ]);
    assert_eq!(u16::from_le_bytes([bytes[12], bytes[13]]), 23);
    assert_eq!(u16::from_le_bytes([bytes[14], bytes[15]]), 60);
    assert_eq!(u16::from_le_bytes([bytes[72], bytes[73]]), 24);
    assert_eq!(u16::from_le_bytes([bytes[74], bytes[75]]), 8);
}

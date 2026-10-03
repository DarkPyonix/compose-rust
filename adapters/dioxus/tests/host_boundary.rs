//! The boundary driven through a Dioxus application: the five entry points, the
//! handshake, the first batch and an event's diff, as the Renderer sees them.
//!
//! These lived beside the boundary before the Dioxus adapter was carved out of the core
//! crate. The boundary is still the core's; what these need from the adapter is an
//! application to drive it with.

use dioxus_compose_adapter::Host;
use dioxus_compose_adapter::boundary::{
    MutationBatch, STATUS_OK, compose_rust_host_dispatch_event, compose_rust_host_init,
    compose_rust_host_shutdown,
};
use dioxus_compose_adapter::prelude::*;
use dioxus_compose_adapter::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
use dioxus_compose_adapter::schema::{EventPayload, PROTOCOL_VERSION, PropertyKind, SCHEMA_HASH};
use std::collections::HashMap;
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, Ordering};

static CLICKS: AtomicUsize = AtomicUsize::new(0);

/// `APP` is process-global, as `launch` is, so two tests that launch different apps at
/// the same time would each see the other's. Cargo runs tests on one thread each, so
/// the ones that launch take this first. Poisoning is ignored: a failing test has
/// already reported itself, and the rest still need the lock.
static LAUNCH: Mutex<()> = Mutex::new(());

fn launch_guard() -> std::sync::MutexGuard<'static, ()> {
    LAUNCH
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn app() -> Element {
    let mut count = use_signal(|| 0_i64);
    rsx! {
        Column {
            Text { text: count().to_string() }
            TextField { placeholder: "Message" }
            Button {
                text: "Increment",
                on_click: move |_| {
                    CLICKS.fetch_add(1, Ordering::SeqCst);
                    *count.write() += 1;
                }
            }
        }
    }
}

fn key_app() -> Element {
    rsx! {
        Column {
            TextField {
                multiline: true,
                on_key_down: move |event: KeyEvent| {
                    if event.key() == Key::Enter && !event.shift_key() {
                        event.consume();
                    }
                }
            }
            TextField {
                on_key_down: move |_event: KeyEvent| {}
            }
        }
    }
}

#[derive(Default)]
struct MockRenderer {
    widgets: HashMap<u32, dioxus_compose_adapter::WidgetKind>,
    parents: HashMap<u32, u32>,
}

impl MockRenderer {
    fn apply(&mut self, batch: &[Mutation<'_>]) {
        for mutation in batch {
            match mutation {
                Mutation::Create { node_id, widget } => {
                    self.widgets.insert(*node_id, *widget);
                }
                Mutation::Insert {
                    parent_id, node_id, ..
                }
                | Mutation::Move {
                    parent_id, node_id, ..
                } => {
                    self.parents.insert(*node_id, *parent_id);
                }
                Mutation::Remove { node_id } => {
                    self.widgets.remove(node_id);
                    self.parents.remove(node_id);
                }
                Mutation::SetProp { .. }
                | Mutation::SetModifier { .. }
                | Mutation::SetText { .. }
                | Mutation::AppendText { .. }
                | Mutation::RegisterAsset { .. }
                | Mutation::ReleaseAsset { .. }
                | Mutation::ShowMessage { .. }
                | Mutation::SetTheme(_)
                | Mutation::SetWindow(_)
                | Mutation::PostNotification { .. }
                | Mutation::WithdrawNotification { .. }
                | Mutation::RequestNotificationPermission
                | Mutation::StartAnimation(_)
                | Mutation::ControlAnimation { .. } => {}
            }
        }
    }
}

#[test]
fn click_runs_once_and_emits_only_text_set_prop() {
    CLICKS.store(0, Ordering::SeqCst);
    let mut host = Host::new(app);
    let initial = decode_batch(host.rebuild().unwrap()).unwrap();
    let mut mock = MockRenderer::default();
    mock.apply(&initial);
    assert_eq!(mock.widgets.len(), 4);
    assert_eq!(mock.parents.len(), 3);

    let (button_node, handler_id) = initial
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnClick,
                value: PropertyValue::Integer(handler),
            } => Some((*node_id, *handler as u64)),
            _ => None,
        })
        .unwrap();
    drop(initial);

    let event = HostEvent {
        node_id: button_node,
        handler_id,
        payload: EventPayload::Clicked,
    };
    let (batch, result) = host.dispatch(event).unwrap();
    let mutations = decode_batch(batch).unwrap();
    assert_eq!(CLICKS.load(Ordering::SeqCst), 1);
    assert_eq!(result, 0);
    assert_eq!(mutations.len(), 1);
    assert!(matches!(
        &mutations[0],
        Mutation::SetProp {
            property: PropertyKind::Text,
            value: PropertyValue::String(value),
            ..
        } if *value == "1"
    ));
}

#[test]
fn fr12_key_consumption_is_returned_and_does_not_leak() {
    let _launch = launch_guard();
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .launch(key_app);
    let mut handshake = Vec::with_capacity(12);
    handshake.extend_from_slice(&SCHEMA_HASH.to_le_bytes());
    handshake.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
    handshake.extend_from_slice(&[LoopMode::Platform as u8, 0]);
    let mut output = MutationBatch::default();
    // SAFETY: All pointers refer to live test-owned buffers for the duration of each call.
    unsafe {
        assert_eq!(
            compose_rust_host_init(handshake.as_ptr(), handshake.len() as u32, &mut output,),
            STATUS_OK
        );
    }
    // SAFETY: A successful init returned a readable batch owned by the Host.
    let initial = unsafe { std::slice::from_raw_parts(output.ptr, output.len as usize) };
    let handlers: Vec<_> = decode_batch(initial)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnKeyDown,
                value: PropertyValue::Integer(handler),
            } => Some((node_id, handler as u64)),
            _ => None,
        })
        .collect();
    assert_eq!(handlers.len(), 2);

    let dispatch = |node_id, handler_id, shift_key, output: &mut MutationBatch| {
        let event = HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::KeyDown {
                key: Key::Enter,
                shift_key,
                ctrl_key: false,
                alt_key: false,
                meta_key: false,
            },
        };
        let mut bytes = Vec::new();
        dioxus_compose_adapter::protocol::encode_event(&event, &mut bytes).unwrap();
        // SAFETY: The encoded event and output storage remain live for the call.
        unsafe {
            assert_eq!(
                compose_rust_host_dispatch_event(bytes.as_ptr(), bytes.len() as u32, output,),
                STATUS_OK
            );
        }
    };

    dispatch(handlers[0].0, handlers[0].1, false, &mut output);
    assert_ne!(output.result, 0);
    dispatch(handlers[0].0, handlers[0].1, true, &mut output);
    assert_eq!(output.result, 0);
    dispatch(handlers[1].0, handlers[1].1, false, &mut output);
    assert_eq!(output.result, 0);
    dispatch(handlers[0].0, handlers[0].1, false, &mut output);
    assert_ne!(output.result, 0);
    compose_rust_host_shutdown();
}

/// `launch` runs on the Rust main thread, but the Renderer UI thread that calls
/// `compose_rust_host_init` is a different thread (on macOS, AWT's event thread inside
/// the isolate). The app the application launched must be reachable from there.
#[test]
fn pr3_init_runs_on_a_different_thread_than_launch() {
    let _launch = launch_guard();
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .launch(app);
    let mut handshake = Vec::with_capacity(12);
    handshake.extend_from_slice(&SCHEMA_HASH.to_le_bytes());
    handshake.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
    handshake.extend_from_slice(&[LoopMode::Platform as u8, 0]);
    std::thread::spawn(move || {
        let mut output = MutationBatch::default();
        // SAFETY: Both buffers are live test-owned storage for the duration of the call.
        let status = unsafe {
            compose_rust_host_init(handshake.as_ptr(), handshake.len() as u32, &mut output)
        };
        assert_eq!(status, STATUS_OK, "init must work off the launch thread");
        assert!(output.len > 0, "init returns the initial tree batch");
        compose_rust_host_shutdown();
    })
    .join()
    .unwrap();
}

/// The first record of the initial batch carries the theme. Saying nothing follows the
/// host platform with a Material 3 fallback; `unified` is never implicit.
#[test]
fn fr14_initial_batch_opens_with_the_launched_theme() {
    let _launch = launch_guard();
    let first_record = |builder: LaunchBuilder| {
        builder.with_mode(LoopMode::Platform).launch(app);
        let mut handshake = Vec::with_capacity(12);
        handshake.extend_from_slice(&SCHEMA_HASH.to_le_bytes());
        handshake.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
        handshake.extend_from_slice(&[LoopMode::Platform as u8, 0]);
        std::thread::spawn(move || {
            let mut output = MutationBatch::default();
            // SAFETY: Both buffers are live test-owned storage for this call.
            let status = unsafe {
                compose_rust_host_init(handshake.as_ptr(), handshake.len() as u32, &mut output)
            };
            assert_eq!(status, STATUS_OK);
            // SAFETY: A successful init returned a readable batch owned by the Host.
            let batch = unsafe { std::slice::from_raw_parts(output.ptr, output.len as usize) };
            let first = decode_batch(batch).unwrap().into_iter().next().unwrap();
            compose_rust_host_shutdown();
            first
        })
        .join()
        .unwrap()
    };

    // Saying nothing follows the host platform, falling back to Material 3.
    assert_eq!(
        first_record(LaunchBuilder::new()),
        Mutation::SetTheme(Theme::adaptive(DesignSystem::Material3))
    );
    assert_eq!(
        first_record(LaunchBuilder::new().with_theme(Theme::unified(DesignSystem::Fluent))),
        Mutation::SetTheme(Theme {
            design_system: DesignSystem::Fluent,
            fallback: DesignSystem::Fluent,
            color_scheme: ColorScheme::FollowSystem,
            adaptive: false,
            fonts: [0; dioxus_compose_adapter::schema::TYPE_ROLE_COUNT],
            palette: None,
        })
    );
    assert_eq!(
        first_record(LaunchBuilder::new().with_theme(Theme::adaptive(DesignSystem::Cupertino))),
        Mutation::SetTheme(Theme {
            design_system: DesignSystem::Cupertino,
            fallback: DesignSystem::Cupertino,
            color_scheme: ColorScheme::FollowSystem,
            adaptive: true,
            fonts: [0; dioxus_compose_adapter::schema::TYPE_ROLE_COUNT],
            palette: None,
        })
    );
}

/// The theme is sent once, not once per frame: resending it would put a record in every
/// batch for a value that almost never changes.
#[test]
fn fr14_set_theme_is_not_resent_every_frame() {
    let mut host = Host::new(app);
    let initial = decode_batch(host.rebuild().unwrap()).unwrap();
    assert!(matches!(initial.first(), Some(Mutation::SetTheme(_))));
    let frame = decode_batch(host.render_frame(0).unwrap()).unwrap();
    assert!(
        !frame
            .iter()
            .any(|mutation| matches!(mutation, Mutation::SetTheme(_)))
    );
}

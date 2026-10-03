//! Notifications, the Host's half: what an application posts reaches a batch as a command
//! record, from a handler or from a worker thread, and what the user presses comes back as
//! an event addressed to the Host rather than to a node.
//!
//! The Renderer's half (permission prompts, presentation while the window is active, one
//! activation per press, a stopped frame clock) is checked in the Renderer's own tests,
//! because that is the side that owns the platform.
//!
//! The queue a worker posts into is process wide, and so is the frame request counter, so
//! every test here takes `STATE` in turn and starts from an empty queue.

use compose_rust::boundary::STATUS_OK;
use compose_rust::codegen::{generate_event_vector, generate_mutation_vector};
use compose_rust::notification::reset_notifications;
use compose_rust::prelude::*;
use compose_rust::protocol::{
    HostEvent, Mutation, PropertyValue, decode_batch, decode_event, encode_event,
};
use compose_rust::schema::BOUNDARY_SCHEMA;
use compose_rust::{
    EventPayload, Host, PropertyKind, RendererApi, WidgetKind, install_renderer_api,
    request_frame_from_worker,
};
use std::ffi::c_int;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Mutex, MutexGuard};

static FRAME_REQUESTS: AtomicUsize = AtomicUsize::new(0);
static STATE: Mutex<()> = Mutex::new(());

extern "C" fn count_request() {
    FRAME_REQUESTS.fetch_add(1, Ordering::AcqRel);
}

extern "C" fn never_run() -> c_int {
    STATUS_OK as c_int
}

/// Takes the shared state, with the counting renderer installed and nothing queued.
fn state() -> MutexGuard<'static, ()> {
    let guard = STATE.lock().unwrap_or_else(|poison| poison.into_inner());
    let _ = install_renderer_api(RendererApi {
        run: never_run,
        request_frame: count_request,
    });
    reset_notifications();
    FRAME_REQUESTS.store(0, Ordering::Release);
    guard
}

fn requests() -> usize {
    FRAME_REQUESTS.load(Ordering::Acquire)
}

/// A notification command, copied out of a batch so the batch can be dropped.
#[derive(Clone, Debug, PartialEq, Eq)]
enum Command {
    Post {
        key: String,
        title: String,
        body: String,
        channel: String,
        actions: [String; 2],
        importance: NotificationImportance,
        presentation: NotificationPresentation,
    },
    Withdraw(String),
    RequestPermission,
}

fn commands(batch: &[u8]) -> Vec<Command> {
    decode_batch(batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::PostNotification {
                key,
                title,
                body,
                channel,
                action_1,
                action_2,
                importance,
                presentation,
            } => Some(Command::Post {
                key: key.to_owned(),
                title: title.to_owned(),
                body: body.to_owned(),
                channel: channel.to_owned(),
                actions: [action_1.to_owned(), action_2.to_owned()],
                importance,
                presentation,
            }),
            Mutation::WithdrawNotification { key } => Some(Command::Withdraw(key.to_owned())),
            Mutation::RequestNotificationPermission => Some(Command::RequestPermission),
            _ => None,
        })
        .collect()
}

fn texts(batch: &[u8]) -> Vec<String> {
    decode_batch(batch)
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
        .collect()
}

fn session_finished() -> Notification {
    Notification::new("세션이 끝났습니다")
        .body("refactor-parser: 테스트 214개 통과")
        .key("session/7")
        .channel("세션")
        .presentation(NotificationPresentation::WhenInactive)
}

fn session_finished_command() -> Command {
    Command::Post {
        key: "session/7".to_owned(),
        title: "세션이 끝났습니다".to_owned(),
        body: "refactor-parser: 테스트 214개 통과".to_owned(),
        channel: "세션".to_owned(),
        actions: [String::new(), String::new()],
        importance: NotificationImportance::Normal,
        presentation: NotificationPresentation::WhenInactive,
    }
}

fn host_event(payload: EventPayload<'_>) -> HostEvent<'_> {
    HostEvent {
        node_id: 0,
        handler_id: 0,
        payload,
    }
}

/// The first button's click handler, from the batch that created it.
fn first_click(batch: &[u8]) -> (u32, u64) {
    let mutations = decode_batch(batch).unwrap();
    let button = mutations
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Button,
            } => Some(*node_id),
            _ => None,
        })
        .expect("the tree has a button");
    let handler = mutations
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnClick,
                value: PropertyValue::Integer(handler),
            } if *node_id == button => Some(*handler as u64),
            _ => None,
        })
        .expect("the button has a click handler");
    (button, handler)
}

fn posting_app() -> Element {
    rsx! {
        Column {
            Button {
                text: "Finish",
                on_click: move |_| {
                    session_finished()
                        .action_1("Open")
                        .action_2("Dismiss")
                        .importance(NotificationImportance::Urgent)
                        .post();
                    withdraw_notification("approval/3");
                    request_notification_permission();
                },
            }
        }
    }
}

/// Posted from a handler, a notification rides out in the batch that handler produced,
/// beside the withdrawal and the request made after it and in the same order. It is a
/// record, not a node: nothing is created for it.
#[test]
fn fr36_a_notification_posted_in_a_handler_rides_that_handlers_batch() {
    let _state = state();
    let mut host = Host::new(posting_app);
    let initial = host.rebuild().unwrap().to_vec();
    assert!(commands(&initial).is_empty());
    let (node_id, handler_id) = first_click(&initial);

    let before = requests();
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        })
        .unwrap();
    assert_eq!(
        commands(batch),
        [
            Command::Post {
                key: "session/7".to_owned(),
                title: "세션이 끝났습니다".to_owned(),
                body: "refactor-parser: 테스트 214개 통과".to_owned(),
                channel: "세션".to_owned(),
                actions: ["Open".to_owned(), "Dismiss".to_owned()],
                importance: NotificationImportance::Urgent,
                presentation: NotificationPresentation::WhenInactive,
            },
            Command::Withdraw("approval/3".to_owned()),
            Command::RequestPermission,
        ]
    );
    assert!(
        !decode_batch(batch)
            .unwrap()
            .iter()
            .any(|mutation| matches!(mutation, Mutation::Create { .. })),
        "a notification is not a node"
    );
    assert_eq!(
        requests(),
        before,
        "a notification posted inside a call needs no frame of its own"
    );
    // Gone once it has been written: the next frame carries nothing.
    assert!(commands(host.render_frame(0).unwrap()).is_empty());
}

fn idle_app() -> Element {
    rsx! { Text { text: "idle" } }
}

/// A worker posts through a handle and nothing else: the Host asks for the frame, and the
/// batch that frame returns carries the notification. The worker's code names no boundary
/// function, which is the whole of what the handle is for.
#[test]
fn fr36_a_worker_notification_reaches_the_next_frame() {
    let _state = state();
    let mut host = Host::new(idle_app);
    host.rebuild().unwrap();
    FRAME_REQUESTS.store(0, Ordering::Release);

    let sender = NotificationSender::new();
    std::thread::spawn(move || {
        sender.post(session_finished());
        sender.withdraw("session/6");
    })
    .join()
    .unwrap();

    assert!(requests() >= 1, "the Host has to ask for the frame itself");
    assert_eq!(
        commands(host.render_frame(1).unwrap()),
        [
            session_finished_command(),
            Command::Withdraw("session/6".to_owned())
        ]
    );
}

/// The handle crosses threads, which is the point of it.
#[test]
fn fr36_the_sender_is_send_and_sync() {
    fn assert_send_sync<T: Send + Sync + Copy>() {}
    assert_send_sync::<NotificationSender>();
}

thread_local! {
    static ACTIVATIONS: std::cell::RefCell<Vec<NotificationActivation>> =
        const { std::cell::RefCell::new(Vec::new()) };
}

fn listening_app() -> Element {
    let mut opened = use_signal(String::new);
    use_notification_activated(move |activation: NotificationActivation| {
        ACTIVATIONS.with_borrow_mut(|seen| seen.push(activation.clone()));
        opened.set(activation.key);
    });
    rsx! { Text { text: "opened {opened}" } }
}

/// A press comes back with the key and which part was pressed, reaches the hook, and the
/// render it causes is in the batch the event returns.
#[test]
fn fr36_an_activation_reaches_the_hook_with_its_key_and_action() {
    let _state = state();
    ACTIVATIONS.with_borrow_mut(Vec::clear);
    let mut host = Host::new(listening_app);
    host.rebuild().unwrap();

    let (batch, result) = host
        .dispatch(host_event(EventPayload::NotificationActivated {
            action: 0,
            key: "session/7",
        }))
        .unwrap();
    assert_eq!(result, 0);
    assert_eq!(texts(batch), ["opened session/7".to_owned()]);

    host.dispatch(host_event(EventPayload::NotificationActivated {
        action: 2,
        key: "approval/3",
    }))
    .unwrap();
    assert_eq!(
        ACTIVATIONS.with_borrow(Clone::clone),
        [
            NotificationActivation {
                key: "session/7".to_owned(),
                action: 0
            },
            NotificationActivation {
                key: "approval/3".to_owned(),
                action: 2
            },
        ]
    );
}

/// With nobody listening an activation is dropped, quietly: no error and nothing rendered.
#[test]
fn fr36_an_activation_nobody_listens_for_is_dropped() {
    let _state = state();
    let mut host = Host::new(idle_app);
    host.rebuild().unwrap();
    let (batch, result) = host
        .dispatch(host_event(EventPayload::NotificationActivated {
            action: 1,
            key: "session/7",
        }))
        .unwrap();
    assert_eq!(result, 0);
    assert!(decode_batch(batch).unwrap().is_empty());
}

/// Both notification events address the Host. One naming a node or a handler is malformed.
#[test]
fn fr36_notification_events_name_no_node_and_no_handler() {
    let _state = state();
    let mut host = Host::new(idle_app);
    host.rebuild().unwrap();
    for payload in [
        EventPayload::NotificationActivated {
            action: 0,
            key: "k",
        },
        EventPayload::NotificationPermissionChanged(NotificationPermission::Granted),
    ] {
        assert!(
            host.dispatch(HostEvent {
                node_id: 3,
                handler_id: 0,
                payload: payload.clone(),
            })
            .is_err()
        );
        assert!(
            host.dispatch(HostEvent {
                node_id: 0,
                handler_id: 9,
                payload,
            })
            .is_err()
        );
    }
}

fn permission_app() -> Element {
    let label = match use_notification_permission() {
        NotificationPermission::NotDetermined => "not asked",
        NotificationPermission::Granted => "granted",
        NotificationPermission::Denied => "denied",
        NotificationPermission::Unsupported => "unsupported",
    };
    rsx! { Text { text: label } }
}

/// Both sides start at NotDetermined, a change re-renders the component that reads it, and a
/// repeat of the same answer renders nothing.
#[test]
fn fr36_permission_changes_reach_the_hook_and_a_repeat_wakes_nobody() {
    let _state = state();
    let mut host = Host::new(permission_app);
    assert_eq!(texts(host.rebuild().unwrap()), ["not asked".to_owned()]);

    let granted = EventPayload::NotificationPermissionChanged(NotificationPermission::Granted);
    let (batch, _) = host.dispatch(host_event(granted.clone())).unwrap();
    assert_eq!(texts(batch), ["granted".to_owned()]);
    assert_eq!(notification_permission(), NotificationPermission::Granted);

    let (batch, _) = host.dispatch(host_event(granted)).unwrap();
    assert!(decode_batch(batch).unwrap().is_empty());

    let (batch, _) = host
        .dispatch(host_event(EventPayload::NotificationPermissionChanged(
            NotificationPermission::Unsupported,
        )))
        .unwrap();
    assert_eq!(texts(batch), ["unsupported".to_owned()]);
}

/// An unknown permission state never reaches the application: the event is refused as
/// malformed, the Host keeps running, and the answer it had stays.
#[test]
fn fr36_an_unknown_permission_state_is_refused_and_the_host_survives() {
    let _state = state();
    let mut host = Host::new(permission_app);
    host.rebuild().unwrap();
    let mut bytes = Vec::new();
    encode_event(
        &host_event(EventPayload::NotificationPermissionChanged(
            NotificationPermission::Denied,
        )),
        &mut bytes,
    )
    .unwrap();
    bytes[16..20].copy_from_slice(&9_u32.to_le_bytes());
    assert!(host.dispatch_event(&bytes).is_err());
    assert_eq!(
        notification_permission(),
        NotificationPermission::NotDetermined
    );
    // Still answering.
    assert!(host.render_frame(2).is_ok());
}

/// While the platform has the UI stopped, frame requests are held, except the one that
/// carries a notification: that goes to the Renderer at once, and the frame it is served in
/// carries the notification and nothing else. Timers stay held, and the request they made
/// is still delivered once on start.
#[test]
fn pr5_a_notification_is_not_held_while_the_ui_is_stopped() {
    let _state = state();
    let mut host = Host::new(idle_app);
    host.rebuild().unwrap();
    host.dispatch(host_event(EventPayload::LifecycleStop))
        .unwrap();
    FRAME_REQUESTS.store(0, Ordering::Release);

    request_frame_from_worker();
    assert_eq!(requests(), 0, "an ordinary request is still held");

    std::thread::spawn(|| NotificationSender::new().post(session_finished()))
        .join()
        .unwrap();
    assert_eq!(requests(), 1, "a notification is not held");

    let batch = host.render_frame(3).unwrap();
    let mutations = decode_batch(batch).unwrap();
    assert_eq!(commands(batch), [session_finished_command()]);
    assert_eq!(mutations.len(), 1, "nothing but the notification is drawn");

    host.dispatch(host_event(EventPayload::LifecycleStart))
        .unwrap();
    assert_eq!(requests(), 2, "the held request is delivered once on start");
}

/// The wire grew by records and events, not by entry points.
#[test]
fn fr36_the_boundary_entry_points_are_unchanged() {
    let names: Vec<&str> = BOUNDARY_SCHEMA.iter().map(|op| op.symbol).collect();
    assert_eq!(
        names,
        [
            "compose_rust_host_init",
            "compose_rust_host_dispatch_event",
            "compose_rust_host_render_frame",
            "compose_rust_host_release_batch",
            "compose_rust_host_shutdown",
        ]
    );
}

/// The three commands and the two events are in the protocol vectors both sides are tested
/// against, so the Renderer decodes the same bytes the Host writes.
#[test]
fn fr36_notification_records_are_in_the_protocol_vectors() {
    let generated = generate_mutation_vector().unwrap();
    for bytes in [
        generated.as_slice(),
        include_bytes!("vectors/mutations.bin").as_slice(),
    ] {
        let mutations = decode_batch(bytes).unwrap();
        assert!(
            mutations
                .iter()
                .any(|mutation| matches!(mutation, Mutation::PostNotification { .. }))
        );
        assert!(
            mutations
                .iter()
                .any(|mutation| matches!(mutation, Mutation::WithdrawNotification { .. }))
        );
        assert!(
            mutations
                .iter()
                .any(|mutation| matches!(mutation, Mutation::RequestNotificationPermission))
        );
    }

    let generated = generate_event_vector().unwrap();
    for bytes in [
        generated.as_slice(),
        include_bytes!("vectors/events.bin").as_slice(),
    ] {
        let mut kinds = Vec::new();
        let mut position = 0;
        while position < bytes.len() {
            let event = decode_event(&bytes[position..]).unwrap();
            let record = usize::from(u16::from_le_bytes([
                bytes[position + 2],
                bytes[position + 3],
            ]));
            let text = match event.payload {
                EventPayload::TextChanged(text)
                | EventPayload::TextSubmitted(text)
                | EventPayload::FilesDropped(text)
                | EventPayload::ProtocolError { message: text, .. }
                | EventPayload::NotificationActivated { key: text, .. } => text.len(),
                _ => 0,
            };
            kinds.push(std::mem::discriminant(&event.payload));
            position += record + text;
        }
        assert!(kinds.contains(&std::mem::discriminant(
            &EventPayload::NotificationActivated { action: 0, key: "" }
        )));
        assert!(kinds.contains(&std::mem::discriminant(
            &EventPayload::NotificationPermissionChanged(NotificationPermission::Granted)
        )));
    }
}

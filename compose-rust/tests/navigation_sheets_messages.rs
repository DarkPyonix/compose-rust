//! Widget tags 30 to 32 and the message record: the three families an application cannot
//! do without, taken through the wire and back.
//!
//! What is checked here is the Host's half of the claim: one declaration, no branch on the
//! window size, and a message that is a record rather than a node. The other half, that
//! the one declaration comes out as a bar, a rail and a drawer, is checked in the
//! Renderer's tests, because only the Renderer knows how wide the window is.

use compose_rust::prelude::*;
use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
use compose_rust::{
    EventPayload, Host, MessageDuration, PropertyKind, WidgetKind, WindowHeightClass,
    WindowSizeClass,
};
use std::cell::RefCell;
use std::sync::atomic::{AtomicUsize, Ordering};

/// What one batch said, with the borrowed strings copied out so the batch can be dropped.
#[derive(Clone, Debug, PartialEq)]
enum Record {
    Create(u32, WidgetKind),
    Prop(u32, PropertyKind, PropertyValue<'static>),
    Message {
        handler_id: u64,
        text: String,
        action: String,
        duration: MessageDuration,
    },
}

fn records(batch: &[u8]) -> Vec<Record> {
    decode_batch(batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create { node_id, widget } => Some(Record::Create(node_id, widget)),
            Mutation::SetProp {
                node_id,
                property,
                value,
            } => Some(Record::Prop(
                node_id,
                property,
                match value {
                    PropertyValue::String(_) | PropertyValue::Bytes(_) => PropertyValue::None,
                    PropertyValue::None => PropertyValue::None,
                    PropertyValue::Bool(value) => PropertyValue::Bool(value),
                    PropertyValue::Integer(value) => PropertyValue::Integer(value),
                    PropertyValue::Float(value) => PropertyValue::Float(value),
                },
            )),
            Mutation::ShowMessage {
                handler_id,
                text,
                action,
                duration,
            } => Some(Record::Message {
                handler_id,
                text: text.to_owned(),
                action: action.to_owned(),
                duration,
            }),
            _ => None,
        })
        .collect()
}

fn created(records: &[Record], widget: WidgetKind) -> Vec<u32> {
    records
        .iter()
        .filter_map(|record| match record {
            Record::Create(node_id, kind) if *kind == widget => Some(*node_id),
            _ => None,
        })
        .collect()
}

fn messages(records: &[Record]) -> Vec<&Record> {
    records
        .iter()
        .filter(|record| matches!(record, Record::Message { .. }))
        .collect()
}

fn resize(host: &mut Host, width_dp: f32) -> Vec<Record> {
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: EventPayload::WindowSizeChanged {
                width_dp,
                height_dp: 800.0,
                class: WindowSizeClass::from_width_dp(width_dp),
                height_class: WindowHeightClass::from_height_dp(800.0),
            },
        })
        .unwrap();
    records(batch)
}

thread_local! {
    static SELECTED: RefCell<usize> = const { RefCell::new(0) };
}

fn navigation_app() -> Element {
    let mut selected = use_signal(|| 0_usize);
    // Deliberately no `use_window_size()`: the whole point is that the Host declares one
    // navigation and never asks how wide the window is.
    rsx! {
        Navigation {
            fill_max_width: true,
            fill_max_height: true,
            selected_index: selected(),
            NavigationItem {
                text: "Tasks",
                icon: IconRole::List,
                on_click: move |()| {
                    selected.set(0);
                    SELECTED.with_borrow_mut(|value| *value = 0);
                },
            }
            NavigationItem {
                text: "Done",
                icon: IconRole::Check,
                on_click: move |()| {
                    selected.set(1);
                    SELECTED.with_borrow_mut(|value| *value = 1);
                },
            }
            Text { text: "the screen" }
        }
    }
}

/// One declaration, and it does not change when the window does.
///
/// The Renderer chooses a bar, a rail or a drawer from the width it has already measured.
/// If the Host chose instead, crossing a boundary would tear the destinations down and
/// build them again, and that is exactly what the absence of `Create` records here denies.
#[test]
fn fr21_navigation_is_one_declaration_and_a_resize_creates_nothing() {
    let mut host = Host::new(navigation_app);
    let initial = records(host.rebuild().unwrap());
    assert_eq!(created(&initial, WidgetKind::Navigation).len(), 1);
    assert_eq!(created(&initial, WidgetKind::NavigationItem).len(), 2);

    for width in [700.0, 1100.0, 500.0] {
        let batch = resize(&mut host, width);
        assert!(
            created(&batch, WidgetKind::Navigation).is_empty()
                && created(&batch, WidgetKind::NavigationItem).is_empty(),
            "a resize to {width}dp rebuilt navigation nodes: {batch:?}",
        );
    }
}

/// A destination carries its label and its icon as properties, so the three presentations
/// are free to arrange them differently.
#[test]
fn fr21_a_destination_sends_its_label_and_the_meaning_of_its_icon() {
    let mut host = Host::new(navigation_app);
    let initial = records(host.rebuild().unwrap());
    let destinations = created(&initial, WidgetKind::NavigationItem);
    let icons: Vec<_> = initial
        .iter()
        .filter_map(|record| match record {
            Record::Prop(node_id, PropertyKind::Icon, PropertyValue::Integer(role))
                if destinations.contains(node_id) =>
            {
                Some(*role)
            }
            _ => None,
        })
        .collect();
    assert_eq!(
        icons,
        vec![
            i64::from(IconRole::List as u16),
            i64::from(IconRole::Check as u16)
        ],
    );
}

/// Choosing a destination is that destination's own click, so no new event tag was needed
/// and the selection moves in the Renderer.
#[test]
fn fr21_choosing_a_destination_fires_that_destinations_click_once() {
    SELECTED.with_borrow_mut(|value| *value = 0);
    let mut host = Host::new(navigation_app);
    let initial = records(host.rebuild().unwrap());
    let destinations = created(&initial, WidgetKind::NavigationItem);
    let second = destinations[1];
    let handler = initial
        .iter()
        .find_map(|record| match record {
            Record::Prop(node_id, PropertyKind::OnClick, PropertyValue::Integer(handler))
                if *node_id == second =>
            {
                Some(*handler as u64)
            }
            _ => None,
        })
        .expect("the second destination has no click handler");

    let (batch, _) = host
        .dispatch(HostEvent {
            node_id: second,
            handler_id: handler,
            payload: EventPayload::Clicked,
        })
        .unwrap();
    let after = records(batch);
    assert_eq!(SELECTED.with_borrow(|value| *value), 1);
    assert!(
        created(&after, WidgetKind::NavigationItem).is_empty(),
        "selecting a destination rebuilt the destinations: {after:?}",
    );
}

static DELETES: AtomicUsize = AtomicUsize::new(0);
static UNDOS: AtomicUsize = AtomicUsize::new(0);

fn message_app() -> Element {
    rsx! {
        Column {
            Button {
                text: "Delete",
                on_click: move |_| {
                    DELETES.fetch_add(1, Ordering::SeqCst);
                    Message::new("Task deleted")
                        .with_action("Undo", move |()| {
                            UNDOS.fetch_add(1, Ordering::SeqCst);
                        })
                        .with_duration(MessageDuration::Long)
                        .show();
                },
            }
            Button {
                text: "Two",
                on_click: move |_| {
                    show_message("first");
                    show_message("second");
                },
            }
        }
    }
}

fn click_button(host: &mut Host, initial: &[Record], index: usize) -> Vec<Record> {
    let buttons = created(initial, WidgetKind::Button);
    let node = buttons[index];
    let handler = initial
        .iter()
        .find_map(|record| match record {
            Record::Prop(node_id, PropertyKind::OnClick, PropertyValue::Integer(handler))
                if *node_id == node =>
            {
                Some(*handler as u64)
            }
            _ => None,
        })
        .expect("that button has no click handler");
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id: node,
            handler_id: handler,
            payload: EventPayload::Clicked,
        })
        .unwrap();
    records(batch)
}

/// A message is a record, not a node: nothing is created, and the Host never has to take
/// it away again.
#[test]
fn fr21_a_message_rides_the_batch_and_creates_no_node() {
    DELETES.store(0, Ordering::SeqCst);
    UNDOS.store(0, Ordering::SeqCst);
    let mut host = Host::new(message_app);
    let initial = records(host.rebuild().unwrap());
    assert!(messages(&initial).is_empty());

    let after = click_button(&mut host, &initial, 0);
    assert_eq!(DELETES.load(Ordering::SeqCst), 1);
    let posted = messages(&after);
    assert_eq!(posted.len(), 1);
    let Record::Message {
        handler_id,
        text,
        action,
        duration,
    } = posted[0].clone()
    else {
        unreachable!("filtered above")
    };
    assert_eq!(text, "Task deleted");
    assert_eq!(action, "Undo");
    assert_eq!(duration, MessageDuration::Long);
    assert_ne!(handler_id, 0);
    assert!(
        created(&after, WidgetKind::Text).is_empty(),
        "saying something created a node: {after:?}",
    );

    // Pressing the action names no node, because the message owns none.
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id: 0,
            handler_id,
            payload: EventPayload::Clicked,
        })
        .unwrap();
    let _ = records(batch);
    assert_eq!(UNDOS.load(Ordering::SeqCst), 1);

    // An action is pressed once. The message is gone afterwards, so a second press is a
    // press on something that is no longer there and is refused rather than run again.
    assert!(
        host.dispatch(HostEvent {
            node_id: 0,
            handler_id,
            payload: EventPayload::Clicked,
        })
        .is_err()
    );
    assert_eq!(UNDOS.load(Ordering::SeqCst), 1);
}

/// Two messages said in one breath stay two messages, in the order they were said. What
/// happens next, one at a time, is the Renderer's business.
#[test]
fn fr21_two_messages_in_one_call_arrive_in_order() {
    let mut host = Host::new(message_app);
    let initial = records(host.rebuild().unwrap());
    let after = click_button(&mut host, &initial, 1);
    let texts: Vec<_> = messages(&after)
        .into_iter()
        .map(|record| match record {
            Record::Message { text, .. } => text.clone(),
            _ => unreachable!("filtered above"),
        })
        .collect();
    assert_eq!(texts, vec!["first".to_owned(), "second".to_owned()]);
}

fn sheet_app() -> Element {
    let mut open = use_signal(|| true);
    rsx! {
        Sheet {
            open: open(),
            on_dismiss: move |()| {
                open.set(false);
                DISMISSALS.fetch_add(1, Ordering::SeqCst);
            },
            Text { text: "the sheet's content" }
        }
    }
}

static DISMISSALS: AtomicUsize = AtomicUsize::new(0);

/// A sheet is a Dialog on the wire: an open flag the Renderer owns from there, and one
/// dismissal. Nothing about how far it has been dragged crosses.
#[test]
fn fr21_a_sheet_seeds_its_open_state_and_reports_one_dismissal() {
    DISMISSALS.store(0, Ordering::SeqCst);
    let mut host = Host::new(sheet_app);
    let initial = records(host.rebuild().unwrap());
    let sheet = created(&initial, WidgetKind::Sheet)[0];
    assert!(initial.contains(&Record::Prop(
        sheet,
        PropertyKind::Open,
        PropertyValue::Bool(true),
    )));
    let handler = initial
        .iter()
        .find_map(|record| match record {
            Record::Prop(node_id, PropertyKind::OnDismiss, PropertyValue::Integer(handler))
                if *node_id == sheet =>
            {
                Some(*handler as u64)
            }
            _ => None,
        })
        .expect("the sheet has no dismiss handler");

    let (batch, _) = host
        .dispatch(HostEvent {
            node_id: sheet,
            handler_id: handler,
            payload: EventPayload::Clicked,
        })
        .unwrap();
    let after = records(batch);
    assert_eq!(DISMISSALS.load(Ordering::SeqCst), 1);
    assert!(after.contains(&Record::Prop(
        sheet,
        PropertyKind::Open,
        PropertyValue::Bool(false),
    )));
}

/// A destination drawn in the colour the application named.
///
/// The design system decides what selected looks like where nobody says otherwise, which
/// is what an adaptive application wants. A unified one has a reference to match, and not
/// every reference has an accent in its bar: the drum school's is white icons on black,
/// and asking the active system instead put its own accent on the selected one. Without a
/// colour to send, the sample had no way to say what its picture says.
#[test]
fn fr14_a_destination_carries_the_colour_it_was_given() {
    fn app() -> Element {
        rsx! {
            Navigation {
                selected_index: 0_usize,
                NavigationItem {
                    text: "Skills",
                    icon: IconRole::List,
                    color: Paint::Literal(Color::rgb(0xff_ffff)),
                    on_click: move |()| {},
                }
                Text { text: "the screen" }
            }
        }
    }

    let mut host = Host::new(app);
    let mutations = records(host.rebuild().expect("the first tree encodes"));
    let painted = mutations
        .iter()
        .any(|record| matches!(record, Record::Prop(_, PropertyKind::Color, _)));
    assert!(
        painted,
        "the destination sent no colour, so the renderer has nothing to use in place of \
         the design system's accent and a unified sample cannot match its reference"
    );
}

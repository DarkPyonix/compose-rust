//! Widget tags 18 to 25: the containers, the overlays, the tab strip and the horizontal
//! windowed list, each taken through the wire and back.

use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
use dioxus_compose::{EventPayload, Host, PropertyKind, WidgetKind};
use std::cell::RefCell;

/// The decoded records of the first frame, with borrowed strings turned into owned ones so
/// the batch can be dropped.
#[derive(Clone, Debug, PartialEq)]
enum Record {
    Create(u32, WidgetKind),
    Prop(u32, PropertyKind, PropertyValue<'static>),
    Insert(u32, u32, u32),
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
                    // The assertions here never look at string contents except through
                    // `text_of`, which reads the batch directly.
                    // Neither strings nor byte blobs are compared by value here.
                    PropertyValue::String(_) | PropertyValue::Bytes(_) => PropertyValue::None,
                    PropertyValue::None => PropertyValue::None,
                    PropertyValue::Bool(value) => PropertyValue::Bool(value),
                    PropertyValue::Integer(value) => PropertyValue::Integer(value),
                    PropertyValue::Float(value) => PropertyValue::Float(value),
                },
            )),
            Mutation::Insert {
                parent_id,
                node_id,
                index,
            } => Some(Record::Insert(parent_id, node_id, index)),
            _ => None,
        })
        .collect()
}

fn node_of(records: &[Record], widget: WidgetKind) -> u32 {
    records
        .iter()
        .find_map(|record| match record {
            Record::Create(node_id, kind) if *kind == widget => Some(*node_id),
            _ => None,
        })
        .unwrap_or_else(|| panic!("no {widget:?} was created"))
}

/// Children in the order the parent will draw them, replayed the way the Renderer applies
/// a batch: each Insert takes the position its index names and moves the ones already
/// standing there along. Node id 0 is the Host's placeholder for a dynamic slot that
/// produced nothing, so it occupies no position and is dropped.
///
/// The indices cannot be sorted on instead. An index is a position in the list as it
/// stands when that record is applied, not a position in the finished list, and the Host
/// builds a dynamic slot before the slot that comes before it whenever Dioxus hands the
/// two over in that order.
fn children_of(records: &[Record], parent: u32) -> Vec<u32> {
    let mut children: Vec<u32> = Vec::new();
    for record in records {
        let Record::Insert(parent_id, node_id, index) = record else {
            continue;
        };
        if *parent_id != parent || *node_id == 0 {
            continue;
        }
        let at = (*index as usize).min(children.len());
        children.insert(at, *node_id);
    }
    children
}

fn handler_of(records: &[Record], node: u32, property: PropertyKind) -> u64 {
    records
        .iter()
        .find_map(|record| match record {
            Record::Prop(node_id, kind, PropertyValue::Integer(value))
                if *node_id == node && *kind == property =>
            {
                Some(*value as u64)
            }
            _ => None,
        })
        .unwrap_or_else(|| panic!("node {node} has no {property:?}"))
}

fn text_of(batch: &[u8], node: u32, property: PropertyKind) -> String {
    decode_batch(batch)
        .unwrap()
        .into_iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: kind,
                value: PropertyValue::String(text),
            } if node_id == node && kind == property => Some(text.to_owned()),
            _ => None,
        })
        .unwrap_or_else(|| panic!("node {node} has no {property:?} string"))
}

/// A widget tag is assigned once and never reused, so a later edit that renumbers one of
/// these silently breaks every Renderer already built against it. Pinning the numbers here
/// is what turns that into a failing test instead.
#[test]
fn fr15_container_and_overlay_widgets_keep_their_assigned_tags() {
    assert_eq!(
        [
            WidgetKind::Card as u16,
            WidgetKind::Surface as u16,
            WidgetKind::Dialog as u16,
            WidgetKind::Menu as u16,
            WidgetKind::Tabs as u16,
            WidgetKind::TopAppBar as u16,
            WidgetKind::LazyRow as u16,
            WidgetKind::Tooltip as u16,
        ],
        [18, 19, 20, 21, 22, 23, 24, 25],
    );
}

fn card_app() -> Element {
    rsx! {
        Card {
            Text { text: "grouped" }
        }
    }
}

/// A Card says "these belong together" and nothing about how that is drawn, so it reaches
/// the wire as a widget tag with children and no properties at all.
#[test]
fn fr15_card_carries_children_and_no_appearance() {
    let mut host = Host::new(card_app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    let card = node_of(&records, WidgetKind::Card);
    assert_eq!(children_of(&records, card).len(), 1);
    assert!(
        !records
            .iter()
            .any(|record| matches!(record, Record::Prop(node_id, _, _) if *node_id == card)),
        "a Card must not send appearance of its own: {records:?}",
    );
}

fn surface_app() -> Element {
    rsx! {
        Surface {
            Text { text: "raised" }
        }
    }
}

/// A Surface is the same contract as a Card: background and height come from the design
/// system, and `Modifier::Elevation` is the only way the Host adjusts the height.
#[test]
fn fr15_surface_carries_children_and_no_appearance() {
    let mut host = Host::new(surface_app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    let surface = node_of(&records, WidgetKind::Surface);
    assert_eq!(children_of(&records, surface).len(), 1);
    assert!(
        !records
            .iter()
            .any(|record| matches!(record, Record::Prop(node_id, _, _) if *node_id == surface)),
    );
}

thread_local! {
    static DISMISSED: RefCell<u32> = const { RefCell::new(0) };
}

fn dialog_app() -> Element {
    rsx! {
        Dialog {
            open: true,
            on_dismiss: move |()| DISMISSED.with(|count| *count.borrow_mut() += 1),
            Text { text: "are you sure" }
        }
    }
}

/// The open state belongs to the Renderer. `open` seeds it and a dismissal comes back as
/// an event, so opening and closing never re-renders the Host's tree by itself.
#[test]
fn fr15_dialog_seeds_open_state_and_reports_dismissal() {
    DISMISSED.with(|count| *count.borrow_mut() = 0);
    let mut host = Host::new(dialog_app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    let dialog = node_of(&records, WidgetKind::Dialog);
    assert!(records.contains(&Record::Prop(
        dialog,
        PropertyKind::Open,
        PropertyValue::Bool(true)
    )));
    let handler_id = handler_of(&records, dialog, PropertyKind::OnDismiss);

    // A dismissal carries no value, so it travels as the empty payload, encoded and
    // decoded exactly as the Renderer would send it.
    let mut wire = Vec::new();
    let event = HostEvent {
        node_id: dialog,
        handler_id,
        payload: EventPayload::Clicked,
    };
    encode_event(&event, &mut wire).unwrap();
    host.dispatch_event(&wire).unwrap();
    assert_eq!(DISMISSED.with(|count| *count.borrow()), 1);
}

fn menu_app() -> Element {
    rsx! {
        Menu {
            expanded: true,
            anchor: rsx! { Button { text: "open" } },
            Text { text: "first entry" }
            Text { text: "second entry" }
        }
    }
}

/// The Renderer places the popup, so it has to know which child the popup hangs off. The
/// anchor is child 0 and the entries follow it.
#[test]
fn fr15_menu_sends_its_anchor_as_the_first_child() {
    let mut host = Host::new(menu_app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    let menu = node_of(&records, WidgetKind::Menu);
    let children = children_of(&records, menu);
    assert_eq!(children.len(), 3);
    assert_eq!(children[0], node_of(&records, WidgetKind::Button));
    assert!(records.contains(&Record::Prop(
        menu,
        PropertyKind::Open,
        PropertyValue::Bool(true)
    )));
}

thread_local! {
    static SELECTED: RefCell<Vec<usize>> = const { RefCell::new(Vec::new()) };
}

fn tabs_app() -> Element {
    rsx! {
        Tabs {
            selected_index: 1,
            for index in 0..3 {
                Button {
                    key: "{index}",
                    text: "tab {index}",
                    on_click: move |()| SELECTED.with(|log| log.borrow_mut().push(index)),
                }
            }
        }
    }
}

/// The selection belongs to the Renderer: `selected_index` seeds it, and which tab was
/// chosen comes back as that tab's own click, so no new event payload is needed and the
/// node id says which one it was.
#[test]
fn fr15_tabs_seed_the_selection_and_report_it_as_the_tab_s_own_click() {
    SELECTED.with(|log| log.borrow_mut().clear());
    let mut host = Host::new(tabs_app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    let tabs = node_of(&records, WidgetKind::Tabs);
    assert!(records.contains(&Record::Prop(
        tabs,
        PropertyKind::SelectedIndex,
        PropertyValue::Integer(1)
    )));
    let children = children_of(&records, tabs);
    assert_eq!(children.len(), 3);

    let third = children[2];
    let handler_id = handler_of(&records, third, PropertyKind::OnClick);
    let mut wire = Vec::new();
    let event = HostEvent {
        node_id: third,
        handler_id,
        payload: EventPayload::Clicked,
    };
    encode_event(&event, &mut wire).unwrap();
    host.dispatch_event(&wire).unwrap();
    assert_eq!(SELECTED.with(|log| log.borrow().clone()), vec![2]);
}

fn top_app_bar_app() -> Element {
    rsx! {
        TopAppBar {
            Text { text: "Inbox" }
            Button { text: "compose" }
        }
    }
}

/// The bar's height, spacing and separation are the design system's rules, so the widget
/// sends its content and nothing else.
///
/// Its title is content. It is a property rather than a child because the Renderer has to
/// be able to find it, since three design systems centre the window title and a bar whose
/// children are an arbitrary tree gives no way to tell which of them to centre. What the
/// bar still must not send is anything about how it looks.
#[test]
fn fr15_top_app_bar_carries_only_its_content() {
    let mut host = Host::new(top_app_bar_app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    let bar = node_of(&records, WidgetKind::TopAppBar);
    assert_eq!(children_of(&records, bar).len(), 2);
    let styling: Vec<_> = records
        .iter()
        .filter_map(|record| match record {
            Record::Prop(node_id, kind, _) if *node_id == bar && *kind != PropertyKind::Text => {
                Some(*kind)
            }
            _ => None,
        })
        .collect();
    assert!(
        styling.is_empty(),
        "the bar sent {styling:?}, and how it looks is the design system's to decide"
    );
}

fn lazy_row_app() -> Element {
    rsx! {
        LazyRow {
            item_count: 10_000,
            key_of: move |index: usize| format!("row-{index}"),
            item: move |index: usize| rsx! { Text { text: "cell {index}" } },
        }
    }
}

/// The windowing protocol says nothing about an axis, so the horizontal list keeps the same
/// contract: the Host materialises exactly the requested range and never widens it, which is
/// what lets the Renderer place the first child it receives at global index `start`.
#[test]
fn fr15_lazy_row_materialises_exactly_the_requested_range() {
    let mut host = Host::new(lazy_row_app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    let row = node_of(&records, WidgetKind::LazyRow);
    assert!(records.contains(&Record::Prop(
        row,
        PropertyKind::ItemCount,
        PropertyValue::Integer(10_000)
    )));
    assert!(children_of(&records, row).is_empty());

    let handler_id = handler_of(&records, row, PropertyKind::OnRangeRequested);
    let mut wire = Vec::new();
    let event = HostEvent {
        node_id: row,
        handler_id,
        payload: EventPayload::RangeRequested {
            start: 500,
            count: 24,
        },
    };
    encode_event(&event, &mut wire).unwrap();
    let (batch, _) = host.dispatch_event(&wire).unwrap();
    let batch = batch.to_vec();
    let created = decode_batch(&batch)
        .unwrap()
        .into_iter()
        .filter(|mutation| matches!(mutation, Mutation::Create { widget, .. } if *widget == WidgetKind::Box))
        .count();
    assert_eq!(created, 24, "the Host must not widen the requested range");
}

fn tooltip_app() -> Element {
    rsx! {
        Tooltip {
            text: "Delete the selected message",
            Button { text: "delete" }
        }
    }
}

/// The description is the payload, not a hover behaviour. The Renderer decides the delay,
/// the placement and whether a pointer is involved at all.
#[test]
fn fr15_tooltip_carries_its_description_as_text() {
    let mut host = Host::new(tooltip_app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    let tooltip = node_of(&records, WidgetKind::Tooltip);
    assert_eq!(
        text_of(&batch, tooltip, PropertyKind::Text),
        "Delete the selected message",
    );
    assert_eq!(children_of(&records, tooltip).len(), 1);
}

/// Every one of the eight is reachable from `rsx!` and lands on its own widget tag in a
/// single frame, which is the per-widget round trip taken as one tree.
#[test]
fn fr15_all_eight_widgets_round_trip_in_one_frame() {
    fn app() -> Element {
        rsx! {
            Column {
                TopAppBar {
                    Text { text: "title" }
                }
                Card {
                    Surface {
                        Tooltip {
                            text: "why",
                            Text { text: "body" }
                        }
                    }
                }
                Tabs {
                    selected_index: 0,
                    Button { text: "one" }
                }
                Menu {
                    anchor: rsx! { Button { text: "more" } },
                    Text { text: "entry" }
                }
                Dialog {
                    Text { text: "modal" }
                }
                LazyRow { item_count: 3, item: move |index: usize| rsx! { Text { text: "{index}" } } }
            }
        }
    }

    let mut host = Host::new(app);
    let batch = host.rebuild().unwrap().to_vec();
    let records = records(&batch);
    for widget in [
        WidgetKind::Card,
        WidgetKind::Surface,
        WidgetKind::Dialog,
        WidgetKind::Menu,
        WidgetKind::Tabs,
        WidgetKind::TopAppBar,
        WidgetKind::LazyRow,
        WidgetKind::Tooltip,
    ] {
        node_of(&records, widget);
    }
}

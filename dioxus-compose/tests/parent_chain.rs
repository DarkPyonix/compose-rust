//! The tree the Host describes has to be a tree.
//!
//! Every Insert and Move names a parent, and a renderer that keeps that parent for each
//! node walks up from a node to find its root. A chain that loops has no root, so the walk
//! never ends.

use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
use dioxus_compose::{EventPayload, Host, PropertyKind, WidgetKind};
use std::collections::HashMap;

/// A screen with two branches, both of which place the same held `Element`.
///
/// Holding an `Element` in a variable and using it in more than one branch is the shape
/// that produced a looping parent chain: the same node is mounted, unmounted and mounted
/// again as the branches swap.
fn branching_app() -> Element {
    let mut expanded = use_signal(|| false);
    let held = rsx! { Text { text: "held" } };
    rsx! {
        Column {
            Button {
                text: "toggle",
                on_click: move |_| {
                    let now = expanded();
                    expanded.set(!now);
                },
            }
            if expanded() {
                Column {
                    {held.clone()}
                    Text { text: "only while expanded" }
                }
            } else {
                Row {
                    Text { text: "only while collapsed" }
                    {held.clone()}
                }
            }
        }
    }
}

/// The same held `Element` placed in two branches that can both be on screen at once,
/// with a sibling that comes and goes between them.
fn doubled_app() -> Element {
    let mut step = use_signal(|| 0_usize);
    let held = rsx! {
        Column {
            Text { text: "held" }
            Text { text: "held tail" }
        }
    };
    let phase = step() % 3;
    rsx! {
        Column {
            Button {
                text: "step",
                on_click: move |_| {
                    let now = step();
                    step.set(now + 1);
                },
            }
            if phase == 0 {
                Row { {held.clone()} }
            }
            if phase != 1 {
                Text { text: "between" }
            }
            Column {
                {held.clone()}
                if phase == 2 {
                    Row {
                        Text { text: "tail" }
                        {held.clone()}
                    }
                }
            }
        }
    }
}

/// The parent of each node, as a renderer that trusts every Insert and Move would hold it.
fn follow(batch: &[u8], parents: &mut HashMap<u32, u32>) {
    for mutation in decode_batch(batch).unwrap() {
        match mutation {
            Mutation::Insert {
                parent_id, node_id, ..
            }
            | Mutation::Move {
                parent_id, node_id, ..
            } => {
                if node_id != 0 {
                    parents.insert(node_id, parent_id);
                }
            }
            Mutation::Remove { node_id } => {
                parents.remove(&node_id);
            }
            _ => {}
        }
    }
}

/// The chain from `node` up to its root, or the node the walk met twice.
fn loops_from(parents: &HashMap<u32, u32>, node: u32) -> Option<Vec<u32>> {
    let mut chain = vec![node];
    let mut current = node;
    for _ in 0..parents.len() + 1 {
        let Some(&parent) = parents.get(&current) else {
            return None;
        };
        if chain.contains(&parent) {
            chain.push(parent);
            return Some(chain);
        }
        chain.push(parent);
        current = parent;
    }
    Some(chain)
}

/// Clicks the one button `rounds` times, following every parent the Host names and
/// failing the moment a chain loops.
fn toggle_and_follow(app: fn() -> Element, rounds: usize) {
    let mut host = Host::new(app);
    let mut parents = HashMap::new();
    let batch = host.rebuild().unwrap().to_vec();
    follow(&batch, &mut parents);

    let records = decode_batch(&batch).unwrap();
    let button = records
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Button,
            } => Some(*node_id),
            _ => None,
        })
        .expect("the screen has a button");
    let handler = records
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnClick,
                value: PropertyValue::Integer(id),
            } if *node_id == button => Some(*id as u64),
            _ => None,
        })
        .expect("the button reports its clicks");

    for round in 0..rounds {
        let mut wire = Vec::new();
        encode_event(
            &HostEvent {
                node_id: button,
                handler_id: handler,
                payload: EventPayload::Clicked,
            },
            &mut wire,
        )
        .unwrap();
        let (batch, _) = host.dispatch_event(&wire).unwrap();
        follow(&batch.to_vec(), &mut parents);

        let nodes: Vec<u32> = parents.keys().copied().collect();
        for node in nodes {
            assert!(
                loops_from(&parents, node).is_none(),
                "round {round}: the parent chain from {node} loops: {:?}",
                loops_from(&parents, node),
            );
        }
    }
}

#[test]
fn nfr7_swapping_branches_never_loops_the_parent_chain() {
    toggle_and_follow(branching_app, 6);
}

#[test]
fn nfr7_mounting_a_held_element_twice_never_loops_the_parent_chain() {
    toggle_and_follow(doubled_app, 9);
}

/// Two branches that are both empty at first, in two different parents.
///
/// An empty branch is a Dioxus placeholder, and a placeholder is not a node in the Compose
/// tree. Two of them on screen at once still stand in two different places, and when they
/// fill in, each one's content belongs to its own parent.
fn two_placeholders_app() -> Element {
    let mut show = use_signal(|| false);
    rsx! {
        Column {
            Button {
                text: "toggle",
                on_click: move |_| {
                    let now = show();
                    show.set(!now);
                },
            }
            Row {
                Text { text: "row head" }
                if show() {
                    Text { text: "inside the row" }
                }
            }
            Card {
                Text { text: "card head" }
                if show() {
                    Text { text: "inside the card" }
                }
            }
        }
    }
}

/// The node the given text was set on.
fn node_with_text(batch: &[Mutation<'_>], text: &str) -> Option<u32> {
    batch.iter().find_map(|mutation| match mutation {
        Mutation::SetProp {
            node_id,
            property: PropertyKind::Text,
            value: PropertyValue::String(value),
        } if *value == text => Some(*node_id),
        _ => None,
    })
}

/// The parent the given node was last attached to.
fn parent_of(batch: &[Mutation<'_>], node: u32) -> Option<u32> {
    batch.iter().rev().find_map(|mutation| match mutation {
        Mutation::Insert {
            parent_id, node_id, ..
        }
        | Mutation::Move {
            parent_id, node_id, ..
        } if *node_id == node => Some(*parent_id),
        _ => None,
    })
}

#[test]
fn fr1_an_empty_branch_fills_in_under_its_own_parent() {
    let mut host = Host::new(two_placeholders_app);
    let first = host.rebuild().unwrap().to_vec();
    let records = decode_batch(&first).unwrap();
    let row = records
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Row,
            } => Some(*node_id),
            _ => None,
        })
        .expect("the screen has a row");
    let card = records
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Card,
            } => Some(*node_id),
            _ => None,
        })
        .expect("the screen has a card");
    let button = records
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Button,
            } => Some(*node_id),
            _ => None,
        })
        .expect("the screen has a button");
    let handler = records
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnClick,
                value: PropertyValue::Integer(id),
            } if *node_id == button => Some(*id as u64),
            _ => None,
        })
        .expect("the button reports its clicks");

    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: button,
            handler_id: handler,
            payload: EventPayload::Clicked,
        },
        &mut wire,
    )
    .unwrap();
    let second = host.dispatch_event(&wire).unwrap().0.to_vec();
    let filled = decode_batch(&second).unwrap();

    let in_row = node_with_text(&filled, "inside the row").expect("the row's branch filled in");
    let in_card = node_with_text(&filled, "inside the card").expect("the card's branch filled in");
    assert_eq!(
        Some(row),
        parent_of(&filled, in_row),
        "the row's branch was attached somewhere else",
    );
    assert_eq!(
        Some(card),
        parent_of(&filled, in_card),
        "the card's branch was attached somewhere else",
    );
}

/// A Menu whose children are a branch that is not taken, inside a list item.
///
/// The empty branch and the loop over nothing both leave a placeholder, and the list
/// materialises its window on demand, so the same positions are filled in and emptied
/// again as the window moves.
fn menu_in_a_list_app() -> Element {
    rsx! {
        LazyColumn {
            item_count: 20,
            item: move |index: usize| rsx! {
                Row {
                    Text { text: "row {index}" }
                    if index == usize::MAX {
                        Text { text: "never" }
                    }
                    Menu {
                        expanded: false,
                        anchor: rsx! { Button { text: "menu" } },
                        if index == usize::MAX {
                            Text { text: "never either" }
                        }
                        for entry in Vec::<usize>::new() {
                            Text { text: "{entry}" }
                        }
                    }
                }
            },
        }
    }
}

#[test]
fn nfr7_a_menu_of_empty_branches_in_a_list_item_never_loops_the_parent_chain() {
    let mut host = Host::new(menu_in_a_list_app);
    let mut parents = HashMap::new();
    let batch = host.rebuild().unwrap().to_vec();
    follow(&batch, &mut parents);

    let records = decode_batch(&batch).unwrap();
    let list = records
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::LazyColumn,
            } => Some(*node_id),
            _ => None,
        })
        .expect("the screen has a list");
    let handler = records
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnRangeRequested,
                value: PropertyValue::Integer(id),
            } if *node_id == list => Some(*id as u64),
            _ => None,
        })
        .expect("the list asks for its range");

    // Three windows, each one replacing the last, which is what scrolling costs.
    for (round, start) in [0_u32, 6, 12].into_iter().enumerate() {
        let mut wire = Vec::new();
        encode_event(
            &HostEvent {
                node_id: list,
                handler_id: handler,
                payload: EventPayload::RangeRequested { start, count: 6 },
            },
            &mut wire,
        )
        .unwrap();
        let batch = host.dispatch_event(&wire).unwrap().0.to_vec();
        follow(&batch, &mut parents);

        let nodes: Vec<u32> = parents.keys().copied().collect();
        for node in nodes {
            assert!(
                loops_from(&parents, node).is_none(),
                "round {round}: the parent chain from {node} loops: {:?}",
                loops_from(&parents, node),
            );
        }
    }
}

/// A list whose items hold an empty Box.
///
/// A Box with no children still has a children slot, and an empty slot is a placeholder,
/// so a window of these items is a window of placeholders standing in different parents.
fn empty_box_in_a_list_app() -> Element {
    rsx! {
        LazyColumn {
            item_count: 20,
            item: move |index: usize| rsx! {
                Column {
                    dioxus_compose::Box {}
                    Text { text: "row {index}" }
                }
            },
        }
    }
}

#[test]
fn fr8_an_empty_box_in_a_list_item_keeps_the_item_under_its_own_parent() {
    let mut host = Host::new(empty_box_in_a_list_app);
    let mut parents = HashMap::new();
    let batch = host.rebuild().unwrap().to_vec();
    follow(&batch, &mut parents);

    let records = decode_batch(&batch).unwrap();
    let list = records
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::LazyColumn,
            } => Some(*node_id),
            _ => None,
        })
        .expect("the screen has a list");
    let handler = records
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnRangeRequested,
                value: PropertyValue::Integer(id),
            } if *node_id == list => Some(*id as u64),
            _ => None,
        })
        .expect("the list asks for its range");

    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: list,
            handler_id: handler,
            payload: EventPayload::RangeRequested { start: 0, count: 4 },
        },
        &mut wire,
    )
    .unwrap();
    let batch = host.dispatch_event(&wire).unwrap().0.to_vec();
    follow(&batch, &mut parents);
    let window = decode_batch(&batch).unwrap();

    for index in 0..4 {
        let text = node_with_text(&window, &format!("row {index}"))
            .unwrap_or_else(|| panic!("item {index} was not materialised"));
        let column = parent_of(&window, text).expect("the item's text was attached");
        let wrapper = parent_of(&window, column).expect("the item's column was attached");
        assert_eq!(
            Some(list),
            parent_of(&window, wrapper),
            "item {index} was attached outside the list",
        );
    }
    let nodes: Vec<u32> = parents.keys().copied().collect();
    for node in nodes {
        assert!(
            loops_from(&parents, node).is_none(),
            "the parent chain from {node} loops: {:?}",
            loops_from(&parents, node),
        );
    }
}

/// A list of rows whose actions are entries declared only while the row's menu is open.
///
/// This is the shape a row of overflow actions wants: nothing is built for an action until
/// someone asks for it. It puts three conditionals in one item, one inside another. The
/// row chooses between editing and reading, the menu's entries are a branch of their own,
/// and the entries themselves are a loop, so an item carries three placeholders that fill
/// in and empty again while the list's window moves over them.
fn menu_of_lazy_entries_app() -> Element {
    let mut open = use_signal(|| None::<usize>);
    let mut editing = use_signal(|| None::<usize>);
    rsx! {
        LazyColumn {
            item_count: 40,
            item: move |index: usize| rsx! {
                Row {
                    if editing() == Some(index) {
                        TextField { placeholder: "title" }
                        Button {
                            text: "Save",
                            on_click: move |_| editing.set(None),
                        }
                    } else {
                        Text { text: "row {index}" }
                        Menu {
                            expanded: open() == Some(index),
                            on_dismiss: move |_| open.set(None),
                            anchor: rsx! {
                                Button {
                                    text: "actions",
                                    on_click: move |_| open.set(Some(index)),
                                }
                            },
                            if open() == Some(index) {
                                for entry in MENU_ENTRIES {
                                    Button {
                                        text: "{entry}",
                                        on_click: move |_| {
                                            open.set(None);
                                            if entry == MENU_ENTRIES[0] {
                                                editing.set(Some(index));
                                            }
                                        },
                                    }
                                }
                            }
                        }
                    }
                }
            },
        }
    }
}

/// What one row's menu offers, in the order the entries are declared.
const MENU_ENTRIES: [&str; 4] = ["Edit", "Move up", "Move down", "Delete"];

/// The handler the given node reports its clicks through.
fn click_handler(batch: &[Mutation<'_>], node: u32) -> Option<u64> {
    batch.iter().find_map(|mutation| match mutation {
        Mutation::SetProp {
            node_id,
            property: PropertyKind::OnClick,
            value: PropertyValue::Integer(id),
        } if *node_id == node => Some(*id as u64),
        _ => None,
    })
}

/// The one node a property with the given name was created for.
fn only_node_of(batch: &[Mutation<'_>], widget: WidgetKind) -> Option<u32> {
    batch.iter().find_map(|mutation| match mutation {
        Mutation::Create {
            node_id,
            widget: got,
        } if *got == widget => Some(*node_id),
        _ => None,
    })
}

/// The nodes the given text was set on, in the order they appear.
fn nodes_with_text(batch: &[Mutation<'_>], text: &str) -> Vec<u32> {
    batch
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::Text,
                value: PropertyValue::String(value),
            } if *value == text => Some(*node_id),
            _ => None,
        })
        .collect()
}

/// Sends one event and hands back what came out of it.
fn send(host: &mut Host, event: HostEvent<'_>) -> Vec<u8> {
    let mut wire = Vec::new();
    encode_event(&event, &mut wire).unwrap();
    host.dispatch_event(&wire).unwrap().0.to_vec()
}

/// Fails with the chain it found if any node's parents lead back to the node itself.
fn assert_no_loop(parents: &HashMap<u32, u32>, what: &str) {
    let nodes: Vec<u32> = parents.keys().copied().collect();
    for node in nodes {
        assert!(
            loops_from(parents, node).is_none(),
            "{what}: the parent chain from {node} loops: {:?}",
            loops_from(parents, node),
        );
    }
}

#[test]
fn nfr7_a_menu_whose_entries_arrive_only_when_open_never_loops_the_parent_chain() {
    let mut host = Host::new(menu_of_lazy_entries_app);
    let mut parents = HashMap::new();
    let first = host.rebuild().unwrap().to_vec();
    follow(&first, &mut parents);
    let records = decode_batch(&first).unwrap();
    let list = only_node_of(&records, WidgetKind::LazyColumn).expect("the screen has a list");
    let range = records
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnRangeRequested,
                value: PropertyValue::Integer(id),
            } if *node_id == list => Some(*id as u64),
            _ => None,
        })
        .expect("the list asks for its range");

    // The window moves forward and back over the same items, so every item is built,
    // dropped and built again with its menu opened in between.
    for start in [0_u32, 4, 8, 4, 0] {
        let window = send(
            &mut host,
            HostEvent {
                node_id: list,
                handler_id: range,
                payload: EventPayload::RangeRequested { start, count: 6 },
            },
        );
        follow(&window, &mut parents);
        assert_no_loop(&parents, &format!("window at {start}"));
        let window = decode_batch(&window).unwrap();

        let anchors = nodes_with_text(&window, "actions");
        assert!(
            !anchors.is_empty(),
            "the window at {start} materialised no rows",
        );
        for anchor in anchors {
            let menu = parent_of(&window, anchor).expect("the anchor sits in its menu");
            let handler = click_handler(&window, anchor).expect("the anchor reports its clicks");
            let opened = send(
                &mut host,
                HostEvent {
                    node_id: anchor,
                    handler_id: handler,
                    payload: EventPayload::Clicked,
                },
            );
            follow(&opened, &mut parents);
            assert_no_loop(&parents, &format!("window at {start}, menu {menu} open"));
            let opened = decode_batch(&opened).unwrap();

            for (offset, entry) in MENU_ENTRIES.iter().enumerate() {
                let nodes = nodes_with_text(&opened, entry);
                assert_eq!(
                    1,
                    nodes.len(),
                    "opening menu {menu} built {entry} {} times",
                    nodes.len(),
                );
                assert_eq!(
                    Some(menu),
                    parent_of(&opened, nodes[0]),
                    "{entry} was attached outside the menu it belongs to",
                );
                assert_eq!(
                    Some(offset as u32 + 1),
                    index_of(&opened, nodes[0]),
                    "the window at {start}: {entry} went in at the wrong place under menu \
                     {menu}, where the anchor holds the first position",
                );
            }
        }
    }
}

/// The slot the given node was last attached at.
fn index_of(batch: &[Mutation<'_>], node: u32) -> Option<u32> {
    batch.iter().rev().find_map(|mutation| match mutation {
        Mutation::Insert { node_id, index, .. } | Mutation::Move { node_id, index, .. }
            if *node_id == node =>
        {
            Some(*index)
        }
        _ => None,
    })
}

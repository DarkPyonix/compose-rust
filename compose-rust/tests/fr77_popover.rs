//! `Popover`: a floating box of any content attached to an anchor, as it reaches the wire.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::HostEvent;
use compose_rust::schema::{
    EventPayload, POPOVER_ALIGN_SCHEMA, POPOVER_PLACEMENT_SCHEMA, PropertyKind, WidgetKind,
};
use compose_rust::ui::*;
use std::sync::atomic::{AtomicUsize, Ordering};
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

/// The tags are the wire. A number that moved would be read by a Renderer built from the
/// older schema as a different widget or a different side.
#[test]
fn fr77_popover_tags_are_appended_and_fixed() {
    assert_eq!(WidgetKind::Popover as u16, 47);
    assert_eq!(PropertyKind::Placement as u16, 175);
    assert_eq!(PropertyKind::Align as u16, 176);
    assert_eq!(PropertyKind::OffsetDp as u16, 177);
    assert_eq!(
        PropertyKind::from_name("placement"),
        Ok(PropertyKind::Placement)
    );
    assert_eq!(PropertyKind::from_name("align"), Ok(PropertyKind::Align));
    assert_eq!(
        PropertyKind::from_name("offset_dp"),
        Ok(PropertyKind::OffsetDp)
    );
    let placements: Vec<(&str, u16)> = POPOVER_PLACEMENT_SCHEMA
        .iter()
        .map(|variant| (variant.name, variant.tag))
        .collect();
    assert_eq!(
        placements,
        [("Top", 1), ("Bottom", 2), ("Start", 3), ("End", 4)]
    );
    let aligns: Vec<(&str, u16)> = POPOVER_ALIGN_SCHEMA
        .iter()
        .map(|variant| (variant.name, variant.tag))
        .collect();
    assert_eq!(aligns, [("Start", 1), ("Center", 2), ("End", 3)]);
}

/// The anchor is the first child whatever order the builder was called in, and the content
/// follows it.
#[test]
fn fr77_popover_writes_its_anchor_first_and_its_content_after() {
    let (_host, tree) = start(|| {
        Popover(true)
            .anchor(|| {
                Button("Model");
            })
            .content(|| {
                Text("Opus");
                Text("Sonnet");
            });
    });
    let dump = tree.dump();
    let lines: Vec<&str> = dump.lines().map(str::trim).collect();
    let popover = lines
        .iter()
        .position(|line| line.starts_with("Popover "))
        .unwrap_or_else(|| panic!("no Popover in\n{dump}"));
    assert!(lines[popover].contains("Open=Bool(true)"), "{dump}");
    assert!(lines[popover + 1].starts_with("Button "), "{dump}");
    assert!(lines[popover + 2].contains("Text=\"Opus\""), "{dump}");
    assert!(lines[popover + 3].contains("Text=\"Sonnet\""), "{dump}");
}

/// A popover that says nothing about where it goes sends nothing about it, which leaves
/// the side, the edge and the gap to the Renderer's defaults.
#[test]
fn fr77_popover_sends_no_placement_it_was_not_given() {
    let (_host, tree) = start(|| {
        Popover(false)
            .anchor(|| {
                Text("Filter");
            })
            .content(|| {
                Text("Unread");
            });
    });
    let dump = tree.dump();
    assert!(dump.contains("Popover "), "{dump}");
    assert!(dump.contains("Open=Bool(false)"), "{dump}");
    for absent in ["Placement=", "Align=", "OffsetDp=", "Scrim="] {
        assert!(!dump.contains(absent), "{absent} was sent unasked:\n{dump}");
    }
}

#[test]
fn fr77_popover_writes_placement_align_offset_and_scrim() {
    let (_host, tree) = start(|| {
        Popover(true)
            .placement(PopoverPlacement::Top)
            .align(PopoverAlign::End)
            .offset(6.0)
            .scrim(true)
            .anchor(|| {
                Text("More");
            })
            .content(|| {
                Text("Rename");
            });
    });
    let dump = tree.dump();
    assert!(dump.contains("Placement=Integer(1)"), "{dump}");
    assert!(dump.contains("Align=Integer(3)"), "{dump}");
    assert!(dump.contains("OffsetDp=Float(6.0)"), "{dump}");
    assert!(dump.contains("Scrim=Bool(true)"), "{dump}");
}

/// A dismissal reaches the application's handler once per event and changes nothing by
/// itself: `open` is the application's to change.
#[test]
fn fr77_popover_dismiss_calls_the_handler_and_leaves_open_alone() {
    static DISMISSED: AtomicUsize = AtomicUsize::new(0);
    let (mut host, mut tree) = start(|| {
        Popover(true)
            .on_dismiss(|| {
                DISMISSED.fetch_add(1, Ordering::SeqCst);
            })
            .anchor(|| {
                Text("Model");
            })
            .content(|| {
                Text("Opus");
            });
    });
    let (node_id, handler_id) = tree.handled("Popover", PropertyKind::OnDismiss)[0];
    let (batch, _) = host
        .dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        })
        .expect("the dismissal is accepted");
    let batch = batch.to_vec();
    tree.apply(&batch);
    assert_eq!(DISMISSED.load(Ordering::SeqCst), 1);
    assert!(tree.dump().contains("Open=Bool(true)"), "{}", tree.dump());
}

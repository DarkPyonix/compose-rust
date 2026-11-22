//! A node can say what it is and what it is called to assistive technology.

mod support;

use compose_rust::ComposeHost;
use compose_rust::runtime::*;
use compose_rust::ui::*;
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use support::Tree;

struct TrackingAllocator;

thread_local! {
    static TRACKING: Cell<bool> = const { Cell::new(false) };
    static ALLOCATIONS: Cell<usize> = const { Cell::new(0) };
    static LIVE_BYTES: Cell<isize> = const { Cell::new(0) };
}

unsafe impl GlobalAlloc for TrackingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let _ = TRACKING.try_with(|tracking| {
            if tracking.get() {
                let _ = ALLOCATIONS.try_with(|c| c.set(c.get() + 1));
                let _ = LIVE_BYTES.try_with(|b| b.set(b.get() + layout.size() as isize));
            }
        });
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        let _ = TRACKING.try_with(|tracking| {
            if tracking.get() {
                let _ = LIVE_BYTES.try_with(|b| b.set(b.get() - layout.size() as isize));
            }
        });
        unsafe { System.dealloc(ptr, layout) }
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        let _ = TRACKING.try_with(|tracking| {
            if tracking.get() {
                let _ = ALLOCATIONS.try_with(|c| c.set(c.get() + 1));
                let _ = LIVE_BYTES
                    .try_with(|b| b.set(b.get() + size as isize - layout.size() as isize));
            }
        });
        unsafe { System.realloc(ptr, layout, size) }
    }
}

#[global_allocator]
static GLOBAL: TrackingAllocator = TrackingAllocator;

fn count_allocations(work: impl FnOnce()) -> usize {
    ALLOCATIONS.with(|count| count.set(0));
    TRACKING.with(|tracking| tracking.set(true));
    work();
    TRACKING.with(|tracking| tracking.set(false));
    ALLOCATIONS.with(|count| count.get())
}

fn start(content: impl Fn() + Send + Sync + 'static) -> Tree {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    tree
}

#[Composable]
fn Plain() {
    Column!({
        Box!(modifier = Modifier.padding(4.0), {
            Text!("plain");
        });
        Button!("Save", || {});
        Switch!(true, |_| {});
    });
}

#[Composable]
fn Named() {
    Column!({
        Box!(
            modifier = Modifier.label("Refresh").role(Role::Button),
            on_click = Some(|| {}),
            {
                Text!("↻");
            }
        );
        Button!("", || {}, icon = IconRole::Add, accessible_name = "Import");
        Button!(
            "Save",
            || {},
            modifier = Modifier.label("From the modifier"),
            accessible_name = "Save the draft"
        );
        Switch!(true, |_| {}, accessible_name = "Notifications");
        Checkbox!(false, |_| {}, accessible_name = "Remember me");
        RadioButton!(true, |_| {}, accessible_name = "Small");
        Slider!(0.5, |_| {}, accessible_name = "Volume");
    });
}

#[Composable]
fn Stated() {
    Column!({
        Box!(
            modifier = Modifier.semantics(Role::Tab, "Files", SemanticsState::selected(true)),
            {}
        );
        Box!(modifier = Modifier.checked(false), {});
        Box!(
            modifier = Modifier.role(Role::Button).label("More").has_popup(true),
            {}
        );
    });
}

#[test]
fn fr68_a_node_that_asks_for_no_semantics_sends_none() {
    let dump = start(Plain).dump();
    assert!(!dump.contains("AccessibleName"), "{dump}");
    assert!(!dump.contains("Semantics"), "{dump}");
}

#[test]
fn fr68_the_modifier_sends_the_name_and_the_role() {
    let dump = start(Named).dump();
    assert!(dump.contains("AccessibleName=\"Refresh\""), "{dump}");
    assert!(
        dump.contains("Semantics { role: Button, state: 0 }"),
        "{dump}"
    );
}

#[test]
fn fr68_the_modifier_sends_the_state_and_an_unset_role_stays_none() {
    let dump = start(Stated).dump();
    // Selected on is 2 in the selected field, which starts two bits up.
    assert!(dump.contains("Semantics { role: Tab, state: 8 }"), "{dump}");
    assert!(dump.contains("AccessibleName=\"Files\""), "{dump}");
    // Checked off is 1 in the checked field, with no role given.
    assert!(
        dump.contains("Semantics { role: None, state: 1 }"),
        "{dump}"
    );
    // Has-popup is bit 13 (8192).
    assert!(
        dump.contains("Semantics { role: Button, state: 8192 }"),
        "{dump}"
    );
    assert!(dump.contains("AccessibleName=\"More\""), "{dump}");
}

#[test]
fn fr68_accessible_name_reaches_every_control_that_has_one() {
    let dump = start(Named).dump();
    for (widget, name) in [
        ("Button", "Import"),
        ("Switch", "Notifications"),
        ("Checkbox", "Remember me"),
        ("RadioButton", "Small"),
        ("Slider", "Volume"),
    ] {
        let line = dump
            .lines()
            .find(|line| line.trim_start().starts_with(widget))
            .unwrap_or_else(|| panic!("no {widget} in\n{dump}"));
        assert!(
            line.contains(&format!("AccessibleName=\"{name}\"")),
            "{widget}: {line}"
        );
    }
}

#[test]
fn fr68_the_widget_name_wins_over_the_modifier_label_and_the_icon() {
    let dump = start(Named).dump();
    assert!(dump.contains("AccessibleName=\"Save the draft\""), "{dump}");
    assert!(!dump.contains("From the modifier"), "{dump}");
}

#[Composable]
fn Renaming() {
    let named = remember(|| mutable_state_of(true));
    Column!({
        let flip = named;
        Row!(on_click = Some(move || flip.update(|n| *n = !*n)), {
            Text!("flip");
        });
        Button!(
            "",
            || {},
            icon = IconRole::Add,
            accessible_name = named.get().then_some("Import"),
        );
    });
}

#[test]
fn fr68_a_widget_that_stops_naming_itself_sends_a_removal() {
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
    use compose_rust::schema::{EventPayload, PropertyKind};

    let mut host = ComposeHost::with_content(Renaming);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    assert!(tree.dump().contains("AccessibleName=\"Import\""));

    let (node_id, handler_id) = tree.handled("Row", PropertyKind::OnClick)[0];
    let mut batches = Vec::new();
    let (batch, _) = host
        .dispatch(HostEvent::new(node_id, handler_id, EventPayload::Clicked))
        .expect("the click is accepted");
    batches.push(batch.to_vec());
    batches.push(host.render_frame(0).expect("the frame encodes").to_vec());

    let mut removed = 0;
    for batch in &batches {
        for mutation in decode_batch(batch).expect("the batch decodes") {
            if let Mutation::SetProp {
                property: PropertyKind::AccessibleName,
                value,
                ..
            } = mutation
            {
                assert_eq!(value, PropertyValue::None);
                removed += 1;
            }
        }
        tree.apply(batch);
    }
    assert_eq!(removed, 1, "one removal of the name was expected");
    assert!(!tree.dump().contains("AccessibleName"), "{}", tree.dump());
}

#[Composable]
fn Described() {
    let described = remember(|| mutable_state_of(true));
    Column!({
        let flip = described;
        Row!(on_click = Some(move || flip.update(|d| *d = !*d)), {
            Text!("flip");
        });
        let modifier = if described.get() {
            Modifier.semantics(Role::Heading, "Title", SemanticsState::expanded(true))
        } else {
            Modifier
        };
        Box!(modifier = modifier, {});
    });
}

#[test]
fn fr71_removing_semantics_restores_the_defaults() {
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
    use compose_rust::schema::{EventPayload, Modifier as Wire, PropertyKind};

    let mut host = ComposeHost::with_content(Described);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    assert!(
        tree.dump().contains("Semantics { role: Heading"),
        "{}",
        tree.dump()
    );

    let (node_id, handler_id) = tree.handled("Row", PropertyKind::OnClick)[0];
    let (batch, _) = host
        .dispatch(HostEvent::new(node_id, handler_id, EventPayload::Clicked))
        .expect("the click is accepted");
    let batches = [
        batch.to_vec(),
        host.render_frame(0).expect("the frame encodes").to_vec(),
    ];

    let (mut name_removed, mut modifier_cleared) = (0, 0);
    for batch in &batches {
        for mutation in decode_batch(batch).expect("the batch decodes") {
            match mutation {
                Mutation::SetProp {
                    property: PropertyKind::AccessibleName,
                    value,
                    ..
                } => {
                    assert_eq!(value, PropertyValue::None);
                    name_removed += 1;
                }
                Mutation::SetModifier {
                    modifier: Wire::Empty,
                    ..
                } => modifier_cleared += 1,
                _ => {}
            }
        }
        tree.apply(batch);
    }
    assert_eq!(name_removed, 1, "one removal of the name was expected");
    assert!(modifier_cleared >= 1, "the semantics slot was not cleared");
    assert!(!tree.dump().contains("Semantics"), "{}", tree.dump());
    assert!(!tree.dump().contains("AccessibleName"), "{}", tree.dump());
}

#[Composable]
fn EveryNewRole() {
    Column!({
        for role in [
            Role::TabList,
            Role::Tree,
            Role::TreeItem,
            Role::Menu,
            Role::MenuItem,
            Role::TextBox,
            Role::Heading,
            Role::Navigation,
            Role::List,
            Role::ListItem,
            Role::Group,
        ] {
            Box!(
                modifier = Modifier.role(role).disabled(false).click_action(true),
                {}
            );
        }
    });
}

#[test]
fn fr71_every_new_role_round_trips_and_is_exposed() {
    let dump = start(EveryNewRole).dump();
    for role in [
        "TabList",
        "Tree",
        "TreeItem",
        "Menu",
        "MenuItem",
        "TextBox",
        "Heading",
        "Navigation",
        "List",
        "ListItem",
        "Group",
    ] {
        assert!(
            dump.contains(&format!("Semantics {{ role: {role}, state: ")),
            "{role} missing in\n{dump}"
        );
    }
}

#[Composable]
fn DynamicLabelBox(name: MutableState<String>) {
    let current = name.get();
    Box!(modifier = Modifier.label(&current), {});
}

#[test]
fn fr68_dynamic_labels_do_not_leak_across_frames() {
    let name = mutable_state_of("initial".to_string());
    let state = name;
    let mut host = ComposeHost::with_content(move || DynamicLabelBox(state));
    host.rebuild().expect("the initial build encodes");

    for frame in 0..200u64 {
        name.set(format!("warm {frame}"));
        host.render_frame(frame).unwrap();
    }

    TRACKING.with(|t| t.set(true));
    let warm_live = LIVE_BYTES.with(|b| b.get());

    for frame in 200..100_200u64 {
        name.set(format!("frame {frame}"));
        host.render_frame(frame).unwrap();
    }

    let end_live = LIVE_BYTES.with(|b| b.get());
    TRACKING.with(|t| t.set(false));

    let growth = end_live - warm_live;
    assert!(
        growth < 64 * 1024,
        "live memory grew by {growth} bytes over 100,000 dynamic labels"
    );
}

fn label_frame_allocations(with_labels: bool) -> usize {
    let count = mutable_state_of(0);
    let state = count;
    let mut host = ComposeHost::with_content(move || {
        let _ = state.get();
        Column!({
            if with_labels {
                Box!(modifier = Modifier.padding(4.0).label("Refresh"), {});
                Box!(modifier = Modifier.padding(4.0).label("Save"), {});
            } else {
                Box!(modifier = Modifier.padding(4.0), {});
                Box!(modifier = Modifier.padding(4.0), {});
            }
        });
    });
    host.rebuild().unwrap();

    for frame in 1..10u64 {
        count.set(frame as i32);
        host.render_frame(frame).unwrap();
    }

    count_allocations(|| {
        for frame in 10..50u64 {
            count.set(frame as i32);
            host.render_frame(frame).unwrap();
        }
    })
}

#[test]
fn fr68_repeated_labels_do_not_allocate_in_steady_state() {
    let without_labels = label_frame_allocations(false);
    let with_labels = label_frame_allocations(true);
    assert_eq!(
        with_labels, without_labels,
        "repeated labels allocated {with_labels} where without took {without_labels}"
    );
}

#[Composable]
fn Mixed() {
    Column!({
        Box!(
            modifier = Modifier.semantics(Role::Checkbox, "Staged", SemanticsState::mixed()),
            {}
        );
        // Mixed wins over a checked value given beside it.
        Box!(
            modifier = Modifier.semantics(
                Role::Checkbox,
                "Partly staged",
                SemanticsState::mixed().with_checked(true),
            ),
            {}
        );
    });
}

/// The checked field's fourth value, 3, is mixed: `SemanticsState::mixed()` writes it,
/// and it comes back from the encoded batch as written, inside the mask.
#[test]
fn fr68_semantics_checked_mixed_round_trips() {
    use compose_rust::protocol::{Mutation, decode_batch};
    use compose_rust::schema::{
        Modifier as Wire, SEMANTICS_CHECKED_MIXED, SEMANTICS_CHECKED_SHIFT, SEMANTICS_STATE_MASK,
        SemanticsRole,
    };

    assert_eq!(SEMANTICS_CHECKED_MIXED, 3);
    let mut host = ComposeHost::with_content(Mixed);
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let states: Vec<u32> = decode_batch(&batch)
        .expect("the batch decodes")
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                modifier:
                    Wire::Semantics {
                        role: SemanticsRole::Checkbox,
                        state,
                    },
                ..
            } => Some(state),
            _ => None,
        })
        .collect();
    let mixed = SEMANTICS_CHECKED_MIXED << SEMANTICS_CHECKED_SHIFT;
    assert_eq!(states, [mixed, mixed]);
    assert_eq!(mixed & !SEMANTICS_STATE_MASK, 0);
}

/// Code outside the crate cannot write a `SemanticsState` literal, so every public field
/// has a `with_` method, and a chain of them from the default sets exactly those fields.
#[test]
fn fr68_semantics_state_chains_set_every_field() {
    let unset = SemanticsState::default();
    assert_eq!(unset.with_checked(true).checked, Some(true));
    assert_eq!(unset.with_checked(false).checked, Some(false));
    assert!(unset.with_mixed(true).mixed);
    assert_eq!(unset.with_selected(true).selected, Some(true));
    assert_eq!(unset.with_expanded(false).expanded, Some(false));
    assert_eq!(unset.with_disabled(true).disabled, Some(true));
    assert_eq!(unset.with_focused(true).focused, Some(true));
    assert!(unset.with_hidden(true).hidden);
    assert!(unset.with_click_action(true).click_action);
    assert!(unset.with_focus_action(true).focus_action);
    assert!(unset.with_has_popup(true).has_popup);

    // Each one starts where the constructor of the same name does.
    assert_eq!(unset.with_checked(true), SemanticsState::checked(true));
    assert_eq!(unset.with_mixed(true), SemanticsState::mixed());
    assert_eq!(unset.with_selected(true), SemanticsState::selected(true));
    assert_eq!(unset.with_expanded(true), SemanticsState::expanded(true));
    assert_eq!(unset.with_disabled(true), SemanticsState::disabled(true));
    assert_eq!(unset.with_focused(true), SemanticsState::focused(true));
    assert_eq!(unset.with_hidden(true), SemanticsState::hidden(true));
    assert_eq!(
        unset.with_click_action(true),
        SemanticsState::click_action(true)
    );
    assert_eq!(
        unset.with_focus_action(true),
        SemanticsState::focus_action(true)
    );
    assert_eq!(unset.with_has_popup(true), SemanticsState::has_popup(true));

    // A link keeps what the links before it set, and a flag can be taken away again.
    let chained = SemanticsState::mixed()
        .with_checked(true)
        .with_selected(false)
        .with_has_popup(true)
        .with_hidden(true)
        .with_hidden(false);
    assert!(chained.mixed);
    assert_eq!(chained.checked, Some(true));
    assert_eq!(chained.selected, Some(false));
    assert!(chained.has_popup);
    assert!(!chained.hidden);
    assert_eq!(chained.expanded, None);
    assert_eq!(unset.with_mixed(true).with_mixed(false), unset);
}

/// An attribute cannot be seen at run time, so this reads the declaration as text: without
/// `#[non_exhaustive]` on it, a field added later breaks every literal outside the crate.
#[test]
fn fr68_semantics_state_is_non_exhaustive() {
    let source = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/ui/mod.rs"));
    let lines: Vec<&str> = source.lines().collect();
    let at = lines
        .iter()
        .position(|line| line.starts_with("pub struct SemanticsState "))
        .expect("src/ui/mod.rs declares `pub struct SemanticsState`");
    let marked = lines[..at]
        .iter()
        .rev()
        .map(|line| line.trim())
        .take_while(|line| line.starts_with("#[") || line.starts_with("///"))
        .any(|line| line == "#[non_exhaustive]");
    assert!(
        marked,
        "`pub struct SemanticsState` has no `#[non_exhaustive]` among the attributes above it"
    );
}

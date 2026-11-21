//! How many things a destination holds, said on the destination.
//!
//! A `NavigationItem` takes the badge's `Count` property. Left out, the destination sends
//! no record at all and reaches the Renderer as it did before it could carry a count; 0 is
//! a count of nothing and is sent. Changing one destination's count writes that one
//! destination and nothing else.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{PropertyKind, WidgetKind};
use compose_rust::ui::*;

/// What one node was given for `Count`, in order: `Some(value)` for a count, `None` for a
/// cleared one.
fn counts(mutations: &[Mutation<'_>], node: u32) -> Vec<Option<i64>> {
    mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::Count,
                value,
            } if *node_id == node => match value {
                PropertyValue::Integer(value) => Some(Some(*value)),
                PropertyValue::None => Some(None),
                other => panic!("a count is an integer or nothing, not {other:?}"),
            },
            _ => None,
        })
        .collect()
}

/// The destinations `mutations` creates, in order.
fn destinations(mutations: &[Mutation<'_>]) -> Vec<u32> {
    mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::NavigationItem,
            } => Some(*node_id),
            _ => None,
        })
        .collect()
}

/// Inbox counts what the state says, Today counts nothing, and Archive names no count.
#[Composable]
fn Destinations(inbox: MutableState<Option<u32>>) {
    Navigation!(0, {
        NavigationItem!("Inbox", || {}, icon = IconRole::List, count = inbox.get());
        NavigationItem!("Today", || {}, icon = IconRole::Check, count = 0);
        NavigationItem!("Archive", || {}, icon = IconRole::Library);
    });
}

/// The macro's `count` is the badge's property 80 on the destination's own node: a count
/// of four is a record of 4, a count of nothing a record of 0, and a destination that
/// named no count sends nothing.
#[test]
fn fr21_2_4_navigation_item_count_writes_the_count_property() {
    assert_eq!(PropertyKind::Count as u16, 80);
    let inbox = mutable_state_of(Some(4u32));
    let state = inbox;
    let mut host = ComposeHost::with_content(move || Destinations(state));
    let first = host.rebuild().expect("the first frame encodes").to_vec();
    let first = decode_batch(&first).expect("the first frame decodes");
    let items = destinations(&first);
    assert_eq!(items.len(), 3, "three declarations, three destinations");
    assert_eq!(
        counts(&first, items[0]),
        vec![Some(4)],
        "Inbox did not send its count"
    );
    assert_eq!(
        counts(&first, items[1]),
        vec![Some(0)],
        "a count of nothing is still a count"
    );
    assert_eq!(
        counts(&first, items[2]),
        Vec::<Option<i64>>::new(),
        "Archive named no count"
    );
}

/// A changed count is one record on that destination, and the destinations beside it are
/// not written at all. Taking the count away clears the property rather than sending 0.
#[test]
fn fr21_2_4_a_changed_count_writes_only_its_destination() {
    let inbox = mutable_state_of(Some(4u32));
    let state = inbox;
    let mut host = ComposeHost::with_content(move || Destinations(state));
    let first = host.rebuild().expect("the first frame encodes").to_vec();
    let items = destinations(&decode_batch(&first).expect("the first frame decodes"));

    inbox.set(Some(5));
    let changed = host
        .render_frame(1)
        .expect("the second frame encodes")
        .to_vec();
    let changed = decode_batch(&changed).expect("the second frame decodes");
    assert_eq!(counts(&changed, items[0]), vec![Some(5)]);
    let elsewhere: Vec<&Mutation<'_>> = changed
        .iter()
        .filter(|mutation| match mutation {
            Mutation::SetProp { node_id, .. } | Mutation::SetModifier { node_id, .. } => {
                *node_id != items[0]
            }
            Mutation::Create { .. } | Mutation::Insert { .. } | Mutation::Remove { .. } => true,
            _ => false,
        })
        .collect();
    assert!(
        elsewhere.is_empty(),
        "a count change wrote more than its own destination: {elsewhere:?}"
    );

    inbox.set(None);
    let cleared = host
        .render_frame(2)
        .expect("the third frame encodes")
        .to_vec();
    assert_eq!(
        counts(
            &decode_batch(&cleared).expect("the third frame decodes"),
            items[0]
        ),
        vec![None],
        "a count that goes away is removed, not turned into a count of nothing"
    );
}

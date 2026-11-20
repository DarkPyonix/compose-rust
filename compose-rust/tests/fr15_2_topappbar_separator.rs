use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{PropertyKind, WidgetKind};
use compose_rust::ui::*;

#[Composable]
fn BarNoHairline() {
    TopAppBar!(hairline = false, {});
}

#[Composable]
fn BarNoSeparator() {
    TopAppBar!(separator = false, {});
}

#[Composable]
fn BarWithHairline() {
    TopAppBar!(hairline = true, {});
}

#[Composable]
fn BarDefault() {
    TopAppBar!({});
}

#[test]
fn fr15_2_topappbar_hairline_travels_on_wire() {
    assert_eq!(PropertyKind::Separator as u16, 208);
    assert_eq!(
        PropertyKind::from_name("separator"),
        Ok(PropertyKind::Separator)
    );

    // When hairline is false
    let mut host = ComposeHost::with_content(BarNoHairline);
    let batch = host.rebuild().expect("encodes").to_vec();
    let mutations = decode_batch(&batch).expect("decodes");
    let bar_node = mutations
        .iter()
        .find_map(|m| match m {
            Mutation::Create {
                node_id,
                widget: WidgetKind::TopAppBar,
            } => Some(*node_id),
            _ => None,
        })
        .expect("TopAppBar node created");
    let has_prop_false = mutations.iter().any(|m| {
        matches!(
            m,
            Mutation::SetProp {
                node_id,
                property: PropertyKind::Separator,
                value: PropertyValue::Bool(false),
            } if *node_id == bar_node
        )
    });
    assert!(
        has_prop_false,
        "expected Separator = false on bar node, got {mutations:?}"
    );

    // When separator is false
    let mut host = ComposeHost::with_content(BarNoSeparator);
    let batch = host.rebuild().expect("encodes").to_vec();
    let mutations = decode_batch(&batch).expect("decodes");
    let bar_node = mutations
        .iter()
        .find_map(|m| match m {
            Mutation::Create {
                node_id,
                widget: WidgetKind::TopAppBar,
            } => Some(*node_id),
            _ => None,
        })
        .expect("TopAppBar node created");
    let has_prop_false = mutations.iter().any(|m| {
        matches!(
            m,
            Mutation::SetProp {
                node_id,
                property: PropertyKind::Separator,
                value: PropertyValue::Bool(false),
            } if *node_id == bar_node
        )
    });
    assert!(
        has_prop_false,
        "expected Separator = false on bar node, got {mutations:?}"
    );

    // When hairline is true
    let mut host = ComposeHost::with_content(BarWithHairline);
    let batch = host.rebuild().expect("encodes").to_vec();
    let mutations = decode_batch(&batch).expect("decodes");
    let bar_node = mutations
        .iter()
        .find_map(|m| match m {
            Mutation::Create {
                node_id,
                widget: WidgetKind::TopAppBar,
            } => Some(*node_id),
            _ => None,
        })
        .expect("TopAppBar node created");
    let has_prop_true = mutations.iter().any(|m| {
        matches!(
            m,
            Mutation::SetProp {
                node_id,
                property: PropertyKind::Separator,
                value: PropertyValue::Bool(true),
            } if *node_id == bar_node
        )
    });
    assert!(
        has_prop_true,
        "expected Separator = true on bar node, got {mutations:?}"
    );

    // When default (unset)
    let mut host = ComposeHost::with_content(BarDefault);
    let batch = host.rebuild().expect("encodes").to_vec();
    let mutations = decode_batch(&batch).expect("decodes");
    let has_separator = mutations.iter().any(|m| {
        matches!(
            m,
            Mutation::SetProp {
                property: PropertyKind::Separator,
                ..
            }
        )
    });
    assert!(
        !has_separator,
        "expected no Separator prop on default bar, got {mutations:?}"
    );
}

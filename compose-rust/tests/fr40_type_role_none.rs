//! `TypeRole::None` on the wire: text that says it has no role sends the role tag 0, so
//! the Renderer sets it in the fonts it names, and text that names no role sends nothing.
//!
//! The tag of `TypeRole::None` is 0, which is also what "no role was set" used to travel
//! as, and a design property at 0 is not sent. Text could therefore not say it had no
//! role: a `Text` with `.type_role(TypeRole::None).font(..)` reached the Renderer as text
//! in the default role, whose fonts are ignored and reported as ignored. The minimal
//! sample's serif letters did exactly that, and the web page check failed on the report.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{PropertyKind, WidgetKind};
use compose_rust::ui::*;
use compose_rust::{FontRef, FontRefs, GenericFamily};

/// What one node was given for `property`, in order: `Some(value)` for an integer, `None`
/// for a cleared property.
fn writes(mutations: &[Mutation<'_>], node: u32, property: PropertyKind) -> Vec<Option<i64>> {
    mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: kind,
                value,
            } if *node_id == node && *kind == property => match value {
                PropertyValue::Integer(value) => Some(Some(*value)),
                PropertyValue::None => Some(None),
                other => panic!("{property:?} is an integer or nothing, not {other:?}"),
            },
            _ => None,
        })
        .collect()
}

/// The nodes `mutations` creates as `widget`, in order.
fn created(mutations: &[Mutation<'_>], widget: WidgetKind) -> Vec<u32> {
    mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: kind,
            } if *kind == widget => Some(*node_id),
            _ => None,
        })
        .collect()
}

fn has_font(mutations: &[Mutation<'_>], node: u32) -> bool {
    mutations.iter().any(|mutation| {
        matches!(
            mutation,
            Mutation::SetProp {
                node_id,
                property: PropertyKind::Font,
                value: PropertyValue::Bytes(_),
            } if *node_id == node
        )
    })
}

fn serif() -> FontRefs {
    FontRefs::new([FontRef::Generic(GenericFamily::Serif)])
}

#[Composable]
fn Texts() {
    Column().content(|| {
        Text("A")
            .type_role(TypeRole::None)
            .font(serif())
            .font_size(14.0);
        Text("plain");
        Text("body").type_role(TypeRole::Body);
        TextField()
            .placeholder("own fonts")
            .type_role(TypeRole::None)
            .font(serif());
        TextField().placeholder("plain");
    });
}

#[test]
fn fr40_type_role_none_is_sent_and_an_unset_role_is_not() {
    let mut host = ComposeHost::with_content(Texts);
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the batch decodes");
    let texts = created(&mutations, WidgetKind::Text);
    assert_eq!(texts.len(), 3, "three texts are created");
    assert_eq!(
        writes(&mutations, texts[0], PropertyKind::TypeRole),
        vec![Some(0)],
        "text with no role says so, once, with the tag of TypeRole::None"
    );
    assert!(
        has_font(&mutations, texts[0]),
        "the text with no role sends the fonts it is set in"
    );
    assert!(
        writes(&mutations, texts[1], PropertyKind::TypeRole).is_empty(),
        "text that names no role sends no role, and takes the Renderer's default"
    );
    assert_eq!(
        writes(&mutations, texts[2], PropertyKind::TypeRole),
        vec![Some(i64::from(TypeRole::Body as u16))],
        "a role is sent as its tag"
    );

    let fields = created(&mutations, WidgetKind::TextField);
    assert_eq!(fields.len(), 2, "two fields are created");
    assert_eq!(
        writes(&mutations, fields[0], PropertyKind::TypeRole),
        vec![Some(0)],
        "a field with no role says so, as a text does"
    );
    assert!(
        writes(&mutations, fields[1], PropertyKind::TypeRole).is_empty(),
        "a field that names no role sends none"
    );
}

/// 0 is no role set, 1 is `TypeRole::None`, 2 is `TypeRole::Body`.
#[Composable]
fn Switching(role: MutableState<u8>) {
    Column().content(|| {
        let text = Text("A").font(serif());
        match role.get() {
            1 => text.type_role(TypeRole::None),
            2 => text.type_role(TypeRole::Body),
            _ => text,
        };
    });
}

/// Moving between a role, no role and an unset role sends one record each time, and
/// clearing a role drops the property instead of turning the text into text with no role.
#[test]
fn fr40_type_role_none_and_an_unset_role_stay_apart_across_frames() {
    let role = mutable_state_of(2u8);
    let state = role;
    let mut host = ComposeHost::with_content(move || Switching(state));
    let first = host.rebuild().unwrap().to_vec();
    let first = decode_batch(&first).unwrap();
    let node = created(&first, WidgetKind::Text)[0];
    assert_eq!(
        writes(&first, node, PropertyKind::TypeRole),
        vec![Some(i64::from(TypeRole::Body as u16))]
    );

    role.set(0);
    let cleared = host.render_frame(1).unwrap().to_vec();
    assert_eq!(
        writes(
            &decode_batch(&cleared).unwrap(),
            node,
            PropertyKind::TypeRole
        ),
        vec![None],
        "a role that goes away is removed, so the text takes the default role again"
    );

    role.set(1);
    let none = host.render_frame(2).unwrap().to_vec();
    assert_eq!(
        writes(&decode_batch(&none).unwrap(), node, PropertyKind::TypeRole),
        vec![Some(0)],
        "TypeRole::None is sent after an unset role"
    );

    role.set(0);
    let unset = host.render_frame(3).unwrap().to_vec();
    assert_eq!(
        writes(&decode_batch(&unset).unwrap(), node, PropertyKind::TypeRole),
        vec![None],
        "and leaving it again drops it"
    );
}

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{DesignSystem, PropertyKind, Theme};
use compose_rust::ui::*;

#[Composable]
fn ReadOnlyField() {
    TextField!("https://example.invalid/invite", |_| {}, read_only = true);
}

#[test]
fn fr5_textfield_read_only_emits_property_215() {
    assert_eq!(PropertyKind::ReadOnly as u16, 215);
    assert_eq!(PropertyKind::try_from(215), Ok(PropertyKind::ReadOnly));

    let mut host = ComposeHost::with_theme(ReadOnlyField, Theme::unified(DesignSystem::Material3));
    let batch = host.rebuild().expect("the first frame encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the first frame decodes");
    let found = mutations.iter().any(|m| {
        matches!(
            m,
            Mutation::SetProp {
                property: PropertyKind::ReadOnly,
                value: PropertyValue::Bool(true),
                ..
            }
        )
    });
    assert!(found, "expected PropertyKind::ReadOnly = true in batch");
}

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{DesignSystem, Modifier as ProtocolModifier, Theme};
use compose_rust::ui::*;

#[Composable]
fn PaddingBoxes() {
    Box!(
        modifier = Modifier::new().padding_each(16.0, 20.0, 12.0, 8.0),
        {}
    );
    Box!(modifier = Modifier::new().padding_xy(16.0, 20.0), {});
}

#[test]
fn padding_each_and_padding_xy_emit_padding_each_modifier() {
    let mut host = ComposeHost::with_theme(PaddingBoxes, Theme::unified(DesignSystem::Material3));
    let batch = host.rebuild().expect("the first frame encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the first frame decodes");
    let padding_each: Vec<_> = mutations
        .iter()
        .filter_map(|m| match m {
            Mutation::SetModifier {
                modifier:
                    ProtocolModifier::PaddingEach {
                        start,
                        top,
                        end,
                        bottom,
                    },
                ..
            } => Some((*start, *top, *end, *bottom)),
            _ => None,
        })
        .collect();
    assert_eq!(
        padding_each,
        vec![(16.0, 20.0, 12.0, 8.0), (16.0, 20.0, 16.0, 20.0)]
    );
}

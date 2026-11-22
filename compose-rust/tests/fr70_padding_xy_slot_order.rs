use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{Color, DesignSystem, Modifier as ProtocolModifier, Paint, Theme};
use compose_rust::ui::*;

#[Composable]
fn BorderedPaddedCard() {
    Box!(
        modifier = Modifier::new()
            .border(1.0, Paint::Literal(Color::argb(0xFFCC_CCCC)))
            .padding_xy(20.0, 16.0),
        {}
    );
}

#[test]
fn fr70_padding_xy_slot_order() {
    let mut host =
        ComposeHost::with_theme(BorderedPaddedCard, Theme::unified(DesignSystem::Material3));
    let batch = host.rebuild().expect("the first frame encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the first frame decodes");

    let mut border_slot = None;
    let mut padding_slot = None;
    for mutation in &mutations {
        if let Mutation::SetModifier {
            index, modifier, ..
        } = mutation
        {
            match modifier {
                ProtocolModifier::Border { width, .. } if *width == 1.0 => {
                    border_slot = Some(*index);
                }
                ProtocolModifier::PaddingEach {
                    start,
                    top,
                    end,
                    bottom,
                } if (*start, *top, *end, *bottom) == (20.0, 16.0, 20.0, 16.0) => {
                    padding_slot = Some(*index);
                }
                _ => {}
            }
        }
    }

    assert_eq!(border_slot, Some(19));
    assert_eq!(padding_slot, Some(22));
    assert!(border_slot.unwrap() < padding_slot.unwrap());
}

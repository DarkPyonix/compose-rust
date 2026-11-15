//! Physical text alignment on the wire: `Left` and `Right` travel as tags 5 and 6, after
//! the writing-direction alignments, so the Renderer can set them on the edge they name.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::PropertyKind;
use compose_rust::ui::*;

#[Composable]
fn Aligned() {
    Column().content(|| {
        Text("left").text_align(TextAlign::Left);
        Text("right").text_align(TextAlign::Right);
        Text("start").text_align(TextAlign::Start);
    });
}

#[test]
fn fr13_physical_text_align_is_sent_as_its_tag() {
    assert_eq!(TextAlign::Left as u16, 5);
    assert_eq!(TextAlign::Right as u16, 6);
    let mut host = ComposeHost::with_content(Aligned);
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let aligns: Vec<i64> = decode_batch(&batch)
        .expect("the batch decodes")
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                property: PropertyKind::TextAlign,
                value: PropertyValue::Integer(tag),
                ..
            } => Some(*tag),
            _ => None,
        })
        .collect();
    assert_eq!(aligns, vec![5, 6, 1]);
}

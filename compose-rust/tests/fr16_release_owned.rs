//! Bytes registered at run time can be let go of: the Renderer is told once, and the same
//! bytes registered again are a new registration with a new id.

use compose_rust::ComposeHost;
use compose_rust::asset::{register_owned, release_owned};
use compose_rust::protocol::{Mutation, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::AssetKind;
use compose_rust::ui::*;

#[Composable]
fn Empty() {
    Row!({});
}

/// The asset records of a batch: (registered id, bytes) and released ids.
fn asset_records(batch: &[u8]) -> (Vec<(u32, Vec<u8>)>, Vec<u32>) {
    let mut registered = Vec::new();
    let mut released = Vec::new();
    for mutation in decode_batch(batch).expect("the batch decodes") {
        match mutation {
            Mutation::RegisterAsset {
                asset_id,
                kind: AssetKind::Svg,
                bytes,
            } => registered.push((asset_id, bytes.to_vec())),
            Mutation::ReleaseAsset { asset_id } => released.push(asset_id),
            _ => {}
        }
    }
    (registered, released)
}

fn picture() -> Vec<u8> {
    b"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 4 4'>\
<rect width='4' height='4' fill='#008000'/></svg>"
        .to_vec()
}

#[test]
fn fr16_4_released_owned_bytes_are_registered_again_under_a_new_id() {
    let mut host = ComposeHost::with_content(Empty);
    let first_id = register_owned(AssetKind::Svg, picture());
    let (registered, released) = asset_records(host.rebuild().expect("the first batch"));
    assert_eq!(registered, vec![(first_id, picture())]);
    assert!(released.is_empty(), "{released:?}");

    assert!(release_owned(first_id, AssetKind::Svg));
    let (registered, released) = asset_records(host.render_frame(1).expect("a frame"));
    assert!(registered.is_empty(), "{registered:?}");
    assert_eq!(
        released,
        vec![first_id],
        "one release record for one release"
    );

    assert!(
        !release_owned(first_id, AssetKind::Svg),
        "a second release of the same id releases nothing"
    );
    let (_, released) = asset_records(host.render_frame(2).expect("a frame"));
    assert!(
        released.is_empty(),
        "a refused release sent a record: {released:?}"
    );

    let second_id = register_owned(AssetKind::Svg, picture());
    assert_ne!(second_id, first_id, "a released id is handed out again");
    let (registered, _) = asset_records(host.render_frame(3).expect("a frame"));
    assert_eq!(
        registered,
        vec![(second_id, picture())],
        "the same bytes registered again are sent again"
    );
}

#[test]
fn fr16_4_releasing_an_unknown_id_returns_false_and_sends_nothing() {
    let mut host = ComposeHost::with_content(Empty);
    host.rebuild().expect("the first batch");
    assert!(!release_owned(4_000_000, AssetKind::Svg));
    let kept = register_owned(AssetKind::Svg, picture());
    assert!(
        !release_owned(kept, AssetKind::Png),
        "an id registered as another kind is not released"
    );
    let (_, released) = asset_records(host.render_frame(1).expect("a frame"));
    assert!(released.is_empty(), "{released:?}");
}

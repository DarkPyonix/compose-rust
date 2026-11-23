//! A registered font is let go of through the authoring API: `release_asset(id)` puts one
//! `ReleaseAsset` on the next batch, and the id is never handed out again.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::AssetKind;
use compose_rust::ui::*;
use compose_rust::{asset, release_asset};
use std::sync::atomic::{AtomicU32, Ordering};

/// Stand-in font bytes: the Host does not read them, the Renderer does.
static FONT: &[u8] = b"\x00\x01\x00\x00fr48-release-asset-test-font";

static REGISTERED: AtomicU32 = AtomicU32::new(0);

#[Composable]
fn Screen() {
    Row!({
        REGISTERED.store(asset(AssetKind::Font, FONT), Ordering::SeqCst);
    });
}

fn releases(batch: &[u8]) -> Vec<u32> {
    decode_batch(batch)
        .expect("the batch decodes")
        .iter()
        .filter_map(|m| match m {
            Mutation::ReleaseAsset { asset_id } => Some(*asset_id),
            _ => None,
        })
        .collect()
}

#[test]
fn fr48_release_asset_sends_one_release_and_never_reuses_the_id() {
    let mut host = ComposeHost::with_content(Screen);
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    let id = REGISTERED.load(Ordering::SeqCst);
    assert_ne!(id, 0);
    let registered = decode_batch(&first).expect("the batch decodes");
    assert!(
        registered.iter().any(|m| matches!(
            m,
            Mutation::RegisterAsset { asset_id, kind: AssetKind::Font, .. } if *asset_id == id
        )),
        "{registered:?}"
    );

    assert!(release_asset(id));
    assert!(!release_asset(id), "a second release is a no-op");
    assert!(!release_asset(id + 1_000), "an unknown id is not released");

    let next = host
        .render_frame(16_000_000)
        .expect("the next batch encodes")
        .to_vec();
    assert_eq!(releases(&next), [id]);
    let after = host
        .render_frame(32_000_000)
        .expect("a quiet batch encodes")
        .to_vec();
    assert!(releases(&after).is_empty(), "the release is sent once");

    assert_ne!(
        asset(AssetKind::Font, FONT),
        id,
        "a released id is not handed out again"
    );
}

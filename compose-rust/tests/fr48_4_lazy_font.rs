//! Fonts set aside on the Host: `lazy_asset` hands out the id `asset` would, the bytes stay
//! here, and the Renderer's `AssetWanted(id)` is answered with the `RegisterAsset` that
//! carries them on the next batch.

use compose_rust::protocol::{
    HostEvent, Mutation, ProtocolError, decode_batch, decode_event, encode_event,
};
use compose_rust::runtime::*;
use compose_rust::schema::{AssetKind, EVENT_SCHEMA, EventPayload, EventPayloadType};
use compose_rust::ui::*;
use compose_rust::{ComposeHost, FontRef, FontRefs, FontStack, asset, lazy_asset, release_asset};

/// Stand-in font bytes: the Host does not read them, the Renderer does.
static SUBSET: &[u8] = b"\x00\x01\x00\x00fr48-4 subset face, the letters most screens use";
static FULL: &[u8] = b"\x00\x01\x00\x00fr48-4 full face, which stays on the Host until asked for";

/// The ids and the list one screen uses: the subset registered, the full face set aside,
/// and a stack of the two. The same bytes give the same ids on every call.
fn fonts() -> (u32, u32, FontRefs, u32) {
    let subset = asset(AssetKind::Font, SUBSET);
    let full = lazy_asset(AssetKind::Font, FULL);
    let list = FontRefs::new([FontRef::Asset(subset), FontRef::LazyAsset(full)]);
    let stack = FontStack::new()
        .family([subset])
        .lazy_family([full])
        .register();
    (subset, full, list, stack)
}

#[Composable]
fn Screen() {
    let (_, _, list, _) = fonts();
    Column!({
        Text!("가똠", type_role = TypeRole::None, font = list.clone());
    });
}

/// The registrations a batch carries, as (id, kind, bytes).
fn registrations(batch: &[u8]) -> Vec<(u32, AssetKind, Vec<u8>)> {
    decode_batch(batch)
        .expect("the batch decodes")
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::RegisterAsset {
                asset_id,
                kind,
                bytes,
            } => Some((*asset_id, *kind, bytes.to_vec())),
            _ => None,
        })
        .collect()
}

fn releases(batch: &[u8]) -> Vec<u32> {
    decode_batch(batch)
        .expect("the batch decodes")
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::ReleaseAsset { asset_id } => Some(*asset_id),
            _ => None,
        })
        .collect()
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    haystack
        .windows(needle.len())
        .any(|window| window == needle)
}

fn asset_wanted(asset_id: u32) -> HostEvent<'static> {
    HostEvent::new(0, 0, EventPayload::AssetWanted { asset_id })
}

fn start() -> (ComposeHost, Vec<u8>) {
    let mut host = ComposeHost::with_content(Screen);
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    (host, first)
}

/// A screen set in `[subset, LazyAsset(full)]` and in a stack of the same two sends the
/// subset and the stack in its first batch and nothing of the full face, and a quiet frame
/// after it sends nothing of it either.
#[test]
fn fr48_4_lazy_face_bytes_stay_on_the_host() {
    let (mut host, first) = start();
    let (subset, full, _, stack) = fonts();
    assert_ne!(full, 0);
    assert_ne!(full, subset);

    let registered = registrations(&first);
    assert!(
        registered.contains(&(subset, AssetKind::Font, SUBSET.to_vec())),
        "the subset goes out with the first batch: {registered:?}"
    );
    assert!(
        registered.iter().all(|(id, _, _)| *id != full),
        "the full face is not registered: {registered:?}"
    );
    assert!(
        !contains(&first, FULL),
        "the full face's bytes are nowhere in the first batch"
    );

    // The stack names the full face, as a family set aside, by id alone.
    let (_, _, stack_bytes) = registered
        .iter()
        .find(|(id, _, _)| *id == stack)
        .expect("the stack goes out with the first batch");
    let decoded = FontStack::from_bytes(stack_bytes).expect("the stack reads back");
    assert_eq!(decoded.families(), &[vec![subset], vec![full]]);
    assert!(!decoded.is_lazy(0));
    assert!(decoded.is_lazy(1));

    let quiet = host.render_frame(16_000_000).expect("a frame").to_vec();
    assert!(
        !contains(&quiet, FULL),
        "nothing asked for it, so nothing sent it"
    );
}

/// The Renderer's request is answered with the existing `RegisterAsset`, under the id the
/// lists name, with the bytes set aside, on the batch the request returns. It goes once.
#[test]
fn fr48_4_asset_wanted_registers_the_set_aside_face_on_the_next_batch() {
    let (mut host, _) = start();
    let (_, full, _, _) = fonts();
    let (answer, _) = host
        .dispatch(asset_wanted(full))
        .expect("the request is answered");
    let answer = answer.to_vec();
    assert_eq!(
        registrations(&answer),
        vec![(full, AssetKind::Font, FULL.to_vec())],
        "one registration of the full face"
    );
    let after = host.render_frame(16_000_000).expect("a frame").to_vec();
    assert!(
        registrations(&after).is_empty(),
        "the registration is sent once"
    );
    // Loaded, the id is a registered asset like any other.
    assert_eq!(asset(AssetKind::Font, FULL), full);
}

/// A request for an id the Host never set anything aside under changes nothing and is
/// one protocol error, for that request. A request that names a node or a handler is
/// refused as well.
#[test]
fn fr48_4_an_unknown_asset_wanted_is_one_protocol_error() {
    let (mut host, _) = start();
    let (_, full, _, _) = fonts();
    let unknown = full + 1_000;
    assert_eq!(
        host.dispatch(asset_wanted(unknown)).err(),
        Some(ProtocolError::UnknownAsset(unknown))
    );
    let after = host.render_frame(16_000_000).expect("a frame").to_vec();
    assert!(
        registrations(&after).is_empty(),
        "nothing was queued for it"
    );
    assert!(releases(&after).is_empty());

    assert_eq!(
        host.dispatch(HostEvent::new(
            3,
            0,
            EventPayload::AssetWanted { asset_id: full }
        ))
        .err(),
        Some(ProtocolError::InvalidValueKind(0)),
        "the request is the Host's own, about no node"
    );
    assert_eq!(
        host.dispatch(HostEvent::new(
            0,
            9,
            EventPayload::AssetWanted { asset_id: full }
        ))
        .err(),
        Some(ProtocolError::InvalidValueKind(0)),
        "and for no handler"
    );
    // The process lives on, and the face is still there to send.
    let (answer, _) = host
        .dispatch(asset_wanted(full))
        .expect("a real request is answered");
    assert_eq!(registrations(answer).len(), 1);
}

/// A loaded lazy face lives until `ReleaseAsset`, and a request after the release sends
/// the same bytes again under the same id.
#[test]
fn fr48_4_a_released_lazy_face_is_sent_again_when_wanted() {
    let (mut host, _) = start();
    let (_, full, _, _) = fonts();
    let (answer, _) = host
        .dispatch(asset_wanted(full))
        .expect("the request is answered");
    assert_eq!(registrations(answer).len(), 1);

    assert!(release_asset(full), "a loaded lazy face can be let go of");
    let released = host.render_frame(16_000_000).expect("a frame").to_vec();
    assert_eq!(releases(&released), [full]);

    let (again, _) = host
        .dispatch(asset_wanted(full))
        .expect("the second request is answered");
    assert_eq!(
        registrations(again),
        vec![(full, AssetKind::Font, FULL.to_vec())],
        "the same bytes under the same id"
    );
    assert_eq!(
        lazy_asset(AssetKind::Font, FULL),
        full,
        "the id did not move"
    );
}

/// `lazy_asset` gives the id `asset` would: the registration's own where the bytes are
/// registered already, and where they are not, an id `asset` then registers them under.
#[test]
fn fr48_4_lazy_asset_gives_the_id_asset_would() {
    static REGISTERED_FIRST: &[u8] = b"\x00\x01\x00\x00fr48-4 registered before it was set aside";
    static SET_ASIDE_FIRST: &[u8] = b"\x00\x01\x00\x00fr48-4 set aside before it was registered";
    let mut host = ComposeHost::with_content(Screen);
    host.rebuild().expect("the first batch encodes");

    let registered = asset(AssetKind::Font, REGISTERED_FIRST);
    assert_eq!(lazy_asset(AssetKind::Font, REGISTERED_FIRST), registered);

    let set_aside = lazy_asset(AssetKind::Font, SET_ASIDE_FIRST);
    assert_eq!(
        lazy_asset(AssetKind::Font, SET_ASIDE_FIRST),
        set_aside,
        "the same bytes, the same id"
    );
    assert_ne!(set_aside, registered);
    assert!(
        !release_asset(set_aside),
        "nothing was sent, so nothing is let go of"
    );
    let quiet = host.render_frame(16_000_000).expect("a frame").to_vec();
    assert_eq!(
        registrations(&quiet),
        vec![(registered, AssetKind::Font, REGISTERED_FIRST.to_vec())],
        "what asset registered goes out, what lazy_asset set aside does not"
    );

    assert_eq!(asset(AssetKind::Font, SET_ASIDE_FIRST), set_aside);
    let next = host.render_frame(32_000_000).expect("a frame").to_vec();
    assert_eq!(
        registrations(&next),
        vec![(set_aside, AssetKind::Font, SET_ASIDE_FIRST.to_vec())],
        "registered with asset, the set aside bytes go out under the id lazy_asset gave"
    );
}

/// The candidate and the event come back as they went. The candidate is kind 4 with its
/// id in the value word; a stack with a lazy family is version 2 with a kind word per
/// family, and one without stays version 1. The event is tag 71, a 20 byte record with
/// the id after the header, and the vector's last record asks for asset 42.
#[test]
fn fr10_lazy_asset_font_ref_round_trips() {
    let list = FontRefs::new([FontRef::Asset(3), FontRef::LazyAsset(9)]);
    assert_eq!(list.refs(), vec![FontRef::Asset(3), FontRef::LazyAsset(9)]);
    let bytes = list.as_bytes();
    assert_eq!(&bytes[0..4], &2_u32.to_le_bytes());
    assert_eq!(
        &bytes[16..20],
        &4_u32.to_le_bytes(),
        "the second record's kind"
    );
    assert_eq!(&bytes[20..24], &9_u32.to_le_bytes(), "and its id");
    assert_eq!(compose_rust::fonts::FONT_REF_LAZY_ASSET, 4);
    assert_eq!(FontRefs::from_bytes(bytes.to_vec()).refs(), list.refs());

    let lazy = FontStack::new().family([1, 2]).lazy_family([3]).family([4]);
    let lazy_bytes = lazy.to_bytes();
    assert_eq!(&lazy_bytes[4..8], &2_u32.to_le_bytes(), "version 2");
    assert_eq!(
        &lazy_bytes[12..16],
        &1_u32.to_le_bytes(),
        "a family of registered faces"
    );
    assert_eq!(
        &lazy_bytes[28..32],
        &4_u32.to_le_bytes(),
        "a family set aside"
    );
    assert_eq!(FontStack::from_bytes(&lazy_bytes), Some(lazy));
    let plain = FontStack::new().family([1, 2]).family([3]);
    assert_eq!(
        &plain.to_bytes()[4..8],
        &1_u32.to_le_bytes(),
        "version 1, as before"
    );
    assert_eq!(FontStack::from_bytes(&plain.to_bytes()), Some(plain));

    let schema = EVENT_SCHEMA
        .iter()
        .find(|event| event.name == "AssetWanted")
        .expect("AssetWanted is in the event schema");
    assert_eq!(schema.tag, 71);
    assert_eq!(schema.payload, EventPayloadType::AssetWanted);
    assert_eq!(
        EVENT_SCHEMA.iter().filter(|event| event.tag == 71).count(),
        1
    );

    for asset_id in [1_u32, 42, u32::MAX] {
        let event = asset_wanted(asset_id);
        let mut encoded = Vec::new();
        encode_event(&event, &mut encoded).unwrap();
        assert_eq!(encoded.len(), 20);
        assert_eq!(&encoded[0..2], &71_u16.to_le_bytes());
        assert_eq!(&encoded[2..4], &20_u16.to_le_bytes());
        assert_eq!(&encoded[4..8], &0_u32.to_le_bytes());
        assert_eq!(&encoded[8..16], &0_u64.to_le_bytes());
        assert_eq!(&encoded[16..20], &asset_id.to_le_bytes());
        assert_eq!(decode_event(&encoded), Ok(event));
    }

    let events = include_bytes!("vectors/events.bin");
    // The last record, after the two rich text events.
    assert_eq!(events.len(), 2042 + 20);
    assert_eq!(decode_event(&events[2042..2062]), Ok(asset_wanted(42)));

    let mut long = Vec::new();
    encode_event(&asset_wanted(42), &mut long).unwrap();
    long[2..4].copy_from_slice(&24_u16.to_le_bytes());
    long.extend_from_slice(&[0; 4]);
    assert_eq!(decode_event(&long), Err(ProtocolError::InvalidRecordLength));
}

//! Asset delivery: registering bytes once, drawing them by id, and releasing them.

use dioxus_compose_adapter::prelude::*;
use dioxus_compose_adapter::protocol::{Mutation, PropertyValue, ProtocolError, decode_batch};
use dioxus_compose_adapter::{AssetKind, Host, IconRole, PropertyKind, WidgetKind};

const PNG_HEADER: &[u8] = &[0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a];

/// The bytes ride behind the records, the way a string does, so the record itself stays
/// the fixed layout everything else on this wire is.
#[test]
fn fr16_register_asset_carries_the_bytes_behind_a_fixed_layout_record() {
    let mut host = Host::new(|| rsx! { Text { text: "" } });
    let batch = host.register_asset(7, AssetKind::Png, PNG_HEADER).unwrap();
    let decoded = decode_batch(batch).unwrap();
    assert_eq!(
        decoded,
        vec![Mutation::RegisterAsset {
            asset_id: 7,
            kind: AssetKind::Png,
            bytes: PNG_HEADER,
        }],
    );
}

/// A release names the id and nothing else: the Host owns the lifetime and the Renderer
/// only needs to know which entry to drop.
#[test]
fn fr16_release_asset_names_only_the_id() {
    let mut host = Host::new(|| rsx! { Text { text: "" } });
    let batch = host.release_asset(7).unwrap();
    assert_eq!(
        decode_batch(batch).unwrap(),
        vec![Mutation::ReleaseAsset { asset_id: 7 }],
    );
}

/// An icon is registered by the meaning it carries, never by a system icon name. A name
/// would move the check that the icon exists to run time and would pin one platform's
/// artwork into the protocol, so what crosses is the role tag.
#[test]
fn fr16_icons_are_registered_by_role_not_by_name() {
    let mut host = Host::new(|| rsx! { Text { text: "" } });
    let batch = host.register_icon(3, IconRole::Search).unwrap();
    assert_eq!(
        decode_batch(batch).unwrap(),
        vec![Mutation::RegisterAsset {
            asset_id: 3,
            kind: AssetKind::VectorIcon,
            bytes: &(IconRole::Search as u16).to_le_bytes(),
        }],
    );
}

/// A kind the schema does not name never becomes a mutation. The Renderer answers the
/// same case with a reported protocol error rather than a guess.
#[test]
fn fr16_an_unknown_asset_kind_does_not_decode() {
    let mut batch = vec![0, 0, 12, 0, 32, 0, 0, 0, 1, 0, 0, 0];
    batch.extend_from_slice(&[
        10, 0, 20, 0, // RegisterAsset, 20 bytes
        7, 0, 0, 0, // asset id
        0xff, 0xff, 0, 0, // an unnamed kind
        32, 0, 0, 0, // offset
        0, 0, 0, 0, // length
    ]);
    assert_eq!(
        decode_batch(&batch),
        Err(ProtocolError::InvalidAssetKind(u16::MAX)),
    );
}

fn image_app() -> Element {
    rsx! {
        Image { asset_id: 7 }
    }
}

/// An `Image` carries the asset id and nothing else. The bytes crossed once, at
/// registration, and the per-frame record stays fixed layout because of it.
#[test]
fn fr16_image_sends_only_the_asset_id() {
    let mut host = Host::new(image_app);
    let batch = host.rebuild().unwrap().to_vec();
    let decoded = decode_batch(&batch).unwrap();
    let node = decoded
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Image,
            } => Some(*node_id),
            _ => None,
        })
        .expect("no Image was created");
    let props: Vec<_> = decoded
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property,
                value,
            } if *node_id == node => Some((*property, value.clone())),
            _ => None,
        })
        .collect();
    assert_eq!(
        props,
        vec![(PropertyKind::Asset, PropertyValue::Integer(7))],
    );
}

fn icon_app() -> Element {
    rsx! {
        Icon { asset_id: 4, color: Paint::Role(ColorRole::Primary) }
    }
}

/// An `Icon` adds a tint, and the tint is a role: which pixels that is belongs to the
/// design system, the same way it does for every other colour on this wire.
#[test]
fn fr16_icon_sends_the_asset_id_and_a_role_tint() {
    let mut host = Host::new(icon_app);
    let batch = host.rebuild().unwrap().to_vec();
    let decoded = decode_batch(&batch).unwrap();
    let node = decoded
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Icon,
            } => Some(*node_id),
            _ => None,
        })
        .expect("no Icon was created");
    let props: Vec<_> = decoded
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property,
                value,
            } if *node_id == node => Some((*property, value.clone())),
            _ => None,
        })
        .collect();
    assert_eq!(
        props,
        vec![
            (PropertyKind::Asset, PropertyValue::Integer(4)),
            (
                PropertyKind::Color,
                PropertyValue::Integer(Paint::Role(ColorRole::Primary).to_bits() as i64),
            ),
        ],
    );
}

/// The asset tags are assigned once. Renumbering one silently breaks every Renderer
/// already built against it, so the numbers are pinned here.
#[test]
fn fr16_asset_kinds_keep_their_assigned_tags() {
    assert_eq!(
        [
            AssetKind::Png as u16,
            AssetKind::Jpeg as u16,
            AssetKind::Svg as u16,
            AssetKind::VectorIcon as u16,
        ],
        [1, 2, 3, 4],
    );
    assert_eq!(
        [WidgetKind::Image as u16, WidgetKind::Icon as u16],
        [10, 11],
    );
}

/// A hand-written one pixel PNG and a hand-written SVG, so a test that registers two
/// different pictures is registering two different things rather than the same bytes
/// twice.
static ONE_PIXEL: &[u8] = &[
    0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, b'I', b'H', b'D', b'R',
];
static TINY_SVG: &[u8] = b"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1 1'/>";

/// The picture an application declares, drawn twice, which is the shape a real screen has:
/// the same artwork in a list and in a header is one registration and two nodes.
fn declared_image_app() -> Element {
    rsx! {
        Column {
            Image { asset_id: asset(AssetKind::Svg, TINY_SVG), height: 40.0 }
            Image { asset_id: asset(AssetKind::Svg, TINY_SVG), height: 20.0 }
        }
    }
}

fn registrations(batch: &[u8]) -> Vec<(u32, AssetKind, Vec<u8>)> {
    decode_batch(batch)
        .expect("the batch did not decode")
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::RegisterAsset {
                asset_id,
                kind,
                bytes,
            } => Some((asset_id, kind, bytes.to_vec())),
            _ => None,
        })
        .collect()
}

/// An application registers a picture by declaring it, and the registration rides out on
/// the batch that declaration produced. Nothing here touches a boundary function.
#[test]
fn fr16_an_application_registers_a_picture_by_drawing_it() {
    let mut host = Host::new(declared_image_app);
    let batch = host.rebuild().unwrap().to_vec();
    assert_eq!(
        registrations(&batch),
        vec![(1, AssetKind::Svg, TINY_SVG.to_vec())],
        "the same picture twice is one registration"
    );
    let ids: Vec<i64> = decode_batch(&batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                property: PropertyKind::Asset,
                value: PropertyValue::Integer(id),
                ..
            } => Some(id),
            _ => None,
        })
        .collect();
    assert_eq!(ids, vec![1, 1], "both nodes draw the one registration");
}

/// Nothing is registered again on a later frame. A component body calls `asset` on every
/// render, so without this the same file would cross the boundary once a frame and the
/// steady-state allocation ceiling would be gone.
#[test]
fn fr16_a_later_frame_registers_nothing() {
    let mut host = Host::new(declared_image_app);
    host.rebuild().unwrap();
    let batch = host.render_frame(0).unwrap().to_vec();
    assert!(
        registrations(&batch).is_empty(),
        "a picture was registered a second time"
    );
}

fn two_pictures_app() -> Element {
    rsx! {
        Column {
            Image { asset_id: asset(AssetKind::Svg, TINY_SVG) }
            Image { asset_id: asset(AssetKind::Png, ONE_PIXEL) }
        }
    }
}

/// Two different pictures get two different ids, and the Host hands them out: an
/// application that picked its own numbers could give one picture's id to another screen's
/// picture and neither side would notice.
#[test]
fn fr16_two_pictures_are_two_registrations_with_ids_from_the_host() {
    let mut host = Host::new(two_pictures_app);
    let batch = host.rebuild().unwrap().to_vec();
    assert_eq!(
        registrations(&batch),
        vec![
            (1, AssetKind::Svg, TINY_SVG.to_vec()),
            (2, AssetKind::Png, ONE_PIXEL.to_vec()),
        ],
    );
}

/// A new Host registers everything again. Its Renderer's cache is empty, so an id the
/// Host before it handed out names nothing, and a screen built on those ids would draw no
/// pictures while every batch it sent looked correct.
#[test]
fn fr16_a_new_host_registers_the_pictures_again() {
    let mut first = Host::new(declared_image_app);
    assert_eq!(registrations(first.rebuild().unwrap()).len(), 1);
    drop(first);

    let mut second = Host::new(declared_image_app);
    assert_eq!(
        registrations(second.rebuild().unwrap()),
        vec![(1, AssetKind::Svg, TINY_SVG.to_vec())],
    );
}

/// The same picture reached by two names.
///
/// A `const` holding a reference is inlined at each use, and each use can be given an
/// allocation of its own, so a catalogue written as a `const` array hands out two pointers
/// to one file. The shop did exactly this and registered one cover twice under two ids,
/// which is two copies of a file across the boundary and a Renderer cache holding the same
/// poster twice.
const FIRST_NAME: &[u8] = TINY_SVG;
// Spelled out byte by byte rather than as a string literal. Two equal string literals are
// one allocation, and one allocation is the case this is here to rule out.
#[allow(clippy::byte_char_slices)]
const SECOND_NAME: &[u8] = &[
    b'<', b's', b'v', b'g', b' ', b'x', b'm', b'l', b'n', b's', b'=', b'\'', b'h', b't', b't',
    b'p', b':', b'/', b'/', b'w', b'w', b'w', b'.', b'w', b'3', b'.', b'o', b'r', b'g', b'/', b'2',
    b'0', b'0', b'0', b'/', b's', b'v', b'g', b'\'', b' ', b'v', b'i', b'e', b'w', b'B', b'o',
    b'x', b'=', b'\'', b'0', b' ', b'0', b' ', b'1', b' ', b'1', b'\'', b'/', b'>',
];

fn twice_named_app() -> Element {
    rsx! {
        Column {
            Image { asset_id: asset(AssetKind::Svg, FIRST_NAME) }
            Image { asset_id: asset(AssetKind::Svg, SECOND_NAME) }
        }
    }
}

#[test]
fn fr16_the_same_bytes_under_two_names_are_one_registration() {
    assert_eq!(
        FIRST_NAME, SECOND_NAME,
        "the two names are not the same file"
    );
    let mut host = Host::new(twice_named_app);
    let batch = host.rebuild().unwrap().to_vec();
    assert_eq!(
        registrations(&batch),
        vec![(1, AssetKind::Svg, TINY_SVG.to_vec())],
    );
}

/// An asset registered before the Host exists still reaches the Renderer.
///
/// A theme names its fonts by id and a window names its icon by id, and both are built by
/// the caller before `launch` makes a Host out of them. The Host was clearing the asset
/// table on the way in, on the reasoning that a new Renderer has an empty cache and an
/// old id names nothing there. True, and the cure threw away the registrations the ids it
/// was about to send belonged to: the theme travelled naming font 2, the window travelled
/// naming icon 3, and nothing in the batch said what either of them was.
///
/// The cache being empty is the reason to send them all again, not the reason to forget
/// them.
#[test]
fn fr16_an_asset_registered_before_the_host_is_sent_by_it() {
    use dioxus_compose_adapter::protocol::{Mutation, decode_batch};
    use dioxus_compose_adapter::schema::AssetKind;

    static PICTURE: &[u8] = b"not really a picture, and nothing decodes it here";
    dioxus_compose_adapter::asset::reset_assets();
    // Before the Host, which is where a theme's fonts and a window's icon are registered.
    let registered = dioxus_compose_adapter::asset::asset(AssetKind::Png, PICTURE);

    let mut host = dioxus_compose_adapter::Host::new(|| dioxus_compose_adapter::rsx! {});
    let first = host.rebuild().expect("the first frame failed to encode");
    let sent: Vec<u32> = decode_batch(first)
        .expect("the first batch did not decode")
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::RegisterAsset { asset_id, .. } => Some(*asset_id),
            _ => None,
        })
        .collect();
    assert!(
        sent.contains(&registered),
        "asset {registered} was registered before the Host and the first batch sent {sent:?}",
    );
}

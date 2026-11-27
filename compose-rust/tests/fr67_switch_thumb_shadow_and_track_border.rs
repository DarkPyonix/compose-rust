//! A `Switch` can lift its thumb with a shadow and draw a line around its track. Each is
//! sent only when it was given, so a switch that gives neither reaches the Renderer exactly
//! as it did before, and a value that did not change is not sent again.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{EventPayload, PropertyKind, WidgetKind};
use compose_rust::ui::*;
use support::Tree;

const INK: Paint = Paint::Literal(Color::rgb(0x1c1b19));

/// One `SetProp` record, owned, so it outlives the batch it was read from.
#[derive(Debug, PartialEq)]
enum Sent {
    Float(f32),
    Bytes(Vec<u8>),
    Removed,
    Other(String),
}

/// The records `batch` writes for `property`, with the widget of the node each is on.
fn records(
    batch: &[u8],
    widgets: &[(u32, WidgetKind)],
    property: PropertyKind,
) -> Vec<(WidgetKind, Sent)> {
    let mutations = decode_batch(batch).expect("the batch decodes");
    let widget_of = |id: u32| {
        mutations
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::Create { node_id, widget } if *node_id == id => Some(*widget),
                _ => None,
            })
            .or_else(|| {
                widgets
                    .iter()
                    .find(|(node, _)| *node == id)
                    .map(|(_, widget)| *widget)
            })
            .expect("every property is written on a node that was created")
    };
    mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: written,
                value,
            } if *written == property => {
                let sent = match value {
                    PropertyValue::Float(value) => Sent::Float(*value),
                    PropertyValue::Bytes(bytes) => Sent::Bytes(bytes.to_vec()),
                    PropertyValue::None => Sent::Removed,
                    other => Sent::Other(format!("{other:?}")),
                };
                Some((widget_of(*node_id), sent))
            }
            _ => None,
        })
        .collect()
}

/// The nodes the first batch created, with their widgets.
fn created(batch: &[u8]) -> Vec<(u32, WidgetKind)> {
    decode_batch(batch)
        .expect("the batch decodes")
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create { node_id, widget } => Some((*node_id, *widget)),
            _ => None,
        })
        .collect()
}

/// The 12 bytes a track border travels as: the width's f32 bits, then the paint's u64 bits.
fn border_bytes(width: f32, paint: Paint) -> Vec<u8> {
    let mut bytes = width.to_bits().to_le_bytes().to_vec();
    bytes.extend_from_slice(&paint.to_bits().to_le_bytes());
    bytes
}

#[Composable]
fn Plain() {
    Column!({
        Switch!(true, |_| {});
        Switch!(false, |_| {}, track_color = INK, size = (36.0, 20.0));
    });
}

/// A switch that gives neither parameter writes neither property, whatever else it gives.
#[test]
fn fr67_switch_sends_no_thumb_elevation_or_track_border_unless_given() {
    let mut host = ComposeHost::with_content(Plain);
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let widgets = created(&batch);
    assert_eq!(records(&batch, &widgets, PropertyKind::ThumbElevation), []);
    assert_eq!(records(&batch, &widgets, PropertyKind::TrackBorder), []);
}

#[Composable]
fn Lifted() {
    Column!({
        Switch!(
            false,
            |_| {},
            thumb_elevation = 2.0,
            track_border = (1.5, INK)
        );
        Switch!(
            true,
            |_| {},
            track_border = (0.0, Paint::Role(ColorRole::Outline))
        );
    });
}

/// The elevation is property 212 as the f32 it was given, and the border is property 213 as
/// the width's bits followed by the paint's, both little-endian. A width of zero still
/// travels: it is how a switch asks for no line where its design system draws one.
#[test]
fn fr67_switch_thumb_elevation_and_track_border_travel_with_their_bytes() {
    assert_eq!(PropertyKind::ThumbElevation as u16, 212);
    assert_eq!(PropertyKind::TrackBorder as u16, 213);
    assert_eq!(
        PropertyKind::try_from(212),
        Ok(PropertyKind::ThumbElevation)
    );
    assert_eq!(PropertyKind::try_from(213), Ok(PropertyKind::TrackBorder));
    assert_eq!(
        PropertyKind::from_name("thumb_elevation"),
        Ok(PropertyKind::ThumbElevation)
    );
    assert_eq!(
        PropertyKind::from_name("track_border"),
        Ok(PropertyKind::TrackBorder)
    );

    let mut host = ComposeHost::with_content(Lifted);
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let widgets = created(&batch);
    assert_eq!(
        records(&batch, &widgets, PropertyKind::ThumbElevation),
        [(WidgetKind::Switch, Sent::Float(2.0))]
    );
    let borders = records(&batch, &widgets, PropertyKind::TrackBorder);
    assert_eq!(
        borders,
        [
            (WidgetKind::Switch, Sent::Bytes(border_bytes(1.5, INK))),
            (
                WidgetKind::Switch,
                Sent::Bytes(border_bytes(0.0, Paint::Role(ColorRole::Outline)))
            ),
        ]
    );
    if let (_, Sent::Bytes(bytes)) = &borders[0] {
        assert_eq!(bytes.len(), 12, "a track border is 12 bytes");
    }
}

#[Composable]
fn Recomposed() {
    let count = remember(|| mutable_state_of(0_u32));
    let lifted = remember(|| mutable_state_of(false));
    Column!({
        let more = count;
        Row!(on_click = Some(move || more.update(|n| *n += 1)), {
            Text!("again");
        });
        let lift = lifted;
        Row!(on_click = Some(move || lift.set(true)), {
            Text!("lift");
        });
        // The name changes on every click, so the switch is written again each time while
        // its elevation and border stay what they were, until "lift" changes the elevation.
        let name = format!("switch {}", count.get());
        let elevation: f32 = if lifted.get() { 4.0 } else { 2.0 };
        Switch!(
            false,
            |_| {},
            accessible_name = name.as_str(),
            thumb_elevation = elevation,
            track_border = (1.0, INK),
        );
    });
}

/// The batches a click on the `index`th clickable row produces, the frame after it included.
fn click(host: &mut ComposeHost, tree: &Tree, index: usize) -> Vec<Vec<u8>> {
    let (node_id, handler_id) = tree.handled("Row", PropertyKind::OnClick)[index];
    let (batch, _) = host
        .dispatch(HostEvent::new(node_id, handler_id, EventPayload::Clicked))
        .expect("the click is accepted");
    let mut batches = vec![batch.to_vec()];
    batches.push(host.render_frame(0).expect("the frame encodes").to_vec());
    batches
}

/// Writing the switch again with the same elevation and border sends neither property.
#[test]
fn fr67_switch_same_thumb_elevation_and_track_border_send_nothing_again() {
    let mut host = ComposeHost::with_content(Recomposed);
    let mut tree = Tree::default();
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    tree.apply(&first);
    let widgets = created(&first);

    let batches = click(&mut host, &tree, 0);
    let mut names = 0;
    for batch in &batches {
        tree.apply(batch);
        names += records(batch, &widgets, PropertyKind::AccessibleName).len();
        assert_eq!(records(batch, &widgets, PropertyKind::ThumbElevation), []);
        assert_eq!(records(batch, &widgets, PropertyKind::TrackBorder), []);
    }
    assert_eq!(names, 1, "the switch was written again, with its new name");
}

/// A changed elevation is one `SetProp` of the new value, and the border is not sent.
#[test]
fn fr67_switch_changed_thumb_elevation_sends_one_set_prop() {
    let mut host = ComposeHost::with_content(Recomposed);
    let mut tree = Tree::default();
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    tree.apply(&first);
    let widgets = created(&first);

    let mut elevations = Vec::new();
    let mut borders = Vec::new();
    for batch in click(&mut host, &tree, 1) {
        tree.apply(&batch);
        elevations.extend(records(&batch, &widgets, PropertyKind::ThumbElevation));
        borders.extend(records(&batch, &widgets, PropertyKind::TrackBorder));
    }
    assert_eq!(elevations, [(WidgetKind::Switch, Sent::Float(4.0))]);
    assert_eq!(borders, []);
}

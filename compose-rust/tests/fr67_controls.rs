//! Switch, Slider, Checkbox and RadioButton take colours and a size, and send them only when
//! asked. A Column or a Row can be a radio group.

mod support;

use compose_rust::ComposeHost;
use compose_rust::runtime::*;
use compose_rust::ui::*;
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> Tree {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    tree
}

const INK: Paint = Paint::Literal(Color::rgb(0x1c1b19));
const SURFACE: Paint = Paint::Literal(Color::rgb(0xffffff));

#[Composable]
fn Plain() {
    Column!({
        Switch!(true, |_| {});
        Slider!(0.5, |_| {});
        Checkbox!(true, |_| {});
    });
}

#[Composable]
fn Styled() {
    Column!({
        Switch!(
            true,
            |_| {},
            size = (36.0, 20.0),
            track_color = INK,
            thumb_color = SURFACE
        );
        Slider!(
            0.5,
            |_| {},
            active_track_color = INK,
            track_color = SURFACE,
            thumb_color = INK
        );
        Checkbox!(
            true,
            |_| {},
            size = 18.0,
            container_color = INK,
            check_color = SURFACE
        );
    });
}

fn line<'a>(dump: &'a str, widget: &str) -> &'a str {
    dump.lines()
        .find(|line| line.trim_start().starts_with(widget))
        .unwrap_or_else(|| panic!("no {widget} in\n{dump}"))
}

#[test]
fn fr67_controls_that_set_nothing_send_none_of_the_new_properties() {
    let dump = start(Plain).dump();
    for name in [
        "TrackColor",
        "ThumbColor",
        "ActiveTrackColor",
        "ContainerColor",
        "CheckColor",
        "ControlSize",
    ] {
        assert!(!dump.contains(name), "{name} was sent in\n{dump}");
    }
}

#[test]
fn fr67_the_switch_sends_its_colours_and_its_size() {
    let dump = start(Styled).dump();
    let switch = line(&dump, "Switch");
    for name in ["TrackColor", "ThumbColor", "ControlSize"] {
        assert!(switch.contains(name), "{switch}");
    }
    for name in ["ActiveTrackColor", "ContainerColor", "CheckColor"] {
        assert!(!switch.contains(name), "{switch}");
    }
}

#[test]
fn fr67_the_slider_sends_the_active_and_remaining_track_and_the_thumb() {
    let dump = start(Styled).dump();
    let slider = line(&dump, "Slider");
    for name in ["ActiveTrackColor", "TrackColor", "ThumbColor"] {
        assert!(slider.contains(name), "{slider}");
    }
    assert!(!slider.contains("ControlSize"), "{slider}");
}

#[test]
fn fr67_the_checkbox_sends_its_container_check_and_side() {
    let dump = start(Styled).dump();
    let checkbox = line(&dump, "Checkbox");
    for name in ["ContainerColor", "CheckColor", "ControlSize"] {
        assert!(checkbox.contains(name), "{checkbox}");
    }
    assert!(!checkbox.contains("TrackColor"), "{checkbox}");
}

#[Composable]
fn Dropping() {
    let styled = remember(|| mutable_state_of(true));
    Column!({
        let flip = styled;
        Row!(on_click = Some(move || flip.update(|n| *n = !*n)), {
            Text!("flip");
        });
        // Each widget is finished by its own statement, so they compose in source order.
        let styled = styled.get();
        Switch!(
            true,
            |_| {},
            thumb_color = SURFACE,
            size = styled.then_some((36.0, 20.0)),
            track_color = styled.then_some(INK),
        );
        Checkbox!(
            true,
            |_| {},
            container_color = INK,
            size = styled.then_some(18.0),
            check_color = styled.then_some(SURFACE),
        );
    });
}

#[test]
fn fr67_dropping_a_colour_or_size_sends_a_removal() {
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
    use compose_rust::schema::{EventPayload, PropertyKind};

    let mut host = ComposeHost::with_content(Dropping);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));

    let (node_id, handler_id) = tree.handled("Row", PropertyKind::OnClick)[0];
    let (batch, _) = host
        .dispatch(HostEvent::new(node_id, handler_id, EventPayload::Clicked))
        .expect("the click is accepted");
    let mut batches = vec![batch.to_vec()];
    batches.push(host.render_frame(0).expect("the frame encodes").to_vec());

    let mut removed = Vec::new();
    for batch in &batches {
        for mutation in decode_batch(batch).expect("the batch decodes") {
            if let Mutation::SetProp {
                property, value, ..
            } = mutation
            {
                if value == PropertyValue::None {
                    removed.push(format!("{property:?}"));
                }
            }
        }
    }
    removed.sort();
    // The Switch drops its track colour and size, the Checkbox its check colour and side.
    // Colours and sizes that are still set are not removed.
    assert_eq!(
        removed,
        ["CheckColor", "ControlSize", "ControlSize", "TrackColor"],
        "{removed:?}"
    );
}

#[Composable]
fn Radios() {
    Column!(radio_group = true, {
        RadioButton!(
            true,
            |_| {},
            size = 18.0,
            ring_color = INK,
            dot_color = SURFACE
        );
        RadioButton!(false, |_| {});
    });
    Row!({
        RadioButton!(false, |_| {});
    });
}

#[test]
fn fr67_the_radio_button_sends_its_ring_dot_and_side() {
    let dump = start(Radios).dump();
    let styled = line(&dump, "RadioButton");
    // The ring is the container slot and the dot the check slot, as on a Checkbox.
    for name in ["ContainerColor", "CheckColor", "ControlSize"] {
        assert!(styled.contains(name), "{styled}");
    }
    let plain: Vec<_> = dump
        .lines()
        .filter(|line| line.trim_start().starts_with("RadioButton"))
        .skip(1)
        .collect();
    assert_eq!(plain.len(), 2, "{dump}");
    for line in plain {
        for name in ["ContainerColor", "CheckColor", "ControlSize"] {
            assert!(!line.contains(name), "{line}");
        }
    }
}

#[test]
fn fr67_radio_group_is_sent_only_for_a_group() {
    let dump = start(Radios).dump();
    assert!(line(&dump, "Column").contains("RadioGroup"), "{dump}");
    assert!(!line(&dump, "Row").contains("RadioGroup"), "{dump}");
}

/// The group flag and the radio button's look survive the wire: the property records come
/// back from the encoded batch with the kinds and the values they were written with.
#[test]
fn fr67_radio_group_and_radio_look_round_trip() {
    use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
    use compose_rust::schema::{PropertyKind, WidgetKind};

    let mut host = ComposeHost::with_content(Radios);
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the batch decodes");
    let widget_of = |id: u32| {
        mutations.iter().find_map(|mutation| match mutation {
            Mutation::Create { node_id, widget } if *node_id == id => Some(*widget),
            _ => None,
        })
    };
    let mut group = Vec::new();
    let mut look = Vec::new();
    for mutation in &mutations {
        if let Mutation::SetProp {
            node_id,
            property,
            value,
        } = mutation
        {
            match property {
                PropertyKind::RadioGroup => group.push((widget_of(*node_id), value.clone())),
                PropertyKind::ContainerColor
                | PropertyKind::CheckColor
                | PropertyKind::ControlSize => look.push((widget_of(*node_id), *property)),
                _ => {}
            }
        }
    }
    assert_eq!(
        group,
        [(Some(WidgetKind::Column), PropertyValue::Bool(true))]
    );
    assert_eq!(PropertyKind::RadioGroup as u16, 198);
    assert_eq!(
        look,
        [
            (Some(WidgetKind::RadioButton), PropertyKind::ContainerColor),
            (Some(WidgetKind::RadioButton), PropertyKind::CheckColor),
            (Some(WidgetKind::RadioButton), PropertyKind::ControlSize),
        ]
    );
}

#[Composable]
fn SmallThumb() {
    Column!({
        Slider!(0.5, |_| {}, thumb_size = 12.0);
        Slider!(0.5, |_| {});
    });
}

/// A slider sends its thumb's diameter only when it was given one, as property 202, and
/// the value comes back from the encoded batch as the f32 it was written with.
#[test]
fn fr67_slider_thumb_size() {
    use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
    use compose_rust::schema::{PropertyKind, WidgetKind};

    assert_eq!(PropertyKind::ThumbSize as u16, 202);
    assert_eq!(PropertyKind::try_from(202), Ok(PropertyKind::ThumbSize));

    let mut host = ComposeHost::with_content(SmallThumb);
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the batch decodes");
    let sliders: Vec<u32> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Slider,
            } => Some(*node_id),
            _ => None,
        })
        .collect();
    assert_eq!(sliders.len(), 2);
    let sizes: Vec<_> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::ThumbSize,
                value,
            } => Some((*node_id, value.clone())),
            _ => None,
        })
        .collect();
    assert_eq!(sizes, [(sliders[0], PropertyValue::Float(12.0))]);
}

#[Composable]
fn Mixing() {
    let mixed = remember(|| mutable_state_of(true));
    Column!({
        let flip = mixed;
        Row!(on_click = Some(move || flip.update(|m| *m = !*m)), {
            Text!("flip");
        });
        // Each widget is finished by its own statement, so they compose in source order.
        let mixed = mixed.get();
        Checkbox!(
            false,
            |_| {},
            indeterminate = mixed,
            outline_color = mixed.then_some(INK),
        );
        Checkbox!(true, |_| {}, indeterminate = false);
        Checkbox!(true, |_| {});
    });
}

/// The `property` records of the first frame of `Mixing`, by Checkbox in source order, and
/// the removals of it the frame after the flip.
fn mixing_records(
    property: compose_rust::schema::PropertyKind,
) -> (
    Vec<(usize, compose_rust::protocol::PropertyValue<'static>)>,
    usize,
) {
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch};
    use compose_rust::schema::{EventPayload, PropertyKind, WidgetKind};

    let mut host = ComposeHost::with_content(Mixing);
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    let mutations = decode_batch(&first).expect("the batch decodes");
    let boxes: Vec<u32> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Checkbox,
            } => Some(*node_id),
            _ => None,
        })
        .collect();
    assert_eq!(boxes.len(), 3);
    let sent: Vec<_> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: found,
                value,
            } if *found == property => {
                let index = boxes.iter().position(|id| id == node_id)?;
                let value = match value {
                    PropertyValue::Bool(on) => PropertyValue::Bool(*on),
                    PropertyValue::Integer(bits) => PropertyValue::Integer(*bits),
                    other => panic!("unexpected value {other:?}"),
                };
                Some((index, value))
            }
            _ => None,
        })
        .collect();

    let mut tree = Tree::default();
    tree.apply(&first);
    let (node_id, handler_id) = tree.handled("Row", PropertyKind::OnClick)[0];
    let (batch, _) = host
        .dispatch(HostEvent::new(node_id, handler_id, EventPayload::Clicked))
        .expect("the click is accepted");
    let batches = [
        batch.to_vec(),
        host.render_frame(0).expect("the frame encodes").to_vec(),
    ];
    let mut removed = 0;
    for batch in &batches {
        for mutation in decode_batch(batch).expect("the batch decodes") {
            if let Mutation::SetProp {
                node_id,
                property: found,
                value: PropertyValue::None,
            } = mutation
            {
                if found == property {
                    assert_eq!(node_id, boxes[0], "only the first box ever sent it");
                    removed += 1;
                }
            }
        }
    }
    (sent, removed)
}

/// A Checkbox sends `Indeterminate` (property 216) only while it is mixed, as `true`;
/// `false` and leaving it out send nothing, and a box that stops being mixed sends one
/// removal.
#[test]
fn fr67_checkbox_sends_indeterminate_only_when_mixed() {
    use compose_rust::protocol::PropertyValue;
    use compose_rust::schema::PropertyKind;

    assert_eq!(PropertyKind::Indeterminate as u16, 216);
    assert_eq!(PropertyKind::try_from(216), Ok(PropertyKind::Indeterminate));
    let (sent, removed) = mixing_records(PropertyKind::Indeterminate);
    assert_eq!(sent, [(0, PropertyValue::Bool(true))]);
    assert_eq!(removed, 1);
}

/// A Checkbox sends `OutlineColor` (property 217) only when it was given one, and a box
/// that drops it sends one removal.
#[test]
fn fr67_checkbox_sends_outline_color_only_when_given() {
    use compose_rust::schema::PropertyKind;

    assert_eq!(PropertyKind::OutlineColor as u16, 217);
    assert_eq!(PropertyKind::try_from(217), Ok(PropertyKind::OutlineColor));
    let (sent, removed) = mixing_records(PropertyKind::OutlineColor);
    assert_eq!(sent.len(), 1, "{sent:?}");
    assert_eq!(sent[0].0, 0, "{sent:?}");
    assert_eq!(removed, 1);
}

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
    Column().content(|| {
        Switch(true);
        Slider(0.5);
        Checkbox(true);
    });
}

#[Composable]
fn Styled() {
    Column().content(|| {
        Switch(true)
            .size(36.0, 20.0)
            .track_color(INK)
            .thumb_color(SURFACE);
        Slider(0.5)
            .active_track_color(INK)
            .track_color(SURFACE)
            .thumb_color(INK);
        Checkbox(true)
            .size(18.0)
            .container_color(INK)
            .check_color(SURFACE);
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
    Column().content(|| {
        let flip = styled;
        Row()
            .on_click(move || flip.update(|n| *n = !*n))
            .content(|| {
                Text("flip");
            });
        // Each widget is finished by its own statement, so they compose in source order.
        let switch = Switch(true).thumb_color(SURFACE);
        if styled.get() {
            switch.size(36.0, 20.0).track_color(INK)
        } else {
            switch
        };
        let checkbox = Checkbox(true).container_color(INK);
        if styled.get() {
            checkbox.size(18.0).check_color(SURFACE)
        } else {
            checkbox
        };
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
        .dispatch(HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        })
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
    Column().radio_group(true).content(|| {
        RadioButton(true)
            .size(18.0)
            .ring_color(INK)
            .dot_color(SURFACE);
        RadioButton(false);
    });
    Row().content(|| {
        RadioButton(false);
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

use dioxus_compose::Host;
use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{Mutation, decode_batch};

fn app() -> Element {
    rsx! {
        Column {
            padding: 24.0,
            background: Paint::Role(ColorRole::Surface),
            corner_radius: 16.0,
            elevation: 2.0,
            Text { text: "styled" }
        }
    }
}

#[test]
fn modifiers_written_in_rsx_reach_the_wire() {
    let mut host = Host::new(app);
    let mutations = decode_batch(host.rebuild().unwrap()).unwrap();
    let mods: Vec<_> = mutations
        .iter()
        .filter_map(|m| match m {
            Mutation::SetModifier { modifier, .. } => Some(format!("{modifier:?}")),
            _ => None,
        })
        .collect();
    println!("{mods:#?}");
    assert!(
        mods.iter().any(|m| m.starts_with("Padding")),
        "padding missing: {mods:?}"
    );
    assert!(
        mods.iter().any(|m| m.starts_with("Background")),
        "background missing"
    );
    assert!(mods.iter().any(|m| m.starts_with("Shape")), "shape missing");
    assert!(
        mods.iter().any(|m| m.starts_with("Elevation")),
        "elevation missing"
    );
}

/// Every widget takes the Modifier attributes, and filling the available width is one of
/// them. It used to be declared only on the layout containers, so a Card could not be made
/// to span its parent and every grouped design collapsed to the width of its own text.
fn filling_containers() -> Element {
    rsx! {
        Card {
            fill_max_width: true,
            padding_role: SpaceRole::Md,
            Surface {
                fill_max_width: true,
                fill_max_height: true,
                Text { text: "grouped" }
            }
        }
    }
}

#[test]
fn fr13_fill_max_is_available_on_every_widget() {
    let mut host = Host::new(filling_containers);
    let mutations = decode_batch(host.rebuild().unwrap()).unwrap();
    let mods: Vec<_> = mutations
        .iter()
        .filter_map(|m| match m {
            Mutation::SetModifier { modifier, .. } => Some(format!("{modifier:?}")),
            _ => None,
        })
        .collect();
    assert_eq!(
        mods.iter()
            .filter(|m| m.starts_with("FillMaxWidth"))
            .count(),
        2,
        "both the Card and the Surface should fill their width: {mods:?}"
    );
    assert!(
        mods.iter().any(|m| m.starts_with("FillMaxHeight")),
        "the Surface should fill its height: {mods:?}"
    );
}

/// A windowed list has to be able to take the space its parent column leaves it, or every
/// screen with a list in it is laid out around something that sized itself to its own
/// contents. The two Lazy widgets were the only ones declaring no Modifier props at all.
fn weighted_list() -> Element {
    rsx! {
        Column {
            fill_max_height: true,
            LazyColumn {
                weight: 1.0,
                fill_max_width: true,
                item_count: 3,
                item: move |index: usize| rsx! {
                    Text { text: "{index}" }
                },
            }
        }
    }
}

#[test]
fn fr13_a_windowed_list_takes_the_modifiers_every_widget_takes() {
    let mut host = Host::new(weighted_list);
    let mutations = decode_batch(host.rebuild().unwrap()).unwrap();
    let mods: Vec<_> = mutations
        .iter()
        .filter_map(|m| match m {
            Mutation::SetModifier { modifier, .. } => Some(format!("{modifier:?}")),
            _ => None,
        })
        .collect();
    assert!(
        mods.iter().any(|m| m.starts_with("Weight")),
        "the list should ask its column for a weight: {mods:?}"
    );
    assert!(
        mods.iter().any(|m| m.starts_with("FillMaxWidth")),
        "the list should fill its width: {mods:?}"
    );
}

fn rounded_fill() -> Element {
    rsx! {
        Column {
            background: Paint::Role(ColorRole::SurfaceVariant),
            shape_role: ShapeRole::Large,
            padding_role: SpaceRole::Md,
            Text { text: "bubble" }
        }
    }
}

/// A container that names a shape role keeps it.
///
/// `shape_role` and `corner_radius` fill the same modifier slot, and so do `padding_role`
/// and `padding`. Whichever of a pair is left unset arrives as an empty attribute, and
/// that empty attribute used to clear the slot its partner had just written, one mutation
/// later in the same frame. Every rounded container in every sample was drawn square.
#[test]
fn fr13_an_unset_attribute_does_not_clear_its_partner_in_the_same_slot() {
    let mut host = Host::new(rounded_fill);
    let mutations = decode_batch(host.rebuild().unwrap()).unwrap();
    let mut slots: Vec<(u16, String)> = Vec::new();
    for mutation in &mutations {
        if let Mutation::SetModifier {
            index, modifier, ..
        } = mutation
        {
            slots.push((*index, format!("{modifier:?}")));
        }
    }
    let shape_slot = slots
        .iter()
        .find(|(_, modifier)| modifier.starts_with("ShapeRole"))
        .map(|(index, _)| *index)
        .expect("the shape role never reached the wire");
    let last = slots
        .iter()
        .filter(|(index, _)| *index == shape_slot)
        .next_back()
        .expect("the slot has no writes");
    assert!(
        last.1.starts_with("ShapeRole"),
        "the shape slot ended the frame holding {}: {slots:?}",
        last.1
    );
    let padding_slot = slots
        .iter()
        .find(|(_, modifier)| modifier.starts_with("PaddingRole"))
        .map(|(index, _)| *index)
        .expect("the padding role never reached the wire");
    let last = slots
        .iter()
        .filter(|(index, _)| *index == padding_slot)
        .next_back()
        .expect("the slot has no writes");
    assert!(
        last.1.starts_with("PaddingRole"),
        "the padding slot ended the frame holding {}: {slots:?}",
        last.1
    );
}

/// Every widget, each one given a size and a fill.
///
/// This is here because half of them could not be. `Canvas` documented that its size came
/// from the Modifier chain and then declared no Modifier props at all, so a chart could
/// only ever be as large as the box someone wrapped it in. A `Slider` and a `Divider`
/// could not fill the row they were in, which is the only width either of them is ever
/// wanted at. An `Image` had no size, so a photograph was whatever size the Renderer
/// guessed. The attributes existed on the elements the whole time; the components that
/// wrap them had hand written prop lists, and a prop list is the sort of thing that gets
/// written once and then not extended.
///
/// It is written as one screen holding all of them, because the failure is a compile
/// error rather than a wrong value: a widget that does not take the attribute cannot be
/// written down here at all.
fn every_widget_sized() -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,
            Canvas {
                fill_max_width: true,
                height: 40.0,
                commands: DrawList::builder()
                    .circle(Paint::Role(ColorRole::Primary), 10.0, 10.0, 8.0, 0.0)
                    .build(),
            }
            Image { fill_max_width: true, height: 60.0, asset_id: 1 }
            Icon { width: 24.0, height: 24.0, asset_id: 2 }
            Checkbox { fill_max_width: true, checked: true }
            RadioButton { fill_max_width: true, selected: true }
            Switch { fill_max_width: true, checked: false }
            Slider { fill_max_width: true, value: 0.5 }
            ProgressIndicator { fill_max_width: true, value: 0.25 }
            Divider { fill_max_width: true }
            DatePicker { fill_max_width: true, value: 20_000 }
            TimePicker { fill_max_width: true, value: 600 }
            Dropdown {
                fill_max_width: true,
                selected_index: 0,
                Text { text: "one" }
            }
            Navigation {
                fill_max_width: true,
                NavigationItem { fill_max_width: true, text: "Home" }
                Text { text: "page" }
            }
        }
    }
}

#[test]
fn fr13_every_widget_takes_the_modifier_chain() {
    let mut host = Host::new(every_widget_sized);
    let mutations = decode_batch(host.rebuild().unwrap()).unwrap();
    let filled: Vec<u32> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                node_id,
                modifier: dioxus_compose::Modifier::FillMaxWidth,
                ..
            } => Some(*node_id),
            _ => None,
        })
        .collect();
    // The column, the twelve children that asked to fill their width, and the one
    // destination inside the navigation.
    assert_eq!(
        filled.len(),
        14,
        "{} widgets filled their width rather than 14: {filled:?}",
        filled.len()
    );

    let sized: Vec<u32> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                node_id,
                modifier: dioxus_compose::Modifier::Height(_),
                ..
            } => Some(*node_id),
            _ => None,
        })
        .collect();
    assert_eq!(
        sized.len(),
        3,
        "the canvas, the image and the icon should each carry a height: {sized:?}"
    );
}

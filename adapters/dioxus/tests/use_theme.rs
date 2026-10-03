//! Changing the theme while the application runs: one record, and a palette made at run
//! time owned by the crate rather than leaked by the application.

use dioxus_compose_adapter::prelude::*;
use dioxus_compose_adapter::protocol::{
    HostEvent, Mutation, PropertyValue, decode_batch, encode_event,
};
use dioxus_compose_adapter::theme::{current_theme, owned_palette_count};
use dioxus_compose_adapter::{EventPayload, Host, PropertyKind, WidgetKind};

fn brand(argb: u32) -> Palette {
    Palette::new().with(ColorRole::Primary, Color::rgb(argb), Color::rgb(argb))
}

fn app() -> Element {
    let theme = use_theme();
    rsx! {
        Column {
            Button { text: "brand", on_click: move |_| theme.set_palette(brand(0xE8590C)) }
            Button { text: "plain", on_click: move |_| theme.clear_palette() }
            Button { text: "dark", on_click: move |_| theme.set_color_scheme(ColorScheme::Dark) }
        }
    }
}

/// The three buttons' node ids and click handlers, in order.
fn buttons(batch: &[u8]) -> Vec<(u32, u64)> {
    let mutations = decode_batch(batch).unwrap();
    let created: Vec<u32> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Button,
            } => Some(*node_id),
            _ => None,
        })
        .collect();
    created
        .into_iter()
        .map(|node| {
            let handler = mutations
                .iter()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::OnClick,
                        value: PropertyValue::Integer(handler),
                    } if *node_id == node => Some(*handler as u64),
                    _ => None,
                })
                .expect("a button with no click handler");
            (node, handler)
        })
        .collect()
}

fn click(host: &mut Host, (node, handler): (u32, u64)) -> Vec<Mutation<'static>> {
    let mut wire = Vec::new();
    encode_event(
        &HostEvent {
            node_id: node,
            handler_id: handler,
            payload: EventPayload::Clicked,
        },
        &mut wire,
    )
    .unwrap();
    let (batch, _) = host.dispatch_event(&wire).unwrap();
    let mut out = Vec::new();
    for mutation in decode_batch(batch).unwrap() {
        out.push(match mutation {
            Mutation::SetTheme(theme) => Mutation::SetTheme(theme),
            Mutation::Remove { node_id } => Mutation::Remove { node_id },
            Mutation::Create { node_id, widget } => Mutation::Create { node_id, widget },
            // Only the shape of the batch is compared below, so anything else stands in
            // as a removal of node zero, which no real record is.
            _ => Mutation::Remove { node_id: 0 },
        });
    }
    out
}

/// A palette set from a handler is one SetTheme carrying it, in the batch that handler
/// produced, and nothing else.
#[test]
fn fr14_10_a_palette_set_at_run_time_is_one_theme_record() {
    dioxus_compose_adapter::window::reset_window_size();
    let mut host = Host::with_theme(app, Theme::unified(DesignSystem::Fluent));
    let first = host.rebuild().unwrap().to_vec();
    let [brand_button, plain_button, dark_button] = buttons(&first)[..] else {
        panic!("three buttons were expected");
    };

    let batch = click(&mut host, brand_button);
    assert_eq!(batch.len(), 1, "{batch:?}");
    let Mutation::SetTheme(theme) = batch[0] else {
        panic!("not a theme: {batch:?}");
    };
    assert_eq!(theme.design_system, DesignSystem::Fluent);
    assert_eq!(theme.palette, Some(&brand(0xE8590C)));
    assert_eq!(current_theme().palette, Some(&brand(0xE8590C)));

    let cleared = click(&mut host, plain_button);
    let [Mutation::SetTheme(theme)] = cleared[..] else {
        panic!("not one theme: {cleared:?}");
    };
    assert_eq!(theme.palette, None);

    let darker = click(&mut host, dark_button);
    let [Mutation::SetTheme(theme)] = darker[..] else {
        panic!("not one theme: {darker:?}");
    };
    assert_eq!(theme.color_scheme, ColorScheme::Dark);
    assert_eq!(theme.design_system, DesignSystem::Fluent);
}

/// Setting the same palette again does not hold another copy of it.
#[test]
fn fr14_10_a_run_time_palette_is_owned_once() {
    dioxus_compose_adapter::window::reset_window_size();
    let mut host = Host::with_theme(app, Theme::unified(DesignSystem::Gnome));
    let first = host.rebuild().unwrap().to_vec();
    let brand_button = buttons(&first)[0];
    click(&mut host, brand_button);
    let held = owned_palette_count();
    for _ in 0..5 {
        click(&mut host, brand_button);
    }
    assert_eq!(owned_palette_count(), held);
}

/// The Host keeps the changed theme, so building the tree again sends it rather than the
/// one the application launched with.
#[test]
fn fr14_10_a_run_time_theme_survives_a_resync() {
    dioxus_compose_adapter::window::reset_window_size();
    let mut host = Host::with_theme(app, Theme::unified(DesignSystem::Breeze));
    let first = host.rebuild().unwrap().to_vec();
    click(&mut host, buttons(&first)[0]);
    let (batch, _) = host.resync().unwrap();
    let theme = decode_batch(batch)
        .unwrap()
        .into_iter()
        .find_map(|mutation| match mutation {
            Mutation::SetTheme(theme) => Some(theme),
            _ => None,
        })
        .unwrap();
    assert_eq!(theme.palette, Some(&brand(0xE8590C)));
}

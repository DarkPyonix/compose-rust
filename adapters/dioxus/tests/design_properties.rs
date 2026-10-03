//! What the Renderer receives for the design properties a screen declares: observed
//! sizes, runs inside a string, drop targets, motion and material roles, gradients.
//!
//! These sat in the core crate's renderer resolution tests, beside the build script checks.
//! They are driven by a Dioxus screen, so they live with the Dioxus adapter now.

/// Nothing is measured unless a screen asked.
///
/// The first thing this requirement promises: a tree that observes nothing costs exactly
/// what it cost before observing existed. The modifier is the only thing that makes the
/// Renderer measure, so a batch that carries none of them is the proof.
#[test]
fn fr28_a_tree_that_observes_nothing_sends_no_observation() {
    use dioxus_compose_adapter::prelude::*;
    use dioxus_compose_adapter::protocol::{Mutation, decode_batch};
    use dioxus_compose_adapter::schema::Modifier;

    fn quiet() -> Element {
        rsx! { Column { Text { text: "nothing is watching this" } } }
    }

    dioxus_compose_adapter::window::reset_window_size();
    let mut host = dioxus_compose_adapter::Host::new(quiet);
    let batch = host.rebuild().expect("the first frame failed to encode");
    let mutations = decode_batch(batch).expect("decode");
    assert!(
        !mutations.iter().any(|mutation| matches!(
            mutation,
            Mutation::SetModifier {
                modifier: Modifier::ObserveSize { .. },
                ..
            }
        )),
        "a tree nobody is observing asked the Renderer to measure something",
    );
}

/// A node that asked carries the token its screen gave it.
#[test]
fn fr28_an_observed_node_carries_its_own_token() {
    use dioxus_compose_adapter::prelude::*;
    use dioxus_compose_adapter::protocol::{Mutation, decode_batch};
    use dioxus_compose_adapter::schema::Modifier;

    fn watched() -> Element {
        let panel = use_node_size();
        rsx! {
            Column {
                observe_size: panel.token(),
                Text { text: "this one is watched" }
            }
        }
    }

    dioxus_compose_adapter::window::reset_window_size();
    let mut host = dioxus_compose_adapter::Host::new(watched);
    let batch = host.rebuild().expect("the first frame failed to encode");
    let mutations = decode_batch(batch).expect("decode");
    let observed: Vec<_> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                modifier: Modifier::ObserveSize { token },
                ..
            } => Some(*token),
            _ => None,
        })
        .collect();
    assert_eq!(observed.len(), 1, "one node asked, so one modifier travels");
    assert_ne!(observed[0], 0, "a token of zero is the window, not a node");
}

/// A Text that says nothing about runs travels as it always did.
///
/// The requirement is explicit that the record and the path are unchanged where there
/// are no runs, because a feature nobody used must not cost every string in every screen
/// one record per node.
#[test]
fn fr26_a_text_without_runs_carries_no_run_record() {
    use dioxus_compose_adapter::prelude::*;
    use dioxus_compose_adapter::protocol::{Mutation, decode_batch};
    use dioxus_compose_adapter::schema::PropertyKind;

    fn plain() -> Element {
        rsx! { Text { text: "nothing special about this" } }
    }

    dioxus_compose_adapter::window::reset_window_size();
    let mut host = dioxus_compose_adapter::Host::new(plain);
    let batch = host.rebuild().expect("the first frame failed to encode");
    assert!(
        !decode_batch(batch)
            .expect("decode")
            .iter()
            .any(|mutation| matches!(
                mutation,
                Mutation::SetProp {
                    property: PropertyKind::Spans,
                    ..
                }
            )),
        "a string with no runs paid a record for saying so",
    );
}

/// Runs survive the wire exactly as they were written.
#[test]
fn fr26_runs_round_trip_through_the_boundary() {
    use dioxus_compose_adapter::prelude::*;
    use dioxus_compose_adapter::protocol::{Mutation, PropertyValue, decode_batch};
    use dioxus_compose_adapter::schema::PropertyKind;
    use dioxus_compose_adapter::spans::{TextSpan, TextSpans};

    fn marked() -> Element {
        let spans = TextSpans::new([
            TextSpan::new(0, 5).bold(),
            TextSpan::new(6, 4)
                .underline()
                .with_color(Paint::Role(ColorRole::Primary)),
        ]);
        rsx! { Text { text: "Plain link here", spans } }
    }

    dioxus_compose_adapter::window::reset_window_size();
    let mut host = dioxus_compose_adapter::Host::new(marked);
    let batch = host.rebuild().expect("the first frame failed to encode");
    let bytes = decode_batch(batch)
        .expect("decode")
        .into_iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                property: PropertyKind::Spans,
                value: PropertyValue::Bytes(bytes),
                ..
            } => Some(bytes.to_vec()),
            _ => None,
        })
        .expect("the runs did not travel");

    let decoded: Vec<_> = TextSpans::from_bytes(bytes).spans().collect();
    assert_eq!(decoded.len(), 2);
    assert_eq!((decoded[0].start, decoded[0].length), (0, 5));
    assert!(decoded[0].bold && !decoded[0].underline);
    assert!(decoded[1].underline && !decoded[1].bold);
    assert_eq!(decoded[1].color, Some(Paint::Role(ColorRole::Primary)));
}

/// A node that said nothing about files is not a place files may be dropped.
///
/// Said as "pays nothing" rather than "sends false", because a container that is silent
/// about files is the common case: every Box, Column, Card and Surface in every screen.
#[test]
fn fr27_a_node_that_did_not_ask_is_not_a_drop_target() {
    use dioxus_compose_adapter::prelude::*;
    use dioxus_compose_adapter::protocol::{Mutation, decode_batch};
    use dioxus_compose_adapter::schema::PropertyKind;

    fn plain() -> Element {
        rsx! { dioxus_compose_adapter::Box { Text { text: "not a target" } } }
    }

    dioxus_compose_adapter::window::reset_window_size();
    let mut host = dioxus_compose_adapter::Host::new(plain);
    let batch = host.rebuild().expect("the first frame failed to encode");
    let said = decode_batch(batch)
        .expect("decode")
        .into_iter()
        .filter(|mutation| {
            matches!(
                mutation,
                Mutation::SetProp {
                    property: PropertyKind::OnFilesEntered | PropertyKind::OnFilesDropped,
                    ..
                }
            )
        })
        .count();
    assert_eq!(
        said, 0,
        "a node that said nothing was offered as a drop target"
    );
}

/// The widget that exists to receive files is the one that carries the handlers.
#[test]
fn fr27_a_drop_target_carries_both_handlers() {
    use dioxus_compose_adapter::prelude::*;
    use dioxus_compose_adapter::protocol::{Mutation, decode_batch};
    use dioxus_compose_adapter::schema::{PropertyKind, WidgetKind};

    fn target() -> Element {
        rsx! {
            FileDropTarget {
                on_files_entered: move |_| {},
                on_files_dropped: move |_| {},
                Text { text: "drop files here" }
            }
        }
    }

    dioxus_compose_adapter::window::reset_window_size();
    let mut host = dioxus_compose_adapter::Host::new(target);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");
    assert!(mutations.iter().any(|mutation| matches!(
        mutation,
        Mutation::Create {
            widget: WidgetKind::FileDropTarget,
            ..
        }
    )));
    for wanted in [PropertyKind::OnFilesEntered, PropertyKind::OnFilesDropped] {
        assert!(
            mutations.iter().any(|mutation| matches!(
                mutation,
                Mutation::SetProp { property, .. } if *property == wanted
            )),
            "{wanted:?} did not reach the Renderer",
        );
    }
}

/// A node says how important its changes are, and nothing about how long they take.
#[test]
fn fr24_a_motion_role_reaches_the_renderer_as_a_role() {
    use dioxus_compose_adapter::prelude::*;
    use dioxus_compose_adapter::protocol::{Mutation, decode_batch};

    fn moving() -> Element {
        rsx! {
            Card { motion: MotionRole::Emphasized, Text { text: "opens" } }
        }
    }

    dioxus_compose_adapter::window::reset_window_size();
    let mut host = dioxus_compose_adapter::Host::new(moving);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");
    assert!(
        mutations.iter().any(|mutation| matches!(
            mutation,
            Mutation::SetModifier {
                modifier: Modifier::Motion(MotionRole::Emphasized),
                ..
            }
        )),
        "the motion role did not reach the Renderer: {mutations:?}",
    );
}

/// A node that said nothing about motion pays nothing.
#[test]
fn fr24_silence_about_motion_costs_no_record() {
    use dioxus_compose_adapter::prelude::*;
    use dioxus_compose_adapter::protocol::{Mutation, decode_batch};

    fn still() -> Element {
        rsx! { Card { Text { text: "still" } } }
    }

    dioxus_compose_adapter::window::reset_window_size();
    let mut host = dioxus_compose_adapter::Host::new(still);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");
    assert!(!mutations.iter().any(|mutation| matches!(
        mutation,
        Mutation::SetModifier {
            modifier: Modifier::Motion(_),
            ..
        }
    )));
}

/// The five roles survive the round trip in the order the wire fixes them in.
#[test]
fn fr24_every_motion_role_survives_the_wire() {
    use dioxus_compose_adapter::prelude::*;
    use dioxus_compose_adapter::protocol::{Mutation, decode_batch};

    const ROLES: [MotionRole; 5] = [
        MotionRole::Instant,
        MotionRole::Quick,
        MotionRole::Standard,
        MotionRole::Slow,
        MotionRole::Emphasized,
    ];

    fn all_five() -> Element {
        rsx! {
            Column {
                for role in ROLES {
                    Card { motion: role, Text { text: "{role:?}" } }
                }
            }
        }
    }

    dioxus_compose_adapter::window::reset_window_size();
    let mut host = dioxus_compose_adapter::Host::new(all_five);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");
    let arrived: Vec<MotionRole> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                modifier: Modifier::Motion(role),
                ..
            } => Some(*role),
            _ => None,
        })
        .collect();
    assert_eq!(arrived, ROLES);
}

/// A surface says what it is made of, and nothing about blur.
#[test]
fn fr23_a_material_role_reaches_the_renderer_as_a_role() {
    use dioxus_compose_adapter::prelude::*;
    use dioxus_compose_adapter::protocol::{Mutation, decode_batch};

    fn sheet() -> Element {
        rsx! {
            Surface { material: MaterialRole::Regular, Text { text: "over the page" } }
        }
    }

    dioxus_compose_adapter::window::reset_window_size();
    let mut host = dioxus_compose_adapter::Host::new(sheet);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");
    assert!(
        mutations.iter().any(|mutation| matches!(
            mutation,
            Mutation::SetModifier {
                modifier: Modifier::Material(MaterialRole::Regular),
                ..
            }
        )),
        "the material role did not reach the Renderer: {mutations:?}",
    );
}

/// The four roles survive the round trip in the order the wire fixes them in.
#[test]
fn fr23_every_material_role_survives_the_wire() {
    use dioxus_compose_adapter::prelude::*;
    use dioxus_compose_adapter::protocol::{Mutation, decode_batch};

    const ROLES: [MaterialRole; 4] = [
        MaterialRole::Thin,
        MaterialRole::Regular,
        MaterialRole::Thick,
        MaterialRole::Chrome,
    ];

    fn all_four() -> Element {
        rsx! {
            Column {
                for role in ROLES {
                    Surface { material: role, Text { text: "{role:?}" } }
                }
            }
        }
    }

    dioxus_compose_adapter::window::reset_window_size();
    let mut host = dioxus_compose_adapter::Host::new(all_four);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");
    let arrived: Vec<MaterialRole> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetModifier {
                modifier: Modifier::Material(role),
                ..
            } => Some(*role),
            _ => None,
        })
        .collect();
    assert_eq!(arrived, ROLES);
}

/// A node that said nothing about material pays nothing.
#[test]
fn fr23_silence_about_material_costs_no_record() {
    use dioxus_compose_adapter::prelude::*;
    use dioxus_compose_adapter::protocol::{Mutation, decode_batch};

    fn plain() -> Element {
        rsx! { Surface { Text { text: "flat" } } }
    }

    dioxus_compose_adapter::window::reset_window_size();
    let mut host = dioxus_compose_adapter::Host::new(plain);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");
    assert!(!mutations.iter().any(|mutation| matches!(
        mutation,
        Mutation::SetModifier {
            modifier: Modifier::Material(_),
            ..
        }
    )));
}

/// A gradient reaches the Renderer as a registration and an id, not as a list of stops.
#[test]
fn fr23_a_gradient_is_registered_once_and_named_by_id() {
    use dioxus_compose_adapter::prelude::*;
    use dioxus_compose_adapter::protocol::{Mutation, decode_batch};
    use dioxus_compose_adapter::schema::{AssetKind, Color, Paint};

    fn sky() -> Element {
        let paint = brush(Brush::vertical(vec![
            Stop::new(0.0, Color::rgb(0x4a90d9)),
            Stop::new(0.5, Color::rgb(0x9ec9f0)),
            Stop::new(1.0, Color::rgb(0xffffff)),
        ]));
        rsx! { Surface { background: paint, Text { text: "over a gradient" } } }
    }

    dioxus_compose_adapter::window::reset_window_size();
    let mut host = dioxus_compose_adapter::Host::new(sky);
    let mutations = decode_batch(host.rebuild().expect("encode")).expect("decode");

    let registration = mutations
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::RegisterAsset {
                asset_id,
                kind: AssetKind::Brush,
                bytes,
            } => Some((*asset_id, *bytes)),
            _ => None,
        })
        .expect("the brush was never registered");
    // Header, then one record per stop.
    assert_eq!(
        registration.1.len(),
        dioxus_compose_adapter::brush::HEADER_LEN + 3 * dioxus_compose_adapter::brush::STOP_LEN,
    );

    let named = mutations.iter().any(|mutation| {
        matches!(
            mutation,
            Mutation::SetModifier { modifier: Modifier::Background(Paint::Asset(id)), .. }
                if *id == registration.0
        )
    });
    assert!(
        named,
        "the surface did not name the brush it registered: {mutations:?}"
    );
}
